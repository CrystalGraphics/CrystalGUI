package com.crystalgui.language.grammar;

import org.treesitter.TSQuery;

import javax.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a grammar's {@code highlights.scm} from this module's resources.
 *
 * <p>The query files are <b>vendored</b>, because the grammar jars do not carry them: {@code
 * tree-sitter-java-0.23.5.jar} contains the compiled parser and its natives and nothing else. Each one is
 * copied in with its grammar's own licence, and each is the grammar author's file rather than a
 * hand-written approximation — the capture names in it are what a theme is expected to style, so an
 * approximation would produce highlighting that is subtly unlike every other editor's.</p>
 */
final class Queries {

    private Queries() {
    }

    /**
     * Loads a vendored query and applies this engine's documented deviations from it.
     *
     * <p>The rule is that the author's file is vendored verbatim, and it still is — nothing edits the
     * resource. What this does is apply a small, explicit, per-grammar list of adjustments at load, so
     * the deviation is one reviewable table rather than a fork of somebody else's query.</p>
     */
    static Prepared loadForHighlighting(String resourcePath) {
        String query = load(resourcePath);
        query = splitMethodDeclarationsFromCalls(query);
        query = captureBinaryLiterals(query);
        query = captureObjectLikeDefines(query);
        query = promoteBuiltinTypes(query);
        query = captureSelfReferencesAsKeywords(query);
        query = normalizeCaptureDialect(query);
        return liftUnambiguousPredicates(query);
    }

    /**
     * Folds a grammar's capture dialect onto the one vocabulary a scheme styles.
     *
     * <h3>Why grammars disagree at all</h3>
     * <p>Capture names are a convention, not a specification, and the convention moved: nvim-treesitter
     * renamed a swathe of them in 2023, so a published {@code highlights.scm} speaks whichever dialect was
     * current when it was written. The GLSL grammar says {@code @delimiter} where Java, CSS, JavaScript and
     * HTML all say {@code @punctuation.delimiter} — the same concept under two names.</p>
     *
     * <p>Left alone that is not a missing colour but a <b>silently different</b> one: a scheme would have
     * to name both, every scheme would have to name both, and the governance test would be satisfied by
     * two entries that must never diverge. Renaming at load keeps the vocabulary closed, which is what
     * lets a scheme be written against §10.1 rather than against a list of grammars.</p>
     *
     * <p>Only <b>synonyms</b> belong here. A name this vocabulary genuinely lacks — GLSL's {@code @label},
     * for a {@code case} label — gets a token of its own instead, because folding it onto a near-miss
     * would be inventing a meaning rather than translating one.</p>
     */
    private static String normalizeCaptureDialect(String query) {
        return query.replaceAll("@delimiter\\b", "@punctuation.delimiter");
    }

    /**
     * Adds {@code binary_integer_literal} to the numbers, which the vendored Java query omits.
     *
     * <p>Its literal list names {@code hex_integer_literal}, {@code decimal_integer_literal},
     * {@code octal_integer_literal} and both floating-point forms — and not the binary one, which Java has
     * had since 7. So {@code 0b1010_1010} is the one numeric form in the language that renders as plain
     * text while every other literal on the same screen is coloured.</p>
     *
     * <p><b>Appended rather than spliced into the existing list.</b> A new pattern at the end takes the
     * highest pattern index, which is exactly the precedence a refinement wants — the same rule that lets
     * {@code @constant} outrank the blanket {@code @variable}. Editing the list in place would work too and
     * would mean rewriting somebody else's s-expression rather than adding one line after it.</p>
     */
    private static String captureBinaryLiterals(String query) {
        if (!query.contains("(decimal_integer_literal)") || query.contains("(binary_integer_literal)")) {
            return query;                                  // not this grammar, or already covered
        }
        return query + "\n\n; Added by CrystalGUI: the vendored query omits Java 7's binary literal.\n"
                + "(binary_integer_literal) @number\n";
    }

