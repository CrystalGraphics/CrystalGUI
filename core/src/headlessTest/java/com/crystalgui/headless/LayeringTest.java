package com.crystalgui.headless;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * <b>Nothing references upward.</b> The layering M6 ports into, read out of the constant pool.
 *
 * <p>{@code plan_m6.md} §2.6: the widget layer is being re-homed by KIND and by LAYER while it is
 * copied, and a layering nothing enforces is a layering that lasts until the first hurry. The rule
 * is one line — <b>engine &lt; widget &lt; chrome &lt; desktop &lt; workbench &lt; applications</b>,
 * and inside {@code widget}, {@code control}/{@code text}/{@code scroll} below
 * {@code overlay}/{@code layout}/{@code dnd} below everything else — and this is what stops a
 * {@code Button} learning about a {@code WindowFrame} again.</p>
 *
 * <h3>Why it is worth a test rather than a convention</h3>
 *
 * <p>The old tree had no layering to break: {@code ui.elements} was one directory with a
 * {@code Button} and a {@code MarkupView} at its root, {@code desktop}, {@code workbench},
 * {@code editor} and {@code chrome} were flat at 24–38 files each, and a leaf widget importing the
 * workbench would have looked like every other import. The census found the consequences rather than
 * the cause — {@code .__content__} claimed by three unrelated widgets, a selector zeroing every
 * {@code ConfiguratorGroup} in the application — and both are what a layer boundary would have
 * refused.</p>
 *
 * <h3>It was written before anything was ported, and that is the point</h3>
 *
 * <p>Every assertion was vacuous at 6.0 — the layers were all empty. Written then because the first
 * ported widget is the one that would otherwise set the wrong precedent, and a test added after the
 * fact is a test somebody has to make pass. {@code widget/control} is the first to exist.</p>
 *
 * @see EngineBoundaryTest the OTHER direction: what the new engine may not name at all
 */
public class LayeringTest {

    /** A layer, and everything it may not name. Ordered bottom-up; each may name only what precedes it. */
    private static final List<String> LAYERS = List.of(
            "com/crystalgui/widget/",
            // THE DOCUMENT LAYER -- above `widget` because a document says what SHOWS it
            // (`FileDocument.view()` answers a node), and below everything that opens one. It is
            // deliberately NOT in `fs`, which has zero imports of any UI package across its 37
            // files: putting a class that names a node there would put the engine on a dedicated
            // server's classpath, which is the one thing `headlessTest` exists to prevent. And it
            // is not the workbench's, because four things outside the workbench open documents --
            // CrystalEditor, the shader graph, the dock and WorkbenchSettings.
            "com/crystalgui/document/",
            "com/crystalgui/desktop/",
            // ONE LAYER, and its sub-packages are organisational rather than ordered: a prefix
            // covers them, so region, toolwindow, explorer, dock, chrome and the rest are already
            // inside it. Listing one as a LAYER made it read as "above" the layer root, and the
            // layer's own registrar -- which must name everything in it -- became a layer reaching
            // upward. Ordering WITHIN a layer is a separate question and has its own list; see
            // WIDGET_TIERS, which is the only layer that needs one.
            //
            // `chrome` WAS its own layer here and is now `workbench.chrome`, because the split was
            // in the wrong place and there was a cycle proving it: `ProblemsPanel implements
            // HeaderContributor`, a workbench interface, so chrome reached UP. Nothing below the
            // workbench wanted it either -- the desktop's four chrome imports were all
            // `ContextMenu`/`MenuBuilder`, which 6.3 had already put in `widget.overlay`.
            "com/crystalgui/workbench/",
            // THE APPLICATIONS, and the layer the doctrine has always named without anything
            // enforcing it: `graph.shader` was reachable from a leaf widget because nothing in this
            // list covered it. ONE prefix for all of them -- app.shadergraph at 6.4, app.editor and
            // app.machine at 6.7 -- rather than an entry each, and its sub-packages get none of
            // their own, for the reason the chrome note above records.
            "com/crystalgui/app/");

