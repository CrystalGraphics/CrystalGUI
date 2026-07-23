package com.crystalgui.style.property;

import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.general.bools.BoolValue;
import com.crystalgui.style.property.general.enums.EnumProperty;
import com.crystalgui.style.property.general.floats.FloatProperty;
import com.crystalgui.style.property.general.ints.IntProperty;
import com.crystalgui.style.property.general.strings.StringValue;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.OverflowClip;
import com.crystalgui.style.property.visual.color.ColorProperty;
import com.crystalgui.style.property.visual.texture.TextureProperty;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSprite;
import com.crystalgui.style.transition.TransitionSpec;
import com.crystalgui.style.transition.TransitionValue;
import dev.vfyjxf.taffy.style.TaffyDimension;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class StylePropertyRegistry {
    private static final Map<String, StyleProperty<?>> PROPERTIES_BY_NAME = new ConcurrentHashMap<>();
    private static volatile AtomicReferenceArray<StyleProperty<?>> PROPERTIES_BY_ID = new AtomicReferenceArray<>(256);

    /**
     * Distinguishes the MIN_WIDTH/MIN_HEIGHT candidates {@link #applyBackgroundImpliedMinSize} pushes
     * from any other DEFAULT-origin candidate on the same properties (e.g. one left behind by
     * {@code moveInlineAsDefault()}) — a plain {@code origin() == DEFAULT} predicate would risk
     * deleting those too. Real stylesheet source orders are non-negative, so this never collides.
     */
    private static final int BACKGROUND_IMPLIED_MIN_SIZE_SOURCE_ORDER = Integer.MIN_VALUE;

    public static final StyleProperty<CgUiDrawable> BACKGROUND = create("background", CgUiDrawable.EMPTY)
            .addListener(StylePropertyRegistry::applyBackgroundImpliedMinSize);
    public static final StyleProperty<CgUiDrawable> OVERLAY = create("overlay", CgUiDrawable.EMPTY);
    // Distinct from BACKGROUND (a drawable) and COLOR (inherited, meant for text) — matches real CSS,
    // where `background-color` is its own independent fill layer. Not inherited.
    public static final StyleProperty<Integer> BACKGROUND_COLOR = create(new ColorProperty("background-color", 0x00000000));
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
    // Outer corner radius for SDF rounded rects (also read by rounded-corner hit-testing) — not
    // inherited, matches real CSS border-radius.
    public static final StyleProperty<Float> BORDER_RADIUS = create("border-radius", 0f).setRange(0f, Float.MAX_VALUE);
    public static final StyleProperty<Integer> BORDER_COLOR = create(new ColorProperty("border-color", 0xFF000000));
    public static final StyleProperty<List<TransitionSpec>> TRANSITION =
            create("transition", List.<TransitionSpec>of(), TransitionValue::new);
//    public static final StyleProperty<Tooltips> TOOLTIPS = create("tooltips", Tooltips.empty());
//    public static final StyleProperty<Transform2D> TRANSFORM_2D = create("transform", Transform2D.identity());

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

    /**
     * {@code background}'s {@link StyleProperty.StyleChangeListener}: when the new drawable is a
     * {@link CgUiSprite} with a real 9-slice border, pushes a low-priority ({@link StyleOrigin#DEFAULT})
     * min-width/min-height floor equal to the border sums — so an element never collapses smaller than
     * its own 9-slice border can render. Uses {@link com.crystalgui.style.ElementStyle#replaceCandidates}
     * so both properties update atomically and any explicit user-set min-width/min-height (at any
     * higher origin) naturally overrides it.
     */
    private static void applyBackgroundImpliedMinSize(com.crystalgui.ui.UIElement element,
                                                        StyleProperty<CgUiDrawable> property,
                                                        @Nullable CgUiDrawable oldVal,
                                                        @Nullable CgUiDrawable newVal) {
        List<StyleSlot<?>> impliedSlots = new ArrayList<>();
        if (newVal instanceof CgUiSprite sprite && sprite.hasBorder()) {
            impliedSlots.add(StyleSlot.of(LayoutProperties.MIN_WIDTH, StyleOrigin.DEFAULT,
                    0, BACKGROUND_IMPLIED_MIN_SIZE_SOURCE_ORDER, TaffyDimension.length(sprite.borderSumX())));
            impliedSlots.add(StyleSlot.of(LayoutProperties.MIN_HEIGHT, StyleOrigin.DEFAULT,
                    0, BACKGROUND_IMPLIED_MIN_SIZE_SOURCE_ORDER, TaffyDimension.length(sprite.borderSumY())));
        }
        element.getStyle().replaceCandidates(
                slot -> slot.origin() == StyleOrigin.DEFAULT
                        && slot.sourceOrder() == BACKGROUND_IMPLIED_MIN_SIZE_SOURCE_ORDER,
                impliedSlots);
    }

//    public static StyleProperty<Component> create(String name, Component initialValue) {
//        return create(name, Component.class, ComponentSerialization.CODEC, initialValue, ComponentValue::new);
//    }

//    public static StyleProperty<Identifier> create(String name, Identifier initialValue) {
//        return create(name, Identifier.class, Identifier.CODEC, initialValue, ResourceLocationValue::new);
//    }

}
