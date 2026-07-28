package com.crystalgui.serialization;

import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.input.FocusPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializes a {@link UIElement} tree well enough for a client to rebuild it without running the
 * code that created it.
 *
 * <pre>
 * { tag, id?, class[]?, style{}?, flags?, focus?, state{}?, children[]? }
 * </pre>
 *
 * <h3>What the old codec got wrong</h3>
 * <p>Its predecessor captured tag, id, classes and children and nothing else — no styles, no widget
 * state — and had three defects that made it unusable over a wire:</p>
 * <ul>
 *   <li>{@code decode} called the <b>public</b> {@code addChild}, which the nine widgets with
 *       constructor-built internals refuse outright, so decoding a Button threw.</li>
 *   <li>{@code encode} wrote internal children, so a decode would have rebuilt a Button's label from
 *       the constructor <em>and</em> re-added the serialized copy.</li>
 *   <li>An unknown tag silently produced a bare {@link UIElement}, turning a typo or a missing
 *       registration into a styleless div rather than an error.</li>
 * </ul>
 *
 * <h3>Field order is fixed, and that matters</h3>
 * <p>Descriptions are content-addressed — a client caches them by a hash of their encoded bytes — so
 * encoding the same tree twice must produce byte-identical output. Every map here is insertion
 * ordered, absent optionals are omitted rather than written as null, and class lists preserve
 * insertion order.</p>
 */
public final class UIDescriptionCodec {

    /** Bit 0 = enabled, bit 1 = hit-testable. Both default on, so an ordinary element omits the field. */
    private static final int FLAG_ENABLED = 1;
    private static final int FLAG_HIT_TEST = 2;
    private static final int DEFAULT_FLAGS = FLAG_ENABLED | FLAG_HIT_TEST;

    private UIDescriptionCodec() {
    }

    public static final Codec<UIElement> CODEC = new Codec<UIElement>() {

        @Override
        public <T> T encode(DynamicOps<T> ops, UIElement input) {
            Codecs.MapCodecBuilder<T> out = Codecs.map(ops);

            out.field("tag", Codecs.STRING, input.tagName());
            out.optional("id", Codecs.STRING, input.getId(), "");
            out.optionalList("class", Codecs.STRING, publicClassesOf(input));

            T style = InlineStyleCodec.encode(ops, input);
            if (style != null) out.raw("style", style);

            int flags = (input.isEnabled() ? FLAG_ENABLED : 0) | (input.isHitTest() ? FLAG_HIT_TEST : 0);
            out.optional("flags", Codecs.INT, flags, DEFAULT_FLAGS);
            out.optional("focus", Codecs.enumOf(FocusPolicy.class), input.getFocusPolicy(), FocusPolicy.NONE);

            StateMap<T> state = new StateMap<>(ops);
            input.writeStateTo(state);
            if (!state.isEmpty()) out.raw("state", state.encode());

            // Which interactions the far side should report back. The handler itself never travels —
            // it stays a lambda on the session that declared it.
            out.optionalList("events", Codecs.STRING, List.copyOf(input.getReportedEvents()));

            List<UIElement> children = publicChildrenOf(input);
            if (!children.isEmpty()) {
                List<T> encoded = new ArrayList<>(children.size());
                for (UIElement child : children) encoded.add(encode(ops, child));
                out.raw("children", ops.createList(encoded));
            }
            return out.build();
        }

        @Override
        public <T> UIElement decode(DynamicOps<T> ops, T input) {
            Codecs.MapCodecReader<T> in = Codecs.read(ops, input);

            String tag = in.field("tag", Codecs.STRING);
            UIElement element;
            try {
                element = ElementRegistry.create(tag);
            } catch (IllegalArgumentException e) {
                throw new CodecException("Cannot rebuild element with tag '" + tag + "'", e);
            }

            String id = in.optional("id", Codecs.STRING, "");
            if (!id.isEmpty()) element.setId(id);
            for (String cls : in.optionalList("class", Codecs.STRING)) element.addClass(cls);

            T style = in.raw("style");
            if (style != null) InlineStyleCodec.decodeInto(ops, style, element);

            int flags = in.optional("flags", Codecs.INT, DEFAULT_FLAGS);
            element.setEnabled((flags & FLAG_ENABLED) != 0);
            element.setHitTest((flags & FLAG_HIT_TEST) != 0);
            element.setFocusPolicy(in.optional("focus", Codecs.enumOf(FocusPolicy.class), FocusPolicy.NONE));

            T state = in.raw("state");
            if (state != null) element.readStateFrom(new StateMap<>(ops, state));

            for (String kind : in.optionalList("events", Codecs.STRING)) element.addReportedEvent(kind);

            T children = in.raw("children");
            if (children != null) {
                List<T> raw = ops.getListValue(children);
                if (!raw.isEmpty() && !element.acceptsPublicChildren()) {
                    throw new CodecException("<" + tag + "> does not accept public children, but its "
                            + "description carries " + raw.size() + " — its internals are rebuilt by its "
                            + "constructor and must not be serialized");
                }
                for (T child : raw) element.addChild(decode(ops, child));
            }
            return element;
        }
    };

    /**
     * Public children only. A composite widget's internals are rebuilt by its constructor, so
     * serializing them would duplicate the whole structure on decode.
     */
    private static List<UIElement> publicChildrenOf(UIElement element) {
        List<UIElement> out = new ArrayList<>();
        for (UIElement child : element.getChildren()) {
            if (!child.isInternalUI()) out.add(child);
        }
        return out;
    }

    /**
     * Author classes only. {@code __name__} classes are a widget's own structural markers, applied by
     * its constructor and by its runtime logic ({@code __vertical__}, {@code __top__}), so the client
     * re-derives them. Sending them would let a stale one outlive the state that produced it.
     * Borrowed from LDLib2, which skips the same convention in its own serializer.
     */
    private static List<String> publicClassesOf(UIElement element) {
        List<String> out = new ArrayList<>();
        for (String cls : element.getClasses()) {
            if (cls.startsWith("__") && cls.endsWith("__")) continue;
            out.add(cls);
        }
        return out;
    }
}
