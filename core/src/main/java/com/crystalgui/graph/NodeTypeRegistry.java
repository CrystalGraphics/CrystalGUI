package com.crystalgui.graph;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The node library: every {@link NodeType} this editor can create, and the two questions the create
 * menu asks of it.
 *
 * <h3>Three things need this, not one</h3>
 * <p>It is the create menu's backing store, it is how a {@link GraphDocument} turns a {@code typeId}
 * back into a widget when a document is loaded, and it is what duplicate needs to rebuild a copied node
 * — which is precisely why 6.2.4 had to defer duplicate. One registry answers all three.</p>
 *
 * <h3>Not static, unlike the engine's other registries</h3>
 * <p>{@code ElementRegistry} and {@code CgMaterialRegistry} are process-wide because their contents are
 * the engine's own. A node library belongs to an <em>editor</em>: a shader graph and a dialogue graph in
 * the same process have entirely different libraries, and a global one would have them fighting over
 * ids. So this is an instance a consumer owns and hands to its view.</p>
 *
 * <p>{@link #shared()} exists for the common case of an application with exactly one library, so that
 * simplicity is available without making it the only option.</p>
 */
public final class NodeTypeRegistry {

    private static final NodeTypeRegistry SHARED = new NodeTypeRegistry();

    private final Map<String, NodeType> types = new LinkedHashMap<>();

    /** A library for an application that only ever has one. Not a requirement — see the class note. */
    public static NodeTypeRegistry shared() {
        return SHARED;
    }

    /**
     * @throws IllegalArgumentException on a duplicate id, matching every other registry here — a silent
     *         overwrite hides two consumers fighting over one id far more often than it is deliberate
     */
    public NodeTypeRegistry register(NodeType type) {
        NodeType previous = types.putIfAbsent(type.id(), type);
        if (previous != null && !previous.equals(type)) {
            throw new IllegalArgumentException("Node type id already registered: " + type.id());
        }
        return this;
    }

    public NodeTypeRegistry register(NodeType.Builder builder) {
        return register(builder.build());
    }

    @Nullable
    public NodeType get(String typeId) {
        return types.get(typeId);
    }

    public boolean contains(String typeId) {
        return types.containsKey(typeId);
    }

    public Collection<NodeType> all() {
        return Collections.unmodifiableCollection(types.values());
    }

    public int size() {
        return types.size();
    }

    public void clear() {
        types.clear();
    }

    // ── What the menu asks ──────────────────────────────────────────────────

    /** Types whose label, category or synonyms answer to {@code query}; everything when it is blank. */
    public List<NodeType> search(String query) {
        List<NodeType> found = new ArrayList<>();
        for (NodeType type : types.values()) {
            if (type.matches(query)) found.add(type);
        }
        return found;
    }

    /**
     * One offer the contextual menu can make: create <em>this type</em> and land the dragged wire on
     * <em>this port</em>.
     *
     * <p>An entry is a (type, port) pair rather than a type, because Unity's menu *"lists every
     * available Port on nodes that match"* — and that is the better interaction by some distance:
     * picking an entry creates the node and completes the connection in one step, rather than leaving
     * the user to wire up what they just asked for.</p>
     */
    public record Offer(NodeType type, @Nullable PortSpec port) {
        /** The type's name, plus the port when there is one. A menu opened without a wire has no port to
         * name, and this is called from the row builder either way. */
        public String label() {
            return port == null ? type.label() : type.label() + " - " + port.portId();
        }
    }

    /**
     * Everything that could receive a wire dragged from an output of {@code sourceTypeId}.
     *
     * @param compatibility the <b>document's</b> rule, not equality — a consumer whose floats promote to
     *                      vectors must be offered those ports, or the menu is useless on exactly the
     *                      graphs that needed it
     */
    public List<Offer> offersForOutput(String sourceTypeId, TypeCompatibility compatibility, String query) {
        List<Offer> offers = new ArrayList<>();
        for (NodeType type : types.values()) {
            if (!type.matches(query)) continue;
            for (PortSpec port : type.inputsAccepting(sourceTypeId, compatibility)) {
                offers.add(new Offer(type, port));
            }
        }
        return offers;
    }

    /** The mirror, for a wire dragged out of an input. */
    public List<Offer> offersForInput(String targetTypeId, TypeCompatibility compatibility, String query) {
        List<Offer> offers = new ArrayList<>();
        for (NodeType type : types.values()) {
            if (!type.matches(query)) continue;
            for (PortSpec port : type.outputsFeeding(targetTypeId, compatibility)) {
                offers.add(new Offer(type, port));
            }
        }
        return offers;
    }
}
