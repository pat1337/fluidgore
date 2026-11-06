package dev.fluidgore.demo;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.Cleaning;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.GorePart;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.dismember.Viscera;
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
	/** Fluid Gore's own demos. */
	private static final List<Demo> OWN = List.of(
			new Demo("underwater", "4.0.0", "TNT under water: a block of TNT goes off at the bottom of a deep pool between two pigs. No flame and no scorch: its gas swells into a bubble, collapses and rebounds, the water rushing out and back with it, then bursts out of the surface in a plume of spray that rains back down. The pig beside it is torn apart inside the bubble and its blood clouds out in the water; the one further along only coughs blood from its lungs", true, Demos::underwater),
			new Demo("deadblast", "4.0.0", "A blast on bodies already dead: two cows killed, then a block of TNT beside them. The body next to it comes apart, and the one further off is thrown limp by the wave", true, Demos::deadBlast),
			new Demo("wetpieces", "4.0.0", "Pieces in water: a sheep blown apart at the bottom of a deep pool. Its blood clouds in the water; the lungs and the fat come up and float, the bones lie on the bottom, and meat and organs sink after them", true, Demos::piecesInWater),
			new Demo("lying", "4.0.0", "Bodies lying together: four pigs and a sheep packed tight, one stuck first, are killed together. They come to rest side by side, against each other and not through each other, and the stuck one's blood smears the flanks of the bodies against it. With Ragdoll Physics on, they set with their legs locked straight out from the trunk", false, Demos::lying),
			new Demo("water", "4.0.0", "A body in water: a pig killed in a deep pool sinks slowly to the bottom, its blood clouding round it. Then ten days are skipped: with Rot on (Extreme), the bloated body comes up and floats low in the water", true, Demos::water),
			new Demo("coats", "4.0.0", "Blood on the animals and in water: a pig stuck beside a sheep and a cow spatters their coats; its pool runs under the sheep, and blood climbs the wool of its legs higher than it reaches on the pig's hide. Then a pig stuck standing in a trough of water: its blood clouds out in the water, sinks and thins away", true, Demos::coats),
			new Demo("pallor", "4.0.0", "Pallor: two pigs side by side, and one is stuck and bled out. As the blood leaves it the pink flush goes out of its skin, and it lies a washed-out, pale pink beside the one that never bled", false, Demos::pallor),
			new Demo("stuckpig", "4.0.0", "A pig stuck with a sword: the blade to its head goes into the throat, and the blood pours down its chest and the fronts of both forelegs. It bleeds out, goes down, and the blood runs on across the side it lies on. Then a punch on another pig: a bruise, a red flush under unbroken skin", false, Demos::stuckPig),
			new Demo("headsplit", "4.0.0", "A sheep's head and a lamb's, cut off and split down the middle with a cleaver: each half falls open, cut face up, showing the skull and jaw open to their marrow, the brain's grey and white matter, the tongue, the larynx and the throat where they lie. The lamb's is smaller. Look at a half to see what it is, pick it up, or cut it on down", false, Demos::headSplit),
			new Demo("resolution", "4.0.0", "Anatomy detail side by side: four shorn ewes opened raw, their insides cut at 1x (a cube a pixel), 2x (half pixels), 4x (quarter pixels) and mixed (2x with the heart, great vessels and kidneys at 4x, the anatomy every sheep has), each name-tagged, to compare by eye. Skin, fat and muscle stay a cell a pixel on all four", false, Demos::resolution),
			new Demo("coworgans", "3.3.1", "Cow-specific anatomy: an opened cow beside a sheep, then a cow's liver, both lungs and both kidneys laid out as named, shaped pieces. Pick them up, throw them, or cut them with a blade", false, Demos::cowOrgans),
			new Demo("organs", "3.3.0", "A sheep's liver and lungs out of the body and handled: beside a ewe opened to show them in place, the liver and both lungs of another are taken out whole, each its own piece of the lobes it is made of, as it lay. A blade cuts the liver in two, dark glossy parenchyma and veins on the cut, and a lung, pink sponge and airways; a portion is thrown onto a campfire, cooks, chars and catches, and water puts it out. Pick them up and throw them (use), punch them, cut them again with a blade; look at one to see what it is", true, Demos::organs),
			new Demo("charred", "3.3.0", "Three shorn sheep burnt three ways: one on its own fat leaves a charred, mottled skeleton, brown and black, as an ordinary fire does; one on a campfire that goes on under its bare bones greys them and slowly chalks them white; one in lava calcines fast. Broken up, the charred bones crumble to black char and the calcined ones to pale dust", true, Demos::charred),
			new Demo("blast", "3.3.0", "A block of TNT (about 8 kg of real explosive) under a cow, and cows at 3, 5, 8 and 12 metres, each in its own direction: the one over the charge comes apart into its own named organs and bones, the near side first and fastest; at 3 m the legs facing the blast tear off and the belly bursts; at 5 m blast lung kills it whole, blood from the mouth and nose; at 8 and 12 m they live, ears bleeding. Look at any piece to see what it is", true, Demos::blast),
			new Demo("beasts", "3.3.0", "A sheep, a pig and a cow set on fire: the fleece smoulders under white smoke and chars off in black patches while the pig's bare hide blisters at once; dead, they burn on through their own anatomy, the sheep's hard white suet, the pig's soft lard and the cow's yellow fat running, the meat cooking from red to brown, the ribs, lungs, heart, stomachs and guts showing, down to skeletons standing on charred hooves", false, Demos::beasts),
			new Demo("lamb", "3.3.0", "A sheep killed and jointed as a butcher joints one: neck, shoulder, rack, loin and leg across the spine, breast and flank off the belly, dark red mutton under hard white fat; then the joints split down the spine and cut into chops", false, Demos::lamb),
			new Demo("burn", "3.2.0", "A glass pen of long grass, hay and a plank wall, and a pig and a cow set on fire: the panicking pig lights the grass it runs through, the fire climbs the wall, and with Fluid Gore on the server the bodies that died burning burn on, on their own fat, lighting what they lie against; then it is put out", true, Demos::burn),
			new Demo("melt", "3.2.0", "Two husks, shaped like players, set on fire: the skin reddens, blisters and chars as the fire takes their health; dead, they burn on their own fat, the skin through to the yellow fat that melts and drips, then the muscle, the ribs and organs, down to a charred and then white skeleton that breaks up under blows", false, Demos::melt),
			new Demo("sponge", "3.1.0", "A pig's blood and guts cleaned up: a clean sponge lifts the blood, a bloody one smears it along the stroke and squashes the guts into streaks, a soaked one leaves red wipes on a clean floor, and the blood clouds out of it when it is washed", false, Demos::sponge),
			new Demo("arrows", "3.0.0", "Arrows with no one behind them, as from a dispenser, at a cow side-on: with Fluid Gore on the server each wounds exactly where it strikes (leg, flank, neck), the same on every screen", false, Demos::arrows),
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

	/** Demos added by add-ons (the Cobble Tank), listed after Fluid Gore's own. */
	private static final List<Demo> ADDED = new java.util.concurrent.CopyOnWriteArrayList<>();

	private Demos() {
	}

	/** Every demo: Fluid Gore's own, newest first, then each add-on's. */
	public static List<Demo> all() {
		List<Demo> all = new ArrayList<>(OWN);
		all.addAll(ADDED);
		return all;
	}

	/** Adds an add-on's demo; its name must not already be taken. */
	public static void add(Demo demo) {
		if (get(demo.name()) != null) throw new IllegalArgumentException("Demo name taken: " + demo.name());
		ADDED.add(demo);
	}

	public static @Nullable Demo get(String name) {
		for (Demo demo : all()) {
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
				GoreSync.act(GoreAction.of(GoreAction.CLEAN, corpse.entity.getId(), 0L, mc.player.getEyePosition(), mc.player.getViewVector(1.0F), 0, 1.0F));
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
					GoreSync.act(GoreAction.of(GoreAction.CLEAN, corpse.entity.getId(), 0L, mc.player.getEyePosition(), mc.player.getViewVector(1.0F), 0, 1.0F));
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

	/**
	 * 3.2 burning bodies set fire to what they touch, from the game test's pen: a glass box (so the fire stays in it) of
	 * long grass, hay and a plank wall, a pig that runs about burning and a cow that stands. The fire is put out at the
	 * end. Cleared to nine blocks up: fire reaches four blocks above itself, and the wall burns three up.
	 */
	private static void burn(Script s, Stage st) {
		s.say("A glass pen of long grass, with hay and a plank wall");
		s.cmd("fill " + st.block(-6, 0, 2) + " " + st.block(6, 9, 14) + " minecraft:air");
		s.cmd("fill " + st.block(-5, -1, 3) + " " + st.block(5, 5, 13) + " minecraft:glass hollow");
		s.cmd("fill " + st.block(-4, -1, 4) + " " + st.block(4, -1, 12) + " minecraft:grass_block");
		s.cmd("fill " + st.block(3, 0, 4) + " " + st.block(4, 2, 12) + " minecraft:oak_planks");
		s.cmd("fill " + st.block(-1, 0, 10) + " " + st.block(0, 0, 11) + " minecraft:hay_block");
		s.cmd("fill " + st.block(-4, 0, 4) + " " + st.block(2, 0, 12) + " minecraft:short_grass replace air");
		s.summon(st, "pig", "fgd_burn_pig", 1.5, 0, 6.5, 90.0F, "Fire:900s");
		s.summon(st, "cow", "fgd_burn_cow", -3.0, 0, 8.5, 90.0F, "Fire:900s");
		// Summoned still, as every demo mob is; the pig is let loose to run.
		s.cmd("data merge entity @e[tag=fgd_burn_pig,limit=1] {NoAI:0b}");
		s.pause(40).say("A pig and a cow, set on fire. In Vanilla a burning animal lights nothing; this one is a torch").pause(140);
		s.say("Running in a panic, the pig lights the dry grass it brushes past").pause(200);
		s.say("It is real fire: it climbs the plank wall, and water or rain puts it out").pause(200);
		s.say("With Fluid Gore on the server, a body that died burning burns on, on its own fat, and lights what it lies against").pause(300);
		s.say("Put out");
		s.cmd("fill " + st.block(-4, 0, 4) + " " + st.block(4, 4, 12) + " minecraft:air replace minecraft:fire").pause(60);
	}

	/** 3.3: a block of TNT and cows at real distances from it, read like a scene afterwards. */
	private static void blast(Script s, Stage st) {
		double[] metres = {0.0, 3.0, 5.0, 8.0, 12.0};
		double[] angle = {0.0, 0.5, 2.1, 3.6, 5.2};
		for (int i = 0; i < metres.length; i++) {
			s.summon(st, "cow", "fgd_blast" + i, Math.cos(angle[i]) * metres[i], 0, 14 + Math.sin(angle[i]) * metres[i], 90.0F, "");
		}

		s.pause(20).say("A block of TNT is about 8 kg of real explosive. One under a cow; others at 3, 5, 8 and 12 metres");
		s.cmd("summon minecraft:tnt " + st.pos(0, 0, 14) + " {fuse:80}").pause(90);
		s.say("Over the charge the cow comes apart into its own organs and bones: the underside first and fastest").pause(100);
		s.say("At 3 m the legs that faced the blast are torn off and the belly bursts").pause(80);
		s.say("At 5 m it dies whole: blast lung, blood from the mouth and nose").pause(80);
		s.say("At 8 and 12 m they live. Their eardrums burst").pause(80);
		s.say("Every piece keeps its name: walk the scene and look at them, they read out over your hotbar").pause(200);
	}

	/** 3.3: a sheep, a pig and a cow burning alive and burning on, each through its own anatomy. */
	private static void beasts(Script s, Stage st) {
		s.summon(st, "sheep", "fgd_beast_sheep", -3.0, 0, 4.5, 90.0F, "");
		s.summon(st, "pig", "fgd_beast_pig", 0.0, 0, 4.5, 90.0F, "");
		s.summon(st, "cow", "fgd_beast_cow", 3.5, 0, 4.5, 90.0F, "");
		s.pause(20).say("A sheep, a pig and a cow, set on fire");
		for (String role : new String[] {"fgd_beast_sheep", "fgd_beast_pig", "fgd_beast_cow"}) {
			s.cmd("execute as @e[tag=" + role + "] run data merge entity @s {Fire:2000s}");
		}

		s.pause(60).say("Wool hardly burns: the fleece smoulders under white smoke, scorches and chars off in black patches");
		s.pause(80).say("The pig has no fleece to shield it: its hide reddens, blisters and splits at once").pause(100);
		s.say("Dead, they burn on their own fat: the sheep's hard white suet, the pig's soft lard, the cow's yellow fat").pause(300);
		s.say("The meat under it cooks from red to grey-brown and shrinks onto the bone; the legs draw up").pause(300);
		s.say("Through the ribs: the lungs and the heart; behind them the cow's and the sheep's great rumen, the pig's one stomach").pause(300);
		s.say("At the end, skeletons: the bones charred, then grey, then white, still standing on their hooves of horn").pause(400);
		s.hold("minecraft:stone_shovel").say("And the bones break up under blows");
		for (int i = 0; i < 8; i++) {
			s.client(mc -> beatPyre(mc, EntityTypes.SHEEP)).pause(8);
		}

		s.pause(60);
	}

	/** Cow-specific trunk beside a sheep, with five loose organs using the existing player controls. */
	private static void cowOrgans(Script s, Stage st) {
		s.summon(st, "cow", "fgd_coworgans_open", -1.8, 0, 4, 90.0F, "Invulnerable:1b");
		s.summon(st, "sheep", "fgd_coworgans_sheep", 1.8, 0, 4, 90.0F, "Sheared:1b,Invulnerable:1b");
		s.pause(25).say("A cow and a sheep: each now has its own trunk anatomy");
		for (int i = 0; i < 20; i++) {
			s.client(mc -> {
				LivingEntity cow = st.find(mc.level, EntityTypes.COW, -1.8, 0, 4);
				LivingEntity sheep = st.find(mc.level, EntityTypes.SHEEP, 1.8, 0, 4);
				if (cow != null) GoreSystems.dismemberment().melting().set(cow, 0.6F);
				if (sheep != null) GoreSystems.dismemberment().melting().set(sheep, 0.6F);
			}).pause(10);
		}
		s.summon(st, "cow", "fgd_coworgans_donor", 0, 0, 6.5, 90.0F, "Invulnerable:1b");
		s.pause(20).say("The cow's liver and gallbladder, two lungs, and two lobulated kidneys, out whole");
		s.client(mc -> {
			LivingEntity cow = st.find(mc.level, EntityTypes.COW, 0, 0, 6.5);
			if (cow == null) return;
			var made = Viscera.lift(mc.level, GoreSystems.dismemberment().giblets(), cow, java.util.Set.of("liver", "lung", "kidney"), new Vec3(0, 0.25, 0));
			int i = 0;
			for (GorePart p : made) {
				Vec3 at = st.at(-1.8 + i++ * 0.9, 0.8, 2.5);
				p.x = p.prevX = at.x;
				p.y = p.prevY = at.y;
				p.z = p.prevZ = at.z;
			}
		}).pause(80);
		s.say("Look at a piece to identify it. Use to carry and throw; strike with a blade to cut it into portions").pause(100);
	}

	/** The four grains of a sheep's inside, left to right, for {@link #resolution}. */
	private static final dev.fluidgore.anatomy.Anatomy.Detail[] GRAINS = dev.fluidgore.anatomy.Anatomy.Detail.values();
	private static final String[] GRAIN_NAMES = {"1x", "2x", "4x", "Mixed (now)"};

	/**
	 * 4.0: the same shorn ewe opened four times side by side, raw, with its inside cut at each grain (see
	 * {@link dev.fluidgore.anatomy.Anatomy.Detail}), to choose the look by eye. Skin, fat and muscle stay a cell a pixel on all four.
	 */
	private static void resolution(Script s, Stage st) {
		for (int i = 0; i < GRAINS.length; i++) {
			s.summon(st, "sheep", "fgd_resolution_" + i, grainAt(i), 0, 3.5, 90.0F,
					"Sheared:1b,Invulnerable:1b,Silent:1b,CustomNameVisible:1b,CustomName:\"" + GRAIN_NAMES[i] + "\"");
		}

		s.pause(20).say("Four shorn ewes, the same anatomy cut four ways. Skin, fat and muscle are a cell a pixel on all of them");
		grains(s, st, 0.45F, 8);
		s.say("Opened further: the ribs, lungs, heart and rumen. Walk round them and look close");
		grains(s, st, 0.65F, 12);
		s.say("Down to the organs: 1x is blocky like the wool was, 2x keeps the organs apart, 4x shows loops and every rib");
		grains(s, st, 0.85F, 16);
		s.say("Mixed, every sheep's anatomy now, keeps 2x but cuts the heart, great vessels and kidneys at 4x. They stay opened: compare as long as you like");
		grains(s, st, 0.85F, 4);
	}

	/** Where grain {@code i}'s ewe stands, right of the player in blocks: a body and a half apart. */
	private static double grainAt(int i) {
		return (i - (GRAINS.length - 1) * 0.5) * 1.8;
	}

	/** {@code tenths} half-seconds, keeping every ewe of {@link #resolution} opened {@code bare} deep at its own grain. */
	private static void grains(Script s, Stage st, float bare, int tenths) {
		for (int t = 0; t < tenths; t++) {
			s.client(mc -> {
				for (int i = 0; i < GRAINS.length; i++) {
					LivingEntity e = st.find(mc.level, EntityTypes.SHEEP, grainAt(i), 0, 3.5);
					if (e != null) {
						GoreSystems.dismemberment().melting().sample(e, bare, GRAINS[i]);
					}
				}
			}).pause(10);
		}
	}

	/** Sheep organs through ejection, cutting, fire and water; stage-relative so it can be replayed anywhere. */
	private static void organs(Script s, Stage st) {
		s.summon(st, "sheep", "fgd_organs_open", -1.6, 0, 3.5, 90.0F, "Sheared:1b,Invulnerable:1b");
		s.summon(st, "sheep", "fgd_organs_out", 1.6, 0, 3.5, 90.0F, "Sheared:1b,Invulnerable:1b");
		s.pause(20).say("Two shorn ewes. The left one opened: the liver and lungs in place behind the ribs");
		hold(s, st, 6);
		s.say("From the right one, the liver and both lungs, taken out whole");
		s.client(mc -> {
			LivingEntity donor = st.find(mc.level, EntityTypes.SHEEP, 1.6, 0, 3.5);
			if (donor != null) {
				// Up and a little towards the player, to land on the grass in front of the ewe.
				Vec3 toward = st.at(0, 0, 0).subtract(st.at(0, 0, 1)).scale(0.06).add(0.0, 0.3, 0.0);
				Viscera.lift(mc.level, GoreSystems.dismemberment().giblets(), donor, java.util.Set.of("liver", "lung"), toward);
			}
		});
		hold(s, st, 8);
		s.say("Each its own piece, as it lay in the body: the liver's lobes and green gallbladder, each lung's lobes");
		hold(s, st, 8);
		s.say("A blade through the liver: two portions, still liver, the cut dark and glossy with the veins showing");
		s.client(mc -> cut(mc, "liver"));
		hold(s, st, 8);
		s.say("And through a lung: pink sponge, and the airways cut across");
		s.client(mc -> cut(mc, "lung"));
		hold(s, st, 8);
		s.say("A portion of lung onto a campfire: it steams, cooks grey, chars and catches");
		s.cmd("setblock " + st.block(0, 0, 2) + " minecraft:campfire[lit=true]");
		s.client(mc -> onto(mc, "lung", st.at(0, 0.5, 2)));
		hold(s, st, 24);
		s.say("Water puts it out");
		s.cmd("setblock " + st.block(0, 0, 2) + " minecraft:water");
		hold(s, st, 8);
		s.cmd("setblock " + st.block(0, 0, 2) + " minecraft:air");
		s.say("Pick them up and throw them with use, punch them, cut them again with a blade; look at one to see what it is");
		hold(s, st, 10);
	}

	/** {@code tenths} half-seconds, keeping the left ewe opened meanwhile. */
	private static void hold(Script s, Stage st, int tenths) {
		for (int i = 0; i < tenths; i++) {
			s.client(mc -> openUp(mc, st, -1.6)).pause(10);
		}
	}

	/**
	 * The player cuts the biggest piece of a sheep's organ called {@code name} with a blade, from where they stand: the same
	 * action their own cut sends (see {@code PartInteraction}), so every screen cuts it the same.
	 */
	private static void cut(Minecraft mc, String name) {
		GorePart target = biggest(name);
		if (target != null && mc.player != null) {
			Vec3 eye = mc.player.getEyePosition();
			Vec3 look = new Vec3(target.x, target.y, target.z).subtract(eye).normalize();
			GoreSync.act(GoreAction.of(GoreAction.CUT, -1, target.key, eye, look, 0, 0.0F));
		}
	}

	/** Throws the biggest piece of a sheep's organ called {@code name} in an arc to land on {@code spot}, as the player would. */
	private static void onto(Minecraft mc, String name, Vec3 spot) {
		GorePart p = biggest(name);
		if (p == null) {
			return;
		}

		dev.fluidgore.net.PieceSync.moved(p);
		p.resting = false;
		p.restTicks = 0;
		p.restTarget = null;
		// About a second's flight.
		p.vx = (spot.x - p.x) / 20.0;
		p.vz = (spot.z - p.z) / 20.0;
		p.vy = 0.55 + (spot.y - p.y) / 20.0;
	}

	/** The piece of a sheep's organ called {@code name} with the most cubes, or null. */
	private static @Nullable GorePart biggest(String name) {
		GorePart best = null;
		int most = 0;
		for (GorePart p : GoreSystems.dismemberment().parts().parts()) {
			if (name.equals(p.organ) && p.owner == EntityTypes.SHEEP && !p.shattered) {
				int cubes = dev.fluidgore.dismember.OrganModel.cubes(p.model);
				if (cubes > most) {
					most = cubes;
					best = p;
				}
			}
		}

		return best;
	}

	/** Keeps the sheep at stage spot {@code right} opened to its organs (a living body heals its burns; this holds them). */
	private static void openUp(Minecraft mc, Stage st, double right) {
		LivingEntity e = st.find(mc.level, EntityTypes.SHEEP, right, 0, 3.5);
		if (e != null) {
			GoreSystems.dismemberment().melting().set(e, 0.6F);
		}
	}

	/** 3.3: a sheep jointed into its cuts, then the cuts split down the spine and cut to chops. */
	private static void lamb(Script s, Stage st) {
		s.summon(st, "sheep", "fgd_lamb", 0, 0, 3.5, 90.0F, "");
		s.pause(20).hold("minecraft:iron_axe").say("A sheep, killed and opened");
		s.hit("fgd_lamb", 100).pause(70);
		s.say("Jointed as a butcher does it: neck, shoulder, rack, loin and leg, the breast and flank off the belly");
		s.client(mc -> {
			for (Corpse c : new ArrayList<>(GoreSystems.suffering().corpses())) {
				ModelParts.Posed posed = ModelParts.pose(c.entity);
				if (posed != null && c.entity.getType() == EntityTypes.SHEEP) {
					GoreSystems.dismemberment().butchery().split(GoreSystems.blood(), mc.level, c, ModelParts.torso(posed.model()), new Vec3(0, -1, 0));
				}
			}
		}).pause(100);
		s.say("Dark red mutton under a rim of hard white fat; the spine and ribs sawn through");
		s.pause(60).say("Each joint split down the spine: two racks, two loins, two shoulders");
		s.client(Demos::cutAll).pause(80);
		s.say("And on down to chops");
		s.client(Demos::cutAll).pause(40);
		s.client(Demos::cutAll).pause(120);
	}

	/** 4.0: a sheep's head and a lamb's split down the middle, the skull, brain and tongue on the cut faces. */
	private static void headSplit(Script s, Stage st) {
		s.summon(st, "sheep", "fgd_split_ewe", -0.8, 0, 3.5, 90.0F, "Sheared:1b");
		s.summon(st, "sheep", "fgd_split_lamb", 0.8, 0, 3.5, 90.0F, "Sheared:1b,Age:-2000000000");
		s.pause(20).hold("minecraft:iron_axe").say("A sheep and a lamb: their heads off with a cleaver");
		s.client(mc -> {
			for (var e : mc.level.entitiesForRendering()) {
				if (e instanceof LivingEntity living && e.getType() == EntityTypes.SHEEP && living.isAlive() && e.distanceTo(mc.player) < 8.0F) {
					GoreSystems.dismemberment().severPart(GoreSystems.blood(), mc.level, living, BodyPart.HEAD, living::position, living.yBodyRot, 0.0F, null);
				}
			}
		}).pause(5);
		s.hit("fgd_split_ewe", 100.0F).hit("fgd_split_lamb", 100.0F).pause(80);
		s.say("Each head split down the middle, as a butcher splits one for the brain and the tongue");
		s.client(mc -> {
			for (GorePart p : new ArrayList<>(GoreSystems.dismemberment().parts().parts())) {
				if (!p.shattered && p.section != null && p.section.half() == 0) {
					Vec3 mid = new Vec3(p.x, p.y, p.z);
					Vec3 eye = mid.add(0.0, 1.2, -0.3);
					GoreSync.act(GoreAction.of(GoreAction.CUT, -1, p.key, eye, mid.subtract(eye).normalize(), 0, 0.0F));
				}
			}
		}).pause(60);
		s.say("The halves fall open: the skull and jaw cut to their marrow, the brain grey outside and white within");
		s.pause(100).say("The tongue, the larynx and the throat run back along the floor of the head; the lamb's is smaller");
		s.pause(140);
	}

	/** 4.0: pallor. From the game test {@code pallor}. */
	private static void pallor(Script s, Stage st) {
		s.summon(st, "pig", "fgd_pale", -1.2, 0, 3.0, 90.0F, "");
		s.summon(st, "pig", "fgd_ruddy", 1.2, 0, 3.0, 90.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("Two pigs, both pink with the blood in their skin");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.PIG, -1.2, 0, 3.0), BodyPart.HEAD));
		s.hit("fgd_pale", 4.0F).pause(30);
		s.cmd("damage @e[tag=fgd_pale,limit=1] 100 minecraft:generic");
		s.hold("minecraft:air").say("One is stuck and bleeds out: as the blood leaves it, the flush goes out of its skin").pause(200);
		s.say("Bled out, it lies a washed-out, pale pink beside the one that never bled").pause(160);
	}

	/** 4.0: a blast on bodies already dead. From the game test {@code deadBlast}. */
	private static void deadBlast(Script s, Stage st) {
		s.summon(st, "cow", "fgd_db_near", -1.0, 0, 6.0, 90.0F, "");
		s.summon(st, "cow", "fgd_db_far", 3.0, 0, 6.0, 90.0F, "");
		s.pause(20).say("Two cows, killed where they stand");
		s.cmd("damage @e[tag=fgd_db_near,limit=1] 30 minecraft:generic").cmd("damage @e[tag=fgd_db_far,limit=1] 30 minecraft:generic").pause(120);
		s.say("A block of TNT goes off beside the bodies");
		s.cmd("summon minecraft:tnt " + st.pos(-2.0, 0, 6.0) + " {fuse:40}").pause(50);
		s.say("The wave reaches the dead as it does the living: the body beside it comes apart, the one further off is thrown limp").pause(160);
	}

	/** 4.0: TNT under water. From the game test {@code underwaterBlast}. */
	private static void underwater(Script s, Stage st) {
		s.cmd("fill " + st.block(-3, -4, 3) + " " + st.block(9, -1, 8) + " minecraft:water");
		s.summon(st, "pig", "fgd_uw_near", -1.0, -4.0, 5.5, 90.0F, "");
		s.summon(st, "pig", "fgd_uw_far", 8.5, -4.0, 5.5, 90.0F, "");
		s.pause(30).say("Two pigs at the bottom of a deep pool, and a block of TNT beside one of them");
		s.cmd("summon minecraft:tnt " + st.pos(0.5, -4.0, 5.5) + " {fuse:40}").pause(45);
		s.say("No flame and no scorch: the gas swells into a bubble, the water is crushed back in and it rebounds, rising").pause(40);
		s.say("It bursts out of the surface in a plume of spray, which rains back down").pause(80);
		s.say("The pig beside it was inside the bubble: torn apart, its blood clouding out in the water").pause(100);
		s.say("The one further along took the shock through the water: blood from its mouth and nose, from the lungs").pause(120);
	}

	/** 4.0: pieces in water. From the game test {@code piecesInWater}. */
	private static void piecesInWater(Script s, Stage st) {
		s.cmd("fill " + st.block(-2, -4, 3) + " " + st.block(2, -1, 7) + " minecraft:water");
		s.summon(st, "sheep", "fgd_wet_sheep", 0, -4.0, 5.0, 90.0F, "");
		s.pause(30).say("A sheep at the bottom of a deep pool, blown apart");
		s.client(mc -> {
			LivingEntity sheep = st.find(mc.level, EntityTypes.SHEEP, 0, -4.0, 5.0);
			if (sheep != null) {
				GoreSystems.dismemberment().vaporize(GoreSystems.blood(), mc.level, sheep, sheep.position().add(0.3, -0.2, 0.2));
			}
		});
		s.pause(60).say("Its blood clouds in the water. The lungs, full of air, and the fat come up and float").pause(160);
		s.say("Bone sinks to the bottom; meat and organs follow it down more slowly").pause(160);
	}

	/** 4.0: bodies lying together, side by side, smearing each other. From the game test {@code lying}. */
	private static void lying(Script s, Stage st) {
		double[][] at = {{-0.4, 3.0}, {0.4, 3.1}, {-0.5, 3.8}, {0.5, 3.9}, {0.0, 3.45}};
		for (int i = 0; i < at.length; i++) {
			s.summon(st, i == 4 ? "sheep" : "pig", "fgd_lie" + i, at[i][0], 0, at[i][1], i * 23 % 90, "");
		}

		// Living, they would shove each other apart before they died: vanilla pushes the living, not the dead.
		s.cmd("team add fgd_lie").cmd("team modify fgd_lie collisionRule never").cmd("team join fgd_lie @e[tag=" + Script.TAG + "]");
		s.pause(20).hold("minecraft:iron_sword").say("Four pigs and a sheep packed tight, as at a trough");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.PIG, at[0][0], 0, at[0][1]), BodyPart.HEAD));
		s.hit("fgd_lie0", 4.0F).pause(40);
		for (int i = 0; i < at.length; i++) {
			s.cmd("damage @e[tag=fgd_lie" + i + ",limit=1] 12 minecraft:generic");
		}

		s.hold("minecraft:air").say("They go down together, and come to rest side by side, against each other, not through each other").pause(160);
		s.say("The blood of the stuck one comes off on the flanks of the bodies against it").pause(120);
		s.say("With Ragdoll Physics on, they set with their legs locked straight out from the trunk").pause(120);
		s.cmd("team remove fgd_lie");
	}

	/** 4.0: blood on the animals' coats, and blood in water. From the game test {@code bloodyCoats}. */
	private static void coats(Script s, Stage st) {
		s.summon(st, "pig", "fgd_coat_pig", -0.6, 0, 3.0, 90.0F, "");
		s.summon(st, "sheep", "fgd_coat_sheep", 0.6, 0, 3.0, 90.0F, "");
		s.summon(st, "cow", "fgd_coat_cow", 2.1, 0, 3.8, 90.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("A pig stuck beside a sheep and a cow");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.PIG, -0.6, 0, 3.0), BodyPart.HEAD));
		s.hit("fgd_coat_pig", 4.0F).pause(40);
		s.say("Its blood spatters the coats of the sheep and the cow").pause(60);
		s.cmd("damage @e[tag=fgd_coat_pig,limit=1] 100 minecraft:generic");
		s.hold("minecraft:air").say("It bleeds out where it stands, and the pool runs under the sheep").pause(160);
		s.say("Blood climbs the wool of the sheep's legs, higher than it reaches on the pig's hide").pause(200);
		s.cmd("fill " + st.block(-2, -1, 6) + " " + st.block(2, -1, 8) + " minecraft:water");
		s.summon(st, "pig", "fgd_coat_wet", 0, -0.6, 7.0, 90.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("A pig stuck standing in a trough of water");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.PIG, 0, -0.6, 7.0), BodyPart.HEAD));
		s.hit("fgd_coat_wet", 4.0F).pause(20);
		s.cmd("damage @e[tag=fgd_coat_wet,limit=1] 100 minecraft:generic");
		s.hold("minecraft:air").say("Its blood clouds out in the water, sinks and thins away").pause(300);
	}

	/**
	 * 4.0: a body sinks in water, and floats once it bloats. From the game test {@code bodiesInWater}. Ten days are
	 * skipped (/time add, so this world's day count moves on); the float needs the Extreme gore level and Bodies Rot.
	 */
	private static void water(Script s, Stage st) {
		s.cmd("fill " + st.block(-2, -3, 3) + " " + st.block(2, -1, 7) + " minecraft:water");
		s.summon(st, "pig", "fgd_water_pig", 0, -1.2, 5.0, 90.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("A pig in a deep pool");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.PIG, 0, -1.2, 5.0), null));
		s.hit("fgd_water_pig", 4.0F).pause(20);
		s.cmd("damage @e[tag=fgd_water_pig,limit=1] 100 minecraft:generic");
		s.hold("minecraft:air").say("Dead, it sinks slowly to the bottom, its blood clouding round it").pause(300);
		s.say("Ten days later").cmd("time add 240000").pause(120);
		s.say("Bloated with the gas of its rot, it comes up and floats low in the water (Extreme, with Bodies Rot on)").pause(300);
	}

	/** 4.0: sticking, blood running down the skin, and a fresh bruise. From the game test {@code stuckPig}. */
	private static void stuckPig(Script s, Stage st) {
		s.summon(st, "pig", "fgd_stuck", 0, 0, 2.5, 160.0F, "");
		s.pause(20).hold("minecraft:iron_sword").say("A pig stuck: a blade to the head goes into the throat, low in the neck, just ahead of the breastbone");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.PIG, 0, 0, 2.5), BodyPart.HEAD));
		s.hit("fgd_stuck", 4.0F).pause(60);
		s.say("The blood pours from it down the chest and the fronts of both forelegs").pause(100);
		s.cmd("damage @e[tag=fgd_stuck,limit=1] 100 minecraft:generic");
		s.say("It bleeds out and goes down, and the blood runs on across the side it lies on").pause(180);
		s.summon(st, "pig", "fgd_bruised", 1.8, 0, 3.0, 90.0F, "");
		s.pause(20).hold("minecraft:air").say("A punch: a bruise, a red flush under the unbroken skin");
		s.client(mc -> aim(mc, st.find(mc.level, EntityTypes.PIG, 1.8, 0, 3.0), null));
		s.hit("fgd_bruised", 1.0F).pause(120);
	}

	/** Every loose piece not cut yet is cut once. */
	private static void cutAll(Minecraft mc) {
		for (GorePart p : new ArrayList<>(GoreSystems.dismemberment().parts().parts())) {
			if (!p.shattered) {
				GoreSystems.dismemberment().butchery().cut(GoreSystems.blood(), mc.level, p, mc.player.getViewVector(1.0F));
			}
		}
	}

	/** 3.2: people burning, on husks (they do not burn in the sun, so only the fire put to them burns them). */
	private static void melt(Script s, Stage st) {
		s.summon(st, "husk", "fgd_melt_left", -1.2, 0, 3.5, 180.0F, "");
		s.summon(st, "husk", "fgd_melt_right", 1.2, 0, 3.5, 160.0F, "");
		s.pause(20).say("Two husks: shaped like players, they burn just as a player does");
		s.cmd("execute as @e[tag=fgd_melt_left] run data merge entity @s {Fire:2000s}");
		s.cmd("execute as @e[tag=fgd_melt_right] run data merge entity @s {Fire:2000s}").pause(60);
		s.say("On fire: the hair and clothes singe off, the skin reddens and blisters").pause(100);
		s.say("The deeper the fire takes their health, the deeper the burns: the skin chars and splits");
		s.cmd("damage @e[tag=fgd_melt_left,limit=1] 9 minecraft:on_fire");
		s.cmd("damage @e[tag=fgd_melt_right,limit=1] 9 minecraft:on_fire").pause(120);
		s.say("Dead, the body burns on its own fat: the skin goes, the yellow fat melts and drips").pause(260);
		s.say("Then the muscle cooks and shrinks onto the bone; the ribs and the organs show, the jaw drops").pause(300);
		s.say("At the end a charred skeleton, going grey, then white").pause(240);
		s.hold("minecraft:stone_shovel").say("And the bones break up under blows, until only ash is left");
		for (int i = 0; i < 9; i++) {
			s.client(mc -> beatPyre(mc, EntityTypes.HUSK)).pause(8);
		}

		s.pause(60);
	}

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
		s.say("The sheep has burnt to the bone on its own fat: scorched brown and charred black, mottled, not white").pause(140);
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

	/** 3.3: how far bones burn is the fire under them, not the flesh gone: charred on their own fat, white only under a long fire. */
	private static void charred(Script s, Stage st) {
		s.summon(st, "sheep", "fgd_bones_own", -3.0, 0, 4.5, 90.0F, "Sheared:1b");
		s.summon(st, "sheep", "fgd_bones_campfire", 0.0, 0, 4.5, 90.0F, "Sheared:1b");
		s.summon(st, "sheep", "fgd_bones_lava", 3.0, 0, 4.5, 90.0F, "Sheared:1b");
		s.pause(20).hold("minecraft:iron_sword").say("Three shorn sheep, killed where they stand");
		s.hit("fgd_bones_own", 100.0F).hit("fgd_bones_campfire", 100.0F).hit("fgd_bones_lava", 100.0F).pause(60);
		s.say("One on the bare ground, one on a campfire, one in lava");
		s.cmd("setblock " + st.block(0.0, -1, 4.5) + " minecraft:campfire");
		s.cmd("setblock " + st.block(3.0, -1, 4.5) + " minecraft:lava").pause(20);
		s.hold("minecraft:flint_and_steel").say("Fire put to each");
		s.client(mc -> {
			for (Corpse corpse : GoreSystems.suffering().corpses()) {
				GoreSync.act(GoreAction.of(GoreAction.IGNITE, corpse.entity.getId(), 0L, mc.player.getEyePosition(), mc.player.getViewVector(1.0F), 0, 0.0F));
			}
		}).pause(300);
		s.say("In lava the flesh is gone fast, and the heat goes on into the bare bones: grey, then chalk white").pause(400);
		s.say("On its own fat the left one burns out on a charred skeleton, brown and black and mottled: what an ordinary fire leaves").pause(400);
		s.say("The campfire goes on under the middle one's bare bones: grey patches first, the thin bones ahead of the thick").pause(700);
		s.say("White, calcined bone means long, hot burning: lava, a pyre, a fire kept going under the bones").pause(200);
		Vec3 own = st.at(-3.0, 0, 4.5);
		Vec3 lava = st.at(3.0, 0, 4.5);
		s.hold("minecraft:stone_shovel").say("Broken up, charred bone crumbles to black char; calcined bone to pale dust");
		for (int i = 0; i < 12; i++) {
			s.client(mc -> beatAt(mc, own)).pause(8);
		}

		s.cmd("setblock " + st.block(3.0, -1, 4.5) + " minecraft:stone").pause(10);
		for (int i = 0; i < 12; i++) {
			s.client(mc -> beatAt(mc, lava)).pause(8);
		}

		s.cmd("setblock " + st.block(0.0, -1, 4.5) + " minecraft:stone").pause(60);
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

	/** A blunt blow, through GoreSync, on the burnt body lying within a block and a half of {@code near}. */
	private static void beatAt(Minecraft mc, Vec3 near) {
		for (Corpse corpse : GoreSystems.suffering().corpses()) {
			if (GoreSystems.dismemberment().vaporized(corpse.entity.getId()) || CorpseStrike.centre(corpse).distanceToSqr(near.x, CorpseStrike.centre(corpse).y, near.z) > 2.25) {
				continue;
			}

			Vec3 centre = CorpseStrike.centre(corpse);
			Vec3 eye = centre.add(mc.player.position().subtract(centre).multiply(1.0, 0.0, 1.0).normalize().scale(1.2)).add(0.0, 1.0, 0.0);
			lookAt(mc, centre);
			GoreSync.act(GoreAction.of(GoreAction.STRIKE, corpse.entity.getId(), 0L, eye, centre.subtract(eye).normalize(), Weapon.BLUNT.ordinal(), 5.0F));
			return;
		}
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
