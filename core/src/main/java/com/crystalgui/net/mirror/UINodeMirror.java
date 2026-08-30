package com.crystalgui.net.mirror;

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
        if (idOf != null) fields.put(key(NID), ops.createNumber(idOf.applyAsInt(node)));
        if (!node.children().isEmpty()) {
            List<T> children = new ArrayList<>(node.children().size());
            for (UINode child : node.children()) children.add(write(child, idOf));
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
        T nid = fields.get(key(NID));
        if (nid != null && idSink != null) idSink.accept(node, ops.getNumberValue(nid).intValue());
        T children = fields.get(key(CHILDREN));
        if (children != null) {
            for (T child : ops.getListValue(children)) node.append(read(child, idSink));
        }
        return node;
    }

    // ── State ────────────────────────────────────────────────────────────────

    @Override
    @Nullable
    public T encodeState(UINode node) {
        return null;   // no plain node carries state; M6's widgets do, through their contracts
    }

    @Override
    public void applyState(T value, UINode node) {
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

    @Override
    @Nullable
    public T encodeInlineStyle(UINode node) {
        return ops.createMap(Map.of());
    }

    @Override
    public void applyInlineStyle(T value, UINode node) {
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
}
