# Changelog

All notable changes to Fluid Gore. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [2.11.0]

The vanilla death, made gory, and bodies that stay until you clean them up. Butchery everyone sees.

### Added
- Kept Bodies (needs Fluid Gore on the server; in singleplayer and on a LAN world you host, that is you): a
  slaughtered animal stays where it fell, with every wound, missing limb and plucked feather, until someone cleans it
  up with a sponge or butchers it apart. It is saved with the world. Settings: Keep Bodies, Most Kept
  Bodies (64 per dimension; the oldest goes up in smoke).
- Shared butchery: with the mod on the server, every chop, cut, pluck, clean-up and scrub on a body is shown to the
  other players with the mod as it happens, and comes out the same on every screen.
- A sponge (or any cleaning item) on a body cleans the whole body away in a puff of smoke.
- Shared loose pieces: whoever punches, kicks, carries, throws, stomps or cuts off a limb or a diced chunk moves it on
  every screen; it lands and lies the same for everyone, and a bone smashed by one player breaks for all.
- Evidence: the server keeps what was done. A creature someone hit or butchered keeps its picture (wounds, missing
  limbs, broken bones, char, plucks), saved with it in the world; the pieces cut off or moved are kept for as long as
  they live. A player who was not there, comes back later or rejoins finds the zombie missing its arm and the arm
  lying where it fell, until it despawns.
- Demo: `kept`.

### Fixed
- Horses, donkeys and mules floated above the ground once they lay on their side (they were lifted by half their
  hitbox, which is twice as wide as their body; now by half their trunk).

### Changed
- Deaths are the vanilla death by default, made gory: the animal goes red and keels over onto its side exactly as in
  vanilla, its heart pumping its blood out as it lies still. The death throes are a setting now (Death Throes, off).
  The weapon shows in it: it goes over away from the blow; a sword turns it with the swing and throws blood off the
  slash as it goes; an axe (or a pick) is hacked into it, the body jolts and its legs buckle for a moment before it
  keels over. It is red until it is down, then it is just dead.
- A dead body can no longer be milked, sheared, fed, leashed or ridden.
- The mod now has a server half (`environment` is `*`). On a vanilla server, or a server without the mod, everything
  works as before, on your screen only.

## [2.10.0]

Injuries you feel, wounds that heal, and armour that stays on.

### Added
- Player Injuries (new settings section). Your own hits now show on your screen. Chunky pixel-art blood is thrown
  across it from the side the blow came from and runs down it, drying darker as it fades. A fall splashes the bottom,
  a blast hits every edge. Setting: Blood on Screen.
- A heavy blow knocks your view aside, away from where it came from. Setting: Stagger, 0-200%.
- Low Health Heartbeat: below a third of your health the screen edge closes in red (a dithered, old-school fade) and
  your heart pounds, faster and louder the closer you are to death. Your view sways slowly as well.
- Limping: a cut or broken leg makes players and humanoid mobs (zombies, skeletons, piglins) limp. The leg barely
  swings and the body drops onto it. In first person your view hitches on that step. A hurt arm hangs. A deep belly
  wound, or a player near death, is held with the free hand, and their head hangs.
- Bloody Clothes: blood from open wounds soaks through a player's jacket, sleeves and trousers and smears their
  armour.
- Wounds Heal: wounds close as health comes back (meat to a gash, a gash to a bruise, then nothing) and broken bones
  knit. Full health is a whole body again.
- Wounds Clot After (30 s): a living body stops dripping that long after the last blow. The dead still bleed out.
- Armor on Severed Parts: heads and limbs keep the armour they wore.
- Brutal PvP: a player's blade cuts other players deeper and they bleed harder. A kill on High or Extreme takes more
  than one limb.
- A creeper that kills a player tears them apart like any other body.
- Demos: `injured`, `heal`, `armor`.

All of it is cosmetic and client-side. Health, damage and movement are untouched.

## [2.9.0]

Ender blood glows.

### Added
- Glowing Ender Blood setting (on): enderman blood (splats, puddles, drops, footprints) and the raw magenta cuts in an
  enderman are drawn at full brightness, so they glow violet in the dark. With Iris shader packs they stay lit and
  most packs bloom them.
- Demo: `glow`.

### Changed
- Ender blood is a deeper violet (0x7616C4), a hue no other blood colour shares, so it can be told apart at any brightness.

## [2.8.0]

Endermen of their own, and limbs that stay.

