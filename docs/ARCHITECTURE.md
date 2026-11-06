# Architecture

A map of the code in `src/main/java/dev/fluidgore/`, for anyone changing it. For the rules the code follows, see
[CONTRIBUTING.md](../CONTRIBUTING.md). For what each feature looks like in game, see the [README](../README.md).

## The big picture

One jar holds a client half and a small common (server) half. The Cobble Tank is a separate add-on in the same
repository (`src/tank/`, its own jar), built on the physics and gore described here.

- **Client** (`FluidGoreClient`): detects hits, decides what they look like, simulates blood, pieces and dying bodies,
  and draws all of it. It needs nothing from the server beyond what vanilla already sends.
- **Common** (`FluidGore`): runs only where the server has the mod. It keeps dead bodies, reports hits exactly,
  relays player actions on bodies and pieces, and counts rot. It registers no blocks, items or entities, and it
  never works out gore.

A hit flows through the client like this:

1. **Detect.** `trigger/DamageTracker` pairs vanilla's damage-event packet (who hit whom) with the victim's synced
   health drop. If the server has the mod, `net/HitReport` replaces the guess with the exact damage, weapon and ray.
2. **Seed.** `trigger/Fate.begin` reseeds every named random stream from the victim's UUID, its exact health after
   the hit and the attacker. Every client that sees the same hit rolls the same dice.
3. **Locate.** `wound/ModelHit` casts the blow against the victim's posed model cubes. `wound/Wounds` uses the
   player's own aim, captured at click time, or another player's synced view, or the server's ray.
4. **Apply.** Wounds deepen (`wound/WoundSet`), blood is emitted (`blood/BloodEmitter`), parts may come off
   (`dismember/Dismemberment`), sounds play (`wound/HitFeedback`), and the animal reacts or dies (`suffer/`).
5. **Draw.** `mixin/ModelFeatureRendererMixin` draws wounds, burns and rot inside the entity's own part transforms.
   `render/GoreRenderer` draws everything in the world: decals, puddles, droplets, loose parts and bodies the server
   has already removed.

`GoreSystems` owns all client state, runs it from the client tick, and clears it on a level change, on disconnect, or
when a feature is turned off.

## Configuration (`config/`)
- `GoreConfig`: one Gson JSON file (`config/fluidgore.json`), read live every tick. Every tunable lives here and is
  checked in `sanitize()`. `configVersion` migrates old files when a default changes.
- `GoreLevel`: Off to Extreme, with per-level multipliers (amount, lifetime, wound depth, linger).
- `BloodColor` (colour and chunk block; ender blood is recognised by hue and glows), `BloodStyle` (Redstone, Realistic,
  Mixed).

## Hits and dice (`trigger/`)
- `DamageTracker`: hit detection from health drops plus the damage event. With server reports it ignores both and
  holds each `Report` until the synced health reaches the reported value, then plays it. It also grades explosions:
  `ClientPacketListenerMixin` records each explosion's centre and radius, and the impact uses vanilla's own exposure
  formula (`SHRED_IMPACT`, `TEAR_IMPACT`).
- `Fate`: shared dice. `Fate.stream(name)` is the only allowed source of randomness for anything that changes what a
  hit looks like. `Fate.key()` names pieces made inside a hit's scope, so every client gives a piece the same key.
- `EntityFilter`: who bleeds, the blood colour, which items count as blades, and which bodies are rotten.
- `PressureVessel`: the experimental 2x2 TNT vessel and the shockwave effects (ear and lung injury) shared by every
  explosion. `BlastCloud`: the fireball or gas cloud of a blast, which sears, chills or moves what lies in it.
  `PressureVessel.dead` grades the bodies already lying dead by the same overpressure (shred, tear, throw through
  `Suffering.blasted`): a dead body has no health for `DamageTracker` to see drop.
- `GasBubbles`: a charge under water (`DamageTracker.onExplosion` finds the `Underwater.Site`). Its gas bubble's pulses,
  rebounds, rise and venting through the surface (dome, plume, falling spray), played out from the charge and the water
  alone. Its flow is added to the water's current through `Buoyancy.disturb`, so pieces, ragdolls and blood clouds ride
  it. The blast's `Events` scope carries the site (`Events.water`); `Events.overpressure` and `Events.throwSpeed` pick
  the open-air or the underwater rules, and a scope under water has no fireball or cold cloud.

