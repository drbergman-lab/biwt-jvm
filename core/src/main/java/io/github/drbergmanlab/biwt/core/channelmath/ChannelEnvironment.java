package io.github.drbergmanlab.biwt.core.channelmath;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The per-pixel input data used during {@link Expression} evaluation. Each named identifier maps
 * to a {@code float[]} of the same length — one value per pixel in the current tile. Look-ups are
 * case-insensitive ({@code "h"}, {@code "H"}, and {@code "h"} all resolve to the same buffer) but
 * the canonical names registered by the caller are preserved for error messages.
 *
 * <p>Typically the wizard builds one of these per tile by calling each referenced channel's
 * {@link qupath.lib.images.servers.ColorTransforms.ColorTransform#extractChannel
 * extractChannel} method, then hands the environment to {@link Expression#evaluate}.
 */
public final class ChannelEnvironment {

    private final Map<String, float[]> values = new HashMap<>();

    /**
     * Register a channel under {@code canonicalName}. Subsequent {@link #get} calls match
     * case-insensitively but the canonical casing is preserved for error reporting.
     *
     * @throws IllegalArgumentException if another channel is already registered under the same
     *         case-folded name.
     */
    public ChannelEnvironment register(String canonicalName, float[] data) {
        String key = key(canonicalName);
        if (values.containsKey(key)) {
            throw new IllegalArgumentException("Channel '" + canonicalName + "' already registered.");
        }
        values.put(key, data);
        return this;
    }

    /**
     * Returns the buffer registered under any case-folded match of {@code name}.
     *
     * @throws NoSuchElementException if no matching channel was registered.
     */
    public float[] get(String name) {
        float[] data = values.get(key(name));
        if (data == null) {
            throw new NoSuchElementException("Unknown channel '" + name + "'.");
        }
        return data;
    }

    /** Test whether a case-folded match for {@code name} is registered. */
    public boolean contains(String name) {
        return values.containsKey(key(name));
    }

    private static String key(String name) {
        return name.toLowerCase(java.util.Locale.ROOT);
    }
}
