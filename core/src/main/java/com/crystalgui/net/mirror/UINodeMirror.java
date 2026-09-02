package com.crystalgui.net.mirror;

import com.crystalgui.style.Styleable;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.dom.UINodeTreeSource;
import com.crystalgui.ui.dom.TreeSource;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UINodeRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;
import javax.annotation.Nullable;

/**
 * {@link NodeMirror} over the new engine's {@link UINode} tree — the second engine's whole networking
 * half, beside {@link ElementNodeMirror}'s for the first.
 *
 * <p>A node is described as its {@link Name}, its id, its classes, every attribute something set that
 * the wire can carry ({@link Attribute#isCarried()}), and its <b>light</b> children. A shadow tree is
 * never written: it is the receiving side's registered class that rebuilds one, which is the same
 * arrangement the old codec had for internal children and the reason a description is small.</p>
 *
 * <p>State is the contract's, and no plain node carries any — the state half fills in with M6's
 * widgets. Inline style is the style pass's and arrives with 5.2; until then the field travels
 * empty, because a delta carrying only what is present cannot say "this is gone".</p>
 *
 * @param <T> the serialization form
 */
public final class UINodeMirror<T> implements NodeMirror<UINode, T> {

    private static final String NAME = "n";
    private static final String ID = "i";
    private static final String CLASSES = "c";
    private static final String ATTRIBUTES = "a";
    private static final String STYLE = "s";
    private static final String STATE = "v";
    private static final String CHILDREN = "k";
    private static final String NID = "nid";

    private final DynamicOps<T> ops;

    public UINodeMirror(DynamicOps<T> ops) {
        this.ops = ops;
    }

    // ── Descriptions ─────────────────────────────────────────────────────────

    @Override
    public T describe(UINode node) {
        return write(node, null);
    }

    @Override
    public T describeLive(UINode node, ToIntFunction<UINode> idOf) {
        return write(node, idOf);
    }

    private T write(UINode node, @Nullable ToIntFunction<UINode> idOf) {
        Map<T, T> fields = new LinkedHashMap<>();
        fields.put(key(NAME), ops.createString(node.name().toString()));
        if (!node.id().isEmpty()) fields.put(key(ID), ops.createString(node.id()));
        if (!node.classes().isEmpty()) fields.put(key(CLASSES), ops.createString(String.join(" ", node.classes())));
        T attributes = attributesOf(node);
        if (attributes != null) fields.put(key(ATTRIBUTES), attributes);
        // A description carries the whole node, not merely its shape. The order here is FIXED and the
        // optionals are OMITTED rather than written empty, because a description is content-addressed:
        // the same tree must encode byte-identically or its hash stops naming it.
        T style = InlineStyleCodec.encode(ops, node);
        if (style != null) fields.put(key(STYLE), style);
        T state = encodeState(node);
        if (state != null && !ops.getMapValue(state).isEmpty()) fields.put(key(STATE), state);
        if (idOf != null) fields.put(key(NID), ops.createNumber(idOf.applyAsInt(node)));
        List<UINode> described = node.describedChildren();
        if (!described.isEmpty()) {
            List<T> children = new ArrayList<>(described.size());
            for (UINode child : described) children.add(write(child, idOf));
            fields.put(key(CHILDREN), ops.createList(children));
        }
        return ops.createMap(fields);
    }

    @Override
    public UINode decode(T described) {
        return read(described, null);
    }

    @Override
    public UINode decodeLive(T described, ObjIntConsumer<UINode> idSink) {
        return read(described, idSink);
    }