    /**
     * Names the object-like {@code #define}, which the GLSL query captures only in its function-like form.
     *
     * <p>It has {@code (preproc_function_def name: (identifier) @function.special)} for
     * {@code #define SATURATE(x)} and nothing for {@code #define MAX_STEPS 64} — so the name of a plain
     * macro falls through to the blanket {@code @variable} and reads as an ordinary local, next to a
     * function-like macro two lines above that is coloured.</p>
     *
     * <p>Captured as {@code @constant}, which is what it is: a compile-time value with a fixed name. That
     * also puts it in the same colour as the SCREAMING_CASE constants around it, which is how every shader
     * editor shows them.</p>
     *
     * <p><b>What this cannot reach:</b> the replacement text. {@code 64} in {@code #define MAX_STEPS 64}
     * lives inside a {@code preproc_arg}, which the grammar treats as opaque — it is not parsed as an
     * expression, so there is no number node to capture. Colouring it would need an injection of the
     * language into its own preprocessor, which is a real technique and a disproportionate one here.</p>
     */
    private static String captureObjectLikeDefines(String query) {
        if (!query.contains("(preproc_function_def") || query.contains("(preproc_def")) return query;

        // A SCREAMING_CASE identifier is a constant, which the C-family query never says and Java's does.
        //
        // Without it `PI`, `EPSILON`, `MAX_LIGHTS` and `DEBUG_NORMALS` fall through to the blanket
        // @variable and render as ordinary locals, while the identical convention two files away is
        // purple and italic. That is the same naming rule getting two answers depending on which grammar
        // happened to write a test for it -- a decision the SCHEME is supposed to own.
        //
        // This also subsumes the object-like #define, which is why there is no separate rule for it:
        // MAX_STEPS in `#define MAX_STEPS 64` is an identifier and is SCREAMING, so one pattern covers
        // both and leaves @constant with exactly one guarded use -- which is what lets the predicate be
        // lifted at all (see liftUnambiguousPredicates: a name is only lifted when EVERY use is guarded).
        //
        // `null` moves to @constant.builtin at the same time, matching what the Java query already does
        // with true/false/null. It has to move: leaving it as a bare @constant would be a second,
        // unguarded use of the name and the lift would refuse the whole thing -- and, being lowercase, it
        // would then be filtered out by the very regex being added.
        String out = query.replaceAll("(\\(null\\)\\s*)@constant\\b", "$1@constant.builtin");
        return out + "\n\n; Added by CrystalGUI: the C-family query has no SCREAMING_CASE rule, so a\n"
                + "; constant renders as an ordinary local -- and an object-like #define with it.\n"
                + "((identifier) @constant\n"
                + " (#match? @constant \"^_*[A-Z][A-Z0-9_]+$\"))\n"
                + "\n; The parts of a directive that ARE parsed. See preprocessorNote() for the parts\n"
                + "; that are not, and why they cannot be reached from here.\n"
                + "(preproc_params (identifier) @variable.parameter)\n"
                + "(preproc_extension extension: (identifier) @attribute)\n"
                + "(preproc_extension behavior: (extension_behavior) @keyword)\n";
    }

    /**
     * Why a preprocessor line is only partly coloured, recorded so it is not re-investigated.
     *
     * <p>The grammar parses a directive's <em>shape</em> and leaves its payload as one opaque token:</p>
     * <pre>
     *   #version 330 core            (preproc_call  directive: … argument: (preproc_arg))
     *   #define MAX_STEPS 64         (preproc_def   name: (identifier) value: (preproc_arg))
     *   #define SATURATE(x) clamp(…) (preproc_function_def … parameters: (preproc_params …) value: (preproc_arg))
     *   #extension GL_ARB_x : enable (preproc_extension extension: (identifier) behavior: (extension_behavior))
     * </pre>
     *
     * <p>So the macro's parameters, the extension's name and its behaviour are real nodes and are now
     * captured. <b>Everything inside a {@code preproc_arg} is not.</b> {@code 330 core}, {@code 64} and
     * {@code clamp(x, 0.0, 1.0)} are each a single undifferentiated token — there is no number node to
     * colour, and no query can invent one.</p>
     *
     * <p>The only way to reach them is to inject the language into its own preprocessor, and it does not
     * work here: {@code 330 core} is not valid GLSL in any context, and {@code clamp(x, 0.0, 1.0)} is an
     * expression where a translation unit is expected. Both would parse to {@code ERROR} nodes, and
     * highlighting driven off an error tree is worse than none — it is confidently wrong rather than
     * visibly absent. A macro body would need an expression-level entry point the grammar does not
     * expose.</p>
     */
    private static void preprocessorNote() {
        // Documentation only.
    }

