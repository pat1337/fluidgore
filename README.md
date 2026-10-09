# Fluid Gore

Client-side cosmetic gore for Minecraft Java **26.3** (Fabric). Blood sprays and sticks to the ground and walls, pools and
flows like a cheap fluid, and bladed kills can cut off heads and limbs that tumble away as physical objects.

Everything is visual and local to your game. The mod adds no blocks, items, entities, packets or registry entries, so it
works on **vanilla servers** and other players don't need it. It never changes health, loot, drops or game rules.

## Install

1. Install Fabric Loader **0.19.5+** for Minecraft 26.3 and **Fabric API** (0.162.0+26.3 tested).
2. Copy `fluidgore-1.0.0.jar` into `%APPDATA%\.minecraft\mods`.
3. Optional: **Mod Menu** (21.0.0) for a config button in the mod list.

Open the settings from **Options → Fluid Gore...** (top-left button) or from Mod Menu. Changes apply immediately and are
saved to `.minecraft\config\fluidgore.json` when the screen closes.

## Settings

| Setting | Default | What it does |
|---|---|---|
| Gore Level | Medium | Off / Low / Medium / High / Extreme. Scales droplet count, puddle size, chance and number of severed parts, and how long effects last. **Off** disables everything and removes existing blood and parts. |
| Affect Players / Mobs / Animals | On | Who bleeds. Mobs = hostile (`Enemy`); Animals = everything else that isn't a player (animals, villagers, ...). |
| Blood Color | Red | Players and passive creatures. Red, Dark Red, Green, Yellow, Blue, Purple, Black. |
| Mob Blood | Red | Hostile mobs, e.g. set Green for alien-looking mob blood. |
| Persistence | 180 s | How long blood stays at Medium before it fades (Low ×0.5, High ×1.5, Extreme ×2). |
| Style | Redstone | **Redstone**: blocky pixel-art splats and chunky drops like redstone dust. **Realistic**: smooth round splats. **Mixed**: random mix. |
| Gore Chunks | On | Heavy hits and kills throw redstone-block crumbs (or a block matching the blood colour) and bits of the body's meat. |
| Arterial Spurts | On | Bad wounds and severed limbs pump blood out in pulsing jets that paint streaks. |
| Gore Sounds | On | Wet crunching on heavy hits, kills and dismemberment. |
| Max Blood Decals | 1500 | Cap on splats + puddle cells. The oldest go first. |
| Wall Splatter | On | Blood sticks to walls and runs down ledges as streaks. |
| Bloody Footprints | On | Anything walking through a puddle leaves a few footprints. |
| Rain Washes Blood | On | Blood in the rain fades much faster. Blood in water always disperses. |
| Fluid Cells/Tick | 64 | Fluid simulation budget per tick. Lower if you have a weak CPU. |
| Max Droplets | 600 | Cap on flying droplets. |
| Part Lifetime | 60 s | How long severed parts stay at Medium (scaled by gore level). |
| Max Severed Parts | 32 | Cap on parts lying around; oldest removed first. |

| Play With Parts | On | Punch severed parts (attack), pick up / throw them (use), kick them by walking into them, stomp them flat by landing on them. |
| Suffering | Medium | Animal Suffering intensity, independent of Gore Level. Off disables it. |
| Scream Volume | 100% | Screams, whimpers and bellows. Also follows the Friendly Creatures volume. |
| Max Screams | 8 | Sounds playing at once; background whimpers/bellows are dropped first. |
| Herd Panic | On | Animals near a slaughter tremble and bellow; more killing nearby = worse panic. |

### Animal Suffering (1.1)

This applies to farm animals: cows, mooshrooms, pigs, sheep, chickens, goats, horses, donkeys, mules, llamas, camels
and rabbits. The list is `sufferingAnimals` in the config.

- **Screams.** Built from each animal's own vanilla sounds, which are pitch-bent, layered and re-triggered into
  screams, whimpers, gurgles and panicked bellows. No new audio files.
