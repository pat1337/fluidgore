package dev.fluidgore.dismember;

import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.BoneEmitter;
import dev.fluidgore.GoreSystems;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.net.GoreAction;
import dev.fluidgore.net.GoreSync;
import dev.fluidgore.net.PieceSync;
import dev.fluidgore.suffer.CorpseStrike;
import dev.fluidgore.wound.Weapon;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Lets the player mess with severed parts: punch them (attack), pick them up and throw them (use), kick them by
 * walking into them and squash them by landing on them. Parts are client-side objects, so this sends nothing
 * extra to the server; clicks that hit a part are consumed so they do not also break a block or use an item.
 */
public final class PartInteraction {
	private static final double REACH = 4.5;
	private static final double HOLD_DISTANCE = 1.6;
	private static final int USE_COOLDOWN = 6;

	private final GorePartManager parts;
	private final RandomSource random = RandomSource.create();
	private @Nullable GorePart held;
	private int tick;
	private int lastUseTick = -100;

	PartInteraction(GorePartManager parts) {
		this.parts = parts;
	}

	public @Nullable GorePart held() {
		return held;
	}

	private static boolean enabled() {
		GoreConfig config = GoreConfig.get();
		return config.playWithParts && config.goreLevel.enabled();
	}

	/** Attack key pressed. Returns true if a part took the hit (the vanilla attack is then skipped). */
	public boolean onAttack(Minecraft mc, BloodWorld blood) {
		LocalPlayer player = mc.player;
		if (!enabled() || player == null || !(mc.level instanceof ClientLevel level)) {
			return false;
		}

		if (held != null) {
			throwHeld(player, 1.3);
			player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			return true;
		}

		GorePart target = pick(mc, player);
		if (target == null) {
			// Not a loose part: maybe a body that is down or still dying.
			return CorpseStrike.onAttack(mc, player, level, blood);
		}

		Vec3 look = player.getViewVector(1.0F);
		if (GoreConfig.get().butchery && Weapon.of(player.getMainHandItem()).cuts()
				&& GoreSync.act(GoreAction.of(GoreAction.CUT, -1, target.key, player.getEyePosition(), look, 0, 0.0F))) {
			// A blade on a loose piece cuts it in two.
			player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			return true;
		}

		if (target.bone() && ++target.hits >= 2) {
			// Second blow: the bone breaks apart.
			PieceSync.moved(target);
			PieceSync.shattered(target);
			BoneEmitter.shatter(level, new Vec3(target.x, target.y, target.z), 1.0F);
			target.shattered = true;
			player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			return true;
		}

		PieceSync.moved(target);
		wake(target);
		target.vx += look.x * 0.55 + (random.nextDouble() - 0.5) * 0.1;
		target.vy += 0.22 + Math.max(0.0, look.y) * 0.3;
		target.vz += look.z * 0.55 + (random.nextDouble() - 0.5) * 0.1;
		spin(target, 0.5F);
		player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		BloodEmitter.burst(blood, level, new Vec3(target.x, target.y, target.z), target.bloodRgb, 0.04F, 6);
		level.playLocalSound(target.x, target.y, target.z, SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.PLAYERS, 0.8F, 0.9F, false);
		level.playLocalSound(target.x, target.y, target.z, target.bone() ? SoundEvents.BONE_BLOCK_HIT : SoundEvents.SLIME_SQUISH_SMALL, SoundSource.NEUTRAL, 0.8F, 0.7F, false);
		return true;
	}

	/** Use key pressed. Returns true if it picked up or threw a part (the vanilla use is then skipped). */
	public boolean onUse(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (!enabled() || player == null) {
			return false;
		}

		if (tick - lastUseTick < USE_COOLDOWN) {
			// Holding the use key repeats it every few ticks; don't instantly drop what was just picked up.
			return held != null;
		}

		if (held != null) {
			lastUseTick = tick;
			throwHeld(player, 1.0);
			return true;
		}

		GorePart target = pick(mc, player);
		if (target == null) {
			return false;
		}

		lastUseTick = tick;
		held = target;
		target.held = true;
		PieceSync.moved(target);
		wake(target);
		if (mc.level != null) {
			mc.level.playLocalSound(target.x, target.y, target.z, SoundEvents.SLIME_SQUISH_SMALL, SoundSource.NEUTRAL, 0.6F, 1.2F, false);
		}

		return true;
	}

