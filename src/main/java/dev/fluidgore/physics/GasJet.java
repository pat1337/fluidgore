package dev.fluidgore.physics;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The geometry of a jet of compressed gas out of a crack: how far it reaches and how hard it pushes what it meets. The
 * jet of a 1 cm^2 crack at 10,000 psi leaves at the speed of sound and widens as it goes; for a smaller crack or a lower
 * pressure every distance shrinks with the square root of the jet's power. Shared by anything that makes a jet (the
 * Cobble Tank add-on hurts bodies with it on the server) and the gore, which lifts loose pieces and scraps with it.
 */
public final class GasJet {
	/** Damage types that are a gas jet on a body: tagged by whatever adds them, so the gore needs no dependency. */
	public static final TagKey<DamageType> JET = tag("gas_jet");
	/** Damage types that are gas driven into the blood (an air embolism). */
	public static final TagKey<DamageType> EMBOLISM = tag("gas_embolism");
	/** How far a full-power jet reaches, metres. */
	private static final double REACH = 5.0;

	private GasJet() {
	}

	private static TagKey<DamageType> tag(String path) {
		return TagKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath("fluidgore", path));
	}

	/** The jet's power relative to a 1 cm^2 crack at 10,000 psi. */
	public static double power(double psi, double areaCm2) {
		return psi / 10_000.0 * areaCm2;
	}

	/** The first solid collision stops the jet. This same limit is used by living bodies, loose organs and fog. */
	public static double reach(Level level, Vec3 origin, Vec3 dir, double psi, double areaCm2) {
		double reach = Math.min(32.0, REACH * Math.sqrt(Math.max(0.0, power(psi, areaCm2))));
		Vec3 end = origin.add(dir.scale(reach));
		var hit = level.clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS ? reach : Math.max(0.0, origin.distanceTo(hit.getLocation()) - 0.01);
	}

	/** A bounded force proxy for small loose pieces; zero outside the directed cone or behind cover. */
	public static double push(Level level, AABB box, Vec3 origin, Vec3 dir, double psi, double areaCm2) {
		return push(box, origin, dir, psi, areaCm2, reach(level, origin, dir, psi, areaCm2));
	}

	public static double push(AABB box, Vec3 origin, Vec3 dir, double psi, double areaCm2, double reach) {
		if (reach <= 0.0) return 0.0;
		double along = firstContact(box, origin, dir, reach);
		return along < 0.0 ? 0.0 : Math.min(0.12, 0.012 * power(psi, areaCm2) / Math.max(0.09, along * along));
	}

	/** How far along the jet it first reaches {@code box} (the jet widening as it goes), or -1 if it misses. */
	public static double firstContact(AABB box, Vec3 origin, Vec3 dir, double reach) {
		for (double s = 0.0; s <= reach; s += 0.05) {
			double r = 0.03 + 0.12 * s;
			Vec3 p = origin.add(dir.scale(s));
			if (p.x > box.minX - r && p.x < box.maxX + r && p.y > box.minY - r && p.y < box.maxY + r && p.z > box.minZ - r && p.z < box.maxZ + r) {
				return s;
			}
		}

		return -1.0;
	}
}