## Wounds (`wound/`)
- `ModelHit`: raycasts against the posed model cubes. It mirrors `LivingEntityRenderer` plus the mod's ragdoll offsets
  and returns the part, cube, face and point in part-local pixels. `ModelParts.pose(entity)` poses the shared model
  for one entity, using the model that entity was actually drawn with (recorded at submit time; babies and variants
  differ).
- `WoundSet`: one body's wounds in flat arrays (cap 32). Each wound is a spot on a cube face with a radius and a
  severity from 0 to 1 through four layers: bruise, gash (0.25), meat (0.5) and bone (0.75). It also holds the morph
  (round, slash, incised, sticking, cut face), the game time each wound was opened (a bruise darkens with it),
  fracture masks, plucked patches, and up to 24 `Run`s: stretches of blood on one cube face, in part space.
- `Sticking`: a blade or flint on the head of a `QuadrupedModel` goes to the throat instead, on the skull's
  underside at the end against the trunk, the first spot clear of every other cube.
- `Runs`: follows blood from a wound down the posed cubes (`ModelHit.boxes`), keeping to skin within about a pixel
  and dropping off where there is none; undersides shed it and upturned faces hold it. `Wounds` traces a wound a
  second after it opens or deepens (not mid-flinch) and once more when the body has lain for 30 ticks.
- `Wounds`: resolves blows, applies the gore level's depth cap, decides targeted cuts, heals with health, and clots
  after `woundClotSeconds`. Only aimed blows sever precisely; guessed spots keep random severing.
- `WoundRenderer`: draws a wound as a stack of layers from `wounds.png`, in the same part transforms as the model, so
  wounds follow the animation. Rotten flesh adds pus and maggot layers. `WoundCodec` serialises a body's wounds, their ages and
  runs for sync (format 2 in `PieceCodec` and `Evidence`; format 1 still reads).
- `Weapon` (blunt, flint, blade, chop, pick, pierce, bite, blast) and `Flesh` (meat, bone, dried, rotten, poultry,
  pork, beef, mutton, ender) pick the look and sound. `HitFeedback`: per-weapon, per-flesh hit sounds and directional
  blood.
- Fire: `Burns` (living char), `Cremation` + `CremationRenderer` (dead bodies burning down to the bone), `BurntBone` (the
  one rule for bone colour in fire: scorched, charred, grey, calcined), `BodyFlames` (vanilla flames moved onto a lying
  body). `BloodyWool`: blood on coats, per region (head, each leg, body) as a soak (its own wounds, lying in blood),
  a spatter per face (neighbours' blows and blasts, on the faces turned to them, in the model's space) and a wade (how
  high blood has climbed from a pool). A `Kind` says how a coat takes it: a fleece drinks from
  the pool and blood climbs it, feathers less, hide holds a film that water rinses off. `CoatRenderer` draws it on the
  body's model and on a fleece over it, measured from the body's own ground line. A piece cut off takes its region's
  blood with it (`BloodyWool.Piece` on `GorePart.coat`), drawn by `CoatRenderer.drawPiece`. Bodies lying against each other
  smear each other (`BloodyWool.contacts`, every 10 ticks, by `Suffering.footprint`).

## Blood (`blood/`)
- `BloodWorld`: the container for all blood state.
- `BloodEmitter`: turns a hit into droplets, redstone dust, chunks, spurts and crunch sounds. `BoneEmitter` does the
  same for bony mobs.
- `DropletSystem`: ballistic droplets. `BloodFluidSim` + `BloodCell`: per-block puddle cells that flow downhill within
  a per-tick budget. `DecalManager` + `Decal`: splats cut into pieces that follow stairs and slabs. `SurfaceProbe`:
  exact surface heights. `Spurts`: arterial jets. `Footprints`. `Gush`: rate-limited blood sounds.
- `WaterClouds`: blood that reaches water (a drop, a flooded pool, a rinsed tool) becomes a cloud that joins nearby
  clouds, sinks, drifts with the current and thins away. `GoreRenderer` draws each as a few soft puffs.
