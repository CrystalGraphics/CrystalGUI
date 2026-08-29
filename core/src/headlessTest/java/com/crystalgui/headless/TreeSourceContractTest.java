package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.dom.ElementTreeSource;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.TreeObserver;
import com.crystalgui.ui.dom.TreeSource;
import org.junit.Test;

/**
 * <b>The seam's contract.</b> {@code plan_ui_rewrite.md} M0, and its stated acceptance criterion:
 * <em>"the seam has a headless test suite that the old engine passes and the new one (M5) must pass
 * unchanged."</em>
 *
 * <h3>How to use this file at M5</h3>
 *
 * <p>Everything below is written against {@link TreeSource} and {@link TreeObserver}. The only thing
 * that knows what a node <em>is</em> is {@link #sourceOver}, which builds an {@link ElementTreeSource}
 * over a {@code UIElement}. When {@code ui.dom}'s node tree lands, point that one method at the new
 * implementation and <b>change nothing else</b>. A test that needed editing would be a behaviour the
 * new engine got away with changing.</p>
 *
 * <p>That is also why nothing here asserts on {@code UIElement} internals — no child lists, no
 * internal-child flags, no {@code networkId}. Those are the old engine's spelling of answers the seam
 * states in its own terms.</p>
 *
 * <p>Headless on purpose: no CrystalGraphics, no fonts, no GL, no {@code UIWindow}. The seam is about
 * identity and structure, and a dedicated server has both. Thread ownership is deliberately NOT here --
 * it is a property of a tree being painted, so it needs a window, and it lives in
 * {@code FrameThreadOwnershipTest} beside the other tests that have one.</p>
 */
public class TreeSourceContractTest {

    /** <b>The one seam-specific line in the file.</b> Repoint this at M5. */
    private static TreeSource<UIElement> sourceOver(UIElement root) {
        return new ElementTreeSource(root);
    }

