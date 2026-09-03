package com.crystalgui.document;

import com.crystalgui.fs.Resource;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * <b>What a tab was opened WITH</b> — VS Code's {@code EditorInput}, IntelliJ's {@code FileEditor}'s file.
 *
 * <h3>One lane, and there were two</h3>
 *
 * <p>{@code plan_fs_rewrite.md} N1. A project file opened through {@code openFile(CgPath)} and a library
 * class through {@code openResource(Resource)}, into two different stores with two different state keys,
 * and the second lane cost roughly four hundred lines re-deriving open, adopt and presentation. Every
 * caller had to know which kind of thing it was holding, and the two answered differently to the tab
 * strip, the session record and the dock.</p>
 *
 * <p>An input is a {@link Resource} plus what to do with it, so a project file, a decompiled class, a
 * generated shader source and — at M7 — a server-described panel are the same lane. Which one it is,
 * where the bytes come from and whether it may be saved are all read off the resource's scheme by
 * whoever opens it.</p>
 */
public final class EditorInput {

    private final Resource resource;
    @Nullable
    private final String preferredKindId;
    private final boolean readOnly;

    private EditorInput(Resource resource, @Nullable String preferredKindId, boolean readOnly) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.preferredKindId = preferredKindId;
        this.readOnly = readOnly;
    }

    /** The ordinary case: open this, however the registered kinds say it should be opened. */
    public static EditorInput of(Resource resource) {
        return new EditorInput(resource, null, false);
    }

    /**
     * Open this <b>as</b> that kind, whatever the file's name suggests.
     *
     * <p>For "Open With…", and for a caller that knows something the name does not — a generated shader
     * source has no extension of its own and is GLSL.</p>
     */
    public EditorInput as(String kindId) {
        return new EditorInput(resource, kindId, readOnly);
    }

    /**
     * Refuse edits, whatever the model would allow.
     *
     * <p>A property of this OPENING, not of the document: the same class file is read-only through the
     * library scheme and editable if the user has its source in a project. Putting it on the model
     * would make the second case unreachable.</p>
     */
    public EditorInput readOnly() {
        return new EditorInput(resource, preferredKindId, true);
    }

    public Resource resource() {
        return resource;
    }

    @Nullable
    public String preferredKindId() {
        return preferredKindId;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Two inputs open the same tab when they name the same thing the same way.
     *
     * <p>{@code readOnly} is part of it: a read-only view of a file and an editable one are two tabs,
     * which is what lets a diff's left pane sit beside the live document without one closing the
     * other.</p>
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EditorInput that)) return false;
        return readOnly == that.readOnly
                && resource.equals(that.resource)
                && Objects.equals(preferredKindId, that.preferredKindId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resource, preferredKindId, readOnly);
    }

    @Override
    public String toString() {
        return "EditorInput(" + resource + (preferredKindId == null ? "" : " as " + preferredKindId)
                + (readOnly ? ", read-only)" : ")");
    }
}
