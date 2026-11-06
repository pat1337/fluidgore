package dev.fluidgore.cobbletank.client;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.demo.Demo;
import dev.fluidgore.demo.Demos;
import dev.fluidgore.demo.Script;
import dev.fluidgore.demo.Stage;
import dev.fluidgore.dismember.GorePart;
import dev.fluidgore.dismember.Viscera;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** The Cobble Tank's demos, added to {@code /gore demo}. Each is a scene from the tank's game tests. */
final class TankDemos {
	private TankDemos() {
	}

	static void register() {
		Demos.add(new Demo("fragments", "3.5.0", "A tank casing burst with exposed cows on the left and stone cover on the right: short-lived carbon and metal chips follow server-traced paths, leaving local wounds only where a body intercepts one", true, TankDemos::fragments));
		Demos.add(new Demo("roadster", "3.4.0", "The Cobble Tank up close: dark fibre casing, a valve jet lifting loose cow organs, then sustained fire weakens a sealed tank until its pressure rupture tears a nearby cow apart. White gas, casing debris, no combustion fireball", true, TankDemos::roadster));
		Demos.add(new Demo("tank", "2.16.0", "The Cobble Tank: a pinhole hisses, a cracked fitting sends it flying, and a redstone-triggered rupture throws casing and gas. Bodies, glass and a gate show how effects fall with distance; strength uses the server's selected wave coupling", true, TankDemos::tank));
	}

	private static void fragments(Script s, Stage st) {
		s.cmd("setblock " + st.block(0, 0, 12) + " cobbletank:cobble_tank{WavePercent:1}");
		s.cmd("fill " + st.block(2, 0, 9) + " " + st.block(2, 3, 15) + " minecraft:stone");
		for (int i = 0; i < 3; i++) {
			s.summon(st, "cow", "fgd_fragment_open", -4, 0, 10 + i * 2, 90, "");
			s.summon(st, "cow", "fgd_fragment_cover", 4, 0, 10 + i * 2, 90, "");
		}
		s.say("Casing fragments: exposed cows on the left, solid stone cover on the right").pause(60);
		s.cmd("setblock " + st.block(0, 1, 12) + " minecraft:redstone_block");
		s.say("A short burst. Each chip stops at its first wall or body; an exposed animal can also be missed").pause(100);
		s.say("Look for local wounds and intact sheltered cows. This demonstration keeps the pressure wave small").pause(180);
	}

	private static void roadster(Script s, Stage st) {
		s.cmd("setblock " + st.block(-4, 0, 7) + " cobbletank:cobble_tank");
		s.summon(st, "cow", "fgd_roadster_donor", 4, 0, 7, 90.0F, "Invulnerable:1b");
		s.say("Carbon overwrap, metal bands and fitting. 600 L at 10,000 psi by default").pause(60);
		s.cmd("data merge block " + st.block(-4, 0, 7) + " {Crack:1f,JetX:0f,JetY:1f,JetZ:0f}");
		s.client(mc -> {
			LivingEntity donor = st.find(mc.level, EntityTypes.COW, 4, 0, 7);
			if (donor == null) return;
			var made = Viscera.lift(mc.level, GoreSystems.dismemberment().giblets(), donor, java.util.Set.of("liver", "lung"), Vec3.ZERO);
			int i = 0;
			for (GorePart p : made) {
				Vec3 at = st.at(-4, 1.15 + i++ * 0.35, 7);
				p.x = p.prevX = at.x; p.y = p.prevY = at.y; p.z = p.prevZ = at.z;
				p.vx = p.vy = p.vz = 0;
			}
		});
		s.say("The valve's jet lifts the liver and lungs. Recoil presses the casing down; pressure fades as gas escapes").pause(160);
		s.cmd("setblock " + st.block(0, 0, 30) + " cobbletank:cobble_tank");
		s.summon(st, "cow", "fgd_roadster_near", 0, 0, 31.7, 90.0F, "");
		s.summon(st, "cow", "fgd_roadster_mid", 5, 0, 30, 90.0F, "");
		s.cmd("setblock " + st.block(1, 0, 30) + " minecraft:campfire");
		s.say("Fire beside the sealed tank. No fuse: pressure rises while heat weakens the overwrap").pause(220);
		s.say("Heat damage remains after cooling. Venting changes whether and when it fails").pause(400);
		s.say("Pressure, carbon and fittings tear tissue. The campfire is the separate heat source").pause(100);
		s.cmd("setblock " + st.block(1, 0, 30) + " minecraft:air");
		s.say("Try one: empty-hand use reads its state; sneak-use a stick opens the valve on the clicked face").pause(120);
	}

	/**
	 * The tank block. A pinhole leak 6 blocks ahead, its crack towards the player: it hisses and frosts, and standing by it
	 * costs hearing. A cracked tank 20 ahead and 15 to the right tears loose and flies off away from the player. Then a full tank 30 ahead,
	 * lit with redstone: cows at 3, 7 and 12 m from it (shredded, killed, hurt), a gate 18 m to its side and a row of glass
	 * 20 m beyond it; the player, 30 m off, is only deafened.
	 */
	private static void tank(Script s, Stage st) {
		Vec3 back = st.at(0, 0, -1).subtract(st.at(0, 0, 0));
		// The crack faces the player, so the jet comes their way and the thrust pushes the tank away from them.
		String towards = String.format(java.util.Locale.ROOT, "JetX:%.0ff,JetY:0f,JetZ:%.0ff", back.x, back.z);
		s.say("The Cobble Tank: TNT with cobblestone on four sides. 600 litres of air at 10,000 psi");
		s.cmd("setblock " + st.block(-4, 0, 6) + " cobbletank:cobble_tank[leak=pinhole,scars=3]{Damage:0.6f,Crack:0.05f," + towards + "}");
		s.pause(20).say("A pinhole: it hisses for minutes, frost grows at the crack. Standing this close, your hearing goes");
		s.pause(140);
		s.cmd("setblock " + st.block(15, 0, 20) + " cobbletank:cobble_tank[leak=cracked,scars=3]{Damage:0.8f,Crack:0.6f," + towards + "}");
		s.say("A cracked fitting: 5 kN of thrust on a 400 kg tank. It tears loose and flies");
		s.pause(120);
		s.cmd("setblock " + st.block(0, 0, 30) + " cobbletank:cobble_tank");
		s.summon(st, "cow", "fgd_tank_cow", 0.5, 0, 32.0, 90.0F, "");
		s.summon(st, "cow", "fgd_tank_cow2", 5.0, 0, 30.5, 0.0F, "");
		s.summon(st, "cow", "fgd_tank_cow3", -12.0, 0, 30.5, 45.0F, "");
		s.cmd("setblock " + st.block(-18, 0, 30) + " minecraft:oak_fence_gate");
		s.cmd("fill " + st.block(-6, 0, 50) + " " + st.block(6, 2, 50) + " minecraft:glass_pane");
		s.pause(40).say("A full one, 30 blocks off. Cows near 2, 5 and 12 m; a gate and glass. Redstone triggers the crafted block...");
		s.cmd("setblock " + st.block(0, 1, 30) + " minecraft:redstone_block");
		s.pause(100).say("A sharp gas rupture and casing debris; the pressure wave fades with distance. No TNT fireball").pause(200);
		s.say("Blast strength uses the server's coupling setting. Stored energy alone does not fix the damage radius").pause(200);
	}

}
