package com.crystalgui.ui.elements;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.style.TaffyDisplay;

/**
 * A search box: an icon, a {@link TextField}, and a clear button that appears once there is something to
 * clear.
 *
 * <h3>General, and deliberately NOT a {@code ConfigControl}</h3>
 * <p>Worth stating because it is the obvious mistake: <b>a {@code ConfigControl} edits a value; a search
 * field filters a view.</b> They are different jobs, and the config kit's row height, label column and
 * {@code .__config-control__} cascade are all built for the first — a search box inheriting them would be
 * laid out as a form row inside things that are not forms. Using a {@code SearchField} <em>inside</em> a
 * configurator panel is completely fine; that is composition, not membership.</p>
 *
 * <p>Consumers this exists for beyond the create menu: a command palette over
 * {@code core.command.CommandRegistry}, searching a configurator's settings, and filtering a
 * {@code ListView} or {@code TableView}.</p>
 *
 * <h3>The parts are internal children</h3>
 * <p>{@code __icon__} / {@code __field__} / {@code __clear__}, so a theme draws the magnifier and the
 * clear affordance without this class naming a single colour or size. {@link #acceptsPublicChildren()} is
 * false for the same reason every other composite's is.</p>
 */
public class SearchField extends UIElement {

    public static final String SEARCH_FIELD_CLASS = "__search-field__";
    public static final String ICON_CLASS = "__icon__";
    public static final String FIELD_CLASS = "__field__";
    public static final String CLEAR_CLASS = "__clear__";

    private final UIElement icon = new UIElement();
    private final TextField field = new TextField();
    private final UIElement clear = new UIElement();

    /** Fires on every keystroke — see the constructor's note on why this is not deferred to Enter. */
    public final Signal.Action onQueryChanged = new Signal.Action();

    public SearchField() {
        addClass(SEARCH_FIELD_CLASS);
        markAsInternal();

        icon.addClass(ICON_CLASS);
        // Scenery. A press on the magnifier belongs to the field beside it, and an icon that ate the
        // click would make the leftmost pixels of the box dead.
        icon.setHitTest(false);

        field.addClass(FIELD_CLASS);
        // IMMEDIATE, not on-commit: a box that only filters on Enter is not a search box, it is a filter
        // you have to submit. Same reasoning NodeCreationMenu's own field already carried.
        field.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        field.attachListener(text -> {
            refreshClear();
            onQueryChanged.emit();
        });

        clear.addClass(CLEAR_CLASS);
        clear.onMouseDown.attachListener((element, event) -> {
            setText("");
            event.stopPropagation();
        }, false, true);

        addInternalChild(icon);
        addInternalChild(field);
        addInternalChild(clear);
        refreshClear();
    }

    /** The field itself — for focus, key handling, and a placeholder. Exposed because the owner routes
     * arrow keys through it while keeping focus in the box; see {@code NodeCreationMenu}. */
    public TextField field() {
        return field;
    }

    public String getText() {
        return field.getText();
    }

    public SearchField setText(String text) {
        field.setText(text);
        refreshClear();
        return this;
    }

    public SearchField setPlaceholder(String placeholder) {
        field.setPlaceholder(placeholder);
        return this;
    }

    /** Hidden with {@code display: none} rather than dimmed: an always-present clear button is a target
     * that does nothing most of the time, and one that keeps its box would leave a gap the text could
     * never use. */
    private void refreshClear() {
        boolean any = !field.getText().isEmpty();
        StyleGroup.importantPipeline(clear.getStyle().getLayoutGroup(),
                l -> l.display(any ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

    /** The parts are this widget's own structure — see the class note. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }
}
