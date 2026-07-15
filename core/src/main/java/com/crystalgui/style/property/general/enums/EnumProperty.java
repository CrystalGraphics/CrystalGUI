package com.crystalgui.style.property.general.enums;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.texture.CgUiDrawable;
import lombok.Setter;
import lombok.experimental.Accessors;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

@Accessors(chain = true)
public class EnumProperty<T extends Enum<T>> extends StyleProperty<T> {
    @Setter
    private List<T> candidates;
    @Setter
    @Nullable
    private Function<T, CgUiDrawable> iconProvider;

    public EnumProperty(String name, Class<T> clazz, T initialValue) {
        this(name, clazz, initialValue, List.of(clazz.getEnumConstants()));
    }

    public EnumProperty(String name, Class<T> clazz, T initialValue, List<T> candidates) {
        super(name, clazz, initialValue, EnumValue.of(clazz));
        this.candidates = Collections.unmodifiableList(candidates);
    }


}
