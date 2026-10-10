package dev.fluidgore.wound;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.BoneEmitter;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.trigger.EntityFilter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * What a blow sounds and looks like where it lands, by weapon and by what it lands in. Layered from vanilla block
 * sounds pitched down: a fist on a flank is a dull, heavy thud that barely bleeds; a flint scrapes; a sword is a wet
 * slice; a cleaver a heavy chop; a pick digs in and knocks bits out like mining; once the wound is down to the bone
 * the blade cracks on it. Blood comes off the cut along the blow and out of the wound, as much as the weapon opens it.
 */
public final class HitFeedback {
	private static final RandomSource RANDOM = RandomSource.create();

	private HitFeedback() {
	}

	/** Feedback for {@code strike} on {@code entity}, a blow travelling along {@code dir} (unit vector). */
	public static void hit(ClientLevel level, BloodWorld blood, LivingEntity entity, Wounds.Strike strike, Weapon weapon, Vec3 dir) {
		Flesh flesh = Flesh.of(entity);
		int rgb = EntityFilter.bloodType(entity).rgb;
		Vec3 at = strike.hit().point();
		float depth = strike.severity();
		boolean bone = depth >= 0.75F;
		if (GoreConfig.get().goreSounds) {
			sounds(level, at, weapon, flesh, bone);
		}

		// Out of the wound and on along the swing.
		Vec3 away = strike.hit().normal().scale(0.6).add(dir.scale(0.45)).add(0.0, 0.15, 0.0);
		if (away.lengthSqr() > 1.0E-6) {
			int count = Math.round((3 + 10 * depth) * weapon.bleed);
			int color = flesh == Flesh.BONE ? BoneEmitter.BONE : flesh == Flesh.DRIED ? flesh.tint(1, rgb) : rgb;
			if (count > 0) {
				BloodEmitter.cut(blood, level, at, away.normalize(), color, (0.01F + 0.04F * depth) * weapon.bleed, count);
			}
		}

		// A pick or an axe knocks bits out of the wound once it is past the skin.
		if (weapon.chips > 0 && depth >= 0.35F) {
			int n = weapon.chips + RANDOM.nextInt(weapon.chips + 1);
			GoreSystems.dismemberment().giblets().chips(level, entity, at, strike.hit().normal(), n, depth);
		}

		// A beating that has gone on long enough: blood comes up with the breath.
		WoundSet set = GoreSystems.dismemberment().wounds().get(entity.getId());
		if (weapon == Weapon.BLUNT && set != null && set.trauma() > 1.5F && flesh.bleeds() && RANDOM.nextFloat() < 0.5F) {
			BloodEmitter.burst(blood, level, entity.getEyePosition(), rgb, 0.01F, 3);
		}
	}

	private static void sounds(ClientLevel level, Vec3 at, Weapon weapon, Flesh flesh, boolean bone) {
		switch (flesh) {
			case BONE -> {
				play(level, at, SoundEvents.BONE_BLOCK_HIT, 1.0F, 0.9F);
				if (weapon != Weapon.BLUNT) {
					play(level, at, SoundEvents.BONE_BLOCK_BREAK, 0.8F, 1.15F);
				}
			}
			case DRIED -> {
				// Leathery flesh tearing, and dust.
				play(level, at, SoundEvents.SAND_BREAK, 0.9F, 0.7F);
				if (weapon.cuts()) {
					play(level, at, SoundEvents.HONEY_BLOCK_BREAK, 0.6F, 0.8F);
				}
			}
			case MEAT -> {
				switch (weapon) {
					// A heavy, dead thud: meat over bone, no splash.
					case BLUNT -> {
						play(level, at, SoundEvents.MUD_BREAK, 0.9F, 0.5F);
						play(level, at, SoundEvents.WOOL_HIT, 0.8F, 0.6F);
					}
					case FLINT -> {
						play(level, at, SoundEvents.STONE_HIT, 0.6F, 1.4F);
						play(level, at, SoundEvents.SLIME_BLOCK_STEP, 0.6F, 1.2F);
					}
					case BLADE -> play(level, at, SoundEvents.SLIME_BLOCK_BREAK, 0.8F, 1.1F);
					case CHOP -> {
						play(level, at, SoundEvents.HONEY_BLOCK_BREAK, 1.0F, 0.6F);
						play(level, at, SoundEvents.MUD_BREAK, 0.8F, 0.7F);
					}
					// Digging into it like a block.
					case PICK -> {
						play(level, at, SoundEvents.MUD_HIT, 1.0F, 0.8F);
						play(level, at, SoundEvents.HONEY_BLOCK_HIT, 0.8F, 0.7F);
					}
					case PIERCE -> play(level, at, SoundEvents.HONEY_BLOCK_STEP, 0.9F, 1.3F);
					case BITE -> {
						play(level, at, SoundEvents.SLIME_BLOCK_STEP, 0.8F, 0.9F);
						play(level, at, SoundEvents.HONEY_BLOCK_BREAK, 0.5F, 1.3F);
					}
					// The explosion is loud enough on its own.
					case BLAST -> {
					}
				}

				if (bone && weapon.cuts()) {
					play(level, at, SoundEvents.BONE_BLOCK_BREAK, 0.9F, 0.6F);
				}
			}
		}
	}

	private static void play(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.NEUTRAL, volume * (0.85F + RANDOM.nextFloat() * 0.3F),
				pitch * (0.9F + RANDOM.nextFloat() * 0.2F), false);
	}
}
