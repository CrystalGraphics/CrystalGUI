package com.crystalgui.headless;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * A widget that declares a kind must actually REPORT it.
 *
 * <p>{@code UIElement}'s no-argument constructor answers {@code crystalgui:element}, so a subclass that
 * declares {@code NAME} and never passes it to {@code super} compiles, registers, and then reports the
 * base tag for the rest of its life — and <b>every tag-scoped rule aimed at it matches nothing</b>.</p>
 *
 * <p>Thirteen classes did this at once: the whole workbench layer plus both editors. The Crystal Editor
 * came out as a menu bar, a 78px sliver and a status bar, because {@code crystaleditor > .__editor-content__}
 * and every {@code workbench ...} rule in the user-agent sheet were addressing a tag no instance had.
 * The left rail is the clearest single symptom — {@code workbench .__activity-bar__} never matched, so
 * it laid out as a 582px-wide horizontal bar with full-width buttons instead of a 20px vertical rail
 * with 16px squares.</p>
 *
 * <p>It reads as the workbench having failed to BUILD rather than as its rules having failed to match,
 * which is what makes it worth a test rather than a careful eye: the tree was correct the whole time.
 * The same shape is already recorded for the old engine, where {@code tagName()} was an exact-class
 * lookup and {@code ToolWindowFrame} reported {@code toolwindowframe} and matched none of
 * {@code ua/desktop.css}.</p>
 *
 * <h3>Inheriting a kind on purpose</h3>
 *
 * <p>{@link #INHERITS_ON_PURPOSE} is the other half of the rule, and it is a real design choice rather
 * than an escape hatch: when the answer is "everything the supertype has, plus a modifier class", the
 * supertype's tag is the RIGHT one to report. A tool window and a torn-out dock window are windows and
 * are styled as {@code window} plus a class; no sheet names either of their own tags.</p>
 */
public class NodeKindDeclarationTest {

    /**
     * Classes that declare a {@code NAME} and deliberately report their supertype's tag instead.
     *
     * <p>Both are {@code WindowFrame}s and both are styled as {@code window} plus a modifier class. The
     * old engine has the counter-example written down: {@code Dropdown extends Button} does NOT answer
     * {@code button}, because a dropdown taking a button's whole look is wrong.</p>
     */
    private static final Set<String> INHERITS_ON_PURPOSE = Set.of("DockWindow", "ToolWindowFrame");

    @Test
    public void everyDeclaredKindIsActuallyReported() throws IOException {
        Path root = sourceRoot();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (!source.contains("Name NAME = Name.of(")) continue;
                String name = file.getFileName().toString().replace(".java", "");
                if (INHERITS_ON_PURPOSE.contains(name)) continue;
                // The three legal spellings: pass it up, chain to a constructor that does, or take a
                // Name as a parameter and pass that -- the protected `(Name, ...)` pattern a widget
                // meant to be extended uses.
                if (source.contains("super(NAME") || source.contains("this(NAME")
                        || source.contains("super(name") || source.contains("super(kind")) {
                    continue;
                }
                offenders.add(name);
            }
        }
        assertTrue("these declare a kind and never pass it to super, so every instance reports"
                + " crystalgui:element and every tag-scoped rule aimed at them matches nothing: "
                + offenders, offenders.isEmpty());
    }

    /** The engine's source tree, found by walking up — tests run from a module directory. */
    private static Path sourceRoot() {
        for (Path at = Paths.get("").toAbsolutePath(); at != null; at = at.getParent()) {
            Path candidate = at.resolve("src/main/java/com/crystalgui");
            if (Files.isDirectory(candidate)) return candidate;
            Path nested = at.resolve("core/src/main/java/com/crystalgui");
            if (Files.isDirectory(nested)) return nested;
        }
        throw new IllegalStateException("the engine's source tree is not above " + Paths.get("").toAbsolutePath());
    }
}
