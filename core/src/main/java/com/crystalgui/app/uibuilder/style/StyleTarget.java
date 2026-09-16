package com.crystalgui.app.uibuilder.style;

import javax.annotation.Nullable;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.source.CssSourceModel;
import com.crystalgui.text.TextBuffer;

/**
 * One place a style edit can land: the element's own inline style, or one rule in one sheet.
 *
 * <pre>{@code
 * for (StyleTarget target : StyleTargets.of(node, sheets).targets()) {
 *     chip(target.label(), target.isEditable(), () -> selection.selectStyleTarget(target.key()));
 * }
 * StyleFields fields = StyleFields.on(document, target, node);   // what it declares, read live
 * }</pre>
 *
 * <p><b>Where, never what.</b> A target names the rule; what the rule declares is read from its text through
 * {@link StyleFields} each time it is asked, so nothing here goes stale when the sheet changes.</p>
 *
 * <p>Only a rule in a <b>project</b> sheet can be written to. A shipped asset is inside a jar and the engine's
 * own sheet is not the author's to edit; both are listed and readable, and {@link #isEditable()} is false.</p>
 */
public final class StyleTarget {

    /** The element's own inline style — always available, always editable in a document. */
    public static final String INLINE_KEY = "";

    private static final StyleTarget INLINE = new StyleTarget(null, null, "", -1, "inline", true);

    @Nullable
    private final SheetDocuments.Sheet sheet;

    /** The live sheet the rule is in, for one the document does not name: the engine's own, a theme's. */
    @Nullable
    private final StyleSheet live;

    private final String sheetLabel;
    private final int ruleOrder;
    private final String selector;
    private final boolean inline;

    private StyleTarget(@Nullable SheetDocuments.Sheet sheet, @Nullable StyleSheet live, String sheetLabel,
                        int ruleOrder, String selector, boolean inline) {
        this.sheet = sheet;
        this.live = live;
        this.sheetLabel = sheetLabel;
        this.ruleOrder = ruleOrder;
        this.selector = selector;
        this.inline = inline;
    }

    /** The element's inline style. */
    public static StyleTarget inline() {
        return INLINE;
    }

    /**
     * A rule in a sheet the document names.
     *
     * @param ruleOrder the number the cascade gave it, or -1 for one found by {@code selector} — a rule with
     *                  nothing declared yet has no number
     */
    public static StyleTarget rule(SheetDocuments.Sheet sheet, int ruleOrder, String selector) {
        return new StyleTarget(sheet, sheet.sheet(), sheet.label(), ruleOrder, selector, false);
    }

    /** A rule in a sheet the document does not name — readable, never editable. */
    public static StyleTarget engineRule(StyleSheet sheet, String label, int ruleOrder, String selector) {
        return new StyleTarget(null, sheet, label, ruleOrder, selector, false);
    }

    /** What a rule found by its selector is remembered by, before it has a number. @see #key */
    public static String ruleKey(String sheetLabel, String selector) {
        return "rule:" + sheetLabel + ":" + selector;
    }

    /**
     * What the choice is remembered by — stable across a rebuild, and across the sheet being edited, since
     * a rule keeps its number while anything above it is only changed in place.
     */
    public String key() {
        if (isInline()) return INLINE_KEY;
        // A RULE THE CASCADE SKIPPED HAS NO NUMBER -- an empty one just written -- so it is named by its
        // selector until it has something to say.
        return ruleKey(sheetLabel, ruleOrder >= 0 ? String.valueOf(ruleOrder) : selector);
    }

    public boolean isInline() {
        return inline;
    }

    /** The selector as written, or {@code inline}. */
    public String selector() {
        return selector;
    }

    /** The chip's text: {@code inline}, or the rule's selector as written. */
    public String label() {
        return selector;
    }

    /** Which sheet it is in — a file name, an asset id, or {@code engine} for the user-agent sheet. */
    public String sheetLabel() {
        return sheetLabel;
    }

    /** The rule's number in its sheet, or -1 for inline or a rule found by selector. @see CssSourceModel.Rule#sourceOrder */
    public int ruleOrder() {
        return ruleOrder;
    }

    /** Whether an edit here lands anywhere: a project sheet, or inline. */
    public boolean isEditable() {
        return isInline() || (sheet != null && sheet.isEditable());
    }

    /** The text to edit, or null — inline, a shipped asset, or the engine's own sheet. */
    @Nullable
    public TextBuffer buffer() {
        return sheet == null ? null : sheet.buffer();
    }

    /** The document's entry for the sheet, or null for inline or a sheet the document does not name. */
    @Nullable
    public SheetDocuments.Sheet sheet() {
        return sheet;
    }

    /** The live sheet the rule is in, or null for inline. */
    @Nullable
    public StyleSheet liveSheet() {
        return live;
    }

    /** Why this cannot be written to, for a pane to say — null when it can. */
    @Nullable
    public String readOnlyReason() {
        if (isEditable()) return null;
        return sheet == null
                ? "The engine's own stylesheet is not editable here."
                : "A shipped stylesheet lives inside a jar. Copy it into the project to edit it.";
    }

    @Override
    public String toString() {
        return key() + " " + selector;
    }
}
