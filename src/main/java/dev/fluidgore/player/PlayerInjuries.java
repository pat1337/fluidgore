package dev.fluidgore.player;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.trigger.EntityFilter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What the local player feels of their own injuries: blood thrown across the screen from the side a blow came from
 * ({@link HurtScreen} draws it), the view knocked aside by a heavy blow, a red edge closing in near death with the
 * heart pounding, a slow sway as the body fails, and a hitch in the step on a crippled leg. All of it is on this
 * screen only; health, damage and movement are the server's and are never touched.
 */
public final class PlayerInjuries {
	/** What hurt the player, for where the blood goes. */
	public enum Cause {
		/** A blow or a shot from somewhere: the blood comes from that side. */
		STRUCK,
		/** A blast: blood all round the edges. */
		BLAST,
		/** A fall: a splash across the bottom of the view. */
		FALL,
		/** Drowning, starving, burning, poison: no blood, only the red. */
		OTHER
	}

	/** One blot of blood on the screen, positioned in screen fractions; it runs down and dries as it ages. */
	public static final class Splat {
		public final float x;
		public final float y;
		public final int tile;
		public final float size;
		public final int drip;
		public final float dripX;
		public final int life;
		public final int rgb;
		public int age;

		Splat(float x, float y, int tile, float size, int drip, float dripX, int life, int rgb) {
			this.x = x;
			this.y = y;
			this.tile = tile;
			this.size = size;
			this.drip = drip;
			this.dripX = dripX;
			this.life = life;
			this.rgb = rgb;
		}
	}

	private static final int MAX_SPLATS = 24;
	/** A blow this hard gives the full stagger. */
	private static final float STAGGER_DAMAGE = 8.0F;
	/** Degrees of roll and pitch at a full stagger. */
	private static final float STAGGER_ROLL = 9.0F;
	private static final float STAGGER_PITCH = 5.0F;

	private final RandomSource random = RandomSource.create();
	private final List<Splat> splats = new ArrayList<>();
	private int tick;
	/** A red flash over the screen edges after a blow, 0..1; fades quickly. */
	private float flash;
	private float flashO;
	/** The heartbeat: ticks until the next beat, and the tick of the last one. */
	private int beatIn;
	private int lastBeat = -1000;
	/** The current stagger: its roll (signed by side) and pitch, and when the blow landed. */
	private float staggerRoll;
	private float staggerPitch;
	private int staggerAt = -1000;
	/** For demos: a share of health to show instead of the real one, until {@link #fakeUntil}. */
	private float fakeHealth = Float.NaN;
	private int fakeUntil;

	public List<Splat> splats() {
		return splats;
	}

