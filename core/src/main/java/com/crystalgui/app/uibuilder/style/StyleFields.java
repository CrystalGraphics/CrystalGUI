package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.app.uibuilder.inspect.NodeFields;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.style.sheet.StyleRule;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.source.CssEdits;
import com.crystalgui.style.sheet.source.CssSourceModel;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.ui.dom.UIDocument;
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
 *   <li>{@code text-stroke} is one field on both targets. A sheet holds only the shorthand and an element
 *       only its two longhands, so an inline target reads and writes the pair behind that one name.</li>
 * </ul>
 */
public final class StyleFields {

    /**
     * One declaration as the target holds it — read from the sheet's text, or off the element for inline.
     *
     * @param property the registered property, or null for a shorthand, a custom property or a typo
     * @param disabled a declaration commented out in the rule, which is how a switched-off row is kept
     */
    public record Declared(@Nullable StyleProperty<?> property, String name, String value, boolean important,
                           boolean disabled) {
    }

    /** The shorthand a sheet must use and an element cannot hold. @see #valueOf */
    static final String TEXT_STROKE = "text-stroke";
    private static final String STROKE_WIDTH = "text-stroke-width";
    private static final String STROKE_COLOR = "text-stroke-color";

    private final StyleTarget target;
    private final UIElement node;

    @Nullable
    private final UiBuilderDocument document;

    @Nullable
    private final NodeFields nodes;

    /** The sheet as last parsed, and the buffer version it was parsed at. @see #model() */
    @Nullable
    private CssSourceModel model;

    private String modelText = "";
    private int modelVersion = -1;

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
     * what makes "take this back off" reachable without a second control. Its edits go into the history
     * the text lives in, so a drag bound to it is one undo step.</p>
     */
    public Property<String> value(String property) {
        return Property.derived(() -> valueOf(property), css -> write(property, css == null ? "" : css.trim()))
                .editedIn(history());
    }

    /** Where an edit here is undone: the sheet's own buffer for a rule, the document for inline. */
    @Nullable
    public UndoStack history() {
        if (target.isInline()) return document == null ? null : document.history();
        TextBuffer buffer = target.buffer();
        return buffer == null ? null : buffer.history();
    }

    /**
     * What the target declares for {@code property} right now, or "" when it declares nothing.
     *
     * <p><b>Read from where a write lands, never from the {@link StyleTarget}'s own list.</b> That list is
     * a snapshot of one cascade pass, and the row set is deliberately not rebuilt when a value changes —
     * so a control bound here, which re-reads every frame, would read back the value its own edit replaced
     * and spring to it, while the element on the canvas showed the new one.</p>
     */
    public String valueOf(String property) {
        if (target.isInline() && TEXT_STROKE.equals(property)) {
            String width = inlineValueOf(STROKE_WIDTH);
            String color = inlineValueOf(STROKE_COLOR);
            return (width + " " + color).trim();
        }
        if (target.isInline()) return inlineValueOf(property);
        CssSourceModel model = model();
        if (model == null) return "";
        CssSourceModel.Rule rule = ruleOf(model);
        if (rule == null) return "";
        CssSourceModel.Declaration declaration = lastDeclarationOf(rule, property);
        if (declaration != null) return declaration.value();
        String disabled = commentedValueOf(model, rule, property);
        return disabled == null ? "" : disabled;
    }

    /**
     * What the target declares right now, in source order — a rule's declarations and its switched-off ones,
     * or the element's inline properties.
     */
    public List<Declared> declared() {
        List<Declared> out = new ArrayList<>();
        if (target.isInline()) {
            boolean stroke = false;
            for (Map.Entry<StyleProperty<?>, List<StyleSlot<?>>> entry : node.getStyle().candidates.entrySet()) {
                for (StyleSlot<?> slot : entry.getValue()) {
                    if (slot.origin() != StyleOrigin.INLINE) continue;
                    StyleProperty<?> property = entry.getKey();
                    // THE STROKE IS ONE DECLARATION on either target: the element holds two longhands a sheet
                    // may not even write, and a row each showed names the palette does not offer.
                    if (property.name.equals(STROKE_WIDTH) || property.name.equals(STROKE_COLOR)) {
                        if (!stroke) out.add(new Declared(null, TEXT_STROKE, valueOf(TEXT_STROKE), false, false));
                        stroke = true;
                        break;
                    }
                    out.add(new Declared(property, property.name, inlineValueOf(property.name), false, false));
                    break;
                }
            }
            // SWITCHED OFF WHERE THEY STAND: a text with no value under it.
            boolean hiddenStroke = false;
            for (StyleProperty<?> property : node.getStyle().inlineTextProperties()) {
                if (LiveEdits.hasInline(node, property)) continue;
                if (property.name.equals(STROKE_WIDTH) || property.name.equals(STROKE_COLOR)) {
                    // One row for the pair, hidden once neither half is live.
                    if (!stroke && !hiddenStroke) out.add(new Declared(null, TEXT_STROKE, valueOf(TEXT_STROKE), false, true));
                    hiddenStroke = true;
                    continue;
                }
                String text = node.getStyle().inlineText(property);
                out.add(new Declared(property, property.name, CssValues.bodyOf(text), false, true));
            }
            return out;
        }
        CssSourceModel model = model();
        CssSourceModel.Rule rule = model == null ? null : ruleOf(model);
        if (rule == null) return out;
        for (CssSourceModel.Declaration declaration : rule.declarations()) {
            out.add(new Declared(propertyOf(declaration.property()), declaration.property(), declaration.value(),
                    declaration.important(), false));
        }
        for (CssSourceModel.Comment comment : model.comments()) {
            int at = comment.range().start();
            if (at < rule.bodyRange().start() || at >= rule.bodyRange().end()) continue;
            String inner = inner(comment.text());
            if (inner == null) continue;
            int colon = inner.indexOf(':');
            String name = inner.substring(0, colon).trim();
            String value = inner.substring(colon + 1).trim();
            if (value.endsWith(";")) value = value.substring(0, value.length() - 1).trim();
            if (name.isEmpty() || value.isEmpty() || name.indexOf(' ') >= 0) continue;
            out.add(new Declared(propertyOf(name), name, value, false, true));
        }
        return out;
    }