- `BloodAmount` + `BloodSupply`: how much blood each body holds in litres and how much has come out. Pool volume is
  tied to litres, so a body cannot bleed more than it has.
- `Pallor`: the share of blood a body has lost, scaled by how much bare skin the species shows. On the body's own model
  only (the one `ModelParts.drawn` recorded, not wool or armour): a tint takes the flush out
  (`ModelFeatureRendererMixin.fluidgore$char`) and vanilla's white overlay lifts it toward white (`fluidgore$pallid`).
- `Cleaning`: sponge and brush scrubbing (take up a share of each decal, drag the rest along the stroke), rinsing in
  water, cleaning a body away. `ToolSoak` reads a tool's stain from the item when the server runs the mod, and keeps a
  local one otherwise.
- `BrokenBlocks`: blocks that just lost their collision, so resting gore drops at once.

## Dismemberment and pieces (`dismember/`)
- `Dismemberment`: decides when a part comes off, spawns the piece, tracks each body's missing-part mask, and runs
  explosion shredding (`vaporize`) and tearing (`tear`).
- `GorePart` + `GorePartManager`: severed parts and organs as rigid bodies. Each is keyed by `GorePart.key`; that key is
  how every client finds the same piece. `PartInteraction`: punching, grabbing, throwing, kicking and stomping pieces.
  With a blade it calls `Butchery.cut`.
- `Giblets` + `Tissue`: small flesh, bone and organ scraps in flat arrays (cap `Giblets.CAPACITY`), drawn by
  `render/GibletRenderer` with `flesh.png`, the animal's own skin, or an organ's shape.
- `Butchery`: plucking, jointing, splitting a carcass (the trunk lifts off as a `GorePart`), cutting pieces in half
  along their longest side, a sheep's butcher's joints, and cutting organs along the line of sight.
- `Viscera`: puts a body's anatomy into the world. It places every organ by the body's real pose chain, then throws
  whole organs as `GorePart`s (`OrganModel`, at most 56 cubes) and everything else as shaped giblets.
  `Identify` names the piece you look at.
- `PieceCodec`: serialises a piece's whole model tree, identity and condition for sync and evidence. `ModelParts`
  clones and measures model parts. `BodyPart` maps parts to names. `Feathers`: loose feathers. `ArmorPieces`: armour
  on severed parts.

## Anatomy (`anatomy/`)
- `Anatomy`: the inside of a body in the vanilla model's own pixels. A `Region` is a canonical cube with fat and muscle
  layers under the skin. `Piece`s are bones and organs, and `Kind` gives each one its tile in `anatomy.png` and how it
  cooks. There are human regions (head, torso, arm, leg) and beast regions (head, trunk, foreleg, hindleg) for sheep,
  pigs and cows. A grown beast's trunk cube is stood on end in the vanilla model, so its pieces are pre-turned.
- `SheepTrunk`, `SheepHead` and `CowTrunk` are **generated** organ tables (see `tools/`). The sheep's tables are mixed: half
  pixels, with small organs (heart, great vessels, kidneys; brain, eyes, teeth) recut at quarter pixels inside the cells
  their half-pixel selves filled. `Anatomy.Detail` picks the even grains the `resolution` demo compares; only drawing
  uses them, and organ identity always comes from the mixed table. A sheep's trunk comes as a ewe's, a ram's, a ewe
  lamb's and a ram lamb's, and its head as a grown sheep's and a lamb's. `Anatomy.male(UUID)` picks the sex, so every
  client agrees, and `isBaby()` picks the lamb. Both are carried in `Melt` and used by `Viscera`. `Kind.section()` is
  the tile a blade's cut face shows on a loose organ (the liver, lung, heart, kidney, brain and bone have their own).
  A beast's severed head of one block carries a `HeadSection` (beast, sex, age, its cube). A blade splits it at the
  middle (`Butchery.halve`) into two halves whose `HeadSection.draw` lays the head's anatomy pieces that cross the
  midline over the raw cut face, each in its `section()` tile. `PieceCodec` saves it after the identity block.
  `Anatomy.objects` joins touching cubes of the same organ into one object. `Anatomy.shapes` gives each object an
  immutable shape. `MIDLINE_OWNERS` and `Piece.owner` decide which side a midline or disconnected lobe belongs to,
  by ownership rather than distance.
