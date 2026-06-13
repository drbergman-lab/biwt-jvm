# progress.md — biwt-jvm Session Journal

## Session: Back navigation in the wizards (2026-06-12)

### Goal

Let the user step back to an earlier wizard screen (fix a voxel size, add a forgotten substrate)
without canceling and losing entered state.

### Key design decisions

**Back lives on our windows; remaining built-ins stay forward-only.** A built-in JavaFX OK/Cancel
dialog can't carry an extra button, so to give a screen a Back button we render it as our own
`Stage`. The plan-confirmation screen (computed grid + PhysiCell domain) was the obvious candidate —
it's where the user first sees the consequence of the voxel size — so it moved off
`Dialogs.showConfirmDialog` into `WizardSupport.confirmWithBack` (Back / Cancel / Proceed). To avoid
the code-like look of an all-monospace blob, the helper takes the prose summary and the domain block
separately: prose renders in the UI font, the x/y/z bounds sit in a bordered monospaced "data card"
so the columns align, with a bold "Proceed with these settings?" prompt. Back buttons now appear on: the plan-confirmation
screen, the substrate-definition dialog, and the build wizard's save-target dialog. Back hops to the
previous *our-window* (always the params screen for the confirm/substrate screens), re-running the
remaining built-in gate (the domain picker, a `showChoiceDialog`) on the way forward. The params
screens have no Back button of their own — they're the first custom window — but they are Back
*targets*, re-opened pre-filled with the prior values. The initial reminder confirm, the domain
picker, and the native file chooser stay plain OK/Cancel.

**A tiny `Nav<T>` instead of a wizard framework.** Each navigable dialog returns
`Nav.next(value) | Nav.back() | Nav.cancel()` (was value-or-`null`). Each command's `run()` became a
hand-rolled phase machine (`enum Phase` + `while/switch`) rather than a generic engine — with only
two commands and 2–3 custom screens each, explicit phases read far clearer than a step-list driver,
and they handle the conditional substrate step (on/off decided at the params screen) without a
dynamic step list. Cancel of a built-in gate still aborts the whole wizard (unchanged behavior);
only the Back *button* navigates.

**State preservation.** `SubstrateDialog.show` gained an `initial` list (re-seeds the committed
substrates + list view); `promptForInputs`/`promptForPlanInputs` take a `prior` to pre-fill fields;
`chooseSaveTarget` takes a `prior` to keep a browsed output folder. When substrates are toggled off
on a back-and-forward pass, the build wizard clears the stale specs/transforms/channelSet in the
DOMAIN phase so a no-substrate build doesn't carry a previous visit's substrates.

**Cell wizard left linear.** It has a single custom screen (options) with nothing before it but the
detection check, so there's nowhere to go back to — no Back added.

## Session: Non-modal wizard + dialog polish (2026-06-12)

### Goal

Let the user pan/zoom the QuPath image while the wizard is open, and fix confirmation dialogs whose
"Cancel" button read wrong.

### Key design decisions

**Non-modal step dialogs + an image-swap guard.** JavaFX modality is all-or-nothing per window —
there is no "allow viewer pan/zoom but block other input" mode, and QuPath has no "lock the current
image" API. So the five custom step dialogs (params + substrate definition in the substrate wizard,
params + save-target in the build wizard, options in the cell wizard) switch from
`APPLICATION_MODAL`/`WINDOW_MODAL` to `Modality.NONE`. `showAndWait()` is kept: its nested event
loop keeps every window live while preserving the sequential `run()` flow, so no threading change.
The one hazard a non-modal dialog opens up — switching the *active image* mid-wizard — is caught by
`WizardSupport.confirmSameImage`, called right before each command commits (image identity check;
aborts with an error on mismatch). The ABM domain is already snapshotted to pixel bounds at the
domain-selection step, so moving/deleting the annotation afterward can't corrupt the output —
image-swap is the only thing worth guarding. The progress dialogs were already `Modality.NONE`.

**Yes/No for post-export offers.** The two prompts that fire *after* a successful export — "update a
PhysiCell config?" and "preview the results?" — were `showConfirmDialog` (OK/**Cancel**), but there
is nothing to cancel at that point; they're plain yes/no questions. Switched to
`Dialogs.showYesNoDialog`. The four wizard-step gates ("Continue?"/"Proceed?") keep OK/Cancel —
there, Cancel genuinely aborts the wizard.

## Session: Viewer zoom + spaced channel names (2026-06-12)

### Goal

Two v0.5.0 defects: (1) the viewer's `xmin/xmax/ymin/ymax` boxes appeared inert, and (2) the
channel-math parser couldn't reference a channel whose name has a space (an unnamed `Channel 0`,
or e.g. `DAPI nuclei`).

### Key design decisions

