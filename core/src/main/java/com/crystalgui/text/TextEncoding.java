package com.crystalgui.text;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * How a file's bytes became text, and how they go back — <b>charset, byte-order mark, and the NUL sniff
 * that decides whether it is text at all</b>.
 *
 * <h3>None of this existed</h3>
 *
 * <p>A BOM is stripped on the way in and written back on the way out, recorded
 * and re-emitted, and it was never built — there is no reference to a byte-order mark anywhere in the
 * filesystem, the document layer or the text package. Every read was
 * {@code new String(bytes, UTF_8)} and every save {@code getBytes(UTF_8)}, so a UTF-8 file with a BOM
 * opened with a stray {@code U+FEFF} as its first character, which shows as an invisible glyph before
 * the first word and breaks anything that parses from offset zero. Saving then wrote it back as three
 * more bytes of content rather than as a mark.</p>
 *
 * <h3>Text or binary is a sniff, never an extension</h3>
 *
 * <p>An extension is a claim by whoever named the file. A NUL byte in the first few kilobytes is
 * evidence, and it is what git, VS Code and every diff tool use — a file with no extension at all is
 * usually text, and a {@code .dat} that happens to be JSON is text too. The window is bounded because
 * the answer has to be cheap enough to ask before deciding how to open something.</p>
 */
public final class TextEncoding {

    /** How far in to look for a NUL before concluding a file is text. Git uses 8000; so do we. */
    public static final int SNIFF_BYTES = 8000;

    /** What a file with no mark and no declared charset is read as. */
    public static final Charset DEFAULT = StandardCharsets.UTF_8;

    private final Charset charset;
    private final boolean byteOrderMark;

    private TextEncoding(Charset charset, boolean byteOrderMark) {
        this.charset = charset;
        this.byteOrderMark = byteOrderMark;
    }

    /** UTF-8, no mark — what a new document is and what a file with nothing to say is read as. */
    public static final TextEncoding UTF_8 = new TextEncoding(StandardCharsets.UTF_8, false);

    public static TextEncoding of(Charset charset, boolean byteOrderMark) {
        return new TextEncoding(charset == null ? DEFAULT : charset, byteOrderMark);
    }

    public Charset charset() {
        return charset;
    }

    /** Whether the file began with a mark, and therefore whether a save writes one back. */
    public boolean hasByteOrderMark() {
        return byteOrderMark;
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    /**
     * What these bytes are encoded as, read off the mark alone.
     *
     * <p>Only a mark is evidence. Guessing a charset from the byte distribution is a whole discipline
     * with a real error rate, and getting it wrong writes somebody's file back in the wrong encoding —
     * so the absence of a mark means UTF-8, which is what every file this engine has ever written is,
     * and a host that knows better sets it explicitly.</p>
     */
    public static TextEncoding sniff(byte[] bytes) {
        if (bytes == null) return UTF_8;
        if (startsWith(bytes, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF)) {
            return new TextEncoding(StandardCharsets.UTF_8, true);
        }
        if (startsWith(bytes, (byte) 0xFF, (byte) 0xFE)) {
            return new TextEncoding(StandardCharsets.UTF_16LE, true);
        }
        if (startsWith(bytes, (byte) 0xFE, (byte) 0xFF)) {
            return new TextEncoding(StandardCharsets.UTF_16BE, true);
        }
        return UTF_8;
    }

    /**
     * Whether these bytes look like something a person would edit as text.
     *
     * <p>A NUL in the first {@link #SNIFF_BYTES} means no — with the exception of a UTF-16 file, whose
     * mark says outright that every other byte of ASCII content is a NUL and which is text.</p>
     */
    public static boolean looksBinary(@Nullable byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        TextEncoding marked = sniff(bytes);
        if (marked.charset != StandardCharsets.UTF_8) return false;
        int limit = Math.min(bytes.length, SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    /** The text these bytes hold, with the mark consumed rather than left in the first character. */
    public String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        int offset = byteOrderMark ? markLength() : 0;
        if (offset >= bytes.length) return "";
        return new String(bytes, offset, bytes.length - offset, charset);
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────

    /** These bytes back, mark and all. What a save writes. */
    public byte[] encode(String text) {
        byte[] body = (text == null ? "" : text).getBytes(charset);
        if (!byteOrderMark) return body;
        byte[] mark = mark();
        byte[] out = new byte[mark.length + body.length];
        System.arraycopy(mark, 0, out, 0, mark.length);
        System.arraycopy(body, 0, out, mark.length, body.length);
        return out;
    }

    private int markLength() {
        return mark().length;
    }

    private byte[] mark() {
        if (charset == StandardCharsets.UTF_16LE) return new byte[]{(byte) 0xFF, (byte) 0xFE};
        if (charset == StandardCharsets.UTF_16BE) return new byte[]{(byte) 0xFE, (byte) 0xFF};
        return new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    }

    private static boolean startsWith(byte[] bytes, byte... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) return false;
        }
        return true;
    }

    /** The readout: {@code UTF-8}, or {@code UTF-8 with BOM}. VS Code's status bar says exactly this. */
    @Override
    public String toString() {
        return byteOrderMark ? charset.name() + " with BOM" : charset.name();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TextEncoding that)) return false;
        return byteOrderMark == that.byteOrderMark && charset.equals(that.charset);
    }

    @Override
    public int hashCode() {
        return charset.hashCode() * 31 + (byteOrderMark ? 1 : 0);
    }
}