    /**
     * The tiers WITHIN {@code widget}, bottom-up.
     *
     * <p>A control does not know what a dialog is; a dialog does not know what a list is. The split
     * is what makes "a {@code Button} is general-purpose" a checkable claim rather than an intention
     * — and the reason {@code ScrollerView} and {@code ListView} are not in the same package as
     * {@code Button} despite all three being widgets.</p>
     */
    private static final List<String> WIDGET_TIERS = List.of(
            "com/crystalgui/widget/control/",
            // DISPLAY IS A BOTTOM TIER and was two files inside `control`. A ProgressBar and a
            // SymbolIcon take no input at all, which is the whole of what `control` means -- and the
            // tier is not a guess: between them they import `ui` and `style` and NOT ONE widget.
            "com/crystalgui/widget/display/",
            "com/crystalgui/widget/text/",
            // SCROLL IS A SIBLING OF LAYOUT AND CANNOT BE A CHILD OF IT. Nesting it as
            // `widget/layout/scroll` was tried and this list cannot express it: `matches` is a prefix
            // test, so every reference Scroller makes TO ITSELF matches the rule
            // `com/crystalgui/widget/layout/` -- and scroll sits BELOW layout, so the package failed
            // against its own parent. Loosening the match to let a sub-package off would silently
            // permit scroll -> layout, which is the one edge the ordering exists to forbid. The
            // dependencies agree with the flat shape: `widget/text/MarkupView` uses a ScrollerView,
            // and layout/scroll imported nothing from layout at all.
            "com/crystalgui/widget/scroll/",
            "com/crystalgui/widget/overlay/",
            "com/crystalgui/widget/layout/",
            "com/crystalgui/widget/dnd/",
            "com/crystalgui/widget/collection/",
            "com/crystalgui/widget/collection/list/",
            "com/crystalgui/widget/collection/tree/",
            "com/crystalgui/widget/collection/table/",
            "com/crystalgui/widget/composite/",
            // THE CONFIG KIT IS ITS OWN THING, not a corner of `form`. `form` holds controls a caller
            // places by hand -- ColorSelector, SearchField; `config` is the descriptor-driven form
            // GENERATOR over them, and `config/control` its thirteen field editors. Above tier 5, so
            // the three impose no ordering on each other, which is right: a control extends
            // ConfigControl and the inspector composes Configurators, so any order between them would
            // be a claim the code contradicts.
            "com/crystalgui/widget/config/",
            "com/crystalgui/widget/config/control/",
            "com/crystalgui/widget/config/inspector/",
            "com/crystalgui/widget/canvas/",
            "com/crystalgui/widget/graph/",
            // The node's own BUILD half: the widget factory's callers, the field binder and the
            // create menu. GraphNode, NodePort and PortDefaultEditor are NOT here and cannot be --
            // they share package-private members with GraphView by design (`setSelected`,
            // `bindToDocument`, `setConnectionCount`, the whole editor mount lifecycle), and Java has
            // no sub-package visibility, so splitting them means publishing ten "only the view may
            // call this" methods. The boundary is where the encapsulation already was.
            "com/crystalgui/widget/graph/node/",
            "com/crystalgui/widget/texteditor/",
            // THE EDITOR'S FOUR LANGUAGE FEATURES. Listed because `theTreeHasNoWidgetPackageThisFileHasNotHeardOf`
            // wants every widget directory named -- and listing them claims NO order, because every
            // entry past WIDGET_MIDDLE_TIER may name every other. That matters here more than
            // anywhere: TextEditor holds an EditorSuggest and an EditorFind as FIELDS, so the core
            // names the features and the features name the core. They are sub-packages OF the editor,
            // not tiers above it, and an ordered claim either way would be false.
            "com/crystalgui/widget/texteditor/part/",
            "com/crystalgui/widget/texteditor/fold/",
            "com/crystalgui/widget/texteditor/diff/",
            "com/crystalgui/widget/texteditor/suggest/",
            "com/crystalgui/widget/texteditor/doc/",
            "com/crystalgui/widget/texteditor/find/",
            "com/crystalgui/widget/texteditor/lang/");

    /** Which tiers may name which: an index into {@link #WIDGET_TIERS}, and everything at or below it. */
    private static final int WIDGET_BOTTOM_TIER = 3; // control, display, text, scroll
    private static final int WIDGET_MIDDLE_TIER = 6; // + overlay, layout, dnd

    @Test
    public void aLayerNamesNothingAboveIt() throws IOException {
        Path root = ClassReferences.mainClassesRoot(getClass());
        List<String> offences = new ArrayList<>();
        for (int i = 0; i < LAYERS.size(); i++) {
            List<String> above = LAYERS.subList(i + 1, LAYERS.size());
            if (above.isEmpty()) continue;
            offences.addAll(ClassReferences.offences(root, LAYERS.get(i), above));
        }
        assertTrue("a layer reached upward:\n" + String.join("\n", offences), offences.isEmpty());
    }

