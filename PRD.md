# Product Requirements Document — biwt-jvm (BIWT, JVM implementation)

> **Purpose:** This document defines the complete feature set of the BIWT
> sampler in behavioral terms. It is the authoritative answer to *"what should
> this system do?"* — read the relevant entry at the start of any feature
> session to align intent and implementation.

---

## Product Overview

**Vision:** Provide a one-click bridge from a digital pathology image opened
in QuPath to a substrate initial-condition CSV that an agent-based model
(primarily PhysiCell) can ingest, with no custom scripting required for
common cases and a headless API for the rest.

**Target users:** Researchers running tissue-level ABMs informed by histology
or fluorescence imaging. Typical workflow: stained slide → QuPath analysis →
BIWT export → PhysiCell run.

**Objectives:**

1. Make substrate export a *2-minute* GUI workflow for a researcher with no
   programming background — open image, draw a rectangle, pick channels, save.
2. Stay strictly headless-callable so batch processing across cohorts is
   trivial from a Groovy script.
3. Match PhysiCell's coordinate conventions exactly so the CSV requires no
   post-processing on the PhysiCell side.
4. Never silently distort the data — non-square pixels, missing calibration,
   non-divisible step sizes are all surfaced with clear messages.

---

## Feature: Headless Core API

**One-line description:** All sampling functionality is reachable from a Groovy
script with no GUI thread running.

**Priority:** Must-have.

**Behavioral specification:**

- `BiwtSampler.create()` returns a stateless façade.
- `BiwtSampler.plan(imageData, domainOptions, requestedStepMicrons, origin)`
  returns a `SamplingPlan` (domain, voxel grid, kernel, requested/effective
  step) without touching pixel data beyond metadata.
- `BiwtSampler.sample(server, plan, substrates)` runs the per-channel
  sampling pass, returning a `SamplingResult`.
- `BiwtSampler.sampleOne(server, plan, spec)` samples a single substrate
  (used by the GUI for parallel/per-substrate progress reporting).
- `BiwtSampler.run(samplingRequest)` is the one-shot convenience: plan + sample
  against the raw server.
- `SamplingResult.writeCsv(Path)` writes the PhysiCell-format CSV.

**Acceptance criteria:**

- A Groovy script in QuPath's script editor can sample and write a CSV in
  ≤ 10 lines of user code.
- No `core` class imports `javafx.*` or `qupath.fx.*`.
- All public methods have Javadoc.

---

## Feature: Domain Detection

**One-line description:** Find the rectangular region of the image to sample.

**Priority:** Must-have.

**Behavioral specification:**

- Default: look for an annotation named `abm_domain`.
- `DomainDetectionOptions` carries the name (defaults `"abm_domain"`) and
  a `Fallback`:
  - `FAIL` — throw `AnnotationNotFoundException` if missing.
  - `WHOLE_IMAGE` — use the entire image as the domain.
  - `ASK_USER` — throw `AskUserRequiredException` so the GUI can prompt.
- The matching annotation must be a `RectangleROI`. If not, throw
  `NonRectangularDomainException`.
- Multiple matching annotations → `DomainException` (the user must keep one).
- Image must have pixel calibration set; missing calibration →
  `DomainException` with a "set the pixel size in µm" message.
- Returned `AbmDomain` carries pixel-space bounds, per-axis µm calibration,
  a Shape clip mask, and a human-readable source description.

**Acceptance criteria:**

- Unit tests cover: name match, custom name, each fallback mode,
  non-rectangle rejection, duplicate rejection, missing-calibration rejection.

---

## Feature: Voxel Grid Math

**One-line description:** Build the regular grid that maps image pixels to
ABM-µm coordinates using PhysiCell's voxel-center convention.

**Priority:** Must-have.

**Behavioral specification:**

