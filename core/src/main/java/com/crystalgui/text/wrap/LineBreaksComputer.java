package com.crystalgui.text.wrap;

/**
 * Decides where one row's text breaks — VS Code's {@code ILineBreaksComputer}.
 *
 * <p>An interface for the same reason VS Code's is: <b>where a line breaks depends on how it will be
 * measured</b>, and there is more than one honest answer. VS Code ships a monospace computer (fast,
 * assumes every glyph is one column) and a DOM one (renders into a hidden element and reads the real
 * layout back). This engine ships {@link MonospaceLineBreaks} and a shaped counterpart backed by
 * CrystalGraphics' {@code CgLineBreaker}.</p>
 *
 * <p>The split is also what keeps the projection layer headless. {@link LineProjection} and
 * {@link ProjectedLines} are arithmetic over an {@code int[]} and load on a dedicated server; only an
 * implementation of <em>this</em> needs fonts.</p>
 */
@FunctionalInterface
public interface LineBreaksComputer {

    /**
     * Where {@code line} breaks, as a projection.
     *
     * <p>Never null: a row that does not wrap returns {@link LineProjection#unwrapped}. Returning null
     * for the common case is what VS Code does and it pushes a null check into every caller of every
     * conversion — worth diverging from, since the un-wrapped projection is two ints.</p>
     */
    LineProjection project(String line);

    /** The computer used when soft wrap is off — every row is one view line. */
    static LineBreaksComputer none() {
        return line -> LineProjection.unwrapped(line.length());
    }
}
