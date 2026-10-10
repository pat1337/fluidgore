package dev.fluidgore.suffer;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Mothers and their young, and the grief when one of them is killed. A client cannot know which cow bore which calf,
 * so the bond is the one an onlooker would see: the nearest grown animal of the same kind to a young one, and the
 * other way round. Grief lasts minutes, not seconds; it fades slowly, and the survivor goes on calling to the place
 * where it happened long after the body is gone. Cosmetic only: real behaviour is untouched.
 */
final class Bereavement {
	/** How far a mother and young can be apart and still be each other's. */
	static final double BOND_RADIUS = 24.0;

	/**
	 * @param where  where the other one died
	 * @param orphan true for a young animal whose mother was killed
	 */
	record Grief(Vec3 where, int start, int length, boolean orphan) {
		/** 1 right after the death, falling to 0 over the grief; slow at first, so the worst of it lasts. */
		float strength(int tick) {
			float left = 1.0F - (tick - start) / (float) Math.max(1, length);
			return left <= 0.0F ? 0.0F : (float) Math.sqrt(left);
		}
	}

	private final Int2ObjectOpenHashMap<Grief> grieving = new Int2ObjectOpenHashMap<>();

	@Nullable Grief of(int entityId) {
		return grieving.get(entityId);
	}

	int count() {
		return grieving.size();
	}

	/** The nearest living animal of the same kind and the other age (a calf's mother, a mother's calf), or null. */
	static @Nullable LivingEntity kinOf(ClientLevel level, LivingEntity entity) {
		LivingEntity best = null;
		double bestDist = BOND_RADIUS * BOND_RADIUS;
		AABB around = entity.getBoundingBox().inflate(BOND_RADIUS);
		for (Entity other : level.getEntities(entity, around, e -> e.getType() == entity.getType())) {
			if (other instanceof LivingEntity living && living.isAlive() && living.isBaby() != entity.isBaby()) {
				double dist = living.distanceToSqr(entity);
				if (dist < bestDist) {
					bestDist = dist;
					best = living;
				}
			}
		}

		return best;
	}

	/** {@code dead} has just been killed: its mother (or its young) starts grieving for {@code length} ticks. */
	@Nullable LivingEntity onDeath(ClientLevel level, LivingEntity dead, int tick, int length) {
		LivingEntity kin = kinOf(level, dead);
		if (kin != null && length > 0) {
			grieving.put(kin.getId(), new Grief(dead.position(), tick, length, !dead.isBaby()));
		}

		return kin;
	}

	void tick(int tick) {
		grieving.int2ObjectEntrySet().removeIf(e -> e.getValue().strength(tick) <= 0.0F);
	}

	void forget(int entityId) {
		grieving.remove(entityId);
	}

	void clear() {
		grieving.clear();
	}
}
