package com.crystalgui.document;

import com.crystalgui.core.async.Broadcast;
import com.crystalgui.core.async.Contributions;
import com.crystalgui.core.async.Owner;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.fs.Resource;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The registered {@link DocumentKind}s: what file types the process can open.
 *
 * <p>One route in, {@link #contribute}, and every workbench reads {@link #process()}. A kind contributed by the
 * process (a service entry) is there for the process; one contributed by an application or a workbench extension is
 * visible to every application while its {@link Owner} runs, and leaves with it. An instance of its own, for a test
 * or a host with a different set, is {@code new DocumentKinds()}.</p>
 *
 * <pre>{@code
 * Disposable mine = DocumentKinds.process().contribute(workbench.owner(), MY_FILE_TYPE);
 * DocumentKinds.process().removeOwner(workbench.owner());      // when the owner ends
 * }</pre>
 *
 * <p>One owner contributing an id twice is refused rather than silently winning, for the reason a duplicate project
 * id is: which one applies would depend on load order. Another owner contributing the id is a second holder of it
 * (every application activates the same extension), and the first contributed is the one read.</p>
 */
public final class DocumentKinds {

    private static final DocumentKinds PROCESS = new DocumentKinds();

    private final Contributions<DocumentKind> contributed =
            Contributions.of("document-kinds", DocumentKind::id, Contributions.Conflict.SHARED);

    /** Each id once, in the order first contributed. */
    private final Contributions.Index<List<DocumentKind>> distinct = contributed.index(entries -> {
        Map<String, DocumentKind> byId = new LinkedHashMap<>();
        for (Contributions.Entry<DocumentKind> entry : entries) byId.putIfAbsent(entry.item().id(), entry.item());
        return List.copyOf(byId.values());
    });

    /** The kinds every workbench reads. */
    public static DocumentKinds process() {
        return PROCESS;
    }

    /** A kind was contributed or withdrawn: what a {@code New File} menu and an icon theme re-read. */
    public Broadcast<?> changes() {
        return contributed.published().changes();
    }

    /**
     * Contributes {@code kind} for {@code owner}; disposing the answer withdraws just this contribution.
     *
     * @throws IllegalArgumentException if this owner already contributed the id
     * @throws IllegalStateException if a second fallback is contributed, or the kind declares no model
     */
    public Disposable contribute(Owner owner, DocumentKind kind) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        if (kind.isFallback()) {
            for (DocumentKind existing : distinct.get()) {
                if (existing.isFallback() && !existing.id().equals(kind.id())) {
                    throw new IllegalStateException("'" + existing.id() + "' is already the fallback kind; "
                            + "which one applied would depend on registration order");
                }
            }
        }
        kind.freeze();
        return contributed.add(owner, kind);
    }

    /** Withdraws everything {@code owner} contributed. */
    public void removeOwner(Owner owner) {
        contributed.removeOwner(owner);
    }

    /**
     * The kind that claims this resource, or null.
     *
     * <p><b>Last contributed wins</b>, which is the opposite of the project registry's rule and is deliberate: two
     * kinds claiming one extension is not a collision to refuse, it is a mod specialising something the application
     * already handles, a Markdown preview over a text kind. The later contribution is the more specific one by
     * construction, since the application contributes its own first.</p>
     */
    @Nullable
    public DocumentKind forResource(Resource resource) {
        List<DocumentKind> kinds = distinct.get();
        DocumentKind fallback = null;
        for (int i = kinds.size() - 1; i >= 0; i--) {
            DocumentKind kind = kinds.get(i);
            if (kind.matches(resource)) return kind;
            if (kind.isFallback()) fallback = kind;
        }
        // ONLY AFTER EVERY MATCHER, so a kind claiming an extension still wins for it. @see
        // DocumentKind#fallback
        return fallback;
    }

    @Nullable
    public DocumentKind byId(String id) {
        for (DocumentKind kind : distinct.get()) {
            if (kind.id().equals(id)) return kind;
        }
        return null;
    }

    /** Every kind, in the order contributed. What a "New File" menu lists. */
    public List<DocumentKind> all() {
        return distinct.get();
    }

    public boolean isEmpty() {
        return distinct.get().isEmpty();
    }
}
