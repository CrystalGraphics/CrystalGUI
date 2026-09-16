package com.crystalgui.app.uibuilder.style;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.app.uibuilder.inspect.NodeFields;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.source.CssEdits;
import com.crystalgui.style.sheet.source.CssSourceModel;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.ui.dom.UIElement;

/**
 * What a control in the Styles tab is bound to: one declaration of one {@link StyleTarget}, read as the
 * sheet wrote it and written back as <b>one text edit at the bytes it names</b>.
 *
 * <pre>{@code
 * StyleFields fields = StyleFields.on(document, target, node);
 * form.prop(descriptor, fields.value("background"));    // typing lands in the sheet
 * fields.add("border-radius", "6px");                   // a new declaration in this rule
 * fields.setEnabled(declared, false);                   // commented out, and reversible
 * }</pre>
 *
 * <p>Two targets, two histories, and that is deliberate. A rule's edit goes through {@link CssEdits} into
 * the sheet's own {@code TextBuffer} — so an editor tab open on that file shows it land and undoing it
 * there undoes it here, because they are one buffer. An inline edit is a {@code SetInlineStyle} in the
 * {@code .cgui}'s history, exactly as the Element tab's rows are.</p>
 *
 * <ul>
 *   <li>{@link #canWrite()} first: the engine's sheet and a shipped asset are readable and not editable.</li>
 *   <li>A rule is found by its NUMBER, never by re-matching a selector — the number the cascade gave the
 *       slot that put the value on screen.</li>
 *   <li>Writing a property the rule does not declare yet <b>adds</b> it; writing an empty value removes it.</li>
 * </ul>
 */
public final class StyleFields {

    private final StyleTarget target;
    private final UIElement node;

    @Nullable
    private final UiBuilderDocument document;

    @Nullable
    private final NodeFields nodes;

    private StyleFields(@Nullable UiBuilderDocument document, StyleTarget target, UIElement node) {
        this.document = document;
        this.target = Objects.requireNonNull(target, "target");
        this.node = Objects.requireNonNull(node, "node");
        this.nodes = document == null ? null : NodeFields.on(document);
    }

    /** @param document the open {@code .cgui}, or null over a live pick — where an inline edit is recorded */
    public static StyleFields on(@Nullable UiBuilderDocument document, StyleTarget target, UIElement node) {
        return new StyleFields(document, target, node);
    }

    public StyleTarget target() {
        return target;
    }

    /** Whether anything written here lands. @see StyleTarget#readOnlyReason */
    public boolean canWrite() {
        return target.isEditable();
    }

    /**
     * The declaration's value as CSS text, read from the target and written straight back into it.
     *
     * <p>Setting it to blank removes the declaration, which is what an emptied field means in DevTools and
     * what makes "take this back off" reachable without a second control.</p>
     */
    public Property<String> value(String property) {
        return Property.derived(() -> valueOf(property), css -> write(property, css == null ? "" : css.trim()));
    }

    /** What the target declares for {@code property} right now, or "" when it declares nothing. */
    public String valueOf(String property) {
        StyleTarget.Declared declared = target.declaring(property);
        return declared == null ? "" : declared.value();
    }

    /** Adds a declaration this target does not have yet — what the palette's pick does. */
    public void add(String property, String value) {
        write(property, value);
    }

    /** Takes the declaration out of the rule, or off the element. */
    public void remove(String property) {
        write(property, "");
    }

