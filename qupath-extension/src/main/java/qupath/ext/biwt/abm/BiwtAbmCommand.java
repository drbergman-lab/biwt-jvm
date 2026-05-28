package qupath.ext.biwt.abm;

import io.github.drbergmanlab.biwt.core.BiwtSampler;
import io.github.drbergmanlab.biwt.core.SamplingPlan;
import io.github.drbergmanlab.biwt.core.SamplingResult;
import io.github.drbergmanlab.biwt.core.SubstrateSpec;
import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin;
import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import io.github.drbergmanlab.biwt.core.domain.AnnotationNotFoundException;
import io.github.drbergmanlab.biwt.core.domain.AskUserRequiredException;
import io.github.drbergmanlab.biwt.core.domain.DomainDetectionOptions;
import io.github.drbergmanlab.biwt.core.domain.DomainException;
import io.github.drbergmanlab.biwt.core.domain.NonRectangularDomainException;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.gui.QuPathGUI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The interactive wizard launched from <b>Extensions → BIWT → Sample substrates…</b>. Drives the
 * user through:
 *
 * <ol>
 *   <li>A normalization reminder (design doc §6).</li>
 *   <li>Domain detection — auto-find an annotation named {@code abm_domain}; if absent, offer to
 *       use the whole image.</li>
 *   <li>Step-size input in µm.</li>
 *   <li>Plan confirmation — shows {@code nx × ny} voxels and the effective step in µm.</li>
 *   <li>Substrate definition loop — name + channel for each substrate, until the user finishes.</li>
 *   <li>File-save chooser, then sampling on a background thread with a progress indicator.</li>
 * </ol>
 *
 * <p>All UI work is on the JavaFX thread; the actual pixel-reading pass runs on a background
 * thread via {@link Task} so the QuPath UI doesn't freeze on large images. The wizard never
 * touches pixel data directly — it only constructs requests/plans and calls {@link BiwtSampler}.
 */
public final class BiwtAbmCommand {

    private static final Logger logger = LoggerFactory.getLogger(BiwtAbmCommand.class);

    private static final String TITLE = "BIWT";
    private static final double DEFAULT_STEP_MICRONS = 20.0;
    private static final CoordinateOrigin DEFAULT_ORIGIN = CoordinateOrigin.IMAGE_CENTER;

    private final QuPathGUI qupath;
    private final BiwtSampler sampler = BiwtSampler.create();

    public BiwtAbmCommand(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** Entry point — call from the menu-item action handler on the JavaFX thread. */
    public void run() {
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(TITLE, "Open an image first.");
            return;
        }

        // Step 1: normalization reminder.
        boolean go = Dialogs.showConfirmDialog(TITLE,
                "Ensure all image normalization is complete before proceeding.\n\n"
                        + "BIWT samples raw channel intensities and makes no assumptions about\n"
                        + "color, stain separation, or background subtraction. Continue?");
        if (!go) return;

        // Step 2 & 3: plan = detect domain + reconcile step size. May prompt the user.
        SamplingPlan plan = planWithUser(imageData);
        if (plan == null) return;

        // Step 4: confirmation showing nx × ny and effective step.
        if (!confirmPlan(plan)) return;

        // Step 5: substrate definition loop.
        List<SubstrateSpec> substrates = collectSubstrates(imageData.getServer());
        if (substrates.isEmpty()) return;

        // Step 6: file-save chooser.
        Path outPath = chooseOutputPath(imageData);
        if (outPath == null) return;

        // Step 7: run sampling on a background thread with a progress indicator.
        runSamplingTask(imageData, plan, substrates, outPath);
    }

    // ---------------- step 2 & 3 (plan: detect domain + step size) ----------------

    private SamplingPlan planWithUser(ImageData<BufferedImage> imageData) {
        Double stepMicrons = Dialogs.showInputDialog(TITLE,
                "Voxel size in µm (single scalar for x and y):", DEFAULT_STEP_MICRONS);
        if (stepMicrons == null) return null;
        if (!(stepMicrons > 0)) {
            Dialogs.showErrorMessage(TITLE, "Voxel size must be a positive number.");
            return null;
        }

        // Try with the default annotation name; fall through to whole-image if missing.
        try {
            return sampler.plan(imageData, DomainDetectionOptions.defaults(),
                    stepMicrons, DEFAULT_ORIGIN);
        } catch (AnnotationNotFoundException ann) {
            boolean useWholeImage = Dialogs.showYesNoDialog(TITLE,
                    "No annotation named 'abm_domain' was found.\n\n"
                            + "Use the whole image as the domain instead?");
            if (!useWholeImage) return null;
            try {
                return sampler.plan(imageData, DomainDetectionOptions.wholeImageFallback(),
                        stepMicrons, DEFAULT_ORIGIN);
            } catch (DomainException e2) {
                Dialogs.showErrorMessage(TITLE, e2.getMessage());
                return null;
            }
        } catch (NonRectangularDomainException nr) {
            Dialogs.showErrorMessage(TITLE, nr.getMessage());
            return null;
        } catch (AskUserRequiredException askEx) {
            // Should not happen for DomainDetectionOptions.defaults() — that uses FAIL fallback.
            logger.warn("Unexpected ASK_USER signal from default options", askEx);
            return null;
        } catch (DomainException d) {
            Dialogs.showErrorMessage(TITLE, d.getMessage());
            return null;
        }
    }

