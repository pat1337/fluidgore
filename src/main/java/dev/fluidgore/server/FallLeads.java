package dev.fluidgore.server;

import dev.fluidgore.net.BodyFall;
import dev.fluidgore.net.BodyLead;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Who leads the fall of each body the server keeps. The server's body is a box: it cannot tumble down a slope, roll
 * over a step's edge or carry its momentum off a cliff. A player's game can: its ragdoll falls with the animal's weight.
 * So the nearest player with the mod leads ({@link BodyLead}): its game reports where its ragdoll got to
 * ({@link BodyFall}) and the server moves its body there, where every other player's ragdoll follows it as before. The
 * server works nothing out; it checks the report and moves its box.
 *
 * <p>Until the leading game has reported (its Ragdoll Physics may be off), and when no player with the mod is near, the
 * server moves the body itself, as it always has ({@code BodySlide}).
 */
public final class FallLeads {
	/** Players this close to a body may be chosen to lead it, and lead it while they stay within {@link #KEEP_RANGE}. */
	private static final double CHOOSE_RANGE = 48.0;
	private static final double KEEP_RANGE = 64.0;
	/** How often a body's leader is checked, ticks. */
	private static final int CHECK_TICKS = 20;
	/** Furthest a report may move a body across the ground, up, and down, blocks: a few ticks of a fast tumble or fall. */
	private static final double MOST_ACROSS = 6.0;
	private static final double MOST_UP = 2.0;
	private static final double MOST_DOWN = 24.0;
	/** How high a reported spot may be lifted to fit the body's box, blocks. */
	private static final double MOST_LIFT = 1.5;
	/** How far a reported box may cut into a block and still be taken, blocks; it is then stood on top of it. */
	private static final double GRAZE = 0.02;
	/**
	 * How far a reported box may be moved sideways out of a wall, blocks: less than the leading game allows between where
	 * it put a body and where the server has it before it takes the body to have been moved (0.3, {@code Rigging.REPORTED}).
	 */
	private static final double MOST_ASIDE = 0.25;
	/** A body reports four or five times a second while it moves; a player leading a dozen falling at once is plenty. */
	private static final Throttle FALLS = new Throttle(200, 100);

	/** The leading player of each body, and whether its game has taken up the lead (reported once). */
	private static final Map<LivingEntity, Lead> LEADS = new WeakHashMap<>();

	private static final class Lead {
		final UUID player;
		boolean confirmed;

		Lead(UUID player) {
			this.player = player;
		}
	}

	private FallLeads() {
	}

	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(BodyLead.TYPE, BodyLead.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(BodyFall.TYPE, BodyFall.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(BodyFall.TYPE, (fall, context) -> receive(context.player(), fall));
		// A leader who comes back into sight of the body (rejoined, or back from another dimension) is told again: its
		// game forgot.
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			Lead lead = entity instanceof LivingEntity body ? LEADS.get(body) : null;
			if (lead != null && lead.player.equals(player.getUUID()) && ServerPlayNetworking.canSend(player, BodyLead.TYPE)) {
				ServerPlayNetworking.send(player, new BodyLead(entity.getId(), true));
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			LEADS.clear();
			FALLS.clear();
		});
	}

	/**
	 * Called every tick of a kept body: chooses or keeps its leader. True if a player's game leads its fall now, so the
	 * server leaves its moving to that game.
	 */
	static boolean led(ServerLevel level, LivingEntity body) {
		Lead lead = LEADS.get(body);
		if (lead == null || (level.getGameTime() + body.getId()) % CHECK_TICKS == 0) {
			ServerPlayer now = lead != null ? leader(level, body, lead.player) : null;
			if (now == null) {
				ServerPlayer next = choose(level, body);
				if (lead != null) {
					ServerPlayer old = level.getServer().getPlayerList().getPlayer(lead.player);
					if (old != null && ServerPlayNetworking.canSend(old, BodyLead.TYPE)) {
						ServerPlayNetworking.send(old, new BodyLead(body.getId(), false));
					}
				}

				if (next == null) {
					LEADS.remove(body);
					return false;
				}

				lead = new Lead(next.getUUID());
				LEADS.put(body, lead);
				ServerPlayNetworking.send(next, new BodyLead(body.getId(), true));
			}
		}

		return lead.confirmed;
	}

	/** The player whose game leads {@code body}'s fall now, or null if the server moves it itself. */
	static @Nullable UUID leader(LivingEntity body) {
		Lead lead = LEADS.get(body);
		return lead != null && lead.confirmed ? lead.player : null;
	}

	/** The body is gone (cleaned away, cleared, vanished). */
	static void forget(LivingEntity body) {
		LEADS.remove(body);
	}

	/** {@code id}'s player if they can still lead {@code body}: online, in its level, near it, with the mod. */
	private static @Nullable ServerPlayer leader(ServerLevel level, LivingEntity body, UUID id) {
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
		if (player == null || player.isRemoved() || player.level() != level || !ServerPlayNetworking.canSend(player, BodyLead.TYPE)
				|| !(player.distanceToSqr(body) <= KEEP_RANGE * KEEP_RANGE)) {
			return null;
		}

		return player;
	}

