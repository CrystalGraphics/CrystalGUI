package com.crystalgui.language.js.rhino;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code import net.minecraft.client.Minecraft;} at the top of a script — <b>blanked, never rewritten</b>.
 *
 * <h3>Why Rhino cannot simply be given the statement</h3>
 *
 * <p>{@code import} is a <em>reserved word</em> in Rhino and is not implemented, so a script carrying one
 * dies in the parser with "identifier is a reserved word: import" before anything of ours runs. Neither
 * {@code importClass} nor {@code importPackage} exists either — those come from {@code ImporterTopLevel},
 * and this engine builds its scope with a plain {@code initStandardObjects}. Measured against the running
 * band rather than assumed.</p>
 *
 * <h3>Blank and bind, not substitute</h3>
 *
 * <p>The obvious implementation rewrites the line into {@code var Minecraft = Java.type("…");}. That
 * changes its length, so <b>every offset after it shifts</b> — and this editor's diagnostics, squiggles,
 * folds, completion and go-to are all offsets. A rewriting preprocessor buys one keyword and pays for it
 * with every position in the file.</p>
 *
 * <p>So the statement is replaced with spaces of exactly its own length. Same length, same line count:
 * the parser sees whitespace, and no position anywhere in the document moves. The name is then bound
 * separately — into the Rhino scope for the runtime, and into the analyser's bindings for the editor.</p>
 *
 * <p><b>This is the Java side's trick, deliberately.</b> {@code ScriptPrelude.blankImports} does exactly
 * this for a bare Java snippet — blanking the imports out of the body and re-emitting them above the
 * synthesized class — and its comment makes the same argument: "Same length, not removal — that is the
 * entire trick." Two languages, one mechanism, and the rule is easier to keep than to rediscover.</p>
 *
 * <h3>The one class in this package that BOTH loaders define, on purpose</h3>
 *
 * <p>Everything else here is child-side and stays there. This is named by {@code Grammar} as well — the
 * grammar has to blank the same statements or tree-sitter mis-colours the whole file, and a bytecode
 * scan forbids {@code language.js} from naming {@code language.grammar}, so the reference can only run
 * in that direction. So there are two copies of this class in the process, and that is <b>safe here and
 * nowhere near general</b>: it is stateless, and every value it hands across is a {@code String} or a
 * collection of them — all parent-first, all meaning the same thing on both sides. The moment
 * {@code Scanned} or {@code Imported} crossed the bridge the two copies would stop being assignable and
 * this would be the bug the package charter warns about.</p>
 */
public final class JsImports {

    private JsImports() {
    }

    /**
     * Anchored at the start of a line, which is what keeps a commented-out import out of it.
     *
     * <p>{@code // import a.b.C;} does not match, because {@code /} is not whitespace. An import inside a
     * <em>block</em> comment does match, and that is the same exposure {@code ScriptPrelude} accepts:
     * blanking a line that was already inert changes nothing a reader can observe.</p>
     *
     * <h3>A terminator is required, and the end of the line counts as one</h3>
     *
     * <p>JavaScript has automatic semicolon insertion and authors use it, so requiring the {@code ;} left
     * {@code import a.b.C} unblanked — and what the author then saw was Rhino's own
     * <em>"'import': ES modules are not supported"</em>, which names a feature they had not asked for and
     * points at a line whose only fault was punctuation.</p>
     *
     * <p>Required rather than optional, though: with a bare {@code ;?} the pattern happily matches the
     * {@code java.util.} of {@code import java.util.*;} and blanks it, leaving {@code *;} behind — a
     * wildcard silently half-erased. Demanding {@code ;} or end-of-line makes that shape fail to match at
     * all, which is what leaves it for the parser to report.</p>
     *
     * <p>{@code [ \t]} and never {@code \s}: {@code \s} matches a newline, and blanking one would
     * change the row count. Every row below an import has to stay on the row the editor already
     * published.</p>
     */
    private static final Pattern IMPORT =
            Pattern.compile("(?m)^[ \\t]*(import[ \\t]+([\\w.$]+)[ \\t]*(?:;|$))");

    /**
     * One import's fully qualified name, and where it was written.
     *
     * <p>The offset is why this exists. The statement is blanked before the parser sees it, so the tree
     * carries nothing about it — and without the span the editor cannot colour the line, which is how a
     * working import came to be drawn by tree-sitter's error recovery guessing at ES module syntax.</p>
     */
    public static final class Imported {

        private final String binaryName;
        private final String simpleName;
        private final int nameStart;
        private final int keywordStart;

        Imported(String binaryName, int nameStart, int keywordStart) {
            this.binaryName = binaryName;
            this.simpleName = simpleNameOf(binaryName);
            this.nameStart = nameStart;
            this.keywordStart = keywordStart;
        }

        public String binaryName() {
            return binaryName;
        }

        /**
         * The name this statement actually BINDS — the last segment, a nested type unwrapped.
         *
         * <p>Carried rather than re-derived. {@code scan} computes it anyway to key its own map, so this
         * costs one field, and it had already been written out a second time by the colour pass — which
         * needs it to tell an imported name from a host binding. A third reader (the unused-import check)
         * was one transcription too many for arithmetic that is silently wrong on a nested type.</p>
         */
        public String simpleName() {
            return simpleName;
        }

