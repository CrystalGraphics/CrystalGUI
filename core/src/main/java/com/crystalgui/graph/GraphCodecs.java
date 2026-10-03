package com.crystalgui.graph;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgui.core.settings.SettingsCodec;
import com.crystalgui.core.settings.SettingsLayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codecs for the graph document.
 *
 * <h3>Content-addressable, which constrains how it is written</h3>
 * <p>Field order is fixed, maps are insertion-ordered, and absent optionals are omitted rather than
 * written null — the same three rules {@code UIDescriptionCodec} follows, and for the same reason: the
 * same graph must encode to the same bytes twice or {@code ContentHash} means nothing, and a graph that
 * can be named by its hash can be sent by its hash.</p>
 *
 * <h3>One thing a UI description does not need: a version</h3>
 * <p>A description is regenerated from live code every time it is sent, so both ends are the same build
 * by construction. A <b>document</b> is written to disk and outlives the code that wrote it. The version
 * goes in from the first commit, because the first format change is a migration with no anchor
 * otherwise.</p>
 */
public final class GraphCodecs {

    private GraphCodecs() {
    }

    public static final CgCodec<PortRef> PORT_REF = new CgCodec<>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, PortRef input) {
            return CgCodecs.map(ops)
                    .field("n", CgCodecs.STRING, input.nodeId())
                    .field("p", CgCodecs.STRING, input.portId())
                    .build();
        }

        @Override
        public <T> PortRef decode(CgDynamicOps<T> ops, T input) {
            var in = CgCodecs.read(ops, input);
            return new PortRef(in.field("n", CgCodecs.STRING), in.field("p", CgCodecs.STRING));
        }
    };

    public static final CgCodec<PortSpec> PORT_SPEC = new CgCodec<>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, PortSpec input) {
            return CgCodecs.map(ops)
                    .field("id", CgCodecs.STRING, input.portId())
                    // By NAME, never ordinal: inserting a constant must not re-point an existing file.
                    .field("dir", CgCodecs.enumOf(PortDirection.class), input.direction())
                    .field("type", CgCodecs.STRING, input.typeId())
                    .build();
        }

        @Override
        public <T> PortSpec decode(CgDynamicOps<T> ops, T input) {
            var in = CgCodecs.read(ops, input);
            return new PortSpec(in.field("id", CgCodecs.STRING),
                    in.field("dir", CgCodecs.enumOf(PortDirection.class)),
                    in.field("type", CgCodecs.STRING));
        }
    };

    public static final CgCodec<EdgeData> EDGE = new CgCodec<>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, EdgeData input) {
            return CgCodecs.map(ops)
                    .field("from", PORT_REF, input.from())
                    .field("to", PORT_REF, input.to())
                    .build();
        }

        @Override
        public <T> EdgeData decode(CgDynamicOps<T> ops, T input) {
            var in = CgCodecs.read(ops, input);
            return new EdgeData(in.field("from", PORT_REF), in.field("to", PORT_REF));
        }
    };

    public static final CgCodec<NodeData> NODE = new CgCodec<>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, NodeData input) {
            var builder = CgCodecs.map(ops)
                    .field("id", CgCodecs.STRING, input.id())
                    .field("type", CgCodecs.STRING, input.typeId())
                    .field("x", CgCodecs.FLOAT, input.x())
                    .field("y", CgCodecs.FLOAT, input.y())
                    .optionalList("ports", PORT_SPEC, input.ports());
            if (!input.properties().isEmpty()) {
                Map<T, T> props = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : input.properties().entrySet()) {
                    props.put(ops.createString(entry.getKey()), ops.createString(entry.getValue()));
                }
                builder.raw("props", ops.createMap(props));
            }
            return builder.build();
        }

        @Override
        public <T> NodeData decode(CgDynamicOps<T> ops, T input) {
            var in = CgCodecs.read(ops, input);
            List<PortSpec> ports = in.has("ports")
                    ? in.field("ports", CgCodecs.listOf(PORT_SPEC))
                    : List.of();
            Map<String, String> properties = new LinkedHashMap<>();
            if (in.has("props")) {
                T raw = in.raw("props");
                for (Map.Entry<T, T> entry : ops.getMapValue(raw).entrySet()) {
                    properties.put(ops.getStringValue(entry.getKey()), ops.getStringValue(entry.getValue()));
                }
            }
            return new NodeData(in.field("id", CgCodecs.STRING), in.field("type", CgCodecs.STRING),
                    in.field("x", CgCodecs.FLOAT), in.field("y", CgCodecs.FLOAT), ports, properties);
        }
    };

    public static final CgCodec<GraphProperty> PROPERTY = new CgCodec<>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, GraphProperty input) {
            var builder = CgCodecs.map(ops)
                    .field("id", CgCodecs.STRING, input.id())
                    .field("name", CgCodecs.STRING, input.name())
                    .field("ref", CgCodecs.STRING, input.reference())
                    .field("type", CgCodecs.STRING, input.typeId())
                    .field("def", CgCodecs.STRING, input.defaultValue())
                    .field("exposed", CgCodecs.BOOL, input.exposed());
            // Omitted when empty rather than written blank, so the encoding of a plain property is
            // stable and its content hash is not disturbed by fields nobody set.
            if (input.isCategorised()) builder.field("cat", CgCodecs.STRING, input.category());
            if (!input.options().isEmpty()) {
                Map<T, T> options = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : input.options().entrySet()) {
                    options.put(ops.createString(entry.getKey()), ops.createString(entry.getValue()));
                }
                builder.raw("opts", ops.createMap(options));
            }
            return builder.build();
        }

        @Override
        public <T> GraphProperty decode(CgDynamicOps<T> ops, T input) {
            var in = CgCodecs.read(ops, input);
            Map<String, String> options = new LinkedHashMap<>();
            if (in.has("opts")) {
                for (Map.Entry<T, T> entry : ops.getMapValue(in.raw("opts")).entrySet()) {
                    options.put(ops.getStringValue(entry.getKey()), ops.getStringValue(entry.getValue()));
                }
            }
            return new GraphProperty(
                    in.field("id", CgCodecs.STRING),
                    in.field("name", CgCodecs.STRING),
                    in.field("ref", CgCodecs.STRING),
                    in.field("type", CgCodecs.STRING),
                    in.field("def", CgCodecs.STRING),
                    in.optional("exposed", CgCodecs.BOOL, true),
                    in.optional("cat", CgCodecs.STRING, ""),
                    options);
        }
    };

    public static final CgCodec<GraphDocument> DOCUMENT = new CgCodec<>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, GraphDocument input) {
            var builder = CgCodecs.map(ops)
                    .field("v", CgCodecs.INT, GraphDocument.SCHEMA_VERSION)
                    .optionalList("nodes", NODE, new ArrayList<>(input.nodes()))
                    .optionalList("edges", EDGE, input.edges())
                    .optionalList("props", PROPERTY, input.properties());
            // The DOCUMENT layer alone. The others come from different FILES -- a user's preferences and
            // a project's overrides -- and writing those in here would mean opening this graph elsewhere
            // silently re-applied someone else's preferences as if the document had asked for them.
            //
            // Omitted when empty rather than written as an empty map: a document is content-addressed, so
            // "absent" and "present but empty" must not be two encodings of one graph.
            var documentLayer = input.settings().layer(SettingsLayer.DOCUMENT);
            if (SettingsCodec.isWorthWriting(documentLayer)) {
                builder.raw("settings", SettingsCodec.MODEL.encode(ops, documentLayer));
            }
            return builder.build();
        }

        @Override
        public <T> GraphDocument decode(CgDynamicOps<T> ops, T input) {
            var in = CgCodecs.read(ops, input);
            int version = in.optional("v", CgCodecs.INT, GraphDocument.SCHEMA_VERSION);
            if (version > GraphDocument.SCHEMA_VERSION) {
                throw new CgCodecException("Graph document is version " + version
                        + ", which this build does not understand (it writes " + GraphDocument.SCHEMA_VERSION
                        + "). Refusing rather than dropping whatever is new.");
            }

            GraphDocument document = new GraphDocument();
            if (in.has("settings")) {
                document.settings().replaceLayer(SettingsLayer.DOCUMENT,
                        SettingsCodec.MODEL.decode(ops, in.raw("settings")).asMap());
            }
            if (in.has("props")) {
                for (GraphProperty property : in.field("props", CgCodecs.listOf(PROPERTY))) {
                    document.addProperty(property);
                }
            }
            if (in.has("nodes")) {
                for (NodeData node : in.field("nodes", CgCodecs.listOf(NODE))) document.addNode(node);
            }
            if (in.has("edges")) {
                // Restored directly rather than through connect(): these edges were legal in the document
                // that wrote them, and re-validating on load would silently drop every edge whose types
                // this build has no registered rule for — which is precisely the "opened without the
                // plugin" case the model is built to survive.
                for (EdgeData edge : in.field("edges", CgCodecs.listOf(EDGE))) document.restoreEdge(edge);
            }
            return document;
        }
    };
}