    private UINode read(T described, @Nullable ObjIntConsumer<UINode> idSink) {
        Map<T, T> fields = ops.getMapValue(described);
        T name = fields.get(key(NAME));
        if (name == null) throw new IllegalArgumentException("A described node names its kind");
        UINode node = UINodeRegistry.create(Name.parse(ops.getStringValue(name)));
        applyIdentity(fields, node);
        T style = fields.get(key(STYLE));
        if (style != null) applyInlineStyle(style, node);
        T nid = fields.get(key(NID));
        if (nid != null && idSink != null) idSink.accept(node, ops.getNumberValue(nid).intValue());
        T children = fields.get(key(CHILDREN));
        if (children != null) {
            for (T child : ops.getListValue(children)) node.adoptDescribedChild(read(child, idSink));
        }
        // STATE LAST, because some of it INDEXES INTO THE CHILDREN. A TabView's selection is an index
        // and a Dropdown's is an index into its options, so applied to an empty widget it clamps to
        // nothing and the widget arrives showing its first entry with the description perfectly
        // correct. Encode order is fixed for the content hash and is a separate question from this.
        T state = fields.get(key(STATE));
        if (state != null) applyState(state, node);
        return node;
    }

    // ── State ────────────────────────────────────────────────────────────────

    /**
     * What this node's widget carries, from its contract.
     *
     * <p>A plain node answers null -- it is pure structure, and there is nothing to send. So does a
     * widget whose contract declares no state slots, which is most of them: a {@code Button}'s label
     * is an attribute, not state.</p>
     *
     * <p>These two used to be stubs returning null and doing nothing, on a comment saying widgets
     * carry state "through their contracts" -- which was true of the contracts and false of this
     * class, because nothing reached them. Every observable was right: the contract declared its
     * slots, the session marked the node dirty, the delta was encoded and sent, and it carried an
     * empty map. A slider moved on the server arrived at its default and stayed there.</p>
     */
    @Override
    @Nullable
    public T encodeState(UINode node) {
        WidgetContract<UINode> contract = WidgetContracts.of(node);
        if (contract == null || !contract.carriesState()) return null;
        StateMap<T> state = new StateMap<>(ops);
        contract.write(node, state);
        return state.encode();
    }

    @Override
    public void applyState(T value, UINode node) {
        WidgetContract<UINode> contract = WidgetContracts.of(node);
        if (contract == null || !contract.carriesState()) return;
        // DECLARATION ORDER IS APPLY ORDER, and four widgets depend on it -- a Slider takes its range
        // before its value, or the value is clamped against the range it is replacing. The contract
        // holds that order; this must not re-sort or filter.
        contract.read(node, new StateMap<>(ops, value));
    }

    // ── Attributes (identity) ────────────────────────────────────────────────

    @Override
    @Nullable
    public T encodeAttributes(UINode node) {
        Map<T, T> fields = new LinkedHashMap<>();
        fields.put(key(ID), ops.createString(node.id()));
        fields.put(key(CLASSES), ops.createString(String.join(" ", node.classes())));
        T attributes = attributesOf(node);
        fields.put(key(ATTRIBUTES), attributes != null ? attributes : ops.createMap(Map.of()));
        return ops.createMap(fields);
    }

    @Override
    public void applyAttributes(T value, UINode node) {
        applyIdentity(ops.getMapValue(value), node);
    }

