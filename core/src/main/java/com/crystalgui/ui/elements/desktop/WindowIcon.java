package com.crystalgui.ui.elements.desktop;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;
import org.jetbrains.annotations.Nullable;

/**
 * <b>A window's icon as a TILE</b> — the one drawing of it the taskbar entry, the hover preview and the
 * switcher tile all share.
 *
 * <h3>Why a tile and not the glyph</h3>
 *
 * <p>Every OS taskbar is carried by its icons, and every one of them can be because each application
 * ships a filled, branded, coloured mark drawn for exactly that size. What this engine has is a handful
 * of Feather <em>chrome marks</em> — stroked outlines in {@code currentColor}, drawn for a button face.
 * Put those in a strip as they are and three windows read as three copies of {@code <>}: an outline is
 * a mark <em>on</em> something, never a thing in its own right. So the slot gives it the something —
 * a filled rounded square with the glyph knocked out in the on-accent colour — which is how VS Code's,
 * IntelliJ's and every Fluent app icon reads at taskbar size, and it is the shape a real per-app icon
 * drops into later without the layout moving.</p>
 *
 * <h3>Same icon, same colour</h3>
 *
 * <p>The tile's hue is chosen from the icon <em>name</em>, so every window declaring {@code code} shares
 * one tile and every {@code package} another. That is Windows' "these are the same application"
 * semantics without its grouping — and it is deliberately not keyed on the title, which for a torn-out
 * editor window changes with every tab. The palette lives in the sheet as {@code __tile-N__} classes;
 * Java picks an index and names no colour, per the no-colours-in-widgets rule.</p>
 *
 * <h3>No icon → a monogram</h3>
 *
 * <p>A window that declares nothing gets its title's first letter on a neutral tile, which is what every
 * modern shell does for a missing icon (Android, Teams, GNOME's app grid). A blank slot reads as a
 * window somebody forgot to finish; a letter reads as a window.</p>
 *
 * <p><b>Built entirely in the constructor</b> — the monogram exists from the start and is shown or
 * hidden — because adding a child later inserts a Taffy node into a parent that may be mid-registration,
 * the crash {@code UIElement.taffyChildIndex} is named after.</p>
 */
public class WindowIcon extends UIElement {

    /** On the element. Sizes come from the context class beside it ({@code __icon__}, {@code __pre-icon__}). */
    public static final String ICON_CLASS = "__window-icon__";
    /** The letter drawn when there is no icon. */
    public static final String MONOGRAM_CLASS = "__monogram__";
    /** The neutral tile a monogram sits on. */
    public static final String MONO_TILE_CLASS = "__tile-mono__";
    /** {@code __tile-1__} … {@code __tile-N__}: the palette, one class per hue. */
    public static final String TILE_CLASS_PREFIX = "__tile-";
    /** How many hues the sheet defines. Kept small so two windows rarely share one by accident of hashing. */
    public static final int PALETTE_SIZE = 6;

    private final UIText monogram = new UIText("");
    private boolean showingMonogram;
    @Nullable
    private String tileClass;

    public WindowIcon() {
        addClass(ICON_CLASS);
        // UNHITTABLE, like every composite part: a hittable icon would swallow the press meant for the
        // entry, the preview or the tile it sits in.
        setHitTest(false);
        monogram.addClass(MONOGRAM_CLASS);
        monogram.setHitTest(false);
        monogram.setDisplayed(false);
        addInternalChild(monogram);
        setTile(MONO_TILE_CLASS);
    }

    /**
     * Shows {@code iconName}'s glyph on its tile, or {@code title}'s initial on the neutral one.
     *
     * <p>Through {@link CgUiSvg#ofIcon}, never {@code of(path)} — that is what binds the light/dark
     * variant at draw time, and the one time a caller reached past it every {@code icon()} in every
     * stylesheet drew the light file forever.</p>
     */
    public WindowIcon show(@Nullable String iconName, @Nullable String title) {
        CgUiSvg glyph = iconName == null ? null : CgUiSvg.ofIcon(iconName);
        if (glyph != null) {
            StyleGroup.defaultPipeline(getStyle().getGeneralGroup(), g -> g.overlay(glyph));
            if (showingMonogram) monogram.setDisplayed(false);
            showingMonogram = false;
            setTile(TILE_CLASS_PREFIX + paletteIndexOf(iconName) + "__");
        } else {
            // THE OLD GLYPH MUST GO. An overlay is a cascade candidate at DEFAULT origin and outlives the
            // icon it was set for, so a window that loses its icon would keep drawing it under the letter.
            StyleGroup.defaultPipeline(getStyle().getGeneralGroup(), g -> g.overlay(CgUiDrawable.EMPTY));
            monogram.setText(initialOf(title));
            if (!showingMonogram) monogram.setDisplayed(true);
            showingMonogram = true;
            setTile(MONO_TILE_CLASS);
        }
        return this;
    }

    /** {@code 1..PALETTE_SIZE}, stable across runs — {@code String.hashCode} is specified, not incidental. */
    public static int paletteIndexOf(String iconName) {
        return Math.floorMod(iconName.hashCode(), PALETTE_SIZE) + 1;
    }

    /** The first letter that is not whitespace, upper-cased; empty for an empty title. */
    static String initialOf(@Nullable String title) {
        if (title == null) return "";
        String trimmed = title.trim();
        if (trimmed.isEmpty()) return "";
        int first = trimmed.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(first)));
    }

    /** SWAPS the palette class rather than adding it — a recycled element must never wear two hues. */
    private void setTile(String cls) {
        if (cls.equals(tileClass)) return;
        if (tileClass != null) removeClass(tileClass);
        tileClass = cls;
        addClass(cls);
    }

    /** The palette class currently worn — for tests, which have no other way to read the hue. */
    @Nullable
    public String tileClass() {
        return tileClass;
    }

    /** What the tile is showing as a letter, or empty while it shows a glyph. */
    public String monogram() {
        return showingMonogram ? monogram.getText() : "";
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }
}