- **Every hit.** A scream, plus a flinch with the head thrown around and legs kicking.
- **Badly wounded (under 30% health).** Constant trembling and whimpering.
- **Missing a part.**
  - Legless animals sag, tilt towards the missing leg and stagger.
  - Headless animals shake silently while the neck spurts blood with each heartbeat.
- **Death throes depend on how it died.**
  - **Quick:** one clean heavy blow gives a short spasm and a cut-off cry.
  - **Normal:** a scream, then kicking that dies away.
  - **Brutal:** beaten with bare hands, hit five or more times, or a long fight. A long wail that sags into
    gurgles, and kicking broken by sudden spasms for up to about 20 seconds at Extreme.
  - **Beheaded:** the body keeps running blindly for a few seconds while blood pumps from the neck, then collapses
    and twitches.
- **The body stays after death.** The body stays visible after the server removes the entity until its throes
  are over, then vanishes in the usual puff.
- **Herd panic.** Animals near a slaughter tremble, look around wildly and bellow. Fear builds with every kill nearby.
- **Severed heads.** They twitch and let out a few weak moans.

Everything is client-side: animals still move exactly as the server says. Vanilla animals already run when hit.
Only how they look and sound changes, except for the body after death, which belongs to your client.

You can only change these in `fluidgore.json`:

- `heavyHitDamage` (6.0): a non-lethal hit this strong can cut a part off a mob, which keeps walking without it.
  Players only lose parts when they die.
- `bladedItems`: what counts as a blade. Item ids (`minecraft:iron_sword`) or tags (`#minecraft:swords`). Default: all swords and axes.
- `bloodlessEntities`: entity ids that never bleed (skeletons, golems, armor stands, blazes, ...).

## How it works

- **Damage detection.** Vanilla servers already send a damage event (attacker and source) and the synced health of
  every living entity. The mod pairs the two: the health drop gives the amount and the damage event gives the attacker.
- **Blood.** Droplets fly with gravity and ray-cast against block collision shapes. A droplet that hits a floor leaves
  a splat and adds volume to a puddle. One that hits a wall leaves a splat and sometimes a run.
- **Fluid.** Each puddle is a per-block cell with a volume. Volume flows to lower neighbours by "head" (surface height
  plus depth), so blood runs down steps, spreads and thins on flat ground, and pools in dips. At a ledge it drips off as
  droplets. Only cells that changed are simulated, within a fixed per-tick budget. Puddles darken, shrink and fade as
  they dry.
- **Decals** follow stairs and slabs: they are cut into half-block pieces, each placed on the real surface height. Each
  sits a tiny, unique distance above the surface, which prevents z-fighting.
- **Dismemberment.** On a qualifying sword or axe hit, the part is copied from the entity's own model and drawn with
  the entity's own texture (players use their skin). The copy is simulated as a tumbling rigid body that bounces and
  settles flat. The entity's model hides that part from then on, including during the death animation.
- **Cleanup.** Effects are removed on chunk unload, dimension change or disconnect, when the entity unloads, and when
  you set Off.

## Building from source

Needs a **JDK 25**.

```
gradlew build              # jar in build/libs/fluidgore-1.0.0.jar
gradlew runClient          # dev client
gradlew runClientGameTest  # automated in-game test (singleplayer + dedicated server), screenshots in build/run/clientGameTest/screenshots
```

`tools/GenerateTextures.java` regenerates the procedural textures:
`java tools/GenerateTextures.java src/main/resources/assets/fluidgore`.

## Known limitations

- An item held in a severed hand still renders where the hand was, because held items aren't part of the arm model.
- Mobs with unusual models (slimes, fish, ghasts...) have no matching head, arm or leg parts. They bleed but don't
  lose parts.
- Decals are axis-aligned squares with 8 orientations, not freely rotated, so they never sample outside their texture.
