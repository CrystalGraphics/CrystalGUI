package com.crystalgui.syntax.treesitter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
        return liftUnambiguousPredicates(query);
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

    /** A query ready to compile, plus the text conditions that have to be applied in Java. */
    record Prepared(String text, Map<String, Pattern> captureFilters) {
    }

    /** {@code (#match? @name "regex")}, which is the only predicate form the shipped grammars use. */
    private static final Pattern MATCH_PREDICATE =
            Pattern.compile("\\(#match\\?\\s+@([\\w.]+)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)");

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
    private static Prepared liftUnambiguousPredicates(String query) {
        Map<String, Pattern> filters = new HashMap<>();
        Map<String, Integer> predicateCount = new HashMap<>();
        Map<String, String> regexOf = new HashMap<>();

        Matcher predicates = MATCH_PREDICATE.matcher(query);
        while (predicates.find()) {
            String name = predicates.group(1);
            predicateCount.merge(name, 1, Integer::sum);
            regexOf.put(name, predicates.group(2));
        }

        StringBuffer out = new StringBuffer(query.length());
        Matcher rewrite = MATCH_PREDICATE.matcher(query);
        while (rewrite.find()) {
            String name = rewrite.group(1);
            int uses = countCaptureUses(query, name);
            boolean unambiguous = uses == 2 * predicateCount.getOrDefault(name, 0);
            if (!unambiguous) continue;                       // leave it in place, untouched
            try {
                filters.put(name, Pattern.compile(unescape(regexOf.get(name))));
                rewrite.appendReplacement(out, "");            // the pattern now fires bare
            } catch (RuntimeException badRegex) {
                // A regex this JVM cannot compile is not worth failing a language over.
                filters.remove(name);
            }
        }
        rewrite.appendTail(out);
        return new Prepared(out.toString(), filters);
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
        return query.replaceAll(
                "(?s)(\\(method_invocation\\s+name:\\s*\\(identifier\\)\\s*)@function\\.method",
                "$1@function.call");
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
