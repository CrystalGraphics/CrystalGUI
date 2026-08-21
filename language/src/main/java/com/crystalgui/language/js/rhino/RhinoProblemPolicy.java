package com.crystalgui.language.js.rhino;

import com.crystalgui.language.js.rhino.resolve.LineIndex;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.TextPoint;

import org.mozilla.javascript.ast.ParseProblem;

import java.util.ArrayList;
import java.util.List;

/**
 * What this engine chooses to report, and how it should read — the two halves of the same decision.
 *
 * <h3>Rhino's raw output is not showable, and this is why</h3>
 *
 * <p>Measured, not assumed. {@code class A { m() { return 1; } }} produces <b>five</b> errors:</p>
 *
 * <pre>
 *   0:0   identifier is a reserved word: class
 *   0:6   missing ; before statement
 *   0:8   missing ; before statement
 *   0:14  missing ; before statement
 *   0:16  invalid return
 * </pre>
 *
 * <p>Only the first says anything. The rest are the parser failing to re-synchronise after a token it
 * could not use — each one a fact about its own recovery rather than about the code. Shown raw, one
 * unsupported keyword paints five squiggles across a correct line and fills the Problems panel with
 * four rows that navigate to arbitrary punctuation. {@code import} gives four, {@code export} two,
 * {@code await} three.</p>
 *
 * <h3>One error per line, and warnings are unaffected</h3>
 *
 * <p>The rule is deliberately blunt because the precise version is unavailable: the honest unit is
 * "one per <em>statement</em>", and a file with a syntax error is exactly the file whose statement
 * boundaries the parser could not find. A line is the practical approximation, and it is what both
 * reference IDEs converge on in practice — a compiler stops at the first problem in a construct and so
 * does a reader.</p>
 *
 * <p>What it costs is a second genuine error on one line, which the author sees after fixing the
 * first — which is how every compiler in the world already behaves. <b>Warnings are never suppressed
 * by an error</b>: they come from independent checks rather than from recovery, so a duplicate
 * parameter name on the same line as a syntax error is still true.</p>
 *
 * <h3>The engine's refusals get named</h3>
 *
 * <p>{@code class}, {@code import}, {@code export} and {@code await} are refused by every band we ship,
 * and Rhino reports them as <i>"identifier is a reserved word"</i> — which is accurate about its own
 * lexer and useless to an author, who did not think they were declaring an identifier. Re-titling costs
 * one table and turns the single most likely first-day confusion into a sentence that says what is
 * actually true.</p>
 *
 * <p>{@code async} is the one that needs the source rather than the message: it lexes as a plain
 * identifier, so the error lands on the {@code function} after it and reads <i>"missing ; before
 * statement"</i> with no mention of {@code async} anywhere. Looking back over the preceding word is the
 * only way to recognise it, and it is worth doing — {@code async function} is how a modern author
 * starts.</p>
 */
final class RhinoProblemPolicy {

    /** How this engine identifies itself in a re-titled message. */
    private final String engineName;

    private RhinoProblemPolicy(String engineName) {
        this.engineName = engineName;
    }

    static RhinoProblemPolicy of(String engineName) {
        return new RhinoProblemPolicy(engineName == null || engineName.isEmpty()
                ? "this engine" : engineName);
    }

    /**
     * A keyword this engine refuses, and what to say instead.
     *
     * <p>Every one of these is refused by <b>both</b> shipped Rhinos, which is why the message can be
     * flat rather than per band: there is no host where writing {@code class} works, so "not supported"
     * is the whole truth and a version-qualified message would imply a newer band might help.</p>
     */
    private static String refusalFor(String keyword) {
        switch (keyword) {
            case "class":
                return "classes are not supported by ";
            case "import":
            case "export":
                return "ES modules are not supported by ";
            case "await":
            case "async":
                return "async functions are not supported by ";
            default:
                return null;
        }
    }

    /** Rhino's own wording for a keyword it lexed as an identifier. */
    private static final String RESERVED_WORD = "identifier is a reserved word: ";

    /**
     * Rhino's wording when recovery gives up at a token — the cascade message, and also what
     * {@code async} produces because {@code async} itself lexes cleanly.
     */
    private static final String MISSING_SEMICOLON = "missing ; before statement";

