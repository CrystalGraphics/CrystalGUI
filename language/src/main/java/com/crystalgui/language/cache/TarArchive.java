package com.crystalgui.language.cache;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * A minimal reader for {@code .tar.gz}, because the JDK ships one for {@code .zip} and not for this.
 *
 * <h3>Why this exists at all</h3>
 *
 * <p>M13 §25.5 fetches OpenJDK's own source archive, and every upstream that publishes one publishes it
 * as a gzipped tar — {@code src.zip} is a file <em>inside</em> a JDK installation and is not separately
 * downloadable from anywhere. So the choice was between eighty lines of a thirty-year-old format and
 * fetching a 190 MB JDK to read one entry out of it.</p>
 *
 * <h3>What it implements, and what it deliberately does not</h3>
 *
 * <p>Regular files and directories under ustar, including the {@code prefix} field that carries paths
 * over 100 characters, and GNU's {@code L} long-name record. Everything else — links, devices, sparse
 * files, and pax {@code x}/{@code g} extended headers — is <b>skipped</b> rather than interpreted: the
 * archives this reads hold source files and nothing else, and a pax header is always followed by an
 * ordinary ustar header carrying a usable (if occasionally truncated) name.</p>
 *
 * <p>It is a forward-only reader by construction, which is what a tar is. There is no index — the format
 * has none — so a caller takes what it wants as the entries go past.</p>
 */
public final class TarArchive implements Closeable {

    /** Every tar field is a multiple of this, including the two zero blocks that end the archive. */
    private static final int BLOCK = 512;

    private final DataInputStream in;
    /** Bytes of the current entry not yet read, so {@link #next()} can skip to the next header. */
    private long remaining;
    /** A GNU long-name record applies to the entry AFTER it. */
    private String pendingName;
    private boolean ended;

    private TarArchive(InputStream in) {
        this.in = new DataInputStream(in);
    }

    /** Over a gzipped stream, which is the only way anybody ships one of these. */
    public static TarArchive gzip(InputStream compressed) throws IOException {
        return new TarArchive(new GZIPInputStream(compressed, 65536));
    }

    /** One entry. Content is available from {@link TarArchive#read()} until {@link #next()} is called. */
    public static final class Entry {
        private final String name;
        private final long size;
        private final boolean file;

        Entry(String name, long size, boolean file) {
            this.name = name;
            this.size = size;
            this.file = file;
        }

        public String name() {
            return name;
        }

        public long size() {
            return size;
        }

        public boolean isFile() {
            return file;
        }
    }

    /**
     * The next entry, or null at the end of the archive.
     *
     * <p>Skips whatever of the previous entry was not read, so a caller may take one file in a hundred
     * without tracking offsets itself.</p>
     */
    public Entry next() throws IOException {
        if (ended) return null;
        skipFully(remaining + padding(remaining));
        remaining = 0;

        while (true) {
            byte[] header = new byte[BLOCK];
            try {
                in.readFully(header);
            } catch (EOFException truncated) {
                // A TRUNCATED ARCHIVE IS AN ORDINARY OUTCOME of an interrupted download, and the caller
                // above verifies a digest anyway. Ending here rather than throwing means a partial fetch
                // reports "nothing usable" instead of a stack trace.
                ended = true;
                return null;
            }
            if (isZeroBlock(header)) {
                ended = true;
                return null;
            }

            long size = octal(header, 124, 12);
            char type = (char) (header[156] & 0xFF);
            String name = nameOf(header);

            if (type == 'L') {
                // GNU long name: this record's CONTENT is the next entry's name.
                byte[] bytes = new byte[(int) Math.min(size, 8192)];
                in.readFully(bytes);
                skipFully(size - bytes.length + padding(size));
                pendingName = trimNul(new String(bytes, StandardCharsets.UTF_8));
                continue;
            }
            if (pendingName != null) {
                name = pendingName;
                pendingName = null;
            }
            if (type == 'x' || type == 'g') {
                // Pax extended header. The ustar header that follows carries the name we can use.
                skipFully(size + padding(size));
                continue;
            }

            boolean file = type == '0' || type == '\0';
            remaining = file ? size : 0;
            if (!file) skipFully(size + padding(size));
            return new Entry(name, size, file);
        }
    }

    /** The current entry's bytes. Reading it twice answers empty the second time — a tar is one pass. */
    public byte[] read() throws IOException {
        if (remaining <= 0) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(remaining, 1 << 20));
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int want = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, want);
            if (read < 0) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toByteArray();
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ── The format ──────────────────────────────────────────────────────────────────────────────

    /** {@code prefix} + {@code /} + {@code name}, which is how ustar spells a path over 100 characters. */
    private static String nameOf(byte[] header) {
        String name = trimNul(new String(header, 0, 100, StandardCharsets.UTF_8));
        String prefix = trimNul(new String(header, 345, 155, StandardCharsets.UTF_8));
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    /**
     * A tar number is ASCII octal, {@code NUL}- or space-padded — and the padding is not consistent
     * between writers, so both are trimmed rather than one.
     */
    private static long octal(byte[] header, int offset, int length) {
        long value = 0;
        for (int at = offset; at < offset + length; at++) {
            int c = header[at] & 0xFF;
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7') return value;
            value = value * 8 + (c - '0');
        }
        return value;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static String trimNul(String text) {
        int end = text.indexOf('\0');
        return (end < 0 ? text : text.substring(0, end)).trim();
    }

    /** Content is padded out to a whole block. */
    private static long padding(long size) {
        long over = size % BLOCK;
        return over == 0 ? 0 : BLOCK - over;
    }

    /** {@code InputStream.skip} may do less than asked and answer honestly; a loop is the contract. */
    private void skipFully(long count) throws IOException {
        long left = count;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                skipped = 1;
            }
            left -= skipped;
        }
    }
}