    /**
     * Comments a declaration out, or brings it back — DevTools' checkbox.
     *
     * <p>Rules only: a comment is a thing a stylesheet can hold and an inline style cannot, so an inline row
     * offers removal instead.</p>
     *
     * @return whether anything changed
     */
    public boolean setEnabled(String property, boolean enabled) {
        TextBuffer buffer = target.buffer();
        if (buffer == null || target.isInline()) return false;
        String text = buffer.toString();
        CssSourceModel model = CssSourceModel.parse(text);
        CssSourceModel.Rule rule = ruleOf(model);
        if (rule == null) return false;

        if (!enabled) {
            CssSourceModel.Declaration declaration = lastDeclarationOf(rule, property);
            if (declaration == null) return false;
            // The declaration SPELLED OUT rather than sliced, and the semicolon taken with it: what comes
            // back when it is switched on again has to be a declaration a sheet can hold on its own.
            String body = declaration.property() + ": " + declaration.value() + ";";
            buffer.edit(ChangeSet.replace(text.length(), declaration.range().start(),
                    endOfStatement(text, declaration.range().end()), "/* " + body + " */"));
            return true;
        }
        for (CssSourceModel.Comment comment : model.comments()) {
            int at = comment.range().start();
            if (at < rule.bodyRange().start() || at >= rule.bodyRange().end()) continue;
            String inner = inner(comment.text());
            if (inner == null || !inner.startsWith(property + ":")) continue;
            buffer.edit(ChangeSet.replace(text.length(), comment.range().start(), comment.range().end(), inner));
            return true;
        }
        return false;
    }

    /** The registered property a name means, or null — a custom property, or a typo the sheet holds. */
    @Nullable
    public static StyleProperty<?> propertyOf(String name) {
        return StylePropertyRegistry.byName(name);
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    private void write(String property, String css) {
        if (!canWrite()) return;
        if (target.isInline()) {
            writeInline(property, css);
            return;
        }
        TextBuffer buffer = target.buffer();
        if (buffer == null) return;
        String text = buffer.toString();
        CssSourceModel model = CssSourceModel.parse(text);
        CssSourceModel.Rule rule = ruleOf(model);
        if (rule == null) return;   // the file changed under a stale pane; it will rebuild from the new text

        CssSourceModel.Declaration existing = lastDeclarationOf(rule, property);
        if (css.isEmpty()) {
            if (existing != null) buffer.edit(CssEdits.deleteDeclaration(model, existing));
            return;
        }
        buffer.edit(existing == null
                ? CssEdits.insertDeclaration(model, rule, property, css)
                : CssEdits.replaceValue(model, existing, css));
    }

    private void writeInline(String property, String css) {
        StyleProperty<?> styled = propertyOf(property);
        if (styled == null) return;
        if (nodes != null && document != null) {
            // The whole inline style as one edit, blank meaning "take it off" -- the Element tab's own rule.
            BuilderEdit edit = nodes.inlineEdit(node, styled, css);
            if (edit != null) document.apply(edit);
            return;
        }
        // A live pick: the running screen changes and nothing is recorded, because there is no document.
        if (css.isEmpty()) {
            LiveEdits.clearInline(node, styled);
        } else {
            LiveEdits.setInline(node, cast(styled), css);
        }
    }

    /**
     * The rule being edited: by the number the cascade gave it, or — for one the cascade skipped, which has
     * no number — by the selector it was written with.
     */
    @Nullable
    private CssSourceModel.Rule ruleOf(CssSourceModel model) {
        if (target.ruleOrder() >= 0) return model.ruleAt(target.ruleOrder());
        for (CssSourceModel.Rule rule : model.rules()) {
            if (model.textOf(rule.selectorsRange()).trim().equals(target.selector())) return rule;
        }
        return null;
    }

    /** The last one wins inside a rule, so it is the one an edit means. */
    @Nullable
    private static CssSourceModel.Declaration lastDeclarationOf(CssSourceModel.Rule rule, String property) {
        List<CssSourceModel.Declaration> declarations = rule.declarations();
        for (int i = declarations.size() - 1; i >= 0; i--) {
            if (declarations.get(i).property().equals(property)) return declarations.get(i);
        }
        return null;
    }

    /** Past the declaration's own semicolon, so commenting it out does not leave one behind. */
    private static int endOfStatement(String text, int end) {
        int at = end;
        while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '	')) at++;
        return at < text.length() && text.charAt(at) == ';' ? at + 1 : end;
    }

    /** A comment's text without its delimiters, or null when it holds no declaration. */
    @Nullable
    private static String inner(String comment) {
        String text = comment.trim();
        if (!text.startsWith("/*") || !text.endsWith("*/")) return null;
        text = text.substring(2, text.length() - 2).trim();
        return text.indexOf(':') > 0 && text.indexOf('{') < 0 ? text : null;
    }

    @SuppressWarnings("unchecked")
    private static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
