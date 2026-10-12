package com.crystalgui.ui.contract;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.net.rate.CgRateGate;
import com.crystalgraphics.net.rate.CgRatePolicy;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgStateMap;

/**
 * One interaction a widget can report, declared once as a {@code static final} on the widget class.
 * {@code plan/engine-rewrite.md} M1.
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
public final class Event<W, P> {

    private final String kind;
    private final BiConsumer<W, Consumer<P>> attach;
    @Nullable
    private final Payload<P> payload;
    private final CgRatePolicy rate;

    @Nullable
    private final java.util.function.BiFunction<W, P, P> sanitize;

    /**
     * How a payload crosses the wire. Null for a kind that carries nothing.
     *
     * <p>An interface rather than a lambda pair for the same reason {@link StateType} is one: writing
     * needs to be generic in the ops type, and a generic method cannot be a lambda.</p>
     */
    public interface Payload<P> {
        <T> void write(CgStateMap<T> out, P value);

        <T> P read(CgStateMap<T> in);
    }

    private Event(String kind, BiConsumer<W, Consumer<P>> attach, @Nullable Payload<P> payload,
                  CgRatePolicy rate,
                  @Nullable java.util.function.BiFunction<W, P, P> sanitize) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.attach = Objects.requireNonNull(attach, "attach");
        this.payload = payload;
        this.rate = Objects.requireNonNull(rate, "rate");
        this.sanitize = sanitize;
    }

    /**
     * An event carrying a payload.
     *
     * @param attach how a <b>client</b> listens: given the widget and a sink, subscribe so that each
     *               occurrence hands the sink its payload
     */
    public static <W, P> Event<W, P> of(
            String kind, BiConsumer<W, Consumer<P>> attach, Payload<P> payload, CgRatePolicy rate) {
        return new Event<>(kind, attach, Objects.requireNonNull(payload, "payload"), rate, null);
    }

    /** An event carrying nothing — a press, a focus change. */
    public static <W> Event<W, Void> signal(String kind, BiConsumer<W, Runnable> attach) {
        return new Event<>(kind, (widget, sink) -> attach.accept(widget, () -> sink.accept(null)),
                null, CgRatePolicy.IMMEDIATE, null);
    }

    /**
     * How this widget makes an arriving payload <b>safe</b>, given the widget it arrived for.
     *
     * <p>Takes the widget because safety is usually a question about the widget's own configuration: a
     * slider's bounds and step, a text field's maximum length, a dropdown's option count. Nothing
     * outside the widget class knows those, which is precisely why validation cannot live in the
     * session.</p>
     *
     * <pre>{@code
     * Event.of("value", …, CgRatePolicy.DRAGGING).sanitizedBy((slider, v) -> slider.clampAndSnap(v));
     * }</pre>
     *
     * <p><b>Sanitize, do not reject.</b> A clamped value is a value the user could have produced with a
     * legal gesture, so the handler runs and the model stays sane; rejecting would need a second
     * channel to say why and would make every handler defensive. Refusal is reserved for what a legal
     * gesture could never do at all — a disabled element, an undeclared kind — which the session checks
     * and counts.</p>
     */
    public Event<W, P> sanitizedBy(java.util.function.BiFunction<W, P, P> sanitize) {
        return new Event<>(kind, attach, payload, rate, sanitize);
    }

    /** Applies {@link #sanitizedBy}, if this event has one. */
    public P sanitize(W widget, P value) {
        return sanitize == null ? value : sanitize.apply(widget, value);
    }

    public String kind() {
        return kind;
    }

    public CgRatePolicy rate() {
        return rate;
    }

    public boolean carriesPayload() {
        return payload != null;
    }

    /** Subscribes {@code sink} to this event on {@code widget}. Client side. */
    public void attach(W widget, Consumer<P> sink) {
        attach.accept(widget, sink);
    }

    /**
     * Subscribes {@code gate} to this event on {@code widget}, at the event's own {@link #rate()}. Client side.
     *
     * <pre>{@code
     * Slider.VALUE_CHANGED.attach(throughput, gate);   // dragging: at most every 50 ms, and where it was released
     * }</pre>
     */
    public void attach(W widget, CgRateGate<? super W, P> gate) {
        attach.accept(widget, value -> gate.offer(widget, kind, rate, value));
    }

    /** Encodes an occurrence, or answers null for a kind that carries nothing. */
    @Nullable
    public <T> CgStateMap<T> encode(CgDynamicOps<T> ops, P value) {
        if (payload == null) return null;
        CgStateMap<T> out = new CgStateMap<>(ops);
        payload.write(out, value);
        return out;
    }

    /** Decodes an occurrence. Server side. */
    @Nullable
    public <T> P decode(@Nullable CgStateMap<T> in) {
        if (payload == null || in == null) return null;
        return payload.read(in);
    }

    @Override
    public String toString() {
        return "Event[" + kind + " " + rate + "]";
    }
}