	/**
	 * The local player was hurt for {@code damage}. {@code from} is where the blow came from (the attacker's eyes, the
	 * blast), or null if nothing struck them.
	 */
	public void onHurt(LocalPlayer player, float damage, @Nullable Vec3 from, Cause cause) {
		GoreConfig config = GoreConfig.get();
		if (!config.goreLevel.enabled()) {
			return;
		}

		float strength = Mth.clamp(damage / STAGGER_DAMAGE, 0.15F, 1.0F);
		flash = Math.max(flash, 0.35F + 0.65F * strength);
		// Where it came from, relative to the view: side -1 (left) .. 1 (right), front -1 (behind) .. 1 (ahead).
		float side = 0.0F;
		float front = 1.0F;
		if (from != null) {
			Vec3 d = from.subtract(player.getEyePosition());
			double flat = Math.sqrt(d.x * d.x + d.z * d.z);
			if (flat > 1.0E-4) {
				float yaw = player.getYRot() * Mth.DEG_TO_RAD;
				// Facing (-sin, cos); the right hand points along (-cos, -sin).
				side = (float) ((-Mth.cos(yaw) * d.x - Mth.sin(yaw) * d.z) / flat);
				front = (float) ((-Mth.sin(yaw) * d.x + Mth.cos(yaw) * d.z) / flat);
			}
		}

		if (cause == Cause.STRUCK || cause == Cause.BLAST) {
			// Knocked away from the blow: the head rolls off to the far side and snaps back (or forward, from behind).
			staggerRoll = -Math.signum(side == 0.0F ? random.nextFloat() - 0.5F : side) * STAGGER_ROLL * strength * (0.4F + 0.6F * Math.abs(side));
			staggerPitch = -front * STAGGER_PITCH * strength * (cause == Cause.BLAST ? 1.6F : 1.0F);
			staggerAt = tick;
		} else if (cause == Cause.FALL) {
			staggerRoll = (random.nextFloat() - 0.5F) * STAGGER_ROLL * strength;
			staggerPitch = STAGGER_PITCH * strength;
			staggerAt = tick;
		}

		if (!config.hurtScreen || cause == Cause.OTHER || !EntityFilter.affects(player) || EntityFilter.isBloodless(player)) {
			return;
		}

		int rgb = EntityFilter.bloodColor(player);
		int count = Math.min(6, 1 + Math.round(damage / 3.0F * config.goreLevel.puddles)) + (cause == Cause.BLAST ? 3 : 0);
		for (int i = 0; i < count; i++) {
			float x;
			float y;
			if (cause == Cause.FALL) {
				x = 0.08F + random.nextFloat() * 0.84F;
				y = 0.8F + random.nextFloat() * 0.18F;
			} else if (cause == Cause.BLAST) {
				float edge = random.nextFloat() * 4.0F;
				float along = edge % 1.0F;
				x = edge < 1.0F ? along : edge < 2.0F ? 0.95F : edge < 3.0F ? 1.0F - along : 0.05F;
				y = edge < 1.0F ? 0.05F : edge < 2.0F ? along : edge < 3.0F ? 0.95F : 1.0F - along;
			} else {
				// The side it came from, out towards the edge; from behind, round the rim.
				float reach = front < 0.0F ? 0.42F : 0.34F;
				x = 0.5F + side * reach + (float) random.nextGaussian() * 0.08F + (front < 0.0F ? Math.signum(side) * 0.06F : 0.0F);
				y = 0.2F + random.nextFloat() * 0.55F;
			}

			add(Mth.clamp(x, 0.0F, 1.0F), Mth.clamp(y, 0.0F, 1.0F), 0.8F + strength * 0.5F + random.nextFloat() * 0.4F, rgb);
		}
	}

	private void add(float x, float y, float size, int rgb) {
		if (splats.size() >= MAX_SPLATS) {
			splats.removeFirst();
		}

		// Most run; a few are thrown too thin to.
		int drip = random.nextInt(5) < 4 ? random.nextInt(4) : -1;
		splats.add(new Splat(x, y, random.nextInt(4), size, drip, (random.nextFloat() - 0.5F) * 0.5F, 70 + random.nextInt(60), rgb));
	}

	public void tick(Minecraft mc) {
		tick++;
		flashO = flash;
		flash *= 0.82F;
		splats.removeIf(s -> ++s.age >= s.life);
		LocalPlayer player = mc.player;
		if (player == null || !player.isAlive()) {
			// Dead, or no one to be: start the next life clean.
			if (player != null && player.isDeadOrDying()) {
				splats.clear();
			}

			beatIn = 0;
			return;
		}

		float weak = weak(player);
		GoreConfig config = GoreConfig.get();
		if (weak > 0.0F && config.lowHealthHeartbeat && config.goreLevel.enabled() && !mc.isPaused()) {
			if (--beatIn <= 0) {
				// It quickens as the end comes: about 70 a minute at a third of the health, 140 on the last half-heart.
				beatIn = Math.round(Mth.lerp(weak, 26.0F, 9.0F));
				lastBeat = tick;
				mc.getSoundManager().play(new SimpleSoundInstance(SoundEvents.WARDEN_HEARTBEAT.location(), SoundSource.PLAYERS,
						0.25F + 0.55F * weak, 0.8F + 0.25F * weak, SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE,
						0.0, 0.0, 0.0, true));
			}
		} else {
			beatIn = 0;
		}
	}

