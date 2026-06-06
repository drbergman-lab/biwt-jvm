package io.github.drbergmanlab.biwt.core.coord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the {@code <domain>} block of a PhysiCell configuration XML in place so its bounds match
 * a {@link PhysiCellDomain} produced by BIWT — so the simulated mesh lines up with the exported
 * substrate/cell coordinates voxel-for-voxel, with no hand-copying.
 *
 * <p>The edit is a <b>targeted text splice</b>, not a full XML reparse: it finds the first
 * {@code <domain>…</domain>} block and replaces only the values of its known child tags, leaving
 * everything else — attributes/units (e.g. {@code <x_min units="micron">}), comments, indentation,
 * and the rest of the file — byte-for-byte intact. Tags absent from the block are appended to it.
 *
 * <p>A {@code <name>.bak} backup is written before the file is overwritten. If the file has no
 * {@code <domain>} element, nothing is changed and an {@link IllegalArgumentException} is thrown.
 */
public final class PhysiCellConfigUpdater {

    private PhysiCellConfigUpdater() {}

    private static final Pattern DOMAIN_BLOCK =
            Pattern.compile("(<domain\\b[^>]*>)(.*?)(</domain\\s*>)", Pattern.DOTALL);

    /**
     * Update {@code configXml}'s {@code <domain>} bounds to {@code domain}, writing a {@code .bak}
     * backup first.
     *
     * @return the path of the backup that was written
     * @throws IllegalArgumentException if no {@code <domain>} element is present
     * @throws IOException              on read/write failure
     */
    public static Path updateDomain(Path configXml, PhysiCellDomain domain) throws IOException {
        Objects.requireNonNull(configXml, "configXml");
        Objects.requireNonNull(domain, "domain");

        String xml = Files.readString(configXml);
        String updated = applyToXml(xml, domain);

        Path backup = configXml.resolveSibling(configXml.getFileName() + ".bak");
        Files.copy(configXml, backup, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(configXml, updated);
        return backup;
    }

    /**
     * Pure-string form of {@link #updateDomain} — returns the rewritten XML. Exposed for testing and
     * for callers that manage their own I/O.
     */
    public static String applyToXml(String xml, PhysiCellDomain domain) {
        Matcher m = DOMAIN_BLOCK.matcher(xml);
        if (!m.find()) {
            throw new IllegalArgumentException("No <domain> element found in the PhysiCell config.");
        }
        String open = m.group(1);
        String inner = m.group(2);
        String close = m.group(3);

        for (Map.Entry<String, String> e : domain.domainTags().entrySet()) {
            inner = setTagValue(inner, e.getKey(), e.getValue());
        }
        return xml.substring(0, m.start()) + open + inner + close + xml.substring(m.end());
    }

    /** Replace {@code <tag>…</tag>}'s content within {@code block}; append the tag if it's missing. */
    private static String setTagValue(String block, String tag, String value) {
        Pattern p = Pattern.compile("(<" + tag + "\\b[^>]*>)(.*?)(</" + tag + "\\s*>)", Pattern.DOTALL);
        Matcher m = p.matcher(block);
        if (m.find()) {
            return block.substring(0, m.start()) + m.group(1) + value + m.group(3) + block.substring(m.end());
        }
        // Not present — append it, mimicking the block's trailing indentation.
        String indent = trailingIndent(block);
        String insertion = indent + "<" + tag + ">" + value + "</" + tag + ">\n";
        // Insert before the final indentation that precedes </domain>, so the new line nests neatly.
        if (block.endsWith(indent)) {
            return block.substring(0, block.length() - indent.length()) + insertion + indent;
        }
        return block + insertion;
    }

    /** The whitespace at the end of the block (the indentation that preceded {@code </domain>}). */
    private static String trailingIndent(String block) {
        int i = block.length();
        while (i > 0 && (block.charAt(i - 1) == ' ' || block.charAt(i - 1) == '\t')) {
            i--;
        }
        String indent = block.substring(i);
        return indent.isEmpty() ? "\t\t" : indent;
    }
}
