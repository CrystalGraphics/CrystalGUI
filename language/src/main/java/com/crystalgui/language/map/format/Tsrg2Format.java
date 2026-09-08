package com.crystalgui.language.map.format;

import com.crystalgui.language.map.MappingSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MCPConfig's {@code joined.tsrg} — Forge's obf → SRG data, in TSRG2.
 *
 * <pre>{@code
 * tsrg2 obf srg
 * dhg net/minecraft/world/level/Level
 * 	a (Lfx;)Ldkr; m_8055_
 * 	c Ldsx; f_46441_
 * }</pre>
 *
 * <p>Read <b>obf → srg</b>, which is one half of the join a Forge runtime needs:</p>
 *
 * <pre>{@code
 * MappingSet srgToOfficial = obfToSrg.invert().then(obfToOfficial);
 * }</pre>
 *
 * <h3>The "srg" namespace carries OFFICIAL class names</h3>
 *
 * <p>Since 1.17 only members are SRG; classes are already {@code net/minecraft/world/level/Level}. So
 * the class half of the join composes to the identity, and that is not a bug in the join — it is what
 * Forge 1.20.1 actually runs, and what makes {@code Level.m_8055_ → getBlockState} the right answer.</p>
 *
 * <h3>Indentation is the grammar</h3>
 *
 * <p>One tab is a member of the current class; two is a parameter or a {@code static} marker, and both
 * are skipped — parameter names live in bytecode only as debug metadata and nothing resolves against
 * them. Reading a two-tab line as a member would register a parameter as a field.</p>
 */
public final class Tsrg2Format implements MappingFormat {

    @Override
    public String id() {
        return "tsrg2";
    }

    /** The magic line the format opens with, which nothing else does. */
    @Override
    public boolean matches(Path file) {
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = lines.readLine();
            return header != null && header.startsWith("tsrg2 ");
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    @Override
    public void parse(Path file, MappingSet.Builder into) throws IOException {
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = lines.readLine();
            if (header == null || !header.startsWith("tsrg2 ")) return;

            String obfOwner = null;
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (line.trim().isEmpty()) continue;
                int depth = indentOf(line);
                if (depth == 0) {
                    obfOwner = parseClass(line, into);
                } else if (depth == 1 && obfOwner != null) {
                    parseMember(line.trim(), obfOwner, into);
                }
                // depth >= 2 is a parameter or `static`; see the class note.
            }
        }
    }

    /** {@code dhg net/minecraft/world/level/Level} → the obf owner for the members that follow. */
    private static String parseClass(String line, MappingSet.Builder into) {
        String[] columns = line.trim().split(" ");
        if (columns.length < 2) return null;
        into.type(columns[0], columns[1]);
        return columns[0];
    }

    /**
     * {@code a (Lfx;)Ldkr; m_8055_} (method) or {@code c Ldsx; f_46441_} (field).
     *
     * <p>Both are three columns and differ only in whether the middle one is a method descriptor, so the
     * bracket is what tells them apart. A two-column line is a field whose descriptor was omitted, which
     * TSRG2 permits.</p>
     */
    private static void parseMember(String line, String obfOwner, MappingSet.Builder into) {
        String[] columns = line.split(" ");
        if (columns.length == 2) {
            into.field(obfOwner, columns[0], columns[1]);
        } else if (columns.length >= 3) {
            if (columns[1].startsWith("(")) {
                into.method(obfOwner, columns[0], columns[2]);
            } else {
                into.field(obfOwner, columns[0], columns[2]);
            }
        }
    }

    /** Leading tabs, which is what TSRG2 nests with. Spaces are separators here, never indentation. */
    private static int indentOf(String line) {
        int tabs = 0;
        while (tabs < line.length() && line.charAt(tabs) == '\t') tabs++;
        return tabs;
    }
}
