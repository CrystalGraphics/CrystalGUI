package com.crystalgui.graph;

import javax.annotation.Nullable;
import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.core.search.SearchQuery;

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

    /**
     * Types whose label, category or synonyms answer to {@code query}, <b>best first</b>; everything, in
     * registration order, when it is blank.
     *
     * <p>Ranked rather than filtered — see {@link #rank}. A blank query is not ranked at all because
     * there is nothing to rank by, and imposing an order on "everything" would only fight the category
     * tree the menu shows instead.</p>
     */
    public List<NodeType> search(String query) {
        SearchQuery parsed = SearchQuery.of(query);
        List<NodeType> found = new ArrayList<>();
        if (parsed.isEmpty()) {
            found.addAll(types.values());
            return found;
        }
        List<Ranked<NodeType>> ranked = new ArrayList<>();
        for (NodeType type : types.values()) {
            SearchMatch match = type.bestMatch(parsed);
            if (match != null) ranked.add(new Ranked<>(type, match, type.label()));
        }
        return rank(ranked);
    }

    /** A candidate paired with why it matched, so {@link #rank} can order without re-matching. */
    private record Ranked<T>(T value, SearchMatch match, String label) {
    }

    /**
     * Sorts by score descending, then alphabetically.
     *
     * <p>The alphabetical tiebreak is not cosmetic: {@code types} is a {@code LinkedHashMap}, so without
     * it two equally-scored entries would be ordered by whoever happened to register first — an order the
     * user cannot see, cannot predict, and which changes when a library adds a node.</p>
     */
    private static <T> List<T> rank(List<Ranked<T>> ranked) {
        ranked.sort((a, b) -> {
            int byScore = Integer.compare(b.match().score(), a.match().score());
            return byScore != 0 ? byScore : a.label().compareToIgnoreCase(b.label());
        });
        List<T> out = new ArrayList<>(ranked.size());
        for (Ranked<T> entry : ranked) out.add(entry.value());
        return out;
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
        SearchQuery parsed = SearchQuery.of(query);
        List<Ranked<Offer>> ranked = new ArrayList<>();
        for (NodeType type : types.values()) {
            SearchMatch match = parsed.isEmpty() ? null : type.bestMatch(parsed);
            if (!parsed.isEmpty() && match == null) continue;
            for (PortSpec port : type.inputsAccepting(sourceTypeId, compatibility)) {
                Offer offer = new Offer(type, port);
                ranked.add(new Ranked<>(offer, match, offer.label()));
            }
        }
        return parsed.isEmpty() ? unranked(ranked) : rank(ranked);
    }

    /** The offers in registration order, for a blank query — see {@link #search}. */
    private static <T> List<T> unranked(List<Ranked<T>> ranked) {
        List<T> out = new ArrayList<>(ranked.size());
        for (Ranked<T> entry : ranked) out.add(entry.value());
        return out;
    }

    /** The mirror, for a wire dragged out of an input. */
    public List<Offer> offersForInput(String targetTypeId, TypeCompatibility compatibility, String query) {
        SearchQuery parsed = SearchQuery.of(query);
        List<Ranked<Offer>> ranked = new ArrayList<>();
        for (NodeType type : types.values()) {
            SearchMatch match = parsed.isEmpty() ? null : type.bestMatch(parsed);
            if (!parsed.isEmpty() && match == null) continue;
            for (PortSpec port : type.outputsFeeding(targetTypeId, compatibility)) {
                Offer offer = new Offer(type, port);
                ranked.add(new Ranked<>(offer, match, offer.label()));
            }
        }
        return parsed.isEmpty() ? unranked(ranked) : rank(ranked);
    }
}
