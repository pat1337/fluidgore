package dev.fluidgore.material;

import io.netty.buffer.ByteBuf;
import net.minecraft.util.Mth;

/**
 * What has happened to one piece of a body: how much of its water is left, how hot it is now, how far it is cooked and
 * charred (both for good: cooling does not undo them), how much of what burns in it is left, whether it is alight,
 * how much loose fluid it still has to leave where it lands, and how whole it still is. It goes with the piece from
 * owner to owner: from the body to the limb cut off it, from the limb to the joints butchered out of it, from the body
 * to the scraps a blast makes of it, so a burnt piece never comes out fresh because another system now holds it.
 *
 * <p>For bone, {@link #charred} is the heat of the bone on {@code BurntBone}'s scale (scorched, charred, grey,
 * calcined), the same number cremation keeps for a whole skeleton.
 */
public final class Condition {
	/** The air, in degrees C. */
	public static final float AMBIENT = 20.0F;
	/** Bytes {@link #write} puts down. */
	public static final int BYTES = 7 * 4 + 1;
	/** Frost on a frozen surface. */
	private static final int FROST = 0xDCEAF8;

	/** Water left, as a share of what fresh tissue of its material holds, 0..1. */
	public float moisture = 1.0F;
	/** Degrees C. */
	public float temperature = AMBIENT;
	/** Protein set by heat, 0..1, for good. */
	public float cooked;
	/** Carbonised, 0..1, for good. */
	public float charred;
	/** What will still burn of what could, 0..1. */
	public float fuel = 1.0F;
	/** Loose blood (or cud) it still has to leave on the ground, in the units of a landing splat. */
	public float fluid;
	/** How whole it is, 0..1: char and calcining crack it, and a crumbled piece has lost what fell off. */
	public float integrity = 1.0F;
	public boolean burning;

	/** A fresh piece holding {@code fluid} to leave where it lands. */
	public static Condition fresh(float fluid) {
		Condition c = new Condition();
		c.fluid = fluid;
		return c;
	}

	public Condition copy() {
		Condition c = new Condition();
		c.set(this);
		return c;
	}

	public void set(Condition o) {
		moisture = o.moisture;
		temperature = o.temperature;
		cooked = o.cooked;
		charred = o.charred;
		fuel = o.fuel;
		fluid = o.fluid;
		integrity = o.integrity;
		burning = o.burning;
	}

	public void reset(float fluid) {
		moisture = 1.0F;
		temperature = AMBIENT;
		cooked = 0.0F;
		charred = 0.0F;
		fuel = 1.0F;
		this.fluid = fluid;
		integrity = 1.0F;
		burning = false;
	}

	/**
	 * Takes on what a body (or a piece) it came from had been through, keeping whatever is worse of the two: a scrap
	 * of a burnt body is at least as burnt, as dry and as hot as the body, and alight if it was.
	 */
	public void inherit(Condition from) {
		moisture = Math.min(moisture, from.moisture);
		temperature = Math.max(temperature, from.temperature);
		cooked = Math.max(cooked, from.cooked);
		charred = Math.max(charred, from.charred);
		fuel = Math.min(fuel, from.fuel);
		// A dried body has little loose blood left to give its pieces.
		fluid *= Mth.clamp(from.moisture, 0.0F, 1.0F);
		integrity = Math.min(integrity, from.integrity);
		burning |= from.burning && fuel > 0.05F;
	}

	/** Seared by a flash of heat (a fireball, the fire it came out of): {@code amount} 0..1 of char, dried with it. */
	public void sear(float amount) {
		charred = Math.max(charred, amount);
		cooked = Math.max(cooked, Math.min(1.0F, amount * 1.5F));
		moisture = Math.min(moisture, 1.0F - 0.8F * amount);
		fuel = Math.min(fuel, 1.0F - 0.6F * amount);
		fluid *= 1.0F - amount;
		integrity = Math.min(integrity, 1.0F - 0.5F * amount);
	}

	/** Hot enough to hurt to touch. */
	public boolean hot() {
		return temperature > 60.0F;
	}

	/**
	 * The colour heat (or cold) has given it, as a multiplier on its own colour or texture: cooked tissue goes the dull colour of
	 * its material cooked through, char takes it to brown-black; bone goes through {@code BurntBone}'s stages.
	 */
	public int tint(Material material) {
		int heat;
		if (material.bone()) {
			heat = dev.fluidgore.wound.BurntBone.tint(charred);
		} else {
			int cookedTint = lerp(0xFFFFFF, material.cooked(), cooked);
			heat = charred <= 0.0F ? cookedTint : lerp(cookedTint, 0x2A201B, Math.min(1.0F, charred * 1.1F));
		}

		// Below freezing a pale bloom of frost over it, gone again as it warms (it does nothing for good).
		return temperature < 0.0F ? lerp(heat, FROST, Math.min(0.65F, -temperature / 50.0F)) : heat;
	}

	public void write(ByteBuf buf) {
		buf.writeFloat(moisture);
		buf.writeFloat(temperature);
		buf.writeFloat(cooked);
		buf.writeFloat(charred);
		buf.writeFloat(fuel);
		buf.writeFloat(fluid);
		buf.writeFloat(integrity);
		buf.writeBoolean(burning);
	}

	/** Reads what {@link #write} put down; numbers that are not, or out of range, are kept in range. */
	public void read(ByteBuf buf) {
		moisture = unit(buf.readFloat());
		float t = buf.readFloat();
		temperature = Float.isFinite(t) ? Mth.clamp(t, -50.0F, 1500.0F) : AMBIENT;
		cooked = unit(buf.readFloat());
		charred = unit(buf.readFloat());
		fuel = unit(buf.readFloat());
		float f = buf.readFloat();
		fluid = Float.isFinite(f) ? Mth.clamp(f, 0.0F, 1.0F) : 0.0F;
		integrity = unit(buf.readFloat());
		burning = buf.readBoolean();
	}

	private static float unit(float f) {
		return Float.isFinite(f) ? Mth.clamp(f, 0.0F, 1.0F) : 0.0F;
	}

	static int lerp(int from, int to, float t) {
		t = Mth.clamp(t, 0.0F, 1.0F);
		int r = Math.round(Mth.lerp(t, from >> 16 & 0xFF, to >> 16 & 0xFF));
		int g = Math.round(Mth.lerp(t, from >> 8 & 0xFF, to >> 8 & 0xFF));
		int b = Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));
		return r << 16 | g << 8 | b;
	}
}
