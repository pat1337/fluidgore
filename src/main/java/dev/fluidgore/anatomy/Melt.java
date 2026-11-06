package dev.fluidgore.anatomy;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * How far a burning person has gone, for {@link MeltRenderer}.
 *
 * @param bare how far the fire has got through the body, 0..1: the skin, then the fat, then the muscle (see
 *     {@link MeltRenderer} for where each layer goes); char knocked off a dead body counts too
 * @param burn how long it has burnt, 0..1: how cooked the organs are
 * @param bone how burnt the bones are, 0..1 (see {@link dev.fluidgore.wound.BurntBone}): charred by the flesh burning off them,
 *     grey and calcined only by a fire that went on under them after
 * @param lit on fire now: the edges of the holes glow and the fat runs
 * @param dead a body: the heat draws its limbs in and its jaw drops
 * @param seed the body's own dice for where the fire gets through first, from its UUID: the same on every screen
 * @param rotten a walking corpse (a zombie): what is under its skin is rotting, not fresh
 * @param beast a sheep, a pig or a cow, burning through its own anatomy; null for a person
 * @param fleece a sheep in its wool: the fleece smoulders off first and keeps the fire off the skin under it a while
 * @param vital it burnt while alive: its burns are reddened (blood rushing in), which a body burnt after death never is
 * @param detail how finely what lies under the muscle is drawn (see {@link Anatomy.Detail}): {@code MIXED} but in the {@code resolution} demo
 * @param male a ram (from its UUID, see {@link Anatomy#male}): a sheep's trunk then holds his reproductive organs, not a ewe's
 * @param young a baby animal: a lamb is drawn with a lamb's anatomy
 */
public record Melt(float bare, float burn, float bone, boolean lit, boolean dead, long seed, boolean rotten, Anatomy.@Nullable Beast beast, boolean fleece,
		boolean vital, Anatomy.Detail detail, boolean male, boolean young) {
	public static final RenderStateDataKey<Melt> KEY = RenderStateDataKey.create(() -> "fluidgore:melt");
	/** How far the jaw of a burnt body hangs open, in radians. */
	static final float JAW = 0.38F;

	/** The jaw hanging open once the muscles that close it have burnt, in radians. */
	public float jaw() {
		return dead ? JAW * ramp(bare, 0.5F, 0.75F) : 0.0F;
	}

	/**
	 * How far the heat has drawn a body's limbs in (the boxer's pose of every burnt body), 0..1. It is the muscle
	 * shortening as it cooks, so it comes once the fire is through the skin and fat, not while the skin only reddens.
	 */
	public float flex() {
		return dead ? ramp(bare, 0.22F, 0.55F) : 0.0F;
	}

	/** Opened with no fire (the {@code resolution} demo): every layer as it is in life, nothing scorched, cooked or running. */
	public boolean raw() {
		return burn <= 0.0F && !lit;
	}

	/**
	 * How far the belly wall of a body has split from the heat and the bowel pushed out through it, 0..1 (see
	 * {@link Anatomy.Region#rupture}): it opens once the fire has dried the wall through, and the guts that come out
	 * char and shrink back as it burns on.
	 */
	public float rupture() {
		return dead ? ramp(burn, 0.35F, 0.55F) : 0.0F;
	}

	static float ramp(float x, float from, float to) {
		float t = Mth.clamp((x - from) / (to - from), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}
}
