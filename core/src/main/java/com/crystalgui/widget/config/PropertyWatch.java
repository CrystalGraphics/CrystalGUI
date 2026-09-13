package com.crystalgui.widget.config;

import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

import java.util.Objects;

/**
 * <b>Keeps an element following a {@link Property} for as long as the element is on screen.</b>
 *
 * <p>Whatever the property is — stored, derived from a model that announces itself, or derived from one
 * that has to be polled — the owner hears one thing: the value moved.</p>
 *
 * <pre>{@code
 * // in the owner's constructor
 * PropertyWatch watch = new PropertyWatch(this, size, (was, now) -> readout.setText(now));
 * whileConnected(watch::start);      // live while attached, re-read on every attach
 *
 * watch.retarget(otherSize);         // later: follow something else from now on
 * }</pre>
 *
 * <ul>
 *   <li>A polled property is refreshed <b>after layout</b> on every frame the element has a box, so a value
 *       read off a laid-out box is this frame's, and a field in a collapsed group or a hidden page costs
 *       nothing until it is shown — when the first poll reports whatever moved meanwhile.</li>
 *   <li>Coming back into a tree re-reads first: whatever moved while nothing was listening is reported
 *       then.</li>
 *   <li>Nothing is followed while detached — there is nothing on screen to keep current.</li>
 * </ul>
 */
public final class PropertyWatch {

    private final UIElement owner;

    private final Signal.Pair.Listener<Object, Object> onMoved;

    private Property<?> property;

    private Connection changed = Connection.DISCONNECTED;

    private Connection source = Connection.DISCONNECTED;

    /** Bumped on every start and stop, so a poll registered by an earlier start knows to end. */
    private int generation;

    private boolean running;

    @SuppressWarnings("unchecked")
    public <T> PropertyWatch(UIElement owner, Property<T> property, Signal.Pair.Listener<T, T> onMoved) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.property = Objects.requireNonNull(property, "property");
        this.onMoved = (Signal.Pair.Listener<Object, Object>) (Signal.Pair.Listener<?, ?>) onMoved;
    }

    /** The property being followed. */
    public Property<?> property() {
        return property;
    }

    /** Starts following; for the owner's {@code whileConnected}, whose connection is what stops it. */
    @SuppressWarnings("unchecked")
    public Connection start() {
        stop();
        running = true;
        int mine = ++generation;
        changed = ((Property<Object>) property).changed.connect(onMoved);
        source = property.watchSource();
        property.refresh();
        UIDocument window = owner.document();
        if (property.isPolled() && window != null) {
            Property<?> polled = property;
            window.animation().afterLayout(owner, delta -> {
                if (mine != generation) return false;
                if (owner.box() != null) polled.refresh();
                return true;
            });
        }
        return this::stop;
    }

    /** Stops following. Idempotent. */
    public void stop() {
        generation++;
        running = false;
        changed.disconnect();
        source.disconnect();
        changed = Connection.DISCONNECTED;
        source = Connection.DISCONNECTED;
    }

    /** Follows {@code next} from now on, carrying on at once if this was following something already. */
    public void retarget(Property<?> next) {
        boolean wasRunning = running;
        stop();
        property = Objects.requireNonNull(next, "next");
        if (wasRunning) start();
    }
}