The grid is discretized **exactly like a PhysiCell `Cartesian_Mesh`**: anchor at
the domain minimum corner `(x_min, y_min)`, step half a voxel in, then stride a
full voxel until `(x_max, y_max)`. The algorithm is origin-agnostic — the origin
only sets the numeric anchor.

- `VoxelGrid.cover(...)` returns the smallest integer-step-multiple grid covering
  the annotation: `nx = ceil(annW/step)`, `ny = ceil(annH/step)`.
- `xCenter(i) = xMin + (i + 0.5)·dx`, `i = 0…nx-1` left → right.
- `yCenter(k) = yMin + (k + 0.5)·dy`, `k = 0…ny-1` **bottom → top** (PhysiCell
  mesh index). The grid carries no y-flip; the image-row→math-y flip lives in the
  sampler (it anchors windows at the annotation bottom so `k = 0` averages the
  image's bottom rows).
- `xMax = xMin + nx·dx`, `yMax = yMin + ny·dy`. The non-dividing overhang lands as
  partial voxels at the **top and right** (far edges from the anchor).
- `CoordinateOrigin.ABM_DOMAIN_TOP_LEFT`: the annotation's top-left corner is
  (0, 0), so `xMin = 0`, `yMin = -annH`.
- `CoordinateOrigin.ABM_DOMAIN_CENTER`: the **annotation** is centered on (0, 0),
  so `xMin = -annW/2`, `yMin = -annH/2`. On a non-dividing step the domain
  *bounds* are mildly asymmetric (one-sided overhang) even though the annotation
  is centered — an accepted cost of a step that doesn't divide the domain.

The anchor depends only on the annotation's size and the origin choice — never on
where the annotation sits on the slide.

**Acceptance criteria:**

- Voxel `k = 0` (PhysiCell index 0, image bottom) has the **smallest** y;
  `k = ny − 1` (image top) the largest.
- Annotation 310 µm × 410 µm, step 20 µm, `ABM_DOMAIN_TOP_LEFT`: domain
  `x ∈ [0, 320], y ∈ [-410, +10]`; x-centers `10, 30, …, 310`; y-centers
  `-400, -380, …, 0`.
- Annotation 60 µm × 60 µm, step 20 µm, `ABM_DOMAIN_CENTER`: 3 × 3 grid, centers
  `{-20, 0, 20}` per axis (symmetric, because the step divides the annotation).

---

## Feature: Substrate Sampler

**One-line description:** Average channel pixel values inside the annotation
for each voxel.

**Priority:** Must-have.

**Behavioral specification:**

- For each voxel `(i, k)`, build a pixel-space window of size
  `kernel.windowSizePx`: x in `[domain.xMinPx + i·stride, …]`, y *ending* at
  `domain.yMaxPx − k·stride`. Windows are anchored at the annotation's
  **bottom-left** pixel corner and tiled right and up, so row `k = 0` sits on the
  annotation bottom (this is where the image-row→math-y flip lives) and the
  overhang lands at the top and right.
- Clip the window to image bounds (the grid may overhang by up to
  `stride − 1` pixels per far side).
- Read the clipped tile via `ImageServer.readRegion` at downsample 1.0.
- Intersect each pixel center with `domain.clipMaskPx`; average only the
  channel values for pixels inside the mask.
- Empty intersection → NaN.
- Fast paths: skip the read entirely if the window doesn't intersect the
  mask; skip the per-pixel `Shape.contains` test when the window is fully
  inside the mask.

**Acceptance criteria:**

- Uniform image → uniform mean across the grid.
- Column-gradient image (`value = x`) → expected mean per column.
- Window straddling the image edge → only in-image pixels counted.
- Window outside the clip mask → NaN.
- Channel index outside `[0, server.nChannels())` → `IllegalArgumentException`.

---

## Feature: CSV Export

**One-line description:** Write a PhysiCell-compatible substrates CSV.

**Priority:** Must-have.

**Behavioral specification:**

- Schema: `x,y,z,<name1>,<name2>,...` — coordinates in µm, `z = 0` for the 2D
  MVP, one row per voxel.
- Iteration order: **PhysiCell mesh order** — bottom-left voxel first, `i` (x)
  inner (`x_min → x_max`), `k` (y) outer (`y_min → y_max`, bottom → top). Loads
  identically whether PhysiCell reads by coordinate or positionally.
- Numbers: integers printed without trailing zeros where possible;
  `NaN` for unknown values.
- Throw `IllegalArgumentException` for mismatched dimensions or empty
  substrate list.

**Acceptance criteria:**

- Header row matches the schema.
- Row count = `nx · ny`.
- Round-trip parse with a CSV library returns the same values.
- First data row is the bottom-left voxel (smallest x, smallest y); the y column
  is non-decreasing down the file.

---

## Feature: PhysiCell Domain Bounds Readout

**One-line description:** Surface the exact PhysiCell domain bounds implied by the
grid so the user can configure their simulation to match the export voxel-for-voxel.

**Priority:** Must-have.

**Behavioral specification:**

- `PhysiCellDomain.of(grid)` derives `x_min, x_max, y_min, y_max, dx, dy` from the
  voxel grid, plus a 2D z-slice: `dz = dx`, `z ∈ [-dz/2, +dz/2]`, `use_2D = true`,
  `nz = 1`.
- `toXml()` renders a paste-ready PhysiCell `<domain>` block (tab-indented).
- `summary()` renders a compact human-readable form for a dialog.
- `SamplingPlan.physiCellDomain()` / `SamplingResult.physiCellDomain()` expose it.
- The wizard shows `summary()` in the plan-confirmation dialog and, on save,
  writes a `<csv-basename>-physicell-domain.xml` sidecar next to the CSV. A
  sidecar (not an inline CSV comment) keeps PhysiCell's numeric line parser clean.

**Acceptance criteria:**

- For the canonical 310 × 410 µm / 20 µm / top-left grid, the XML carries
  `x_min 0, x_max 320, y_min -410, y_max 10, dx 20, use_2D true`.
- Fractional/asymmetric bounds (center origin, non-dividing step) round-trip
  without loss (e.g. `x_min -20.5`).

---

## Feature: Step-Size Reconciliation

**One-line description:** Translate the user's µm step size into integer
pixel strides without lying about the result.

**Priority:** Must-have.

**Behavioral specification:**

- `stridePx = max(1, round(stepSizeMicrons / pixelMicrons))`.
- `effectiveStepMicrons = stridePx · pixelMicrons` — the µm distance actually
  traversed, which may differ from the request.
- Both values are surfaced on `SamplingResult` / `SamplingPlan` so the GUI
  can show "*requested 7 µm, effective 6.9 µm (rounded to whole pixels)*".
- Square pixels are required for MVP — if `pixelWidthMicrons ≠ pixelHeightMicrons`,
  `BiwtSampler.plan` throws `DomainException`.

**Acceptance criteria:**

- A 7 µm request at 0.3 µm/px → `stridePx = 23`, `effectiveStepMicrons = 6.9`.
- Non-square pixels throw with a descriptive message.

---

## Feature: QuPath Extension Wizard

**One-line description:** The interactive flow from menu click to saved CSV.

**Priority:** Must-have.

**Behavioral specification:**

The wizard runs as a sequence of modal dialogs on the JavaFX thread:

1. **Normalization reminder** — confirm dialog: "BIWT samples raw channel
   intensities. Ensure normalization is complete. Continue?"
2. **Voxel size in µm** — numeric input, default 20.0.
3. **Domain detection** — try `abm_domain`; on miss, ask
   "Use the whole image as the ABM domain instead?".
4. **Plan confirmation** — text dialog showing source description, `nx × ny`,
   requested and effective step (with a *(rounded to whole pixels)* note when
   they differ), and the pixel size. OK / Cancel.
5. **Substrate definition** — a single modal:
   - A list of substrates added so far.
   - Name field + channel dropdown (raw channels and, when stains are set,
     deconvolved H / E / Residual).
   - **Add substrate** button: disabled until name + channel are valid;
     a duplicate name shows an inline "already in use" warning.
   - **Remove selected** button: removes the highlighted row.
   - **Finish** button: disabled until at least one substrate is in the list.
   - **Cancel** button: aborts everything.
   - **Enter** in the name field triggers Add when enabled.
6. **File save** — JavaFX `FileChooser`; default filename
   `<image-name>-substrates.csv`.
7. **Sampling** — non-modal stage with a determinate `ProgressBar` and a
   status label, running an `ExecutorService` of N workers
   (`N = min(substrates, availableProcessors())`). Workers update
   `Task.progressProperty` / `Task.messageProperty` as they complete.
   The CSV column order matches the user's submission order even though
   workers may finish in any order.

**Acceptance criteria:**

- The wizard never blocks the JavaFX thread on pixel reads.
- The image can be panned / zoomed while sampling runs.
- The wizard step dialogs (parameters, substrate definition, save target) are non-modal, so the
  user can pan / zoom the source image while they are open. Switching the *active image* mid-wizard
  is caught before committing (the wizard aborts with an error rather than sampling the wrong image).
- The plan-confirmation screen (computed grid + PhysiCell domain), the substrate-definition dialog,
  and the Build wizard's save-target dialog each carry a **Back** button that returns to the previous
  custom screen with state preserved: the substrate list, the entered voxel size, and a browsed
  output folder survive a Back/forward round-trip. Back returns to the parameters screen, re-running
  the domain picker going forward. The domain picker itself (a QuPath choice dialog) and the native
  file chooser stay plain OK/Cancel — they can't host a Back button. The Cell wizard has a single
  screen and so has no Back.
- Canceling at any step writes nothing.
- Errors raised from `core` (no calibration, non-rectangular ROI, non-square
  pixels, duplicate substrate name) become `Dialogs.showErrorMessage` calls
  with the original message.

---

## Feature: Color-Deconvolution Channels

**One-line description:** Surface QuPath's deconvolved H / E / Residual
channels in the BIWT channel dropdown.

**Priority:** Must-have.

**Behavioral specification:**

- When `imageData.getColorDeconvolutionStains() != null` and the server is
  RGB, append one dropdown entry per stain.
- Implemented by building a `TransformedServerBuilder` pipeline that
  applies `ColorTransforms.createChannelExtractor(i)` for each raw channel
  followed by `ColorTransforms.createColorDeconvolvedChannel(stains, n)`
  for n = 1, 2, 3.
- The combined server is what gets sampled — the user's channel choice maps
  to its index in the combined server.

**Acceptance criteria:**

- An H&E brightfield image with stains set shows H, E, and Residual in
  addition to R, G, B.
- A fluorescence (non-RGB) image shows only its raw channels.

---

## Feature: Parallel Sampling

**One-line description:** Run multiple substrates concurrently to take
advantage of QuPath's tile cache.

**Priority:** Should-have (shipped in v0.1.0 as a UX improvement).

**Behavioral specification:**

- Each substrate is an independent pass; per-substrate workers submit to
  a fixed thread pool sized `min(substrates, availableProcessors())`.
- `ImageServer` is thread-safe for reads; QuPath caches decoded tiles
  server-wide, so overlapping reads pay the decode cost only once.
- Output column order matches submission order — futures are collected
  in submission order regardless of completion order.
- Each worker calls `BiwtSampler.sampleOne`; the orchestrator updates the
  `Task`'s message and progress as workers complete.

**Acceptance criteria:**

- Speedup ≥ 2× over sequential on N ≥ 4 substrates.
- The CSV is unchanged from the sequential output.

---

## Feature: Cell Placement Export

**One-line description:** Place segmented, classified cells as PhysiCell cell
initial conditions, in the same coordinate frame as the substrate export.

**Priority:** Must-have.

**Behavioral specification:**

BIWT links existing tools; it does not segment or classify. Segmentation comes
from StarDist / Cellpose / InstanSeg / built-in cell detection, and cell types
from QuPath classifications. BIWT reads the resulting detection hierarchy and:

- `CoordinateTransform.of(domain, origin)` builds the continuous pixel→µm map
  (same anchor as `VoxelGrid`, with the y-flip). Cells are placed at their exact
  centroids — no voxel snapping — so a cell and a substrate voxel at the same
  physical location share coordinates.
- For each detection: centroid → µm; QuPath `PathClass` name → PhysiCell cell
  type (identity, or via `CellPlacementOptions.typeNameOverrides`).
- Cells whose centroid falls outside the domain clip mask are dropped.
- Unclassified detections are dropped, or assigned a default type, per
  `unclassifiedTypeName`.
- When `includeVolume` is set, `volume` (µm³) is the equivalent-sphere volume of
  the segmented area: `r = sqrt(area/π)`, `V = (4/3)π r³`.
- `CellCsvWriter` writes a headered CSV with **named** types:
  `x,y,z,type` (or `x,y,z,type,volume`). Type strings are CSV-escaped only when
  needed. Order is irrelevant (a cell IC is an unordered point set).
- `BiwtCellPlacer.create().run(imageData, domainOptions, origin, options)` is the
  headless one-shot, returning a `CellPlacementResult` with `writeCsv(Path)`.
- Cell placement does **not** require square pixels (continuous transform + area
  use per-axis calibration).

**Wizard:** *Extensions → BIWT → Place cells…* — requires existing detections;
options dialog (origin radio, volume toggle, unclassified handling); whole-image
fallback when `abm_domain` is absent; runs synchronously (geometry only);
file-save; "no cells placed" guard.

**Acceptance criteria:**

- A cell at pixel (222, 333) in a 310 × 410 µm top-left domain anchored at pixel
  (100, 200) is placed at (122, −133).
- Detections outside the clip mask and unclassified detections (default options)
  are dropped; `withUnclassifiedAs("x")` keeps the latter as type `x`.
- `includeVolume` on a 400 µm² cell yields ≈ 6018 µm³.
- CSV header is `x,y,z,type` (or `…,volume`); empty cell list throws.

---

## Feature: Unified Build Wizard (v0.4.0)

**One-line description:** One wizard that builds substrates and/or cells over a single ABM
domain + origin, so both exports share the same coordinate frame.

**Priority:** Must-have (v0.4.0).

**Behavioral specification:**

- *Extensions → BIWT → Build initial conditions…* — the primary entry point (the focused
  *Sample substrates…* and *Place cells…* items remain).
- Parameters: "Build substrates" / "Build cells" checkboxes (≥ 1 required); voxel size
  (enabled only for substrates); cell volume + unclassified options (enabled only for cells).
- **Origin is annotation-center only** — no radio. `ABM_DOMAIN_TOP_LEFT` stays in the core enum
  for headless callers; the GUI never offers it. Transparency comes from the domain-bounds readout
  at confirmation, not a label.
- **Domain selection / annotation picker:** exactly one `abm_domain` annotation is used directly;
  with none, the user picks from the image's rectangle annotations (or whole image); with several
  `abm_domain`, the user picks which. Backed by `DomainDetector.fromAnnotation`.
- Confirmation shows the source, grid size (if substrates), and the PhysiCell domain bounds.
- Substrates reuse the existing substrate dialog (Channel / Expression per substrate).
- Output: one folder + base name → `<base>-substrates.csv`, `<base>-cells.csv`,
  `<base>-physicell-domain.xml` (only those that apply).
- The emitted domain is the **full** voxel-sized domain when substrates are built, else the
  **bounds-only** annotation box for cells.

**Acceptance criteria:**

- Substrates and cells exported together land in the same frame (same domain + center origin).
- With no `abm_domain` and several rectangle annotations, the user is asked which to use.
- Cells-only run writes a bounds-only domain (x/y bounds, no voxel size).

---

## Feature: PhysiCell Config Auto-Patch (v0.4.0)

**One-line description:** Rewrite the `<domain>` block of a user's PhysiCell config XML to match
the export, so the simulated mesh lines up without hand-copying.

**Priority:** Should-have (v0.4.0).

**Behavioral specification:**

- After an export, each wizard offers to update a chosen PhysiCell config XML.
- `PhysiCellConfigUpdater.updateDomain(path, domain)` does a targeted text-splice of the first
  `<domain>…</domain>` block: replaces the values of known child tags, **preserving** attributes
  (e.g. `units="micron"`), comments, indentation, and the rest of the file; appends any missing
  tag; writes a `<name>.bak` backup; throws if there is no `<domain>`.
- A bounds-only domain (cells) updates only `x_min/x_max/y_min/y_max`; a voxel-sized domain
  (substrates) updates the full block. The user's untouched values (e.g. `dz`, `z_min`) remain.

**Acceptance criteria:**

- Values update; `units` attributes and the rest of the config are preserved; a `.bak` is written.
- Cells-only patch leaves the config's voxel size and z bounds unchanged.

---

## Feature: Results Visualizer

**One-line description:** An interactive viewer that draws the cells and substrate fields a build
produced, in their shared ABM µm frame, so the user can sanity-check the export before running
PhysiCell.

**Priority:** Should-have (staged for v0.5.0).

**Behavioral specification:**

- **Cell scatter.** Each placed cell is a filled disk centered at its `(x, y)` µm position with
  radius = the cell's equivalent-sphere radius (`r = cbrt(3V/4π)`); cells with no volume use
  PhysiCell's default `V = 2494 µm³` (`r ≈ 8.412 µm`). Disks are world-scaled (so they zoom and stay
  circular), colored by `type` from a color-blind-friendly categorical palette (first-seen order),
  drawn with ~0.7 alpha and a thin stroke, and accompanied by a legend (swatch + name + count). A
  "show cells" toggle hides them so the heatmap can be read alone.
