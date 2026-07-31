package com.crystalgui.graph;

import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.CodecException;
import com.crystalgui.serialization.Codecs;
import com.crystalgui.serialization.DynamicOps;

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

    public static final Codec<PortRef> PORT_REF = new Codec<>() {
        @Override
        public <T> T encode(DynamicOps<T> ops, PortRef input) {
            return Codecs.map(ops)
                    .field("n", Codecs.STRING, input.nodeId())
                    .field("p", Codecs.STRING, input.portId())
                    .build();
        }

        @Override
        public <T> PortRef decode(DynamicOps<T> ops, T input) {
            var in = Codecs.read(ops, input);
            return new PortRef(in.field("n", Codecs.STRING), in.field("p", Codecs.STRING));
        }
    };

    public static final Codec<PortSpec> PORT_SPEC = new Codec<>() {
        @Override
        public <T> T encode(DynamicOps<T> ops, PortSpec input) {
            return Codecs.map(ops)
                    .field("id", Codecs.STRING, input.portId())
                    // By NAME, never ordinal: inserting a constant must not re-point an existing file.
                    .field("dir", Codecs.enumOf(PortDirection.class), input.direction())
                    .field("type", Codecs.STRING, input.typeId())
                    .build();
        }

        @Override
        public <T> PortSpec decode(DynamicOps<T> ops, T input) {
            var in = Codecs.read(ops, input);
            return new PortSpec(in.field("id", Codecs.STRING),
                    in.field("dir", Codecs.enumOf(PortDirection.class)),
                    in.field("type", Codecs.STRING));
        }
    };

    public static final Codec<EdgeData> EDGE = new Codec<>() {
        @Override
        public <T> T encode(DynamicOps<T> ops, EdgeData input) {
            return Codecs.map(ops)
                    .field("from", PORT_REF, input.from())
                    .field("to", PORT_REF, input.to())
                    .build();
        }

        @Override
        public <T> EdgeData decode(DynamicOps<T> ops, T input) {
            var in = Codecs.read(ops, input);
            return new EdgeData(in.field("from", PORT_REF), in.field("to", PORT_REF));
        }
    };

    public static final Codec<NodeData> NODE = new Codec<>() {
        @Override
        public <T> T encode(DynamicOps<T> ops, NodeData input) {
            var builder = Codecs.map(ops)
                    .field("id", Codecs.STRING, input.id())
                    .field("type", Codecs.STRING, input.typeId())
                    .field("x", Codecs.FLOAT, input.x())
                    .field("y", Codecs.FLOAT, input.y())
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
        public <T> NodeData decode(DynamicOps<T> ops, T input) {
            var in = Codecs.read(ops, input);
            List<PortSpec> ports = in.has("ports")
                    ? in.field("ports", Codecs.listOf(PORT_SPEC))
                    : List.of();
            Map<String, String> properties = new LinkedHashMap<>();
            if (in.has("props")) {
                T raw = in.raw("props");
                for (Map.Entry<T, T> entry : ops.getMapValue(raw).entrySet()) {
                    properties.put(ops.getStringValue(entry.getKey()), ops.getStringValue(entry.getValue()));
                }
            }
            return new NodeData(in.field("id", Codecs.STRING), in.field("type", Codecs.STRING),
                    in.field("x", Codecs.FLOAT), in.field("y", Codecs.FLOAT), ports, properties);
        }
    };

    public static final Codec<GraphDocument> DOCUMENT = new Codec<>() {
        @Override
        public <T> T encode(DynamicOps<T> ops, GraphDocument input) {
            return Codecs.map(ops)
                    .field("v", Codecs.INT, GraphDocument.SCHEMA_VERSION)
                    .optionalList("nodes", NODE, new ArrayList<>(input.nodes()))
                    .optionalList("edges", EDGE, input.edges())
                    .build();
        }

        @Override
        public <T> GraphDocument decode(DynamicOps<T> ops, T input) {
            var in = Codecs.read(ops, input);
            int version = in.optional("v", Codecs.INT, GraphDocument.SCHEMA_VERSION);
            if (version > GraphDocument.SCHEMA_VERSION) {
                throw new CodecException("Graph document is version " + version
                        + ", which this build does not understand (it writes " + GraphDocument.SCHEMA_VERSION
                        + "). Refusing rather than dropping whatever is new.");
            }

            GraphDocument document = new GraphDocument();
            if (in.has("nodes")) {
                for (NodeData node : in.field("nodes", Codecs.listOf(NODE))) document.addNode(node);
            }
            if (in.has("edges")) {
                // Restored directly rather than through connect(): these edges were legal in the document
                // that wrote them, and re-validating on load would silently drop every edge whose types
                // this build has no registered rule for — which is precisely the "opened without the
                // plugin" case the model is built to survive.
                for (EdgeData edge : in.field("edges", Codecs.listOf(EDGE))) document.restoreEdge(edge);
            }
            document.getChangeset().clear();
            return document;
        }
    };
}
