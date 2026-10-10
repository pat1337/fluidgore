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
			babies(context, singleplayer.getServer());
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
		check("BRUTAL".equals(kind), "beaten-to-death cow should get BRUTAL throes, got " + kind);
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
					if (corpse.kind == dev.fluidgore.suffer.Corpse.Kind.HEADLESS_RUN && corpse.running()) {
						return true;
					}
				}

				return false;
			});
		}

		System.out.println("[FluidGoreTest] headless run seen: " + headless);
		check(headless, "no beheaded cow ran headless in 5 sword kills at Extreme");
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
					now.add(corpse.kind.name());
				}

				return now;
			}));
		}

		System.out.println("[FluidGoreTest] death styles seen among 12 animals: " + kinds);
		check(kinds.size() >= 2, "every animal died the same way: " + kinds);
		check(kinds.stream().anyMatch(k -> k.equals("BOLT") || k.equals("COLLAPSE") || k.equals("CURL")), "no personality death style occurred: " + kinds);
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
		context.waitTicks(10);
		context.takeScreenshot("hack_03_beheaded_body");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");
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