- **Substrate heatmap.** The active substrate is rendered over the voxel grid with a
  perceptually-uniform (viridis-like) colormap; `NaN` voxels (clipped) are transparent. A
  `ComboBox`, ◀ / ▶ buttons, and Left/Right arrow keys all cycle the active substrate (wrapping at
  the ends) via one shared active index.
- **Colorbar + range.** A vertical colorbar sits right of the plot with `cmax` directly above and
  `cmin` directly below it. A number in either box pins that end of the color range; empty means the
  current substrate's data max/min (recomputed on substrate change, ignoring `NaN`) and is shown as
  the box's prompt text. Unparseable input is treated as empty and flagged inline; degenerate ranges
  don't divide by zero.
- **Zoom.** `xmin/xmax/ymin/ymax` boxes around the plot pin the visible world rectangle; empty uses
  the full domain bound for that side. Commit on Enter or focus-loss; `min ≥ max` falls back. The
  heatmap and cells are clipped to that window rectangle, so narrowing a single axis is a true crop.
- **Coordinate frame.** The world→screen transform uses equal x/y scale (letterbox) and flips y, so
  the largest µm-y is at the top of the plot (tissue right-side-up). Because the scale is equal on
  both axes, zooming one axis alone on a non-square domain yields a narrow band (a crop, not a
  stretch); tighten both axes to magnify a region while keeping disks circular.
