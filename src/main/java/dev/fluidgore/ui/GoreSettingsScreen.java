package dev.fluidgore.ui;

import com.mojang.serialization.Codec;
import dev.fluidgore.config.BloodColor;
import dev.fluidgore.config.BloodStyle;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Settings screen built from vanilla {@link OptionInstance}s, so it matches the look and controller/keyboard
 * navigation of the vanilla options menus. Every option writes straight into {@link GoreConfig}, which the
 * effect systems read live; the file is saved when the screen closes.
 */
public class GoreSettingsScreen extends OptionsSubScreen {
	/** Sections added by add-ons (the Cobble Tank), shown after Fluid Gore's own and saved when the screen closes. */
	private static final List<AddonSection> ADDONS = new java.util.concurrent.CopyOnWriteArrayList<>();

	/**
	 * An add-on's titled group of options. {@code options} is called each time the screen opens, so the buttons show
	 * the current values; {@code save} writes the add-on's own config file.
	 */
	public record AddonSection(String header, java.util.function.Supplier<List<OptionInstance<?>>> supplier, Runnable save) {
		List<OptionInstance<?>> options() {
			return supplier.get();
		}
	}

	public static void addSection(AddonSection section) {
		ADDONS.add(section);
	}

	public GoreSettingsScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("fluidgore.settings.title"));
	}

	@Override
	protected void addOptions() {
		GoreConfig c = GoreConfig.get();
		if (this.list == null) {
			return;
		}

		this.list.addBig(enumSlider("fluidgore.option.gore_level", GoreLevel.values(), c.goreLevel, GoreLevel::translationKey, v -> c.goreLevel = v));

		section("fluidgore.header.targets",
				toggle("fluidgore.option.affect_players", c.affectPlayers, v -> c.affectPlayers = v),
				toggle("fluidgore.option.affect_mobs", c.affectMobs, v -> c.affectMobs = v),
				toggle("fluidgore.option.affect_animals", c.affectAnimals, v -> c.affectAnimals = v),
				enumCycle("fluidgore.option.blood_color", BloodColor.values(), c.bloodColor, BloodColor::translationKey, v -> c.bloodColor = v),
				enumCycle("fluidgore.option.monster_blood_color", BloodColor.values(), c.monsterBloodColor, BloodColor::translationKey, v -> c.monsterBloodColor = v));

		section("fluidgore.header.blood",
				enumCycle("fluidgore.option.blood_style", BloodStyle.values(), c.bloodStyle, BloodStyle::translationKey, v -> c.bloodStyle = v),
				toggle("fluidgore.option.gore_chunks", c.goreChunks, v -> c.goreChunks = v),
				toggle("fluidgore.option.arterial_spurts", c.arterialSpurts, v -> c.arterialSpurts = v),
				toggle("fluidgore.option.gore_sounds", c.goreSounds, v -> c.goreSounds = v),
				scaledSlider("fluidgore.option.persistence", 1, 120, 10, c.persistenceSeconds, "fluidgore.unit.seconds", v -> c.persistenceSeconds = v),
				scaledSlider("fluidgore.option.max_decals", 1, 100, 50, c.maxBloodDecals, null, v -> c.maxBloodDecals = v),
				toggle("fluidgore.option.wall_splatter", c.wallSplatter, v -> c.wallSplatter = v),
				toggle("fluidgore.option.directional_spatter", c.directionalSpatter, v -> c.directionalSpatter = v),
				toggle("fluidgore.option.drag_trails", c.dragTrails, v -> c.dragTrails = v),
				toggle("fluidgore.option.footprints", c.footprints, v -> c.footprints = v),
				toggle("fluidgore.option.rain_washes", c.rainWashesBlood, v -> c.rainWashesBlood = v),
				toggle("fluidgore.option.cleaning", c.cleaning, v -> c.cleaning = v),
				toggle("fluidgore.option.bloody_wool", c.bloodyWool, v -> c.bloodyWool = v),
				toggle("fluidgore.option.bloody_coats", c.bloodyCoats, v -> c.bloodyCoats = v),
				toggle("fluidgore.option.blood_in_water", c.bloodInWater, v -> c.bloodInWater = v),
				toggle("fluidgore.option.feathers", c.feathers, v -> c.feathers = v),
				toggle("fluidgore.option.bloody_feathers", c.bloodyFeathers, v -> c.bloodyFeathers = v),
				toggle("fluidgore.option.glowing_ender_blood", c.glowingEnderBlood, v -> c.glowingEnderBlood = v));

		section("fluidgore.header.dismemberment",
				toggle("fluidgore.option.wounds", c.wounds, v -> c.wounds = v),
				toggle("fluidgore.option.wound_hostiles", c.woundHostiles, v -> c.woundHostiles = v),
				toggle("fluidgore.option.wound_players", c.woundPlayers, v -> c.woundPlayers = v),
				toggle("fluidgore.option.fractures", c.fractures, v -> c.fractures = v),
				toggle("fluidgore.option.blood_runs", c.bloodRuns, v -> c.bloodRuns = v),
				toggle("fluidgore.option.pallor", c.pallor, v -> c.pallor = v),
				toggle("fluidgore.option.rotten_undead", c.rottenUndead, v -> c.rottenUndead = v),
				toggle("fluidgore.option.wounds_heal", c.woundsHeal, v -> c.woundsHeal = v),
				scaledSlider("fluidgore.option.wound_clot", 1, 120, 5, c.woundClotSeconds, "fluidgore.unit.seconds", v -> c.woundClotSeconds = v),
				toggle("fluidgore.option.brutal_pvp", c.brutalPvp, v -> c.brutalPvp = v),
				scaledSlider("fluidgore.option.part_lifetime", 1, 120, 30, c.partLifetimeSeconds, "fluidgore.unit.seconds", v -> c.partLifetimeSeconds = v),
				scaledSlider("fluidgore.option.max_parts", 1, 256, 1, c.maxDetachedParts, null, v -> c.maxDetachedParts = v),
				toggle("fluidgore.option.play_with_parts", c.playWithParts, v -> c.playWithParts = v),
				toggle("fluidgore.option.armor_on_parts", c.armorOnParts, v -> c.armorOnParts = v),
				toggle("fluidgore.option.blast_gore", c.blastGore, v -> c.blastGore = v),
				toggle("fluidgore.option.butchery", c.butchery, v -> c.butchery = v));

		section("fluidgore.header.injuries",
				toggle("fluidgore.option.hurt_screen", c.hurtScreen, v -> c.hurtScreen = v),
				toggle("fluidgore.option.heartbeat", c.lowHealthHeartbeat, v -> c.lowHealthHeartbeat = v),
				scaledSlider("fluidgore.option.stagger", 0, 20, 10, c.staggerPercent, "fluidgore.unit.percent", v -> c.staggerPercent = v),
				toggle("fluidgore.option.limp", c.limp, v -> c.limp = v),
				toggle("fluidgore.option.bloody_clothes", c.bloodyClothes, v -> c.bloodyClothes = v),
				toggle("fluidgore.option.blast_deafness", c.blastDeafness, v -> c.blastDeafness = v));

		this.list.addHeader(Component.translatable("fluidgore.header.suffering"));
		this.list.addBig(enumSlider("fluidgore.option.suffering_level", GoreLevel.values(), c.sufferingLevel, GoreLevel::translationKey, v -> c.sufferingLevel = v));
		this.list.addSmall(
				scaledSlider("fluidgore.option.scream_volume", 0, 40, 5, c.screamVolume, "fluidgore.unit.percent", v -> c.screamVolume = v),
				scaledSlider("fluidgore.option.max_screams", 1, 32, 1, c.maxScreams, null, v -> c.maxScreams = v),
				toggle("fluidgore.option.herd_panic", c.herdPanic, v -> c.herdPanic = v),
				toggle("fluidgore.option.personality", c.personality, v -> c.personality = v),
				toggle("fluidgore.option.ecology", c.ecology, v -> c.ecology = v),
				scaledSlider("fluidgore.option.grief", 0, 60, 30, c.griefSeconds, "fluidgore.unit.seconds", v -> c.griefSeconds = v),
				toggle("fluidgore.option.death_throes", c.deathThroes, v -> c.deathThroes = v),
				toggle("fluidgore.option.chicken_deaths", c.chickenDeaths, v -> c.chickenDeaths = v),
				scaledSlider("fluidgore.option.death_launch", 0, 30, 10, c.deathLaunchPercent, "fluidgore.unit.percent", v -> c.deathLaunchPercent = v),
				toggle("fluidgore.option.red_death", c.redDeath, v -> c.redDeath = v),
				toggle("fluidgore.option.death_pop", c.deathPop, v -> c.deathPop = v),
				scaledSlider("fluidgore.option.death_pop_chance", 0, 20, 5, c.deathPopPercent, "fluidgore.unit.percent", v -> c.deathPopPercent = v));

		section("fluidgore.header.bodies",
				toggle("fluidgore.option.keep_bodies", c.keepBodies, v -> c.keepBodies = v),
				scaledSlider("fluidgore.option.max_kept_bodies", 1, 64, 8, c.maxKeptBodies, null, v -> c.maxKeptBodies = v),
				scaledSlider("fluidgore.option.corpse_linger", 1, 120, 5, c.corpseLingerSeconds, "fluidgore.unit.seconds", v -> c.corpseLingerSeconds = v),
				toggle("fluidgore.option.body_slide", c.bodySlide, v -> c.bodySlide = v),
				scaledSlider("fluidgore.option.wade_slow", 0, 16, 5, c.wadeSlowPercent, "fluidgore.unit.percent", v -> c.wadeSlowPercent = v),
				toggle("fluidgore.option.identify_remains", c.identifyRemains, v -> c.identifyRemains = v),
				toggle("fluidgore.option.piece_reactions", c.pieceReactions, v -> c.pieceReactions = v),
				toggle("fluidgore.option.body_decay", c.bodyDecay, v -> c.bodyDecay = v),
				scaledSlider("fluidgore.option.decay_speed", 1, 100, 1, c.decaySpeed, "fluidgore.unit.times", v -> c.decaySpeed = v));

		section("fluidgore.header.fire",
				toggle("fluidgore.option.burns", c.burns, v -> c.burns = v),
				toggle("fluidgore.option.cremation", c.cremation, v -> c.cremation = v),
				scaledSlider("fluidgore.option.cremation_speed", 1, 40, 25, c.cremationSpeedPercent, "fluidgore.unit.percent", v -> c.cremationSpeedPercent = v),
				toggle("fluidgore.option.melting", c.melting, v -> c.melting = v),
				toggle("fluidgore.option.melting_mobs", c.meltingMobs, v -> c.meltingMobs = v),
				toggle("fluidgore.option.melting_animals", c.meltingAnimals, v -> c.meltingAnimals = v),
				toggle("fluidgore.option.burning_spreads_fire", c.burningSpreadsFire, v -> c.burningSpreadsFire = v));

		for (AddonSection addon : ADDONS) {
			section(addon.header(), addon.options().toArray(OptionInstance[]::new));
		}

		section("fluidgore.header.performance",
				scaledSlider("fluidgore.option.cells_per_tick", 1, 64, 8, c.maxActiveCellsPerTick, null, v -> c.maxActiveCellsPerTick = v),
				scaledSlider("fluidgore.option.max_droplets", 1, 100, 50, c.maxDroplets, null, v -> c.maxDroplets = v),
				scaledSlider("fluidgore.option.max_giblets", 0, 80, 50, c.maxGiblets, null, v -> c.maxGiblets = v),
				scaledSlider("fluidgore.option.max_feathers", 0, 60, 50, c.maxFeathers, null, v -> c.maxFeathers = v));

		section("fluidgore.header.experimental",
				toggle("fluidgore.option.articulated_ragdoll", c.articulatedRagdoll, v -> c.articulatedRagdoll = v),
				toggle("fluidgore.option.ragdoll_knees", c.ragdollKnees, v -> c.ragdollKnees = v),
				toggle("fluidgore.option.ragdoll_debug", c.ragdollDebug, v -> c.ragdollDebug = v),
				toggle("fluidgore.option.pressure_vessel", c.pressureVessel, v -> c.pressureVessel = v),
				scaledSlider("fluidgore.option.vessel_litres", 1, 100, 10, c.vesselLitres, "fluidgore.unit.litres", v -> c.vesselLitres = v),
				scaledSlider("fluidgore.option.vessel_psi", 1, 40, 500, c.vesselPsi, "fluidgore.unit.psi", v -> c.vesselPsi = v));

		section("fluidgore.header.extras",
				toggle("fluidgore.option.inventory_sorting", c.inventorySorting, v -> c.inventorySorting = v));
	}

	/** A titled group of options, two to a row. */
	private void section(String header, OptionInstance<?>... options) {
		this.list.addHeader(Component.translatable(header));
		this.list.addSmall(options);
	}

	/** An on/off button that shows the option's {@code .tooltip} text, like every other option on this screen. */
	public static OptionInstance<Boolean> toggle(String key, boolean value, OptionInstance.ValueUpdateListener<Boolean> setter) {
		return OptionInstance.createBoolean(key, OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")), value, setter);
	}

	@Override
	public void removed() {
		super.removed();
		GoreConfig.save();
		for (AddonSection addon : ADDONS) {
			addon.save().run();
		}
	}

	/** A slider whose raw steps are multiplied by {@code step}, so large ranges stay easy to set precisely. */
	public static OptionInstance<Integer> scaledSlider(String key, int minSteps, int maxSteps, int step, int current, String unitKey, IntConsumer setter) {
		int initial = Math.max(minSteps, Math.min(maxSteps, Math.round(current / (float) step)));
		return new OptionInstance<>(
				key,
				OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
				(caption, steps) -> {
					int value = steps * step;
					Component shown = unitKey == null ? Component.literal(Integer.toString(value)) : Component.translatable(unitKey, value);
					return Options.genericValueLabel(caption, shown);
				},
				new OptionInstance.IntRange(minSteps, maxSteps, true),
				initial,
				steps -> setter.accept(steps * step)
		);
	}

	private static <E extends Enum<E>> OptionInstance<E> enumSlider(String key, E[] values, E current, java.util.function.Function<E, String> names, Consumer<E> setter) {
		return new OptionInstance<>(
				key,
				OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
				(caption, value) -> Options.genericValueLabel(caption, Component.translatable(names.apply(value))),
				new OptionInstance.SliderableEnum<>(Arrays.asList(values), enumCodec(values)),
				current,
				setter::accept
		);
	}

	private static <E extends Enum<E>> OptionInstance<E> enumCycle(String key, E[] values, E current, java.util.function.Function<E, String> names, Consumer<E> setter) {
		return new OptionInstance<>(
				key,
				OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
				// Cycle buttons add the "Caption: " prefix themselves, so only the value is returned here.
				(caption, value) -> Component.translatable(names.apply(value)),
				new OptionInstance.Enum<>(List.of(values), enumCodec(values)),
				current,
				setter::accept
		);
	}

	/** Codec is required by the option API for vanilla's options.txt; it is never persisted for these options. */
	private static <E extends Enum<E>> Codec<E> enumCodec(E[] values) {
		return Codec.INT.xmap(i -> values[Math.max(0, Math.min(values.length - 1, i))], Enum::ordinal);
	}
}
