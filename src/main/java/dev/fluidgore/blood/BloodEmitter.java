package dev.fluidgore.blood;

import dev.fluidgore.config.BloodColor;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import dev.fluidgore.trigger.EntityFilter;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Turns damage into blood. Every wound is rolled, not computed: a log-normal "severity" makes most hits ordinary
 * and some gush, and independent chances add flying chunks, an arterial spurt and a wet crunch. The look leans on
 * vanilla's own pieces, the way Minecraft itself shows breaking things: redstone-dust particles in the blood
 * colour, crumbs of a matching block (redstone block for red blood) and bits of the animal's meat item.
 */
public final class BloodEmitter {
	private static final RandomSource RANDOM = RandomSource.create();
	private static final SoundEvent[] CRUNCH = {
			SoundEvents.MUD_BREAK, SoundEvents.SLIME_BLOCK_BREAK, SoundEvents.HONEY_BLOCK_BREAK, SoundEvents.WART_BLOCK_BREAK
	};

	private BloodEmitter() {
	}

	/**
	 * @param damage health lost (2 = one heart)
	 * @param lethal true for the killing blow: much larger burst and a pool under the body
	 * @param away   unit direction pointing away from the attacker, or null if unknown
	 */
	public static void bleed(BloodWorld world, ClientLevel level, LivingEntity entity, float damage, boolean lethal, @Nullable Vec3 away) {
		GoreConfig config = GoreConfig.get();
		GoreLevel preset = config.goreLevel;
		if (!preset.enabled()) {
			return;
		}

		if (EntityFilter.isBone(entity)) {
			BoneEmitter.hit(level, entity, damage, lethal, away);
			return;
		}

		damage = Mth.clamp(damage, 0.5F, 40.0F);
		BloodColor type = EntityFilter.bloodType(entity);
		int rgb = type.rgb;
		float bodySize = Mth.clamp(entity.getBbWidth() * entity.getBbHeight() / (0.6F * 1.8F), 0.3F, 3.0F);
		// How bad this particular wound is: most near 1, some barely bleed, some gush.
		float severity = Mth.clamp(world.lognormal(0.5F), 0.3F, 4.0F);
		float volume = Math.min(3.0F, (0.03F + damage * 0.03F) * preset.puddles * bodySize * (lethal ? 3.0F : 1.0F) * severity);
		int count = Math.round((3.0F + damage * 2.5F) * preset.particles * (lethal ? 2.2F : 1.0F) * severity);

		// The wound is a random point on the upper body, not always the exact centre.
		double ox = (RANDOM.nextDouble() - 0.5) * entity.getBbWidth() * 0.6;
		double oy = entity.getBbHeight() * (0.4 + RANDOM.nextDouble() * 0.45);
		double oz = (RANDOM.nextDouble() - 0.5) * entity.getBbWidth() * 0.6;
		Vec3 origin = new Vec3(entity.getX() + ox, entity.getY() + oy, entity.getZ() + oz);

		spray(world, origin, away, count, volume * 0.7F, rgb, entity.getBbWidth() * 0.4, 0.12, 0.32 + 0.1 * (severity - 1.0));
		// Only a light mist at the wound; the blood itself is the falling droplets and the trickle below.
		redstoneDust(world, level, origin, rgb, Math.min(12, Math.round(count * 0.25F)), entity.getBbWidth() * 0.3);

		int trickle = Math.round((50 + RANDOM.nextInt(60)) * preset.duration * Math.min(2.0F, severity));
		double width = entity.getBbWidth() * 0.25;
		world.spurts().ooze(() -> new Vec3(entity.getX() + ox, entity.getY() + oy, entity.getZ() + oz),
				() -> new Vec3(entity.getX() + ox, entity.getY() + 0.1, entity.getZ() + oz), width, rgb, trickle,
				Mth.clamp(0.5F + damage * 0.1F, 0.5F, 1.5F));

		if (lethal) {
			double surface = SurfaceProbe.surfaceBelow(level, entity.getX(), entity.getY() + 0.3, entity.getY() - 1.5, entity.getZ());
			if (!Double.isNaN(surface)) {
				world.landOnFloor(level, entity.getX(), surface, entity.getZ(), volume * 0.3F, rgb, world.decalLifetime());
			}
		}

		boolean heavy = lethal || damage >= 5.0F;
		if (config.goreChunks && (heavy || RANDOM.nextFloat() < 0.2F * severity)) {
			chunks(level, origin, type, meatOf(entity), Math.round((lethal ? 14 : 6) * preset.particles * severity));
		}

		float spurtChance = Mth.clamp(damage / 14.0F + (lethal ? 0.35F : 0.0F) + (severity - 1.0F) * 0.2F, 0.0F, 0.9F) * Math.min(1.0F, preset.particles);
		if (config.arterialSpurts && RANDOM.nextFloat() < spurtChance) {
			Vec3 dir = new Vec3(RANDOM.nextGaussian() * 0.5 + (away != null ? away.x : 0.0), 0.6 + RANDOM.nextDouble() * 0.6,
					RANDOM.nextGaussian() * 0.5 + (away != null ? away.z : 0.0));
			int life = Math.round((20 + RANDOM.nextInt(40)) * preset.duration);
			world.spurts().start(() -> new Vec3(entity.getX() + ox, entity.getY() + oy, entity.getZ() + oz), dir, rgb, life,
					0.7F + RANDOM.nextFloat() * 0.6F);
		}

		if (config.goreSounds && (heavy || severity > 2.0F)) {
			crunch(level, origin, lethal ? 1.0F : 0.7F);
		}
	}