- **Entry points.** *Build initial conditions…*, *Sample substrates…*, and *Place cells…* each offer
  "Preview results" on success, opening the viewer on the in-memory results (no file parsing). A
  standalone *Extensions → BIWT → View results…* loads a saved substrates or cells CSV (and its
  matching sibling, when present) by reconstructing the grid from the CSV coordinates.
- **Architecture.** All value→pixel math (radius, colormap, autorange, world→screen) is pure
  `:core` (`io.github.drbergmanlab.biwt.core.viz`) with unit tests; rendering is JavaFX in
  `qupath.ext.biwt.abm.viz` and validated manually.

**Acceptance criteria:**

- A cell near the tissue top renders near the top of the plot (y-flip correct); disks stay circular
  under zoom (equal scale).
- Cycling substrates (dropdown, arrows, keyboard) stays in sync and wraps; the colorbar and autorange
  update to the new substrate.
- `NaN` substrate voxels are transparent; an empty `cmin`/`cmax` autoranges; a pinned value clamps.
- With no substrates, the substrate controls and colorbar are hidden; with no cells, the toggle and
  legend are hidden.
- A saved substrates CSV reopens with the grid and values reconstructed (round-trips through
  `ResultsCsvLoader`).

---

## Deferred Features (not in v0.1.0)

