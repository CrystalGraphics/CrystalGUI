package com.crystalgui.language.map.format;

import com.crystalgui.language.map.MappingSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fabric's {@code mappings.tiny} — Tiny v2, obf → intermediary.
 *
 * <pre>{@code
 * tiny	2	0	official	intermediary
 * c	dhg	net/minecraft/class_1937
 * 	m	(Lfx;)Ldkr;	a	method_8320
 * 	f	Ldsx;	c	field_9229
 * }</pre>
 *
 * <p>Read as <b>first namespace → second</b>, whatever they are called. Fabric's intermediary artifact
 * names them {@code official} and {@code intermediary}, and {@code official} is the obfuscated one —
 * so this is the runtime half of a join, exactly as MCPConfig's TSRG2 is for Forge:</p>
 *
 * <pre>{@code
 * MappingSet intermediaryToOfficial = obfToIntermediary.invert().then(obfToOfficial);
 * }</pre>
 *
 * <h3>Only the first two namespaces are read</h3>
 *
 * <p>Tiny v2 permits any number, and a yarn file carries a third. Taking the first two keeps this a
 * mapping between the two ends a join needs, and a file whose second column is not the wanted namespace
 * is a coordinates mistake rather than something to guess at.</p>
 *
 * <h3>Indentation is the grammar, and a comment is a nested record</h3>
 *
 * <p>One tab is a member, two are its parameters, locals or a {@code c} comment. A comment nested under
 * a method starts with the same letter a class line does, so reading by first token instead of by depth
 * turns a javadoc into a class mapping.</p>
 */
public final class TinyV2Format implements MappingFormat {

    @Override
    public String id() {
        return "tiny-v2";
    }

    @Override
    public boolean matches(Path file) {
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = lines.readLine();
            return header != null && header.startsWith("tiny\t2\t");
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    @Override
    public void parse(Path file, MappingSet.Builder into) throws IOException {
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = lines.readLine();
            if (header == null || !header.startsWith("tiny\t2\t")) return;

            String owner = null;
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (line.trim().isEmpty()) continue;
                int depth = indentOf(line);
                String[] columns = line.substring(depth).split("\t");
                if (depth == 0) {
                    // A class, or a file-level comment; anything else ends the current class.
                    owner = "c".equals(columns[0]) && columns.length >= 3 ? parseClass(columns, into) : null;
                } else if (depth == 1 && owner != null) {
                    parseMember(columns, owner, into);
                }
                // depth >= 2 is a parameter, a local or a comment. @see the class note
            }
        }
    }

    /** {@code c<TAB>dhg<TAB>net/minecraft/class_1937} */
    private static String parseClass(String[] columns, MappingSet.Builder into) {
        String from = columns[1];
        String to = columns[2];
        if (from.isEmpty() || to.isEmpty()) return from.isEmpty() ? null : from;
        into.type(from, to);
        return from;
    }

    /**
     * {@code m<TAB>desc<TAB>a<TAB>method_8320} — the descriptor sits between the tag and the names.
     *
     * <p>An entry whose second name is empty is a member the second namespace does not rename, which
     * Tiny v2 writes as a blank column. Registering it would map the name to nothing.</p>
     */
    private static void parseMember(String[] columns, String owner, MappingSet.Builder into) {
        if (columns.length < 4) return;
        String from = columns[2];
        String to = columns[3];
        if (from.isEmpty() || to.isEmpty()) return;
        if ("m".equals(columns[0])) {
            into.method(owner, from, to);
        } else if ("f".equals(columns[0])) {
            into.field(owner, from, to);
        }
    }

    private static int indentOf(String line) {
        int tabs = 0;
        while (tabs < line.length() && line.charAt(tabs) == '\t') tabs++;
        return tabs;
    }
}