**Zoom: clip to the view window, keep equal aspect.** Root cause was not the transform but the
draw: `WorldToScreen` letterboxes with one equal x/y scale, so on a tall-thin domain the **y-axis
governs the scale**; narrowing only `xmin/xmax` left the scale unchanged, and `redraw()` drew the
heatmap/cells at the *full grid extent* relying on the Canvas to clip — so an x-only window just
re-centered the same band by a few pixels. Fix is GUI-local: `redraw()` now sets a rectangular clip
to the view-window's screen rect (from the resolved view via the transform), draws inside it, and
strokes the plot border around that window. Equal aspect is kept (PRD invariant, disks stay round),
so an x-only zoom on a tall domain legitimately yields a thin vertical strip — a *true crop*; tighten
y as well to magnify. Considered stretching the window to fill the plot (independent x/y scale) and
rejected it: it turns cells into ellipses and breaks the equal-scale invariant.

**Spaces: bracketed identifiers `[name]` in the grammar.** `ExpressionParser` identifiers follow
Java rules, so a space terminated the name and the palette "insert" buttons emitted unparseable raw
labels. Added a `[...]` form: inside brackets the parser reads to the closing `]`, trims, and emits
one `IdentifierRef` whose name is the inner text (already the extractor-map key, so resolution and
case-insensitive matching are unchanged). Square brackets chosen over backticks/double-quotes — no
collision with any math token (`+ - * / ^ ( ) ,`), spreadsheet/SQL-familiar, and they also let you
reference a channel that collides with a builtin name (`[log]`). A `]` inside a name is unsupported
(rare). The palette now emits the bracketed form for any label that isn't already a bare identifier.

### Open questions

- None blocking. A name containing `]` is unsupported; revisit only if a real image needs it.

## Session: Results Visualizer (2026-06-09)

### Goal

Let a researcher *see* a build before handing it to PhysiCell: cells drawn as type-colored disks
over the active substrate as a colormapped heatmap, in the shared ABM µm frame. Cycle substrates,
pin the color range, and zoom — both from the in-memory build results (post-build preview on all
three wizards) and from saved CSVs (a standalone *View results…* item).

### Key design decisions

**Strict core/GUI split for the math.** Every value→pixel decision lives in a new headless,
unit-tested `io.github.drbergmanlab.biwt.core.viz` package: `CellGeometry` (volume→equivalent-sphere
radius, with PhysiCell's 2494 µm³ default for cells lacking a volume), `ColorMap` (viridis-like,
packed-ARGB `argb`/`colorAt`, NaN→transparent, degenerate range→midpoint), `DataRange`
(NaN-skipping min/max for autorange), and `WorldToScreen` (letterbox equal-scale + y-flip). The
extension's `ResultsViewer` only drives the `Canvas`; the int↔`javafx Color` bridge sits in
`CategoricalPalette`. This keeps the historically-troublesome bits (the y-flip especially) testable
without a GUI — 23 new core tests.

**Y-flip is the load-bearing invariant.** PhysiCell math +y is up; screen rows go down.
`WorldToScreen.screenY` maps the largest world-y to the top of the plot, and the heatmap
`WritableImage` is written with row 0 = top (`imgRow = ny-1-k`). Substrate `values[k][i]` has
`k=0` = bottom (confirmed against `SubstrateCsvWriter`), so the image and the cells agree. This is
the exact spot the old "upside-down scatter" bug lived; a `WorldToScreenTest` pins it.

**Heatmap via a voxel-resolution `WritableImage`, not per-voxel `fillRect`.** One `PixelWriter`
pass builds an `nx×ny` image (cached, rebuilt only on substrate/cmin/cmax change), then a single
`drawImage` with smoothing off maps the grid extent onto the plot — cheap for the README's
625×575 grids and allocation-free on resize/zoom redraws. NaN voxels are written as transparent
(ARGB 0) so clipped voxels read as "no data".

**One shared frame, cells toggled — not tabs.** Cells and substrate share the µm frame, so the
viewer overlays them with a "show cells" checkbox rather than splitting into tabs. Substrate
cycling is driven by a single active index synced across the `ComboBox`, ◀/▶ buttons, and Left/Right
keys (an event filter that defers to a focused `TextField` so arrow-key editing still works).

**Limit/cmin/cmax boxes: empty = resolved default, commit on Enter/focus-loss.** Empty `cmin`/`cmax`
autorange the active substrate (recomputed on every substrate change) and surface the auto value as
the box's prompt text; empty `xmin…ymax` fall back to the model's default bounds. Unparseable input
is treated as empty and flags the box with a red border (no crash); `min ≥ max` falls back to the
default for that axis. No Apply button.

**CSV-loading viewer reconstructs the grid from coordinates.** `ResultsCsvLoader` (core) parses a
cells CSV into `CellRecord`s and a substrate CSV back into a `VoxelGrid` + `NamedSubstrate`s by
taking the sorted unique x/y centers (`nx/ny`, `dx/dy` from their spacing, bounds = center ∓ d/2)
and re-binning each row into `values[k][i]` (`k=0` = smallest y). `BiwtViewCommand` also auto-loads
the matching `-cells.csv`/`-substrates.csv` sibling so a full build opens with both layers. A small
RFC-4180 line parser handles quoted, comma-bearing type names.

**Preview offered on all three wizards.** The build task now returns the in-memory `SamplingResult`
+ `CellPlacementResult` (was a bare status string) so `WizardSupport.offerResultsPreview` can open
the viewer with no file parsing; `Sample substrates…` and `Place cells…` offer their single-layer
results the same way. This is staged for a future release (v0.5.0) — no version bump here.

