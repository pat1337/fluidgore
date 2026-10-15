package dev.fluidgore.net;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.Cleaning;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.Butchery;
import dev.fluidgore.dismember.GorePart;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.CorpseStrike;
import dev.fluidgore.trigger.Fate;
import dev.fluidgore.wound.Weapon;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * The client half of shared butchery. A player's blow, cut, pluck or clean-up on a body is played here at once and
 * sent to the server, which passes it on to the other players nearby, who play the same action with the same dice
 * ({@link GoreAction#seed}). Everyone starts from the same body (live hits already agree, see {@link Fate}), so
 * everyone ends up with the same wounds, the same severed parts and the same pieces.
 * <p>
 * On a server without Fluid Gore nothing is sent, and the gore stays the player's own, as before.
 */
public final class GoreSync {
	/** Bodies this client has already asked the server to take away. */
	private static final IntOpenHashSet GONE = new IntOpenHashSet();
	/** Ticks dead after which a body still there is one the server keeps (vanilla removes it after 20). */
	private static final int KEPT_AFTER = 30;
	/** True while this player's own action (or hit) is played: the pieces it makes are this game's to move. */
	private static boolean local;

	private GoreSync() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(GoreAction.TYPE, (action, context) -> perform(action));
	}

	/** The server runs Fluid Gore: it keeps slaughtered animals' bodies and passes butchery on to the other players. */
	public static boolean serverKeepsBodies() {
		return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(GoreAction.TYPE);
	}

	/** Does {@code action} here and shows it to the others. Returns true if it did anything. */
	public static boolean act(GoreAction action) {
		local = true;
		boolean done;
		try {
			done = perform(action);
		} finally {
			local = false;
		}

		if (done) {
			send(action);
			if (action.entityId() >= 0) {
				Evidence.touched(action.entityId());
			}
		}

		return done;
	}

	/** Shows {@code action} to the others without doing it here (the caller already has). */
	public static void send(GoreAction action) {
		if (serverKeepsBodies()) {
			if (action.kind() == GoreAction.CLEAN) {
				GONE.add(action.entityId());
			}

			ClientPlayNetworking.send(action);
		}
	}

	/** Plays an action, this player's own or another's, with the action's own dice. */
	public static boolean perform(GoreAction action) {
		if (!(Minecraft.getInstance().level instanceof ClientLevel level) || !GoreConfig.get().goreLevel.enabled()) {
			return false;
		}

		Corpse corpse = action.entityId() >= 0 ? GoreSystems.suffering().corpse(action.entityId()) : null;
		// Entity ids are the same on every client of a server, but the UUID is the body's own for good.
		long seed = action.seed();
		if (corpse != null) {
			seed = seed * 31 + (corpse.entity.getUUID().getMostSignificantBits() ^ corpse.entity.getUUID().getLeastSignificantBits());
		}

		Fate.begin(seed);
		try {
			return switch (action.kind()) {
				case GoreAction.STRIKE -> {
					if (corpse == null) {
						yield false;
					}

					Weapon[] weapons = Weapon.values();
					Weapon weapon = weapons[Math.floorMod(action.weapon(), weapons.length)];
					CorpseStrike.strike(level, GoreSystems.blood(), corpse, action.at(), action.dir(), weapon, action.damage());
					yield true;
				}
				case GoreAction.PLUCK -> corpse != null && Butchery.pluckable(corpse.entity)
						&& GoreSystems.dismemberment().butchery().pluck(level, corpse, action.at(), action.dir());
				case GoreAction.CUT -> {
					GorePart part = GoreSystems.dismemberment().parts().byKey(action.partKey());
					yield part != null && GoreConfig.get().butchery && GoreSystems.dismemberment().butchery().cut(GoreSystems.blood(), level, part, action.dir());
				}
				case GoreAction.CLEAN -> {
					GONE.add(action.entityId());
					yield GoreSystems.suffering().clean(level, action.entityId());
				}
				case GoreAction.SCRUB -> {
					Direction[] faces = Direction.values();
					yield Cleaning.scrub(level, GoreSystems.blood(), action.at(), faces[Math.floorMod(action.weapon(), faces.length)]) > 0;
				}
				default -> false;
			};
		} finally {
			Fate.end();
		}
	}

	/**
	 * A body torn or butchered apart has nothing left to keep: the server is asked to take it away, so it does not come
	 * back whole when someone comes back to the place.
	 */
	public static void tick(ClientLevel level) {
		if (!serverKeepsBodies()) {
			return;
		}

		IntIterator it = GoreSystems.dismemberment().vaporizedIds().iterator();
		while (it.hasNext()) {
			int id = it.nextInt();
			Entity entity = level.getEntity(id);
			// Only one the server is keeping: vanilla takes a dead body away on its own within a second.
			if (entity instanceof LivingEntity body && !body.isAlive() && !body.isRemoved() && body.deathTime > KEPT_AFTER && GONE.add(id)) {
				ClientPlayNetworking.send(new GoreAction(GoreAction.CLEAN, id, 0L, (float) body.getX(), (float) body.getY(), (float) body.getZ(),
						0.0F, 0.0F, 0.0F, (byte) 0, 0.0F));
			}
		}
	}

	/** Marks what this player does themselves (see {@link #local}); {@code DamageTracker} sets it around their own hits. */
	public static void local(boolean own) {
		local = own;
	}

	/** True while this player's own action or hit is played. */
	public static boolean local() {
		return local;
	}

	public static void forget(int entityId) {
		GONE.remove(entityId);
	}

	public static void clear() {
		GONE.clear();
	}
}
