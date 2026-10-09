# Next task: "The Slaughterhouse" (for Opus 5.5)

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
