package dev.fluidgore.tank.client;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.net.TankBurstPayload;
import dev.fluidgore.tank.Blast;
import dev.fluidgore.tank.TankFx;
import dev.fluidgore.tank.TankState;
import dev.fluidgore.tank.Tanks;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The client half of the pressure vessel block: the flying tank's renderer, the burst message (handed to the 2.15
 * vessel gore, {@code trigger/PressureVessel}, so a tank block's burst looks and feels like one), and the leaks: a
 * hiss or a scream that follows each leaking tank, and the hearing it costs to stand near one.
 */
public final class TankClient {
	/** Beyond this the server sends no explosion packet: the burst is played from its message alone. */
	private static final double NO_EXPLOSION_PACKET = 60.0;
	/** Leaks heard this tick and last (keyed by block entity or entity), with where and how hard they blow. */
	private static final Map<Object, Leak> LEAKS = new HashMap<>();
	private static int tick;

	private static final class Leak {
		Vec3 origin;
		double psi;
		double area;
		boolean cracked;
		int seen;
		LeakSound sound;
	}

	private TankClient() {
	}

	public static void register() {
		EntityRendererRegistry.register(Tanks.ENTITY, TankRenderer::new);
		TankFx.leaks = TankClient::leak;
		ClientPlayNetworking.registerGlobalReceiver(TankBurstPayload.TYPE, (payload, context) -> burst(payload));
	}

	private static void burst(TankBurstPayload payload) {
		Minecraft mc = Minecraft.getInstance();
		if (!payload.finite() || mc.player == null) {
			return;
		}

		Int2IntOpenHashMap hits = new Int2IntOpenHashMap();
		for (int i = 0; i < payload.ids().length; i++) {
			hits.put(payload.ids()[i], Math.max(0, payload.hits()[i]));
		}

		Vec3 centre = new Vec3(payload.x(), payload.y(), payload.z());
		// The server has already taken the tank away, but its block update comes at the end of its tick, after this:
		// left standing here, the block would shade every body from the burst (seen from inside it, nothing is in view).
		net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(centre);
		if (mc.level != null && mc.level.getBlockState(at).is(Tanks.BLOCK)) {
			mc.level.removeBlock(at, false);
		}

		GoreSystems.tracker().vessels().told(centre, payload.litres(), payload.psi(), hits);
		if (mc.player.position().distanceTo(centre) >= NO_EXPLOSION_PACKET) {
			GoreSystems.tracker().onExplosion(centre, payload.radius());
		}
	}

	/** A leaking tank seen this tick (from its client tick). */
	private static void leak(Object key, Vec3 origin, TankState tank) {
		Leak leak = LEAKS.computeIfAbsent(key, k -> new Leak());
		leak.origin = origin;
		leak.psi = tank.psiNow();
		leak.area = tank.area;
		leak.cracked = tank.cracked();
		leak.seen = tick;
	}

	/** Every client tick: keeps each leak's sound going and stops the ones no longer seen; the loudest costs hearing. */
	public static void tick(Minecraft mc) {
		tick++;
		if (mc.level == null || mc.player == null) {
			clear();
			return;
		}

		Vec3 ear = mc.player.getEyePosition();
		float loudest = 0.0F;
		for (Iterator<Leak> it = LEAKS.values().iterator(); it.hasNext(); ) {
			Leak leak = it.next();
			if (tick - leak.seen > 2) {
				if (leak.sound != null) {
					leak.sound.end();
				}

				it.remove();
				continue;
			}

			if (leak.sound == null || !mc.getSoundManager().isActive(leak.sound)) {
				leak.sound = new LeakSound(leak);
				mc.getSoundManager().play(leak.sound);
			}

			loudest = Math.max(loudest, Blast.loudness(leak.psi, leak.area, ear.distanceTo(leak.origin)));
		}

		GoreSystems.injuries().noise(loudest);
	}

	public static void clear() {
		for (Leak leak : LEAKS.values()) {
			if (leak.sound != null) {
				leak.sound.end();
			}
		}

		LEAKS.clear();
	}

	/**
	 * A leak's sound, held on its crack: a pinhole's thin hiss, a cracked fitting's scream (the wind of an elytra pushed
	 * up in pitch, loud enough to be heard a long way off), dropping as the pressure goes.
	 */
	private static final class LeakSound extends AbstractTickableSoundInstance {
		private final Leak leak;

		LeakSound(Leak leak) {
			super(SoundEvents.ELYTRA_FLYING, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
			this.leak = leak;
			this.looping = true;
			this.delay = 0;
			update();
		}

		private void update() {
			x = leak.origin.x;
			y = leak.origin.y;
			z = leak.origin.z;
			float atOneMetre = Blast.loudness(leak.psi, leak.area, 1.0);
			// 100 dB at a metre is a quiet hiss; the volume over 1 only carries it further (16 blocks per unit).
			volume = Mth.clamp((atOneMetre - 100.0F) / 12.0F, 0.05F, 4.0F);
			pitch = leak.cracked ? Mth.clamp(1.2F + 0.4F * (float) (leak.psi / 10_000.0), 1.0F, 1.7F) : 2.0F;
		}

		@Override
		public void tick() {
			update();
		}

		void end() {
			stop();
		}
	}
}
