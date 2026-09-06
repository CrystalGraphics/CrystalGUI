package com.crystalgui.core.pattern;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Maps file names to values by <b>pattern</b> — an exact name, an extension, or a glob.
 *
 * <h3>Why this is one class rather than one per consumer</h3>
 *
 * <p>Two things need to answer "what is this file?" and they must not disagree: which <em>language</em> it
 * is written in, and which <em>editor</em> opens it. Those are genuinely different questions — a
 * {@code .java} and a {@code .glsl} want different languages and the same editor — but "does this name
 * match that pattern" is the same question both times, and the second implementation of it is where the
 * two start answering differently about {@code my.project/README} or {@code .gitignore}.</p>
 *
 * <h3>Most specific wins</h3>
 *
 * <p>Consulted in order: exact <b>name</b>, then <b>extension</b>, then <b>glob</b>. That order is the
 * load-bearing part. It is what lets {@code CMakeLists.txt} be CMake while {@code .txt} stays plain, and
 * what stops a broad {@code *} from shadowing every deliberate rule beneath it — with the order reversed,
 * every registration becomes a race with whichever ran last.</p>
 *
 * <p>Within one kind, a later registration for the same pattern <b>replaces</b> the earlier one, so a host
 * can override a default without having to unregister it first.</p>
 *
 * <h3>Globs are matched, not compiled</h3>
 *
 * <p>{@code *} and {@code ?} only, matched by an explicit walk rather than by translating to a regex. The
 * escaping is the whole risk: a file pattern is mostly dots, and one unescaped dot turns {@code *.js} into
 * a rule that also claims {@code axjs}. Dots here are literal, which is what whoever wrote the pattern
 * meant.</p>
 *
 * <p>Matching is case-insensitive throughout, and a path is reduced to its last segment first — so a dot
 * in a <em>directory</em> name can never be read as the file's extension.</p>
 */
public final class FilePatternMap<V> {

    private enum Kind {
        /** The whole file name, exactly — {@code Dockerfile}, {@code .gitignore}. */
        NAME,
        /** The part after the last dot — {@code java}. */
        EXTENSION,
        /** A glob over the whole name — {@code *.test.js}. */
        GLOB
    }

    private record Rule<V>(Kind kind, String pattern, V value) {
    }

    /** Insertion-ordered, so {@link #patterns()} reads predictably and glob evaluation is deterministic
     * rather than dependent on hash order. */
    private final Map<String, Rule<V>> rules = new LinkedHashMap<>();

    /** Binds {@code value} to each <b>exact</b> file name. */
    public FilePatternMap<V> putNames(V value, String... fileNames) {
        return put(Kind.NAME, value, fileNames);
    }

    /** Binds {@code value} to each extension, with or without the leading dot. */
    public FilePatternMap<V> putExtensions(V value, String... extensions) {
        return put(Kind.EXTENSION, value, extensions);
    }

    /** Binds {@code value} to each glob over the whole file name. */
    public FilePatternMap<V> putGlobs(V value, String... globs) {
        return put(Kind.GLOB, value, globs);
    }

    private FilePatternMap<V> put(Kind kind, V value, String... patterns) {
        if (value == null) throw new IllegalArgumentException("A pattern value must not be null");
        for (String pattern : patterns) {
            if (pattern == null || pattern.isEmpty()) continue;
            String normalised = kind == Kind.EXTENSION ? stripDot(pattern) : lower(pattern);
            rules.put(kind + ":" + normalised, new Rule<>(kind, normalised, value));
        }
        return this;
    }

    /** The value bound to this file name or path, or {@code null} when nothing claims it. */
    @Nullable
    public V get(@Nullable String fileNameOrPath) {
        String name = lastSegment(fileNameOrPath);
        if (name == null || name.isEmpty()) return null;

        Rule<V> byName = rules.get(Kind.NAME + ":" + name);
        if (byName != null) return byName.value();

        String extension = extensionOf(name);
        if (extension != null) {
            Rule<V> byExtension = rules.get(Kind.EXTENSION + ":" + extension);
            if (byExtension != null) return byExtension.value();
        }

        for (Rule<V> rule : rules.values()) {
            if (rule.kind() == Kind.GLOB && globMatches(rule.pattern(), name)) return rule.value();
        }
        return null;
    }

    /** Every registered pattern, in registration order, as {@code KIND:pattern}. Deliberately a list:
     * the order is the point, and a set loses it. */
    public List<String> patterns() {
        return List.copyOf(rules.keySet());
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    // ── Name arithmetic ─────────────────────────────────────────────────────────────────────────

    @Nullable
    private static String lastSegment(@Nullable String path) {
        if (path == null) return null;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lower(slash >= 0 ? path.substring(slash + 1) : path);
    }

    /**
     * The extension of a bare file name, or null.
     *
     * <p>A <b>leading</b> dot is not an extension: {@code .gitignore} is a file called {@code .gitignore},
     * not a file with a {@code gitignore} extension — which is why such files have to be matched by name,
     * and why {@code dot <= 0} rather than {@code dot < 0}.</p>
     */
    @Nullable
    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1);
    }

    private static String stripDot(String extension) {
        String trimmed = extension.trim();
        return lower(trimmed.startsWith(".") ? trimmed.substring(1) : trimmed);
    }

    private static String lower(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** Standard backtracking glob match — linear in practice, and immune to punctuation. */
    private static boolean globMatches(String glob, String name) {
        int g = 0, n = 0, star = -1, mark = 0;
        while (n < name.length()) {
            if (g < glob.length() && (glob.charAt(g) == '?' || glob.charAt(g) == name.charAt(n))) {
                g++;
                n++;
            } else if (g < glob.length() && glob.charAt(g) == '*') {
                star = g++;
                mark = n;
            } else if (star >= 0) {
                g = star + 1;
                n = ++mark;
            } else {
                return false;
            }
        }
        while (g < glob.length() && glob.charAt(g) == '*') g++;
        return g == glob.length();
    }
}