    /** The declaration of {@code name}, or null when the target has none. */
    @Nullable
    public Declared declared(String name) {
        for (Declared declared : declared()) {
            if (declared.name().equals(name)) return declared;
        }
        return null;
    }

    /**
     * {@link #declared()} as a property, for a list of rows that follows the target.
     *
     * <p>A rule's list is told when its sheet's text changes rather than polled; an inline list is polled,
     * which costs a walk of the element's candidates.</p>
     */
    public Property<List<Declared>> declarations() {
        Property<List<Declared>> declarations = Property.derived(this::declared);
        TextBuffer buffer = target.isInline() ? null : target.buffer();
        if (buffer == null) return declarations;
        return declarations.announcedBy(refresh -> buffer.onChanged.connect(change -> refresh.run()));
    }

    /**
     * Whether this target's declaration of {@code name} is the one the element uses — false is what a row
     * draws struck through. Asked of the cascade, which already knows, so it is cheap enough to poll.
     */
    public boolean wins(String name) {
        StyleProperty<?> property = longhandOf(name);
        StyleSlot<?> winner = property == null ? null : node.getStyle().computeCandidateSlot(cast(property));
        if (winner == null) return false;
        StyleOrigin origin = winner.origin();
        if (target.isInline()) return origin == StyleOrigin.INLINE;
        // A WIDGET'S SLOT IS NOT A SHEET'S, though an inline one packs source order 0 and so decodes as sheet 0,
        // rule 0. A sheet's declaration has the sheet's own origin, or is one of its marked !important.
        if (origin == StyleOrigin.INLINE || origin == StyleOrigin.ANIMATION || origin == StyleOrigin.DEFAULT) {
            return false;
        }
        UIDocument window = node.document();
        StyleSheet sheet = target.liveSheet();
        if (window == null || sheet == null) return false;
        if (origin != sheet.getOrigin()) {
            Declared declared = declared(name);
            if (declared == null || !declared.important()) return false;
        }
        if (StyleEngine.sheetIndexOf(winner.sourceOrder()) != window.styles().getSheets().indexOf(sheet)) {
            return false;
        }
        int order = target.ruleOrder();
        return order < 0 || StyleEngine.ruleOrderOf(winner.sourceOrder()) == order;
    }

    /**
     * The property a declaration of {@code name} sets: itself, or for a shorthand the first longhand it expands
     * to, which is what decides whether the shorthand won.
     */
    @Nullable
    private StyleProperty<?> longhandOf(String name) {
        StyleProperty<?> property = propertyOf(name);
        if (property != null) return property;
        StyleProperty<?> known = LONGHANDS.get(name);
        if (known != null) return known;
        Declared declared = declared(name);
        if (declared == null) return null;
        try {
            List<StyleRule> rules = StyleSheet.parse("x { " + name + ": " + declared.value() + "; }").getRules();
            if (rules.isEmpty() || rules.get(0).declarations().isEmpty()) return null;
            StyleProperty<?> first = rules.get(0).declarations().get(0).property();
            LONGHANDS.put(name, first);
            return first;
        } catch (RuntimeException unparsed) {
            return null;
        }
    }

    /** A shorthand's name to the longhand it leads with, which is the same for every value. */
    private static final Map<String, StyleProperty<?>> LONGHANDS = new ConcurrentHashMap<>();

    /** What the element itself carries, in the spelling a sheet would need to hold to mean the same thing. */
    private String inlineValueOf(String property) {
        StyleProperty<?> styled = propertyOf(property);
        if (styled == null) return "";
        String text = node.getStyle().inlineText(styled);
        if (text != null) return CssValues.isOff(text) ? CssValues.bodyOf(text) : text;
        List<StyleSlot<?>> slots = node.getStyle().candidates.get(styled);
        if (slots == null) return "";
        for (StyleSlot<?> slot : slots) {
            if (slot.origin() == StyleOrigin.INLINE) return written(styled, slot.value());
        }
        return "";
    }

