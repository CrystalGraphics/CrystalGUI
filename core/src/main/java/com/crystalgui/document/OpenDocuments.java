package com.crystalgui.document;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.Markers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * Every open file, and what the workbench knows about each one.
 *
 * <h3>Why this is a class and not four fields on {@code Workbench}</h3>
 *
 * <p>It was four fields, briefly: the documents, the bytes last read from disk, which paths had been
 * requested, and which had refused to load — four maps keyed by the same {@code CgPath}, which every close
 * and every rename had to update together. That synchronisation was already written out by hand in two
 * places before it was obvious what it was: parallel maps are one object pulled apart, and the bug they
 * produce is the one this codebase keeps paying for — a second copy that drifts. A rename that moved three
 * of the four leaves a file reporting itself modified against a baseline filed under its old name.</p>
 *
 * <p>So there is one entry per path and one map. {@link #close} and {@link #retarget} touch it once each,
 * and there is no fourth thing to forget.</p>
 *
 * <h3>Dirtiness lives here rather than on {@link FileDocument}</h3>
 *
 * <p>It started on the document, which forced every kind to carry its own copy of the on-disk bytes and
 * its own comparison — a field and two methods duplicated per kind, and an abstract base could not absorb
 * it because a graph editor is a widget and already extends {@code UIElement}. It belongs on this side
 * anyway: "differs from disk" is a question about the disk, and the document only ever knew its own
 * content.</p>
 */
public final class OpenDocuments {

    /** One open file. Mutable, deliberately: it is the single owner of everything per-path. */
    private static final class Entry {
        final FileDocument document;

        /** What is on disk. Null until a read has actually landed — which is not the same as empty. */
        @Nullable
        byte[] onDisk;

        /** True once {@link FileDocument#adopt} refused the bytes. Such a file is shown, never written. */
        boolean unreadable;

        /** True once a read has been asked for, so a dock rebuild does not re-read over unsaved work. */
        boolean requested;

        /** This document's change subscription, dropped when it closes. */
        @Nullable
        Connection changes;

        Entry(FileDocument document) {
            this.document = document;
        }
    }

    private final Map<CgPath, Entry> byPath = new HashMap<>();

    /**
     * Where a document's problems are indexed for the workspace, or null when nobody is indexing.
     *
     * <p>Handed in rather than reached for, because the index belongs to one workspace — see
     * {@link Markers}. A process-wide one holds a listener on every set it has ever seen and therefore
     * never lets a document go.</p>
     */
    @Nullable
    private Markers markers;

    public void indexInto(@Nullable Markers into) {
        this.markers = into;
        if (into == null) return;
        for (Entry entry : byPath.values()) index(entry);
    }

    private void index(Entry entry) {
        if (markers == null) return;
        DiagnosticSet problems = entry.document.diagnostics();
        Resource resource = entry.document.resource();
        if (problems != null && resource != null) markers.attach(resource, problems);
    }

    /**
     * The document for a path, built on first use.
     *
     * <p>A newly built document is subscribed to immediately, so {@link #onDidChangeDirty} can announce
     * it. Doing it here rather than at each call site is the point of the entry existing at all: there is
     * one place a document comes into existence, and therefore one place its subscription can be paired
     * with its {@link #close}.</p>
     */
    public FileDocument documentFor(CgPath path, Function<CgPath, FileDocument> factory) {
        Entry entry = byPath.get(path);
        if (entry != null) return entry.document;
        entry = new Entry(factory.apply(path));
        byPath.put(path, entry);
        entry.changes = entry.document.onDidChange(() -> onDidChangeDirty.emit(path));
        // INDEXED WHILE OPEN. This is the one place a document enters, so it is the one place that can
        // answer "how many problems are there in the workspace" -- a question no per-document set can.
        index(entry);
        return entry.document;
    }

    /**
     * A document's content changed, so whether it is dirty may have.
     *
     * <h3>What it replaced</h3>
     *
     * <p>{@code Workbench.refreshDirtyMarkers} ran every frame, and "is anything unsaved" means
     * {@code encode()} on <b>every open document</b> compared against the bytes last read — so an open
     * shader graph was serialised sixty times a second to keep a tab marker up to date.</p>
     *
     * <p>Carries the path rather than the dirty set. The set is what the poll computed; a path is what
     * the change <em>is</em>, and a listener wanting the set can ask {@code dirtyPaths()} once per change
     * instead of once per frame.</p>
     *
     * <p><b>Over-fires by design.</b> It means "content moved", not "dirtiness flipped" — deciding the
     * latter needs the encode this exists to avoid doing eagerly.</p>
     */
    public final Signal.Value<CgPath> onDidChangeDirty = new Signal.Value<>();

    /** The document already open for a path, or null. */
    @Nullable
    public FileDocument get(CgPath path) {
        Entry entry = byPath.get(path);
        return entry == null ? null : entry.document;
    }

    public boolean isOpen(CgPath path) {
        return byPath.containsKey(path);
    }

    /** Every open path. */
    public List<CgPath> paths() {
        return new ArrayList<>(byPath.keySet());
    }

    /**
     * Marks a path as having had its read requested, and reports whether this call was the first.
     *
     * <p>The dock rebuilds a panel on every split, drag and close, and each rebuild would otherwise start
     * another read — landing on top of whatever is unsaved in the document.</p>
     */
    public boolean requestRead(CgPath path) {
        Entry entry = byPath.get(path);
        if (entry == null || entry.requested) return false;
        entry.requested = true;
        return true;
    }

    /**
     * Gives a document the bytes read from disk, and records them as the baseline.
     *
     * <p>A document that refuses them is remembered as unreadable rather than left looking modified
     * against a file it never managed to load — see {@link FileDocument#adopt}.
     *
     * @return the failure message when the document refused, else null
     */
    @Nullable
    public String adopt(CgPath path, byte[] bytes) {
        Entry entry = byPath.get(path);
        if (entry == null) return null;
        try {
            entry.document.adopt(bytes);
            entry.unreadable = false;
            entry.onDisk = bytes.clone();
            return null;
        } catch (RuntimeException refused) {
            entry.unreadable = true;
            entry.onDisk = null;
            return String.valueOf(refused.getMessage());
        }
    }

    /** Whether this file may be written at all — false once its document has refused to load it. */
    public boolean isSaveable(CgPath path) {
        Entry entry = byPath.get(path);
        return entry != null && !entry.unreadable;
    }

    /** Records what was actually written as the new baseline. */
    public void markSaved(CgPath path, byte[] written) {
        Entry entry = byPath.get(path);
        if (entry != null) entry.onDisk = written;
    }

    /**
     * Whether this file has changes that are not on disk.
     *
     * <p>Compared against the bytes last read or written rather than counted from edit events: a counter
     * says "modified" after a change <em>and its undo</em>, which is exactly the state somebody is in when
     * they close a tab and get asked to save a file identical to the one already there.</p>
     *
     * <p>False for a file that never loaded, and false for one no read has reached yet — a document the
     * dock built while the read is still in flight is empty because it is loading, not because it was
     * emptied.</p>
     */
    public boolean isDirty(CgPath path) {
        Entry entry = byPath.get(path);
        if (entry == null || entry.unreadable || entry.onDisk == null) return false;
        return !Arrays.equals(entry.document.encode(), entry.onDisk);
    }

    public List<CgPath> dirtyPaths() {
        List<CgPath> dirty = new ArrayList<>();
        for (CgPath path : byPath.keySet()) {
            if (isDirty(path)) dirty.add(path);
        }
        return dirty;
    }

    /**
     * Drops a document, releasing whatever it owned.
     *
     * <p><b>The dispose is the point.</b> A {@code ShaderGraphEditor} holds a {@code CgPreviewRenderer}
     * whose targets are {@code createOwned} and therefore invisible to every CrystalGraphics registry —
     * so dropping the entry without releasing it strands GPU memory until the process ends. Nothing
     * else can find it: the map was the only reference.</p>
     *
     * <p>Only reached today when a file is <em>deleted or moved</em>, which is genuinely the end of that
     * document. Closing a tab does not come through here yet, so a closed tab still keeps its document
     * alive — see the plan's step 3, where the dock gains a close event to route it.</p>
     */
    public void close(CgPath path) {
        Entry entry = byPath.remove(path);
        if (entry == null) return;
        // A CLOSED FILE'S PROBLEMS ARE NOT THE WORKSPACE'S, and the index holds a listener on every set in
        // it — so skipping this keeps the document, its diagnostics and the listener alive.
        long timed = FrameProfile.begin();
        if (markers != null && entry.document.resource() != null) {
            markers.detach(entry.document.resource());
        }
        FrameProfile.step(timed, "close.markers.detach");
        // BEFORE disposing. A listener told about a document whose dispose() has already run will ask it
        // something -- and encode() on a released graph is exactly the question dirtiness asks.
        if (entry.changes != null) entry.changes.disconnect();
        timed = FrameProfile.begin();
        if (entry.document instanceof Disposable disposable) {
            Disposer.dispose(disposable);
        }
        FrameProfile.step(timed, "close.Disposer.dispose");
    }

    /**
     * Moves everything about a file to its new path, in one step.
     *
     * <p>The whole reason the entry is one object: a rename used to move the document and leave the
     * baseline filed under the old name, so a renamed file reported itself modified against nothing and
     * the next Save All wrote it back for no reason.</p>
     */
    public void retarget(CgPath from, CgPath to) {
        Entry entry = byPath.remove(from);
        if (entry != null) byPath.put(to, entry);
    }
}
