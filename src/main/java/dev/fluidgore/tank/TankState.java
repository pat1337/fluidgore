package dev.fluidgore.tank;

import dev.fluidgore.config.GoreConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * What a tank is carrying and what has been done to it, kept the same whether it stands as a block or flies loose:
 * its size and rated pressure, how much of the pressure is left, how deep blows have cut into the overwrap, and the
 * crack the gas is leaving by.
 * <p>
 * Blows cut the carbon overwrap, a fraction of its thickness at a time (by tool: a fist does nothing, wood and stone
 * scuff it, iron cuts fibres, diamond and netherite get through in a few blows). Once a blow is {@link #THROUGH}, the
 * liner leaks: a pinhole first, then, deeper, a cracked fitting of up to 1 cm^2; at {@link #SPLIT} the split runs and
 * the tank bursts. A cracked tank can also burst while it leaks: hit again, or the crack running on its own.
 */
public final class TankState {
	/** Damage at which a blow has gone through the overwrap and the liner leaks. */
	public static final float THROUGH = 0.5F;
	/** Damage at which the leak is a cracked fitting rather than a pinhole. */
	public static final float CRACK = 0.75F;
	/** Damage at which the split runs: the tank bursts. */
	public static final float SPLIT = 1.0F;
	/** Pressure left (fraction) below which the tank counts as empty: it can be broken like any block. */
	public static final float EMPTY = 0.03F;
	/** Crack area, cm^2, from which a leak is a cracked fitting (screams, pushes, may run). */
	public static final float CRACKED_AREA = 0.3F;
	/** Chance a second, while a cracked fitting leaks at full pressure, that the crack runs on and the tank bursts. */
	private static final float RUN_PER_SECOND = 0.015F;
	/** Chance a blow to a tank already leaking through a cracked fitting bursts it, at full pressure. */
	private static final float BURST_ON_BLOW = 0.2F;

	/** What a blow did. */
	public enum Blow {
		/** Nothing: a fist, or something no harder than one. */
		THUD,
		/** Marked the surface. */
		SCUFF,
		/** Cut into the fibres. */
		CUT,
		/** Through to the liner: it leaks (or leaks more). */
		THROUGH,
		/** The split ran: it bursts. */
		BURST
	}

	public float litres;
	public float psi;
	/** Pressure left, as a fraction of {@link #psi}. */
	public float fraction = 1.0F;
	/** How far blows have cut, 0..1 of the overwrap ({@link #SPLIT} bursts it). */
	public float damage;
	/** The crack the gas leaves by, cm^2 (0: sealed). */
	public float area;

	public TankState() {
		GoreConfig config = GoreConfig.get();
		litres = config.tankLitres;
		psi = config.tankPsi;
	}

	public TankState copy() {
		TankState t = new TankState();
		t.litres = litres;
		t.psi = psi;
		t.fraction = fraction;
		t.damage = damage;
		t.area = area;
		return t;
	}

	public float psiNow() {
		return psi * fraction;
	}

	public boolean empty() {
		return fraction < EMPTY;
	}

	public boolean leaking() {
		return area > 0.0F && !empty();
	}

	public boolean cracked() {
		return area >= CRACKED_AREA;
	}

	/** Temperature of the casing, C: room temperature until it leaks, then colder the more of the gas has gone. */
	public float temperature() {
		return area > 0.0F ? Blast.temperature(fraction) : 20.0F;
	}

	public double mass() {
		return Blast.mass(litres, psiNow());
	}

	public double thrust() {
		return leaking() ? Blast.thrust(psiNow(), area) : 0.0;
	}

	/**
	 * A blow of {@code force} (from {@link #force}) lands. Returns what it did; a {@link Blow#THROUGH} may have opened
	 * or widened the crack.
	 */
	public Blow blow(float force, RandomSource random) {
		if (force <= 0.0F) {
			return Blow.THUD;
		}

		boolean wasCracked = cracked() && leaking();
		damage += force;
		if (damage >= SPLIT || wasCracked && random.nextFloat() < BURST_ON_BLOW * fraction) {
			return Blow.BURST;
		}

		if (damage >= THROUGH) {
			if (!empty()) {
				area = Math.max(area, areaFor(damage));
			}

			return Blow.THROUGH;
		}

		return force >= 0.05F ? Blow.CUT : Blow.SCUFF;
	}

	/** A crack this deep: a pinhole of 0.02 to 0.08 cm^2, then a cracked fitting of 0.4 to 1 cm^2. */
	private static float areaFor(float damage) {
		if (damage < CRACK) {
			return 0.02F + 0.06F * (damage - THROUGH) / (CRACK - THROUGH);
		}

		return 0.4F + 0.6F * Math.min(1.0F, (damage - CRACK) / (SPLIT - CRACK));
	}

	/** A tick of leaking: the pressure falls. Returns true if the crack ran on and the tank bursts. */
	public boolean bleed(RandomSource random) {
		if (!leaking()) {
			return false;
		}

		fraction *= (float) Blast.bleed(litres, area);
		return cracked() && random.nextFloat() < RUN_PER_SECOND / 20.0F * fraction;
	}

	/**
	 * How hard a blow with {@code stack} cuts into the overwrap, as a fraction of its thickness: 0 for a fist or
	 * anything that is not a tool, a scuff for wood, gold and stone, fibres cut with iron, through in a few blows with
	 * diamond or netherite. Picks and axes cut best, swords and spears nearly as well, shovels and hoes half as well.
	 */
	public static float force(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0.0F;
		}

		float kind;
		if (stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES)) {
			kind = 1.0F;
		} else if (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.SPEARS)) {
			kind = 0.8F;
		} else if (stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)) {
			kind = 0.5F;
		} else {
			return 0.0F;
		}

		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		float material;
		if (id.startsWith("netherite_")) {
			material = 0.3F;
		} else if (id.startsWith("diamond_")) {
			material = 0.22F;
		} else if (id.startsWith("iron_")) {
			material = 0.07F;
		} else if (id.startsWith("copper_")) {
			material = 0.03F;
		} else if (id.startsWith("stone_")) {
			material = 0.012F;
		} else {
			// Wood, gold, and tools from other mods of unknown make: a scuff.
			material = 0.006F;
		}

		return kind * material;
	}

	public void save(ValueOutput output) {
		output.putFloat("Litres", litres);
		output.putFloat("Psi", psi);
		output.putFloat("Pressure", fraction);
		output.putFloat("Damage", damage);
		output.putFloat("Crack", area);
	}

	public void load(ValueInput input) {
		GoreConfig config = GoreConfig.get();
		litres = clean(input.getFloatOr("Litres", config.tankLitres), 1.0F, 5000.0F);
		psi = clean(input.getFloatOr("Psi", config.tankPsi), 0.0F, 50_000.0F);
		fraction = clean(input.getFloatOr("Pressure", 1.0F), 0.0F, 1.0F);
		damage = clean(input.getFloatOr("Damage", 0.0F), 0.0F, SPLIT);
		area = clean(input.getFloatOr("Crack", 0.0F), 0.0F, 1.0F);
	}

	private static float clean(float value, float min, float max) {
		return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
	}
}
