package dev.fluidgore.demo;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.CorpseStrike;
import dev.fluidgore.wound.ModelHit;
import dev.fluidgore.wound.Weapon;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Every demo, one per feature, newest first. Each is a scene from the automated game test, set up in front of the
 * player. <b>Every release that adds something you can see adds a demo here.</b>
 */
public final class Demos {
	public static final List<Demo> ALL = List.of(
			new Demo("deaths", "2.1.0", "Eight cows, eight causes of death: each drops at once, then rigid, paddling, gasps, still", false, Demos::deaths),
			new Demo("burnt", "1.8.0", "A chicken and a pig burnt alive: they char black, smoke and leave scorch marks", false, Demos::burnt),
			new Demo("evidence", "1.8.0", "A pig beaten to death on Extreme: the body and the blood stay as evidence", false, Demos::evidence),
			new Demo("death", "1.7.0", "A cow dies red and keels over, is knocked about, then pops into pieces", false, Demos::death),
			new Demo("floor", "1.7.0", "Pigs blown up on a raised floor; the floor goes and the gore falls with it", true, Demos::floor),
			new Demo("pit", "1.6.0", "A 5x5 pit of pigs and a lit TNT: shredded, torn open, thrown", true, Demos::pit),
			new Demo("weapons", "1.6.0", "Fists, flint, sword, axe and pickaxe on five cows", false, Demos::weapons),
			new Demo("grief", "1.5.0", "A calf is hurt and killed; its mother answers and grieves", false, Demos::grief),
			new Demo("hack", "1.3.0", "Blows on one leg: bruise, gash, meat, bone, off; then the head", false, Demos::hack),
			new Demo("personalities", "1.3.0", "Six animals, wounded and killed alike, die their own way", false, Demos::personalities),
			new Demo("bones", "1.3.0", "A skeleton and a parched shatter", false, Demos::bones),
			new Demo("launch", "1.3.0", "An axe kill throws the body, then the body is hacked", false, Demos::launch),
			new Demo("headless", "1.1.0", "A beheaded cow drops and kicks hard while the neck pumps", false, Demos::headless),
			new Demo("beating", "1.1.0", "A cow beaten to death in front of its herd", false, Demos::beating));

	private Demos() {
	}

	public static @Nullable Demo get(String name) {
		for (Demo demo : ALL) {
			if (demo.name().equals(name)) {
				return demo;
			}
		}

		return null;
	}

	/**
	 * A row of cows, each killed with one blow and made to die of a different cause. Every one drops at once; what
	 * follows on the ground is what the cause brings. Then one that bursts at the end.
	 */
	private static void deaths(Script s, Stage st) {
		Corpse.Kind[] kinds = Corpse.Kind.values();
		String[] names = {
				"Head blow: unconscious before it lands. Rigid, head arched back, then the legs paddle and stop",
				"Cut open: it lies bleeding with every heartbeat, weaker and slower, then a last paddle",
				"Broken back: the hind end is dead, the forelegs go rigid, laboured gasps",
				"Beheaded: the hardest kicking of all and the neck pumping, without a breath",
				"Through the lungs: wet gasps, blood from the mouth, weak kicks",
				"Blast: limp at once, a few twitches",
				"Burnt: the heat draws the limbs in, and they twitch",
				"Beaten to death: violent, disorganised kicking and gurgling"};
		for (int i = 0; i <= kinds.length; i++) {
			s.summon(st, "cow", "fgd_cow" + i, -10.0 + i * 2.4, 0, 9, 90.0F, "");
		}

		s.pause(20).hold("minecraft:iron_sword").say("One blow each, and each made to die of a different cause:");
		for (int i = 0; i < kinds.length; i++) {
			double right = -10.0 + i * 2.4;
			Corpse.Kind kind = kinds[i];
			s.client(mc -> {
				aim(mc, st.find(mc.level, EntityTypes.COW, right, 0, 9), null);
				GoreSystems.suffering().forceNextDeath(kind, false);
			});
			s.hit("fgd_cow" + i, 100.0F).say(names[i]).pause(110);
		}

		double last = -10.0 + kinds.length * 2.4;
		s.client(mc -> {
			aim(mc, st.find(mc.level, EntityTypes.COW, last, 0, 9), null);
			GoreSystems.suffering().forceNextDeath(null, true);
		});
		s.hit("fgd_cow" + kinds.length, 100.0F).say("And the rare one (one in twenty): when it finally goes, it bursts").pause(300);
	}

