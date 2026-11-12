package com.crystalgui.document;

import com.crystalgui.core.async.Broadcast;
import com.crystalgui.core.async.Contributions;
import com.crystalgui.core.async.Owner;
import com.crystalgui.core.dispose.Disposable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The registered {@link NewDocumentKind}s: what <b>New ▸</b> is built from.
 *
 * <p>The creation-side twin of {@link DocumentKinds}, with the same one route and the same scoping: every workbench
 * reads {@link #process()}, and what an application contributes is offered while its {@link Owner} runs. An instance
 * of its own, for a test, is {@code new NewDocumentKinds()}.</p>
 *
 * <pre>{@code
 * NewDocumentKinds.process().contribute(workbench.owner(), NewDocumentKind.of("mymod:thing", "Thing")
 *         .suffix(".thing")
 *         .template(target -> "{ \"name\": \"" + target.typeName() + "\" }"));
 * }</pre>
 *
 * <p>One owner contributing an id twice is refused: the id is a command id, so a keymap naming it would fire
 * different things on different launches. Another owner contributing it is a second holder; the first is read.</p>
 */
public final class NewDocumentKinds {

    private static final NewDocumentKinds PROCESS = new NewDocumentKinds();

    private final Contributions<NewDocumentKind> contributed =
            Contributions.of("new-document-kinds", NewDocumentKind::id, Contributions.Conflict.SHARED);

    private final Contributions.Index<List<NewDocumentKind>> distinct = contributed.index(entries -> {
        Map<String, NewDocumentKind> byId = new LinkedHashMap<>();
        for (Contributions.Entry<NewDocumentKind> entry : entries) byId.putIfAbsent(entry.item().id(), entry.item());
        return List.copyOf(byId.values());
    });

    /** The kinds every workbench reads. */
    public static NewDocumentKinds process() {
        return PROCESS;
    }

    /** A kind was contributed or withdrawn: what an open New menu re-reads. */
    public Broadcast<?> changes() {
        return contributed.published().changes();
    }

    /** @throws IllegalStateException if another kind has the id */
    public Disposable contribute(Owner owner, NewDocumentKind kind) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        return contributed.add(owner, kind);
    }

    /** Withdraws everything {@code owner} contributed. */
    public void removeOwner(Owner owner) {
        contributed.removeOwner(owner);
    }

    /** Every contributed kind, in the order contributed. */
    public List<NewDocumentKind> all() {
        return distinct.get();
    }

    /**
     * The kinds offered in {@code at}, ordered as the menu draws them: by group, then by order.
     *
     * <p>Sorted here rather than by the renderer so two menus over one registry cannot disagree, and so
     * a contributor's {@code at("2_kinds", 10)} means the same thing wherever its row appears.</p>
     */
    public List<NewDocumentKind> offeredAt(NewDocumentContext at) {
        List<NewDocumentKind> offered = new ArrayList<>();
        for (NewDocumentKind kind : distinct.get()) {
            if (kind.offeredAt(at)) offered.add(kind);
        }
        offered.sort(Comparator.comparing(NewDocumentKind::group)
                .thenComparingInt(NewDocumentKind::order));
        return offered;
    }

    /** The kind with {@code id}, or null. */
    public NewDocumentKind byId(String id) {
        for (NewDocumentKind kind : distinct.get()) {
            if (kind.id().equals(id)) return kind;
        }
        return null;
    }
}
