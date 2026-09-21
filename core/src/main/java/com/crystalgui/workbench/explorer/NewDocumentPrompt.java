package com.crystalgui.workbench.explorer;

import com.crystalgui.core.property.ObservableList;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.widget.collection.list.ListRenderer;
import com.crystalgui.widget.collection.list.ListView;
import com.crystalgui.document.NewDocumentKind;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.widget.display.SymbolIcon;
import com.crystalgui.widget.overlay.InputDialog;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * <b>New Java Class</b> — a name, and which kind of type to make it.
 *
 * <pre>{@code
 * NewDocumentPrompt.ask(source, (name, kind) -> create(name, body(name, kind)));
 * }</pre>
 *
 * <h3>The kinds are not a list this class invented</h3>
 *
 * <p>They are {@link SymbolKind}'s type kinds, drawn by the {@link SymbolIcon} a completion row uses —
 * so the glyph beside <i>Record</i> here is the same drawing, in the same colour, as the one beside a
 * record in the completion list. A second list of six names with a second mapping to six icons would
 * have looked identical on the day it was written and drifted on the first one either side changed.</p>
 *
 * <h3>Why it is not on {@code InputDialog}</h3>
 *
 * <p>Because it needs a {@link ListView}, and {@code widget.overlay} sits <em>below</em>
 * {@code widget.collection} in the widget layering — a prompt there cannot name a list at all. It still
 * borrows {@link InputDialog#prompt} and {@link InputDialog#centre}, so it is the same chrome in the
 * same place rather than a second dialog that looks nearly the same.</p>
 */
public final class NewDocumentPrompt {


    /**
     * The row height, which has to be stated here as well as in the sheet: a virtualised list turns an
     * index into a scroll offset in Java and cannot read the cascade. Same forced duplication
     * {@code CompletionPopup.ROW_HEIGHT} documents, and the same fix if it ever drifts — change both.
     */
    public static final float ROW_HEIGHT = 16f;

    public static final String PROMPT_CLASS = "__new-class__";
    public static final String NAME_ROW_CLASS = "__new-class-name__";
    public static final String DIVIDER_CLASS = "__new-class-divider__";
    public static final String KINDS_CLASS = "__new-class-kinds__";
    public static final String ROW_CLASS = "__new-class-kind__";
    public static final String LABEL_CLASS = "__new-class-label__";

    private NewDocumentPrompt() {
    }

    /**
     * Opens the prompt; {@code onAccept} runs once, for a non-blank name confirmed with Enter.
     *
     * <p>A cancelled prompt reports nothing rather than an empty name — the same contract
     * {@link InputDialog#ask} states, and for the same reason: otherwise every caller re-checks and one
     * of them forgets.</p>
     */
    public static void ask(@Nullable UIElement from, NewDocumentKind kind,
                           BiConsumer<String, NewDocumentKind.Variant> onAccept) {
        List<NewDocumentKind.Variant> KINDS = kind.variants();
        if (KINDS.isEmpty()) return;
        UIDocument window = from == null ? null : from.document();
        if (window == null) return;

        Popover popup = InputDialog.prompt(window, from, "New " + kind.label());
        popup.addClass(PROMPT_CLASS);
        // THE NAME HOLDS FOCUS FOR THE WHOLE LIFE OF THE POPUP. Without this a press on the caption, the
        // divider or the padding finds nothing click-focusable to walk up to and CLEARS the owner --
        // Blink's behaviour, and why clicking a bare div blurs the input beside it. The rows are still
        // focusable, so picking a kind with the pointer still selects. @see Attribute#RETAINS_FOCUS
        popup.setRetainsFocus(true);

        UIElement nameRow = new UIElement();
        nameRow.addClass(NAME_ROW_CLASS);
        UIElement nameIcon = new UIElement();
        nameIcon.addClass(SymbolIcon.ICON_CLASS);
        showGlyph(nameIcon, KINDS.get(0));
        nameRow.append(nameIcon);
        TextField name = new TextField();
        name.setPlaceholder("Name");
        nameRow.append(name);
        popup.append(nameRow);

        // A RULE BETWEEN THE NAME AND THE KINDS. The two halves answer different questions -- what it is
        // called, and what it is -- and with the field's box gone there was nothing left saying where one
        // ended. A real element rather than a border, since a 1px edge on the list is the list's own box
        // and would scroll with it.
        UIElement divider = new UIElement();
        divider.addClass(DIVIDER_CLASS);
        divider.setHitTest(false);
        popup.append(divider);

        ObservableList<NewDocumentKind.Variant> model = new ObservableList<>();
        model.setAll(KINDS);
        ListView<NewDocumentKind.Variant> kinds = new ListView<>(model);
        kinds.addClass(KINDS_CLASS);
        kinds.setRenderer(new KindRow());
        kinds.setItemHeight(ROW_HEIGHT);
        kinds.select(0);
        popup.append(kinds);

        // THE FIELD'S GLYPH IS THE CHOSEN KIND, which is what makes the two halves read as one prompt
        // rather than a name and an unrelated list -- IntelliJ shows the same mark in the same place.
        kinds.onSelectionChanged.connect(indices -> {
            showGlyph(nameIcon, selected(KINDS, kinds));
            // AND THE CARET GOES STRAIGHT BACK TO THE NAME. ListView drives selection FROM FOCUS -- its
            // rows are CLICK_NOT_TABBABLE precisely so a click can select one -- so picking a kind with
            // the pointer takes the caret out of the field, and the next keystroke goes nowhere. The
            // name is what you are typing for the whole life of this popup, so it holds focus for the
            // whole life of it.
            //
            // Safe against a loop: select() moves no focus and nothing clears the selection on blur, so
            // handing focus back cannot raise this signal again. The arrow path never gets here with the
            // field unfocused anyway.
            // POINTER, NOT PROGRAMMATIC. requestFocus is the DOM's element.focus(): it rings and it
            // SCROLLS ITS TARGET INTO VIEW, and a reveal here scrolled the whole popup sideways -- the
            // divider and every row shifted left together, which reads as the prompt jumping on click.
            // This focus change came from a click, so it should behave like one.
            if (window.focus().focused() != name) window.focus().requestPointerFocus(name);
        });

        Runnable accept = () -> {
            String typed = name.getText().trim();
            NewDocumentKind.Variant chosen = selected(KINDS, kinds);
            popup.hide();
            if (!typed.isEmpty()) onAccept.accept(typed, chosen);
        };

        // THE NAME KEEPS FOCUS AND THE ARROWS STILL REACH THE LIST, which is the whole interaction: you
        // type a name and pick a kind without ever leaving the field. Captured on the popover so it runs
        // before the field's own caret handling, exactly as QuickPick does it.
        popup.events.getGroup(KeyboardEvent.Down.class).attachListener((element, event) -> {
            int code = event.getKeyCode();
            if (code == CgKeyCodes.KEY_DOWN) {
                move(kinds, 1);
                event.stopPropagation();
            } else if (code == CgKeyCodes.KEY_UP) {
                move(kinds, -1);
                event.stopPropagation();
            } else if (code == CgKeyCodes.KEY_RETURN) {
                accept.run();
                event.stopPropagation();
} else if (code == CgKeyCodes.KEY_ESCAPE) {
                // HANDLED HERE, and it has to be. TextField consumes Escape whenever there is an edit to
                // abandon, so once a name has been typed the first press reverts the field and only the
                // second reaches the popover's close watcher -- which reads, correctly, as Escape not
                // working. Captured above the field, so one press always closes the prompt.
                popup.hide();
                event.stopPropagation();
            }
        }, true, false);

        // Double-click, or Enter once focus has somehow reached the list.
        kinds.onRowActivated.connect(index -> accept.run());

        InputDialog.centre(window, popup);
        window.focus().requestFocus(name);
    }

    private static NewDocumentKind.Variant selected(List<NewDocumentKind.Variant> all,
                                                    ListView<NewDocumentKind.Variant> kinds) {
        Set<Integer> chosen = kinds.getSelectedIndices();
        if (chosen.isEmpty()) return all.get(0);
        int index = chosen.iterator().next();
        return index < 0 || index >= all.size() ? all.get(0) : all.get(index);
    }

    /** A variant's own mark, drawn as an overlay so any icon works — not only a SymbolKind's. */
    private static void showGlyph(UIElement on, NewDocumentKind.Variant variant) {
        CgUiSvg glyph = variant.icon() == null ? null : CgUiSvg.ofIcon(variant.icon());
        CgUiDrawable drawn = glyph == null ? CgUiDrawable.EMPTY : glyph;
        StyleGroup.defaultPipeline(on.getStyle().getGeneralGroup(), g -> g.overlay(drawn));
    }

    /** Steps the selection, stopping at the ends rather than wrapping — six rows are all visible. */
    private static void move(ListView<NewDocumentKind.Variant> kinds, int by) {
        Set<Integer> chosen = kinds.getSelectedIndices();
        int at = chosen.isEmpty() ? 0 : chosen.iterator().next();
        int next = Math.max(0, Math.min(kinds.getModel().size() - 1, at + by));
        kinds.select(next);
    }


    private static final class KindRow implements ListRenderer<NewDocumentKind.Variant> {

        @Override
        public UIElement createTemplate() {
            UIElement row = new UIElement();
            row.addClass(ROW_CLASS);
            UIElement glyph = new UIElement();
            glyph.addClass(SymbolIcon.ICON_CLASS);
            glyph.setHitTest(false);
            row.append(glyph);
            UIText label = new UIText("");
            label.addClass(LABEL_CLASS);
            row.append(label);
            return row;
        }

        @Override
        public void bind(NewDocumentKind.Variant variant, int index, UIElement template) {
            for (UIElement child : template.children()) {
                if (child instanceof UIText label) label.setText(variant.label());
                else showGlyph(child, variant);
            }
        }
    }
}