### Added
- Endermen (and endermites) bleed bright violet ender blood that crumbles like purpur. Under the black hide their flesh is dark
  violet, cut glowing magenta, with bones pale as end stone. Portal sparks pour out of every wound, and their flesh rings like
  crystal when hit.
- Severed enderman limbs will not lie still: for half a minute they blink to another spot close by every few seconds. Water
  and rain burn them, so a wet piece fizzes and keeps blinking until it finds somewhere dry. Cutting them up throws ender
  pearl crumbs.
- Demo: `ender`.

### Changed
- Severed limbs and pieces stay for 10 minutes by default (was 1 minute). The Part Lifetime slider now goes up to an hour in
  30-second steps. Configs still on the old default are moved up.

## [2.7.0]

Pork, steak and a bloodied herd.

### Added
- Pigs are pork: pale pink lean, thick white fat. Cows and mooshrooms are beef: bright red steak marbled with creamy fat.
- Cut faces of diced pieces are now pixel-art cross-sections of meat edge to edge, as on a butcher's block: the grain of the
  muscle, veins of fat (marbling), a rim of fat along one edge, wet smears of blood, and bone in big pieces. Chicken is
  plain pink meat.
- Bloody Wool setting (on): a sheep's wool soaks up its blood, with a dark stain over every wound and the whole fleece
  reddening as it bleeds and as it lies dying in its blood. Sheep standing close by get spattered pink. Rain and water wash it out.
- Scraps of pork chop, beef or chicken fly off each cut.
- Demo: `meats`.

### Changed
- Loose pieces limit raised from 64 to 160 (slider up to 256) so a diced animal's pieces are not lost while the next is cut;
  configs still on the old default are moved up.

## [2.6.0]

Horses, donkeys and mules butchered like chickens; meatier, pinker dicing.

