package com.crystalgui.headless;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * <b>Nothing references upward.</b> The layering M6 ports into, read out of the constant pool.
 *
 * <p>{@code plan/engine-port.md} §2.6: the widget layer is being re-homed by KIND and by LAYER while it is
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
     * {@code plan/engine-port.md} D12, not drift.</p>
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

    /**
     * <b>The filesystem's own tiers</b> — {@code plan/fs-rewrite.md} D23, F0.7.
     *
     * <p>{@code fs} was five concerns in one directory (N35): a provider tier, a server workspace, a
     * wire binding, a client and a pile of client-local config. Nothing stopped the server naming the
     * client or either naming the UI's networking, and both happened —
     * {@code WorkspaceClient}'s constructor took a {@code ClientUiSession}, so the filesystem depended
     * on UI networking.</p>
     *
     * <p>Read bottom-up: the provider tier knows nothing above it; {@code fs.project} knows the
     * provider; {@code fs.protocol} is shared and knows both; the server knows all three; the client
     * knows everything except the server. Empty packages pass vacuously, which is the plan working —
     * they fill in at F2, F3 and F4.</p>
     */
    @Test
    public void theFilesystemTiersDoNotReachUpward() throws IOException {
        Path root = ClassReferences.mainClassesRoot(getClass());
        List<String> tiers = List.of(
                // A PROJECT IS A NAMED ROOT, and a FILESYSTEM is what resolves one to a real directory
                // -- so `provider` names `project` and not the other way round. It was the other way
                // round for an afternoon: `CgPath`, `CgFileError` and `CgFileSystemException` were
                // moved into `provider` with the rest, `project` names all three, and the two packages
                // became a CYCLE that compiles perfectly and cannot be ordered here at all. They live
                // at `fs`'s root instead, with `Resource` -- a path, a failure and an identity are the
                // vocabulary every tier names, which is what a root package is for.
                //
                // The root itself is NOT a tier and cannot be: `com/crystalgui/fs/` is a prefix of
                // every entry below, so listing it would fail each package against its own parent.
                // It needs no entry -- those four classes import nothing from `fs` at all.
                "com/crystalgui/fs/project/",
                "com/crystalgui/fs/provider/",
                // SHARED, and therefore below both halves rather than between them. A server that
                // could not name the protocol could not answer, and a protocol that named either half
                // would put one of them on the other's classpath.
                "com/crystalgui/fs/protocol/",
                "com/crystalgui/fs/server/",
                "com/crystalgui/fs/client/");
        List<String> offences = new ArrayList<>();
        for (int i = 0; i < tiers.size(); i++) {
            offences.addAll(ClassReferences.offences(root, tiers.get(i),
                    tiers.subList(i + 1, tiers.size())));
        }
        assertTrue("a filesystem tier reached upward:\n" + String.join("\n", offences),
                offences.isEmpty());
    }

    /**
     * <b>The filesystem may name the protocol layer of the wire and nothing above it.</b>
     *
     * <p>{@code net.mirror}, {@code net.window} and {@code net.projection} are the UI's; a file
     * service that names one of them is a filesystem that cannot be served without a UI session, which
     * is exactly what {@code WorkspaceClient} was. {@code net.protocol} and {@code net.wire} are the
     * transport and are fair game — that is what they are for.</p>
     */
    @Test
    public void theFilesystemNamesNoUiNetworking() throws IOException {
        Path src = repoRoot().resolve("core/src/main/java/com/crystalgui/fs");
        List<String> forbidden = List.of(
                "import com.crystalgui.net.mirror.",
                "import com.crystalgui.net.window.",
                "import com.crystalgui.net.projection.",
                "import com.crystalgui.net.ClientUiSession",
                "import com.crystalgui.net.ServerUiSession");
        List<String> offences = new ArrayList<>();
        if (Files.isDirectory(src)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(src)) {
                for (Path f : walk.toList()) {
                    if (!f.toString().endsWith(".java")) continue;
                    for (String line : Files.readAllLines(f)) {
                        for (String bad : forbidden) {
                            if (line.startsWith(bad)) offences.add(src.relativize(f) + ": " + line.trim());
                        }
                    }
                }
            }
        }
        // NO EXEMPTIONS. WorkspaceClient and WorkspaceRpc were listed here by name while F3 and F4 were
        // deleting them -- the day either stopped appearing was the day its entry went, which is what an
        // exemption listed by name buys over a relaxed rule.
        assertTrue("the filesystem named the UI's networking:\n" + String.join("\n", offences),
                offences.isEmpty());
    }

    /**
     * <b>The document MODEL names no widget</b> — {@code plan/fs-rewrite.md} D4, A6.
     *
     * <p>The document layer sits above {@code widget} today because {@code FileDocument.view()} answers
     * a node and {@code TextFileDocument} wraps a {@code TextEditor}. That is what left the one headless
     * document model the engine has ({@code TextBuffer}) sitting a package below, unused as one, and it
     * is why a document could not be opened, analysed or saved without a window.</p>
     *
     * <p>The layer cannot move until those three classes are deleted at F5. What can be asserted now is
     * the property the move exists for, over the types that replace them: a model, a document, a kind and
     * an editor input name {@code ui.dom} at most, and never a widget. {@code DocumentEditor} is the one
     * exception and names {@code UIElement} rather than any widget — a view is an element, and which
     * widget it is made of is the editor's business.</p>
     */
    @Test
    public void theDocumentModelNamesNoWidget() throws IOException {
        Path src = repoRoot().resolve("core/src/main/java/com/crystalgui/document");
        List<String> headless = List.of(
                "DocumentModel.java", "AbstractDocumentModel.java", "TextDocumentModel.java",
                "BytesDocumentModel.java", "Document.java", "DocumentReference.java",
                "Documents.java", "DocumentState.java", "DocumentKind.java", "DocumentKinds.java",
                "EditorInput.java");
        List<String> offences = new ArrayList<>();
        for (String name : headless) {
            Path file = src.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            for (String line : Files.readAllLines(file)) {
                if (line.startsWith("import com.crystalgui.widget.")) {
                    offences.add(name + ": " + line.trim());
                }
            }
        }
        assertTrue("the document model named a widget:" + offences, offences.isEmpty());
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
                + "plan/engine-port.md §2.6?\n" + String.join("\n", ungoverned), ungoverned.isEmpty());
    }

    /**
     * <b>An extension names the CONTEXT, never the engine.</b>
     *
     * <p>{@code WorkbenchContext} exists so that a feature can attach itself to a workbench without
     * being able to reach into one, and an interface only holds that line if something checks it. This
     * is the first case of the rule, asserted where it is already true: the Notes file type is a
     * complete extension — an id, a declaration and a handle back, in one class — and its class file
     * names the context and not {@code Workbench}.</p>
     *
     * <p><b>What it deliberately does not yet assert</b> is the whole rule: nothing under {@code app/}
     * or {@code language/} may name the engine. Both still do, and porting them is the point of two
     * later steps rather than something to smuggle in here — an assertion that fails, or one that is
     * ignored, is worse than the narrow one that passes. {@code CrystalEditor} holds a {@code Workbench}
     * field, and the Run shell names it in four files that live in another worktree.</p>
     */
    @Test
    public void anExtensionNamesTheContextAndNotTheEngine() throws IOException {
        Path root = ClassReferences.mainClassesRoot(getClass());
        Path notes = root.resolve("com/crystalgui/example/notes/NotesKind.class");
        assertTrue("the Notes extension was not compiled: " + notes, Files.isRegularFile(notes));

        Set<String> referenced = ClassReferences.referencesOf(notes);
        assertTrue("an extension is written against com/crystalgui/workbench/WorkbenchContext",
                referenced.contains("com/crystalgui/workbench/WorkbenchContext"));
        assertTrue("...and must not name the engine itself -- an engine that can be named can be "
                        + "reached into, which is the whole reason the interface exists",
                !referenced.contains("com/crystalgui/workbench/Workbench"));
    }
}
