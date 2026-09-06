package com.crystalgui.app.uibuilder.inspect;

import javax.annotation.Nullable;

import com.crystalgui.style.Styleable;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.sheet.source.CssEdits;
import com.crystalgui.style.sheet.source.CssSourceModel;
import com.crystalgui.text.TextBuffer;

/**
 * Making a live tweak permanent — the edit lands in the SHEET, as text.
 *
 * <pre>{@code
 * StyleWriteBack.writeValue(sheet, rule.ruleOrder(), "opacity", "0.18");
 * StyleWriteBack.promoteToRule(sheet, element, OPACITY, ".taskbar-glow");
 * }</pre>
 *
 * <p>{@link LiveEdits} changes the running screen and nothing else; this is the other half. A declaration
 * edited in the Styles pane becomes a {@code ChangeSet} against the sheet's own {@link TextBuffer}, so a
 * {@code TextEditor} open on that sheet shows the change land and undoing it there undoes it here —
 * because they are one buffer, not two views that copy from each other.</p>
 *
 * <p><b>The rule is found by its NUMBER, not by re-matching the selector.</b> Every {@code StyleSlot}
 * carries the source order the cascade gave it, and {@code CssSourceModel} numbers rules the same way —
 * so the rule that produced a value on screen is the rule that gets edited, even when three rules share a
 * selector or one selector appears twice in a file.</p>
 */
public final class StyleWriteBack {

    private StyleWriteBack() {
    }

    /**
     * Sets {@code property} to {@code value} in the rule numbered {@code ruleOrder}.
     *
     * <p>Replaces the declaration when the rule already has one, and adds it when it does not — which is
     * what "edit this value" means for a property the rule inherits rather than states.</p>
     *
     * @return whether anything was written. False for a rule number no sheet rule carries, which is what
     *         a stale pane asks after the file changed underneath it.
     */
    public static boolean writeValue(TextBuffer sheet, int ruleOrder, String property, String value) {
        if (sheet == null || property == null || value == null) return false;
        CssSourceModel model = CssSourceModel.parse(sheet.toString());
        CssSourceModel.Rule rule = model.ruleAt(ruleOrder);
        if (rule == null) return false;

        CssSourceModel.Declaration existing = declarationOf(rule, property);
        sheet.edit(existing == null
                ? CssEdits.insertDeclaration(model, rule, property, value)
                : CssEdits.replaceValue(model, existing, value));
        return true;
    }

    /**
     * Turns an inline tweak into a rule.
     *
     * <p>The inline value is read off the element, written into a new rule at the end of the sheet, and
     * then <b>cleared from the element</b> — so what is on screen afterwards is what the sheet says, not
     * an inline value that happens to agree with it. Leaving both would hide the moment the rule stopped
     * matching: the screen would still look right, from the wrong source.</p>
     *
     * @return whether there was an inline value to promote
     */
    public static <T> boolean promoteToRule(TextBuffer sheet, @Nullable Styleable element,
                                            StyleProperty<T> property, String selector) {
        if (sheet == null || element == null || property == null || selector == null) return false;
        if (!LiveEdits.hasInline(element, property)) return false;

        T value = element.getStyle().getComputed(property);
        if (value == null) return false;

        CssSourceModel model = CssSourceModel.parse(sheet.toString());
        sheet.edit(CssEdits.insertRule(model, selector, property.name + ": " + value + ";"));
        LiveEdits.clearInline(element, property);
        return true;
    }

    @Nullable
    private static CssSourceModel.Declaration declarationOf(CssSourceModel.Rule rule, String property) {
        for (CssSourceModel.Declaration declaration : rule.declarations()) {
            if (declaration.property().equals(property)) return declaration;
        }
        return null;
    }
}