### Coordinate-origin radio

**Goal:** Let the user pick image-center vs image-top-left vs user-defined
from the wizard.

**Notes:** Image-center is currently hard-coded. The `CoordinateOrigin` enum
already supports both standard options; `VoxelGrid.cover` honors them.
A user-defined origin would require a third option that prompts for
`(x_origin, y_origin)` in µm and shifts the grid accordingly.

### Channel math — broader vision

> See the standalone **Feature: Channel Math** section below for the full
> behavioral spec. The OD-sum channel that ships ahead of it is the
> first concrete instance of this feature family.

---

## Feature: Channel Math (planned)

**One-line description:** Let the user define a substrate as an arbitrary
expression over the image's available channels.

**Priority:** Planned (post-v0.1.0).

### Motivation

The MVP supports raw channels and the three QuPath color-deconvolved
channels (H / E / Residual). Real workflows also need:

- **Optical density sum** — `-log10((R+ε)/255) + -log10((G+ε)/255) + -log10((B+ε)/255)`
  (the "tissue thickness" proxy used in many digital pathology pipelines).
  Ships as a built-in channel choice before general channel math is
  available, because it has no parameters worth exposing.
- **Linear combinations of deconvolved channels** —
  e.g. `0.8 * Hematoxylin - 0.2 * Eosin` to weight cell-density signal
  while subtracting some cytoplasm noise.