    /**
     * Separates a language's <em>own</em> types from the ones a user declared.
     *
     * <p>The C-family queries capture both under {@code @type}:</p>
     * <pre>
     *   (type_identifier)       @type      -- a user's struct
     *   (primitive_type)        @type      -- void, float, int
     *   (sized_type_specifier)  @type      -- unsigned int
     * </pre>
     *
     * <p>Java's query makes the distinction and this one does not, so the same scheme that colours
     * {@code int} as a reserved word leaves {@code void} and {@code float} at the default foreground —
     * the two languages disagreeing about a decision the <em>scheme</em> is supposed to own. Every
     * reference editor treats a builtin type as a keyword and a declared type as a name; the vocabulary
     * has {@code type.builtin} for exactly that, and only this grammar family fails to use it.</p>
     */
    private static String promoteBuiltinTypes(String query) {
        if (!query.contains("(primitive_type)")) return query;      // not a C-family grammar
        return query
                .replaceAll("(\\(primitive_type\\)\\s*)@type\\b", "$1@type.builtin")
                .replaceAll("(\\(sized_type_specifier\\)\\s*)@type\\b", "$1@type.builtin");
    }

    /**
     * <b>{@code this} and {@code super} are keywords, and the vendored query calls them values.</b>
     *
     * <p>nvim-treesitter writes {@code (super) @function.builtin} and {@code (this) @variable.builtin},
     * which is defensible where they behave like values — {@code super(...)} really is a call. It is wrong
     * everywhere else, and the place it shows is the one where {@code super} cannot possibly be a call:
     * a wildcard bound. {@code List<? super T>} drew {@code super} in the call colour, which under a
     * scheme whose calls are lime is unmissable and reads as the highlighter mis-parsing the generic.</p>
     *
     * <p>Both are reserved words. The JLS has no context in which either is anything else, and both
     * references draw them as keywords — which is why <em>every</em> shipped scheme already maps
     * {@code variable.builtin} and {@code function.builtin} onto its keyword colour for exactly these
     * two. Those names still earn their keep elsewhere ({@code console} in JavaScript,
     * {@code gl_Position} in GLSL); Java simply has no use for them, and saying so once here beats
     * five schemes each restating a colour they already agree on — and getting the WEIGHT wrong, which
     * a colour alias cannot carry.</p>
     */
    private static String captureSelfReferencesAsKeywords(String query) {
        return query
                .replaceAll("(\\(super\\)\\s*)@function\\.builtin\\b", "$1@keyword")
                .replaceAll("(\\(this\\)\\s*)@variable\\.builtin\\b", "$1@keyword");
    }

    /** A query ready to compile, plus the text conditions that have to be applied in Java. */
    /**
     * One {@code #match?} taken out of the query text, and where it was.
     *
     * <p>{@code at} is a <b>byte</b> offset into {@link Prepared#text}, because that is the coordinate
     * {@code TSQuery.getStartByteForPattern} answers in and comparing a char offset against it would
     * drift the moment a query contained one non-ASCII character. It is the position the predicate
     * occupied, which is inside its own pattern by construction — so the pattern it belongs to is
     * whichever one's byte range contains it.</p>
     */
    record LiftedFilter(int at, String capture, Pattern regex) {
    }

    record Prepared(String text, Map<String, Pattern> captureFilters, List<LiftedFilter> lifted) {
    }

    /**
     * {@code (#match? @name "regex")} and {@code (#lua-match? @name "pattern")} — the two predicate forms
     * the shipped grammars use, and they are <b>not the same language</b>. See {@link #toJavaRegex}.
     */
    private static final Pattern MATCH_PREDICATE =
            Pattern.compile("\\(#(lua-)?match\\?\\s+@([\\w.]+)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)");

