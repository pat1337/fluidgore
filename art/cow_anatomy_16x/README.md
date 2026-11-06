# Cow anatomy at 16x

The same veterinary model of a cow as `../cow_anatomy`, at the scale of the game: squashed into the vanilla adult
cow's own cubes, inside its fat and muscle (the mod's `Region.BEAST_TRUNK` and leg regions), cut into voxels of
16/16 of a pixel and drawn with the mod's own `anatomy.png` tiles, exactly as the game draws them.

| File | What | Cubes |
|---|---|---|
| `cow_trunk_16x.bbmodel` | Cow trunk at 16x (as the mod draws it) | 309 |
| `cow_organs_16x.bbmodel` | Cow organs at 16x (skeleton and diaphragm left out) | 212 |
| `cow_liver_16x.bbmodel` | Cow liver at 16x | 11 |
| `cow_heart_lungs_16x.bbmodel` | Cow heart and lungs at 16x | 50 |
| `cow_stomachs_16x.bbmodel` | Cow stomachs at 16x | 89 |
| `cow_guts_16x.bbmodel` | Cow intestines, kidneys, spleen, bladder at 16x | 55 |
| `cow_head_16x.bbmodel` | Cow head and neck at 16x | 52 |
| `cow_limbs_16x.bbmodel` | Cow limb bones at 16x | 32 |
| `cow_skeleton_16x.bbmodel` | Cow skeleton at 16x | 164 |
| `cow_whole_16x.bbmodel` | Cow whole animal at 16x | 393 |

- Blockbench units are the vanilla cow's own model pixels with y up from the ground: the body is x -6..6,
  12..22 up, z -8..10, the head x -4..4, 16..24 up, z -15..-8, the legs x -6..-2 or 2..6, 0..12 up. The cow faces
  **north** (-Z) and its right is **-X**, exactly as in `CowModel`.
- Every part is fitted on its own (as the mod's `Anatomy.fit` does), so the head and limbs are squashed more
  than the trunk: the vanilla cow is a wide, blocky animal and real anatomy is fitted to its silhouette.
- The `vanilla_cow_reference` group (hidden) is the vanilla body, udder, head, muzzle, horns and legs: show it
  to see where the organs sit.
- Every cube is named by its tissue; groups are organs and their parts, as in the full-size files. States
  (opened, torn) are not exported at game scale: the mod makes its own damage from these shapes.
- At this grain the thinnest vessels and ducts are left out and only every fourth rib is kept, so a gap shows
  between them. Nothing here is dropped for being unimportant: see the "Sources and simplifications" section
  of `../cow_anatomy/README.md` for what had to be simplified and why.
- The mod uses this table for loose organs: `tools/ExportCowAnatomy.ps1` reads this folder's `piece_table.json`
  into `CowTrunk.compact()`: the coarser shape a loose organ takes when its 32x shape has too many cubes
  to carry and sync. Run this tool first, then that script; never edit `CowTrunk.java` by hand.
