package com.crystalgui.app.uibuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.sheet.StyleRule;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.inspector.InspectorRegistry;

/**
 * What the UI builder is pointed at — <b>one object</b>, shared by the canvas, the hierarchy and the
 * inspector.
 *
 * <pre>{@code
 * selection.replaceWith(List.of(node));   // a click on the canvas
 * selection.selectRule(rule);             // and the Styles panel picking one
 * }</pre>
 *
 * <p>Three things can be selected and they are not alternatives: a set of NODES is what the canvas and
 * hierarchy agree on, a RULE is what the Styles panel adds on top of it, and a TOKEN is what the Tokens
 * panel adds. An inspector showing a node's box model and the rule that set it is showing both at once.</p>
 *
 * <p><b>Announcing is not the caller's job.</b> Every change tells {@link InspectorRegistry} the subject
 * moved, which is what {@code GraphSelection} does — so no panel has to remember to, and none of them can
 * forget. That is the whole reason this is one object rather than three fields on three widgets.</p>
 */
public final class BuilderSelection {

    /** Fires after any change, before the inspector is told. */
    public final Signal.Action onChanged = new Signal.Action();

    private final Set<UIElement> nodes = new LinkedHashSet<>();

    @Nullable
    private StyleRule rule;

    @Nullable
    private String token;

    private boolean canvasSelected;

    /** The selected nodes, in the order they were selected. */
    public List<UIElement> nodes() {
        return List.copyOf(nodes);
    }

    /** The first selected node, or null — what a section describing ONE node asks for. */
    @Nullable
    public UIElement node() {
        for (UIElement node : nodes) return node;
        return null;
    }

    public boolean contains(UIElement node) {
        return nodes.contains(node);
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /**
     * Whether this selection says <b>nothing at all</b> — no nodes, no rule, no token, not the canvas.
     *
     * <p>Distinct from {@link #isEmpty()}, which is about nodes only: a selection with a rule picked and
     * no node is empty and is very much a statement. Asked by a provider deciding whether to answer or to
     * let the question travel further out.</p>
     */
    public boolean statesNothing() {
        return nodes.isEmpty() && rule == null && token == null && !canvasSelected;
    }

    public int size() {
        return nodes.size();
    }

    /** Selects exactly {@code chosen}, dropping whatever was selected. */
    public void replaceWith(Collection<UIElement> chosen) {
        List<UIElement> next = new ArrayList<>();
        for (UIElement node : chosen) {
            if (node != null) next.add(node);
        }
        if (next.size() == nodes.size() && nodes.containsAll(next)) return;
        nodes.clear();
        nodes.addAll(next);
        changed();
    }

    public void selectOnly(@Nullable UIElement node) {
        replaceWith(node == null ? List.of() : List.of(node));
    }

    public void add(UIElement node) {
        if (node == null || !nodes.add(node)) return;
        changed();
    }

    public void remove(UIElement node) {
        if (node == null || !nodes.remove(node)) return;
        changed();
    }

    /** Adds what is not selected and removes what is — a Ctrl-click. */
    public void toggle(UIElement node) {
        if (node == null) return;
        if (!nodes.remove(node)) nodes.add(node);
        changed();
    }

    public void clear() {
        if (nodes.isEmpty() && rule == null && token == null) return;
        nodes.clear();
        rule = null;
        token = null;
        changed();
    }

    /** Whether the CANVAS itself is what is selected — the document, rather than anything in it. What
     * the Document tab describes, and the answer when nothing else is picked. */
    public boolean canvasSelected() {
        return canvasSelected;
    }

    public void selectCanvas(boolean selected) {
        if (canvasSelected == selected) return;
        canvasSelected = selected;
        if (selected) {
            nodes.clear();
            rule = null;
            token = null;
        }
        changed();
    }

    /** The rule the Styles panel picked, or null. */
    @Nullable
    public StyleRule rule() {
        return rule;
    }

    public void selectRule(@Nullable StyleRule chosen) {
        if (chosen == rule) return;
        rule = chosen;
        changed();
    }

    /** The design token the Tokens panel picked, or null. */
    @Nullable
    public String token() {
        return token;
    }

    public void selectToken(@Nullable String chosen) {
        if (chosen == null ? token == null : chosen.equals(token)) return;
        token = chosen;
        changed();
    }

    private void changed() {
        onChanged.emit();
        InspectorRegistry.subjectChanged();
    }
}
