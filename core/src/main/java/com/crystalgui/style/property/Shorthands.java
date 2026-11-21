package com.crystalgui.style.property;

import com.crystalgui.style.property.layout.FlexShorthand;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.BackgroundShorthand;
import com.crystalgui.style.property.visual.OutlineShorthand;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.style.property.visual.border.BorderRadiusShorthand;
import com.crystalgui.style.property.visual.border.BorderShorthand;
import com.crystalgui.style.property.visual.text.TextStrokeShorthand;
import com.crystalgui.style.property.visual.transform.TransformOriginShorthand;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.crystalgui.style.property.StylePropertyRegistry.*;
import static com.crystalgui.style.property.layout.LayoutProperties.*;

/**
 * Every shorthand a sheet may write, by name. {@code DeclarationParser} expands through {@link #byName}
 * and {@code transition} animates a shorthand's longhands through {@link #covers}.
 *
 * <p>A new shorthand is one line in {@link #ALL}.</p>
 */
public final class Shorthands {

    private static final List<Shorthand> ALL = List.of(
            new EdgeShorthand("margin", MARGIN_TOP, MARGIN_RIGHT, MARGIN_BOTTOM, MARGIN_LEFT),
            new PairShorthand("margin-inline", MARGIN_LEFT, MARGIN_RIGHT),
            new PairShorthand("margin-block", MARGIN_TOP, MARGIN_BOTTOM),
            new EdgeShorthand("padding", PADDING_TOP, PADDING_RIGHT, PADDING_BOTTOM, PADDING_LEFT),
            new PairShorthand("padding-inline", PADDING_LEFT, PADDING_RIGHT),
            new PairShorthand("padding-block", PADDING_TOP, PADDING_BOTTOM),
            new EdgeShorthand("border-width", BORDER_TOP, BORDER_RIGHT, BORDER_BOTTOM, BORDER_LEFT),
            new EdgeShorthand("border-style", BORDER_TOP_STYLE, BORDER_RIGHT_STYLE, BORDER_BOTTOM_STYLE,
                    BORDER_LEFT_STYLE),
            new EdgeShorthand("border-color", BORDER_TOP_COLOR, BORDER_RIGHT_COLOR, BORDER_BOTTOM_COLOR,
                    BORDER_LEFT_COLOR),
            new BorderShorthand("border",
                    List.of(BORDER_TOP, BORDER_RIGHT, BORDER_BOTTOM, BORDER_LEFT),
                    List.of(BORDER_TOP_STYLE, BORDER_RIGHT_STYLE, BORDER_BOTTOM_STYLE, BORDER_LEFT_STYLE),
                    List.of(BORDER_TOP_COLOR, BORDER_RIGHT_COLOR, BORDER_BOTTOM_COLOR, BORDER_LEFT_COLOR)),
            new BorderShorthand("border-top", List.of(BORDER_TOP), List.of(BORDER_TOP_STYLE), List.of(BORDER_TOP_COLOR)),
            new BorderShorthand("border-right", List.of(BORDER_RIGHT), List.of(BORDER_RIGHT_STYLE),
                    List.of(BORDER_RIGHT_COLOR)),
            new BorderShorthand("border-bottom", List.of(BORDER_BOTTOM), List.of(BORDER_BOTTOM_STYLE),
                    List.of(BORDER_BOTTOM_COLOR)),
            new BorderShorthand("border-left", List.of(BORDER_LEFT), List.of(BORDER_LEFT_STYLE),
                    List.of(BORDER_LEFT_COLOR)),
            new BorderRadiusShorthand(),
            new PairShorthand("border-top-left-radius", BorderRadiusProperties.TOP_LEFT_X, BorderRadiusProperties.TOP_LEFT_Y),
            new PairShorthand("border-top-right-radius", BorderRadiusProperties.TOP_RIGHT_X,
                    BorderRadiusProperties.TOP_RIGHT_Y),
            new PairShorthand("border-bottom-right-radius", BorderRadiusProperties.BOTTOM_RIGHT_X,
                    BorderRadiusProperties.BOTTOM_RIGHT_Y),
            new PairShorthand("border-bottom-left-radius", BorderRadiusProperties.BOTTOM_LEFT_X,
                    BorderRadiusProperties.BOTTOM_LEFT_Y),
            new EdgeShorthand("outline-offset", OUTLINE_OFFSET_TOP, OUTLINE_OFFSET_RIGHT, OUTLINE_OFFSET_BOTTOM,
                    OUTLINE_OFFSET_LEFT),
            new OutlineShorthand(),
            new BackgroundShorthand(),
            new PairShorthand("overflow", OVERFLOW_X, OVERFLOW_Y),
            new FlexShorthand(),
            new TransformOriginShorthand(),
            new PairShorthand("text-offset", TEXT_OFFSET_X, TEXT_OFFSET_Y, "0"),
            new TextStrokeShorthand());

    private static final Map<String, Shorthand> BY_NAME = new HashMap<>();
    static {
        for (Shorthand s : ALL) BY_NAME.put(s.name(), s);
    }

    private Shorthands() {
    }

    /** The shorthand a sheet writes as {@code name}, or null when {@code name} is not one. */
    public static @Nullable Shorthand byName(String name) {
        return BY_NAME.get(name);
    }

    /** Whether {@code transition: <name> ...} animates {@code longhand}. */
    public static boolean covers(String name, StyleProperty<?> longhand) {
        Shorthand s = BY_NAME.get(name);
        return s != null && s.longhands().contains(longhand);
    }

    /** Every shorthand, for tools that list what a sheet may write. */
    public static List<Shorthand> all() {
        return ALL;
    }
}
