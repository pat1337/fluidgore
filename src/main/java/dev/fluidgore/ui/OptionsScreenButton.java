package dev.fluidgore.ui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;

/** Adds a small "Fluid Gore..." button to the top-left corner of the vanilla Options screen. */
public final class OptionsScreenButton {
	private OptionsScreenButton() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof OptionsScreen) {
				Button button = Button.builder(Component.translatable("fluidgore.settings.button"),
								b -> client.gui.setScreen(new GoreSettingsScreen(screen)))
						.bounds(6, 6, 90, 20)
						.build();
				Screens.getWidgets(screen).add(button);
			}
		});
	}
}