	/** Set alight where they stand: they char as they burn, writhe, and lie charred and smoking. */
	private static void burnt(Script s, Stage st) {
		s.summon(st, "chicken", "fgd_chicken", -1, 0, 3, 90.0F, "");
		s.summon(st, "pig", "fgd_pig", 1.5, 0, 3.5, 90.0F, "");
		s.pause(20).say("Set on fire: seared, roasted, then black");
		s.cmd("data merge entity @e[tag=fgd_chicken,limit=1] {Fire:400s}");
		s.cmd("data merge entity @e[tag=fgd_pig,limit=1] {Fire:400s}");
		s.pause(160).say("Burnt to death: charred, smoking, a scorch mark under it").pause(300);
	}

	/** Bare hands until it dies, on Extreme: the battered body lies in its blood for a long while. */
	private static void evidence(Script s, Stage st) {
		s.summon(st, "pig", "fgd_pig", 0, 0, 3, 90.0F, "");
		s.pause(20).hold("minecraft:air").say("Beaten to death with bare hands");
		for (int i = 0; i < 6; i++) {
			s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.PIG, 0, 0, 3), null));
			s.hit("fgd_pig", 2.0F).pause(14);
		}

		s.say("On Extreme the body and the blood stay: anyone coming by can see what happened").pause(300);
	}

	/** One sword kill, then the body is punched about while it dies, until it pops. */
	private static void death(Script s, Stage st) {
		s.summon(st, "cow", "fgd_cow", 0, 0, 3, 90.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("One killing blow: it goes red and keels over, like vanilla, only harder");
		s.client(mc -> {
			aim(mc, st.find(mc.level, EntityTypes.COW, 0, 0, 3), null);
			// The rare end, made certain for the demo.
			GoreSystems.suffering().forceNextDeath(null, true);
		});
		s.hit("fgd_cow", 100.0F).pause(40);
		s.hold("minecraft:air").say("It is still dying: punch it about");
		for (int i = 0; i < 6; i++) {
			s.client(Demos::punchNearest).pause(12);
		}

		s.say("When it goes, it pops: head, legs and scraps to kick around").pause(500);
	}

	/** A raised floor with pigs and TNT on it; after the blast what is left of the floor is removed. */
	private static void floor(Script s, Stage st) {
		s.say("A plank floor three blocks up, 8 blocks ahead, with pigs on it");
		s.cmd("fill " + st.block(-4, 3, 4) + " " + st.block(4, 3, 12) + " minecraft:oak_planks");
		double[][] spots = {{-2, 6}, {2, 6}, {0, 10}, {-2, 10}, {2, 10}};
		for (int i = 0; i < spots.length; i++) {
			s.summon(st, "pig", "fgd_pig", spots[i][0], 4, spots[i][1], i * 70.0F, "");
		}

		s.pause(30).say("TNT...");
		s.cmd("summon minecraft:tnt " + st.pos(0, 4, 8) + " {fuse:40}");
		s.pause(40).say("Gore falls through the hole the blast made").pause(80);
		s.say("Now the rest of the floor goes: everything on it drops");
		s.cmd("fill " + st.block(-4, 3, 4) + " " + st.block(4, 3, 12) + " minecraft:air");
		s.pause(160);
	}

	/** Pigs in a pit 8 blocks ahead (just out of the blast's reach of the player), and TNT with a two-second fuse. */
	private static void pit(Script s, Stage st) {
		s.say("Digging a 5x5 pit, three deep, 8 blocks ahead");
		s.cmd("fill " + st.block(-2, -3, 6) + " " + st.block(2, -1, 10) + " minecraft:air");
		double[][] spots = {{-1, 0}, {1, 0}, {0, -2}, {0, 2}, {-2, -2}, {2, -2}, {-2, 2}, {2, 2}};
		for (int i = 0; i < spots.length; i++) {
			s.summon(st, "pig", "fgd_pig", spots[i][0], -3, 8 + spots[i][1], i * 45.0F, "");
		}

		s.pause(40).say("Eight pigs. Lighting the TNT...");
		s.cmd("summon minecraft:tnt " + st.pos(0, -3, 8) + " {fuse:50}");
		s.pause(50).say("Next to it: shredded. Corners: torn open and thrown.");
		s.pause(200);
	}

	/** Five cows side by side, each struck three times and then killed with a different weapon. */
	private static void weapons(Script s, Stage st) {
		String[][] weapons = {
				{"minecraft:air", "Fists: crushed, not cut. A beating bleeds inside, then from the mouth"},
				{"minecraft:flint", "Flint: a sharp rock. Ragged, shallow cuts"},
				{"minecraft:iron_sword", "Sword: clean cuts that open the meat"},
				{"minecraft:iron_axe", "Axe: deep, wide chops"},
				{"minecraft:iron_pickaxe", "Pickaxe: digs in and knocks bits out"}};
		for (int i = 0; i < weapons.length; i++) {
			s.summon(st, "cow", "fgd_cow" + i, -4 + i * 2, 0, 3.5, 90.0F, "");
		}

		s.pause(20);
		for (int i = 0; i < weapons.length; i++) {
			double right = -4 + i * 2;
			s.hold(weapons[i][0]).say(weapons[i][1]);
			for (int blow = 0; blow < 3; blow++) {
				s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.COW, right, 0, 3.5), null));
				s.hit("fgd_cow" + i, 2.0F).pause(14);
			}

			s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.COW, right, 0, 3.5), null));
			s.hit("fgd_cow" + i, 100.0F).pause(50);
		}

		s.pause(100);
	}

	/** A calf near the player, its mother further off. The calf is hurt, then killed. */
	private static void grief(Script s, Stage st) {
		s.summon(st, "cow", "fgd_mother", 3, 0, 8, 0.0F, "");
		s.summon(st, "cow", "fgd_calf", 0, 0, 3.5, 180.0F, "Age:-24000");
		s.pause(30).hold("minecraft:iron_sword").say("The calf is hurt. It cries for its mother; she answers");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.COW, 0, 0, 3.5), null));
		s.hit("fgd_calf", 2.0F).pause(80);
		s.say("The calf is killed");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.COW, 0, 0, 3.5), null));
		s.hit("fgd_calf", 100.0F).pause(120);
		s.say("She grieves: head hung, turned to where it happened, calling");
		s.client(mc -> lookAt(mc, st.at(3, 1, 8)));
		s.pause(400);
	}

	/** Four sword blows on one hind leg open it down to the bone; the killing blow takes it off; an axe takes the head. */
	private static void hack(Script s, Stage st) {
		s.summon(st, "cow", "fgd_cow", 0, 0, 3, 90.0F, "");
		s.pause(20).hold("minecraft:iron_sword");
		String[] layers = {"Bruise", "Gash", "Open meat", "Down to the bone"};
		for (String layer : layers) {
			s.client(mc -> {
				LivingEntity cow = st.find(mc.level, EntityTypes.COW, 0, 0, 3);
				aim(mc, cow, cow == null ? null : nearLeg(mc, cow));
			});
			s.hit("fgd_cow", 2.0F).say(layer).pause(30);
		}

		s.client(mc -> {
			LivingEntity cow = st.find(mc.level, EntityTypes.COW, 0, 0, 3);
			aim(mc, cow, cow == null ? null : nearLeg(mc, cow));
		});
		s.hit("fgd_cow", 100.0F).say("Through the bone").pause(50);
		s.hold("minecraft:iron_axe").say("Now the head, on the body");
		for (int i = 0; i < 12; i++) {
			s.client(mc -> chopNearest(mc, BodyPart.HEAD)).pause(8);
		}

		s.pause(120);
	}

	/** Cows and pigs in a row, each struck three times and killed the same way: they still die differently. */
	private static void personalities(Script s, Stage st) {
		for (int i = 0; i < 6; i++) {
			s.summon(st, i % 2 == 0 ? "cow" : "pig", "fgd_a" + i, -5 + i * 2, 0, 4.5, 180.0F, "");
		}

		s.pause(20).hold("minecraft:stick").say("Each one: three blows, then the same killing blow");
		for (int i = 0; i < 6; i++) {
			EntityType<?> type = i % 2 == 0 ? EntityTypes.COW : EntityTypes.PIG;
			double right = -5 + i * 2;
			for (int blow = 0; blow < 3; blow++) {
				s.client(mc -> aim(mc, st.find(mc.level, type, right, 0, 4.5), null));
				s.hit("fgd_a" + i, 2.0F).pause(12);
			}

			s.client(mc -> aim(mc, st.find(mc.level, type, right, 0, 4.5), null));
			s.hit("fgd_a" + i, 100.0F).pause(30);
		}

		s.pause(160);
	}

	private static void bones(Script s, Stage st) {
		s.summon(st, "skeleton", "fgd_skeleton", -1, 0, 4, 180.0F, "");
		s.summon(st, "parched", "fgd_parched", 1, 0, 4, 180.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("No blood: bone chips, cracks and shatters");
		s.hit("fgd_skeleton", 100.0F).pause(10);
		s.hit("fgd_parched", 100.0F).pause(120);
	}

	private static void launch(Script s, Stage st) {
		s.summon(st, "cow", "fgd_cow", 0, 0, 2.5, 0.0F, "");
		s.pause(20).hold("minecraft:iron_axe").say("One heavy blow: the body is thrown the way it was struck");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.COW, 0, 0, 2.5), null));
		s.hit("fgd_cow", 100.0F).pause(50);
		s.say("Hacking at the body");
		for (int i = 0; i < 10; i++) {
			s.client(mc -> chopNearest(mc, BodyPart.RIGHT_ARM)).pause(8);
		}

		s.pause(120);
	}

	/** The head comes off with the killing blow, and the body runs on. */
	private static void headless(Script s, Stage st) {
		s.summon(st, "cow", "fgd_cow", 0, 0, 3.5, 90.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("The head comes off with the killing blow...");
		s.client(mc -> {
			LivingEntity cow = st.find(mc.level, EntityTypes.COW, 0, 0, 3.5);
			if (cow != null) {
				aim(mc, cow, BodyPart.HEAD);
				Vec3 away = cow.position().subtract(mc.player.position()).multiply(1.0, 0.0, 1.0).normalize();
				GoreSystems.dismemberment().severPart(GoreSystems.blood(), mc.level, cow, BodyPart.HEAD, cow::position, Float.NaN, 0.0F, away);
			}
		});
		s.hit("fgd_cow", 100.0F).pause(30).say("...and the body does not know yet").pause(160);
	}

	/** A herd of six; the nearest is punched to death. The others panic and the beaten one dies hard. */
	private static void beating(Script s, Stage st) {
		s.summon(st, "cow", "fgd_victim", 0, 0, 3, 90.0F, "");
		for (int i = 0; i < 5; i++) {
			s.summon(st, "cow", "fgd_herd", -4 + i * 2, 0, 6 + (i % 2), i * 60.0F, "");
		}

		s.pause(20).hold("minecraft:air").say("Beaten to death with bare hands, in front of the herd");
		for (int i = 0; i < 5; i++) {
			s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.COW, 0, 0, 3), null));
			s.hit("fgd_victim", 2.0F).pause(14);
		}

		s.say("The worst death: long throes. The herd panics").pause(200);
	}

	/**
	 * Turns the player to {@code target} and records the swing on it, as a real click would: on {@code part}, or on
	 * the middle of the body if null.
	 */
	private static void aim(Minecraft mc, @Nullable LivingEntity target, @Nullable BodyPart part) {
		if (target == null) {
			return;
		}

		Vec3 at = target.position().add(0.0, target.getBbHeight() * 0.6, 0.0);
		if (part != null) {
			ModelParts.Posed posed = ModelParts.pose(target);
			Vec3 centre = posed == null ? null : ModelHit.centre(target, posed, part);
			if (centre != null) {
				at = centre;
			}
		}

		lookAt(mc, at);
		Vec3 eye = mc.player.getEyePosition();
		GoreSystems.dismemberment().wounds().recordAim(target, eye, at.subtract(eye).normalize());
	}

	private static void lookAt(Minecraft mc, Vec3 at) {
		Vec3 d = at.subtract(mc.player.getEyePosition());
		double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
		mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
		mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, horizontal)));
	}

	/** The hind leg on the side facing the player; the far one is hidden behind the body. */
	private static BodyPart nearLeg(Minecraft mc, LivingEntity cow) {
		ModelParts.Posed posed = ModelParts.pose(cow);
		Vec3 eye = mc.player.getEyePosition();
		Function<BodyPart, Double> dist = part -> {
			Vec3 c = posed == null ? null : ModelHit.centre(cow, posed, part);
			return c == null ? Double.MAX_VALUE : c.distanceToSqr(eye);
		};
		return dist.apply(BodyPart.LEFT_LEG) < dist.apply(BodyPart.RIGHT_LEG) ? BodyPart.LEFT_LEG : BodyPart.RIGHT_LEG;
	}

	/** A punch on the nearest dying body, at its middle. */
	private static void punchNearest(Minecraft mc) {
		Corpse nearest = null;
		double bestSq = 12.0 * 12.0;
		for (Corpse corpse : GoreSystems.suffering().corpses()) {
			double d = corpse.position().distanceToSqr(mc.player.position());
			if (d < bestSq) {
				nearest = corpse;
				bestSq = d;
			}
		}

		if (nearest != null) {
			Vec3 target = CorpseStrike.centre(nearest);
			lookAt(mc, target);
			Vec3 eye = mc.player.getEyePosition();
			CorpseStrike.strike(mc.level, GoreSystems.blood(), nearest, eye, target.subtract(eye).normalize(), Weapon.BLUNT, 1.0F);
		}
	}

	/** One axe blow on the nearest body lying on the stage, aimed at {@code part} from outside the body. */
	private static void chopNearest(Minecraft mc, BodyPart part) {
		Corpse nearest = null;
		double bestSq = Double.MAX_VALUE;
		for (Corpse corpse : GoreSystems.suffering().corpses()) {
			double d = corpse.position().distanceToSqr(mc.player.position());
			if (d < bestSq) {
				nearest = corpse;
				bestSq = d;
			}
		}

		if (nearest == null || bestSq > 12.0 * 12.0 || (GoreSystems.dismemberment().missingMask(nearest.entity.getId()) & part.bit()) != 0) {
			return;
		}

		ModelParts.Posed posed = ModelParts.pose(nearest.entity);
		Vec3 target = posed == null ? null : ModelHit.centre(nearest.entity, posed, part);
		if (target == null) {
			return;
		}

		lookAt(mc, target);
		Vec3 outward = target.subtract(CorpseStrike.centre(nearest)).add(0.0, 0.3, 0.0).normalize();
		Vec3 eye = target.add(outward.scale(1.5));
		CorpseStrike.strike(mc.level, GoreSystems.blood(), nearest, eye, target.subtract(eye).normalize(), Weapon.CHOP, 9.0F);
	}
}
