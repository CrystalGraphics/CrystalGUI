package com.crystalgui.language.run;

/**
 * Removes ANSI terminal escapes from a line of output.
 *
 * <h3>Why a console needs this at all</h3>
 *
 * <p>Nothing in the engine emits them, and plenty of ordinary Java does. A logging framework with colour
 * enabled, a library that draws a progress bar, anything ported from a command-line tool — all of them
 * write {@code ESC[31m} around their text because a terminal eats it. This console is not a terminal, so
 * it showed the escapes: {@code [31mERROR[0m something failed}, which reads as the output being
 * corrupted rather than as the console not speaking ANSI.</p>
 *
 * <h3>Stripped rather than interpreted, and that is a scope decision worth stating</h3>
 *
 * <p>IntelliJ's console <em>renders</em> the colours. Doing that here means carrying a colour per span
 * from the producing thread, through the queue, into the tokenizer — which today assigns one capture per
 * line from {@link RunLevel} and splits only around navigable spans. That is a real feature and it is
 * not this one. Stripping is the part with no downside: the text becomes what the author meant it to
 * say, and a later colour pass reads the same escapes from the same place.</p>
 *
 * <h3>The whole CSI family, not just colour</h3>
 *
 * <p>Matching only {@code SGR} ({@code ESC[…m}) would leave cursor moves, erases and scroll-region codes
 * behind — and those are exactly what a progress bar emits, so the one case most likely to flood a
 * console with escapes is the one a colour-only rule would miss.</p>
 */
public final class AnsiEscapes {

    private static final char ESCAPE = 0x1B;

    private AnsiEscapes() {
    }

    /**
     * {@code text} with any escape sequences removed.
     *
     * <p>Returns the argument itself when there is nothing to strip, which is nearly every line — this
     * runs on every line a script prints, and the common case must not allocate.</p>
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) return text;
        int first = text.indexOf(ESCAPE);
        if (first < 0) return text;

        StringBuilder out = new StringBuilder(text.length());
        out.append(text, 0, first);
        int index = first;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c != ESCAPE) {
                out.append(c);
                index++;
                continue;
            }
            index = skipSequence(text, index);
        }
        return out.toString();
    }

    /**
     * The index just past the escape sequence beginning at {@code start}.
     *
     * <p>Two shapes cover everything that turns up in practice. <b>CSI</b> — {@code ESC[} then parameter
     * and intermediate bytes then a final byte in {@code @}–{@code ~} — is colour, cursor movement,
     * erasing and scroll regions. <b>OSC</b> — {@code ESC]} until {@code BEL} or {@code ESC\} — is the
     * window title, which a build tool sets and which would otherwise dump a path into the transcript.</p>
     *
     * <p>An escape at the very end of a line, or one whose terminator never arrives, consumes the rest:
     * a half-written sequence is not text the author meant to show, and leaving it would put the
     * fragment on screen at exactly the moment the line was cut.</p>
     */
    private static int skipSequence(String text, int start) {
        int index = start + 1;
        if (index >= text.length()) return text.length();

        char kind = text.charAt(index);
        if (kind == '[') {
            for (index++; index < text.length(); index++) {
                char c = text.charAt(index);
                // The final byte of a CSI sequence, per ECMA-48: everything before it is parameters and
                // intermediates, which are all below '@'.
                if (c >= '@' && c <= '~') return index + 1;
            }
            return text.length();
        }
        if (kind == ']') {
            for (index++; index < text.length(); index++) {
                char c = text.charAt(index);
                if (c == 0x07) return index + 1;
                if (c == ESCAPE && index + 1 < text.length() && text.charAt(index + 1) == '\\') {
                    return index + 2;
                }
            }
            return text.length();
        }
        // A two-character escape -- ESC 7, ESC =, and the rest of the Fe/Fs family. One byte follows.
        return index + 1;
    }
}