        /** Where the qualified name begins, in the author's own offsets. Blanking preserves them. */
        public int nameStart() {
            return nameStart;
        }

        /**
         * Where the {@code import} keyword begins.
         *
         * <p>Carried because <b>nothing else can find it</b>. The statement is blanked before the parser
         * sees it, so there is no node to colour from, and the grammar — reading the raw text — gives
         * the word nothing either once the line stops parsing as an ES module declaration. Without this
         * the one keyword on the line rendered as plain body text while the path beside it was coloured,
         * which reads as the import being half-understood.</p>
         */
        public int keywordStart() {
            return keywordStart;
        }
    }

    /** A script with its imports blanked, and the names they bound. */
    public static final class Scanned {

        private final String blanked;
        private final Map<String, String> imported;
        private final List<Imported> statements;

        Scanned(String blanked, Map<String, String> imported, List<Imported> statements) {
            this.blanked = blanked;
            this.imported = imported;
            this.statements = statements;
        }

        /** Every import, in source order, with the span of its qualified name. */
        public List<Imported> statements() {
            return statements;
        }

        /** The script as the parser should see it — same length, same rows, imports spaced out. */
        public String source() {
            return blanked;
        }

        /** Simple name to binary name, in the order they were written. */
        public Map<String, String> imported() {
            return imported;
        }

        public boolean isEmpty() {
            return imported.isEmpty();
        }
    }

    /**
     * Finds every import, blanks it, and answers what it bound.
     *
     * <p>A trailing segment that is not a plain identifier — {@code import a.b.*} — is <b>not</b>
     * collected and <b>not</b> blanked, so it reaches the parser and is reported as the error it is. A
     * wildcard import cannot be honoured here: binding it would mean enumerating a package, which is not
     * a thing a classloader can do, and silently ignoring it would leave the author with a line that
     * looks like it worked.</p>
     */
    public static Scanned scan(String source) {
        if (source == null || source.isEmpty()) {
            return new Scanned(source == null ? "" : source, new LinkedHashMap<>(), List.of());
        }
        Matcher matcher = IMPORT.matcher(source);
        StringBuilder blanked = new StringBuilder(source);
        Map<String, String> imported = new LinkedHashMap<>();
        List<Imported> statements = new ArrayList<>();
        while (matcher.find()) {
            String binaryName = matcher.group(2);
            String simple = simpleNameOf(binaryName);
            if (simple.isEmpty()) continue;
            // GROUP 1 STARTS AT THE KEYWORD -- the pattern anchors past leading whitespace, so its start
            // is exactly where `import` is written.
            statements.add(new Imported(binaryName, matcher.start(2), matcher.start(1)));
            // FIRST WINS, so a repeated import is not a redefinition. Two lines naming the same simple
            // name is an author error, and the one the editor underlines should be the second.
            imported.putIfAbsent(simple, binaryName);
            for (int i = matcher.start(1); i < matcher.end(1); i++) blanked.setCharAt(i, ' ');
        }
        return new Scanned(blanked.toString(), imported, statements);
    }

    /**
     * The source with its imports blanked — what a parser should be handed.
     *
     * <p>The shape a grammar source filter takes, and the reason it is safe to be one: same length, same
     * rows. @see com.crystalgui.language.grammar.Grammar#filterSourceWith</p>
     */
    public static String blank(String source) {
        return scan(source).source();
    }

    /**
     * Which of {@code imports} nothing in the file mentions.
     *
     * <h3>One definition, because two readers ask</h3>
     *
     * <p>The analyser asks it to raise the warning and the fix catalog asks it to offer "Remove unused
     * import". Two transcriptions of the same predicate is a warning with no fix, or a fix on a line with
     * no warning, and both look like the feature half-working rather than like a disagreement.</p>
     *
     * <p>{@code referenced} is the set of FREE names — those resolving to nothing the file declares, which
     * is exactly what an import provides. That is what makes shadowing come out right: a file importing
     * {@code Greeter} and then declaring its own {@code var Greeter} mentions the name and the import is
     * still dead, because the reference binds to the local.</p>
     */
    public static List<Imported> unusedIn(List<Imported> imports, Set<String> referenced) {
        if (imports == null || imports.isEmpty()) return List.of();
        List<Imported> unused = new ArrayList<>();
        for (Imported each : imports) {
            String simple = each.simpleName();
            if (simple.isEmpty() || referenced.contains(simple)) continue;
            unused.add(each);
        }
        return unused;
    }

    /** The names this script imports, without touching the source. */
    public static List<String> namesIn(String source) {
        return new ArrayList<>(scan(source).imported().keySet());
    }

    private static String simpleNameOf(String binaryName) {
        int lastDot = binaryName.lastIndexOf('.');
        String simple = lastDot < 0 ? binaryName : binaryName.substring(lastDot + 1);
        // A NESTED CLASS is written `a.b.Outer$Inner` at the JVM and `a.b.Outer.Inner` by an author. The
        // binding takes the last segment either way, which is `Inner` -- the name the script will use.
        int lastDollar = simple.lastIndexOf('$');
        return lastDollar < 0 ? simple : simple.substring(lastDollar + 1);
    }
}
