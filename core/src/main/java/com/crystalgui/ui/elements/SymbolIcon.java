package com.crystalgui.ui.elements;

import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.ui.UIElement;

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

    /** The box itself. Sized and given its picture by the cascade. */
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

    public static final String STATIC_MARK_CLASS = "__completion-mark-static__";
    public static final String FINAL_MARK_CLASS = "__completion-mark-final__";

    /** {@code completion-kind-interface} — what the stylesheet keys the glyph on. */
    public static final String KIND_CLASS_PREFIX = "completion-kind-";

    /** {@code completion-mod-abstract} — the one modifier that changes the glyph itself. */
    public static final String MODIFIER_CLASS_PREFIX = "completion-mod-";

    private final UIElement staticMark = new UIElement();
    private final UIElement finalMark = new UIElement();

    public SymbolIcon() {
        addClass(ICON_CLASS);
        // NOT HITTABLE BY DEFAULT, which is what every current consumer wants: a completion row is
        // clicked as a whole, and a tab's icon must not swallow the press that selects the tab.
        // A caller that needs the icon itself to be hoverable turns it back on -- see setHitTest.
        setHitTest(false);

        staticMark.addClass(STATIC_MARK_CLASS);
        staticMark.setHitTest(false);
        // INTERNAL, which is what a widget's own parts are: skipped by public traversal and by
        // UIDescriptionCodec, and still perfectly styleable as a selector SUBJECT -- it is marking the
        // WIDGET itself internal that would make it unreachable, not its parts.
        addInternalChild(staticMark);

        finalMark.addClass(FINAL_MARK_CLASS);
        finalMark.setHitTest(false);
        addInternalChild(finalMark);

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

    /** A symbol icon owns its marks; it has no public content slot. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    private static void swapPrefixed(UIElement element, String prefix, @Nullable String wanted) {
        for (String name : new ArrayList<>(element.getClasses())) {
            if (name.startsWith(prefix) && !name.equals(wanted)) element.removeClass(name);
        }
        if (wanted != null) element.addClass(wanted);
    }
}
