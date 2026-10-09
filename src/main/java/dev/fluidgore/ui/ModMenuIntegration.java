package dev.fluidgore.ui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Loaded by Fabric only when Mod Menu is installed (it is the only class referencing Mod Menu). */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return GoreSettingsScreen::new;
	}
}
