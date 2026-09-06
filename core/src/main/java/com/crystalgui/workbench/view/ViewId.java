package com.crystalgui.workbench.view;

import com.crystalgui.workbench.dock.layout.DockPanelRef;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The identity of a <b>view</b> — one tool window, wherever it currently lives.
 *
 * <h3>Why this is not a {@link DockPanelRef}</h3>
 *
 * <p>A {@code DockPanelRef}'s equality <b>includes its state map</b>. That is correct for a document — two
 * tabs on different files are genuinely different things — and it is exactly wrong for a view, because it
 * makes identity depend on where the view happens to be and what it happens to be showing.</p>
 *
 * <p>The consequence is not theoretical. Under the Parts model a view can be dragged from the sidebar to
 * the bottom panel. If its container were carried in ref state, the view in the sidebar and the view in
 * the panel would be <b>two different refs</b> — so {@code leafContaining} would not find it,
 * {@code DockGroup.content} would build a second copy, every {@code Map<DockPanelRef, …>} would hold two
 * entries, and the drag would destroy and rebuild the view rather than move it. The scroll position, the
 * selection and the undo stack would go with it, and the bug would read as "the panel resets when I move
 * it" rather than as an identity error.</p>
 *
 * <p>It is also already known to bite: adding the {@code ICON} state key was a breaking change for saved
 * layouts for this reason, which {@code plan/shell-architecture-audit.md} §1.5 records.</p>
 *
 * <h3>A view does not know its container</h3>
 *
 * <p><b>Membership belongs to the container, never to the view.</b> Both references arrange it this way: a
 * VS Code view declares a {@code containerId} that the <em>registry</em> owns the mapping for, and an
 * IntelliJ {@code Content} does not know its {@code ContentManager}'s identity. Putting the container on
 * the view means moving a view is a write to the view, so two containers can disagree about who holds it
 * and nothing is authoritative.</p>
 *
 * <p>So there is deliberately no {@code container()} accessor here, and there should never be one. Ask the
 * container registry which container holds a view.</p>
 *
 * <h3>Interned, like {@link com.crystalgui.core.command.MenuId} and {@code DataKey}</h3>
 *
 * <p>Same reasoning: a view named in two places must be one view, and identity comparison is what keeps a
 * lookup cheap enough to do while a layout is being rebuilt. The string survives serialisation, so a
 * persisted view id is stable across runs.</p>
 */
public final class ViewId {

    private static final Map<String, ViewId> INTERNED = new ConcurrentHashMap<>();

    private final String id;

    private ViewId(String id) {
        this.id = id;
    }

    /**
     * @param id stable and dotted — {@code "workbench.view.explorer"}. This is what a saved layout stores,
     *           so renaming one silently orphans every session that named it.
     */
    public static ViewId of(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isEmpty()) throw new IllegalArgumentException("A view needs an id");
        return INTERNED.computeIfAbsent(id, ViewId::new);
    }

    public String id() {
        return id;
    }

    /**
     * The panel ref this view is shown as, for as long as views are still dock panels.
     *
     * <p><b>Transitional</b>, and deliberately one-way. The tool-window half is being moved out of the
     * dock tree (plan/shell-architecture-audit.md §23, F2); until it is, a view still has to be findable as a panel. Nothing should
     * grow a {@code fromRef} inverse — a ref carries state, an id does not, and the whole point is that the
     * conversion loses nothing in this direction and could not be faithful in the other.</p>
     */
    public DockPanelRef asPanelRef() {
        return new DockPanelRef(id);
    }

    @Override
    public String toString() {
        return "ViewId[" + id + "]";
    }
}
