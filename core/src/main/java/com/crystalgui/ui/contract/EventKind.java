package com.crystalgui.ui.contract;

/**
 * <b>Well-known kind names.</b> A convenience, not an authority. {@code plan_ui_rewrite.md} M1.
 *
 * <h3>An element owns its events; this class owns nothing</h3>
 *
 * <p>The kinds a widget can report are declared by the widget, as {@link Event} constants on its own
 * class, and {@code io.on(picker, ColorSelector.CHANGED, ...)} names one directly. So the ordinary way
 * to subscribe never mentions this class at all.</p>
 *
 * <p>What it is for is <b>shared spelling</b>. Several unrelated widgets have an "the user did the
 * thing" event, and it is worth them all calling it {@code activate}: a generic consumer -- a recorder,
 * a test harness, a future accessibility layer -- can then reason about a kind it has never seen the
 * widget for. That is a convention, and a convention is exactly as binding as its usefulness.</p>
 *
 * <p><b>A widget may mint its own, and nothing here has to change.</b> A kind is scoped to its element
 * on the wire (an id and a name), so a mod's {@code "scrub"} or {@code "reorder"} collides with nothing
 * and needs no entry below. That is the whole reason this stopped being the vocabulary: a central list
 * a third party cannot edit is a list a third party cannot use, which is the same trap
 * {@code BundledSources} records for namespace registration.</p>
 *
 * <p>It replaced {@code UiEventKinds}, which had four names for the five widgets wired at the time and
 * <em>was</em> the vocabulary -- so a {@code Dropdown} could not report what was chosen, a
 * {@code TabView} could not say which tab, and nothing could hear a key, a right-click or a drop. The
 * names below are a superset with identical strings, so nothing on the wire moved.</p>
 */
public final class EventKind {

    private EventKind() {
    }

    // ── The original four ────────────────────────────────────────────────────

    /** A button was pressed, or a menu item chosen. No payload. */
    public static final String ACTIVATE = "activate";

    /** A checkbox or switch flipped. Payload: {@code checked}. */
    public static final String TOGGLE = "toggle";

    /** A continuous value moved. Payload: {@code value}. */
    public static final String VALUE = "value";

    /** Text changed. Payload: {@code text}. */
    public static final String TEXT = "text";

    // ── Added at M1, for widgets that could not speak ────────────────────────

    /**
     * Something was chosen from a set — a dropdown option, a tab, a list row. Payload:
     * {@code index}, and {@code label} where the widget has one.
     *
     * <p>Distinct from {@link #ACTIVATE} on purpose: activating is "I pressed this", selecting is "the
     * selection is now that". A {@code TabView} has no press to report, and a {@code Dropdown} has
     * both.</p>
     */
    public static final String SELECT = "select";

    /**
     * An edit was <b>committed</b> rather than merely made — Enter in a field, focus leaving it, a
     * slider being released.
     *
     * <p>The pair {@link #TEXT}/{@code COMMIT} is what lets a server take a cheap running view of an
     * edit and an expensive one only when the user means it. {@code TextField.UpdateMode} already draws
     * this distinction locally and had no way to say it over a wire.</p>
     */
    public static final String COMMIT = "commit";

    /** A discrete value changed where there is no drag to throttle — a colour, a date, an enum. */
    public static final String CHANGE = "change";

    /** A key went down on a widget that asked for keys. Payload: {@code key}, {@code modifiers}. */
    public static final String KEY = "key";

    /** A pointer press. Payload: {@code button}, and {@code x}/{@code y} in the element's own space. */
    public static final String POINTER = "pointer";

    /** A wheel notch. Payload: {@code delta}. */
    public static final String WHEEL = "wheel";

    /** The widget took focus. No payload. */
    public static final String FOCUS = "focus";

    /** The widget lost focus. No payload. */
    public static final String BLUR = "blur";

    /** Something was dropped on the widget. Payload is the drag's own {@code StateMap}. */
    public static final String DROP = "drop";

    /** A drag started from the widget. Payload is the drag's own {@code StateMap}. */
    public static final String DRAG = "drag";

    /** A context menu was asked for. Payload: {@code x}/{@code y} in the element's own space. */
    public static final String CONTEXT_MENU = "contextMenu";

    /**
     * The widget was asked to close and the answer is the server's.
     *
     * <p>The veto path (network audit N4). Every other kind here is a notification; this one is a
     * <em>question</em>, and M4 is where the answer travels back.</p>
     */
    public static final String CLOSE_REQUESTED = "closeRequested";

    // ── Payload keys, so both halves spell them once ─────────────────────────

    public static final String PAYLOAD_CHECKED = "checked";
    public static final String PAYLOAD_VALUE = "value";
    public static final String PAYLOAD_TEXT = "text";
    public static final String PAYLOAD_INDEX = "index";
    public static final String PAYLOAD_LABEL = "label";
    public static final String PAYLOAD_COLOR = "color";
    public static final String PAYLOAD_WEIGHTS = "weights";
}
