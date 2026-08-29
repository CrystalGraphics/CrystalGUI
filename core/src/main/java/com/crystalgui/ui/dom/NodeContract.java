package com.crystalgui.ui.dom;

import java.util.Set;

/**
 * What a <b>kind</b> of node is, as the seam sees it: its registered name, what it can report, and
 * whether a description may carry children for it. {@code plan_ui_rewrite.md} M0, filled in at M1.
 *
 * <h3>Why this is an interface and {@code WidgetContract} is the implementation</h3>
 *
 * <p>{@link TreeSource} is generic in the node type — that is what lets today's {@code UIElement} tree
 * and M5's {@code ui.dom} tree satisfy one interface without either knowing about the other. A
 * {@code WidgetContract} is typed in {@code UIElement} and knows how to read a widget's state, which a
 * generic source cannot promise. So the seam asks for the part that is node-agnostic, and
 * {@code com.crystalgui.ui.contract.WidgetContract} implements it while carrying the rest.</p>
 *
 * <p>M0 defined this as a final class holding three fields, with a note that M1 would replace its body.
 * That was half right: what M1 actually did was split it — the <em>question</em> stayed here and the
 * <em>answer</em> moved to where widgets can declare it.</p>
 */
public interface NodeContract {

    /** For a node kind that carries nothing over a wire — the overwhelming majority. */
    NodeContract INERT = new NodeContract() {
        @Override public String name() {
            return "";
        }
        @Override public Set<String> eventKinds() {
            return Set.of();
        }
        @Override public boolean acceptsDescribedChildren() {
            return false;
        }
        @Override public boolean carriesState() {
            return false;
        }
    };

    /**
     * The registered name this kind is described by — the tag.
     *
     * <p>D5 makes these namespaced ({@code crystalgui-button}) so two mods' {@code EnginePanel} cannot
     * collide. Today it is whatever {@code ElementRegistry} answers, which for an unregistered class is
     * its lowercased simple name — the fallback that once left {@code ToolWindowFrame} matching no rule
     * in the sheet at all.</p>
     */
    String name();

    /**
     * Which interaction kinds this kind of node is <b>capable</b> of reporting.
     *
     * <p>Not what any instance was asked to report: a session subscribes to what it has a handler for,
     * and the description carries that subset. Collapsing the two makes every client report everything
     * its widgets can do.</p>
     */
    Set<String> eventKinds();

    /** Whether a description may carry children for this kind at all. */
    boolean acceptsDescribedChildren();

    /** Whether this kind has any authored state to write. */
    boolean carriesState();

    default boolean reportsAnything() {
        return !eventKinds().isEmpty();
    }
}