### Added
- Pieces made of several blocks of model (horse, donkey and mule heads, a chicken's head with its beak, donkey chests) are
  hacked apart into their blocks with the first chop, and every block can then be diced.
- Chickens are raw poultry: pale pink meat and yellow fat in their wounds, cut faces and giblets.
- Demo: `dice`.

### Changed
- A blade opens a dead body at the same pace whatever its size, so a horse is jointed and split in a few chops like a chicken.
- Dicing goes finer, the pieces stay about where they are cut, and every face a piece was ever cut on stays raw meat
  (small pieces are meat through, big ones show bone). Each cut flicks off scraps of raw meat with a wet squelch.

## [2.5.0]

Butchery, broken bones and the horse revamp (including the unreleased 2.4.0 work).

### Added
- Broken Bones setting: blunt blows accumulate on skulls, ribs and limbs; fractures deform the pose, expose broken bone and throw shards. Further blows smash heads or tear limbs off.
- Horse revamp: head and neck sever together (also donkeys and mules), longer paddling, stronger early cries, heavier blood flow, a heavy landing and longer corpse persistence. Horse blood measure increased from 1.7 to 2.6.
- Horse carcasses split with the tail falling separately; horse demo and regression coverage for horses, donkeys, mules and fractures.
- Plucking: right-click a dead chicken with an empty hand (hold it to keep going). A handful of feathers comes out
  where you aim and drifts down; the skin there is bare, pale and goose-pimpled with the pits the feathers came out
  of. The more you pluck, the more the whole bird turns to bare skin. Pieces cut off a plucked bird are plucked too.
- Jointing: the head, both wings and both legs come off with a blade, as before, but the body now stays while you
  work: every pluck and cut keeps it on the ground for another 30 seconds.
- Splitting: cut through the carcass to the bone, then three more chops through the backbone, and it splits: whatever is still attached comes
  off, the innards spill, and the trunk falls apart into two pieces with raw meat and bone on the cut faces.
- Dicing: a blade on any loose piece (a split carcass, a leg, a wing) cuts it in two across its longest side, raw on
  the new face, until the pieces are too small to cut. Works on any animal, not just chickens.
- Butchery setting (on by default), `butcher` demo and game test.

### Changed
- Fixed indentation in code a few earlier patches had left one tab short.

## [2.3.0]

### Added
- Inventory Sorting, the simple 1.7.10 way: a tiny sort button in the top right of chests, barrels, ender chests and
  shulker boxes, one next to your inventory, and middle-click over either part to sort it. Partial stacks are merged
  and everything is ordered by item id (blocks first, then items, like the old numeric ids), then damage and name.
  The hotbar is left alone. It moves items with ordinary inventory clicks, so it works on any server. Turn it off in
  the settings.

## [2.2.0]

Rotting zombies and slashes that look like real cuts.

### Added
- Slashes: swords, axes and flint leave long, narrow incised and chop wounds laid along the swing, gaping in the
  middle and tapering at the ends, that open through bruised margins, the yellow rim of fat under the skin, the dark
  split, muscle and a line of bone. A blade through a bruise turns it into a cut. Fists, clubs, points and teeth
  still leave round, ragged wounds.
- Rotten Undead (on by default): zombies, zombie villagers, drowned, zombified piglins, zoglins and zombie horses are
  rotting meat. Their wounds show skin marbled black and green, fat turned to grey wax, black-brown clotted ooze,
  grey-green putrid muscle, pus, maggots and stained bone; maggots drop out of deep wounds. Their blood is a new dark
  "Rotten" colour, oozes at about half the rate, never spurts (no pulse), and slops out of a stump. Flesh pieces
  knocked out of them are rotten too.
- `rot` demo and game test.

### Changed
- Wounds on Monsters is on by default (existing configs are switched on once).

## [2.1.0]

Deaths overhauled: every animal now goes down at once and dies out on the ground, the way real animals do.

### Changed
- Every death starts with the body dropping: the legs give way within a third of a second and it falls onto its side.
  The standing, running, kneeling, dragging and circling deaths are gone.
- On the ground it goes through what slaughterhouse and veterinary staff see in a dying animal, in three to five
  seconds: a rigid (tonic) phase with the legs locked out and the head arched back; involuntary paddling (clonic) in
  bursts with pauses, each leg in its own rhythm, fading and slowing; a few agonal gasps, further apart and weaker,
  with the chest heaving and the head lifting; fine flickers of muscle; then limp.
- The heart pumps blood out of the worst wound (or the neck, or the mouth) in beats that start fast and weaken and
  slow until it stops, and the pool grows under the body.
- Eight causes of death shape all of it: head blow (silent, rigid, hard paddling), cut open (lies bleeding, kicks
  late), broken back (hind end limp, forelegs rigid), beheaded (hardest kicking, no breath, neck pumping), through
  the lungs (wet gasps, blood from the mouth), blast (limp, a few twitches), burnt (limbs drawn in), beaten (violent,
  disorganised kicking). Only a conscious animal cries out, and weakly.
- Every length, kick strength, rhythm and gasp is rolled from the body's own shared dice, so no two deaths are alike
  and every player with the mod sees the same one.
- The `deaths` demo shows the eight causes side by side.

### Fixed
- Bodies the server had removed froze mid-kick: their animation ran on the entity's age, which stops when the game
  stops ticking it. Dying bodies now run on their own clock, the same on every client.

## [2.0.0]

The multiplayer release: everyone on a server with the mod sees the same gore, still with nothing on the server.

### Added
- Shared dice. Every random choice a hit makes (wound depth, which part comes off, which of the deaths an animal dies,
  how long it runs, how its body thrashes and whether it bursts at the end) is seeded from what the server tells every
  client the same way: who was hit, who hit them, and their exact health afterwards. Two players watching the same
  fight see the same wound, the same severed part and the same death. Mobs' and arrows' guessed hit spots match too.

### Changed
- Wounds on players are on by default, now that everyone sees the same ones. Existing configs are switched on once;
  turn it off again under Settings if you prefer.

### Fixed
- The `deaths` game test sampled the broken-backed cow too late (screenshots between samples add ticks) and failed
  most runs.

## [1.9.3]

### Fixed
- Hits by other players landed on a random spot: only your own swings were aimed, so when you hit something on the
  head, a friend with the mod saw the wound somewhere else. Other players' swings are now traced along where they
  are looking, so the wound lands on the same spot on everyone's screen. Mobs and arrows still land on a guessed spot.

## [1.9.2]

### Fixed
- The click when a carcass falls over: the body hitting the ground used Minecraft's hard fall-damage crunch every
  time. That crunch now plays one fall in four; the other falls are a dull, heavy thud. The sound of a body
  heaving and dropping back, and of flesh pieces landing, is also a quarter as frequent.

## [1.9.1]

### Fixed
- The clicking when an animal is hit or killed: its cries were the animal's own hurt sound at nearly double pitch,
  chopped short, layered in pairs and restarted every 5 to 9 ticks, which stuttered like a machine gun. Cries are
  now near natural pitch, a death is at most three of them spaced further apart, only the first has a second
  layer, and repeats after a hit are capped at two.

## [1.9.0]

### Added
- Each mob has its own measure of blood (`blood/BloodAmount`): a cow or horse gushes, a chicken or rabbit barely
  bleeds. Exaggerated further on Extreme. Override per mob with `bloodAmounts` in the config.
- Death blood sounds are built like a real bleed (`blood/Gush`): the wound opens, the heart pumps it out in
  weakening jets, it drips and pools, and a heavy kill gurgles. Sounds vary from death to death.
- Blasts sound like one low wet thud and a soft patter of gore coming back down; weeping carcasses and stumps drip
  faintly now and then.

### Fixed
- The clicking: single short sounds are rare now, screams fade to silence instead of being cut off, and burning
  animals sizzle softly and rarely instead of hissing sharply many times a second.
- Flames on a dying, ragdolled animal hung in the air where it used to stand. They are now flame particles on the
  body.
- Gore sounds stacking into a wall of noise: they are quieter and rate-limited when many things die at once.

## [1.8.0]

### Added
- Nine realistic deaths, picked by where and with what the killing blow landed and by the animal itself, with
  enough chance in it that a herd killed the same way dies many different ways:
  - **Stunned**: a heavy blow to the head. It drops where it stands like a sack, lies rigid with its legs locked
    out and its head arched back, then the legs paddle on their own and slowly stop. Not a sound.
  - **Bled out**: an opened artery. It stands swaying while its blood pumps out with every heartbeat, slower each
    time, into a pool that keeps growing; its front knees go, then its hind end, and it lies on its chest before it
    rolls onto its side.
  - **The death run**: it bolts blind, screaming and bleeding, and crashes nose first.
  - **The downer**: down on its front knees, then its chest, head turned back against its flank. It keeps trying to
    heave its hind end up, groaning, and falls back each time, until it rolls over.
  - **Broken back**: the hind end drops into a dog-sit and it drags itself on its front legs, bellowing and
    smearing blood behind it. Falls make this one likely.
  - **Drowning in blood**: neck stretched out low, it coughs up frothy blood with every heave and gasps for air,
    sinks onto its chest and coughs there until it stops.
  - **Gut-shot** and **agonal thrashing after a beating**, as before.
  - **Brain hurt**: it walks in tight circles with its head cocked, crashes over, rolls onto its back kicking at the
    sky, and stiffens there, legs up.
- Every death answers a blow while it dies: a stunned cow's legs start paddling, a bleeding one gushes, a downer
  heaves to get up, a broken-backed one drags itself away from you, a choking one coughs, a circling one turns.
- New sounds built from vanilla ones: a wet cough, a groan of straining to stand, a gasp.
- Burnt bodies: animals on fire char from seared through roasted brown to black and stay that way, alive or dead.
  They smoke, sizzle and smoulder, die writhing, and leave a black scorch mark. Fire sears wounds shut, so burning
  does not bleed.
- On High and Extreme, a body that was beaten or burnt to death lies there a good while as evidence, and blood
  stays on the ground longer than the flesh.
- Settings: Death Pop Chance, Burnt Bodies. Demos: `deaths`, `burnt`, `evidence`.

### Changed
- The death that bursts the body apart at the end (Death Pop) is now rare: one death in twenty (Death Pop Chance,
  5% by default). Every other body is left lying and goes with the vanilla puff of smoke.

## [1.7.0]

### Changed
- Deaths feel like vanilla's, only harder: a dying body stays red until it is gone, keels over onto its side about as
  fast as the vanilla tip-over, and lands with a heavy thud and a jolt of blood.
- Hitting a dying body knocks it about like a struck mob (Terraria-style knockback): it flashes red, skids and hops,
  harder the harder the hit, and every blow keeps it around a little longer. Walking into a body shoves it.
- When a body finally goes it pops: a bigger puff of smoke, and what is left bursts into its head and limbs and some
  scraps of flesh that can still be kicked about for a while.
- Gore does not last as long on High and Extreme (decals, puddles, parts and spurts: High 1.5x -> 1.2x, Extreme
  2x -> 1.3x). Flesh pieces last half as long as severed parts. Bodies lie 10 s after their throes instead of 45 s
  (configs still on the old default are moved over), and the longest throes are shorter.
- Settings: Red Death, Death Pop. Demos: `death`, `floor`.

### Fixed
- Gore floating in the air after explosions or broken blocks. Flesh pieces resting on a block never noticed it was
  gone; now everything resting on or stuck to a block (flesh, parts, splats, puddles) drops the moment the block
  breaks.

## [1.6.0]

### Added
- Explosions graded like real ones, by distance and how much of the body the blast could see, using the real
  blast radius from the server's explosion packet. Point blank: the body bursts into hundreds of pieces of
  muscle, fat, bone, gut, organs, cud and its own hide, the near side seared black, in a red mist. Close: two to
  four limbs or the head torn off and thrown, the belly opened and the gut spilled, the body thrown up and out
  (out of pits too) and dead before it lands. Further off: shrapnel wounds and a hard throw.
- Flesh pieces (giblets) as small tumbling boxes that bounce by tissue, smear walls and floors, stick to walls
  and peel off, and patter down with wet slaps and clattering bone.
- New weapons: flint (ragged, shallow cuts), pickaxe (digs in and knocks out pieces), bites from animals, and
  blasts. Fists and clubs bleed far less on the outside; a long beating makes the animal bleed from the mouth.
- Settings: Blast Gore, Max Flesh Pieces.
- In-game demos: `/gore demo` lists a scene for every feature (pig pit and TNT, weapons, hacking a leg off,
  headless run, a beating in front of the herd, grief, personalities, bones, launch) and plays it in front of
  you. Needs cheats or op; client-side only.

### Fixed
- Skeletons killed by a hit whose attacker the client never learned dropped no bones.

## [1.5.0]

### Added
- Mothers and their young. A hurt calf cries for its mother and she answers, turning to it, shaking. A dying calf
  keeps calling for her, weaker each time. Afterwards she grieves for minutes: head hung, turned to where it
  happened, calling in a long, breaking call that goes on after the body is gone and fades slowly. A calf whose
  mother is killed calls for her the same way. Works for every animal in `sufferingAnimals`.
- Killing the young leaves the herd's fear raised far longer than killing a grown animal, and a small body lies
  where it fell longer.
- Setting: Grief (seconds, 0 turns it off).

## [1.4.0]

### Added
- Hit feedback by weapon and flesh: a dull wet thud for fists, a wet slice for swords, a heavy chop for axes, a crack
  when the blade reaches bone, dry tearing for husks and hard knocks for skeletons. All built from vanilla sounds.
- Blood thrown off the cut along the blow and out of the wound, more the deeper the wound.
- Severed pieces keep the wounds that were cut into them and show a raw cut face (meat around bone) where they were
  joined to the body.
- Body weight: big carcasses take blows, bounce and swing heavily; small bodies fly further and twitch faster.

### Fixed
- Babies and model variants (baby animals, warm and cold cows, pigs, chickens). Renderers swap models per entity
  while drawing, so the mod sometimes measured the wrong one: babies were hit against adult geometry, their wounds
  never showed, and parts cut off them were shrunk twice and started in the wrong place. Each entity now uses the
  model it is really drawn with.

## [1.3.0]

### Added
- The hack model. Blows land on the exact spot of the model you aimed at (leg, head, flank, neck) and open a wound
  there that worsens with each blow to the same spot: bruise, gash, open meat, then bone. Pixel-art wound layers are
  drawn on the model and follow its animation, on living animals mid-fight and on bodies after death.
- Targeted severing: a blade through a wound that cannot get any deeper takes that limb or the head off and leaves
  a raw stump on the body. Open wounds drip, so a hurt animal leaves a trail.
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
  for each other, predators take interest in fresh blood, and bodies draw flies.
- Scrub Blood: wipe blood away with a sponge or brush.

## [1.2.0]

### Added
- Redstone pixel-art blood style (now the default), with Realistic and Mixed styles.
- Redstone-dust particles, gore chunks (redstone-block crumbs and bits of meat), arterial spurts.
- Log-normal random wound severity, satellite splats, colour variation and wet crunching sounds.

## [1.1.0]

### Added
- Animal Suffering: screams made from each animal's own distorted vanilla sounds, flinching, trembling, limping and
  herd panic.
- Death throes based on how the animal died (quick, normal, brutal, beheaded), and bodies that stay after the server
  removes the entity until their throes are over.
- Headless run, severed heads that twitch and moan.
- Severed parts can be punched, picked up, thrown, kicked and stomped.

## [1.0.0]

### Added
- Blood spray, splat decals on floors and walls that follow stairs and slabs, and a lightweight blood fluid
  simulation with puddles that flow, pool and drip off ledges.
- Bloody footprints, rain and water washing blood away.
- Dismemberment with bladed weapons: parts tumble away as physical objects drawn with the entity's own texture.
- Settings screen and Mod Menu integration.