    /**
     * Converts a Lua pattern to a Java regex, or returns {@code null} when it cannot be done safely.
     *
     * <p><b>A Lua pattern is not a regex</b>, and the overlap is wide enough to be dangerous: {@code ^gl_}
     * means the same thing in both, while {@code %d} is Lua's digit class and Java's escaped literal
     * {@code d}. Translating the classes that have exact Java equivalents covers every use in the shipped
     * grammars; anything else — {@code %b} balanced match, {@code %f} frontier, a captured position —
     * has no equivalent and returns null so the predicate is <em>left in place</em> and its pattern goes
     * on not firing.</p>
     *
     * <p>That failure mode is the conservative one, and it matters here: a mistranslated pattern would
     * silently colour the wrong tokens, while an untranslated one colours nothing, which is the status
     * quo and visibly incomplete rather than quietly wrong.</p>
     */
    private static String toJavaRegex(String luaPattern) {
        StringBuilder out = new StringBuilder(luaPattern.length());
        for (int i = 0; i < luaPattern.length(); i++) {
            char c = luaPattern.charAt(i);
            if (c != '%') {
                out.append(c);
                continue;
            }
            if (++i >= luaPattern.length()) return null;
            char cls = luaPattern.charAt(i);
            switch (cls) {
                case 'd' -> out.append("\\d");
                case 'a' -> out.append("[a-zA-Z]");
                case 'w' -> out.append("[a-zA-Z0-9]");
                case 's' -> out.append("\\s");
                case 'u' -> out.append("[A-Z]");
                case 'l' -> out.append("[a-z]");
                case 'p' -> out.append("\\p{Punct}");
                case 'x' -> out.append("[0-9a-fA-F]");
                default -> {
                    // A literal escape of a non-alphanumeric is the same idea in both languages; a letter
                    // we do not know is a class we cannot translate, and guessing is the one outcome worse
                    // than not translating.
                    if (Character.isLetterOrDigit(cls)) return null;
                    out.append(Pattern.quote(String.valueOf(cls)));
                }
            }
        }
        return out.toString();
    }

    /**
     * Removes the predicates this engine can re-apply itself, and reports them — leaving the rest alone.
     *
     * <h3>The problem</h3>
     * <p><b>A pattern carrying a predicate never yields a match through this binding.</b> Measured against
     * the Java grammar: of 25 patterns, the ones that fire are
     * {@code [0,1,2,7,9,10,15,16,18,19,20,24]} — and the five absent are exactly those with {@code #match?}
     * on them. So the predicate is not being evaluated <em>wrongly</em>; the pattern is contributing
     * nothing at all, and there is no match downstream to filter.</p>
     *
     * <p>That silently deletes the grammar's stand-ins for resolution. A grammar cannot tell
     * {@code MAX_RETRIES} from {@code retries}, so it tests for SCREAMING_CASE — which is how constants,
     * enum constants and static fields get their colour in every editor built on tree-sitter. With the
     * pattern inert they render as plain identifiers, and against a reference scheme that makes them
     * purple and italic that is one of the most visible differences on screen.</p>
     *
     * <h3>Why only SOME predicates are lifted</h3>
     * <p>Stripping a predicate makes its pattern fire <em>unconditionally</em>, so the condition has to be
     * re-applied here — and this can only match on the capture NAME, not on the pattern it came from. That
     * is exact when every use of the name is predicated, and wrong when it is not:</p>
     * <ul>
     *   <li>{@code @constant} appears once, guarded — filtering every {@code @constant} by that regex is
     *       precisely the original meaning.</li>
     *   <li>{@code @type} appears in a dozen patterns, most unguarded ({@code (type_identifier) @type} and
     *       friends). Filtering all of them by the {@code ^[A-Z]} test that belongs to four of them would
     *       delete type colouring the grammar states outright.</li>
     * </ul>
     *
     * <p>So a name is lifted only when <b>every</b> occurrence of it is predicated — checked as
     * {@code uses == 2 × predicates}, since a guarded pattern names its capture twice, once to capture and
     * once inside the predicate. Anything else keeps its predicate and therefore keeps not firing, which
     * is the status quo rather than a regression. Under-reaching here costs a colour; over-reaching
     * deletes one that works.</p>
     */
    /**
     * Which lifted filter applies to which <b>pattern</b>, resolved once the query has been compiled.
     *
     * <p>This is what makes the lift exact rather than approximate. The filter used to be re-applied by
     * CAPTURE NAME, which is only sound when every use of that name is guarded by the same test — so a
     * capture used both guarded and bare could not be lifted at all, and its guarded patterns stayed
     * inert. {@code @type} is exactly that case: four patterns guard it with {@code ^[A-Z]} and a dozen
     * use it bare, so {@code System.out.println} reported two plain variables and the grammar's stand-in
     * for resolution was silently absent. The editor hid it — the Java engine's semantic tokens replace
     * the grammar's guess — and a documentation popup, which has no engine, showed it plainly.</p>
     *
     * <p>A pattern's byte range is a fact the compiled query already knows, so keying on it needs no
     * parsing of our own: the predicate was written inside its pattern, so the pattern whose range
     * contains the position it was lifted from is the pattern it guarded.</p>
     */
    static Map<Integer, Map<String, Pattern>> filtersByPattern(TSQuery query, Prepared prepared) {
        if (prepared.lifted().isEmpty()) return Map.of();
        Map<Integer, Map<String, Pattern>> byPattern = new HashMap<>();
        int patterns = query.getPatternCount();
        for (LiftedFilter filter : prepared.lifted()) {
            for (int pattern = 0; pattern < patterns; pattern++) {
                if (filter.at() < query.getStartByteForPattern(pattern)) continue;
                if (filter.at() >= query.getEndByteForPattern(pattern)) continue;
                byPattern.computeIfAbsent(pattern, any -> new HashMap<>())
                        .put(filter.capture(), filter.regex());
                break;
            }
        }
        return byPattern;
    }

