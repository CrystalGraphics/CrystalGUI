package com.crystalgui.language.run;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * A tag the shipped sheets name is a tag some class must answer to.
 *
 * <p><b>The old engine derived a tag and this one does not.</b> There, {@code tagName()} fell back to
 * the class's lowercased simple name, so a widget that registered nothing still answered
 * {@code runpanel} and its rules matched. Here a kind is a {@code NAME} constant declared on the class
 * and INHERITED when absent, so a class that declares none answers {@code crystalgui:element} — and
 * every rule written for its tag matches nothing.</p>
 *
 * <p>Which is silent in the worst way: the widget builds, lays out, takes input and works. It is
 * simply unstyled, so it reads as a missing stylesheet rather than a missing constant.
 * {@code RunPanel} shipped that way — 49 rules in {@code ua/panels.css} matching nothing — and was
 * reported as the empty-state caption not being centred.</p>
 *
 * <p>{@code core} has {@code NodeKindsCoverageTest} for its own widgets and cannot see this module,
 * which is exactly where this slipped through. The question is NOT "does every class declare a kind":
 * {@code RunConsoleView} and {@code RunRail} declare none and are right not to, because no rule names
 * those tags — they are styled by class. What must hold is that nothing in the sheets is left
 * addressing a tag nobody answers.</p>
 *
 * <p>A SOURCE scan rather than a reflective one, deliberately: loading a widget resolves its field
 * descriptors, so it drags JOML and the whole render stack onto a classpath this module's tests do
 * not have. The same reason {@code EngineBoundaryTest} reads class files instead of classes.</p>
 */
public class SheetNamedKindsTest {

    private static final String[] SHEETS = {
            "/assets/crystalgui/ui/styles/ua/panels.css",
            "/assets/crystalgui/ui/styles/ua/workbench.css",
    };

    private static final Pattern DECLARES_NAME =
            Pattern.compile("static final Name NAME = Name[.]of[(]" + '"' + "([a-z0-9-]+)" + '"' + "[)]");
    private static final Pattern IS_NODE = Pattern.compile("class (\\w+)[^{]*? extends (\\w+)");

    private static String read(String path) throws IOException {
        try (InputStream in = SheetNamedKindsTest.class.getResourceAsStream(path)) {
            assertTrue(path + " is not on the classpath", in != null);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Type selectors, with comments stripped first — a comment is where prose lives. */
    private static Set<String> tagsIn(String css) {
        Set<String> tags = new LinkedHashSet<>();
        String text = css.replaceAll("(?s)/\\*.*?\\*/", " ");
        Matcher rules = Pattern.compile("([^{}]+)\\{").matcher(text);
        while (rules.find()) {
            for (String part : rules.group(1).split(",")) {
                Matcher token = Pattern.compile("(?:^|[\\s>])([a-z][a-z0-9-]*)").matcher(part.trim());
                while (token.find()) tags.add(token.group(1));
            }
        }
        return tags;
    }

    @Test
    public void everyKindTheSheetsNameIsAnsweredByItsClass() throws IOException {
        Set<String> tags = new LinkedHashSet<>();
        for (String sheet : SHEETS) tags.addAll(tagsIn(read(sheet)));
        assertTrue("the sheets parsed to nothing, so this proves nothing", tags.size() > 20);

        Path root = Paths.get("src", "main", "java", "com", "crystalgui", "language");
        assertTrue("cannot find this module's sources at " + root.toAbsolutePath(),
                Files.isDirectory(root));

        List<String> wrong = new ArrayList<>();
        try (Stream<Path> java = Files.walk(root)) {
            for (Path file : java.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher declaration = IS_NODE.matcher(source);
                if (!declaration.find()) continue;
                String simple = declaration.group(1);
                String expected = simple.toLowerCase(Locale.ROOT);
                // A tag no rule names needs no kind: those widgets are styled by class.
                if (!tags.contains(expected)) continue;
                Matcher declared = DECLARES_NAME.matcher(source);
                if (!declared.find() || !expected.equals(declared.group(1))) {
                    wrong.add(expected + " is named by a sheet, but " + simple
                            + " declares no matching NAME (" + file.getFileName() + ")");
                }
            }
        }
        assertEquals("declare `public static final Name NAME` and pass it to super(): " + wrong,
                List.of(), wrong);
    }
}
