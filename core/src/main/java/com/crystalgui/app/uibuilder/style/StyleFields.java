package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.inspect.Declarations;
import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.app.uibuilder.inspect.NodeFields;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.style.CssComments;
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
 *   <li>A {@link Group} is one field on both targets -- {@code border-radius}, {@code border-width},
 *       {@code outline-offset}, {@code outline}, {@code text-stroke}. A sheet holds the shorthand and an element its
 *       longhands, so an inline target reads and writes them behind that one name, in one edit.</li>
 * </ul>
 */
public final class StyleFields implements Declarations {

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

    /** A width and a color as one declaration -- or, spelled with a function, the drawable ring the engine also takes. */
    static final String OUTLINE = "outline";

    /**
     * The corners as one declaration. A sheet may write the shorthand or its eight longhands, and an element holds the
     * longhands; the Styles tab shows and edits them as the shorthand either way, one row where there were eight.
     */
    static final String BORDER_RADIUS = "border-radius";

    /** The longhands in the order CSS states them: top-left, top-right, bottom-right, bottom-left, each x then y. */
    static final List<String> RADIUS_LONGHANDS = List.of(
            "border-top-left-radius-x", "border-top-left-radius-y", "border-top-right-radius-x",
            "border-top-right-radius-y", "border-bottom-right-radius-x", "border-bottom-right-radius-y",
            "border-bottom-left-radius-x", "border-bottom-left-radius-y");

    /** The four border widths as one declaration, which a sheet may also write. */
    static final String BORDER_WIDTH = "border-width";
    /** The outline's four offsets as one declaration, which a sheet may also write. */
    static final String OUTLINE_OFFSET = "outline-offset";

    /** How a group's longhands are spelled as its shorthand. */
    enum Spelling {
        /** {@code border-radius}: eight radii, a slash before the vertical ones when they differ. */
        CORNERS,
        /** {@code border-width}, {@code outline-offset}: four sides, top, right, bottom, left, collapsed as CSS allows. */
        SIDES,
        /**
         * {@code text-stroke}, {@code outline}: a width and a color, whichever term parses as a color being the color. An
         * {@code outline} spelled with a function is its drawable, a third longhand that takes the place of both.
         */
        STROKE
    }

    /**
     * Longhands the Styles tab shows and edits as the shorthand a sheet writes: one row, one Add property entry, one
     * undo step.
     *
     * <pre>{@code
     * StyleFields.groupOf("border-top-width");   // the border-width group
     * StyleFields.group("outline").unpack("2px #FFFFFF");   // [2px, #FFFFFF, ""]
     * }</pre>
     */
    record Group(String name, List<String> longhands, Spelling spelling) {

        /** The values as the shorthand is written, "" when none is set. @see #radiusShorthand */
        String pack(String[] values) {
            switch (spelling) {
                case CORNERS -> {
                    return radiusShorthand(values);
                }
                case STROKE -> {
                    // THE DRAWABLE FIRST, as the engine gives it precedence over the stroke.
                    if (values.length > 2 && values[2] != null && !values[2].isEmpty()) return values[2];
                    return ((values[0] == null ? "" : values[0]) + " " + (values[1] == null ? "" : values[1])).trim();
                }
                default -> {
                    boolean any = false;
                    for (String value : values) any |= value != null && !value.isEmpty();
                    if (!any) return "";
                    String[] sides = new String[4];
                    for (int i = 0; i < 4; i++) {
                        sides[i] = values[i] == null || values[i].isEmpty() ? "0px" : CssValues.readable(values[i]);
                    }
                    return side(sides);
                }
            }
        }