	/**
	 * A limb or head coming off: a burst at the wound, chunks, a crunch, and spurts from both the stump and the
	 * severed part.
	 */
	public static void sever(BloodWorld world, ClientLevel level, LivingEntity entity, Supplier<Vec3> stump, Supplier<Vec3> part, int rgb) {
		GoreConfig config = GoreConfig.get();
		GoreLevel preset = config.goreLevel;
		Vec3 at = part.get();
		if (rgb == BoneEmitter.BONE) {
			BoneEmitter.sever(level, at);
			return;
		}

		spray(world, at, null, Math.round(18 * preset.particles), 0.25F * preset.puddles, rgb, 0.1, 0.08, 0.35);
		redstoneDust(world, level, at, rgb, Math.round(6 * preset.particles), 0.1);
		if (config.goreChunks) {
			chunks(level, at, EntityFilter.bloodType(entity), meatOf(entity), Math.round(12 * preset.particles));
		}

		if (config.goreSounds) {
			crunch(level, at, 1.0F);
		}

		if (config.arterialSpurts) {
			int life = Math.round((30 + RANDOM.nextInt(40)) * preset.duration);
			world.spurts().start(stump, new Vec3(RANDOM.nextGaussian() * 0.4, 1.0, RANDOM.nextGaussian() * 0.4), rgb, life, 1.1F);
			world.spurts().start(part, new Vec3(RANDOM.nextGaussian(), 0.3, RANDOM.nextGaussian()), rgb, life / 2, 0.6F);
		}
	}

	/** A burst from an arbitrary point (thrown or stomped parts, headless necks). */
	public static void burst(BloodWorld world, ClientLevel level, Vec3 origin, int rgb, float volume, int count) {
		GoreLevel preset = GoreConfig.get().goreLevel;
		int scaled = Math.round(count * preset.particles);
		if (rgb == BoneEmitter.BONE) {
			BoneEmitter.burst(level, origin, null, scaled, scaled * 2, 0.15);
			return;
		}

		spray(world, origin, null, scaled, volume * preset.puddles, rgb, 0.1, 0.08, 0.3);
		redstoneDust(world, level, origin, rgb, Math.min(6, scaled / 3), 0.1);
	}

	/** A weapon biting into a body that is already down: a wet crunch. */
	public static void slash(ClientLevel level, Vec3 at, float volume) {
		if (GoreConfig.get().goreSounds) {
			crunch(level, at, volume);
		}
	}

	/** A slow trickle, used for stumps and moving severed parts. */
	public static void drip(BloodWorld world, Vec3 origin, int rgb, float volume) {
		if (rgb == BoneEmitter.BONE) {
			return;
		}

		world.droplets().spawn(origin.x + (RANDOM.nextDouble() - 0.5) * 0.1, origin.y, origin.z + (RANDOM.nextDouble() - 0.5) * 0.1,
				(RANDOM.nextDouble() - 0.5) * 0.04, -0.02, (RANDOM.nextDouble() - 0.5) * 0.04, volume, world.vary(rgb, 0.12F), 0.03F);
	}

