package com.crystalgui.ui.service;

import javax.annotation.Nullable;

import com.crystalgui.render.texture.CgUiDrawable;

/**
 * What a drag looks like under the pointer: an icon, a label and a style, as values. The drag owns it and the host draws
 * it, so it is never a widget's own element and never cut off at a window's edge.
 *
 * <pre>{@code
 * Drag.start(row, x, y, button, payload, Drag.DEFAULT_THRESHOLD_PX, listener)
 *         .preview(DragPreview.of("crystalgui:folder", "Problems").styled("__row-ghost__"));
 *
 * // a tab or a rail button is carried at its own size, from where it was grabbed
 * DragPreview.of(icon, null).styled("__tab-ghost__").grabbing();
 *
 * // a target rewrites the label as the pointer crosses it
 * preview.text("Move to Bottom Left").flipped(true);
 * }</pre>
 *
 * <ul>
 *   <li>The host is the shell while the drag's document is a window's client, else the document itself.</li>
 *   <li>Any thread may change it; the host reads the latest on its next frame.</li>
 *   <li>It carries no element, so there is nothing to keep in a tree and nothing to park.</li>
 * </ul>
 */
public final class DragPreview {

    private volatile @Nullable String iconName;
    private volatile @Nullable CgUiDrawable glyph;
    private volatile int tint = 0xFFFFFFFF;
    private volatile String label = "";
    private volatile String style = "";
    private volatile boolean grabbing;
    private volatile boolean flipped;
    private volatile float grabX, grabY;
    private volatile int version;

    private DragPreview() {
    }

    /** An icon by name ({@code null} for none) and a label ({@code null} or empty for none). */
    public static DragPreview of(@Nullable String iconName, @Nullable String label) {
        DragPreview preview = new DragPreview();
        preview.iconName = iconName;
        preview.label = label == null ? "" : label;
        return preview;
    }

    /** A picture already resolved, with the colour it is multiplied by ({@code 0xFFFFFFFF} draws it as authored). */
    public static DragPreview of(@Nullable CgUiDrawable glyph, int tint, @Nullable String label) {
        DragPreview preview = new DragPreview();
        preview.glyph = glyph;
        preview.tint = tint;
        preview.label = label == null ? "" : label;
        return preview;
    }

    /** Just a label, for a drag with no one icon: {@code "3 items"}. */
    public static DragPreview of(String label) {
        return of((String) null, label);
    }

    /** A style class on the ghost, so a sheet shapes it: a capsule, a tab, a chip. */
    public DragPreview styled(String cssClass) {
        style = cssClass == null ? "" : cssClass;
        version++;
        return this;
    }

    /**
     * Keeps where the source was grabbed, so the ghost sits where the source was: right when it is the thing being moved
     * at its own size (a tab, a rail button). The default sits below and right of the pointer, right for a stand-in
     * (a file row, "3 items").
     */
    public DragPreview grabbing() {
        grabbing = true;
        version++;
        return this;
    }

    /** Rewrites the label mid-drag; blank leaves the icon alone. */
    public DragPreview text(@Nullable String value) {
        label = value == null ? "" : value;
        version++;
        return this;
    }

    /** Puts the label on the left of the icon, for a ghost near a right-hand edge. */
    public DragPreview flipped(boolean value) {
        if (flipped != value) {
            flipped = value;
            version++;
        }
        return this;
    }

    @Nullable
    public String iconName() {
        return iconName;
    }

    @Nullable
    public CgUiDrawable glyph() {
        return glyph;
    }

    public int tint() {
        return tint;
    }

    public String label() {
        return label;
    }

    public String style() {
        return style;
    }

    public boolean isGrabbing() {
        return grabbing;
    }

    public boolean isFlipped() {
        return flipped;
    }

    /** Where the source was grabbed within it, in surface pixels; zero unless {@link #grabbing}. Set by the drag. */
    public float grabX() {
        return grabbing ? grabX : 0f;
    }

    public float grabY() {
        return grabbing ? grabY : 0f;
    }

    void grabbedAt(float x, float y) {
        grabX = x;
        grabY = y;
    }

    /** Changes with every edit, so a host redraws only what moved. */
    public int version() {
        return version;
    }
}
