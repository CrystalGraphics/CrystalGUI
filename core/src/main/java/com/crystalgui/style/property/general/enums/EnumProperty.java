package com.crystalgui.style.property.general.enums;

import java.util.Locale;

import com.crystalgui.style.property.StyleProperty;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.List;

@Accessors(chain = true)
public class EnumProperty<T extends Enum<T>> extends StyleProperty<T> {
    @Setter
    private List<T> candidates;

    public EnumProperty(String name, Class<T> clazz, T initialValue) {
        this(name, clazz, initialValue, List.of(clazz.getEnumConstants()));
    }

    public EnumProperty(String name, Class<T> clazz, T initialValue, List<T> candidates) {
        super(name, clazz, initialValue, EnumValue.of(clazz));
        this.candidates = Collections.unmodifiableList(candidates);
    }

    /**
     * The CSS keyword, not the Java constant: {@code space-between}, never {@code SPACE_BETWEEN}.
     *
     * <p>The default writer is {@link String#valueOf}, which answers the constant's own name — and the
     * parser is case-insensitive, so it reads back and nothing failed. What it produced was CSS nobody
     * writes: every sheet in this engine says {@code align-items: center}, and a value copied off an
     * element came back {@code CENTER}.</p>
     */
    @Override
    public String write(T value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }


}
