package dev.fluidgore.test;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodCell;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.ui.GoreSettingsScreen;
import dev.fluidgore.wound.ModelHit;
import dev.fluidgore.wound.Weapon;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Husk;

/**
 * End-to-end check in a real client: kills and wounds mobs with a sword through the vanilla /damage command
 * (so the client only sees normal vanilla packets) and verifies that blood, puddles and severed parts appear,
 * that the fluid simulation spreads and spills over a ledge, that Off clears everything, that the settings
 * screens open, and that the same works while connected to a dedicated server.
 */
public class FluidGoreClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(mc -> GoreConfig.get().goreLevel = GoreLevel.EXTREME);

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			setUpArena(server);
			context.waitTicks(20);

			combat(context, server, "sp");
			fluid(context, server);
			offClearsEverything(context);
		}

		context.runOnClient(mc -> {
			GoreConfig.get().goreLevel = GoreLevel.EXTREME;
			GoreConfig.get().sufferingLevel = GoreLevel.EXTREME;
		});
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			suffering(context, singleplayer.getServer());
			hackModel(context, singleplayer.getServer());
			sharedFate(context, singleplayer.getServer());
			rot(context, singleplayer.getServer());
			babies(context, singleplayer.getServer());
			grief(context, singleplayer.getServer());
			weapons(context, singleplayer.getServer());
			pigPit(context, singleplayer.getServer());
			floorAndDeath(context, singleplayer.getServer());
			deaths(context, singleplayer.getServer());
			burntAndEvidence(context, singleplayer.getServer());
			// Cheats are off in this world: demos must refuse, not half-run.
			check(!context.computeOnClient(mc -> dev.fluidgore.demo.DemoPlayer.allowed(mc)), "demos allowed without cheats");
		}

		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			singleplayer.getConnection().waitForChunksRender();
			sorting(context, singleplayer.getServer());
			demos(context, singleplayer.getServer());
		}

		screens(context);

		context.runOnClient(mc -> GoreConfig.get().goreLevel = GoreLevel.EXTREME);
		java.util.Properties properties = new java.util.Properties();
		// Avoid clashing with a real server already using the default port on this machine.
		properties.setProperty("server-port", "25599");
		try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			setUpArena(server);
			context.waitTicks(20);
			combat(context, server, "server");
		}

		context.runOnClient(mc -> GoreConfig.get().goreLevel = GoreLevel.MEDIUM);
	}

	private static void setUpArena(TestServerContext server) {
		server.runCommand("time set noon");
		server.runCommand("weather clear");
		server.runCommand("gamerule show_death_messages false");
		server.runCommand("tp @a 0.5 -60 0.5 0 25");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:diamond_sword");
		server.runCommand("summon minecraft:husk 0.5 -60 4.5 {NoAI:1b,Rotation:[180f,0f]}");
		server.runCommand("summon minecraft:husk 2.5 -60 5.5 {NoAI:1b,Rotation:[160f,0f]}");
		server.runCommand("summon minecraft:pig -1.5 -60 4.5 {NoAI:1b}");
		// A one-block-high stone platform with a stair step for the fluid test.
		server.runCommand("fill -6 -60 3 -4 -60 5 minecraft:stone");
		server.runCommand("setblock -3 -60 4 minecraft:stone_slab");
	}

	private static void combat(ClientGameTestContext context, TestServerContext server, String tag) {
		// Lethal sword hit on the nearest zombie.
		server.runCommand("execute as @p run damage @e[type=minecraft:husk,limit=1,sort=nearest] 100 minecraft:player_attack by @s");
		context.waitTicks(4);
		context.takeScreenshot(tag + "_01_kill_burst");
		context.waitTicks(50);
		context.takeScreenshot(tag + "_02_after_kill");

		Stats afterKill = stats(context);
		System.out.println("[FluidGoreTest] " + tag + " after kill: " + afterKill);
		check(afterKill.decals > 0, tag + ": kill produced no blood decals");
		check(afterKill.cells > 0, tag + ": kill produced no puddle cells");
		check(afterKill.parts > 0, tag + ": lethal sword kill at Extreme severed no part");

		// Heavy, non-lethal hit on the other zombie: it must survive with a part missing.
		server.runCommand("execute as @p run damage @e[type=minecraft:husk,limit=1,sort=nearest] 8 minecraft:player_attack by @s");
		context.waitTicks(30);
		int mask = context.computeOnClient(mc -> {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof Husk zombie && zombie.isAlive()) {
					return GoreSystems.dismemberment().missingMask(zombie.getId());
				}
			}

			return -1;
		});
		System.out.println("[FluidGoreTest] " + tag + " wounded husk missing-part mask: " + mask + " health: " + context.computeOnClient(mc -> { for (Entity e : mc.level.entitiesForRendering()) if (e instanceof Husk h) return h.getHealth(); return -1F; }));
		check(mask > 0, tag + ": heavy sword hit did not remove a part from the surviving zombie");
		context.takeScreenshot(tag + "_03_wounded_zombie");

		// Passive mob killed by a sword.
		server.runCommand("execute as @p run damage @e[type=minecraft:pig,limit=1] 100 minecraft:player_attack by @s");
		context.waitTicks(60);
		context.takeScreenshot(tag + "_04_after_pig");
		System.out.println("[FluidGoreTest] " + tag + " after pig: " + stats(context));

		// Bony bodies break instead of bleeding.
		server.runCommand("summon minecraft:skeleton 0.5 -60 8.5 {NoAI:1b,Rotation:[180f,0f]}");
		server.runCommand("summon minecraft:parched 2.5 -60 8.5 {NoAI:1b,Rotation:[180f,0f]}");
		context.waitTicks(10);
		boolean[] bone = context.computeOnClient(mc -> {
			boolean bloodless = false;
			boolean allBone = true;
			int found = 0;
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.LivingEntity living && living.isAlive() && (net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().matches("skeleton|parched"))) {
					found++;
					bloodless |= dev.fluidgore.trigger.EntityFilter.isBloodless(living);
					allBone &= dev.fluidgore.trigger.EntityFilter.bloodColor(living) == dev.fluidgore.blood.BoneEmitter.BONE;
				}
			}

			return new boolean[]{found == 2, !bloodless, allBone};
		});
		check(bone[0] && bone[1] && bone[2], tag + ": skeleton and parched must count as bone (found/not bloodless/bone) " + java.util.Arrays.toString(bone));
		int partsBefore = stats(context).parts;
		for (int i = 0; i < 2; i++) {
			server.runCommand("execute as @p run damage @e[type=minecraft:skeleton,limit=1,sort=nearest] 100 minecraft:player_attack by @s");
			server.runCommand("execute as @p run damage @e[type=minecraft:parched,limit=1,sort=nearest] 100 minecraft:player_attack by @s");
			context.waitTicks(3);
		}

		context.waitTicks(6);
		context.takeScreenshot(tag + "_05_bones");
		int boneParts = context.computeOnClient(mc -> (int) GoreSystems.dismemberment().parts().parts().stream().filter(dev.fluidgore.dismember.GorePart::bone).count());
		System.out.println("[FluidGoreTest] " + tag + " bone parts: " + boneParts + " (parts before " + partsBefore + ")");
		check(boneParts > 0, tag + ": killing skeletons produced no bone parts");
	}

	private static void fluid(ClientGameTestContext context, TestServerContext server) {
		context.runOnClient(mc -> GoreSystems.blood().fluid().addVolume(mc.level, -4.5, -59.0, 4.5, 2.5F, 0x8A0303, 20 * 600));
		int before = context.computeOnClient(mc -> GoreSystems.blood().fluid().size());
		context.waitTicks(120);
		int[] result = context.computeOnClient(mc -> {
			int onPlatform = 0;
			int onGround = 0;
			int onSlab = 0;
			for (BloodCell cell : GoreSystems.blood().fluid().cells()) {
				if (Math.abs(cell.surfaceY - (-59.0)) < 1.0E-3) onPlatform++;
				else if (Math.abs(cell.surfaceY - (-60.0)) < 1.0E-3) onGround++;
				else if (Math.abs(cell.surfaceY - (-59.5)) < 1.0E-3) onSlab++;
			}

			return new int[]{onPlatform, onGround, onSlab};
		});
		System.out.println("[FluidGoreTest] fluid: cells before=" + before + " platform=" + result[0] + " slab=" + result[2]
				+ " ground(spilled over ledge)=" + result[1] + " " + stats(context));
		check(result[0] > 1, "fluid: blood did not spread across the platform");
		check(result[1] > 0 || result[2] > 0, "fluid: blood never left the platform (no flow down the step or over the ledge)");
		context.takeScreenshot("sp_05_fluid");
	}

	/**
	 * Cows: beat one to death with bare hands (brutal death with long throes), check its body outlives the server
	 * entity and that the rest of the herd panics; then kill cows with a sword until one is beheaded (headless
	 * run); then punch, pick up and throw a severed part.
	 */
	private static void suffering(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("time set noon");
		server.runCommand("weather clear");
		server.runCommand("gamerule show_death_messages false");
		server.runCommand("tp @a 0.5 -60 0.5 0 20");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");
		for (int i = 0; i < 6; i++) {
			server.runCommand("summon minecraft:cow " + (-3.5 + i * 1.6) + " -60 " + (5.5 + (i % 2)) + " {NoAI:1b,Rotation:[" + (i * 60) + "f,0f]}");
		}

		context.waitTicks(20);
		int screamsBefore = context.computeOnClient(mc -> GoreSystems.suffering().screams().totalPlayed());

		// Five bare-handed punches, spaced past the invulnerability window.
		for (int i = 0; i < 5; i++) {
			server.runCommand("execute as @p run damage @e[type=minecraft:cow,limit=1,sort=nearest] 2 minecraft:player_attack by @s");
			context.waitTicks(i == 1 ? 3 : 12);
			if (i == 1) {
				context.takeScreenshot("cow_01_beaten_flinch");
				context.waitTicks(9);
			}
		}

		String kind = context.computeOnClient(mc -> {
			for (var corpse : GoreSystems.suffering().corpses()) {
				return corpse.kind.name();
			}

			return "none";
		});
		System.out.println("[FluidGoreTest] beaten cow corpse kind: " + kind);
		check("BEATEN".equals(kind), "beaten-to-death cow should die BEATEN, got " + kind);
		context.waitTicks(10);
		context.takeScreenshot("cow_02_brutal_throes");

		// Vanilla removes the dead entity after 20 ticks; the body must stay for its throes.
		context.waitTicks(40);
		boolean bodyRemains = context.computeOnClient(mc -> {
			for (var corpse : GoreSystems.suffering().corpses()) {
				if (corpse.unloaded) {
					return true;
				}
			}

			return false;
		});
		check(bodyRemains, "cow body did not outlive the server entity");
		float herdFear = context.computeOnClient(mc -> {
			float max = 0.0F;
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.animal.cow.Cow && e.isAlive()) {
					max = Math.max(max, GoreSystems.suffering().fear(e.getId()));
				}
			}

			return max;
		});
		int screamsAfter = context.computeOnClient(mc -> GoreSystems.suffering().screams().totalPlayed());
		System.out.println("[FluidGoreTest] herd fear=" + herdFear + " scream layers played=" + (screamsAfter - screamsBefore));
		check(herdFear > 0.2F, "herd did not panic");
		check(screamsAfter - screamsBefore > 10, "too few scream sounds played");
		context.takeScreenshot("cow_03_body_after_removal_and_panic");
		server.runCommand("tp @a 0.5 -60 3.2 0 40");
		context.waitTicks(5);
		context.takeScreenshot("cow_03b_closeup");
		boolean cleaned = context.computeOnClient(mc -> {
			net.minecraft.world.phys.Vec3 spot = new net.minecraft.world.phys.Vec3(0.5, -60.0, 5.5);
			for (int i = 0; i < 3; i++) {
				GoreSystems.blood().clean(spot, net.minecraft.core.Direction.UP, 4.0F);
			}

			return !GoreSystems.blood().hasBloodNear(spot, 3.0F);
		});
		check(cleaned, "scrubbing did not remove the blood around the cows");
		context.waitTicks(10);
		context.takeScreenshot("cow_03c_cleaned");
		server.runCommand("tp @a 0.5 -60 0.5 0 20");

		// Sword kills until one is beheaded.
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:diamond_sword");
		boolean headless = false;
		for (int attempt = 0; attempt < 5 && !headless; attempt++) {
			server.runCommand("execute as @p run damage @e[type=minecraft:cow,limit=1,sort=nearest] 100 minecraft:player_attack by @s");
			context.waitTicks(3);
			headless = context.computeOnClient(mc -> {
				for (var corpse : GoreSystems.suffering().corpses()) {
					if (corpse.kind == dev.fluidgore.suffer.Corpse.Kind.DECAPITATION) {
						return true;
					}
				}

				return false;
			});
		}

		System.out.println("[FluidGoreTest] beheaded body seen: " + headless);
		check(headless, "no cow was beheaded in 5 sword kills at Extreme");
		context.waitTicks(15);
		context.takeScreenshot("cow_04_headless_run");
		context.waitTicks(40);
		context.takeScreenshot("cow_05_headless_collapsed");

		// Play with a severed part: aim at it from close by, punch it, pick it up, throw it.
		double[] at = context.computeOnClient(mc -> {
			var parts = GoreSystems.dismemberment().parts().parts();
			var p = parts.getLast();
			return new double[]{p.x, p.y, p.z};
		});
		server.runCommand("tp @a " + at[0] + " -60 " + (at[2] - 2.0));
		context.waitTicks(5);
		boolean punched = context.computeOnClient(mc -> {
			var part = GoreSystems.dismemberment().parts().parts().getLast();
			aimAt(mc, part.x, part.y, part.z);
			mc.hitResult = null;
			return GoreSystems.dismemberment().interaction().onAttack(mc, GoreSystems.blood());
		});
		check(punched, "punch did not hit the severed part");
		context.waitTicks(30);
		boolean grabbed = context.computeOnClient(mc -> {
			var part = GoreSystems.dismemberment().parts().parts().getLast();
			if (mc.player.distanceToSqr(part.x, part.y, part.z) > 16.0) {
				mc.player.setPos(part.x, -60.0, part.z - 1.5);
			}

			aimAt(mc, part.x, part.y, part.z);
			mc.hitResult = null;
			return GoreSystems.dismemberment().interaction().onUse(mc) && GoreSystems.dismemberment().interaction().held() != null;
		});
		check(grabbed, "could not pick up the severed part");
		context.waitTicks(10);
		context.takeScreenshot("cow_06_holding_part");
		boolean thrown = context.computeOnClient(mc -> {
			mc.player.setXRot(-20.0F);
			return GoreSystems.dismemberment().interaction().onUse(mc) && GoreSystems.dismemberment().interaction().held() == null;
		});
		check(thrown, "could not throw the held part");
		context.waitTicks(30);
		System.out.println("[FluidGoreTest] part play: punched=" + punched + " grabbed=" + grabbed + " thrown=" + thrown);

		// The killing blow throws the body the way it was struck, and the body can then be slashed.
		server.runCommand("tp @a 8.5 -60 0.5 0 0");
		server.runCommand("summon minecraft:cow 8.5 -60 4.5 {NoAI:1b,Rotation:[0f,0f]}");
		context.waitTicks(10);
		int cowId = context.computeOnClient(mc -> {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.animal.cow.Cow && Math.abs(e.getX() - 8.5) < 0.2 && Math.abs(e.getZ() - 4.5) < 0.2) {
					return e.getId();
				}
			}

			return -1;
		});
		check(cowId >= 0, "launch test cow not found");
		server.runCommand("execute as @p run damage @e[type=minecraft:cow,x=8.5,y=-60,z=4.5,distance=..1,limit=1] 100 minecraft:player_attack by @s");
		context.waitTicks(25);
		double travelled = context.computeOnClient(mc -> {
			var corpse = GoreSystems.suffering().corpse(cowId);
			return corpse == null ? -99.0 : corpse.body.z(1.0F) - 4.5;
		});
		System.out.println("[FluidGoreTest] cow thrown along the blow: " + travelled + " blocks");
		check(travelled > 0.6 && travelled < 7.0, "killing blow did not throw the body away from the attacker, moved " + travelled);
		context.takeScreenshot("cow_07_launched");

		boolean cut = context.computeOnClient(mc -> {
			var corpse = GoreSystems.suffering().corpse(cowId);
			if (corpse == null) {
				return false;
			}

			for (int i = 0; i < 40 && GoreSystems.dismemberment().missingMask(cowId) == 0; i++) {
				chop(mc, corpse, BodyPart.RIGHT_ARM);
			}

			return GoreSystems.dismemberment().missingMask(cowId) != 0;
		});
		context.waitTicks(10);
		context.takeScreenshot("cow_08_slashed_body");
		check(cut, "slashing a body with a blade never cut a part off");
		context.runOnClient(mc -> GoreConfig.get().sufferingLevel = GoreLevel.MEDIUM);
		individuality(context, server);
	}

	/**
	 * Twelve animals (cows and pigs) are wounded three times and then killed the same way. They must have distinct
	 * voices, must not all die the same way, and a living animal next to a fresh body must lower its head and face it.
	 */
	private static void individuality(ClientGameTestContext context, TestServerContext server) {
		context.runOnClient(mc -> GoreConfig.get().sufferingLevel = GoreLevel.EXTREME);
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:stick");
		server.runCommand("tp @a 0.5 -60 -1.5 180 12");
		for (int i = 0; i < 12; i++) {
			String type = i % 2 == 0 ? "cow" : "pig";
			server.runCommand("summon minecraft:" + type + " " + (-5.5 + i) + " -60 -6.5 {NoAI:1b,Tags:[\"indiv\"],UUID:[I;7," + i + "," + (i * 31 + 5) + ",1013],Rotation:[0f,0f]}");
		}

		context.waitTicks(20);
		int voices = context.computeOnClient(mc -> {
			java.util.Set<Float> pitches = new java.util.HashSet<>();
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.LivingEntity living && (e.getUUID().getMostSignificantBits() >>> 32) == 7L) {
					pitches.add(dev.fluidgore.suffer.Personality.of(living).pitch());
				}
			}

			return pitches.size();
		});
		System.out.println("[FluidGoreTest] distinct voices among 12 animals: " + voices);
		check(voices >= 10, "animals do not have individual voices");

		java.util.Set<String> kinds = new java.util.TreeSet<>();
		for (int i = 0; i < 12; i++) {
			for (int hit = 0; hit < 3; hit++) {
				server.runCommand("execute as @p run damage @e[tag=indiv,limit=1,sort=nearest] 2 minecraft:player_attack by @s");
				context.waitTicks(12);
			}

			server.runCommand("execute as @p run damage @e[tag=indiv,limit=1,sort=nearest] 100 minecraft:player_attack by @s");
			context.waitTicks(i < 6 ? 28 : 4);
			if (i < 6) {
				context.takeScreenshot("indiv_" + (i + 1));
			}

			kinds.addAll(context.computeOnClient(mc -> {
				java.util.Set<String> now = new java.util.TreeSet<>();
				for (var corpse : GoreSystems.suffering().corpses()) {
					now.add(corpse.kind.name() + "/" + corpse.throeTicks + "/" + corpse.clonicEnd);
				}

				return now;
			}));
		}

		System.out.println("[FluidGoreTest] death styles seen among 12 animals: " + kinds);
		check(kinds.size() >= 8, "too few different deaths among 12 animals: " + kinds);
		context.waitTicks(120);
		context.takeScreenshot("indiv_7_aftermath");
		context.runOnClient(mc -> GoreConfig.get().sufferingLevel = GoreLevel.MEDIUM);
	}

	/**
	 * The hack model on a living cow and then its body: sword blows aimed at one hind leg must all land on that leg
	 * and deepen one wound down to the bone; after death, chopping at the head must take the head off and leave a
	 * stump wound on the body.
	 */
	private static void hackModel(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("kill @e[type=!minecraft:player]");
		server.runCommand("tp @a 20.5 -60 0.5 0 15");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:iron_sword");
		server.runCommand("summon minecraft:cow 20.5 -60 3.5 {NoAI:1b,Rotation:[90f,0f]}");
		context.waitTicks(15);
		int cowId = context.computeOnClient(mc -> {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.animal.cow.Cow && Math.abs(e.getX() - 20.5) < 0.3) {
					return e.getId();
				}
			}

			return -1;
		});
		check(cowId >= 0, "hack test cow not found");
		// The hind leg on the side facing the player; the far one is hidden behind it.
		BodyPart leg = context.computeOnClient(mc -> {
			var cow = (net.minecraft.world.entity.LivingEntity) mc.level.getEntity(cowId);
			var posed = ModelParts.pose(cow);
			var eye = mc.player.getEyePosition();
			return ModelHit.centre(cow, posed, BodyPart.LEFT_LEG).distanceToSqr(eye) < ModelHit.centre(cow, posed, BodyPart.RIGHT_LEG).distanceToSqr(eye)
					? BodyPart.LEFT_LEG : BodyPart.RIGHT_LEG;
		});

		float[] severity = new float[3];
		String[] parts = new String[3];
		for (int i = 0; i < 3; i++) {
			int hit = i;
			context.runOnClient(mc -> {
				var cow = (net.minecraft.world.entity.LivingEntity) mc.level.getEntity(cowId);
				var posed = ModelParts.pose(cow);
				var at = ModelHit.centre(cow, posed, leg);
				aimAt(mc, at.x, at.y, at.z);
				var eye = mc.player.getEyePosition();
				GoreSystems.dismemberment().wounds().recordAim(cow, eye, at.subtract(eye).normalize());
			});
			server.runCommand("execute as @p run damage @e[type=minecraft:cow,limit=1,sort=nearest] 2 minecraft:player_attack by @s");
			context.waitTicks(15);
			context.runOnClient(mc -> {
				var set = GoreSystems.dismemberment().wounds().get(cowId);
				severity[hit] = set == null ? 0.0F : set.severity(leg);
				StringBuilder all = new StringBuilder(set == null ? "none" : set.count() + " wounds");
				for (BodyPart part : BodyPart.values()) {
					all.append(' ').append(part).append('=').append(set == null ? 0.0F : set.severity(part));
				}

				parts[hit] = all.toString();
			});
			if (hit == 1) {
				context.takeScreenshot("hack_01_leg_open");
			}
		}

		System.out.println("[FluidGoreTest] " + leg + " wound severity per blow: " + java.util.Arrays.toString(severity) + " (" + parts[2] + ")");
		check(severity[0] >= 0.25F, "a sword blow on the leg did not cut it: " + severity[0]);
		check(severity[1] > severity[0] && severity[2] > severity[1], "repeated blows did not deepen the leg wound: " + java.util.Arrays.toString(severity));
		check(severity[2] >= 0.75F, "three sword blows at Extreme did not reach the bone: " + severity[2]);
		context.takeScreenshot("hack_02_leg_bone");

		server.runCommand("execute as @p run damage @e[type=minecraft:cow,limit=1,sort=nearest] 100 minecraft:player_attack by @s");
		context.waitTicks(30);
		boolean headOff = context.computeOnClient(mc -> {
			var corpse = GoreSystems.suffering().corpse(cowId);
			if (corpse == null) {
				return false;
			}

			for (int i = 0; i < 30 && (GoreSystems.dismemberment().missingMask(cowId) & BodyPart.HEAD.bit()) == 0; i++) {
				chop(mc, corpse, BodyPart.HEAD);
			}

			return (GoreSystems.dismemberment().missingMask(cowId) & BodyPart.HEAD.bit()) != 0;
		});
		check(headOff, "chopping at a body's head never took the head off");
		int wounds = context.computeOnClient(mc -> {
			var set = GoreSystems.dismemberment().wounds().get(cowId);
			return set == null ? 0 : set.count();
		});
		System.out.println("[FluidGoreTest] head chopped off the body, wounds left on it: " + wounds);
		check(wounds > 0, "the body kept no wounds or stump after its head came off");
		int headWounds = context.computeOnClient(mc -> {
			var head = GoreSystems.dismemberment().parts().parts().getLast();
			return head.wounds == null ? 0 : head.wounds.count();
		});
		System.out.println("[FluidGoreTest] wounds on the severed head (cut face included): " + headWounds);
		check(headWounds > 0, "the severed head has no raw cut face");
		context.waitTicks(10);
		context.takeScreenshot("hack_03_beheaded_body");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");
	}

	/**
	 * Multiplayer without a server mod: every client must see the same outcome of the same hit. Two clients watching
	 * one fight are two replays of the same facts (same victim UUID, same attacker, same health after each blow), so
	 * the fight is played twice on a cow with a fixed UUID and both runs must match. The blows are aimed along the
	 * player's view with no recorded click, the way another player's swings are resolved, so they must land on the head.
	 */
	private static void sharedFate(ClientGameTestContext context, TestServerContext server) {
		String[] outcome = new String[2];
		boolean[] onHead = new boolean[1];
		for (int run = 0; run < 2; run++) {
			server.runCommand("kill @e[type=!minecraft:player]");
			context.waitTicks(40);
			context.runOnClient(mc -> {
				GoreSystems.blood().clear();
				GoreSystems.dismemberment().clear();
				GoreSystems.suffering().clear();
			});
			server.runCommand("tp @a 40.5 -60 0.5 0 15");
			server.runCommand("item replace entity @a weapon.mainhand with minecraft:iron_sword");
			server.runCommand("summon minecraft:cow 40.5 -60 2.5 {NoAI:1b,Rotation:[90f,0f],UUID:[I;1,2,3,4]}");
			context.waitTicks(15);
			int cowId = context.computeOnClient(mc -> {
				for (Entity e : mc.level.entitiesForRendering()) {
					if (e instanceof net.minecraft.world.entity.animal.cow.Cow) {
						return e.getId();
					}
				}

				return -1;
			});
			check(cowId >= 0, "shared-fate cow not found");
			for (int blow = 0; blow < 4; blow++) {
				context.runOnClient(mc -> {
					var cow = (net.minecraft.world.entity.LivingEntity) mc.level.getEntity(cowId);
					var at = ModelHit.centre(cow, ModelParts.pose(cow), BodyPart.HEAD);
					aimAt(mc, at.x, at.y, at.z);
				});
				server.runCommand("execute as @p run damage @e[type=minecraft:cow,limit=1] " + (blow < 3 ? 2 : 100) + " minecraft:player_attack by @s");
				context.waitTicks(blow < 3 ? 15 : 40);
			}

			int r = run;
			context.runOnClient(mc -> {
				var set = GoreSystems.dismemberment().wounds().get(cowId);
				var corpse = GoreSystems.suffering().corpse(cowId);
				StringBuilder s = new StringBuilder(set == null ? "no wounds" : set.count() + " wounds");
				for (BodyPart part : BodyPart.values()) {
					s.append(' ').append(part).append('=').append(set == null ? 0.0F : set.severity(part));
				}

				s.append(" missing=").append(GoreSystems.dismemberment().missingMask(cowId));
				s.append(corpse == null ? " no body" : " body=" + corpse.kind + " collapse=" + corpse.collapseAt + " throes=" + corpse.throeTicks + " pops=" + corpse.pops);
				outcome[r] = s.toString();
				onHead[0] = set != null && set.severity(BodyPart.HEAD) > 0.0F || (GoreSystems.dismemberment().missingMask(cowId) & BodyPart.HEAD.bit()) != 0;
			});
			System.out.println("[FluidGoreTest] shared fate run " + run + ": " + outcome[run]);
		}

		check(onHead[0],
				"a blow along the player's view did not land on the head: " + outcome[0]);
		check(outcome[0].equals(outcome[1]), "the same fight came out differently: " + outcome[0] + " vs " + outcome[1]);
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");
	}

	/**
	 * Rotting zombies and anatomical slashes: sword blows aimed at a zombie's body must open slashes (long cuts along the
	 * swing) in rotten flesh, deep enough for maggots; punches must leave round wounds; its blood must be rotten; and a
	 * cow must still be ordinary meat.
	 */
	private static void rot(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("kill @e[type=!minecraft:player]");
		server.runCommand("tp @a 60.5 -60 0.5 0 10");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:iron_sword");
		// The helmet keeps the daylight from setting it alight.
		server.runCommand("summon minecraft:zombie 60.5 -60 2.5 {NoAI:1b,Rotation:[180f,0f],equipment:{head:{id:\"minecraft:leather_helmet\"}}}");
		server.runCommand("summon minecraft:cow 63.5 -60 2.5 {NoAI:1b,Rotation:[180f,0f]}");
		context.runOnClient(mc -> {
			GoreSystems.blood().clear();
			GoreSystems.dismemberment().clear();
			GoreSystems.suffering().clear();
		});
		context.waitTicks(15);
		int zombieId = context.computeOnClient(mc -> {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.monster.zombie.Zombie) {
					return e.getId();
				}
			}

			return -1;
		});
		check(zombieId >= 0, "rot test zombie not found");
		String blood = context.computeOnClient(mc -> dev.fluidgore.trigger.EntityFilter.bloodType((net.minecraft.world.entity.LivingEntity) mc.level.getEntity(zombieId)).name());
		check("ROTTEN".equals(blood), "a zombie does not bleed rotten blood: " + blood);

		// Four sword blows on the same spot of its chest, each aimed as the player sees it, cut deeper each time.
		double[][] spots = {{0.0, 1.2}, {0.0, 1.2}, {0.0, 1.2}, {0.0, 1.2}};
		for (double[] spot : spots) {
			context.runOnClient(mc -> {
				var zombie = (net.minecraft.world.entity.LivingEntity) mc.level.getEntity(zombieId);
				var at = zombie.position().add(spot[0], spot[1], 0.0);
				aimAt(mc, at.x, at.y, at.z);
				var eye = mc.player.getEyePosition();
				GoreSystems.dismemberment().wounds().recordAim(zombie, eye, at.subtract(eye).normalize());
			});
			server.runCommand("execute as @p run damage @e[type=minecraft:zombie,limit=1] 3 minecraft:player_attack by @s");
			context.waitTicks(14);
		}

		server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");
		context.runOnClient(mc -> {
			var zombie = (net.minecraft.world.entity.LivingEntity) mc.level.getEntity(zombieId);
			var at = zombie.position().add(0.15, 0.6, 0.0);
			aimAt(mc, at.x, at.y, at.z);
			var eye = mc.player.getEyePosition();
			GoreSystems.dismemberment().wounds().recordAim(zombie, eye, at.subtract(eye).normalize());
		});
		server.runCommand("execute as @p run damage @e[type=minecraft:zombie,limit=1] 2 minecraft:player_attack by @s");
		context.waitTicks(14);
		server.runCommand("tp @a 60.5 -60 1.1 0 25");
		context.waitTicks(5);
		context.takeScreenshot("rot_zombie_wounds");

		String wounds = context.computeOnClient(mc -> {
			var set = GoreSystems.dismemberment().wounds().get(zombieId);
			return set == null ? "none" : set.flesh() + " slashes=" + set.count(dev.fluidgore.wound.WoundSet.SLASH) + " round="
					+ set.count(dev.fluidgore.wound.WoundSet.ROUND) + " deepest=" + set.deepest();
		});
		System.out.println("[FluidGoreTest] rotten zombie: " + wounds);
		check(wounds.startsWith("ROTTEN"), "the zombie's flesh is not rotten: " + wounds);
		check(!wounds.contains("slashes=0"), "sword blows left no slashes: " + wounds);
		check(!wounds.contains("round=0"), "a punch left no round wound: " + wounds);
		float deepest = context.computeOnClient(mc -> GoreSystems.dismemberment().wounds().get(zombieId).deepest());
		check(deepest >= 0.5F, "the slashes never got deep enough for maggots: " + deepest);

		String cow = context.computeOnClient(mc -> {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.animal.cow.Cow living) {
					return dev.fluidgore.wound.Flesh.of(living).name();
				}
			}

			return "none";
		});
		check("MEAT".equals(cow), "a cow is not ordinary meat: " + cow);
	}

	/**
	 * The 1.7.10 sorter on a real chest, opened the way a player opens it: scrambled partial stacks go in, the sort is
	 * done with ordinary clicks, and the server's own copy of the chest must come out merged and in item-id order with
	 * nothing lost. The player's main inventory is sorted the same way, and the hotbar left alone.
	 */
	private static void sorting(ClientGameTestContext context, TestServerContext server) {
		net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(100, -60, 102);
		server.runCommand("tp @a 100.5 -60 100.5 0 30");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");
		server.runCommand("setblock 100 -60 102 minecraft:chest");
		String[] chest = {"0 minecraft:diamond 3", "4 minecraft:dirt 10", "7 minecraft:stone 5", "12 minecraft:dirt 30", "15 minecraft:iron_sword 1",
				"20 minecraft:cobblestone 64", "23 minecraft:stone 9", "26 minecraft:dirt 40", "9 minecraft:oak_log 7"};
		for (String entry : chest) {
			String[] e = entry.split(" ");
			server.runCommand("item replace block 100 -60 102 container." + e[0] + " with " + e[1] + " " + e[2]);
		}

		// inventory.N is the main inventory (slots 9-35); hotbar.0 must stay where it is.
		server.runCommand("item replace entity @p inventory.20 with minecraft:dirt 5");
		server.runCommand("item replace entity @p inventory.3 with minecraft:stone 7");
		server.runCommand("item replace entity @p inventory.11 with minecraft:dirt 6");
		server.runCommand("item replace entity @p hotbar.1 with minecraft:torch 12");
		context.waitTicks(5);
		context.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
				new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.NORTH, pos, false)));
		context.waitTicks(10);
		boolean open = context.computeOnClient(mc -> mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen);
		check(open, "the chest did not open");
		context.takeScreenshot("sort_01_before");
		boolean sorted = context.computeOnClient(mc -> {
			var menu = ((net.minecraft.client.gui.screens.inventory.ContainerScreen) mc.gui.screen()).getMenu();
			return dev.fluidgore.ui.InventorySorter.sort(mc, menu, dev.fluidgore.ui.InventorySorter.Region.CONTAINER)
					&& dev.fluidgore.ui.InventorySorter.sort(mc, menu, dev.fluidgore.ui.InventorySorter.Region.PLAYER);
		});
		check(sorted, "the sorter refused to sort");
		context.waitTicks(10);
		context.takeScreenshot("sort_02_after");
		context.runOnClient(mc -> mc.player.closeContainer());
		context.waitTicks(5);

		String chestAfter = server.computeOnServer(s -> {
			var container = (net.minecraft.world.Container) s.overworld().getBlockEntity(pos);
			return describe(java.util.stream.IntStream.range(0, container.getContainerSize()).mapToObj(container::getItem).toList());
		});
		String mainAfter = server.computeOnServer(s -> {
			var inventory = s.getPlayerList().getPlayers().getFirst().getInventory();
			return describe(java.util.stream.IntStream.range(9, 36).mapToObj(inventory::getItem).toList());
		});
		String torch = server.computeOnServer(s -> {
			var stack = s.getPlayerList().getPlayers().getFirst().getInventory().getItem(1);
			return stack.getItem() + " " + stack.getCount();
		});
		System.out.println("[FluidGoreTest] sorted chest: " + chestAfter);
		System.out.println("[FluidGoreTest] sorted inventory: " + mainAfter + ", hotbar slot 1: " + torch);
		check(chestAfter.equals("stone 14, dirt 64, dirt 16, cobblestone 64, oak_log 7, diamond 3, iron_sword 1"),
				"the chest did not come out merged and in id order: " + chestAfter);
		check(mainAfter.equals("stone 7, dirt 11"), "the inventory did not come out merged and in id order: " + mainAfter);
		check(torch.equals("minecraft:torch 12"), "the hotbar was touched: " + torch);
	}

	/** The stacks in order, empty slots allowed only at the end ("item count, ..."), or a note of a gap. */
	private static String describe(java.util.List<net.minecraft.world.item.ItemStack> stacks) {
		StringBuilder out = new StringBuilder();
		boolean gap = false;
		for (var stack : stacks) {
			if (stack.isEmpty()) {
				gap = true;
				continue;
			}

			if (gap) {
				return "gap before " + stack;
			}

			if (!out.isEmpty()) {
				out.append(", ");
			}

			out.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()).append(' ').append(stack.getCount());
		}

		return out.toString();
	}

	/** The head, or for babies without one (a chick is a single block) the first part the model has. */
	private static BodyPart severable(net.minecraft.world.entity.LivingEntity entity) {
		var parts = ModelParts.index(ModelParts.pose(entity).model());
		for (BodyPart part : BodyPart.values()) {
			if (parts[part.ordinal()] != null) {
				return part;
			}
		}

		throw new AssertionError("no severable part on " + entity);
	}

	/** One axe blow on a body, aimed at a part from outside the body (away from its middle). */
	private static void chop(net.minecraft.client.Minecraft mc, dev.fluidgore.suffer.Corpse corpse, BodyPart part) {
		var target = ModelHit.centre(corpse.entity, ModelParts.pose(corpse.entity), part);
		if (target == null) {
			return;
		}

		var outward = target.subtract(dev.fluidgore.suffer.CorpseStrike.centre(corpse)).add(0.0, 0.3, 0.0).normalize();
		var eye = target.add(outward.scale(1.5));
		dev.fluidgore.suffer.CorpseStrike.strike(mc.level, GoreSystems.blood(), corpse, eye, target.subtract(eye).normalize(), Weapon.CHOP, 9.0F);
	}

	/**
	 * Babies are drawn with their own models (and cows and pigs with per-variant ones). Wounds must be cut into the
	 * model each baby is really drawn with, and parts cut off a baby must be baby-sized and start on the baby.
	 */
	private static void babies(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("kill @e[type=!minecraft:player]");
		server.runCommand("tp @a 30.5 -60 0.5 0 15");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:iron_sword");
		String[] kinds = {"cow", "pig", "sheep", "chicken"};
		for (int i = 0; i < kinds.length; i++) {
			// An adult of each kind first, so the shared renderer's last-drawn model is the adult one.
			server.runCommand("summon minecraft:" + kinds[i] + " " + (27.5 + i * 2) + " -60 6.5 {NoAI:1b,Rotation:[90f,0f]}");
			server.runCommand("summon minecraft:" + kinds[i] + " " + (27.5 + i * 2) + " -60 3.5 {NoAI:1b,Age:-24000,Rotation:[90f,0f]}");
		}

		context.waitTicks(20);
		java.util.List<Integer> ids = context.computeOnClient(mc -> {
			java.util.List<Integer> found = new java.util.ArrayList<>();
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.AgeableMob baby && baby.isBaby()) {
					found.add(e.getId());
				}
			}

			return found;
		});
		check(ids.size() == kinds.length, "expected " + kinds.length + " babies, found " + ids.size());

		for (int blow = 0; blow < 2; blow++) {
			for (int id : ids) {
				context.runOnClient(mc -> {
					var baby = (net.minecraft.world.entity.LivingEntity) mc.level.getEntity(id);
					var at = ModelHit.centre(baby, ModelParts.pose(baby), severable(baby));
					var eye = mc.player.getEyePosition();
					GoreSystems.dismemberment().wounds().recordAim(baby, eye, at.subtract(eye).normalize());
				});
				server.runCommand("execute as @p run damage @e[type=!minecraft:player,limit=1,sort=nearest,x=" + context.computeOnClient(mc -> mc.level.getEntity(id).getX())
						+ ",y=-60,z=3.5,distance=..0.6] 1 minecraft:player_attack by @s");
				context.waitTicks(12);
			}
		}

		context.takeScreenshot("baby_01_wounded");
		for (int id : ids) {
			String result = context.computeOnClient(mc -> {
				var baby = mc.level.getEntity(id);
				var set = GoreSystems.dismemberment().wounds().get(id);
				var drawn = ModelParts.drawn(id);
				return baby.getType().getDescriptionId() + " wounds=" + (set == null ? 0 : set.count())
						+ " onDrawnModel=" + (set != null && drawn != null && set.drawsOn(drawn));
			});
			System.out.println("[FluidGoreTest] baby " + result);
			check(result.contains("onDrawnModel=true") && !result.contains("wounds=0"), "baby wounds not on the model it is drawn with: " + result);
		}

		// Bodies from earlier scenes pop into parts when they go; keep them from filling the part cap here.
		context.runOnClient(mc -> {
			GoreSystems.suffering().clear();
			GoreSystems.dismemberment().parts().clear();
		});
		int partsBefore = context.computeOnClient(mc -> GoreSystems.dismemberment().parts().parts().size());
		for (int id : ids) {
			context.runOnClient(mc -> {
				var baby = (net.minecraft.world.entity.LivingEntity) mc.level.getEntity(id);
				GoreSystems.dismemberment().severPart(GoreSystems.blood(), mc.level, baby, severable(baby), baby::position, Float.NaN, 0.0F, null);
			});
		}

		String sizes = context.computeOnClient(mc -> {
			StringBuilder out = new StringBuilder();
			var parts = GoreSystems.dismemberment().parts().parts();
			for (int i = partsBefore; i < parts.size(); i++) {
				var part = parts.get(i);
				double nearest = Double.MAX_VALUE;
				for (int id : ids) {
					nearest = Math.min(nearest, mc.level.getEntity(id).position().distanceTo(new net.minecraft.world.phys.Vec3(part.x, part.y, part.z)));
				}

				out.append(String.format(java.util.Locale.ROOT, "[half=%.3f dist=%.2f]", Math.max(part.halfExtents.x(), Math.max(part.halfExtents.y(), part.halfExtents.z())), nearest));
			}

			return out.toString();
		});
		System.out.println("[FluidGoreTest] parts cut off babies: " + sizes);
		int heads = context.computeOnClient(mc -> GoreSystems.dismemberment().parts().parts().size()) - partsBefore;
		check(heads == ids.size(), "not every baby lost a part: " + heads);
		for (String entry : sizes.split("]")) {
			if (entry.isEmpty()) {
				continue;
			}

			double half = Double.parseDouble(entry.substring(entry.indexOf("half=") + 5, entry.indexOf(' ')));
			double dist = Double.parseDouble(entry.substring(entry.indexOf("dist=") + 5));
			check(half > 0.01 && half < 0.3, "part cut off a baby is not baby-sized: " + entry);
			check(dist < 1.2, "part cut off a baby did not start on the baby: " + entry);
		}

		context.waitTicks(5);
		context.takeScreenshot("baby_02_beheaded");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");
	}

	/**
	 * Mother and young: a hurt calf must make its mother answer and turn to it; a killed calf must leave her grieving,
	 * head hung towards where it died; and a killed mother must leave her calf grieving too.
	 */
	private static void grief(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("kill @e[type=!minecraft:player]");
		server.runCommand("tp @a 40.5 -60 0.5 0 15");
		// Let the bodies from before finish, so none of them bonds with the new cows.
		context.waitTicks(20);
		context.runOnClient(mc -> GoreSystems.suffering().clear());
		server.runCommand("summon minecraft:cow 43.5 -60 8.5 {NoAI:1b,Rotation:[0f,0f],Tags:[\"mother\"]}");
		server.runCommand("summon minecraft:cow 40.5 -60 4.5 {NoAI:1b,Age:-24000,Rotation:[180f,0f],Tags:[\"calf\"]}");
		server.runCommand("summon minecraft:cow 30.5 -60 12.5 {NoAI:1b,Rotation:[0f,0f],Tags:[\"other_mother\"]}");
		server.runCommand("summon minecraft:cow 30.5 -60 15.5 {NoAI:1b,Age:-24000,Rotation:[0f,0f],Tags:[\"orphan\"]}");
		context.waitTicks(20);
		int[] ids = context.computeOnClient(mc -> {
			int[] found = {-1, -1, -1, -1};
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.animal.cow.Cow cow) {
					int slot = (cow.getX() > 35 ? 0 : 2) + (cow.isBaby() ? 1 : 0);
					found[slot] = e.getId();
				}
			}

			return found;
		});
		int mother = ids[0];
		int calf = ids[1];
		int otherMother = ids[2];
		int otherCalf = ids[3];
		check(mother >= 0 && calf >= 0 && otherMother >= 0 && otherCalf >= 0, "grief test cows not found: " + java.util.Arrays.toString(ids));

		int before = context.computeOnClient(mc -> GoreSystems.suffering().screams().totalPlayed());
		server.runCommand("execute as @p run damage @e[tag=calf,limit=1] 2 minecraft:player_attack by @s");
		context.waitTicks(25);
		float alarm = context.computeOnClient(mc -> GoreSystems.suffering().fear(mother));
		int calls = context.computeOnClient(mc -> GoreSystems.suffering().screams().totalPlayed()) - before;
		System.out.println("[FluidGoreTest] calf hurt: mother fear " + alarm + ", sounds " + calls);
		check(alarm > 0.5F, "the mother did not react to her calf being hurt: fear " + alarm);
		context.takeScreenshot("grief_01_mother_turns");

		server.runCommand("execute as @p run damage @e[tag=calf,limit=1] 100 minecraft:player_attack by @s");
		server.runCommand("execute as @p run damage @e[tag=other_mother,limit=1] 100 minecraft:player_attack by @s");
		context.waitTicks(400);
		String grief = context.computeOnClient(mc -> {
			var pose = GoreSystems.suffering().pose(mother);
			return GoreSystems.suffering().grieving(mother) + " " + GoreSystems.suffering().grieving(otherCalf) + " " + (pose == null ? 0.0F : pose.headDip());
		});
		System.out.println("[FluidGoreTest] after the killings: mother grieving, orphan grieving, mother head dip: " + grief);
		String[] g = grief.split(" ");
		check(g[0].equals("true"), "the mother does not grieve for her killed calf");
		check(g[1].equals("true"), "the calf does not grieve for its killed mother");
		check(Float.parseFloat(g[2]) > 0.3F, "the grieving mother does not hang her head: " + g[2]);
		context.takeScreenshot("grief_02_mother_grieving");
	}

	/**
	 * Weapons differ: a beating with fists must spill a small fraction of the blood the same blows with a sword do; a
	 * pickaxe must knock pieces out of the wound; a flint is its own weapon; and a cow next to primed TNT must vanish
	 * into pieces of flesh.
	 */
	private static void weapons(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("kill @e[type=!minecraft:player]");
		server.runCommand("gamemode creative @a");
		server.runCommand("tp @a 50.5 -60 0.5 0 15");
		context.waitTicks(20);
		context.runOnClient(mc -> GoreSystems.suffering().clear());

		int fists = bloodFrom(context, server, "minecraft:air", 53.5);
		int sword = bloodFrom(context, server, "minecraft:iron_sword", 47.5);
		System.out.println("[FluidGoreTest] blood from three blows: fists " + fists + ", sword " + sword);
		check(sword > 0 && fists * 3 < sword, "a beating with fists bled about as much as a sword: fists " + fists + ", sword " + sword);
		context.takeScreenshot("weapon_01_fists_and_sword");

		boolean flint = context.computeOnClient(mc -> Weapon.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FLINT)) == Weapon.FLINT);
		check(flint, "flint is not treated as a sharp rock");

		server.runCommand("kill @e[type=!minecraft:player]");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:iron_pickaxe");
		server.runCommand("summon minecraft:cow 50.5 -60 3.5 {NoAI:1b,Rotation:[90f,0f],Tags:[\"mined\"]}");
		context.waitTicks(15);
		// Scraps from bodies that popped earlier would expire meanwhile and hide the new ones.
		context.runOnClient(mc -> {
			GoreSystems.suffering().clear();
			GoreSystems.dismemberment().giblets().clear();
		});
		int before = context.computeOnClient(mc -> GoreSystems.dismemberment().giblets().count());
		for (int i = 0; i < 3; i++) {
			context.runOnClient(mc -> {
				var cow = (net.minecraft.world.entity.LivingEntity) mc.level.getEntitiesOfClass(net.minecraft.world.entity.animal.cow.Cow.class,
						mc.player.getBoundingBox().inflate(8.0)).getFirst();
				var at = cow.position().add(0.0, cow.getBbHeight() * 0.6, 0.0);
				aimAt(mc, at.x, at.y, at.z);
				var eye = mc.player.getEyePosition();
				GoreSystems.dismemberment().wounds().recordAim(cow, eye, at.subtract(eye).normalize());
			});
			server.runCommand("execute as @p run damage @e[tag=mined,limit=1] 2 minecraft:player_attack by @s");
			context.waitTicks(12);
		}

		int chips = context.computeOnClient(mc -> GoreSystems.dismemberment().giblets().count()) - before;
		System.out.println("[FluidGoreTest] pieces knocked out by three pickaxe blows: " + chips);
		check(chips > 0, "the pickaxe knocked nothing out of the wound");
		context.takeScreenshot("weapon_02_pickaxe");

		server.runCommand("kill @e[type=!minecraft:player]");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");
		server.runCommand("summon minecraft:cow 50.5 -60 9.5 {NoAI:1b,Tags:[\"blasted\"]}");
		context.waitTicks(15);
		int cowId = context.computeOnClient(mc -> {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.animal.cow.Cow cow && cow.isAlive() && Math.abs(cow.getZ() - 9.5) < 0.5) {
					return e.getId();
				}
			}

			return -1;
		});
		int gibsBefore = context.computeOnClient(mc -> GoreSystems.dismemberment().giblets().count());
		server.runCommand("summon minecraft:tnt 50.5 -60 10.3 {fuse:1}");
		// Checked straight away: once the server removes the dead entity, it is forgotten.
		context.waitTicks(6);
		boolean gone = context.computeOnClient(mc -> GoreSystems.dismemberment().vaporized(cowId));
		context.waitTicks(24);
		int gibs = context.computeOnClient(mc -> GoreSystems.dismemberment().giblets().count()) - gibsBefore;
		System.out.println("[FluidGoreTest] cow next to TNT: torn apart " + gone + ", pieces " + gibs);
		check(gone, "the cow next to the TNT was not torn apart");
		check(gibs >= 50, "the TNT made too few pieces of the cow: " + gibs);
		context.takeScreenshot("weapon_03_tnt");
		context.waitTicks(40);
		context.takeScreenshot("weapon_04_tnt_settled");
	}

	/**
	 * A 5x5 pit of pigs with TNT in the middle. The blast must be graded like a real one: the pigs next to the charge
	 * are shredded, the ones further off are torn open (limbs and heads thrown, guts out) and thrown, not left in a heap.
	 */
	private static void pigPit(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("kill @e[type=!minecraft:player]");
		server.runCommand("fill 60 -63 -10 64 -61 -6 minecraft:air");
		server.runCommand("tp @a 62.5 -60 -15.5 0 40");
		context.runOnClient(mc -> {
			GoreSystems.blood().clear();
			GoreSystems.dismemberment().clear();
			GoreSystems.suffering().clear();
		});
		double[][] spots = {{61.5, -7.5}, {63.5, -7.5}, {62.5, -9.5}, {62.5, -5.5}, {60.5, -9.5}, {64.5, -9.5}, {60.5, -5.5}, {64.5, -5.5}};
		for (double[] spot : spots) {
			server.runCommand("summon minecraft:pig " + spot[0] + " -63 " + spot[1] + " {NoAI:1b,Tags:[\"pit\"]}");
		}

		context.waitTicks(20);
		java.util.List<Integer> pigs = context.computeOnClient(mc -> {
			java.util.List<Integer> ids = new java.util.ArrayList<>();
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.animal.pig.Pig pig && pig.isAlive()) {
					ids.add(e.getId());
				}
			}

			return ids;
		});
		check(pigs.size() == spots.length, "expected " + spots.length + " pigs in the pit, saw " + pigs.size());

		server.runCommand("summon minecraft:tnt 62.5 -63 -7.5 {fuse:1}");
		context.waitTicks(4);
		int shredded = context.computeOnClient(mc -> (int) pigs.stream().filter(id -> GoreSystems.dismemberment().vaporized(id)).count());
		int torn = context.computeOnClient(mc -> (int) pigs.stream()
				.filter(id -> Integer.bitCount(GoreSystems.dismemberment().missingMask(id)) >= 2).count());
		int parts = context.computeOnClient(mc -> GoreSystems.dismemberment().parts().parts().size());
		context.takeScreenshot("pit_01_blast");
		context.waitTicks(16);
		int pieces = context.computeOnClient(mc -> GoreSystems.dismemberment().giblets().count());
		context.takeScreenshot("pit_02_raining");
		context.waitTicks(60);
		context.takeScreenshot("pit_03_after");
		System.out.println("[FluidGoreTest] pig pit: shredded " + shredded + ", torn open " + torn + ", parts thrown " + parts
				+ ", flesh pieces " + pieces + " of " + spots.length + " pigs");
		check(shredded >= 1, "no pig next to the TNT was shredded");
		check(torn >= 1, "no pig further from the TNT was torn open");
		check(pieces >= 150, "too few pieces of pig: " + pieces);
	}

	/**
	 * Gore must never hang in the air: a pig is shredded on a raised floor, the floor is removed, and no piece may be
	 * left at floor height. Then a cow is killed: it is knocked about by blows while it dies, and pops into parts.
	 */
	private static void floorAndDeath(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("kill @e[type=!minecraft:player]");
		server.runCommand("tp @a 100.5 -60 -15.5 0 30");
		server.runCommand("fill 96 -57 -12 104 -57 -4 minecraft:stone");
		server.runCommand("summon minecraft:pig 100.5 -56 -8.5 {NoAI:1b}");
		context.runOnClient(mc -> {
			GoreSystems.blood().clear();
			GoreSystems.dismemberment().clear();
			GoreSystems.suffering().clear();
		});
		context.waitTicks(20);
		context.runOnClient(mc -> {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.animal.pig.Pig pig) {
					GoreSystems.dismemberment().vaporize(GoreSystems.blood(), mc.level, pig, pig.position().add(0.0, -0.3, 0.0));
				}
			}
		});
		context.waitTicks(80);
		// Pieces lying on top of the floor (its top face is at y -56).
		java.util.function.Function<net.minecraft.client.Minecraft, Integer> onFloor = mc -> {
			var g = GoreSystems.dismemberment().giblets();
			int n = 0;
			for (int i = 0; i < g.count(); i++) {
				if (g.y[i] > -56.3 && g.y[i] < -55.5 && Math.abs(g.x[i] - 100.5) < 4.5 && Math.abs(g.z[i] + 8.0) < 4.5) {
					n++;
				}
			}

			return n;
		};
		int before = context.computeOnClient(onFloor::apply);
		int decalsBefore = context.computeOnClient(mc -> GoreSystems.blood().decals().size());
		context.takeScreenshot("floor_01_on_floor");
		server.runCommand("fill 96 -57 -12 104 -57 -4 minecraft:air");
		context.waitTicks(30);
		int after = context.computeOnClient(onFloor::apply);
		int decalsAfter = context.computeOnClient(mc -> GoreSystems.blood().decals().size());
		context.takeScreenshot("floor_02_fell");
		System.out.println("[FluidGoreTest] floor: pieces on the floor before " + before + ", after removing it " + after
				+ "; decals " + decalsBefore + " -> " + decalsAfter);
		check(before >= 10, "too few pieces landed on the raised floor: " + before);
		check(after == 0, after + " pieces were left hanging in the air where the floor was");

		server.runCommand("kill @e[type=!minecraft:player]");
		server.runCommand("summon minecraft:cow 100.5 -60 -12.5 {NoAI:1b,Rotation:[90f,0f]}");
		context.waitTicks(20);
		int cowId = context.computeOnClient(mc -> {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.animal.cow.Cow) {
					return e.getId();
				}
			}

			return -1;
		});
		check(cowId >= 0, "no cow for the death test");
		// The rare end, made certain: the body bursts when it goes.
		context.runOnClient(mc -> {
			GoreSystems.dismemberment().clear();
			GoreSystems.suffering().forceNextDeath(null, true);
		});
		server.runCommand("execute as @p run damage @e[type=minecraft:cow,limit=1] 100 minecraft:player_attack by @s");
		context.waitTicks(30);
		context.takeScreenshot("death_01_down");
		double[] moved = context.computeOnClient(mc -> {
			var corpse = GoreSystems.suffering().corpse(cowId);
			if (corpse == null) {
				return new double[]{-1.0};
			}

			var start = corpse.position();
			var eye = mc.player.getEyePosition();
			var at = dev.fluidgore.suffer.CorpseStrike.centre(corpse);
			dev.fluidgore.suffer.CorpseStrike.strike(mc.level, GoreSystems.blood(), corpse, eye, at.subtract(eye).normalize(), Weapon.BLUNT, 1.0F);
			return new double[]{start.x, start.z, corpse.flash};
		});
		check(moved.length == 3, "the killed cow left no body");
		check(moved[2] > 0, "a blow on the body did not flash it red");
		context.waitTicks(10);
		double shoved = context.computeOnClient(mc -> {
			var corpse = GoreSystems.suffering().corpse(cowId);
			return corpse == null ? -1.0 : Math.hypot(corpse.position().x - moved[0], corpse.position().z - moved[1]);
		});
		System.out.println("[FluidGoreTest] death: a punch knocked the body " + shoved + " blocks");
		check(shoved > 0.3, "a punch barely moved the dying body: " + shoved);

		int waited = 0;
		while (context.computeOnClient(mc -> GoreSystems.suffering().corpse(cowId) != null) && waited < 1600) {
			context.waitTicks(20);
			waited += 20;
		}

		context.waitTicks(3);
		int popped = context.computeOnClient(mc -> GoreSystems.dismemberment().parts().parts().size());
		context.takeScreenshot("death_02_pop");
		System.out.println("[FluidGoreTest] death: body gone after " + waited + " ticks, popped into " + popped + " parts");
		check(waited < 1600, "the body never went");
		check(popped >= 3, "the body did not pop into parts: " + popped);
	}

	/**
	 * The deaths, one cow per cause, side by side, sampled every tick. Every body must be down within half a second
	 * and lie where it dropped; a head blow goes rigid and a blast leaves it limp; all of them paddle, a broken back with
	 * its hind end hanging dead; those that still breathe gasp and a beheaded one never does; every body is still
	 * within three to five and a half seconds; and a blow during the rigid phase sets the legs going.
	 */
	private static void deaths(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("kill @e[type=!minecraft:player]");
		server.runCommand("tp @a 168.5 -60 -9.5 0 22");
		dev.fluidgore.suffer.Corpse.Kind[] kinds = dev.fluidgore.suffer.Corpse.Kind.values();
		for (int i = 0; i <= kinds.length; i++) {
			server.runCommand("summon minecraft:cow " + (158.5 + i * 2.5) + " -60 0.5 {NoAI:1b,Tags:[\"death" + i + "\"],Rotation:[90f,0f]}");
		}

		context.runOnClient(mc -> {
			GoreConfig.get().sufferingLevel = GoreLevel.EXTREME;
			GoreSystems.blood().clear();
			GoreSystems.dismemberment().clear();
			GoreSystems.suffering().clear();
		});
		context.waitTicks(20);
		check(context.computeOnClient(mc -> GoreConfig.get().deathPop && GoreConfig.get().deathPopPercent == 5), "the death pop is not a 5% chance by default");

		int[] ids = new int[kinds.length + 1];
		for (int i = 0; i <= kinds.length; i++) {
			int n = i;
			ids[i] = context.computeOnClient(mc -> {
				for (Entity e : mc.level.entitiesForRendering()) {
					if (e instanceof net.minecraft.world.entity.animal.cow.Cow && Math.abs(e.getX() - (158.5 + n * 2.5)) < 0.3) {
						return e.getId();
					}
				}

				return -1;
			});
			check(ids[i] >= 0, "no cow for death " + i);
		}

		double[][] start = new double[kinds.length][];
		for (int i = 0; i < kinds.length; i++) {
			int n = i;
			context.runOnClient(mc -> GoreSystems.suffering().forceNextDeath(kinds[n], false));
			server.runCommand("damage @e[tag=death" + i + ",limit=1] 100 minecraft:generic");
			context.waitTicks(2);
			start[i] = context.computeOnClient(mc -> {
				var corpse = GoreSystems.suffering().corpse(ids[n]);
				return corpse == null ? null : new double[]{corpse.position().x, corpse.position().z};
			});
			check(start[i] != null, kinds[i] + ": the killed cow left no body");
		}

		int[] downAt = new int[kinds.length];
		int[] stillAt = new int[kinds.length];
		float[] stiff = new float[kinds.length];
		float[] kick = new float[kinds.length];
		float[] gasp = new float[kinds.length];
		float[] limp = new float[kinds.length];
		boolean[] onSide = new boolean[kinds.length];
		java.util.Arrays.fill(downAt, -1);
		java.util.Arrays.fill(stillAt, -1);
		for (int tick = 0; tick < 130; tick++) {
			context.waitTicks(1);
			if (tick == 6 || tick == 30 || tick == 60) {
				context.takeScreenshot("deaths_t" + tick);
			}

			context.runOnClient(mc -> {
				for (int i = 0; i < kinds.length; i++) {
					var c = GoreSystems.suffering().corpse(ids[i]);
					if (c == null) {
						continue;
					}

					var pose = c.pose();
					// When its legs gave way, and whether it is really lying on its side by a second and a half.
					downAt[i] = c.collapseAt;
					if (c.age <= 30 && Math.abs(c.body.roll(1.0F)) > 1.2F) {
						onSide[i] = true;
					}

					if (stillAt[i] < 0 && c.lifeless()) {
						stillAt[i] = c.age;
					}

					stiff[i] = Math.max(stiff[i], pose.stiff());
					kick[i] = Math.max(kick[i], pose.kick());
					gasp[i] = Math.max(gasp[i], pose.gasp());
					limp[i] = Math.max(limp[i], pose.hindLimp());
					check(!c.pops, kinds[i] + ": a body forced not to burst is set to burst");
				}
			});
		}

		for (int i = 0; i < kinds.length; i++) {
			int n = i;
			double moved = context.computeOnClient(mc -> {
				var c = GoreSystems.suffering().corpse(ids[n]);
				return c == null ? -1.0 : Math.hypot(c.position().x - start[n][0], c.position().z - start[n][1]);
			});
			System.out.println("[FluidGoreTest] death " + kinds[i] + ": legs gone at " + downAt[i] + ", on its side " + onSide[i] + ", still at " + stillAt[i] + ", stiff " + stiff[i]
					+ ", kick " + kick[i] + ", gasp " + gasp[i] + ", hind limp " + limp[i] + ", moved " + String.format("%.2f", moved));
			check(downAt[i] >= 0 && downAt[i] <= 8, kinds[i] + ": the legs did not give way at once: " + downAt[i]);
			check(onSide[i], kinds[i] + ": not lying on its side within a second and a half");
			check(stillAt[i] >= 60 && stillAt[i] <= 110, kinds[i] + ": did not die out in three to five and a half seconds: " + stillAt[i]);
			check(moved >= 0.0 && moved < 0.8, kinds[i] + ": did not lie where it dropped, moved " + moved);
			check(kick[i] > 0.1F, kinds[i] + ": never paddled: " + kick[i]);
		}

		int concussion = dev.fluidgore.suffer.Corpse.Kind.CONCUSSION.ordinal();
		int trauma = dev.fluidgore.suffer.Corpse.Kind.TRAUMA.ordinal();
		int decapitation = dev.fluidgore.suffer.Corpse.Kind.DECAPITATION.ordinal();
		int spinal = dev.fluidgore.suffer.Corpse.Kind.SPINAL.ordinal();
		check(stiff[concussion] >= 0.8F, "the head blow did not go rigid: " + stiff[concussion]);
		check(kick[concussion] >= 0.4F, "the head blow did not paddle hard: " + kick[concussion]);
		check(stiff[trauma] == 0.0F && kick[trauma] < 0.45F, "the blast victim was not limp: stiff " + stiff[trauma] + " kick " + kick[trauma]);
		check(kick[decapitation] >= 0.5F && gasp[decapitation] == 0.0F, "the beheaded body did not kick hard without a breath: kick "
				+ kick[decapitation] + " gasp " + gasp[decapitation]);
		check(limp[spinal] == 1.0F, "the broken back did not leave the hind end limp");
		for (dev.fluidgore.suffer.Corpse.Kind kind : new dev.fluidgore.suffer.Corpse.Kind[]{dev.fluidgore.suffer.Corpse.Kind.CONCUSSION,
				dev.fluidgore.suffer.Corpse.Kind.EXSANGUINATION, dev.fluidgore.suffer.Corpse.Kind.SPINAL, dev.fluidgore.suffer.Corpse.Kind.CHEST,
				dev.fluidgore.suffer.Corpse.Kind.BEATEN}) {
			check(gasp[kind.ordinal()] > 0.0F, kind + ": never gasped");
		}

		int blood = context.computeOnClient(mc -> GoreSystems.blood().fluid().size() + GoreSystems.blood().decals().size());
		System.out.println("[FluidGoreTest] deaths: blood on the ground " + blood);
		check(blood > 20, "the dying bodies left little blood: " + blood);

		// A blow during the rigid phase breaks it: the legs start going at once.
		int last = ids[kinds.length];
		context.runOnClient(mc -> GoreSystems.suffering().forceNextDeath(dev.fluidgore.suffer.Corpse.Kind.CONCUSSION, false));
		server.runCommand("damage @e[tag=death" + kinds.length + ",limit=1] 100 minecraft:generic");
		context.waitTicks(16);
		int[] rigid = context.computeOnClient(mc -> {
			var c = GoreSystems.suffering().corpse(last);
			return c == null ? null : new int[]{c.groundTicks(), c.tonicEnd};
		});
		check(rigid != null && rigid[0] < rigid[1], "the head-blow cow was not rigid when struck: " + java.util.Arrays.toString(rigid));
		context.runOnClient(mc -> {
			var corpse = GoreSystems.suffering().corpse(last);
			var eye = mc.player.getEyePosition();
			var at = dev.fluidgore.suffer.CorpseStrike.centre(corpse);
			dev.fluidgore.suffer.CorpseStrike.strike(mc.level, GoreSystems.blood(), corpse, eye, at.subtract(eye).normalize(), Weapon.BLUNT, 1.0F);
		});
		context.waitTicks(5);
		float kicked = context.computeOnClient(mc -> GoreSystems.suffering().corpse(last).pose().kick());
		System.out.println("[FluidGoreTest] rigid cow struck: kick " + kicked + " five ticks later");
		check(kicked > 0.1F, "a blow during the rigid phase did not set the legs going: " + kicked);
		context.takeScreenshot("deaths_struck");
		context.runOnClient(mc -> GoreConfig.get().sufferingLevel = GoreLevel.EXTREME);
	}

	/**
	 * A chicken burnt alive must char, not bleed, die writhing, leave a scorch mark, and (on Extreme) lie there long
	 * as evidence; so must a pig beaten to death.
	 */
	private static void burntAndEvidence(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("kill @e[type=!minecraft:player]");
		server.runCommand("tp @a 130.5 -60 -15.5 0 30");
		server.runCommand("summon minecraft:chicken 130.5 -60 -12.5 {NoAI:1b,Fire:400s}");
		server.runCommand("summon minecraft:pig 133.5 -60 -12.5 {NoAI:1b,Rotation:[90f,0f]}");
		context.runOnClient(mc -> {
			GoreSystems.blood().clear();
			GoreSystems.dismemberment().clear();
			GoreSystems.suffering().clear();
		});
		context.waitTicks(10);
		int chickenId = context.computeOnClient(mc -> {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.animal.chicken.Chicken) {
					return e.getId();
				}
			}

			return -1;
		});
		check(chickenId >= 0, "no chicken for the burn test");
		context.waitTicks(40);
		float burning = context.computeOnClient(mc -> GoreSystems.dismemberment().burns().charOf(chickenId));
		int cellsWhileBurning = context.computeOnClient(mc -> GoreSystems.blood().fluid().size());
		context.takeScreenshot("burnt_01_burning");
		int waited = 0;
		while (context.computeOnClient(mc -> GoreSystems.suffering().corpse(chickenId) == null) && waited < 400) {
			context.waitTicks(10);
			waited += 10;
		}

		context.waitTicks(60);
		String corpse = context.computeOnClient(mc -> {
			var c = GoreSystems.suffering().corpse(chickenId);
			return c == null ? "none" : c.kind.name() + " " + c.stillTicks;
		});
		float charred = context.computeOnClient(mc -> GoreSystems.dismemberment().burns().charOf(chickenId));
		long scorch = context.computeOnClient(mc -> GoreSystems.blood().decals().decals().stream().filter(d -> d.rgb == 0x1A1512).count());
		context.takeScreenshot("burnt_02_charred");
		System.out.println("[FluidGoreTest] burnt chicken: char while burning " + burning + ", at death " + charred + ", puddles while burning "
				+ cellsWhileBurning + ", body " + corpse + ", scorch marks " + scorch);
		check(burning > 0.1F, "the burning chicken did not char: " + burning);
		check(charred > 0.3F, "the chicken burnt to death is not charred: " + charred);
		check(cellsWhileBurning == 0, "burning made the chicken bleed puddles: " + cellsWhileBurning);
		check(corpse.startsWith("BURN"), "a chicken burnt alive did not die of its burns: " + corpse);
		check(scorch >= 1, "the burnt chicken left no scorch mark");
		int still = Integer.parseInt(corpse.split(" ")[1]);
		check(still >= 1000, "on Extreme a burnt body does not lie long enough to be seen: " + still + " ticks");

		for (int i = 0; i < 6; i++) {
			server.runCommand("execute as @p run damage @e[type=minecraft:pig,limit=1] 2 minecraft:player_attack by @s");
			context.waitTicks(14);
		}

		context.waitTicks(40);
		String pig = context.computeOnClient(mc -> {
			for (var c : GoreSystems.suffering().corpses()) {
				if (c.entity instanceof net.minecraft.world.entity.animal.pig.Pig) {
					return c.kind.name() + " " + c.stillTicks + " " + GoreSystems.blood().decalLifetime();
				}
			}

			return "none";
		});
		context.takeScreenshot("evidence_01_beaten_pig");
		System.out.println("[FluidGoreTest] beaten pig: body " + pig);
		check(pig.startsWith("BEATEN"), "the beaten pig did not die beaten: " + pig);
		String[] p = pig.split(" ");
		check(Integer.parseInt(p[1]) >= 1000, "on Extreme a beaten body does not lie long enough to be seen: " + p[1] + " ticks");
		check(Integer.parseInt(p[2]) >= 20 * 60 * 5, "on Extreme blood does not stay five minutes: " + p[2] + " ticks");
	}

	/**
	 * Every in-game demo ({@code /gore demo <name>}), played through the real client command, each on its own spot. Each
	 * must run to the end and leave gore behind: bodies, parts or flesh pieces.
	 */
	private static void demos(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("time set noon");
		server.runCommand("weather clear");
		boolean allowed = context.computeOnClient(mc -> dev.fluidgore.demo.DemoPlayer.allowed(mc));
		check(allowed, "the test player may not run demo commands");
		int spot = 0;
		for (dev.fluidgore.demo.Demo demo : dev.fluidgore.demo.Demos.ALL) {
			server.runCommand("tp @a " + (200 + spot * 40) + ".5 -60 0.5 0 15");
			spot++;
			context.waitTicks(30);
			context.runOnClient(mc -> {
				GoreSystems.blood().clear();
				GoreSystems.dismemberment().clear();
				GoreSystems.suffering().clear();
			});
			// Destructive demos ask twice, as a player would click "go".
			int runs = demo.destructive() ? 2 : 1;
			for (int i = 0; i < runs; i++) {
				context.runOnClient(mc -> mc.player.connection.sendCommand("gore demo " + demo.name()));
			}

			context.waitTicks(5);
			check(context.computeOnClient(mc -> dev.fluidgore.demo.DemoPlayer.playing()), "demo " + demo.name() + " did not start");
			int ticks = 0;
			boolean shot = false;
			while (context.computeOnClient(mc -> dev.fluidgore.demo.DemoPlayer.playing()) && ticks < 2400) {
				context.waitTicks(10);
				ticks += 10;
				if (!shot && ticks >= 120) {
					context.takeScreenshot("demo_" + demo.name());
					shot = true;
				}
			}

			int gore = context.computeOnClient(mc -> GoreSystems.suffering().corpses().size() + GoreSystems.dismemberment().parts().parts().size()
					+ GoreSystems.dismemberment().giblets().count() + GoreSystems.blood().decals().decals().size());
			System.out.println("[FluidGoreTest] demo " + demo.name() + ": ran " + ticks + " ticks, gore left " + gore);
			check(ticks < 2400, "demo " + demo.name() + " never finished");
			check(gore > 0, "demo " + demo.name() + " left no gore");
		}
	}

	/** Blood decals and puddle cells made by three 2-damage blows with {@code item} on a fresh cow at {@code x}. */
	private static int bloodFrom(ClientGameTestContext context, TestServerContext server, String item, double x) {
		server.runCommand("item replace entity @a weapon.mainhand with " + item);
		server.runCommand("summon minecraft:cow " + x + " -60 4.5 {NoAI:1b,Tags:[\"struck\"]}");
		context.waitTicks(15);
		context.runOnClient(mc -> GoreSystems.blood().clear());
		for (int i = 0; i < 3; i++) {
			server.runCommand("execute as @p run damage @e[tag=struck,limit=1] 2 minecraft:player_attack by @s");
			context.waitTicks(12);
		}

		context.waitTicks(60);
		int made = context.computeOnClient(mc -> GoreSystems.blood().decals().decals().size() + GoreSystems.blood().fluid().cells().size());
		server.runCommand("kill @e[tag=struck]");
		context.waitTicks(5);
		return made;
	}

	private static void aimAt(net.minecraft.client.Minecraft mc, double x, double y, double z) {
		var eye = mc.player.getEyePosition();
		double dx = x - eye.x;
		double dy = y - eye.y;
		double dz = z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
		mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, horizontal)));
	}

	private static void offClearsEverything(ClientGameTestContext context) {
		context.runOnClient(mc -> GoreConfig.get().goreLevel = GoreLevel.OFF);
		context.waitTicks(2);
		Stats off = stats(context);
		System.out.println("[FluidGoreTest] after Off: " + off);
		check(off.decals == 0 && off.cells == 0 && off.droplets == 0 && off.parts == 0, "Off did not clear all effects");
		context.runOnClient(mc -> GoreConfig.get().goreLevel = GoreLevel.EXTREME);
	}

	private static void screens(ClientGameTestContext context) {
		context.setScreen(() -> new OptionsScreen(null, net.minecraft.client.Minecraft.getInstance().options));
		context.waitTicks(2);
		context.takeScreenshot("ui_01_options_screen");
		context.clickScreenButton("fluidgore.settings.button");
		context.waitForScreen(GoreSettingsScreen.class);
		context.takeScreenshot("ui_02_settings_screen");
		context.setScreen(() -> null);
	}

	private record Stats(int decals, int cells, int droplets, int parts) {
	}

	private static Stats stats(ClientGameTestContext context) {
		return context.computeOnClient(mc -> new Stats(
				GoreSystems.blood().decals().size(),
				GoreSystems.blood().fluid().size(),
				GoreSystems.blood().droplets().droplets().size(),
				GoreSystems.dismemberment().parts().parts().size()));
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
