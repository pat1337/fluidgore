# Changelog

All notable changes to Fluid Gore are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and version numbers roughly follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- A sheep's anatomy now reaches its skin. Its trunk holds the hide, the fat under it and its named muscles (the
  longissimus, psoas, gluteus medius, the shoulder and thigh muscles, the flank in its layers), and its legs are its
  own, from the elbow and the stifle to the hooves. A sheep blown apart throws these as pieces of their own: the hide
  in patches, the fat, the loin in chunks, the chump, the leg bones and hooves, the cheek muscles. Demo: `sheepblast`.
- Identify Remains names the new pieces.
- `/fluidgore clear` takes away the bodies and gore in an area so a scene can be played again: within 16 blocks, a
  radius you give, the blocks between two corners (as `/fill` takes them), or `all`. The server removes the bodies it
  keeps there, and every player with the mod loses the blood, loose pieces and bodies shown there. Living animals are
  left alone. It needs operator rights, so a command block can run it.

### Fixed
- Blood no longer hangs in the air over fences, fence gates and walls. It used to land on their collision box, which
  stands half a block above the rails, and lay there as a square sheet. Blood that lands on a rail, a wall top or a pane
  now leaves a thin line along it, and the rest runs off the edge and drips to the ground below. No pool forms on top
  of a fence. Loose scraps rest on the rails too, not above them.
- Ragdoll Physics: bodies no longer pass through fences or end up lying across them. A thin wall or a step's edge
  could slip between the points a body was tested at; now every edge of a body and of the block is tested as well.
- Ragdoll Physics: a body the server keeps no longer jumps about while it goes down stairs or steps. The body on
  screen used to be moved to the server's position directly, through the steps; it now slumps and falls after it.
- Ragdoll Physics: a four-legged body that tumbles down a long flight of steps no longer comes to rest on its back. It
  settles onto its side as one that falls on the flat does.
- Ragdoll Physics: a body killed crosswise in a passage one block wide (on stairs, say) no longer hangs in the air
  between the walls. Walls grip a body only as hard as it is pressed against them, not as if they bore its weight, and
  its joints give under its weight, so its head and legs no longer brace it against both walls. It drops onto the
  steps and comes down them.
- Ragdoll Physics: a leg caught where a wall meets the floor no longer sinks through the floor and flings the body
  away along the ground.
- Ragdoll Physics with the mod on the server: a body tumbling down a passage one block wide is no longer left behind
  on the server partway down. Everyone now finds it where it came to rest.

