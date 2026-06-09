package qupath.ext.biwt.abm.viz;

import io.github.drbergmanlab.biwt.core.cells.CellRecord;
import io.github.drbergmanlab.biwt.core.coord.VoxelGrid;
import io.github.drbergmanlab.biwt.core.export.NamedSubstrate;
import io.github.drbergmanlab.biwt.core.viz.CellGeometry;
import io.github.drbergmanlab.biwt.core.viz.ColorMap;
import io.github.drbergmanlab.biwt.core.viz.DataRange;
import io.github.drbergmanlab.biwt.core.viz.WorldToScreen;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Interactive viewer for a BIWT build: a cell scatter over a substrate heatmap in the shared ABM µm
 * frame. Cells are drawn as filled disks (radius = equivalent-sphere radius, colored by type, with a
 * legend); the active substrate is a colormapped heatmap with a colorbar. Substrates cycle via the
 * dropdown, the ◀ / ▶ buttons, or the Left/Right arrow keys. {@code cmin}/{@code cmax} boxes pin the
 * color range and {@code xmin/xmax/ymin/ymax} boxes zoom; empty means "use the data/domain bound".
 *
 * <p>All value→pixel math (radius, colormap, autorange, world→screen with the y-flip) lives in
 * {@code :core}; this class only drives the {@link Canvas}.
 */
public final class ResultsViewer {

    private final ViewerModel model;
    private final CategoricalPalette palette;
    private final ColorMap colorMap = ColorMap.VIRIDIS;

    // Plot + colorbar canvases (sized to their parent panes).
    private final Canvas plotCanvas = new Canvas();
    private final Canvas colorbarCanvas = new Canvas();

    // Controls.
    private final ComboBox<String> substrateBox = new ComboBox<>();
    private final CheckBox showCellsCheck = new CheckBox("Show cells");
    private final TextField xMinField = limitField();
    private final TextField xMaxField = limitField();
    private final TextField yMinField = limitField();
    private final TextField yMaxField = limitField();
    private final TextField cMinField = limitField();
    private final TextField cMaxField = limitField();

    private int activeSubstrate = 0;
    // Cached heatmap for the active substrate + color range; rebuilt on substrate/cmin/cmax change.
    private WritableImage heatmap;
    private double resolvedCMin;
    private double resolvedCMax;

    private ResultsViewer(ViewerModel model) {
        this.model = model;
        this.palette = new CategoricalPalette(model.typeColors());
    }

    /** Build and show a non-modal results window for {@code model}, owned by {@code owner}. */
    public static void show(Window owner, String title, ViewerModel model) {
        ResultsViewer viewer = new ResultsViewer(model);
        Stage stage = new Stage();
        stage.setTitle(title);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setScene(viewer.buildScene());
        stage.setWidth(900);
        stage.setHeight(720);
        stage.show();
        // First paint after the panes have a size.
        viewer.rebuildHeatmap();
        viewer.redraw();
        viewer.drawColorbar();
    }

    // ---------------- layout ----------------

