# Next task: Ragdoll, grabbing, wet blood, anatomy (2.17 onward)

Asked for by the user on 2026-10-17, to be built in slices on top of 2.16. Each slice ships on its own with a game test
and, if it shows something new, a `/gore demo`. Order: A1 -> A2 -> A3 -> B1 -> B2, then the rest.

## Track A: articulated ragdoll (everything else hangs off it)
| # | Slice | Done when |
|---|---|---|
| A1 | Segment skeletons: each model split at real joints (knee/hock, chest/hips, a virtual neck) per body plan (quadruped, biped, bird, "thirds" fallback for modded mobs). Debug overlay. A body drawn from segments looks the same as today. | Round trip old pose -> segments -> drawn pose matches; debug view shows sensible joints on cow, sheep, pig, chicken, horse, zombie/player. |
| A2 | Solver: XPBD rigid bodies, 8 substeps a tick, gravity, lifeless joints with anatomical limits, block collision, sleeping. Behind `articulatedRagdoll`, replacing the old ragdoll for corpses. | Bodies come to rest in a flat room, legs fold the right way; tick time flat with 50 bodies. |
| A3 | Stairs, ledges, walls; no tunnelling (a segment moves at most half its thickness per substep). The body box follows the ragdoll. | Stairs test: no segment inside a block on any tick. |
| A4 | Bodies hit bodies and severed pieces. | 10 bodies in a pit: no overlaps, all asleep. |
| A5 | Per-species joint tables and weights. | Side-by-side drop demo. |

## Track B: grabbing (GMod / Skyrim)
| # | Slice | Done when |
|---|---|---|
| B1 | Grab the exact point aimed at, spring to in front of the face, scroll for distance, a key to rotate, release to throw; weight decides lift or drag. | Grab a lamb by the leg: the head ends up lowest. |
| B2 | Multiplayer: the grabbing player's game owns the body and streams it while it moves; first grab wins, a second grab is a tug. | Two games agree within a small tolerance. |
| B3 | Severed pieces (and organs later) in the same physics as one-segment bodies. | Old punch/kick/throw tests pass. |

## Track C: wet (Dexter-style blood)
C1 prints (wetness per segment/piece, used up per print), C2 drag trails and drips from the lowest point, C3 slick vs
absorbent surfaces and fresh/tacky/dry stages, pieces soak puddles up, C4 the brush pushes blood and pieces around.

## Track D: anatomy
D1 organ/bone tables per species (debug X-ray), D2 organs come out with their own fluids (liver ooze, pink lung froth,
green cud, brown-green gut, yellow bladder, grey-pink brain, marrow), D3 intestines as a draping chain, D4 modelled
bones broken by weapon (blunt shatters, sword nicks, axe splits), D5 fractures add a floppy joint mid-bone, D6 face slab.

## Track E: polish
E1 weapon-by-layer tuning, E2 water (currents, floating lungs/fat, sinking liver/bone), E3 rigor mortis on the joints.

