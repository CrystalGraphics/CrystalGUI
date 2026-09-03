package com.crystalgui.fs.project;

import com.crystalgui.core.pattern.FilePatternMap;
import java.util.List;

/**
 * What a project never lists and never watches — <b>one matcher, for both</b>.
 *
 * <h3>There were three, and production used none of them</h3>
 *
 * <p>A pattern matching {@code *} and {@code ?} anywhere is not what a person writes;
 * {@code NioFileEventSource} matched a <em>leading</em> {@code *} only, while its javadoc claimed
 * "the same rule"; {@code FilePatternMap} had a third, case-folded, for file icons. So a project
 * excluding {@code node_modules/*} was excluded from listings and watched anyway, and a client was told
 * about changes to files it could never see — the divergence that javadoc was written to warn against,
 * present in the class it was written in.</p>
 *
 * <p>{@code FilePatternMap}'s is deliberately <b>not</b> merged in: an icon theme matches a different
 * thing (a whole name against a user's theme file, case-insensitively) and folding the two would make
 * one of them wrong to keep the other right.</p>
 *
 * <h3>The rule</h3>
 *
 * <p>A pattern is matched against a single path <b>segment</b> — a file or directory name — with
 * {@code *} standing for any run of characters and {@code ?} for exactly one. That is what
 * {@code WorkspaceProject.excludes()} has always documented and what both consumers ask about: a
 * listing filters the names in one directory, and a watcher decides whether to descend into one.</p>
 */
public final class Excludes {

    /** Excludes nothing. The common case, and it costs no allocation and no comparison. */
    public static final Excludes NONE = new Excludes(List.of());

    private final List<String> patterns;

    private Excludes(List<String> patterns) {
        this.patterns = patterns;
    }

    public static Excludes of(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return NONE;
        return new Excludes(List.copyOf(patterns));
    }

    /** Whether this name is excluded. {@code name} is one segment, never a path. */
    public boolean excludes(String name) {
        if (patterns.isEmpty() || name == null) return false;
        for (String pattern : patterns) {
            if (matches(name, pattern, 0, 0)) return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    /** The patterns as written, for a manifest that has to send them or a log line that names them. */
    public List<String> patterns() {
        return patterns;
    }

    /**
     * {@code *} and {@code ?}, anywhere.
     *
     * <p>Recursive on {@code *} rather than iterative with a backtrack mark: the patterns here are a
     * handful of short names per project, so the simpler reading is worth more than the constant factor.
     * A pattern with several stars against a long name is the pathological case and does not arise —
     * these are {@code .git}, {@code node_modules}, {@code *.class}.</p>
     */
    private static boolean matches(String name, String pattern, int n, int p) {
        while (p < pattern.length()) {
            char c = pattern.charAt(p);
            if (c == '*') {
                for (int skip = n; skip <= name.length(); skip++) {
                    if (matches(name, pattern, skip, p + 1)) return true;
                }
                return false;
            }
            if (n >= name.length()) return false;
            if (c != '?' && c != name.charAt(n)) return false;
            n++;
            p++;
        }
        return n == name.length();
    }

    @Override
    public String toString() {
        return "Excludes" + patterns;
    }
}
