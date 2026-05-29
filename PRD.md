# Product Requirements Document — qupath-extension-biwt

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

- `VoxelGrid.cover(...)` returns the smallest integer-step-multiple grid
  covering the annotation in each axis.
- `xCenter(i) = xStart + (i + 0.5)·dx` (PhysiCell convention).
- `yCenter(j) = yStart - (j + 0.5)·dy` — y is flipped because image rows go
  top→bottom but math +y is up.
- `CoordinateOrigin.ABM_DOMAIN_CENTER`: grid center maps to (0, 0).
  Spans `[-W/2, +W/2] × [-H/2, +H/2]` in µm — the symmetric domain PhysiCell
  expects, where `W` and `H` are the grid extent (not the image extent).
- `CoordinateOrigin.ABM_DOMAIN_TOP_LEFT`: grid top-left maps to (0, 0); the
  domain spans into the fourth quadrant (x ≥ 0, y ≤ 0).

The origin tracks the **voxel grid**, not the image — so when an annotation
is the domain, the (0, 0) point sits on the annotation no matter where it
happens to be on the slide.

**Acceptance criteria:**

- Voxel j = 0 (image top) has the **largest** y; voxel j = ny − 1 has the smallest.
- For an annotation 100 µm × 100 µm with dx = 20 µm and
  `ABM_DOMAIN_CENTER`: 5 × 5 grid, centers at {-40, -20, 0, 20, 40} on each axis
  — same symmetric output regardless of where the annotation sits on the slide.

---

## Feature: Substrate Sampler

**One-line description:** Average channel pixel values inside the annotation
for each voxel.

**Priority:** Must-have.

**Behavioral specification:**

- For each voxel `(i, j)`, build a pixel-space window of size
  `kernel.windowSizePx` starting at
  `(domain.xMinPx + i·stride, domain.yMinPx + j·stride)`.
- Clip the window to image bounds (the grid may overhang by up to
  `stride − 1` pixels per side).
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
- Iteration order: outer loop on `j`, inner on `i`.
- Numbers: integers printed without trailing zeros where possible;
  `NaN` for unknown values.
- Throw `IllegalArgumentException` for mismatched dimensions or empty
  substrate list.

**Acceptance criteria:**

- Header row matches the schema.
- Row count = `nx · ny`.
- Round-trip parse with a CSV library returns the same values.
- Sample row matches a hand-computed expectation
  (`5,5,0,7` for a 2 × 2 grid of uniform value 7 at the image-top-left origin).

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