### Changed
- Ragdoll Physics: a body carries its weight as the animal does, not as its blocks are sized. Most of it is in the
  trunk, and the head and legs are light (a sheep's legs used to hold two fifths of its weight). A body that goes over
  an edge falls trunk first with its legs swinging loose, keeps its momentum, and tumbles on down a slope or a flight
  of steps instead of stopping on the first step below. Demo: `tumble`.
- Ragdoll Physics with the mod on the server: the nearest player with the mod leads each kept body's fall. Their
  game's ragdoll tumbles it, and the server moves the body to where it came to rest, so everyone finds it there. A
  kept body no longer slides down a slope at a slow, even pace behind the server's box.
- The Cobble Tank moved to a project of its own and is now `CobbleTank-<version>.jar` (1.1.0, mod id `cobbletank` as
  before). It no longer needs Fluid Gore; with both installed, its jets and bursts play Fluid Gore's gore as before.
  Fluid Gore's build no longer makes the tank's jar. Remove an old `cobbletank-1.0.0.jar` when installing it.
- A sheep's organs are fitted to its whole body rather than to the inside of its ribs, so they keep truer
  proportions. Its heart is cut at half pixels, small enough to be carried.
- A sheep blown apart makes fewer made-up flesh scraps, since it throws its own hide and muscle.

## 4.0.0 - 2026-11-05

### Added
- Explosions under water. A charge that goes off in water has no fireball: nothing is scorched, singed or seared.
  Its gas swells into a bubble, collapses and rebounds as it rises, and bursts out of the surface in white spray. The
  water's movement carries pieces, bodies and blood clouds out and back. Bodies inside the bubble are torn apart.
  Further off, the shock wave reaches bodies in the water much further than in air, and they bleed from the lungs.
  `/gore demo underwater` shows it.
- Sheep are drawn at a mixed grain: half pixels, with the heart, great vessels, windpipe, gullet and kidneys at
  quarter pixels in the same cavities. The organs around them are unchanged.
- A sheep's head has its real anatomy, generated from the same model as its body. It has the braincase, nasal
  bones, upper jaw, cheekbones and orbits; the jaw with its cheek teeth and the lower incisors biting on the dental pad;
  the eyes; the tongue; the larynx and pharynx; the nasal cavity; and the brain in three parts (cerebrum, cerebellum
  and brainstem). The brain, eyes and teeth are at quarter pixels.
- More of a sheep's insides: the pancreas in the bend of the duodenum, the adrenal glands at the front of the
  kidneys, the thymus ahead of the heart, the greater omentum (a lacy veil of fat over the guts on the right side), the
  ureters and urethra, the spinal cord in the spine, and the salivary glands and thyroid in the head. The glands share a
  new pale, lobulated gland texture. Each one can be carried and cut like any other organ.
- Ewes and rams. Half of all sheep are rams, fixed by each sheep's UUID, so every player sees the same one.
  - A ewe has the vulva, vagina, cervix, a uterus with its two curled horns, the ovaries and oviducts, and an udder
    with two teats.
  - A ram has the testes in the scrotum, each with its epididymis, plus the spermatic cords, vasa deferentia,
    vesicular glands, and the penis with its sigmoid flexure and sheath. The testes have their own texture.
- Lambs (baby sheep) have their own anatomy: milk teeth (narrow milk incisors and three milk premolars a side), the
  small rumen and big abomasum of a suckling lamb, a large thymus that runs up the neck, and an immature reproductive
  tract (a ewe lamb's or a ram lamb's).
- Cut faces for more organs. A blade through a heart shows its ventricles and septum, and through a kidney its
  cortex, medulla and pelvis.
- A blade splits a severed sheep's head down the middle. Each half falls open, cut face up, showing the skull and
  jaw open to their marrow, the brain's grey and white matter, the tongue, the larynx and the throat where they lie. Halves
  are saved like other pieces. `/gore demo headsplit` shows it.
- Sticking. A sword or a flint to the head of a pig, cow, sheep or other four-legged animal goes into its throat: a
  short cut along the underside of the neck, just ahead of the breastbone. The animal bleeds out through it. A second
  blade blow through a throat cut that deep can still take the head off. `/gore demo stuckpig` shows it.
- Blood runs down the skin. Blood from an open wound runs down the skin below it, clinging to the chest and the fronts
  of the legs, and dries where it ran. A stuck pig pours down its chest and both forelegs. Once a body lies down, what
  still bleeds runs across the side it lies on. Runs are kept with a body's wounds and with severed pieces. The
  **Blood Runs on Bodies** setting (on by default) turns them off.
- Pallor. Skin goes pale as a body loses blood: a bled-out pig lies a washed-out, pale pink beside one that never bled.
  It shows most on pigs and shorn sheep; a coat hides most of it and wool keeps its colour. The colour comes back as
  an animal heals. The **Pallor** setting (on by default) turns it off. `/gore demo pallor` shows it.
- `/gore demo resolution`: four shorn ewes opened side by side, with their insides cut at four grains: 1x (a cube per
  pixel), 2x (half pixels, the default), 4x (quarter pixels), and mixed (2x with the heart, great vessels and kidneys
  at 4x). Each ewe has a name tag. The bodies are opened raw, with no scorching or cooking, so the anatomy can be
  compared by eye.
- Blood on the animals, not only the ground. Every animal that bleeds carries blood on its coat, part by part (head,
  each leg, body):
  - A blow spatters the coats of the animals standing near it, on the side that faced it. Blood that flies low lands
    on their legs, high on their heads. A body blown apart spatters everything round it.
  - Standing or walking in a pool reddens the feet and lower legs; walking through it splashes higher. Lying in a pool
    soaks the whole body, more while it still thrashes.
  - A sheep's fleece drinks blood. It takes much more than hide, the pool under it shrinks as it does, and the red
    climbs the wool of the legs above where it touched. Blood on the legs stays on the legs; only blood on the body
    reddens the whole fleece.
  - Blood on a coat dries darker with time. Water rinses it off hide quickly, and wool slowly. Rain washes it out
    more slowly still.
  - A leg or head cut off keeps the blood that was on it.
  - The **Bloody Coats** setting (on by default) turns it off for hide; **Bloody Wool** and **Bloody Feathers** still
    cover wool and feathers. `/gore demo coats` shows it.
- Blood in water. Blood that reaches water clouds out in it as a dark red plume, sinks slowly to the bottom, drifts
  with the current and thins away over half a minute to three minutes, the more blood the longer. A bleeding body in
  water feeds one growing cloud. A pool that water floods lifts off the floor in a cloud, and a bloody sponge or brush
  rinsed in water leaves one too. The **Blood in Water** setting (on by default) turns it off.
- Bodies in water. A body that dies in water sinks slowly to the bottom instead of dropping through it. With Rot on, it
  comes up and floats low in the water once the bloat fills it with gas, and goes down again when it splits. A current
  carries it along. A body the server keeps does the same, so every player sees it in the same place.
  `/gore demo water` shows it.
- Bodies lie side by side. Animals killed together come to rest against each other instead of lying through each
  other. With Fluid Gore on the server, the server spreads the bodies it keeps, so every player sees them lie the same
  way. Bodies lying against each other smear each other: the blood on one comes off on the flank of the next.
  `/gore demo lying` shows it.
- With Ragdoll Physics on, a four-legged animal that dies on its side sets with its legs locked straight out from the
  trunk, instead of lying with them folded under it. One left propped on its legs or against another body settles
  over onto its side.
- Blasts reach the dead. A body already lying dead is shredded, torn open or thrown limp by an explosion or a burst
  pressure vessel by the overpressure where it lies, as a living one is, and a vessel's fragments go into it. Before,
  it was only knocked about. `/gore demo deadblast` shows it.
- Pieces in water. Severed pieces and scraps sink or float by what they are made of: lungs and fat float, bone sinks
  to the bottom, and meat and organs sink more slowly. The current carries them. `/gore demo wetpieces` shows it.

### Changed
- An axe on a downed body takes a leg or the head off with the chop that reaches the bone, on Medium and above,
  instead of the chop after it and only with luck. A sword still needs a cut already to the bone, and luck.
- Bruises are a soft red flush with a dithered edge, in place of the dark purple blot with a spiky rim. They darken as
  the blood in them goes stale, and a new blow on a bruise reddens it again.
- Compatibility: bodies and pieces saved or sent by an older version still load, their wounds as old ones with no runs
  of blood. A game on an older version does not read this version's: it shows such a body unwounded and does not show
  such a piece at all.
- Blood on the ground behaves like fresh blood. It lands bright scarlet, darkens to the deep red of standing blood, then
  dries brown-black. It stops spreading while still thick, so a large bleed lies as one pool of a few blocks with a
  ragged edge instead of a wide field of small dots, and neighbouring puddles run together. Fresh blood poured into an
  old pool makes it look fresh again.
- The Cobble Tank is now a separate add-on, `cobbletank-<version>.jar` (mod id `cobbletank`), built from the same
  repository. Fluid Gore on its own registers no blocks, items or entities. The tank's physics and gore are still
  Fluid Gore's: the blast and jet physics, fragment tracing, and the wounds, shredding and deafness a burst causes.
  With both installed, the tank works as before.
- The tank's ids moved to the add-on's namespace: `cobbletank:cobble_tank`, and the damage types `cobbletank:jet`,
  `cobbletank:embolism` and `cobbletank:tank`. **A tank already placed in a world disappears when the world loads
  with this version.**
- The tank's settings moved to `config/cobbletank.json`. Values set in an older `config/fluidgore.json` are copied
  over once. They still appear in the Fluid Gore settings screen under Cobble Tank (Server) when the add-on is
  installed. Its demos (`tank`, `roadster`, `fragments`) are listed only with the add-on.
- With Fluid Gore on the server, the server now counts each kept body's rot and sends it to players. The count is
  saved with the world and updated every five seconds and whenever a player starts seeing the body, so everyone sees
  the same rot history, through unloads and relogs. Time a body spent unloaded is counted where it lies, since it
  cannot move while unloaded. A body kept by an older version counts its whole age where it lies the first time.
- Compatibility: a server and a client on different versions no longer share rot; the client then counts on its own.
- The settings screen is regrouped into sections: Who Bleeds, Blood, Wounds and Dismemberment, Player Injuries,
  Animal Suffering, Bodies, Fire, Cobble Tank (Server), Performance, Experimental and Extras. The Cobble Tank and 2x2
  vessel settings no longer sit under Player Injuries, and the ragdoll options are under Experimental.
- Every option now shows its description when you hover over it; before, about half of the on/off buttons had none.
- Clearer slider names and units: Tank Heat-Up Time, Tank Fragment Paths, and Tank Fragment Range in blocks.

### Removed
- The Breaching Frame. The Cobble Tank stays. A frame already placed in a world disappears when the world loads
  with this version.

### Fixed
- A hurt player who switches to creative or spectator no longer keeps the blood on the screen, the heartbeat and
  their bleeding wounds. Switching does not heal, so these had stayed at the health they switched at.
- A living animal no longer loses its head to a blow that does not kill it. A head comes off only with the killing
  blow; a heavy blow that leaves the animal alive can still take a leg.
- A dead skeleton, zombie or player no longer leaves what it held hanging in the air, jumping back and forth, as
  its body falls. It lets go of it. A hand that is cut off takes its item with it.
- Blood that landed on ground a blast then blew away no longer hangs over the crater. The part over the hole goes,
  and fresh blood runs off the new edge and falls.
- Blood clouds in water are soft puffs. They used to be drawn with the hard-edged drop shape, as big dark discs that
  looked black at night.
- Blood let out under water (a body torn open by a blast in a pool) clouds out where it leaves the body. It no longer
  flies through the water and paints the bottom and walls.
- A charge under water no longer throws flame and smoke, scorches the ground or sears the pieces in it.
- Blood clouds in water no longer show above the surface as grey half-discs.
- Pieces from a blast in water no longer lie on the water's surface.
- A body that popped when it went could burst into different pieces on different screens, because its dice depended
  on what each player had done near it (walking over it, flies). The pop is now settled when the body falls.
- Every animal that died on screen kept a little memory until you changed dimension, so long sessions at a farm grew
  slowly heavier. It is now freed when the body goes or is cleaned away.
- A hand-edited config with an empty entry in a list (such as `[null]` in the suffering animals) crashed the server on
  the first death. Empty entries are now dropped.
- Rot could run backwards. It was worked out every tick from the body's whole age at the temperature of where it lay
  now, so a carcass carried into snow or water got fresher, and one could bloat and burst twice. Rot now only adds up,
  each stretch at the pace of where the body lay through it. Whether a body mummifies is settled once it starts to
  bloat: a bloating body carried into a desert still bursts, and a mummy carried into the rain stays a mummy. Severed
  pieces follow the same rule.
- With a shader pack, cut pieces (split heads, joints, diced meat) could look hollow: their far walls showed through
  the near ones. Whole pieces are now drawn solid, and only see-through while they fade away.
- The flames on a burning body were a sheet two or more blocks tall, standing beside small bodies. They are now low
  flames over the body, as tall as the fire on it.
- Burning bodies and breaking bones gave off large dark star-shaped flakes. The smoke, soot and bone dust are now fine
  wisps and specks.
- With Ragdoll Physics on, a dead animal's head could fold right back against its shoulder and sink into it. The neck
  now bends about half as far.
- With Ragdoll Physics on, bodies that fell on each other passed through each other. They now lie against and on top
  of each other.
- Blood that landed at a slant, and blood dragged along the ground, left marks shaped like pins or arrows. They are
  now drops drawn out the way they flew, smaller towards the end.
- Exposed muscle in a pig's wounds looked grey. Open wounds now show the muscle wet with blood.

### Security
- A loose piece's pose sent by another player is refused if its rotation is not a proper rotation, or if it spins
  about no axis. Before, a modified client could make a piece vanish or grow on everyone else's screen.
- The server checks how a kept body is cleaned away. Cleaning by hand needs a cleaning tool in the main hand at normal
  reach, and only that stains the tool. The removal that follows butchering a body apart is a separate action that
  never stains the held item. An older client's automatic removal is refused instead of being taken as a hand clean.

## 3.6.0 - 2026-10-22

### Added
- Breaching Frame: an iron frame, two blocks high, that you mount against a wall or a closed door. It has an ordinary
  crafting recipe and vanilla textures, with red lights that show when it is armed. Demo: `/gore demo breach`.
- Arm it with redstone or an igniter. After a three-second fuse (saved with the world) it removes a fixed section one
  block wide, two high and one deep, and throws debris through the opening. Break either half to cancel it and get
  the frame back.
- The opening only forms in supported blocks on the demolition whitelist; closed wooden and iron doors work.
  Containers, fluids, unbreakable blocks and anything not on the list are left alone, with no partial hole. The frame
  and the removed blocks are used up.
- Debris follows the paths the server traces (see 3.5.0): the first body or solid block stops each piece, and each
  victim takes all of its debris damage at once. There is no blast radius, fireball or flying organs.
- Server settings for fuse length, debris count and debris range. The `tnt_explodes` game rule decides whether a frame
  can be armed, and turning it off disarms a running fuse.

### Changed
- A debris hit leaves its own wounds instead of an extra generic one; player injury effects and animal death throes
  still play.
- Compatibility: the frame is a registered block, so the server and every client need 3.6.0.

## 3.5.0 - 2026-10-22

### Added
- Traced fragments: when a Cobble Tank bursts, the server follows each casing fragment to the first body or solid
  block in its way, instead of rolling hits per body. The same paths drive the wounds and the flying chips that every
  player sees.
- Short-lived carbon and metal chips, with vanilla textures, fly along those paths. Demo: `/gore demo fragments`
  (cows in the open beside cows behind stone, with a deliberately weak tank).
- Server settings for the fragment budget and the tank's fragment range. A burst traces at most 256 fragments
  against at most 128 bodies, and a trace stops at an unloaded chunk instead of loading terrain.

### Changed
- Blast pressure and fragments reach each body as one damage event on the server. Fragment effects on clients never
  deal damage.
- The default fragment range is 48 blocks, replacing the old statistical spread of 300. The fragment budget changes
  gameplay, so it is a server setting, separate from Gore Level.
- Known limit: the server tests fragments against hitboxes and clients place the wound on the model, so a fragment
  that passes through a gap in the model leaves no surface wound.
- Compatibility: the server and every client need 3.5.0.

## 3.4.0 - 2026-10-21

### Added
- A new look for the Cobble Tank, from vanilla textures: a dark fibre casing, metal bands and a copper fitting.
- Valves: sneak and use a stick on a tank to open a valve on the face you clicked. The jet pushes bodies and loose
  organs, and the recoil can tear the tank loose. Use a tank with an empty hand to read its pressure, shell
  temperature and damage.
- Demo: `/gore demo roadster`, for the crafted tank: a gas jet lifts a cow's organs, then a tank heated by fire
  ruptures. (The 2x2 vessel's demo of the same name was removed in 3.3.0.)
- Settings for how fast tanks heat up and how much of the stored energy goes into the blast wave. A tank keeps the
  value it was placed with.

### Changed
- Gas escapes at a rate set by the size of the opening and the pressure left, instead of a fixed decay. Gas and
  casing heat up separately: a long fire raises the pressure and permanently weakens the casing. A brief flame no
  longer starts an unstoppable countdown; the crafted block's redstone or igniter countdown is unchanged.
- By default 35% of the stored energy goes into the blast wave (a gameplay choice). The server sends the blast
  strength to every client, so local settings do not change what others see.
- Solid blocks stop a valve jet, and jets now move loose organs and small scraps too. An empty tank cannot rupture,
  and venting first leaves less for the blast and the fragments.
- A nearby explosion damages a tank by the pressure where it stands, instead of setting off every tank around it.
- The cold gas cloud is a condensation fog. It no longer coats the ground in snow or freezes every organ it touches.
- The tank's figures (600 litres at 10,000 psi) are a game preset, not a real vessel. `docs/TANK_OVERHAUL.md` lists
  the assumptions and limits.
- Compatibility: the server and every client need 3.4.0.

### Fixed
- Sneaking no longer skips the valve. A lit tank keeps its countdown in flight and after it lands as a block again.
- A flying tank shows its own pressure, volume and temperature instead of each client's default settings.
- Gas pressure no longer adds an extra slash across a body after knockback; the organ effects and casing wounds
  already show the injury.

### Removed
- The random instant death from a gas embolism when standing right against a jet. Close contact now does steady
  damage and slows you.

## 3.3.1 - 2026-10-21

### Added
- Cows have their own internal anatomy, separate from sheep: bovine lungs, lobed kidneys, the liver with its
  gallbladder, four stomach compartments and the spiral colon. Demo: `/gore demo coworgans` (cow and sheep anatomy
  side by side, then the cow's liver, lungs and kidneys).

### Changed
- A cow's lung lobes and paired organs are assigned to the correct side, including the right lung's accessory lobe
  and the left kidney, which the rumen pushes out of place. Large loose organs use a simpler shape to stay within the limits
  for carried and cut pieces; small tissues such as the gallbladder keep their detail. Inside the body the full detail
  is used.
- Cow heads and legs still use the anatomy shared by all hoofed animals.

### Fixed
- A loose organ's collision box fits the shape that came out, so coarse cow organs no longer float above the ground
  after landing.

## 3.3.0 - 2026-10-20

### Added
- Burning Animals (on by default): sheep, pigs and cows burn down through their own anatomy, as players do. Each has
  a hoofed animal's skeleton fitted to its model (spine, ribs down to the breastbone, shoulder blades, pelvis, a long
  skull with the eyes on the sides, legs down to the cloven hooves), lungs and heart, and behind the diaphragm a
  sheep's or cow's four stomachs or a pig's single stomach and spiral colon, with the kidneys wrapped in fat.
- Sheep and cow insides follow a ewe's anatomy instead of placeholder boxes: the liver on the right with its
  gallbladder, the rumen filling the left of the belly with the reticulum in front, the omasum and abomasum on the
  right, the spleen on the left, the heart low between the lungs. Every cube belongs to an organ, so a blast throws
  out a whole liver or heart rather than scraps. Blockbench models of each organ are in `art/`.
- Organs that come out keep their shape and can be handled like a severed limb: pick them up, carry and throw them,
  punch them, stamp on them or cut them. A blade cuts an organ into portions that keep their shape and show the cut
  surface, until they are too small to cut. Loose organs keep their own blood, cook and char in a fire, go out in
  water, are thrown by a later blast, and look the same to every player. Demo: `/gore demo organs`.
- Each animal burns as its own meat: a sheep's hard white suet over dark mutton, a pig's soft lard over pale pork, a
  cow's yellow fat over red beef. Fat renders away before the organs it wraps.
- A sheep's fleece hardly burns: it smoulders under white smoke, scorches, chars and falls away in patches, and the
  skin under it burns later than a pig's bare hide. The face and lower legs, which have no fleece, go first.
- Hooves and horns char black but stay. Pig snouts, cow muzzles and udders burn as flesh; the shaggy coats of
  cold-climate cows and pigs singe off first.
- Mutton: sheep wounds, cut faces and scraps are dark red lean under a thick rim of white fat, with little marbling.
- Lamb butchery: a split sheep carcass comes apart into a butcher's joints (neck, shoulder, rack, loin, the chump end
  with the leg, breast and flank). Cut again, each joint splits down the spine and then into chops.
  Demo: `/gore demo lamb`.
- Heat rupture: as a body burns, the belly wall dries and splits and the bowel pushes out, then chars and shrinks.
- Burns made after death do not redden: a body set alight once dead, or a walking corpse, browns and chars without
  the red flush of a burn made alive.
- Pork rind puffs up into golden crackling before it chars.
- Explosions are graded through the anatomy. Each explosion is sized as a charge: a block of TNT counts as about
  8 kg of TNT, which kills a cow out to vanilla's 6 blocks; a creeper is smaller and a charged creeper much bigger;
  the Cobble Tank keeps its own size. Each body takes the blast pressure where it stands instead of vanilla's
  straight-line falloff, and each bone and organ takes the pressure where it lies, so the side facing the charge
  comes apart first:
  - Close to a block of TNT (out to about 2.6 m) the body breaks into its own bones and organs at true size, thrown
    and tumbling, with scraps of muscle, fat and hide; a rumen bursts in a spray of green cud.
  - Out to about 3.6 m the limbs facing the blast are torn off and the belly wall facing it bursts.
  - Further out the body dies whole, bleeding from the mouth and nose; out to about 11 m eardrums burst.

  Demo: `/gore demo blast`.
- Identify Remains (on by default): look at a piece of a body and its name shows above the hotbar: whose it was,
  which organ or bone, which side, and whether it is seared or charred ("Cow · right lung", "Sheep · left femur,
  charred"). Severed limbs, carcasses and lamb joints are named too ("cut from Sheep · rack").
- Demos: `/gore demo beasts`, `/gore demo charred`.

### Changed
- The 2x2 TNT pressure vessel is now experimental and off by default ("2x2 TNT Vessel (Experimental)" in the
  settings); the crafted Cobble Tank is the pressure vessel to use. The 2x2 vessel's `roadster` demo was removed.

### Fixed
- Blasts now sear (a charge's fireball) or frost (a gas burst) what they reach, and throw pieces already lying on the
  ground. Both effects existed but had never been switched on. The same burst set up the same way now gives the
  same result.
- A burning body's flames slid off it as it rolled onto its side and jumped in size as the server relit it. The
  flames, particles, the heat a body lies in and the box you aim at on a fallen body now use the body's middle
  instead of its feet. With Ragdoll Physics on, the flames follow the ragdoll.
- Burnt bodies always ended as white skeletons. How much flesh has burnt off and how burnt the bones are now count
  separately: a body burning on its own fat ends as a dark, mottled skeleton, and bones go grey and then chalk white
  only in a fire that keeps burning under them once the flesh is gone (slowly on a campfire, fast in lava). Each bone
  burns as far as its own exposure takes it, the same on every screen. Charred remains break into black char and
  soot; only calcined bone breaks into pale crumbs. Players who arrive later see the same bones.
- A body killed by a blast too weak to tear it apart could still lose a random limb.
- Every animal blown open spilled cud. Only cud-chewers (cattle, sheep, goats, camels, llamas) do now; the rest
  spill gut.
- The liver and the heart are no longer called "right" or "left".

## 3.2.0 - 2026-10-20

### Added
- Anatomy for players, built in blocks at the skin's own pixel size: fat, muscle (chest, abs, back, face), a skeleton
  (skull with eye sockets, teeth and a jaw, spine, ribs, pelvis, the long bones, hands and feet) and the organs in
  place (windpipe, lungs, heart, liver, stomach, spleen, kidneys, intestines, bladder). Slim-armed players and other
  mobs shaped like people get it fitted to their proportions.
- Burning People: a player on fire burns down through it. Hair and clothes singe off; the skin reddens, blisters,
  peels, chars and cracks, then burns through to fat that melts and drips, then to muscle that cooks and shrinks onto
  the bone. The eyes and teeth show where there is no muscle. Thick black smoke rises off them. How deep it goes
  depends on the gore level and on how close to death the fire has brought them, and it heals as health returns.
- A player who dies on fire or in lava leaves a body where they fell. It burns on its own fat until it is a charred
  skeleton, works with Burning Bodies (water puts it out, blows break the bones up) and stays after the respawn.
- Burning Mobs Too (off by default): zombies, piglins and other mobs shaped like people burn the same way.
- Burning Bodies Spread Fire (on by default; needs Fluid Gore on the server, whose own setting decides): an animal
  running on fire lights the grass, hay, wool, leaves and wood it brushes past, and a body that died burning burns on
  where it lies (about a minute for a pig, nearly two for a cow), lighting what it touches. It is vanilla fire:
  players without the mod see it, water and rain put it out, and the `fire_spread_radius_around_player` game rule
  decides where it may spread (spectators do not count as players for it).
- Smoke rises off a burning body in a plume, about a third as much as before, so you can watch it char; a burning
  person's hair smokes white at first, with a faint crackle.
- Demos: `/gore demo melt`, `/gore demo burn`.

### Fixed
- A body whose ground stopped being loaded (its player went far away or respawned far off) fell through the world.
  It now waits where it lies until the ground loads again.
- Dead bodies slump against walls instead of falling through them.

## 3.1.0 - 2026-10-19

### Added
- Bloody sponges and brushes: a cleaning tool soaks up the blood it wipes, and the item shows the stain in three
  stages, in the blood's own colour (red, ender violet, rotten). The bloodier it is, the less it lifts and the more it
  smears along the stroke. Soaked through, it leaves red wipes even on a clean floor. Cleaning a whole body away
  soaks it too.
- Guts on the floor are squashed into dark streaks when a tool is dragged over them.
- Washing: use a bloody tool on water, on a water cauldron, or while standing in water. Each dunk rinses some of the
  blood out in a red cloud. A sponge washed clean comes out a wet sponge.
- With Fluid Gore on the server the stain is stored on the item, so everyone sees it and it stays with the item.
  Without it, your game tracks the stain for you alone and the item itself does not change.
- A soaked brush still brushes suspicious sand and gravel as usual.
- Demo: `/gore demo sponge`.

## 3.0.0 - 2026-10-19

### Added
- Server-reported hits: with Fluid Gore on the server (singleplayer and LAN or e4mc worlds included), the server
  tells every player who can see a body what hit it: the damage, the weapon, the path of the blow (a player's exact
  view, an arrow's flight) and the dice for it. Without the mod on the server, each game works it out from what
  vanilla sends, as before.
- Arrows with no shooter (dispensers, `/summon`) wound where they strike. They used to leave no wound at all.
- Demo: `/gore demo arrows`.

### Changed
- Compatibility: the server and every player must run the same Fluid Gore version (since 2.16.0 the Cobble Tank
  required this anyway).

### Fixed
- With Fluid Gore on the server, a shield block no longer draws blood.
- With Fluid Gore on the server, a hit soaked up by absorption hearts or armour no longer shows a guessed small bleed
  four ticks later.
- With Fluid Gore on the server, arrows from a skeleton too far away for your game to know about still wound and
  bleed correctly.

## 2.17.0 - 2026-10-19

### Added
- Directional Spatter (on by default): blood that lands at an angle stretches into a teardrop, longer the shallower
  it came in, with its tail pointing the way it flew. A burst on the ground leaves a ring of them pointing outwards.
- Drag Trails (on by default): a bleeding body that slides, tumbles or is shoved over the ground leaves a smear
  behind it for the first minute after death.
- A fall of more than about three blocks bursts the body on landing, with blood driven out low all round.

### Changed
- With Ragdoll Physics on:
  - Bodies end on their sides. A pig or sheep no longer stays on its belly with its legs folded, and a four-legged
    body that lands on its back rolls onto the nearer side (people still lie on their backs).
  - Bodies tip over their feet and roll over what they lie on instead of skating across the floor, and twitches on
    the ground no longer lift the carcass and walk it away.
  - On stairs and ledges, a body only your game has slides down to the bottom, a body the server keeps follows as
    soon as the server moves it down a step, and a body on a lip slumps over it instead of being flung off a cliff.
  - Each step down a flight of stairs thuds and knocks a little blood out, as with the old ragdoll.

### Fixed
- A player who burnt to nothing in lava, or was blown or cut apart, came back invisible after respawning.

## 2.16.0 - 2026-10-19

### Added
- Cobble Tank: a pressure tank crafted from TNT with cobblestone on its four sides. By default it holds 600 litres of
  air at 10,000 psi; both can be changed for new tanks. It is placed like a piston, its top facing away from the block
  you put it on.
- Light it like TNT (flint and steel, fire charge, burning arrow, fire or lava beside it, redstone): the casing creaks
  and pops, then it bursts. Bodies are hurt by the blast pressure where they stand: nothing is left within 2 m, bodies
  die out to about 8 m, and eardrums burst out to about 15 m. Casing fragments fly a few hundred metres. Glass breaks
  out to about 55 m and wooden doors, trapdoors and gates are blown open out to about 28 m (Tank Breaks Glass). There
  is no crater and no fire, since it is cold gas: a white fog, then debris clattering down. Explosions burst it, and
  it bursts other tanks near it.
- It cannot be mined while it holds pressure; hit it instead. A fist thuds, wood and stone scuff it, iron cuts the
  fibres, and diamond and netherite get through in a few blows, with grinding, cobble crumbs and sparks. The
  scratches, chips and gouges stay on the tank.
- A blow through the casing makes a leak where it struck. A pinhole hisses and frosts for minutes; a cracked fitting
  screams, vents in 15 to 30 seconds and pushes back the way the blow came. Struck from the side, the tank tears loose
  and flies once the push beats friction; struck from above, it is pressed into the ground; braced against a wall, it
  stays. In flight it tumbles faster and faster, smashes through glass, leaves and dirt, and hits what is in its way.
  Once vented, it lands and stands as a block again.
- Standing in the jet: air is forced under the skin within 30 cm (swelling, and now and then a fatal air embolism),
  you are sandblasted out to 1 m, knocked down or thrown out to 3 m, and caught in a gale out to 5 m. The tank and
  the crack get cold enough that touching them freezes you, and frost and ice form at the crack, though the jet itself
  is only a cold wind. Staying near a leak costs you your hearing over time. Hitting a leaking tank again, or letting
  the crack run, can still burst it, sized to the pressure left.
- Sneak and use an undamaged tank with an empty hand to pick it up again.
- Demo: `/gore demo tank`.
- Ragdoll Physics (off by default): dead bodies go limp. The head, trunk and each leg move on their own, each joint
  within its natural range. The body falls the way the blow sent it, folds, drapes over edges and comes to rest under
  its own weight without passing through blocks, then stops simulating. It stays whole, as on the vanilla model.
  Zombies, skeletons and other players get one too. Ragdolls Bend at the Knee (off) adds knees, hocks and elbows;
  Show Ragdoll Segments draws the physics bodies.

### Changed
- Compatibility: the Cobble Tank is a registered block, so Fluid Gore must be on the server and on every player who
  joins (singleplayer just works). Unlike the rest of the mod's gore, the tank deals real damage.

## 2.15.0 - 2026-10-19

### Added
- Pressure Vessel (on by default): light a 2x2 square of TNT (flat or standing, with flint and steel or redstone) and
  it bursts as a high-pressure gas vessel instead. By default it holds 200 litres of gas at 10,000 psi; both can be
  changed.
- Bodies are hurt by the blast pressure where they stood, worked out from the gas's stored energy and less what walls
  take out of it: nothing recognisable is left within about 2 m, limbs are torn off out to about 3 m, lungs torn out
  to about 4.5 m, and bodies cough blood with burst eardrums out to about 10 m, with ringing ears beyond. Casing
  fragments wound whatever they hit, more often the closer it was.
- It happens to you too. A line in chat tells you how far you were and what the blast did. Your view is thrown about;
  with burst eardrums, blood runs down both sides of the screen, every sound goes muffled under a high ringing, and
  your hearing comes back over half a minute or more (Blast Deafness, can be turned off).
- The vessel only changes what you see: who lives and dies is still decided by vanilla's TNT, and the two agree
  closely (four TNT kill a player out to about 4.4 blocks).
- Demo: `/gore demo roadster`.

## 2.14.0 - 2026-10-19

### Added
- Burning Bodies (on by default): set a dead animal alight where it lies (flint and steel or a fire charge on it, or
  lay it in fire, on a lit campfire or in lava) and it burns like a carcass on an open fire. Wool, hair or feathers
  flash up in white smoke and the hide sears black. Once the fat catches, it burns on its own: thick black smoke, fat
  dripping and spitting, the skin splitting over glowing cracks. The heat draws the limbs in, the flesh burns away,
  and the body shrinks to a skeleton with its ribs showing, the bones going black, then grey, then white, over grey
  ash.
- Burning time goes by weight: a chicken is bones in about 16 seconds, a sheep burns for most of a minute, and a cow
  for nearly two under a tall column of smoke. Burning Speed sets the pace.
- Put out early (water, or rain on a body burning on its own), a body stays half-burnt. Hitting it knocks the char
  off in black crumbs and soot, with sparks if it is still burning. Hitting the bare bones breaks them (the legs
  first, then the skull, last the ribs and spine) until the skeleton falls in on itself and is gone: that is how you
  clear a burnt body away. With the mod on the server it is gone for everyone.
- Bones and char do not slow you down the way a carcass does; you crunch through them. Burnt bodies do not rot.
- With the mod on the server, lighting a body is shared with the other players, and players who come by later see
  what is left of it.
- Demo: `/gore demo cremation`.

## 2.13.1 - 2026-10-17

### Changed
- Wading Through Bodies (35% by default): each body you walk through slows you and squelches, and a pile of them is
  hard going, while you shove them along.
- An axe clears a carcass in two chops: the first goes in to the bone, the second through the backbone, and the body
  splits apart and is gone. Other blades still take a few.
- Butchering is wetter: every chop into a carcass squelches, and splitting it spills the guts in a gush of blood that
  pools where it lay. Sheep throw clumps of matted, bloody wool with every cut.

### Fixed
- A kept body stopped blocks being placed where it lay, even after it was knocked away. Bodies never block building
  now: build on one and it is shoved aside (on top only if there is no room to the side). Loose pieces in the way are
  pushed out too.
- Cut-up pieces could be missing when you came back to them: the server sent them before the ground under them had
  loaded. They now wait for the ground.
- Punching or kicking a kept body only moved it on your own screen. The server moves it now, for every player, and it
  stays there. Walking into a body shoves it along too.

## 2.13.0 - 2026-10-16

### Added
- Chicken Deaths (on by default): a chicken dies in a frenzy of wing-beats that bounces it along the ground and spins
  it round, shedding feathers, in bursts that fade. What killed it shapes it: bare hands or a club wring the neck (the
  head hangs loose and swings with every beat), a cut throat lies bleeding a moment before the flapping starts, and a
  chicken without its head flaps hardest and longest, the open neck throwing blood. A blow on a dying bird sets its
  wings going again.
- Feathers: blows knock feathers out of a bird (a burst from a club or a bite, a few from a blade), wet with the blood
  of the wound under them. Plucking, the death flapping and blasts throw them too. A dry feather rocks as it drifts
  down; a soaked one clumps, goes dark and drops. They lie where they land and soak up blood or rain, and dry ones
  scatter when you walk through them. Max Feathers caps them.
- Bloody Feathers: a chicken's plumage soaks up blood like a sheep's fleece, matted round each wound and reddening as
  it bleeds, and chickens beside one being cut up get spattered. In rain or water the feathers go dark and slick and
  the bird looks half its size until it dries. A plucked chicken shows how skinny it is: a narrow body and bony wings.
- Bodies Slide Down (on by default): a body that falls across stairs, a ledge or the lip of a pit no longer hangs
  there like a box. With more than a fifth of it over the drop, it slumps over the edge, drops onto the next step with
  a soft thud and a jolt of its limbs, and goes on down until it lies flat, smearing blood as it drags. The server
  moves kept bodies, so every player sees them where they end up, and they are saved there.
- Demos: `/gore demo chickens`, `/gore demo stairs`.

### Changed
- Plucking throws feathers instead of item crumbs.

## 2.12.1 - 2026-10-16

### Fixed
- Kept bodies counted against the mob cap, so a field of slaughtered animals stopped new ones from spawning. They are
  left out of the count now (this works alongside Lithium, which changes the same code).

### Security
- A client could clean away any dead creature, not only bodies the server keeps: the Ender Dragon during its death
  (no exit portal, no egg), the Wither, other mods' bosses, even a dead player waiting to respawn. Only kept bodies
  can be cleaned now; everything else dies the vanilla way.
- A client could store gore records on any creature near it (a villager, someone's pet), saved in the world for good.
  Now only creatures that player hurt in the last five seconds, and kept bodies, take a record.
- Packets with not-a-number positions slipped past every distance check, so a client could delete any stored piece.
  Actions and piece moves with invalid numbers are refused, on the server and on the client.
- There was no limit on how much a client could send. Actions, piece moves and gore record uploads now have
  per-player rate limits, far above what the game itself sends.
- A loose piece sent by the server is checked as it is read (valid numbers, four-cornered faces, a sensible model
  size), so a broken one is dropped instead of crashing the renderer. Pieces that never get a position are forgotten
  after 30 seconds, on the server and on the client.

## 2.12.0 - 2026-10-16

### Added
- Bodies Rot (Extreme gore level): a body left lying rots, one Minecraft day of rot for each day. Fresh: pale, stiff
  with rigor mortis, the first flies. Bloat: swollen green and tight, legs pushed out stiff, the skin marbling, gas
  gurgling in it. Then it bursts with a wet rip and the active decay begins: the skin slips and splits, pus and
  maggots in it, maggots spilling off, a black pool spreading under it, swarms of flies and a stench haze. Then it
  sinks in, the bones come through, and it dries to bones and hide that bleach.
- Rot is counted in degree-days: a day at 20 °C is a full day of rot, colder is slower, freezing stops it, and water
  halves it. Small bodies go faster: in the plains a chicken is bones in about ten days, a pig in four weeks, a cow in
  five and a horse in eight. In dry heat (deserts, badlands, savanna, the Nether) bodies dry into leathery mummies
  instead.
- Severed pieces rot too, starting as rotten as the body they came off, at the pace of their own size.
- With Fluid Gore on the server (singleplayer, LAN host), a kept body remembers when it died: it goes on rotting while
  nobody is near and while players sleep, and a player who comes along later sees it as rotten as it is. Every player
  with the mod sees the same rot.
- Rot Speed (1x real time, up to 100x).
- Demo: `/gore demo decay` (six weeks of rot in about a minute).

## 2.11.0 - 2026-10-15

### Added
- Kept Bodies (needs Fluid Gore on the server; in singleplayer and on a LAN world you host, that is you): a
  slaughtered animal stays where it fell, with every wound, missing limb and plucked feather, until someone cleans it
  up with a sponge or butchers it apart. It is saved with the world. Settings: Keep Bodies, Most Kept Bodies (64 per
  dimension; the oldest goes up in smoke).
- Shared butchery: with the mod on the server, every chop, cut, pluck, clean-up and scrub on a body is shown to the
  other players with the mod as it happens, and comes out the same on every screen.
- A sponge, or any cleaning item, used on a body cleans the whole body away in a puff of smoke.
- Shared loose pieces: whoever punches, kicks, carries, throws, stomps or cuts off a limb or a diced chunk moves it on
  every screen; it lands and lies the same for everyone, and a bone smashed by one player breaks for all.
- The server keeps a record of what was done. A creature someone hit or butchered keeps its wounds, missing limbs,
  broken bones, char and plucks, saved with it in the world, and the pieces cut off or moved are kept for as long as
  they last. A player who was not there, comes back later or rejoins finds the zombie missing its arm and the arm
  lying where it fell, until it despawns.
- Demo: `/gore demo kept`.

### Changed
- Deaths are the vanilla death by default, made gory: the animal goes red and keels over onto its side as in vanilla,
  its heart pumping its blood out as it lies still. The death throes are now a setting (Death Throes, off). The weapon
  shows: the body goes over away from the blow, a sword turns it with the swing and throws blood off the slash, and an
  axe or pick hacks into it so that its legs buckle for a moment before it keels over. It is red until it is down,
  then just dead.
- A dead body can no longer be milked, sheared, fed, leashed or ridden.
- The mod now has a server half. On a vanilla server, or a server without the mod, everything works as before, on
  your screen only.

### Fixed
- Horses, donkeys and mules floated above the ground once they lay on their side: they were lifted by half their
  hitbox, which is twice as wide as their body, and are now lifted by half their trunk.

## 2.10.0 - 2026-10-15

### Added
- Player Injuries (a new settings section): your own hits show on your screen. Chunky pixel-art blood is thrown
  across it from the side the blow came from and runs down it, drying darker as it fades. A fall splashes the bottom,
  a blast hits every edge. Setting: Blood on Screen.
- A heavy blow knocks your view aside, away from where it came from. Setting: Stagger, 0-200%.
- Low Health Heartbeat: below a third of your health the screen edge closes in red (a dithered, old-school fade) and
  your heart pounds, faster and louder the closer you are to death. Your view sways slowly as well.
- Limping: a cut or broken leg makes players and humanoid mobs (zombies, skeletons, piglins) limp. The leg barely
  swings and the body drops onto it, and in first person your view hitches on that step. A hurt arm hangs. A deep
  belly wound, or a player near death, is held with the free hand, head hanging.
- Bloody Clothes: blood from open wounds soaks through a player's jacket, sleeves and trousers and smears their
  armour.
- Wounds Heal: wounds close as health comes back (meat to a gash, a gash to a bruise, then nothing) and broken bones
  knit. Full health is a whole body again.
- Wounds Clot After (30 s): a living body stops dripping that long after the last blow. The dead still bleed out.
- Armor on Severed Parts: heads and limbs keep the armour they wore.
- Brutal PvP: a player's blade cuts other players deeper and they bleed harder. A kill on High or Extreme takes more
  than one limb.
- A creeper that kills a player tears them apart like any other body.
- All of these are visual and client-side: health, damage and movement are unchanged.
- Demos: `/gore demo injured`, `/gore demo heal`, `/gore demo armor`.

## 2.9.0 - 2026-10-10

### Added
- Glowing Ender Blood (on by default): enderman blood (splats, puddles, drops, footprints) and the raw magenta cuts in
  an enderman are drawn at full brightness, so they glow violet in the dark. With Iris shader packs they stay lit, and
  most packs make them bloom.
- Demo: `/gore demo glow`.

### Changed
- Ender blood is a deeper violet that no other blood colour shares, so it can be told apart at any brightness.

## 2.8.0 - 2026-10-10

### Added
- Endermen (and endermites) bleed bright violet ender blood that crumbles like purpur. Under the black hide their
  flesh is dark violet and cuts glowing magenta, with bones pale as end stone. Portal sparks pour out of every wound,
  and their flesh rings like crystal when hit.
- Severed enderman limbs will not lie still: for half a minute they blink to another spot close by every few seconds.
  Water and rain burn them, so a wet piece fizzes and keeps blinking until it finds somewhere dry. Cutting them up
  throws ender pearl crumbs.
- Demo: `/gore demo ender`.

### Changed
- Severed limbs and pieces stay for 10 minutes by default (was 1 minute). The Part Lifetime slider now goes up to an
  hour in 30-second steps. Configs still on the old default are moved up.

## 2.7.0 - 2026-10-10

### Added
- Pigs are pork: pale pink lean under thick white fat. Cows and mooshrooms are beef: bright red, marbled with creamy
  fat.
- Cut faces of diced pieces are pixel-art cross-sections of meat from edge to edge, as on a butcher's block: the grain
  of the muscle, veins of fat, a rim of fat along one edge, wet smears of blood, and bone in big pieces. Chicken is
  plain pink meat.
- Bloody Wool (on by default): a sheep's wool soaks up its blood, with a dark stain over every wound and the whole
  fleece reddening as it bleeds and as it lies dying. Sheep standing close by get spattered pink. Rain and water wash
  it out.
- Scraps of pork chop, beef or chicken fly off each cut.
- Demo: `/gore demo meats`.

### Changed
- The loose piece limit is raised from 64 to 160 (the slider goes up to 256), so a diced animal's pieces are not lost
  while the next one is cut. Configs still on the old default are moved up.

## 2.6.0 - 2026-10-10

### Added
- Pieces made of several model blocks (horse, donkey and mule heads, a chicken's head with its beak, donkey chests)
  are hacked apart into their blocks with the first chop, and every block can then be diced.
- Chickens are raw poultry: pale pink meat and yellow fat in their wounds, cut faces and scraps.
- Demo: `/gore demo dice`.

### Changed
- A blade opens a dead body at the same pace whatever its size, so a horse is jointed and split in a few chops, like a
  chicken.
- Dicing goes finer, the pieces stay about where they are cut, and every face a piece was ever cut on stays raw meat
  (small pieces are meat through, big ones show bone). Each cut flicks off scraps of raw meat with a wet squelch.

## 2.5.0 - 2026-10-10

### Added
- Broken Bones: blunt blows add up on skulls, ribs and limbs. Fractures bend the pose, show broken bone and throw
  shards; further blows smash heads or tear limbs off.
- Horses: the head and neck sever together (donkeys and mules too), with longer paddling, stronger early cries,
  heavier bleeding, a heavy landing and a body that lies longer. A split horse carcass drops the tail separately.
- Plucking: right-click a dead chicken with an empty hand (hold it to keep going). A handful of feathers comes out
  where you aim and drifts down, and the skin there is bare, pale and goose-pimpled. The more you pluck, the more of
  the bird is bare skin. Pieces cut off a plucked bird are plucked too.
- Jointing: the head, wings and legs come off with a blade, as before, but the body now stays while you work: every
  pluck and cut keeps it on the ground for another 30 seconds.
- Splitting: cut through the carcass to the bone, then three more chops through the backbone, and it splits: whatever
  is still attached comes off, the innards spill, and the trunk falls apart in two with raw meat and bone on the cut
  faces.
- Dicing: a blade on any loose piece (a split carcass, a leg, a wing) cuts it in two across its longest side, raw on
  the new face, until the pieces are too small to cut. Works on any animal, not only chickens.
- Butchery setting (on by default).
- Demos: `/gore demo butcher`, `/gore demo horse`.

### Changed
- Version 2.4.0 was never released on its own; its changes are part of this release.

## 2.3.0 - 2026-10-10

### Added
- Inventory Sorting, the simple 1.7.10 way: a small sort button in the top right of chests, barrels, ender chests and
  shulker boxes and one next to your inventory, or middle-click over either part to sort it. Partial stacks are
  merged and everything is ordered by item id (blocks first, then items, like the old numeric ids), then damage and
  name. The hotbar is left alone. It moves items with ordinary inventory clicks, so it works on any server. It can be
  turned off in the settings.

## 2.2.0 - 2026-10-10

### Added
- Slashes: swords, axes and flint leave long, narrow incised and chop wounds laid along the swing, gaping in the middle
  and tapering at the ends, that open through bruised edges, the yellow rim of fat under the skin, the dark split,
  muscle and a line of bone. A blade through a bruise turns it into a cut. Fists, clubs, points and teeth still leave
  round, ragged wounds.
- Rotten Undead (on by default): zombies, zombie villagers, drowned, zombified piglins, zoglins and zombie horses are
  rotting meat. Their wounds show skin marbled black and green, fat turned to grey wax, black-brown clotted ooze,
  grey-green muscle, pus, maggots and stained bone, and maggots drop out of deep wounds. Their blood is a new dark
  "Rotten" colour that oozes at about half the rate, never spurts (no pulse) and slops out of a stump. Flesh knocked
  out of them is rotten too.
- Demo: `/gore demo rot`.

### Changed
- Wounds on Monsters is on by default (existing configs are switched on once).

## 2.1.0 - 2026-10-10

### Changed
- Every death starts with the body dropping: the legs give way within a third of a second and it falls onto its side.
  The standing, running, kneeling, dragging and circling deaths are gone.
- On the ground it goes through the stages of a dying animal in three to five seconds: a rigid phase with the legs
  locked out and the head arched back; involuntary paddling in bursts with pauses, each leg in its own rhythm, fading
  and slowing; a few gasps, further apart and weaker, the chest heaving and the head lifting; fine flickers of muscle;
  then stillness.
- The heart pumps blood out of the worst wound (or the neck, or the mouth) in beats that start fast and weaken and
  slow until it stops, and the pool grows under the body.
- Eight causes of death shape all of it: head blow (silent, rigid, hard paddling), cut open (lies bleeding, kicks
  late), broken back (hind end limp, forelegs rigid), beheaded (hardest kicking, no breath, neck pumping), through the
  lungs (wet gasps, blood from the mouth), blast (limp, a few twitches), burnt (limbs drawn in) and beaten (violent,
  disorganised kicking). Only a conscious animal cries out, and weakly.
- Every length, kick strength, rhythm and gasp is rolled from the body's own shared dice, so no two deaths are alike
  and every player with the mod sees the same one.
- Demo: `/gore demo deaths` shows the eight causes side by side.

### Fixed
- Bodies the server had removed froze mid-kick, because their animation ran on the entity's age, which stops when the
  game stops ticking it. Dying bodies now run on their own clock, the same on every client.

## 2.0.0 - 2026-10-10

### Added
- Shared dice: every random choice a hit makes (wound depth, which part comes off, which death an animal dies, how its
  body thrashes and whether it bursts at the end) is seeded from what the server tells every client the same way: who
  was hit, who hit them, and their exact health afterwards. Two players watching the same fight see the same wound,
  the same severed part and the same death. The guessed hit spots of mobs and arrows match too.

### Changed
- Wounds on Players is on by default, now that everyone sees the same wounds. Existing configs are switched on once;
  turn it off again in the settings if you prefer.

## 1.9.3 - 2026-10-10

### Fixed
- Hits by other players landed on a random spot: only your own swings were aimed, so when you hit something on the
  head, a friend with the mod saw the wound somewhere else. Other players' swings are now traced along where they are
  looking, so the wound lands on the same spot on everyone's screen. Mobs and arrows still land on a guessed spot.

## 1.9.2 - 2026-10-10

### Fixed
- The click when a carcass falls over: the body hitting the ground used Minecraft's hard fall-damage crunch every
  time. That crunch now plays one fall in four; the other falls are a dull, heavy thud. The sound of a body heaving
  and dropping back, and of flesh pieces landing, is also a quarter as frequent.

## 1.9.1 - 2026-10-10

### Fixed
- The clicking when an animal is hit or killed: its cries were the animal's own hurt sound at nearly double pitch,
  chopped short, layered in pairs and restarted every 5 to 9 ticks, which stuttered like a machine gun. Cries are now
  near natural pitch, a death is at most three of them spaced further apart, only the first has a second layer, and
  repeats after a hit are capped at two.

## 1.9.0 - 2026-10-10

### Added
- Each mob has its own amount of blood: a cow or horse gushes, a chicken or rabbit barely bleeds, and Extreme
  exaggerates it further. Override it per mob with `bloodAmounts` in the config file.
- Death blood sounds follow the bleed: the wound opens, the heart pumps the blood out in weakening jets, it drips and
  pools, and a heavy kill gurgles. The sounds vary from death to death.
- A blast sounds like one low wet thud and a soft patter of gore coming back down; weeping carcasses and stumps drip
  faintly now and then.

### Fixed
- The clicking: single short sounds are rare now, screams fade to silence instead of being cut off, and burning
  animals sizzle softly and rarely instead of hissing sharply many times a second.
- Flames on a dying, ragdolled animal hung in the air where it used to stand. They are now flame particles on the
  body.
- Gore sounds stacked into a wall of noise when many things died at once. They are quieter and rate-limited now.

## 1.8.0 - 2026-10-10

### Added
- Nine deaths, picked by where and with what the killing blow landed and by the animal itself, with enough chance in
  it that a herd killed the same way dies many different ways:
  - **Stunned**: a heavy blow to the head. It drops where it stands like a sack, lies rigid with its legs locked out
    and its head arched back, then the legs paddle on their own and slowly stop. Not a sound.
  - **Bled out**: an opened artery. It stands swaying while its blood pumps out with every heartbeat, slower each
    time, into a growing pool; its front knees go, then its hind end, and it lies on its chest before it rolls onto
    its side.
  - **The death run**: it bolts blind, screaming and bleeding, and crashes nose first.
  - **The downer**: down on its front knees, then its chest, head turned back against its flank. It keeps trying to
    heave its hind end up, groaning, and falls back each time, until it rolls over.
  - **Broken back**: the hind end drops into a dog-sit and it drags itself on its front legs, bellowing and smearing
    blood behind it. Falls make this one likely.
  - **Drowning in blood**: neck stretched out low, it coughs up frothy blood with every heave and gasps for air, sinks
    onto its chest and coughs there until it stops.
  - **Gut-shot** and **thrashing after a beating**, as before.
  - **Brain hurt**: it walks in tight circles with its head cocked, crashes over, rolls onto its back kicking at the
    sky, and stiffens there, legs up.
- Every death answers a blow while it dies: a stunned cow's legs start paddling, a bleeding one gushes, a downer heaves
  to get up, a broken-backed one drags itself away from you, a choking one coughs, a circling one turns.
- New sounds built from vanilla ones: a wet cough, a groan of straining to stand, a gasp.
- Burnt bodies: animals on fire char from seared through roasted brown to black and stay that way, alive or dead.
  They smoke, sizzle and smoulder, die writhing, and leave a black scorch mark. Fire sears wounds shut, so burning
  does not bleed.
- On High and Extreme, a body that was beaten or burnt to death lies there a good while, and blood stays on the ground
  longer than the flesh.
- Settings: Death Pop Chance, Burnt Bodies.
- Demos: `/gore demo deaths`, `/gore demo burnt`, `/gore demo evidence`.

### Changed
- The death that bursts the body apart at the end (Death Pop) is now rare: one death in twenty (Death Pop Chance, 5%
  by default). Every other body is left lying and goes with the vanilla puff of smoke.

## 1.7.0 - 2026-10-10

### Changed
- Deaths feel like vanilla's, only harder: a dying body stays red until it is gone, keels over onto its side about as
  fast as the vanilla tip-over, and lands with a heavy thud and a jolt of blood.
- Hitting a dying body knocks it about like a struck mob (Terraria-style knockback): it flashes red, skids and hops,
  harder the harder the hit, and every blow keeps it around a little longer. Walking into a body shoves it.
- When a body finally goes, it pops: a bigger puff of smoke, and what is left bursts into its head and limbs and some
  scraps of flesh that can still be kicked about for a while.
- Gore does not last as long on High and Extreme (decals, puddles, parts and spurts: High 1.5x -> 1.2x, Extreme
  2x -> 1.3x). Flesh pieces last half as long as severed parts. Bodies lie 10 s after their throes instead of 45 s
  (configs still on the old default are moved over), and the longest throes are shorter.
- Settings: Red Death, Death Pop.
- Demos: `/gore demo death`, `/gore demo floor`.

### Fixed
- Gore floated in the air after explosions or broken blocks: flesh pieces resting on a block never noticed it was
  gone. Now everything resting on or stuck to a block (flesh, parts, splats, puddles) drops the moment the block
  breaks.

## 1.6.0 - 2026-10-10

### Added
- Explosions graded by distance and by how much of the body the blast could see, using the blast radius from the
  server's explosion packet. Point blank: the body bursts into hundreds of pieces of muscle, fat, bone, gut, organs,
  cud and its own hide, the near side seared black, in a red mist. Close: two to four limbs or the head torn off and
  thrown, the belly opened and the gut spilled, the body thrown up and out (out of pits too) and dead before it
  lands. Further off: shrapnel wounds and a hard throw.
- Flesh pieces as small tumbling boxes that bounce by tissue, smear walls and floors, stick to walls and peel off, and
  patter down with wet slaps and clattering bone.
- New weapons: flint (ragged, shallow cuts), pickaxe (digs in and knocks out pieces), bites from animals, and blasts.
  Fists and clubs bleed far less on the outside; a long beating makes the animal bleed from the mouth.
- Settings: Blast Gore, Max Flesh Pieces.
- In-game demos: `/gore demo` lists a scene for every feature (a pit of pigs and TNT, weapons, hacking a leg off, the
  headless run, a beating in front of the herd, grief, personalities, bones, launch) and plays it in front of you.
  Needs cheats or op; nothing is needed on the server.

### Fixed
- Skeletons killed by a hit whose attacker the client never learned dropped no bones.

## 1.5.0 - 2026-10-09

### Added
- Mothers and their young. A hurt calf cries for its mother and she answers, turning to it, shaking. A dying calf
  keeps calling for her, weaker each time. Afterwards she grieves for minutes: head hung, turned to where it happened,
  calling in a long, breaking call that goes on after the body is gone and fades slowly. A calf whose mother is killed
  calls for her the same way. Works for every animal in `sufferingAnimals`.
- Killing the young leaves the herd's fear raised far longer than killing a grown animal, and a small body lies where
  it fell longer.
- Setting: Grief (seconds; 0 turns it off).

## 1.4.0 - 2026-10-09

### Added
- Hit feedback by weapon and flesh: a dull wet thud for fists, a wet slice for swords, a heavy chop for axes, a crack
  when the blade reaches bone, dry tearing for husks and hard knocks for skeletons. All built from vanilla sounds.
- Blood thrown off the cut along the blow and out of the wound, more the deeper the wound.
- Severed pieces keep the wounds that were cut into them and show a raw cut face (meat around bone) where they were
  joined to the body.
- Body weight: big carcasses take blows, bounce and swing heavily; small bodies fly further and twitch faster.

### Fixed
- Babies and model variants (baby animals, warm and cold cows, pigs, chickens). Renderers swap models per entity while
  drawing, so the mod sometimes measured the wrong one: babies were hit against adult geometry, their wounds never
  showed, and parts cut off them were shrunk twice and started in the wrong place. Each entity now uses the model it
  is drawn with.

## 1.3.0 - 2026-10-09

### Added
- The hack model: blows land on the exact spot of the model you aimed at (leg, head, flank, neck) and open a wound
  there that worsens with each blow to the same spot: bruise, gash, open meat, then bone. Pixel-art wound layers are
  drawn on the model and follow its animation, on living animals mid-fight and on bodies after death.
- Targeted severing: a blade through a wound that cannot get any deeper takes that limb or the head off and leaves a
  raw stump on the body. Open wounds drip, so a hurt animal leaves a trail.
- Gore Level sets wound depth: Low stops at gashes, Medium at open meat, High and Extreme reach the bone.
- Weapons cut differently: fists bruise, swords cut, axes chop deep, arrows punch small, deep holes.
- Materials: husks are dried leather that splits to a sickly ooze; skeletons scuff and crack.
- Settings: Wounds, Wounds on Monsters (off by default), Wounds on Players (off by default).
- Bone bodies: skeletons and other bony mobs never bleed; hits chip them into bone shards and dust, any melee weapon
  can snap a bone off, and loose bones shatter.
- Hitting bodies after death: swing at a body that is down or still dying to spray blood, shove it and cut it.
- Death Launch: the killing blow throws a body along the direction it was struck.
- Personalities: every animal has its own voice, nerve and way of dying, fixed by its species and identity.
- Ecology: animals remember killing for the session. Survivors cower and stand over bodies, mothers and young call
  for each other, predators take an interest in fresh blood, and bodies draw flies.
- Scrub Blood: wipe blood away with a sponge or brush.

## 1.2.0 - 2026-10-08

### Added
- Redstone pixel-art blood style (now the default), with Realistic and Mixed styles.
- Redstone-dust particles, gore chunks (redstone-block crumbs and bits of meat) and arterial spurts.
- Random wound severity on a log-normal curve, satellite splats, colour variation and wet crunching sounds.

## 1.1.0 - 2026-10-08

### Added
- Animal Suffering: screams made from each animal's own distorted vanilla sounds, flinching, trembling, limping and
  herd panic.
- Death throes based on how the animal died (quick, normal, brutal, beheaded), and bodies that stay after the server
  removes the entity until their throes are over.
- Headless run, and severed heads that twitch and moan.
- Severed parts can be punched, picked up, thrown, kicked and stomped.

## 1.0.0 - 2026-10-08

### Added
- Blood spray, splat decals on floors and walls that follow stairs and slabs, and a lightweight blood fluid simulation
  with puddles that flow, pool and drip off ledges.
- Bloody footprints, and rain and water washing blood away.
- Dismemberment with bladed weapons: parts tumble away as physical objects drawn with the entity's own texture.
- Settings screen and Mod Menu integration.
