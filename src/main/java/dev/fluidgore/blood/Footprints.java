package dev.fluidgore.blood;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Whatever steps in blood gets it on its feet and leaves prints as it walks away, each fainter than the last. The
 * prints are those of the foot that made them (see {@link Foot}): a shoe, a cloven hoof, a horse's hoof, a paw, a
 * bird's foot, as long as the real one and as far apart as the animal steps. Only entities near the camera are
 * checked.
 */
final class Footprints {
	private static final double RANGE = 32.0;
	private static final int STEPS_PER_SOAK = 8;
	/** Depth of blood, mm, that wets a foot set down in it. */
	private static final float SOAK_THRESHOLD = 0.5F;

	/**
	 * A foot: the tile of its print, how long the print is, how far apart prints fall as the animal walks, and how far
	 * to either side of its line of travel they lie; all in blocks.
	 */
	private record Foot(int tile, float length, float step, float straddle) {
	}

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

	/**
	 * The foot of {@code entity}: full grown as listed, and smaller on the young and on anything scaled down. An animal
	 * this does not know walks on paws, anything else on two shod feet.
	 */
	private static Foot foot(LivingEntity entity) {
		Foot grown = grownFoot(entity);
		float size = entity.getScale() * entity.getAgeScale();
		return size == 1.0F ? grown : new Foot(grown.tile, grown.length * size, grown.step * size, grown.straddle * size);
	}

	private static Foot grownFoot(LivingEntity entity) {
		float tall = entity.getType().getDimensions().height() / 1.8F;
		float wide = entity.getType().getDimensions().width();
		return switch (BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath()) {
			case "pig" -> new Foot(BloodTextures.PRINT_CLOVEN, 0.07F, 0.3F, 0.11F);
			case "sheep", "goat" -> new Foot(BloodTextures.PRINT_CLOVEN, 0.06F, 0.3F, 0.1F);
			case "cow", "mooshroom" -> new Foot(BloodTextures.PRINT_CLOVEN, 0.12F, 0.4F, 0.15F);
			case "hoglin", "zoglin" -> new Foot(BloodTextures.PRINT_CLOVEN, 0.12F, 0.45F, 0.2F);
			case "llama", "trader_llama" -> new Foot(BloodTextures.PRINT_CLOVEN, 0.1F, 0.4F, 0.12F);
			case "camel" -> new Foot(BloodTextures.PRINT_CLOVEN, 0.18F, 0.6F, 0.2F);
			case "horse", "zombie_horse", "skeleton_horse" -> new Foot(BloodTextures.PRINT_HOOF, 0.14F, 0.5F, 0.14F);
			case "donkey", "mule" -> new Foot(BloodTextures.PRINT_HOOF, 0.11F, 0.45F, 0.12F);
			case "chicken" -> new Foot(BloodTextures.PRINT_BIRD, 0.08F, 0.18F, 0.05F);
			case "parrot" -> new Foot(BloodTextures.PRINT_BIRD, 0.04F, 0.1F, 0.03F);
			case "wolf" -> new Foot(BloodTextures.PRINT_PAW, 0.09F, 0.3F, 0.08F);
			case "fox" -> new Foot(BloodTextures.PRINT_PAW, 0.05F, 0.22F, 0.05F);
			case "cat", "ocelot" -> new Foot(BloodTextures.PRINT_PAW, 0.045F, 0.2F, 0.04F);
			case "rabbit", "armadillo" -> new Foot(BloodTextures.PRINT_PAW, 0.04F, 0.15F, 0.04F);
			case "panda" -> new Foot(BloodTextures.PRINT_PAW, 0.2F, 0.45F, 0.18F);
			case "polar_bear" -> new Foot(BloodTextures.PRINT_PAW, 0.26F, 0.5F, 0.2F);
			default -> entity instanceof Animal
					? new Foot(BloodTextures.PRINT_PAW, Math.clamp(wide * 0.13F, 0.04F, 0.25F), Math.clamp(wide * 0.45F, 0.15F, 0.6F), wide * 0.15F)
					: new Foot(BloodTextures.FOOTPRINT, Math.clamp(0.34F * tall, 0.08F, 0.6F), Math.clamp(0.75F * tall, 0.25F, 1.2F), 0.12F * Math.max(0.5F, tall));
		};
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
			BloodCell cell = world.fluid().at(entity.getX(), entity.getY(), entity.getZ());
			boolean wet = cell != null && cell.depthAt(entity.getX(), entity.getZ()) > SOAK_THRESHOLD;
			if (wet) {
				if (track == null) {
					track = new Track();
					track.lastX = entity.getX();
					track.lastZ = entity.getZ();
					tracks.put(entity.getId(), track);
				}

				track.steps = STEPS_PER_SOAK;
				track.rgb = cell.rgb;
				world.fluid().take(entity.getX(), entity.getY(), entity.getZ(), 0.2, 0.008F, 0.3F);
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
			if (track.steps > 0 && !wet) {
				Foot foot = foot(entity);
				if (track.travelled >= foot.step) {
					track.travelled = 0.0;
					placePrint(level, entity, foot, dx, dz, track);
				}
			}
		}

		// Forget entities that left the area, stopped being tracked or ran out of blood on their feet.
		tracks.values().removeIf(t -> t.steps <= 0 || tick - t.seenTick > 40);
	}

	private void placePrint(Level level, LivingEntity entity, Foot foot, double dx, double dz, Track track) {
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
			sideZ = dx > 0 ? foot.straddle : -foot.straddle;
		} else {
			orient = dz > 0 ? 2 : 0;
			sideX = dz > 0 ? -foot.straddle : foot.straddle;
		}

		double sign = track.leftFoot ? 1.0 : -1.0;
		if (!track.leftFoot) {
			orient ^= 1;
		}

		track.leftFoot = !track.leftFoot;
		track.steps--;
		// Each print has less blood to leave than the one before: less of the foot prints.
		float left = track.steps / (float) STEPS_PER_SOAK;
		// A shoe print takes up nearly all of its tile end to end, the others a little over half of theirs.
		float fills = foot.tile == BloodTextures.FOOTPRINT ? 0.9F : 0.6F;
		float half = foot.length / fills * 0.5F * (0.6F + 0.4F * left);
		world.decals().addFloor(level, entity.getX() + sideX * sign, surface, entity.getZ() + sideZ * sign, half, foot.tile, orient, track.rgb, true,
				world.decalLifetime() / 2);
	}

	void clear() {
		tracks.clear();
	}
}
