package dev.fluidgore;

import dev.fluidgore.blood.BloodSupply;
import dev.fluidgore.blood.ToolSoak;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.BrokenBlocks;
import dev.fluidgore.blood.Gush;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.Dismemberment;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.net.Evidence;
import dev.fluidgore.net.GoreSync;
import dev.fluidgore.net.PieceSync;
import dev.fluidgore.player.PlayerInjuries;
import dev.fluidgore.rot.Decay;
import dev.fluidgore.suffer.Suffering;
import dev.fluidgore.trigger.DamageTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/**
 * Owns the per-level effect state and drives it from the client tick. Everything is dropped when the level
 * changes (dimension change, disconnect, reconnect). Blood/dismemberment and Animal Suffering can be switched
 * off independently; whatever is off is cleared.
 */
public final class GoreSystems {
	private static final BloodWorld BLOOD = new BloodWorld();
	private static final Dismemberment DISMEMBERMENT = new Dismemberment();
	private static final Suffering SUFFERING = new Suffering();
	private static final DamageTracker TRACKER = new DamageTracker();
	private static final PlayerInjuries INJURIES = new PlayerInjuries();
	private static final Decay DECAY = new Decay();
	private static @Nullable ClientLevel currentLevel;
	private static int tickCount;

	private GoreSystems() {
	}

	public static BloodWorld blood() {
		return BLOOD;
	}

	public static Dismemberment dismemberment() {
		return DISMEMBERMENT;
	}

	public static Suffering suffering() {
		return SUFFERING;
	}

	public static PlayerInjuries injuries() {
		return INJURIES;
	}

	public static Decay decay() {
		return DECAY;
	}

	public static DamageTracker tracker() {
		return TRACKER;
	}

	public static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level != currentLevel) {
			clearAll();
			currentLevel = level;
		}

		if (level == null) {
			return;
		}

		GoreConfig config = GoreConfig.get();
		boolean gore = config.goreLevel.enabled();
		boolean suffering = config.sufferingLevel.enabled();
		if (!suffering) {
			SUFFERING.clear();
		}

		if (!gore) {
			BLOOD.clear();
			DISMEMBERMENT.clear();
			DECAY.clear();
		}

		if (!gore && !suffering) {
			TRACKER.clear();
			BrokenBlocks.clear();
			return;
		}

		if (client.isPaused()) {
			return;
		}

		tickCount++;
		INJURIES.tick(client);
		Gush.tick();
		TRACKER.tick(level, BLOOD, DISMEMBERMENT, SUFFERING);
		if (gore) {
			BLOOD.tick(level, tickCount);
			DISMEMBERMENT.tick(level, BLOOD);
			DECAY.tick(level, BLOOD);
			GoreSync.tick(level);
			PieceSync.tick(level);
			Evidence.tick(level);
		}

		if (suffering) {
			SUFFERING.tick(level);
		}

		// Every system has had its chance to react to this tick's broken blocks.
		BrokenBlocks.clear();
	}

	public static void onChunkUnload(int chunkX, int chunkZ) {
		BLOOD.onChunkUnload(chunkX, chunkZ);
		DISMEMBERMENT.parts().removeChunk(chunkX, chunkZ);
	}

	/**
	 * A living entity comes into this world under an id a dead body still holds: a player respawned (the server gives
	 * them back their old id) or a new mob took a freed one. The old body's state (burnt away, cut apart, its corpse)
	 * must not follow the id onto it, or the respawned player is drawn as nothing.
	 */
	public static void onEntityLoad(net.minecraft.world.entity.Entity entity) {
		if (!(entity instanceof net.minecraft.world.entity.LivingEntity living) || !living.isAlive()) {
			return;
		}

		int id = living.getId();
		if (SUFFERING.release(living) || DISMEMBERMENT.vaporized(id)) {
			DISMEMBERMENT.restore(id);
			DECAY.forget(id);
			BloodSupply.refill(id, 1.0F, true);
		}
	}

	public static void onEntityUnload(int entityId) {
		TRACKER.forget(entityId);
		BloodSupply.forget(entityId);
		GoreSync.forget(entityId);
		Evidence.forget(entityId);
		DECAY.forget(entityId);
		ModelParts.forgetDrawn(entityId);
		// A body still going through its death throes keeps its missing parts until it is gone.
		if (!SUFFERING.onEntityUnload(entityId)) {
			DISMEMBERMENT.onEntityUnload(entityId);
		}
	}

	public static void clearAll() {
		GoreSync.clear();
		Evidence.clear();
		Gush.clear();
		BloodSupply.clear();
		ToolSoak.clear();
		BrokenBlocks.clear();
		ModelParts.clearDrawn();
		INJURIES.clear();
		DECAY.clear();
		SUFFERING.clear();
		BLOOD.clear();
		DISMEMBERMENT.clear();
		TRACKER.clear();
	}
}
