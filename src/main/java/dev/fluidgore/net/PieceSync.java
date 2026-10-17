package dev.fluidgore.net;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.dismember.GorePart;
import dev.fluidgore.dismember.GorePartManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.joml.Quaternionf;

/**
 * Loose pieces that move the same on every screen. Each game runs the pieces' physics itself, so a piece kicked on one
 * screen would stay put on the others. Instead the game of the player who moved a piece (punched, kicked, carried,
 * threw, stomped it, or cut it off) owns its motion for a while: it sends where the piece is every few ticks until it
 * comes to rest, then once more as it lies. The others put their copy of the piece there and let their own physics
 * fill in between. A piece is found by its {@link GorePart#key}, which is the same on every screen.
 * <p>
 * Only with Fluid Gore on the server (see {@link GoreSync#serverKeepsBodies}); otherwise nothing is sent.
 */
public final class PieceSync {
	/** Ticks a touched piece's motion is sent for, at most, if it never comes to rest. */
	public static final int STREAM_TICKS = 100;
	/** Ticks between two sends while it moves. */
	private static final int EVERY = 2;
	/** Ticks a piece needs at rest (easing into how it lies) before its last send. */
	private static final int SETTLED = 12;
	/** A carried piece that hears nothing from its carrier for this long falls. */
	private static final int HELD_TIMEOUT = 8;

	private static int tick;

	private PieceSync() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PieceState.TYPE, (state, context) -> apply(state));
	}

	/** This player moved {@code part}: from now on this game tells the others where it goes. */
	public static void moved(GorePart part) {
		part.streamFor = STREAM_TICKS;
		part.remoteHeld = 0;
	}

	public static void tick(ClientLevel level) {
		tick++;
		boolean online = GoreSync.serverKeepsBodies();
		for (GorePart part : GoreSystems.dismemberment().parts().parts()) {
			if (part.remoteHeld > 0 && --part.remoteHeld == 0) {
				part.held = false;
			}

			if (part.streamFor <= 0) {
				continue;
			}

			part.streamFor--;
			if (!online) {
				part.streamFor = 0;
				continue;
			}

			if (!part.uploaded) {
				// What it is, before where it is: the server keeps both for whoever comes along later.
				part.uploaded = true;
				Evidence.piece(part);
			}

			boolean settled = part.resting && part.restTicks >= SETTLED && !part.held;
			if (settled) {
				part.streamFor = 0;
				send(part, PieceState.RESTING);
			} else if (tick % EVERY == 0) {
				send(part, part.held ? PieceState.HELD : 0);
			}
		}
	}

	/** {@code part}, moved by this player, broke apart: it breaks on the other screens too. */
	public static void shattered(GorePart part) {
		if (part.streamFor > 0 && GoreSync.serverKeepsBodies()) {
			part.streamFor = 0;
			send(part, PieceState.SHATTERED);
		}
	}

	private static void send(GorePart p, byte flags) {
		Quaternionf q = p.rotation;
		ClientPlayNetworking.send(new PieceState(p.key, (float) p.x, (float) p.y, (float) p.z, (float) p.vx, (float) p.vy, (float) p.vz, q.x, q.y, q.z, q.w,
				p.spinAxis.x, p.spinAxis.y, p.spinAxis.z, p.spinSpeed, flags));
	}

	/** Another player's game moved a piece: this one follows. */
	public static void apply(PieceState s) {
		if (!(Minecraft.getInstance().level instanceof ClientLevel level) || !s.finite()) {
			return;
		}

		GorePart p = GoreSystems.dismemberment().parts().byKey(s.key());
		if (p == null) {
			if (!level.isLoaded(BlockPos.containing(s.x(), s.y(), s.z()))) {
				// The server sends what lies here as soon as the player is near; the ground under it may not be loaded
				// yet, and a piece in an unloaded chunk is dropped at once. It waits, and is put down when the ground is.
				Evidence.waitForGround(s);
				return;
			}

			// One the server kept, for a player who was not there when it came off.
			p = Evidence.place(s.key());
		}

		if (p == null || p == GoreSystems.dismemberment().interaction().held()) {
			// Not on this screen (it came off before this player was near), or this player has it in hand.
			return;
		}

		// They have it now: this game stops sending its own idea of where it is.
		p.streamFor = 0;
		if (s.has(PieceState.SHATTERED)) {
			p.x = s.x();
			p.y = s.y();
			p.z = s.z();
			GorePartManager.shatter(level, p);
			p.shattered = true;
			return;
		}

		p.x = s.x();
		p.y = s.y();
		p.z = s.z();
		p.vx = s.vx();
		p.vy = s.vy();
		p.vz = s.vz();
		p.rotation.set(s.qx(), s.qy(), s.qz(), s.qw());
		p.spinAxis.set(s.ax(), s.ay(), s.az());
		p.spinSpeed = s.spin();
		p.held = s.has(PieceState.HELD);
		p.remoteHeld = p.held ? HELD_TIMEOUT : 0;
		if (s.has(PieceState.RESTING)) {
			// Lying exactly as it lies there: no new resting pose of its own.
			p.resting = true;
			p.landed = true;
			p.vx = p.vy = p.vz = 0.0;
			p.spinSpeed = 0.0F;
			p.restTicks = Math.max(p.restTicks, 1);
			p.restTarget = new Quaternionf(p.rotation);
		} else {
			p.resting = false;
			p.restTicks = 0;
			p.restTarget = null;
		}
	}
}
