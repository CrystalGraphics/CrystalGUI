package com.crystalgui.text.lang;

/**
 * What a resolved name <em>is</em> — the Language Server Protocol's {@code SymbolKind}, trimmed to the
 * kinds the engines in this repository can actually produce.
 *
 * <h3>Every kind names a capture, and that is deliberate</h3>
 *
 * <p>{@link #captureName()} maps a kind onto the highlighting vocabulary ({@code plan_syntax.md} §10.1),
 * which is what lets a semantic token provider be written without inventing a second colour vocabulary
 * beside the grammars'. That matters more than it looks: the whole value of semantic tokens is
 * <em>correcting</em> what the grammar guessed, so both have to be saying the same kind of thing. A
 * parallel vocabulary would need its own scheme tokens, its own governance test, and a mapping table
 * between the two that nobody would keep current.</p>
 *
 * <p><b>Every name returned here must be in §10.1's set</b>, or the highlight resolves to nothing and the
 * symbol simply renders as body text — the invisible-when-wrong failure this stack keeps producing.
 * {@code StyleGovernanceTest.everyCaptureInAShippedGrammarHasAColour} covers the grammars' names;
 * {@code LanguageSpiTest.everySymbolKindNamesACaptureTheSchemesColour} covers these.</p>
 */
public enum SymbolKind {

    /** A class, and the default for a named type with nothing more specific to say. */
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    /**
     * A class whose hierarchy reaches {@code Throwable}.
     *
     * <p>A <b>display refinement</b> of {@link #CLASS} rather than a language kind — the JLS has no such
     * category, and {@link #captureName()} still colours it as a type. It is here because both reference
     * IDEs draw it with its own icon and because "is this a thing I can throw" is the fact a reader most
     * wants from a list of forty similar names.</p>
     */
    EXCEPTION,
    ANNOTATION,
    /** {@code T} in {@code <T>} — a type, but one with no declaration to jump to outside its own scope. */
    TYPE_PARAMETER,

    /** A method on a type. */
    METHOD,
    /** A constructor. Separate from {@link #METHOD} because completion inserts it differently. */
    CONSTRUCTOR,
    /** A free function — JavaScript has these; Java does not. */
    FUNCTION,

    FIELD,
    /** An enum constant. Coloured as a constant, which is what both reference IDEs do. */
    ENUM_MEMBER,
    /** A {@code static final} field, or any name an engine is confident is a constant. */
    CONSTANT,
    PARAMETER,
    LOCAL_VARIABLE,
    /** A JavaScript object property, or anything reached by name off a value at runtime. */
    PROPERTY,

    PACKAGE,
    MODULE,
    /** A language keyword — completion offers these, and hover occasionally explains one. */
    KEYWORD,
    /** A {@code break}/{@code continue} target. */
    LABEL,

    /** The engine resolved something and cannot say what. Honest, and it still colours as a plain name. */
    UNKNOWN;

    /**
     * The §10.1 capture name a symbol of this kind should be coloured as.
     *
     * <p>{@code "variable"} is the catch-all, and it is the same catch-all the tokenizer's precedence rule
     * treats as losing to anything more specific — so a kind that lands here is not a mis-colour, it is
     * the absence of a better answer, rendered as such.</p>
     */
    public String captureName() {
        switch (this) {
            case CLASS:
            case RECORD:
            case ANNOTATION:
            // AN EXCEPTION IS STILL A TYPE TO THE EYE. The kind exists so the ICON can differ -- both
            // references draw a throwable with its own glyph -- and nothing about being throwable makes
            // the NAME a different sort of word on the line. Without this case it fell to the default and
            // every `catch (IOException e)` and `throws IOException` lost its type colour, which is a
            // conspicuous regression in exchange for a glyph.
            case EXCEPTION:
                return "type";
            // AN INTERFACE AND AN ENUM ARE TYPES THAT A SCHEME MAY WANT TO SAY MORE ABOUT, and the dotted
            // vocabulary is what lets them without a scheme having to care: `type.interface` publishes
            // under its general form too, so a scheme that draws every type alike -- Islands, Dark+ -- keeps
            // doing so with no entry of its own, while Eclipse Dark's light cyan interfaces and italic pink
            // enums become expressible. Exactly the argument the TYPE_PARAMETER note below makes, applied
            // one level further: folded into `type` the distinction is not muted, it is unsayable.
            case INTERFACE:
                return "type.interface";
            case ENUM:
                return "type.enum";
            // A TYPE PARAMETER IS NOT A TYPE, to a reader or to a scheme. `<E>` is a placeholder the
            // declaration introduces, and both references give it a colour of its own —
            // TYPE_PARAMETER_NAME_ATTRIBUTES, teal in Islands and in every JetBrains scheme since. Folded
            // into `type` it took the default foreground, so the one thing on the line that is not a real
            // type read exactly like the ones that are.
            case TYPE_PARAMETER:
                return "type.parameter";
            case METHOD:
            case CONSTRUCTOR:
                return "function.method";
            case FUNCTION:
                return "function";
            case FIELD:
                return "variable.member";
            case ENUM_MEMBER:
            case CONSTANT:
                return "constant";
            case PARAMETER:
                return "variable.parameter";
            case PROPERTY:
                return "property";
            case KEYWORD:
                return "keyword";
            // A PACKAGE IS NOT A VARIABLE, and the catch-all was making it one. `java` and `util` in an
            // import resolve to package bindings, fell through to `variable`, and were saved from being
            // drawn as locals only because nothing was answering for them at all -- so every import line
            // read as body text with one blue word at the end. `module` is tree-sitter's own name for
            // this (nvim-treesitter and Helix both capture a Java package path with it), which keeps the
            // grammar and the engine speaking one vocabulary here as everywhere else.
            case PACKAGE:
            case MODULE:
                return "module";
            case LOCAL_VARIABLE:
            case LABEL:
            case UNKNOWN:
            default:
                return "variable";
        }
    }

    /** Whether this is a type rather than something declared inside one — what an import needs to know. */
    public boolean isType() {
        return this == CLASS || this == INTERFACE || this == ENUM || this == RECORD
                || this == ANNOTATION || this == TYPE_PARAMETER;
    }

    /** Whether this is invocable — what completion reads to decide whether to insert an open paren. */
    public boolean isInvocable() {
        return this == METHOD || this == CONSTRUCTOR || this == FUNCTION;
    }
}
