package dev.fluidgore;

import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.BrokenBlocks;
import dev.fluidgore.blood.Gush;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.Dismemberment;
import dev.fluidgore.dismember.ModelParts;
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
		Gush.tick();
		TRACKER.tick(level, BLOOD, DISMEMBERMENT, SUFFERING);
		if (gore) {
			BLOOD.tick(level, tickCount);
			DISMEMBERMENT.tick(level, BLOOD);
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

	public static void onEntityUnload(int entityId) {
		TRACKER.forget(entityId);
		ModelParts.forgetDrawn(entityId);
		// A body still going through its death throes keeps its missing parts until it is gone.
		if (!SUFFERING.onEntityUnload(entityId)) {
			DISMEMBERMENT.onEntityUnload(entityId);
		}
	}

	public static void clearAll() {
		Gush.clear();
		BrokenBlocks.clear();
		ModelParts.clearDrawn();
		SUFFERING.clear();
		BLOOD.clear();
		DISMEMBERMENT.clear();
		TRACKER.clear();
	}
}
