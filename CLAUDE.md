# Fluid Gore

Fabric mod for Minecraft Java 26.3: blood, a blood fluid simulation, dismemberment, wounds, anatomy, animal suffering,
and an optional server half. Mod id `fluidgore`, MIT. The current version is `version=` in `gradle.properties`.

@CONTRIBUTING.md

## Before changing code
- Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the map of the code and its invariants.
- If `notes/` exists, read `notes/ENVIRONMENT.md` for this machine's setup (the JDK 25 path, installed mods) and
  private constraints. `notes/DEV_HISTORY.md` holds the detailed version-by-version internals, and
  `notes/NEXT_TASK.md` holds the roadmap and its progress. `notes/` is git-ignored.

## Writing docs
- Public docs (README, CHANGELOG, CONTRIBUTING, docs/, art READMEs) are for players and contributors. Write them
  plainly. Avoid "the user asked", model names, "slice N", test-run logs, hashes and local paths. Do not stack up
  claims of realism or disclaimers.
- The README describes what the mod does today. The CHANGELOG uses Keep a Changelog sections (Added, Changed,
  Fixed, Removed, Security), with an ISO date on every release heading.
- Session handoffs and validation logs go in `notes/`.
