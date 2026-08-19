package com.crystalgui.language.java.exec;

import com.crystalgui.language.java.ecj.SourcePackages;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A script is not a compilation unit — this is what makes it one, reversibly.
 *
 * <h3>What the author writes and what the compiler sees</h3>
 *
 * <p>A script is a body: statements, and the host's context ({@code graph}, {@code event}) already in
 * scope. Java has no such thing, so the text is wrapped in a synthesized unit — an imports region, a
 * class header, a typed field per host binding, the user's text, and the closing braces. JShell does
 * exactly this and for exactly this reason.</p>
 *
 * <h3>The user's text is spliced at a FIXED prefix, and that is the whole design</h3>
 *
 * <p>Mapping a compiler offset back to the author's is then {@code - constant}, applied in one place.
 * Anything cleverer — a table, a per-edit recomputation — is a second source of truth about where the
 * text is, and the failure when it drifts is a diagnostic pointing at the wrong line, which reads as
 * the compiler being confused.</p>
 *
 * <p><b>Line numbers ride the same constant</b>, which §15.5 D.2 requires and which is easy to get
 * wrong separately: a runtime stack trace and a compile diagnostic must name the line the author sees,
 * or the engine is undebuggable.</p>
 *
 * <h3>An import the user types is hoisted WITHOUT moving anything</h3>
 *
 * <p>Java requires imports before the class declaration, and the user writes them wherever they like.
 * Moving the text would break the constant offset — so the statement is copied into the prelude's
 * import region and <b>blanked in place with spaces of exactly the same length</b>. The compiler sees
 * a legal unit, every offset after it is unchanged, and the author's line numbering is untouched.</p>
 *
 * <p>The alternative — actually relocating the line — makes the mapping a table, and the table is wrong
 * the moment two imports are on one line or one is inside a comment.</p>
 */
public final class ScriptPrelude {

    /**
     * An import statement anywhere in the text.
     *
     * <p>Anchored to a line start so a {@code import} inside a string or a comment is not hoisted.
     * That is not a complete lexer and does not need to be: hoisting something that was not an import
     * produces a compile error in the prelude, which is visible, rather than silently changing the
     * script — and the case it fails on (a line whose first token is {@code import} inside a block
     * comment) is one nobody writes.</p>
     */
    private static final Pattern IMPORT =
            Pattern.compile("(?m)^[ \\t]*(import\\s+(?:static\\s+)?[\\w.]+\\s*(?:\\.\\s*\\*)?\\s*;)");

    private final String className;
    private final Map<String, String> bindings;

    private ScriptPrelude(String className, Map<String, String> bindings) {
        this.className = className;
        this.bindings = bindings;
    }

    public static Builder forClass(String className) {
        return new Builder(className);
    }

    public static final class Builder {
        private final String className;
        private final Map<String, String> bindings = new LinkedHashMap<>();

        private Builder(String className) {
            this.className = className;
        }

        /**
         * A name the host puts in scope, and its type.
         *
         * <p>Emitted as a field rather than a parameter so it is in scope for the whole script without
         * the author declaring anything — which is the point of a scripting host — and so that
         * completion, hover and go-to all treat it as an ordinary member.</p>
         */
        public Builder binding(String name, String typeName) {
            bindings.put(name, typeName);
            return this;
        }

        public ScriptPrelude build() {
            return new ScriptPrelude(className, bindings);
        }
    }

    /**
     * A source file that is ALREADY a compilation unit — wrapped by nothing.
     *
     * <p>A user's {@code Main.java} declares its own class, so there is nothing to synthesize: the
     * prelude and the suffix are empty and every offset maps by zero. It goes through {@link Wrapped}
     * anyway rather than round a separate path, because everything downstream — the compiler, the cache
     * key, the diagnostic mapping — is written against that type, and a second shape would mean a second
     * set of call sites that can drift.</p>
     *
     * <p><b>Wrapping one would not merely be unnecessary, it would not compile.</b> A prelude puts the
     * text inside a method body, where a top-level {@code public class} is a local class — and a local
     * class may not be {@code public}. The error names an access modifier the author wrote correctly,
     * which is the worst kind of report.</p>
     */
    public static Wrapped compilationUnit(String className, String source) {
        return new Wrapped(className, "", source == null ? "" : source, "");
    }

