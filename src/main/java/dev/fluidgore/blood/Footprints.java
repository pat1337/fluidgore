package dev.fluidgore.blood;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Entities that step in a puddle get bloody feet and leave a few alternating left/right footprints as they walk
 * away. Only entities near the camera are checked.
 */
final class Footprints {
	private static final double RANGE = 32.0;
	private static final int STEPS_PER_SOAK = 8;
	private static final double STRIDE = 0.75;
	private static final float SOAK_THRESHOLD = 0.04F;

	private static final class Track {
		int steps;
		int rgb;
		double lastX;
		double lastZ;
		double travelled;
		boolean leftFoot;
		int seenTick;
	}

	private final BloodWorld world;
	private final Int2ObjectOpenHashMap<Track> tracks = new Int2ObjectOpenHashMap<>();
	private int tick;

	Footprints(BloodWorld world) {
		this.world = world;
	}

	void tick(Level level) {
		tick++;
		Entity camera = Minecraft.getInstance().getCameraEntity();
		if (camera == null) {
			return;
		}

		AABB area = camera.getBoundingBox().inflate(RANGE);
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area, e -> e.onGround() && !e.isSpectator())) {
			Track track = tracks.get(entity.getId());
			BloodCell cell = world.fluid().cellAt(Mth.floor(entity.getX()), SurfaceProbe.airBlockY(entity.getY()), Mth.floor(entity.getZ()));
			if (cell != null && cell.volume > SOAK_THRESHOLD) {
				if (track == null) {
					track = new Track();
					track.lastX = entity.getX();
					track.lastZ = entity.getZ();
					tracks.put(entity.getId(), track);
				}

				track.steps = STEPS_PER_SOAK;
				track.rgb = cell.rgb;
				world.fluid().drain(cell, 0.002F);
			}

			if (track == null) {
				continue;
			}

			track.seenTick = tick;
			double dx = entity.getX() - track.lastX;
			double dz = entity.getZ() - track.lastZ;
			track.travelled += Math.sqrt(dx * dx + dz * dz);
			track.lastX = entity.getX();
			track.lastZ = entity.getZ();

			if (track.travelled >= STRIDE && track.steps > 0 && (cell == null || cell.volume <= SOAK_THRESHOLD)) {
				track.travelled = 0.0;
				placePrint(level, entity, dx, dz, track);
			}
		}

		// Forget entities that left the area, stopped being tracked or ran out of blood on their feet.
		tracks.values().removeIf(t -> t.steps <= 0 || tick - t.seenTick > 40);
	}

	private void placePrint(Level level, LivingEntity entity, double dx, double dz, Track track) {
		double surface = SurfaceProbe.surfaceBelow(level, entity.getX(), entity.getY() + 0.3, entity.getY() - 0.6, entity.getZ());
		if (Double.isNaN(surface)) {
			return;
		}

		// Snap the walking direction to one of four orientations (see Decal#orient for the bit meaning).
		boolean alongX = Math.abs(dx) > Math.abs(dz);
		int orient;
		double sideX = 0.0;
		double sideZ = 0.0;
		if (alongX) {
			orient = dx > 0 ? 6 : 4;
			sideZ = dx > 0 ? 0.12 : -0.12;
		} else {
			orient = dz > 0 ? 2 : 0;
			sideX = dz > 0 ? -0.12 : 0.12;
		}

		double sign = track.leftFoot ? 1.0 : -1.0;
		if (!track.leftFoot) {
			orient ^= 1;
		}

		track.leftFoot = !track.leftFoot;
		track.steps--;
		float alpha = track.steps / (float) STEPS_PER_SOAK;
		float half = 0.13F * (0.8F + 0.2F * alpha) * Math.max(0.6F, entity.getBbWidth() / 0.6F);
		world.decals().addFloor(level, entity.getX() + sideX * sign, surface, entity.getZ() + sideZ * sign, Math.min(half, 0.3F),
				BloodTextures.FOOTPRINT, orient, track.rgb, true, world.decalLifetime() / 2);
	}

	void clear() {
		tracks.clear();
	}
}