    // ---------------- step 4 (plan confirmation) ----------------

    private boolean confirmPlan(SamplingPlan plan) {
        String message = String.format(
                "Source: %s%n"
                        + "Grid:   %d × %d voxels%n"
                        + "Requested step: %.4g µm%n"
                        + "Effective step: %.4g µm%s%n"
                        + "Pixel size:     %.4g µm%n%n"
                        + "Proceed?",
                plan.domain().sourceDescription(),
                plan.grid().nx(), plan.grid().ny(),
                plan.requestedStepMicrons(),
                plan.effectiveStepMicrons(),
                effectiveStepNote(plan),
                plan.domain().pixelWidthMicrons());
        return Dialogs.showConfirmDialog(TITLE, message);
    }

    private static String effectiveStepNote(SamplingPlan plan) {
        if (Math.abs(plan.requestedStepMicrons() - plan.effectiveStepMicrons()) < 1e-9) {
            return "";
        }
        return "  (rounded to whole pixels)";
    }

    // ---------------- step 5 (substrate definition loop) ----------------

    private List<SubstrateSpec> collectSubstrates(ImageServer<BufferedImage> server) {
        List<ImageChannel> channels = server.getMetadata().getChannels();
        if (channels.isEmpty()) {
            Dialogs.showErrorMessage(TITLE, "Image has no channels reported by its server.");
            return List.of();
        }
        String[] channelLabels = new String[channels.size()];
        for (int i = 0; i < channels.size(); i++) {
            String name = channels.get(i).getName();
            channelLabels[i] = (name == null || name.isBlank())
                    ? "Channel " + i
                    : i + ": " + name;
        }

        List<SubstrateSpec> substrates = new ArrayList<>();
        while (true) {
            String prompt = substrates.isEmpty()
                    ? "Name for the first substrate:"
                    : "Name for next substrate (Cancel to finish — you have "
                            + substrates.size() + " so far):";
            String name = Dialogs.showInputDialog(TITLE, prompt, "");
            if (name == null || name.isBlank()) {
                if (substrates.isEmpty()) {
                    Dialogs.showErrorMessage(TITLE, "At least one substrate is required.");
                    continue;
                }
                break;
            }
            String chosen = Dialogs.showChoiceDialog(TITLE,
                    "Source channel for '" + name + "':",
                    channelLabels, channelLabels[0]);
            if (chosen == null) continue;
            int channelIdx = indexOf(channelLabels, chosen);
            substrates.add(new SubstrateSpec(name.trim(), channelIdx));
        }
        return substrates;
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return 0;
    }

    // ---------------- step 6 (file save) ----------------

    private Path chooseOutputPath(ImageData<BufferedImage> imageData) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save substrates CSV");
        fc.setInitialFileName(suggestFileName(imageData));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        Stage owner = qupath == null ? null : qupath.getStage();
        File f = fc.showSaveDialog(owner);
        return f == null ? null : f.toPath();
    }

    private static String suggestFileName(ImageData<BufferedImage> imageData) {
        String base = imageData.getServer().getMetadata().getName();
        if (base == null || base.isBlank()) base = "substrates";
        // Strip an extension if the image name carries one.
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base + "-substrates.csv";
    }

    // ---------------- step 7 (background sampling) ----------------

    private void runSamplingTask(ImageData<BufferedImage> imageData,
                                 SamplingPlan plan,
                                 List<SubstrateSpec> substrates,
                                 Path outPath) {
        ProgressIndicator indicator = new ProgressIndicator(-1);  // indeterminate
        indicator.setPrefSize(80, 80);
        Label label = new Label(
                "Sampling " + plan.grid().nx() + " × " + plan.grid().ny()
                        + " voxels for " + substrates.size() + " substrate(s)…");
        VBox box = new VBox(12, label, indicator);
        box.setPadding(new Insets(20));
        Stage progressStage = new Stage();
        progressStage.setTitle(TITLE);
        progressStage.initOwner(qupath.getStage());
        progressStage.initModality(Modality.WINDOW_MODAL);
        progressStage.setScene(new Scene(box));
        progressStage.setResizable(false);

        Task<SamplingResult> task = new Task<>() {
            @Override
            protected SamplingResult call() throws Exception {
                SamplingResult result = sampler.sample(imageData, plan, substrates);
                result.writeCsv(outPath);
                return result;
            }
        };
        task.setOnSucceeded(e -> {
            progressStage.close();
            SamplingResult r = task.getValue();
            Dialogs.showInfoNotification(TITLE,
                    "Saved " + r.grid().nx() + " × " + r.grid().ny()
                            + " voxels to " + outPath.getFileName());
            logger.info("BIWT wrote {} ({} × {} voxels, {} substrate(s))",
                    outPath, r.grid().nx(), r.grid().ny(), r.substrates().size());
        });
        task.setOnFailed(e -> {
            progressStage.close();
            Throwable cause = task.getException();
            logger.error("BIWT sampling failed", cause);
            Dialogs.showErrorMessage(TITLE, cause == null ? "Sampling failed." : cause.getMessage());
        });

        Thread t = new Thread(task, "biwt-sampler");
        t.setDaemon(true);
        t.start();
        Platform.runLater(progressStage::show);
    }
}
