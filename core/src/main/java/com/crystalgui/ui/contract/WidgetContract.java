package com.crystalgui.ui.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.dom.NodeContract;

/**
 * <b>What a kind of widget is</b>, as far as anything outside it needs to know: its name, the state it
 * carries, and the interactions it can report. {@code plan_ui_rewrite.md} M1, D7.
 *
 * <h3>One declaration, four readers</h3>
 *
 * <p>A widget's answer to "what do you carry" used to be given four times in four places — a
 * {@code writeState}/{@code readState} pair, a line in a coverage test's map, an
 * {@code addReportedEvent} call at the registration site, and an {@code instanceof} arm inside the
 * client session. Nothing linked them, so each could be right while another was silently absent, and
 * the failure mode is a widget that <em>arrives blank</em> rather than one that throws.</p>
 *
 * <p>A contract is that answer once. The description codec writes state from it, the client wires
 * listeners from it, the server validates against it, and the coverage test enumerates it.</p>
 *
 * <h3>It belongs to the CLASS</h3>
 *
 * <p>Which slots a {@code Slider} has is a fact about sliders. So a contract is a {@code static final}
 * on the widget, registered once in a static initialiser, and {@link WidgetContracts} caches by class
 * — never per instance, which is what the previous per-element {@code Set<String>} of reported events
 * was, on tens of thousands of elements so that a handful could report something.</p>
 *
 * @param <W> the widget type
 */
public final class WidgetContract<W extends UIElement> implements NodeContract {

    private final Class<W> type;
    private final String name;
    private final List<State<W, ?>> states;
    private final List<Event<W, ?>> events;
    private final boolean acceptsDescribedChildren;

    private WidgetContract(Class<W> type, String name, List<State<W, ?>> states,
                           List<Event<W, ?>> events, boolean acceptsDescribedChildren) {
        this.type = type;
        this.name = name;
        this.states = Collections.unmodifiableList(new ArrayList<>(states));
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.acceptsDescribedChildren = acceptsDescribedChildren;
    }

    public static <W extends UIElement> Builder<W> of(Class<W> type, String name) {
        return new Builder<>(type, name);
    }

    /** Fluent, because the ORDER slots are added in is the order they are applied. @see State */
    public static final class Builder<W extends UIElement> {
        private final Class<W> type;
        private final String name;
        private final List<State<W, ?>> states = new ArrayList<>();
        private final List<Event<W, ?>> events = new ArrayList<>();
        private boolean acceptsDescribedChildren;

        private Builder(Class<W> type, String name) {
            this.type = Objects.requireNonNull(type, "type");
            this.name = Objects.requireNonNull(name, "name");
        }

        public Builder<W> state(State<W, ?> slot) {
            for (State<W, ?> existing : states) {
                if (existing.key().equals(slot.key())) {
                    throw new IllegalArgumentException(
                            name + " declares the state key '" + slot.key() + "' twice. One of them would "
                                    + "silently overwrite the other on the wire.");
                }
            }
            states.add(slot);
            return this;
        }

        public Builder<W> event(Event<W, ?> event) {
            for (Event<W, ?> existing : events) {
                if (existing.kind().equals(event.kind())) {
                    throw new IllegalArgumentException(
                            name + " declares the event kind '" + event.kind() + "' twice; a client would "
                                    + "attach both and report every occurrence at least twice.");
                }
            }
            events.add(event);
            return this;
        }

        /** Whether a description may carry children for this kind. */
        public Builder<W> withDescribedChildren() {
            this.acceptsDescribedChildren = true;
            return this;
        }

        public WidgetContract<W> build() {
            return new WidgetContract<>(type, name, states, events, acceptsDescribedChildren);
        }
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    public Class<W> type() {
        return type;
    }

    /** The registered name this kind is described by — the tag. */
    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean acceptsDescribedChildren() {
        return acceptsDescribedChildren;
    }

    // ── State ────────────────────────────────────────────────────────────────

    public List<State<W, ?>> states() {
        return states;
    }

    @Override
    public boolean carriesState() {
        return !states.isEmpty();
    }

    /** Writes every slot, in declaration order. */
    public <T> void write(W widget, StateMap<T> out) {
        for (State<W, ?> slot : states) slot.write(widget, out);
    }

    /** Applies every slot, <b>in declaration order</b>, which for several widgets is load-bearing. */
    public <T> void read(W widget, StateMap<T> in) {
        for (State<W, ?> slot : states) slot.apply(widget, in);
    }

    // ── Events ───────────────────────────────────────────────────────────────

    public List<Event<W, ?>> events() {
        return events;
    }

    @Override
    public boolean reportsAnything() {
        return !events.isEmpty();
    }

    /** The kinds this widget <em>can</em> report — not what any instance was asked to. */
    @Override
    public Set<String> eventKinds() {
        Set<String> kinds = new LinkedHashSet<>();
        for (Event<W, ?> event : events) kinds.add(event.kind());
        return Collections.unmodifiableSet(kinds);
    }

    @Nullable
    public Event<W, ?> event(String kind) {
        for (Event<W, ?> event : events) {
            if (event.kind().equals(kind)) return event;
        }
        return null;
    }

    @Override
    public String toString() {
        return "WidgetContract[" + name + " states=" + states.size() + " events=" + eventKinds() + "]";
    }
}
