package com.crystalgui.workbench.region;

/**
 * Which <b>half</b> of a region a tool window occupies — IntelliJ's {@code WindowInfo.isSplit()}.
 *
 * <h3>One field, four rows</h3>
 *
 * <p>IntelliJ splits a stripe: Project top-left, Structure bottom-left, one region divided along its cross
 * axis. §23.5 ruled that out on the grounds that stacking two tool windows on one wall is <i>"a feature of
 * IntelliJ's tool-window host rather than of a dock tree"</i> — true, and irrelevant once we are building a
 * tool-window host. plan/shell-architecture-audit.md §24.5 records the reversal.</p>
 *
 * <p><b>The axis is the region's cross axis</b>, which is what lets one field cover both cases:</p>
 *
 * <ul>
 *   <li>{@link DockRegion#SIDEBAR} and {@link DockRegion#AUXILIARY} are columns, so the halves are
 *       <em>top</em> and <em>bottom</em> — Project over Structure.</li>
 *   <li>{@link DockRegion#PANEL} is a strip, so the halves are <em>left</em> and <em>right</em> — Terminal
 *       beside Services.</li>
 * </ul>
 *
 * <h3>Why {@code PANEL} is the one that also moves a stripe button</h3>
 *
 * <p>The New UI deleted the bottom stripe. A left-anchored tool window's button is in the left rail whether
 * it is split or not, but the bottom region has no rail of its own — so its two halves <b>borrow the two
 * rails' bottom groups</b>. That asymmetry is real behaviour in the target rather than a modelling
 * compromise, and {@link com.crystalgui.ui.elements.workbench.StripeRail} is where it is written down.</p>
 *
 * <p><b>At most two per region</b>, which is IntelliJ's own limit and what keeps a region a region rather
 * than a second dock tree. There is no {@code TERTIARY} and there should not be one.</p>
 */
public enum RegionSide {

    /** The first half — top of a column, left of a strip. Where everything goes until something says else. */
    PRIMARY,

    /** The second half — bottom of a column, right of a strip. */
    SECONDARY;

    /** The other one. */
    public RegionSide opposite() {
        return this == PRIMARY ? SECONDARY : PRIMARY;
    }

    /**
     * The constant named {@code name}, or {@link #PRIMARY}.
     *
     * <p>Matched by hand rather than through {@code StateMap.getEnum}, which throws for a constant this
     * build does not have — and a session record is untrusted input written by a possibly-newer build.
     * Losing a half costs one drag; losing the record costs the size, the order and whether it was open.</p>
     */
    public static RegionSide ofName(String name) {
        for (RegionSide side : values()) {
            if (side.name().equals(name)) return side;
        }
        return PRIMARY;
    }
}
