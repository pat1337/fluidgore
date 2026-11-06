package dev.fluidgore.dismember;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.demo.DemoPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Telling remains for what they are. Look at a piece of a body lying about (an organ or a bone a blast threw, a scrap
 * of flesh, a severed limb, a joint off the butcher's block) and what it is shows over the hotbar: whose it was, what it
 * is, which side, and whether it was seared or charred. "Cow · right lung", "Sheep · left femur, charred", "a cut of
 * Sheep's rack". Every piece keeps its name from the moment it came out of the body, so a scene can be read the way an
 * investigator reads one: what was nearest the charge, what flew furthest, which way.
 */
public final class Identify {
	/** How far off a piece can be made out, blocks. */
	private static final double REACH = 6.0;
	/** Ticks between looks; the text stays up a while on its own. */
	private static final int EVERY = 4;
	private static int tick;
	private static @Nullable String shown;

	private Identify() {
	}

	public static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (!GoreConfig.get().identifyRemains || player == null || mc.gui.screen() != null || DemoPlayer.playing() || ++tick % EVERY != 0) {
			return;
		}

		Component what = look(mc, player);
		String text = what == null ? null : what.getString();
		if (text != null && (!text.equals(shown) || tick % 40 == 0)) {
			player.sendOverlayMessage(what.copy().withStyle(ChatFormatting.GRAY));
		}

		shown = text;
	}

	/** What the player is looking at, if it is a piece of a body: the nearer of a loose piece and a scrap or organ. */
	public static @Nullable Component look(Minecraft mc, LocalPlayer player) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		double reach = REACH;
		HitResult hit = mc.hitResult;
		if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
			// Nothing behind a wall.
			reach = Math.min(reach, hit.getLocation().distanceTo(eye) + 0.1);
		}

		Giblets giblets = GoreSystems.dismemberment().giblets();
		int scrap = giblets.aimed(eye, look, reach);
		double scrapAt = scrap < 0 ? Double.MAX_VALUE : new Vec3(giblets.x[scrap], giblets.y[scrap], giblets.z[scrap]).distanceTo(eye);
		GorePart best = null;
		double bestAt = reach;
		Vec3 end = eye.add(look.scale(reach));
		for (GorePart part : GoreSystems.dismemberment().parts().parts()) {
			if (part.label == null) {
				continue;
			}

			float r = Math.max(part.halfExtents.x, Math.max(part.halfExtents.y, part.halfExtents.z)) + 0.05F;
			double d = new AABB(part.x - r, part.y - r, part.z - r, part.x + r, part.y + r, part.z + r).clip(eye, end).map(p -> p.distanceTo(eye))
					.orElse(Double.MAX_VALUE);
			if (d < bestAt) {
				bestAt = d;
				best = part;
			}
		}

		if (best != null && bestAt <= scrapAt) {
			return best.label;
		}

		return scrap >= 0 ? giblets.describe(scrap) : null;
	}
}
