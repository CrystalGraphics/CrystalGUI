package com.crystalgui.graph.shader;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.event.MouseEvent;

/**
 * A category's heading on the Blackboard — a fold arrow and a name, over the properties filed under it.
 *
 * <p>Reference: {@code docs/research/unity-blackboard/02-blackboard-categories-1.png}.</p>
 *
 * <h3>It is drawn from a field, and owns nothing</h3>
 * <p>There is no category entity. A category is a <b>string on each property</b>
 * ({@link com.crystalgui.graph.GraphProperty#category()}), and this row is a heading the panel emits
 * whenever that string changes as it scans the list — the same shape {@code NodeType.category()} already
 * uses. So a header has no members to keep, no ownership of the rows beneath it, and deleting one cannot
 * delete properties: it clears a field.</p>
 *
 * <h3>Collapse is view state and lives on the panel</h3>
 * <p>Not here, and not in the document. Folding is not a change to the graph — the same boundary
 * {@code TextEditor}'s folding draws, and the reason {@code Ctrl+Z} does not unfold. This row only
 * <em>renders</em> the state and reports the press; the panel decides what a press means.</p>
 */
public class CategoryHeader extends UIElement {

    public static final String HEADER_CLASS = "__category__";
    public static final String ARROW_CLASS = "__arrow__";
    public static final String NAME_CLASS = "__name__";

    /** On the row while its group is folded away. */
    public static final String COLLAPSED_CLASS = "__collapsed__";

    /** Shared with {@link PropertyPill}, so one pair of rules styles every inline rename on the board. */
    public static final String EDITOR_CLASS = PropertyPill.EDITOR_CLASS;
    public static final String RENAMING_CLASS = PropertyPill.RENAMING_CLASS;

    /**
     * The fold arrow, as text rather than as a sprite.
     *
     * <p>The board has no atlas of its own and the gutter's fold arrows are drawn the same way. Two
     * glyphs the bundled fonts genuinely carry — a triangle that is absent renders as a blank advance
     * and is indistinguishable from a category that cannot be folded.</p>
     */
    private static final String OPEN_ARROW = "▼";
    private static final String CLOSED_ARROW = "▶";

    private final UIText arrow = new UIText(OPEN_ARROW);
    private final UIText name;

    private final InlineRename rename;

    private String category;
    private boolean collapsed;

    /** A press on the row, forwarded so the panel can fold, rename or open a menu. @see PropertyPill#onPressed */
    public final Signal.Value<MouseEvent.Down> onPressed = new Signal.Value<>();

    /** Fires with the committed name. The header does not write the document; its host does. */
    public final Signal.Value<String> onRenamed = new Signal.Value<>();

    /** Fires once a rename is over, however it ended — so the panel can take focus back. */
    public final Signal.Action onRenameEnded = new Signal.Action();

    public CategoryHeader(String category, boolean collapsed) {
        this.category = category;
        addClass(HEADER_CLASS);
        markAsInternal();

        arrow.addClass(ARROW_CLASS);
        name = new UIText(category);
        name.addClass(NAME_CLASS);
        // Scenery, both of them: the whole row is the fold target, which is what every tree in this
        // engine and every one it is modelled on does. An arrow that alone toggled would be a 7px
        // target beside a 200px row that looks just as clickable.
        arrow.setHitTest(false);
        name.setHitTest(false);
        addInternalChild(arrow);
        addInternalChild(name);

        setCollapsed(collapsed);
        rename = new InlineRename(this, EDITOR_CLASS, RENAMING_CLASS, category, name::setText,
                this::invalidateStyleMatch);
        rename.onCommitted.connect(onRenamed::emit);
        rename.onEnded.connect(onRenameEnded::emit);

        onMouseDown.attachListener((element, event) -> {
            onPressed.emit(event);
            // Kept off the panel behind, which would otherwise clear the property selection. Safe here
            // because this is the only listener on the group -- the same constraint PropertyPill records.
            event.stopPropagation();
        }, false, true);
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public String category() {
        return category;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public CategoryHeader setCollapsed(boolean value) {
        this.collapsed = value;
        arrow.setText(value ? CLOSED_ARROW : OPEN_ARROW);
        if (value) addClass(COLLAPSED_CLASS); else removeClass(COLLAPSED_CLASS);
        invalidateStyleMatch();
        return this;
    }

    public void beginRename() {
        rename.begin();
    }

    public boolean isRenaming() {
        return rename.isRunning();
    }

    public void endRename() {
        rename.end();
    }

    /** Renames without a gesture — used when the panel adopts a header for a category that was renamed. */
    public void setCategory(String value) {
        this.category = value;
        name.setText(value);
        rename.setShownName(value);
    }
}