    private static UIElement node() {
        return new UIElement();
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    @Test
    public void anIdIsStableForTheLifeOfTheSource() {
        UIElement root = node();
        UIElement child = node();
        root.addChild(child);

        TreeSource<UIElement> source = sourceOver(root);
        int first = source.idOf(child);

        assertEquals("asking twice must not allocate twice", first, source.idOf(child));
        assertSame("and the reverse lookup must agree", child, source.byId(first));
    }

    @Test
    public void distinctNodesGetDistinctIds() {
        UIElement root = node();
        UIElement a = node();
        UIElement b = node();
        root.addChildren(a, b);

        TreeSource<UIElement> source = sourceOver(root);
        assertNotEquals(source.idOf(a), source.idOf(b));
        assertNotEquals(source.idOf(root), source.idOf(a));
    }

    /**
     * <b>The defect the whole rewrite came out of.</b> Under the positional scheme this is exactly what
     * broke: the number was the position, so inserting a sibling in front renumbered everything after
     * it and the far side started addressing the wrong elements.
     */
    @Test
    public void anIdSurvivesASiblingBeingInsertedBeforeIt() {
        UIElement root = node();
        UIElement existing = node();
        root.addChild(existing);

        TreeSource<UIElement> source = sourceOver(root);
        int before = source.idOf(existing);

        root.addChildAt(node(), 0);

        assertEquals("inserting a sibling must not renumber anything", before, source.idOf(existing));
        assertSame(existing, source.byId(before));
    }

    /**
     * The tear-out case. A client reparenting a subtree — into a window frame, a dock, a tab — must not
     * change what the server addresses it by, because presentation is the client's business and
     * identity is not.
     */
    @Test
    public void anIdSurvivesAReparent() {
        UIElement root = node();
        UIElement from = node();
        UIElement to = node();
        UIElement moving = node();
        root.addChildren(from, to);
        from.addChild(moving);

        TreeSource<UIElement> source = sourceOver(root);
        int before = source.idOf(moving);

        to.addChild(moving);

        assertEquals("a reparent is presentation, not identity", before, source.idOf(moving));
        assertSame(moving, source.byId(before));
    }

    @Test
    public void anIdSurvivesBeingDetached() {
        // Hide is detach in this engine, so an id that died with the parent link would mean a hidden
        // window came back as a stranger.
        UIElement root = node();
        UIElement child = node();
        root.addChild(child);

        TreeSource<UIElement> source = sourceOver(root);
        int before = source.idOf(child);

        root.removeChild(child);

        assertEquals(before, source.idOf(child));
    }

    @Test
    public void peekAllocatesNothing() {
        UIElement root = node();
        UIElement child = node();
        root.addChild(child);
        TreeSource<UIElement> source = sourceOver(root);

        assertEquals(TreeSource.NO_ID, source.peekId(child));
        assertNull(source.byId(0));

        source.idOf(child);
        assertNotEquals(TreeSource.NO_ID, source.peekId(child));
    }

    @Test
    public void twoSourcesOverOneTreeDoNotFightOverTheNumbering() {
        // The old scheme could not do this at all: the id was one field on the element, so a second
        // session overwrote the first. It is why `setObserver holds ONE observer` had to be documented.
        UIElement root = node();
        UIElement a = node();
        UIElement b = node();
        root.addChildren(a, b);

        TreeSource<UIElement> first = sourceOver(root);
        TreeSource<UIElement> second = sourceOver(root);

        int aInFirst = first.idOf(a);
        second.idOf(b);
        second.idOf(a);

        assertEquals("the first source's numbering is its own", aInFirst, first.idOf(a));
        assertSame(a, first.byId(aInFirst));
    }

    // ── Structure ────────────────────────────────────────────────────────────

    @Test
    public void theLightTreeIsWhatAPeerIsToldAbout() {
        UIElement root = node();
        UIElement content = node();
        UIElement scaffolding = node();
        root.addChild(content);
        root.addInternalChild(scaffolding);

        TreeSource<UIElement> source = sourceOver(root);
        List<UIElement> children = source.childrenOf(root);

        assertEquals("a composite's own scaffolding is rebuilt on the far side, not described",
                1, children.size());
        assertSame(content, children.get(0));
    }

    @Test
    public void parentAnswersNullAtTheRoot() {
        UIElement root = node();
        UIElement child = node();
        root.addChild(child);
        TreeSource<UIElement> source = sourceOver(root);

        assertNull("the root of the OBSERVED tree, whatever is above it", source.parentOf(root));
        assertSame(root, source.parentOf(child));
        assertSame(root, source.root());
    }

    @Test
    public void containsIsAboutTheObservedTree() {
        UIElement root = node();
        UIElement child = node();
        root.addChild(child);
        UIElement stranger = node();

        TreeSource<UIElement> source = sourceOver(root);
        assertTrue(source.contains(child));
        assertTrue(source.contains(root));
        assertFalse(source.contains(stranger));

        root.removeChild(child);
        assertFalse("and it stops being true when the node leaves", source.contains(child));
    }

    // ── Contract ─────────────────────────────────────────────────────────────

    @Test
    public void aContractNamesTheKind() {
        UIElement root = node();
        TreeSource<UIElement> source = sourceOver(root);

        NodeContract contract = source.contractOf(root);
        assertEquals("element", contract.name());
        assertFalse(contract.reportsAnything());
    }

    /**
     * <b>Changed at M1, and the change is the feature.</b> This used to call
     * {@code addReportedEvent("activate")} on a bare {@code UIElement} — which now throws, because a
     * plain element has no contract and therefore nothing it can report. Before contracts a session
     * could ask any element for any string: the request was recorded, written into the description, and
     * the client's wiring hit a {@code default} arm that logged and carried on. A widget that reports
     * what it is capable of is the point.
     */
    @Test
    public void aContractCarriesWhatTheNodeReports() {
        UIElement root = node();
        Button reporting = new Button("go");
        root.addChild(reporting);

        TreeSource<UIElement> source = sourceOver(root);

        assertTrue("a Button declares that it can be activated",
                source.contractOf(reporting).eventKinds().contains("activate"));
        assertFalse("...and a plain container declares nothing",
                source.contractOf(root).reportsAnything());
    }

    @Test
    public void anElementCannotBeAskedToReportWhatItCannotObserve() {
        UIElement plain = node();
        try {
            plain.addReportedEvent("activate");
            fail("a plain UIElement has no contract, so there is no way for it to report anything -- "
                    + "this used to be recorded, described, and silently dropped by the client");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("WidgetContract"));
        }

        Button button = new Button("go");
        button.addReportedEvent("activate");           // declared, so accepted
        try {
            button.addReportedEvent("wheel");
            fail("a Button declares no wheel event and must refuse to be asked for one");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("cannot report"));
        }
    }

    // ── Observation ──────────────────────────────────────────────────────────

    /** Records the edit script as text, so an assertion reads as the sequence it is describing. */
    private static final class Script implements TreeObserver<UIElement> {
        final List<String> lines = new ArrayList<>();
        private final TreeSource<UIElement> source;

        Script(TreeSource<UIElement> source) {
            this.source = source;
        }

        private String name(UIElement node) {
            return node.getId().isEmpty() ? "?" : node.getId();
        }

        @Override public void inserted(UIElement n, UIElement p, int i) {
            lines.add("inserted " + name(n) + " into " + name(p) + " at " + i);
        }
        @Override public void removed(UIElement n, UIElement p) {
            lines.add("removed " + name(n) + " from " + name(p));
        }
        @Override public void moved(UIElement n, UIElement p, int i) {
            lines.add("moved " + name(n) + " to " + name(p) + " at " + i);
        }
        @Override public void attributeChanged(UIElement n) { lines.add("attribute " + name(n)); }
        @Override public void inlineStyleChanged(UIElement n) { lines.add("inlineStyle " + name(n)); }
        @Override public void stateChanged(UIElement n) { lines.add("state " + name(n)); }
    }

    private static UIElement named(String id) {
        UIElement element = new UIElement();
        element.setId(id);
        return element;
    }

    @Test
    public void installingAnObserverReportsNothing() {
        // The old setObserver emitted an attach per element, so every consumer had to remember to
        // discard its own installation before it could tell a real insertion from being handed the
        // tree. An edit script describes CHANGES; being given a tree is not one.
        UIElement root = named("root");
        root.addChild(named("a"));
        root.addChild(named("b"));

        TreeSource<UIElement> source = sourceOver(root);
        Script script = new Script(source);
        source.observe(script);

        assertTrue("installing must be silent, was " + script.lines, script.lines.isEmpty());
    }

    @Test
    public void anInsertionCarriesItsIndex() {
        UIElement root = named("root");
        root.addChild(named("first"));
        TreeSource<UIElement> source = sourceOver(root);
        Script script = new Script(source);
        source.observe(script);

        root.addChildAt(named("second"), 0);

        assertEquals(List.of("inserted second into root at 0"), script.lines);
    }

    @Test
    public void aGraftedSubtreeIsReportedParentsFirst() {
        UIElement root = named("root");
        TreeSource<UIElement> source = sourceOver(root);
        Script script = new Script(source);
        source.observe(script);

        UIElement branch = named("branch");
        branch.addChild(named("leaf"));
        root.addChild(branch);

        assertEquals("a receiver must be able to place each node against a parent it has heard of",
                List.of("inserted branch into root at 0", "inserted leaf into branch at 0"),
                script.lines);
    }

    @Test
    public void aRemovalIsReportedBeforeTheParentLinkIsCleared() {
        UIElement root = named("root");
        UIElement child = named("child");
        root.addChild(child);
        TreeSource<UIElement> source = sourceOver(root);
        Script script = new Script(source);
        source.observe(script);

        root.removeChild(child);

        assertEquals("the parent has to be nameable, or the change cannot be anchored",
                List.of("removed child from root"), script.lines);
    }

    @Test
    public void aRemovalNamesOnlyTheSubtreeRoot() {
        UIElement root = named("root");
        UIElement branch = named("branch");
        branch.addChild(named("leaf"));
        root.addChild(branch);

        TreeSource<UIElement> source = sourceOver(root);
        Script script = new Script(source);
        source.observe(script);

        root.removeChild(branch);

        assertEquals("removing a node removes what is under it; a message per descendant is waste",
                List.of("removed branch from root"), script.lines);
    }

    /**
     * <b>The distinction the old observer could not draw</b>, and the reason stable identity had to come
     * first. Told "destroyed, and here is an identical one", a receiver rebuilds the subtree and loses
     * the instance, its scroll position and anything half-typed in it.
     */
    @Test
    public void aReparentIsAMoveAndNotADestroyAndRebuild() {
        UIElement root = named("root");
        UIElement from = named("from");
        UIElement to = named("to");
        UIElement moving = named("moving");
        root.addChildren(from, to);
        from.addChild(moving);

        TreeSource<UIElement> source = sourceOver(root);
        Script script = new Script(source);
        source.observe(script);

        to.addChild(moving);

        assertEquals(List.of("moved moving to to at 0"), script.lines);
    }

    @Test
    public void aNodeArrivingFromOutsideTheTreeIsAnInsertionNotAMove() {
        // The counter-assertion: an element that had a parent, but not one this source was watching,
        // is genuinely new to the receiver. A `moved` here would name a node it has never heard of.
        UIElement root = named("root");
        UIElement elsewhere = named("elsewhere");
        UIElement arriving = named("arriving");
        elsewhere.addChild(arriving);

        TreeSource<UIElement> source = sourceOver(root);
        Script script = new Script(source);
        source.observe(script);

        root.addChild(arriving);

        assertEquals(List.of("inserted arriving into root at 0"), script.lines);
    }

    @Test
    public void attributesAndInlineStyleAreSeparateSignals() {
        // They were one flag, which then carried neither: `onIdentityDirty` was collected and never
        // flushed, so disabling a button after the window opened did nothing on the far side.
        UIElement root = named("root");
        TreeSource<UIElement> source = sourceOver(root);
        Script script = new Script(source);
        source.observe(script);

        root.addClass("primary");

        assertEquals(List.of("attribute root"), script.lines);
    }

    @Test
    public void observingNullStopsTheStream() {
        UIElement root = named("root");
        TreeSource<UIElement> source = sourceOver(root);
        Script script = new Script(source);
        source.observe(script);
        source.observe(null);

        root.addChild(named("child"));

        assertTrue("was " + script.lines, script.lines.isEmpty());
        assertNull(source.observer());
    }

    @Test
    public void closingReleasesEverything() {
        UIElement root = named("root");
        UIElement child = named("child");
        root.addChild(child);
        TreeSource<UIElement> source = sourceOver(root);
        Script script = new Script(source);
        source.observe(script);
        source.idOf(child);

        source.close();

        root.addChild(named("later"));
        assertTrue("a closed source reports nothing, was " + script.lines, script.lines.isEmpty());
        assertNull(source.byId(0));
    }

}
