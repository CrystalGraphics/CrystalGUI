package com.crystalgui.app.uibuilder.insert;

import java.util.List;

import com.crystalgui.template.UiTemplates;
import com.crystalgui.ui.dom.UIElement;

/**
 * The snippets the Insert menu offers under <i>Starters</i> — a row, a card, a toolbar — each a small {@code .cgui}
 * shipped under {@code assets/crystalgui/uibuilder/starters/}, its root the snippet itself.
 *
 * <pre>{@code
 * UIElement card = BuilderStarters.CARD.build();   // a fresh tree each call
 * }</pre>
 *
 * <p>A starter's nodes carry no ids, so placing one twice cannot give a document two of the same.</p>
 */
public final class BuilderStarters {

    /**
     * One snippet.
     *
     * @param icon the layout mark or kind glyph its row draws
     */
    public record Starter(String label, String asset, String icon, String description, List<String> synonyms) {

        /** A fresh tree of the snippet. */
        public UIElement build() {
            return UiTemplates.load(asset).inflate();
        }

        /** What a recent pick remembers it by. */
        public String id() {
            return "starter:" + asset;
        }
    }

    public static final Starter ROW = new Starter("Row", "crystalgui:uibuilder/starters/row", "crystalgui:nodes/ui/row",
            "Children side by side, centred on the cross axis.", List.of("horizontal", "hstack", "inline"));
    public static final Starter COLUMN = new Starter("Column", "crystalgui:uibuilder/starters/column",
            "crystalgui:nodes/ui/column", "Children stacked top to bottom.", List.of("vertical", "vstack", "stack"));
    public static final Starter CARD = new Starter("Card", "crystalgui:uibuilder/starters/card", "crystalgui:nodes/ui/frame",
            "A padded, rounded panel with a title and a line of text.", List.of("panel", "box", "tile"));
    public static final Starter TOOLBAR = new Starter("Toolbar", "crystalgui:uibuilder/starters/toolbar",
            "crystalgui:nodes/ui/button", "A row of buttons.", List.of("actions", "buttons", "bar"));
    public static final Starter LABELLED_FIELD = new Starter("Labelled Field", "crystalgui:uibuilder/starters/labelled-field",
            "crystalgui:nodes/ui/textfield", "A label beside a text field.", List.of("form", "input", "label"));
    public static final Starter DIALOG_BUTTONS = new Starter("Dialog Buttons", "crystalgui:uibuilder/starters/dialog-buttons",
            "crystalgui:nodes/ui/dialog", "Cancel and OK, aligned to the end.", List.of("ok", "cancel", "confirm"));

    public static final List<Starter> ALL = List.of(ROW, COLUMN, CARD, TOOLBAR, LABELLED_FIELD, DIALOG_BUTTONS);

    private BuilderStarters() {
    }
}
