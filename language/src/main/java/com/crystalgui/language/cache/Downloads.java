package com.crystalgui.language.cache;

import com.crystalgui.core.async.Progress;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.List;

/**
 * <b>The way this project fetches things.</b> Describe a transfer, then open it or complete it.
 *
 * <pre>{@code
 * // A stream to read through something else.
 * try (Download open = Downloads.from(url).named("JDK sources").reporting(progress).open()) {
 *     read(open.stream());
 * }
 *
 * // A file, verified and installed atomically.
 * Downloads.from(url).named("Minecraft mappings").verifying(md5).reporting(progress).into(target);
 *
 * // Many files under ONE bar, with one aggregate total.
 * Downloads.batch(artifacts).named("Downloading Java engine (band 8)").reporting(progress).into(dir);
 * }</pre>
 *
 * <h3>Why a described transfer rather than a method with five parameters</h3>
 *
 * <p>The three consumers want three different endings — a stream through a tar reader, one verified file,
 * fifteen files under a single bar — from the same beginning. A method per ending takes the same
 * {@code (url, what, progress, md5)} tail each time and grows a fourth when something new appears; a
 * described transfer names each of those once and lets the ending be the thing that varies. It also reads
 * as a sentence at the call site, which is where somebody has to be able to tell what was asked for.</p>
 *
 * <p>Every {@code Request} is immutable and every wither returns a new one, so a caller can hold a
 * half-described transfer and finish it two ways without them interfering.</p>
 *
 * <h3>{@code java.net.http.HttpClient} is not an option here</h3>
 *
 * <p>Not a bytecode-target question — the class is <b>absent at runtime</b>. A 1.7.10 client runs on Java
 * 8, where {@code java.net.http} does not exist, so a build that compiled against it would fail on the one
 * host this code exists for. {@code URLConnection} is what is available and it is sufficient.</p>
 */
public final class Downloads {

    /** Fifteen seconds each for connect and read. Long enough for a slow mirror, short enough to give up. */
    public static final int TIMEOUT_MILLIS = 15_000;

    /**
     * How many times a transfer is tried before it is reported as failed.
     *
     * <p>Three, and it is only worth having <b>because of resume</b>: without one, a retry re-downloads
     * everything and the second attempt is as likely to be interrupted as the first, so three attempts
     * at 110 MB is a way to spend a quarter of an hour failing. With the {@code .part} kept, each attempt
     * starts where the last stopped, which turns a flaky connection from fatal into slow.</p>
     */
    public static final int ATTEMPTS = 3;

    /** Multiplied by the attempt number, so 1s then 2s. Long enough to outlast a blip, short enough to wait. */
    public static final long RETRY_BACKOFF_MILLIS = 1000L;

    /**
     * A failure worth trying again, or one that will answer the same way in two seconds.
     *
     * <p><b>An unknown host is not a blip.</b> It means DNS could not resolve the name at all, which on a
     * machine with no network is the immediate and permanent answer — retrying it three times spends the
     * backoff re-asking a question that cannot change that fast, and delays the report of a failure the
     * user can see for themselves. Observed offline: the whole command failed correctly and took three
     * seconds longer than it needed to.</p>
     *
     * <p>A timeout, a reset, a truncated body and a 5xx are the opposite — those are what retrying is
     * for, and they are the reason the loop exists at all.</p>
     */
    private static boolean worthRetrying(IOException failure) {
        if (failure instanceof java.net.UnknownHostException) return false;
        // A 404 is an answer, and it will be the same answer next time.
        return !(failure instanceof java.io.FileNotFoundException);
    }