### Rejected approaches

**Per-voxel `fillRect` heatmap.** Fine for tiny grids but re-rasterizes every redraw; the cached
`WritableImage` is strictly better at the grid sizes BIWT produces.

**Pulling QuPath `PathClass` colors for cell types.** The in-memory `CellRecord` carries only the
type *string*, so the viewer auto-assigns from a color-blind-friendly categorical palette
(Okabe–Ito + extras) in first-seen order, with an optional `typeColors` override left on the model
for a future caller that has the classes handy.

### Tests added

23 in `:core` viz: `CellGeometryTest`, `ColorMapTest`, `DataRangeTest`, `WorldToScreenTest`
(y-flip, letterbox, inverse round-trip), `ResultsCsvLoaderTest` (substrate grid round-trip incl. a
NaN voxel, cells with/without volume, quoted type). Core suite now 101 (was 78). The JavaFX viewer
is validated manually per the repo's convention.

---

## Session: v0.4.0 — unified initial-conditions flow (2026-06-06)

### Goal

Tie the two export methods together so users can't get the coordinate frame wrong, emit the
PhysiCell domain from every path, and let the tool update the user's config directly. Keep it
tight and intuitive.

### Key design decisions

**Center-only origin (GUI).** Dropped the center/top-left radio + origin-preview canvas from both
dialogs; the GUI always uses annotation-center. `ABM_DOMAIN_TOP_LEFT` stays in the enum for
headless callers. With one origin and one shared annotation, substrates and cells can't land in
mismatched frames — most of the "idiot-proofing" falls out of removing the choice. Also dropped the
explicit "(0,0) = center" note as too loud ("a smell"): the domain-bounds readout at confirmation
is the honest, concrete transparency.

**Cells emit a bounds-only domain.** Cells need no voxel size, so a cell export's domain is just the
annotation's raw `x_min/x_max/y_min/y_max` (PhysiCellDomain.ofAnnotation, voxelSized=false).
`domainTags()` then returns only those four, so the sidecar XML and the config patch touch only the
x/y bounds — the user's `dx`/`dz`/`z` stay as they are. Substrates still emit the full voxel-sized
domain. (When both run in the combined wizard, the full substrate domain is the one emitted.)

**Config auto-patch via targeted text-splice, not DOM.** `PhysiCellConfigUpdater` rewrites the first
`<domain>…</domain>` block's child values in place, preserving attributes (`units="micron"`),
comments, indentation, and the rest of the file — a DOM round-trip would reformat the whole config,
which users diff. Writes a `.bak` first. Iterates `PhysiCellDomain.domainTags()`, so a bounds-only
domain naturally patches only x/y. Kept as a **post-export prompt** (not an in-dialog checkbox) to
keep the parameter dialogs uncluttered — the user leaned checkbox but flagged busyness, and the
prompt resolves exactly that.

**Annotation picker.** `DomainDetector.fromAnnotation(imageData, chosen)` builds the domain from a
user-selected annotation. The GUI (`WizardSupport.chooseDomain`) uses the single `abm_domain`
directly when present, else shows a choice of the image's rectangle annotations + "whole image";
with several `abm_domain` it asks which.

**Core overloads for the GUI.** `BiwtSampler.plan(AbmDomain, step, origin)` and
`BiwtCellPlacer.place(imageData, AbmDomain, origin, options)` let the wizard plan/place against an
already-picked domain (the detection-based methods now delegate to these).

**Combined wizard reuses, doesn't duplicate.** `BiwtBuildCommand` calls the existing
`buildChannelSet` + `SubstrateDialog` (made package-accessible) and the core façades; one folder +
base-name "Save outputs" dialog writes the (up to) three siblings. That single save dialog is the
"ask the user" that gives both location control and predictable auto-named siblings.

### Tests

Core 78 (added PhysiCellConfigUpdater (5), bounds-only PhysiCellDomain, DomainDetector.fromAnnotation
(2)); extension 13; 91 total, all green. The combined wizard / dialogs are GUI-only (verified by
build + manual), consistent with the rest of the extension.

### Status

v0.4.0 feature work complete; version bumped. Remaining: real-image smoke test of the combined
wizard + config-patch, then tag / GitHub release / catalog entry.

---

## Rejected approach: per-image substrate normalization (2026-06-04)

Considered adding a per-substrate "clamp at the Nth percentile (e.g. 95th), then
normalize and rescale to a target range." Rejected.

