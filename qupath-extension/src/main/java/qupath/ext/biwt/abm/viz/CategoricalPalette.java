package qupath.ext.biwt.abm.viz;

import javafx.scene.paint.Color;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assigns a stable, color-blind-friendly color to each distinct cell-type name in first-seen order,
 * for the cell scatter and its legend.
 *
 * <p>The base palette is the Okabe–Ito set (designed for color-vision deficiency) extended with a
 * few extra hues; once exhausted it wraps. The core {@code ColorMap} {@code int} → {@link Color}
 * bridge (for the substrate heatmap) also lives here, keeping JavaFX color construction out of
 * {@code :core}.
 */
public final class CategoricalPalette {

    /** Okabe–Ito (8) + 4 extra distinct hues = 12 categorical colors. */
    private static final List<Color> BASE = List.of(
            Color.web("#E69F00"), // orange
            Color.web("#56B4E9"), // sky blue
            Color.web("#009E73"), // bluish green
            Color.web("#F0E442"), // yellow
            Color.web("#0072B2"), // blue
            Color.web("#D55E00"), // vermillion
            Color.web("#CC79A7"), // reddish purple
            Color.web("#000000"), // black
            Color.web("#999999"), // gray
            Color.web("#882255"), // wine
            Color.web("#44AA99"), // teal
            Color.web("#117733")  // forest green
    );

    private final Map<String, Color> assigned = new LinkedHashMap<>();
    private final Map<String, Color> overrides;

    /**
     * @param overrides optional fixed colors keyed by type name (e.g. from QuPath classes); may be
     *                  {@code null} or empty for fully-automatic assignment
     */
    public CategoricalPalette(Map<String, Color> overrides) {
        this.overrides = overrides == null ? Map.of() : overrides;
    }

    /** The color for {@code type}, assigning the next palette entry on first sight. */
    public Color colorFor(String type) {
        Color override = overrides.get(type);
        if (override != null) {
            return override;
        }
        return assigned.computeIfAbsent(type, t -> BASE.get(assigned.size() % BASE.size()));
    }

    /** The type→color assignments made so far, in first-seen order (drives the legend). */
    public Map<String, Color> assignments() {
        return new LinkedHashMap<>(assigned);
    }

    /** Bridge a packed {@code 0xAARRGGBB} from the core {@code ColorMap} to a JavaFX {@link Color}. */
    public static Color toColor(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return Color.rgb(r, g, b, a / 255.0);
    }
}
