package com.crystalgui.syntax.treesitter;


/**
 * Converts between this engine's UTF-16 offsets and tree-sitter's UTF-8 byte offsets, in better than
 * linear time.
 *
 * <h3>Why this class exists at all</h3>
 * <p>tree-sitter counts bytes and the engine counts UTF-16 code units. They coincide for ASCII, which is
 * exactly how a missing conversion survives every test anyone writes and then breaks on the first
 * accented character — so the conversion is not optional.</p>
 *
 * <p>The version this replaced was correct and quadratic: it built
 * {@code text.substring(0, limit).getBytes()} to convert one offset, <b>per token</b>. A viewport with a
 * couple of thousand captures over a 100KB file therefore allocated a couple of thousand partial copies
 * of the file on every repaint. Nothing failed; it was simply the first thing a profiler would ever
 * find.</p>
 *
 * <h3>Why not just parse UTF-16 instead</h3>
 * <p>Because the vendored binding cannot. {@code TSParser.parseStringEncoding} accepts
 * {@code TSInputEncodingUTF16LE}/{@code BE} and, measured against the Java grammar on 2026-08-12,
 * produces a byte length matching the <em>UTF-8</em> encoding and a tree containing {@code ERROR}
 * nodes — i.e. the string reaches the native side as UTF-8 whatever it is told. The clean fix is
 * upstream; until then the conversion stays and is merely made fast.</p>
 *
 * <h3>How</h3>
 * <ol>
 *   <li><b>ASCII fast path.</b> Checked once per parse. Source code is overwhelmingly ASCII, and when it
 *       is, both conversions are the identity and cost nothing.</li>
 *   <li><b>A per-line index otherwise.</b> Two {@code int}s per line — the UTF-8 and UTF-16 offset each
 *       line starts at. A conversion binary-searches the line, then walks within it. Lines are short, so
 *       this is O(log lines) plus a bounded scan, and the memory is two arrays sized by line count rather
 *       than by character count.</li>
 * </ol>
 */
final class Utf8Offsets {

    /** The empty document — identity conversions, no arrays. */
    static final Utf8Offsets EMPTY = new Utf8Offsets("");

    private final String text;

    /**
     * The document's length in UTF-8 bytes — a number, deliberately not the bytes.
     *
     * <p>This was {@code text.getBytes(UTF_8)}, a full document-sized copy built on every reparse to be
     * asked only for its length. On a 200KB file that is 200KB of garbage per keystroke, which is exactly
     * the kind of allocation that turns into a GC pause in the middle of typing.</p>
     */
    private final int utf8Length;

    /** True when every character is one byte, making both conversions the identity. */
    private final boolean ascii;

    /** Line starts, in each coordinate system. Null on the ASCII path — nothing would read them. */
    private final int[] lineUtf8Starts;
    private final int[] lineUtf16Starts;

    private Utf8Offsets(String text) {
        this.text = text;

        // One allocation-free pass to answer both "is this ASCII" and "how many bytes". A char scan is far
        // cheaper than encoding the document, and for the overwhelmingly common ASCII case it is all the
        // work there is.
        int bytes = 0;
        boolean allAscii = true;
        for (int i = 0; i < text.length(); i++) {
            int width = utf8LengthOf(text, i);
            bytes += width;
            if (width != 1) allAscii = false;
        }
        this.utf8Length = bytes;
        this.ascii = allAscii;

        if (ascii) {
            this.lineUtf8Starts = null;
            this.lineUtf16Starts = null;
            return;
        }

        // One pass, counting lines first so the arrays are allocated exactly once.
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;

        this.lineUtf8Starts = new int[lines];
        this.lineUtf16Starts = new int[lines];
        int line = 1;
        int utf8At = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            utf8At += utf8LengthOf(text, i);
            if (c == '\n') {
                lineUtf8Starts[line] = utf8At;
                lineUtf16Starts[line] = i + 1;
                line++;
            }
        }
    }

    static Utf8Offsets of(String text) {
        if (text == null || text.isEmpty()) return EMPTY;
        return new Utf8Offsets(text);
    }

    /**
     * How many UTF-8 bytes the character at {@code index} contributes.
     *
     * <p>A surrogate <em>pair</em> is four bytes total; this reports 4 for the high surrogate and 0 for
     * the low one, so summing over code units gives the right total either way round. An unpaired
     * surrogate is counted as 3, which is what the JDK's encoder emits for one (a replacement
     * character) — the point is that the two sides agree, not that lone surrogates are meaningful.</p>
     */
    private static int utf8LengthOf(String text, int index) {
        char c = text.charAt(index);
        if (c < 0x80) return 1;
        if (c < 0x800) return 2;
        if (Character.isHighSurrogate(c)) {
            return index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1)) ? 4 : 3;
        }
        if (Character.isLowSurrogate(c)) {
            return index > 0 && Character.isHighSurrogate(text.charAt(index - 1)) ? 0 : 3;
        }
        return 3;
    }

    /** UTF-16 index to UTF-8 byte offset, clamped to the document. */
    int toUtf8(int utf16Index) {
        int limit = clamp(utf16Index, text.length());
        if (ascii) return limit;

        int line = lineFor(lineUtf16Starts, limit);
        int utf8At = lineUtf8Starts[line];
        for (int i = lineUtf16Starts[line]; i < limit; i++) utf8At += utf8LengthOf(text, i);
        return utf8At;
    }

    /** UTF-8 byte offset back to UTF-16 index, clamped to the document. */
    int toUtf16(int byteOffset) {
        int limit = clamp(byteOffset, utf8Length);
        if (ascii) return limit;

        int line = lineFor(lineUtf8Starts, limit);
        int utf8At = lineUtf8Starts[line];
        int utf16At = lineUtf16Starts[line];
        while (utf8At < limit && utf16At < text.length()) {
            utf8At += utf8LengthOf(text, utf16At);
            utf16At++;
        }
        return utf16At;
    }

    /**
     * The row a position falls on, and the byte column within it — what a {@code TSPoint} wants.
     *
     * <p>Columns are counted in <b>bytes</b>, not characters. That is the same trap as the offsets and is
     * easier to miss, because a point with a character column is only wrong on lines that contain
     * non-ASCII before the position.</p>
     */
    int rowAt(int utf16Index) {
        int limit = clamp(utf16Index, text.length());
        if (!ascii) return lineFor(lineUtf16Starts, limit);
        int row = 0;
        for (int i = 0; i < limit; i++) if (text.charAt(i) == '\n') row++;
        return row;
    }

    int byteColumnAt(int utf16Index) {
        int limit = clamp(utf16Index, text.length());
        if (ascii) {
            int lastBreak = text.lastIndexOf('\n', limit - 1);
            return limit - (lastBreak + 1);
        }
        int line = lineFor(lineUtf16Starts, limit);
        return toUtf8(limit) - lineUtf8Starts[line];
    }

    /** Index of the greatest line start not after {@code position}. */
    private static int lineFor(int[] starts, int position) {
        int low = 0;
        int high = starts.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (starts[mid] <= position) low = mid;
            else high = mid - 1;
        }
        return low;
    }

    private static int clamp(int value, int limit) {
        return Math.max(0, Math.min(value, limit));
    }
}