        /** The shorthand as its longhands' values, "" for one it does not set, or null when malformed. */
        @Nullable
        String[] unpack(String css) {
            return switch (spelling) {
                case CORNERS -> radiusLonghands(css);
                case SIDES -> corners(CssComments.strip(css));
                case STROKE -> {
                    String[] out = new String[longhands.size()];
                    Arrays.fill(out, "");
                    // A FUNCTION IS THE DRAWABLE, as OutlineShorthand reads it.
                    if (out.length > 2 && css.indexOf('(') >= 0) {
                        out[2] = css.trim();
                        yield out;
                    }
                    for (String term : CssValues.terms(css)) {
                        if (ColorValue.parseCssColor(term) != null) out[1] = term;
                        else out[0] = term;
                    }
                    yield out;
                }
            };
        }
    }

    static final List<Group> GROUPS = List.of(
            new Group(BORDER_RADIUS, RADIUS_LONGHANDS, Spelling.CORNERS),
            new Group(BORDER_WIDTH, List.of("border-top-width", "border-right-width", "border-bottom-width",
                    "border-left-width"), Spelling.SIDES),
            new Group(OUTLINE_OFFSET, List.of("outline-offset-top", "outline-offset-right", "outline-offset-bottom",
                    "outline-offset-left"), Spelling.SIDES),
            new Group(OUTLINE, List.of("outline-width", "outline-color", OUTLINE), Spelling.STROKE),
            new Group(TEXT_STROKE, List.of(STROKE_WIDTH, STROKE_COLOR), Spelling.STROKE));

    /** The group a longhand belongs to, or null. */
    @Nullable
    static Group groupOf(String longhand) {
        for (Group group : GROUPS) {
            if (group.longhands().contains(longhand)) return group;
        }
        return null;
    }

    /** The group a shorthand names, or null. */
    @Nullable
    static Group group(String name) {
        for (Group group : GROUPS) {
            if (group.name().equals(name)) return group;
        }
        return null;
    }

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

    // ── Where a gizmo's edits land ──────────────────────────────────────────
    //
    // THE INSPECTOR'S ROWS AND GIZMOS ARE TARGET-BLIND: they name a declaration, and this is the target answering.
    // @see Declarations

    @Override
    public String valueOf(StyleProperty<?> property) {
        return valueOf(property.name);
    }

    @Override
    public Property<String> value(StyleProperty<?> property) {
        return value(property.name);
    }

    @Override
    public boolean set(StyleProperty<?> property, String css) {
        // INSIDE AN ELEMENT'S GESTURE the write is live and unrecorded, which is the inline style's own mechanism.
        if (inGesture != null) return inGesture.set(property, css);
        write(property.name, css);
        return true;
    }

    @Override
    public boolean declares(StyleProperty<?> property) {
        return !valueOf(property.name).isEmpty();
    }

    /**
     * A gesture's writes are one undo step: the run holds the history open, and every write inside it folds into
     * the one before it. @see UndoStack#beginMergeRun
     */
    @Override
    public void beginGesture() {
        // AN ELEMENT'S GESTURE IS THE INLINE STYLE'S: it snapshots the whole of it, writes live and records the
        // difference once -- so a cancelled edit records NOTHING, which a text put back cannot manage.
        if (target.isInline() && document != null) {
            inGesture = Declarations.inline(node, document);
            inGesture.beginGesture();
            return;
        }
        gestureFrom = new LinkedHashMap<>();
        UndoStack history = history();
        if (history != null) history.beginMergeRun();
    }

    @Override
    public void endGesture(boolean keep) {
        if (inGesture != null) {
            inGesture.endGesture(keep);
            inGesture = null;
            return;
        }
        Map<String, String> was = gestureFrom;
        // PUT BACK WHAT EACH ONE SAID, which is what a cancelled edit means wherever the declaration lives --
        // INSIDE the run, so the writes and their undoing are one step, and only where the value actually moved,
        // so a gesture that wrote nothing records nothing.
        if (!keep && was != null) {
            was.forEach((property, text) -> {
                if (!valueOf(property).equals(text)) write(property, text);
            });
        }
        gestureFrom = null;
        UndoStack history = history();
        if (history != null) history.endMergeRun();
    }

    /** What each property said when the open gesture began, so a cancelled one can put it back. */
    @Nullable
    private Map<String, String> gestureFrom;

