package com.crystalgui.ui.elements.workbench;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.SourceRoots;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ProjectIndex} — what the workspace declares, and what its text currently is.
 *
 * <h3>What is actually at stake</h3>
 *
 * <p>Three properties, and each fails silently on its own. The index must be <b>eventually complete</b>
 * (the crawl grows, and an index that indexed once is permanently short of whatever arrived after);
 * it must answer with the <b>live buffer</b> (a compiler resolving saved text reports errors about code
 * the author has already fixed); and it must <b>never block</b>, because it is asked from inside a
 * compile on the analysis thread.</p>
 */
public class ProjectIndexTest {

    private final List<CgPath> files = new ArrayList<>();
    private final Map<CgPath, String> onDisk = new LinkedHashMap<>();
    private final Map<CgPath, String> buffers = new HashMap<>();
    private final List<CgPath> readsRequested = new ArrayList<>();
    private final List<Runnable> pendingReads = new ArrayList<>();
    private int fills;

    /** The index under test, wired to this test's stand-ins for the crawl, the disk and the editors. */
    private final ProjectIndex index = new ProjectIndex(
            () -> files,
            id -> SourceRoots.CONVENTION,
            buffers::get,
            this::requestRead,
            () -> fills++);

    /** Reads are DEFERRED, as a round trip is — nothing lands until {@link #deliverReads}. */
    private void requestRead(CgPath path, Consumer<String> onText) {
        readsRequested.add(path);
        pendingReads.add(() -> onText.accept(onDisk.get(path)));
    }

    private void deliverReads() {
        List<Runnable> due = new ArrayList<>(pendingReads);
        pendingReads.clear();
        for (Runnable r : due) r.run();
    }

    private CgPath add(String path, String text) {
        CgPath cg = CgPath.parse(path);
        files.add(cg);
        onDisk.put(cg, text);
        return cg;
    }

    // ── Names ───────────────────────────────────────────────────────────────────────────────────

    /** <b>A file under a source root is declared by its path</b>, with no read at all. */
    @Test
    public void namesComeFromPathsAndCostNoIo() {
        add("p:src/main/java/com/example/Main.java", "package com.example; class Main {}");

        assertEquals(List.of("com.example.Main"), index.declaredTypes());
        assertTrue("indexing a name should not have read anything", readsRequested.isEmpty());
    }

    /** <b>A file outside every root is not declared.</b> A README is not a type. */
    @Test
    public void aFileOutsideARootIsNotDeclared() {
        add("p:README.md", "hello");
        add("p:src/Main.java", "class Main {}");

        assertTrue(index.declaredTypes().isEmpty());
    }

    /**
     * <b>The index notices files that arrive after it first answered.</b>
     *
     * <p>The crawl is asynchronous and grows a list, so anything that indexed once and never looked
     * again is permanently short of whatever landed later — and nothing tells it, which is why this is
     * pulled rather than pushed.</p>
     */
    @Test
    public void filesArrivingLaterAreStillIndexed() {
        add("p:src/main/java/A.java", "class A {}");
        assertEquals(List.of("A"), index.declaredTypes());

        add("p:src/main/java/B.java", "class B {}");
        assertEquals(List.of("A", "B"), index.declaredTypes());
    }

    /**
     * <b>...including a change that does not move the file count.</b>
     *
     * <p>A rename and a delete-plus-add both leave the count alone, so the cheap "has it grown" check
     * cannot see them. `invalidate` says so explicitly.</p>
     */
    @Test
    public void aChangeThatKeepsTheCountIsStillNoticed() {
        CgPath was = add("p:src/main/java/A.java", "class A {}");
        assertEquals(List.of("A"), index.declaredTypes());

        files.remove(was);
        add("p:src/main/java/B.java", "class B {}");
        index.invalidate(was);

        assertEquals(List.of("B"), index.declaredTypes());
    }

