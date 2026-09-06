package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.TreeObserver;
import com.crystalgui.ui.dom.TreeSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The seam's acceptance suite, written once and run over <b>every engine</b>.
 *
 * <p>These assertions are the M0 suite's, unchanged — what changed at 5.1 is that the tree they drive
 * is reached through a {@link Fixture} rather than through {@code UIElement}'s own methods, so the
 * same file runs over the old engine ({@code TreeSourceContractTest}) and the new node tree
 * ({@code NodeTreeSourceContractTest}). The M0 plan said "repoint {@code sourceOver} and change
 * nothing else"; the honest version is "repoint the fixture and change nothing else", because the
 * old suite constructed elements directly. The two tests that need a WIDGET — a contract that reports
 * something, an element refusing to report what it cannot — stay in the old engine's subclass until
 * M6 gives the new tree a widget to ask.</p>
 *
 * @param <N> the node type
 */
public abstract class TreeSourceContract<N> {

    /** What the suite needs from an engine: nodes, the four structural operations, and a source. */
    public interface Fixture<N> {
        N node();

        N named(String id);

        /** Appends; for a child that already has a parent this is a reparent. */
        void add(N parent, N child);

        void addAt(N parent, N child, int index);

        void remove(N parent, N child);

        /** Content the far side rebuilds rather than being told about: an internal child, shadow content. */
        void addScaffolding(N parent, N child);

        void addClass(N node, String className);

        String idOf(N node);

        TreeSource<N> sourceOver(N root);

        /** What a plain container's contract calls itself. */
        String plainKindName();
    }

    protected abstract Fixture<N> fixture();

    private Fixture<N> f;

    private N node() {
        return f().node();
    }

