package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.trigger.EntityFilter;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * Pallor: skin goes pale as the blood leaves it. The flush of pink skin is blood in the capillaries; as a body loses
 * blood they empty (the body shuts them down first in shock, to keep the heart and brain supplied) and the skin goes a
 * waxy grey-white. A pig stuck and bled out is paler than one standing beside it within the minute it takes to bleed.
 * <p>
 * How pale is the share of its blood the body has lost ({@link BloodSupply#lostShare}): first signs at about a twelfth, as pale
 * as it gets at {@link #FULL}, about what a carcass loses when it is bled. It goes as the blood comes back
 * ({@link BloodSupply#refill}). How much shows is how much bare skin the animal has: all of a pig, the skin of a shorn
 * sheep and its face (never the wool), a person's face and hands, and on a cow or a horse only the muzzle and the thin
 * skin, because the coat hides the rest. Drawn on the body's own model (not its wool, armour or saddle) as a tint that takes the flush out and vanilla's white
 * overlay that lifts it toward white, so the markings stay.
 */
public final class Pallor {
	/** How pale the body is (see {@link #of}), for its own model only: drawn with {@link #tint(float)} and {@link #overlay}. */
	public static final RenderStateDataKey<Float> KEY = RenderStateDataKey.create(() -> "fluidgore:pallor");
	/** Share of the blood lost at which a body is as pale as it gets. */
	private static final float FULL = 0.45F;
	/** Share lost before any of it shows. */
	private static final float ONSET = 0.08F;
	/** The tint at full pallor on bare skin: the red flush out, a cold grey cast. */
	private static final int WAXY = 0xCCE2E8;
	/** The white overlay at full pallor, in vanilla's steps of 1/20 (6: the skin is lifted 30% of the way to white). */
	private static final int WHITE_U = 6;
	/** How much of the body is bare skin that shows pallor, by entity type path. */
	private static final Map<String, Float> BARE = Map.ofEntries(
			Map.entry("pig", 1.0F), Map.entry("hoglin", 0.45F), Map.entry("sheep", 0.75F), Map.entry("goat", 0.35F),
			Map.entry("cow", 0.3F), Map.entry("mooshroom", 0.3F), Map.entry("horse", 0.25F), Map.entry("donkey", 0.25F),
			Map.entry("mule", 0.25F), Map.entry("camel", 0.25F), Map.entry("llama", 0.2F), Map.entry("trader_llama", 0.2F),
			Map.entry("chicken", 0.3F), Map.entry("rabbit", 0.2F), Map.entry("wolf", 0.2F), Map.entry("fox", 0.2F),
			Map.entry("cat", 0.2F), Map.entry("ocelot", 0.2F), Map.entry("polar_bear", 0.15F), Map.entry("panda", 0.15F),
			Map.entry("player", 0.8F), Map.entry("villager", 0.8F), Map.entry("wandering_trader", 0.8F), Map.entry("pillager", 0.8F),
			Map.entry("vindicator", 0.8F), Map.entry("evoker", 0.8F), Map.entry("illusioner", 0.8F), Map.entry("witch", 0.8F)
	);
	/** Anything not listed: fur, scales or feathers over most of it. */
	private static final float DEFAULT_BARE = 0.3F;

	private Pallor() {
	}

	/** How pale {@code entity} is, 0 (not at all) to 1 (as pale as its skin shows). */
	public static float of(LivingEntity entity) {
		if (!GoreConfig.get().pallor || EntityFilter.isRotten(entity) || EntityFilter.isBone(entity)) {
			return 0.0F;
		}

		float lost = BloodSupply.lostShare(entity.getId());
		if (lost <= ONSET) {
			return 0.0F;
		}

		float t = Mth.clamp((lost - ONSET) / (FULL - ONSET), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t) * bare(entity);
	}

	/** Share of {@code entity}'s body that is bare skin, where pallor shows. */
	public static float bare(LivingEntity entity) {
		String path = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
		return BARE.getOrDefault(path, DEFAULT_BARE);
	}

	/** How pale {@code entity} is for its render state, or null if it is not pale at all. */
	public static @Nullable Float look(LivingEntity entity) {
		float p = of(entity);
		return p <= 0.0F ? null : Float.valueOf(p);
	}

	/** White (a full flush) to {@link #WAXY} at 1. */
	public static int tint(float pale) {
		float t = Mth.clamp(pale, 0.0F, 1.0F);
		int r = Math.round(Mth.lerp(t, 0xFF, WAXY >> 16 & 0xFF));
		int g = Math.round(Mth.lerp(t, 0xFF, WAXY >> 8 & 0xFF));
		int b = Math.round(Mth.lerp(t, 0xFF, WAXY & 0xFF));
		return r << 16 | g << 8 | b;
	}

	/**
	 * The overlay coordinates for a body this pale: vanilla's white overlay (the flash of a creeper about to blow) lifts
	 * the skin toward white, which a tint cannot do. Keeps the red of a fresh hurt, which uses the same overlay.
	 */
	public static int overlay(int overlay, float pale) {
		int v = overlay >>> 16;
		if (v < OverlayTexture.WHITE_OVERLAY_V) {
			return overlay;
		}

		int u = Math.round(Mth.clamp(pale, 0.0F, 1.0F) * WHITE_U);
		return OverlayTexture.pack(Math.max(overlay & 0xFFFF, u), v);
	}
}
