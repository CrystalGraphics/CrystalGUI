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

    /** Qualified name to the file declaring it. Insertion-ordered so a collision resolves stably. */
    private final Map<String, CgPath> byName = new LinkedHashMap<>();

    /** Every package this project declares anything at or under, so {@link #declaresPackage} is a lookup. */
    private final Set<String> packages = new TreeSet<>();

    /** Cached text for a file nobody has open, keyed by path. Cleared per file by the watcher. */
    private final Map<CgPath, String> text = new LinkedHashMap<>();

    /** Names a read is already in flight for, so a repeated miss does not repeat the request. */
    private final Set<String> reading = new TreeSet<>();

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

    /** How many files the last rebuild saw, so a grown crawl is noticed without anything telling us. */
    private int builtFrom = -1;

    /** Set when something changed that a count cannot see — a rename, a delete. @see #markStale */
    private boolean stale = true;

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
        if (!stale && size == builtFrom) return;
        rebuild(current, sourceRootsOf);
        builtFrom = size;
        stale = false;
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
    private void rebuild(List<CgPath> files, Function<String, List<String>> sourceRootsOf) {
        byName.clear();
        packages.clear();
        if (files == null || sourceRootsOf == null) return;

        for (CgPath file : files) {
            if (file == null) continue;
            SourceRoots.Located located = SourceRoots.locate(file, sourceRootsOf.apply(file.project()));
            if (located == null) continue;
            // FIRST DECLARATION WINS, matching the registry's rule for two projects. Re-deriving the map
            // means the order is the crawl's, which is stable for a given workspace.
            byName.putIfAbsent(located.qualifiedName(), file);
            addPackageChain(located.packageName());
        }
    }

    /**
     * Records a package and every ancestor of it.
     *
     * <p>The ancestors are the point. ECJ asks about each segment of a qualified name before it looks the
     * type up, so a project whose only file is {@code com/example/Main.java} must answer true for
     * {@code com} — which declares nothing itself — or {@code com.example.Main} never resolves at all.</p>
     */
    private void addPackageChain(String packageName) {
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

    /** What the index currently knows, for tests and for a picker. */
    List<String> declaredNames() {
        ensureCurrent();
        return new ArrayList<>(byName.keySet());
    }

    // ── Answering ───────────────────────────────────────────────────────────────────────────────

    @Override
    @Nullable
    public String sourceOf(String qualifiedName) {
        if (qualifiedName == null) return null;
        ensureCurrent();
        CgPath path = byName.get(qualifiedName);
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
    public boolean declaresPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        ensureCurrent();
        return packages.contains(packageName);
    }

    @Override
    public List<String> typesIn(String packageName) {
        if (packageName == null) return Collections.emptyList();
        ensureCurrent();
        List<String> out = new ArrayList<>();
        String prefix = packageName.isEmpty() ? "" : packageName + ".";
        for (String name : byName.keySet()) {
            if (!name.startsWith(prefix)) continue;
            String rest = name.substring(prefix.length());
            // DIRECTLY IN, not under: `com.example.nested.Thing` is not a type in `com.example`.
            if (rest.isEmpty() || rest.indexOf('.') >= 0) continue;
            out.add(rest);
        }
        return out;
    }
}