- `Melting`: burns on living people and beasts (depth from fire time and health lost, healing with health), handed to
  `Cremation` when the body dies. `MeltRenderer`: cuts every face into skin-texel cells with per-cell thresholds,
  seeded by UUID so every screen agrees, and draws the skin, the fat and muscle shells, the walls of holes, drips, and
  the fitted organs and bones inside. It draws in two passes, because a feature renderer can hold only one vertex
  builder at a time.

## Materials and events (`material/`, `event/`)
- `Material`: what a piece is made of (water, fuel, how it ignites and cooks, restitution, brittleness).
  `Condition`: what has happened to one piece (moisture, temperature, cooked, charred, remaining fluid, burning).
  `Exposure` samples the surroundings, and `Thermal.step` is the single heat rule: wet pieces hold near 100 C, cooking
  starts at 55 C, charring at 180 C, and fat burns on its own flame. Everything except temperature and water is
  irreversible. A piece's condition is inherited when it is cut or severed, and synced with it.
- `Events`: source attribution. Every blast, jet or fire has a stable id derived from what it physically is, never from
  dice or the clock, plus a `Source` (charge, compressed gas, environment). Effects are recorded per channel (pressure,
  jet, fragment, collision, thermal) into a bounded trace. **Only a thermal source may sear a piece.**

## Animals, deaths and bodies (`suffer/`, `ragdoll/`, `physics/`)
- `Suffering`: per-animal fear and pain, herd panic, session memory of killing (`ecology`), predators' interest, flies,
  corpses, severed-head moans. `Personality`: voice, nerve and role, from species and UUID. `Bereavement`: mothers and
  their young. `ScreamManager` + `ScreamSound` + `Voice`: pitch-bent, layered vanilla sounds.
- `Corpse`: a dying or dead body the client keeps on screen. `Corpse.random` holds its per-body dice. **Use those, not
  a shared stream, for anything over time.** `Deaths` plans and plays death throes by cause, including chickens.
  `CorpseStrike`: blows on bodies. `SufferPose` + `BodyPose`: animation channels for the renderer. Use `SufferPose.CLOCK`,
  not `ageInTicks`, because removed entities stop ticking.
- `Ragdoll`: the coarse body every system reads (position, yaw, roll, on ground). With Ragdoll Physics on,
  `ragdoll/Rig` (XPBD, 8 substeps, joint limits, block contacts, friction, sleep) drives it through `Rigging`. Each tick
  `Rigging` hands a rig the segments of the rigged bodies near it (`Rig.Block`, oriented boxes), and its points are
  pushed out of them a little each substep, so bodies lie against each other; sleeping bodies skip this. `Skeleton`
  defines body plans and joints, `CubeSplit` splits cubes with the vanilla constructor (so Sodium draws them exactly),
  and `Poser` swaps split parts in for the draw only. `Bends` maps wounds and hits on a bent stretch. Gore data never
  moves: wounds, rot and char stay on the original part and cube.
- `Suffering.footprint`: what a lying body covers of the ground, a capsule along its length. `Suffering.spread` eases
  bodies only this client has out of each other through the old ragdoll's velocity (a rig takes it up as a push);
  the server spreads its kept bodies itself (`KeptBodies.spread`). A rig's leg joints can be driven back to the
  model's standing pose (`Rig.stiffen`): a dead four-legged body on its side lies with its legs straight out.
- `physics/Blast` (common): pressure-vessel physics in plain numbers: expansion energy, TNT equivalent, overpressure
  by distance, thrust, choked flow and the injury thresholds. `physics/GasJet`: how far a gas jet reaches and how hard
  it pushes what it meets, plus the damage-type tags (`fluidgore:gas_jet`, `fluidgore:gas_embolism`) by which an
  add-on's jet damage is recognised.
- `physics/BodySlide` (common): pulls a body hanging over stairs or a ledge towards the drop, on the server for kept
  bodies and on the client for its own. `BodyMass`: weight, and so burn time.
