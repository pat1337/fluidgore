package dev.fluidgore.cobbletank.client;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.cobbletank.CobbleTank;
import dev.fluidgore.cobbletank.TankFx;
import dev.fluidgore.cobbletank.TankState;
import dev.fluidgore.net.VesselBurstPayload;
import dev.fluidgore.net.VesselBursts;
import dev.fluidgore.physics.Blast;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The Cobble Tank's client half: the flying tank's renderer, its settings and demos inside Fluid Gore's, clearing the
 * block when a burst arrives (Fluid Gore plays the burst's gore), and the leaks: a hiss or a scream that follows each
 * leaking tank, its jet lifting loose pieces, and the hearing it costs to stand near one.
 */
public final class TankClient implements ClientModInitializer {
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

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(CobbleTank.ENTITY, TankRenderer::new);
		TankFx.leaks = TankClient::leak;
		VesselBursts.listen(TankClient::clearBlock);
		ClientTickEvents.END_CLIENT_TICK.register(TankClient::tick);
		TankSettings.register();
		TankDemos.register();
	}

	/**
	 * The server has already taken the tank away, but its block update comes at the end of its tick, after the burst
	 * message: left standing, the block would shade every body from the burst (seen from inside it, nothing is in view).
	 */
	private static void clearBlock(VesselBurstPayload payload) {
		Minecraft mc = Minecraft.getInstance();
		BlockPos at = BlockPos.containing(payload.x(), payload.y(), payload.z());
		if (mc.level != null && mc.level.getBlockState(at).is(CobbleTank.BLOCK)) {
			mc.level.removeBlock(at, false);
		}
	}

	/** A leaking tank seen this tick (from its client tick). */
	private static void leak(Object key, Vec3 origin, Vec3 direction, TankState tank) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && dev.fluidgore.config.GoreConfig.get().goreLevel.enabled() && !mc.isPaused()) {
			GoreSystems.dismemberment().parts().jet(mc.level, origin, direction, tank.psiNow(), tank.area);
			GoreSystems.dismemberment().giblets().jet(mc.level, origin, direction, tank.psiNow(), tank.area);
		}
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
