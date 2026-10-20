# Fluid Gore

Fabric mod for **Minecraft Java 26.3**, client-side gore with an optional server half (2.11): blood, a blood fluid simulation, dismemberment, and
"Animal Suffering" (screams, death throes, herd panic). Mod id `fluidgore`, MIT. Current version: see
`gradle.properties` (`version=`). Released versions are git tags (`v1.1.0`, `v1.2.0`, ...) with jars in
`releases/` (git-ignored).

Easy follow-up work is listed in **TASKS.md**. Do those one at a time.

## Hard rules
1. **Client-side first.** No registries (blocks, items, entities, particle types, sounds). Use vanilla sound events and
   particle types only. It must keep working on a vanilla server and when the server lacks the mod. The one exception,
   asked for by the user in 2.11 ("a server side mod so i can see my brother chopping up the bits of dead cow"): the
   server half, `server/KeptBodies` (common code, `main` entrypoint `FluidGore`, mixin `LivingEntityDeathMixin`) and
   its one packet `net/GoreAction`. Keep it that small: the server keeps bodies and relays actions; it never works out
   gore. Every client feature must still work with `GoreSync.serverKeepsBodies()` false. 3.0 starts moving decisions to
   the server when it has the mod (the user's choice, 2026-10-19: e4mc LAN now, a dedicated server with other mods
   later): `server/HitReports` (mixin `LivingEntityHurtMixin`) reports each landed hit as `net/HitReport`. The server
   only says what it knows (damage, weapon stack, ray, seed); the client still makes the gore, and must keep working
   from health drops when `GoreSync.serverReportsHits()` is false. Server work must stay cheap and capped. The second exception, asked
   for by the user in 2.16 ("It's a real block, so Fluid Gore has to be on the server and on every player who joins.
   Real damage applies for this block only"): the Cobble Tank (`fluidgore:cobble_tank`), `tank/` (block, block entity, flying-tank
   entity, item, recipe, three damage types, packet `net/TankBurstPayload`). Nothing else may register content.
2. **Cosmetic only.** Never change health, damage, drops, loot, AI or game rules. Exceptions: kept bodies (2.11, a
   dead body stays as an entity on the server and refuses interactions; drops and XP are untouched), and, asked for by the
   user in 2.3, the inventory sorter (`ui/InventorySorter`, `ui/SortButtons`), which moves items with ordinary vanilla
   inventory clicks (`handleContainerInput` PICKUP), exactly what a player could do by hand. No custom packets.
   And the tank block (2.16), whose burst, jet and flight do real damage, break glass, open doors and freeze; nothing
   else it touches is changed. And bloody tools (3.1, asked for by the user: "bloody sponge ... the brush gets bloody
   and needs washed"): the server writes `custom_model_data` (float 0 soak, colour 0 blood) on the held cleaning item
   and turns a sponge washed clean into a wet sponge (`server/BloodyTools`); nothing else about items changes.
3. **26.3 is not 1.21.** Names are Mojang's unobfuscated names and many APIs changed (render states,
   `SubmitNodeCollector`, `LevelRenderEvents`, `GuiGraphicsExtractor`, `Identifier` instead of `ResourceLocation`,
   `client.gui.setScreen`). Check the real source before guessing. See "Looking up APIs".
4. Every tunable goes in `config/GoreConfig.java`, sanitized in `sanitize()`, and exposed in the settings screen
   if it is a user setting. Add `en_us.json` keys for every new option, including a `.tooltip` key.
5. No TODOs or stubs in delivered code. Build and run the game test before calling something done.
6. **Every release that adds something you can see adds a demo** to `demo/Demos.ALL` (newest first, tagged with
   the version): the user replays them in game with `/gore demo <name>` to show their brother. Base it on the
   game-test scene for the feature, placed with `Stage` (right/up/forward from the player). Only tag-scoped
   commands: never `kill @e` without `tag=fluidgore_demo`, never `item replace` (use `Script.hold`). Terrain changes
   mean `destructive = true`. The `demos` game test plays every demo, so a new one is tested for free.

## Environment (this PC)
- JDK 25: `C:\Users\Thele\.jdks\jdk-25.0.4.1+1`. The system `java` is 21 and will not build this.
  Bash: `export JAVA_HOME="/c/Users/Thele/.jdks/jdk-25.0.4.1+1"`.
- No Python on this machine. Use the JDK for scripts (`java SomeFile.java`).
- Port 25565 is taken by something else on this PC, so the game test's dedicated server uses 25599.
- The user's game: `%APPDATA%\.minecraft\mods` holds the installed jar plus Fabric API 0.162.0, Mod Menu 21.0.0,
  Sodium 0.9.3-alpha.1, Iris 1.11.7, Lithium, FerriteCore, Entity Culling and ImmediatelyFast. When installing a
  new version, delete the old `fluidgore-*.jar` there first (two copies of the same mod id crash the game).

## Commands
```
./gradlew build                 # jar -> build/libs/fluidgore-<version>.jar
./gradlew runClient             # dev client
./gradlew runClientGameTest     # automated in-game test; screenshots in build/run/clientGameTest/screenshots/
./gradlew runClientGameTest --no-configuration-cache -PextraMods=<dir of jars>   # same, with extra mods loaded (compat check)
java tools/GenerateTextures.java src/main/resources/assets/fluidgore             # regenerate blood.png + icon.png
```
The game test (`src/gametest/java/dev/fluidgore/test/FluidGoreClientTest.java`) must pass (exit 0, `[FluidGoreTest]`
lines in the log, no `AssertionError`). Look at the screenshots for visual changes.

