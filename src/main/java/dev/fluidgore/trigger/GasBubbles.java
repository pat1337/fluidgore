package dev.fluidgore.trigger;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.physics.Buoyancy;
import dev.fluidgore.physics.Underwater;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The gas bubbles of charges gone off under water (see {@link Underwater}), each played out over its pulses: it grows
 * to its full radius, the water crushes it back down, it rebounds smaller and quicker, two or three times, rising as
 * it goes, until it reaches the surface and bursts out as a plume of spray that rains back down, or breaks up into a
 * column of bubbles. Its growing and collapsing moves the water round it, and that flow (added to the water's current,
 * see {@link Buoyancy#disturb}) is what carries pieces and bodies out and back in. The motion is worked out from the
 * charge and the water alone, so every screen sees the same; only the particles are this screen's own.
 */
public final class GasBubbles {
	/** Each rebound's largest radius against the one before: the bubble loses most of its energy each time it collapses. */
	private static final double REBOUND = 0.7;
	/** Pulses played before what is left breaks up into rising bubbles. */
	private static final int PULSES = 3;
	/** Smallest the bubble gets between pulses, against that pulse's largest. */
	private static final double SQUEEZED = 0.12;
	/** How far the bubble rises each pulse, against that pulse's radius (buoyancy acts mostly while it is small). */
	private static final double RISE = 0.45;
	/** Fastest the water is driven, blocks per tick, so nothing is flung through a wall in one step. */
	private static final double FASTEST = 1.5;
	/** Ticks the plume keeps pushing up once the bubble has burst out of the surface. */
	private static final int PLUME_TICKS = 8;
	/** Ticks a broken-up bubble keeps streaming to the surface. */
	private static final int STREAM_TICKS = 50;
	private static final int CAPACITY = 8;
	private static final List<Bubble> BUBBLES = new ArrayList<>();
	/** This screen's dice for where the particles go: they change nothing anything does. */
	private static final RandomSource FX = RandomSource.create();

	static {
		Buoyancy.disturb(GasBubbles::flow);
	}

	private GasBubbles() {
	}

	private static final class Bubble {
		final ClientLevel level;
		final Underwater.Site site;
		final double tntKg;
		final double x;
		final double z;
		double y;
		int age;
		int pulse;
		int pulseStart;
		/** This pulse's largest radius, blocks, and how long it lasts, ticks. */
		double amp;
		double period;
		double radius;
		/** How fast the wall is moving, blocks per tick (out positive). */
		double rate;
		/** Ticks since it burst out of the surface, or -1. */
		int vented = -1;
		/** Ticks since it broke up into a stream of bubbles, or -1. */
		int streaming = -1;
		double plumeSpeed;
		double plumeHeight;

		Bubble(ClientLevel level, Underwater.Site site, double tntKg) {
			this.level = level;
			this.site = site;
			this.tntKg = tntKg;
			this.x = site.centre().x;
			this.y = site.centre().y;
			this.z = site.centre().z;
			this.amp = Underwater.bubbleRadius(tntKg, site.depth());
			this.period = Math.max(4.0, Underwater.bubblePeriod(tntKg, site.depth()) * 20.0);
			this.plumeHeight = Underwater.plumeHeight(site, tntKg);
			this.plumeSpeed = Math.min(FASTEST, Math.sqrt(2.0 * 9.81 * plumeHeight) / 20.0);
		}

		boolean finished() {
			return vented > PLUME_TICKS + 60 || streaming > STREAM_TICKS;
		}

		/** Radius at {@code t} ticks into this pulse: Rayleigh's collapse is flat at the top and sharp at the bottom. */
		double radiusAt(double t) {
			double s = Math.sin(Math.PI * Mth.clamp(t / period, 0.0, 1.0));
			return amp * Math.max(SQUEEZED, Math.pow(Math.max(0.0, s), 2.0 / 3.0));
		}
	}

	/** A charge of {@code tntKg} went off under water at {@code site}: its bubble starts now. */
	public static void start(ClientLevel level, Underwater.Site site, double tntKg) {
		if (BUBBLES.size() >= CAPACITY) {
			BUBBLES.removeFirst();
		}

		Bubble b = new Bubble(level, site, tntKg);
		BUBBLES.add(b);
		dome(b);
		silt(b);
	}

	public static void tick(ClientLevel level) {
		for (int i = BUBBLES.size() - 1; i >= 0; i--) {
			Bubble b = BUBBLES.get(i);
			if (b.level != level || b.finished()) {
				BUBBLES.remove(i);
				continue;
			}

			step(b);
		}
	}

	public static void clear() {
		BUBBLES.clear();
	}

	/** Bubbles now playing out, for the game test. */
	public static int active() {
		return BUBBLES.size();
	}

	/** True if any bubble has burst out of the surface, for the game test. */
	public static boolean anyVented() {
		for (Bubble b : BUBBLES) {
			if (b.vented >= 0) {
				return true;
			}
		}

		return false;
	}

	private static void step(Bubble b) {
		b.age++;
		if (b.vented >= 0) {
			b.vented++;
			plume(b);
			return;
		}

		if (b.streaming >= 0) {
			b.streaming++;
			stream(b);
			return;
		}

		double t = b.age - b.pulseStart;
		double before = b.radius;
		b.radius = b.radiusAt(t);
		b.rate = b.radius - before;
		if (b.y + b.radius >= b.site.surfaceY() - 0.05) {
			// It has reached the surface: the gas bursts out, and with it a plume of water.
			b.vented = 0;
			b.rate = 0.0;
			vent(b);
			return;
		}

		shell(b);
		if (t >= b.period) {
			// Crushed to its smallest: a pulse of pressure, a burst of bubbles, and it rebounds smaller, a little higher.
			collapse(b);
			boolean onBottom = b.y - b.site.centre().y + b.site.floor() < b.amp;
			if (!onBottom) {
				b.y = Math.min(b.site.surfaceY() - 0.1, b.y + RISE * b.amp);
			}

			b.pulse++;
			b.pulseStart = b.age;
			b.amp *= REBOUND;
			b.period = Math.max(3.0, b.period * REBOUND);
			if (b.pulse >= PULSES) {
				b.streaming = 0;
				b.rate = 0.0;
			}
		}
	}

	/**
	 * The water's movement at {@code (x, y, z)} from every bubble, blocks per tick: outside a bubble it flows with the
	 * wall, falling off as the square of the distance (the water cannot compress, so all of it round the bubble moves
	 * out as it grows); inside, the gas carries what is in it with the wall; over a burst bubble, the plume lifts it.
	 */
	private static Vec3 flow(BlockGetter level, double x, double y, double z) {
		if (BUBBLES.isEmpty() || level != Minecraft.getInstance().level) {
			return Vec3.ZERO;
		}

		double fx = 0.0;
		double fy = 0.0;
		double fz = 0.0;
		for (Bubble b : BUBBLES) {
			double dx = x - b.x;
			double dy = y - b.y;
			double dz = z - b.z;
			double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (b.vented >= 0) {
				if (b.vented <= PLUME_TICKS) {
					double h = Math.sqrt(dx * dx + dz * dz);
					double wide = Math.max(0.6, b.radius * 0.8);
					if (h < wide && y > b.y - b.radius) {
						fy += b.plumeSpeed * (1.0 - h / wide) * (1.0 - b.vented / (double) (PLUME_TICKS + 1));
					}
				}

				continue;
			}

			if (b.streaming >= 0 || r < 1.0E-3 || r > 4.0 * b.amp + 1.0) {
				continue;
			}

			double u = r < b.radius ? b.rate * r / b.radius : b.rate * b.radius * b.radius / (r * r);
			fx += u * dx / r;
			fy += u * dy / r;
			fz += u * dz / r;
		}

		double speed = Math.sqrt(fx * fx + fy * fy + fz * fz);
		if (speed > FASTEST) {
			double k = FASTEST / speed;
			return new Vec3(fx * k, fy * k, fz * k);
		}

		return new Vec3(fx, fy, fz);
	}

	private static float particles() {
		return GoreConfig.get().goreLevel.particles;
	}

	private static boolean wet(ClientLevel level, double x, double y, double z) {
		return level.getFluidState(BlockPos.containing(x, y, z)).is(FluidTags.WATER);
	}

	/** The bubble's skin: bubbles all over its surface, moving with it, so its size and its pulses can be seen. */
	private static void shell(Bubble b) {
		int n = Math.min(48, Math.round((float) (8.0 * b.radius * b.radius) * particles()));
		for (int i = 0; i < n; i++) {
			Vec3 d = new Vec3(FX.nextGaussian(), FX.nextGaussian(), FX.nextGaussian());
			if (d.lengthSqr() < 1.0E-6) {
				continue;
			}

			d = d.normalize();
			double px = b.x + d.x * b.radius;
			double py = b.y + d.y * b.radius;
			double pz = b.z + d.z * b.radius;
			if (!wet(b.level, px, py, pz)) {
				continue;
			}

			// BubbleParticle keeps a fifth of what it is given.
			double v = Mth.clamp(b.rate * 5.0, -2.0, 2.0);
			b.level.addParticle(ParticleTypes.BUBBLE, px, py, pz, d.x * v, d.y * v + 0.05, d.z * v);
		}
	}

	/** The shock wave reaching the surface over the charge: the water there is thrown up in a white dome of spray. */
	private static void dome(Bubble b) {
		double depth = b.site.depth();
		if (depth > 3.0 * b.amp) {
			return;
		}

		double speed = Math.min(1.2, Underwater.domeSpeed(b.site, b.tntKg) / 20.0) * (1.0 - depth / (3.0 * b.amp));
		double wide = Math.max(1.0, depth * 1.5);
		int n = Math.round(40 * particles());
		for (int i = 0; i < n; i++) {
			double a = FX.nextDouble() * Math.PI * 2.0;
			double r = wide * Math.sqrt(FX.nextDouble());
			double up = speed * (1.0 - r / wide * 0.7) * (0.6 + 0.4 * FX.nextDouble());
			b.level.addParticle(ParticleTypes.CLOUD, b.x + Math.cos(a) * r, b.site.surfaceY() + 0.1, b.z + Math.sin(a) * r, Math.cos(a) * 0.03,
					up * 0.25, Math.sin(a) * 0.03);
			b.level.addParticle(ParticleTypes.SPLASH, b.x + Math.cos(a) * r, b.site.surfaceY() + 0.05, b.z + Math.sin(a) * r, 0.0, 0.0, 0.0);
		}
	}

	/** The bubble touching the bottom stirs it up: a ring of whatever the floor is made of, thrown out along it. */
	private static void silt(Bubble b) {
		double gap = b.site.floor() - (b.y - b.site.centre().y);
		if (gap > b.amp) {
			return;
		}

		double floorY = b.y - gap;
		BlockState ground = b.level.getBlockState(BlockPos.containing(b.x, floorY - 0.5, b.z));
		if (ground.isAir()) {
			return;
		}

		BlockParticleOption bits = new BlockParticleOption(ParticleTypes.BLOCK, ground);
		int n = Math.round(30 * particles() * (float) (1.0 - gap / b.amp));
		for (int i = 0; i < n; i++) {
			double a = FX.nextDouble() * Math.PI * 2.0;
			double r = b.amp * 0.6 * FX.nextDouble();
			double out = 0.15 + 0.2 * FX.nextDouble();
			b.level.addParticle(bits, b.x + Math.cos(a) * r, floorY + 0.1, b.z + Math.sin(a) * r, Math.cos(a) * out, 0.1 + 0.15 * FX.nextDouble(),
					Math.sin(a) * out);
		}
	}

	/** Crushed to its smallest: a burst of fine bubbles, a thud through the water, the bottom stirred again. */
	private static void collapse(Bubble b) {
		int n = Math.round(30 * particles());
		for (int i = 0; i < n; i++) {
			Vec3 d = new Vec3(FX.nextGaussian(), FX.nextGaussian(), FX.nextGaussian()).normalize();
			double r = b.radius * FX.nextDouble();
			if (wet(b.level, b.x + d.x * r, b.y + d.y * r, b.z + d.z * r)) {
				b.level.addParticle(ParticleTypes.BUBBLE, b.x + d.x * r, b.y + d.y * r, b.z + d.z * r, d.x * 1.5, d.y * 1.5 + 0.3, d.z * 1.5);
			}
		}

		b.level.playLocalSound(b.x, b.y, b.z, SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_INSIDE, SoundSource.BLOCKS, (float) Math.min(1.5, 0.5 + b.amp * 0.3),
				0.5F + 0.15F * b.pulse, false);
		silt(b);
	}

	/** The bubble breaks the surface: a roar of spray, the gas out into the air, and a plume of water thrown up. */
	private static void vent(Bubble b) {
		b.level.playLocalSound(b.x, b.site.surfaceY(), b.z, SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 2.0F, 0.6F, false);
		b.level.playLocalSound(b.x, b.site.surfaceY(), b.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.BLOCKS, 2.0F, 0.5F, false);
		int n = Math.round(40 * particles());
		for (int i = 0; i < n; i++) {
			double a = FX.nextDouble() * Math.PI * 2.0;
			double r = b.radius * Math.sqrt(FX.nextDouble());
			b.level.addParticle(ParticleTypes.BUBBLE_POP, b.x + Math.cos(a) * r, b.site.surfaceY() + 0.05, b.z + Math.sin(a) * r, 0.0, 0.0, 0.0);
			b.level.addParticle(ParticleTypes.SPLASH, b.x + Math.cos(a) * r, b.site.surfaceY() + 0.05, b.z + Math.sin(a) * r, Math.cos(a) * 0.2, 0.0,
					Math.sin(a) * 0.2);
		}
	}

	/** The plume: white water going up for a moment, then raining back down over the place for a couple of seconds. */
	private static void plume(Bubble b) {
		if (b.plumeHeight < 0.5) {
			return;
		}

		double wide = Math.max(0.6, b.radius * 0.8);
		if (b.vented <= PLUME_TICKS) {
			int n = Math.round(14 * particles());
			for (int i = 0; i < n; i++) {
				double a = FX.nextDouble() * Math.PI * 2.0;
				double r = wide * Math.sqrt(FX.nextDouble());
				// The cloud particle drags at 4% a tick: given this, it rises about as high as the plume.
				double up = b.plumeHeight * 0.04 * (1.0 - r / wide * 0.6) * (0.7 + 0.3 * FX.nextDouble());
				b.level.addParticle(ParticleTypes.CLOUD, b.x + Math.cos(a) * r, b.site.surfaceY() + 0.2, b.z + Math.sin(a) * r, Math.cos(a) * 0.02, up,
						Math.sin(a) * 0.02);
			}
		}

		// What went up comes down: drops falling from anywhere in the plume, over the time it takes to fall from its top.
		int fall = (int) Math.round(Math.sqrt(2.0 * b.plumeHeight / 9.81) * 20.0) + PLUME_TICKS;
		if (b.vented < fall + 30) {
			int n = Math.round(10 * particles());
			double spread = wide + b.plumeHeight * 0.25;
			for (int i = 0; i < n; i++) {
				double a = FX.nextDouble() * Math.PI * 2.0;
				double r = spread * Math.sqrt(FX.nextDouble());
				double h = b.plumeHeight * FX.nextDouble();
				b.level.addParticle(ParticleTypes.FALLING_WATER, b.x + Math.cos(a) * r, b.site.surfaceY() + h, b.z + Math.sin(a) * r, 0.0, 0.0, 0.0);
				if (i % 3 == 0) {
					b.level.addParticle(ParticleTypes.SPLASH, b.x + Math.cos(a) * r, b.site.surfaceY() + 0.05, b.z + Math.sin(a) * r, 0.0, 0.0, 0.0);
				}
			}
		}
	}

	/** What is left of the gas, broken up: a column of bubbles streaming to the surface and popping there. */
	private static void stream(Bubble b) {
		int n = Math.max(1, Math.round(6 * particles() * (1.0F - b.streaming / (float) STREAM_TICKS)));
		for (int i = 0; i < n; i++) {
			double px = b.x + FX.nextGaussian() * b.amp * 0.5;
			double pz = b.z + FX.nextGaussian() * b.amp * 0.5;
			double py = b.y + FX.nextDouble() * Math.max(0.5, b.site.surfaceY() - b.y);
			if (wet(b.level, px, py, pz)) {
				b.level.addParticle(ParticleTypes.BUBBLE_COLUMN_UP, px, py, pz, 0.0, 0.04, 0.0);
			}

			if (i % 2 == 0) {
				b.level.addParticle(ParticleTypes.BUBBLE_POP, px, b.site.surfaceY() + 0.05, pz, 0.0, 0.0, 0.0);
			}
		}
	}
}