    /**
     * A failure in words somebody can act on, rather than a class name.
     *
     * <p>Offline, the balloon read <i>"Could not download JDK sources —
     * java.net.UnknownHostException: api.adoptium.net"</i>. Every part of that after the dash is for
     * whoever is debugging, and the person reading it wanted to know their connection was off. The
     * exception's own {@code toString} stays on the stderr line, where debugging happens.</p>
     */
    public static String describe(Throwable failure) {
        if (failure == null) return "unknown error";
        if (failure instanceof java.net.UnknownHostException) {
            return "could not reach " + failure.getMessage() + " — check your connection";
        }
        if (failure instanceof java.net.SocketTimeoutException) return "the connection timed out";
        if (failure instanceof java.io.FileNotFoundException) {
            return "the server does not have it any more";
        }
        if (failure instanceof java.io.InterruptedIOException) return "stopped";
        if (failure instanceof java.net.ConnectException) return "the connection was refused";
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    private static void sleep(long millis) throws java.io.InterruptedIOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // THE FLAG IS RESTORED AND THE WAIT IS ABANDONED. Swallowing this is how a job that was asked
            // to stop keeps going, which is the defect the cancel work in this same file just fixed.
            throw new java.io.InterruptedIOException("interrupted while waiting to retry");
        }
    }

    private Downloads() {
    }

    /** One artifact in a {@link Batch}: what to call it, where it is, and what it should hash to. */
    public record Artifact(String fileName, String url, String md5) {
    }

    // ── Describing one transfer ─────────────────────────────────────────────────────────────────

    /** A transfer of {@code url}, undescribed. Add what it is called and where to report it. */
    public static Request from(String url) {
        return new Request(url, "Downloading", null, Progress.NONE, NEVER_CANCELLED);
    }

    /** The default: a transfer nobody can stop, which is what an unattended one is. */
    private static final java.util.function.BooleanSupplier NEVER_CANCELLED = () -> false;

    /**
     * An immutable description of one transfer.
     *
     * <p>Nothing has happened when you hold one of these; {@link #open} and {@link #into} are the two
     * things that touch the network.</p>
     */
    public static final class Request {

        private final String url;
        private final String what;
        private final String md5;
        private final Progress progress;
        private final java.util.function.BooleanSupplier cancelled;

        private Request(String url, String what, String md5, Progress progress,
                        java.util.function.BooleanSupplier cancelled) {
            this.url = url;
            this.what = what;
            this.md5 = md5;
            this.progress = progress;
            this.cancelled = cancelled;
        }

        /** The line the chrome shows, present tense — {@code "Downloading engine band 17"}. */
        public Request named(String what) {
            return new Request(url, what, md5, progress, cancelled);
        }

        /** Where to report. Defaults to {@link Progress#NONE}, which is a real answer, not a stub. */
        public Request reporting(Progress progress) {
            return new Request(url, what, md5, progress == null ? Progress.NONE : progress, cancelled);
        }

        /**
         * When to give up — polled as the bytes go past.
         *
         * <p><b>Cancellation is cooperative, and a transfer that never asks cannot be cancelled.</b> The
         * scheduler's × marked the job cancelled and discarded its result, exactly as designed, while the
         * download ran happily to completion — so pressing it did nothing observable and read as a dead
         * button. {@code JobContext} says this in as many words: <i>"Work that never polls is not wrong,
         * merely uninterruptible."</i> A 110 MB transfer is not something to leave uninterruptible.</p>
         */
        public Request cancelledWhen(java.util.function.BooleanSupplier cancelled) {
            return new Request(url, what, md5, progress,
                    cancelled == null ? NEVER_CANCELLED : cancelled);
        }

        /**
         * The expected digest.
         *
         * <p>Null — the default — is a real state rather than a gap: it is what {@code CacheFiles} means
         * by "any non-empty file will do", and it is the honest posture wherever upstream publishes no
         * digest to pin, which is where the MCP mapping data still is.</p>
         */
        public Request verifying(String md5) {
            return new Request(url, what, md5, progress, cancelled);
        }

        /** Opens it. The caller reads {@link Download#stream()} and closes the {@link Download}. */
        public Download open() throws IOException {
            return Download.start(url, what, progress, cancelled);
        }

        /**
         * Fetches the whole thing into {@code target}, verified and installed atomically.
         *
         * @return whether the file is now valid there; false means the digest did not match and nothing
         *         was installed, which is a caller's cue to report rather than to retry in a loop
         */
        public boolean into(Path target) throws IOException {
            IOException last = null;
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                if (cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("cancelled");
                // WHAT SURVIVED THE LAST ATTEMPT. The `.part` was always kept across a crash so the next
                // launch could overwrite it; asking how big it is turns that into a resume for free.
                long have = CacheFiles.partialSize(target);
                try (Download download = Download.start(url, what, progress, cancelled, have)) {
                    return CacheFiles.install(target, download.stream(), md5, download.resumed());
                } catch (java.io.InterruptedIOException stopped) {
                    // ASKED TO STOP is not a failure to retry -- and the .part stays, so resuming later
                    // costs nothing.
                    throw stopped;
                } catch (IOException failed) {
                    last = failed;
                    if (attempt == ATTEMPTS || !worthRetrying(failed)) break;
                    sleep(RETRY_BACKOFF_MILLIS * attempt);
                }
            }
            throw last;
        }
    }

    // ── Many transfers, one bar ─────────────────────────────────────────────────────────────────

    /** Several artifacts into one directory, under a single aggregate bar. */
    public static Batch batch(List<Artifact> artifacts) {
        return new Batch(artifacts, "Downloading", Progress.NONE, NEVER_CANCELLED);
    }

    /**
     * A set of artifacts fetched as one reported unit.
     *
     * <h3>Why a batch is its own shape and not a loop over {@link Request}</h3>
     *
     * <p>Fifteen jars fetched in a loop is fifteen bars, each starting at zero — which tells a user
     * nothing about how long the whole thing takes, and flickers. A batch sums the sizes first and reports
     * <b>one</b> total, with each artifact's name on the detail line as it goes past. The sizes cost a
     * {@code HEAD} apiece, which is the price of a bar that means something.</p>
     *
     * <p><b>All or nothing.</b> A partial set of engine jars is worse than none — it loads and then fails
     * on a class nobody can explain — so a failure abandons the batch and reports which artifact stopped
     * it. Nothing already installed is removed: {@code CacheFiles} makes each one atomic, so a retry
     * resumes rather than restarting.</p>
     */
    public static final class Batch {

        private final List<Artifact> artifacts;
        private final String what;
        private final Progress progress;
        private final java.util.function.BooleanSupplier cancelled;

        private Batch(List<Artifact> artifacts, String what, Progress progress,
                      java.util.function.BooleanSupplier cancelled) {
            this.artifacts = artifacts;
            this.what = what;
            this.progress = progress;
            this.cancelled = cancelled;
        }

        public Batch named(String what) {
            return new Batch(artifacts, what, progress, cancelled);
        }

        public Batch reporting(Progress progress) {
            return new Batch(artifacts, what, progress == null ? Progress.NONE : progress, cancelled);
        }

        /** @see Request#cancelledWhen */
        public Batch cancelledWhen(java.util.function.BooleanSupplier cancelled) {
            return new Batch(artifacts, what, progress,
                    cancelled == null ? NEVER_CANCELLED : cancelled);
        }

        /**
         * What a batch did.
         *
         * @param installed how many artifacts are now valid on disk
         * @param total     how many were asked for
         * @param failure   the first artifact that could not be fetched, or null — named rather than
         *                  counted, because "3 of 15 failed" sends somebody looking and "stopped at
         *                  ecj-3.26.0.jar" tells them where
         */
        public record Result(int installed, int total, String failure, boolean cancelled) {

            Result(int installed, int total, String failure) {
                this(installed, total, failure, false);
            }

            /** Asked to stop, which is not the same as unable to continue. */
            static Result cancelled(int installed, int total) {
                return new Result(installed, total, null, true);
            }

            public boolean complete() {
                return !cancelled && failure == null && installed == total;
            }

            @Override
            public String toString() {
                if (cancelled) return "cancelled after " + installed + " of " + total;
                return complete() ? installed + " of " + total
                        : installed + " of " + total + ", stopped at " + failure;
            }
        }

        /** Fetches every artifact into {@code directory}, stopping at the first that will not come. */
        public Result into(Path directory) {
            // THE SIZES FIRST, so the bar has a total. A HEAD apiece is the cost of one honest bar over
            // fifteen files instead of fifteen bars that each start again at zero.
            long total = 0;
            for (Artifact artifact : artifacts) {
                long length = lengthOf(artifact.url());
                if (length > 0) total += length;
            }
            progress.begin(what, total > 0 ? total : -1, Progress.Unit.BYTES);

            long done = 0;
            int installed = 0;
            for (Artifact artifact : artifacts) {
                // BETWEEN ARTIFACTS AS WELL AS INSIDE THEM: a batch that only polled inside a transfer
                // would still start the next one after being told to stop.
                // CANCELLED IS ITS OWN STATE, not a failure whose "artifact name" is the word cancelled.
                // That is what the field said a moment ago, and it put a thing that is not an artifact
                // into the slot that names one -- so anything reading `failure` to say WHICH jar stopped
                // it would have reported a jar called "cancelled".
                if (cancelled.getAsBoolean()) return Result.cancelled(installed, artifacts.size());
                progress.detail(artifact.fileName());
                Path target = directory.resolve(artifact.fileName());
                try {
                    if (!CacheFiles.isValid(target, artifact.md5())
                            && !from(artifact.url()).verifying(artifact.md5())
                                    .named(what).reporting(Progress.NONE)
                                    .cancelledWhen(cancelled).into(target)) {
                        return new Result(installed, artifacts.size(), artifact.fileName());
                    }
                } catch (IOException | RuntimeException unavailable) {
                    return new Result(installed, artifacts.size(), artifact.fileName());
                }
                installed++;
                // AGGREGATE, and stepped per FILE rather than per chunk: an inner transfer reporting into
                // the same Progress would reset the bar to its own size fifteen times.
                try {
                    done += java.nio.file.Files.size(target);
                } catch (IOException unreadable) {
                    // Installed but unmeasurable is not a failure of the batch; the bar just does not move.
                }
                progress.advance(done);
            }
            return new Result(installed, artifacts.size(), null);
        }
    }

    // ── The raw connection, which the shapes above are built on ─────────────────────────────────

    /**
     * Opens {@code url} for reading, following redirects.
     *
     * <p>Redirects followed because Maven Central and Forge's raw-content host both use them, and a
     * downloader that refused would fail with a 30x nobody would think to look for.</p>
     *
     * <p><b>Both a connect and a read timeout</b>, because only setting the first leaves a stalled-mid-body
     * transfer hanging for ever — which on a game client is indistinguishable from a freeze, and is the
     * failure people actually hit rather than a refused connection.</p>
     */
    public static InputStream open(String url) throws IOException {
        return fetch(url).stream();
    }

    /**
     * The stream <b>and</b> the length the same response declared.
     *
     * <p>Because asking separately costs a second round trip and, on a host that is simply not answering,
     * a second full timeout — thirty seconds of a client showing nothing before it can even report that
     * it failed. The length is on the response we are already reading; there is no reason to ask twice.</p>
     */
    public static Body fetch(String url) throws IOException {
        return fetch(url, 0L);
    }

    /**
     * The same, asking the server to continue from {@code from}.
     *
     * <p>{@link Body#resumed()} says whether it agreed. <b>A server that ignores the range is not an
     * error</b> — it answers 200 with the whole body, which is a correct response to a request it chose
     * not to honour, and the caller simply starts the file again. Treating that as a failure would make
     * resume a thing that breaks downloads on the hosts that do not support it.</p>
     */
    public static Body fetch(String url, long from) throws IOException {
        URLConnection connection = connect(url);
        if (from > 0) connection.setRequestProperty("Range", "bytes=" + from + "-");
        InputStream stream = connection.getInputStream();
        boolean resumed = from > 0 && connection instanceof HttpURLConnection
                && ((HttpURLConnection) connection).getResponseCode() == 206;
        long length = connection.getContentLengthLong();
        // THE TOTAL, NOT THE REMAINDER. A 206 declares only what it is about to send, so a resumed
        // transfer would otherwise report a bar sized to the tail and a percentage that starts at zero
        // three-quarters of the way through the file.
        if (resumed && length >= 0) length += from;
        return new Body(stream, length, resumed);
    }

    /** An open response: what to read, and how much of it there is. */
    public static final class Body implements java.io.Closeable {

        private final InputStream stream;
        private final long length;
        private final boolean resumed;

        Body(InputStream stream, long length) {
            this(stream, length, false);
        }

        Body(InputStream stream, long length, boolean resumed) {
            this.stream = stream;
            this.length = length;
            this.resumed = resumed;
        }

        /** Whether the server honoured a range request and this is a continuation. */
        public boolean resumed() {
            return resumed;
        }

        public InputStream stream() {
            return stream;
        }

        /** Negative when the response declared none — a chunked reply, which is an ordinary answer. */
        public long length() {
            return length;
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    /**
     * The declared length of {@code url}'s body, or a negative number.
     *
     * <p>Negative is the ordinary answer rather than a failure: a chunked response has no length, and
     * {@code -1} is exactly what {@link Progress#begin} takes to mean indeterminate — so a caller passes
     * it straight through. Prefer {@link #fetch} where the body is wanted too; this exists for
     * {@link Batch}, which has to total up sizes it is not yet ready to read.</p>
     */
    public static long lengthOf(String url) {
        try {
            URLConnection connection = connect(url);
            if (connection instanceof HttpURLConnection http) {
                http.setRequestMethod("HEAD");
                long length = http.getContentLengthLong();
                http.disconnect();
                return length;
            }
            return connection.getContentLengthLong();
        } catch (IOException | RuntimeException unavailable) {
            // Not knowing the size is not a failure to download -- it is a sweep instead of a bar.
            return -1L;
        }
    }

    private static URLConnection connect(String url) throws IOException {
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        if (connection instanceof HttpURLConnection http) {
            http.setInstanceFollowRedirects(true);
        }
        return connection;
    }
}