	/** Carrying, kicking by walking into parts, and squashing by landing on them. */
	void tick(ClientLevel level, BloodWorld blood) {
		tick++;
		LocalPlayer player = Minecraft.getInstance().player;
		if (held != null && (!enabled() || player == null || !player.isAlive() || !parts.parts().contains(held))) {
			if (held != null) {
				held.held = false;
			}

			held = null;
		}

		if (player == null || !enabled()) {
			return;
		}

		if (held != null) {
			carry(player, held, blood);
		}

		Vec3 motion = player.getDeltaMovement();
		AABB feet = player.getBoundingBox();
		for (GorePart part : parts.parts()) {
			if (part.held) {
				continue;
			}

			float r = GorePartManager.radius(part);
			AABB box = new AABB(part.x - r, part.y - r, part.z - r, part.x + r, part.y + r, part.z + r);
			if (!box.inflate(0.15).intersects(feet)) {
				continue;
			}

			if (motion.y < -0.3 && player.getY() > part.y && part.squash > 0.5F) {
				// Landed on it.
				PieceSync.moved(part);
				if (part.bone()) {
					PieceSync.shattered(part);
					BoneEmitter.shatter(level, new Vec3(part.x, part.y, part.z), 1.2F);
					part.shattered = true;
					continue;
				}

				part.squash = 0.35F;
				part.bleedLeft += 0.05F;
				blood.landOnFloor(level, part.x, part.y - r, part.z, 0.12F, part.bloodRgb, blood.decalLifetime());
				BloodEmitter.burst(blood, level, new Vec3(part.x, part.y, part.z), part.bloodRgb, 0.08F, 14);
				level.playLocalSound(part.x, part.y, part.z, SoundEvents.SLIME_SQUISH, SoundSource.NEUTRAL, 1.0F, 0.55F, false);
				continue;
			}

			double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
			if (horizontal > 0.03 && Math.abs(part.vx) + Math.abs(part.vz) < 0.15) {
				// Walked into it: a kick in the walking direction.
				PieceSync.moved(part);
				wake(part);
				part.vx += motion.x * 1.6;
				part.vz += motion.z * 1.6;
				part.vy += 0.12 + horizontal * 0.5;
				spin(part, 0.3F);
			}
		}
	}

	private void carry(LocalPlayer player, GorePart part, BloodWorld blood) {
		PieceSync.moved(part);
		Vec3 eye = player.getEyePosition();
		Vec3 target = eye.add(player.getViewVector(1.0F).scale(HOLD_DISTANCE)).subtract(0.0, 0.25, 0.0);
		part.x += (target.x - part.x) * 0.5;
		part.y += (target.y - part.y) * 0.5;
		part.z += (target.z - part.z) * 0.5;
		part.vx = part.vy = part.vz = 0.0;
		part.rotation.slerp(new Quaternionf().rotationY((180.0F - player.getYRot()) * Mth.DEG_TO_RAD), 0.3F);
		if (part.bleedLeft > 0.0F && tick % 5 == 0) {
			BloodEmitter.drip(blood, new Vec3(part.x, part.y - 0.1, part.z), part.bloodRgb, 0.006F);
			part.bleedLeft -= 0.006F;
		}
	}

	private void throwHeld(LocalPlayer player, double strength) {
		GorePart part = held;
		held = null;
		if (part == null) {
			return;
		}

		part.held = false;
		Vec3 look = player.getViewVector(1.0F);
		PieceSync.moved(part);
		Vec3 motion = player.getDeltaMovement();
		part.vx = look.x * strength + motion.x;
		part.vy = look.y * strength + 0.1 + Math.max(0.0, motion.y);
		part.vz = look.z * strength + motion.z;
		spin(part, 0.45F);
	}

	/** Nearest part under the crosshair that is closer than whatever block or entity vanilla is aiming at. */
	private @Nullable GorePart pick(Minecraft mc, LocalPlayer player) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(REACH));
		double limit = REACH * REACH;
		HitResult hit = mc.hitResult;
		if (hit != null && hit.getType() != HitResult.Type.MISS) {
			limit = Math.min(limit, hit.getLocation().distanceToSqr(eye));
		}

		GorePart best = null;
		List<GorePart> all = parts.parts();
		for (GorePart part : all) {
			float r = Math.max(GorePartManager.radius(part), Math.max(part.halfExtents.x, Math.max(part.halfExtents.y, part.halfExtents.z))) + 0.08F;
			Optional<Vec3> clip = new AABB(part.x - r, part.y - r, part.z - r, part.x + r, part.y + r, part.z + r).clip(eye, end);
			if (clip.isPresent()) {
				double d = clip.get().distanceToSqr(eye);
				if (d < limit) {
					limit = d;
					best = part;
				}
			}
		}

		return best;
	}

	private static void wake(GorePart part) {
		part.resting = false;
		part.restTicks = 0;
		part.restTarget = null;
	}

	private void spin(GorePart part, float speed) {
		part.spinAxis.set(random.nextFloat() - 0.5F, random.nextFloat() - 0.5F, random.nextFloat() - 0.5F).normalize();
		if (!part.spinAxis.isFinite()) {
			part.spinAxis.set(0.0F, 0.0F, 1.0F);
		}

		part.spinSpeed = speed * (0.6F + random.nextFloat() * 0.6F);
	}

	void clear() {
		if (held != null) {
			held.held = false;
		}

		held = null;
	}
}
