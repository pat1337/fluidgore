package dev.fluidgore.player;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.config.GoreConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

/**
 * The injured player's screen, drawn over the world under the hotbar: the red edge (hurt.png's dithered vignette,
 * stretched so every texel is a big block) and the blots of blood from {@link PlayerInjuries}, each a pixel-art splat
 * with a run creeping down from it, drying darker until it fades. keeping beta look with chunky pixels on purpose, the way the
 * game's own hearts and redstone look.
 **/

public final class HurtScreen {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("fluidgore", "hurt_screen");
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("fluidgore", "textures/misc/hurt.png");
	private static final int TEX_W = 192;
	private static final int TEX_H = 64;
	/** The colour of the red edge. */
	private static final int EDGE = 0xA00000;
	/** Screen texels per splat texel, per 100 GUI pixels of screen height. */
	private static final float TEXEL = 1.0F / 110.0F;

	private HurtScreen() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, ID, HurtScreen::draw);
	}

	private static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || !GoreConfig.get().goreLevel.enabled() || player.isSpectator()) {
			return;
		}

		PlayerInjuries injuries = GoreSystems.injuries();
		float partial = delta.getGameTimeDeltaPartialTick(false);
		int w = graphics.guiWidth();
		int h = graphics.guiHeight();
		if (GoreConfig.get().hurtScreen) {
			for (PlayerInjuries.Splat splat : injuries.splats()) {
				splat(graphics, splat, partial, w, h);
			}
		}

		float red = injuries.redness(player, partial);
		if (red > 0.01F) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, 0.0F, 0.0F, w, h, 64, 64, TEX_W, TEX_H, ARGB.color(Math.round(red * 255.0F), EDGE));
		}
	}

	private static void splat(GuiGraphicsExtractor graphics, PlayerInjuries.Splat splat, float partial, int w, int h) {
		float age = splat.age + partial;
		float t = age / splat.life;
		float alpha = t < 0.6F ? 1.0F : Math.max(0.0F, 1.0F - (t - 0.6F) / 0.4F);
		if (alpha <= 0.0F) {
			return;
		}

		// Wet blood shows bright on the screen; it dries darker.
		float light = Mth.lerp(Math.min(1.0F, t * 1.4F), 1.5F, 0.8F);
		int rgb = splat.rgb;
		int color = ARGB.color(Math.round(alpha * 255.0F), shade(rgb >> 16, light), shade(rgb >> 8, light), shade(rgb, light));
		int px = Math.max(1, Math.round(h * TEXEL * splat.size));
		int cx = Math.round(splat.x * w);
		int cy = Math.round(splat.y * h);
		if (splat.drip >= 0) {
			// The run creeps down from the middle of the blot, a texel at a time.
			int length = Mth.clamp(Math.round((age - 6.0F) * 0.9F), 0, 64);
			if (length > 0) {
				int x = cx + Math.round(splat.dripX * 16.0F * px) - 8 * px;
				graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, cy, 128.0F + splat.drip * 16.0F, 0.0F, 16 * px, length * px, 16, length, TEX_W,
						TEX_H, color);
			}
		}

		graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, cx - 16 * px, cy - 16 * px, 64.0F + splat.tile % 2 * 32.0F, splat.tile / 2 * 32.0F,
				32 * px, 32 * px, 32, 32, TEX_W, TEX_H, color);
	}

	private static int shade(int channel, float light) {
		return Math.min(255, Math.round((channel & 0xFF) * light));
	}
}
