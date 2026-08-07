package com.crystalgui.ui.elements.dock;

import com.crystalgui.fs.Resource;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * What a pane is pointed at — VS Code's {@code EditorInput}, IntelliJ's {@code (provider, file)} pair.
 *
 * <h3>Why this is not just a {@link DockPanelRef}</h3>
 *
 * <p>A {@code DockPanelRef} is the <b>persisted</b> form: a type id plus a string state map, shaped by
 * what a session has to write down. An input is the <b>runtime</b> form, and it answers questions the ref
 * cannot without every caller re-deriving them — most of all "what {@link Resource} is this", which is a
 * parse rather than a field.</p>
 *
 * <p>It wraps the ref rather than replacing it, so the session codec is untouched: {@link #ref()} is what
 * gets written, and nothing downstream of persistence has to change for panes to exist.</p>
 */
public final class DockInput {

    private final DockPanelRef ref;

    @Nullable
    private final Resource resource;

    private DockInput(DockPanelRef ref, @Nullable Resource resource) {
        this.ref = ref;
        this.resource = resource;
    }

    /**
     * The input a panel ref denotes.
     *
     * <p>The resource is parsed from the ref's {@code path} state when it has one, and is null otherwise
     * — a tool window is a perfectly good input with nothing behind it. An unparseable value yields null
     * rather than throwing: this runs while a layout is being built, and a saved session containing
     * something odd must degrade to a panel that shows nothing rather than refuse the whole restore.</p>
     */
    public static DockInput of(DockPanelRef ref) {
        Objects.requireNonNull(ref, "ref");
        String raw = ref.state(DockPanelRef.PATH, "");
        Resource parsed = null;
        if (!raw.isEmpty()) {
            try {
                parsed = Resource.parse(raw);
            } catch (RuntimeException unparseable) {
                parsed = null;
            }
        }
        return new DockInput(ref, parsed);
    }

    /** An input built from its parts — what a caller opening something new writes. */
    public static DockInput of(String typeId, Resource resource) {
        Objects.requireNonNull(typeId, "typeId");
        DockPanelRef ref = new DockPanelRef(typeId);
        if (resource != null) ref = ref.withState(DockPanelRef.PATH, resource.toString());
        return new DockInput(ref, resource);
    }

    public String typeId() {
        return ref.typeId();
    }

    /** What this input is <em>about</em>, or null for a tool window that is about nothing. */
    @Nullable
    public Resource resource() {
        return resource;
    }

    /** The persisted form. What a session writes, and what the layout is keyed by. */
    public DockPanelRef ref() {
        return ref;
    }

    /**
     * Whether two inputs denote the same thing.
     *
     * <p>Ref equality, deliberately — the ref's identity <b>includes its state</b>, which is what makes
     * two file tabs on different paths different panels. Comparing resources alone would make two panel
     * types over one file look like the same input.</p>
     */
    public boolean matches(@Nullable DockInput other) {
        return other != null && ref.equals(other.ref);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DockInput that && ref.equals(that.ref);
    }

    @Override
    public int hashCode() {
        return ref.hashCode();
    }

    @Override
    public String toString() {
        return "DockInput(" + ref.typeId() + (resource == null ? "" : ", " + resource) + ")";
    }
}
