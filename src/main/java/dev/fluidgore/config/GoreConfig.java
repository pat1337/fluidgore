package dev.fluidgore.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.fluidgore.FluidGoreClient;
import dev.fluidgore.dismember.Giblets;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/**
 * User settings, stored as {@code config/fluidgore.json}. Fields are read directly by the effect systems every tick,
 * so edits made in the settings screen apply immediately without a restart.
 */
public final class GoreConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("fluidgore.json");

	private static GoreConfig instance = new GoreConfig();

	public GoreLevel goreLevel = GoreLevel.MEDIUM;
	public boolean affectPlayers = true;
	public boolean affectMobs = true;
	public boolean affectAnimals = true;
	/** Look of splats, puddles and droplets. */
	public BloodStyle bloodStyle = BloodStyle.REDSTONE;
	/** Flying chunks (redstone-block crumbs and meat bits) on heavy hits and kills. */
	public boolean goreChunks = true;
	/** Arterial spurts: pulsing jets of blood from bad wounds. */
	public boolean arterialSpurts = true;
	/** Wet crunching sounds on heavy hits, kills and dismemberment. */
	public boolean goreSounds = true;
	/** Blood of players and passive creatures. */
	public BloodColor bloodColor = BloodColor.RED;
	/** Blood of hostile mobs (anything implementing {@code Enemy}). */
	public BloodColor monsterBloodColor = BloodColor.RED;
	/** Seconds a decal or puddle stays at Medium before it starts fading. Scaled by the gore level. */
	public int persistenceSeconds = 180;
	/** Hard cap on splat decals plus puddle cells; the oldest are removed first. */
	public int maxBloodDecals = 1500;
	public boolean wallSplatter = true;
	public boolean footprints = true;
	public boolean rainWashesBlood = true;
	/** Fluid simulation budget: puddle cells processed per client tick. */
	public int maxActiveCellsPerTick = 64;
	/** Cap on flying droplets simulated at once. */
	public int maxDroplets = 600;
	/** Seconds a detached body part stays before fading. Scaled by the gore level. */
	public int partLifetimeSeconds = 600;
	/** Cap on detached parts in the world. */
	public int maxDetachedParts = 160;
	/**
	 * A blast that kills from close by tears the body into pieces of muscle, fat, bone, hide, gut and organs instead
	 * of leaving it to die. Medium gore level and up; never players.
	 */
	public boolean blastGore = true;
	/** Cap on the small pieces of flesh and bone (from blasts, picks and axes) in the world. */
	public int maxGiblets = 3000;
	/** Non-lethal hits at or above this damage count as heavy and may sever a part from a mob. */
	public float heavyHitDamage = 6.0F;
	/** Items that can cut. Entries are item ids ({@code minecraft:iron_sword}) or item tags ({@code #minecraft:swords}). */
	public List<String> bladedItems = new ArrayList<>(List.of("#minecraft:swords", "#minecraft:axes"));
	/**
	 * Animal Suffering intensity, independent of {@link #goreLevel}: screams, trembling, herd panic, death throes.
	 * Off disables it.
	 */
	public GoreLevel sufferingLevel = GoreLevel.MEDIUM;
	/** Scream volume in percent (also scaled by the game's Friendly Creatures volume). */
	public int screamVolume = 100;
	/** Maximum scream sounds playing at once. */
	public int maxScreams = 8;
	/** Nearby animals of the same kind panic when one is hurt or killed. */
	public boolean herdPanic = true;
	/** Every animal has its own voice, nerve and way of dying, derived from its species and UUID. */
	public boolean personality = true;
	/**
	 * Animals remember killing for the session and react by role: prey cower, herds call, mothers search, predators
	 * take interest, and bodies draw flies. Pose, sound and particles only.
	 */
	public boolean ecology = true;
	/**
	 * How long a mother grieves for her killed young (or a young animal for its killed mother), in seconds at Medium
	 * suffering. It calls to the place it happened, less and less often. 0 turns grief off. Needs {@link #ecology}.
	 */
	public int griefSeconds = 600;
	/** How long a body stays on the ground after its death throes, in seconds. */
	public int corpseLingerSeconds = 10;
	/** Dying bodies stay tinted red, like vanilla's death, until they are gone. */
	public boolean redDeath = true;
	/**
	 * When a body finally goes, it pops: a puff of smoke, and what is left of it bursts into its head and limbs and a
	 * few scraps of flesh, which can still be kicked about for a little while.
	 */
	public boolean deathPop = true;
	/**
	 * Percent of deaths that end with the body bursting apart (with Death Pop on). The rest die one of the nine
	 * realistic deaths and are left lying.
	 */
	public int deathPopPercent = 5;
	/** Animals on fire char from seared to black, smoke and smoulder, and leave a scorch mark where they burn to death. */
	public boolean burns = true;
	/** Version of the saved file, so changed defaults can reach configs saved by older versions. */
	public int configVersion;
	/**
	 * Hack model: blows open wounds on the exact spot of the body they hit, deepening through bruise, gash, meat and
	 * bone, and a blade through a wound that cannot get deeper takes that part off.
	 */
	public boolean wounds = true;
	/** Wounds on hostile mobs as well as animals. */
	public boolean woundHostiles = true;
	/**
	 * Zombies and their kin are rotting meat: rotten wounds with pus and maggots, and dark, thick, slow rotten blood
	 * instead of the monster blood colour.
	 */
	public boolean rottenUndead = true;
	/** Sort buttons on chests and the inventory, and middle-click to sort (plain inventory clicks, works on any server). */
	public boolean inventorySorting = true;
	/** Plucking dead birds, splitting carcasses and dicing the pieces with a blade. */
	public boolean butchery = true;
	/** Sheep's wool soaks up blood: stains round wounds, the coat reddening as it bleeds, the herd round it spattered. */
	public boolean bloodyWool = true;
	/** Blunt blows add up on the bone they land on until it breaks: skulls cave in, ribs stove in, limbs snap and come off. */
	public boolean fractures = true;
	/** Wounds on other players. Everyone with the mod sees the same wounds (see trigger/Fate). */
	public boolean woundPlayers = true;
	/** Severed parts can be punched, kicked, picked up, thrown and stomped. */
	public boolean playWithParts = true;
	/** How hard the killing blow throws a body in the direction it was struck, in percent. 0 turns it off. */
	public int deathLaunchPercent = 100;
	/** Hold a cleaning item and use it on a surface to scrub blood off. */
	public boolean cleaning = true;
	/** Item ids that scrub blood away when used (sneak to use them normally). */
	public List<String> cleaningItems = new ArrayList<>(List.of("minecraft:sponge", "minecraft:wet_sponge", "minecraft:brush"));
	/** Entity ids that scream, panic and get death throes. */
	public List<String> sufferingAnimals = new ArrayList<>(List.of(
			"minecraft:cow",
			"minecraft:mooshroom",
			"minecraft:pig",
			"minecraft:sheep",
			"minecraft:chicken",
			"minecraft:goat",
			"minecraft:horse",
			"minecraft:donkey",
			"minecraft:mule",
			"minecraft:llama",
			"minecraft:trader_llama",
			"minecraft:camel",
			"minecraft:rabbit",
			"minecraft:wolf",
			"minecraft:fox",
			"minecraft:cat"
	));
	/** Entities made of bone: no blood; hits chip and crack them and severed bones shatter. Wins over bloodlessEntities. */
	public List<String> boneEntities = new ArrayList<>(List.of(
			"minecraft:skeleton",
			"minecraft:stray",
			"minecraft:wither_skeleton",
			"minecraft:bogged",
			"minecraft:parched",
			"minecraft:skeleton_horse"
	));
	/** Per-mob blood measure overrides, by entity id (1 = average). Mobs not listed use the built-in amounts. */
	public java.util.Map<String, Float> bloodAmounts = new java.util.HashMap<>();
	/** Entities that never bleed or lose parts (constructs, decorations). */
	public List<String> bloodlessEntities = new ArrayList<>(List.of(
			"minecraft:armor_stand",
			"minecraft:iron_golem",
			"minecraft:snow_golem",
			"minecraft:copper_golem",
			"minecraft:shulker",
			"minecraft:blaze",
			"minecraft:breeze",
			"minecraft:vex",
			"minecraft:allay",
			"minecraft:creaking",
			"minecraft:wither",
			"minecraft:ender_dragon",
			"claysoldiers:clay_soldier"
	));

	public static GoreConfig get() {
		return instance;
	}

	public static void load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				GoreConfig loaded = GSON.fromJson(reader, GoreConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (IOException | JsonParseException e) {
				FluidGoreClient.LOGGER.error("Could not read {}, using defaults", PATH, e);
				instance = new GoreConfig();
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
			FluidGoreClient.LOGGER.error("Could not write {}", PATH, e);
		}
	}

	/** Repairs values that a hand-edited file may have broken (missing enums, negative caps, null lists). */
	private static final int CONFIG_VERSION = 6;

	private void sanitize() {
		if (configVersion < 2) {
			// 1.7: bodies lingered 45 s by default, which was too long. Only an untouched default is changed.
			if (corpseLingerSeconds == 45) corpseLingerSeconds = 10;
		}

		if (configVersion < 3) {
			// 2.0: wounds on players were off because each screen showed different ones. Now every player sees the same.
			woundPlayers = true;
		}

		if (configVersion < 4) {
			// 2.2: monsters got their own wounds (rotting zombies, anatomical slashes), so they are shown by default.
			woundHostiles = true;
		}

		if (configVersion < 5) {
			// 2.7: dicing a carcass makes many small pieces; 64 lost the first animal's before the second was done.
			if (maxDetachedParts == 64) maxDetachedParts = 160;
		}

		if (configVersion < 6) {
			// 2.8: severed limbs stay for ten minutes by default instead of one.
			if (partLifetimeSeconds == 60) partLifetimeSeconds = 600;
		}

		configVersion = CONFIG_VERSION;
		if (goreLevel == null) goreLevel = GoreLevel.MEDIUM;
		if (bloodStyle == null) bloodStyle = BloodStyle.REDSTONE;
		if (bloodColor == null) bloodColor = BloodColor.RED;
		if (monsterBloodColor == null) monsterBloodColor = BloodColor.RED;
		persistenceSeconds = clamp(persistenceSeconds, 10, 1800);
		maxBloodDecals = clamp(maxBloodDecals, 50, 10000);
		maxActiveCellsPerTick = clamp(maxActiveCellsPerTick, 8, 1024);
		maxDroplets = clamp(maxDroplets, 50, 5000);
		partLifetimeSeconds = clamp(partLifetimeSeconds, 5, 3600);
		maxDetachedParts = clamp(maxDetachedParts, 1, 256);
		maxGiblets = clamp(maxGiblets, 0, Giblets.CAPACITY);
		if (!(heavyHitDamage > 0.0F)) heavyHitDamage = 6.0F;
		if (sufferingLevel == null) sufferingLevel = GoreLevel.MEDIUM;
		screamVolume = clamp(screamVolume, 0, 200);
		maxScreams = clamp(maxScreams, 1, 32);
		corpseLingerSeconds = clamp(corpseLingerSeconds, 3, 600);
		griefSeconds = clamp(griefSeconds, 0, 3600);
		if (sufferingAnimals == null) sufferingAnimals = new ArrayList<>();
		if (bladedItems == null) bladedItems = new ArrayList<>();
		deathLaunchPercent = clamp(deathLaunchPercent, 0, 300);
		deathPopPercent = clamp(deathPopPercent, 0, 100);
		if (cleaningItems == null) cleaningItems = new ArrayList<>();
		if (boneEntities == null) boneEntities = new ArrayList<>();
		if (bloodlessEntities == null) bloodlessEntities = new ArrayList<>();
		if (bloodAmounts == null) bloodAmounts = new java.util.HashMap<>();
		bloodAmounts.values().removeIf(v -> v == null || !(v >= 0.0F));
		bloodAmounts.replaceAll((k, v) -> Math.min(v, 10.0F));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
