package com.crystalgui.headless;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * <b>A widget that paints its own glyphs has to decide about {@code text-stroke} and {@code text-shadow}.</b>
 *
 * <p>Both are INHERITABLE, so a declaration on any ancestor reaches every piece of text under it the way
 * CSS does. A widget that draws text and never asks leaves the property parsing, cascading and computing
 * correctly while drawing nothing — which is indistinguishable from the property not existing, and is
 * how {@code TextField} ignored the stroke for the whole life of that feature.</p>
 *
 * <p>So this pins the rule rather than the call sites that exist today: reach {@code ctx.text().draw()}
 * and you must also name {@link com.crystalgui.render.text.TextStrokeStyle} and
 * {@link com.crystalgui.render.text.TextShadowStyle}. Deciding NOT to apply one is fine and needs no
 * exemption; what the test refuses is a file that never considered the question.</p>
 */
public class EveryTextDrawConsidersStrokeAndShadowTest {

    /** Reaching the text renderer at all, in code rather than in a javadoc example. */
    private static final String DRAW = ".text().draw()";
    private static final String RETAINED = ".text().retainedDraw()";
    private static final List<String> HELPERS = List.of("TextStrokeStyle", "TextShadowStyle");

    @Test
    public void everyFileThatDrawsTextNamesTheInheritedStyleHelpers() throws IOException {
        String root = System.getProperty("cgui.test.repoRoot");
        Assume.assumeNotNull(root);

        List<String> offenders = new ArrayList<>();
        List<Path> drawing = new ArrayList<>();
        for (Path file : sources(Paths.get(root, "core", "src", "main", "java"))) {
            String code = stripComments(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (!code.contains(DRAW) && !code.contains(RETAINED)) continue;
            drawing.add(file);
            for (String helper : HELPERS) {
                if (!code.contains(helper)) offenders.add(file.getFileName() + " (no " + helper + ")");
            }
        }

        // If this drops to zero the scan has broken -- a rule that matches nothing passes forever.
        assertTrue("no text draw sites found at all; the scan is looking in the wrong place",
                drawing.size() >= 2);

        assertTrue("these draw text and never consider an inherited text style, so a declaration on an "
                        + "ancestor silently does nothing there: " + String.join(", ", offenders),
                offenders.isEmpty());
    }

    /** Comment bodies hold example code; only real statements count. */
    private static String stripComments(String source) {
        return Stream.of(source.split(String.valueOf((char) 10)))
                .filter(line -> {
                    String t = line.trim();
                    return !t.startsWith("*") && !t.startsWith("/*") && !t.startsWith("//");
                })
                .collect(Collectors.joining(String.valueOf((char) 10)));
    }

    private static List<Path> sources(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }
}
