package com.crystalgui.widget.control;

import com.crystalgui.fs.SourceRoots;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.UIElement;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * What a declaration IS, drawn — a class glyph, an interface glyph, with {@code static} and
 * {@code final} marks over it.
 *
 * <h3>One widget, because there were two of everything</h3>
 *
 * <p>The completion popup grew this first: a box whose picture comes from a {@code completion-kind-*}
 * class, with two full-size mark layers parented to it. Then a library viewer's tab needed the same
 * answer and got a second implementation — a Java table of icon NAMES, resolved to a drawable and set as
 * an overlay, with no marks at all because those are elements rather than a name.</p>
 *
 * <p>Two tables saying one thing is the ordinary failure, and it is invisible here: a class glyph on an
 * interface looks like a tab with an icon rather than a tab with the wrong icon. This is the union —
 * the completion row and the tab now build the same element and the vocabulary exists once, in the
 * stylesheet, where a theme can reach it.</p>
 *
 * <h3>The class prefix still says {@code completion-}</h3>
 *
 * <p>Deliberately. It was never really the popup's — the documentation popup's owner band already used
 * it, on the stated ground that "one kind means one glyph everywhere" — and renaming it would rewrite
 * two dozen rules to say the same thing, breaking every theme that had learnt them. The name is
 * historical; the vocabulary is shared.</p>
 *
 * <h3>Kind and modifier are two axes</h3>
 *
 * <p>An abstract class is not a kind, it is a class that draws differently, so {@code abstract} refines
 * the kind through a compound selector. {@code static} and {@code final} are neither: they are
 * <b>full-size layers stacked over the glyph</b>, because JetBrains draws each mark on its own 16×16
 * canvas with the glyph already placed — static bottom-left, final top-left. That is what lets both show
 * at once, and scaling one into a small corner box re-does, badly, the positioning the artwork already
 * did.</p>
 */
public class SymbolIcon extends UIElement {

    public static final Name NAME = Name.of("symbolicon");

    /**
     * The box itself. Sized and given its picture by the cascade.
     *
     * <p>A CLASS and not a part, because it is on the widget itself: a part names something a rule can
     * reach INTO a widget for, and there is nothing to reach into here. Kept as a class rather than
     * folded into the {@code symbolicon} tag because two dozen rules in three sheets name it, and the
     * class is also what a caller puts on a plain box to borrow the vocabulary without the widget.</p>
     */
    public static final String ICON_CLASS = "__completion-icon__";

    /**
     * The same answer in words — "Final class", "Interface", "Abstract class".
     *
     * <p>Beside the glyph on purpose. A tooltip over an icon says what the icon means, so the picture and
     * the wording are one answer given twice, and the ordinary failure is that they drift: the glyph
     * gains a kind the sentence has never heard of and reads as "Unknown" over a perfectly good picture.
     * Both are switched off {@link SymbolKind} here, in one file.</p>
     *
     * <p><b>{@code abstract} and {@code final} lead; {@code static} does not.</b> The first two qualify
     * the noun — an abstract class is a kind of class — while a static <em>type</em> is a nested type,
     * which is a statement about where it is declared rather than what it is, and IntelliJ does not say
     * it either. The mark is still drawn; it is just not read out.</p>
     *
     * <p>Null for a kind with nothing worth saying, so a caller can leave the tooltip off entirely rather
     * than showing the word "Unknown" — which is worse than silence, because it looks like an answer.</p>
     */
    @Nullable
    public static String describe(@Nullable SymbolKind kind, @Nullable Set<SymbolModifier> modifiers) {
        String noun = nounFor(kind);
        if (noun == null) return null;
        Set<SymbolModifier> marks = modifiers == null ? Collections.emptySet() : modifiers;
        // ABSTRACT before FINAL, and never both: the two are mutually exclusive on a type in every
        // language this draws, so the order only decides which wins if a broken engine reports both.
        if (marks.contains(SymbolModifier.ABSTRACT)) return "Abstract " + noun.toLowerCase(Locale.ROOT);
        if (marks.contains(SymbolModifier.FINAL)) return "Final " + noun.toLowerCase(Locale.ROOT);
        return noun;
    }

    /** The bare noun for a kind, capitalised, or null where there is nothing useful to say. */
    @Nullable
    private static String nounFor(@Nullable SymbolKind kind) {
        if (kind == null) return null;
        switch (kind) {
            case CLASS:          return "Class";
            case INTERFACE:      return "Interface";
            case ENUM:           return "Enum";
            case RECORD:         return "Record";
            case EXCEPTION:      return "Exception";
            case ANNOTATION:     return "Annotation";
            case TYPE_PARAMETER: return "Type parameter";
            case METHOD:         return "Method";
            case CONSTRUCTOR:    return "Constructor";
            case FUNCTION:       return "Function";
            case FIELD:          return "Field";
            case ENUM_MEMBER:    return "Enum constant";
            case CONSTANT:       return "Constant";
            case PARAMETER:      return "Parameter";
            case LOCAL_VARIABLE: return "Local variable";
            case PROPERTY:       return "Property";
            case PACKAGE:        return "Package";
            case MODULE:         return "Module";
            // KEYWORD, LABEL and UNKNOWN deliberately fall through. A keyword has no declaration to
            // describe, and "Unknown" is a worse answer than none -- it reads as a fact rather than as
            // the absence of one.
            default:             return null;
        }
    }

