package com.crystalgui.style.property.layout;

import com.crystalgui.style.property.StyleProperty;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared table describing the three 4-edge box-model shorthand groups (margin/padding/border-width)
 * — used by {@link com.crystalgui.style.sheet.StyleSheet} to expand a shorthand declaration into its
 * real longhand declarations at parse time, and by
 * {@link com.crystalgui.style.transition.TransitionEngine} so {@code transition: margin ...} still
 * matches all four longhands.
 *
 * <p>Each group's four edges ({@code -left}/{@code -top}/{@code -right}/{@code -bottom}) are the only
 * real, independently-cascading {@link StyleProperty} instances (declared in {@link LayoutProperties}).
 * The composite name (e.g. {@code margin}) and its {@code -all}/{@code -horizontal}/{@code -vertical}
 * aliases are pure syntax recognized here, never registered in {@code StylePropertyRegistry} — matching
 * how a real browser only ever exposes resolved longhands (`getComputedStyle`), with the shorthand as
 * sugar over them, not a separately-cascading concept.</p>
 */
public final class BoxEdgeShorthands {

    public enum Kind { COMPOSITE, ALL, HORIZONTAL, VERTICAL }

    public record Group(String prefix,
                         StyleProperty<LengthPercentageAuto> left,
                         StyleProperty<LengthPercentageAuto> top,
                         StyleProperty<LengthPercentageAuto> right,
                         StyleProperty<LengthPercentageAuto> bottom) {
    }

    /** One shorthand alias name resolving to its group and which expansion rule applies. */
    public record Match(Group group, Kind kind) {
    }

    public static final List<Group> GROUPS = List.of(
            new Group("margin", LayoutProperties.MARGIN_LEFT, LayoutProperties.MARGIN_TOP,
                    LayoutProperties.MARGIN_RIGHT, LayoutProperties.MARGIN_BOTTOM),
            new Group("padding", LayoutProperties.PADDING_LEFT, LayoutProperties.PADDING_TOP,
                    LayoutProperties.PADDING_RIGHT, LayoutProperties.PADDING_BOTTOM),
            new Group("border-width", LayoutProperties.BORDER_LEFT, LayoutProperties.BORDER_TOP,
                    LayoutProperties.BORDER_RIGHT, LayoutProperties.BORDER_BOTTOM)
    );

    private static final Map<String, Match> BY_ALIAS = new ConcurrentHashMap<>();
    static {
        for (Group group : GROUPS) {
            BY_ALIAS.put(group.prefix(), new Match(group, Kind.COMPOSITE));
            BY_ALIAS.put(group.prefix() + "-all", new Match(group, Kind.ALL));
            BY_ALIAS.put(group.prefix() + "-horizontal", new Match(group, Kind.HORIZONTAL));
            BY_ALIAS.put(group.prefix() + "-vertical", new Match(group, Kind.VERTICAL));
        }
    }

    private BoxEdgeShorthands() {
    }

    /** @return the group+kind a declaration name expands to, or {@code null} if it isn't one of these shorthands. */
    public static @Nullable Match lookup(String declarationName) {
        return BY_ALIAS.get(declarationName);
    }

    /**
     * Whether {@code transition: <name> ...} should animate {@code longhand} — true for an exact
     * name match, {@code all}, or naming the longhand's own shorthand group prefix (e.g.
     * {@code transition: margin 300ms;} must animate all four margin edges together, matching real
     * CSS's shorthand transition-property behavior).
     */
    public static boolean transitionNameMatches(String transitionPropertyName, StyleProperty<?> longhand) {
        for (Group group : GROUPS) {
            if (group.prefix().equals(transitionPropertyName)
                    && (longhand == group.left() || longhand == group.top()
                        || longhand == group.right() || longhand == group.bottom())) {
                return true;
            }
        }
        return false;
    }
}
