package com.crystalgui.document;

import com.crystalgui.fs.Resource;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * What a tab was opened WITH — VS Code's {@code EditorInput}, IntelliJ's {@code FileEditor}'s file.
 *
 * <p>A {@link Resource} plus what to do with it, so a project file, a decompiled class and a generated
 * shader source all open through one lane. Which one it is, where its bytes come from and whether it
 * may be saved are read off the resource's scheme by whoever opens it, not by the caller.</p>
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
