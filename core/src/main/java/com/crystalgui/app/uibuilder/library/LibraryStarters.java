package com.crystalgui.app.uibuilder.library;

import java.util.List;
import java.util.function.Supplier;

import com.crystalgui.template.UiTemplates;
import com.crystalgui.ui.dom.GlyphRole;
import com.crystalgui.ui.dom.KindInfo;
import com.crystalgui.ui.dom.Preview;
import com.crystalgui.ui.dom.UIElement;

/**
 * The snippets the Library and the Insert menu offer under <i>Starters</i> — a card, a stat bar, an item tooltip —
 * each a small {@code .cgui} shipped under {@code assets/crystalgui/uibuilder/starters/}, its root the snippet itself.
 *
 * <pre>{@code
 * LibraryCatalog.Entry card = LibraryStarters.CARD;
 * card.build();      // a fresh tree each call
 * card.kind();       // null: a starter is no one kind
 * }</pre>
 *
 * <p>A starter's nodes carry no ids, so placing one twice cannot give a document two of the same. A starter is not
 * a kind, so it joins no user group.</p>
 */
public final class LibraryStarters {

    /** The folder starters are listed under. */
    public static final String FOLDER = "Starters";

    private static final String LAYOUT = "Layout";
    private static final String FORMS = "Forms and Dialogs";
    private static final String GAME = "Game UI";

    /** How a starter's card shows it; what placing it inserts is the snippet as shipped either way. */
    private enum Fit {
        /** At its own size: a snippet small enough to read in the card as it is. */
        OWN,
        /** Laid out at the card's width and shown whole: a snippet whose far end matters — a value, a button. */
        CARD,
        /** Laid out at {@link #NARROW_WIDTH}: a panel of text, which at its own width is too wide to read in a card. */
        NARROW
    }

    /** What a {@link Fit#NARROW} starter is laid out at in its card, in logical pixels. */
    private static final float NARROW_WIDTH = 100f;

    public static final LibraryCatalog.Entry ROW = starter(LAYOUT, "Row", "row", Fit.OWN, "crystalgui:nodes/ui/row",
            "Children side by side, centred on the cross axis.", "horizontal", "hstack", "inline");
    public static final LibraryCatalog.Entry COLUMN = starter(LAYOUT, "Column", "column", Fit.OWN, "crystalgui:nodes/ui/column",
            "Children stacked top to bottom.", "vertical", "vstack", "stack");
    public static final LibraryCatalog.Entry CARD = starter(LAYOUT, "Card", "card", Fit.NARROW, "crystalgui:nodes/ui/frame",
            "A padded, rounded panel with a title and a line of text.", "panel", "box", "tile");
    public static final LibraryCatalog.Entry TOOLBAR = starter(LAYOUT, "Toolbar", "toolbar", Fit.OWN, "crystalgui:nodes/ui/button",
            "A row of buttons.", "actions", "buttons", "bar");
    public static final LibraryCatalog.Entry LABELLED_FIELD = starter(FORMS, "Labelled Field", "labelled-field", Fit.OWN,
            "crystalgui:nodes/ui/textfield", "A label beside a text field.", "form", "input", "label");
    public static final LibraryCatalog.Entry CONFIRM_DIALOG = starter(FORMS, "Confirm Dialog", "confirm-dialog", Fit.CARD,
            "crystalgui:nodes/ui/dialog", "A question, what it means, and Cancel beside the action.",
            "modal", "prompt", "are you sure", "warning", "ok", "cancel");
    public static final LibraryCatalog.Entry STAT_BAR = starter(GAME, "Stat Bar", "stat-bar", Fit.CARD,
            "crystalgui:nodes/ui/progressbar", "A stat's name, a bar of how full it is, and its value.",
            "health", "mana", "energy", "progress", "meter");
    public static final LibraryCatalog.Entry STAT_LIST = starter(GAME, "Stat List", "stat-list", Fit.CARD,
            "crystalgui:nodes/ui/tableview", "Names down one side, their values down the other.",
            "stats", "key value", "properties", "details");
    public static final LibraryCatalog.Entry ITEM_TOOLTIP = starter(GAME, "Item Tooltip", "item-tooltip", Fit.CARD,
            "crystalgui:nodes/ui/tooltip", "An item's name over its lore, on the dark purple-edged panel.",
            "lore", "hover", "item name", "description");

    public static final List<LibraryCatalog.Entry> ALL = List.of(
            ROW, COLUMN, CARD, TOOLBAR, LABELLED_FIELD, CONFIRM_DIALOG, STAT_BAR, STAT_LIST, ITEM_TOOLTIP);

    private LibraryStarters() {
    }

    /** @param folder the sub-folder of {@link #FOLDER} it is listed in */
    private static LibraryCatalog.Entry starter(String folder, String label, String file, Fit fit, String icon,
                                                String description, String... synonyms) {
        String asset = "crystalgui:uibuilder/starters/" + file;
        Supplier<UIElement> build = () -> UiTemplates.load(asset).inflate();
        KindInfo info = KindInfo.named(label).inCategory(FOLDER + "/" + folder).describedAs(description).synonyms(synonyms)
                .glyph(icon, GlyphRole.LAYOUT).starter(build);
        if (fit == Fit.CARD) info = info.preview(Preview.sample(build).atCardWidth());
        if (fit == Fit.NARROW) info = info.preview(Preview.sample(build).width(NARROW_WIDTH));
        return LibraryCatalog.Entry.starter(asset, label, info);
    }
}