    /**
     * Whether {@code source} already declares a top-level type, and so should be compiled as-is.
     *
     * <p>Deliberately shallow: a line whose first token, after modifiers, is {@code class},
     * {@code interface}, {@code enum}, {@code record} or {@code @interface}. It is not a parser and does
     * not need to be — the two shapes are a file somebody wrote as a class and a snippet somebody wrote
     * as statements, and nothing in between is a case anybody produces. Getting it wrong in either
     * direction produces a compile error the author can read, rather than silently doing the other thing.</p>
     */
    public static boolean declaresType(String source) {
        if (source == null) return false;
        return TOP_LEVEL_TYPE.matcher(stripComments(source)).find();
    }

    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|final|abstract|sealed|non-sealed|strictfp)\\s+)*"
                    + "(?:class|interface|enum|record|@interface)\\s+\\w");

    /**
     * Comments removed before the scan.
     *
     * <p>Without it a file opening with a javadoc that says "class" — which is most of them in this
     * repository — is read as declaring one. Strings are left alone: a string containing something that
     * looks like a class declaration at the start of a line is not a thing anybody writes, and stripping
     * them properly needs the lexer this deliberately is not.</p>
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /** Wraps {@code script} into a compilation unit and returns the mapping with it. */
    public Wrapped wrap(String script) {
        List<String> hoisted = new ArrayList<>();
        String body = blankImports(script, hoisted);

        StringBuilder prefix = new StringBuilder();
        for (String statement : hoisted) prefix.append(statement).append('\n');
        prefix.append("public class ").append(className).append(" {\n");
        for (Map.Entry<String, String> binding : bindings.entrySet()) {
            prefix.append("    public ").append(binding.getValue()).append(' ')
                    .append(binding.getKey()).append(";\n");
        }
        prefix.append("    public void run() throws Throwable {\n");

        String suffix = "\n    }\n}\n";
        return new Wrapped(className, prefix.toString(), body, suffix);
    }

    /**
     * Replaces every import statement with spaces of the same length, collecting the originals.
     *
     * <p>Same length, not removal — that is the entire trick. Length-preserving means every offset
     * after the import is unchanged and the mapping stays a constant.</p>
     */
    private static String blankImports(String script, List<String> collected) {
        Matcher matcher = IMPORT.matcher(script);
        StringBuilder blanked = new StringBuilder(script);
        while (matcher.find()) {
            String statement = matcher.group(1);
            collected.add(statement);
            for (int i = matcher.start(1); i < matcher.end(1); i++) blanked.setCharAt(i, ' ');
        }
        return blanked.toString();
    }

    /**
     * A wrapped script, and the arithmetic to get back.
     *
     * <p>Every conversion here is an addition or a subtraction. If one ever needs a lookup, the
     * splice stopped being at a fixed prefix and the design has been lost.</p>
     */
    public static final class Wrapped {

        private final String className;
        private final String prefix;
        private final String body;
        private final String suffix;
        private final int prefixRows;

        Wrapped(String className, String prefix, String body, String suffix) {
            this.className = className;
            this.prefix = prefix;
            this.body = body;
            this.suffix = suffix;
            int rows = 0;
            for (int i = 0; i < prefix.length(); i++) {
                if (prefix.charAt(i) == '\n') rows++;
            }
            this.prefixRows = rows;
        }

        /** What the compiler is given. */
        public String unitSource() {
            return prefix + body + suffix;
        }

        public String className() {
            return className;
        }

        /**
         * The name a loader must be asked for — {@code com.example.Main}, not {@code Main}.
         *
         * <p>Derived from the unit's own {@code package} declaration rather than from
         * {@link #className()}, which is a file stem and knows nothing about packages. A caller that
         * used the stem would compile a packaged file successfully and then fail to load it, with a
         * {@code ClassNotFoundException} naming a class that is plainly right there in the output.</p>
         *
         * <p>For a prelude-wrapped snippet the two are the same: a synthesized unit declares no package.</p>
         */
        public String binaryName() {
            return SourcePackages.binaryName(className, body);
        }

        /** Characters of synthesized text before the author's first character. */
        public int prefixLength() {
            return prefix.length();
        }

        /** Lines of synthesized text before the author's first line. */
        public int prefixRows() {
            return prefixRows;
        }

        /** An offset in the author's text, as the compiler sees it. */
        public int toUnitOffset(int scriptOffset) {
            return scriptOffset + prefix.length();
        }

        /**
         * A compiler offset, as the author sees it — or {@code -1} when it is in synthesized text.
         *
         * <p><b>-1 rather than a clamp.</b> A problem in the prelude is a problem in code the author
         * never wrote and cannot fix; clamping it to offset 0 puts a squiggle on their first character
         * and blames them for it. The caller drops it, or reports it as an engine fault, and either is
         * better than a lie.</p>
         */
        public int toScriptOffset(int unitOffset) {
            int mapped = unitOffset - prefix.length();
            return mapped < 0 || mapped > body.length() ? -1 : mapped;
        }

        /** A compiler row/column, as the author sees it — or null when it is in synthesized text. */
        public TextPoint toScriptPoint(TextPoint unitPoint) {
            if (unitPoint == null) return null;
            int row = unitPoint.row() - prefixRows;
            return row < 0 ? null : new TextPoint(row, unitPoint.column());
        }

        /**
         * A diagnostic about the unit, rewritten to be about the script — or null to drop it.
         *
         * <p>Dropped when either end lands in the prelude, for the reason above. The one case worth
         * naming: a script whose text is legal in isolation but not as a method body (a stray
         * {@code }}) can produce a problem the compiler places on the synthesized closing brace. There
         * is nothing useful to show for it, and showing it on line 1 would be worse than nothing.</p>
         */
        public Diagnostic toScriptDiagnostic(Diagnostic unitDiagnostic) {
            if (unitDiagnostic == null) return null;
            TextPoint start = toScriptPoint(unitDiagnostic.start());
            TextPoint end = toScriptPoint(unitDiagnostic.end());
            if (start == null || end == null) return null;
            return new Diagnostic(start, end, unitDiagnostic.severity(), unitDiagnostic.message(),
                    unitDiagnostic.source(), unitDiagnostic.code(),
                    unitDiagnostic.tags(), unitDiagnostic.related());
        }
    }
}
