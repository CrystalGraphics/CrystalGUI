package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.app.uibuilder.inspect.NodeFields;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.sheet.source.CssEdits;
import com.crystalgui.style.sheet.source.CssSourceModel;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.ui.dom.ClassNames;
import com.crystalgui.ui.dom.UIElement;

/**
 * The three ways a rule comes into being: a new one for what is selected, an inline value promoted into
 * one, and a whole inline style extracted as a class.
 *
 * <pre>{@code
 * String selector = RuleActions.selectorFor(node);        // ".card", "#save", "button"
 * RuleActions.newRule(sheet, selector);                   // an empty rule at the end
 * RuleActions.promote(sheet, document, node, OPACITY, selector);
 * RuleActions.extractClass(sheet, document, node, "card");
 * }</pre>
 *
 * <p>Each writes <b>one</b> text edit into the sheet, and each says up front what selector it would use —
 * an editor that invents a selector you did not see is an editor that quietly restyles other elements.</p>
 *
 * <p>Promoting clears the inline value it moved, so what is on screen afterwards comes from the sheet
 * rather than from an inline copy that happens to agree: keeping both hides the moment the rule stops
 * matching, and the screen stays right from the wrong source.</p>
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
     * Adds an empty rule for {@code selector} at the end of {@code sheet}.
     *
     * <p>It has no number until it declares something — the cascade never sees an empty rule — so it is
     * reached by its selector until then. @see StyleTarget#key</p>
     *
     * @return whether it was written
     */
    public static boolean newRule(@Nullable TextBuffer sheet, String selector) {
        if (sheet == null || selector.isBlank()) return false;
        CssSourceModel model = CssSourceModel.parse(sheet.toString());
        sheet.edit(CssEdits.insertRule(model, selector, ""));
        return true;
    }

    /**
     * Moves one inline declaration into a rule for {@code selector}, adding the rule when there is none.
     *
     * @return whether there was an inline value to move
     */
    public static boolean promote(@Nullable TextBuffer sheet, @Nullable UiBuilderDocument document,
                                  UIElement node, StyleProperty<?> property, String selector) {
        if (sheet == null || !LiveEdits.hasInline(node, property)) return false;
        String value = writtenValue(node, property);
        if (value == null) return false;

        writeInto(sheet, selector, List.of(property.name + ": " + value + ";"));
        clearInline(document, node, property);
        return true;
    }

    /**
     * Moves <b>every</b> inline declaration into a rule for {@code .className}, and puts that class on the
     * node — the "this element is a kind of thing now" gesture.
     *
     * @return how many declarations moved
     */
    public static int extractClass(@Nullable TextBuffer sheet, @Nullable UiBuilderDocument document,
                                   UIElement node, String className) {
        if (sheet == null || className.isBlank()) return 0;
        List<StyleProperty<?>> inline = inlineOf(node);
        if (inline.isEmpty()) return 0;

        List<String> declarations = new ArrayList<>();
        for (StyleProperty<?> property : inline) {
            String value = writtenValue(node, property);
            if (value != null) declarations.add(property.name + ": " + value + ";");
        }
        writeInto(sheet, "." + className, declarations);

        List<String> classes = new ArrayList<>(ClassNames.authored(node.classes()));
        if (!classes.contains(className)) classes.add(className);
        if (document != null) {
            document.applyAll("Extract class", List.of(new BuilderEdit.SetClasses(node,
                    ClassNames.authored(node.classes()), classes)));
        } else {
            node.addClass(className);
        }
        for (StyleProperty<?> property : inline) clearInline(document, node, property);
        return declarations.size();
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    /** Adds to the rule for {@code selector} when the sheet already has one, else writes a new rule. */
    private static void writeInto(TextBuffer sheet, String selector, List<String> declarations) {
        CssSourceModel model = CssSourceModel.parse(sheet.toString());
        CssSourceModel.Rule existing = ruleFor(model, selector);
        if (existing == null) {
            sheet.edit(CssEdits.insertRule(model, selector, String.join(" ", declarations)));
            return;
        }
        for (String declaration : declarations) {
            int colon = declaration.indexOf(':');
            if (colon <= 0) continue;
            String property = declaration.substring(0, colon).trim();
            String value = declaration.substring(colon + 1).replace(";", "").trim();
            // Re-read between writes: every edit moves the ranges of everything after it.
            CssSourceModel current = CssSourceModel.parse(sheet.toString());
            CssSourceModel.Rule rule = ruleFor(current, selector);
            if (rule == null) return;
            CssSourceModel.Declaration held = declarationOf(rule, property);
            sheet.edit(held == null
                    ? CssEdits.insertDeclaration(current, rule, property, value)
                    : CssEdits.replaceValue(current, held, value));
        }
    }

    @Nullable
    private static CssSourceModel.Rule ruleFor(CssSourceModel model, String selector) {
        for (CssSourceModel.Rule rule : model.rules()) {
            if (model.textOf(rule.selectorsRange()).trim().equals(selector)) return rule;
        }
        return null;
    }

    @Nullable
    private static CssSourceModel.Declaration declarationOf(CssSourceModel.Rule rule, String property) {
        for (CssSourceModel.Declaration declaration : rule.declarations()) {
            if (declaration.property().equals(property)) return declaration;
        }
        return null;
    }

    /** What the node has inline, as the sheet would spell it. */
    @Nullable
    @SuppressWarnings("unchecked")
    private static String writtenValue(UIElement node, StyleProperty<?> property) {
        Object value = node.getStyle().getComputed(property);
        return value == null ? null : ((StyleProperty<Object>) property).write(value);
    }

    private static List<StyleProperty<?>> inlineOf(UIElement node) {
        List<StyleProperty<?>> inline = new ArrayList<>();
        for (StyleProperty<?> property : node.getStyle().candidates.keySet()) {
            if (LiveEdits.hasInline(node, property)) inline.add(property);
        }
        return inline;
    }

    /** Recorded in the document when there is one; a live pick just loses the inline value. */
    private static void clearInline(@Nullable UiBuilderDocument document, UIElement node,
                                    StyleProperty<?> property) {
        if (document == null) {
            LiveEdits.clearInline(node, property);
            return;
        }
        BuilderEdit edit = NodeFields.on(document).inlineEdit(node, property, "");
        if (edit != null) document.apply(edit);
    }
}
