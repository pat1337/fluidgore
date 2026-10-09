package dev.fluidgore.test;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodCell;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import dev.fluidgore.ui.GoreSettingsScreen;
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
		context.runOnClient(mc -> GoreConfig.get().sufferingLevel = GoreLevel.MEDIUM);
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
