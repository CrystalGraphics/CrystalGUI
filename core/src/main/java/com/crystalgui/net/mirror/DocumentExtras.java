package com.crystalgui.net.mirror;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.ui.dom.UIElement;

/**
 * What a {@code .cgui} document carries about a node that the node itself cannot hold: design-time
 * values, model bindings, event hooks, and an instance's parameters and overrides.
 *
 * <p>An {@code Attribute} is written as a string, so a map-valued one has no encoding — and none of
 * these three is a runtime property anyway. They live beside the tree instead, keyed by node identity,
 * and {@link UIElementMirror} writes and reads them only in the {@link UIElementMirror.Keys#DOCUMENT}
 * dialect. A description going over a wire never carries any of them.</p>
 *
 * <pre>{@code
 * DocumentExtras<JsonElement> extras = new DocumentExtras<>();
 * UIElement root = mirror.decode(json, extras);          // fills it as it decodes
 * JsonElement design = extras.get(node, DocumentExtras.DESIGN);
 * JsonElement out = mirror.describe(root, extras);       // writes it back
 * }</pre>
 *
 * <p>Identity, not equality: two nodes with the same tag and classes are different nodes. Entries are
 * therefore only as long-lived as the tree — a node that leaves the document should be
 * {@link #forget}ten, or the table pins it.</p>
 *
 * @param <T> the serialization form the values are held in, untouched
 */
public final class DocumentExtras<T> {

    /** Values the builder canvas applies and {@code UiTemplate.inflate} strips. */
    public static final String DESIGN = "design";

    /** Per state key, a path into the document's declared model. Read by the exporter, not the runtime. */
    public static final String BIND = "bind";

    /** Per event kind, the name of a hook the generated class declares. */
    public static final String ON = "on";

    /** On an instance node: values for the placed template's declared parameters. */
    public static final String PARAMS = "params";

    /** On an instance node: per internal id, state to apply after the template is inflated. */
    public static final String OVERRIDES = "overrides";

    /**
     * Written in this order, so a document diffs predictably.
     *
     * <p>The last two are not the builder's: {@link #PARAMS} and {@link #OVERRIDES} are read by the
     * loader, so {@code UiTemplate.inflate} collects them even though it strips the first three.</p>
     */
    static final String[] KEYS = {PARAMS, OVERRIDES, BIND, ON, DESIGN};

    private final Map<UIElement, Map<String, T>> byNode = new IdentityHashMap<>();

    /** What {@code node} carries under {@code key}, or null. */
    @Nullable
    public T get(UIElement node, String key) {
        Map<String, T> entries = byNode.get(node);
        return entries == null ? null : entries.get(key);
    }

    /** Sets or, with a null value, clears one entry. */
    public void put(UIElement node, String key, @Nullable T value) {
        if (value == null) {
            Map<String, T> entries = byNode.get(node);
            if (entries != null && entries.remove(key) != null && entries.isEmpty()) byNode.remove(node);
            return;
        }
        byNode.computeIfAbsent(node, ignored -> new LinkedHashMap<>()).put(key, value);
    }

    /** Everything {@code node} carries, in write order. Empty when it carries nothing. */
    public Map<String, T> of(UIElement node) {
        Map<String, T> entries = byNode.get(node);
        return entries == null ? Map.of() : Map.copyOf(entries);
    }

    public boolean has(UIElement node) {
        return byNode.containsKey(node);
    }

    /** Drops everything about {@code node} — call when it leaves the document. */
    public void forget(UIElement node) {
        byNode.remove(node);
    }

    public boolean isEmpty() {
        return byNode.isEmpty();
    }

    public int size() {
        return byNode.size();
    }

    public void clear() {
        byNode.clear();
    }
}
