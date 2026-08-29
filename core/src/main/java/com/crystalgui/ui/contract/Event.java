package com.crystalgui.ui.contract;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;

/**
 * One interaction a widget can report, declared once as a {@code static final} on the widget class.
 * {@code plan_ui_rewrite.md} M1.
 *
 * <h3>The listener lives here, and that is the point</h3>
 *
 * <p>{@code ClientUiSession.wireReportedEvents} used to be a {@code switch} over kind names holding an
 * {@code instanceof} chain per case — the session knew how to listen to a {@code Slider} because
 * somebody had written {@code if (element instanceof Slider slider)} inside it. Two things follow and
 * both were real: a new widget could declare a kind and be silently ignored (the {@code default} arm
 * logs and moves on), and the networking layer had to import every widget it could hear from.</p>
 *
 * <p>{@link #attach} inverts it. The widget says how to listen to itself, the session just calls it,
 * and adding a reportable widget touches the widget alone.</p>
 *
 * @param <W> the widget type
 * @param <P> the payload type, or {@link Void} for a kind that carries nothing
 */
public final class Event<W extends UIElement, P> {

    private final String kind;
    private final BiConsumer<W, Consumer<P>> attach;
    @Nullable
    private final Payload<P> payload;
    private final RatePolicy rate;

    /**
     * How a payload crosses the wire. Null for a kind that carries nothing.
     *
     * <p>An interface rather than a lambda pair for the same reason {@link StateType} is one: writing
     * needs to be generic in the ops type, and a generic method cannot be a lambda.</p>
     */
    public interface Payload<P> {
        <T> void write(StateMap<T> out, P value);

        <T> P read(StateMap<T> in);
    }

    private Event(String kind, BiConsumer<W, Consumer<P>> attach, @Nullable Payload<P> payload,
                  RatePolicy rate) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.attach = Objects.requireNonNull(attach, "attach");
        this.payload = payload;
        this.rate = Objects.requireNonNull(rate, "rate");
    }

    /**
     * An event carrying a payload.
     *
     * @param attach how a <b>client</b> listens: given the widget and a sink, subscribe so that each
     *               occurrence hands the sink its payload
     */
    public static <W extends UIElement, P> Event<W, P> of(
            String kind, BiConsumer<W, Consumer<P>> attach, Payload<P> payload, RatePolicy rate) {
        return new Event<>(kind, attach, Objects.requireNonNull(payload, "payload"), rate);
    }

    /** An event carrying nothing — a press, a focus change. */
    public static <W extends UIElement> Event<W, Void> signal(String kind, BiConsumer<W, Runnable> attach) {
        return new Event<>(kind, (widget, sink) -> attach.accept(widget, () -> sink.accept(null)),
                null, RatePolicy.IMMEDIATE);
    }

    public String kind() {
        return kind;
    }

    public RatePolicy rate() {
        return rate;
    }

    public boolean carriesPayload() {
        return payload != null;
    }

    /** Subscribes {@code sink} to this event on {@code widget}. Client side. */
    public void attach(W widget, Consumer<P> sink) {
        attach.accept(widget, sink);
    }

    /** Encodes an occurrence, or answers null for a kind that carries nothing. */
    @Nullable
    public <T> StateMap<T> encode(DynamicOps<T> ops, P value) {
        if (payload == null) return null;
        StateMap<T> out = new StateMap<>(ops);
        payload.write(out, value);
        return out;
    }

    /** Decodes an occurrence. Server side. */
    @Nullable
    public <T> P decode(@Nullable StateMap<T> in) {
        if (payload == null || in == null) return null;
        return payload.read(in);
    }

    @Override
    public String toString() {
        return "Event[" + kind + " " + rate + "]";
    }
}
