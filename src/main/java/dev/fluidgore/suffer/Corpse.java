package dev.fluidgore.suffer;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

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
 * </ul>
 */
public final class Corpse {
	public enum Kind {
		QUICK, NORMAL, BRUTAL, HEADLESS_RUN
	}

	/** Length of the vanilla tip-over animation in ticks. */
	static final float FALL_TICKS = 20.0F;

	public final LivingEntity entity;
	public final Kind kind;
	public final Voice voice;
	public final int runTicks;
	public final int throeTicks;
	public final int stillTicks;
	public final float seed;
	public int age;
	/** True once the server has removed the entity and only this client-side body remains. */
	public boolean unloaded;

	float bodyRot;
	float bodyRotO;
	float walkPos;
	float walkPosO;
	float walkSpeed;
	double fallSpeed;
	/** Current spasm strength; set to 1 by a sudden agonal spasm and decays quickly. */
	float burst;
	/** Tick at which the body starts falling over (after the headless run, otherwise immediately). */
	int collapseAt;
	SufferPose pose = new SufferPose(0, 0, 0, 0, 0, 0, 0, 0);

	Corpse(LivingEntity entity, Kind kind, Voice voice, int runTicks, int throeTicks, int stillTicks, float seed) {
		this.entity = entity;
		this.kind = kind;
		this.voice = voice;
		this.runTicks = runTicks;
		this.throeTicks = throeTicks;
		this.stillTicks = stillTicks;
		this.seed = seed;
		this.bodyRot = this.bodyRotO = entity.yBodyRot;
		this.collapseAt = kind == Kind.HEADLESS_RUN ? runTicks : 0;
	}

	public boolean running() {
		return kind == Kind.HEADLESS_RUN && age < runTicks;
	}

	/** Ticks spent on the ground so far (negative while still running). */
	int groundTicks() {
		return age - collapseAt;
	}

	public boolean done() {
		return groundTicks() > Math.round(FALL_TICKS) + throeTicks + stillTicks;
	}

	/** Value for the vanilla death tip-over: 0 = upright, 20 = lying on its side. */
	public float deathTime(float partialTick) {
		float t = groundTicks() + partialTick;
		return t <= 0.0F ? 0.0F : Math.min(FALL_TICKS, 1.0F + t);
	}

	public float bodyRot(float partialTick) {
		return Mth.rotLerp(partialTick, bodyRotO, bodyRot);
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
