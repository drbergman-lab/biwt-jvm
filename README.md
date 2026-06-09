# BIWT — JVM implementation

[![CI](https://github.com/drbergman-lab/biwt-jvm/actions/workflows/ci.yml/badge.svg)](https://github.com/drbergman-lab/biwt-jvm/actions/workflows/ci.yml)

**BIWT** (BioInformatics WalkThrough) is a framework for bridging digital pathology
data to agent-based models. This repository is the JVM-side implementation —
a pure-Java core library plus a QuPath extension.

This implementation builds PhysiCell initial conditions from digital pathology in
two complementary ways: it samples **images** on a regular spatial grid to export
**substrate concentration** fields, and it places **segmented, classified cells**
as **cell initial conditions** — both in the same PhysiCell coordinate frame
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
-1190,-1190,0,42.7,0.83
-1170,-1190,0,41.9,0.85
...
```

Coordinates are in µm; voxel centers follow PhysiCell's mesh convention
(`x_min + (i+0.5)·dx`, anchored at the domain's bottom-left corner), and rows are
emitted in PhysiCell mesh order (bottom-left first, x inner, y bottom→top). A
sidecar `<name>-physicell-domain.xml` carries the matching `<domain>` bounds.

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

1. Open an image; verify pixel calibration is set (Image tab → Properties).
2. *(Optional)* Draw an axis-aligned rectangle and name it `abm_domain`. If you
   don't, the wizard asks which annotation to use (or offers the whole image).
3. For cells, segment + classify first (StarDist, Cellpose, InstanSeg, or
   QuPath's built-in cell detection) so the detections carry a class.
4. **Extensions → BIWT → Build initial conditions…** — the one-stop wizard:
   - Choose substrates, cells, or both; voxel size in µm.
   - Confirm — shows `nx × ny`, the effective step, and the PhysiCell domain bounds.
   - Define substrates (Channel or Expression per substrate) if building them.
   - Pick an output folder + base name → writes `*-substrates.csv`,
     `*-cells.csv`, and a `*-physicell-domain.xml` sidecar.
   - Optionally point it at your PhysiCell config XML to rewrite its `<domain>`
     to match (a `.bak` backup is kept).

The focused **Sample substrates…** and **Place cells…** menu items do each half
on its own; all three share the same annotation-center coordinate frame.

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
    CoordinateOrigin.ABM_DOMAIN_CENTER,
    [new SubstrateSpec("oxygen", 0),
     new SubstrateSpec("ecm",    1)]
)
def result = BiwtSampler.create().run(request)
result.writeCsv(Path.of(System.getProperty("user.home"), "substrates.csv"))
```

And to place cells (after segmenting + classifying in QuPath):

```groovy
import io.github.drbergmanlab.biwt.core.BiwtCellPlacer
import io.github.drbergmanlab.biwt.core.cells.CellPlacementOptions
import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin
import io.github.drbergmanlab.biwt.core.domain.DomainDetectionOptions
import java.nio.file.Path

def result = BiwtCellPlacer.create().run(
    getCurrentImageData(),
    DomainDetectionOptions.wholeImageFallback(),
    CoordinateOrigin.ABM_DOMAIN_CENTER,
    CellPlacementOptions.defaults().withVolume(true))   // x,y,z,type,volume
result.writeCsv(Path.of(System.getProperty("user.home"), "cells.csv"))
```

A complete example with input expansion lives at
[`scripts/run_biwt_sample.groovy`](scripts/run_biwt_sample.groovy).

## Build from source

Requires JDK 17 or newer on PATH for the Gradle daemon. The build itself uses a
toolchain-provisioned JDK 25 (downloaded automatically on first build).

```sh
git clone https://github.com/drbergman-lab/biwt-jvm
cd biwt-jvm
./gradlew :core:test                    # 101 unit tests in the core
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

- [x] **OD-sum channel** — added "Optical density sum" to the channel dropdown for RGB images.
- [x] **Channel math** — user-defined per-substrate expressions over the available channels (`+ - * / ^`, parentheses, and `log`/`log10`/`exp`/`sqrt`/`abs`/`min`/`max`/`clip`). Live validation, click-to-insert channel and function palettes. Hand-rolled recursive-descent parser in `:core`.
- [x] **PhysiCell-exact mesh frame** — the voxel grid and sampler are anchored at the annotation's bottom-left corner and discretized exactly like a PhysiCell `Cartesian_Mesh` (`min + (k+0.5)·d`, bottom→top). The CSV is written in PhysiCell mesh order so it loads voxel-for-voxel whether read by coordinate or positionally.
- [x] **PhysiCell domain-bounds readout** — `PhysiCellDomain` derives `x_min…z_max, dx, dy, dz` from the grid (2D z-slice). The wizard shows the bounds in the plan dialog and writes a paste-ready `<domain>` XML sidecar next to the CSV on save.
- [x] **Cell placement export** — places segmented, classified cells (from StarDist / Cellpose / InstanSeg / built-in detection) as PhysiCell cell initial conditions. Centroids map through a shared `CoordinateTransform` (same frame as the substrate voxels), QuPath classifications become named cell types, cells outside the domain are clipped, and an optional `volume` column is derived from segmented area (equivalent-sphere). Headless `BiwtCellPlacer` + *Place cells…* wizard. CSV: `x,y,z,type[,volume]`.
- [x] **CI** — GitHub Actions runs the full test suite on every push and PR.

### Completed (v0.4.0 — unified initial-conditions flow)

- [x] **Combined "Build initial conditions…" wizard** — produces substrates and/or cells in one pass over a single ABM domain + origin (so the two exports always share the same frame), with one folder + base-name "Save outputs" step.
- [x] **Center-only origin** — (0, 0) is fixed at the annotation center in the GUI; the top-left radio and origin-preview canvas are gone. `ABM_DOMAIN_TOP_LEFT` remains in the core enum for headless callers. The domain-bounds readout at confirmation is the transparency, not an editorialized note.
- [x] **Domain selection / annotation picker** — when there's no `abm_domain`, the wizard offers the image's rectangle annotations (or whole image); when there are several, it asks which one. `DomainDetector.fromAnnotation` builds the domain from the chosen annotation.
- [x] **Domain emitted from every method** — the cell export now writes a **bounds-only** `<domain>` sidecar (just `x_min/x_max/y_min/y_max`, leaving the user's voxel size alone); substrates write the full block.
- [x] **PhysiCell config auto-patch** — every wizard offers to rewrite a chosen PhysiCell config's `<domain>` to match the export (`.bak` backup, attributes/comments preserved). `PhysiCellConfigUpdater` in `:core`.
- [x] **91 unit tests** — 78 in `:core` (VoxelGrid, CoordinateTransform, DomainDetector, SubstrateSampler, SubstrateCsvWriter, BiwtSampler, PhysiCellDomain, PhysiCellConfigUpdater, CellExtractor, CellCsvWriter, channel-math) and 13 in `:qupath-extension`.

### Completed (v0.5.0 — results visualizer)

- [x] **Results visualizer** — an interactive viewer for a build: cells drawn as type-colored disks (radius = equivalent-sphere radius) over the active substrate as a viridis-like heatmap, in the shared ABM µm frame (y-flipped so tissue is right-side-up). Cycle substrates (dropdown / ◀ ▶ / arrow keys), pin the color range with `cmin`/`cmax`, zoom with `xmin/xmax/ymin/ymax`, and toggle cells over the heatmap with a legend. All value→pixel math (`CellGeometry`, `ColorMap`, `DataRange`, `WorldToScreen`) is unit-tested in `:core` (`…core.viz`); rendering is JavaFX in `…abm.viz`. Offered as a "Preview results" step on all three wizards, plus a standalone **View results…** menu item that reopens saved CSVs (`ResultsCsvLoader` reconstructs the grid from the coordinates).
- [x] **Shared, persistent output folder** — all wizards default the save location to the last folder you exported to (a preference that survives restarts, for processing images serially), else the current image's directory, else home.
- [x] **101 unit tests** — 23 new in `:core` (`…core.viz`: CellGeometry, ColorMap, DataRange, WorldToScreen, ResultsCsvLoader) on top of the existing 78.

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
