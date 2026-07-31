package com.crystalgui.text;

/**
 * How a document's lines were terminated when it arrived, and how to write it back that way.
 *
 * <h3>The document itself is always LF</h3>
 * <p>Everything in this engine — every offset, every {@link TextSummary}, every {@code ChangeSet} — counts
 * a line break as <b>one</b> UTF-16 unit. Letting {@code \r\n} into the buffer would make a line break
 * sometimes one unit and sometimes two, and every piece of arithmetic built on offsets would be wrong by
 * the number of preceding lines. So text is normalised on the way in and the original ending is
 * <em>remembered</em> rather than preserved in place.</p>
 *
 * <p>That is what editors do, and it is why a Windows file does not silently become a Unix one after an
 * edit: the ending is restored on the way out. Without any of this, a CRLF file shows a stray carriage
 * return at the end of every line — it is not a rendering artefact, it really is in the text.</p>
 */
public enum LineEnding {

    /** Unix. The engine's internal form. */
    LF("\n"),

    /** Windows. Normalised to {@link #LF} in the buffer and restored on save. */
    CRLF("\r\n");

    private final String text;

    LineEnding(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    /**
     * The dominant ending in {@code source}.
     *
     * <p>Dominant rather than first-found, because mixed files exist and a single stray {@code \r\n} in an
     * otherwise Unix file should not convert the whole thing on save. Ties go to {@link #LF}, which is
     * also the answer for a file with no line breaks at all.</p>
     */
    public static LineEnding detect(CharSequence source) {
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) != '\n') continue;
            if (i > 0 && source.charAt(i - 1) == '\r') crlf++;
            else lf++;
        }
        return crlf > lf ? CRLF : LF;
    }

    /**
     * {@code source} with every ending collapsed to a single {@code \n}.
     *
     * <p>Handles a lone {@code \r} too — old Mac endings, and more usefully the half of a {@code \r\n}
     * that survives a careless paste. Leaving those in produces a document whose line count disagrees with
     * what is on screen.</p>
     */
    public static String normalise(CharSequence source) {
        StringBuilder out = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\r') {
                // Swallow the \n of a \r\n pair; a lone \r becomes \n on its own.
                if (i + 1 < source.length() && source.charAt(i + 1) == '\n') i++;
                out.append('\n');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** {@code source} — which must be LF-normalised — written back with this ending. */
    public String applyTo(CharSequence source) {
        if (this == LF) return source.toString();
        return source.toString().replace("\n", text);
    }
}
