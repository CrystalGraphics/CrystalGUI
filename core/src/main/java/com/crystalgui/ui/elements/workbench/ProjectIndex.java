package com.crystalgui.ui.elements.workbench;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.SourceRoots;
import com.crystalgui.text.lang.ProjectSources;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What the workspace itself declares, by qualified name — the project half of resolution.
 *
 * <h3>Names come from paths; text comes from wherever it is freshest</h3>
 *
 * <p>The names are free. A source root makes a path mean a package ({@link SourceRoots}), and the crawl
 * already has every path — so indexing what the project declares costs no I/O at all, which is what makes
 * it affordable to do on every listing. Reading each file to find its {@code package} line instead would
 * be one read per file across the whole workspace.</p>
 *
 * <p>The <b>text</b> is the expensive half and is answered in three tiers: an open editor's live buffer
 * first, then a cached read, then nothing — with a background read started so the next question can be
 * answered. Null is "not yet" as much as "no", which {@link ProjectSources} states as its contract.</p>
 *
 * <h3>Why it cannot simply read the file</h3>
 *
 * <p>Because {@code sourceOf} is called from inside a compile, on the analysis thread, and reading a file
 * goes over {@code WorkspaceClient} — which is a round trip and may be a genuine network one. Blocking
 * there would stall a keystroke on I/O. So a miss returns null and schedules the read, exactly as the
 * file tree lists a directory it has not seen yet and fills in when the answer lands.</p>
 *
 * <p>That also means the index is <b>eventually</b> right rather than immediately, and the analysis has to
 * be re-run when it fills. {@link #onFilled} is that signal.</p>
 */
final class ProjectIndex implements ProjectSources {

    /**
     * The derived name map and package set, published as ONE immutable value.
     *
     * <h3>Why a snapshot and not two mutable fields</h3>
     *
     * <p>These are built on the ANALYSIS thread, and a workbench with two Java files open is two analyses
     * — two threads, both calling {@link #ensureCurrent}. The previous shape rebuilt in place, opening
     * with {@code byName.clear(); packages.clear();}, so a thread arriving mid-rebuild read a set that had
     * been emptied and not yet refilled.</p>
     *
     * <p>It presents as <b>one file resolving and another not</b>, which reads as something specific to the
     * file that failed — the shape of its import, the package it is in — and it sticks, because the
     * loser also sees {@code stale} cleared and skips its own rebuild. Nothing throws: an empty package set
     * is a complete, well-formed answer meaning "the workspace declares nothing".</p>
     *
     * <p>Two threads racing now both derive and both publish; the values are identical, so the loser costs
     * one wasted derivation and nothing else. A reader takes the reference ONCE and sees a whole answer.</p>
     */
    private volatile Names names = Names.EMPTY;

    /**
     * One derivation of the workspace's names. Immutable, published by reference.
     *
     * @param byName    qualified name to the file declaring it, insertion-ordered so a collision between
     *                  two files claiming one name resolves the same way every time
     * @param packages  every package the project declares anything at or under, so
     *                  {@link #declaresPackage} is a lookup rather than a walk
     * @param builtFrom how many files this was derived from. @see #ensureCurrent
     */
    private record Names(Map<String, CgPath> byName, Set<String> packages, int builtFrom) {
        static final Names EMPTY = new Names(Collections.emptyMap(), Collections.emptySet(), -1);
    }

    /**
     * Cached text for a file nobody has open, keyed by path. Cleared per file by the watcher.
     *
     * <p><b>Concurrent, and that is not decoration.</b> It is written by whatever thread the workspace
     * client answers a read on and read from the analysis thread inside a compile. A plain map here can
     * be walked mid-write, and the resulting failure is <em>invisible</em>: the registry view catches a
     * {@code RuntimeException} from a provider so one broken provider cannot fail a whole compile, so a
     * concurrent-modification fault arrives as this index calmly answering "I do not have that" — the
     * exact shape of a type that does not exist.</p>
     */
    private final Map<CgPath, String> text = new java.util.concurrent.ConcurrentHashMap<>();

    /** Names a read is already in flight for, so a repeated miss does not repeat the request. */
    private final Set<String> reading = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** An open document's current text, or null when it is not open. Live, never snapshotted. */
    private final Function<CgPath, String> openBuffer;

    /** Starts a background read; the callback lands on whatever thread the client answers on. */
    private final ReadRequest read;

    /** Told when a background read has landed, so a stale analysis can be re-run. */
    private final Runnable onFilled;

    /** Where the file list comes from. Asked again whenever the index might be behind. */
    private final java.util.function.Supplier<List<CgPath>> files;

    /** Project id to its declared source roots. */
    private final Function<String, List<String>> sourceRootsOf;

    /** Set when something changed that a count cannot see — a rename, a delete. @see #markStale */
    private volatile boolean stale = true;

    ProjectIndex(java.util.function.Supplier<List<CgPath>> files,
                 Function<String, List<String>> sourceRootsOf,
                 Function<CgPath, String> openBuffer, ReadRequest read, Runnable onFilled) {
        this.files = files == null ? Collections::<CgPath>emptyList : files;
        this.sourceRootsOf = sourceRootsOf == null ? id -> SourceRoots.CONVENTION : sourceRootsOf;
        this.openBuffer = openBuffer == null ? path -> null : openBuffer;
        this.read = read == null ? (path, onText) -> { } : read;
        this.onFilled = onFilled == null ? () -> { } : onFilled;
    }

    /**
     * Rebuilds the name map if it might be behind, before answering anything.
     *
     * <h3>Pulled, not pushed — and that is the same argument the dock's tab presentation makes</h3>
     *
     * <p>The workspace crawl is asynchronous and grows a list; the project listing lands separately; a
     * watcher reports changes on its own schedule. Anything pushed into this index would have to be
     * pushed again by whoever noticed each of those, and the one that gets forgotten fails silently — the
     * index is simply short of a file, and nothing says so.</p>
     *
     * <p>The count catches a growing crawl for free. It cannot catch a rename or a delete, which keep the
     * count the same, so those set {@link #stale} explicitly.</p>
     */
    private void ensureCurrent() {
        List<CgPath> current = files.get();
        int size = current == null ? 0 : current.size();
        // ONE READ of the volatile, so the size check and the answer cannot describe two snapshots.
        if (!stale && size == names.builtFrom) return;
        // CLEARED BEFORE the derivation, never after: a change landing while this runs must leave the flag
        // set so the next ask re-derives. Clearing afterwards swallows it.
        stale = false;
        names = derive(current, sourceRootsOf, size);
    }

    /** Says the name map may be wrong in a way a file count cannot show. */
    void markStale() {
        stale = true;
    }

    /** How this index asks for a file it has not got. Deliberately not the whole client. */
    @FunctionalInterface
    interface ReadRequest {
        void read(CgPath path, Consumer<String> onText);
    }

    // ── Building ────────────────────────────────────────────────────────────────────────────────

    /**
     * Rebuilds the name map from the current file list.
     *
     * <p>Whole-map rather than incremental, because the input is whole: {@code knownFiles()} is a growing
     * list rather than a stream of changes, and reconciling one against a diff nobody produces is more
     * machinery than re-deriving a few thousand strings. <b>The text cache survives</b> — a file's
     * contents do not change because the crawl found a sibling, and dropping them would re-read the
     * workspace every time a directory listing landed.</p>
     */
    private static Names derive(List<CgPath> files, Function<String, List<String>> sourceRootsOf,
                                int builtFrom) {
        if (files == null || sourceRootsOf == null) {
            return new Names(Collections.emptyMap(), Collections.emptySet(), builtFrom);
        }
        Map<String, CgPath> byName = new LinkedHashMap<>();
        Set<String> packages = new TreeSet<>();
        for (CgPath file : files) {
            if (file == null) continue;
            SourceRoots.Located located = SourceRoots.locate(file, sourceRootsOf.apply(file.project()));
            if (located == null) continue;
            // FIRST DECLARATION WINS, matching the registry's rule for two projects. Re-deriving the map
            // means the order is the crawl's, which is stable for a given workspace.
            byName.putIfAbsent(located.qualifiedName(), file);
            addPackageChain(packages, located.packageName());
        }
        return new Names(byName, packages, builtFrom);
    }

    /**
     * Records a package and every ancestor of it.
     *
     * <p>The ancestors are the point. ECJ asks about each segment of a qualified name before it looks the
     * type up, so a project whose only file is {@code com/example/Main.java} must answer true for
     * {@code com} — which declares nothing itself — or {@code com.example.Main} never resolves at all.</p>
     */
    private static void addPackageChain(Set<String> packages, String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        int at = -1;
        while (true) {
            at = packageName.indexOf('.', at + 1);
            if (at < 0) break;
            packages.add(packageName.substring(0, at));
        }
        packages.add(packageName);
    }

    /** Forgets one file's cached text. The watcher's job, on any change to it. */
    void invalidate(@Nullable CgPath path) {
        if (path == null) return;
        text.remove(path);
        // AND THE NAMES, because a change can be a rename or a delete -- neither of which moves the file
        // count, so `ensureCurrent` would otherwise never look again.
        markStale();
    }

    /** Forgets everything cached, keeping the names. For a workspace-wide reload. */
    void invalidateAll() {
        text.clear();
        reading.clear();
    }

    // ── Answering ───────────────────────────────────────────────────────────────────────────────

    @Override
    @Nullable
    public String sourceOf(String qualifiedName) {
        if (qualifiedName == null) return null;
        ensureCurrent();
        CgPath path = names.byName.get(qualifiedName);
        if (path == null) return null;

        // THE BUFFER FIRST, always. Resolving against the saved file would report errors about text the
        // author has already fixed, in the one place they are looking.
        String live = openBuffer.apply(path);
        if (live != null) return live;

        String cached = text.get(path);
        if (cached != null) return cached;

        requestRead(qualifiedName, path);
        return null;
    }

    /**
     * Starts one read per name, at most.
     *
     * <p>Without the guard every keystroke that fails to resolve a type issues another request for it:
     * the analysis re-runs on each edit, misses again because nothing has landed yet, and asks again.
     * That is a request storm generated by typing, and it grows with how long the round trip takes.</p>
     */
    private void requestRead(String qualifiedName, CgPath path) {
        if (!reading.add(qualifiedName)) return;
        read.read(path, content -> {
            reading.remove(qualifiedName);
            if (content == null) return;
            text.put(path, content);
            onFilled.run();
        });
    }

    @Override
    public List<String> declaredTypes() {
        ensureCurrent();
        // The snapshot's own key set, not a copy: it is immutable once published and this is walked on
        // every keystroke that opens a completion popup.
        return new ArrayList<>(names.byName.keySet());
    }

    /**
     * What a file at this path must be called.
     *
     * <p>Computed rather than looked up, so it answers for a file the crawl has not reached yet — which
     * is the common case, since a document is usually opened before the walk gets to its directory. It
     * needs no {@link #ensureCurrent()} for the same reason: nothing here reads the name map.</p>
     */
    @Override
    @Nullable
    public String nameOf(String workspacePath) {
        if (workspacePath == null || workspacePath.isEmpty()) return null;
        CgPath path;
        try {
            path = CgPath.parse(workspacePath);
        } catch (RuntimeException notAPath) {
            return null;
        }
        SourceRoots.Located located = SourceRoots.locate(path, sourceRootsOf.apply(path.project()));
        return located == null ? null : located.qualifiedName();
    }

    @Override
    @Nullable
    public String pathOf(String qualifiedName) {
        if (qualifiedName == null) return null;
        ensureCurrent();
        CgPath path = names.byName.get(qualifiedName);
        // NO READ, and no scheduling of one: this answers from the crawl alone, which is what makes
        // go-to-definition able to name a file the editor has never opened.
        return path == null ? null : path.toString();
    }

    /**
     * The same three tiers, except that the read is WAITED for instead of scheduled.
     *
     * <p>Bounded, because the alternative to a slow answer must never be no answer at all: a workspace
     * that has gone away leaves the run stuttering for {@link #READ_TIMEOUT_MILLIS} and then failing on
     * the name, which is what would have happened immediately without this.</p>
     *
     * <p>An interrupt is honoured and re-raised. A run is stoppable, and a script waiting on a file is
     * exactly when somebody presses Stop — swallowing it would leave the thread unstoppable until the
     * bound expired. @see com.crystalgui.language.run.ScriptPolicy</p>
     */
    @Override
    @Nullable
    public String awaitSourceOf(String qualifiedName) {
        if (qualifiedName == null) return null;
        ensureCurrent();
        CgPath path = names.byName.get(qualifiedName);
        if (path == null) return null;

        String live = openBuffer.apply(path);
        if (live != null) return live;
        String cached = text.get(path);
        if (cached != null) return cached;

        java.util.concurrent.CountDownLatch landed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> answer =
                new java.util.concurrent.atomic.AtomicReference<>();
        // ITS OWN READ, deliberately not routed through `requestRead`: that one is guarded so a repeated
        // MISS does not repeat a request, and here a request already in flight would leave nothing to
        // wait on -- the guard would swallow the call and the latch would never count down.
        //
        // CACHED IN THE CALLBACK, not after the wait. An answer that arrives LATE is still the file's
        // text, and storing it only on the waiting side threw it away: the wait timed out, the content
        // landed a moment later with nowhere to go, and the next run asked all over again. That is what
        // made this fail identically however many times it was pressed, in flat contradiction of the note
        // above -- "running a second time makes it work" was the behaviour this method was written to
        // replace, and it had quietly lost even that.
        read.read(path, content -> {
            if (content != null) text.put(path, content);
            answer.set(content);
            landed.countDown();
        });
        // NEVER ON THE FRAME THREAD, because there the wait cannot succeed -- it can only stall.
        //
        // The workspace connection is pumped by the frame loop, so the callback above is delivered BY the
        // thread this would block. Waiting on it is a deadlock against its own answer, resolved only by
        // the timeout: a run that needed a file nobody had open froze the UI for the full bound and then
        // failed anyway. It looked like a compiler fault -- "Formatter cannot be resolved" -- and went
        // away the moment the file was opened in a tab, because then the buffer answers above and no read
        // is needed at all.
        //
        // The read is still ISSUED, so the text is cached by the callback and the next attempt has it.
        // @see com.crystalgui.core.async.UiThread
        // A TRANSPORT MAY ANSWER SYNCHRONOUSLY, and then there is nothing to wait for. An in-memory
        // workspace -- the harness, every test -- runs the callback inside `read` above, so the text is
        // already here; returning null because of the guard below would throw away an answer we hold.
        // This is also the difference between the two hosts: the harness resolves a cold file on the
        // first run and mc1710 cannot, because there the read is a round trip.
        String immediate = answer.get();
        if (immediate != null) return immediate;

        if (com.crystalgui.core.async.UiThread.isCurrent()) return null;
        try {
            if (!landed.await(READ_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return null;
        }
        String content = answer.get();
        if (content != null) text.put(path, content);
        return content;
    }

    /** Long enough for a real round trip, short enough that a mistake is a stutter. @see #awaitSourceOf */
    private static final long READ_TIMEOUT_MILLIS = 2_000L;

    @Override
    public boolean declaresPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        ensureCurrent();
        return names.packages.contains(packageName);
    }
}