    /**
     * Turns one parse's problems into what the editor should show.
     *
     * @param source the exact text the parse saw — needed to recognise {@code async}, and the only
     *               thing that still has it by the time anything downstream looks
     */
    List<Diagnostic> apply(List<ParseProblem> problems, String source, LineIndex lines) {
        List<Diagnostic> out = new ArrayList<>(problems.size());
        int lastErrorRow = -1;
        for (ParseProblem problem : problems) {
            boolean isError = problem.getType() == ParseProblem.Type.Error;
            int from = clamp(problem.getFileOffset(), source.length());
            TextPoint start = lines.pointAt(from);

            // ONE ERROR PER LINE. Warnings are exempt: they are independent checks rather than
            // recovery, so an error does not make them untrue. @see the class note
            if (isError && start.row() == lastErrorRow) continue;
            if (isError) lastErrorRow = start.row();

            // TRAILING WHITESPACE IS NOT PART OF THE PROBLEM. Rhino reports `identifier is a reserved
            // word: static` with a length that runs past the keyword and over the space after it, so the
            // squiggle drew under `static ` -- a mark that looks like it is complaining about the gap,
            // and visibly ragged next to one on the line above ending at its own last letter.
            //
            // Trimmed rather than second-guessed with a token scan: the offset Rhino gives is right and
            // only the tail is loose, so taking whitespace off the end fixes it without this class
            // having an opinion about where tokens end.
            int reported = Math.min(source.length(), from + Math.max(0, problem.getLength()));
            while (reported > from && Character.isWhitespace(source.charAt(reported - 1))) reported--;

            // A ZERO-LENGTH PROBLEM IS REAL AND IS WIDENED BY ONE. "missing ; before statement" points
            // BETWEEN two characters, and a mark with no width cannot be seen -- the same rule the
            // editor's diagnostic lane already applies, stated here so the range arrives usable. Applied
            // AFTER the trim, or a problem whose whole reported span is whitespace would collapse.
            int to = Math.min(source.length(), Math.max(reported, from + 1));
            out.add(new Diagnostic(start, lines.pointAt(to),
                    isError ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING,
                    retitle(problem.getMessage(), source, from), OWNER, null));
        }
        return out;
    }

    /**
     * The engine's own message, or a better one where this engine's limits are what went wrong.
     *
     * <p>Falls through unchanged for everything else, which is most problems and is the right default:
     * Rhino's parse messages are ordinary and clear, and rewriting them wholesale would put a
     * translation layer between an author and their engine for no gain.</p>
     */
    private String retitle(String message, String source, int offset) {
        if (message == null) return "";

        if (message.startsWith(RESERVED_WORD)) {
            String keyword = message.substring(RESERVED_WORD.length()).trim();
            String refusal = refusalFor(keyword);
            if (refusal != null) return "'" + keyword + "': " + refusal + engineName;
        }

        // `#` IS A FEATURE, NOT A TYPO. `#weight` is an ES2022 private class field, which Rhino has no
        // support for -- but it fails in the LEXER rather than the parser, so the message is
        // "illegal character: #" where every other unsupported construct on the same screen names
        // itself. An author reads that as having typed something wrong, and goes looking at the
        // character instead of at the feature.
        //
        // Guarded on what follows, because `#` genuinely is a stray character anywhere else: only a
        // `#` immediately before an identifier is the private-member syntax. A lone one keeps Rhino's
        // own wording, which is then the accurate answer.
        if (message.startsWith(ILLEGAL_HASH) && startsPrivateMember(source, offset)) {
            return "'#': private class members are not supported by" + engineName;
        }

        // `async` NEEDS THE SOURCE, because it does not appear in the message. It lexes as an ordinary
        // identifier, so the parser only complains at the `function` that follows it -- and the author
        // reads "missing ; before statement" pointing at a `function` keyword they wrote correctly.
        if (MISSING_SEMICOLON.equals(message) && precededByWord(source, offset, "async")) {
            return "'async': " + refusalFor("async") + engineName;
        }
        return message;
    }

    /** Rhino's wording when its lexer meets a {@code #}, which is the private-member syntax. */
    private static final String ILLEGAL_HASH = "illegal character: #";

    /** Whether the {@code #} at {@code offset} begins a private member rather than being a stray. */
    private static boolean startsPrivateMember(String source, int offset) {
        int at = Math.min(offset, source.length());
        // THE OFFSET MAY POINT AT THE `#` OR JUST PAST IT, depending on where the lexer stopped, so the
        // character itself is found rather than assumed.
        if (at < source.length() && source.charAt(at) == '#') at++;
        else if (at > 0 && source.charAt(at - 1) == '#') { /* already past it */ }
        else return false;
        return at < source.length() && Character.isJavaIdentifierStart(source.charAt(at));
    }

    /** Whether the last word before {@code offset}, skipping whitespace, is {@code word}. */
    private static boolean precededByWord(String source, int offset, String word) {
        int end = Math.min(offset, source.length());
        while (end > 0 && Character.isWhitespace(source.charAt(end - 1))) end--;
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) start--;
        return end > start && source.regionMatches(start, word, 0, word.length())
                && end - start == word.length();
    }

    private static int clamp(int offset, int length) {
        return Math.max(0, Math.min(offset, length));
    }

    /** Where a problem says it came from, in the Problems panel's source column. */
    static final String OWNER = "rhino";
}