- `physics/Buoyancy` (common): a body in water. How much of a span is under water, the current's pull, and the lift
  from how bloated it is (`RotClock.swell`, common, so the server can use it): below its weight fresh, above it
  bloated. Used by `Ragdoll.move`, by `Rig` per segment (with water drag, and a body asleep in water staying asleep
  while it floats or lies still on the bottom) and by `KeptBodies` for the server's own bodies. Pieces (`GorePartManager`,
  `Giblets`) use it too, sinking or floating by `Material.density` (lung and fat under 1, bone 1.5).
- `physics/Underwater` (common): a charge in water. Cole's shock wave, the surface cutoff, the gas bubble's radius and
  period, the lung-injury impulse, and the venting share. `Underwater.overpressure` gives the open-air overpressure that
  does the same, so the `Blast` bands (eardrums, lungs, torn, shredded) read it unchanged.

## Rot (`rot/`)
- `Decay`: on Extreme with Bodies Rot on, every dead body gets a `RotClock`. **Decay-days are only ever added, never
  recomputed from age.** Each tick adds elapsed days times a degree-day rate where the body lies (biome temperature
  above 20 C, halved in water, zero when freezing) times Rot Speed, scaled by body size. Dry, hot places mummify
  instead, until the body reaches bloat; after that it bursts wherever it is.
- `Rot`: stages (bloat 1.5, active 4.5, advanced 10, dry 20 decay-days) and the render state: tint, swelling, rigor.
  `RotRenderer`: per-face patches seeded by UUID. Severed pieces rot the same way.
- With the mod on the server, `server/KeptBodies` keeps the authoritative count, which is saved with the body and sent
  as `net/BodyRot`. Without the server, each client counts from the body's death time.

## The player (`player/`)
`PlayerInjuries`: the local player's own hurt (screen splats, hit flash, heartbeat, stagger, deafness).
`HurtScreen`: the HUD layer. `Tinnitus`: ringing ears. `Injury` + `InjuryPose`: limp, hanging arm and held belly on any
humanoid. `GameRendererMixin` adds stagger and the limp hitch to the camera.

## Networking and the server half (`net/`, `server/`)
- `GoreAction` (STRIKE, PLUCK, CUT, CLEAN, SCRUB, IGNITE, WASH, VAPORIZED): a player's action on a body, sent as what
  they did (eye, look, weapon, damage). `GoreSync.act` performs it locally and sends it. `perform` reseeds `Fate` from
  the action and the body's UUID, so every client gets the same result.
- `PieceSync` + `PieceState`: the client of the player who moved a piece streams its pose until it settles.
  `Evidence` + `EvidenceRecord` + `server/EvidenceStore`: what was done to a body or piece, uploaded by the actor and
  sent to players who arrive later. It is restored only where the client saw nothing itself.
- `HitReport`: the server's account of each landed hit (`server/HitReports`, from `LivingEntityHurtMixin`).
- `server/KeptBodies`: holds dead bodies (from `LivingEntityDeathMixin`), up to Most Kept Bodies per dimension. It
  refuses interactions with them, relays actions to players within 96 blocks, shoves, spreads and unsticks bodies, and
  counts rot. `NaturalSpawnerMixin` leaves kept bodies out of the mob cap.
- `server/BloodyTools`: stains on cleaning tools. `server/BurningBodies`: vanilla fire from burning bodies.
- **Every packet is hostile.** Check `finite()`, distance, ownership and kept status, and rate-limit with
  `server/Throttle` (per-player token buckets for actions, poses and evidence bytes). Manual CLEAN needs a cleaning
  tool in the server's copy of the main hand at ordinary reach. VAPORIZED is the separate automatic report and never
  stains a tool.

## Pressure-vessel bursts (`fragment/`, `net/VesselBursts`)
- `fragment/`: `FragmentTrace` traces at most 256 seeded debris paths against body boxes and solid blocks on the server.
  The client projects contacts onto the drawn anatomy (`FragmentEffects`) and draws chips (`render/FragmentRenderer`).
  See [FRAGMENT_CONTACTS.md](FRAGMENT_CONTACTS.md).
- `net/VesselBurstPayload` is a burst reported by the server (wave, fragment paths); `net/VesselBursts` plays it
  through `trigger/PressureVessel`, so a burst looks the same whatever made it. Add-ons can listen before the gore.