	/** The nearest player with the mod within {@link #CHOOSE_RANGE}, or null. */
	private static @Nullable ServerPlayer choose(ServerLevel level, LivingEntity body) {
		ServerPlayer best = null;
		double bestSq = CHOOSE_RANGE * CHOOSE_RANGE;
		for (ServerPlayer player : PlayerLookup.around(level, body.position(), CHOOSE_RANGE)) {
			double d = player.distanceToSqr(body);
			if (d <= bestSq && !player.isSpectator() && ServerPlayNetworking.canSend(player, BodyLead.TYPE)) {
				best = player;
				bestSq = d;
			}
		}

		return best;
	}

	/**
	 * A leading game says where its ragdoll is. Only its leader may move a body, only a kept one, only a few blocks at a
	 * time and only near the leader; the body goes there, lifted out of any block its box would be in.
	 */
	private static void receive(ServerPlayer player, BodyFall fall) {
		if (!fall.finite() || !FALLS.take(player, 1)) {
			return;
		}

		ServerLevel level = player.level();
		if (!(level.getEntity(fall.entityId()) instanceof LivingEntity body) || !KeptBodies.isKept(body)) {
			return;
		}

		Lead lead = LEADS.get(body);
		if (lead == null || !lead.player.equals(player.getUUID())) {
			return;
		}

		Vec3 from = body.position();
		Vec3 to = fall.at();
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		double dy = to.y - from.y;
		if (dx * dx + dz * dz > MOST_ACROSS * MOST_ACROSS || dy > MOST_UP || dy < -MOST_DOWN
				|| !(player.distanceToSqr(to) <= KEEP_RANGE * KEEP_RANGE)) {
			return;
		}

		// The ragdoll's middle may lie nearer a wall than half the box's width (a body down a corridor one block wide): the
		// box is put beside any block it cuts into that no lift would clear, by no more than the leading game takes for
		// where it put the body.
		double[] aside = aside(level, body, body.getBoundingBox().move(dx, dy, dz));
		if (aside == null) {
			return;
		}

		dx += aside[0];
		dz += aside[1];
		// The ragdoll lies in a shape the box is not: where its lowest point is, the box may cut into a step.
		AABB box = body.getBoundingBox().move(dx, dy, dz).deflate(GRAZE / 2.0);
		double lift = 0.0;
		while (!level.noBlockCollision(body, box.move(0.0, lift, 0.0))) {
			lift += 0.1;
			if (lift > MOST_LIFT) {
				return;
			}
		}

		// The ragdoll's lowest point lies a hair into the ground, and the check above lets the box graze a block. Vanilla's
		// movement only stops a box at a block it starts outside of, so a box left a thousandth into the ground falls
		// through its top. It goes on top of anything it grazes.
		AABB at = body.getBoundingBox().move(dx, dy + lift, dz);
		double rise = 0.0;
		for (VoxelShape shape : level.getBlockCollisions(body, at)) {
			// Box by box: a stair grazed on its lower step has its top half a block higher.
			for (AABB part : shape.toAabbs()) {
				if (part.intersects(at) && part.maxY > at.minY && part.maxY - at.minY <= GRAZE) {
					rise = Math.max(rise, part.maxY - at.minY);
				}
			}
		}

		lead.confirmed = true;
		body.setPos(to.x + aside[0], to.y + lift + rise, to.z + aside[1]);
		body.setDeltaMovement(Vec3.ZERO);
	}

	/**
	 * How far ({@code x, z}) to move {@code box} to stand clear of the walls it cuts into (block boxes reaching higher above
	 * its foot than any lift), or null if walls hold it on both sides of an axis or it would go further than
	 * {@link #MOST_ASIDE}. Each wall pushes it out along whichever of x and z it cuts in least.
	 */
	private static double @Nullable [] aside(ServerLevel level, LivingEntity body, AABB box) {
		double east = 0.0;
		double west = 0.0;
		double south = 0.0;
		double north = 0.0;
		for (VoxelShape shape : level.getBlockCollisions(body, box)) {
			for (AABB part : shape.toAabbs()) {
				if (!part.intersects(box) || part.maxY - box.minY <= MOST_LIFT) {
					continue;
				}

				double inX = Math.min(part.maxX - box.minX, box.maxX - part.minX);
				double inZ = Math.min(part.maxZ - box.minZ, box.maxZ - part.minZ);
				if (inX <= inZ) {
					if (part.getCenter().x < box.getCenter().x) {
						east = Math.max(east, inX);
					} else {
						west = Math.max(west, inX);
					}
				} else if (part.getCenter().z < box.getCenter().z) {
					south = Math.max(south, inZ);
				} else {
					north = Math.max(north, inZ);
				}
			}
		}

		double x = east - west;
		double z = south - north;
		if (east > 0.0 && west > 0.0 || south > 0.0 && north > 0.0 || Math.abs(x) > MOST_ASIDE || Math.abs(z) > MOST_ASIDE) {
			return null;
		}

		return new double[] {x, z};
	}
}