    private Scene buildScene() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));
        root.setTop(buildControls());
        root.setCenter(buildPlotArea());
        if (model.hasCells()) {
            root.setBottom(buildLegend());
        }

        Scene scene = new Scene(root);
        // Left/Right arrows cycle substrates, unless a text box has focus (it owns the arrows).
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (scene.getFocusOwner() instanceof TextField) return;
            if (e.getCode() == KeyCode.LEFT) { cycleSubstrate(-1); e.consume(); }
            else if (e.getCode() == KeyCode.RIGHT) { cycleSubstrate(1); e.consume(); }
        });
        return scene;
    }

    private HBox buildControls() {
        HBox controls = new HBox(10);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(0, 0, 8, 0));

        if (model.hasSubstrates()) {
            for (NamedSubstrate s : model.substrates()) {
                substrateBox.getItems().add(s.name());
            }
            substrateBox.getSelectionModel().select(0);
            substrateBox.getSelectionModel().selectedIndexProperty().addListener((o, a, idx) -> {
                if (idx.intValue() >= 0 && idx.intValue() != activeSubstrate) {
                    setActiveSubstrate(idx.intValue());
                }
            });
            Button prev = new Button("◀");
            Button next = new Button("▶");
            prev.setOnAction(e -> cycleSubstrate(-1));
            next.setOnAction(e -> cycleSubstrate(1));
            boolean multiple = model.substrates().size() > 1;
            prev.setDisable(!multiple);
            next.setDisable(!multiple);
            controls.getChildren().addAll(new Label("Substrate:"), substrateBox, prev, next);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        controls.getChildren().add(spacer);

        if (model.hasCells()) {
            showCellsCheck.setSelected(true);
            showCellsCheck.selectedProperty().addListener((o, a, b) -> redraw());
            controls.getChildren().add(showCellsCheck);
        }
        return controls;
    }

    private BorderPane buildPlotArea() {
        // Plot canvas grows to fill its pane; redraw whenever the pane resizes.
        Pane plotPane = new Pane(plotCanvas);
        plotPane.setStyle("-fx-background-color: white;");
        plotCanvas.widthProperty().bind(plotPane.widthProperty());
        plotCanvas.heightProperty().bind(plotPane.heightProperty());
        plotCanvas.widthProperty().addListener((o, a, b) -> redraw());
        plotCanvas.heightProperty().addListener((o, a, b) -> redraw());

        // y-limit column: ymax aligned to the top edge, ymin to the bottom edge.
        promptLimit(yMaxField, model.yMax());
        promptLimit(yMinField, model.yMin());
        Region yGap = new Region();
        VBox.setVgrow(yGap, Priority.ALWAYS);
        VBox yCol = new VBox(4, new Label("ymax"), yMaxField, yGap, new Label("ymin"), yMinField);
        yCol.setAlignment(Pos.CENTER_LEFT);
        yCol.setPrefWidth(68);
        yCol.setPadding(new Insets(0, 6, 0, 0));

        BorderPane innerPlot = new BorderPane(plotPane);
        innerPlot.setLeft(yCol);

        // x-limit row under the plot, indented to clear the y-column so xmin sits below the left edge.
        promptLimit(xMinField, model.xMin());
        promptLimit(xMaxField, model.xMax());
        Region xLead = new Region();
        xLead.setPrefWidth(68);
        Region xGap = new Region();
        HBox.setHgrow(xGap, Priority.ALWAYS);
        HBox xRow = new HBox(4, xLead, new Label("xmin"), xMinField, xGap, new Label("xmax"), xMaxField);
        xRow.setAlignment(Pos.CENTER_LEFT);
        xRow.setPadding(new Insets(6, 0, 0, 0));

        VBox plotColumn = new VBox(innerPlot, xRow);
        VBox.setVgrow(innerPlot, Priority.ALWAYS);

        for (TextField f : List.of(xMinField, xMaxField, yMinField, yMaxField)) {
            commitOnEnterOrFocusLoss(f, this::redraw);
        }

        BorderPane plotArea = new BorderPane(plotColumn);
        if (model.hasSubstrates()) {
            plotArea.setRight(buildColorbarColumn());
        }
        return plotArea;
    }

    private VBox buildColorbarColumn() {
        Pane barPane = new Pane(colorbarCanvas);
        colorbarCanvas.widthProperty().bind(barPane.widthProperty());
        colorbarCanvas.heightProperty().bind(barPane.heightProperty());
        colorbarCanvas.heightProperty().addListener((o, a, b) -> drawColorbar());
        VBox.setVgrow(barPane, Priority.ALWAYS);

        for (TextField f : List.of(cMinField, cMaxField)) {
            commitOnEnterOrFocusLoss(f, () -> { rebuildHeatmap(); redraw(); drawColorbar(); });
        }

        VBox col = new VBox(4, cMaxField, barPane, cMinField);
        col.setAlignment(Pos.CENTER);
        col.setPrefWidth(78);
        col.setPadding(new Insets(0, 0, 0, 8));
        return col;
    }

    private FlowPane buildLegend() {
        FlowPane legend = new FlowPane(12, 6);
        legend.setPadding(new Insets(8, 0, 0, 0));
        legend.setAlignment(Pos.CENTER_LEFT);
        // Touch the palette in cell order so first-seen assignment matches the scatter.
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (CellRecord c : model.cells()) {
            counts.merge(c.type(), 1, Integer::sum);
        }
        legend.getChildren().add(boldLabel("Cell types:"));
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Color color = palette.colorFor(e.getKey());
            Rectangle swatch = new Rectangle(12, 12, color);
            swatch.setStroke(Color.gray(0.4));
            Label label = new Label(e.getKey() + " (" + e.getValue() + ")");
            HBox item = new HBox(5, swatch, label);
            item.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(item);
        }
        return legend;
    }

    // ---------------- substrate cycling ----------------

    private void cycleSubstrate(int delta) {
        if (!model.hasSubstrates() || model.substrates().size() < 2) return;
        int n = model.substrates().size();
        setActiveSubstrate(((activeSubstrate + delta) % n + n) % n);
    }

    private void setActiveSubstrate(int idx) {
        activeSubstrate = idx;
        if (substrateBox.getSelectionModel().getSelectedIndex() != idx) {
            substrateBox.getSelectionModel().select(idx);
        }
        rebuildHeatmap();
        redraw();
        drawColorbar();
    }

    // ---------------- rendering ----------------

    /** Resolve the color range for the active substrate, honoring the cmin/cmax boxes (empty = auto). */
    private double[] resolveColorRange(NamedSubstrate s) {
        double[] auto = DataRange.minMaxIgnoringNaN(s.values());
        if (Double.isNaN(auto[0])) {
            auto = new double[] {0, 1}; // all-NaN field — keep the colormap valid
        }
        promptLimit(cMinField, auto[0]);
        promptLimit(cMaxField, auto[1]);
        double lo = parseOr(cMinField, auto[0]);
        double hi = parseOr(cMaxField, auto[1]);
        return new double[] {lo, hi};
    }

    /** Rebuild the cached voxel-resolution heatmap image for the active substrate. */
    private void rebuildHeatmap() {
        if (!model.hasSubstrates()) {
            heatmap = null;
            return;
        }
        NamedSubstrate s = model.substrates().get(activeSubstrate);
        double[] range = resolveColorRange(s);
        resolvedCMin = range[0];
        resolvedCMax = range[1];

        VoxelGrid grid = model.grid();
        int nx = grid.nx();
        int ny = grid.ny();
        double[][] values = s.values();
        WritableImage img = new WritableImage(nx, ny);
        PixelWriter pw = img.getPixelWriter();
        for (int k = 0; k < ny; k++) {
            int imgRow = ny - 1 - k; // image row 0 = top = largest world-y
            for (int i = 0; i < nx; i++) {
                pw.setArgb(i, imgRow, colorMap.argb(values[k][i], range[0], range[1]));
            }
        }
        heatmap = img;
    }

    private void redraw() {
        double w = plotCanvas.getWidth();
        double h = plotCanvas.getHeight();
        GraphicsContext gc = plotCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);
        if (w < 2 || h < 2) return;

        double[] view = resolveView();
        WorldToScreen t;
        try {
            t = new WorldToScreen(view[0], view[1], view[2], view[3], 0, 0, w, h);
        } catch (IllegalArgumentException ex) {
            return; // degenerate view rectangle — nothing to draw
        }

        // Substrate heatmap: map the full grid extent onto screen; the Canvas clips to its bounds.
        if (heatmap != null) {
            VoxelGrid grid = model.grid();
            double gx0 = t.screenX(grid.xMinMicrons());
            double gx1 = t.screenX(grid.xMaxMicrons());
            double gyTop = t.screenY(grid.yMaxMicrons());
            double gyBot = t.screenY(grid.yMinMicrons());
            gc.setImageSmoothing(false);
            gc.drawImage(heatmap, gx0, gyTop, gx1 - gx0, gyBot - gyTop);
        }

        if (model.hasCells() && showCellsCheck.isSelected()) {
            drawCells(gc, t, w, h);
        }

        // Plot border.
        gc.setStroke(Color.gray(0.5));
        gc.setLineWidth(1);
        gc.strokeRect(0.5, 0.5, w - 1, h - 1);
    }

    private void drawCells(GraphicsContext gc, WorldToScreen t, double w, double h) {
        gc.setLineWidth(0.5);
        for (CellRecord c : model.cells()) {
            double r = t.screenLen(CellGeometry.radiusMicronsForCell(c.volumeMicrons3()));
            double sx = t.screenX(c.xMicrons());
            double sy = t.screenY(c.yMicrons());
            if (sx + r < 0 || sx - r > w || sy + r < 0 || sy - r > h) {
                continue; // fully outside the visible plot
            }
            Color color = palette.colorFor(c.type());
            gc.setFill(Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.7));
            gc.fillOval(sx - r, sy - r, 2 * r, 2 * r);
            gc.setStroke(color.darker());
            gc.strokeOval(sx - r, sy - r, 2 * r, 2 * r);
        }
    }

    private void drawColorbar() {
        double w = colorbarCanvas.getWidth();
        double h = colorbarCanvas.getHeight();
        GraphicsContext gc = colorbarCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);
        if (!model.hasSubstrates() || w < 4 || h < 4) return;

        double barW = Math.min(22, w);
        for (int py = 0; py < (int) h; py++) {
            double frac = 1.0 - py / (h - 1); // top = 1 (cmax), bottom = 0 (cmin)
            gc.setFill(CategoricalPalette.toColor(colorMap.colorAt(frac)));
            gc.fillRect(0, py, barW, 1);
        }
        gc.setStroke(Color.gray(0.4));
        gc.strokeRect(0.5, 0.5, barW - 1, h - 1);

        // Tick labels: max (top), mid, min (bottom), to the right of the bar.
        gc.setFill(Color.BLACK);
        gc.setFont(Font.font(10));
        double mid = (resolvedCMin + resolvedCMax) / 2.0;
        gc.fillText(fmt(resolvedCMax), barW + 4, 10);
        gc.fillText(fmt(mid), barW + 4, h / 2 + 3);
        gc.fillText(fmt(resolvedCMin), barW + 4, h - 3);
    }

    /** The visible world rectangle {@code {x0, x1, y0, y1}} from the limit boxes (empty = default). */
    private double[] resolveView() {
        double x0 = parseOr(xMinField, model.xMin());
        double x1 = parseOr(xMaxField, model.xMax());
        double y0 = parseOr(yMinField, model.yMin());
        double y1 = parseOr(yMaxField, model.yMax());
        if (!(x1 > x0)) { x0 = model.xMin(); x1 = model.xMax(); }
        if (!(y1 > y0)) { y0 = model.yMin(); y1 = model.yMax(); }
        return new double[] {x0, x1, y0, y1};
    }

    // ---------------- field helpers ----------------

    private static TextField limitField() {
        TextField f = new TextField();
        f.setPrefColumnCount(6);
        return f;
    }

    /** A parsed value from the field, or {@code fallback} when empty; invalid input flags the field. */
    private double parseOr(TextField field, double fallback) {
        String text = field.getText() == null ? "" : field.getText().trim();
        if (text.isEmpty()) {
            field.setStyle("");
            return fallback;
        }
        try {
            double v = Double.parseDouble(text);
            field.setStyle("");
            return v;
        } catch (NumberFormatException e) {
            field.setStyle("-fx-border-color: #cc0000; -fx-border-width: 1;");
            return fallback;
        }
    }

    /** Show the resolved default as the prompt so an empty box still communicates the active bound. */
    private static void promptLimit(TextField field, double value) {
        field.setPromptText(fmt(value));
    }

    private static void commitOnEnterOrFocusLoss(TextField field, Runnable onCommit) {
        field.setOnAction(e -> onCommit.run());
        field.focusedProperty().addListener((o, was, now) -> {
            if (was && !now) onCommit.run();
        });
    }

    private static Label boldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) return "—";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.format(Locale.ROOT, "%d", (long) v);
        }
        return String.format(Locale.ROOT, "%.4g", v);
    }
}