- **Per-channel transforms with parameters** —
  e.g. `log(R + 1)`, `clip(H, 0, 1.5)`, `(R - 50) / 200`.
- **Multi-channel arithmetic** — e.g. ratios for two-stain experiments,
  differences for change-detection.

A general "type an expression" facility covers all of the above and lets
the user explore without code changes.

### Behavioral specification

- The substrate dialog's channel dropdown gains an "Expression…" entry
  (always present, regardless of image type).
- Choosing it expands a text area where the user types a formula such as
  `0.5 * H - 0.3 * E + R / 255`.
- Identifiers in the expression bind to channel names available in the
  current server:
  - Raw channel names verbatim (case-insensitive match) — e.g. `R`, `G`,
    `B`, `DAPI`, `FITC`.
  - Deconvolved channel names — `H`, `E`, `Residual` for an H&E image.
  - Built-in transforms: `OD_R`, `OD_G`, `OD_B`, `OD_sum`.
  - A channel whose name has a space or other punctuation (e.g. an unnamed `Channel 0`, or
    `DAPI nuclei`), or that collides with a builtin function name, is referenced by wrapping it in
    square brackets: `[Channel 0]`, `[DAPI nuclei]`, `[log]`. Bare identifiers are unchanged. The
    palette "insert" buttons emit the bracketed form automatically when needed.
