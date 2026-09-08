package com.crystalgui.style.property.layout.grid;

import dev.vfyjxf.taffy.style.GridTemplateComponent;
import dev.vfyjxf.taffy.style.NamedGridLine;

import java.util.List;

/**
 * {@code grid-template-rows} / {@code -columns}: the tracks <b>in source order</b>, and the lines named
 * between them.
 *
 * <p>{@code components} is the whole template — a plain track is a
 * {@link GridTemplateComponent#single} and a {@code repeat(...)} is a repetition, in the order they
 * were written. That ordering is the thing to preserve: {@code 100px repeat(2, 1fr) 50px} is three
 * components and is not the same grid as {@code 100px 50px repeat(2, 1fr)}.</p>
 *
 * <p>It used to also carry a {@code simples} list of just the plain tracks, which was a lossy duplicate
 * of the same data — every reader that wanted the real template used the ordered list, Taffy included
 * ({@code gridTemplateRowsWithRepeat} takes precedence over its flat field), and the projection existed
 * only to be kept in step. A {@link NamedGridLine} carries the component index it sits before.</p>
 */
public record GridTemplate(List<GridTemplateComponent> components, List<NamedGridLine> names) {

    public GridTemplate {
        components = List.copyOf(components);
        names = List.copyOf(names);
    }

    public static final GridTemplate EMPTY = new GridTemplate(List.of(), List.of());
}