    /** The element's own gesture, while one is open over an inline target. @see #beginGesture */
    @Nullable
    private Declarations inGesture;

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
        Group grouped = group(property);
        if (target.isInline() && grouped != null) {
            String[] values = new String[grouped.longhands().size()];
            for (int i = 0; i < values.length; i++) values[i] = inlineValueOf(grouped.longhands().get(i));
            return grouped.pack(values);
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
            List<Group> shown = new ArrayList<>();
            for (Map.Entry<StyleProperty<?>, List<StyleSlot<?>>> entry : node.getStyle().candidates.entrySet()) {
                for (StyleSlot<?> slot : entry.getValue()) {
                    if (slot.origin() != StyleOrigin.INLINE) continue;
                    StyleProperty<?> property = entry.getKey();
                    // SEVERAL LONGHANDS, ONE DECLARATION: the corners, the widths, the offsets, the stroke, the
                    // outline. A row each showed names the palette does not offer, and some a sheet may not write.
                    Group group = groupOf(property.name);
                    if (group != null) {
                        if (!shown.contains(group)) out.add(new Declared(null, group.name(), valueOf(group.name()), false, false));
                        if (!shown.contains(group)) shown.add(group);
                        break;
                    }
                    out.add(new Declared(property, property.name, inlineValueOf(property.name), false, false));
                    break;
                }
            }
            // SWITCHED OFF WHERE THEY STAND: a text with no value under it.
            List<Group> hiddenGroups = new ArrayList<>();
            for (StyleProperty<?> property : node.getStyle().inlineTextProperties()) {
                if (LiveEdits.hasInline(node, property)) continue;
                // One row for the group, hidden once none of it is live.
                Group group = groupOf(property.name);
                if (group != null) {
                    if (!shown.contains(group) && !hiddenGroups.contains(group)) {
                        out.add(new Declared(null, group.name(), hiddenValue(group), false, true));
                        hiddenGroups.add(group);
                    }
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
        // A GROUP BY WHICHEVER LONGHAND THE CASCADE HOLDS: `outline` is also the drawable's own name, so reading it by
        // name alone judged a width-and-color outline by a drawable nothing set -- lost, and struck through.
        Group group = group(name);
        if (group != null) {
            for (String longhand : group.longhands()) {
                StyleProperty<?> candidate = propertyOf(longhand);
                if (candidate != null && node.getStyle().computeCandidateSlot(cast(candidate)) != null) return candidate;
            }
        }
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
        if (!target.isInline() || group(property) != null) {
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
            // SAFE INSIDE A COMMENT: a value may carry a switched-off layer of its own, and CSS comments do not
            // nest -- the value's own would have closed this one. @see CssValues#forComment
            String body = declaration.property() + ": " + declaration.value() + ";";
            buffer.edit(ChangeSet.replace(text.length(), declaration.range().start(),
                    endOfStatement(text, declaration.range().end()), "/* " + CssValues.forComment(body) + " */"));
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
        if (group(property) != null) {
            // A GROUP IS SEVERAL LONGHANDS inline, so each is switched where it stands and the row is one.
            for (String longhand : group(property).longhands()) {
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

    /**
     * A group's shorthand, expanded onto the element's longhands as ONE edit: eight writes were eight undo steps for a
     * value typed once. Blank takes them all off.
     */
    private void writeGroupInline(Group group, String css) {
        int count = group.longhands().size();
        String[] values = css.isEmpty() ? new String[count] : group.unpack(css);
        if (values == null) return;
        JsonElement was = NodeFields.inlineStyleOf(node);
        for (int i = 0; i < count; i++) {
            StyleProperty<?> longhand = propertyOf(group.longhands().get(i));
            if (values[i] == null || values[i].isEmpty()) LiveEdits.clearInline(node, longhand);
            else LiveEdits.setInline(node, longhand, values[i]);
        }
        JsonElement after = NodeFields.inlineStyleOf(node);
        if (document != null && !after.equals(was)) document.apply(new BuilderEdit.SetInlineStyle(node, was, after));
    }

    /** A group as a hidden row reads it: the commented longhands' values. */
    private String hiddenValue(Group group) {
        String[] values = new String[group.longhands().size()];
        for (int i = 0; i < values.length; i++) {
            String text = node.getStyle().inlineText(propertyOf(group.longhands().get(i)));
            values[i] = text == null ? "" : CssValues.bodyOf(text);
        }
        return group.pack(values);
    }

    /**
     * Eight corner values as {@code border-radius} is written: each side's four collapsed as CSS allows, and the
     * vertical radii after a slash only when they differ. "" when no corner is set; an unset corner reads as 0.
     */
    static String radiusShorthand(String[] corners) {
        boolean any = false;
        for (String corner : corners) any |= corner != null && !corner.isEmpty();
        if (!any) return "";
        String[] xs = new String[4], ys = new String[4];
        // READABLE, so an element's stored `6.0px` compares and reads as the `6px` a sheet would hold.
        for (int i = 0; i < 4; i++) {
            xs[i] = corners[i * 2] == null || corners[i * 2].isEmpty() ? "0px" : CssValues.readable(corners[i * 2]);
            ys[i] = corners[i * 2 + 1] == null || corners[i * 2 + 1].isEmpty() ? "0px" : CssValues.readable(corners[i * 2 + 1]);
        }
        String horizontal = side(xs);
        String vertical = side(ys);
        return horizontal.equals(vertical) ? horizontal : horizontal + " / " + vertical;
    }

    /** Four corners as few values as CSS reads back to the same four. */
    private static String side(String[] c) {
        if (c[0].equals(c[1]) && c[1].equals(c[2]) && c[2].equals(c[3])) return c[0];
        if (c[0].equals(c[2]) && c[1].equals(c[3])) return c[0] + " " + c[1];
        if (c[1].equals(c[3])) return c[0] + " " + c[1] + " " + c[2];
        return String.join(" ", c);
    }

    /**
     * {@code border-radius} expanded to its eight longhands' values, in {@link #RADIUS_LONGHANDS} order, as the engine's
     * own shorthand does: one to four values, then optionally a slash and one to four more. Null when malformed.
     */
    @Nullable
    static String[] radiusLonghands(String css) {
        String[] halves = CssComments.strip(css).split("/", 2);
        String[] xs = corners(halves[0]);
        String[] ys = halves.length == 2 ? corners(halves[1]) : xs;
        if (xs == null || ys == null) return null;
        String[] out = new String[8];
        for (int i = 0; i < 4; i++) {
            out[i * 2] = xs[i];
            out[i * 2 + 1] = ys[i];
        }
        return out;
    }

    @Nullable
    private static String[] corners(String list) {
        String[] t = list.trim().split("\\s+");
        return switch (t.length) {
            case 1 -> new String[] {t[0], t[0], t[0], t[0]};
            case 2 -> new String[] {t[0], t[1], t[0], t[1]};
            case 3 -> new String[] {t[0], t[1], t[2], t[1]};
            case 4 -> new String[] {t[0], t[1], t[2], t[3]};
            default -> null;
        };
    }

    /** The element this edits. */
    public UIElement node() {
        return node;
    }

    /** The registered property a name means, or null — a custom property, or a typo the sheet holds. */
    @Nullable
    public static StyleProperty<?> propertyOf(String name) {
        return StylePropertyRegistry.byName(name);
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    private void write(String property, String css) {
        if (gestureFrom != null) gestureFrom.putIfAbsent(property, valueOf(property));
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
        Group grouped = group(property);
        if (grouped != null) {
            writeGroupInline(grouped, css);
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
        text = CssValues.fromComment(text.substring(2, text.length() - 2).trim());
        return text.indexOf(':') > 0 && text.indexOf('{') < 0 ? text : null;
    }

    @SuppressWarnings("unchecked")
    static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