    private static Prepared liftUnambiguousPredicates(String query) {
        Map<String, Pattern> filters = new HashMap<>();
        Map<String, Integer> predicateCount = new HashMap<>();
        Map<String, String> regexOf = new HashMap<>();

        Matcher predicates = MATCH_PREDICATE.matcher(query);
        while (predicates.find()) {
            String name = predicates.group(2);
            predicateCount.merge(name, 1, Integer::sum);
            boolean lua = predicates.group(1) != null;
            String pattern = unescape(predicates.group(3));
            regexOf.put(name, lua ? toJavaRegex(pattern) : pattern);
        }

        // EVERY translatable predicate is lifted now, not only the unambiguous ones. The restriction
        // existed because re-application could only match on a capture NAME; `filtersByPattern` resolves
        // each filter to the pattern it came from, so a capture used both guarded and bare is no longer a
        // reason to leave four patterns inert. What is still refused is a predicate this JVM cannot
        // express -- see below -- because stripping one of those would make its pattern fire
        // UNCONDITIONALLY, which is a wrong colour rather than a missing one.
        List<LiftedFilter> lifted = new ArrayList<>();
        StringBuffer out = new StringBuffer(query.length());
        Matcher rewrite = MATCH_PREDICATE.matcher(query);
        while (rewrite.find()) {
            String name = rewrite.group(2);
            boolean lua = rewrite.group(1) != null;
            String source = lua ? toJavaRegex(unescape(rewrite.group(3))) : unescape(rewrite.group(3));
            // A Lua pattern with no safe Java equivalent translates to null; leaving the predicate in
            // place keeps its pattern inert, which is what it already was.
            if (source == null) continue;
            Pattern compiled;
            try {
                compiled = Pattern.compile(source);
            } catch (RuntimeException badRegex) {
                // A regex this JVM cannot compile is not worth failing a language over.
                continue;
            }
            rewrite.appendReplacement(out, "");                // the pattern now fires bare
            // RECORDED AT THE POSITION THE PREDICATE OCCUPIED IN THE STRIPPED TEXT, which is inside the
            // pattern it guarded -- the coordinate `filtersByPattern` resolves against.
            lifted.add(new LiftedFilter(out.length(), name, compiled));
        }
        rewrite.appendTail(out);

        String text = out.toString();
        // CHAR OFFSETS BECOME BYTE OFFSETS, once, now that the text is final. A query is ASCII in
        // practice and `getStartByteForPattern` is not, so converting is the difference between exact
        // and exact-until-somebody-writes-a-comment-with-an-accent in it.
        List<LiftedFilter> byBytes = new ArrayList<>(lifted.size());
        for (LiftedFilter filter : lifted) {
            int bytes = text.substring(0, filter.at()).getBytes(StandardCharsets.UTF_8).length;
            byBytes.add(new LiftedFilter(bytes, filter.capture(), filter.regex()));
        }
        return new Prepared(text, filters, byBytes);
    }

