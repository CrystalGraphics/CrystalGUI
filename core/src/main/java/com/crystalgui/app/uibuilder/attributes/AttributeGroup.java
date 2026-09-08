package com.crystalgui.app.uibuilder.attributes;

/**
 * How a copied style property is filed in the Paste Attributes window.
 *
 * <p>DaVinci Resolve's grouping, in this application's terms. The window is a list of checkboxes and a
 * flat list of forty is not a choice anyone can make, so the groups are the unit somebody actually thinks
 * in: <em>give it the same look but not the same size</em>.</p>
 *
 * <pre>{@code
 * AttributeGroup.of("border-radius")   // APPEARANCE
 * AttributeGroup.of("flex-grow")       // LAYOUT
 * }</pre>
 *
 * <p><b>Matched on the property name, and LAYOUT is the default.</b> Not a registry lookup: the groups
 * answer a question the registry does not hold — a border's WIDTH is layout to Taffy and appearance to a
 * person, and it is filed where the person would look for it. A property nobody thought about lands in
 * Layout, which is where a builder's properties overwhelmingly are.</p>
 */
public enum AttributeGroup {

    /** {@code transform} and its two origins — what Free Transform writes. */
    TRANSFORM("Transform"),

    /** The box: size, spacing, flex, grid, position. */
    LAYOUT("Layout"),

    /** What it looks like rather than where it sits: background, borders, outline, opacity, masks. */
    APPEARANCE("Appearance"),

    /** Type: colour, family, size, weight, alignment, decoration. */
    TEXT("Text"),

    /** How it responds: cursor, overflow, scrolling, transitions, stacking. */
    BEHAVIOUR("Behaviour");

    /**
     * Where a section with this label belongs, for ordering the Paste Attributes window.
     *
     * <p>A label this enum does not know goes AFTER all of them, and those are the value-kind sections —
     * Glass, Gradient, Sprite — which a {@link com.crystalgui.serialization.style.StyleParts} names from
     * the value rather than from the property. The fixed taxonomy first and the value's own kinds after
     * it: a reader looking for {@code width} should not have to find Layout somewhere in the middle
     * because a gradient sorted above it.</p>
     *
     * <p>Unknown labels keep their arrival order among themselves rather than being sorted by name — a
     * heading is not the thing being looked up, and the sections then follow the properties.</p>
     */
    public static int orderOf(String label) {
        for (AttributeGroup group : values()) {
            if (group.label.equals(label)) return group.ordinal();
        }
        return values().length;
    }

    private final String label;

    AttributeGroup(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Which group a CSS property name belongs to. @see AttributeGroup */
    public static AttributeGroup of(String property) {
        if (property.startsWith("transform")) return TRANSFORM;
        if (property.equals("color") || property.startsWith("font-") || property.startsWith("text-")
                || property.equals("line-height") || property.equals("white-space")
                || property.equals("selection-color") || property.startsWith("caret-")) {
            return TEXT;
        }
        if (property.startsWith("background") || property.startsWith("backdrop-")
                || property.startsWith("border-")
                || property.startsWith("outline") || property.equals("opacity")
                || property.startsWith("mask") || property.startsWith("overlay")) {
            return APPEARANCE;
        }
        if (property.equals("cursor") || property.startsWith("overflow")
                || property.startsWith("scroll-") || property.equals("resize")
                || property.equals("transition") || property.equals("z-index")
                || property.startsWith("tooltip-")) {
            return BEHAVIOUR;
        }
        return LAYOUT;
    }
}
