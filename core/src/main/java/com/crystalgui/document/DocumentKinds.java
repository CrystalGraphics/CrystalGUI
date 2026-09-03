package com.crystalgui.document;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The registered {@link DocumentKind}s — <b>an instance, never a static.</b>
 *
 * <p>{@code plan_fs_rewrite.md} D24, A1. {@code ResourceRegistry} is static and so were the host's three
 * peer maps, which means a second workspace in one process cannot have its own: a {@code DockWindow}
 * makes two workbenches per process ordinary, and two servers in one client is what a dev environment
 * looks like. {@code Markers} was made an instance for exactly this reason.</p>
 *
 * <p>Registration answers a {@link Disposable}, so a mod that unloads takes its kinds with it. A second
 * registration of the same id is refused rather than silently winning, for the reason a duplicate
 * project id is: which one applies would then depend on load order, and a session record naming that id
 * would open a different editor on different launches.</p>
 */
public final class DocumentKinds {

    private final List<DocumentKind> kinds = new ArrayList<>();

    /** A kind was registered or withdrawn — what a "New File" menu and an icon theme re-read. */
    public final Signal.Action onDidChange = new Signal.Action();

    /**
     * @throws IllegalStateException if the id is already registered, or the kind declares no model
     */
    public Disposable register(DocumentKind kind) {
        Objects.requireNonNull(kind, "kind");
        for (DocumentKind existing : kinds) {
            if (existing.id().equals(kind.id())) {
                throw new IllegalStateException("a document kind '" + kind.id() + "' is already "
                        + "registered — ids must be namespaced, e.g. 'mymod:" + kind.id() + "'");
            }
        }
        kind.freeze();
        kinds.add(kind);
        onDidChange.emit();
        return () -> {
            if (kinds.remove(kind)) onDidChange.emit();
        };
    }

    /**
     * The kind that claims this resource, or null.
     *
     * <p><b>Last registered wins</b>, which is the opposite of the project registry's rule and is
     * deliberate: two kinds claiming one extension is not a collision to refuse, it is a mod
     * specialising something the application already handles — a Markdown preview over a text kind. The
     * later registration is the more specific one by construction, since the application registers its
     * own first.</p>
     */
    @Nullable
    public DocumentKind forResource(Resource resource) {
        for (int i = kinds.size() - 1; i >= 0; i--) {
            if (kinds.get(i).matches(resource)) return kinds.get(i);
        }
        return null;
    }

    @Nullable
    public DocumentKind byId(String id) {
        for (DocumentKind kind : kinds) {
            if (kind.id().equals(id)) return kind;
        }
        return null;
    }

    /** Every kind, in registration order. What a "New File" menu lists. */
    public List<DocumentKind> all() {
        return List.copyOf(kinds);
    }

    public boolean isEmpty() {
        return kinds.isEmpty();
    }
}