**Why it's flawed.** A percentile computed *per image* normalizes each slide to its own
distribution, so the same raw intensity maps to different exported values across the
cohort — breaking the cross-image comparability that's usually the whole point of
running one ABM over many slides. The "95th" is also a hyperparameter tuned by eye on a
single image, with no guarantee it transfers; you'd only find out after running the
cohort. And it doesn't stop there — admit a per-image auto-stat and the next asks
(low+high percentiles, per-channel, robust estimators, then "make it consistent across
images") grow a normalization subsystem inside what should be a coordinate bridge.

**The line that keeps BIWT tight.** BIWT should compute only what depends on *the current
image alone, deterministically*. A tuned per-image statistic violates that. Anything that
must be *consistent across images* needs a shared reference — a cohort-level operation,
out of per-image scope.

**Honest alternatives (none adopted):**
- *Downstream (recommended):* export raw; normalize across the set of CSVs in a small
  post-processing script, where the whole cohort is in hand and comparability is
  guaranteed.
- *Fixed cohort-wide constant:* a user-supplied clamp/scale applied identically to every
  image (deterministic, cohort-safe); a percentile could only ever *suggest* a value from
  one image, never auto-commit it.
- *Real cohort batch mode in BIWT:* sweep the dataset, compute one shared reference, apply
  it — the correct in-tool version, but a substantially bigger feature.

Decision: drop it for now; revisit only if the need sharpens, and then at cohort altitude.

Note: BIWT's existing channel-math already offers a per-pixel `clip(value, lo, hi)` for
*fixed-bound* clamping, which is deterministic and cohort-safe — distinct from the
data-derived percentile clamp rejected here.

---

## Session: PhysiCell-frame correction (2026-06-04)

### Goal

Groundwork for cell-placement export (see next session) forced a hard look at the
substrate coordinate frame. The discretization must match PhysiCell's mesh
*exactly* so a user can paste domain bounds into their config and have cells and
substrates land in the same voxels. This session corrects the frame; cell export
builds on it.

### The core realization (after several wrong turns)

PhysiCell builds its mesh by **anchoring at the domain minimum corner** and
striding up: `center(k) = min + (k+0.5)·d`, `k = 0` at the min, up to `max`. The
domain is an integer number of voxels; when the step doesn't divide the
annotation, the leftover is a partial voxel at the **far** (top/right) edges.

The old code got two things wrong relative to this:

1. **Y was top-anchored.** The sampler tiled pixel windows from the annotation
   *top* (`yMinPx`) downward, and `VoxelGrid` pinned the grid *top* at the origin.
   For a 410 µm tall annotation at 20 µm steps that produced y-centers
   `{-10, -30, …, -410}` (overhang at the bottom). PhysiCell, anchored at the
   bottom, wants `{-400, -380, …, 0}` (overhang at the top). These are **disjoint
   sets** a half-step apart — not a reordering; the kernels aggregate different
   pixels. (I initially, wrongly, claimed they were equivalent. They are not.)
2. **CENTER centered the rounded grid, not the annotation.** Old CENTER used
   `xStart = -gridW/2` (rounded). New CENTER uses `xMin = -annW/2` (actual), so
   the **annotation** is centered on (0,0); the domain *bounds* then come out
   mildly asymmetric on a non-dividing step (e.g. `x ∈ [-20.5, 39.5]`). The user
   explicitly accepted this: "that's the cost of not having your domain
   well-matched with your voxel size."

### Key design decisions

**One origin-agnostic discretization; origin only sets the anchor.** The
algorithm is always "anchor at `(x_min, y_min)`, half-step in, stride to
`(x_max, y_max)`." `CoordinateOrigin` only picks the numeric anchor:
`TOP_LEFT → (0, -annH)`, `CENTER → (-annW/2, -annH/2)`. Nothing else in the
math cares which it is. (User's framing — and it collapsed a lot of special-casing.)

**`VoxelGrid` index `k` is now bottom-up (PhysiCell order), no flip in the grid.**
Renamed fields `xStart/yStart → xMin/yMin`; `yCenter(k) = yMin + (k+0.5)·dy`.
The image-row→math-y flip moved entirely into the **sampler**, which anchors its
vertical windows at the annotation bottom (`yMaxPx`) so row `k = 0` averages the
image's bottom rows. Added `xMax()/yMax()` accessors.

**CSV rows now emitted in PhysiCell mesh order.** Bottom-left voxel first, x
inner (`x_min → x_max`), y outer (bottom → top). Because the value array and the
grid are both bottom-up now, the writer's existing `for k` loop produces this for
free. Safe under both coordinate-matched and positional PhysiCell loaders.

**Domain bounds are surfaced (`PhysiCellDomain`).** New record derived from the
grid: `x_min…z_max, dx, dy, dz, nx, ny, nz`, with a 2D z-slice (`dz = dx`,
`z ∈ [-dz/2, dz/2]`, `use_2D = true`). `toXml()` renders a paste-ready `<domain>`
block; the wizard shows `summary()` in the plan dialog and writes a
`*-physicell-domain.xml` sidecar next to the CSV on save. Chose a **sidecar**
over an inline CSV comment so PhysiCell's numeric line parser isn't disturbed.

### Tests

- `VoxelGridTest`: rewrote the CENTER + y-axis tests for the new bottom-up,
  annotation-anchored model; added `topLeftAnchorsBottomLeftCornerWithOverhangTopRight`
  pinning the canonical 310×410 example (`x∈[0,320], y∈[-410,10]`, centers
  `10…310` / `-400…0`).
- `SubstrateCsvWriterTest`: `writesRowsInPhysiCellMeshOrderBottomUp` pins bottom-
  first ascending-y output.
- `BiwtSamplerTest`: end-to-end ground truth now `99 + 20i - 20k`; round-trip
  coords bottom-up.
- `SubstrateSamplerTest`: unchanged — its fixtures are uniform / column-gradient /
  full-height-mask, so the vertical re-anchor doesn't move the assertions.
- New `PhysiCellDomainTest` (3): bounds derivation, XML rendering, fractional bounds.

Core 57 tests + extension 13 = 70, all green.

### Status

Part 1 (substrate frame) done. Part 2 (cell placement export) builds on the same
anchor model + a shared pixel→µm transform. Docs (PRD/README) updated to match.

---

## Session: Cell placement export (2026-06-04, part 2)

### Goal

The main ask: initial cell placement for the ABM. User's workflow is
segmentation → centroids → label type → export CSV, and they're happy leaning on
existing QuPath tools. So BIWT is the **linker**, not a segmenter: it reads
whatever detections exist (StarDist/Cellpose/InstanSeg/built-in), places them in
the same frame the substrate export uses, and writes a PhysiCell cell-IC CSV.

### Key design decisions

**Shared `CoordinateTransform`, extracted from the anchor math.** Put the
`(xMin, yMin)` computation on `CoordinateOrigin.minCornerMicrons(...)` as the
single source of truth; both `VoxelGrid.cover` and the new `CoordinateTransform`
read it, so cells and substrates are guaranteed to share the frame. The
transform is the continuous affine pixel→µm map (with the y-flip); cells are
placed at exact centroids (no voxel snapping). Pinned by a test that a cell on a
voxel center gets that voxel's coordinates.

**CSV format: headered, named types** (`x,y,z,type[,volume]`), per the user —
`type` is the QuPath `PathClass` name and must match a PhysiCell
`<cell_definition>`. `CellPlacementOptions.typeNameOverrides` allows a
QuPath-class → PhysiCell-name remap (identity by default); the wizard doesn't
expose the map yet.

**Volume is an optional column** (user wanted it toggleable — "some users will
use this, others discard it"). Equivalent-sphere from the segmented area:
`r = sqrt(area/π)`, `V = (4/3)π r³`, area in µm² via per-axis calibration.

**No square-pixel requirement for cells.** Unlike the substrate sampler (which
needs square pixels for its single stride), the cell transform and the area→µm²
conversion both honor per-axis calibration, so non-square pixels are fine.

**Clip + unclassified handling.** Drop cells whose centroid is outside the domain
clip mask (matches the substrate clip-to-annotation rule). Unclassified
detections are dropped by default, or assigned a configurable type name.

**Cell placement runs synchronously.** It's geometry only (centroids + areas, no
pixel reads), so the wizard does it on the FX thread — no background Task needed,
unlike substrate sampling.

### Structure

Mirrors the substrate side: façade `BiwtCellPlacer` + `CellPlacementResult` in
core root; `CellRecord`, `CellExtractor`, `CellPlacementOptions` in `core.cells`;
`CellCsvWriter` in `core.export`; `BiwtCellCommand` wizard +
*Place cells…* menu item in the extension.

### Tests (13 new, core 70 + ext 13 = 83 green)

- `CoordinateTransformTest` (4): canonical (122,-133) placement + y-flip, center
  offset, agreement with `VoxelGrid` at a voxel center, non-square pixels.
- `CellExtractorTest` (5): placement + clip-drop + unclassified-drop, default
  type assignment, volume-from-area, type-name override, the volume formula.
- `CellCsvWriterTest` (4): headered named types, optional volume column, comma
  escaping, empty-list rejection.

### Open questions / future

- **Type-mapping UI.** Headless `typeNameOverrides` exists; the wizard would need
  a small QuPath-class → PhysiCell-name table to expose it. Deferred.
- **Nucleus vs cell area for volume.** Currently uses the detection's main ROI
  (cell boundary). Could add a nucleus-area option for cell objects.
- **Cell-side domain bounds.** Cells share the substrate frame but don't define a
  voxel step, so the `PhysiCellDomain` bounds come from the substrate run. A
  cells-only user gets bounds by also running the (cheap) substrate plan, or sets
  them from the annotation. Not surfaced in the cell wizard yet.

### Status

Part 2 complete. Headless `BiwtCellPlacer` + wizard, all tests green. PRD gains a
Cell Placement Export feature; README Implementation Status updated.

---

## Session: CI + transform tests (2026-06-01)

### Goal

Harden v0.3.0: add the two missing unit tests (the OD-sum and channel-math
transforms were validated by eye, not by test) and stand up CI.

### What shipped

- `OpticalDensitySumTransformTest` (6) and `ChannelMathTransformTest` (7) in a
  new `qupath-extension/src/test` source set. Both transforms ignore the
  `ImageServer` arg of `extractChannel`, so tests construct a small
  `BufferedImage` (TYPE_INT_RGB, `setRGB`) and pass `null` for the server —
  no GUI or real server needed. The channel-math test also pins that
  `readOpticalDensitySum` agrees with the standalone `OpticalDensitySumTransform`
  (they share the OD formula) and that `OD_sum` works as an expression identifier.
- `.github/workflows/ci.yml` — runs `./gradlew test` (both modules, 66 tests)
  on push/PR to main + manual dispatch. Uploads test reports on failure.

### Key decisions

- **CI installs JDK 25 directly via `setup-java`** rather than relying on the
  foojay toolchain download. QuPath 0.7.0 JARs need 25; installing it means
  Gradle both launches on it and satisfies the toolchain spec, skipping the
  per-run foojay fetch.
- **`:qupath-extension` test setup mirrors `:core`'s fix** — needs the explicit
  `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` and
  `tasks.test { useJUnitPlatform() }`; the `qupath-conventions` plugin doesn't
  bring the launcher transitively (same gotcha that bit `:core` originally).
- **CI runs `test`, not `build`.** Tests catch logic regressions cheaply;
  building the shadow jar would pull the full QuPath GUI dependency tree on
  every run for marginal extra coverage. Packaging regressions (like the
  shadow-vs-implementation bug) are rare and caught at release time by the
  smoke test.

### Status

66 tests green locally. `.github/workflows/ci.yml` will run on first push;
README carries a CI badge. No version bump — this is test/infra only, folds
into the next release. (Release notes are GitHub-only, not committed —
`release-notes/` is gitignored for local drafts.)

---


> **Purpose:** Session-level decisions, rejected approaches, and open questions.
> Unlike [PRD.md](PRD.md) (specification) and [README.md](README.md)
> (completion status), this file captures the *reasoning* behind decisions —
> things that would otherwise exist only in ended chat history.

---

## Session: v0.1.0 — MVP (2026-05-28)

### Goal

Ship the MVP per the original design brief
(`my_qupath_plugin_context.html`): a QuPath extension that samples one or
more image channels on a regular grid, clipped to an annotation, and
exports a PhysiCell-format substrates CSV. Strict core/GUI separation.
End-to-end on `test_data/small.tif` and `test_data/large.jpg`.

### Key design decisions

**Two-module Gradle build over single-module-with-discipline.**
The "strict core/GUI separation" constraint could have been satisfied by a
single module with package-level boundaries. Going two-module made the
guarantee compile-time enforceable — `core/build.gradle.kts` simply doesn't
have JavaFX on its classpath, so a stray `javafx.*` import fails to
compile. Worth the modest extra build complexity. Also opens the door to a
future CLI module that depends on `:core` without QuPath.

**Package `io.github.drbergmanlab.biwt.*` for the core.**
GitHub-username reverse-DNS convention (hyphen dropped per Java package
rules). Effectively unique for a non-published lab project; trivially
renameable later if we publish to Maven Central. The extension lives at
`qupath.ext.biwt.abm` per QuPath's third-party recommendation. Asked the
user explicitly: they preferred the umbrella org name even though the
extension uses the QuPath-style namespace.

**JDK 25, not 21.**
First pass set the toolchain to 21 based on a stale assumption about
QuPath's LTS pin. The actual 0.7.0 JARs are class-file version 69 (Java 25).
Fixed by bumping the toolchain to 25 and enabling
`foojay-resolver-convention` 1.0.0 (not 0.10.0 — that version references
`JvmVendorSpec.IBM_SEMERU` which was removed in Gradle 9.x and breaks the
plugin). Gradle now auto-downloads JDK 25 into `~/.gradle/jdks/` on first
build, leaving the user's system JDKs untouched.

**Voxel-grid y-axis flip (caught in user testing).**
First implementation made `yCenter(j) = yStart + (j+0.5)·dy` mirroring the
x formula. A user-supplied Plots.scatter of the exported CSV showed the
silhouette upside-down. PhysiCell math has `+y` up, but image rows go
top→bottom — so voxel `j = 0` (image top) should get the *largest* µm y,
not the smallest. Fixed by changing `yCenter` to subtract, recomputing
`yStart` in `cover()` accordingly, and adding a regression test
`yAxisIsFlippedRelativeToImageRows`.

**Clip-to-annotation rule, not domain expansion.**
The original design doc suggested "silently expand the domain to the
nearest integer multiple of the step size." User overrode this during
planning: don't widen the annotation, just let the voxel grid overhang
and average only the pixels actually inside the annotation. Edge voxels
average fewer pixels rather than over-counting outside-annotation space.
Empty intersections write NaN. Sampler uses `Shape.intersects` to skip
reads for windows that don't touch the mask, and `Shape.contains(window)`
to skip the per-pixel test for interior windows.

**Image-center origin only for MVP.**
The user wants an eventual radio button (image-center | image-top-left |
user-defined origin), but MVP is image-center fixed. `CoordinateOrigin`
enum has both standard options ready; the wizard just always passes
`IMAGE_CENTER`. Promoting the radio is task #11 (post-MVP).

**Modal vs non-modal progress dialog.**
First pass made the progress dialog `WINDOW_MODAL`, which blocked the
QuPath main window during sampling. User asked to be able to pan/zoom
the image. Switched to `Modality.NONE`. Risk: user switching to a
different image mid-sample would produce a stale read. We accept the
risk — the user explicitly asked for it and would see the resulting
`IOException` as an error dialog. Documented the warning in the
"Sampling in progress" header.

**Parallel sampling at the substrate level, not per voxel.**
After v0.1.0 wizard ran the user's 6-substrate test on `large.jpg` in
60 s sequentially, we parallelized at the substrate level rather than at
the voxel level. Each substrate is an independent pass; `ImageServer` is
thread-safe; QuPath caches decoded tiles at the server level, so
overlapping reads are cheap. Got a ~4.3× speedup
(60 s → 14 s on 6 substrates, 625 × 575 voxels). Per-voxel-multi-channel
refactor (the next obvious optimization) is queued.

**Substrate dialog: custom JavaFX modal, not a sequence of standard prompts.**
First pass used `Dialogs.showInputDialog` + `Dialogs.showChoiceDialog`
for each substrate, with Cancel as the "I'm done adding substrates"
signal. User flagged the Cancel-as-confirmation pattern as confusing.
Replaced with a single custom modal stage holding a list of added
substrates, a name + channel form, and explicit Add / Finish / Cancel /
Remove buttons. The design doc §4.4 had specified this layout from the
start — we shortcut it initially and circled back.

**Tooltip → inline label for duplicate-name warning.**
Bound a Tooltip to the disabled Add button to explain why it was greyed
out. Tooltips on disabled JavaFX buttons don't fire (disabled state
swallows hover events). Replaced with an always-visible red "already in
use" label next to the name field; `visibleProperty` + `managedProperty`
both bound so it doesn't reserve layout space when not active.

### Rejected approaches

**`TransformedServerBuilder.pixelSizeMicrons(double, double)`.**
Doesn't exist on `TransformedServerBuilder` — pixel calibration lives on
`ImageServerMetadata`, not the transformed-server pipeline. Test fixtures
now build calibrated servers via
`server.setMetadata(new ImageServerMetadata.Builder(server.getOriginalMetadata()).pixelSizeMicrons(px, px).build())`.

**`shadow(project(":core"))` in `qupath-extension/build.gradle.kts`.**
Made the fat jar ship without core classes, so the wizard couldn't load.
The `qupath-conventions` plugin's `shadow(...)` configuration means
"QuPath provides this at runtime, exclude from the shadow jar" — not
"include in the shadow jar." For our own modules use `implementation(...)`.

**Indeterminate progress spinner.**
Fine for 1–2 s sampling; on `large.jpg` with 6 substrates it spun
silently for 60 s. Replaced with a determinate `ProgressBar` bound to
`Task.progressProperty`, status label bound to `Task.messageProperty`,
and per-substrate updates after each worker completes.

### Tests added (30 passing)

- `VoxelGridTest` (8): PhysiCell-convention centers, cover sizing,
  image-center vs image-top-left origin, y-axis flip regression,
  out-of-bounds rejection.
- `DomainDetectorTest` (8): name match, custom name, each fallback
  mode, non-rectangle rejection, duplicate rejection,
  uncalibrated-image rejection.
- `SubstrateSamplerTest` (6): uniform image, column-gradient,
  edge-clip, full mask-clip, partial mask-clip, channel-index bounds.
- `SubstrateCsvWriterTest` (5): single substrate, multiple substrates,
  NaN handling, dimension mismatch, empty list.
- `BiwtSamplerTest` (4): end-to-end x+y ground truth, non-divisible
  step reconciliation, CSV round-trip, non-square pixel rejection.

### Open questions

- **Channel math expression language.** Custom mini-language vs `exp4j`
  vs GraalVM JS? Punted to the channel-math design entry (task #13).
- **OD-sum formula.** What ε to add inside the log to avoid log(0)?
  Standard choice is 1/256 (one quantization step for 8-bit), but
  QuPath's own pixel classifier uses a slightly different convention.
  Need to check before implementing (task #12).
- **Channel-deconvolution UX when no stains are set.** Today the dropdown
  just doesn't show deconvolved entries. Consider a one-shot prompt:
  "This image has no deconvolution stains set. Configure them now?"
  with a button that opens QuPath's Image → Set image type dialog.

### Status

`v0.1.0` tagged locally. Distribution path: GitHub release first, then
PR to `qupath/qupath-catalog`, then forum.image.sc announcement. See
`README.md` "Distribution" subsection.

Next sessions queued:
- task #11 — coordinate-origin radio (post-MVP)
- task #12 — OD-sum channel (testbed for pixel math)
- task #13 — channel math design entry in PRD

---

## Session: origin anchor refactor (2026-05-28, same day)

### Goal

User flagged that the "Image center" / "Image top-left" labels on the
origin radio were misleading: when the user defined an `abm_domain`
annotation, the (0, 0) point still sat at the image center (often far
from the annotation), not the annotation center. The PhysiCell-natural
expectation is for the origin to track the ABM domain itself.

### Key design decisions

**Anchor on the voxel grid, not the image.**
Renamed `CoordinateOrigin.IMAGE_CENTER` → `ABM_DOMAIN_CENTER` and
`IMAGE_TOP_LEFT` → `ABM_DOMAIN_TOP_LEFT`. The math now depends only on
the grid extent (`nx · stepSize`, `ny · stepSize`) and the origin
choice — never on where the annotation sits on the slide.

For `ABM_DOMAIN_CENTER`: `xStart = -gridW/2`, `yStart = +gridH/2`.
The grid is symmetric around (0, 0) — what PhysiCell expects for a
domain defined by `x_min, x_max = -W/2, +W/2`.

For `ABM_DOMAIN_TOP_LEFT`: `xStart = 0`, `yStart = 0`. Grid extends
into the fourth quadrant.

**Drop the image-dim parameters from `VoxelGrid.cover`.**
The new math doesn't reference image dimensions at all. Removed the
`imageWidthMicrons` and `imageHeightMicrons` parameters; `cover()` is
now `(annotationWidth, annotationHeight, stepSize, origin)`. Also
removed the `annotationXMin/YMin` params since the position on the
slide is irrelevant. Updated `BiwtSampler.plan` to stop computing
image dims.

**Grid-center vs annotation-center for non-divisible steps.**
For a 41 µm annotation with 20 µm step, the grid is 60 µm wide. With
the new semantics, the grid center is at (0, 0) but the annotation
center is offset by ~9.5 µm (half the overhang). Picked grid-center
because it gives PhysiCell-symmetric coords; the half-step offset is
usually negligible relative to the domain size, and the alternative
(annotation-center) would make `x_min, x_max` asymmetric, which
PhysiCell handles awkwardly.

### Test updates

- `VoxelGridTest` — rewrote the cover-related tests with the new API
  shape. Added `abmDomainCenterMakesGridSymmetric` and
  `abmDomainCenterStaysSymmetricWhenStepDoesNotDivideAnnotation` to
  pin the symmetry guarantee. Old `imageCenterOriginShiftsToImageCenter`
  removed (its premise — origin tracks image, not grid — no longer
  applies).
- `BiwtSamplerTest`, `SubstrateCsvWriterTest` — sed rename of enum
  values; assertions unchanged (those tests used annotation = whole
  image or didn't check coordinates beyond the y-flip).

### Wizard updates

- Radio labels: "ABM domain center" / "ABM domain top-left".
- Inline comment in the dialog source explains the wording is
  deliberate (track the domain, not the image).
- Origin preview canvas (the small rectangle + dot graphic) is
  unchanged — its rectangle was always meant to represent the ABM
  domain; the labels now match the implementation.

All 31 core tests green.

---

## Session: origin radio + OD-sum + channel-math PRD (2026-05-28, same day)

### Goal

Three loosely-coupled post-MVP deliverables in one pass:
- Promote the deferred coordinate-origin radio into the wizard.
- Add an "Optical density sum" channel — the first concrete pixel-math
  channel transform.
- Write a PRD section laying out the broader channel-math story.

### Key design decisions

**Combined step-size + origin dialog.**
`Dialogs.showInputDialog(Double)` was fine when step size was the only
parameter. Adding origin would have meant a second prompt, which feels
choppy. Built a small custom `Stage` with the step-size `TextField` plus
a `ToggleGroup` of two `RadioButton`s. Validation (positive number,
parseable) happens inline with an error label so a bad value re-prompts
without losing the radio selection. Default origin remains
`IMAGE_CENTER` to match v0.1.0 behavior.

**OD-sum as a `ColorTransform`, not a custom server.**
`ColorTransform` has only three methods (`extractChannel`,
`supportsImage`, `getName`). Plugging into the existing
`TransformedServerBuilder.applyColorTransforms(...)` pipeline meant no
new sampler code paths. The OD-sum transform sits next to the
deconvolved channels in the channel list and is sampled exactly the
same way as any built-in channel.

**Epsilon = 1.0 (one quantization step on 8 bits).**
Standard pick for OD calculations on 8-bit RGB. White pixels
(value=255) → OD ≈ 0; pure-black pixels (value=0) → OD ≈ 2.41 per
channel, capping the sum at ~7.24. Avoids `log(0)` without distorting
the signal range for typical stained tissue.

**Restricted to `server.isRGB()`.**
The 255 normalization and 3-channel assumption are baked in. For
fluorescence (variable bit depth, arbitrary channel count) OD-sum
isn't well defined — `supportsImage` returns false there and the
choice doesn't appear in the dropdown.

**Channel-math PRD: hand-rolled recursive-descent parser over external libs.**
Rejected GraalVM JS (~30 MB), JSR-223/Nashorn (gone in Java 17+), and
external libs like `exp4j` / `mXparser`. A ~200 LOC recursive-descent
parser in `:core` covers `+ - * / ^`, parens, and a fixed function
list. Cleaner dependency surface, no transitive conflicts with whatever
QuPath ships, and it lives next to the rest of the headless API.

### Rejected approaches

**Passing the radio choice through `Dialogs.showChoiceDialog` after the
step-size input.** Two separate dialogs felt like a hack. Custom dialog
is ~40 lines and looks like part of the wizard.

**Adding OD-sum as a `createLinearCombinationChannelTransform` entry.**
Doesn't include the logarithm — couldn't express the actual formula.
Custom `ColorTransform` was the only path.

### Tests added

None for this session — the new OD-sum transform is exercised through
the wizard's existing pipeline and validated visually by the user on
test_data/. Adding a unit test against a hand-computed pixel would be
worthwhile if we refactor it.

### Status

Origin radio (task #11) and OD-sum (task #12) implemented and built
into a fresh shadow jar. Channel-math PRD section (task #13) added.
`v0.1.0` tag stays at the previous commit; these changes accumulate
toward a future `v0.2.0`.

---