    /** A switched-off declaration is a comment in the rule body, and still has a value its row shows. */
    @Nullable
    private static String commentedValueOf(CssSourceModel model, CssSourceModel.Rule rule, String property) {
        for (CssSourceModel.Comment comment : model.comments()) {
            int at = comment.range().start();
            if (at < rule.bodyRange().start() || at >= rule.bodyRange().end()) continue;
            String inner = inner(comment.text());
            if (inner == null || !inner.startsWith(property + ":")) continue;
            String value = inner.substring(property.length() + 1).trim();
            return value.endsWith(";") ? value.substring(0, value.length() - 1).trim() : value;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String written(StyleProperty<?> property, @Nullable Object value) {
        return value == null ? "" : ((StyleProperty<Object>) property).write(value);
    }

    /**
     * The sheet's text, parsed once per edit rather than once per read — every bound control re-reads
     * {@link #valueOf} every frame, and a project sheet is not free to parse.
     */
    @Nullable
    private CssSourceModel model() {
        TextBuffer buffer = target.buffer();
        if (buffer == null) {
            // THE ENGINE'S OWN SHEET, or one inside a jar: read-only, so parsed once from its own text.
            StyleSheet live = target.liveSheet();
            if (model == null && live != null && live.source() != null) model = CssSourceModel.parse(live.source());
            return model;
        }
        if (model == null || buffer.version() != modelVersion) {
            modelVersion = buffer.version();
            modelText = buffer.toString();
            model = CssSourceModel.parse(modelText);
        }
        return model;
    }

    /**
     * Adds a declaration this target does not have yet — what the palette's pick does.
     *
     * <p><b>Never dropped as redundant.</b> An ordinary inline write withdraws a value equal to what the
     * element already computes without it, which is right when somebody sets a field back to its default —
     * and wrong here, because a property added at its initial value is exactly that, so the row asked for
     * vanished as it was created.</p>
     */
    public void add(String property, String value) {
        if (!canWrite()) return;
        if (!target.isInline() || TEXT_STROKE.equals(property)) {
            write(property, value);
            return;
        }
        StyleProperty<?> styled = propertyOf(property);
        if (styled == null) return;
        JsonElement was = NodeFields.inlineStyleOf(node);
        if (!LiveEdits.setInline(node, styled, value)) return;
        JsonElement after = NodeFields.inlineStyleOf(node);
        if (document != null && !after.equals(was)) {
            document.apply(new BuilderEdit.SetInlineStyle(node, was, after));
        }
    }

    /** Takes the declaration out of the rule, or off the element. */
    public void remove(String property) {
        write(property, "");
    }

    /**
     * Comments a declaration out, or brings it back — the row's eye.
     *
     * <p>In a rule it becomes a comment in the rule body; inline it is kept where it stands as a commented value,
     * {@code "color": "/* #FFF *}{@code /"}, which the element's style holds and the file carries.</p>
     *
     * @return whether anything changed
     */
    public boolean setEnabled(String property, boolean enabled) {
        if (target.isInline()) return setInlineEnabled(property, enabled);
        CssSourceModel model = model();
        TextBuffer buffer = target.buffer();
        if (model == null || buffer == null) return false;
        String text = modelText;
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

    private boolean setInlineEnabled(String property, boolean enabled) {
        if (!canWrite()) return false;
        JsonElement was = NodeFields.inlineStyleOf(node);
        if (TEXT_STROKE.equals(property)) {
            // THE STROKE IS TWO LONGHANDS inline, so each is switched where it stands and the row is one.
            for (String longhand : List.of(STROKE_WIDTH, STROKE_COLOR)) {
                String value = CssValues.bodyOf(inlineValueOf(longhand));
                if (!value.isEmpty()) LiveEdits.setInline(node, propertyOf(longhand), CssValues.switched(value, enabled));
            }
        } else {
            StyleProperty<?> styled = propertyOf(property);
            String value = styled == null ? "" : CssValues.bodyOf(valueOf(property));
            if (value.isEmpty() || !LiveEdits.setInline(node, styled, CssValues.switched(value, enabled))) return false;
        }
        JsonElement after = NodeFields.inlineStyleOf(node);
        if (document != null && !after.equals(was)) document.apply(new BuilderEdit.SetInlineStyle(node, was, after));
        return !after.equals(was);
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
        CssSourceModel model = model();
        TextBuffer buffer = target.buffer();
        if (model == null || buffer == null) return;
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
        if (TEXT_STROKE.equals(property)) {
            // THE COLOR IS WHICHEVER TERM PARSES AS ONE, the width the other: both orders are CSS.
            String width = "";
            String color = "";
            for (String term : CssValues.terms(css)) {
                if (ColorValue.parseCssColor(term) != null) color = term;
                else width = term;
            }
            writeInline(STROKE_WIDTH, width);
            writeInline(STROKE_COLOR, css.isEmpty() ? "" : color);
            return;
        }
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
            LiveEdits.setInline(node, styled, css);
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
    static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
