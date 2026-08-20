package com.crystalgui.language.cache;

import com.crystalgui.core.async.Progress;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * <b>One transfer, reporting itself.</b> Open it, read the stream, close it.
 *
 * <h3>What this exists to stop being written a fourth time</h3>
 *
 * <p>Three consumers fetch things — the MCP mapping data, the engine bands, and M13 §25.5's JDK source
 * extract — and each had grown its own copy of the same five steps: open with timeouts and redirects, ask
 * how big it is, announce it, count the bytes going past, and report them. {@link JdkSourceExtract} had a
 * private {@code Counting} stream of its own; the next consumer would have had a second one.</p>
 *
 * <p>Every one of those steps has a way to get it subtly wrong, and this class is where each is decided
 * once:</p>
 *
 * <h3>Announce before connecting, then retarget</h3>
 *
 * <p>The size is not known until the server has answered, and <b>the connect is the part most likely to
 * hang</b>. A downloader that waits for the length before calling {@link Progress#begin} shows nothing at
 * all for as long as the network takes to refuse — which is exactly the stall the progress channel exists
 * to report. Reported from a real client as "I ran the command and nothing happened", twice. So: begin
 * indeterminate, connect, then begin again with the total.</p>
 *
 * <h3>One request, not two</h3>
 *
 * <p>The length comes off the response being read rather than a preceding {@code HEAD}. Asking separately
 * costs a second round trip and, against a host that is simply not answering, a second full timeout —
 * thirty seconds before a client can even say it failed.</p>
 *
 * <h3>Reports are rate-limited at the source</h3>
 *
 * <p>{@link com.crystalgui.core.async.ProgressState} allocates per report and its javadoc asks callers to
 * accumulate rather than report per chunk. An 8 KB read loop over 50 MB is six thousand allocations
 * feeding a bar that redraws sixty times a second. {@link #REPORT_EVERY_BYTES} is where that is paid
 * once, so no consumer has to remember it.</p>
 */
public final class Download implements Closeable {

    /**
     * How much has to go past before it is worth saying so.
     *
     * <p>64 KB: on a slow connection that is a report every second or two, and on a fast one a few hundred
     * for a whole transfer — under one per frame either way, which is the rate the chrome can actually
     * show.</p>
     */
    static final int REPORT_EVERY_BYTES = 64 * 1024;

    private final Downloads.Body body;
    private final InputStream stream;

    private Download(Downloads.Body body, InputStream stream) {
        this.body = body;
        this.stream = stream;
    }

    /**
     * Opens {@code url}, announcing it as {@code what} on {@code progress}.
     *
     * <p>Reached through {@code Downloads.from(url).named(what).reporting(progress).open()}, which is the
     * described form and the one a call site should read as. This is what that resolves to.</p>
     */
    static Download start(String url, String what, Progress progress,
                          java.util.function.BooleanSupplier cancelled) throws IOException {
        // BEFORE THE CONNECT, and indeterminate because nothing knows the size yet. This ordering is the
        // whole reason the class exists -- see the header.
        progress.begin(what, -1, Progress.Unit.BYTES);
        Downloads.Body body = Downloads.fetch(url);
        if (body.length() > 0) progress.begin(what, body.length(), Progress.Unit.BYTES);
        return new Download(body, new Counting(body.stream(), progress, cancelled));
    }

    /** The bytes, counted and reported as they are read. */
    public InputStream stream() {
        return stream;
    }

    /** What the response said it was, or negative — a chunked reply has no length and that is ordinary. */
    public long length() {
        return body.length();
    }

    @Override
    public void close() throws IOException {
        body.close();
    }

    /**
     * Counts bytes past and reports on a threshold.
     *
     * <p>Absolute, because {@link Progress#advance} is absolute — a delta here and a total there is the
     * shape that produces a bar reading 4% of a finished download.</p>
     */
    private static final class Counting extends FilterInputStream {

        private final Progress progress;
        private final java.util.function.BooleanSupplier cancelled;
        private long read;
        private long reportedAt;

        Counting(InputStream in, Progress progress, java.util.function.BooleanSupplier cancelled) {
            super(in);
            this.progress = progress;
            this.cancelled = cancelled;
        }

        @Override
        public int read() throws IOException {
            int one = super.read();
            if (one >= 0) advance(1);
            return one;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) advance(count);
            return count;
        }

        private void advance(int count) throws IOException {
            read += count;
            if (read - reportedAt < REPORT_EVERY_BYTES) return;
            reportedAt = read;
            progress.advance(read);
            // ASKED ON THE SAME THRESHOLD AS THE REPORT, so a cancel is noticed within 64 KB rather than
            // per byte. An InterruptedIOException rather than a bespoke type: the caller already catches
            // IOException to mean "did not arrive", which is exactly what a stopped transfer is.
            if (cancelled.getAsBoolean()) {
                throw new java.io.InterruptedIOException("download cancelled");
            }
        }
    }
}
