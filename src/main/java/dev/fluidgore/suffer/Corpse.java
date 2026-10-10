package dev.fluidgore.suffer;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The body of a slaughtered animal, kept on the client after the server removes the entity so its death can
 * last as long as the way it was killed warrants.
 *
 * <ul>
 * <li>{@link Kind#QUICK}: one clean, heavy blow. A short violent spasm and a cut-off cry.</li>
 * <li>{@link Kind#NORMAL}: an ordinary kill. Screams, then kicking that dies away over a few seconds.</li>
 * <li>{@link Kind#BRUTAL}: beaten with bare hands, hit many times, or a long drawn-out fight. A long wail sagging
 *     into gurgles, and agonal kicking with sudden spasms that can go on for many seconds.</li>
 * <li>{@link Kind#HEADLESS_RUN}: beheaded. Spinal reflexes keep the body running blindly while the neck pumps
 *     blood, then it collapses and twitches. Silent: there is no head left to scream with.</li>
 * <li>{@link Kind#BOLT}: a frantic animal that bolts, screaming and bleeding, until its legs give out.</li>
 * <li>{@link Kind#COLLAPSE}: a stoic animal that does not run. Its legs buckle slowly, it goes down on its knees
 *     and then onto its side, and breathes laboured breaths until the last one.</li>
 * <li>{@link Kind#CURL}: gut-shot. It hunches over the wound, whimpering and shaking, and folds before it falls.</li>
 * </ul>
 *
 * <p>Which one an animal gets depends on how it was hit and on its {@link Personality}, so the same cow killed
 * the same way twice still dies differently from the next cow.
 */
public final class Corpse {
	public enum Kind {
		QUICK, NORMAL, BRUTAL, HEADLESS_RUN, BOLT, COLLAPSE, CURL
	}

	/** Length of the vanilla tip-over animation in ticks. */
	static final float FALL_TICKS = 20.0F;

	public final LivingEntity entity;
	public final Kind kind;
	public final Voice voice;
	/** Ticks before the body goes down: a headless run or a bolt, or the buckling of a collapse or a curl. */
	public final int runTicks;
	public final int throeTicks;
	public final int stillTicks;
	public final float seed;
	public int age;
	/** True once the server has removed the entity and only this client-side body remains. */
	public boolean unloaded;

	public final Ragdoll body;
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
	SufferPose pose = new SufferPose(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

	Corpse(LivingEntity entity, Kind kind, Voice voice, int runTicks, int throeTicks, int stillTicks, float seed) {
		this.entity = entity;
		this.kind = kind;
		this.voice = voice;
		this.runTicks = runTicks;
		this.throeTicks = throeTicks;
		this.stillTicks = stillTicks;
		this.seed = seed;
		this.body = new Ragdoll(entity);
		this.collapseAt = runTicks;
	}

	public boolean running() {
		return (kind == Kind.HEADLESS_RUN || kind == Kind.BOLT) && age < runTicks;
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