## The Cobble Tank add-on (`src/tank/`, mod id `cobbletank`)
- `CobbleTank` registers the block, item, block entity, flying entity and the jet, embolism and impact damage types;
  `TankConfig` is its `config/cobbletank.json`. `TankState` holds the fill, the gas and shell temperatures, casing
  damage and cracks, and integrates choked and subcritical outflow, blowdown cooling and heat exchange. `TankBlock` /
  `TankBlockEntity` / `TankEntity` cover the block, its ticking and the flying tank. `Jet` hurts bodies in a valve jet.
  `TankBurst` is the rupture: the real damage, glass and doors, and the `VesselBurstPayload` it sends.
- `client/TankClient`: the renderer, leak sounds and hearing loss, and clearing the block when its burst arrives.
  `TankSettings` and `TankDemos` add its section to the settings screen and its demos to `/gore demo`.
- Fluid Gore never refers to these classes. Details and limits: [TANK_OVERHAUL.md](TANK_OVERHAUL.md).

## Rendering and mixins (`render/`, `mixin/`)
- `GoreRenderer` collects world geometry through `LevelRenderEvents.COLLECT_SUBMITS`. `GibletRenderer`,
  `FeatherRenderer` and `FragmentRenderer` draw their particles.
- Mixins (`fluidgore.mixins.json`; common: death, hurt, spawner; the rest client):
  - `ModelFeatureRendererMixin`: around `renderToBuffer`, it hides severed parts, poses limbs, and draws wounds, burns,
    melt and rot.
  - `LivingEntityRendererMixin`: copies gore state into render states, corpse overrides, whole-body motion.
  - `LivingEntityMixin`: the damage-event hook and the held-back death "poof".
  - `MinecraftMixin`: routes attack and use clicks to pieces and bodies.
  - `ClientPacketListenerMixin`: explosion centre and radius.
  - `ClientLevelMixin`: blocks losing collision.
  - `SoundEngineMixin`: blast deafness.
  - `GameRendererMixin`: camera stagger and limp.
  - Accessors and invokers for model parts, sounds and screens.

## UI and demos (`ui/`, `demo/`)
- `GoreSettingsScreen` is built from vanilla `OptionInstance`s. Add-ons add sections with `addSection`. `OptionsScreenButton` puts "Fluid Gore..." on the
  Options screen, and `ModMenuIntegration` adds the Mod Menu button. `GoreCommand`: `/gore demo`.
  `SortButtons` + `InventorySorter`: sorting with ordinary inventory clicks.
- `Demos.all()` lists the in-game demos: Fluid Gore's own, then those add-ons registered with `Demos.add`. A `Script` is a series of steps (vanilla commands sent as the player, client
  actions, narration, pauses), placed with `Stage`. `DemoPlayer` runs them and can fake the held item for the gore.

## Assets
- `textures/misc/blood.png`: a full 4x4 atlas of 64 px tiles (smooth 0-9, pixel-art 10-15), tinted by vertex colour.
  Adding a tile means growing the grid in `BloodTextures` and the generator.
- `wounds.png`: 8x21 tiles of 16 px, eight variants per row (round layers, slash layers, pus, maggots, plucked skin,
  crushed bone, stove-in ribs, cut-meat grain, marbling, fat rim, blood smear, runs of blood, eight nested levels of
  blood soaked into a coat, blood wicked up a coat to a ragged line). The row count is
  `WoundRenderer.TILE_V`, shared by the rot and cremation renderers.
- `anatomy.png`: 8x7 tiles of 16 px in full colour, in `Anatomy.Kind` order. Tiles 54-55 are free.
- All of them come from `tools/GenerateTextures.java` with fixed seeds. `tools/texture-manifest.txt` records them so
  `VerifyTextures` can show nothing changed by accident.
- `art/` holds Blockbench models of the sheep and cow anatomy written by the anatomy tools, for reviewing the art.

## Tests
`src/gametest/java/dev/fluidgore/test/FluidGoreClientTest.java` is a Fabric client game test. It plays through the mod
in singleplayer and then against a dedicated server: kills, wounds, severing, deaths, kept bodies, rot, fire,
explosions, the Cobble Tank add-on, the settings screens and every demo. It loads both mods. Each feature has a named test that can be run
on its own (see CONTRIBUTING.md).
