package dev.fluidgore.player;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.trigger.EntityFilter;
import dev.fluidgore.trigger.PressureVessel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
	/** Deafness after a blast: how much of the hearing went (0..1), when, and how many ticks it takes to come back. */
	private float deaf;
	private int deafAt = -1000;
	private int deafTicks = 1;
	/** The ringing in the ears: how loud at first, and for how many ticks it lasts. */
	private float ring;
	private int ringTicks = 1;
	/** Noise dose from a steady loud sound (a leaking tank's jet): 1 is enough to lose much of the hearing for a while. */
	private float dose;
	private @Nullable Tinnitus tinnitus;
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

	/**
	 * A pressure vessel burst with the local player {@code kPa} of peak overpressure away, and {@code hits} of its
	 * fragments in them. What a real blast that size does at that pressure: the view thrown about, deafness and ringing,
	 * burst eardrums and torn lungs bleeding onto the screen, and a line in chat that says it plainly. Only the view and
	 * the sound are touched; the damage is the TNT's.
	 */
	public void burst(LocalPlayer player, PressureVessel.Vessel vessel, float kPa, int hits) {
		GoreConfig config = GoreConfig.get();
		if (!config.goreLevel.enabled()) {
			return;
		}

		double metres = player.getBoundingBox().getCenter().distanceTo(vessel.centre);
		if (!vessel.real()) {
			// A Cobble Tank works like TNT: no line in chat, what it does to you is all there is.
			report(player, vessel, metres, kPa, hits);
		}

		if (kPa < PressureVessel.RINGING_KPA && hits == 0) {
			return;
		}

		// The view is thrown away from the vessel, harder the bigger the pressure (20 kPa is a full stagger).
		float strength = Mth.clamp(kPa / 20.0F, 0.2F, 2.5F);
		Vec3 d = vessel.centre.subtract(player.getEyePosition());
		double flat = Math.sqrt(d.x * d.x + d.z * d.z);
		float yaw = player.getYRot() * Mth.DEG_TO_RAD;
		float side = flat > 1.0E-4 ? (float) ((-Mth.cos(yaw) * d.x - Mth.sin(yaw) * d.z) / flat) : 0.0F;
		float front = flat > 1.0E-4 ? (float) ((-Mth.sin(yaw) * d.x + Mth.cos(yaw) * d.z) / flat) : 1.0F;
		staggerRoll = -Math.signum(side == 0.0F ? random.nextFloat() - 0.5F : side) * STAGGER_ROLL * strength * (0.5F + 0.5F * Math.abs(side));
		staggerPitch = -front * STAGGER_PITCH * strength * 1.6F;
		staggerAt = tick;

		if (config.blastDeafness && kPa >= PressureVessel.RINGING_KPA) {
			// Stunned hearing: some of it for a loud bang, nearly all of it with the eardrums gone, back over half a minute or more.
			float depth = kPa >= PressureVessel.LUNG_KPA ? 0.97F : kPa >= PressureVessel.EARDRUM_KPA ? 0.9F
					: Mth.lerp((kPa - PressureVessel.RINGING_KPA) / (PressureVessel.EARDRUM_KPA - PressureVessel.RINGING_KPA), 0.45F, 0.8F);
			int ticks = Math.round(20 * (kPa >= PressureVessel.LUNG_KPA ? 45 : kPa >= PressureVessel.EARDRUM_KPA ? 30 : 8 + 14 * depth));
			if (depth >= deafness()) {
				deaf = depth;
				deafAt = tick;
				deafTicks = ticks;
				ring = Mth.clamp(0.25F + kPa / 200.0F, 0.25F, 0.6F);
				ringTicks = Math.round(ticks * 1.2F);
			}

			Minecraft mc = Minecraft.getInstance();
			if (tinnitus == null || !mc.getSoundManager().isActive(tinnitus)) {
				tinnitus = new Tinnitus();
				mc.getSoundManager().play(tinnitus);
			}
		}

		if (!config.hurtScreen || !EntityFilter.affects(player) || EntityFilter.isBloodless(player)) {
			return;
		}

		int rgb = EntityFilter.bloodColor(player);
		if (kPa >= PressureVessel.EARDRUM_KPA) {
			flash = Math.max(flash, Mth.clamp(kPa / PressureVessel.LETHAL_KPA, 0.4F, 1.0F));
			// Blood from the ears runs down both sides of the view; with the lungs torn it is coughed across the bottom.
			int ears = kPa >= PressureVessel.LUNG_KPA ? 3 : 2;
			for (int i = 0; i < ears; i++) {
				add(0.03F + random.nextFloat() * 0.07F, 0.25F + random.nextFloat() * 0.4F, 0.9F + random.nextFloat() * 0.4F, rgb);
				add(0.9F + random.nextFloat() * 0.07F, 0.25F + random.nextFloat() * 0.4F, 0.9F + random.nextFloat() * 0.4F, rgb);
			}

			if (kPa >= PressureVessel.LUNG_KPA) {
				for (int i = 0; i < 4; i++) {
					add(0.25F + random.nextFloat() * 0.5F, 0.8F + random.nextFloat() * 0.18F, 1.0F + random.nextFloat() * 0.6F, rgb);
				}
			}
		}

		for (int i = 0; i < hits; i++) {
			// A fragment: a splash from the vessel's side.
			float x = 0.5F + side * 0.34F + (float) random.nextGaussian() * 0.12F;
			add(Mth.clamp(x, 0.0F, 1.0F), 0.2F + random.nextFloat() * 0.6F, 1.0F + random.nextFloat() * 0.5F, rgb);
		}
	}

	/** One line in chat: the vessel, how far away it was, the pressure there, and what that pressure does to a body. */
	private static void report(LocalPlayer player, PressureVessel.Vessel vessel, double metres, float kPa, int hits) {
		String effect = kPa >= PressureVessel.SHRED_KPA ? "shred"
				: kPa >= PressureVessel.TEAR_KPA ? "tear"
				: kPa >= (vessel.real() ? dev.fluidgore.tank.Blast.FATAL_KPA : PressureVessel.LETHAL_KPA) ? "lethal"
				: kPa >= PressureVessel.LUNG_KPA ? "lungs"
				: kPa >= PressureVessel.EARDRUM_KPA ? "eardrums"
				: kPa >= PressureVessel.RINGING_KPA ? "ringing" : "bang";
		MutableComponent line = Component.translatable("fluidgore.vessel.report", Math.round(vessel.litres), Math.round(vessel.psi),
				String.format("%.1f", vessel.tntKg), String.format("%.1f", metres), Math.round(kPa), Math.round(kPa * 0.145F),
				Component.translatable("fluidgore.vessel." + effect));
		if (hits > 0) {
			line.append(" ").append(Component.translatable(hits == 1 ? "fluidgore.vessel.fragment" : "fluidgore.vessel.fragments", hits));
		}

		player.sendSystemMessage(line);
	}

	/**
	 * A steady noise of {@code dB} at the ear this tick (a gas jet screaming; 0 for none). Above 110 dB the hearing goes
	 * the longer you stand in it, faster the louder (each 10 dB more, ten times faster: 140 dB a metre from a cracked tank
	 * takes it at once, 115 dB a third of it in half a minute), with ringing after. Quiet, the dose wears off.
	 */
	public void noise(float dB) {
		GoreConfig config = GoreConfig.get();
		dose *= 0.995F;
		if (!config.blastDeafness || !config.goreLevel.enabled() || dB < 110.0F) {
			return;
		}

		dose += (float) Math.pow(10.0, (dB - 110.0F) / 10.0F) / 1200.0F;
		float depth = 0.85F * (1.0F - (float) Math.exp(-dose));
		if (depth > deafness() + 0.01F) {
			deaf = depth;
			deafAt = tick;
			deafTicks = Math.round(20 * (6 + 30 * depth));
			ring = Math.max(ringing(), 0.15F + 0.35F * depth);
			ringTicks = Math.round(deafTicks * 1.2F);
			Minecraft mc = Minecraft.getInstance();
			if (tinnitus == null || !mc.getSoundManager().isActive(tinnitus)) {
				tinnitus = new Tinnitus();
				mc.getSoundManager().play(tinnitus);
			}
		}
	}

	/** How much of the hearing is gone now, 0..1: all of it at once, coming back slowly, the last of it slowest. */
	private float deafness() {
		float left = 1.0F - (tick - deafAt) / (float) deafTicks;
		return left <= 0.0F ? 0.0F : deaf * left * left;
	}

	/** How loud the ringing in the ears is now. */
	public float ringing() {
		float left = 1.0F - (tick - deafAt) / (float) ringTicks;
		return left <= 0.0F ? 0.0F : ring * (float) Math.pow(left, 1.5);
	}

	/** How much of a sound on {@code source} gets through after a blast (1 = all). The ringing, on the master channel, always does. */
	public float hearing(SoundSource source) {
		return source == SoundSource.MASTER ? 1.0F : 1.0F - deafness();
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
		deaf = ring = dose = 0.0F;
		deafAt = -1000;
	}
}