    /**
     * The same answer for a directory's ROLE — "Module", "Sources root".
     *
     * <h3>Here, beside the symbol version, because it is the same question</h3>
     *
     * <p>A tree row's icon says what the node IS, and a tooltip over an icon says what the icon means.
     * That is one answer given twice whichever kind of node it is, and this class exists because there
     * were two of everything: the ordinary failure is that the glyph gains a case the sentence has never
     * heard of and reads as nothing over a perfectly good picture.</p>
     *
     * <p><b>Null for a package and a plain folder, deliberately.</b> "Module" and "Sources root" are
     * structural facts the row shows nowhere else — a directory called {@code main} does not look like a
     * module and {@code java} does not look like where packages start counting from. A package's name IS
     * its information, so a tooltip reading "Package" over {@code com} restates the row and costs a
     * hover, which is the same reason {@link #describe(SymbolKind, Set)} answers null rather than
     * "Unknown".</p>
     */
    @Nullable
    public static String describe(@Nullable SourceRoots.Role role) {
        if (role == null) return null;
        switch (role) {
            case MODULE:      return "Module";
            // IntelliJ's own wording, plural: a source root is one of possibly several roots OF sources,
            // not the root of one source.
            case SOURCE_ROOT: return "Sources root";
            default:          return null;
        }
    }

    /**
     * The {@code static} mark. {@code .__completion-icon__::part(completion-mark-static)} in a sheet.
     *
     * <p>A FULL-SIZE layer, not a badge in a corner box: JetBrains draws each mark on its own 16x16
     * canvas with the glyph already placed -- static bottom-left, final top-left -- which is what lets
     * both show at once. Scaled into a small corner instead, a mark silently draws a third too large
     * and in the wrong corner, and reads as bad artwork.</p>
     */
    public static final String STATIC_MARK_PART = "completion-mark-static";
    /** The {@code final} mark. See {@link #STATIC_MARK_PART}. */
    public static final String FINAL_MARK_PART = "completion-mark-final";

    /** {@code completion-kind-interface} — what the stylesheet keys the glyph on. */
    public static final String KIND_CLASS_PREFIX = "completion-kind-";

    /** {@code completion-mod-abstract} — the one modifier that changes the glyph itself. */
    public static final String MODIFIER_CLASS_PREFIX = "completion-mod-";

    private final ShadowRoot shadow;
    private final UIElement staticMark = new UIElement();
    private final UIElement finalMark = new UIElement();

    public SymbolIcon() {
        super(NAME);
        addClass(ICON_CLASS);
        // NOT HITTABLE BY DEFAULT, which is what every current consumer wants: a completion row is
        // clicked as a whole, and a tab's icon must not swallow the press that selects the tab.
        // A caller that needs the icon itself to be hoverable turns it back on -- see setHitTest.
        setHitTest(false);

        // A SHADOW TREE, which is what replaced markAsInternal/addInternalChild and the
        // __double-underscore__ class together: an outer rule cannot reach a mark at all except
        // through the part name this class chose to expose.
        this.shadow = attachShadow();

        staticMark.set(Attribute.PART, STATIC_MARK_PART);
        staticMark.setHitTest(false);
        shadow.append(staticMark);

        finalMark.set(Attribute.PART, FINAL_MARK_PART);
        finalMark.setHitTest(false);
        shadow.append(finalMark);

        show(null, Collections.emptySet());
    }

    /**
     * Draws {@code kind}, refined by {@code modifiers}. Null kind draws the unknown glyph.
     *
     * <p>Written as a swap rather than an add, for the reason a recycled row already records: a template
     * is a different declaration every time the view reuses it, so adding {@code completion-kind-class}
     * without removing {@code completion-kind-interface} leaves both on the element and the cascade
     * resolves whichever rule happens to win — which reads as a random glyph rather than a stale one.</p>
     */
    public SymbolIcon show(@Nullable SymbolKind kind, @Nullable Set<SymbolModifier> modifiers) {
        Set<SymbolModifier> marks = modifiers == null ? Collections.emptySet() : modifiers;
        swapPrefixed(this, KIND_CLASS_PREFIX,
                KIND_CLASS_PREFIX + (kind == null ? "unknown" : kind.name().toLowerCase(Locale.ROOT)));
        swapPrefixed(this, MODIFIER_CLASS_PREFIX,
                marks.contains(SymbolModifier.ABSTRACT) ? MODIFIER_CLASS_PREFIX + "abstract" : null);
        staticMark.setDisplayed(marks.contains(SymbolModifier.STATIC));
        finalMark.setDisplayed(marks.contains(SymbolModifier.FINAL));
        return this;
    }

    /**
     * Draws NOTHING of its own — for a consumer whose rows are not all declarations.
     *
     * <p>Distinct from {@code show(null, …)}, which draws the <b>unknown</b> glyph: that is the right
     * answer for a completion row, where every row IS a declaration and one whose kind could not be
     * worked out still needs a picture. A file tree is not like that. Most of its rows are folders and
     * plain files, and they carry their own icon already — so the kind class has to come off entirely,
     * or the unknown glyph paints as a BACKGROUND under the file-type icon\'s overlay and every ordinary
     * row grows a second picture behind the first.</p>
     */
    public SymbolIcon showNothing() {
        swapPrefixed(this, KIND_CLASS_PREFIX, null);
        swapPrefixed(this, MODIFIER_CLASS_PREFIX, null);
        staticMark.setDisplayed(false);
        finalMark.setDisplayed(false);
        return this;
    }

    private static void swapPrefixed(UIElement element, String prefix, @Nullable String wanted) {
        for (String name : new ArrayList<>(element.classes())) {
            if (name.startsWith(prefix) && !name.equals(wanted)) element.removeClass(name);
        }
        if (wanted != null) element.addClass(wanted);
    }
}
