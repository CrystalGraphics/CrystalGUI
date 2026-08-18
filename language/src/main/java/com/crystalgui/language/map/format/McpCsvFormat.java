package com.crystalgui.language.map.format;

import com.crystalgui.language.map.MappingSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MCP's {@code methods.csv}, {@code fields.csv} and {@code params.csv} — SRG → readable.
 *
 * <h3>The shape</h3>
 *
 * <pre>
 * searge,name,side,desc
 * func_147439_a,getBlock,0,"Returns the block at the given coordinates"
 * </pre>
 *
 * <p>Four columns, a header line, and a description that may itself contain commas inside quotes — which
 * is why the split is limited to four fields and the fourth is never looked at. Splitting without a limit
 * makes a description with a comma in it produce five columns and silently discards the row, and the rows
 * that go missing are the well-documented ones.</p>
 *
 * <h3>No owner, and none is needed</h3>
 *
 * <p>SRG names are globally unique by construction: {@code func_147439_a} names exactly one method in the
 * whole game. That is why the file has no owner column, and why entries go in through
 * {@link MappingSet.Builder#method(String, String)} rather than the owner-keyed form. Deriving an owner
 * would mean parsing {@code packaged.srg} to recover something the data already guarantees.</p>
 *
 * <h3>Which file is which is read off the NAMES in it, not the file name</h3>
 *
 * <p>{@code methods.csv} and {@code fields.csv} are byte-for-byte the same format and differ only in what
 * their first column names — {@code func_*} versus {@code field_*}. A platform is free to name the files
 * anything, so the prefix is the only honest signal. A row whose name matches neither is skipped: MCP
 * ships a handful of hand-named entries, and refusing the file over them would lose 4,800 good rows to
 * two odd ones.</p>
 *
 * <h3>{@code params.csv} is deliberately ignored</h3>
 *
 * <p>Its names are {@code p_147439_1_} → {@code x}, which are parameter names. Those exist in bytecode
 * only as debug metadata and are never resolved against, so mapping them would change nothing a script
 * can observe. It is listed in §26.6 because it is part of the download, not because it is parsed.</p>
 */
public final class McpCsvFormat implements MappingFormat {

    private static final String METHOD_PREFIX = "func_";
    private static final String FIELD_PREFIX = "field_";
    private static final String PARAMETER_PREFIX = "p_";

    @Override
    public String id() {
        return "mcp-csv";
    }

    /**
     * Recognised by its header, which every MCP CSV carries and nothing else does.
     *
     * <p>Reading one line rather than sampling rows: the header is an exact string, and a row-shape test
     * would accept any four-column CSV — including a future format that happens to look similar, which is
     * precisely the case a content check is supposed to tell apart.</p>
     */
    @Override
    public boolean matches(Path file) {
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = lines.readLine();
            return header != null && header.trim().startsWith("searge,name,side,desc");
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    @Override
    public void parse(Path file, MappingSet.Builder into) throws IOException {
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = lines.readLine();
            // The header, dropped -- but only if it IS one. A file handed over without its header would
            // otherwise lose its first real mapping, which is invisible in a set of 4,800.
            if (line != null && !line.trim().startsWith("searge,")) parse(line, into);
            for (line = lines.readLine(); line != null; line = lines.readLine()) {
                parse(line, into);
            }
        }
    }

    private static void parse(String line, MappingSet.Builder into) {
        if (line.isEmpty()) return;
        // LIMIT 4: the description is the last column and may contain commas inside quotes. Without the
        // limit those rows split into five and are dropped -- and they are the DOCUMENTED members, which
        // is the subset somebody is most likely to be looking for when they notice.
        String[] columns = line.split(",", 4);
        if (columns.length < 2) return;
        String runtime = columns[0].trim();
        String readable = columns[1].trim();
        if (runtime.isEmpty() || readable.isEmpty()) return;

        if (runtime.startsWith(METHOD_PREFIX)) {
            into.method(runtime, readable);
        } else if (runtime.startsWith(FIELD_PREFIX)) {
            into.field(runtime, readable);
        }
        // Anything else -- p_* parameters, and MCP's handful of hand-named rows -- is skipped rather than
        // refused. See the class note: a strict reader loses thousands of good rows to a few odd ones.
    }

    /** Whether a name looks like an MCP parameter, i.e. the tier this format deliberately drops. */
    static boolean isParameter(String name) {
        return name.startsWith(PARAMETER_PREFIX);
    }
}
