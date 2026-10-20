# Changelog

All notable changes to Fluid Gore. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [3.1.0]

Cleaning up is messy work now.

### Added
- Bloody sponges and brushes: a cleaning tool soaks up the blood it wipes, and the stain shows on the item in three
  stages, in the blood's own colour (red, ender violet, rotten). The bloodier it is, the less it takes up and the more
  it drags along the stroke as smears. Soaked through, it leaves red wipes wherever it goes, even on a clean floor.
  Wiping a whole body away soaks it too.
- Guts on the floor are squashed and smeared into dark streaks when a tool is dragged over them.
- Washing: use a bloody tool on water (or a water cauldron, or while standing in water). Each dunk rinses some of it
  out in a red cloud. A sponge washed clean comes out a wet sponge; dry it as usual.
- The stain is kept on the item by the server (Fluid Gore on the server), so everyone sees it and it stays with the
  item. On a server without the mod, your game keeps track of it for you alone and the item does not change.
- A soaked brush is still a brush: on suspicious sand or gravel it digs as usual.
- Demo: `/gore demo sponge`.

## [3.0.0]

The server's word on every hit: the first step of Fluid Gore deciding gore on the server when it runs there.

### Added
- Server-reported hits (with Fluid Gore on the server, singleplayer and LAN/e4mc worlds included): the server tells
  every player who can see a body exactly what hit it: the damage done, the weapon that really struck, the path the
  blow took (another player's exact view instead of the rounded one, an arrow's flight) and the dice for it. Without
  the mod on the server, each game works it out from what vanilla sends, as before.
- Arrows with no shooter (dispensers, `/summon`) wound where they strike. They used to leave no wound at all.
- Demo: `/gore demo arrows`.

### Fixed (with Fluid Gore on the server)
- A shield block no longer draws blood: a hit the shield stopped is never reported.
- Hits that absorption hearts or armour soak up no longer wait four ticks before a guessed small bleed.
- Arrows from a skeleton too far off for your game to know of still wound and bleed the right way.

### Note
- Players and the server must run the same Fluid Gore version (since 2.16 the Cobble Tank makes this required anyway).

## [2.17.0]

Ragdoll Physics, polished for killing off cliffs, down stairs and onto slabs; blood read like a spatter analyst reads it.

### Added
- Directional Spatter (on by default): blood that lands at a slant stretches into a teardrop as long as its width over
  the sine of the angle it came in at, its tail pointing the way it flew. A burst on the ground leaves a ring of them
  pointing away from where it happened.
- Drag Trails (on by default): a bleeding body that slides, tumbles or is shoved over the ground smears a swipe behind
  it, feathered off the way it went, for the first minute after death.
- A fall off a cliff lands as hard as it fell: more than about three blocks and the body bursts on the ground, blood
  driven out low all round.

### Changed (Ragdoll Physics on)
- Bodies end on their sides: a pig or sheep no longer stays on its belly with folded legs, and a four-legged body that
  lands on its back rolls onto the nearer side (people still lie on their backs).
- Bodies tip over their feet and roll over what they lie on, so they no longer skate across the floor; twitches on
  the ground no longer lift the whole carcass and walk it away.
- Stairs and ledges: a body only your game has slides down to the bottom; a body the server keeps is put down a step
  as soon as the server takes it there; a body on a lip slumps over it instead of being flung off a cliff.
- Every step down a flight of stairs thuds and knocks a little blood out, as with the old ragdoll.

### Fixed
- A player who burnt to nothing in lava (or was blown or cut apart) came back invisible after respawning: the server
  gives a respawned player their old id, and the dead body's state followed it onto them.

## [2.16.0]

The Cobble Tank: the Roadster's tank, for real.

### Added
- Cobble Tank: a TNT with cobblestone on its four sides. 600 litres of air at 10,000 psi (about 21 kg of TNT; both can
  be set for new tanks). Placed like a piston: its top faces away from the block you put it on. **It is a real block:
  Fluid Gore must be on the server and on every player who joins** (singleplayer just works). Real damage applies for
  this block only; everything else stays cosmetic.
