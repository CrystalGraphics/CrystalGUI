package com.crystalgui.widget.collection.list;

import com.crystalgui.ui.dom.UIElement;

/**
 * Builds and fills the rows of a {@link ListView} — the contract that makes recycling safe.
 *
 * <h3>The split is the whole design</h3>
 * <p>{@link #createTemplate()} runs <b>once per recycled element</b> and builds structure: children,
 * classes, and — critically — every event listener. {@link #bind} runs on every scroll for every visible
 * row and does nothing but write data into a template that already exists.</p>
 *
 * <p>Ported from VS Code's {@code IListRenderer}, which is the best answer available to the question that
 * sinks naive recycling: <em>how does a consumer bind data without leaking the previous row's
 * listeners?</em> The answer here is that it cannot ask, because there is nowhere to put a listener in
 * {@code bind}. A rule you have to remember becomes a rule the API enforces.</p>
 *
 * <pre>{@code
 * list.setRenderer(new ListRenderer<Person>() {
 *     public UIElement createTemplate() {
 *         UIElement row = new UIElement();
 *         row.append(new UIText(""));
 *         row.onMouseDown.attachListener(...);   // ONCE, for the life of this element
 *         return row;
 *     }
 *     public void bind(Person item, int index, UIElement template) {
 *         ((UIText) template.children().get(0)).setText(item.name());   // data only
 *     }
 * });
 * }</pre>
 */
public interface ListRenderer<T> {

    /**
     * A blank row. Called once per pooled element, never per scroll — so this is where listeners,
     * children and classes belong, and where allocation is acceptable.
     */
    UIElement createTemplate();

    /**
     * Writes {@code item} into a template returned earlier by {@link #createTemplate()}.
     *
     * <p>Runs for every visible row on every scroll step, so it should not allocate and must not attach
     * listeners. The template may have held any other item before — assume nothing about its current
     * contents beyond the structure {@code createTemplate} gave it.</p>
     */
    void bind(T item, int index, UIElement template);

    /**
     * Optional: clear a row as it leaves the window.
     *
     * <p>UIKit's {@code prepareForReuse}, and needed for the same reason — a template holding a reference
     * to a heavyweight item keeps it alive for as long as the pool does. Most renderers overwrite
     * everything in {@link #bind} and need nothing here, which is why it defaults to a no-op.</p>
     */
    default void unbind(UIElement template) {
    }

    /**
     * What Copy puts on the clipboard for {@code item}.
     *
     * <p><b>Asked of the renderer rather than read off the row element</b>, and the reason is the pool: a
     * selection routinely includes rows that are not realised, so scraping text out of elements can only
     * ever copy the part of the selection that happens to be on screen. The renderer is the one thing
     * that knows how an item becomes text, which is its whole job.</p>
     *
     * <p>The default is {@code String.valueOf}, which is right for a list of strings or paths and merely
     * unhelpful for a record — it is a sensible answer for every list rather than a good one for each,
     * and a renderer whose items are structured should say what a row actually reads as.</p>
     */
    default String copyTextFor(T item) {
        return String.valueOf(item);
    }
}
