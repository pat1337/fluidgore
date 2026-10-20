package dev.fluidgore.demo;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.Cleaning;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.GorePart;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.net.GoreAction;
import dev.fluidgore.net.GoreSync;
import dev.fluidgore.player.PlayerInjuries;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.CorpseStrike;
import dev.fluidgore.wound.ModelHit;
import dev.fluidgore.wound.Weapon;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
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
			new Demo("sponge", "3.1.0", "A pig's blood and guts cleaned up: a clean sponge lifts the blood, a bloody one smears it along the stroke and squashes the guts into streaks, a soaked one leaves red wipes on a clean floor, and the blood clouds out of it when it is washed", false, Demos::sponge),
			new Demo("arrows", "3.0.0", "Arrows with no one behind them, as from a dispenser, at a cow side-on: with Fluid Gore on the server each wounds exactly where it strikes (leg, flank, neck), the same on every screen", false, Demos::arrows),
			new Demo("tank", "2.16.0", "The Cobble Tank, for real: a pinhole leak hisses and frosts and costs you your hearing, a cracked tank tears loose and flies, and a full one (600 litres at 10,000 psi, about 21 kg of TNT) lit with redstone 30 blocks off kills to 8 m, bursts eardrums to 15, blows a gate open and the glass out", true, Demos::tank),
			new Demo("roadster", "2.15.0", "A 2x2 of TNT as the Roadster's pressure vessel (200 litres at 10,000 psi, about 7 kg of TNT) with animals in rings round it: shredded, torn apart, lungs burst, fragments driven in, each by the real overpressure where it stood; 9 blocks off, you are deafened and your ears ring", true, Demos::roadster),
			new Demo("cremation", "2.14.0", "A chicken, a sheep and a cow set alight where they lie: each burns on its own fat, the heavier the longer and smokier, down to the bone; the cow is put out half burnt, its char knocked off and its bones broken up into ash", true, Demos::cremation),
			new Demo("chickens", "2.13.0", "Chickens killed three ways (wrung neck, cut throat, beheaded) flap themselves to death; feathers fly bloody, a hen in the rain goes dark and slick, and the feathers lie where they fell", false, Demos::chickens),
			new Demo("stairs", "2.13.0", "A sheep and a pig killed at the top of a narrow stair: the bodies slump over the edge and tumble down step by step", true, Demos::stairs),
			new Demo("decay", "2.12.0", "A chicken, a pig and a cow left to rot (Extreme): six weeks in a minute, day by day, from bloat to bones", false, Demos::decay),
			new Demo("kept", "2.11.0", "The vanilla death made gory: a sword kill and an axe kill keel over, stay, are chopped where they lie, and are cleaned up", false, Demos::kept),
			new Demo("injured", "2.10.0", "Injuries: a zombie limps on a smashed leg holding its belly, then you take blows and fade near death", false, Demos::injured),
			new Demo("armor", "2.10.0", "A zombie in armour hacked apart: the helmet flies off with the head, the boots stay on the leg", false, Demos::armor),
			new Demo("heal", "2.10.0", "A wounded pig heals: its cuts stop dripping and close as the health comes back", false, Demos::heal),
			new Demo("glow", "2.9.0", "Ender blood glows: an enderman bled out, best watched at night or with a shader pack", false, Demos::glow),
			new Demo("ender", "2.8.0", "An enderman cut apart: violet ender blood, portal sparks from its wounds, and limbs that keep teleporting", false, Demos::ender),
			new Demo("meats", "2.7.0", "A pig and a cow diced into pork and steak, and a sheep cut up in its herd, the wool soaking red", false, Demos::meats),
			new Demo("dice", "2.6.0", "A horse and a donkey butchered like chickens: split in a few chops, then diced down to raw chunks", false, Demos::dice),
			new Demo("horse", "2.5.0", "Horse death, broken bones and a split carcass", false, Demos::horse),
			new Demo("butcher", "2.5.0", "A chicken plucked bare, jointed, its carcass split and diced", false, Demos::butcher),
			new Demo("rot", "2.2.0", "A zombie hacked open: slashes along the swing into rotting flesh, pus and maggots", false, Demos::rot),
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
	private static void horse(Script s, Stage st) {
		s.summon(st, "horse", "fgd_horse", 0, 0, 4, 90.0F, "");
		s.pause(20).hold("minecraft:air").say("Horse: a heavy fall and longer paddling");
		s.client(mc -> GoreSystems.suffering().forceNextDeath(Corpse.Kind.BEATEN, false));
		s.hit("fgd_horse", 100).pause(35);
		s.say("Blunt blows break the skull and legs; a cleaver splits the trunk");
		for (int i = 0; i < 8; i++) {
			s.client(mc -> {
				for (Corpse c : GoreSystems.suffering().corpses()) {
					if (c.entity.getType() != EntityTypes.HORSE) continue;
					Vec3 target = ModelHit.centre(c.entity, ModelParts.pose(c.entity), BodyPart.HEAD);
					if (target == null) continue;
					Vec3 eye = target.add(0, 1.5, 0);
					CorpseStrike.strike(mc.level, GoreSystems.blood(), c, eye, target.subtract(eye).normalize(), Weapon.BLUNT, 4);
				}
			}).pause(6);
		}
		s.hold("minecraft:iron_axe").client(mc -> {
			for (Corpse c : GoreSystems.suffering().corpses()) {
				if (c.entity.getType() == EntityTypes.HORSE) GoreSystems.dismemberment().butchery().split(GoreSystems.blood(), mc.level, c,
						ModelParts.torso(ModelParts.pose(c.entity).model()), new Vec3(0, -1, 0));
			}
		}).pause(100);
	}

	/**
	 * Bloody tools (3.1): a pig killed and gutted on the floor, then wiped up by a sponge growing bloodier: clean it
	 * lifts the blood, half soaked it smears it on and squashes the guts, soaked it leaves wipes on clean floor, and it is
	 * washed. The strokes are played here (a demo never puts a real sponge in the player's hand).
	 */
	private static void sponge(Script s, Stage st) {
		s.summon(st, "pig", "fgd_pig", 0, 0, 4, 90.0F, "");
		s.pause(20).hold("minecraft:iron_axe").say("A pig killed on the floor: blood and guts to clean up");
		s.hit("fgd_pig", 100).pause(10);
		s.client(mc -> {
			LivingEntity e = st.find(mc.level, EntityTypes.PIG, 0, 0, 4);
			if (e != null) {
				GoreSystems.dismemberment().giblets().spill(mc.level, GoreSystems.blood(), e, e.position().add(0.0, 0.4, 0.0), 0.6F);
			}
		}).pause(50);
		s.say("A clean sponge takes the blood up");
		strokes(s, st, 0.0F, 3.4);
		s.pause(20).say("Bloodier, it takes up less and drags the rest along, squashing the guts into streaks");
		strokes(s, st, 0.6F, 4.4);
		s.pause(20).say("Soaked through, it leaves red wipes on a clean floor");
		strokes(s, st, 1.0F, 2.2);
		s.pause(30).say("Washed in water, the blood clouds out of it (with Fluid Gore on the server, everyone sees the sponge go red)");
		for (int i = 0; i < 3; i++) {
			s.client(mc -> Cleaning.wash(mc.level, st.at(1.5, 0.1, 2.0), 0x8A0303)).pause(15);
		}

		s.pause(40);
	}

	/** One wipe across the floor {@code forward} blocks out, left to right, with a tool {@code soak} bloody. */
	private static void strokes(Script s, Stage st, float soak, double forward) {
		Vec3 way = st.at(1.0, 0.0, forward).subtract(st.at(0.0, 0.0, forward)).normalize();
		for (int i = 0; i < 9; i++) {
			Vec3 at = st.at(-1.4 + i * 0.35, 0.0, forward);
			s.client(mc -> Cleaning.scrub(mc.level, GoreSystems.blood(), at, Direction.UP, way, soak, 0x8A0303)).pause(3);
		}
	}

	/**
	 * Hit reports (3.0): ownerless arrows at a cow side-on, low, middle and high. The server says where each flew, so
	 * each opens a wound where it struck; on a server without Fluid Gore they leave none (nobody shot them).
	 */
	private static void arrows(Script s, Stage st) {
		s.summon(st, "cow", "fgd_target", 0, 0, 5, 90.0F, "");
		s.pause(20).say("Arrows with no one behind them, as from a dispenser: each wounds where it strikes");
		// Along the cow, then up: a leg, the flank, the neck.
		double[][] shots = {{0.3, 0.35}, {0.0, 0.8}, {-0.5, 1.1}};
		for (double[] shot : shots) {
			Vec3 from = st.at(shot[0], shot[1], 2.0);
			Vec3 v = st.at(shot[0], shot[1], 5.0).subtract(from).normalize().scale(2.0);
			s.cmd(String.format(Locale.ROOT, "summon minecraft:arrow %.3f %.3f %.3f {NoGravity:1b,Motion:[%.3fd,%.3fd,%.3fd],Tags:[\"%s\"]}",
					from.x, from.y, from.z, v.x, v.y, v.z, Script.TAG)).pause(30);
		}

		s.say("The server told every player where each one flew: the same wounds on every screen").pause(60);
	}

	/**
	 * A zombie with its leg smashed, its arm cut and its belly opened walks past and limps, holding the wound. Then the
	 * player's own screen as if struck from the right and the left, and near death: none of it touches their real health.
	 */
	private static void injured(Script s, Stage st) {
		s.summon(st, "zombie", "fgd_limper", -2.5, 0, 4, -90.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("A zombie with a smashed leg, a cut arm and an open belly");
		s.client(mc -> {
			LivingEntity e = st.find(mc.level, EntityTypes.ZOMBIE, -2.5, 0, 4);
			if (e != null) {
				wound(e, BodyPart.RIGHT_LEG, Weapon.BLUNT, 14.0F);
				wound(e, BodyPart.LEFT_ARM, Weapon.BLADE, 8.0F);
				wound(e, null, Weapon.BLADE, 9.0F);
			}
		}).pause(30);
		s.say("It limps on the bad leg and holds its belly");
		for (int i = 0; i <= 80; i++) {
			s.cmd("tp @e[tag=fgd_limper,limit=1] " + st.pos(-2.5 + i * 0.06, 0, 4) + " " + st.yaw(-90.0F) + " 0").pause(1);
		}

		s.say("Now you: blood flies from the side the blow came from");
		s.client(mc -> GoreSystems.injuries().onHurt(mc.player, 6.0F, st.at(4, 1.5, 1), PlayerInjuries.Cause.STRUCK)).pause(40);
		s.client(mc -> GoreSystems.injuries().onHurt(mc.player, 9.0F, st.at(-4, 1.5, 2), PlayerInjuries.Cause.STRUCK)).pause(50);
		s.say("Near death: the edge closes in red and your heart pounds");
		s.client(mc -> GoreSystems.injuries().fakeHealth(0.08F, 160)).pause(170);
		s.cmd("kill @e[tag=fgd_limper]").pause(20);
	}

	/** Strikes {@code part} of {@code e} (its trunk if null) from the player's side, as a blow from {@code weapon} would. */
	private static void wound(LivingEntity e, @Nullable BodyPart part, Weapon weapon, float damage) {
		ModelParts.Posed posed = ModelParts.pose(e);
		if (posed == null) {
			return;
		}

		Vec3 target = part != null ? ModelHit.centre(e, posed, part) : ModelHit.centre(e, posed, ModelParts.torso(posed.model()));
		if (target != null) {
			Vec3 eye = target.add(e.getLookAngle().scale(1.5));
			GoreSystems.dismemberment().wounds().strike(e, eye, target.subtract(eye).normalize(), 4.0, weapon, damage);
		}
	}

	/** A zombie in a full set of armour cut to pieces: each piece flies off still wearing what covered it. */
	private static void armor(Script s, Stage st) {
		s.summon(st, "zombie", "fgd_armored", 0, 0, 4, 180.0F, "equipment:{head:{id:\"minecraft:diamond_helmet\"},chest:{id:\"minecraft:iron_chestplate\"},"
				+ "legs:{id:\"minecraft:leather_leggings\",components:{\"minecraft:dyed_color\":11546150}},feet:{id:\"minecraft:golden_boots\"}}");
		s.pause(20).hold("minecraft:iron_axe").say("Armour stays on what it covered: helmet on the head, sleeve on the arm, boots on the leg");
		s.client(mc -> {
			LivingEntity e = st.find(mc.level, EntityTypes.ZOMBIE, 0, 0, 4);
			if (e != null) {
				for (BodyPart part : new BodyPart[]{BodyPart.HEAD, BodyPart.RIGHT_ARM, BodyPart.LEFT_LEG}) {
					GoreSystems.dismemberment().severPart(GoreSystems.blood(), mc.level, e, part, e::position, e.yBodyRot, 0.0F, null);
				}
			}
		}).pause(30);
		s.hit("fgd_armored", 100).say("A creeper does the same to a player: the pieces fly, armour and all").pause(200);
	}

	/** A pig cut a few times, then given regeneration: the wounds clot, then close up one layer at a time. */
	private static void heal(Script s, Stage st) {
		s.summon(st, "pig", "fgd_heal", 0, 0, 3.5, 90.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("A pig cut open: it drips while the wounds are fresh");
		for (int i = 0; i < 3; i++) {
			s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.PIG, 0, 0, 3.5), null));
			s.hit("fgd_heal", 2.0F).pause(16);
		}

		s.pause(60).say("Healing: the cuts close as its health comes back, meat to a gash, a gash to a bruise, then nothing");
		s.cmd("effect give @e[tag=fgd_heal,limit=1] minecraft:regeneration 12 2").pause(240);
		s.cmd("kill @e[tag=fgd_heal]");
	}

	/** Ender blood glowing violet where it lies; at night or with shaders it lights up the ground. */
	private static void glow(Script s, Stage st) {
		s.summon(st, "enderman", "fgd_glow", 0, 0, 4, 90.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("Ender blood glows: watch it at night, or with a shader pack");
		for (int i = 0; i < 5; i++) {
			s.hit("fgd_glow", 3.0F).pause(12);
		}

		s.hit("fgd_glow", 100).say("Every splat, puddle and drop of it, and the raw cuts in the body, stay lit").pause(200);
	}

	/** An enderman hacked apart: ender blood and portal sparks, then its severed limbs blinking about on their own. */
	private static void ender(Script s, Stage st) {
		s.summon(st, "enderman", "fgd_enderman", 0, 0, 4, 90.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("An enderman bleeds the End: violet blood, and portal sparks pour from its wounds");
		for (int i = 0; i < 4; i++) {
			s.hit("fgd_enderman", 4.0F).pause(15);
		}

		s.say("Its limbs come off and will not lie still: they keep teleporting");
		s.client(mc -> {
			for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, mc.player.getBoundingBox().inflate(16), x -> x.getType() == EntityTypes.ENDERMAN)) {
				for (BodyPart part : new BodyPart[]{BodyPart.RIGHT_ARM, BodyPart.LEFT_ARM, BodyPart.RIGHT_LEG}) {
					GoreSystems.dismemberment().severPart(GoreSystems.blood(), mc.level, e, part, e::position, e.yBodyRot, 0.0F, null);
				}
			}
		}).pause(20);
		s.hit("fgd_enderman", 100).pause(260);
	}

	/** Pork and steak on the block, then a sheep hacked at in the middle of its herd until their wool is red. */
	private static void meats(Script s, Stage st) {
		s.summon(st, "pig", "fgd_pig", -2, 0, 4, 90.0F, "");
		s.summon(st, "cow", "fgd_cow", 2, 0, 4, 90.0F, "");
		s.pause(20).hold("minecraft:iron_axe").say("A pig and a cow, killed and split");
		s.hit("fgd_pig", 100).hit("fgd_cow", 100).pause(70);
		s.client(mc -> {
			for (Corpse c : new ArrayList<>(GoreSystems.suffering().corpses())) {
				ModelParts.Posed posed = ModelParts.pose(c.entity);
				if (posed != null) GoreSystems.dismemberment().butchery().split(GoreSystems.blood(), mc.level, c, ModelParts.torso(posed.model()), new Vec3(0, -1, 0));
			}
		}).pause(20);
		s.say("Diced: pale pork under its white fat, red steak marbled with it");
		for (int i = 0; i < 3; i++) {
			s.client(mc -> {
				for (GorePart p : new ArrayList<>(GoreSystems.dismemberment().parts().parts())) {
					if (!p.shattered) GoreSystems.dismemberment().butchery().cut(GoreSystems.blood(), mc.level, p, mc.player.getViewVector(1.0F));
				}
			}).pause(25);
		}

		s.pause(80);
		for (int i = 0; i < 5; i++) {
			s.summon(st, "sheep", "fgd_sheep" + i, 6 + (i % 3) * 1.3, 0, 4 + (i / 3) * 1.5, 90.0F, "");
		}

		s.pause(20).hold("minecraft:iron_sword").say("A sheep cut up in the middle of its herd: the wool drinks the blood, theirs too");
		for (int i = 0; i < 4; i++) {
			s.hit("fgd_sheep1", 1.5F).pause(15);
		}

		s.hit("fgd_sheep1", 100).pause(120);
		s.hit("fgd_sheep3", 2.0F).pause(10).hit("fgd_sheep3", 100).pause(160);
	}

	/** A horse and a donkey worked like chickens with a cleaver: opened and split in a few chops, every piece diced down. */
	private static void dice(Script s, Stage st) {
		s.summon(st, "horse", "fgd_dice_horse", -2, 0, 4, 90.0F, "");
		s.summon(st, "donkey", "fgd_dice_donkey", 2, 0, 4, 90.0F, "");
		s.pause(20).hold("minecraft:iron_axe").say("A horse and a donkey, butchered like chickens");
		s.hit("fgd_dice_horse", 100).hit("fgd_dice_donkey", 100).pause(80);
		s.say("A few chops through the trunk and it splits, however big the animal");
		for (int i = 0; i < 8; i++) {
			s.client(mc -> {
				for (Corpse c : GoreSystems.suffering().corpses()) {
					if (GoreSystems.dismemberment().vaporized(c.entity.getId())) continue;
					ModelParts.Posed posed = ModelParts.pose(c.entity);
					if (posed == null) continue;
					Vec3 target = ModelHit.centre(c.entity, posed, ModelParts.torso(posed.model()));
					if (target == null) continue;
					Vec3 eye = target.add(0, 1.2, 0);
					CorpseStrike.strike(mc.level, GoreSystems.blood(), c, eye, target.subtract(eye).normalize(), Weapon.CHOP, 9.0F);
				}
			}).pause(8);
		}

		s.pause(20).say("Then every piece, heads and all, is diced down to raw chunks");
		for (int i = 0; i < 4; i++) {
			s.client(mc -> {
				for (GorePart p : new ArrayList<>(GoreSystems.dismemberment().parts().parts())) {
					if (!p.shattered) GoreSystems.dismemberment().butchery().cut(GoreSystems.blood(), mc.level, p, mc.player.getViewVector(1.0F));
				}
			}).pause(20);
		}

		s.pause(100);
	}

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

	/** A chicken processed like on a farm: killed, plucked bare, jointed with a cleaver, the carcass split and diced. */
	/**
	 * The vanilla death made gory, and a body the server keeps (in singleplayer and on a LAN world the host is the
	 * server): it lies where it fell, is chopped where it lies, and stays until it is cleaned up with a sponge.
	 */
	private static void kept(Script s, Stage st) {
		s.summon(st, "cow", "fgd_kept_sword", -1.5, 0, 3, 90.0F, "");
		s.summon(st, "cow", "fgd_kept", 1.5, 0, 3, 90.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("A sword kill: slashed open, it goes red and over away from the blade, turning with the swing");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.COW, -1.5, 0, 3), null));
		s.hit("fgd_kept_sword", 100.0F).pause(50);
		s.hold("minecraft:iron_axe").say("An axe kill: hacked into, its legs buckle, then it keels over");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.COW, 1.5, 0, 3), null));
		s.hit("fgd_kept", 100.0F).pause(60);
		s.say("Out of the red, and dead: no puff of smoke. They lie there, bleeding out").pause(60);
		s.say("Chopped where it lies: everyone with the mod sees the same cuts");
		for (BodyPart part : BodyPart.values()) {
			for (int i = 0; i < 3; i++) {
				s.client(mc -> chopAt(mc, part)).pause(8);
			}
		}

		s.pause(60).hold("minecraft:sponge").say("It stays until someone cleans it up: right-click it with a sponge").pause(40);
		s.client(mc -> {
			Corpse corpse = nearest(mc);
			if (corpse != null) {
				GoreSync.act(GoreAction.of(GoreAction.CLEAN, corpse.entity.getId(), 0L, mc.player.getEyePosition(), mc.player.getViewVector(1.0F), 0, 0.0F));
			}
		}).pause(60);
	}

	/**
	 * Chickens die as chickens do: three killed three ways, each flapping itself to death in its own way; a live one cut
	 * so its feathers redden and fly; then a shower of rain soaks the living ones (in a biome where it rains).
	 */
	private static void chickens(Script s, Stage st) {
		s.summon(st, "chicken", "fgd_hen0", -3, 0, 4, 90.0F, "");
		s.summon(st, "chicken", "fgd_hen1", 0, 0, 4, 90.0F, "");
		s.summon(st, "chicken", "fgd_hen2", 3, 0, 4, 90.0F, "");
		s.pause(20).hold("minecraft:air").say("Bare hands: the neck wrung. The head hangs loose, and the wings go");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.CHICKEN, -3, 0, 4), BodyPart.HEAD));
		s.hit("fgd_hen0", 100.0F).pause(120);
		s.hold("minecraft:iron_sword").say("A cut throat: it lies bleeding a moment, then flaps as the brain dies");
		s.client(mc -> {
			aim(mc, st.find(mc.level, EntityTypes.CHICKEN, 0, 0, 4), BodyPart.HEAD);
			GoreSystems.suffering().forceNextDeath(Corpse.Kind.EXSANGUINATION, false);
		});
		s.hit("fgd_hen1", 100.0F).pause(150);
		s.hold("minecraft:iron_axe").say("An axe to the neck: the headless chicken. It flaps hardest and longest, throwing blood");
		s.client(mc -> {
			aim(mc, st.find(mc.level, EntityTypes.CHICKEN, 3, 0, 4), BodyPart.HEAD);
			GoreSystems.suffering().forceNextDeath(Corpse.Kind.DECAPITATION, false);
		});
		s.hit("fgd_hen2", 100.0F).pause(10);
		s.client(mc -> chopAt(mc, BodyPart.HEAD)).pause(200);

		s.summon(st, "chicken", "fgd_hen3", -1.5, 0, 2.5, 90.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("Cut, not killed: feathers knocked out wet with blood, its own reddening round the wound");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.CHICKEN, -1.5, 0, 2.5), null));
		s.hit("fgd_hen3", 1.5F).pause(40).hit("fgd_hen3", 1.0F).pause(60);
		s.cmd("weather rain");
		s.pause(20).say("In the rain the feathers go dark and slick, and the birds look half their size").pause(140);
		s.cmd("weather clear");
		s.say("Feathers lie where they fell, the bloody ones clumped. Walk through them and the dry ones lift").pause(80);
	}

	/**
	 * A one-block-wide stair cut down into the ground ahead. A body that falls across the top step slumps over it and
	 * goes down step by step to the bottom, as a dead weight does (with Fluid Gore on the server, the server moves it, so
	 * everyone sees it there).
	 */
	private static void stairs(Script s, Stage st) {
		s.say("A narrow stair, one block wide, cut down into the ground ahead");
		s.cmd("fill " + st.block(-1, -5, 1) + " " + st.block(1, 1, 10) + " minecraft:stone");
		s.cmd("fill " + st.block(0, 0, 1) + " " + st.block(0, 1, 1) + " minecraft:air");
		s.cmd("fill " + st.block(0, -4, 2) + " " + st.block(0, 1, 10) + " minecraft:air");
		for (int i = 0; i < 4; i++) {
			int up = -1 - i;
			if (up - 1 >= -4) {
				s.cmd("fill " + st.block(0, -4, 2 + i) + " " + st.block(0, up - 1, 2 + i) + " minecraft:stone");
			}

			s.cmd("setblock " + st.block(0, up, 2 + i) + " minecraft:oak_stairs[facing=" + st.back() + "]");
		}

		s.summon(st, "sheep", "fgd_stairs_sheep", 0, 0, 2.05, 0.0F, "");
		s.pause(30).hold("minecraft:iron_sword").say("A sheep at the top of the stair, killed where it stands");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.SHEEP, 0, 0, 2.05), null));
		s.hit("fgd_stairs_sheep", 100.0F).pause(40);
		s.say("Dead weight: half of it hangs over the step, so it slumps over the edge").pause(60);
		s.say("and goes on down, thumping onto every step, smearing blood as it drags").pause(80);
		s.summon(st, "pig", "fgd_stairs_pig", 0, 0, 2.05, 0.0F, "");
		s.pause(20).hold("minecraft:iron_axe").say("A pig after it");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.PIG, 0, 0, 2.05), null));
		s.hit("fgd_stairs_pig", 100.0F).pause(140);
		s.say("They lie at the bottom, one on the other, where they came to rest").pause(80);
		s.hold("minecraft:sponge").say("Cleaned up with a sponge");
		s.client(mc -> {
			for (Corpse corpse : new ArrayList<>(GoreSystems.suffering().corpses())) {
				if (corpse.entity.distanceToSqr(mc.player) < 16.0 * 16.0) {
					GoreSync.act(GoreAction.of(GoreAction.CLEAN, corpse.entity.getId(), 0L, mc.player.getEyePosition(), mc.player.getViewVector(1.0F), 0, 0.0F));
				}
			}
		}).pause(40);
	}

	/**
	 * Three bodies of three sizes rot side by side, a day skipped every second and a half (/time add, so this world's
	 * day count moves on six weeks). Needs the Extreme gore level and Fluid Gore on the server (singleplayer).
	 */
	private static void decay(Script s, Stage st) {
		s.summon(st, "chicken", "fgd_rot_chicken", -2.0, 0, 3.5, 90.0F, "");
		s.summon(st, "pig", "fgd_rot_pig", 0, 0, 3.5, 90.0F, "");
		s.summon(st, "cow", "fgd_rot_cow", 2.3, 0, 3.5, 90.0F, "");
		s.pause(20).hold("minecraft:iron_axe").say("Three animals killed and left where they fell (Extreme gore level)");
		s.hit("fgd_rot_chicken", 100.0F).hit("fgd_rot_pig", 100.0F).hit("fgd_rot_cow", 100.0F).pause(80);
		String[] days = new String[43];
		// Timed for the plains; a hotter biome is faster, a colder one slower.
		days[0] = "Day one: stiff with rigor mortis, pale, the first flies";
		days[2] = "Day three: the chicken bloats first; the smaller the body, the faster it goes";
		days[3] = "Day four: the chicken has burst; the pig swells tight and green, its legs pushed out stiff";
		days[6] = "Day seven: the pig splits open: maggots, a black pool, the flies at their worst";
		days[8] = "Day nine: the cow bursts";
		days[13] = "Two weeks: the chicken is bones; the pig and the cow sink into themselves";
		days[27] = "Four weeks: the pig is bones and leather";
		days[35] = "Five weeks: the cow is going the same way";
		days[42] = "Six weeks: bones and dried hide. In a desert they dry into mummies instead; in the snow they keep";
		for (int day = 0; day < days.length; day++) {
			if (days[day] != null) {
				s.say(days[day]).pause(50);
			}

			s.cmd("time add 24000").pause(30);
		}

		s.pause(200);
	}

	private static void butcher(Script s, Stage st) {
		s.summon(st, "chicken", "fgd_chicken", 0, 0, 2.5, 90.0F, "");
		s.pause(20).hold("minecraft:air").say("A chicken, killed with one blow");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.CHICKEN, 0, 0, 2.5), null));
		s.hit("fgd_chicken", 100.0F).pause(40);
		s.say("Plucked: right-click it with an empty hand, a handful at a time");
		for (int i = 0; i < 14; i++) {
			int n = i;
			s.client(mc -> {
				Corpse corpse = nearest(mc);
				if (corpse != null) {
					Vec3 centre = CorpseStrike.centre(corpse);
					Vec3 eye = centre.add(Math.cos(n * 0.8) * 0.9, 0.8, Math.sin(n * 0.8) * 0.9);
					GoreSystems.dismemberment().butchery().pluck(mc.level, corpse, eye, centre.subtract(eye).normalize());
				}
			}).pause(6);
		}

		s.say("Bare, goose-pimpled skin, the feather pits showing: a skinny thing under all those feathers").pause(40);
		s.hold("minecraft:iron_axe").say("Jointed: head, wings and legs off at the joints");
		for (BodyPart part : BodyPart.values()) {
			for (int i = 0; i < 3; i++) {
				s.client(mc -> chopAt(mc, part)).pause(8);
			}
		}

		s.say("The carcass split down the middle");
		for (int i = 0; i < 4; i++) {
			s.client(Demos::chopCarcass).pause(10);
		}

		s.say("And diced: a blade on any piece cuts it in two");
		for (int i = 0; i < 8; i++) {
			s.client(Demos::dice).pause(10);
		}

		s.pause(200);
	}

	/** The body nearest the player, or null. */
	private static @Nullable Corpse nearest(Minecraft mc) {
		Corpse nearest = null;
		double bestSq = 12.0 * 12.0;
		for (Corpse corpse : GoreSystems.suffering().corpses()) {
			double d = corpse.position().distanceToSqr(mc.player.position());
			if (d < bestSq && !GoreSystems.dismemberment().vaporized(corpse.entity.getId())) {
				nearest = corpse;
				bestSq = d;
			}
		}

		return nearest;
	}

	/** A cleaver blow on one part of the nearest body, from outside it. */
	private static void chopAt(Minecraft mc, BodyPart part) {
		Corpse corpse = nearest(mc);
		if (corpse == null || (GoreSystems.dismemberment().missingMask(corpse.entity.getId()) & part.bit()) != 0) {
			return;
		}

		ModelParts.Posed posed = ModelParts.pose(corpse.entity);
		Vec3 target = posed == null ? null : ModelHit.centre(corpse.entity, posed, part);
		if (target != null) {
			Vec3 eye = target.add(target.subtract(CorpseStrike.centre(corpse)).add(0.0, 0.3, 0.0).normalize().scale(1.5));
			lookAt(mc, target);
			// Through GoreSync, so a player watching with the mod sees the same cuts.
			GoreSync.act(GoreAction.of(GoreAction.STRIKE, corpse.entity.getId(), 0L, eye, target.subtract(eye).normalize(), Weapon.CHOP.ordinal(), 9.0F));
		}
	}

	/** A cleaver blow down through the middle of the nearest body. */
	private static void chopCarcass(Minecraft mc) {
		Corpse corpse = nearest(mc);
		if (corpse != null) {
			var posed = ModelParts.pose(corpse.entity);
			Vec3 centre = ModelHit.centre(corpse.entity, posed, ModelParts.torso(posed.model()));
			if (centre == null) return;
			Vec3 eye = centre.add(0.0, 1.2, 0.0);
			lookAt(mc, centre);
			CorpseStrike.strike(mc.level, GoreSystems.blood(), corpse, eye, centre.subtract(eye).normalize(), Weapon.CHOP, 9.0F);
		}
	}

	/** Cuts the biggest loose piece near the player in two. */
	private static void dice(Minecraft mc) {
		GorePart biggest = null;
		for (GorePart part : GoreSystems.dismemberment().parts().parts()) {
			if (!part.shattered && part.wounds != null && mc.player.distanceToSqr(part.x, part.y, part.z) < 100.0
					&& (biggest == null || part.halfExtents.lengthSquared() > biggest.halfExtents.lengthSquared())) {
				biggest = part;
			}
		}

		if (biggest != null) {
			lookAt(mc, new Vec3(biggest.x, biggest.y, biggest.z));
			GoreSystems.dismemberment().butchery().cut(GoreSystems.blood(), mc.level, biggest, mc.player.getViewVector(1.0F));
		}
	}

	/**
	 * A zombie (in a helmet, so the daylight leaves it be) slashed with a sword and chopped with an axe: long cuts along
	 * the swing, through marbled skin and waxy fat into grey-green muscle, pus and maggots. A cow beside it for
	 * comparison: the same sword, fresh red meat.
	 */
	private static void rot(Script s, Stage st) {
		s.summon(st, "zombie", "fgd_zombie", -1.2, 0, 3, 180.0F, "equipment:{head:{id:\"minecraft:leather_helmet\"}}");
		s.summon(st, "cow", "fgd_cow", 1.6, 0, 3.5, 90.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("A sword on a zombie: long cuts along the swing, into rotting flesh");
		for (int i = 0; i < 4; i++) {
			s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.ZOMBIE, -1.2, 0, 3), null));
			s.hit("fgd_zombie", 3.0F).pause(16);
		}

		s.say("Marbled skin, grey waxy fat, black ooze, putrid muscle, pus, maggots").pause(60);
		s.hold("minecraft:iron_axe").say("An axe chops wider and deeper, down to stained bone");
		for (int i = 0; i < 2; i++) {
			s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.ZOMBIE, -1.2, 0, 3), null));
			s.hit("fgd_zombie", 3.0F).pause(18);
		}

		s.hold("minecraft:iron_sword").say("The same sword on a cow: fresh red meat and yellow fat");
		for (int i = 0; i < 3; i++) {
			s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.COW, 1.6, 0, 3.5), null));
			s.hit("fgd_cow", 2.0F).pause(16);
		}

		s.pause(200);
	}

	/** Set alight where they stand: they char as they burn, writhe, and lie charred and smoking. */
	/**
	 * Three bodies of three weights burnt where they fell: a chicken, a sheep and a cow, lit with a flint and steel. The
	 * cow is put out with a splash of water half burnt; when the others are bones, its char is knocked off and all three
	 * skeletons are broken up.
	 */
	private static void cremation(Script s, Stage st) {
		s.summon(st, "chicken", "fgd_pyre_chicken", -3.5, 0, 4, 90.0F, "");
		s.summon(st, "sheep", "fgd_pyre_sheep", 0, 0, 4, 90.0F, "");
		s.summon(st, "cow", "fgd_pyre_cow", 4, 0, 4, 90.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("A chicken, a sheep and a cow, killed where they stand");
		s.hit("fgd_pyre_chicken", 100.0F).hit("fgd_pyre_sheep", 100.0F).hit("fgd_pyre_cow", 100.0F).pause(60);
		s.hold("minecraft:flint_and_steel").say("Fire put to each with a flint and steel: the feathers and the wool go up first");
		s.client(mc -> {
			for (Corpse corpse : GoreSystems.suffering().corpses()) {
				GoreSync.act(GoreAction.of(GoreAction.IGNITE, corpse.entity.getId(), 0L, mc.player.getEyePosition(), mc.player.getViewVector(1.0F), 0, 0.0F));
			}
		}).pause(120);
		s.say("The fat has caught: they burn on their own now. The heavier the body, the bigger the fire and the smoke").pause(220);
		s.say("The chicken is bones already. The heat draws the limbs in, like a boxer's").pause(300);
		s.say("Put out early: water on the cow, and it stays as it was, charred");
		s.cmd("fill " + st.block(3, 0, 3) + " " + st.block(5, 0, 5) + " minecraft:water replace air").pause(5);
		s.cmd("fill " + st.block(2, 0, 2) + " " + st.block(6, 1, 6) + " minecraft:air replace water").pause(10);
		s.cmd("fill " + st.block(2, 0, 2) + " " + st.block(6, 1, 6) + " minecraft:air replace water").pause(400);
		s.say("The sheep has burnt to the bone: black first, then grey, then white").pause(140);
		s.hold("minecraft:stone_shovel").say("Hit the charred cow: the char comes off the bone in black crumbs");
		for (int i = 0; i < 6; i++) {
			s.client(mc -> beatPyre(mc, EntityTypes.COW)).pause(10);
		}

		s.say("Then the bones break: the legs, the skull, the ribs, until there is only ash");
		for (int i = 0; i < 14; i++) {
			s.client(mc -> beatPyre(mc, EntityTypes.COW)).pause(8);
		}

		for (int i = 0; i < 7; i++) {
			s.client(mc -> beatPyre(mc, EntityTypes.SHEEP)).pause(8);
		}

		for (int i = 0; i < 7; i++) {
			s.client(mc -> beatPyre(mc, EntityTypes.CHICKEN)).pause(8);
		}

		s.say("Cleared away. Bones and char never drag at your legs like a carcass does").pause(100);
	}

	/** A blunt blow, through GoreSync, on the burnt body of {@code type} nearest the player. */
	private static void beatPyre(Minecraft mc, EntityType<?> type) {
		for (Corpse corpse : GoreSystems.suffering().corpses()) {
			if (corpse.entity.getType() != type || GoreSystems.dismemberment().vaporized(corpse.entity.getId())) {
				continue;
			}

			Vec3 centre = CorpseStrike.centre(corpse);
			Vec3 eye = centre.add(mc.player.position().subtract(centre).multiply(1.0, 0.0, 1.0).normalize().scale(1.2)).add(0.0, 1.0, 0.0);
			lookAt(mc, centre);
			GoreSync.act(GoreAction.of(GoreAction.STRIKE, corpse.entity.getId(), 0L, eye, centre.subtract(eye).normalize(), Weapon.BLUNT.ordinal(), 5.0F));
			return;
		}
	}

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
		s.cmd("setblock " + st.block(-4, 0, 6) + " fluidgore:cobble_tank[leak=pinhole,scars=3]{Damage:0.6f,Crack:0.05f," + towards + "}");
		s.pause(20).say("A pinhole: it hisses for minutes, frost grows at the crack. Standing this close, your hearing goes");
		s.pause(140);
		s.cmd("setblock " + st.block(15, 0, 20) + " fluidgore:cobble_tank[leak=cracked,scars=3]{Damage:0.8f,Crack:0.6f," + towards + "}");
		s.say("A cracked fitting: 5 kN of thrust on a 400 kg tank. It tears loose and flies");
		s.pause(120);
		s.cmd("setblock " + st.block(0, 0, 30) + " fluidgore:cobble_tank");
		s.summon(st, "cow", "fgd_tank_cow", 0.5, 0, 33.5, 90.0F, "");
		s.summon(st, "cow", "fgd_tank_cow2", 7.5, 0, 30.5, 0.0F, "");
		s.summon(st, "cow", "fgd_tank_cow3", -12.0, 0, 30.5, 45.0F, "");
		s.cmd("setblock " + st.block(-18, 0, 30) + " minecraft:oak_fence_gate");
		s.cmd("fill " + st.block(-6, 0, 50) + " " + st.block(6, 2, 50) + " minecraft:glass_pane");
		s.pause(40).say("A full one, 30 blocks off. Cows at 3, 7 and 12 m from it; a gate at 18 m, glass at 20 m beyond. Lit...");
		s.cmd("setblock " + st.block(0, 1, 30) + " minecraft:redstone_block");
		s.pause(100).say("Gone at 3 m. Dead at 7. Hurt at 12. The gate blown open, the glass out. Read the chat for yours").pause(200);
		s.say("You were 30 m away: 11 kPa, enough to ring your ears").pause(200);
	}

	/**
	 * A 2x2 of TNT 9 to 10 blocks ahead is the Roadster's pressure vessel, lit with redstone. Animals stand in rings
	 * round it (2, 3, 4.5 and 7 m); the player, 9 m off and out of the TNT's reach, still gets about 45 kPa: burst eardrums.
	 */
	private static void roadster(Script s, Stage st) {
		s.say("A 2x2 of TNT is the Roadster's tank: 200 litres of gas at 10,000 psi, about 7 kg of TNT");
		s.cmd("fill " + st.block(0, 0, 9) + " " + st.block(1, 0, 10) + " minecraft:tnt");
		s.summon(st, "cow", "fgd_vessel_cow", 0.5, 0, 11.5, 90.0F, "");
		s.summon(st, "sheep", "fgd_vessel_sheep", 3.5, 0, 9.5, 0.0F, "");
		s.summon(st, "pig", "fgd_vessel_pig", -3.5, 0, 12.0, 45.0F, "");
		s.summon(st, "cow", "fgd_vessel_far_cow", 0.5, 0, 16.5, 90.0F, "");
		s.pause(40).say("2 m: a cow. 3 m: a sheep. 4.5 m: a pig. 7 m: another cow. You: 9 m");
		s.pause(60).say("Lit. Four seconds...");
		s.cmd("fill " + st.block(0, 1, 9) + " " + st.block(1, 1, 10) + " minecraft:redstone_block");
		s.pause(90).say("Gone at 2 m. Torn apart at 3. Lungs burst at 4.5. Eardrums and fragments at 7. Read the chat for yours").pause(200);
		s.say("Your hearing comes back slowly; the ringing goes last").pause(300);
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
