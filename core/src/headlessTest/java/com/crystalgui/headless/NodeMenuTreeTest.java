package com.crystalgui.headless;

import com.crystalgui.graph.NodeMenuTree;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.2.6b — the create menu's category tree.
 *
 * <h3>What is actually being asserted</h3>
 * <p>Grouping is pure logic over {@link NodeType#category()}, so it is pinned here rather than through a
 * widget: a test that had to lay out a popover to discover that {@code "Math/Basic"} makes two folders
 * would be testing the popover.</p>
 *
 * <p>The one non-obvious requirement is <b>identity</b>. The tree is rebuilt wholesale on every keystroke
 * and {@code TreeView} tracks which nodes are open in a {@code Set<T>} keyed on {@code equals} — so if a
 * rebuilt node is not equal to the one the user opened, every folder snaps shut as they type.</p>
 */
public class NodeMenuTreeTest {

    private static List<NodeTypeRegistry.Offer> offers(NodeType... types) {
        List<NodeTypeRegistry.Offer> offers = new ArrayList<>();
        for (NodeType type : types) offers.add(new NodeTypeRegistry.Offer(type, null));
        return offers;
    }

    private static NodeType type(String label, String category) {
        return NodeType.of("shader." + label).label(label).category(category).build();
    }

    private static List<String> labels(List<NodeMenuTree.Node> nodes) {
        List<String> out = new ArrayList<>();
        for (NodeMenuTree.Node node : nodes) out.add(node.label());
        return out;
    }

    // ── Grouping ────────────────────────────────────────────────────────────

    @Test
    public void aCategoryPathBecomesAFolderChain() {
        List<NodeMenuTree.Node> roots = NodeMenuTree.categorised(offers(
                type("Position", "Input/Geometry"),
                type("Normal", "Input/Geometry"),
                type("Time", "Input")));

        assertEquals(List.of("Input"), labels(roots));
        NodeMenuTree.Node input = roots.get(0);
        assertTrue(input.isCategory());
        // Geometry (a folder) sorts before Time (a leaf), which is the folders-first rule.
        assertEquals(List.of("Geometry", "Time"), labels(input.children()));

        NodeMenuTree.Node geometry = input.children().get(0);
        assertEquals("Input/Geometry", geometry.path());
        assertEquals(List.of("Normal", "Position"), labels(geometry.children()));
    }

    /**
     * <b>A type with no category is a root-level leaf, not a folder called "".</b>
     *
     * <p>This is what makes the tree degrade gracefully: a library that never bothered with categories
     * renders as exactly the flat list it had before, rather than as one unnamed folder containing
     * everything.</p>
     */
    @Test
    public void anUncategorisedTypeSitsAtTheRoot() {
        List<NodeMenuTree.Node> roots = NodeMenuTree.categorised(offers(
                type("Add", ""),
                type("Multiply", "")));

        assertEquals(List.of("Add", "Multiply"), labels(roots));
        assertFalse(roots.get(0).isCategory());
    }

    /** Blank segments are all one problem, and they are handled in one place. */
    @Test
    public void messyCategoryPathsDoNotProduceEmptyFolders() {
        List<NodeMenuTree.Node> roots = NodeMenuTree.categorised(offers(
                type("A", "Math//Basic"),
                type("B", "Math/Basic/"),
                type("C", "/Math/Basic")));

        assertEquals(List.of("Math"), labels(roots));
        assertEquals(List.of("Basic"), labels(roots.get(0).children()));
        assertEquals("all three land in the same folder", 3,
                roots.get(0).children().get(0).children().size());
    }

    @Test
    public void foldersSortBeforeLeavesAndBothAlphabetically() {
        List<NodeMenuTree.Node> roots = NodeMenuTree.categorised(offers(
                type("zebra", ""),
                type("apple", ""),
                type("Q", "Zzz"),
                type("R", "Aaa")));

        assertEquals(List.of("Aaa", "Zzz", "apple", "zebra"), labels(roots));
    }

    /** Ordering must not depend on the order the library happened to be registered in. */
    @Test
    public void theOrderIsIndependentOfRegistrationOrder() {
        List<String> forwards = labels(NodeMenuTree.categorised(offers(
                type("A", "M"), type("B", "M"), type("C", "M"))).get(0).children());
        List<String> backwards = labels(NodeMenuTree.categorised(offers(
                type("C", "M"), type("B", "M"), type("A", "M"))).get(0).children());

        assertEquals(forwards, backwards);
        assertEquals(List.of("A", "B", "C"), forwards);
    }

    // ── Searching ───────────────────────────────────────────────────────────

    /**
     * <b>A search result is flat.</b>
     *
     * <p>Ranked, not filed. Burying three matches under two levels of collapsed folder is exactly what
     * the user typed in order to avoid, which is why the menu switches shape on a non-blank query rather
     * than filtering the tree in place.</p>
     */
    @Test
    public void flatDropsEveryFolder() {
        List<NodeMenuTree.Node> flat = NodeMenuTree.flat(offers(
                type("Position", "Input/Geometry"),
                type("Add", "Math")));

        assertEquals(List.of("Add", "Position"), labels(flat));
        for (NodeMenuTree.Node node : flat) {
            assertFalse(node.isCategory());
            assertTrue(node.children().isEmpty());
        }
    }

    // ── Identity, which is what keeps folders open while you type ───────────

    /**
     * <b>Two nodes for the same path are equal, even across a full rebuild.</b>
     *
     * <p>{@code TreeView} keeps its expansion set keyed on the caller's {@code equals}, and its own notes
     * warn that a source handing out unequal objects for the same node "will appear to collapse on every
     * refresh". The menu rebuilds the entire tree per keystroke, so this is the property that stops every
     * folder snapping shut as you type — and a structural {@code equals} over the children would not have
     * it, since a folder's contents change under a filter.</p>
     */
    @Test
    public void identityIsThePathSoExpansionSurvivesARebuild() {
        NodeMenuTree.Node first = NodeMenuTree.categorised(offers(
                type("Add", "Math"), type("Step", "Math"))).get(0);
        // Same folder, different contents — what a keystroke produces.
        NodeMenuTree.Node afterFiltering = NodeMenuTree.categorised(offers(
                type("Add", "Math"))).get(0);

        assertEquals("Math", first.label());
        assertEquals(first, afterFiltering);
        assertEquals(first.hashCode(), afterFiltering.hashCode());
        assertNotEquals("but a different folder is a different node",
                first, NodeMenuTree.categorised(offers(type("X", "Other"))).get(0));
    }

    /** Nesting is part of identity — two folders both called "Basic" are not the same folder. */
    @Test
    public void sameNameAtDifferentDepthsAreDifferentNodes() {
        List<NodeMenuTree.Node> roots = NodeMenuTree.categorised(offers(
                type("A", "Math/Basic"),
                type("B", "Artistic/Basic")));

        NodeMenuTree.Node mathBasic = NodeMenuTree.find(roots, "Math/Basic");
        NodeMenuTree.Node artisticBasic = NodeMenuTree.find(roots, "Artistic/Basic");

        assertNotNull(mathBasic);
        assertNotNull(artisticBasic);
        assertEquals("Basic", mathBasic.label());
        assertEquals(mathBasic.label(), artisticBasic.label());
        assertNotEquals(mathBasic, artisticBasic);
    }

    // ── Counting, which drives the auto-expand rule ─────────────────────────

    @Test
    public void leafCountIgnoresFoldersAtEveryDepth() {
        List<NodeMenuTree.Node> roots = NodeMenuTree.categorised(offers(
                type("A", "Math/Basic"),
                type("B", "Math/Advanced"),
                type("C", "Input"),
                type("D", "")));

        assertEquals(4, NodeMenuTree.leafCount(roots));
        assertEquals("Math, Math/Basic, Math/Advanced, Input", 4, NodeMenuTree.categoriesIn(roots).size());
        assertEquals(4, NodeMenuTree.leavesIn(roots).size());
    }

    @Test
    public void anEmptyLibraryProducesAnEmptyTreeRatherThanAnEmptyFolder() {
        List<NodeMenuTree.Node> roots = NodeMenuTree.categorised(List.of());

        assertTrue(roots.isEmpty());
        assertEquals(0, NodeMenuTree.leafCount(roots));
        assertNull(NodeMenuTree.find(roots, "anything"));
    }

    /** An offer carrying a port keeps the port label, so the contextual menu still names what it will
     * connect to once the rows are filed under folders. */
    @Test
    public void portOffersKeepTheirLabelInsideACategory() {
        NodeType add = NodeType.of("shader.Add").label("Add").category("Math")
                .in("A", "vec3").in("B", "vec3").build();
        List<NodeTypeRegistry.Offer> portOffers = new ArrayList<>();
        for (var port : add.ports()) {
            if (port.direction().isInput()) portOffers.add(new NodeTypeRegistry.Offer(add, port));
        }

        List<NodeMenuTree.Node> roots = NodeMenuTree.categorised(portOffers);

        assertEquals(List.of("Add - A", "Add - B"), labels(roots.get(0).children()));
        assertNotNull(roots.get(0).children().get(0).offer());
        assertEquals("Math/Add - A", roots.get(0).children().get(0).path());
    }
}