	private static void spray(BloodWorld world, Vec3 origin, @Nullable Vec3 away, int count, float volume, int rgb, double spread,
			double minSpeed, double maxSpeed) {
		if (count <= 0) {
			return;
		}

		float each = volume / count;
		for (int i = 0; i < count && world.droplets().hasRoom(); i++) {
			// Random direction on the upper hemisphere, biased away from the attacker.
			double yaw = RANDOM.nextDouble() * Math.PI * 2.0;
			double pitch = RANDOM.nextDouble() * 0.9 + 0.1;
			double dx = Math.cos(yaw) * Math.cos(pitch);
			double dz = Math.sin(yaw) * Math.cos(pitch);
			double dy = Math.sin(pitch);
			if (away != null) {
				dx += away.x * 1.4;
				dz += away.z * 1.4;
			}

			double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
			double speed = (minSpeed + RANDOM.nextDouble() * (maxSpeed - minSpeed)) * world.lognormal(0.25F);
			// Drop sizes and volumes vary a lot: a few fat drops among fine spray.
			float sizeRoll = world.lognormal(0.45F);
			world.droplets().spawn(
					origin.x + (RANDOM.nextDouble() - 0.5) * spread,
					origin.y + (RANDOM.nextDouble() - 0.5) * spread,
					origin.z + (RANDOM.nextDouble() - 0.5) * spread,
					dx / length * speed, dy / length * speed * 0.8 + 0.05, dz / length * speed,
					each * sizeRoll, world.vary(rgb, 0.18F), Mth.clamp(0.03F * sizeRoll, 0.015F, 0.09F));
		}
	}

	/**
	 * Redstone-dust particles in the blood colour, in random sizes. Some fade from bright to dark like fresh
	 * blood oxidising.
	 */
	private static void redstoneDust(BloodWorld world, ClientLevel level, Vec3 origin, int rgb, int count, double spread) {
		for (int i = 0; i < count; i++) {
			int color = world.vary(rgb, 0.3F);
			float scale = 0.5F + RANDOM.nextFloat() * 1.5F;
			double x = origin.x + (RANDOM.nextDouble() - 0.5) * spread * 2.0;
			double y = origin.y + (RANDOM.nextDouble() - 0.5) * spread * 2.0;
			double z = origin.z + (RANDOM.nextDouble() - 0.5) * spread * 2.0;
			if (RANDOM.nextFloat() < 0.35F) {
				level.addParticle(new DustColorTransitionOptions(world.vary(color, 0.3F), darken(color), scale), x, y, z, 0.0, 0.0, 0.0);
			} else {
				level.addParticle(new DustParticleOptions(color, scale), x, y, z, 0.0, 0.0, 0.0);
			}
		}
	}

	/** Flying crumbs of the blood colour's block (redstone block for red) and bits of the body's meat item. */
	private static void chunks(ClientLevel level, Vec3 origin, BloodColor type, Item meat, int count) {
		BlockParticleOption crumbs = new BlockParticleOption(ParticleTypes.BLOCK, type.chunkBlock().defaultBlockState());
		ItemParticleOption flesh = new ItemParticleOption(ParticleTypes.ITEM, meat);
		for (int i = 0; i < count; i++) {
			double vx = RANDOM.nextGaussian() * 0.15;
			double vy = 0.1 + RANDOM.nextDouble() * 0.25;
			double vz = RANDOM.nextGaussian() * 0.15;
			level.addParticle(RANDOM.nextFloat() < 0.6F ? crumbs : flesh, origin.x, origin.y, origin.z, vx, vy, vz);
		}
	}

	private static void crunch(ClientLevel level, Vec3 at, float volume) {
		SoundEvent sound = CRUNCH[RANDOM.nextInt(CRUNCH.length)];
		level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.NEUTRAL, volume * (0.6F + RANDOM.nextFloat() * 0.4F),
				0.55F + RANDOM.nextFloat() * 0.45F, false);
	}

	/** The meat a body is made of: what the animal drops, rotten flesh for monsters, beef otherwise. */
	private static Item meatOf(LivingEntity entity) {
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
		return switch (id) {
			case "cow", "mooshroom" -> Items.BEEF;
			case "pig", "hoglin" -> Items.PORKCHOP;
			case "sheep" -> Items.MUTTON;
			case "chicken" -> Items.CHICKEN;
			case "rabbit" -> Items.RABBIT;
			case "cod" -> Items.COD;
			case "salmon" -> Items.SALMON;
			default -> entity instanceof Enemy ? Items.ROTTEN_FLESH : Items.BEEF;
		};
	}

	private static int darken(int rgb) {
		return ((((rgb >> 16) & 0xFF) * 2 / 5) << 16) | ((((rgb >> 8) & 0xFF) * 2 / 5) << 8) | ((rgb & 0xFF) * 2 / 5);
	}
}
