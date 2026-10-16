package dev.fluidgore.dismember;

import dev.fluidgore.suffer.Voice;
import dev.fluidgore.wound.WoundSet;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A severed body part: a client-only render object (not an entity) with simple rigid-body motion. It is drawn
 * with a private copy of the entity's model part and the entity's own texture.
 */
public final class GorePart {
	public final ModelPart model;
	public final Identifier texture;
	/** Centre of the part's cubes in its own space (blocks); the part tumbles around this point. */
	public final Vector3f localCenter;
	/** Half extents of the part's cubes in its own space (blocks), already multiplied by {@link #scale}. */
	public final Vector3f halfExtents;
	public final float scale;
	public final int bloodRgb;
	/**
	 * Names this piece the same on every client: drawn from the dice of the hit or the cut that made it (see
	 * {@link dev.fluidgore.trigger.Fate#key}), so a cut on it can be shown to the other players (see
	 * {@link dev.fluidgore.net.GoreSync}). Set when it is added.
	 */
	public long key;
	/** Ticks this client still sends the piece's motion to the others: its player moved it (see {@link dev.fluidgore.net.PieceSync}). */
	public int streamFor;
	/** Ticks it still hangs where another player carries it, unless their game says otherwise first. */
	public int remoteHeld;
	/** The server has been told what this piece is (see {@link dev.fluidgore.net.Evidence#piece}). */
	public boolean uploaded;
	public int lifetime;
	/** Colour multiplied into the part's texture: white, or the char of a body that burnt. */
	public int tint = 0xFFFFFF;
	/** How far the piece has rotted, in decay-days (see {@link dev.fluidgore.rot.Decay}), and whether it is drying out instead. */
	public float rot;
	public boolean arid;

	public double x, y, z;
	public double prevX, prevY, prevZ;
	public double vx, vy, vz;
	public final Quaternionf rotation = new Quaternionf();
	public final Quaternionf prevRotation = new Quaternionf();
	/** Angular velocity as an axis (unit) and speed in radians per tick. */
	public final Vector3f spinAxis = new Vector3f(1.0F, 0.0F, 0.0F);
	public float spinSpeed;
	/** Orientation the part eases into once it comes to rest; null while still moving. */
	public @Nullable Quaternionf restTarget;
	public float bleedLeft;
	public boolean landed;
	public boolean resting;
	public int restTicks;
	public int age;
	public int light;
	/** For severed heads: the animal's voice for its last moans (null for other parts or when unavailable). */
	public @Nullable Voice voice;
	/** For severed heads: how long the head keeps twitching, in ticks. */
	public int twitchTicks;
	/** Vertical squash after being stomped on: 1 = intact. */
	public float squash = 1.0F;
	/** Being carried by the player (see {@link PartInteraction}). */
	public boolean held;
	/** Wounds carried by the piece: those cut into it before it came off, and the raw face where it was joined. */
	public @Nullable WoundSet wounds;
	/** Faces of a diced piece that are raw cut meat: bit {@code axis * 2}, plus one for the positive side. */
	public int rawFaces;
	/** Blows taken by a bone part; it breaks apart on the second. */
	public int hits;
	/** Broke apart this tick; removed by the manager. */
	public boolean shattered;
	/** Ticks since the part last splatted on impact, to avoid a splat every tick while sliding. */
	public int lastSplat = -100;
	/** For an enderman's pieces: ticks until the piece next blinks somewhere else. */
	public int blinkIn;
	/** Armour the part still wears (see {@link ArmorPieces}); null or empty for none. */
	public @Nullable List<ArmorPieces.Piece> armor;

	public GorePart(ModelPart model, Identifier texture, Vector3f localCenter, Vector3f halfExtents, float scale, int bloodRgb, int lifetime) {
		this.model = model;
		this.texture = texture;
		this.localCenter = localCenter;
		this.halfExtents = halfExtents;
		this.scale = scale;
		this.bloodRgb = bloodRgb;
		this.lifetime = lifetime;
	}

	/** A piece of an enderman: it keeps teleporting after it is cut off. */
	public boolean ender() {
		return bloodRgb == dev.fluidgore.config.BloodColor.ENDER.rgb;
	}

	/** Made of bone: breaks instead of splattering. */
	public boolean bone() {
		return bloodRgb == dev.fluidgore.blood.BoneEmitter.BONE;
	}

	public float alpha() {
		int left = lifetime - age;
		return left >= 40 ? 1.0F : Math.max(0.0F, left / 40.0F);
	}
}
