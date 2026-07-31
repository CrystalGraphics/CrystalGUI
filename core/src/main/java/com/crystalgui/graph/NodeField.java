package com.crystalgui.graph;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One editable value on a node — a dropdown, a number box, a colour swatch, a line of text.
 *
 * <h3>Ports and settings are the same thing, placed differently</h3>
 * <p>It is tempting to treat "the literal on an unconnected input" and "a setting like {@code Space}" as
 * two systems. Unity treats them as one, and it is right to: both are a value the user types or picks,
 * both are stored on the node, both change what the node does. The only difference is <b>where the editor
 * is drawn</b> — in the port's own row, or in the node's body.</p>
 *
 * <p>That unification is what makes this scale. A node declares fields; the editor renders them; nothing
 * downstream needs a special case for "shader property" versus "port default". A dialogue graph, a state
 * machine or a material graph all use the identical mechanism.</p>
 *
 * <h3>Values are strings, and that is deliberate</h3>
 * <p>{@link NodeData#properties()} is a {@code Map<String, String>}, which is what makes a document
 * serialisable, diffable and content-hashable without the document layer knowing any value types. A field
 * carries the <em>kind</em> so the editor can render and parse it; the storage stays text. This is the
 * same trade {@code StyleValue} makes, and for the same reason.</p>
 *
 * @param id          the key in {@link NodeData#properties()}. For a port field this is the PORT id, so
 *                    the literal a compiler reads and the value the editor writes are the same entry
 * @param label       what the editor shows; defaults to the id
 * @param kind        how to render and parse it
 * @param options     the permitted values for {@link Kind#ENUM}, in menu order; empty otherwise
 * @param defaultValue the value used when the document says nothing
 * @param portId      the port this field edits inline, or null for a standalone setting in the node body
 */
public record NodeField(String id, String label, Kind kind, List<String> options, String defaultValue,
                        @Nullable String portId) {

    /**
     * What sort of editor a field wants.
     *
     * <p>Deliberately a small closed set: these are the shapes a value can take, not the widgets that
     * draw them. A consumer wanting a bespoke editor registers a widget for one of these rather than
     * inventing a kind, so every document stays readable by an editor that has never heard of it.</p>
     */
    public enum Kind {
        /** A choice from {@link #options}. */
        ENUM,
        /** Free text. */
        TEXT,
        /** A single number. */
        NUMBER,
        /** A checkbox; stored as {@code "true"} / {@code "false"}. */
        BOOLEAN,
        /** A colour, stored as the CSS-ish literal the consumer expects (e.g. {@code vec4(...)}). */
        COLOR,
        /** Several numbers — a vector. Stored as the consumer's own literal form. */
        VECTOR
    }

    public NodeField {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("A node field needs an id");
        if (kind == null) kind = Kind.TEXT;
        options = List.copyOf(options == null ? List.of() : options);
        if (kind == Kind.ENUM && options.isEmpty()) {
            // A dropdown with nothing in it is a control that silently does nothing — worse than a
            // declaration that fails loudly here.
            throw new IllegalArgumentException("Enum field '" + id + "' declares no options");
        }
        if (label == null || label.isEmpty()) label = id;
        if (kind == Kind.ENUM && (defaultValue == null || !options.contains(defaultValue))) {
            defaultValue = options.get(0);
        }
        if (defaultValue == null) defaultValue = "";
    }

    // ── Fluent construction ─────────────────────────────────────────────────

    /** A dropdown in the node's body. The first option is the default. */
    public static NodeField enumOf(String id, String label, String... options) {
        return new NodeField(id, label, Kind.ENUM, List.of(options), null, null);
    }

    public static NodeField text(String id, String label, String defaultValue) {
        return new NodeField(id, label, Kind.TEXT, List.of(), defaultValue, null);
    }

    public static NodeField number(String id, String label, String defaultValue) {
        return new NodeField(id, label, Kind.NUMBER, List.of(), defaultValue, null);
    }

    public static NodeField bool(String id, String label, boolean defaultValue) {
        return new NodeField(id, label, Kind.BOOLEAN, List.of(), String.valueOf(defaultValue), null);
    }

    public static NodeField color(String id, String label, String defaultValue) {
        return new NodeField(id, label, Kind.COLOR, List.of(), defaultValue, null);
    }

    /**
     * The same field, drawn inline on {@code portId} and shown only while that port is unconnected.
     *
     * <p>The field's {@link #id} becomes the port id, so the value the editor writes is exactly the
     * literal a compiler reads for that port — one entry, not two that have to be kept in step.</p>
     */
    public NodeField onPort(String portId) {
        return new NodeField(portId, label, kind, options, defaultValue, portId);
    }

    /** Whether this field is drawn inline on a port rather than in the node's body. */
    public boolean isPortField() {
        return portId != null;
    }

    /** Whether {@code value} is usable for this field. Only meaningful for {@link Kind#ENUM}. */
    public boolean accepts(String value) {
        return kind != Kind.ENUM ? value != null : value != null && options.contains(value);
    }

    /**
     * {@code value} if usable, otherwise {@link #defaultValue}.
     *
     * <p>A stored document can name an option a later build no longer has — the option list is code and
     * the document is data, so they drift. Falling back beats showing a blank control while whatever
     * consumes the value quietly uses something else.</p>
     */
    public String resolve(@Nullable String value) {
        return accepts(value) ? value : defaultValue;
    }
}
