package com.crystalgui.net;

/**
 * <b>Things a server can ask a client's VIEW to do</b>, which are not changes to the tree.
 *
 * <h3>Why these are not state</h3>
 *
 * <p>Everything else a server sends describes what the UI <em>is</em>: this widget's text, that
 * subtree's shape. These describe what should <em>happen</em> — put the caret in that field, scroll
 * that row into view, show that dialog. The difference is not stylistic:</p>
 *
 * <ul>
 *   <li><b>They are not idempotent and must not be replayed.</b> A state delta can be re-sent and a
 *       late viewer can be re-described, because applying the same state twice lands in the same
 *       place. Focusing twice does not — the second one steals focus from wherever the user has since
 *       put it.</li>
 *   <li><b>They have no resting value.</b> There is no "is focused" for a description to carry: the
 *       tree records that a field exists, not that a moment ago somebody was asked to focus it.</li>
 *   <li><b>They are a request, not an instruction.</b> A client may be showing the window minimised, or
 *       on a host with no notion of a tooltip. Ignoring one is legal and silent by design.</li>
 * </ul>
 *
 * <p>So they travel as their own message, are never part of a description, and are dropped rather than
 * queued when a viewer is not watching. A focus request held for ten seconds and delivered on unhide
 * would move the caret out from under somebody's hands.</p>
 *
 * <h3>The vocabulary is closed on purpose</h3>
 *
 * <p>A server naming an arbitrary method the client would then look up is the shape that makes a
 * remote UI a remote-code surface. These are the operations a client has agreed in advance to perform;
 * anything not on this list is refused and counted like any other bad message.</p>
 */
public final class ViewCommand {

    private ViewCommand() {
    }

    // ── The command, and who it is about ─────────────────────────────────────

    /** Which command. One of the constants below; anything else is refused. */
    public static final String CMD = "cmd";

    /** The element it is about, for the ones that name one. Absent for window-level commands. */
    public static final String NID = "nid";

    /** A second element, for a command that relates two — an anchor, a target. */
    public static final String ANCHOR = "anchor";

    /** Free text: a tooltip's words, a title, a notification's message. */
    public static final String TEXT = "text";

    /** A severity or kind, where the command has one. @see #NOTIFY */
    public static final String LEVEL = "level";

    public static final String WIDTH = "w";
    public static final String HEIGHT = "h";

    // ── Element-level ────────────────────────────────────────────────────────

    /**
     * Put keyboard focus on this element.
     *
     * <p>Uses the client's <b>programmatic</b> focus path, so it rings — {@code :focus-visible} exists
     * to mark focus the user did not place with a pointer, and focus arriving from a server is the
     * clearest case of that there is.</p>
     */
    public static final String FOCUS = "focus";

    /** Scroll this element into view, honouring whatever scroll behaviour the sheet asked for. */
    public static final String SCROLL_INTO_VIEW = "scrollIntoView";

    /** Show this {@code Dialog}. Modal or not is the dialog's own business, already in the tree. */
    public static final String SHOW_DIALOG = "showDialog";

    /** Hide this {@code Dialog}. Silent if it was not showing. */
    public static final String HIDE_DIALOG = "hideDialog";

    /** Open this {@code Popover}/{@code Menu}, anchored to {@link #ANCHOR} when one is named. */
    public static final String OPEN_MENU = "openMenu";

    /**
     * Show a tooltip on this element.
     *
     * <p>Text rather than a tree, because a tooltip is a sentence about a control — and because a
     * server able to graft an arbitrary subtree at a screen position is a different feature with a
     * different threat model.</p>
     */
    public static final String TOOLTIP = "tooltip";

    // ── Window-level ─────────────────────────────────────────────────────────

    /** Rename the window. What a frame's caption shows and what a taskbar entry reads. */
    public static final String SET_TITLE = "setTitle";

    /** Change the window's icon, named the way a sprite is: {@code "namespace:name"}. */
    public static final String SET_ICON = "setIcon";

    /**
     * Suggest a size.
     *
     * <p>A <b>hint</b>, and named so. Where the window goes and how big it is belongs to the client's
     * compositor and to the person using it — a server that could place windows could also cover the
     * screen with one. A host is free to clamp it, ignore it, or apply it only on first open.</p>
     */
    public static final String GEOMETRY_HINT = "geometryHint";

    /**
     * Say something to the user that is not part of the window.
     *
     * <p>Goes to the host's own notification surface rather than into the tree, so it survives the
     * window being closed and does not need a place in the layout to exist.</p>
     */
    public static final String NOTIFY = "notify";

    /** {@link #LEVEL} values for {@link #NOTIFY}. A host that knows none of them may treat all alike. */
    public static final String LEVEL_INFO = "info";
    public static final String LEVEL_WARN = "warn";
    public static final String LEVEL_ERROR = "error";

    /** Every command name, for validating one that arrived. */
    public static final java.util.Set<String> ALL = java.util.Collections.unmodifiableSet(
            new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                    FOCUS, SCROLL_INTO_VIEW, SHOW_DIALOG, HIDE_DIALOG, OPEN_MENU, TOOLTIP,
                    SET_TITLE, SET_ICON, GEOMETRY_HINT, NOTIFY)));
}
