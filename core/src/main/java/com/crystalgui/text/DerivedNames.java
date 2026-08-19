package com.crystalgui.text;

import java.util.Set;

/**
 * <b>A name for something the author has not named</b> — the language-neutral half of that judgement.
 *
 * <h3>Why it is here and not in an engine</h3>
 *
 * <p>Every "introduce a variable" fix needs one, and two engines now have such fixes. The Java one had
 * all of it, in {@code language.java.fix.edit.Names} — but a class importing {@code org.eclipse.jdt} is
 * child-side to the Java band loader, and a JavaScript fix importing it would not fail: it would quietly
 * define a <em>second</em> copy on the far side of a different bridge. {@code SimilarNames} was moved here
 * for exactly this reason and is the precedent being followed.</p>
 *
 * <p>So the split is by what the rule depends on. <b>Here:</b> deduplication, the accessor stem, the
 * lowercase convention — none of which knows what a type is. <b>Left with the engine:</b> deriving a stem
 * from a resolved type, and the reserved-word set, which is not shared and must not be: {@code int} is a
 * Java keyword and an ordinary JavaScript name, {@code function} the reverse. A single merged list would
 * refuse legal names in both languages to be safe in one.</p>
 *
 * <h3>The trap the Java side paid for, kept visible</h3>
 *
 * <p>A type name is not always a legal variable name. {@code int} lowercases to {@code int}, so the first
 * version of "Introduce variable" produced {@code int int = getSize();}, which does not parse. That is why
 * {@link #derive} takes the reserved set rather than assuming the caller checked.</p>
 */
public final class DerivedNames {

    private DerivedNames() {
    }

    /**
     * A legal, unused name from {@code stem}.
     *
     * <p>An unusable stem — empty, reserved, or not a legal identifier start — becomes {@code value}
     * rather than being rejected: the caller asked for a name and there is always one to give.</p>
     *
     * @param stem     the preferred name, typically from what the expression is called
     * @param taken    names already in use; the result will not be one of them
     * @param reserved the language's keywords, which the result will not be one of either
     */
    public static String derive(String stem, Set<String> taken, Set<String> reserved) {
        String base = stem == null ? "" : stem;
        if (base.isEmpty() || reserved.contains(base) || !Character.isJavaIdentifierStart(base.charAt(0))) {
            base = "value";
        }
        String name = base;
        for (int n = 1; taken.contains(name); n++) name = base + n;
        return name;
    }

    /**
     * The first of {@code stems} nothing has taken, else the first stem numbered.
     *
     * <p>For the cases where the <em>stem</em> is the convention rather than derived from anything: a
     * catch parameter is {@code e}, and {@code ex} when something already is — which is what everybody
     * writes and is not something {@link #derive} could work out. Numbering falls back to the first stem,
     * because {@code e1} is a numbered {@code e} and {@code ex1} is nothing anybody means.</p>
     */
    public static String free(Set<String> taken, String... stems) {
        for (String stem : stems) {
            if (!taken.contains(stem)) return stem;
        }
        String stem = stems[0];
        for (int n = 1; ; n++) {
            if (!taken.contains(stem + n)) return stem + n;
        }
    }

    /** {@code getSize} → {@code size}: the stem people actually want from an accessor's name. */
    public static String fromAccessor(String method) {
        if (method == null) return "";
        for (String prefix : new String[] {"get", "is", "to", "as"}) {
            if (method.length() > prefix.length() && method.startsWith(prefix)
                    && Character.isUpperCase(method.charAt(prefix.length()))) {
                return lower(method.substring(prefix.length()));
            }
        }
        return method;
    }

    /** First character down, and {@code []} read as a plural — {@code String[]} names itself {@code strings}. */
    public static String lower(String name) {
        if (name == null || name.isEmpty()) return name == null ? "" : name;
        String cleaned = name.replace("[]", "s");
        return Character.toLowerCase(cleaned.charAt(0)) + cleaned.substring(1);
    }
}
