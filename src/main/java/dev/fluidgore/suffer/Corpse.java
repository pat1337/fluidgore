package dev.fluidgore.suffer;

import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.trigger.Fate;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The body of a slaughtered animal, kept on the client after the server removes the entity so its death can
 * last as long as the way it was killed warrants.
 *
 * <p>How animals really die, chosen by how the animal was killed and who it is (see {@link Deaths#choose}):
 * <ul>
 * <li>{@link Kind#STUNNED}: a heavy blow to the head. It drops where it stands like a sack, lies rigid with its legs
 *     locked out and its head arched back, then the legs paddle on their own and slowly stop. Not a sound.</li>
 * <li>{@link Kind#BLEED_OUT}: an opened artery. It stands swaying while its blood pumps out with every heartbeat,
 *     the beats slowing, into a pool that keeps growing; the knees go, it sinks onto its chest, then onto its side.</li>
 * <li>{@link Kind#BOLT}: the death run. It bolts blind, screaming and bleeding, until it crashes nose first.</li>
 * <li>{@link Kind#DOWNER}: down and unable to rise. Front knees first, then the hind end, it lies on its chest with
 *     its head turned back against its flank, and keeps trying to get up, groaning, until it rolls onto its side.</li>
 * <li>{@link Kind#BROKEN_BACK}: the spine gone. The hind end drops into a dog-sit and it drags itself on its front
 *     legs, bellowing and smearing blood behind it, until the front gives out too.</li>
 * <li>{@link Kind#CHOKING}: blood in the lungs. Neck stretched low, it coughs up froth and blood with every heave,
 *     gasps, sinks onto its chest and coughs there until it drowns.</li>
 * <li>{@link Kind#CURL}: gut-shot. It hunches over the wound, whimpering and shaking, and folds before it falls.</li>
 * <li>{@link Kind#BRUTAL}: beaten with bare hands, burnt, or a long drawn-out fight. A long wail sagging into gurgles,
 *     and agonal kicking with sudden spasms that can go on for many seconds.</li>
 * <li>{@link Kind#CIRCLING}: the brain hurt. It walks in tight circles with its head cocked, crashes over, rolls onto
 *     its back kicking at the sky, and stiffens there, legs up.</li>
 * </ul>
 * And the rest, chosen by the killing alone or for the rare body that bursts at the end ({@link #pops}):
 * <ul>
 * <li>{@link Kind#HEADLESS_RUN}: beheaded. Spinal reflexes keep the body running blindly while the neck pumps
 *     blood, then it collapses and twitches. Silent: there is no head left to scream with.</li>
 * <li>{@link Kind#QUICK}: one clean, heavy blow or a close blast. A short violent spasm and a cut-off cry.</li>
 * <li>{@link Kind#NORMAL}: screams, then kicking that dies away over a few seconds.</li>
 * <li>{@link Kind#COLLAPSE}: a stoic animal that does not run. Its legs buckle slowly, it goes down on its knees
 *     and then onto its side, and breathes laboured breaths until the last one.</li>
 * </ul>
 *
 * <p>Every one of them can be struck while it dies, and answers in its own way (see {@link Deaths#struck}).
 */
public final class Corpse {
	public enum Kind {
		QUICK, NORMAL, BRUTAL, HEADLESS_RUN, BOLT, COLLAPSE, CURL, STUNNED, BLEED_OUT, DOWNER, BROKEN_BACK, CHOKING, CIRCLING;

		/** Played out by {@link Deaths} rather than by the older throes in {@link Suffering}. */
		boolean scripted() {
			return ordinal() >= STUNNED.ordinal();
		}
	}

	/** Length of the vanilla tip-over animation in ticks. */
	static final float FALL_TICKS = 20.0F;

	public final LivingEntity entity;
	public final Kind kind;
	public final Voice voice;
	/**
	 * Ticks before the body goes over onto its side: a headless run or a bolt, the buckling of a collapse or a curl, or
	 * for the scripted deaths everything it does on its feet and on its chest first.
	 */
	public final int runTicks;
	public final int throeTicks;
	public final int stillTicks;
	public final float seed;
	public int age;
	/** True once the server has removed the entity and only this client-side body remains. */
	public boolean unloaded;

	public final Ragdoll body;
	/** This body's own dice for its throes, seeded by the killing blow: the same on every client (see Fate). */
	public final RandomSource random;
	float walkPos;
	float walkPosO;
	float walkSpeed;
	/** Tick at which the body starts falling over (after the headless run, otherwise immediately). */
	int collapseAt;
	boolean toppled;
	/** Next tick (on the ground) a laboured breath is due, and whether the last breath has been taken. */
	int nextBreath;
	boolean lastBreathTaken;
	int nextFly;
	/** Remaining ticks of a deliberate turn away from a wall, and its direction. */
	int turnTicks;
	float turnDir;
	/** Ticks left of the red flash from a blow, as vanilla flashes a mob when it is hurt. */
	public int flash;
	/** Extra ticks on the ground, added by blows after the throes so a body being knocked about does not vanish. */
	int extraTicks;
	/** It bursts into pieces when it finally goes: the rare end, see {@link Deaths#popChance}. */
	public boolean pops;
	/** Its legs, measured from its model at death. */
	final ModelParts.Legs legs;
	/** Where a scripted death is in its own story; see {@link Deaths}. */
	int phaseAt;
	int nextBeat;
	int nextCough;
	int attempts;
	int attemptAt = -100;
	float circleDir;
	SufferPose pose = SufferPose.IDLE;

	Corpse(LivingEntity entity, Kind kind, Voice voice, int runTicks, int throeTicks, int stillTicks, float seed) {
		this.entity = entity;
		this.kind = kind;
		this.voice = voice;
		this.runTicks = runTicks;
		this.throeTicks = throeTicks;
		this.stillTicks = stillTicks;
		this.seed = seed;
		this.random = Fate.body(entity);
		this.body = new Ragdoll(entity);
		this.collapseAt = runTicks;
		this.legs = ModelParts.legs(entity.getId());
	}

	public boolean running() {
		return (kind == Kind.HEADLESS_RUN || kind == Kind.BOLT) && age < runTicks;
	}

	/** Not yet gone over onto its side. */
	public boolean upright() {
		return age < collapseAt;
	}

	/** The legs are giving out but the body is still on its feet. */
	public boolean buckling() {
		return (kind == Kind.COLLAPSE || kind == Kind.CURL) && age < runTicks;
	}

	/** How far into the run or the buckling the body is, 0..1 (1 once it has gone down). */
	public float prefall() {
		return runTicks <= 0 ? 1.0F : Math.min(1.0F, age / (float) runTicks);
	}

	public Personality self() {
		return voice.self();
	}

	/** Ticks spent on the ground so far (negative while still running). */
	int groundTicks() {
		return age - collapseAt;
	}

	/** Where the body is now (its feet), after everything the ragdoll has done to it. */
	public Vec3 position() {
		return new Vec3(body.x, body.y, body.z);
	}

	public float yaw() {
		return body.yaw;
	}

	/** The throes are over: the body lies still from now on. */
	public boolean lifeless() {
		return groundTicks() > Math.round(FALL_TICKS) + throeTicks;
	}

	public boolean done() {
		return groundTicks() > Math.round(FALL_TICKS) + throeTicks + stillTicks + extraTicks;
	}

	/** Keeps the body on screen for at least {@code ticks} more. */
	void keepFor(int ticks) {
		extraTicks = Math.max(extraTicks, groundTicks() - Math.round(FALL_TICKS) - throeTicks - stillTicks + ticks);
	}

	public float walkPos(float partialTick) {
		return Mth.lerp(partialTick, walkPosO, walkPos);
	}

	public float walkSpeed() {
		return walkSpeed;
	}

	public SufferPose pose() {
		return pose;
	}
}