- Supported operators: `+ - * / ^` and grouping with parentheses.
- Supported functions: `log`, `log10`, `exp`, `sqrt`, `abs`, `min`, `max`,
  `clip(value, lo, hi)`.
- Expression compilation happens once at substrate-add time. Errors
  (unknown identifier, malformed syntax, division by literal zero) become
  inline warnings under the text area.
- A "Preview…" button samples the expression at five user-selectable
  pixels and shows the values, so the user can sanity-check before
  committing.
- The committed expression is stored as a string in the saved-state
  (when project save lands) so subsequent runs reproduce the same result.

### Implementation approach

**Parser:** A small recursive-descent parser hand-rolled in `:core`. ~200
LOC for the supported grammar. Rejected:

- *JSR-223 (Nashorn).* Removed in Java 17+; not available on our toolchain.
- *GraalVM JS.* ~30 MB dependency for a small feature; rejected.
- *`exp4j`, `mXparser`, etc.* Light, but each adds an externally-versioned
  dependency that QuPath users may already have transitive conflicts with.
  Rolling our own avoids that and keeps the dependency surface clean.

**Server wiring:** Implement `ChannelMathTransform implements ColorTransform`
that takes the compiled expression + a name-to-band-index map. In
`extractChannel`, evaluate the expression per pixel using cached per-band
arrays (same pattern as `OpticalDensitySumTransform`).

