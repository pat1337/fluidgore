package dev.fluidgore.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.fluidgore.FluidGore;
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
	/** How finely blood is drawn: 16 pixels to a block like the game's own textures, finer, or smooth. */
	public BloodResolution bloodResolution = BloodResolution.X16;
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
	/** Drops that land at a slant leave stretched marks with tails pointing the way they flew (2.17). */
	public boolean directionalSpatter = true;
	/** A bleeding body dragged or sliding over the ground smears a trail behind it (2.17). */
	public boolean dragTrails = true;
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
	/** How long a body stays on the ground after its death throes, in seconds. Unused while the server keeps bodies. */
	public int corpseLingerSeconds = 10;
	/**
	 * Off: the vanilla death, made gory: the body goes red and keels over onto its side in one second, bleeding out as
	 * it lies there, and stays still. On: the realistic death throes (kicking, gasps, a rigid phase) instead.
	 */
	public boolean deathThroes = false;
	/**
	 * Server side (the host of a LAN world, or a server running Fluid Gore): the bodies of the {@link #sufferingAnimals}
	 * are kept where they fell instead of vanishing, until someone cleans them up or butchers them apart.
	 */
	public boolean keepBodies = true;
	/** Server side: most kept bodies per dimension. The oldest goes up in smoke when another one falls. */
	public int maxKeptBodies = 64;
	/**
	 * A dead body lying over a drop (stairs, a ledge, the lip of a pit) slumps over the edge and slides and tumbles down
	 * until it lies on the flat. Kept bodies are moved by the server, so everyone sees them where they end up.
	 */
	public boolean bodySlide = true;
	/**
	 * Server side: a burning body sets fire to what it touches. An animal running on fire lights the dry grass, hay, wool
	 * and wood it brushes past, and a body that died burning burns on where it lies, on its own fat, for as long as its
	 * weight says (see {@code physics/BodyMass}), lighting what it lies against. Real fire, spreading as vanilla fire
	 * does, so the game rule {@code fire_spread_radius_around_player} still decides where fire may spread. Asked for by
	 * the user in 3.2: "a fired pig should probably set a house on fire".
	 */
	public boolean burningSpreadsFire = true;
	/**
	 * Dead bodies are articulated ragdolls: every leg bends at the knee or hock, the trunk at the waist and the head on a
	 * neck, each joint as far as a real one goes, and the whole body falls, folds, drapes over steps and edges and comes
	 * to rest under its own weight, never through a block. Off: the old one-piece ragdoll.
	 */
	public boolean articulatedRagdoll = false;
	/** Articulated ragdolls bend their legs at the knee and hock (and elbows) too, not only where the model's parts join. */
	public boolean ragdollKnees = false;
	/** Shows the segments and joints of every articulated ragdoll (green moving, blue asleep, yellow the neck). */
	public boolean ragdollDebug = false;
	/** How much each body you walk through slows you, in percent: wading through a pile of carcasses is hard going. */
	public int wadeSlowPercent = 35;
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
	/**
	 * Dead bodies can be burnt where they lie (a flint and steel or fire charge on one, or fire, a campfire or lava under
	 * it): the hide sears and splits, the fat catches and burns on its own under thick black smoke, the limbs draw in,
	 * and it burns down to a charred, then white skeleton, slower and smokier the heavier the animal. Put out early, it
	 * stays charred; blows knock the char off the bone and break up the bones until nothing is left but ash.
	 */
	public boolean cremation = true;
	/** How fast bodies burn, in percent: 100 burns a chicken to the bone in about 16 seconds, a sheep in 50, a cow in 100. */
	public int cremationSpeedPercent = 100;
	/**
	 * Players on fire burn down layer by layer: the clothes and hair go, the skin blisters, chars and burns through, the
	 * fat melts and drips, the muscle cooks, and the organs and bones show (as far as the gore level and how near death
	 * they are allow). Dying on fire, the body stays and burns down to a charred skeleton.
	 */
	public boolean melting = true;
	/** The same for zombies, piglins and every other mob drawn as a person (they burn in the sun: a lot of smoke at dawn). */
	public boolean meltingMobs = false;
	/**
	 * Sheep, pigs and cows burn the same way, through their own anatomy: a fleece smoulders and chars off, the hide
	 * blisters and splits, their fat (hard white suet, soft lard, yellow beef fat) runs, the meat cooks, and the bones,
	 * the organs and the hooves show; a body burns on to a skeleton.
	 */
	public boolean meltingAnimals = true;
	/**
	 * Look at a piece of a body lying about (an organ, a bone, a scrap, a severed limb, a butchered joint) and what it is
	 * shows over the hotbar: whose, what, which side, seared or charred.
	 */
	public boolean identifyRemains = true;
	/**
	 * Pieces of a body react to what they lie in, whoever cut or blew them off: in a fire or on lava an organ, a scrap or a
	 * severed limb steams, cooks, dries, chars and catches (fat burns on its own flame), water puts it out, and what heat
	 * did stays done. A charred piece crumbles when it lands hard; a cooked one has no blood left to leave.
	 */
	public boolean pieceReactions = true;
	/**
	 * Developer setting, not on the settings screen: write every recorded effect (which event, through which channel,
	 * did what to which body or piece; see {@code event/Events}) to the log.
	 */
	public boolean traceEvents = false;
	/**
	 * Extreme gore level only: bodies that stay (kept bodies, severed pieces) rot as a real carcass does, by the
	 * Minecraft day: rigor, bloat, rupture, maggots, bones. Faster for small bodies and in hot biomes, slower in water,
	 * not at all in the snow; in deserts they dry out into mummies instead.
	 */
	public boolean bodyDecay = true;
	/** Rot speed multiplier: 1 is real time (a Minecraft day for a day). */
	public int decaySpeed = 1;
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
	/**
	 * Every animal that bleeds (not only sheep and birds) carries blood on its hide: spatter from its own wounds, a
	 * neighbour's blow or a blast, and up its legs from walking or lying in a pool. Water rinses it off.
	 */
	public boolean bloodyCoats = true;
	/** Blood that reaches water clouds out in it, sinks a little and thins away, instead of vanishing. */
	public boolean bloodInWater = true;
	/**
	 * Birds are feathered: blows knock feathers out (wet with blood from a wound), dying birds shed them in their
	 * wing-beats, and they drift down and lie where they land, soaking up blood and rain.
	 */
	public boolean feathers = true;
	/** Cap on loose feathers in the world. */
	public int maxFeathers = 1500;
	/**
	 * A chicken's feathers soak up blood round its wounds and redden as it bleeds, go dark and slick in the rain, and a
	 * plucked or soaked bird shows how skinny it is under them.
	 */
	public boolean bloodyFeathers = true;
	/**
	 * Chickens die as chickens do: in a frenzy of wing-beats that throws the body about, shaped by what killed it (a
	 * head off, a cut throat, a wrung neck, a blow to the head).
	 */
	public boolean chickenDeaths = true;
	/** Ender blood (and the raw cuts in an enderman) drawn at full brightness: it glows in the dark and with shaders. */
	public boolean glowingEnderBlood = true;
	/** Blunt blows add up on the bone they land on until it breaks: skulls cave in, ribs stove in, limbs snap and come off. */
	public boolean fractures = true;
	/** Blood from an open wound runs down the skin below it and dries there: a cut throat down the chest and forelegs. */
	public boolean bloodRuns = true;
	/** Skin goes pale as a body loses blood: a bled-out pig is a washed-out, pale pink beside a living one. Bare skin only. */
	public boolean pallor = true;
	/** Wounds on other players. Everyone with the mod sees the same wounds (see trigger/Fate). */
	public boolean woundPlayers = true;
	/** Wounds close as health comes back: meat to a gash, a gash to a bruise, a bruise gone; broken bones knit. */
	public boolean woundsHeal = true;
	/** A living body's wounds stop dripping this many seconds after the last blow (bodies of the dead still bleed out). */
	public int woundClotSeconds = 30;
	/** Severed limbs and heads keep the armour they wore: a helmet stays on the head, boots on the legs. */
	public boolean armorOnParts = true;
	/** Player-on-player blades cut deeper and bleed harder, and a kill on High or Extreme takes more than the struck part. */
	public boolean brutalPvp = true;
	/** Your own injuries on screen: chunky pixel blood thrown across it from the side a blow came from, running down. */
	public boolean hurtScreen = true;
	/** Near death the screen closes in red and your heart pounds, faster the closer you are. */
	public boolean lowHealthHeartbeat = true;
	/** How hard a blow knocks your view about, in percent; 0 turns it off. A low-health sway comes with it. */
	public int staggerPercent = 100;
	/**
	 * A 2x2 of TNT is a carbon-overwrapped pressure vessel (by default 200 litres at 10,000 psi). When it
	 * goes, bodies are hurt by the real overpressure where they stood: shredded, torn apart, lungs and eardrums burst,
	 * fragments driven in; you are told what it did to you. Cosmetic: who dies is still vanilla's TNT damage. Experimental
	 * and off by default (3.3): the crafted Cobble Tank is the real pressure vessel; this guesses one from TNT blocks.
	 */
	public boolean pressureVessel = false;
	/** The vessel's volume, litres. */
	public int vesselLitres = 200;
	/** The vessel's pressure, psi. */
	public int vesselPsi = 10_000;
	/** A blast loud enough leaves you deaf for a while (other sounds muffled) with your ears ringing. */
	public boolean blastDeafness = true;
	/** A broken or cut leg makes the body limp (your own view hitches on that step), a hurt arm hangs, a belly wound is clutched. */
	public boolean limp = true;
	/** Blood soaks through clothes and smears armour over every open wound on a player. */
	public boolean bloodyClothes = true;
	/** Severed parts can be punched, kicked, picked up, thrown and stomped. */
	public boolean playWithParts = true;
	/** How hard the killing blow throws a body in the direction it was struck, in percent. 0 turns it off. */
	public int deathLaunchPercent = 100;
	/**
	 * Wet blood on a smooth floor is slippery underfoot, in percent: 100 makes a film of fresh blood on glass, ice,
	 * polished stone or metal as slippery as ice, and plain stone and planks a little slippery. Only your own footing,
	 * in your own game. 0 turns it off.
	 */
	public int slipperyBlood = 100;
	/**
	 * An empty bucket used on standing blood takes it up, and a bucket holding blood can be tipped out again. Sneak to
	 * use the bucket as usual.
	 */
	public boolean bloodBuckets = true;
	/** Litres a bucket takes. The game's buckets take a whole block of liquid, which is 1000 litres (264 gallons). */
	public int bucketLitres = 1000;
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
	/**
	 * Litres of blood in each kind of mob, by entity id ({@code "minecraft:cow": 37.0}). Mobs not listed have their real
	 * blood volume: body weight times the species' blood per kilogram (see {@code BloodSupply}).
	 */
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
			handOffTankSettings();
			try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				GoreConfig loaded = GSON.fromJson(reader, GoreConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (IOException | JsonParseException e) {
				FluidGore.LOGGER.error("Could not read {}, using defaults", PATH, e);
				instance = new GoreConfig();
			}
		}

		instance.sanitize();
		save();
	}

	/** The Cobble Tank's settings that lived in this file before 4.0, when the tank became its own add-on. */
	private static final List<String> TANK_KEYS = List.of("tankLitres", "tankPsi", "tankFireSeconds", "tankWavePercent",
			"fragmentRayBudget", "tankFragmentRange", "tankBreaksGlass");

	/**
	 * 4.0: the Cobble Tank's settings moved to {@code config/cobbletank.json}. They are copied there once, before this
	 * file is rewritten without them, so a server owner's tank settings survive the split whether or not the add-on is
	 * installed yet.
	 */
	private static void handOffTankSettings() {
		Path tank = PATH.resolveSibling("cobbletank.json");
		if (Files.exists(tank)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			if (!(com.google.gson.JsonParser.parseReader(reader) instanceof com.google.gson.JsonObject old)) {
				return;
			}

			com.google.gson.JsonObject moved = new com.google.gson.JsonObject();
			for (String key : TANK_KEYS) {
				if (old.has(key)) moved.add(key, old.get(key));
			}

			if (!moved.isEmpty()) {
				try (Writer writer = Files.newBufferedWriter(tank, StandardCharsets.UTF_8)) {
					GSON.toJson(moved, writer);
				}
			}
		} catch (IOException | RuntimeException e) {
			FluidGore.LOGGER.warn("Could not move the old Cobble Tank settings to {}", tank, e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			FluidGore.LOGGER.error("Could not write {}", PATH, e);
		}
	}

	/** Repairs values that a hand-edited file may have broken (missing enums, negative caps, null lists). */
	private static final int CONFIG_VERSION = 9;

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

		if (configVersion < 7) {
			// 2.15: blood amounts are litres now, not a relative measure; the old numbers would mean a few spoonfuls.
			if (bloodAmounts != null) bloodAmounts.clear();
		}

		if (configVersion < 8) {
			// 3.3: the 2x2 TNT vessel is experimental and off by default; the crafted tank is the real vessel. It was on by
			// default before, so an old file's true is that default, not a choice.
			pressureVessel = false;
		}

		configVersion = CONFIG_VERSION;
		if (goreLevel == null) goreLevel = GoreLevel.MEDIUM;
		if (bloodResolution == null) bloodResolution = BloodResolution.X16;
		if (bloodColor == null) bloodColor = BloodColor.RED;
		if (monsterBloodColor == null) monsterBloodColor = BloodColor.RED;
		persistenceSeconds = clamp(persistenceSeconds, 10, 1800);
		maxBloodDecals = clamp(maxBloodDecals, 50, 10000);
		maxActiveCellsPerTick = clamp(maxActiveCellsPerTick, 8, 1024);
		maxDroplets = clamp(maxDroplets, 50, 5000);
		partLifetimeSeconds = clamp(partLifetimeSeconds, 5, 3600);
		woundClotSeconds = clamp(woundClotSeconds, 5, 600);
		slipperyBlood = clamp(slipperyBlood, 0, 100);
		bucketLitres = clamp(bucketLitres, 1, 1000);
		staggerPercent = clamp(staggerPercent, 0, 200);
		vesselLitres = clamp(vesselLitres, 10, 1000);
		vesselPsi = clamp(vesselPsi, 500, 20_000);
		maxDetachedParts = clamp(maxDetachedParts, 1, 256);
		maxGiblets = clamp(maxGiblets, 0, Giblets.CAPACITY);
		maxFeathers = clamp(maxFeathers, 0, dev.fluidgore.dismember.Feathers.CAPACITY);
		if (!(heavyHitDamage > 0.0F)) heavyHitDamage = 6.0F;
		if (sufferingLevel == null) sufferingLevel = GoreLevel.MEDIUM;
		screamVolume = clamp(screamVolume, 0, 200);
		maxScreams = clamp(maxScreams, 1, 32);
		corpseLingerSeconds = clamp(corpseLingerSeconds, 3, 600);
		maxKeptBodies = clamp(maxKeptBodies, 1, 512);
		wadeSlowPercent = clamp(wadeSlowPercent, 0, 80);
		cremationSpeedPercent = clamp(cremationSpeedPercent, 10, 1000);
		decaySpeed = clamp(decaySpeed, 1, 100);
		griefSeconds = clamp(griefSeconds, 0, 3600);
		if (sufferingAnimals == null) sufferingAnimals = new ArrayList<>();
		if (bladedItems == null) bladedItems = new ArrayList<>();
		deathLaunchPercent = clamp(deathLaunchPercent, 0, 300);
		deathPopPercent = clamp(deathPopPercent, 0, 100);
		if (cleaningItems == null) cleaningItems = new ArrayList<>();
		if (boneEntities == null) boneEntities = new ArrayList<>();
		if (bloodlessEntities == null) bloodlessEntities = new ArrayList<>();
		// A hand-edited [null] would crash whatever trims the entries (the first death on a server).
		for (List<String> list : List.of(sufferingAnimals, bladedItems, cleaningItems, boneEntities, bloodlessEntities)) {
			list.removeIf(java.util.Objects::isNull);
		}

		if (bloodAmounts == null) bloodAmounts = new java.util.HashMap<>();
		bloodAmounts.keySet().removeIf(java.util.Objects::isNull);
		bloodAmounts.values().removeIf(v -> v == null || !(v >= 0.0F));
		bloodAmounts.replaceAll((k, v) -> Math.min(v, 500.0F));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