    @Nullable
    private T attributesOf(UINode node) {
        Map<T, T> out = null;
        for (Attribute<?> key : node.setAttributes()) {
            if (!key.isCarried()) continue;
            if (out == null) out = new LinkedHashMap<>();
            out.put(ops.createString(key.name()), ops.createString(writeAttribute(node, key)));
        }
        return out == null ? null : ops.createMap(out);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String writeAttribute(UINode node, Attribute key) {
        return key.write(node.get(key));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyIdentity(Map<T, T> fields, UINode node) {
        T id = fields.get(key(ID));
        if (id != null) node.setId(ops.getStringValue(id));
        T classes = fields.get(key(CLASSES));
        if (classes != null) {
            List<String> wanted = new ArrayList<>();
            for (String c : ops.getStringValue(classes).split(" ")) if (!c.isEmpty()) wanted.add(c);
            for (String present : new ArrayList<>(node.classes())) {
                if (!wanted.contains(present)) node.removeClass(present);
            }
            for (String c : wanted) node.addClass(c);
        }
        T attributes = fields.get(key(ATTRIBUTES));
        if (attributes != null) {
            Map<T, T> entries = ops.getMapValue(attributes);
            // What arrived is the whole set of carried attributes; a carried one that is absent went
            // back to its initial. Unknown names are a newer peer's and are skipped.
            for (Attribute<?> key : new ArrayList<>(node.setAttributes())) {
                if (key.isCarried() && !entries.containsKey(ops.createString(key.name()))) {
                    node.set((Attribute) key, key.initial());
                }
            }
            for (Map.Entry<T, T> entry : entries.entrySet()) {
                Attribute<?> key = Attribute.named(ops.getStringValue(entry.getKey()));
                if (key == null || !key.isCarried()) continue;
                node.set((Attribute) key, key.parse(ops.getStringValue(entry.getValue())));
            }
        }
    }

    // ── Inline style (5.2) ───────────────────────────────────────────────────

    /**
     * The node's inline candidates, through the shared codec.
     *
     * <p>An EMPTY map rather than null: "no inline style" is a real value that has to travel, because
     * a candidate removed on the server has to be removed here too, and a delta carrying only what is
     * present cannot say "this is gone".</p>
     *
     * <p>{@link InlineStyleCodec} is typed on {@link Styleable} rather than on either engine's node,
     * which is what lets one codec serve both -- the cascade was shared at 5.2 and this is the seam
     * paying off. These were stubs until 6.8: an empty map out and nothing applied, so a width set
     * inline on the server arrived as the initial value, with the delta itself perfectly correct.</p>
     */
    @Override
    @Nullable
    public T encodeInlineStyle(UINode node) {
        T style = InlineStyleCodec.encode(ops, node);
        return style == null ? ops.createMap(Map.of()) : style;
    }

    @Override
    public void applyInlineStyle(T value, UINode node) {
        InlineStyleCodec.decodeInto(ops, value, node);
    }

    // ── Structure ────────────────────────────────────────────────────────────

    @Override
    public void insertChild(UINode parent, UINode child, int index) {
        parent.insertAt(index, child);
    }

    @Override
    public void removeChild(UINode parent, UINode child) {
        parent.remove(child);
    }

    private T key(String name) {
        return ops.createString(name);
    }

    /**
     * {@code Attribute.REPORTS}, space-joined — the same shape {@code classes} takes on the wire, and
     * for the same reason: a set of short identifiers is cheaper as one string than as a list, and the
     * attribute encoder already carries it with no special case.
     */
    @Override
    public Set<String> reportedEventsOf(UINode node) {
        String joined = node.get(Attribute.REPORTS);
        if (joined == null || joined.isEmpty()) return Set.of();
        return new LinkedHashSet<>(Arrays.asList(joined.split(" ")));
    }

    @Override
    public void addReportedEvent(UINode node, String kind) {
        // ASKED AND ANSWERED HERE, not at the client. "Can this kind of widget report X" is a fact
        // about the class and lives on its contract; "was this one asked to" is the session's, and
        // is what the attribute below records. Before this check a session could ask any node for any
        // string: the request was described, travelled, and hit a `default` arm on the far side that
        // logged it could not observe such a thing and carried on.
        WidgetContract<UINode> contract = WidgetContracts.of(node);
        if (contract == null) {
            throw new IllegalStateException(node.tagName() + " has no WidgetContract, so there is "
                    + "nothing it can be asked to report");
        }
        if (!contract.eventKinds().contains(kind)) {
            throw new IllegalArgumentException(node.tagName() + " cannot report \"" + kind
                    + "\" -- its contract declares " + contract.eventKinds());
        }
        Set<String> kinds = new LinkedHashSet<>(reportedEventsOf(node));
        if (!kinds.add(kind)) return;
        node.set(Attribute.REPORTS, String.join(" ", kinds));
    }

    @Override
    public TreeSource<UINode> sourceOver(UINode root) {
        return new UINodeTreeSource(root);
    }
}