    /**
     * How many times {@code @name} appears at all — captures and predicate references together.
     *
     * <p>The lookahead rejects a DOT as well as a word character, and that is the whole of the method's
     * difficulty. A plain {@code \b} counts {@code @constant.builtin} as a use of {@code @constant},
     * because a word boundary sits happily between the {@code t} and the {@code .} — which made the one
     * capture in the Java grammar that genuinely is unambiguous look ambiguous, and silently refused the
     * lift it was written for.</p>
     */
    private static int countCaptureUses(String query, String name) {
        Matcher uses = Pattern.compile("@" + Pattern.quote(name) + "(?![\\w.])").matcher(query);
        int count = 0;
        while (uses.find()) count++;
        return count;
    }

    /** tree-sitter query strings escape backslashes for the s-expression, not for the regex engine. */
    private static String unescape(String raw) {
        return raw.replace("\\\\", "\\");
    }

    /**
     * Gives a method <em>call</em> a capture name distinct from a method <em>declaration</em>.
     *
     * <h3>Why this is worth deviating for</h3>
     * <p>Every reference scheme colours the two differently, and IntelliJ's exported Islands scheme is
     * explicit about it: {@code DEFAULT_FUNCTION_DECLARATION} is a blue, while
     * {@code DEFAULT_FUNCTION_CALL} carries {@code baseAttributes="DEFAULT_IDENTIFIER"} and no colour of
     * its own. The vendored Java query captures both under {@code @function.method}:</p>
     * <pre>
     *   (method_declaration name: (identifier) @function.method)
     *   (method_invocation  name: (identifier) @function.method)
     * </pre>
     *
     * <p>With one name, a scheme has to pick a side and both are visibly wrong. Colouring both blue lights
     * up every {@code .get()}, {@code .add()} and {@code .equals()} on screen — calls outnumber
     * declarations several times over — and the file stops looking like IntelliJ. Colouring neither leaves
     * the document with nothing but keywords, strings and numbers tinted, which looks like the highlighter
     * has failed rather than like a restrained palette. <b>Both of those shipped before this existed.</b></p>
     *
     * <p>So the invocation pattern is re-captured as {@code @function.call}, which the sheet styles as the
     * default foreground while {@code @function.method} keeps the declaration blue. Scoped to the
     * invocation node type by name, so a grammar that does not have one is returned unchanged and this
     * costs nothing.</p>
     */
    private static String splitMethodDeclarationsFromCalls(String query) {
        // Matches the capture on the `name:` field of a method_invocation, across the line break the
        // vendored files put there. Deliberately narrow: anything it does not recognise is left alone,
        // because a query that fails to compile is a language with no highlighting at all.
        String out = query.replaceAll(
                "(?s)(\\(method_invocation\\s+name:\\s*\\(identifier\\)\\s*)@function\\.method",
                "$1@function.call");

        // GLSL, whose grammar names the same two things differently and captures BOTH as bare @function:
        //   (call_expression     function:   (identifier) @function)   -- a call
        //   (function_declarator declarator: (identifier) @function)   -- a declaration
        // Without this the C-family languages get one colour for both, which is the exact complaint the
        // Java split exists to answer; the node names differ, so the rule has to be stated twice rather
        // than generalised into something that would match by accident.
        out = out.replaceAll(
                "(?s)(\\(function_declarator\\s+declarator:\\s*\\(identifier\\)\\s*)@function\\b",
                "$1@function.method");
        out = out.replaceAll(
                "(?s)(\\(call_expression\\s+function:\\s*\\(identifier\\)\\s*)@function\\b",
                "$1@function.call");
        out = out.replaceAll(
                "(?s)(field:\\s*\\(field_identifier\\)\\s*)@function\\b",
                "$1@function.call");

        // JAVASCRIPT'S METHOD CALL, which the vendored query captures as a DECLARATION:
        //   (call_expression function: (member_expression property: (property_identifier) @function.method))
        // so `console.log(...)` took the declaration blue while Java's `println(...)` took the call
        // colour, from the same scheme, three lines apart on screen. The same complaint the Java split
        // above answers, in the language that had not been checked for it.
        //
        // ANCHORED ON `call_expression function:`, because the identical inner shape spells a real
        // declaration two patterns earlier -- `(assignment_expression left: (member_expression property:
        // (property_identifier) @function.method) right: [(function_expression) (arrow_function)])` is
        // `Foo.prototype.bar = function () {}`, which IS a declaration and must keep the blue. Matching
        // the member_expression alone would have recoloured both.
        out = out.replaceAll(
                "(?s)(\\(call_expression\\s+function:\\s*\\(member_expression\\s+property:\\s*"
                        + "\\(property_identifier\\)\\s*)@function\\.method",
                "$1@function.call");
        return out;
    }

