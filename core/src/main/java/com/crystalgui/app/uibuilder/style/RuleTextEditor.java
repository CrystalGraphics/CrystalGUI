package com.crystalgui.app.uibuilder.style;

import com.crystalgui.style.sheet.source.CssSourceModel;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.texteditor.TextEditor;

/**
 * <i>Edit as CSS</i> — the rule's own text, in a real editor, over the sheet's own buffer.
 *
 * <pre>{@code
 * RuleTextEditor.open(button, target);   // the rule selected, the caret in it
 * }</pre>
 *
 * <p><b>The same buffer, not a copy.</b> Typing here is typing in the file: the canvas restyles as the text
 * lands, an editor tab open on that sheet shows it, and one undo reverses it wherever it is pressed. A pane
 * that copied the text out and wrote it back on close would silently discard whatever the file did
 * meanwhile, and would reformat the parts nobody touched.</p>
 *
 * <p>The whole sheet is shown rather than the rule alone, for the same reason: there is no way to edit a
 * slice of a buffer as though it were a document. The rule is selected and scrolled to, which is what a
 * person asked for by pressing the button.</p>
 */
public final class RuleTextEditor {

    public static final String EDITOR_CLASS = "__rule-editor__";

    private RuleTextEditor() {
    }

    /** Opens the sheet at {@code target}'s rule, or does nothing for a target with no text to edit. */
    public static void open(UIElement anchor, StyleTarget target) {
        TextBuffer buffer = target.buffer();
        if (buffer == null) return;

        TextEditor editor = new TextEditor();
        editor.addClass(EDITOR_CLASS);
        editor.setBuffer(buffer);
        // Highlighted as what it is, by the same lookup a tab on the file would make.
        LanguageRegistry.Entry css = LanguageRegistry.forFileName(fileNameOf(target));
        editor.setLanguage(css.language()).setTokenizer(css.newTokenizer());
        select(editor, buffer, target.ruleOrder());

        Popover popover = new Popover();
        popover.addClass(EDITOR_CLASS + "-popover");
        popover.append(editor);
        popover.showFor(anchor, anchor);

        UIDocument window = editor.document();
        if (window != null) window.focus().requestPointerFocus(editor);
    }

    /** Puts the caret in the rule and scrolls to it. */
    private static void select(TextEditor editor, TextBuffer buffer, int ruleOrder) {
        CssSourceModel.Rule rule = CssSourceModel.parse(buffer.toString()).ruleAt(ruleOrder);
        if (rule == null) return;
        editor.setSelection(rule.range().start(), rule.range().end());
        editor.revealRow(buffer.offsetToPoint(rule.range().start()).row());
    }

    /** The sheet's own file name, so the language lookup is the one a tab on it would make. */
    private static String fileNameOf(StyleTarget target) {
        SheetDocuments.Sheet sheet = target.sheet();
        return sheet == null || sheet.resource() == null ? "sheet.css" : sheet.resource().name();
    }
}