- Light it like TNT (flint and steel, fire charge, burning arrow, fire or lava beside it, redstone): the casing creaks
  and pops, then it bursts. Like TNT, nothing is said in chat. Every body is hurt by the real peak overpressure where it stood: nothing left within 2 m,
  dead to about 8 m, eardrums burst to about 15 m; fragments of the casing fly out a few hundred metres. Glass breaks to
  about 55 m and wooden doors, trapdoors and gates are blown open to about 28 m (Tank Breaks Glass). No crater, no fire:
  it is cold gas. A white fog, then debris clattering down. Explosions burst it, and it bursts other tanks near it.
- It cannot be mined while it holds pressure. Hit it instead: a fist thuds, wood and stone scuff it, iron cuts the
  fibres, diamond and netherite get through in a few blows. Every blow grinds: stone cracking and chipping off in cobble
  crumbs, the tool scraping the metal under it, sparks once a hard tool bites. The scars stay on the tank: scratches,
  then chips, then a gouge down to the black fibre.
- Through the casing it leaks, where it was struck. A pinhole hisses and frosts for minutes. A cracked fitting screams,
  vents in 15 to 30 seconds and pushes up to 9 kN back the way the blow came. Struck from the side, it tears loose
  once the push beats the friction on its 400 kg and flies; struck from above, the thrust only presses it into the
  ground; braced against a wall, it stays. Flying, it is a rigid body: real gravity, air drag, friction skidding on the
  ground, and a torque from a jet that never points quite through its centre, so it tumbles faster and faster. It hits
  what is in its way by its kinetic energy and smashes through glass, leaves and dirt. When it has vented it lands and
  stands as a block, top the way it came to rest.
- Standing in the jet: air forced under the skin within 30 cm (swelling, and now and then a fatal air embolism),
  sandblasted to 1 m, knocked down or thrown to 3 m, a gale to 5 m. The tank and the crack chill to -80 C: touching them
  freezes you, frost and ice form at the crack, but the jet itself is only a cold wind. Standing near a leak costs your
  hearing the longer you stay. Hit a leaking tank again, or let the crack run, and it can still burst, sized to the
  pressure left.
- Sneak and use an unharmed tank with an empty hand to pick it up again.
- Demo: `/gore demo tank`.
- Ragdoll Physics (off by default): dead bodies go limp as real ones do. Head, trunk and every leg move on their own,
  the head on a neck that lets it droop, each joint only as far as a real one goes. The body falls the way the blow
  sent it, folds, drapes over edges and comes to rest under its own weight, never through a block, then sleeps and
  costs nothing. The body stays whole, as on the vanilla model. Zombies, skeletons and other players get one too.
  Ragdolls Bend at the Knee (off) also bends legs at the knee and hock and arms at the elbow; Show Ragdoll Segments
  draws the physics. Off: the original physics.

## [2.15.0]

The Roadster's pressure vessel.

### Added
- Pressure Vessel (on by default): light a 2x2 square of TNT (flat or standing, by flint and steel or redstone) and it
  bursts like a carbon-overwrapped pressure vessel. By default it is the Roadster's: 200 litres of gas at 10,000 psi,
  about 29 MJ, some 7 kg of TNT. Volume and pressure can be set.
- Every body is hurt by the real peak overpressure where it stood, worked out from the gas's stored energy and the
  standard blast-scaling curves, less what a wall took out of it: nothing recognisable left within about 2 m, limbs
  torn off by the blast wind out to about 3, lungs torn out to about 4.5, coughing blood and burst eardrums out to about
  10, ringing ears beyond. Fragments of the casing fly out and wound whatever they hit, more often the closer it was.
