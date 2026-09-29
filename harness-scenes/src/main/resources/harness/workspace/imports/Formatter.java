package com.example.util;

/**
 * Bottom of the chain — depends on nothing but the JDK.
 *
 * <p>Part of the M15 cross-file fixture. Three files, three different ways of reaching another one:</p>
 *
 * <ul>
 *   <li>{@code Greeter} calls this with <b>no import</b>, because they share a package.</li>
 *   <li>{@code com.example.Main} reaches {@code Greeter} <b>through an import</b>, from another package.</li>
 *   <li>and {@code Main} therefore depends on this one <b>transitively</b>, without naming it at all.</li>
 * </ul>
 *
 * <p><b>Things worth trying here.</b> Rename {@code shout} and watch {@code Greeter} go red on the next
 * analysis without saving anything — the index answers from the open buffer, not the file. Change the
 * return type to {@code int} and watch the error appear in {@code Greeter}, one file away. Delete the
 * method entirely and both other files should still parse, colour and fold; only resolution goes.</p>
 */
public final class Formatter {

    private static final String BANG = "!";

    private Formatter() {
    }

    /** Trims, upper-cases and punctuates. Null-safe, so the fixture has a branch in it. */
    public static String shout(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.trim().toUpperCase() + BANG;
    }

    /** A second member, so completion on {@code Formatter.} has more than one row to show. */
    public static String quiet(String text) {
        return text == null ? "" : text.trim().toLowerCase();
    }
}
