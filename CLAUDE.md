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
   gore. Every client feature must still work with `GoreSync.serverKeepsBodies()` false.
2. **Cosmetic only.** Never change health, damage, drops, loot, AI or game rules. Exceptions: kept bodies (2.11, a
   dead body stays as an entity on the server and refuses interactions; drops and XP are untouched), and, asked for by the
   user in 2.3, the inventory sorter (`ui/InventorySorter`, `ui/SortButtons`), which moves items with ordinary vanilla
   inventory clicks (`handleContainerInput` PICKUP), exactly what a player could do by hand. No custom packets.
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
  `START_TRACKING` / when a player comes within 64 blocks of a piece; restored only where this client saw nothing. **A new way of acting on a body must go through
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
- `render/GoreRenderer`: draws decals, puddles, droplets, parts and removed corpses via
  `LevelRenderEvents.COLLECT_SUBMITS` and `submitCustomGeometry` / `submitModelPart` / the entity dispatcher.
- `mixin/` (`fluidgore.mixins.json`, all client):
  - `LivingEntityMixin`: damage-event hook and the held-back death "poof".
  - `LivingEntityRendererMixin`: copies gore state into render states, corpse overrides, whole-body motion.
  - `ModelFeatureRendererMixin`: hides severed parts and animates limbs right around `renderToBuffer`, then restores.
  - `MinecraftMixin`: routes attack/use clicks to severed parts.
  - `ClientPacketListenerMixin`: records each explosion's centre and radius.
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
