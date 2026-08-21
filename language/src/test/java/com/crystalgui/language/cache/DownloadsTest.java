package com.crystalgui.language.cache;

import com.crystalgui.core.async.Progress;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The transfer's own behaviour: stopping, resuming, retrying, and installing.
 *
 * <h3>Why this exists at all</h3>
 *
 * <p>Cancellation shipped untested. It was wired, documented and reported working, and the only thing
 * standing behind it was somebody clicking the × in a client — which is exactly the position the feature
 * was in <em>before</em> the fix, when the button was equally convincing and did nothing. A capability
 * whose whole point is that it interrupts something is not one to leave to a screenshot.</p>
 *
 * <p>Driven over {@code file:} URLs, which {@code URLConnection} serves like any other. That reaches
 * everything except the HTTP status codes — the one thing a local file cannot express is a {@code 206},
 * so {@link #aResumedTransferAppendsRatherThanRestarting} drives the append path through
 * {@link CacheFiles} directly and says so rather than pretending otherwise.</p>
 */
public class DownloadsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File sourceOf(int bytes) throws IOException {
        File source = folder.newFile("payload-" + bytes + ".bin");
        byte[] content = new byte[bytes];
        for (int at = 0; at < bytes; at++) content[at] = (byte) (at % 251);
        Files.write(source.toPath(), content);
        return source;
    }

    /**
     * <b>A cancelled transfer stops, and installs nothing.</b>
     *
     * <p>Both halves matter. Stopping is the point; installing nothing is what stops a half-file being
     * mistaken for a whole one by the next launch, and it is the part that would still have been wrong
     * had the flag merely broken the read loop.</p>
     */
    @Test
    public void aCancelledTransferStopsAndInstallsNothing() throws Exception {
        File source = sourceOf(4 * 1024 * 1024);
        Path target = folder.getRoot().toPath().resolve("out.bin");
        AtomicLong seen = new AtomicLong();

        try {
            Downloads.from(source.toURI().toString())
                    .named("Test")
                    // AFTER SOME OF IT HAS ARRIVED, not immediately: cancelling before the first byte
                    // would pass against an implementation that only checks once, up front.
                    .cancelledWhen(() -> seen.incrementAndGet() > 3)
                    .reporting(counting(seen))
                    .into(target);
            fail("a cancelled transfer must not report success");
        } catch (InterruptedIOException stopped) {
            // The expected shape: the caller already treats IOException as "did not arrive".
        }

        assertFalse("nothing may be installed from a transfer that was stopped", Files.exists(target));
    }

    /** A cancel asked for before anything starts is honoured too, and without opening a connection. */
    @Test
    public void cancellingBeforeItStartsIsHonoured() throws Exception {
        File source = sourceOf(64 * 1024);
        Path target = folder.getRoot().toPath().resolve("never.bin");
        try {
            Downloads.from(source.toURI().toString()).named("Test")
                    .cancelledWhen(() -> true).into(target);
            fail("expected to be refused");
        } catch (InterruptedIOException expected) {
            assertFalse(Files.exists(target));
        }
    }

    /**
     * <b>A retry resumes from the {@code .part} rather than starting again.</b>
     *
     * <p>Which is the whole reason retrying is worth having: without it, attempt two of a 110 MB transfer
     * is as likely to be interrupted as attempt one, and three attempts is a way to spend a quarter of an
     * hour failing. The {@code .part} was always kept across a crash so the next launch could overwrite
     * it; this turns that into a resume for nothing.</p>
     *
     * <p>The {@code 206} itself is the one thing a {@code file:} URL cannot produce, so the append is
     * driven through {@link CacheFiles} — the half that decides what happens to the bytes.</p>
     */
    @Test
    public void aResumedTransferAppendsRatherThanRestarting() throws Exception {
        Path target = folder.getRoot().toPath().resolve("resumed.bin");
        Path part = CacheFiles.partOf(target);
        Files.write(part, "first-half-".getBytes(StandardCharsets.UTF_8));

        assertEquals("the partial is what a resume would ask to continue from",
                11L, CacheFiles.partialSize(target));

        boolean installed = CacheFiles.install(target,
                new java.io.ByteArrayInputStream("second-half".getBytes(StandardCharsets.UTF_8)),
                null, true);

        assertTrue(installed);
        assertEquals("first-half-second-half",
                new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    /** And without the append flag the same call replaces, which is what a server that refuses a range gets. */
    @Test
    public void aTransferThatCannotResumeStartsAgain() throws Exception {
        Path target = folder.getRoot().toPath().resolve("restarted.bin");
        Files.write(CacheFiles.partOf(target), "stale".getBytes(StandardCharsets.UTF_8));

        CacheFiles.install(target,
                new java.io.ByteArrayInputStream("whole".getBytes(StandardCharsets.UTF_8)), null, false);

        assertEquals("whole", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    /**
     * <b>A digest that does not match installs nothing and leaves no {@code .part} behind.</b>
     *
     * <p>The second half is what makes a retry safe: a partial left by a failed verification would be
     * resumed from on the next attempt, so every subsequent try would be appending to bytes already known
     * to be wrong.</p>
     */
    @Test
    public void aWrongDigestInstallsNothingAndLeavesNothingToResumeFrom() throws Exception {
        File source = sourceOf(1024);
        Path target = folder.getRoot().toPath().resolve("mismatch.bin");

        boolean installed = Downloads.from(source.toURI().toString())
                .named("Test").verifying("00000000000000000000000000000000").into(target);

        assertFalse(installed);
        assertFalse(Files.exists(target));
        assertFalse("a rejected partial must not become the base of the next attempt",
                Files.exists(CacheFiles.partOf(target)));
    }

    /** Git's own blob hash, which is what pins the MCP mapping files. */
    @Test
    public void aGitBlobDigestIsCheckedAsGitComputesIt() throws Exception {
        Path file = folder.getRoot().toPath().resolve("hello.txt");
        Files.write(file, "hello\n".getBytes(StandardCharsets.UTF_8));

        // `git hash-object` of "hello\n" — sha1("blob 6\0hello\n"). A fixed, checkable constant rather
        // than one this test computes with the code under test.
        String known = "ce013625030ba8dba906f756967f9e9ca394464a";
        assertEquals(known, CacheFiles.gitBlobSha1(file));
        assertTrue(CacheFiles.isValid(file, "gitblob:" + known));
        assertFalse(CacheFiles.isValid(file, "gitblob:" + known.replace('c', 'd')));
        assertFalse("an algorithm nobody knows is a failure, never a pass",
                CacheFiles.isValid(file, "sha256:" + known));
    }

    /** A plain value stays MD5, so everything that already pins one is unaffected. */
    @Test
    public void anUntaggedDigestIsStillMd5() throws Exception {
        Path file = folder.getRoot().toPath().resolve("md5.txt");
        Files.write(file, "hello\n".getBytes(StandardCharsets.UTF_8));
        String md5 = CacheFiles.digestOf(file);
        assertNotNull(md5);
        assertTrue(CacheFiles.isValid(file, md5));
        assertTrue(CacheFiles.isValid(file, "md5:" + md5));
    }

    /**
     * <b>A failure reads as a sentence, and a dead network is not retried.</b>
     *
     * <p>Both from the offline run. The balloon said {@code java.net.UnknownHostException:
     * api.adoptium.net} to somebody whose wifi was off — every character after the dash was for whoever
     * is debugging. And an unknown host means DNS resolved nothing at all, so the retry loop spent its
     * backoff re-asking a question that cannot change in two seconds and delayed a report the user could
     * already see out of the window.</p>
     */
    @Test
    public void aFailureReadsAsASentence() {
        assertEquals("could not reach api.adoptium.net — check your connection",
                Downloads.describe(new java.net.UnknownHostException("api.adoptium.net")));
        assertEquals("the connection timed out",
                Downloads.describe(new java.net.SocketTimeoutException("Read timed out")));
        assertEquals("the server does not have it any more",
                Downloads.describe(new java.io.FileNotFoundException("https://example/x")));
        assertEquals("stopped", Downloads.describe(new InterruptedIOException("cancelled")));
    }

    /**
     * An unreachable host fails once, not three times.
     *
     * <p>Measured by the clock: three attempts carry 1s and 2s of backoff, so a retried DNS failure
     * cannot come back in under three seconds. This asserts it does.</p>
     */
    @Test
    public void anUnknownHostIsNotRetried() {
        long started = System.currentTimeMillis();
        try {
            Downloads.from("http://no-such-host.invalid/thing.bin").named("Test")
                    .into(folder.getRoot().toPath().resolve("never.bin"));
            fail("expected an unknown host to fail");
        } catch (IOException expected) {
            long took = System.currentTimeMillis() - started;
            assertTrue("it retried a failure that cannot change: took " + took + "ms", took < 3000);
        }
    }

    /** Counts the reports so a cancel can be asked for partway rather than up front. */
    private static Progress counting(AtomicLong seen) {
        return new Progress() {
            @Override
            public void begin(String what, long total, Unit unit) {
            }

            @Override
            public void advance(long done) {
                seen.incrementAndGet();
            }

            @Override
            public void detail(String item) {
            }
        };
    }
}
