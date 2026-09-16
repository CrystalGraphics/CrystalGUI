package com.crystalgui.app.uibuilder.style;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.sheet.source.CssSourceModel;
import com.crystalgui.text.TextBuffer;

/**
 * One place a style edit can land: the element's own inline style, or one rule in one sheet.
 *
 * <pre>{@code
 * for (StyleTarget target : StyleTargets.of(node, sheets)) {
 *     chip(target.label(), target.isEditable(), () -> selection.selectStyleTarget(target.key()));
 * }
 * }</pre>
 *
 * <p>A target lists what it <b>declares</b> — the authored text, not the computed result — so a rule shows
 * `18%` where the box shows `43px`, and a declaration that lost to something stronger is still listed, with
 * {@link Declared#won()} false. A declaration commented out is listed too, as {@link Declared#disabled()}:
 * that is where a toggled-off row goes and how it comes back.</p>
 *
 * <p>Only a rule in a <b>project</b> sheet can be written to. A shipped asset is inside a jar, and the
 * engine's own user-agent sheet is not the author's to edit; both are listed, both are readable, and
 * {@link #isEditable()} is false for each.</p>
 */
public final class StyleTarget {

    /** One declaration as the sheet wrote it. */
    public record Declared(@Nullable StyleProperty<?> property, String name, String value,
                           boolean important, boolean won, boolean disabled) {

        /** The registered property, or null for a name no property claims — a typo, or a custom one. */
        public boolean isKnown() {
            return property != null;
        }
    }

    /** The element's own inline style — always available, always editable in a document. */
    public static final String INLINE_KEY = "";

    @Nullable
    private final SheetDocuments.Sheet sheet;

    private final String sheetLabel;
    private final int ruleOrder;
    private final String selector;
    private final List<Declared> declarations;

    StyleTarget(@Nullable SheetDocuments.Sheet sheet, String sheetLabel, int ruleOrder, String selector,
                List<Declared> declarations) {
        this.sheet = sheet;
        this.sheetLabel = sheetLabel;
        this.ruleOrder = ruleOrder;
        this.selector = selector;
        this.declarations = List.copyOf(declarations);
    }

    static StyleTarget inline(List<Declared> declarations) {
        return new StyleTarget(null, "", -1, "inline", declarations);
    }

    /**
     * What the choice is remembered by — stable across a rebuild, and across the sheet being edited, since
     * a rule keeps its number while anything above it is only changed in place.
     */
    public String key() {
        return isInline() ? INLINE_KEY : "rule:" + sheetLabel + ":" + ruleOrder;
    }

    public boolean isInline() {
        return ruleOrder < 0;
    }

    /** The chip's text: {@code inline}, or the rule's selector as written. */
    public String label() {
        return selector;
    }

    /** Which sheet it is in — a file name, an asset id, or {@code engine} for the user-agent sheet. */
    public String sheetLabel() {
        return sheetLabel;
    }

    /** The rule's number in its sheet, or -1 for inline. @see CssSourceModel.Rule#sourceOrder */
    public int ruleOrder() {
        return ruleOrder;
    }

    /** What this target declares, in source order. */
    public List<Declared> declarations() {
        return declarations;
    }

    @Nullable
    public Declared declaring(String property) {
        for (Declared declared : declarations) {
            if (declared.name().equals(property)) return declared;
        }
        return null;
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

    /** The sheet this rule is in, or null for inline. */
    @Nullable
    public SheetDocuments.Sheet sheet() {
        return sheet;
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
