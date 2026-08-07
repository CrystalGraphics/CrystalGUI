package com.crystalgui.ui.elements.dock;

/**
 * How to open, as distinct from <b>where</b> — VS Code's {@code IEditorOptions} beside its
 * {@code PreferredGroup}.
 *
 * <h3>Why this is separate from {@link DockPlacement}</h3>
 *
 * <p>Because the two vary independently, and conflating them is what produced three near-identical
 * {@code openPanel} overloads whose real differences were buried: one activated what it opened, one
 * deliberately restored the previous selection, and one set a size share. Those are <em>options</em>, and
 * every combination of them with every placement is meaningful.</p>
 *
 * <p>Immutable and chained, so a call site reads as a sentence and a new option costs nobody a new
 * overload.</p>
 */
public final class DockOpenOptions {

    /** Open it and bring it forward. What "open a file" means. */
    public static final DockOpenOptions ACTIVATE = new DockOpenOptions(true, Float.NaN);

    /**
     * Open it without stealing the current tab.
     *
     * <p>{@code DockLeaf.add} activates what it inserts, which is right for a file and wrong for a
     * companion panel: one that steals its sibling's tab opens by hiding the thing you were looking at,
     * and the pane it joined exists to be looked at.</p>
     */
    public static final DockOpenOptions INACTIVE = new DockOpenOptions(false, Float.NaN);

    private final boolean activate;
    private final float share;

    private DockOpenOptions(boolean activate, float share) {
        this.activate = activate;
        this.share = share;
    }

    /**
     * How much of the space a <b>new</b> split takes, as a fraction. Ignored when nothing is split.
     *
     * <p>NaN means "whatever the layout decides", which is the honest default — a share is only
     * meaningful for a pane being created, and most opens land in a pane that already exists.</p>
     */
    public DockOpenOptions withShare(float share) {
        return new DockOpenOptions(activate, share);
    }

    public boolean activates() {
        return activate;
    }

    public boolean hasShare() {
        return !Float.isNaN(share);
    }

    public float share() {
        return share;
    }
}
