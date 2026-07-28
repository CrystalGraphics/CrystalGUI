package com.crystalgui.net;

/**
 * The interaction names both halves of a session agree on.
 *
 * <p><b>Semantic, not physical.</b> There is no {@code mouse-down} here and there should not be: a
 * server that never lays anything out cannot interpret a coordinate, so an event carrying one would
 * be handing it a number it can only misuse. What travels is what the widget <em>meant</em> — the
 * button was pressed, the value became this — which is also what a handler actually wants.</p>
 *
 * <p>Plain strings rather than an enum, so a third-party widget can define its own kinds without
 * modifying this class.</p>
 */
public final class UiEventKinds {

    /** The user activated the element: a button press, a tab selection, a committed field. */
    public static final String ACTIVATE = "activate";

    /** A checkbox or switch flipped. Payload: {@code checked}. */
    public static final String TOGGLE = "toggle";

    /** A slider or scroller settled on a value. Payload: {@code value}. */
    public static final String VALUE = "value";

    /** A text field committed. Payload: {@code text}. */
    public static final String TEXT = "text";

    private UiEventKinds() {
    }
}
