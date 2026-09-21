package com.crystalgui.document;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.Signal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The registered {@link NewDocumentKind}s — what <b>New ▸</b> is built from.
 *
 * <p>An instance rather than a static, and per workbench, for the reason {@link DocumentKinds} is: two
 * workbenches in one process is ordinary, and each keeps its own. A jar contributes through the same
 * {@code WorkbenchExtension} seam it contributes a panel or a document kind through.</p>
 *
 * <pre>{@code
 * workbench.newDocuments().register(NewDocumentKind.of("mymod:thing", "Thing")
 *         .suffix(".thing")
 *         .template(target -> "{ \"name\": \"" + target.typeName() + "\" }"));
 * }</pre>
 *
 * <p>Registration answers a {@link Disposable}, so a mod that unloads takes its rows with it. A second
 * registration of the same id is refused rather than silently winning — which id applies would otherwise
 * depend on load order, and the id is a command id, so a keymap naming it would fire different things on
 * different launches.</p>
 */
public final class NewDocumentKinds {

    private final List<NewDocumentKind> kinds = new ArrayList<>();

    /** A kind was registered or withdrawn — what an open New menu re-reads. */
    public final Signal.Action onDidChange = new Signal.Action();

    /** @throws IllegalStateException if the id is already registered */
    public Disposable register(NewDocumentKind kind) {
        Objects.requireNonNull(kind, "kind");
        for (NewDocumentKind existing : kinds) {
            if (existing.id().equals(kind.id())) {
                throw new IllegalStateException("a new-document kind '" + kind.id() + "' is already "
                        + "registered — ids must be namespaced, e.g. 'mymod:" + kind.id() + "'");
            }
        }
        kinds.add(kind);
        onDidChange.emit();
        return () -> {
            if (kinds.remove(kind)) onDidChange.emit();
        };
    }

    /** Every registered kind, in registration order. */
    public List<NewDocumentKind> all() {
        return List.copyOf(kinds);
    }

    /**
     * The kinds offered in {@code at}, ordered as the menu draws them: by group, then by order.
     *
     * <p>Sorted here rather than by the renderer so two menus over one registry cannot disagree, and so
     * a contributor's {@code at("2_kinds", 10)} means the same thing wherever its row appears.</p>
     */
    public List<NewDocumentKind> offeredAt(NewDocumentContext at) {
        List<NewDocumentKind> offered = new ArrayList<>();
        for (NewDocumentKind kind : kinds) {
            if (kind.offeredAt(at)) offered.add(kind);
        }
        offered.sort(Comparator.comparing(NewDocumentKind::group)
                .thenComparingInt(NewDocumentKind::order));
        return offered;
    }

    /** The kind with {@code id}, or null. */
    public NewDocumentKind byId(String id) {
        for (NewDocumentKind kind : kinds) {
            if (kind.id().equals(id)) return kind;
        }
        return null;
    }
}
