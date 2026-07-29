package com.crystalgui.style.property.visual;

/**
 * CSS-facing {@code cursor:} value — what the pointer should look like over an element.
 *
 * <p>A port of <a href="https://www.w3.org/TR/css-ui-4/#cursor">CSS Basic User Interface L4</a>. The
 * full keyword set is present even though no platform can present all of it, for the same reason
 * {@code text-shadow} is registered without being consumed: a stylesheet must be able to declare a
 * standard property without tripping a warning, and the alternative — a curated subset — makes the
 * omissions invisible until someone hits one.</p>
 *
 * <h3>Which cursor an element gets is resolved, not just read</h3>
 * <p>{@code cursor} <b>inherits</b> (initial value {@link #AUTO}), so the cascade does most of the
 * work: a container can set one for its whole subtree. {@link #AUTO} then has a context rule the spec
 * spells out — "behaves as {@code text} over selectable text or editable elements, and {@code default}
 * otherwise" — which {@code UIInputHandler} implements against
 * {@code UIElement.consumesTextInput()}.</p>
 *
 * <h3>Presentation is a platform concern</h3>
 * <p>Nothing here draws anything. Resolving a cursor and <em>showing</em> one are different jobs, and
 * the second is loader-specific to an awkward degree: LWJGL3/GLFW ships standard system cursors
 * including the resize set, while LWJGL2 — which both the harness and MC 1.7.10 use — has no standard
 * cursors at all and can only build custom ones from pixel data. So the engine resolves and hands the
 * answer to {@code UICursorService}, whose default is a no-op.</p>
 */
public enum Cursor {

    // ── General purpose ─────────────────────────────────────────────────────
    /** The initial value. Context-dependent: {@code text} over editable content, {@code default}
     * elsewhere — resolved by the input handler, never presented directly. */
    AUTO("auto"),
    DEFAULT("default"),
    NONE("none"),

    // ── Links and status ────────────────────────────────────────────────────
    CONTEXT_MENU("context-menu"),
    HELP("help"),
    POINTER("pointer"),
    PROGRESS("progress"),
    WAIT("wait"),

    // ── Selection ───────────────────────────────────────────────────────────
    CELL("cell"),
    CROSSHAIR("crosshair"),
    TEXT("text"),
    VERTICAL_TEXT("vertical-text"),

    // ── Drag and drop ───────────────────────────────────────────────────────
    ALIAS("alias"),
    COPY("copy"),
    MOVE("move"),
    NO_DROP("no-drop"),
    NOT_ALLOWED("not-allowed"),
    GRAB("grab"),
    GRABBING("grabbing"),

    // ── Resizing and scrolling ──────────────────────────────────────────────
    E_RESIZE("e-resize"),
    N_RESIZE("n-resize"),
    NE_RESIZE("ne-resize"),
    NW_RESIZE("nw-resize"),
    S_RESIZE("s-resize"),
    SE_RESIZE("se-resize"),
    SW_RESIZE("sw-resize"),
    W_RESIZE("w-resize"),
    /** Bidirectional — the ones a resize handle actually wants, since a handle resizes both ways. */
    EW_RESIZE("ew-resize"),
    NS_RESIZE("ns-resize"),
    NESW_RESIZE("nesw-resize"),
    NWSE_RESIZE("nwse-resize"),
    COL_RESIZE("col-resize"),
    ROW_RESIZE("row-resize"),
    ALL_SCROLL("all-scroll"),

    // ── Zooming ─────────────────────────────────────────────────────────────
    ZOOM_IN("zoom-in"),
    ZOOM_OUT("zoom-out");

    /** The CSS keyword. Kept because the enum name cannot spell it — {@code EW_RESIZE} is
     * {@code ew-resize}, and a platform mapping table is far easier to read against the real names. */
    public final String cssName;

    Cursor(String cssName) {
        this.cssName = cssName;
    }

    /** True for the values that only make sense after resolution — see {@link #AUTO}. */
    public boolean needsResolution() {
        return this == AUTO;
    }
}