	/** How near death the local player is, 0..1 (see {@link Injury#weak}), or the demo's stand-in. */
	public float weak(LocalPlayer player) {
		if (tick < fakeUntil) {
			return Injury.weak(fakeHealth);
		}

		return Injury.weak(player.getHealth() / Math.max(1.0F, player.getMaxHealth()));
	}

	/** For a demo: show {@code health} (a share of full) instead of the real health for {@code ticks}. */
	public void fakeHealth(float health, int ticks) {
		fakeHealth = health;
		fakeUntil = tick + ticks;
	}

	/** How hard the screen edge is reddened this frame, 0..1: the flash of a blow, and near death a pulse with each beat. */
	public float redness(LocalPlayer player, float partialTick) {
		float hit = Mth.lerp(partialTick, flashO, flash);
		GoreConfig config = GoreConfig.get();
		float weak = config.lowHealthHeartbeat ? weak(player) : 0.0F;
		float since = tick - lastBeat + partialTick;
		float beat = since < 0.0F ? 0.0F : (float) Math.exp(-since / 3.0F);
		// It comes in quickly once health is low, then deepens slowly to the end.
		return Mth.clamp(Math.max(hit * 0.7F, Mth.sqrt(weak) * (0.5F + 0.4F * beat)), 0.0F, 1.0F);
	}

	/** The stagger and the sway near death, laid onto the view after vanilla's hurt tilt. */
	public void staggerView(PoseStack poseStack, LocalPlayer player, float partialTick) {
		float scale = GoreConfig.get().staggerPercent / 100.0F;
		if (scale <= 0.0F || !GoreConfig.get().goreLevel.enabled()) {
			return;
		}

		float t = tick + partialTick;
		float since = t - staggerAt;
		// A quick knock over two ticks, then it eases back.
		float shape = since < 0.0F || since > 40.0F ? 0.0F : since < 2.0F ? since / 2.0F : (float) Math.exp(-(since - 2.0F) / 6.0F);
		float weak = weak(player);
		float roll = staggerRoll * shape + Mth.sin(t * 0.045F) * 2.5F * weak;
		float pitch = staggerPitch * shape + Mth.sin(t * 0.031F + 1.0F) * 1.2F * weak;
		if (roll != 0.0F || pitch != 0.0F) {
			poseStack.rotateDegrees(Axis.ZP, roll * scale);
			poseStack.rotateDegrees(Axis.XP, pitch * scale);
		}
	}

	/**
	 * A crippled leg in the local player's own view: the step onto it drops further and tips the view towards it.
	 * {@code walk} and {@code bob} are vanilla's view-bobbing walk distance and bob amount.
	 */
	public void limpView(PoseStack poseStack, LocalPlayer player, float walk, float bob) {
		Injury injury = Injury.of(player);
		if (injury == null || bob <= 0.0F) {
			return;
		}

		float s = Mth.sin(walk * Mth.PI);
		// Vanilla sways the view right while sin > 0: the right foot is down.
		float dip = bob * (injury.rightLeg() * Math.max(0.0F, s) + injury.leftLeg() * Math.max(0.0F, -s)) * 1.5F;
		if (dip > 0.0F) {
			poseStack.translate(0.0F, -dip, 0.0F);
			poseStack.rotateDegrees(Axis.ZP, (s > 0.0F ? 1.0F : -1.0F) * dip * 25.0F);
		}
	}

	public void clear() {
		splats.clear();
		flash = flashO = 0.0F;
		staggerAt = lastBeat = -1000;
		beatIn = 0;
		fakeUntil = 0;
	}
}
