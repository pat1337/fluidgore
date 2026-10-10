package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Scrubbing blood away with a held cleaning item (a sponge by default). Holding the use key repeats the click, so
 * a floor is wiped by dragging the crosshair over it. Blood is client-side, so nothing is sent to the server; a
 * sponge is only kept from being placed while there is blood to wipe, or while the player is still scrubbing.
 */
public final class Cleaning {
	private static final double REACH = 4.5;
	private static final float RADIUS = 0.85F;
	/** Ticks after a wipe during which clicks still count as scrubbing, so the last smear does not place the sponge. */
	private static final int SCRUB_GRACE = 12;

	private static long lastScrub = Long.MIN_VALUE;

	private Cleaning() {
	}

	public static boolean isCleaningItem(net.minecraft.world.item.ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}

		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		for (String entry : GoreConfig.get().cleaningItems) {
			if (entry.trim().equals(id)) {
				return true;
			}
		}

		return false;
	}

	/** Use key pressed. Returns true if the click scrubbed (the vanilla item use is then skipped). */
	public static boolean onUse(Minecraft mc, BloodWorld blood) {
		LocalPlayer player = mc.player;
		GoreConfig config = GoreConfig.get();
		if (!config.cleaning || !config.goreLevel.enabled() || player == null || player.isShiftKeyDown()
				|| !(mc.level instanceof ClientLevel level) || !(mc.hitResult instanceof BlockHitResult hit)
				|| hit.getType() != HitResult.Type.BLOCK || !isCleaningItem(player.getMainHandItem())) {
			return false;
		}

		Vec3 at = hit.getLocation();
		if (at.distanceToSqr(player.getEyePosition()) > REACH * REACH) {
			return false;
		}

		long now = level.getGameTime();
		int wiped = blood.clean(at, hit.getDirection(), RADIUS);
		if (wiped == 0) {
			// Nothing here: only swallow the click while a scrub is under way, otherwise the sponge is placed as usual.
			return now - lastScrub <= SCRUB_GRACE && blood.hasBloodNear(at, RADIUS * 2.5F);
		}

		lastScrub = now;
		player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.BRUSH_GENERIC, SoundSource.PLAYERS, 0.7F, 0.9F + level.getRandom().nextFloat() * 0.2F, false);
		Direction face = hit.getDirection();
		for (int i = 0; i < 4; i++) {
			double ox = (level.getRandom().nextDouble() - 0.5) * RADIUS;
			double oz = (level.getRandom().nextDouble() - 0.5) * RADIUS;
			level.addParticle(ParticleTypes.SPLASH, at.x + (face.getStepX() == 0 ? ox : 0.0) + face.getStepX() * 0.05,
					at.y + (face.getStepY() == 0 ? Math.abs(oz) : 0.05), at.z + (face.getStepZ() == 0 ? oz : 0.0) + face.getStepZ() * 0.05,
					0.0, 0.05, 0.0);
		}

		return true;
	}
}
