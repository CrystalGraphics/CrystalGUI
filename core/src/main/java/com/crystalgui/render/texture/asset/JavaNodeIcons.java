package com.crystalgui.render.texture.asset;

import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;

import javax.annotation.Nullable;

import java.util.Set;

/**
 * What a declaration IS, as an icon name — {@code crystalgui:nodes/java/interface}.
 *
 * <h3>Why this exists beside a perfectly good CSS table</h3>
 *
 * <p>The same vocabulary is already written in {@code ua/editor.css} as {@code .completion-kind-*}
 * rules, and that is the right place for it: a completion row and the documentation popup's owner band
 * are elements, so they take a class and let the cascade pick the glyph. A <b>dock tab</b> cannot —
 * {@code DockGroup.applyIcon} resolves an icon NAME through {@code CgUiSvg.ofIcon} and sets it as an
 * overlay, because a tab's icon is chosen per panel rather than per state.</p>
 *
 * <p>So there are two consumers wanting one answer in two forms, and the danger is the ordinary one:
 * two tables that agree today and drift the first time a kind is added to one of them.
 * {@code JavaNodeIconsMatchTheStylesheetTest} parses the CSS and asserts this map agrees with it, so the
 * drift fails a build rather than showing the wrong glyph.</p>
 *
 * <h3>Kind and modifier are two axes</h3>
 *
 * <p>The stylesheet says this already and it is worth repeating where the Java side implements it: an
 * abstract class is not a kind, it is a class that draws differently, which is why {@code abstract} is a
 * compound selector there and a second argument here. {@code static} and {@code final} are overlays
 * drawn <em>on top</em> of the base glyph and are not this method's business.</p>
 */
public final class JavaNodeIcons {

    /** Where the JetBrains node icons live. @see FileIconTheme */
    private static final String NODES = "crystalgui:nodes/java/";

    private JavaNodeIcons() {
    }

    /** The icon for a declaration of {@code kind}, or null when nothing draws that kind. */
    @Nullable
    public static String forKind(@Nullable SymbolKind kind) {
        return forKind(kind, Set.of());
    }

    /**
     * The icon for a declaration, refined by its modifiers.
     *
     * @param modifiers the declaration's own — only {@link SymbolModifier#ABSTRACT} changes the glyph,
     *                  and only for the kinds that have an abstract drawing
     */
    @Nullable
    public static String forKind(@Nullable SymbolKind kind, Set<SymbolModifier> modifiers) {
        if (kind == null) return null;
        boolean isAbstract = modifiers != null && modifiers.contains(SymbolModifier.ABSTRACT);
        return switch (kind) {
            case CLASS -> NODES + (isAbstract ? "classAbstract" : "class");
            case INTERFACE -> NODES + "interface";
            case ENUM, ENUM_MEMBER -> NODES + "enum";
            case RECORD -> NODES + "record";
            case ANNOTATION -> NODES + "annotation";
            // A THROWABLE IS A CLASS with its own drawing -- SymbolKind.EXCEPTION says why that is a
            // display refinement rather than a language kind, and the stylesheet draws it the same way.
            case EXCEPTION -> NODES + "exception";
            // NO GLYPH OF ITS OWN. A type variable is a name standing in for a class, and JetBrains draws
            // it as one; inventing a mark for it would say there is a distinction to look for.
            case TYPE_PARAMETER -> NODES + "class";
            case METHOD, FUNCTION -> NODES + (isAbstract ? "methodAbstract" : "method");
            case CONSTRUCTOR -> NODES + "constructor";
            case FIELD, PROPERTY, CONSTANT -> NODES + "field";
            case LOCAL_VARIABLE -> NODES + "variable";
            case PARAMETER -> NODES + "parameter";
            case PACKAGE, MODULE -> NODES + "package";
            default ->
                // A KEYWORD IS NOT A NODE and JetBrains has no icon for one -- null is the honest answer,
                // and every caller here already has somewhere to fall back to.
                    null;
        };
    }

    /** Whether {@code kind} names a TYPE rather than a member — what a class file's tab shows. */
    public static boolean isType(@Nullable SymbolKind kind) {
        if (kind == null) return false;
        return switch (kind) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, EXCEPTION -> true;
            default -> false;
        };
    }
}
