package com.crystalgui.language.map.format;

import com.crystalgui.language.map.MappingSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mojang's published mappings — {@code client.txt} / {@code server.txt}, ProGuard's own format.
 *
 * <pre>{@code
 * net.minecraft.world.level.Level -> dhg:
 *     net.minecraft.util.RandomSource random -> c
 *     12:34:net.minecraft.world.level.block.state.BlockState getBlockState(...) -> a
 * }</pre>
 *
 * <p>Every 1.20.x loader needs this file, because it is the only published route to official names —
 * and it is <b>obf on the right</b>, which is the namespace no runtime speaks. It is therefore always
 * one half of a join rather than a mapping on its own:</p>
 *
 * <pre>{@code
 * MappingSet srgToOfficial = obfToSrg.invert().then(obfToOfficial);
 * }</pre>
 *
 * <p>Read <b>obf → official</b>, so the set is already runtime→readable for the obf namespace and needs
 * no inversion of its own.</p>
 *
 * <h3>What is dropped, and why none of it matters here</h3>
 *
 * <p>Parameter and return types are parsed only far enough to find the member name; {@link MappingSet}
 * keys on owner and name, so a descriptor would be carried and never read. Two overloads therefore both
 * map to the same official name, which is right in this direction and ambiguous in reverse — the same
 * shape MCP has, handled the same way.</p>
 */
public final class ProGuardFormat implements MappingFormat {

    /** How many lines to read before deciding this is not one of ours. @see #matches */
    private static final int SNIFF_LINES = 40;

    @Override
    public String id() {
        return "proguard";
    }

    /**
     * Recognised by a CLASS line — {@code something -> something:} with no leading whitespace.
     *
     * <p>Mojang's files open with a licence header of unpredictable length, so a fixed line cannot be
     * checked the way an MCP header can. A bounded scan is the compromise: enough to pass the comments,
     * short enough that identifying a file never costs what parsing it does.</p>
     */
    @Override
    public boolean matches(Path file) {
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (int read = 0; read < SNIFF_LINES; read++) {
                String line = lines.readLine();
                if (line == null) return false;
                if (isComment(line) || line.trim().isEmpty()) continue;
                if (isClassLine(line)) return true;
                // A non-comment, non-class line before any class line: something else's file.
                return false;
            }
            return false;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    @Override
    public void parse(Path file, MappingSet.Builder into) throws IOException {
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String obfOwner = null;
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (isComment(line) || line.trim().isEmpty()) continue;
                if (isClassLine(line)) {
                    obfOwner = parseClass(line, into);
                } else if (obfOwner != null) {
                    // A member before any class line has no owner to attach to; skipping is the only
                    // honest answer, and a truncated file is the way it happens.
                    parseMember(line, obfOwner, into);
                }
            }
        }
    }

    /** {@code net.minecraft.world.level.Level -> dhg:} → the obf name, registered obf→official. */
    private static String parseClass(String line, MappingSet.Builder into) {
        int arrow = line.indexOf(ARROW);
        String readable = line.substring(0, arrow).trim();
        String obf = line.substring(arrow + ARROW.length()).trim();
        obf = obf.substring(0, obf.length() - 1).trim();   // the trailing ':'
        if (obf.isEmpty() || readable.isEmpty()) return null;
        into.type(internal(obf), internal(readable));
        return internal(obf);
    }

    /**
     * One indented member line, registered under its OBF owner.
     *
     * <p>A method is told from a field by the bracket in its signature, which is the only difference the
     * format offers — both are {@code <type> <name> -> <obf>} otherwise.</p>
     */
    private static void parseMember(String line, String obfOwner, MappingSet.Builder into) {
        int arrow = line.indexOf(ARROW);
        if (arrow < 0) return;
        String left = line.substring(0, arrow).trim();
        String obf = line.substring(arrow + ARROW.length()).trim();
        if (obf.isEmpty()) return;

        // "12:34:void foo()" -- source line numbers prefix a method and are not part of the signature.
        int lastColon = left.lastIndexOf(':');
        if (lastColon >= 0) left = left.substring(lastColon + 1).trim();

        int space = left.indexOf(' ');
        if (space < 0) return;
        String readable = left.substring(space + 1).trim();

        int bracket = readable.indexOf('(');
        if (bracket >= 0) {
            String name = readable.substring(0, bracket).trim();
            if (!name.isEmpty()) into.method(obfOwner, obf, name);
        } else if (!readable.isEmpty()) {
            into.field(obfOwner, obf, readable);
        }
    }

    private static final String ARROW = " -> ";

    private static boolean isComment(String line) {
        return line.startsWith("#");
    }

    /** A class line is unindented and ends in a colon; a member line is indented. */
    private static boolean isClassLine(String line) {
        return !line.isEmpty() && !Character.isWhitespace(line.charAt(0))
                && line.endsWith(":") && line.contains(ARROW);
    }

    private static String internal(String binaryName) {
        return binaryName.replace('.', '/');
    }
}
