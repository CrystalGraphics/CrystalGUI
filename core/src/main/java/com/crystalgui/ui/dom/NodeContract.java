package com.crystalgui.ui.dom;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a <b>kind</b> of node is, as far as anything above the engine needs to know: its registered
 * name, what interactions it reports, and whether a description may carry children for it.
 * {@code plan_ui_rewrite.md} M0, filled in by M1.
 *
 * <h3>A kind, never an instance</h3>
 *
 * <p>This is the half of the rewrite that turns "every widget hand-writes {@code writeState} and
 * {@code readState}, and separately remembers to call {@code addReportedEvent}" into one declaration
 * the engine reads. A contract belongs to the class, so {@link TreeSource#contractOf} is expected to
 * cache per class rather than per element.</p>
 *
 * <p><b>M0 defines the position; M1 fills it.</b> Today a contract carries the name, the reported
 * kinds and the described-children policy — everything the mirror needs that is not per-instance. M1
 * adds the typed {@code State<T>}/{@code Event<T>} constants with their validation and rate policy,
 * and the engine derives state encoding from those instead of from the widget's own methods. Fixing
 * the position now means that change edits one file rather than every consumer.</p>
 *
 * <h3>Why reported events are here and not on the node</h3>
 *
 * <p>They were a lazily-created {@code Set<String>} field on every {@code UIElement} in the engine — one
 * reference on tens of thousands of elements so that a handful could report something, and a set whose
 * contents are the same for every instance of a widget. Which interactions a {@code Slider} reports is
 * a fact about sliders.</p>
 */
public final class NodeContract {

    /** For a node kind that carries nothing over a wire — the overwhelming majority. */
    public static final NodeContract INERT = new NodeContract("", Set.of(), false);

    private final String name;
    private final Set<String> reportedEvents;
    private final boolean acceptsDescribedChildren;

    public NodeContract(String name, Set<String> reportedEvents, boolean acceptsDescribedChildren) {
        this.name = name;
        this.reportedEvents = reportedEvents.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(reportedEvents));
        this.acceptsDescribedChildren = acceptsDescribedChildren;
    }

    /**
     * The registered name this kind is described by — the tag.
     *
     * <p>D5 makes these namespaced ({@code crystalgui-button}) so two mods' {@code EnginePanel} cannot
     * collide. Today it is whatever {@code ElementRegistry} answers, which for an unregistered class is
     * its lowercased simple name — the fallback that once left {@code ToolWindowFrame} matching no rule
     * in the sheet at all.</p>
     */
    public String name() {
        return name;
    }

    /**
     * Which interaction kinds this node reports to whoever owns the session.
     *
     * <p>Only the <em>name</em>: the handler stays on the server, which is what lets behaviour be a
     * lambda that never leaves the JVM it was written in.</p>
     */
    public Set<String> reportedEvents() {
        return reportedEvents;
    }

    /** Whether a description may carry children for this kind at all. */
    public boolean acceptsDescribedChildren() {
        return acceptsDescribedChildren;
    }

    public boolean reportsAnything() {
        return !reportedEvents.isEmpty();
    }

    /** A copy of this contract also reporting {@code kind}. Contracts are immutable. */
    public NodeContract reporting(String kind) {
        Set<String> merged = new LinkedHashSet<>(reportedEvents);
        merged.add(kind);
        return new NodeContract(name, merged, acceptsDescribedChildren);
    }

    @Override
    public String toString() {
        return "NodeContract[" + name + (reportedEvents.isEmpty() ? "" : " reports=" + reportedEvents)
                + (acceptsDescribedChildren ? " children" : "") + "]";
    }
}
