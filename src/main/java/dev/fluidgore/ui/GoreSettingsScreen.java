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

		this.list.addHeader(Component.translatable("fluidgore.header.targets"));
		this.list.addSmall(
				OptionInstance.createBoolean("fluidgore.option.affect_players", c.affectPlayers, v -> c.affectPlayers = v),
				OptionInstance.createBoolean("fluidgore.option.affect_mobs", c.affectMobs, v -> c.affectMobs = v),
				OptionInstance.createBoolean("fluidgore.option.affect_animals", c.affectAnimals, v -> c.affectAnimals = v),
				enumCycle("fluidgore.option.blood_color", BloodColor.values(), c.bloodColor, BloodColor::translationKey, v -> c.bloodColor = v),
				enumCycle("fluidgore.option.monster_blood_color", BloodColor.values(), c.monsterBloodColor, BloodColor::translationKey, v -> c.monsterBloodColor = v)
		);

		this.list.addHeader(Component.translatable("fluidgore.header.blood"));
		this.list.addSmall(
				enumCycle("fluidgore.option.blood_style", BloodStyle.values(), c.bloodStyle, BloodStyle::translationKey, v -> c.bloodStyle = v),
				OptionInstance.createBoolean("fluidgore.option.gore_chunks", c.goreChunks, v -> c.goreChunks = v),
				OptionInstance.createBoolean("fluidgore.option.arterial_spurts", c.arterialSpurts, v -> c.arterialSpurts = v),
				OptionInstance.createBoolean("fluidgore.option.gore_sounds", c.goreSounds, v -> c.goreSounds = v)
		);
		this.list.addSmall(
				scaledSlider("fluidgore.option.persistence", 1, 120, 10, c.persistenceSeconds, "fluidgore.unit.seconds", v -> c.persistenceSeconds = v),
				scaledSlider("fluidgore.option.max_decals", 1, 100, 50, c.maxBloodDecals, null, v -> c.maxBloodDecals = v),
				OptionInstance.createBoolean("fluidgore.option.wall_splatter", c.wallSplatter, v -> c.wallSplatter = v),
				OptionInstance.createBoolean("fluidgore.option.footprints", c.footprints, v -> c.footprints = v),
				OptionInstance.createBoolean("fluidgore.option.rain_washes", c.rainWashesBlood, v -> c.rainWashesBlood = v),
				OptionInstance.createBoolean("fluidgore.option.cleaning", c.cleaning, v -> c.cleaning = v)
		);

		this.list.addHeader(Component.translatable("fluidgore.header.performance"));
		this.list.addSmall(
				scaledSlider("fluidgore.option.cells_per_tick", 1, 64, 8, c.maxActiveCellsPerTick, null, v -> c.maxActiveCellsPerTick = v),
				scaledSlider("fluidgore.option.max_droplets", 1, 100, 50, c.maxDroplets, null, v -> c.maxDroplets = v),
				scaledSlider("fluidgore.option.max_giblets", 0, 80, 50, c.maxGiblets, null, v -> c.maxGiblets = v)
		);

		this.list.addHeader(Component.translatable("fluidgore.header.dismemberment"));
		this.list.addSmall(
				scaledSlider("fluidgore.option.part_lifetime", 1, 120, 5, c.partLifetimeSeconds, "fluidgore.unit.seconds", v -> c.partLifetimeSeconds = v),
				scaledSlider("fluidgore.option.max_parts", 1, 128, 1, c.maxDetachedParts, null, v -> c.maxDetachedParts = v),
				OptionInstance.createBoolean("fluidgore.option.play_with_parts", c.playWithParts, v -> c.playWithParts = v),
				OptionInstance.createBoolean("fluidgore.option.blast_gore", c.blastGore, v -> c.blastGore = v),
				OptionInstance.createBoolean("fluidgore.option.wounds", c.wounds, v -> c.wounds = v),
				OptionInstance.createBoolean("fluidgore.option.wound_hostiles", c.woundHostiles, v -> c.woundHostiles = v),
				OptionInstance.createBoolean("fluidgore.option.rotten_undead", c.rottenUndead, v -> c.rottenUndead = v),
				OptionInstance.createBoolean("fluidgore.option.inventory_sorting", c.inventorySorting, v -> c.inventorySorting = v),
				OptionInstance.createBoolean("fluidgore.option.butchery", c.butchery, v -> c.butchery = v),
				OptionInstance.createBoolean("fluidgore.option.fractures", c.fractures, v -> c.fractures = v),
				OptionInstance.createBoolean("fluidgore.option.wound_players", c.woundPlayers, v -> c.woundPlayers = v)
		);

		this.list.addHeader(Component.translatable("fluidgore.header.suffering"));
		this.list.addBig(enumSlider("fluidgore.option.suffering_level", GoreLevel.values(), c.sufferingLevel, GoreLevel::translationKey, v -> c.sufferingLevel = v));
		this.list.addSmall(
				scaledSlider("fluidgore.option.scream_volume", 0, 40, 5, c.screamVolume, "fluidgore.unit.percent", v -> c.screamVolume = v),
				scaledSlider("fluidgore.option.max_screams", 1, 32, 1, c.maxScreams, null, v -> c.maxScreams = v),
				OptionInstance.createBoolean("fluidgore.option.herd_panic", c.herdPanic, v -> c.herdPanic = v),
				OptionInstance.createBoolean("fluidgore.option.personality", c.personality, v -> c.personality = v),
				OptionInstance.createBoolean("fluidgore.option.ecology", c.ecology, v -> c.ecology = v),
				scaledSlider("fluidgore.option.grief", 0, 60, 30, c.griefSeconds, "fluidgore.unit.seconds", v -> c.griefSeconds = v),
				scaledSlider("fluidgore.option.death_launch", 0, 30, 10, c.deathLaunchPercent, "fluidgore.unit.percent", v -> c.deathLaunchPercent = v),
				scaledSlider("fluidgore.option.corpse_linger", 1, 120, 5, c.corpseLingerSeconds, "fluidgore.unit.seconds", v -> c.corpseLingerSeconds = v),
				OptionInstance.createBoolean("fluidgore.option.red_death", c.redDeath, v -> c.redDeath = v),
				OptionInstance.createBoolean("fluidgore.option.death_pop", c.deathPop, v -> c.deathPop = v),
				scaledSlider("fluidgore.option.death_pop_chance", 0, 20, 5, c.deathPopPercent, "fluidgore.unit.percent", v -> c.deathPopPercent = v),
				OptionInstance.createBoolean("fluidgore.option.burns", c.burns, v -> c.burns = v)
		);
	}

	@Override
	public void removed() {
		super.removed();
		GoreConfig.save();
	}

	/** A slider whose raw steps are multiplied by {@code step}, so large ranges stay easy to set precisely. */
	private static OptionInstance<Integer> scaledSlider(String key, int minSteps, int maxSteps, int step, int current, String unitKey, IntConsumer setter) {
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
