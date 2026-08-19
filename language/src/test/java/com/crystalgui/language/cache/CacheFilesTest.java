package com.crystalgui.language.cache;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The cache discipline both the engine bands and the mapping data rest on.
 *
 * <p>Every assertion here is about a <b>failure</b>, because the success path is one file copy and the
 * failures are the reason this is shared code rather than two inline copies: a truncated write that
 * persists forever, a corrupt cache that is never re-fetched, a {@code .part} nobody cleans up.</p>
 */
public class CacheFilesTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static InputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /** MD5 of "hello", so the expectations below are not circular. */
    private static final String HELLO_MD5 = "5d41402abc4b2a76b9719d911017c592";

    @Test
    public void aVerifiedFileIsInstalledAndReadsBack() throws IOException {
        Path target = folder.getRoot().toPath().resolve("nested/hello.txt");
        assertTrue(CacheFiles.install(target, bytes("hello"), HELLO_MD5));
        assertEquals("hello", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertTrue(CacheFiles.isValid(target, HELLO_MD5));
    }

    /**
     * <b>A digest mismatch installs nothing and leaves nothing behind.</b>
     *
     * <p>The half that matters is the second: a {@code .part} left after a failed verify is
     * indistinguishable from one a crash left, so the next launch has to be free to overwrite it. If it
     * were kept, a mirror that served bad bytes once would look like an interrupted download forever.</p>
     */
    @Test
    public void badBytesAreNotInstalledAndTheTemporaryIsRemoved() throws IOException {
        Path target = folder.getRoot().toPath().resolve("hello.txt");
        assertFalse(CacheFiles.install(target, bytes("not hello"), HELLO_MD5));
        assertFalse("a file was installed despite failing its digest", Files.exists(target));
        assertFalse("the .part survived a failed verify",
                Files.exists(target.resolveSibling("hello.txt.part")));
    }

    /**
     * <b>Empty is invalid, digest or no digest.</b>
     *
     * <p>Zero length is the classic shape of an interrupted write, and it is exactly what a
     * mere-existence check would accept. Asserted with a null digest because that is the posture the
     * engine bands use — extracted from our own jar, with no upstream hash to compare against — so it is
     * the case where "is the file there" is the only other option.</p>
     */
    @Test
    public void anEmptyFileIsNeverValid() throws IOException {
        Path empty = folder.newFile("empty.jar").toPath();
        assertEquals(0, Files.size(empty));
        assertFalse(CacheFiles.isValid(empty, null));
    }

    /** A file that has gone bad since it was written is re-fetchable, not wedged. */
    @Test
    public void aCorruptedCacheEntryFailsItsDigest() throws IOException {
        Path target = folder.getRoot().toPath().resolve("hello.txt");
        assertTrue(CacheFiles.install(target, bytes("hello"), HELLO_MD5));
        Files.write(target, "tampered".getBytes(StandardCharsets.UTF_8));
        assertFalse(CacheFiles.isValid(target, HELLO_MD5));
        // And re-installing over it works, which is what "retry rather than wedge" actually requires.
        assertTrue(CacheFiles.install(target, bytes("hello"), HELLO_MD5));
        assertTrue(CacheFiles.isValid(target, HELLO_MD5));
    }

    @Test
    public void aMissingFileIsInvalidRatherThanAnError() {
        assertFalse(CacheFiles.isValid(folder.getRoot().toPath().resolve("absent"), null));
        assertFalse(CacheFiles.isValid(folder.getRoot().toPath().resolve("absent"), HELLO_MD5));
    }
}
