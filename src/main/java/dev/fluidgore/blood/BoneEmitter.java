package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import dev.fluidgore.trigger.Fate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What a hit does to something made of bone (skeletons, parched, skeleton horses): no blood, no puddles. Bone
 * cracks, chips into shards and crumbles into dust, with dry, hard sounds. Everything is vanilla particles and
 * sounds: bone-block crumbs, bone and bone-meal items, pale dust and puffs.
 */
public final class BoneEmitter {
	/** Stands in for a blood colour wherever one is carried around: "this is bone, not blood". */
	public static final int BONE = -1;
	private static final int DUST = 0xE6E1CC;
	private static final RandomSource RANDOM = Fate.stream("bone");
	private static final BlockParticleOption CRUMBS = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.BONE_BLOCK.defaultBlockState());
	private static final ItemParticleOption SHARD = new ItemParticleOption(ParticleTypes.ITEM, Items.BONE);
	private static final ItemParticleOption POWDER = new ItemParticleOption(ParticleTypes.ITEM, Items.BONE_MEAL);
	private static final SoundEvent[] CRACK = {SoundEvents.BONE_BLOCK_BREAK, SoundEvents.BONE_BLOCK_HIT, SoundEvents.BONE_BLOCK_FALL};

	private BoneEmitter() {
	}

	/** A wound on a bony body: chips and dust at a point on the upper body, a hard crack, a shattering on the kill. */
	public static void hit(ClientLevel level, LivingEntity entity, float damage, boolean lethal, @Nullable Vec3 away) {
		GoreLevel preset = GoreConfig.get().goreLevel;
		if (!preset.enabled()) {
			return;
		}

		damage = Mth.clamp(damage, 0.5F, 40.0F);
		Vec3 origin = new Vec3(entity.getX() + (RANDOM.nextDouble() - 0.5) * entity.getBbWidth() * 0.6,
				entity.getY() + entity.getBbHeight() * (0.3 + RANDOM.nextDouble() * 0.55),
				entity.getZ() + (RANDOM.nextDouble() - 0.5) * entity.getBbWidth() * 0.6);
		float size = Mth.clamp(damage / 6.0F, 0.4F, 3.0F) * (lethal ? 2.0F : 1.0F);
		burst(level, origin, away, Math.round(8 * size * preset.particles), Math.round(10 * size * preset.particles), 0.2 + 0.05 * size);
		if (GoreConfig.get().goreSounds) {
			crack(level, origin, lethal ? 1.0F : Math.min(0.9F, 0.4F + damage / 10.0F), lethal ? 0.6F : 0.9F);
		}
	}

	/** A bone coming away: a spray of shards and a hard crack. */
	public static void sever(ClientLevel level, Vec3 at) {
		GoreLevel preset = GoreConfig.get().goreLevel;
		burst(level, at, null, Math.round(16 * preset.particles), Math.round(18 * preset.particles), 0.25);
		if (GoreConfig.get().goreSounds) {
			crack(level, at, 1.0F, 0.7F);
		}
	}

	/** A loose bone slammed, stomped or dropped hard: it breaks apart into shards and dust. */
	public static void shatter(ClientLevel level, Vec3 at, float size) {
		GoreLevel preset = GoreConfig.get().goreLevel;
		burst(level, at, null, Math.round(14 * size * preset.particles), Math.round(24 * size * preset.particles), 0.3);
		if (GoreConfig.get().goreSounds) {
			crack(level, at, 1.0F, 0.55F);
			level.playLocalSound(at.x, at.y, at.z, SoundEvents.ITEM_BREAK.value(), SoundSource.NEUTRAL, 0.6F, 0.6F + RANDOM.nextFloat() * 0.3F, false);
		}
	}

	/** Shards thrown out hard, a puff of pale dust that hangs, and fine powder. */
	public static void burst(ClientLevel level, Vec3 origin, @Nullable Vec3 away, int shards, int dust, double speed) {
		for (int i = 0; i < shards; i++) {
			double vx = RANDOM.nextGaussian() * speed + (away != null ? away.x * speed : 0.0);
			double vy = 0.08 + RANDOM.nextDouble() * speed * 1.5;
			double vz = RANDOM.nextGaussian() * speed + (away != null ? away.z * speed : 0.0);
			level.addParticle(RANDOM.nextFloat() < 0.65F ? CRUMBS : SHARD, origin.x, origin.y, origin.z, vx, vy, vz);
		}

		for (int i = 0; i < dust; i++) {
			double x = origin.x + (RANDOM.nextDouble() - 0.5) * 0.4;
			double y = origin.y + (RANDOM.nextDouble() - 0.5) * 0.4;
			double z = origin.z + (RANDOM.nextDouble() - 0.5) * 0.4;
			if (RANDOM.nextFloat() < 0.25F) {
				level.addParticle(POWDER, x, y, z, RANDOM.nextGaussian() * 0.04, 0.03 + RANDOM.nextDouble() * 0.05, RANDOM.nextGaussian() * 0.04);
			} else {
				level.addParticle(new DustParticleOptions(DUST, 0.6F + RANDOM.nextFloat()), x, y, z, 0.0, 0.0, 0.0);
			}
		}

		if (dust > 4) {
			level.addParticle(ParticleTypes.POOF, origin.x, origin.y, origin.z, RANDOM.nextGaussian() * 0.02, 0.02, RANDOM.nextGaussian() * 0.02);
		}
	}

	/** Two layered bone sounds at different pitches, so a hit sounds like a hard crack and not a single sample. */
	private static void crack(ClientLevel level, Vec3 at, float volume, float pitch) {
		SoundEvent first = CRACK[RANDOM.nextInt(CRACK.length)];
		level.playLocalSound(at.x, at.y, at.z, first, SoundSource.NEUTRAL, volume, pitch + RANDOM.nextFloat() * 0.2F, false);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.BONE_BLOCK_BREAK, SoundSource.NEUTRAL, volume * 0.8F, pitch * 0.7F + RANDOM.nextFloat() * 0.2F, false);
	}
}
