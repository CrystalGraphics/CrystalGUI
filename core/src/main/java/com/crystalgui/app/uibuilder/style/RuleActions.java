package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.style.sheet.source.CssEdits;
import com.crystalgui.style.sheet.source.CssSourceModel;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.ui.dom.ClassNames;
import com.crystalgui.ui.dom.UIElement;

/**
 * The three ways a rule comes into being: a new one for what is selected, the inline style promoted into
 * one, and the inline style extracted as a class.
 *
 * <pre>{@code
 * String selector = RuleActions.selectorFor(node);                 // ".card", "#save", "button"
 * StyleTarget rule = RuleActions.newRule(sheet, selector);         // an empty rule at the end, to select
 * RuleActions.promote(sheet, document, node, selector);            // every inline value into it
 * RuleActions.extractClass(sheet, document, node, "card");         // into .card, and the class onto the node
 * }</pre>
 *
 * <p>Every write goes through {@link StyleFields}, so a rule gains declarations exactly as a row adds one. Each
 * action is <b>one undo step</b> in each history it touches — the sheet's and, for what leaves the element,
 * the document's — and says up front what selector it uses: an editor that invents a selector you did not see
 * quietly restyles other elements.</p>
 *
 * <p>What moves is cleared off the element, so the screen then shows the sheet's value rather than an inline
 * copy that happens to agree, which would hide the moment the rule stops matching.</p>
 */
public final class RuleActions {

    private RuleActions() {
    }

    /**
     * The selector a new rule would use for {@code node}: its first authored class, else its id, else its
     * kind. What the button shows before it is pressed.
     */
    public static String selectorFor(@Nullable UIElement node) {
        if (node == null) return "";
        List<String> classes = ClassNames.authored(node.classes());
        if (!classes.isEmpty()) return "." + classes.get(0);
        if (node.id() != null && !node.id().isEmpty()) return "#" + node.id();
        return node.name().local();
    }

    /**
     * Adds an empty rule for {@code selector} at the end of {@code sheet}, or finds the one it already has.
     *
     * @return the rule as a target, to select — or null when the sheet cannot be written
     */
    @Nullable
    public static StyleTarget newRule(SheetDocuments.Sheet sheet, String selector) {
        TextBuffer buffer = sheet.buffer();
        if (buffer == null || selector.isBlank()) return null;
        CssSourceModel model = CssSourceModel.parse(buffer.toString());
        if (!hasRule(model, selector)) buffer.edit(CssEdits.insertRule(model, selector, ""));
        return StyleTarget.rule(sheet, -1, selector);
    }

    /**
     * Moves every inline declaration into the rule for {@code selector}, adding the rule when there is none.
     *
     * @return how many declarations moved
     */
    public static int promote(SheetDocuments.Sheet sheet, @Nullable UiBuilderDocument document, UIElement node,
                              String selector) {
        return move(sheet, document, node, selector, "Promote to rule", null);
    }

    /**
     * Moves every inline declaration into {@code .className} and puts that class on the node — the "this
     * element is a kind of thing now" gesture.
     *
     * @return how many declarations moved
     */
    public static int extractClass(SheetDocuments.Sheet sheet, @Nullable UiBuilderDocument document, UIElement node,
                                   String className) {
        if (className.isBlank()) return 0;
        return move(sheet, document, node, "." + className, "Extract class", className);
    }

    /** The whole move: declarations into the rule, the class onto the node, off the element — one step per history. */
    private static int move(SheetDocuments.Sheet sheet, @Nullable UiBuilderDocument document, UIElement node,
                            String selector, String label, @Nullable String className) {
        TextBuffer buffer = sheet.buffer();
        if (buffer == null) return 0;
        StyleFields inline = StyleFields.on(document, StyleTarget.inline(), node);
        List<StyleFields.Declared> moving = inline.declared();
        if (moving.isEmpty()) return 0;

        UndoStack sheetHistory = buffer.history();
        UndoStack documentHistory = document == null ? null : document.history();
        sheetHistory.beginTransaction(label);
        if (documentHistory != null) documentHistory.beginTransaction(label);
        try {
            StyleTarget rule = newRule(sheet, selector);
            StyleFields ruleFields = StyleFields.on(document, rule, node);
            for (StyleFields.Declared declared : moving) ruleFields.value(declared.name()).set(declared.value());
            if (className != null) name(document, node, className);
            for (StyleFields.Declared declared : moving) inline.remove(declared.name());
        } finally {
            if (documentHistory != null) documentHistory.endTransaction();
            sheetHistory.endTransaction();
        }
        return moving.size();
    }

    /** Puts the class on the node — recorded in the document, or straight onto a live pick, which has none. */
    private static void name(@Nullable UiBuilderDocument document, UIElement node, String className) {
        List<String> before = ClassNames.authored(node.classes());
        if (before.contains(className)) return;
        if (document == null) {
            node.addClass(className);
            return;
        }
        List<String> after = new ArrayList<>(before);
        after.add(className);
        document.apply(new BuilderEdit.SetClasses(node, before, after));
    }

    private static boolean hasRule(CssSourceModel model, String selector) {
        for (CssSourceModel.Rule rule : model.rules()) {
            if (model.textOf(rule.selectorsRange()).trim().equals(selector)) return true;
        }
        return false;
    }
}
