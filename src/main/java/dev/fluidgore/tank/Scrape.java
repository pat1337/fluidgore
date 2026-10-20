package dev.fluidgore.tank;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The sound and spray of a blow on a Cobble Tank, layered the way the gore's hits are (a body sound, a texture on top,
 * pitch jittered so no two blows sound alike): the stone skin cracking and chipping off in cobble crumbs, the tool
 * dragging across the metal under it (an axe scraping copper, a grindstone), sparks once a hard tool bites, and the
 * deep clang of the liner when one gets through.
 */
final class Scrape {
	private Scrape() {
	}

	/** A blow that did {@code blow} with a tool of {@code force}, landing at {@code at}, struck along {@code dir}. */
	static void play(ServerLevel level, Vec3 at, Vec3 dir, TankState.Blow blow, float force) {
		RandomSource random = level.getRandom();
		switch (blow) {
			case THUD -> sound(level, at, SoundEvents.STONE_HIT, 0.7F, 0.5F, random);
			case SCUFF -> {
				sound(level, at, SoundEvents.STONE_HIT, 0.9F, 0.9F, random);
				sound(level, at, SoundEvents.AXE_SCRAPE.value(), 0.5F, 1.5F, random);
				chips(level, at, dir, 3, random);
			}
			case CUT -> {
				sound(level, at, SoundEvents.STONE_BREAK, 0.8F, 1.1F, random);
				sound(level, at, SoundEvents.AXE_SCRAPE.value(), 1.0F, 0.9F, random);
				sound(level, at, SoundEvents.GRINDSTONE_USE, 0.6F, 1.4F, random);
				chips(level, at, dir, 6 + Math.round(force * 40.0F), random);
				sparks(level, at, dir, force >= 0.2F ? 8 : 3, random);
			}
			case THROUGH -> {
				sound(level, at, SoundEvents.STONE_BREAK, 1.0F, 0.8F, random);
				sound(level, at, SoundEvents.AXE_SCRAPE.value(), 1.0F, 0.7F, random);
				sound(level, at, SoundEvents.ANVIL_LAND, 0.8F, 0.6F, random);
				chips(level, at, dir, 16, random);
				sparks(level, at, dir, 12, random);
			}
			case BURST -> sound(level, at, SoundEvents.ANVIL_BREAK, 1.2F, 0.5F, random);
		}
	}

	private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch, RandomSource random) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.BLOCKS, volume, pitch * (0.9F + random.nextFloat() * 0.2F));
	}

	/** Cobble crumbs knocked back off the face towards whoever struck it. */
	private static void chips(ServerLevel level, Vec3 at, Vec3 dir, int count, RandomSource random) {
		BlockParticleOption cobble = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultBlockState());
		for (int i = 0; i < count; i++) {
			Vec3 v = dir.scale(-0.15 - random.nextDouble() * 0.15).add(random.nextGaussian() * 0.08, 0.1 + random.nextDouble() * 0.1, random.nextGaussian() * 0.08);
			level.sendParticles(cobble, at.x, at.y, at.z, 0, v.x, v.y, v.z, 1.0);
		}
	}

	/** Steel on the metal under the stone: sparks and the scrape's flecks. */
	private static void sparks(ServerLevel level, Vec3 at, Vec3 dir, int count, RandomSource random) {
		for (int i = 0; i < count; i++) {
			Vec3 v = dir.scale(-0.3).add(random.nextGaussian() * 0.2, random.nextDouble() * 0.2, random.nextGaussian() * 0.2);
			level.sendParticles(i % 3 == 0 ? ParticleTypes.SCRAPE : ParticleTypes.CRIT, at.x, at.y, at.z, 0, v.x, v.y, v.z, 1.0);
		}
	}
}
