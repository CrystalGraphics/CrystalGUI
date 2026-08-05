package com.crystalgui.ui.elements.workbench;

import com.crystalgui.fs.CgPath;

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
final class OpenDocuments {

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

        Entry(FileDocument document) {
            this.document = document;
        }
    }

    private final Map<CgPath, Entry> byPath = new HashMap<>();

    /** The document for a path, built on first use. */
    FileDocument documentFor(CgPath path, Function<CgPath, FileDocument> factory) {
        return byPath.computeIfAbsent(path, key -> new Entry(factory.apply(key))).document;
    }

    /** The document already open for a path, or null. */
    @Nullable
    FileDocument get(CgPath path) {
        Entry entry = byPath.get(path);
        return entry == null ? null : entry.document;
    }

    boolean isOpen(CgPath path) {
        return byPath.containsKey(path);
    }

    /** Every open path. */
    List<CgPath> paths() {
        return new ArrayList<>(byPath.keySet());
    }

    /**
     * Marks a path as having had its read requested, and reports whether this call was the first.
     *
     * <p>The dock rebuilds a panel on every split, drag and close, and each rebuild would otherwise start
     * another read — landing on top of whatever is unsaved in the document.</p>
     */
    boolean requestRead(CgPath path) {
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
    String adopt(CgPath path, byte[] bytes) {
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
    boolean isSaveable(CgPath path) {
        Entry entry = byPath.get(path);
        return entry != null && !entry.unreadable;
    }

    /** Records what was actually written as the new baseline. */
    void markSaved(CgPath path, byte[] written) {
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
    boolean isDirty(CgPath path) {
        Entry entry = byPath.get(path);
        if (entry == null || entry.unreadable || entry.onDisk == null) return false;
        return !Arrays.equals(entry.document.encode(), entry.onDisk);
    }

    List<CgPath> dirtyPaths() {
        List<CgPath> dirty = new ArrayList<>();
        for (CgPath path : byPath.keySet()) {
            if (isDirty(path)) dirty.add(path);
        }
        return dirty;
    }

    void close(CgPath path) {
        byPath.remove(path);
    }

    /**
     * Moves everything about a file to its new path, in one step.
     *
     * <p>The whole reason the entry is one object: a rename used to move the document and leave the
     * baseline filed under the old name, so a renamed file reported itself modified against nothing and
     * the next Save All wrote it back for no reason.</p>
     */
    void retarget(CgPath from, CgPath to) {
        Entry entry = byPath.remove(from);
        if (entry != null) byPath.put(to, entry);
    }
}
