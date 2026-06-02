# BIWT — JVM implementation

[![CI](https://github.com/drbergman-lab/biwt-jvm/actions/workflows/ci.yml/badge.svg)](https://github.com/drbergman-lab/biwt-jvm/actions/workflows/ci.yml)

**BIWT** (BioInformatics WalkThrough) is a framework for bridging digital pathology
data to agent-based models. This repository is the JVM-side implementation —
a pure-Java core library plus a QuPath extension.

This implementation samples digital pathology **images** on a regular spatial grid
and exports **substrate concentration initial conditions** for an agent-based model
(primarily [PhysiCell](http://physicell.org)).

The Python implementation, which currently handles spatial-transcriptomics
→ cell initial conditions, lives at
[`drbergman-lab/biwt`](https://github.com/drbergman-lab/biwt). The two are
sibling implementations of the same framework; functionality will converge
over time. See each repo's README for what's currently implemented.

The plugin reads pixel intensities from one or more image channels, averages each
channel over non-overlapping square windows clipped to a user-defined annotation,
and writes a PhysiCell-compatible CSV:

```
x,y,z,oxygen,ecm,...
-1190,1190,0,42.7,0.83
-1190,1170,0,41.9,0.85
...
```

Coordinates are in µm; voxel centers follow PhysiCell's
`x_start + (i+0.5)·dx` convention.

## Quick start

### Install

Requires **QuPath 0.7.x**. Two install paths — pick whichever you prefer.

#### Option A: via the drbergman-lab catalog (recommended)

This route lets QuPath manage installs, updates, and uninstalls for every drbergman-lab extension from a single URL.

1. In QuPath, open **Extensions → Manage extension catalogs** (exact wording varies slightly by QuPath build) and add this URL:

   ```
   https://raw.githubusercontent.com/drbergman-lab/qupath-catalog/main/catalog.json
   ```
2. Open **Extensions → Manage extensions**. "BIWT" appears in the list with an Install button.
3. Click Install. QuPath downloads the jar from this repo's GitHub release and prompts to restart.

When a new BIWT release ships, the same Manage-extensions screen will offer an Update button — no manual download/replace required.

#### Option B: manual jar install

For one-off use or testing without configuring the catalog.

1. Download `qupath-extension-biwt-<version>-all.jar` from the [Releases](../../releases) page.
2. Drag the jar onto the running QuPath window. QuPath will copy it into `~/QuPath/v0.7/extensions/` and prompt to restart.
3. Confirm install via **Extensions → Installed extensions** — "BIWT" appears in the list.

### Use (interactive)

1. Open an image.
2. Verify pixel calibration is set (Image tab → Properties).
3. *(Optional)* Draw an axis-aligned rectangle and name it `abm_domain`.
4. **Extensions → BIWT → Sample substrates…** and follow the wizard:
   - Voxel size in µm.
   - Plan confirmation (shows `nx × ny` and the effective step).
   - Define substrates — name + channel (raw or color-deconvolved).
   - Save the CSV.

### Use (headless from a Groovy script)

In QuPath's script editor:

```groovy
import io.github.drbergmanlab.biwt.core.BiwtSampler
import io.github.drbergmanlab.biwt.core.SamplingRequest
import io.github.drbergmanlab.biwt.core.SubstrateSpec
import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin
import io.github.drbergmanlab.biwt.core.domain.DomainDetectionOptions
import java.nio.file.Path

def request = new SamplingRequest(
    getCurrentImageData(),
    DomainDetectionOptions.wholeImageFallback(),
    20.0,                                      // µm step size
    CoordinateOrigin.IMAGE_CENTER,
    [new SubstrateSpec("oxygen", 0),
     new SubstrateSpec("ecm",    1)]
)
def result = BiwtSampler.create().run(request)
result.writeCsv(Path.of(System.getProperty("user.home"), "substrates.csv"))
```

A complete example with input expansion lives at
[`scripts/run_biwt_sample.groovy`](scripts/run_biwt_sample.groovy).

## Build from source

Requires JDK 17 or newer on PATH for the Gradle daemon. The build itself uses a
toolchain-provisioned JDK 25 (downloaded automatically on first build).

```sh
git clone https://github.com/drbergman-lab/biwt-jvm
cd biwt-jvm
./gradlew :core:test                    # 53 unit tests in the core
./gradlew :qupath-extension:shadowJar   # fat jar in qupath-extension/build/libs/
```

The build is two modules:

- **`core/`** — pure Java library with no QuPath GUI dependencies. Callable from
  any Groovy/JVM script. Unit tests exercise the full sampling pipeline against
  synthetic `BufferedImage` fixtures.
- **`qupath-extension/`** — the QuPath wizard. Depends on `:core` plus
  `qupath-gui-fx`. Built into a shadow jar with `:core` bundled inside;
  QuPath itself is left on the runtime classpath.

## Documentation

| Document | Purpose |
|----------|---------|
| [`PRD.md`](PRD.md) | Behavioral specification for every feature — acceptance criteria and edge cases |
| [`progress.md`](progress.md) | Session journal: decisions made, approaches rejected, open questions |
| [`CLAUDE.md`](CLAUDE.md) | Orientation for AI-assisted contributors (and a useful intro for human ones) |
| [`my_qupath_plugin_context.html`](my_qupath_plugin_context.html) *(local only)* | Original design brief that kicked off the project |

## Implementation Status

> Authoritative record of what is built. Update when features ship. See
> [PRD.md](PRD.md) for behavioral specs and [progress.md](progress.md) for
> decision rationale.

### Completed (v0.1.0 — MVP)

- [x] Two-module Gradle build (Java 25 toolchain, QuPath 0.7.0 target)
- [x] Pure-Java core with no GUI deps — headless-callable from Groovy
- [x] Domain detection — finds `abm_domain` annotation, validates axis-aligned rectangle, falls back to whole-image or asks user
- [x] PhysiCell-compatible voxel grid math — `x_start + (i+0.5)·dx`, y-axis flipped to match math up-convention
- [x] Clip-to-annotation sampling — voxel grid sized to cover the annotation; windows averaged only over pixels inside the annotation; NaN for empty intersections
- [x] Square-pixel enforcement with clear error if violated
- [x] Step-size reconciliation — non-divisible µm steps round to whole pixels with the effective step surfaced to the user
- [x] CSV exporter with PhysiCell schema and NaN handling
- [x] `BiwtSampler` orchestrator façade — `plan` + `sample` split so the GUI can preview the grid before sampling
- [x] QuPath extension entry point + menu item
- [x] Wizard: normalization reminder → step size → domain detect → plan confirmation → substrate definition → file save
- [x] Substrate dialog: Add / Finish / Cancel / Remove buttons, Enter-to-add, name uniqueness check with inline warning
- [x] Channel dropdown surfaces raw channels plus color-deconvolution (H / E / Residual) when stains are defined
- [x] Parallel sampling — N workers driven by `ExecutorService`; CSV column order preserves user submission order
- [x] Non-modal progress dialog with determinate progress bar bound to a JavaFX `Task`
- [x] End-to-end validated on real H&E images (small and large) with sensible timing (~14 s for 6 substrates on 625 × 575 voxels)

### Completed (post-v0.1.0)

- [x] **Coordinate-origin radio** — wizard's parameters dialog lets the user pick ABM-domain center or ABM-domain top-left for the (0, 0) point, with a live preview canvas showing where the origin lands. The origin tracks the voxel grid (which is the ABM domain), not the image — so it sits on the annotation no matter where the annotation sits on the slide.
- [x] **OD-sum channel** — added "Optical density sum" to the channel dropdown for RGB images.
- [x] **Channel math** — user-defined per-substrate expressions over the available channels (`+ - * / ^`, parentheses, and `log`/`log10`/`exp`/`sqrt`/`abs`/`min`/`max`/`clip`). Live validation, click-to-insert channel and function palettes. Hand-rolled recursive-descent parser in `:core`.
- [x] **66 unit tests** — 53 in `:core` (VoxelGrid, DomainDetector, SubstrateSampler, SubstrateCsvWriter, BiwtSampler, channel-math parser + evaluation) and 13 in `:qupath-extension` (OpticalDensitySumTransform, ChannelMathTransform).
- [x] **CI** — GitHub Actions runs the full test suite on every push and PR.

### Planned (post-MVP)

- [ ] **User-defined origin** — third option that prompts for (x₀, y₀) µm explicitly.
- [ ] **Overlapping kernels** — separate window vs stride.
- [ ] **3D / z-stack support** — sampling over multiple z-planes.
- [ ] **Per-axis pixel sizes** — drop the square-pixel requirement.
- [ ] **Tile-shared multi-channel sampling** — refactor sampler to read each tile once and sample all channels.

### Distribution

- [x] GitHub release with the shaded jar
- [ ] Listed in the official [qupath-catalog](https://github.com/qupath/qupath-catalog)
- [ ] Announcement on [forum.image.sc](https://forum.image.sc) under `qupath` tag
