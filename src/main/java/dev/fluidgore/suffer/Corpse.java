package dev.fluidgore.suffer;

import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.trigger.Fate;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The body of a slaughtered animal, kept on the client after the server removes the entity so it can die out on
 * screen, or for as long as the server keeps the entity (see {@link #kept}).
 *
 * <p>By default ({@link #vanilla}) it dies the vanilla way, made gory: red, over onto its side in vanilla's own
 * curve, the heart pumping its blood out while it lies still. With Death Throes on it dies as described below.
 *
 * <p>Every body goes down at once: the legs give way within a few ticks and it falls onto its side, as an animal does
 * when a fatal blow takes its brain or its blood pressure. Everything after that happens on the ground and follows
 * what slaughterhouse and veterinary staff see in a dying animal, compressed to a few seconds (see {@link Deaths}):
 * <ol>
 * <li><b>Tonic phase</b>: rigid, legs locked straight out, head arched back, not breathing.</li>
 * <li><b>Clonic phase</b>: involuntary paddling and kicking in bursts, uncoordinated, fading.</li>
 * <li><b>Agonal gasps</b>: a few irregular gasps, further and further apart; the chest heaves and the head lifts.</li>
 * <li><b>Fasciculation</b>: fine flickers of muscle, then nothing. The body lies limp.</li>
 * </ol>
 * The heart pumps blood out in slowing beats until it stops. How the animal was killed decides the mix:
 * <ul>
 * <li>{@link Kind#CONCUSSION}: a heavy blow to the head. The full rigid phase, then strong paddling. Silent: it is
 *     unconscious before it lands.</li>
 * <li>{@link Kind#EXSANGUINATION}: cut open. Blood jets with every heartbeat while it lies quiet and weakening; the
 *     paddling comes late, as the brain runs out of blood. A weak cry at first.</li>
 * <li>{@link Kind#SPINAL}: the spine broken (a fall, a heavy blow to the back). The hind end is dead and limp while
 *     the front legs go rigid (Schiff-Sherrington), with a weak paddle of the forelegs and laboured breathing.</li>
 * <li>{@link Kind#DECAPITATION}: beheaded. The strongest reflex kicking of all, from a spinal cord with no brain to
 *     stop it, and the neck pumping. No breaths and no sound.</li>
 * <li>{@link Kind#CHEST}: pierced through the lungs. Wet, gurgling gasps and blood from the mouth; weak kicks.</li>
 * <li>{@link Kind#TRAUMA}: a close blast. The body is limp at once: a few twitches and nothing else.</li>
 * <li>{@link Kind#BURN}: burnt. The heat draws the limbs in (the pugilistic pose) and they twitch; a weak cry.</li>
 * <li>{@link Kind#BEATEN}: beaten to death or a long fight. Disorganised, violent kicking and gurgling.</li>
 * </ul>
 * The lengths, the strength and rhythm of the kicks, the number and timing of gasps are all rolled from the body's
 * own dice ({@link #random}), seeded by the killing blow, so every player with the mod sees the same death.
 */
public final class Corpse {
	public enum Kind {
		CONCUSSION, EXSANGUINATION, SPINAL, DECAPITATION, CHEST, TRAUMA, BURN, BEATEN
	}

	/** Length of the fall onto its side, roughly, in ticks. */
	static final float FALL_TICKS = 12.0F;
	/** Vanilla's death takes this many ticks: red, over onto its side, then gone. A vanilla death is red as long. */
	static final int VANILLA_TICKS = 20;
	/**
	 * A body the server removes within this many ticks of its death went the vanilla way: the server does not keep
	 * bodies after all, so this client keeps it a while on its own.
	 */
	static final int KEEP_GRACE = 30;

	public final LivingEntity entity;
	public final Kind kind;
	public final Voice voice;
	/** Ticks while the legs give way, before the body goes over onto its side. */
	public final int collapseAt;
	/** Ticks of dying on the ground after the fall, until the body lies still. */
	public int throeTicks;
	public final int stillTicks;
	public final float seed;
	public int age;
	/** True once the server has removed the entity and only this client-side body remains. */
	public boolean unloaded;
	/** Age at which the server removed the entity, or -1. */
	int unloadedAt = -1;
	/** The server runs Fluid Gore and keeps this body until it is cleaned away (see {@link #kept}). */
	public boolean held;
	/** Died the vanilla way (see {@code GoreConfig.deathThroes}): over onto its side, then still. */
	public boolean vanilla;
	/** The vanilla fall has hit the ground. */
	boolean landed;
	/** Vanilla death: the side it goes over onto, +1 its right, -1 its left: away from the killing blow. */
	float fallSide = 1.0F;
	/** Vanilla death: ticks it stays on buckling legs before it goes over (an axe hacked into it). */
	int fallDelay;
	/** Vanilla death: degrees it turns as it goes over, along the swing (a sword). */
	float fallTwist;
	/** Vanilla death by a blade: the way the blow went, for the blood thrown off the slash; null otherwise. */
	@Nullable Vec3 slash;

	public final Ragdoll body;
	/** This body's own dice for its death, seeded by the killing blow: the same on every client (see Fate). */
	public final RandomSource random;
	boolean toppled;
	int nextFly;
	/** Ticks left of the red flash from a blow, as vanilla flashes a mob when it is hurt. */
	public int flash;
	/** Extra ticks on the ground, added by blows after the throes so a body being knocked about does not vanish. */
	int extraTicks;
	/** It bursts into pieces when it finally goes: the rare end, see {@link Deaths#popChance}. */
	public boolean pops;
	/** Its legs, measured from its model at death. */
	final ModelParts.Legs legs;
	SufferPose pose = SufferPose.IDLE;

	// The plan for this death, rolled by Deaths.begin. Times are ground ticks (since the fall began).
	/** End of the rigid phase. */
	public int tonicEnd;
	/** Start and end of the paddling. */
	public int clonicStart;
	public int clonicEnd;
	/** End of the last flickers of muscle. */
	int fasciculationEnd;
	/** Peak strength and starting speed of the paddling. */
	float kickPeak;
	float kickRate;
	/** The hind end is dead (a broken spine). */
	float hindLimp;
	float legSeed;
	/** Ground ticks of each agonal gasp, in order, and the next one due. */
	int[] gasps = new int[0];
	int nextGasp;
	/** Clock time (age) of the gasp being taken, and its strength. */
	float gaspAt = -1000.0F;
	float gaspStrength;
	/** When the heart stops (ground ticks), and the next beat (age). */
	int heartEnd;
	int nextBeat;
	/** The paddling comes in bursts: whether one is on, until when (age), and the kick as it is now (smoothed). */
	boolean burst;
	int burstUntil;
	float kickNow;
	/** Ground ticks until which it can still make a sound. */
	int voiceEnd;
	boolean lastBreathTaken;

	Corpse(LivingEntity entity, Kind kind, Voice voice, int collapseAt, int stillTicks, float seed) {
		this.entity = entity;
		this.kind = kind;
		this.voice = voice;
		this.collapseAt = collapseAt;
		this.stillTicks = stillTicks;
		this.seed = seed;
		this.random = Fate.body(entity);
		this.body = new Ragdoll(entity);
		this.legs = ModelParts.legs(entity.getId());
	}

	/** Still on its feet, the legs giving way. Only for the first few ticks. */
	public boolean upright() {
		return age < collapseAt;
	}

	public Personality self() {
		return voice.self();
	}

	/** Ticks since the fall began (negative while the legs are still giving way). */
	public int groundTicks() {
		return age - collapseAt;
	}

	/** Where the body is now (its feet), after everything the ragdoll has done to it. */
	public Vec3 position() {
		return new Vec3(body.x, body.y, body.z);
	}

	public float yaw() {
		return body.yaw;
	}

	/** The dying is over: the body lies still from now on. */
	public boolean lifeless() {
		return groundTicks() > throeTicks;
	}

	/** The server keeps this body: it stays until the server takes it away (cleaned up, or the player went away). */
	public boolean kept() {
		return held && (!unloaded || unloadedAt > KEEP_GRACE);
	}

	public boolean done() {
		if (kept()) {
			return unloaded;
		}

		return groundTicks() > throeTicks + stillTicks + extraTicks;
	}

	/** Keeps the body on screen for at least {@code ticks} more. */
	public void keepFor(int ticks) {
		extraTicks = Math.max(extraTicks, groundTicks() - throeTicks - stillTicks + ticks);
	}

	public SufferPose pose() {
		return pose;
	}
}
