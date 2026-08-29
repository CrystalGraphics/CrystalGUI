package com.crystalgui.ui.elements.config;

import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.StateType;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;

/**
 * One contract shape for every {@link ValueControl}. {@code plan_ui_rewrite.md} M1.
 *
 * <h3>Why these get contracts at all</h3>
 *
 * <p>Because they are the widgets the whole networked-UI layer exists for. A machine's configuration
 * panel, a block's settings, a mod's options screen — every one of them is a server that owns some
 * values and a client that shows them, which is exactly what a {@code ValueControl} is. They were the
 * largest group of widgets that could carry nothing over a wire.</p>
 *
 * <h3>Why one factory rather than thirteen declarations</h3>
 *
 * <p>{@link ConfigControl} already gives every control a uniform surface — {@code getValueObject},
 * {@code setValueObject}, and a {@code changed} signal — because the Inspector binds them generically.
 * So the only thing that differs between a {@code NumberControl} and a {@code ColorControl} is how
 * their value crosses a {@link StateMap}, which is precisely what a {@link StateType} is. Writing the
 * same twelve-line declaration thirteen times would be thirteen places to get the event wiring subtly
 * different.</p>
 *
 * <p><b>Registered per concrete class, not on {@code ValueControl}</b>, because
 * {@link WidgetContracts#of} is an exact-class lookup — the same rule {@code tagName()} follows, and
 * for the same reason: a subclass silently inheriting its parent's answer is how
 * {@code ToolWindowFrame} came to match no rule in the stylesheet.</p>
 */
public final class ConfigControlContracts {

    private ConfigControlContracts() {
    }

    /**
     * The "this control's value changed" event, as a constant the control can expose.
     *
     * <p>Declared by the control rather than built inside {@link #register} so that
     * {@code io.on(control, NumberControl.CHANGED, (ctx, value) -> ...)} is spellable. An event that
     * exists only inside a contract can be dispatched but not <b>named</b>, which leaves the typed
     * subscription unusable for the one family of widgets a served settings panel is entirely made
     * of.</p>
     *
     * @param rate {@link RatePolicy#DRAGGING} for anything with a drag behind it (a slider, a colour
     *             wheel), {@link RatePolicy#TYPING} for something typed into,
     *             {@link RatePolicy#IMMEDIATE} for a discrete choice. The control knows its own tempo;
     *             a handler cannot.
     */
    @SuppressWarnings("unchecked")
    public static <C extends ConfigControl, V> Event<C, V> changed(
            StateType<V> valueType, V fallback, RatePolicy rate) {
        return Event.of("change",
                (control, sink) -> control.changed.connect(raw -> sink.accept((V) raw)),
                new Event.Payload<V>() {
                    @Override public <T> void write(StateMap<T> out, V raw) {
                        valueType.put(out, "value", raw);
                    }
                    @Override public <T> V read(StateMap<T> in) {
                        return valueType.get(in, "value", fallback);
                    }
                }, rate);
    }

    /**
     * A contract for one control kind: its value, and the fact that the value changed.
     *
     * <p>{@link ConfigControl} already gives every control a uniform surface -- {@code getValueObject},
     * {@code setValueObject}, and a {@code changed} signal -- because the Inspector binds them
     * generically. So the only thing that differs between a {@code NumberControl} and a
     * {@code ColorControl} is how its value crosses a {@link StateMap}, which is what a
     * {@link StateType} is. Writing the same declaration thirteen times would be thirteen places to
     * get the event wiring subtly different.</p>
     */
    @SuppressWarnings("unchecked")
    public static <C extends ConfigControl, V> WidgetContract<C> register(
            Class<C> type, String name, StateType<V> valueType, V fallback, Event<C, V> changed) {

        State<C, V> value = State.of("value", valueType,
                control -> (V) control.getValueObject(),
                (control, next) -> control.setValueObject(next),
                fallback);

        return WidgetContracts.register(WidgetContract.of(type, name)
                .state(value)
                .event(changed)
                .build());
    }
}
