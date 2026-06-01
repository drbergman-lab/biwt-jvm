# CLAUDE.md — biwt-jvm (BIWT, JVM implementation)

## About the User

UMaryland (`dbergman1@som.umaryland.edu`). Building the **BIWT** (BioInformatics
WalkThrough) framework — a bridge from QuPath digital pathology images to
agent-based models, primarily [PhysiCell](http://physicell.org). Fluent in
QuPath, PhysiCell, Java/Groovy, and Julia. Comfortable with Gradle and JavaFX
basics. Talk in domain terms (substrates, voxels, ROIs, channels) without
over-explaining.

## Key Documents — Read These First

| Document | Purpose |
|----------|---------|
| [README.md](README.md) | Project overview + **Implementation Status** (what is built, what remains) |
| [PRD.md](PRD.md) | Behavioral specification for every feature — acceptance criteria and edge cases |
| [progress.md](progress.md) | Session journal: decisions made, approaches rejected, open questions |

Start any feature session by reading the relevant PRD entry and the
Implementation Status section of `README.md`.

## Project Overview

Two-module Gradle build:

- **`core/`** — pure Java library (group `io.github.drbergmanlab.biwt`). No
  QuPath GUI dependencies; callable from a headless Groovy script. Holds the
  voxel-grid math, domain detection, sampler, CSV writer, and the orchestrator
  façade.
- **`qupath-extension/`** — the QuPath wizard. Package `qupath.ext.biwt.abm`,
  per QuPath's convention for third-party extensions. Depends on `:core` +
  QuPath GUI APIs. Shaded into a fat jar that QuPath loads as an extension.

Headless entry point: `BiwtSampler.create().run(SamplingRequest)`. The wizard
is a thin caller of the same façade — anything the wizard does, a script can do.

## Target versions and toolchain

- **Java 25** for compilation (QuPath 0.7.0 JARs require it). Gradle auto-provisions
  via the `foojay-resolver-convention` plugin into `~/.gradle/jdks/`.
- **Gradle 9.4.0** via the wrapper. Needs JDK 17+ to *launch* the daemon —
  any modern installed JDK works.
- **QuPath 0.7.0** is the target API surface. Older 0.5/0.6 are not supported.

## Scope

All work must remain inside this repository (`~/my_qupath_abm_plugin/`).
Do not edit files elsewhere on the user's machine.

## Allowed / Cautioned commands

Allowed:
- `ls`, `cat`, `rg`/`grep`, `./gradlew test`, `./gradlew shadowJar`
- `git status`, `git diff`, `git log`, `git show`, `git tag`, `git branch`
- `git add`, `git commit` — the user has Claude Code, so committing from here is fine

Cautioned:
- `rm` — confine to the repo
- Anything that mutates files outside the repo

Prohibited:
- Never modify `.git/` directly
- Never push without explicit user approval
- Never force-push to `main` or rewrite published history

## Naming conventions

- **Java classes/records**: `PascalCase` (`VoxelGrid`, `AbmDomain`, `SubstrateSpec`)
- **Java methods/fields**: `camelCase` (`sampleOne`, `pixelWidthMicrons`)
- **Constants**: `SCREAMING_SNAKE_CASE` (`DEFAULT_STEP_MICRONS`)
- **Packages**: lowercase, dot-separated; core under `io.github.drbergmanlab.biwt`, extension under `qupath.ext.biwt.abm`
- **Test classes**: `<ClassUnderTest>Test.java` (mirrors the main source layout)

## Build and test commands

```sh
./gradlew :core:test                       # 30 unit tests in the core
./gradlew :qupath-extension:shadowJar      # fat jar in qupath-extension/build/libs/
./gradlew build                            # everything
```

After a code change in the extension or core:

```sh
./gradlew :qupath-extension:shadowJar
cp qupath-extension/build/libs/qupath-extension-biwt-*-all.jar \
   ~/QuPath/v0.7/extensions/
# then fully quit and relaunch QuPath
```

## Required workflow for any change

1. **Read the relevant PRD entry** and the Implementation Status row in
   `README.md`. If neither exists, draft a PRD entry first.
2. **Write a design brief** in the response *before* code changes
   (template below). Wait for human approval.
3. **Add the open-question/decision section to `progress.md`** as you reason
   through the design.
4. **Implement.** Tests added/updated for any new behavior in `core`.
5. **Update `README.md` Implementation Status** when a feature is complete.
6. **Trim PRD.md and progress.md** to reflect the final implementation.
7. **Commit** with a focused message; co-author tag `Co-Authored-By: Claude Opus 4.7`.

### Design brief template

```
# Design Brief: [Feature/Refactor Name]

## Motivation
[1–2 sentences: why this change?]

## Scope
- Files affected: …
- New files: …
- Breaking changes: yes/no — …

## Proposed approach
[2–3 paragraphs or a sketch]
- Current behavior:
- Proposed behavior:
- Key decisions and rejected alternatives:

## Testing strategy
- Unit tests for: …
- Manual checks: …

## Estimated effort
- Lines of code: …
- Risk: Low/Medium/High
- Dependencies: …
```

## Definition of Done

A feature is complete when **all** of:

1. **Tests pass:** `./gradlew :core:test` green; new unit tests for the new behavior in `core` if applicable.
2. **End-to-end check:** wizard or Groovy script verifies the feature on a real image (or synthetic when sufficient).
3. **Docstrings written:** every new public class/record/method has a Javadoc with intent, parameters, and (when non-obvious) usage.
4. **README updated:** Implementation Status marks the feature complete (move from "Planned" to "Completed").
5. **PRD reflects reality:** if implementation deviates from the PRD entry, update it.
6. **No regressions:** existing test suite remains green.

## Architecture invariants

- **Core / GUI separation.** `core` may never import `qupath.fx.*` or
  `javafx.*`. Anything pixel- or grid-related belongs in `core`; anything that
  touches dialogs or threads belongs in `qupath-extension`.
- **Headless callability.** Every public `core` method must be reachable from
  a Groovy script with no GUI initialized. Verify by mentally tracing — if it
  needs the wizard to populate state, it's wrong.
- **PhysiCell coordinate convention.** Voxel centers follow
  `x_start + (i+0.5)·dx`. The y-axis is flipped (math `+y` is up, image rows
  are top→bottom). See `VoxelGrid` Javadoc.
- **Origin is anchored to the ABM domain, not the image.** `CoordinateOrigin`
  is `ABM_DOMAIN_CENTER` / `ABM_DOMAIN_TOP_LEFT`. The grid coordinates depend
  only on the grid extent and the origin choice — never on where the annotation
  sits on the slide.
- **Clip-to-annotation rule.** The voxel grid covers the annotation as a
  smallest-integer-multiple bounding box; sampling intersects each window
  with the annotation Shape and writes NaN for empty intersections.
- **Square pixels only (MVP).** `BiwtSampler.plan` throws if
  `pixelWidthMicrons != pixelHeightMicrons`. Non-square support requires
  per-axis strides in `SamplingKernel`.

## Publishing to QuPath users

Three distribution channels:

1. **GitHub release.** `git push origin main && git push origin <tag>`, then
   create a GitHub release on this repo and attach
   `qupath-extension/build/libs/qupath-extension-biwt-<version>-all.jar`. Users
   can drag-drop the jar into QuPath manually.
2. **QuPath catalog.** The polished path. The catalog manifest lives in a
   **separate repo**: [`drbergman-lab/qupath-catalog`](https://github.com/drbergman-lab/qupath-catalog).
   `qupath/qupath-catalog` is for QuPath-team extensions only; third parties
   publish their own catalog and users add the URL via Extensions → Manage
   extension catalogs. The published catalog URL is
   `https://raw.githubusercontent.com/drbergman-lab/qupath-catalog/main/catalog.json`.
   A new BIWT release isn't "done" until that catalog has the new entry —
   see "Releasing a new version" below.
3. **forum.image.sc announcement.** A post with the `qupath` tag under
   *Software Announcements*. Most ecosystem discovery happens there.

## Releasing a new version

Step-by-step for cutting a new release (replace `0.X.0` with the actual version).
Steps in this repo come first; the catalog repo is last but **mandatory** for
catalog users to see the new release.

### In `biwt-jvm` (this repo)

1. Bump `version` in **`qupath-extension/build.gradle.kts`** and the root
   **`build.gradle.kts`** from the previous version to `0.X.0`. Both files,
   same number.
2. Update **`README.md`** Implementation Status (move shipped items into the
   completed bucket; trim Planned).
3. Update **`PRD.md`** if any behaviour changed since last release.
4. Add a session entry to **`progress.md`** capturing the why behind any
   non-obvious design decisions.
5. Run the full suite: `./gradlew :core:test`. All tests must be green.
6. Build the fat jar: `./gradlew :qupath-extension:shadowJar`. Result lives at
   `qupath-extension/build/libs/qupath-extension-biwt-0.X.0-all.jar`.
7. Commit, then tag: `git tag -a v0.X.0 -m "v0.X.0 — <one-line summary>"`.
8. Push: `git push origin main && git push origin v0.X.0`.
9. Draft a release note in
   **`release-notes/v0.X.0.md`** following the format of `release-notes/v0.3.0.md`.
   Make sure it makes sense to someone landing here without context.
10. `gh release create v0.X.0 --repo drbergman-lab/biwt-jvm
    qupath-extension/build/libs/qupath-extension-biwt-0.X.0-all.jar
    --title "v0.X.0 — <one-line summary>"
    --notes-file release-notes/v0.X.0.md`.
11. Smoke-test the published release: download the jar from the public URL,
    drop into QuPath, walk the wizard once on a known image, confirm the
    output matches the previous release for an unchanged feature path.

### In `drbergman-lab/qupath-catalog` (the catalog repo)

12. Edit **`catalog.json`** in that repo: prepend a new release entry under the
    BIWT extension's `releases` array. Newer first.
    ```json
    {
        "name": "v0.X.0",
        "main_url": "https://github.com/drbergman-lab/biwt-jvm/releases/download/v0.X.0/qupath-extension-biwt-0.X.0-all.jar",
        "version_range": { "min": "v0.7.0" }
    }
    ```
13. Validate before pushing:
    ```sh
    pip install git+https://github.com/qupath/extension-catalog-model
    python -c "from extension_catalog_model.model import Catalog; \
               Catalog.parse_file('catalog.json'); print('valid')"
    ```
    The validator hits `main_url` and `homepage` live to confirm 200 — so the
    GitHub release from step 10 must already be published before this runs.
14. Commit, push. The change is live to QuPath users at the catalog URL
    immediately — QuPath refetches on each Manage-extensions open.

### Optional post-release

15. forum.image.sc post (once per significant release; usually `v0.3.0` style,
    not every patch).
16. Update the user's `README.md` in the catalog repo if the listed extensions
    table needs adjustment.

## QuPath 0.7 API gotchas

Inherited from earlier sessions — surfaces useful when modifying core or wiring new GUI dialogs:

- `TransformedServerBuilder` has no `pixelSizeMicrons(...)` method — pixel
  calibration lives on `ImageServerMetadata`. To set it on a wrapped server in
  tests: `server.setMetadata(new ImageServerMetadata.Builder(server.getOriginalMetadata()).pixelSizeMicrons(px, px).build())`.
- `RectangleROI` lives in `qupath.lib.roi`; the ROI interface in
  `qupath.lib.roi.interfaces.ROI`; rectangle/ellipse creation in
  `qupath.lib.roi.ROIs`.
- Annotations: `imageData.getHierarchy().getAnnotationObjects()`. Name via
  `pathObject.setName(...)`.
- `shadow(...)` in the `qupath-conventions` Gradle plugin means *"QuPath
  provides this at runtime, don't bundle into the fat jar."* For our own
  modules (`:core`), use `implementation(...)`.
- Tooltips on **disabled** JavaFX buttons don't fire (disabled state swallows
  mouse events). Use an always-visible inline label for the warning instead.
- JavaFX `Task.updateMessage`/`updateProgress` are safe to call from any
  thread — they internally post to the JavaFX thread.

## Useful files

- [`scripts/run_biwt_sample.groovy`](scripts/run_biwt_sample.groovy) — headless
  example script that exercises `BiwtSampler.run`. Useful as a smoke test
  when changing the core API.
