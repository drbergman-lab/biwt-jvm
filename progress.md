# progress.md — biwt-jvm Session Journal

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
