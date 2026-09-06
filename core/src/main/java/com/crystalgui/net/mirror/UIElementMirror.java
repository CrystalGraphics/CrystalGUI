package com.crystalgui.net.mirror;

import com.crystalgui.style.Styleable;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.dom.*;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.ui.dom.UIElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;
import javax.annotation.Nullable;

/**
 * {@link NodeMirror} over the new engine's {@link UIElement} tree — the second engine's whole networking
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
public final class UIElementMirror<T> implements NodeMirror<UIElement, T> {

    /**
     * What a node's fields are called — one encoder, two dialects.
     *
     * <p>{@link #WIRE} is short because every packet pays for it and nobody reads it;
     * {@link #DOCUMENT} is readable because a {@code .cgui} file is edited by hand as often as by the
     * builder. Nothing else differs: the same encoder writes both, so a document IS a description and a
     * window built from one hashes to the file on disk.</p>
     *
     * <pre>{@code
     * new UIElementMirror<>(JsonOps.INSTANCE)                        // the wire
     * new UIElementMirror<>(JsonOps.INSTANCE, Keys.DOCUMENT)         // a .cgui file
     * }</pre>
     *
     * <p><b>The content hash is computed over {@link #WIRE} only.</b> A description is content-addressed
     * and the key table is not part of what it says, so re-spelling the keys must never move a hash.</p>
     */
    public record Keys(String name, String id, String classes, String attributes, String style,
                       String state, String children, String nid, boolean bareNames) {

        /** What travels. Short by design, and always fully qualified. */
        public static final Keys WIRE = new Keys("n", "i", "c", "a", "s", "v", "k", "nid", false);

        /**
         * What a {@code .cgui} file is written in.
         *
         * <p>{@code bareNames}: a kind in the engine's own namespace is written {@code text} rather than
         * {@code crystalgui:text}, which is what a hand-author types. {@code Name.parse} reads either, so
         * both dialects decode to the same tree and therefore to the same hash.</p>
         */
        public static final Keys DOCUMENT = new Keys(
                "kind", "id", "class", "attrs", "style", "state", "children", "nid", true);
    }

    private final DynamicOps<T> ops;
    private final Keys keys;

    /** The wire dialect. */
    public UIElementMirror(DynamicOps<T> ops) {
        this(ops, Keys.WIRE);
    }

    public UIElementMirror(DynamicOps<T> ops, Keys keys) {
        this.ops = ops;
        this.keys = keys;
    }

    /** Which dialect this instance reads and writes. */
    public Keys keys() {
        return keys;
    }

    // ── Descriptions ─────────────────────────────────────────────────────────

    @Override
    public T describe(UIElement node) {
        return write(node, null, null);
    }

    /**
     * Describes {@code node} and everything under it, writing what {@code extras} holds about each.
     *
     * <p>{@link Keys#DOCUMENT} only — the wire dialect carries no design or binding data, and being
     * handed some means a document encoder was pointed at a packet.</p>
     */
    public T describe(UIElement node, @Nullable DocumentExtras<T> extras) {
        requireDocumentDialect(extras);
        return write(node, null, extras);
    }

    @Override
    public T describeLive(UIElement node, ToIntFunction<UIElement> idOf) {
        return write(node, idOf, null);
    }

    private T write(UIElement node, @Nullable ToIntFunction<UIElement> idOf,
            @Nullable DocumentExtras<T> extras) {
        Map<T, T> fields = new LinkedHashMap<>();
        fields.put(key(keys.name()), ops.createString(spell(node.name())));
        if (!node.id().isEmpty()) fields.put(key(keys.id()), ops.createString(node.id()));
        if (!node.classes().isEmpty()) fields.put(key(keys.classes()), ops.createString(String.join(" ", node.classes())));
        T attributes = attributesOf(node);
        if (attributes != null) fields.put(key(keys.attributes()), attributes);
        // A description carries the whole node, not merely its shape. The order here is FIXED and the
        // optionals are OMITTED rather than written empty, because a description is content-addressed:
        // the same tree must encode byte-identically or its hash stops naming it.
        T style = InlineStyleCodec.encode(ops, node);
        if (style != null) fields.put(key(keys.style()), style);
        T state = encodeState(node);
        if (state != null && !ops.getMapValue(state).isEmpty()) fields.put(key(keys.state()), state);
        if (idOf != null) fields.put(key(keys.nid()), ops.createNumber(idOf.applyAsInt(node)));
        if (extras != null) {
            for (String extra : DocumentExtras.KEYS) {
                T value = extras.get(node, extra);
                if (value != null) fields.put(key(extra), value);
            }
        }
        List<UIElement> described = node.describedChildren();
        if (!described.isEmpty()) {
            List<T> children = new ArrayList<>(described.size());
            for (UIElement child : described) children.add(write(child, idOf, extras));
            fields.put(key(keys.children()), ops.createList(children));
        }
        return ops.createMap(fields);
    }

    @Override
    public UIElement decode(T described) {
        return read(described, null, null);
    }

    /**
     * Decodes a document, collecting each node's design, binding and hook data into {@code extras}.
     *
     * <p>Pass null to <b>strip</b> them, which is what {@code UiTemplate.inflate} does: the runtime has
     * no use for any of the three, and a tree built without them is the tree a player sees.</p>
     */
    public UIElement decode(T described, @Nullable DocumentExtras<T> extras) {
        requireDocumentDialect(extras);
        return read(described, null, extras);
    }

    @Override
    public UIElement decodeLive(T described, ObjIntConsumer<UIElement> idSink) {
        return read(described, idSink, null);
    }

    private UIElement read(T described, @Nullable ObjIntConsumer<UIElement> idSink,
            @Nullable DocumentExtras<T> extras) {
        Map<T, T> fields = ops.getMapValue(described);
        T name = fields.get(key(keys.name()));
        if (name == null) throw new IllegalArgumentException("A described node names its kind");
        UIElement node = UIElementRegistry.create(Name.parse(ops.getStringValue(name)));
        applyIdentity(fields, node);
        T style = fields.get(key(keys.style()));
        if (style != null) applyInlineStyle(style, node);
        T nid = fields.get(key(keys.nid()));
        if (nid != null && idSink != null) idSink.accept(node, ops.getNumberValue(nid).intValue());
        if (extras != null) {
            for (String extra : DocumentExtras.KEYS) extras.put(node, extra, fields.get(key(extra)));
        }
        T children = fields.get(key(keys.children()));
        if (children != null) {
            for (T child : ops.getListValue(children)) {
                node.adoptDescribedChild(read(child, idSink, extras));
            }
        }
        // STATE LAST, because some of it INDEXES INTO THE CHILDREN. A TabView's selection is an index
        // and a Dropdown's is an index into its options, so applied to an empty widget it clamps to
        // nothing and the widget arrives showing its first entry with the description perfectly
        // correct. Encode order is fixed for the content hash and is a separate question from this.
        T state = fields.get(key(keys.state()));
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
    public T encodeState(UIElement node) {
        WidgetContract<UIElement> contract = WidgetContracts.of(node);
        if (contract == null || !contract.carriesState()) return null;
        StateMap<T> state = new StateMap<>(ops);
        contract.write(node, state);
        return state.encode();
    }

    @Override
    public void applyState(T value, UIElement node) {
        WidgetContract<UIElement> contract = WidgetContracts.of(node);
        if (contract == null || !contract.carriesState()) return;
        // DECLARATION ORDER IS APPLY ORDER, and four widgets depend on it -- a Slider takes its range
        // before its value, or the value is clamped against the range it is replacing. The contract
        // holds that order; this must not re-sort or filter.
        contract.read(node, new StateMap<>(ops, value));
    }

    // ── Attributes (identity) ────────────────────────────────────────────────

    @Override
    @Nullable
    public T encodeAttributes(UIElement node) {
        Map<T, T> fields = new LinkedHashMap<>();
        fields.put(key(keys.id()), ops.createString(node.id()));
        fields.put(key(keys.classes()), ops.createString(String.join(" ", node.classes())));
        T attributes = attributesOf(node);
        fields.put(key(keys.attributes()), attributes != null ? attributes : ops.createMap(Map.of()));
        return ops.createMap(fields);
    }

    @Override
    public void applyAttributes(T value, UIElement node) {
        applyIdentity(ops.getMapValue(value), node);
    }

    @Nullable
    private T attributesOf(UIElement node) {
        Map<T, T> out = null;
        for (Attribute<?> key : node.setAttributes()) {
            if (!key.isCarried()) continue;
            if (out == null) out = new LinkedHashMap<>();
            out.put(ops.createString(key.name()), ops.createString(writeAttribute(node, key)));
        }
        return out == null ? null : ops.createMap(out);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String writeAttribute(UIElement node, Attribute key) {
        return key.write(node.get(key));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyIdentity(Map<T, T> fields, UIElement node) {
        T id = fields.get(key(keys.id()));
        if (id != null) node.setId(ops.getStringValue(id));
        T classes = fields.get(key(keys.classes()));
        if (classes != null) {
            List<String> wanted = new ArrayList<>();
            for (String c : ops.getStringValue(classes).split(" ")) if (!c.isEmpty()) wanted.add(c);
            for (String present : new ArrayList<>(node.classes())) {
                if (!wanted.contains(present)) node.removeClass(present);
            }
            for (String c : wanted) node.addClass(c);
        }
        T attributes = fields.get(key(keys.attributes()));
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
    public T encodeInlineStyle(UIElement node) {
        T style = InlineStyleCodec.encode(ops, node);
        return style == null ? ops.createMap(Map.of()) : style;
    }

    @Override
    public void applyInlineStyle(T value, UIElement node) {
        InlineStyleCodec.decodeInto(ops, value, node);
    }

    // ── Structure ────────────────────────────────────────────────────────────

    @Override
    public void insertChild(UIElement parent, UIElement child, int index) {
        parent.insertAt(index, child);
    }

    @Override
    public void removeChild(UIElement parent, UIElement child) {
        parent.remove(child);
    }

    private T key(String name) {
        return ops.createString(name);
    }

    /** A kind, as this dialect writes it. @see Keys#DOCUMENT */
    private String spell(Name name) {
        return keys.bareNames() && Name.DEFAULT_NAMESPACE.equals(name.namespace())
                ? name.local() : name.toString();
    }

    private void requireDocumentDialect(@Nullable DocumentExtras<T> extras) {
        if (extras != null && keys == Keys.WIRE) {
            throw new IllegalArgumentException("design, bind and on are document-only -- build this "
                    + "mirror with Keys.DOCUMENT, or pass no extras");
        }
    }

    /**
     * {@code Attribute.REPORTS}, space-joined — the same shape {@code classes} takes on the wire, and
     * for the same reason: a set of short identifiers is cheaper as one string than as a list, and the
     * attribute encoder already carries it with no special case.
     */
    @Override
    public Set<String> reportedEventsOf(UIElement node) {
        String joined = node.get(Attribute.REPORTS);
        if (joined == null || joined.isEmpty()) return Set.of();
        return new LinkedHashSet<>(Arrays.asList(joined.split(" ")));
    }

    @Override
    public void addReportedEvent(UIElement node, String kind) {
        // ASKED AND ANSWERED HERE, not at the client. "Can this kind of widget report X" is a fact
        // about the class and lives on its contract; "was this one asked to" is the session's, and
        // is what the attribute below records. Before this check a session could ask any node for any
        // string: the request was described, travelled, and hit a `default` arm on the far side that
        // logged it could not observe such a thing and carried on.
        WidgetContract<UIElement> contract = WidgetContracts.of(node);
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
    public TreeSource<UIElement> sourceOver(UIElement root) {
        return new UIElementTreeSource(root);
    }
}