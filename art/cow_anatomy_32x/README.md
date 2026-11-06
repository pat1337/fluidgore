# Cow anatomy at 32x

The same veterinary model of a cow as `../cow_anatomy`, at the scale of the game: squashed into the vanilla adult
cow's own cubes, inside its fat and muscle (the mod's `Region.BEAST_TRUNK` and leg regions), cut into voxels of
16/32 of a pixel and drawn with the mod's own `anatomy.png` tiles, exactly as the game draws them.

| File | What | Cubes |
|---|---|---|
| `cow_trunk_32x.bbmodel` | Cow trunk at 32x (as the mod draws it) | 1143 |
| `cow_organs_32x.bbmodel` | Cow organs at 32x (skeleton and diaphragm left out) | 925 |
| `cow_liver_32x.bbmodel` | Cow liver at 32x | 65 |
| `cow_heart_lungs_32x.bbmodel` | Cow heart and lungs at 32x | 261 |
| `cow_stomachs_32x.bbmodel` | Cow stomachs at 32x | 409 |
| `cow_guts_32x.bbmodel` | Cow intestines, kidneys, spleen, bladder at 32x | 164 |
| `cow_head_32x.bbmodel` | Cow head and neck at 32x | 124 |
| `cow_limbs_32x.bbmodel` | Cow limb bones at 32x | 47 |
| `cow_skeleton_32x.bbmodel` | Cow skeleton at 32x | 339 |
| `cow_whole_32x.bbmodel` | Cow whole animal at 32x | 1314 |

- Blockbench units are the vanilla cow's own model pixels with y up from the ground: the body is x -6..6,
  12..22 up, z -8..10, the head x -4..4, 16..24 up, z -15..-8, the legs x -6..-2 or 2..6, 0..12 up. The cow faces
  **north** (-Z) and its right is **-X**, exactly as in `CowModel`.
- Every part is fitted on its own (as the mod's `Anatomy.fit` does), so the head and limbs are squashed more
  than the trunk: the vanilla cow is a wide, blocky animal and real anatomy is fitted to its silhouette.
- The `vanilla_cow_reference` group (hidden) is the vanilla body, udder, head, muzzle, horns and legs: show it
  to see where the organs sit.
- Every cube is named by its tissue; groups are organs and their parts, as in the full-size files. States
  (opened, torn) are not exported at game scale: the mod makes its own damage from these shapes.
- At this grain the thinnest vessels and ducts are left out and only every second rib is kept, so a gap shows
  between them. Nothing here is dropped for being unimportant: see the "Sources and simplifications" section
  of `../cow_anatomy/README.md` for what had to be simplified and why.
- The mod uses this table: `tools/ExportCowAnatomy.ps1` turns this folder's `piece_table.json` into the cow's
  trunk (`src/main/java/dev/fluidgore/anatomy/CowTrunk.java`, `pieces()`). The head and limbs still use the
  shared ungulate tables. Run this tool first, then that script; never edit `CowTrunk.java` by hand.
