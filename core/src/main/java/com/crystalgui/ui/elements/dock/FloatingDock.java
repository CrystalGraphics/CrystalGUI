package com.crystalgui.ui.elements.dock;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.style.property.visual.Resize;

/**
 * A torn-off dock, floating over the main one.
 *
 * <h3>Why this is not a second window</h3>
 *
 * <p>VS Code's tear-out opens an actual OS window — {@code auxiliaryEditorPart.ts} is 496 lines of
 * second-window plumbing with its own titlebar, statusbar and layout pass. Minecraft has one window, so
 * none of that is available and none of it is wanted. A float here is a promoted element in the top layer,
 * which {@link Dialog} already knows how to be: drag-to-move from the title bar, resize handles, and a
 * clamp against the containing block are all inherited rather than rewritten.</p>
 *
 * <h3>What it must NOT inherit, and this is the whole reason it is written down</h3>
 *
 * <p>It is shown with {@link Dialog#show()} and never {@code showModal()}, so it is <b>neither modal nor
 * light-dismissable</b> and Escape does not reach it. Those are exactly the behaviours borrowing from
 * {@code Dialog} would otherwise hand over for free — and a floating panel that vanishes when you click
 * the graph behind it, or that makes everything else inert while it is open, is a bug that ships looking
 * like a feature.</p>
 *
 * <h3>It hosts a whole {@link DockLayout}, not a single panel</h3>
 *
 * <p>ImGui's rule, and the reason its floating windows can be split and tabbed exactly like docked ones.
 * A tear-out is <em>remove from tree A</em> then <em>insert into a fresh tree B</em>, and a re-dock is the
 * reverse — so the two hardest-looking gestures are the same two operations the drop code already needs.</p>
 */
public class FloatingDock extends Dialog {

    public static final String FLOATING_CLASS = "__floating-dock__";

    /** Where a float appears when nothing says otherwise, in logical pixels. */
    private static final float DEFAULT_WIDTH = 360f;
    private static final float DEFAULT_HEIGHT = 260f;

    private final DockArea area;

    public FloatingDock(DockPanelRegistry<UIElement> registry, DockLayout layout, String title) {
        super(title);
        addClass(FLOATING_CLASS);

        this.area = new DockArea(registry, layout);
        StyleGroup.defaultPipeline(area.getStyle().getLayoutGroup(), l -> l.flexGrow(1f).flexBasis(0));
        getContent().addChild(area);

        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.width(DEFAULT_WIDTH).height(DEFAULT_HEIGHT));
        // Resizable from every edge and corner. A float is the one thing in the layout with no divider to
        // size it, so without this it is stuck at whatever it was born as.
        StyleGroup.defaultPipeline(getStyle().getGeneralGroup(), g -> g.resize(Resize.BOTH));
    }

    /** The one panel torn out, floating at {@code (left, top)}. */
    public static FloatingDock of(DockPanelRegistry<UIElement> registry, DockPanelRef panel,
                                  float left, float top) {
        FloatingDock dock = new FloatingDock(registry, DockLayout.of(new DockLeaf(panel)),
                registry.titleOf(panel));
        dock.moveTo(left, top);
        return dock;
    }

    public DockArea area() {
        return area;
    }

    public DockLayout layout() {
        return area.layout();
    }

    /**
     * Shows it, promoted.
     *
     * <p>{@link Dialog#show()} is modeless and therefore stays in normal flow; a float has to paint above
     * the dock it came out of, so it joins the top layer explicitly. Deliberately not {@code showModal()},
     * which would promote it <em>and</em> make everything else inert.</p>
     */
    @Override
    public FloatingDock show() {
        super.show();
        addToTopLayer();
        return this;
    }

    /**
     * Closes the float when its last panel has gone.
     *
     * <p>Called by whoever owns the float after a drag out of it. Not automatic on a timer or a tick: an
     * empty float is a legitimate transient state <em>during</em> a drag, and a float that closed itself
     * mid-gesture would take the drop target with it.</p>
     */
    public boolean closeIfEmpty() {
        if (!layout().leaves().isEmpty()) {
            boolean anyPanels = false;
            for (DockLeaf leaf : layout().leaves()) {
                if (!leaf.isEmpty()) {
                    anyPanels = true;
                    break;
                }
            }
            if (anyPanels) return false;
        }
        close();
        removeFromTopLayer();
        removeSelf();
        return true;
    }
}