    private Fixture<N> f() {
        if (f == null) f = fixture();
        return f;
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    @Test
    public void anIdIsStableForTheLifeOfTheSource() {
        N root = node();
        N child = node();
        f().add(root, child);
        TreeSource<N> source = f().sourceOver(root);
        int first = source.idOf(child);
        assertEquals("asking twice must not allocate twice", first, source.idOf(child));
        assertSame("and the reverse lookup must agree", child, source.byId(first));
    }

    @Test
    public void distinctNodesGetDistinctIds() {
        N root = node();
        N a = node();
        N b = node();
        f().add(root, a);
        f().add(root, b);
        TreeSource<N> source = f().sourceOver(root);
        assertNotEquals(source.idOf(a), source.idOf(b));
        assertNotEquals(source.idOf(root), source.idOf(a));
    }

    @Test
    public void anIdSurvivesASiblingBeingInsertedBeforeIt() {
        N root = node();
        N existing = node();
        f().add(root, existing);
        TreeSource<N> source = f().sourceOver(root);
        int before = source.idOf(existing);
        f().addAt(root, node(), 0);
        assertEquals("inserting a sibling must not renumber anything", before, source.idOf(existing));
        assertSame(existing, source.byId(before));
    }

    @Test
    public void anIdSurvivesAReparent() {
        N root = node();
        N from = node();
        N to = node();
        N moving = node();
        f().add(root, from);
        f().add(root, to);
        f().add(from, moving);
        TreeSource<N> source = f().sourceOver(root);
        int before = source.idOf(moving);
        f().add(to, moving);
        assertEquals("a reparent is presentation, not identity", before, source.idOf(moving));
        assertSame(moving, source.byId(before));
    }

    @Test
    public void anIdSurvivesBeingDetached() {
        N root = node();
        N child = node();
        f().add(root, child);
        TreeSource<N> source = f().sourceOver(root);
        int before = source.idOf(child);
        f().remove(root, child);
        assertEquals(before, source.idOf(child));
    }

    @Test
    public void peekAllocatesNothing() {
        N root = node();
        N child = node();
        f().add(root, child);
        TreeSource<N> source = f().sourceOver(root);
        assertEquals(TreeSource.NO_ID, source.peekId(child));
        assertNull(source.byId(0));
        source.idOf(child);
        assertNotEquals(TreeSource.NO_ID, source.peekId(child));
    }

    @Test
    public void twoSourcesOverOneTreeDoNotFightOverTheNumbering() {
        N root = node();
        N a = node();
        N b = node();
        f().add(root, a);
        f().add(root, b);
        TreeSource<N> first = f().sourceOver(root);
        TreeSource<N> second = f().sourceOver(root);
        int aInFirst = first.idOf(a);
        second.idOf(b);
        second.idOf(a);
        assertEquals("the first source's numbering is its own", aInFirst, first.idOf(a));
        assertSame(a, first.byId(aInFirst));
    }

    // ── Shape ────────────────────────────────────────────────────────────────

    @Test
    public void theLightTreeIsWhatAPeerIsToldAbout() {
        N root = node();
        N content = node();
        N scaffolding = node();
        f().add(root, content);
        f().addScaffolding(root, scaffolding);
        TreeSource<N> source = f().sourceOver(root);
        List<N> children = source.childrenOf(root);
        assertEquals("a composite's own scaffolding is rebuilt on the far side, not described",
                1, children.size());
        assertSame(content, children.get(0));
    }

    @Test
    public void parentAnswersNullAtTheRoot() {
        N root = node();
        N child = node();
        f().add(root, child);
        TreeSource<N> source = f().sourceOver(root);
        assertNull("the root of the OBSERVED tree, whatever is above it", source.parentOf(root));
        assertSame(root, source.parentOf(child));
        assertSame(root, source.root());
    }

    @Test
    public void containsIsAboutTheObservedTree() {
        N root = node();
        N child = node();
        f().add(root, child);
        N stranger = node();
        TreeSource<N> source = f().sourceOver(root);
        assertTrue(source.contains(child));
        assertTrue(source.contains(root));
        assertFalse(source.contains(stranger));
        f().remove(root, child);
        assertFalse("and it stops being true when the node leaves", source.contains(child));
    }

    @Test
    public void aContractNamesTheKind() {
        N root = node();
        TreeSource<N> source = f().sourceOver(root);
        NodeContract contract = source.contractOf(root);
        assertEquals(f().plainKindName(), contract.name());
        assertFalse(contract.reportsAnything());
    }

    // ── The edit script ──────────────────────────────────────────────────────

    /** Records the observer's stream as text, so a whole script can be asserted in one line. */
    protected final class Script implements TreeObserver<N> {
        public final List<String> lines = new ArrayList<>();

        private String name(N node) {
            String id = f().idOf(node);
            return id.isEmpty() ? "?" : id;
        }

        @Override public void inserted(N n, N p, int i) {
            lines.add("inserted " + name(n) + " into " + name(p) + " at " + i);
        }

        @Override public void removed(N n, N p) {
            lines.add("removed " + name(n) + " from " + name(p));
        }

        @Override public void moved(N n, N p, int i) {
            lines.add("moved " + name(n) + " to " + name(p) + " at " + i);
        }

        @Override public void attributeChanged(N n) { lines.add("attribute " + name(n)); }

        @Override public void inlineStyleChanged(N n) { lines.add("inlineStyle " + name(n)); }

        @Override public void stateChanged(N n) { lines.add("state " + name(n)); }
    }

    private N named(String id) {
        return f().named(id);
    }

    @Test
    public void installingAnObserverReportsNothing() {
        N root = named("root");
        f().add(root, named("a"));
        f().add(root, named("b"));
        TreeSource<N> source = f().sourceOver(root);
        Script script = new Script();
        source.observe(script);
        assertTrue("installing must be silent, was " + script.lines, script.lines.isEmpty());
    }

    @Test
    public void anInsertionCarriesItsIndex() {
        N root = named("root");
        f().add(root, named("first"));
        TreeSource<N> source = f().sourceOver(root);
        Script script = new Script();
        source.observe(script);
        f().addAt(root, named("second"), 0);
        assertEquals(List.of("inserted second into root at 0"), script.lines);
    }

    @Test
    public void aGraftedSubtreeIsReportedParentsFirst() {
        N root = named("root");
        TreeSource<N> source = f().sourceOver(root);
        Script script = new Script();
        source.observe(script);
        N branch = named("branch");
        f().add(branch, named("leaf"));
        f().add(root, branch);
        assertEquals("a receiver must be able to place each node against a parent it has heard of",
                List.of("inserted branch into root at 0", "inserted leaf into branch at 0"),
                script.lines);
    }

    @Test
    public void aRemovalIsReportedBeforeTheParentLinkIsCleared() {
        N root = named("root");
        N child = named("child");
        f().add(root, child);
        TreeSource<N> source = f().sourceOver(root);
        Script script = new Script();
        source.observe(script);
        f().remove(root, child);
        assertEquals("the parent has to be nameable, or the change cannot be anchored",
                List.of("removed child from root"), script.lines);
    }

    @Test
    public void aRemovalNamesOnlyTheSubtreeRoot() {
        N root = named("root");
        N branch = named("branch");
        f().add(branch, named("leaf"));
        f().add(root, branch);
        TreeSource<N> source = f().sourceOver(root);
        Script script = new Script();
        source.observe(script);
        f().remove(root, branch);
        assertEquals("removing a node removes what is under it; a message per descendant is waste",
                List.of("removed branch from root"), script.lines);
    }

    @Test
    public void aReparentIsAMoveAndNotADestroyAndRebuild() {
        N root = named("root");
        N from = named("from");
        N to = named("to");
        N moving = named("moving");
        f().add(root, from);
        f().add(root, to);
        f().add(from, moving);
        TreeSource<N> source = f().sourceOver(root);
        Script script = new Script();
        source.observe(script);
        f().add(to, moving);
        assertEquals(List.of("moved moving to to at 0"), script.lines);
    }

    @Test
    public void aNodeArrivingFromOutsideTheTreeIsAnInsertionNotAMove() {
        N root = named("root");
        N elsewhere = named("elsewhere");
        N arriving = named("arriving");
        f().add(elsewhere, arriving);
        TreeSource<N> source = f().sourceOver(root);
        Script script = new Script();
        source.observe(script);
        f().add(root, arriving);
        assertEquals(List.of("inserted arriving into root at 0"), script.lines);
    }

    @Test
    public void attributesAndInlineStyleAreSeparateSignals() {
        N root = named("root");
        TreeSource<N> source = f().sourceOver(root);
        Script script = new Script();
        source.observe(script);
        f().addClass(root, "primary");
        assertEquals(List.of("attribute root"), script.lines);
    }

    @Test
    public void observingNullStopsTheStream() {
        N root = named("root");
        TreeSource<N> source = f().sourceOver(root);
        Script script = new Script();
        source.observe(script);
        source.observe(null);
        f().add(root, named("child"));
        assertTrue("was " + script.lines, script.lines.isEmpty());
        assertNull(source.observer());
    }

    @Test
    public void closingReleasesEverything() {
        N root = named("root");
        N child = named("child");
        f().add(root, child);
        TreeSource<N> source = f().sourceOver(root);
        Script script = new Script();
        source.observe(script);
        source.idOf(child);
        source.close();
        f().add(root, named("later"));
        assertTrue("a closed source reports nothing, was " + script.lines, script.lines.isEmpty());
        assertNull(source.byId(0));
    }
}