    /**
     * A query family for a grammar, or {@code null} when this language ships none.
     *
     * <p>Null is the ordinary answer and is <b>not</b> a degradation: {@code folds.scm} and
     * {@code indents.scm} are editor conventions rather than parts of tree-sitter, and every consumer of
     * them already has a working fallback — indentation-based folding, and the "line ends in an opening
     * brace" indent rule. A language with no file keeps exactly the behaviour it had, which is what makes
     * these three additive rather than a migration.</p>
     *
     * <p>Deviations are applied the same way {@link #loadForHighlighting} applies them — at load, as one
     * reviewable table, never by editing the vendored resource. There is only one, and it is a <b>gap</b>
     * rather than a dialect disagreement: unlike a capture rename, which is two names for one idea, a
     * missing pattern is a colour nobody gets.</p>
     */
    @Nullable
    static String loadFamily(String language, String family) {
        String query = loadIfPresent("assets/crystalgui/syntax/" + language + "/" + family + ".scm");
        return query == null ? null : captureJavaScriptParameters(query);
    }

    /**
     * Adds the parameter definitions the ECMAScript {@code locals.scm} omits entirely.
     *
     * <p>Upstream captures {@code var} declarations, imports, functions and methods — and <b>no
     * parameters</b>. So a JavaScript function's arguments resolve to nothing, fall through to the
     * highlight query's blanket {@code @variable}, and render identically to the locals beside them,
     * which is precisely the distinction this whole family exists to draw. Java's file captures them and
     * the C family's captures them; ECMAScript's is the outlier.</p>
     *
     * <p><b>It matters most where there is no engine.</b> A {@code .js} file in an application that
     * called {@code JsLanguage.register()} gets {@code variable.parameter} from Rhino's own scopes, and a
     * semantic token outranks a grammar one — so this changes nothing there. It is the other case that is
     * the point: a build with no engines, or a dedicated server, where the grammar tier is all there is.
     * That is the tier §24.5 was justified by.</p>
     *
     * <p>Appended rather than spliced, for the reason the highlight deviations are: a new pattern takes
     * the highest index and nothing above it is rewritten.</p>
     */
    private static String captureJavaScriptParameters(String query) {
        if (!query.contains("(arrow_function)") || query.contains("@local.definition.parameter")) {
            return query;
        }
        return query
                + System.lineSeparator()
                + "; Added by CrystalGUI: upstream's ECMAScript locals query defines no parameters at"
                + System.lineSeparator()
                + "; all, so every argument renders as an ordinary local."
                + System.lineSeparator()
                + "(formal_parameters (identifier) @local.definition.parameter)"
                + System.lineSeparator()
                + "(formal_parameters (rest_pattern (identifier) @local.definition.parameter))"
                + System.lineSeparator()
                + "(arrow_function parameter: (identifier) @local.definition.parameter)"
                + System.lineSeparator();
    }

    @Nullable
    private static String loadIfPresent(String resourcePath) {
        try (InputStream in = Queries.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) return null;
        } catch (IOException unreadable) {
            return null;
        }
        return load(resourcePath);
    }

    static String load(String resourcePath) {
        try (InputStream in = Queries.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("highlight query not on the classpath: " + resourcePath);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + resourcePath, e);
        }
    }
}