## Design (decided while building A1)
- Gore data never moves: wounds, rot, char and hits stay on the original part and cube. A split is a render-time and
  pose-time idea: `ragdoll/Bends` maps a point past a cut into that segment's frame. The vanilla draw sees the split for
  the length of `renderToBuffer` only (`ragdoll/Poser` swaps the part's cube list and children, then puts them back).
- Split cubes are built with the vanilla `ModelPart.Cube` constructor, one face group per texture offset, so the skin is
  exact under vanilla and under Sodium (which draws cubes from the constructor's arguments, not from the polygons).
- A rigged corpse is drawn with `bodyRot` 180 and no roll, so model space is world space turned half a turn about z,
  scaled by the entity scale and offset by `BodyPose`; each bone part is then posed from its body's world transform.
- `suffer/Ragdoll` stays as the coarse body every other system reads (position, yaw, roll, on ground); the rig writes it
  every tick, and pushes other code gives the old ragdoll (velocity, spasms) are passed on to the rig.

## Asked for on 2026-10-17 (after A1/A2)
- The new physics is a toggle against the original ("Ragdoll Physics"). The trunk is never cut in two (the user: "the
  torso doesnt look good at all splitting into two chunks"); by default every segment is a whole vanilla part, so a
  body looks like its vanilla model, only limp. Knees/hocks/elbows are a separate toggle ("Ragdolls Bend at the
  Knee", off).
- **Next slice (H1): human ragdolls.** Zombies (and their kin) and players get the articulated body when they die,
  not only the suffering animals: today only `sufferingAnimals` get a `Corpse`, so a zombie or player has no rig.
- **H2: a human-looking explosion.** A blast on a zombie or player throws limbs as ragdolls, and the anatomy shows:
  skull, muscle and brain as their own pieces, blood in real litres (`BloodAmount`/`BloodSupply` already measure it),
  bones that break and burst. Builds on D1 (anatomy tables), D2 (organs with their own fluids) and D4 (modelled bones).

## Progress
- **A1, A2 and H1 done (2026-10-17, in the 2.16.0 jar, `articulatedRagdoll` off by default).** New package `ragdoll/`:
  `CubeSplit` (exact splits built with the vanilla constructor, Sodium-safe), `Skeleton` (body plans, joints and
  anatomical limits; `of(model, knees)`; the trunk is never cut), `RigPose` (capture, model frames), `Poser` (poses any
  model sharing the state; swaps cut parts in for `renderToBuffer` only; guarded Euler conversion, a quarter turn about
  y used to give NaN), `Bends` (decals and hits follow a bent stretch; wired into `WoundRenderer`, `RotRenderer`,
  `CremationRenderer`, `ModelHit`), `Rig` (XPBD, 8 substeps, joints and contacts interleaved twice, velocity-level
  Coulomb friction, rolling resistance, settle damping then sleep), `Rigging` (glue: the old `Ragdoll` is written from
  the rig and its pushes/kicks go to it; `topple` buckles every hinge its own way; kept bodies leashed 0.35 to the
  server's position; `slide` uses `BodySlide` on the trunk for bodies only this client has). `Suffering`: guided fall
  onto `fallSide` for `GUIDED_FALL` ticks; `limp`/`limps`: anything drawn with a `HumanoidModel` (zombies, skeletons,
  other players, not this player) gets a plain corpse with a rig when Ragdoll Physics is on.
- Game test `ragdoll` (both modes plus 50 kept sheep: 4-7 ms a tick settling, 0.01 ms asleep). Run alone with
  `./gradlew runClientGameTest -PonlyTests=ragdoll` (`onlyTests` also takes stairs, keptBodies, clearingBodies,
  deaths, hackModel, butchery, cremation, decay, chickens, floorAndDeath, demos, burntAndEvidence). The full suite
  passes with the ragdoll off except one dice-dependent failure seen once ("the beaten pig did not die beaten:
  DECAPITATION"); that test passed twice on its own.
- **Known, to do next:**
  1. With Ragdoll Physics **on**, these old tests still fail: `stairs` (a body only this client has stays on the top
     step: the `BodySlide` pull is not enough), `keptBodies` (expects the sword kill to roll over away from the
     blade by 8 ticks), `deaths` (expects a death-throes body on its side within 30 ticks). `clearingBodies` passes.
  2. Horse with knees: lower legs land about 3.5 cm off in the round trip (the horse model is scaled 1.1 at the root);
     the knee offset is divided by that scale and it still is off. The test allows it for that case.
  3. A demo for the ragdoll (`/gore demo`, rule 6) and the screenshots in `docs/images`.
  4. Pig and sheep often end on their bellies with legs folded rather than on a side (short legs, wide trunk).
- **2.17.0 done (2026-10-17)**: belly-landing, sliding/skating, stairs (kept and local), cliffs and slabs fixed
  with Ragdoll Physics on (`stairs`, `keptBodies`, `deaths`, `drops` pass with `-PragdollOn`); directional spatter and
  drag trails. Still open: known items 2 and 3 above; A4 bodies hit bodies.
- Then: H2 (above), A3 rest (ledges, walls), A4 bodies hit bodies, B1 grabbing, B2 multiplayer ownership, then
  tracks C, D and E.

---

# Earlier: "The Slaughterhouse" (stages 1-7, background)

Read CLAUDE.md and TASKS.md first. Hard rules there still apply: client-side only, cosmetic only (never touch health,
damage, drops, AI, game rules), vanilla sounds/particles only, every tunable in `GoreConfig` + settings screen + lang
keys, build and `runClientGameTest` must pass, look at screenshots. This task *is* allowed to touch mixins and
`GoreRenderer`; it is the "bigger session" TASKS.md refers to.

## Vision
Fluid Gore is a statement about the brutality of extremes: what it actually costs an animal to die. The player should
feel like they are in a butcher's cold room in New York: heavy bodies, hanging carcasses, wet impacts, the weight of
flesh and bone. Physics must feel like RDR2: limbs have mass, drag, fold, dangle and swing. Code quality bar: small,
data-oriented, allocation-free in hot paths, fixed timestep, no cleverness that needs a comment to excuse it. Think
"Linus would merge it".

## What exists (do not rewrite, build on it)
`suffer/Ragdoll.java` is ONE rigid body (point mass, yaw, roll pendulum) with damped spring joints for head/limbs,
stepped at 20 Hz and interpolated; `Corpse` owns it; `BodyPose` carries it to the renderer; `ModelFeatureRendererMixin`
applies joint offsets and hides severed parts; `dismember/` has `GorePart` severed rigid bodies and `PartInteraction`
(punch/grab/throw/kick/stomp).

## Requirements (decided with the user)

### 1. Hack model: per-chunk hit zones + wound layers
- Raycast each attack against the entity model's real geometry, not the entity AABB, and resolve it to a **zone**
  (e.g. neck, shoulder, ribs, belly, haunch, upper leg, lower leg, hoof, jaw, skull, spine). Zones are defined per
  model family (quadruped, biped, bird, skeleton, etc.) in data, not hard-coded per mob.
- Each zone has its own hit points (cosmetic, client-only). Damage escalates visibly through **wound layers**:
  bruise, gash, exposed meat, exposed bone, severed. Wounds are decals painted onto the model (UV-space or
  model-space quads clipped to the cube faces), they must follow the animation and survive across frames.
- Severing is not only at joints: a limb can be cut into segments (upper/lower leg, jaw, tail). Every severed chunk
  becomes a physics chunk that can itself be hacked into smaller chunks, down to a minimum size. Cap total chunks and
  fade/despawn oldest ones (config), keep frame time flat under 200+ chunks.
- Each hit gives matching feedback: sound per weapon type and zone (axe on bone, sword on meat, fist on ribs), blood
  that sprays along the blade direction and hit normal, and an impulse into the ragdoll at the exact hit point.

### 2. Full articulated ragdoll
- Replace the single-body-plus-springs `Ragdoll` with an articulated one: every model part is its own rigid body,
  joined by constraints with angle limits (hinge for knees/elbows, cone for hips/shoulders/neck). Verlet or
  sequential-impulse; pick the simpler one that is stable at 20 Hz with substeps, and justify it in a short comment.
- Each part collides with blocks (box vs voxel shapes) so legs rest on the floor and do not clip. Friction, rolling
  resistance, restitution small. Bodies drape over slabs and stairs, hang from ledges, and settle to sleep (stop
  simulating) when still.
- A half-severed limb dangles from its remaining flesh: the joint becomes a stretchy tether with a break threshold,
  and can tear off from a hard swing or the body being dragged.
- Hanging and carried bodies: the player can grab a body or chunk (extend `PartInteraction`), hold it, hang it from a
  block above (hook point), and hack it while it swings. Held bodies drag and swing from the grab point.
- Blocks the vanilla renderer's `deathTime` tip-over entirely for ragdolled bodies (already done for corpses).
- Headless-run, spasms, throes timing from `Corpse.Kind` must still work, driven through the ragdoll (motor torques
  on joints) instead of fake sine paddling.

### 3. What is hackable
- Corpses after death (keep them as long as the config allows, with no hit limit).
- **Live animals mid-fight**: wounds appear on the living animal as it is hit. Purely visual; real health/AI untouched.
- Hanging / carried bodies (above).
- Hostile mobs and other players too, behind a separate opt-in toggle, **off by default**, plus the existing
  `EntityFilter` rules. Other players: client-side visuals only, never alters anything on the server.
- **Per-species flesh and fluid** (data-driven table: entity type -> material):
  - Skeletons: no blood. Realistic bone: hits crack, chip and crumble bone into shards and dust; ribs and skull
    break apart; bone chunks are brittle (shatter on impact). Dry rattling sounds.
  - Desert/dried zombies (husk, drowned excluded): dusty, sickly **poison-potion** coloured ooze, not red blood:
    puffs of dust mixed with a dribbling toxic liquid, dried-out flesh that tears rather than splashes.
  - Ordinary livestock and others: red blood and meat as now.
  - Make the material a small enum (blood colour/style, flesh item/particle, bone behaviour, hit sounds, dust).

### 4. Butcher's-cold-room atmosphere
- Weighty, wet audio, layered from vanilla sounds with pitch/volume variation (extend `ScreamManager`/`ScreamSound`).
- Hanging carcass behaviour, dripping onto the floor below, puddle growth, steam from fresh meat in cold biomes.
- Everything with mass: slow, heavy settling; no floaty, light motion. Tune masses per species size.

### 5. Personal screams, shaking, and per-animal personality
- Every animal gets a persistent personality from its entity id/UUID seed: voice pitch range, how loud and long it
  screams, how quickly it panics, how much it shakes, stoic vs frantic. Two cows must not scream identically.
- Species traits (examples, make a data table): pig = loud, sustained, human-like shrieks; cow = low bellow, calls for
  nearby herd; sheep = bleats that crack and go silent, flock bunches together; chicken = flapping and sudden
  silence; horse = rearing and panicked whinny.
- Shaking and flinching are driven through the ragdoll/pose as motor input, scaled by personality and wound
  location (a gut wound curls the body, a leg wound favours it).

### 6. The philosophy layer: escalating consequences, ecologically sound
- No lectures, no text lectures. The world responds, with weight.
- Animals remember (per session, client-side only): witnesses flee earlier, stay traumatised, keep distance from the
  player, show fear posture (cosmetic pose/ears/head down). More killing -> the herd's baseline fear stays raised.
- "Sentience made visible": recognisable pain responses via pose and sound only. Protecting a wound, collapsing
  slowly, last laboured breaths, a mother calling for her young (use baby/adult relationships already visible
  client-side), survivors standing near a body.
- **Ecologically sound** means reactions follow real roles: prey species flee and bunch, herd animals call to the herd,
  predators (wolves, etc.) show alert/interested posture towards fresh blood rather than panic, scavengers' cue is
  carrion (flies via particles, ambient sound) that grows with the number of bodies and time, and bodies decay
  visually over a long period (config). Nothing here changes real AI; it is all pose, sound and particles.

## Settings and safety
- Master intensity already exists (`goreLevel`, `sufferingLevel`); add toggles for: hack system, live-animal wounds,
  hostile/player targets (default off), chunk cap, decay time, personality on/off, ecological reactions.
- A global content switch that disables all of this at once, and keeps vanilla-server compatibility.
- Performance budget: measure with the game test (log frame time or sim time per tick); must stay flat with the chunk
  cap reached.

## Progress
- **Stage 1 done (1.3.0)**: `wound/` package. Model raycast against the posed cubes, wound layers drawn in part
  space, on live and dead animals, targeted severing through a fully opened wound, stumps, drips from open wounds,
  `Weapon` and `Flesh` (meat / bone / dried husk). Settings: Wounds, Wounds on Monsters, Wounds on Players; depth
  per gore level in `GoreLevel.woundDepth`. Game test: `hackModel`. Left over from stage 1: per-zone, per-weapon
  sounds (only a bone crack on bodies so far) and blood sprayed along the blade. Wounds do not travel with a severed
  `GorePart` yet; they are dropped from the body. Next: stage 2 (segment severing, chunk physics).
- **1.4.0**: per-weapon/flesh sounds and blood along the blow (`HitFeedback`), wounds travel with severed pieces
  plus a cut face, ragdoll `heft` by body size, baby/variant model fix. Stage 1 is complete.

## Process
Do it in stages, committing each: (1) zone raycast + wound decals on a living and dead animal, (2) segment severing and
chunk physics, (3) articulated ragdoll replacing `Ragdoll`, (4) hanging/carrying, (5) species materials (skeleton,
husk), (6) audio and personality, (7) consequences/ecology. After each stage: `./gradlew build runClientGameTest`,
extend `FluidGoreClientTest` with assertions and screenshots, and look at them.

## Open decisions to settle at the start (propose and proceed, do not stall)
- Which solver for the ragdoll (recommended: position-based/verlet with distance + angle constraints).
- How wound decals attach to animated model parts (recommended: per-part local-space quads emitted in the same
  `ModelPart` transform, so they follow animation for free).
- How to resolve per-model hit zones for modded or unknown models (fallback: use the `ModelParts` index and split each
  cube into thirds along its longest axis).
