package com.crystalgui.ui.dom;

import java.util.Locale;

/**
 * What a kind of element is FOR, as far as its glyph's colour is concerned — the tint a tree row draws it in.
 *
 * <pre>{@code
 * KindInfo.named("Button").glyph(GlyphRole.CONTROL);
 * }</pre>
 *
 * <p>Six roles rather than a colour per kind, so a theme sets six tokens and a tree stays scannable. Each role
 * names a CSS class, {@link #cssClass()}, which a sheet colours from {@code --kind-glyph-<role>}.</p>
 */
public enum GlyphRole {

    /** Containers and structure: layout, split views, tabs, scrolling. */
    LAYOUT,

    /** Text and what displays rather than takes input: labels, markup, icons, form notes. */
    TEXT,

    /** Anything that takes input: buttons, toggles, fields. */
    CONTROL,

    /** Collections and composites: lists, trees, tables, forms, pickers, template instances. */
    COLLECTION,

    /** What opens over everything else: popovers, menus, dialogs, tooltips. */
    OVERLAY,

    /** A kind some other mod added and said nothing about. */
    ADDON;

    /** {@code __glyph-control__} — the class a row's glyph carries for this role. */
    public String cssClass() {
        return "__glyph-" + name().toLowerCase(Locale.ROOT) + "__";
    }
}