## Versions
Fabric Loader 0.19.5, Fabric API 0.162.0+26.3, Loom 1.18-SNAPSHOT (resolves 1.18.3), Gradle 9.7.1, Java 25,
Mod Menu 21.0.0 (compile-only, soft dependency).

## Architecture (`src/main/java/dev/fluidgore/`)
- `FluidGore` (common `main` entrypoint): loads the config, registers the server half. `server/KeptBodies` (2.11):
  `keep` (called from `LivingEntityDeathMixin` at `tickDeath` HEAD) holds bodies of `sufferingAnimals` instead of
  vanilla's removal, up to `maxKeptBodies` per dimension; `UseEntityCallback` refuses
  interactions with the dead; it relays `GoreAction`s to players within 96 blocks and removes a body on CLEAN.
- `net/`: `GoreAction` (the payload: STRIKE/PLUCK/CUT/CLEAN/SCRUB, eye + look floats, weapon, damage; `seed()`),
  `GoreSync` (client: `act` = `perform` here + send, `perform` reseeds `Fate` from the action and the body's UUID,
  `tick` asks the server to take bodies that are vaporized). `PieceSync` + `PieceState` (2.11): the game whose player moved a
  piece (`PieceSync.moved`: punch, kick, carry, throw, stomp, or made inside a local action/hit, `GoreSync.local`)
  streams its pose until it settles; others `apply` it. `Evidence` + `EvidenceRecord` + `server/EvidenceStore`: the
  actor uploads a picture of a creature (`WoundCodec`, missing mask, char, plucks, chops; entity attachment `GORE`,
  persistent) and of each moved piece (`dismember/PieceCodec`: the whole model tree, polygons exactly); sent on
  `START_TRACKING` / when a player comes within 64 blocks of a piece; restored only where this client saw nothing.
  `HitReport` (3.0, clientbound; also registered serverbound with a no-op receiver so `GoreSync.serverReportsHits()`
  can tell a reporting server from an older one): sent from `LivingEntityHurtMixin` (`hurtServer` HEAD health,
  RETURN if it landed; Fabric's AFTER_DAMAGE skips lethal hits) to `PlayerLookup.tracking` + the victim. Seed is
  `Fate.seed(victim, healthAfter, cause)`, the same formula as the guessed path. `DamageTracker` with reports ignores
  damage events and health drops, holds each `Report` until the synced health reaches `healthAfter` (or
  `REPORT_WAIT` ticks), then plays it with `Fate.begin(seed)`, the server's weapon (unless a demo fakes the held item)
  and its ray (`Wounds.onDamage`: own click aim, own view, server ray with nudges, others' synced view, guess). **A new way of acting on a body must go through
  `GoreSync.act`, and a piece must be found by `GorePart.key`** (named by `Fate.key()` in `GorePartManager.add`).
- `FluidGoreClient`: entrypoint; registers events. `GoreSystems`: owns all state, runs it from the client tick,
  and clears it on level change, disconnect, or when a feature is set to Off.
- `config/`: `GoreConfig` (Gson JSON at `config/fluidgore.json`, read live every tick), `GoreLevel` (Off..Extreme
  multipliers), `BloodColor` (rgb + chunk block), `BloodStyle` (Redstone/Realistic/Mixed).
- `ui/`: `GoreSettingsScreen` (vanilla `OptionsSubScreen` + `OptionInstance`), `ModMenuIntegration`,
  `OptionsScreenButton` (the "Fluid Gore..." button on the Options screen), `GoreCommand` (`/gore demo`, a Fabric
  client command). `SortButtons` + `InventorySorter` (2.3): 1.7.10-style sort buttons on chest/barrel/ender chest/shulker
  screens and the player inventory, middle-click (pick-block key) to sort the hovered part; merge then order by item id,
  hotbar untouched, done with vanilla clicks. `mixin/AbstractContainerScreenAccessor` gives the screen position.
- `demo/`: `Demos` (the list), `Demo`, `Script` (steps: vanilla commands sent as the player, client actions,
  narration, pauses), `Stage`, `DemoPlayer` (runs from the client tick, fakes the held item for the gore via
  `DemoPlayer.held`, hides command replies from chat). Needs cheats/op; nothing server-side.
- `trigger/`: `DamageTracker` finds damage from synced health drops plus the damage-event packet (attacker), with
  no server mod needed. `EntityFilter` decides who bleeds, the blood colour, and which items count as bladed.
  `Fate` (2.0) makes multiplayer agree: `DamageTracker` calls `Fate.begin(victim, attacker)` around each hit, which
  reseeds every `Fate.stream(name)` from victim UUID + exact health after the hit + attacker UUID. **Any new random
  choice that changes what a hit looks like must use a `Fate.stream`, not `RandomSource.create()`.** Things a body
  does over time use `Corpse.random` (`Fate.body`), never a shared stream, since per-tick draws differ between clients.
- `blood/`:
  - `BloodWorld` is the container.
  - `BloodEmitter` turns a hit into random wounds, droplets, redstone dust, chunks, spurts and crunch sounds.
  - `DropletSystem` handles ballistic droplets.
  - `BloodFluidSim` + `BloodCell` are the cellular puddle flow.
  - `DecalManager` + `Decal` are splats split into pieces that follow stairs and slabs.
  - `Spurts` are arterial jets. `Footprints`.
  - `Cleaning` (sponge/brush, `cleaningItems`; 3.1 bloody tools): `onUse` washes a bloody tool near water
    (`GoreAction.WASH`, `BloodyTools.nearWater`), cleans a body away (CLEAN, damage 1 = by hand), or scrubs a stroke
    (direction from the last scrub point within `SCRUB_GRACE`). `scrub` = `BloodWorld.scrub` (takes up the share
    `lift = 1 - soak` of each decal/cell, drags the rest along the stroke as `smear` STREAK decals) + `Giblets.squash`
    (resting pieces -> dark gut smears) + a soaked tool's own smear past `DEPOSIT`. `ToolSoak` reads the stack's stain
    when the server runs the mod, else keeps a local one per item kind. The item look: `assets/minecraft/items/
    {sponge,wet_sponge,brush}.json` range-dispatch on `custom_model_data` 0 to `fluidgore:item/*_blood_N` (sponge
    stains in `textures/block`: a block model may only use the block atlas; brush stains in `textures/item`), tinted by
    colour 0.
  - `SurfaceProbe` gives exact surface heights. `BloodTextures` holds the atlas tiles.
- `dismember/`:
  - `Dismemberment` decides when to cut, spawns parts, and tracks missing-part masks.
  - `GorePart` + `GorePartManager` are severed-part rigid bodies.
  - `Giblets` + `Tissue`: small flesh pieces (flat arrays, cap `Giblets.CAPACITY`), drawn by
    `render/GibletRenderer` with `flesh.png` or the animal's own skin for hide. `vaporize` (shred) and `tear` live
    in `Dismemberment`; `DamageTracker` grades blasts by vanilla's impact formula with the radius recorded by
    `ClientPacketListenerMixin` (`SHRED_IMPACT`, `TEAR_IMPACT`).
  - `ModelParts` clones and measures model parts.
  - `PartInteraction` lets you punch, grab, throw, kick and stomp parts; with a blade it calls `Butchery.cut`.
  - `Butchery` (2.4): plucking dead chickens (`onUse`, `Wounds.pluck` -> `WoundSet.PLUCK` skin patches, row 11 of
    wounds.png, and a whole-body tint by plucked fraction combined into `Burns.KEY`), splitting a carcass
    (`Dismemberment.carcass` lifts the trunk off as a `GorePart`, severs what is left, marks the body vaporized) and
    dicing (`cut` halves a single-cube part across its longest side, new `ModelPart.Cube` with the texture origin read
    back from the cube's UVs, `Wounds.cutFaces` for the raw face). Torso cuts: `Wounds.Strike.cut` is true for a
    torso blow through a wound already at its cap. Corpse-only, so local to the player who does it.
  - `BodyPart` maps parts to model-part names.
- `suffer/`:
  - `Suffering` handles per-animal fear and pain, herd panic, corpses and severed-head moans.
  - `Corpse.vanilla` (2.11, `deathThroes` off, the default): `Suffering.vanillaTip` rolls the body exactly like
    vanilla's tip-over, `Deaths.tick` only pumps blood (`heart`), red for `VANILLA_TICKS`. `Corpse.held`/`kept()`: the
    server keeps the body, so it is `done` only when the entity is removed (cleaned, or out of range); a removal within
    `KEEP_GRACE` ticks means the server did not keep it after all. `Suffering.adopt` makes a lying corpse for a kept body
    seen already dead; `Suffering.clean` is the sponge.
  - `Corpse` keeps a body on screen after server removal while it dies. `Deaths` (2.1) chooses the cause
    (`Corpse.Kind`: CONCUSSION, EXSANGUINATION, SPINAL, DECAPITATION, CHEST, TRAUMA, BURN, BEATEN), `plan`s the death
    from `Corpse.random` (tonic/clonic/gasps/fasciculation/heart, ground ticks, 60-100 total) and plays it in `tick`.
    Every body drops within `collapse()` ticks; nothing happens upright. Pose channels for this: `SufferPose.hindLimp`,
    `legSeed` (per-leg kick strength/phase), `gaspAt`/`gasp` (chest heave + head lift, shaped at render time) and
    `SufferPose.CLOCK` (corpse age + partial tick; removed entities stop ticking, so never use `ageInTicks` for them).
  - `ScreamManager` + `ScreamSound` are pitch-bent, layered vanilla sounds. `Voice` is the entity's own sounds.
  - `SufferPose` holds animation amplitudes.
- `wound/` (the hack model, 1.3):
  - `ModelHit` raycasts against the real posed model cubes (mirrors `LivingEntityRenderer` plus our ragdoll
    offset/lean) and returns part, cube, face and the point in part-local pixels. Also finds stump spots and part
    centres. `ModelParts.pose(entity)` poses the shared model for one entity (including ragdoll joints).
  - `WoundSet` is one body's wounds (flat arrays, cap 32): spot on a cube face, radius, severity 0..1 through four
    layers (bruise, gash 0.25, meat 0.5, bone 0.75). `Wounds` owns them, resolves the player's swing at click time
    (the damage packet arrives later, after knockback has moved the legs), applies the gore-level depth cap and
    decides targeted cuts. Other players' melee swings are cast along their synced view (`alongView`), so they land
    on the same spot on every screen. Only aimed blows sever precisely; guessed spots (mobs, projectiles) keep random severing.
  - `WoundRenderer` draws from `ModelFeatureRendererMixin` right after `renderToBuffer`, in the same part
    transforms (`entityCutoutZOffset`, `wounds.png`), as a stack of `Layer`s (row, opens-at severity, size, `Flesh.Tissue`):
    `ROUND` or `SLASH` per wound (`WoundSet.morph`; `Weapon.slashes()`), with PUS and MAGGOTS slotted in before the
    bone for `Flesh.rots()`. A slash's `variant % 4` is its angle (`WoundSet.slashVariant`: level along the face).
    `Weapon` (blunt/flint/blade/chop/pick/pierce/bite/blast), `Flesh` (meat/bone/dried/rotten; `Flesh.rotten(type)` +
    `rottenUndead`; `EntityFilter.isRotten` gives `BloodColor.ROTTEN`, half the bleed, no spurts).
- `tank/` (2.16, common unless noted): the Cobble Tank (`fluidgore:cobble_tank`; FACING = its top, placed on the clicked face; SCARS 0-3 multipart overlay from `cobble_tank_scars_N.png`; the jet is a vector in the block entity, from the blow's direction; `TankEntity` is a rigid body in SI units with a tilted nozzle for torque; `Scrape` = blow sounds and particles; no chat report for `Vessel.real()`). `Blast` is the physics shared with the client
  (`trigger/PressureVessel` delegates to it): Brode energy, Mills overpressure x1.8 surface burst, `reach`, `damage`
  (nothing below `EARDRUM_KPA`, log share of max health, 1000 from `FATAL_KPA`), leak thrust/bleed/temperature/loudness.
  `TankState` (litres, psi, `fraction`, `damage`, crack `area`; `blow` by `force(stack)`: THROUGH 0.5 pinhole, CRACK
  0.75 cracked fitting, SPLIT 1 burst; `bleed` + crack running). `Tanks` registers everything (`main` entrypoint).
  `TankBlock` (LIT, FACING = top, LEAK, SCARS; unmineable while pressurized, `attack` = a blow; light like TNT;
  `onExplosionHit` bursts it later; sneak-use picks up an unharmed tank). `TankBlockEntity` (fuse, leak, tears loose
  into `TankEntity` when the thrust lifts it or beats static friction (0.6) on its weight less any lift, unless a sturdy block braces it; update packets every 10 ticks while leaking). `TankEntity` (synced
  rotation quaternion, crack vector, pressure, area; thrust, tumble, smash weak blocks, impact damage by kinetic energy,
  settles back into a block). `Jet` (injection/sandblast/throw/gale by distance scaled with sqrt of jet power, damage
  types `fluidgore:jet`/`embolism`, `chill`, `frost`). `TankBurst`: sends `TankBurstPayload` (centre, litres, psi,
  fragment hits per body) to players within 320, then a block-keeping vanilla explosion with a `Calculator` for the
  real damage, `shatter` (glass/doors via section `maybeHas`), `chain` (nearby tanks go too), `later` (queued, ticked
  on `END_LEVEL_TICK`). `TankFx` particles + the `leaks` hook. Client (`tank/client`): `TankRenderer` (moving block
  turned by the quaternion), `TankClient` (payload -> `PressureVessel.told`, then `DamageTracker.onExplosion` if the
  player is too far for the explosion packet; looping `LeakSound`; `PlayerInjuries.noise` dose for hearing).
- `render/GoreRenderer`: draws decals, puddles, droplets, parts and removed corpses via
  `LevelRenderEvents.COLLECT_SUBMITS` and `submitCustomGeometry` / `submitModelPart` / the entity dispatcher.
- `mixin/` (`fluidgore.mixins.json`, all client):
  - `LivingEntityMixin`: damage-event hook and the held-back death "poof".
  - `LivingEntityRendererMixin`: copies gore state into render states, corpse overrides, whole-body motion.
  - `ModelFeatureRendererMixin`: hides severed parts and animates limbs right around `renderToBuffer`, then restores.
  - `MinecraftMixin`: routes attack/use clicks to severed parts.
  - `ClientPacketListenerMixin`: records each explosion's centre and radius.
  - `SoundEngineMixin`: blast deafness (`PlayerInjuries.hearing`) scales `calculateVolume(float, SoundSource)`.
  - `ClientLevelMixin`: records blocks that lose their collision in `blood/BrokenBlocks`, so resting gore drops at once.
  - Accessors and invokers for `ModelPart` and entity sounds.

## Textures
`assets/fluidgore/textures/misc/blood.png` is a 4x4 atlas of 64px tiles, white/grey with alpha, tinted by vertex
colour. Tiles 0-9 are smooth (splats 0-3, puddle 4, pool 5, droplet 6, footprint 7, streak 8, small splat 9) and
10-15 are pixel-art "redstone" (splats 10-13, puddle 14, droplet 15). **The atlas is full.** Adding a tile means
growing the grid (e.g. 8x4) and updating `BloodTextures.GRID` math and the generator. `wounds.png` is separate: an
8x18 grid of 16px greyscale tiles, eight variants per row: rows 0-3 round (bruise, gash, meat, bone), 4-8 slash (margin,
fat, split, muscle, bone; column % 4 = angle), 9 pus, 10 maggots, 11 plucked skin, 12 crushed bone, 13 stove-in ribs, 14-17 cut-meat grain, marbling, fat rim, blood smear. All PNGs come from
`tools/GenerateTextures.java` (deterministic seeds). Never hand-edit the PNG.

## Looking up APIs
The decompiled 26.3 Minecraft source is produced by `./gradlew genSources` and lives in
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.3/*-sources.jar`. Extract it to a scratch
folder and grep it. For Fabric API sources, download `<module>-<version>-sources.jar` from maven.fabricmc.net
(module versions are listed in the fabric-api 0.162.0+26.3 POM).

## Not verified yet
Iris with a shader pack enabled; a real multiplayer server with other players (skins, anti-cheat plugins hiding
health). Known limits: a held item still renders where a severed hand was; slimes, fish and ghasts have no
severable parts; severed heads can't blink.

## Version history
- **1.0.0**: blood spray, decals on floors and walls, fluid sim, footprints, rain and water washing, dismemberment
  with physical parts, settings screen + Mod Menu, client game test.
- **1.1.0**: Animal Suffering (screams from distorted vanilla sounds, flinch, tremble, limp, herd panic), death
  throes based on kill method, bodies outliving server removal, headless run, severed heads twitch and moan, and
  punching, grabbing, throwing, kicking and stomping parts.
- **1.2.0**: Redstone pixel-art blood style (default), redstone-dust particles, gore chunks (redstone-block crumbs
  + meat items), arterial spurts, log-normal random wound severity, satellite splats, colour variation, crunch
  sounds. Inspired by the block-crumble particles and break sounds in the user's Clay mod
  (`C:\Users\Thele\ClaySoldiersMod`).
- **1.3.0**: bone bodies (skeletons chip and shatter), hitting bodies after death, death launch, per-animal
  personalities, scrubbing blood, and the hack model (Slaughterhouse stage 1): blows land on the exact spot of the
  model and open wounds that deepen through bruise, gash, meat and bone; targeted severing, stumps, dripping wounds.
- **1.4.0**: weapon/flesh hit sounds and directional blood (`wound/HitFeedback`), wounds and cut faces on severed
  pieces, ragdoll `heft` from body size, and the baby/variant fix: `ModelParts.pose` uses the model each entity was
  really drawn with (recorded in `LivingEntityRendererMixin` at `submit` HEAD), never `renderer.getModel()` alone.
  Public docs: README, CHANGELOG, `docs/images`, CI in `.github/workflows/build.yml`.
- **1.5.0**: `suffer/Bereavement`: mother and young (nearest grown/young animal of the same kind within 24 blocks).
  Calf cries and the mother answers (`ScreamManager.call` with delay), dying calves `weakCall`, survivors `grieve`
  for `griefSeconds`, pose turned to where it happened. Game test: `grief`.
- **1.6.0**: explosions graded by impact (shred / tear open / throw), giblets, new weapons (flint, pick, bite,
  blast), internal bleeding from beatings, and in-game demos (`/gore demo`). Game tests: `weapons`, `pigPit`, `demos`.
- **1.7.0**: vanilla-feel deaths (`redDeath`: red until gone, faster keel-over, `Suffering.slam` landing), Terraria-style
  knockback on dying bodies (`CorpseStrike`, `Corpse.flash`/`keepFor`, walking shoves them), `Dismemberment.pop` at
  the end (`deathPop`), shorter High/Extreme lifetimes, `GoreConfig.configVersion` migration, and the floating-gore
  fix (`BrokenBlocks`). Game test: `floorAndDeath`. Demos: `death`, `floor`.
- **1.8.0**: `wound/Burns` (charring, smoke, scorch marks; burnt -> BRUTAL), evidence linger on High/Extreme
  (`GoreLevel.linger`, `bloodDuration`), and nine realistic deaths in `suffer/Deaths` (STUNNED, BLEED_OUT, BOLT,
  DOWNER, BROKEN_BACK, CHOKING, CURL, BRUTAL, CIRCLING): `Deaths.choose` weighs them by weapon, hit zone, fall and
  personality; `Deaths.upright` plays the part before the body goes over (leg folding via `SufferPose.foldFront/foldHind`,
  `stiff`, `headTilt`; `ModelParts.legs` measures how far a body sinks), `Deaths.ground` adds to the shared throes,
  `Deaths.struck` answers blows. The death pop is now a `deathPopPercent` roll (5%); those bodies keep the old
  `classic` choice. `Suffering.forceNextDeath` for tests and demos. `Ragdoll.rollLimit` = PI lets a body end on its
  back. Game test: `deaths`. Demo: `deaths`.
- **1.9.0**: `blood/BloodAmount` (per-mob blood measure, `bloodAmounts` override), `blood/Gush` (phased, rate-limited
  death/blast/ooze blood sounds, global `GAIN`), screams fade to silence, softer burn sizzle, corpses hide vanilla
  flames and burn with flame particles in `Burns`.
- **1.9.1**: `ScreamManager.pain/death` retuned (natural pitch, at most 3 death cries 14+ ticks apart, repeats capped
  at 2) to stop the stuttering click on animal hits and kills.
- **1.9.2**: `Suffering.slam` plays the hard fall crunch 1 in 4 (else a dull mud/wool thud); `Deaths` back-down and
  `Giblets.thud` sounds are gated to 1 in 4.
- **1.9.3**: other players' swings land where they looked (`Wounds.alongView`, synced view ray with small nudges)
  instead of a random guessed spot, so a head hit shows on the head for everyone with the mod.
- **2.0.0**: multiplayer agreement with no server mod: `trigger/Fate` shared dice (see `trigger/`), `Corpse.random`
  per-body throes dice, seeded death pop, `woundPlayers` on by default (`configVersion` 3). Game test: `sharedFate`
  (same fight twice on a fixed-UUID cow must match). `deaths` test now samples the broken back at 70 ticks.
- **2.1.0**: deaths overhauled (see `suffer/` above): the nine upright/running deaths and the headless run are gone;
  every body drops at once and dies on the ground in 3-5 s, shaped by eight causes. Corpse render clock fixes frozen
  kicking after server removal. Game test `deaths` rewritten (one cow per cause, sampled every tick). Demo: `deaths`.
- **2.2.0**: anatomical slashes (incised/chop wounds along the swing, five tissue layers) and Rotten Undead (rotten
  flesh tints, pus/maggot layers, falling maggots, `BloodColor.ROTTEN`, slow ooze, no spurts, rotten giblets).
  `woundHostiles` on by default (`configVersion` 4). Game test: `rot`. Demo: `rot`.
- **2.3.0**: 1.7.10-style inventory and chest sorter (`inventorySorting`, see `ui/`). Game test: `sorting` (checks the
  server's copy of the chest). No demo: it is not gore and needs no scene.
- **2.5.0**: butchery (`dismember/Butchery`): plucking, jointing, splitting, dicing. Game test: `butchery`. Demo:
  `butcher`. Re-indented blocks earlier text-block patches left one tab short (Java text blocks strip common
  indentation when the closing `"""` shares a line with content: close them on their own line at column 0).

- **2.5.0 horse/bone additions**: horse head includes head_parts, trunk attachments detach separately for splitting; horse death/blood tuning; Wounds bone thresholds and WoundSet fracture masks with render-time pose deformation. Regression: horsesAndFractures. Demo: horse. Includes unreleased butchery.
- **2.6.0**: butchery for big animals: `Butchery.hackApart` splits multi-cube pieces into single-cube ones (child offsets baked, child rotation dropped), `GorePart.rawFaces` bitmask carried through every cut (`Wounds.cutFaces(..., rawFaces, depth)`), `Flesh.POULTRY` (chicken: pink), `CorpseStrike.BUTCHER_SHARE` (a blade on a corpse cuts at least 60% of max health deep). Game test: `horsesAndFractures` dices horse/donkey/mule. Demo: `dice`.
- **2.7.0**: `Flesh.PORK` / `Flesh.BEEF`, `WoundSet.CUT` morph (cut faces span the whole face; rows 14-17 of wounds.png: grain, marbling, fat rim, blood smear, grid now 8x18), `wound/BloodyWool` (per-sheep soak 0..1 from `HitFeedback.hit`, herd spatter capped at 0.35, `BloodyWool.KEY` tint on `SheepFurModel` in `ModelFeatureRendererMixin`, `WoundRenderer.drawStains` on the fleece), `bloodyWool` setting, `maxDetachedParts` 160 (`configVersion` 5). Game test: `meatsAndWool`. Demo: `meats`.
- **2.8.0**: `Flesh.ENDER` + `Flesh.ender(type)`, `BloodColor.ENDER` (forced for endermen/endermites in `EntityFilter.bloodType`), portal sparks + crystal sounds in `HitFeedback`, `GorePart.ender()` (by blood colour) and `GorePartManager.blink` (hops every 2-7 s for `ENDER_RESTLESS` ticks, fizzing and hopping fast when wet). `partLifetimeSeconds` 600 (`configVersion` 6, max 3600). Game test: `endermen`. Demo: `ender`.
- **2.9.0**: `BloodColor.glows(rgb)` recognises ender blood by hue ratio (variation only scales brightness; mixing with other blood loses it), `GoreRenderer.glow` and `WoundRenderer.glows` use `LightCoordsUtil.FULL_BRIGHT`; `glowingEnderBlood` setting. Demo: `glow`.
- **2.11.0**: the server half (see `server/`, `net/`), the vanilla death by default (`deathThroes`), kept bodies
  (`keepBodies`, `maxKeptBodies`), shared butchery, sponge on a body. Game test: `keptBodies` (the rest of the suite runs
  with `deathThroes` on and `keepBodies` off, as it was written). Demo: `kept`.
- **2.10.0**: `player/` package. `PlayerInjuries` (owned by `GoreSystems.injuries()`): the local player's own hurt,
  fed from the top of `DamageTracker.hurt` (cause STRUCK/BLAST/FALL/OTHER from the pending hit). It holds screen
  `Splat`s, the hit flash, the heartbeat (`WARDEN_HEARTBEAT`, below `Injury.WEAK_BELOW` of health) and the stagger;
  `fakeHealth` is for demos. `HurtScreen` is a Fabric `HudElement` after `MISC_OVERLAYS` that draws `hurt.png`
  (192x64: dithered vignette, 2x2 splats, 4 drips; from the generator). `GameRendererMixin` adds the stagger/sway at
  `bobHurt` TAIL and the limp hitch at `bobView` TAIL. `Injury` (render-state key, from `WoundSet` + health, on any
  living body that is not a corpse) drives `InjuryPose` on every `HumanoidModel` in `ModelFeatureRendererMixin`, plus
  the limp lean in `LivingEntityRendererMixin.setupRotations`. `WoundRenderer.drawStains(..., clothes, ...)` puts
  stains on a `PlayerModel`'s outer layer (hat/jacket/sleeves/pants) and on a player's armour. Also: wounds heal with
  health (`Wounds.heal`), clot (`WoundSet.quiet`, `woundClotSeconds`), `ArmorPieces` on severed parts, brutal PvP, and
  blast gore on players. Game tests: `playerGore`, `playerInjuries`. Demos: `injured`, `heal`, `armor`.
- **2.12.0**: `rot/` package. `Decay` (owned by `GoreSystems.decay()`, active on Extreme + `bodyDecay`): every dead
  living entity in the level gets a `Body` whose age is `max(gameTime, overworld clock)` since death (from
  `entity.deathTime` when seen here, or the server's `net/BodyAge`, sent on `START_TRACKING` from the persistent
  `KeptBodies.DIED` attachment stamped when a body is first kept). `Rot.days` = real days x degree-day rate (biome base
  temperature `25t - 5` C over 20 C, halved in water, 0 when freezing) x `decaySpeed` / sqrt(volume / pig volume). Stages
  in `Rot` (BLOAT 1.5, ACTIVE 4.5, ADVANCED 10, DRY 20 decay-days; `arid` = no precipitation and over 20 C:
  mummifies). `Rot.KEY` render state: tint folded into `Burns.KEY`, trunk swell + rigor legs/head in
  `ModelFeatureRendererMixin.fluidgore$rot`, `RotRenderer` patches (one per cube face, seeded by UUID, wound tiles rows
  0/1/2/3/9/10) drawn under the wounds on the drawn body model and on `GorePart`s (`GorePart.rot`/`arid`, inherited in
  `Dismemberment.inheritRot` and `Butchery.piece`, aged in `Decay.ageParts`). Effects: flies (dust), `BEE_LOOP` buzz,
  maggots, ooze, stench, the burst at ACTIVE, the fluid pool decal (`STAIN`, re-placed if the decal cap took it).
  Game test: `decay`. Demo: `decay`.
- **2.12.1**: server half hardened. **Treat every packet as hostile.** `KeptBodies.isKept(entity)` (in the `BODIES` set)
  gates CLEAN (never a vanilla-dying body: dragon/wither deaths do things at their end) and is left out of the mob cap by
  `mixin/NaturalSpawnerMixin` (common; `@ModifyVariable` on the stored entity list, because Lithium already
  `@Redirect`s `getAllEntities` there). Dead players are refused for every action. `GoreAction.finite()` /
  `PieceState.finite()` (NaN passes `>` distance checks) on server and client. `server/Throttle` per-player token
  buckets (actions 64/20 s, poses 400/200 s, evidence 1 MB / 256 KB s). Entity evidence only for
  `getLastHurtByPlayer() == sender` or kept bodies. `EvidenceStore.cut` needs the cutter near the piece; unplaced pieces
  expire after 600 ticks (server) / `Evidence.WAIT` (client). `PieceCodec.read` checks numbers, quads only, depth 16,
  256 parts, 64 cubes; `WoundCodec.read` drops absurd wounds (ox/oy/oz may be NaN: "no spot"). Game test: `hardening`.
  No demo: nothing new to see.
- **2.13.0**: bodies slide down stairs and off ledges (`bodySlide`). `physics/BodySlide.pull` (common) samples a 4x4 grid
  under the collision box; over `HOLDS` (a fifth) hanging, it pulls towards the drop. The server adds it to a kept
  body's motion in `KeptBodies.keep` (vanilla `travel` still runs for the dead, so gravity, friction and position sync
  come free); `Suffering.slide` adds it to the `Ragdoll` of bodies only this client has, and for a kept body eases the
  ragdoll onto the entity while the server moves it. `Corpse.drop` gives a thud (`bump`) per step. Game test: `stairs`.
  Demo: `stairs` (`Stage.back()` for a stair's facing).
- **2.13.0 chickens**: `dismember/Feathers` (flat arrays, cap `maxFeathers`, owned by `Dismemberment`; `tuft` from
  `HitFeedback.hit`, `Butchery.pluck`, `Dismemberment.featherCloud` on shred/tear, `Deaths.bird`), drawn by
  `render/FeatherRenderer` from `feather.png` (64x32 from the generator: dry row, clumped row). `BloodyWool` covers
  chickens too (`soaks(entity)`, `featherTint` folded into `Burns.KEY`, `wet` per bird) and sets `BloodyWool.PLUMAGE`
  (plucked, wet), which `ModelFeatureRendererMixin.fluidgore$skinny` turns into a thin `AdultChickenModel` (the part save
  now keeps x/y/z too); chickens get `drawStains` under their wounds. `Corpse.bird` (`chickenDeaths`) + `Deaths.planBird`
  / `bird` (run after both the vanilla and the throes paths): `SufferPose.flap/flapRate/neckLimp`, animated in
  `fluidgore$flap` (wings are the "front legs" of the kick loop, skipped for birds); `Corpse.wrung` = killed by BLUNT.
  `Corpse.done` waits for `flapEnd`. The rest of the game test runs with `chickenDeaths` off. Game test: `chickens`. Demo:
  `chickens`.
- **2.13.1**: dead bodies have `blocksBuilding = false` (server in `KeptBodies.keep`, client in `Suffering.tickCorpse`);
  `KeptBodies.unstick` lifts a body out of a block placed into it, `shoved` pushes it from players walking into it
  (`getKnownMovement`), `knock` applies a STRIKE's knockback on the server (mirrors `CorpseStrike`). `Suffering.slide`
  follows the server's moves of any kept body once toppled. An axe on a lifeless body cuts with its whole health and
  `chopThrough(..., axe)` splits on the first chop through the bone; `split` spills giblets, gushes and squelches;
  `Feathers.tuft` also throws sheep's wool (`plumage` = dye colour). `unstick` shoves sideways first (8 directions, onto
  ground), then up; `GorePartManager.pushOut` does the same for pieces. `Suffering.shove` slows the local player by
  `wadeSlowPercent` per body. `Evidence.waitForGround`: a server-sent piece whose chunk is not loaded on the client waits
  (it used to be dropped at once by `GorePartManager.step`: the old flaky "diced piece was not there" test). Game test:
  `clearingBodies`. No demo (`kept` shows it).
- **2.14.0**: `wound/Cremation` (owned by `Dismemberment.cremation()`, ticked after `Burns`): per-corpse `Fire` (burn,
  crumble, glow, kindle, blows). Heat from the corpse box (`CorpseStrike.box`, now public): lava 2, fire/lit campfire or
  the entity's synced fire flag 1; `GoreAction.IGNITE` (flint and steel / fire charge via `MinecraftMixin` use routing,
  `GoreSync.perform`) sets `kindle`. It burns on its own past `CATCHES`; water puts it out, rain too unless it lies in
  lava. Pace `1 / (20 * SECONDS * cbrt(kg) * 100 / cremationSpeedPercent)` from the `KILOGRAMS` table (babies x0.15);
  flames, smoke (white, then `LARGE_SMOKE`, cosy-smoke column) and sounds scale with `cbrt(kg)`. It raises `Burns` char
  (Burns skips its own particles for these bodies). `Cremation.KEY` render state (`Charred`: bare, burn, glow, seed):
  `ModelFeatureRendererMixin.fluidgore$cremate` (pugilistic flex, shrink to the bone, sheep fur hidden),
  `CremationRenderer` patches (crust row 6, full-bright embers, meat row 2, bone row 3 coloured by calcination, ribs row
  13 on the torso), chickens singed via `BloodyWool.Plumage`. `Cremation.strike` (called first in `CorpseStrike.strike`):
  past `CHARRED` blows add `crumble` (char crumbs, seared giblets); past `BONE` they break `BONES` in order via the
  missing mask, then `hide` (vaporized, so `GoreSync.tick` CLEANs it). `Suffering.shove` does not slow on burnt bodies,
  `Decay` skips them, `Evidence` appends burn/crumble/blows. Game test: `cremation`. Demo: `cremation` (destructive: water).
- **2.15.0**: `trigger/PressureVessel` (owned by `DamageTracker.vessels()`, ticked first in `DamageTracker.tick`): a
  `PrimedTnt` seen for the first time that is one corner of a 2x2 square of TNT (any plane; the others still blocks or
  lit) arms a `Vessel` at the square's centre. The first explosion within 3 blocks bursts it (`burst`: crack, fog,
  carbon shreds, then per living body `Fate.begin(body, null)`, Poisson `fragments` -> `Wounds.strike` PIERCE, ear drips
  over `EARDRUM_KPA`, mouth spray over `LUNG_KPA`, and `PlayerInjuries.burst` for the local player); later ones within
  `AFTERSHOCK_TICKS` are the same burst. Every `Blast` of a vessel is centred on it and graded by `overpressure`
  (Brode energy of `vesselLitres` at `vesselPsi`, x1.8 surface burst, Mills 1987 curve, `0.35 + 0.65 seen`) mapped
  through `impact` so `TEAR_KPA`/`SHRED_KPA` land on `TEAR_IMPACT`/`SHRED_IMPACT`. `PlayerInjuries.burst`: chat report
  (`fluidgore.vessel.*`), stagger, deafness (`hearing`, `SoundEngineMixin`) and `Tinnitus` (looping amethyst resonate at
  pitch 2 on MASTER, which deafness skips). `Wounds.mouth` is public. Game test: `pressureVessel`. Demo: `roadster`.
- **2.16.0**: the Cobble Tank (`tank/`, see Architecture and the rule 1 and 2 exceptions). Config `tankLitres`,
  `tankPsi`, `tankBreaksGlass`. `PressureVessel.Vessel` carries `psi` and the server's fragment `hits` (`real()`; the
  burst sends no chat report); `PressureVessel.told`; debris `clatter` after every
  burst. `PlayerInjuries.noise(dB)`. Game test: `tankBlock`. Demo: `tank`.
- **2.17.0**: Ragdoll Physics polish (still `articulatedRagdoll` off by default). `Suffering`: guided fall leans until
  `ON_SIDE` (|roll| 1.4, up to `GUIDED_FALL * 3` ticks, harder once on the ground); quadrupeds (`Skeleton.Plan` not BIPED)
  on their back past `ON_BACK` roll onto the nearer side for `RIGHTING_TICKS`; `drag` (rig bodies: step `bump`s for
  drops under 1.5 blocks, `dragTrails` swipe decals for `TRAIL_TICKS`); `slam` bursts radially past `HARD_FALL`.
  `Rigging`: `lean` tops spin up to a rate about the ground contact (never accumulates), `topple` pivots at the feet,
  spasm hops under `HOP` are not passed to a grounded rig, `follow` drops a kept rig to the server's body past `DROP`,
  `slide` feels for the edge at the lowest point and at the trunk (`PROPPED`), runs while asleep, tops speed up to
  `SLIDE` x the pull. Landing strength capped 0.8 (was 0.35). `BloodWorld.landOnFloor(..., vx, vy, vz, ...)`:
  `directionalSpatter` streaks below `SLANT`. Game test: `drops` (cliff onto slabs); `-PragdollOn` runs the picked
  tests with Ragdoll Physics on. No demo (the user waived it for this release).
- **3.0.0**: server-reported hits, step 1 of the server-authority plan (see rule 1 and `net/` above): `HitReport`,
  `server/HitReports`, `mixin/LivingEntityHurtMixin`, `Fate.seed`. Ownerless projectiles (dispensers) are PIERCE and
  wound along their flight. Game test: `hitReports`. Demo: `arrows`. Next steps of the plan: persistence of blood and
  pieces with the world, server-owned ragdolls for kept bodies (capped for the tick budget), then optional gameplay
  consequences behind a server setting (off by default).
- **3.1.0**: bloody tools (see `blood/Cleaning` and rule 2): `server/BloodyTools` (common: soak/colour on the stack,
  `absorb` from SCRUB's reported litres, `wipedBody` on CLEAN by hand, `wash`), `blood/ToolSoak`, `BloodWorld.scrub`
  (replaces `clean`) + `smear`, `Giblets.squash`, `GoreAction.WASH`. Textures from the generator (`spongeBlood`,
  `brushBlood`). Game test: `bloodyTools`. Demo: `sponge`. `-PonlyDemos=a,b` limits the `demos` test.
