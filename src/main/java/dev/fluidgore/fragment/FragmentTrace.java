package dev.fluidgore.fragment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Bounded instantaneous game-debris traces. No projectiles, penetration, terrain damage or chunk generation. */
public final class FragmentTrace {
	public static final int MAX_CANDIDATES = 128;
	private static long occurrence;
	/** Delivery identity is independent of deterministic fragment seeds: two simultaneous bursts remain distinct. */
	public static long nextOccurrence() { return ++occurrence; }
	public static void reset() { occurrence = 0; }
	public record Launch(Vec3 direction, float range, float damage, byte material) {}
	private FragmentTrace() {}

	/** Seeded directions are independent of entity iteration and the world's random stream. */
	public static List<Launch> scatter(long seed, int count, float range, float damage) {
		RandomSource random = RandomSource.create(seed);
		List<Launch> shots = new ArrayList<>();
		for (int i = 0; i < Math.clamp(count, 0, FragmentPath.MAX_PATHS); i++) {
			double y = random.nextDouble() * 2 - 1;
			double angle = random.nextDouble() * Math.PI * 2;
			double side = Math.sqrt(1 - y * y);
			shots.add(new Launch(new Vec3(Math.cos(angle) * side, y, Math.sin(angle) * side), range, damage, i % 4 == 0 ? FragmentPath.METAL : FragmentPath.CARBON));
		}
		return List.copyOf(shots);
	}

	public static List<FragmentPath> trace(ServerLevel level, Vec3 origin, List<Launch> launches) {
		if (!Double.isFinite(origin.x) || !Double.isFinite(origin.y) || !Double.isFinite(origin.z)
				|| Math.abs(origin.x) > 3.0E7 || Math.abs(origin.y) > 3.0E7 || Math.abs(origin.z) > 3.0E7) return List.of();
		double range = launches.stream().limit(FragmentPath.MAX_PATHS).mapToDouble(s -> s.range).filter(Double::isFinite).max().orElse(0);
		range = Math.clamp(range, 0, FragmentPath.MAX_RANGE);
		List<LivingEntity> candidates = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class, new AABB(origin, origin).inflate(range),
				e -> e.isAlive() && !e.isSpectator() && !(e instanceof net.minecraft.world.entity.player.Player p && p.isCreative())));
		// Nearest candidates first; UUID resolves ties without depending on chunk/entity traversal order.
		candidates.sort(Comparator.comparingDouble((LivingEntity e) -> e.getBoundingBox().getCenter().distanceToSqr(origin)).thenComparing(LivingEntity::getUUID));
		if (candidates.size() > MAX_CANDIDATES) candidates.subList(MAX_CANDIDATES, candidates.size()).clear();
		List<FragmentPath> paths = new ArrayList<>();
		for (int i = 0; i < Math.min(launches.size(), FragmentPath.MAX_PATHS); i++) {
			Launch shot = launches.get(i);
			if (!Double.isFinite(shot.direction.lengthSqr()) || shot.direction.lengthSqr() < 1.0E-8 || !Float.isFinite(shot.range)
					|| !Float.isFinite(shot.damage) || shot.damage <= 0 || shot.material < 0 || shot.material > FragmentPath.STONE) continue;
			Vec3 direction = shot.direction.normalize();
			double reach = loadedReach(level, origin, direction, Math.clamp(shot.range, 0, FragmentPath.MAX_RANGE));
			if (reach <= 0) { paths.add(new FragmentPath(i, origin, shot.material, null)); continue; }
			Vec3 end = origin.add(direction.scale(reach));
			var block = level.clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
			if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
			double nearest = origin.distanceToSqr(end);
			LivingEntity victim = null;
			for (LivingEntity body : candidates) {
				var hit = body.getBoundingBox().clip(origin, end);
				Vec3 point = body.getBoundingBox().contains(origin) ? origin : hit.orElse(null);
				if (point != null && point.distanceToSqr(origin) < nearest - 1.0E-8) {
					nearest = point.distanceToSqr(origin); end = point; victim = body;
				}
			}
			FragmentPath.Contact contact = null;
			if (victim != null) {
				Vec3 delta = end.subtract(victim.position());
				double width = Math.max(0.01, victim.getBbWidth() * 0.5);
				Vec3 local = turn(new Vec3(delta.x / width, delta.y / Math.max(0.01, victim.getBbHeight()), delta.z / width), -victim.yBodyRot);
				Vec3 dir = turn(direction, -victim.yBodyRot);
				float damage = (float) (Math.min(50, shot.damage) / (1 + Math.sqrt(nearest) / 24));
				contact = new FragmentPath.Contact(victim.getId(), victim.getUUID(), local, dir, damage);
			}
			paths.add(new FragmentPath(i, end, shot.material, contact));
		}
		return List.copyOf(paths);
	}

	public static Vec3 turn(Vec3 v, float degrees) {
		double a = Math.toRadians(degrees), c = Math.cos(a), s = Math.sin(a);
		return new Vec3(c * v.x - s * v.z, v.y, s * v.x + c * v.z);
	}

	/** Chunk-grid DDA truncates the ray before any unloaded column; the subsequent block clip never requests it. */
	private static double loadedReach(ServerLevel level, Vec3 origin, Vec3 dir, double range) {
		int cx = (int) Math.floor(origin.x / 16), cz = (int) Math.floor(origin.z / 16);
		int sx = dir.x < 0 ? -1 : 1, sz = dir.z < 0 ? -1 : 1;
		double tx = Math.abs(dir.x) < 1.0E-10 ? Double.POSITIVE_INFINITY : ((cx + (sx > 0 ? 1 : 0)) * 16.0 - origin.x) / dir.x;
		double tz = Math.abs(dir.z) < 1.0E-10 ? Double.POSITIVE_INFINITY : ((cz + (sz > 0 ? 1 : 0)) * 16.0 - origin.z) / dir.z;
		double t = 0;
		while (t <= range) {
			if (level.getChunkSource().getChunkNow(cx, cz) == null) return Math.max(0, t - 0.001);
			if (tx < tz) { t = tx; tx += 16 / Math.abs(dir.x); cx += sx; }
			else { t = tz; tz += 16 / Math.abs(dir.z); cz += sz; }
		}
		return range;
	}
}