**Dependency on existing code:**

- Builds on `OpticalDensitySumTransform` (task #12) — same `ColorTransform`
  shape, same `applyColorTransforms` wiring.
- Lives in `:core` so headless callers can `new ChannelMathTransform(expr)`
  without the GUI. The GUI adds the text-area + Preview UX on top.

### Acceptance criteria

- Typing `0.5*H - 0.3*E` over an H&E image produces a single-channel
  output whose values match the hand-computed combination at sampled
  pixels.
- An unknown identifier (`X`) shows "Unknown channel `X`" inline; the
  Add button stays disabled.
- `[Channel 0]` (or any spaced/punctuated channel name) parses to a single
  channel reference and resolves against the same extractor; an unterminated
  `[` is flagged inline.
- `clip(H, 0, 1.5)` clamps negative-H pixels to 0 and high values to 1.5.
- The committed substrate name + expression survives a round-trip through
  the save-state once that lands.

### Open questions

- **Should expressions live in PRD's Project Save feature too?** When
  save-state ships, we want to persist expressions alongside the simpler
  channel-index substrates. Probably one polymorphic `Substrate` record
  with two variants: indexed vs expression.
- **Preview pixel picker.** Five fixed positions (corners + center) vs
  user-clicked positions? Probably the latter, but it requires a click
  callback on the QuPath viewer.
- **Case-sensitivity.** QuPath channel names are case-sensitive in the
  metadata but most users write them however; we'll match
  case-insensitively but warn when an exact match isn't unique.

### Overlapping kernels

**Goal:** Separate window size and stride so windows can overlap.

**Notes:** `SamplingKernel` already has the two fields; only `BiwtSampler.plan`
ties them together by passing `stridePx` for both.

### 3D / z-stack support

**Goal:** Sample across z-planes; export rows with a per-row `z` value.

**Notes:** PhysiCell `Cartesian_Mesh::resize` is already 3D. The clip mask
extends naturally — currently it's a 2D Shape; in 3D each z-plane could have
its own mask, or we assume the same mask applies to every plane.

### Per-axis pixel sizes

**Goal:** Drop the square-pixel requirement.

**Notes:** `SamplingKernel` would need separate `windowSizePxX/Y` and
`stridePxX/Y`. `VoxelGrid.cover` already supports `dxMicrons ≠ dyMicrons`.

### Tile-shared multi-channel sampling

**Goal:** Read each tile once and sample all chosen channels from it,
instead of one full pass per substrate.

**Notes:** Today the sampler loops `for spec : substrates → for voxel`.
Switching to `for voxel → for spec` is the obvious refactor, but it
requires passing the full substrate list into the sampler. Expected
speedup vs current parallel approach is roughly N× / number-of-workers,
so probably ~2× additional on top of v0.1.0's parallelization.
