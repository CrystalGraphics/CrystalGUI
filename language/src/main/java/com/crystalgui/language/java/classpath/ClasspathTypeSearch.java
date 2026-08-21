package com.crystalgui.language.java.classpath;

import com.crystalgui.language.java.JavaLanguageServices;
import com.crystalgui.text.lang.TypeSearch;
import com.crystalgui.text.lang.TypeSearchRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers Go to Class from the same {@link TypeIndex} completion already uses.
 *
 * <h3>The same index, deliberately</h3>
 *
 * <p>{@link JavaLanguageServices#typeIndexFor} caches one index per classpath, and this asks for the host
 * classpath — so the picker costs nothing that completion has not already paid for. A second index would be
 * fifty thousand entries and one filesystem walk, duplicated, to answer identically; that argument is
 * already written on {@code TypeIndex}'s own visibility note, which was widened for the same reason when
 * JavaScript wanted {@code Java.type(…)} completion.</p>
 *
 * <p>It also means the <b>first</b> Go to Class in a session may pay for the scan and every later one is
 * free — and in practice it is never the first thing to ask, because opening any Java document builds it.</p>
 *
 * <h3>Kind is read here, not in the picker</h3>
 *
 * <p>{@link TypeIndex#kindOf} opens the class file to read its access flags, which is why the index does
 * not do it during the scan — fifty thousand files is tens of seconds. For the handful a query returns it
 * is milliseconds, and memoised, so the same names on the next keystroke are free. Doing it here rather
 * than leaving {@code core/} to ask keeps the class-file reading behind the seam, which is the point of
 * the seam.</p>
 *
 * <h3>No policy filter, and that is not an oversight</h3>
 *
 * <p>{@code TypeIndex.filtered} exists so a {@code ScriptPolicy} can hide classes a sandboxed script may
 * not load, and the rule it serves is real: a class <em>offered by the popup and refused at run time</em>
 * is worse than either restriction alone, because the editor is then actively wrong. It does not apply
 * here. A policy belongs to a script host, this picker belongs to no document, and its result is a
 * read-only viewer tab rather than a name a script will compile against. Navigation is not resolution.</p>
 */
public final class ClasspathTypeSearch implements TypeSearch {

    /** Registers this as a type-search provider. Idempotent — {@code TypeSearchRegistry} dedupes. */
    public static void register() {
        TypeSearchRegistry.contribute(new ClasspathTypeSearch());
    }

    @Override
    public Results search(String query, int limit) {
        if (query == null || query.isEmpty() || limit <= 0) return Results.EMPTY;

        TypeIndex index;
        try {
            index = JavaLanguageServices.typeIndexFor(HostClasspath.detect());
        } catch (RuntimeException unavailable) {
            // A classpath that cannot be detected is a host we cannot answer for. Empty, not an exception
            // out of a keystroke handler.
            return Results.EMPTY;
        }

        TypeIndex.Match matched = index.matching(query);
        List<Result> out = new ArrayList<>(Math.min(limit, matched.entries().size()));
        for (TypeIndex.Entry entry : matched.entries()) {
            if (out.size() >= limit) {
                // Our own cap truncated the index's answer, so say so even where the index did not.
                return new Results(out, true);
            }
            TypeIndex.Kind kind = index.kindOf(entry);
            out.add(new Result(entry.simpleName(), entry.packageName(), entry.container(),
                    kind == null ? null : kind.kind(), kind != null && kind.isAbstract()));
        }
        return new Results(out, matched.truncated());
    }
}
