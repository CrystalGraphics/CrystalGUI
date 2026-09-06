package com.crystalgui.headless;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * <b>The graph's features are separable, and the scan is what keeps them so.</b>
 *
 * <p>{@code GraphView} was 1,840 lines holding wires, ports, the clipboard, the node library, the
 * document projection and the policy in one class. Splitting them is easy; keeping them split is not —
 * every one of them is one field access away from reaching into another, and nothing about the symptom
 * would say so. These are the two rules that make the split hold.</p>
 */
public class SurfaceLayeringTest {

    /**
     * The graph's features, by class file.
     *
     * <p>Deliberately NOT everything in the package. {@code GraphEdits} is the shared vocabulary every
     * feature records through — that is the whole reason it is a file of its own — and {@code GraphNode},
     * {@code NodePort}, {@code GraphConnection}, {@code NodeWireLayer} and {@code NodeWidgetFactory} are
     * the graph's TYPES rather than its features. Naming one of those is not coupling; naming another
     * feature is.</p>
     */
    private static final List<String> FEATURES = List.of(
            "GraphWires",
            "GraphPorts",
            "GraphClipboard",
            "GraphNodeLibrary",
            "GraphDocumentSync",
            "GraphPolicy");

    private static final String GRAPH_PACKAGE = "com/crystalgui/widget/graph/";

    /**
     * <b>I2 — no graph feature names another.</b>
     *
     * <p>They meet at the hub instead. A wire dropped on empty space has to open the create menu, and
     * that is the one place two features genuinely have to touch: {@code GraphView.endPendingWire}
     * converts plane space to world — only the wire layer knows that offset — and hands the library a
     * world point. Either feature reaching for the other directly would work perfectly and be the end of
     * the split.</p>
     */
    @Test
    public void noGraphFeatureNamesAnother() throws IOException {
        Path root = ClassReferences.mainClassesRoot(getClass());
        List<String> offences = new ArrayList<>();
        for (String feature : FEATURES) {
            Set<String> forbidden = new LinkedHashSet<>();
            for (String other : FEATURES) {
                if (!other.equals(feature)) forbidden.add(GRAPH_PACKAGE + other);
            }
            for (Path classFile : classesFor(root, feature)) {
                for (String referenced : ClassReferences.referencesOf(classFile)) {
                    for (String bad : forbidden) {
                        if (referenced.equals(bad) || referenced.startsWith(bad + "$")) {
                            offences.add(feature + " names " + referenced);
                        }
                    }
                }
            }
        }
        assertTrue("a graph feature reached another:\n" + String.join("\n", offences), offences.isEmpty());
    }

    /** Every way a {@code GraphDocument} can be changed. */
    private static final List<String> DOCUMENT_MUTATORS = List.of(
            "addNode", "removeNode", "moveNode", "replaceNode", "clear",
            "connect", "disconnect", "restoreEdge",
            "removeProperty", "replaceProperty", "moveProperty");

    /**
     * <b>I6 — a feature does not mutate the document; it records an edit.</b>
     *
     * <p>Two classes may touch a mutator, and the pair is the rule stated twice.
     * {@code GraphDocumentSync} is the projection and therefore the one place the document is written;
     * {@code GraphEdits} is where a change becomes undoable, so a mutator reached from there IS an edit
     * by construction.</p>
     *
     * <p>Anywhere else it is a change with no undo entry — and the failure is not that Ctrl+Z does
     * nothing. It is that Ctrl+Z does something ELSE: the stack's next entry describes a graph that no
     * longer exists, so undoing it applies to nodes that were never there.</p>
     */
    @Test
    public void onlyTheSeamAndTheEditsMutateTheDocument() throws IOException {
        Path root = ClassReferences.mainClassesRoot(getClass());
        Set<String> allowed = Set.of(
                GRAPH_PACKAGE + "GraphDocumentSync",
                GRAPH_PACKAGE + "GraphEdits");
        List<String> offences = new ArrayList<>();
        Path directory = root.resolve(GRAPH_PACKAGE);
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path classFile : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                String owner = ownerOf(root, classFile);
                if (allowed.contains(owner)) continue;
                for (String member : ClassReferences.memberReferencesOf(classFile)) {
                    if (!member.startsWith("com/crystalgui/graph/GraphDocument.")) continue;
                    String called = member.substring(member.lastIndexOf('.') + 1);
                    if (DOCUMENT_MUTATORS.contains(called)) {
                        offences.add(owner + " calls GraphDocument." + called);
                    }
                }
            }
        }
        assertTrue("the document was changed outside the seam and the edits:\n"
                + String.join("\n", offences), offences.isEmpty());
    }

    /** The outer class a (possibly nested) class file belongs to, as a slash name. */
    private static String ownerOf(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString().replace('\\', '/');
        relative = relative.substring(0, relative.length() - ".class".length());
        int nested = relative.indexOf('$');
        return nested < 0 ? relative : relative.substring(0, nested);
    }

    /** A feature's own class file plus every nested class of it. */
    private static List<Path> classesFor(Path root, String feature) throws IOException {
        Path directory = root.resolve(GRAPH_PACKAGE);
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> walk = Files.list(directory)) {
            return walk.filter(p -> {
                String name = p.getFileName().toString();
                return name.equals(feature + ".class") || name.startsWith(feature + "$");
            }).toList();
        }
    }
}