- It happens to you too. A line in chat tells you how far you were, the pressure there and what it does to a body. Your
  view is thrown about; with burst eardrums, blood runs down both sides of the screen, every sound goes muffled under a
  high ringing, and your hearing comes back over half a minute or more (Blast Deafness, can be turned off). Fragments
  splash blood from the vessel's side.
- Cosmetic, as everything else: who lives and dies is still vanilla's TNT. The two agree well: four TNT kill a player
  out to about 4.4 blocks, where the real burst gives about 290 kPa.
- Demo: `/gore demo roadster`.

## [2.14.0]

Burning bodies.

### Added
- Burning Bodies (on by default): set a dead animal alight where it lies (right-click it with a flint and steel or a
  fire charge, or lay it in fire, on a lit campfire or in lava) and it burns the way a carcass really does on an open
  fire. The wool, hair or feathers go up first in a flash and white smoke, and the hide sears black. Once the fat
  catches, it burns on its own, fire or no fire under it: thick, black, greasy smoke, fat dripping and spitting, the
  skin splitting over cracks that glow orange. The heat draws the limbs in (the boxer's pose of every burnt body), the
  flesh burns away, and the body shrinks down to a skeleton with its ribs showing, the bones charred black, then grey,
  then white. Grey ash is left under it.
- It goes by weight: a chicken flares and is bones in about 16 seconds under a wisp of smoke; a sheep burns for most of
  a minute; a cow burns for nearly two, roaring, under a tall column of smoke you can see from far off. Burning Speed
  sets the pace.
- Put out early (water, or rain on a body burning on its own), a body stays as it was: charred, cracked, half-burnt meat
  on blackened bone. Hit it and the char comes off the bone in black crumbs, flakes and soot (poke one still burning and
  sparks fly). Hit the bare bones and they break, the legs first, then the skull, last the ribs and spine, until the
  skeleton falls in on itself and is gone: the way to clear a burnt body away. With the server half it is gone for
  everyone.
- Bones and char do not drag at your legs the way a carcass does: walk through them with a crunch. Burnt bodies do not
  rot.
- Lighting a body is shared with the other players (with the server half), and what is left of a burnt body is shown to
  players who come by later.
- Demo: `/gore demo cremation`.

## [2.13.1]

Clearing a killing floor.

### Fixed
- A kept body stopped blocks being placed where it lay, even after it was punched away (the server still had it where
  it died). Bodies never get in the way of building now: build right on one and it is shoved aside out of the way (up
  on top only if there is no room to the side). Loose pieces in the way are pushed out too.
- Cut-up pieces could be missing when you came back to them: the server sent them a moment before the ground under them
  had loaded, and they were dropped. They now wait for the ground. (This was the butchery test that failed now and
  then.)
- Punching or kicking a kept body only moved it on your own screen. The server moves it now, so it really goes where
  you knocked it, for every player, and stays there. Walking into a body shoves it along too.

### Changed
- Wading Through Bodies (35% by default): every body you walk through drags at your legs and squelches, and a pile of
  them is hard going, while you shove them along.
- An axe clears a carcass in two chops: the first goes in to the bone, the second through the backbone, and the body
  splits apart and is gone. A knife still takes a few.
- Butchering is wetter and more disgusting: every chop into a carcass squelches, and splitting it spills the guts
  out of the belly in a gush of blood that pools where it lay. Sheep throw clumps of bloody, matted wool with every cut.

## [2.13.0]

Chickens die like chickens, and bodies go down stairs.

### Added
- Chicken Deaths (on by default): a chicken dies in a frenzy of wing-beats that bounces it along the ground and spins
  it round, shedding feathers, in bursts that fade. What killed it shapes it: bare hands or a club wring the neck (the
  head hangs loose and swings with every beat), a cut throat lies bleeding a moment before the flapping starts, and a
  head off gives the headless chicken: the hardest, longest flapping, the open neck throwing blood. A blow on a dying
  bird sets its wings going again.
- Feathers: blows knock feathers out of a bird (a burst from a club or a bite, a few from a blade), wet with the blood
  of the wound under them. Plucking, the death flapping and blasts (a cloud of them) throw them too. A dry feather
  sails and rocks as it drifts down; a soaked one clumps into strands, goes dark and drops. They lie where they land,
  drink up any pool of blood or rain, and the dry ones lift and scatter when you walk through them. Max Feathers caps
  them.
- Bloody Feathers: a chicken's plumage soaks up blood like a sheep's fleece, matted round each wound and reddening all
  over as it bleeds, and chickens next to one being cut up get spattered. In the rain or in water the feathers go dark
  and slick, and the bird looks half its size until it dries. A plucked chicken shows how skinny it is under its
  feathers: a narrow body and bony wings.
- Demo: `chickens`.
- Bodies Slide Down (on by default): a body that falls across stairs, a ledge or the lip of a pit no longer hangs
  there like a box. With more than a fifth of it over the drop it slumps over the edge, drops onto the next step with a
  soft thud and a jolt of its limbs, and goes on down until it lies flat, turning a little and smearing blood as it
  drags. Kept bodies are moved by the server, so every player sees them where they end up, and they are saved there.
- Demo: `stairs`.

### Changed
- Plucking throws real feathers (see above) instead of item crumbs.

## [2.12.1]

The server half, hardened.

### Fixed
- A client could clean away any dead creature, not only bodies the server keeps: the Ender Dragon during its death
  (no exit portal, no egg), the Wither, other mods' bosses, even a dead player waiting to respawn. Only kept bodies
  can be cleaned now; everything else dies the vanilla way.
- Kept bodies counted against the mob cap, so a field of slaughtered animals stopped new ones from spawning. They are
  left out of the count now (works alongside Lithium, which changes the same spot).
- A client could store gore pictures on any creature near it (a villager, someone's pet), saved in the world for
  good. Only creatures that player hurt in the last five seconds, or kept bodies, take a picture now.
- Packets with not-a-number positions slipped past every distance check (a client could delete any stored piece).
  Actions and piece moves that are not real numbers are refused, on the server and on the client.
- No limit on how much a client could send: actions, piece moves and evidence uploads now have per-player rate limits,
  far above what the game itself sends.
- A loose piece sent by the server is checked as it is read (real numbers, four-cornered faces, a model of a real
  size) so a spoilt one is dropped instead of crashing the renderer. Pieces that never get a position are forgotten
  after 30 seconds, on the server and the client.

## [2.12.0]

Bodies rot.

### Added
- Bodies Rot (Extreme gore level): a body left lying rots like a real carcass, one Minecraft day for a real day.
  Fresh: pale, stiff with rigor mortis, the first flies. Bloat: swollen green and tight, legs pushed out stiff, the
  skin marbling, gas gurgling in it. Then it bursts with a wet rip and goes into active decay: the skin slips and
  splits, pus and maggots in it, maggots spilling off, a black pool of fluid spreading under it, swarms of flies and
  their buzzing, a stench haze. Then it sinks in, the bones come through, and it dries to bones and hide, bleaching.
- The pace follows forensic degree-days: a day at 20 degrees is a full day of rot, colder is slower, freezing stops it,
  and in water it is half as fast. Small bodies go faster: in the plains a chicken is bones in about ten days, a pig in
  four weeks, a cow in five, a horse in eight. In dry heat (deserts, badlands, savanna, the Nether) bodies dry out into
  leathery mummies instead.
- Severed pieces rot too, from as rotten as the body they came off, at their own size's pace.
- With Fluid Gore on the server (singleplayer, LAN host), a kept body remembers when it died: it goes on rotting while
  nobody is near and while players sleep, and a player who comes along later sees it as rotten as it is. Every player
  with the mod sees the same rot.
- Rot Speed (1x real time, up to 100x).
- Demo: `decay` (six weeks of rot in about a minute, using `/time add`).

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
