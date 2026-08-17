package com.crystalgui.language.js;

import com.crystalgui.text.lang.Signature;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.text.lang.TypeRef;

import javax.annotation.Nullable;

import java.util.List;

/**
 * A JavaScript declaration as the engine would write it — what the Quick Documentation popup draws.
 *
 * <h3>The same builder and the same rules the Java one uses</h3>
 *
 * <p>{@link Signature.Builder}, {@link #MAX_SIGNATURE_LINE}, one parameter per line when it does not fit,
 * a hanging indent under the opening bracket. Not because a shared look is tidy but because the popup is
 * <em>one widget</em>: it renders whatever tokens it is given with the scheme that colours the editor, so
 * two engines that named their tokens differently would draw the same construct in different colours two
 * pixels apart.</p>
 *
 * <h3>What JavaScript has that Java's compiled path does not, and vice versa</h3>
 *
 * <p><b>Parameter names.</b> A Java member read from a class file reports {@code arg0}, so
 * {@code JavaSignatures} shows types alone — {@code getProperty(String, String)} — which is what Eclipse
 * does and is better than confidently printing a placeholder. JavaScript always has the real names,
 * because the declaration is right there in the file, so it shows {@code add(name, count)} and adds the
 * types only where JSDoc supplied them.</p>
 *
 * <p><b>Nothing else.</b> No modifiers to render (there are none), no throws clause, no type parameters,
 * no annotations. A JavaScript declaration is a keyword, a name, and — for a function — a parameter list.
 * That this class is a tenth the size of the Java one is the language being smaller, not the work being
 * unfinished.</p>
 */
final class JsSignatures {

    /**
     * How long a declaration may be before it is broken across lines.
     *
     * <p>The same figure the Java side uses, and for the same reason: a count of characters, because the
     * engine cannot see the box and does not need to — what it knows is where a break is legal and
     * meaningful, which is the half nothing downstream can recover.</p>
     */
    private static final int MAX_SIGNATURE_LINE = 72;

    private JsSignatures() {
    }

    /**
     * The declaration for {@code symbol}, or null when there is nothing worth drawing.
     *
     * <p>Null rather than an empty signature for a symbol whose whole content would be its own name: the
     * popup already shows the name, and a line repeating it is a box that looks like it failed to load.</p>
     */
    @Nullable
    static Signature of(SymbolInfo symbol, List<String> parameterNames) {
        return of(symbol, parameterNames, null);
    }

    /**
     * @param keyword what actually introduced this declaration, when the caller knows — {@code let}
     *                rather than the {@code var} a {@code LOCAL_VARIABLE} would otherwise print.
     *
     *                <p>Handed in rather than carried on {@code SymbolInfo}, for the reason the parameter
     *                names are: the seam is language-neutral and {@code let} is a fact about JavaScript's
     *                syntax, not about what a symbol is. Whoever holds the declaration node knows it.</p>
     */
    @Nullable
    static Signature of(SymbolInfo symbol, List<String> parameterNames, @Nullable String keyword) {
        if (symbol == null || symbol.name().isEmpty()) return null;
        List<String> names = parameterNames == null ? List.of() : parameterNames;
        Signature flat = render(symbol, names, keyword, false);
        if (flat == null) return null;
        // TRIED FLAT FIRST and kept if it fits, exactly as the Java side does: breaking unconditionally
        // would put `const RATE` on two lines, which is worse than the problem being solved.
        return longestLine(flat.text()) <= MAX_SIGNATURE_LINE ? flat
                : render(symbol, names, keyword, true);
    }

    @Nullable
    private static Signature render(SymbolInfo symbol, List<String> names, @Nullable String keyword,
                                    boolean broken) {
        switch (symbol.kind()) {
            case FUNCTION:
                return function(symbol, names, broken);
            case METHOD:
            case CONSTRUCTOR:
                // A JAVA MEMBER REACHED FROM JAVASCRIPT, whose signature the Java engine did not attach:
                // `membersOf` deliberately leaves it off, since a completion list of hundreds would pay for
                // a field it never draws. Assembled from what the member DID carry, which is the same
                // fallback JavaSignatures uses for a classpath member with no source beside it.
                return javaMember(symbol, broken);
            case CONSTANT:
            case LOCAL_VARIABLE:
            case PARAMETER:
            case PROPERTY:
            case FIELD:
                return variable(symbol, keyword);
            case CLASS:
                return javaClass(symbol);
            default:
                return null;
        }
    }

    // ── A function ──────────────────────────────────────────────────────────────────────────────

