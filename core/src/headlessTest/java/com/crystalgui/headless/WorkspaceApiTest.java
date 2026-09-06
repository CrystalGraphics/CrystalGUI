package com.crystalgui.headless;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>What a mod has to name to own a file type</b> — asserted against the class files.
 *
 * <p>{@code com.crystalgui.example.notes} is the smallest complete kind, and it is here to be measured
 * rather than admired: a surface is only as small as the narrowest thing that can actually be built on
 * it, so the example is the measurement.</p>
 *
 * <h3>The split is the assertion</h3>
 *
 * <p>A document model is <b>headless</b> — that is the claim the whole layer rests on, and it is what
 * lets the Problems panel, a background compile and Go to Definition work on a file nobody has a tab
 * open onto. A model that reached one widget would end that quietly: everything would still work, and
 * the next model written by copying this one would inherit the reach.</p>
 *
 * <p>So {@link com.crystalgui.example.notes.NotesModel} may name no widget and no element, the
 * declaration beside it may name nothing but the document layer <em>and the two-method seam that
 * attaches it</em>, and the view is the one half that names widgets. <b>Read on the constant pool</b>, because that is the real question — a class file
 * that names a type at all is one some input can reach it through, where a runtime check only ever
 * proves it for the path the test happened to take.</p>
 */
public class WorkspaceApiTest {

    private static final String EXAMPLE = "com/crystalgui/example/notes";

    /**
     * What an author's own code is allowed to reach.
     *
     * <p>Three packages plus the JDK, which is the surface {@code plan/fs-rewrite.md} §6 set out to
     * make sufficient: {@code document} says what a kind is, {@code fs.client} is the workspace, and
     * {@code core.async} carries every answer that is not ready yet. {@code core.signal},
     * {@code core.undo} and {@code core.dispose} come with them — an edit, a signal and a handle are
     * the vocabulary those three are written in.</p>
     */
    private static final List<String> AUTHOR_SURFACE = List.of(
            "com/crystalgui/document/",
            "com/crystalgui/fs/client/",
            "com/crystalgui/fs/Resource",
            "com/crystalgui/core/async/",
            "com/crystalgui/core/signal/",
            "com/crystalgui/core/undo/",
            "com/crystalgui/core/dispose/",
            // AND THE SEAM, WHICH IS TWO INTERFACES AND NOT A PACKAGE. A file type is attached by
            // implementing `WorkbenchExtension` and taking a `WorkbenchContext`, so a declaration
            // genuinely names those -- it used to name neither only because a second class held them
            // and delegated back, which is a wrapper wearing a boundary's clothes. Naming the two
            // types rather than `com/crystalgui/workbench/` is what keeps the assertion sharp: an
            // author reaches the SEAM, never the engine, and `Workbench` itself is still refused.
            "com/crystalgui/workbench/extension/WorkbenchExtension",
            "com/crystalgui/workbench/WorkbenchContext");

    /** The model's own share of it: no workspace either, because a model knows nothing about files. */
    private static final List<String> MODEL_SURFACE = List.of(
            "com/crystalgui/document/",
            "com/crystalgui/core/signal/",
            "com/crystalgui/core/undo/");

    private static Path classFile(String name) {
        return ClassReferences.mainClassesRoot(WorkspaceApiTest.class).resolve(EXAMPLE + "/" + name);
    }

    /** Every {@code com.crystalgui} type this class file names, outside {@code allowed}. */
    private static List<String> reachesOutside(String name, List<String> allowed) throws IOException {
        Set<String> referenced = ClassReferences.referencesOf(classFile(name));
        List<String> outside = new ArrayList<>();
        for (String reference : referenced) {
            if (!reference.startsWith("com/crystalgui/")) continue;
            if (reference.startsWith(EXAMPLE)) continue;
            if (allowed.stream().anyMatch(reference::startsWith)) continue;
            outside.add(reference);
        }
        return outside;
    }

    /** Every class file of the example, inner classes included. */
    private static List<String> exampleClassFiles() throws IOException {
        Path root = ClassReferences.mainClassesRoot(WorkspaceApiTest.class).resolve(EXAMPLE);
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".class"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    /**
     * <b>A document model is headless.</b> The claim the whole layer rests on, and the one a copied
     * example would silently break.
     */
    @Test
    public void theModelNamesNoWidgetAndNoElement() throws IOException {
        for (String file : exampleClassFiles()) {
            if (!file.startsWith("NotesModel")) continue;
            assertEquals(file + " reaches outside the headless surface",
                    List.of(), reachesOutside(file, MODEL_SURFACE));
        }
    }

    /**
     * The declaration is the document layer plus the seam — no widget, no dock, no {@code Workbench}.
     *
     * <p>{@code WorkbenchExtension} and {@code WorkbenchContext} are named individually rather than by
     * package, which is the whole point: an author writes against two interfaces, and the engine class
     * behind them stays as refused as a widget is.</p>
     */
    @Test
    public void theDeclarationNamesOnlyTheDocumentLayerAndTheSeam() throws IOException {
        assertEquals(List.of(), reachesOutside("NotesKind.class", AUTHOR_SURFACE));
    }

    /**
     * <b>The counter-control.</b> The view <em>does</em> name widgets, which is what a view is — so a
     * test written as "the example names no widget" would be asserting that the example cannot be
     * displayed, and would pass against an example with no view at all.
     */
    @Test
    public void theViewIsTheOneHalfThatNamesWidgets() throws IOException {
        Set<String> referenced = ClassReferences.referencesOf(classFile("NotesView.class"));
        assertTrue("a view of a document is made of widgets",
                referenced.stream().anyMatch(r -> r.startsWith("com/crystalgui/widget/")));
    }

    /**
     * And it names widgets and the document layer and <b>nothing else</b> — not the workbench, not the
     * dock, not the desktop. A view is handed its document; it does not go looking for the application.
     */
    @Test
    public void theViewStillDoesNotReachTheApplication() throws IOException {
        List<String> allowed = new ArrayList<>(AUTHOR_SURFACE);
        allowed.add("com/crystalgui/widget/");
        allowed.add("com/crystalgui/ui/");
        assertEquals(List.of(), reachesOutside("NotesView.class", allowed));
    }

    /**
     * The example is complete, so the measurement means something.
     *
     * <p>A surface is only as small as the narrowest thing that can be built on it — and an "example"
     * that declared a kind and never opened, edited, undid or saved would satisfy every assertion above
     * while measuring nothing.</p>
     */
    @Test
    public void theExampleIsAWholeKind() throws IOException {
        List<String> files = exampleClassFiles();
        assertTrue("a model", files.stream().anyMatch(f -> f.startsWith("NotesModel")));
        assertTrue("a view", files.contains("NotesView.class"));
        assertTrue("a declaration", files.contains("NotesKind.class"));
        // AND THE EDITS, which is what makes the model's changes undoable rather than merely applied.
        assertTrue("undoable edits", files.stream().anyMatch(f -> f.contains("Edit")));
    }
}
