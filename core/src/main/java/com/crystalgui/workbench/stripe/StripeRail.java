package com.crystalgui.workbench.stripe;

import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionSide;

/**
 * Which of the two rails carries a tool window's button, and which end of it — IntelliJ's New UI stripes.
 *
 * <h3>The 2x2, and which end of it is primitive</h3>
 *
 * <table>
 *   <caption>Rail and group, derived from placement</caption>
 *   <tr><th>rail</th><th>group</th><th>region</th><th>side</th></tr>
 *   <tr><td>LEFT</td><td>top</td><td>{@code SIDEBAR}</td><td>splits the column</td></tr>
 *   <tr><td>LEFT</td><td>bottom</td><td>{@code PANEL}</td><td>{@code PRIMARY} — the strip's left half</td></tr>
 *   <tr><td>RIGHT</td><td>top</td><td>{@code AUXILIARY}</td><td>splits the column</td></tr>
 *   <tr><td>RIGHT</td><td>bottom</td><td>{@code PANEL}</td><td>{@code SECONDARY} — its right half</td></tr>
 * </table>
 *
 * <p><b>Placement is primitive and the rail is derived</b>, not the other way round — even though the
 * <em>gesture</em> runs the other way, since dragging a button between rails is how a region gets changed.
 * IntelliJ arranges it identically: it persists {@code anchor} plus {@code isSplit} and works the stripe out
 * from them.</p>
 *
 * <p>The reason is that a region already has an owner. {@link WorkbenchRegions} is an {@code EnumMap} over
 * {@link DockRegion}, {@link RegionHost} is keyed by one, and {@link ToolWindowState} persists one. A stored
 * rail would be a fifth statement of the same fact, free to disagree with the four — and it would disagree
 * silently, because nothing draws a rail and a region side by side to compare them.</p>
 *
 * <h3>{@code PANEL} is the asymmetric row, and it is not a modelling compromise</h3>
 *
 * <p>A side never changes which rail a {@code SIDEBAR} button is in: Project and Structure are both left-rail
 * top group. It <em>does</em> for {@code PANEL}, because the New UI has no bottom stripe — the bottom
 * region's two halves borrow the two rails' bottom groups. That is what the reference does; see
 * {@link RegionSide}.</p>
 */
public enum StripeRail {

    LEFT,
    RIGHT;

    /** Which rail carries the button for a tool window placed here. */
    public static StripeRail of(DockRegion region, RegionSide side) {
        if (region == DockRegion.AUXILIARY) return RIGHT;
        if (region == DockRegion.PANEL) return side == RegionSide.SECONDARY ? RIGHT : LEFT;
        return LEFT;
    }

    /** The region this rail's <b>top</b> group shows — both halves of it. */
    public DockRegion topRegion() {
        return this == RIGHT ? DockRegion.AUXILIARY : DockRegion.SIDEBAR;
    }

    /**
     * Which half of {@link DockRegion#PANEL} this rail's <b>bottom</b> group shows.
     *
     * <p>The asymmetric row of the table above, said the other way round: the bottom region has no rail of
     * its own, so its two halves are split across the two rails' bottom groups.</p>
     */
    public RegionSide bottomSide() {
        return this == RIGHT ? RegionSide.SECONDARY : RegionSide.PRIMARY;
    }
}