    /**
     * {@code function add(name, count)} — and the JSDoc types where the author gave them.
     *
     * <p>A parameter with a documented type reads {@code name: string}, which is the shape every JavaScript
     * tool that understands JSDoc uses and the shape TypeScript made familiar. An undocumented one is the
     * bare name rather than {@code name: ?}, because {@code ?} is noise on every parameter of every
     * undocumented function — which is most of them — and says nothing the absence does not.</p>
     */
    private static Signature function(SymbolInfo symbol, List<String> names, boolean broken) {
        Signature.Builder out = new Signature.Builder();
        out.word("function", "keyword");
        out.append(symbol.name(), "function");
        out.append("(", "punctuation.bracket");

        // THE NAMES COME FROM THE CALLER, not from the SymbolInfo. Core's seam carries parameter TYPES
        // and deliberately not names -- JDT reports `arg0` for a classpath member, so a name field would
        // be populated with a placeholder by the engine that has most members. JavaScript always has the
        // real names because the declaration is in the file, and whoever holds the AST hands them over.
        List<TypeRef> types = symbol.parameters();
        int count = Math.max(types.size(), names.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append(",", "punctuation.delimiter");
                if (broken) out.newline().indent().indent();
                else out.raw(" ");
            } else if (broken && count > 1) {
                out.newline().indent().indent();
            }
            String name = i < names.size() ? names.get(i) : "";
            out.append(name.isEmpty() ? "arg" + i : name, "variable.parameter");
            TypeRef declared = i < types.size() ? types.get(i) : null;
            if (isKnown(declared)) {
                out.append(":", "punctuation.delimiter").raw(" ")
                        .append(declared.displayName(), "type");
            }
        }
        if (broken && count > 1) out.newline();
        out.append(")", "punctuation.bracket");
        // THE RETURN TYPE ONLY WHEN SOMETHING KNOWS IT -- JSDoc, or the live scope. A JavaScript function
        // declares none, so inventing `: any` would be a claim the engine cannot support.
        if (isKnown(symbol.type())) {
            out.append(":", "punctuation.delimiter").raw(" ")
                    .append(symbol.type().displayName(), "type");
        }
        return out.build();
    }

    // ── A variable ──────────────────────────────────────────────────────────────────────────────

    /**
     * {@code const RATE: number} — the keyword the author actually wrote, and the type if one is known.
     *
     * <p>The keyword comes from the <em>kind</em>, which came from the declaration node, so a {@code const}
     * reads {@code const} and never {@code var}. That distinction is the one thing about a JavaScript
     * variable a reader cannot get from the name and the reason the engine tracks it at all.</p>
     */
    private static Signature variable(SymbolInfo symbol, @Nullable String declared) {
        Signature.Builder out = new Signature.Builder();
        String keyword = declared != null ? declared : keywordFor(symbol);
        if (keyword != null) out.word(keyword, "keyword");
        out.append(symbol.name(), captureFor(symbol));
        if (isKnown(symbol.type())) {
            out.append(":", "punctuation.delimiter").raw(" ")
                    .append(symbol.type().displayName(), "type");
        }
        return out.build();
    }

    /**
     * Which keyword introduced this, or null for something no keyword declares.
     *
     * <p>A parameter and a property have no keyword — writing {@code var} in front of either would be an
     * invention, and one a reader could reasonably act on.</p>
     */
    @Nullable
    private static String keywordFor(SymbolInfo symbol) {
        switch (symbol.kind()) {
            case CONSTANT:
                return "const";
            case LOCAL_VARIABLE:
                // `var`, unless the caller said otherwise. RhinoScopes records which keyword was written
                // and passes it in; this is the answer for a symbol that reached here from somewhere with
                // no declaration node to read -- the live scope, or a host binding -- where `var` is what
                // the overwhelming majority of scripts on a Rhino band are written with.
                return "var";
            default:
                return null;
        }
    }

    private static String captureFor(SymbolInfo symbol) {
        switch (symbol.kind()) {
            case CONSTANT: return "constant";
            case PARAMETER: return "variable.parameter";
            case PROPERTY:
            case FIELD: return "property";
            default: return "variable";
        }
    }

    // ── A Java member, and a Java class ─────────────────────────────────────────────────────────

    /**
     * {@code boolean add(Object)} — assembled from what the Java engine reported about the member.
     *
     * <p>Types without names, which is what {@code JavaSignatures} shows for the same reason: a member read
     * from a class file has no parameter names to report, and {@code arg0} is worse than nothing.</p>
     */
    private static Signature javaMember(SymbolInfo symbol, boolean broken) {
        Signature.Builder out = new Signature.Builder();
        if (symbol.is(SymbolModifier.STATIC)) out.word("static", "keyword");
        if (isKnown(symbol.type())) out.word(symbol.type().displayName(), "type");
        out.append(symbol.name(), "function.method");
        out.append("(", "punctuation.bracket");
        List<TypeRef> parameters = symbol.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                out.append(",", "punctuation.delimiter");
                if (broken) out.newline().indent().indent();
                else out.raw(" ");
            } else if (broken && parameters.size() > 1) {
                out.newline().indent().indent();
            }
            out.append(parameters.get(i).displayName(), "type");
        }
        if (broken && parameters.size() > 1) out.newline();
        out.append(")", "punctuation.bracket");
        return out.build();
    }

    /** {@code class java.util.ArrayList} — for a hover over a package chain or a {@code Java.type} result. */
    private static Signature javaClass(SymbolInfo symbol) {
        Signature.Builder out = new Signature.Builder();
        out.word("class", "keyword");
        String qualified = symbol.container() == null || symbol.container().isEmpty()
                ? symbol.name() : symbol.container() + "." + symbol.name();
        out.append(qualified, "type");
        return out.build();
    }

    // ── Small shared pieces ─────────────────────────────────────────────────────────────────────

    /**
     * Whether a type is worth printing.
     *
     * <p>{@code ?} is what an undocumented parameter's type is, and printing it adds a column of question
     * marks to every signature in an undocumented file — which is most JavaScript. The absence says the
     * same thing more quietly.</p>
     */
    private static boolean isKnown(@Nullable TypeRef type) {
        if (type == null) return false;
        String name = type.displayName();
        return !name.isEmpty() && !"?".equals(name);
    }

    private static int longestLine(String text) {
        int longest = 0;
        int lineStart = 0;
        for (int at = 0; at <= text.length(); at++) {
            if (at == text.length() || text.charAt(at) == '\n') {
                longest = Math.max(longest, at - lineStart);
                lineStart = at + 1;
            }
        }
        return longest;
    }
}
