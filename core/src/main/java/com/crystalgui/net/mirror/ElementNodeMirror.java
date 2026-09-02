package com.crystalgui.net.mirror;

import com.crystalgui.ui.dom.ElementTreeSource;
import com.crystalgui.ui.dom.TreeSource;
import java.util.Set;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.serialization.UIDescriptionCodec;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.ui.UIElement;
import java.util.Map;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;
import javax.annotation.Nullable;

/**
 * {@link NodeMirror} over today's {@link UIElement} tree.
 *
 * <p>The whole of what the mirror knows about this engine, and therefore the whole of what a second
 * engine has to supply: this class plus a {@link com.crystalgui.ui.dom.TreeSource} implementation. It
 * is deliberately thin — every method is a call into a codec that already existed.</p>
 *
 * @param <T> the serialization form
 */
public final class ElementNodeMirror<T> implements NodeMirror<UIElement, T> {

    private final DynamicOps<T> ops;

    public ElementNodeMirror(DynamicOps<T> ops) {
        this.ops = ops;
    }

    @Override
    public T describe(UIElement node) {
        return UIDescriptionCodec.CODEC.encode(ops, node);
    }

    @Override
    public T describeLive(UIElement node, ToIntFunction<UIElement> idOf) {
        return UIDescriptionCodec.encodeLive(ops, node, idOf);
    }

    @Override
    public UIElement decode(T described) {
        return UIDescriptionCodec.CODEC.decode(ops, described);
    }

    @Override
    public UIElement decodeLive(T described, ObjIntConsumer<UIElement> idSink) {
        return UIDescriptionCodec.decodeLive(ops, described, idSink);
    }

    @Override
    @Nullable
    public T encodeState(UIElement node) {
        StateMap<T> state = new StateMap<>(ops);
        node.writeStateTo(state);
        return state.encode();
    }

    @Override
    public void applyState(T value, UIElement node) {
        node.readStateFrom(new StateMap<>(ops, value));
    }

    @Override
    @Nullable
    public T encodeAttributes(UIElement node) {
        return UIDescriptionCodec.encodeAttributes(ops, node);
    }

    @Override
    public void applyAttributes(T value, UIElement node) {
        UIDescriptionCodec.applyAttributes(ops, value, node);
    }

    @Override
    @Nullable
    public T encodeInlineStyle(UIElement node) {
        T style = InlineStyleCodec.encode(ops, node);
        // An EMPTY map rather than null: "no inline style" is a real value that has to travel, because
        // a candidate removed on the server has to be removed here too. A delta carrying only what is
        // present cannot say "this is gone".
        return style == null ? ops.createMap(Map.of()) : style;
    }

    @Override
    public void applyInlineStyle(T value, UIElement node) {
        InlineStyleCodec.decodeInto(ops, value, node);
    }

    @Override
    public void insertChild(UIElement parent, UIElement child, int index) {
        parent.addDescribedChildAt(child, index);
    }

    @Override
    public void removeChild(UIElement parent, UIElement child) {
        parent.removeChild(child);
    }

    @Override
    public Set<String> reportedEventsOf(UIElement node) {
        return node.getReportedEvents();
    }

    @Override
    public void addReportedEvent(UIElement node, String kind) {
        node.addReportedEvent(kind);
    }

    @Override
    public TreeSource<UIElement> sourceOver(UIElement root) {
        return new ElementTreeSource(root);
    }
}