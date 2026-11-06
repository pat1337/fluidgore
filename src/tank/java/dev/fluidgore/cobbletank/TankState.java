package dev.fluidgore.cobbletank;

import dev.fluidgore.physics.Blast;
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
 * the pressurized tank bursts. Heat weakens the shell permanently; venting reduces pressure and recoil. These damage
 * fractions are game states, not measurements of laminate thickness or real vessel failure limits.
 */
public final class TankState {
	/** Damage at which a blow has gone through the overwrap and the liner leaks. */
	public static final float THROUGH = 0.5F;
	/** Damage at which the leak is a cracked fitting rather than a pinhole. */
	public static final float CRACK = 0.75F;
	/** Damage at which the split runs: the tank bursts. */
	public static final float SPLIT = 1.0F;
	/** Crack area, cm^2, from which a leak is a cracked fitting (screams and pushes). */
	public static final float CRACKED_AREA = 0.3F;

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
	/** Gas remaining as a fraction of the initial fill. Old Pressure save tags retain their ambient-pressure meaning. */
	public float fraction = 1.0F;
	/** How far blows have cut, 0..1 of the overwrap ({@link #SPLIT} bursts it). */
	public float damage;
	/** The crack the gas leaves by, cm^2 (0: sealed). */
	public float area;
	/** Gas and shell have separate thermal inertia. Heat damage remains after cooling. */
	public float gasC = 20.0F;
	public float shellC = 20.0F;
	public float heatDamage;
	/** The crafted block's deliberate game trigger; copied when recoil tears it loose. */
	public int triggerTicks = -1;
	public int wavePercent;

	public TankState() {
		TankConfig config = TankConfig.get();
		litres = config.tankLitres;
		psi = config.tankPsi;
		wavePercent = config.tankWavePercent;
	}

	public TankState copy() {
		TankState t = new TankState();
		t.litres = litres;
		t.psi = psi;
		t.fraction = fraction;
		t.damage = damage;
		t.area = area;
		t.gasC = gasC;
		t.shellC = shellC;
		t.heatDamage = heatDamage;
		t.triggerTicks = triggerTicks;
		t.wavePercent = wavePercent;
		return t;
	}

	public float psiNow() {
		return Math.clamp((psi + 14.696F) * fraction * (gasC + 273.15F) / 293.15F - 14.696F, 0.0F, 50_000.0F);
	}

	public boolean empty() {
		return psiNow() < 5.0F;
	}

	public boolean leaking() {
		return area > 0.0F && !empty();
	}

	public boolean cracked() {
		return area >= CRACKED_AREA;
	}

	/** Temperature of the casing, C; separate from the gas remaining inside it. */
	public float temperature() {
		return shellC;
	}

	public double mass() {
		// Heating changes pressure, not the mass remaining inside.
		return Blast.mass(litres, psi * fraction);
	}

	public double thrust() {
		return leaking() ? Blast.thrust(psiNow(), area) : 0.0;
	}

	/** The selected wave surrogate, distinct from the total expansion-energy equivalent. */
	public double waveKg() {
		return Blast.tntKg(litres, psiNow()) * wavePercent / 100.0;
	}

	/**
	 * A blow of {@code force} (from {@link #force}) lands. Returns what it did; a {@link Blow#THROUGH} may have opened
	 * or widened the crack.
	 */
	public Blow blow(float force, RandomSource random) {
		if (force <= 0.0F) {
			return Blow.THUD;
		}

		damage = Math.min(SPLIT, damage + force);
		if (fails()) {
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

	/** Compatibility entry point: an unheated tick, without random spontaneous rupture. */
	public boolean bleed(RandomSource random) {
		return advance(false);
	}

	/**
	 * One 20 Hz game step: finite gas leaves the opening, the remaining gas cools, the shell exchanges heat, and
	 * prolonged fire damages the overwrap. Ideal-gas blowdown with a calibrated fill mass; not a high-pressure EOS.
	 * Fire timing and residual strength are game approximations, not specifications for a real vessel.
	 */
	public boolean advance(boolean heated) {
		float before = fraction;
		if (leaking()) {
			double fullGas = Blast.mass(litres, psi) - Blast.mass(litres, 0);
			fraction = (float) Math.max(0.0, fraction - Blast.massFlow(psiNow(), gasC, area) * 0.05 / Math.max(0.001, fullGas));
			gasC = (float) Math.max(-173.15, (gasC + 273.15) * Math.pow(fraction / Math.max(1.0E-8, before), 0.4) - 273.15);
		}
		float seconds = TankConfig.get().tankFireSeconds;
		shellC += ((heated ? 650.0F : 20.0F) - shellC) * (heated ? 0.05F / (seconds * 0.4F) : 0.001F);
		if (area > 0.0F) shellC += (gasC - shellC) * 0.001F;
		gasC += (shellC - gasC) * Math.min(0.05F, 0.05F / (15.0F * Math.max(0.1F, fraction)));
		if (shellC > 150.0F) {
			heatDamage = Math.min(1.0F, heatDamage + (shellC - 150.0F) / 500.0F * 0.05F / seconds);
		}
		return (triggerTicks >= 0 && --triggerTicks <= 0) || fails();
	}

	/** A damaged empty shell cannot produce a pressure rupture. */
	public boolean fails() {
		return !empty() && (damage >= SPLIT || psiNow() / Math.max(1.0F, psi) > 1.5F - 0.5F * damage - 1.2F * heatDamage);
	}

	/** External blasts damage the casing according to local exposure, rather than chaining every tank in range. */
	public Blow shock(float kPa, RandomSource random) {
		return blow(Math.min(1.0F, Math.max(0.0F, kPa) / 600.0F), random);
	}

	/** A player's deliberate valve opening, separate from a cut through the overwrap. */
	public void vent() {
		area = Math.max(area, 1.0F);
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
		output.putFloat("GasC", gasC);
		output.putFloat("ShellC", shellC);
		output.putFloat("HeatDamage", heatDamage);
		output.putInt("Fuse", triggerTicks);
		output.putInt("WavePercent", wavePercent);
	}

	public void load(ValueInput input) {
		TankConfig config = TankConfig.get();
		litres = clean(input.getFloatOr("Litres", config.tankLitres), 1.0F, 5000.0F);
		psi = clean(input.getFloatOr("Psi", config.tankPsi), 0.0F, 50_000.0F);
		fraction = clean(input.getFloatOr("Pressure", 1.0F), 0.0F, 1.0F);
		damage = clean(input.getFloatOr("Damage", 0.0F), 0.0F, SPLIT);
		area = clean(input.getFloatOr("Crack", 0.0F), 0.0F, 1.0F);
		gasC = clean(input.getFloatOr("GasC", 20.0F), -173.15F, 1000.0F);
		shellC = clean(input.getFloatOr("ShellC", 20.0F), -173.15F, 1000.0F);
		heatDamage = clean(input.getFloatOr("HeatDamage", 0.0F), 0.0F, 1.0F);
		triggerTicks = Math.max(-1, Math.min(TankBlock.FUSE, input.getIntOr("Fuse", -1)));
		wavePercent = Math.max(1, Math.min(100, input.getIntOr("WavePercent", config.tankWavePercent)));
	}

	private static float clean(float value, float min, float max) {
		return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
	}
}
