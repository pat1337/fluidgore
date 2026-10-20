package dev.fluidgore.tank;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.net.TankBurstPayload;
import dev.fluidgore.trigger.PressureVessel;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * A tank bursting, for real (server side). The gas's energy makes a blast wave graded by {@link Blast}: every body is
 * hurt by the peak overpressure where it stood, less what cover took out of it (a vanilla explosion that breaks no blocks
 * carries it, so knockback, death messages and the client's blast gore come with it); the casing flies apart in some
 * hundreds of fragments that hit bodies out to a few hundred metres, more often the closer; windows break and doors
 * are blown in. No crater, no fire: it is cold gas, not an explosive.
 */
public final class TankBurst {
	/** How far fragments are followed, metres. */
	private static final double FRAGMENT_RANGE = 300.0;
	/** The server tells players this far away about a burst (the vanilla explosion packet only reaches 64). */
	private static final double TELL_RANGE = 320.0;
	/** Below this much TNT, a tank just pops. */
	private static final double POP_KG = 0.02;

	private record Pending(ServerLevel level, Vec3 centre, TankState tank, @Nullable Entity cause, long at) {
	}

	private static final List<Pending> PENDING = new ArrayList<>();

	private TankBurst() {
	}

	/** Bursts a tank {@code delay} ticks from now (one hit by an explosion goes off a moment after it, like TNT). */
	public static void later(ServerLevel level, Vec3 centre, TankState tank, @Nullable Entity cause, int delay) {
		PENDING.add(new Pending(level, centre, tank.copy(), cause, level.getGameTime() + delay));
	}

	static void tick(ServerLevel level) {
		if (PENDING.isEmpty()) {
			return;
		}

		List<Pending> due = new ArrayList<>();
		PENDING.removeIf(p -> {
			if (p.level == level && level.getGameTime() >= p.at) {
				due.add(p);
				return true;
			}

			return p.level.getServer() != level.getServer();
		});
		for (Pending p : due) {
			burst(level, p.centre, p.tank, p.cause);
		}
	}

	static void clear() {
		PENDING.clear();
	}

	/** The tank at {@code centre} bursts now (whatever stood there, block or flying tank, is already gone). */
	public static void burst(ServerLevel level, Vec3 centre, TankState tank, @Nullable Entity cause) {
		float psi = tank.psiNow();
		double tntKg = Blast.tntKg(tank.litres, psi);
		RandomSource random = level.getRandom();
		if (tntKg < POP_KG) {
			// Next to nothing left in it: the casing splits with a bang and a puff.
			level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.ANVIL_BREAK, SoundSource.BLOCKS, 1.0F, 0.6F);
			level.sendParticles(ParticleTypes.POOF, centre.x, centre.y, centre.z, 20, 0.3, 0.3, 0.3, 0.1);
			return;
		}

		// The blast is carried by a vanilla explosion out to where eardrums burst; beyond that nothing is hurt by it.
		float radius = (float) Math.max(1.0, Blast.reach(tntKg, psi, Blast.EARDRUM_KPA) / 2.0);
		Int2IntOpenHashMap hits = fragments(level, centre, tank.litres, radius, cause, random);
		int[] ids = hits.keySet().toIntArray();
		int[] counts = new int[ids.length];
		for (int i = 0; i < ids.length; i++) {
			counts[i] = hits.get(ids[i]);
		}

		// The gore first: the explosion packet that follows is matched to it on every client.
		TankBurstPayload payload = new TankBurstPayload(centre.x, centre.y, centre.z, tank.litres, psi, radius, ids, counts);
		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(centre) < TELL_RANGE * TELL_RANGE && ServerPlayNetworking.canSend(player, TankBurstPayload.TYPE)) {
				ServerPlayNetworking.send(player, payload);
			}
		}

		DamageSource source = level.damageSources().explosion(null, cause);
		level.explode(null, source, new Calculator(centre, tntKg, psi, hits), centre.x, centre.y, centre.z, radius, false, Level.ExplosionInteraction.NONE,
				ParticleTypes.POOF, ParticleTypes.POOF, WeightedList.of(), SoundEvents.GENERIC_EXPLODE);
		if (GoreConfig.get().tankBreaksGlass) {
			shatter(level, centre, tntKg, psi);
		}

		chain(level, centre, tntKg, psi, cause);
	}

	/** Other tanks near enough to take a lethal blast go too, a moment later, as TNT does. */
	private static void chain(ServerLevel level, Vec3 centre, double tntKg, double psi, @Nullable Entity cause) {
		int r = (int) Math.ceil(Math.min(16.0, Blast.reach(tntKg, psi, Blast.LETHAL_KPA)));
		BlockPos c = BlockPos.containing(centre);
		for (BlockPos pos : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
			if (pos.distToCenterSqr(centre) <= r * r && level.getBlockState(pos).is(Tanks.BLOCK) && level.getBlockEntity(pos) instanceof TankBlockEntity other) {
				TankState copy = other.state().copy();
				level.removeBlock(pos, false);
				later(level, Vec3.atCenterOf(pos), copy, cause, 2 + level.getRandom().nextInt(6));
			}
		}
	}

	/**
	 * The fragments: the casing flies apart in {@link PressureVessel#FRAGMENTS_PER_LITRE} per litre, thrown evenly over
	 * a hemisphere, so a body is hit by its area over the square of its distance (Poisson). Bodies inside the explosion
	 * take theirs with the blast; those beyond it are hurt here.
	 */
	private static Int2IntOpenHashMap fragments(ServerLevel level, Vec3 centre, float litres, float radius, @Nullable Entity cause, RandomSource random) {
		Int2IntOpenHashMap hits = new Int2IntOpenHashMap();
		AABB range = new AABB(centre, centre).inflate(FRAGMENT_RANGE);
		for (LivingEntity body : level.getEntitiesOfClass(LivingEntity.class, range, e -> e.isAlive() && !e.isSpectator())) {
			Vec3 mid = body.getBoundingBox().getCenter();
			double r = Math.max(1.0, mid.distanceTo(centre));
			if (r > FRAGMENT_RANGE) {
				continue;
			}

			double area = body.getBbWidth() * body.getBbHeight();
			double mean = PressureVessel.FRAGMENTS_PER_LITRE * litres * area / (2.0 * Math.PI * r * r) * ServerExplosion.getSeenPercent(centre, body);
			int n = poisson(Math.min(mean, 20.0), random);
			if (n == 0 || hits.size() >= TankBurstPayload.MAX_BODIES) {
				continue;
			}

			hits.put(body.getId(), n);
			if (body.distanceToSqr(centre) > 4.0 * radius * radius) {
				body.hurtServer(level, level.damageSources().explosion(null, cause), n * fragmentDamage(r));
			}
		}

		return hits;
	}

	/** Damage of one fragment {@code r} metres out: a few hearts close by, still a heart at the edge of its range. */
	static float fragmentDamage(double r) {
		return (float) (2.0 + 6.0 * Math.exp(-r / 40.0));
	}

	/** Knuth's method: count draws until their product falls below e^-mean (at most 6 hits). */
	private static int poisson(double mean, RandomSource random) {
		double limit = Math.exp(-mean);
		double product = random.nextDouble();
		int hits = 0;
		while (product > limit && hits < 6) {
			hits++;
			product *= random.nextDouble();
		}

		return hits;
	}

	/** The real damage: the peak overpressure on each body, by {@link Blast#damage}, plus its fragments; knockback by pressure. */
	private static final class Calculator extends ExplosionDamageCalculator {
		private final double tntKg;
		private final double psi;
		private final Int2IntOpenHashMap hits;
		private final Vec3 centre;

		Calculator(Vec3 centre, double tntKg, double psi, Int2IntOpenHashMap hits) {
			this.centre = centre;
			this.tntKg = tntKg;
			this.psi = psi;
			this.hits = hits;
		}

		private float kPa(Explosion explosion, Entity entity, float exposure) {
			double r = entity.getBoundingBox().getCenter().distanceTo(explosion.center());
			return Blast.covered(Blast.overpressure(r, tntKg, psi), exposure);
		}

		@Override
		public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
			return entity instanceof LivingEntity || entity instanceof TankEntity;
		}

		@Override
		public float getEntityDamageAmount(Explosion explosion, Entity entity, float exposure) {
			if (!(entity instanceof LivingEntity body)) {
				return 1.0F;
			}

			float kPa = kPa(explosion, entity, exposure);
			double r = entity.getBoundingBox().getCenter().distanceTo(explosion.center());
			return Blast.damage(kPa, body.getMaxHealth()) + hits.get(entity.getId()) * fragmentDamage(r);
		}

		@Override
		public float getKnockbackMultiplier(Entity entity) {
			// Thrown by the blast wind: hard close in, a stagger at the edge.
			double r = entity.getBoundingBox().getCenter().distanceTo(centre);
			return Math.clamp(Blast.overpressure(r, tntKg, psi) / 40.0F, 0.3F, 4.0F);
		}
	}

	/**
	 * Glass breaks where the overpressure is {@link Blast#GLASS_KPA} or more, wooden doors, trapdoors and gates are blown
	 * open from {@link Blast#DOOR_KPA}. Behind cover the wave is weaker. Only loaded chunks, and only sections that can
	 * hold glass or doors, are searched.
	 */
	private static void shatter(ServerLevel level, Vec3 centre, double tntKg, double psi) {
		double reach = Blast.reach(tntKg, psi, Blast.GLASS_KPA);
		if (reach < 1.0) {
			return;
		}

		int minX = SectionPos.blockToSectionCoord(centre.x - reach);
		int maxX = SectionPos.blockToSectionCoord(centre.x + reach);
		int minZ = SectionPos.blockToSectionCoord(centre.z - reach);
		int maxZ = SectionPos.blockToSectionCoord(centre.z + reach);
		int minY = (int) Math.floor(centre.y - reach);
		int maxY = (int) Math.ceil(centre.y + reach);
		List<BlockPos> glass = new ArrayList<>();
		List<BlockPos> doors = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int cx = minX; cx <= maxX; cx++) {
			for (int cz = minZ; cz <= maxZ; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}

				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection section = sections[i];
					int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
					if (section.hasOnlyAir() || baseY + 15 < minY || baseY > maxY || !section.maybeHas(TankBurst::breakable)) {
						continue;
					}

					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								BlockState state = section.getBlockState(x, y, z);
								if (!breakable(state)) {
									continue;
								}

								pos.set(SectionPos.sectionToBlockCoord(cx) + x, baseY + y, SectionPos.sectionToBlockCoord(cz) + z);
								double r = Vec3.atCenterOf(pos).distanceTo(centre);
								if (r > reach) {
									continue;
								}

								(glass(state) ? glass : doors).add(pos.immutable());
							}
						}
					}
				}
			}
		}

		for (BlockPos at : glass) {
			if (pressureAt(level, centre, at, tntKg, psi) >= Blast.GLASS_KPA) {
				level.destroyBlock(at, false);
			}
		}

		for (BlockPos at : doors) {
			BlockState state = level.getBlockState(at);
			if (!breakable(state) || pressureAt(level, centre, at, tntKg, psi) < Blast.DOOR_KPA) {
				continue;
			}

			if (state.getBlock() instanceof DoorBlock door) {
				door.setOpen(null, level, state, at, true);
			} else if (state.hasProperty(BlockStateProperties.OPEN) && !state.getValue(BlockStateProperties.OPEN)) {
				level.setBlock(at, state.setValue(BlockStateProperties.OPEN, true), 10);
				level.playSound(null, at, SoundEvents.WOODEN_TRAPDOOR_OPEN, SoundSource.BLOCKS, 1.0F, 0.8F);
			}
		}
	}

	private static boolean glass(BlockState state) {
		return state.is(ConventionalBlockTags.GLASS_BLOCKS) || state.is(ConventionalBlockTags.GLASS_PANES);
	}

	private static boolean breakable(BlockState state) {
		return glass(state) || state.is(BlockTags.WOODEN_DOORS) || state.is(BlockTags.WOODEN_TRAPDOORS) || state.is(BlockTags.FENCE_GATES);
	}

	/** The overpressure on the block at {@code at}: open air if nothing solid stands between it and the burst. */
	private static float pressureAt(ServerLevel level, Vec3 centre, BlockPos at, double tntKg, double psi) {
		Vec3 target = Vec3.atCenterOf(at);
		float kPa = Blast.overpressure(target.distanceTo(centre), tntKg, psi);
		BlockHitResult hit = level.clip(new ClipContext(centre, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
		boolean open = hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(at) || breakable(level.getBlockState(hit.getBlockPos()));
		return Blast.covered(kPa, open ? 1.0F : 0.0F);
	}
}
