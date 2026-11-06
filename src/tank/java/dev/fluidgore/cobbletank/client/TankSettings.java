package dev.fluidgore.cobbletank.client;

import dev.fluidgore.cobbletank.TankConfig;
import dev.fluidgore.ui.GoreSettingsScreen;
import java.util.List;

import static dev.fluidgore.ui.GoreSettingsScreen.scaledSlider;
import static dev.fluidgore.ui.GoreSettingsScreen.toggle;

/** The Cobble Tank's section of the Fluid Gore settings screen. Every value is read on the server. */
final class TankSettings {
	private TankSettings() {
	}

	static void register() {
		GoreSettingsScreen.addSection(new GoreSettingsScreen.AddonSection("cobbletank.header.tank", () -> {
			TankConfig c = TankConfig.get();
			return List.of(
					toggle("cobbletank.option.tank_breaks_glass", c.tankBreaksGlass, v -> c.tankBreaksGlass = v),
					scaledSlider("cobbletank.option.tank_litres", 1, 200, 10, c.tankLitres, "fluidgore.unit.litres", v -> c.tankLitres = v),
					scaledSlider("cobbletank.option.tank_psi", 1, 40, 500, c.tankPsi, "fluidgore.unit.psi", v -> c.tankPsi = v),
					scaledSlider("cobbletank.option.tank_fire_seconds", 4, 120, 1, c.tankFireSeconds, "fluidgore.unit.seconds", v -> c.tankFireSeconds = v),
					scaledSlider("cobbletank.option.tank_wave_percent", 1, 100, 1, c.tankWavePercent, "fluidgore.unit.percent", v -> c.tankWavePercent = v),
					scaledSlider("cobbletank.option.fragment_rays", 16, 256, 1, c.fragmentRayBudget, null, v -> c.fragmentRayBudget = v),
					scaledSlider("cobbletank.option.tank_fragment_range", 4, 96, 1, c.tankFragmentRange, "fluidgore.unit.blocks", v -> c.tankFragmentRange = v));
		}, TankConfig::save));
	}
}
