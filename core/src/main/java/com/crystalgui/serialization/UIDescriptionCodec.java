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

    /**
     * Where a live description's ids go while one is being decoded.
     *
     * <p>A thread-local rather than a parameter because {@link Codec#decode} has a fixed shape and this
     * is the one caller that needs more than it offers. Set for the length of one
     * {@link #decodeLive} call and always cleared -- decoding is on the frame thread, which owns the
     * tree, so there is exactly one decode in flight.</p>
     */
    private static final ThreadLocal<java.util.function.ObjIntConsumer<UIElement>> LIVE_IDS =
            new ThreadLocal<>();

    /** Decodes a live description, reporting each element's id to {@code idSink}. */
    public static <T> UIElement decodeLive(DynamicOps<T> ops, T input,
                                           java.util.function.ObjIntConsumer<UIElement> idSink) {
        LIVE_IDS.set(idSink);
        try {
            return CODEC.decode(ops, input);
        } finally {
            LIVE_IDS.remove();
        }
    }

    private UIDescriptionCodec() {
    }

    /**
     * Encodes {@code element}'s subtree with each described element's <b>id written into it</b>.
     *
     * <p>A <b>live</b> description, as opposed to the pristine one {@link #CODEC} produces. Two
     * encodings exist because they answer different questions:</p>
     *
     * <ul>
     *   <li><b>Pristine</b> — no ids, so it is a pure description of a UI. That is what makes it
     *       content-addressable: two windows showing the same thing hash the same, so re-opening costs
     *       one small packet instead of a tree. It is what {@code open()} sends.</li>
     *   <li><b>Live</b> — carries {@code nid}, for a viewer joining a window that has already been
     *       reshaped. Ids stopped being derivable from position, so a newcomer cannot compute the ones
     *       the existing viewers hold; it has to be told them, or every id it derived would name a
     *       different element and no message would land where it was meant to.</li>
     * </ul>
     *
     * <p>A live description hashes to something no pristine one will match, which is correct rather
     * than unfortunate: a reshaped window was never going to share another window's cache entry.</p>
     */
    public static <T> T encodeLive(DynamicOps<T> ops, UIElement element,
                                   java.util.function.ToIntFunction<UIElement> idOf) {
        T encoded = CODEC.encode(ops, element);
        return withIds(ops, encoded, element, idOf);
    }

    private static <T> T withIds(DynamicOps<T> ops, T encoded, UIElement element,
                                 java.util.function.ToIntFunction<UIElement> idOf) {
        java.util.Map<T, T> fields = new java.util.LinkedHashMap<>(ops.getMapValue(encoded));
        fields.put(ops.createString("nid"), ops.createNumber(idOf.applyAsInt(element)));

        java.util.List<UIElement> children = element.describedChildrenFor();
        if (!children.isEmpty()) {
            T rawChildren = fields.get(ops.createString("children"));
            if (rawChildren != null) {
                java.util.List<T> encodedChildren = ops.getListValue(rawChildren);
                java.util.List<T> rebuilt = new java.util.ArrayList<>(encodedChildren.size());
                for (int i = 0; i < encodedChildren.size() && i < children.size(); i++) {
                    rebuilt.add(withIds(ops, encodedChildren.get(i), children.get(i), idOf));
                }
                fields.put(ops.createString("children"), ops.createList(rebuilt));
            }
        }
        return ops.createMap(fields);
    }

    /**
     * The identity fields, on their own — id, classes, enabled, hit-test, focus policy.
     *
     * <p>Exists so an <b>attribute delta</b> and a description agree by construction rather than by two
     * people remembering the same five fields. These are the inputs to the far side's cascade, and
     * before M2 they were collected into a dirty set and <em>never sent at all</em> — so disabling a
     * button after the window opened did nothing on the other side, forever.</p>
     */
    public static <T> T encodeAttributes(DynamicOps<T> ops, UIElement element) {
        Codecs.MapCodecBuilder<T> out = Codecs.map(ops);
        out.optional("id", Codecs.STRING, element.getId(), "");
        out.optionalList("class", Codecs.STRING, publicClassesOf(element));
        int flags = (element.isEnabled() ? FLAG_ENABLED : 0) | (element.isHitTest() ? FLAG_HIT_TEST : 0);
        out.optional("flags", Codecs.INT, flags, DEFAULT_FLAGS);
        out.optional("focus", Codecs.enumOf(FocusPolicy.class), element.getFocusPolicy(), FocusPolicy.NONE);
        return out.build();
    }

    /** Applies what {@link #encodeAttributes} wrote. Classes are REPLACED, not merged. */
    public static <T> void applyAttributes(DynamicOps<T> ops, T value, UIElement element) {
        Codecs.MapCodecReader<T> in = Codecs.read(ops, value);
        element.setId(in.optional("id", Codecs.STRING, ""));

        // Replaced wholesale: a class REMOVED on the server has to come off here, and a delta carrying
        // only what is present cannot express a removal any other way.
        for (String existing : publicClassesOf(element)) element.removeClass(existing);
        for (String cls : in.optionalList("class", Codecs.STRING)) element.addClass(cls);

        int flags = in.optional("flags", Codecs.INT, DEFAULT_FLAGS);
        element.setEnabled((flags & FLAG_ENABLED) != 0);
        element.setHitTest((flags & FLAG_HIT_TEST) != 0);
        element.setFocusPolicy(in.optional("focus", Codecs.enumOf(FocusPolicy.class), FocusPolicy.NONE));
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

            List<UIElement> children = input.describedChildrenFor();
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

            int nid = in.optional("nid", Codecs.INT, -1);
            if (nid >= 0 && LIVE_IDS.get() != null) LIVE_IDS.get().accept(element, nid);

            String id = in.optional("id", Codecs.STRING, "");
            if (!id.isEmpty()) element.setId(id);
            for (String cls : in.optionalList("class", Codecs.STRING)) element.addClass(cls);

            T style = in.raw("style");
            if (style != null) InlineStyleCodec.decodeInto(ops, style, element);

            int flags = in.optional("flags", Codecs.INT, DEFAULT_FLAGS);
            element.setEnabled((flags & FLAG_ENABLED) != 0);
            element.setHitTest((flags & FLAG_HIT_TEST) != 0);
            element.setFocusPolicy(in.optional("focus", Codecs.enumOf(FocusPolicy.class), FocusPolicy.NONE));

            for (String kind : in.optionalList("events", Codecs.STRING)) element.addReportedEvent(kind);

            T children = in.raw("children");
            if (children != null) {
                List<T> raw = ops.getListValue(children);
                if (!raw.isEmpty() && !element.acceptsDescribedChildrenFor()) {
                    throw new CodecException("<" + tag + "> does not accept described children, but its "
                            + "description carries " + raw.size() + " — its internals are rebuilt by its "
                            + "constructor and must not be serialized");
                }
                for (T child : raw) element.addDescribedChildFrom(decode(ops, child));
            }

            // STATE AFTER CHILDREN, and this ordering is load-bearing. A widget's state is routinely an
            // index INTO its children -- a TabView's selected tab, a Dropdown's selected option -- and an
            // index applied to an empty widget is refused as out-of-range and silently lost. Nothing
            // needs the reverse order: state that does not reference children is unaffected by it, so
            // there is no trade here, only a correction. The same argument Slider's own readState makes
            // about range before value, one level up.
            T state = in.raw("state");
            if (state != null) element.readStateFrom(new StateMap<>(ops, state));
            return element;
        }
    };

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
