package com.crystalgui.language.map.format;

import com.crystalgui.language.map.MappingSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Files in, one {@link MappingSet} out — the only entry point a platform needs.
 *
 * <h3>Parse once, hold one set</h3>
 *
 * <p>A {@code MappingSet} is immutable and keyed for lookup in both directions, and MCP's is 670 KB of
 * source data. Rebuilding it per compile would re-read all of it to produce something identical; the
 * caller holds the result for the life of the process, which is legitimate because the files it came
 * from are version-addressed and therefore cannot change under it (§26.5).</p>
 *
 * <h3>Unrecognised files are skipped, not refused</h3>
 *
 * <p>A platform hands over a directory listing, and a directory routinely contains a {@code .md5} beside
 * each artifact, a README, whatever a download left behind. Failing the whole mapping because one file
 * is not a mapping would make the feature depend on the tidiness of a cache directory.</p>
 *
 * <p><b>The report is what makes that safe.</b> Skipping silently and skipping loudly are very different
 * when the outcome is an empty mapping: {@link #load} returns the set, and a caller that gets
 * {@link MappingSet#isIdentity()} back from a non-empty file list knows to say so rather than to present
 * runtime names as though that were the intent.</p>
 */
public final class MappingFiles {

    /**
     * Every format the core knows, in the order they are asked.
     *
     * <p>One entry today. Ordered rather than a set because {@code matches} is a heuristic over content
     * and two formats could in principle both accept a file; first-wins is at least deterministic and
     * describable, where a set would depend on iteration order.</p>
     */
    private static final List<MappingFormat> FORMATS = List.of(new McpCsvFormat());

    private MappingFiles() {
    }

    /** The formats this build can parse — for a caller that wants to say what it supports. */
    public static List<MappingFormat> formats() {
        return FORMATS;
    }

    /**
     * Parses every file any format recognises.
     *
     * <p>Order matters and is the caller's: a later file's entry for the same runtime name replaces an
     * earlier one, because {@code Builder} is a map. That is the ordinary meaning of an overlay and the
     * only behaviour a caller could reason about.</p>
     */
    public static MappingSet load(Collection<Path> files) throws IOException {
        MappingSet.Builder builder = MappingSet.builder();
        for (Path file : files) {
            if (file == null || !Files.isRegularFile(file)) continue;
            MappingFormat format = formatOf(file);
            if (format == null) continue;
            format.parse(file, builder);
        }
        return builder.build();
    }

    /** The first format that recognises {@code file}, or null. */
    public static MappingFormat formatOf(Path file) {
        for (MappingFormat format : FORMATS) {
            if (format.matches(file)) return format;
        }
        return null;
    }

    /**
     * Every regular file directly inside {@code directory}, sorted, or empty if there is no directory.
     *
     * <p>Sorted so a mapping built on two machines is the same mapping: {@link Files#list} gives
     * filesystem order, and with overlay semantics that is the difference between one file winning and
     * the other. Not recursive — a mapping directory is flat, and walking would pull in whatever a
     * neighbouring cache happens to hold.</p>
     */
    public static List<Path> in(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) return List.of();
        List<Path> files = new ArrayList<>();
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.sorted().toArray(Path[]::new)) {
                if (Files.isRegularFile(entry)) files.add(entry);
            }
        }
        return files;
    }
}
