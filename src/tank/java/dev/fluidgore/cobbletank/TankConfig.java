package dev.fluidgore.cobbletank;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The Cobble Tank's settings, stored as {@code config/cobbletank.json}. They are read on the server, where the tank's
 * physics run. Fluid Gore hands over the values an older {@code fluidgore.json} held, once.
 */
public final class TankConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("cobbletank.json");

	private static TankConfig instance = new TankConfig();

	/** A newly placed tank's gas volume, litres. Only new tanks take a changed size. */
	public int tankLitres = 600;
	/** A newly placed tank's pressure when full, psi. */
	public int tankPsi = 10_000;
	/** Game time scale for shell heating and permanent fire damage, not a real fire rating. */
	public int tankFireSeconds = 12;
	/** Chosen fraction of the expansion energy that goes into the blast wave, percent. */
	public int tankWavePercent = 35;
	/** How many casing fragment paths the server traces per burst. */
	public int fragmentRayBudget = 192;
	/** How far fragments can fly, blocks. */
	public int tankFragmentRange = 48;
	/** A burst breaks glass and blows doors open around it. */
	public boolean tankBreaksGlass = true;

	public static TankConfig get() {
		return instance;
	}

	public static void load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				TankConfig loaded = GSON.fromJson(reader, TankConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (IOException | JsonParseException e) {
				CobbleTank.LOGGER.error("Could not read {}, using defaults", PATH, e);
				instance = new TankConfig();
			}
		}

		instance.sanitize();
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			CobbleTank.LOGGER.error("Could not write {}", PATH, e);
		}
	}

	private void sanitize() {
		tankLitres = clamp(tankLitres, 10, 2000);
		tankPsi = clamp(tankPsi, 500, 20_000);
		tankFireSeconds = clamp(tankFireSeconds, 4, 120);
		tankWavePercent = clamp(tankWavePercent, 1, 100);
		fragmentRayBudget = clamp(fragmentRayBudget, 16, 256);
		tankFragmentRange = clamp(tankFragmentRange, 4, 96);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
