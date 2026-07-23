package com.crystalgui.style.property;

import com.crystalgui.style.property.general.bools.BoolValue;
import com.crystalgui.style.property.general.enums.EnumProperty;
import com.crystalgui.style.property.general.floats.FloatProperty;
import com.crystalgui.style.property.general.ints.IntProperty;
import com.crystalgui.style.property.general.strings.StringValue;
import com.crystalgui.style.property.visual.OverflowClip;
import com.crystalgui.style.property.visual.color.ColorProperty;
import com.crystalgui.style.property.visual.texture.TextureProperty;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.style.transition.TransitionSpec;
import com.crystalgui.style.transition.TransitionValue;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class StylePropertyRegistry {
    private static final Map<String, StyleProperty<?>> PROPERTIES_BY_NAME = new ConcurrentHashMap<>();
    private static volatile AtomicReferenceArray<StyleProperty<?>> PROPERTIES_BY_ID = new AtomicReferenceArray<>(256);

    // ── Properties ───────────────────────────────────────────────────────────

    public static final StyleProperty<CgUiDrawable> BACKGROUND = create("background", CgUiDrawable.EMPTY);
    public static final StyleProperty<CgUiDrawable> OVERLAY = create("overlay", CgUiDrawable.EMPTY);
    // Distinct from BACKGROUND (a drawable) and COLOR (inherited, meant for text) — matches real CSS,
    // where `background-color` is its own independent fill layer. Not inherited.
    // Unset = white, a multiplicative identity for the tint model in UIElement.paintSelf — "no
    // background-color set" must mean "no visible change", not fade-toward-transparent-black.
    public static final StyleProperty<Integer> BACKGROUND_COLOR = create(new ColorProperty("background-color", 0xFFFFFFFF));
    public static final StyleProperty<Float> OPACITY = create("opacity", 1f).setRange(0f, 1f);
    // Matches real CSS: `color` inherits by default. Layout/box-model properties below (z-index,
    // clip, and everything in LayoutProperties) do not — none of them are inherited in real CSS either.
    public static final StyleProperty<Integer> COLOR = create(new ColorProperty("color", -1)).setInheritable(true);
    public static final StyleProperty<Integer> Z_INDEX = create("z-index", 0).addListener((elem, prop, oldVal, newVal) -> {
        if (elem.getParent() != null) {
            elem.getParent().getRuntimeCache().sortedChildren.invalidate();
        }
    });
    public static final StyleProperty<OverflowClip> CLIP = create("clip", OverflowClip.class, OverflowClip.NONE);
    public static final StyleProperty<CgUiDrawable> MASK = create("mask", CgUiDrawable.EMPTY);
    // border-radius itself is pure parse-time shorthand syntax expanding to the 8 real longhands in
    // BorderRadiusProperties (see BorderRadiusShorthand) — not a registered property here, matching
    // how margin/padding/border-width work (BoxEdgeShorthands).
    public static final StyleProperty<Integer> BORDER_COLOR = create(new ColorProperty("border-color", 0xFF000000));
    public static final StyleProperty<List<TransitionSpec>> TRANSITION =
            create("transition", List.of(), TransitionValue::new);

    // ── Registry infrastructure ──────────────────────────────────────────────

    public static <T> void register(StyleProperty<T> property) {
        var prev = PROPERTIES_BY_NAME.putIfAbsent(property.name, property);
        if (prev != null) {
            throw new IllegalArgumentException("A style property named '" + property.name + "' already exists (id="
                    + prev.id + ")");
        }
        ensureCapacity(property.id);
        var existing = PROPERTIES_BY_ID.get(property.id);
        if (existing != null) {
            PROPERTIES_BY_NAME.remove(property.name, property);
            throw new IllegalArgumentException("A style property with id " + property.id +
                    " already exists: name='" + existing.name + "'");
        }
        PROPERTIES_BY_ID.set(property.id, property);
    }

    public static synchronized void ensureCapacity(int id) {
        int oldLen = PROPERTIES_BY_ID.length();
        if (id < oldLen) return;
        int newLen = oldLen;
        while (newLen <= id) newLen <<= 1;

        var newArr = new AtomicReferenceArray<StyleProperty<?>>(newLen);
        for (int i = 0; i < oldLen; i++) {
            newArr.set(i, PROPERTIES_BY_ID.get(i));
        }
        PROPERTIES_BY_ID = newArr;
    }

    public static Collection<StyleProperty<?>> all() {
        return PROPERTIES_BY_NAME.values();
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <T> StyleProperty<T> byName(String name) {
        return (StyleProperty<T>) PROPERTIES_BY_NAME.get(name);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <T> StyleProperty<T> byId(int id) {
        if (id < 0 || id >= PROPERTIES_BY_ID.length()) return null;
        return (StyleProperty<T>) PROPERTIES_BY_ID.get(id);
    }

    // ── Factory helpers ──────────────────────────────────────────────────────

    public static <T, P extends StyleProperty<T>> P create(P property) {
        register(property);
        return property;
    }

    public static FloatProperty create(String name, float initialValue) {
        return create(new FloatProperty(name, initialValue));
    }

    public static <T extends Enum<T>> EnumProperty<T> create(String name, Class<T> clazz, T initialValue) {
        return create(new EnumProperty<>(name, clazz, initialValue));
    }

    public static <T extends Enum<T>> EnumProperty<T> create(String name, Class<T> clazz, T initialValue, List<T> candidates) {
        return create(new EnumProperty<>(name, clazz, initialValue, candidates));
    }

    public static <T> StyleProperty<T> create(String name, T initialValue, StyleProperty.ValueParser<T> valueParser) {
        var handler = StyleProperty.of(name, initialValue, valueParser);
        register(handler);
        return handler;
    }

    public static <T> StyleProperty<T> create(String name, Class<T> clazz, T initialValue, StyleProperty.ValueParser<T> valueParser) {
        var handler = StyleProperty.of(name, clazz, initialValue, valueParser);
        register(handler);
        return handler;
    }


    public static TextureProperty create(String name, CgUiDrawable initialValue) {
        return create(new TextureProperty(name, initialValue));
    }

    public static StyleProperty<Boolean> create(String name, boolean initialValue) {
        return create(name, Boolean.class, initialValue, BoolValue::new);
    }

//    public static TooltipsProperty create(String name, Tooltips initialValue) {
//        return create(new TooltipsProperty(name, initialValue));
//    }
//
//    public static TransitionProperty create(String name, Transition initialValue) {
//        return create(new TransitionProperty(name, initialValue));
//    }
//
//    public static Transform2DProperty create(String name, Transform2D initialValue) {
//        return create(new Transform2DProperty(name, initialValue));
//    }

    public static IntProperty create(String name, int initialValue) {
        return create(new IntProperty(name, initialValue));
    }

    public static ColorProperty createColor(String name, int initialValue) {
        return create(new ColorProperty(name, initialValue));
    }


    public static StyleProperty<String> create(String name, String initialValue) {
        return create(name, String.class, initialValue, StringValue::new);
    }

}
