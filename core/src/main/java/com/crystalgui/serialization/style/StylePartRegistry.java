package com.crystalgui.serialization.style;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.visual.transform.Transform;

/**
 * Which composite values divide, and how — {@link StyleParts} by value type.
 *
 * <pre>{@code
 * StyleParts<Transform> parts = StylePartRegistry.forProperty(StylePropertyRegistry.TRANSFORM);
 * if (parts != null) { ... }   // null means the property is only ever copied whole
 * }</pre>
 *
 * <p><b>By TYPE, exactly as {@link StyleValueCodecs} keys its codecs</b>, and for the same reason: how a
 * value divides is a fact about the value, not about the property holding it. Anything that ever holds a
 * {@code Transform} divides the same way, whether it is called {@code transform} or something a
 * consumer adds later.</p>
 *
 * <p>Null is the ordinary answer. A property with no entry here is offered whole, which is right for
 * every scalar and for any composite nobody has found a reason to take apart.</p>
 */
public final class StylePartRegistry {

    private static final Map<Class<?>, StyleParts<?>> BY_TYPE = new LinkedHashMap<>();

    static {
        BY_TYPE.put(Transform.class, TransformParts.INSTANCE);
        BY_TYPE.put(CgUiDrawable.class, DrawableParts.INSTANCE);
    }

    private StylePartRegistry() {
    }

    /** How {@code property}'s value divides, or null when it is only ever copied whole. */
    @SuppressWarnings("unchecked")
    @Nullable
    public static <V> StyleParts<V> forProperty(StyleProperty<V> property) {
        return (StyleParts<V>) BY_TYPE.get(property.type);
    }
}
