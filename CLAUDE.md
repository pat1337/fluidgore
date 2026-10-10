# Fluid Gore

Client-side-only Fabric mod for **Minecraft Java 26.3**: blood, a blood fluid simulation, dismemberment, and
"Animal Suffering" (screams, death throes, herd panic). Mod id `fluidgore`, MIT. Current version: see
`gradle.properties` (`version=`). Released versions are git tags (`v1.1.0`, `v1.2.0`, ...) with jars in
`releases/` (git-ignored).

Easy follow-up work is listed in **TASKS.md**. Do those one at a time.

## Hard rules
1. **Client-side only.** No registries (blocks, items, entities, particle types, sounds), no packets, no
   server-side code. It must keep working on a vanilla server. Use vanilla sound events and particle types only.
2. **Cosmetic only.** Never change health, damage, drops, loot, AI or game rules.
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
- `FluidGoreClient`: entrypoint; registers events. `GoreSystems`: owns all state, runs it from the client tick,
  and clears it on level change, disconnect, or when a feature is set to Off.
- `config/`: `GoreConfig` (Gson JSON at `config/fluidgore.json`, read live every tick), `GoreLevel` (Off..Extreme
  multipliers), `BloodColor` (rgb + chunk block), `BloodStyle` (Redstone/Realistic/Mixed).
- `ui/`: `GoreSettingsScreen` (vanilla `OptionsSubScreen` + `OptionInstance`), `ModMenuIntegration`,
  `OptionsScreenButton` (the "Fluid Gore..." button on the Options screen), `GoreCommand` (`/gore demo`, a Fabric
  client command).
- `demo/`: `Demos` (the list), `Demo`, `Script` (steps: vanilla commands sent as the player, client actions,
  narration, pauses), `Stage`, `DemoPlayer` (runs from the client tick, fakes the held item for the gore via
  `DemoPlayer.held`, hides command replies from chat). Needs cheats/op; nothing server-side.
- `trigger/`: `DamageTracker` finds damage from synced health drops plus the damage-event packet (attacker), with
  no server mod needed. `EntityFilter` decides who bleeds, the blood colour, and which items count as bladed.
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
  - `PartInteraction` lets you punch, grab, throw, kick and stomp parts.
  - `BodyPart` maps parts to model-part names.
- `suffer/`:
  - `Suffering` handles per-animal fear and pain, herd panic, corpses and severed-head moans.
  - `Corpse` keeps a body on screen after server removal for its death throes; `Deaths` picks and plays the nine realistic
    deaths (see its javadoc and `Corpse`).
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
    transforms (`entityCutoutZOffset`, `wounds.png`). `Weapon` (blunt/flint/blade/chop/pick/pierce/bite/blast), `Flesh` (meat/bone/dried).
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
growing the grid (e.g. 8x4) and updating `BloodTextures.GRID` math and the generator. `wounds.png` is separate: a
4x4 grid of 16px pixel-art tiles, one row per wound layer (bruise, gash, meat, bone), four variants. All PNGs come from
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
