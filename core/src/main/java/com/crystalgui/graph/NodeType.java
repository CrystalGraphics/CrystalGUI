package com.crystalgui.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A kind of node the editor can create: what it is called, where it files, what ports it has, and what
 * its settings start as.
 *
 * <h3>Headless, so a server can build one</h3>
 * <p>{@link #create} returns a {@link NodeData}, not a widget. The mapping from a type to something on
 * screen is {@code NodeWidgetFactory}'s, over in {@code ui}, and keeping the two apart is what lets a
 * dedicated server assemble and validate a graph it will never draw.</p>
 *
 * <h3>Synonyms are a field, not a search feature</h3>
 * <p>Unity's create-node search matches *"name parts and synonyms based on industry terms"*, so
 * {@code Add} is findable by typing {@code plus}. That only works if the type declares the words — a
 * fuzzy matcher cannot invent that a multiply is also a "product". Declaring them here means the
 * knowledge lives with the node rather than in a lookup table somebody has to remember to update.</p>
 */
public record NodeType(String id, String label, String category, List<String> synonyms,
                       List<PortSpec> ports, Map<String, String> defaults, List<NodeField> fields) {

    public NodeType {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("A node type needs an id");
        label = label == null || label.isEmpty() ? id : label;
        category = category == null ? "" : category;
        synonyms = List.copyOf(synonyms == null ? List.of() : synonyms);
        ports = List.copyOf(ports == null ? List.of() : ports);
        fields = List.copyOf(fields == null ? List.of() : fields);
        defaults = defaults == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(defaults));
    }

    /** The six-component shape, for a type declaring no editable fields. */
    public NodeType(String id, String label, String category, List<String> synonyms,
                    List<PortSpec> ports, Map<String, String> defaults) {
        this(id, label, category, synonyms, ports, defaults, List.of());
    }

    /** The field with this id, or null. */
    @javax.annotation.Nullable
    public NodeField field(String fieldId) {
        for (NodeField field : fields) {
            if (field.id().equals(fieldId)) return field;
        }
        return null;
    }

    /** Fields drawn in the node's body, in declaration order. */
    public List<NodeField> bodyFields() {
        return fields.stream().filter(f -> !f.isPortField()).toList();
    }

    /** The field editing {@code portId} inline, or null if that port has none. */
    @javax.annotation.Nullable
    public NodeField fieldForPort(String portId) {
        for (NodeField field : fields) {
            if (field.isPortField() && portId.equals(field.portId())) return field;
        }
        return null;
    }

    public static Builder of(String id) {
        return new Builder(id);
    }

    /**
     * A fresh node of this type at a world position, with a generated id.
     *
     * <p>Field defaults are seeded into the node's properties, so a node is <b>born valid</b>: an editor
     * reading a field, and a compiler reading a port's literal, both find a value without either having
     * to know the type's declaration. An explicit {@code defaultProperty} still wins, since it is the more
     * specific statement.</p>
     */
    public NodeData create(float x, float y) {
        Map<String, String> initial = new LinkedHashMap<>();
        for (NodeField field : fields) {
            if (!field.defaultValue().isEmpty()) initial.put(field.id(), field.defaultValue());
        }
        initial.putAll(defaults);
        return new NodeData(GraphIds.generate(), id, x, y, ports, initial);
    }

    /** Every port of this type that could accept {@code sourceTypeId} arriving from an output — what the
     * contextual create menu lists after a wire is dropped on empty canvas. */
    public List<PortSpec> inputsAccepting(String sourceTypeId, TypeCompatibility compatibility) {
        List<PortSpec> found = new ArrayList<>();
        for (PortSpec port : ports) {
            if (port.direction().isInput() && compatibility.accepts(sourceTypeId, port.typeId())) {
                found.add(port);
            }
        }
        return found;
    }

    /** The mirror: outputs that could feed a dragged input of {@code targetTypeId}. */
    public List<PortSpec> outputsFeeding(String targetTypeId, TypeCompatibility compatibility) {
        List<PortSpec> found = new ArrayList<>();
        for (PortSpec port : ports) {
            if (port.direction().isOutput() && compatibility.accepts(port.typeId(), targetTypeId)) {
                found.add(port);
            }
        }
        return found;
    }

    /**
     * Whether this type answers to {@code query} — its label, its category or one of its synonyms.
     *
     * <p>Substring rather than fuzzy, deliberately: a fuzzy matcher makes "which node did it mean?"
     * unanswerable when it guesses wrong, and the synonyms are already doing the work fuzziness would be
     * approximating. Case-insensitive, and an empty query matches everything so the menu opens full.</p>
     */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) return true;
        String needle = query.toLowerCase(Locale.ROOT).trim();
        if (label.toLowerCase(Locale.ROOT).contains(needle)) return true;
        if (category.toLowerCase(Locale.ROOT).contains(needle)) return true;
        for (String synonym : synonyms) {
            if (synonym.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }

    /** Fluent construction, the same shape — and for the same reasons — as {@link NodeBuilder}. */
    public static final class Builder {
        private final String id;
        private String label;
        private String category = "";
        private final List<String> synonyms = new ArrayList<>();
        private final List<PortSpec> ports = new ArrayList<>();
        private final Map<String, String> defaults = new LinkedHashMap<>();
        private final List<NodeField> fields = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder label(String value) {
            this.label = value;
            return this;
        }

        /** Where it files in the menu — {@code "Math"}, {@code "Input/Geometry"}. */
        public Builder category(String value) {
            this.category = value;
            return this;
        }

        /** Other words a user might reach for. @see NodeType */
        public Builder synonyms(String... words) {
            synonyms.addAll(List.of(words));
            return this;
        }

        public Builder in(String portId, String typeId) {
            ports.add(PortSpec.input(portId, typeId));
            return this;
        }

        public Builder out(String portId, String typeId) {
            ports.add(PortSpec.output(portId, typeId));
            return this;
        }

        /**
         * An input port that can also be typed into while nothing is connected to it.
         *
         * <p>The single most common shape in any node editor — Unity draws one on every unconnected
         * input — so it is one call rather than a port plus a separately-placed field.</p>
         */
        public Builder in(String portId, String typeId, NodeField field) {
            ports.add(PortSpec.input(portId, typeId));
            fields.add(field.onPort(portId));
            return this;
        }

        /**
         * A setting drawn in the node's body — a dropdown, a checkbox, a number.
         *
         * @see NodeField
         */
        public Builder field(NodeField field) {
            fields.add(field);
            return this;
        }

        public Builder defaultProperty(String key, String value) {
            defaults.put(key, value);
            return this;
        }

        public NodeType build() {
            // A standalone field sharing a port's id would write to the same properties entry as that
            // port's literal, so one would silently overwrite the other. A PORT field is exempt: sharing
            // the entry is precisely what it is for.
            for (NodeField field : fields) {
                if (field.isPortField()) continue;
                for (PortSpec port : ports) {
                    if (port.portId().equals(field.id())) {
                        throw new IllegalArgumentException("Node type " + id + ": field '" + field.id()
                                + "' has the same id as a port, so the two would share one stored value."
                                + " Rename the field, or declare it with in(portId, typeId, field).");
                    }
                }
            }
            return new NodeType(id, label, category, synonyms, ports, defaults, fields);
        }
    }
}