    // ── Packages ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Every ancestor package is declared, not only the one a file sits in.</b>
     *
     * <p>ECJ asks about each segment of a qualified name <em>before</em> it looks the type up, so a
     * project whose only file is {@code com/example/Main.java} must answer true for {@code com} — which
     * contains no types itself. Answering only for the leaf makes the type never resolve, and the error
     * is about the package rather than about anything the author wrote.</p>
     */
    @Test
    public void everyAncestorPackageIsDeclared() {
        add("p:src/main/java/com/example/deep/Main.java", "class Main {}");

        assertTrue(index.declaresPackage("com"));
        assertTrue(index.declaresPackage("com.example"));
        assertTrue(index.declaresPackage("com.example.deep"));
        assertFalse(index.declaresPackage("com.other"));
        assertFalse(index.declaresPackage(""));
    }

    // ── Text ────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>An open buffer wins over the file, and it is read live.</b>
     *
     * <p>The milestone's headline: a compiler resolving against saved text would report errors about code
     * the author has already fixed. Asserted after a second edit as well, because a version that
     * snapshotted the buffer at index time passes the first assertion.</p>
     */
    @Test
    public void anOpenBufferBeatsTheFileAndStaysLive() {
        CgPath path = add("p:src/main/java/Main.java", "class Main { int saved; }");
        buffers.put(path, "class Main { int edited; }");

        assertEquals("class Main { int edited; }", index.sourceOf("Main"));
        assertTrue("an open file should not have been read from disk", readsRequested.isEmpty());

        buffers.put(path, "class Main { int editedAgain; }");
        assertEquals("class Main { int editedAgain; }", index.sourceOf("Main"));
    }

    /**
     * <b>A closed file answers null and asks for itself, then answers.</b>
     *
     * <p>Null is "not yet" as much as "no" — the alternative is blocking the analysis thread on a round
     * trip, which stalls a keystroke on I/O.</p>
     */
    @Test
    public void aClosedFileIsFetchedInTheBackground() {
        add("p:src/main/java/Main.java", "class Main {}");

        assertNull("the first ask must not block", index.sourceOf("Main"));
        assertEquals(1, readsRequested.size());
        assertEquals(0, fills);

        deliverReads();
        assertEquals("nothing was told the read had landed", 1, fills);
        assertEquals("class Main {}", index.sourceOf("Main"));
    }

    /**
     * <b>A miss asks once, however often it is missed.</b>
     *
     * <p>Without the guard, every keystroke that fails to resolve a type issues another request for it:
     * the analysis re-runs on each edit, misses again because nothing has landed, and asks again. That is
     * a request storm generated by typing, and it grows with how slow the round trip is.</p>
     */
    @Test
    public void arepeatedMissDoesNotRepeatTheRequest() {
        add("p:src/main/java/Main.java", "class Main {}");

        for (int i = 0; i < 20; i++) index.sourceOf("Main");

        assertEquals("one read per name, not one per ask", 1, readsRequested.size());
    }

    /** <b>A name the project does not declare is null, and asks for nothing.</b> */
    @Test
    public void anUnknownNameAsksForNothing() {
        add("p:src/main/java/Main.java", "class Main {}");

        assertNull(index.sourceOf("com.nowhere.Absent"));
        assertTrue(readsRequested.isEmpty());
    }

    /** <b>Invalidating a file drops its cached text</b>, so the next ask re-reads it. */
    @Test
    public void invalidatingAFileDropsItsText() {
        CgPath path = add("p:src/main/java/Main.java", "class Main { int first; }");
        index.sourceOf("Main");
        deliverReads();
        assertEquals("class Main { int first; }", index.sourceOf("Main"));

        onDisk.put(path, "class Main { int second; }");
        index.invalidate(path);

        assertNull(index.sourceOf("Main"));
        deliverReads();
        assertEquals("class Main { int second; }", index.sourceOf("Main"));
    }
}