    @Test
    public void aWidgetTierNamesNothingAboveItsOwn() throws IOException {
        Path root = ClassReferences.mainClassesRoot(getClass());
        List<String> offences = new ArrayList<>();
        for (int i = 0; i < WIDGET_TIERS.size(); i++) {
            int highestAllowed = i <= WIDGET_BOTTOM_TIER ? WIDGET_BOTTOM_TIER
                    : i <= WIDGET_MIDDLE_TIER ? WIDGET_MIDDLE_TIER : WIDGET_TIERS.size() - 1;
            List<String> above = WIDGET_TIERS.subList(highestAllowed + 1, WIDGET_TIERS.size());
            if (above.isEmpty()) continue;
            offences.addAll(ClassReferences.offences(root, WIDGET_TIERS.get(i), above));
        }
        assertTrue("a widget tier reached above its own:\n" + String.join("\n", offences), offences.isEmpty());
    }

    /**
     * <b>The headless side names no UI</b> — and this one is asserted on IMPORTS, not on the constant
     * pool, which is the opposite of every other assertion in this file and deliberate.
     *
     * <p>{@code text/} is the document model and {@code style/} is the cascade, written against
     * {@code Styleable} so a cascade bug is fixed once. Neither has any business naming the tree. Both
     * were true when this was written and both had been false within the previous week — which is the
     * argument for the test rather than the convention.</p>
     *
     * <h3>Why imports and not bytecode</h3>
     *
     * <p>Because the defect this exists to catch <b>is</b> the import. Twice in one afternoon a class
     * was moved DOWN out of {@code ui} to remove an inverted dependency, and arrived carrying a
     * {@code @link} or {@code @see} back to what it had left — {@code text.TextRange} at
     * {@code ui.text.HighlightRegistry}, {@code core.data.ClipboardActions} at
     * {@code ui.data.UiDataKeys}. A javadoc reference compiles to nothing, so a constant-pool scan
     * sees an empty class and passes; the source still says the two layers know about each other, and
     * the next person to need a real reference finds the import already sitting there. Moving a class
     * to fix an edge and leaving the link that recreates it is a shape, not an accident.</p>
     *
     * <p>{@code core/} is deliberately NOT on this list. It names {@code ui} six times and every one is
     * decided — {@code CommandRegistry} holds a {@code Keymap}, {@code UndoScope} walks a
     * {@code UIElement}, the clipboard commands resolve through {@code UiDataKeys} — which is
     * {@code plan_m6.md} D12, not drift.</p>
     */
    @Test
    public void theHeadlessSideNamesNoUi() throws IOException {
        Path src = repoRoot().resolve("core/src/main/java/com/crystalgui");
        List<String> offences = new ArrayList<>();
        for (String pkg : List.of("text", "style", "fs", "serialization")) {
            Path dir = src.resolve(pkg);
            if (!Files.isDirectory(dir)) continue;
            try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                for (Path f : walk.toList()) {
                    if (!f.toString().endsWith(".java")) continue;
                    for (String line : Files.readAllLines(f)) {
                        if (line.startsWith("import com.crystalgui.ui.")) {
                            offences.add(src.relativize(f) + ": " + line.trim());
                        }
                    }
                }
            }
        }
        assertTrue("the headless side named the tree:\n" + String.join("\n", offences), offences.isEmpty());
    }

    /** The repository root, found by walking up to the settings file. */
    private static Path repoRoot() {
        for (Path p = ClassReferences.mainClassesRoot(LayeringTest.class); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("settings.gradle.kts"))) return p;
        }
        throw new IllegalStateException("cannot find the repository root");
    }

    /**
     * A package that exists and is governed by nothing.
     *
     * <p>That is how a layering test rots: its two assertions keep passing by describing a tree that
     * is not there. Checked in the direction that is true mid-port — the layers arrive one batch at a
     * time, so their ABSENCE is the plan working, and only an unrecognised one is a problem.</p>
     *
     * <p>The first version asserted the opposite (once any layer exists, they all do) and Button's
     * port failed it immediately: {@code widget/control} lands in 6.1 and {@code workbench} not until
     * 6.7.</p>
     */
    @Test
    public void theTreeHasNoWidgetPackageThisFileHasNotHeardOf() throws IOException {
        Path root = ClassReferences.mainClassesRoot(getClass());
        List<String> all = new ArrayList<>(LAYERS);
        all.addAll(WIDGET_TIERS);
        Path widget = root.resolve("com/crystalgui/widget");
        if (!Files.isDirectory(widget)) return;
        List<String> ungoverned = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(widget, 2)) {
            for (Path p : walk.toList()) {
                if (!Files.isDirectory(p)) continue;
                String rel = root.relativize(p).toString().replace('\\', '/') + "/";
                boolean known = all.contains(rel) || all.stream().anyMatch(l -> l.startsWith(rel));
                if (!known) ungoverned.add(rel);
            }
        }
        assertTrue("a widget package no tier governs -- renamed without updating LayeringTest and "
                + "plan_m6.md §2.6?\n" + String.join("\n", ungoverned), ungoverned.isEmpty());
    }
}
