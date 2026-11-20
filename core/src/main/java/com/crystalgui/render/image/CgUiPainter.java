package com.crystalgui.render.image;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.api.canvas.CgCanvas;

/**
 * Draws by hand into a box: what a stylesheet value cannot describe. CSS's Paint API, {@code paint(name)}: a painter
 * registered under a name is a {@code background}, {@code overlay}, {@code mask} or {@code outline} in any sheet.
 *
 * <pre>{@code
 * // A widget's own field, set from Java
 * element.generalStyle(g -> g.background(CgUiImage.painted(new HueRing(0.12f))));
 *
 * // A mod's painter, named once, then written in CSS as `background: paint(mymod:stripes)`
 * CgUiPainter.register("mymod:stripes", (canvas, w, h, color) -> {
 *     for (float x = 0; x < w; x += 8) canvas.box(x, 0, 4, h).fill(color).draw();
 * });
 * }</pre>
 *
 * <ul>
 *   <li>The canvas's origin is the box's top-left; draw within {@code width x height}. The caller has saved the
 *       canvas's state and restores it after.</li>
 *   <li>{@code color} is the element's {@code color}: a painter that wants to follow the theme draws in it.</li>
 *   <li>A painter is part of a style value, so give it value equality (a record) when two of it can be equal: a
 *       repeated write of an equal value then restarts no transition.</li>
 *   <li>It runs on the document's paint thread, once per box per frame it is drawn: allocate nothing.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgUiPainter {

    /** Draws into a {@code width x height} box at the canvas's origin. */
    void paint(CgCanvas canvas, float width, float height, int color);

    /** Whether everything this draws covers any pixel at most once, so an opacity can fold into it. */
    default boolean onePrimitive() {
        return false;
    }

    /** Painters by the name CSS's {@code paint(name)} gives them. Threads: concurrent, read on any paint thread. */
    Map<String, CgUiPainter> NAMED = new ConcurrentHashMap<>();

    /** Names {@code painter} for {@code paint(name)} in stylesheets; a second registration under a name replaces it. */
    static void register(String name, CgUiPainter painter) {
        if (name.isEmpty()) throw new IllegalArgumentException("a painter needs a name");
        NAMED.put(name, painter);
    }

    /** The painter registered as {@code name}, or null. */
    @Nullable
    static CgUiPainter registered(String name) {
        return NAMED.get(name);
    }

    /**
     * The painter registered as {@code name} when it draws: a sheet parsed before a mod registers its painter draws
     * it once it does. Equal by name.
     */
    record ByName(String name) implements CgUiPainter {
        @Override
        public void paint(CgCanvas canvas, float width, float height, int color) {
            CgUiPainter painter = NAMED.get(name);
            if (painter != null) painter.paint(canvas, width, height, color);
        }

        @Override
        public boolean onePrimitive() {
            CgUiPainter painter = NAMED.get(name);
            return painter != null && painter.onePrimitive();
        }
    }
}
