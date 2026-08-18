package com.crystalgui.language.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Getting a file into a cache directory without ever leaving a half-written one behind.
 *
 * <h3>One implementation, two callers, and they have the same failure modes</h3>
 *
 * <p>The engine bands are extracted out of the mod jar (§26.2) and the mapping data is downloaded
 * (§26.5). Different sources, identical problems: a partial write, a crash mid-copy, two clients
 * starting at once, a cache that has gone bad since. The plan says to factor the file half out rather
 * than write it twice, and the reason is that a second implementation gets one of the four rules
 * slightly wrong and the symptom is a cache that is wedged until somebody deletes it by hand.</p>
 *
 * <h3>The four rules</h3>
 *
 * <ul>
 *   <li><b>Missing and invalid are the same thing.</b> Checking mere existence is exactly what lets a
 *       truncated download persist forever, so there is no "assume it is fine because the file is
 *       there".</li>
 *   <li><b>Verify against a digest where one is known</b>, never against a size. A mirror serving
 *       something unexpected and a corrupted local copy are then caught by one check.</li>
 *   <li><b>Install atomically.</b> Write to {@code <name>.part} <em>in the same directory</em>, verify
 *       there, then move. Nothing incomplete is ever visible under the real name, and a crash leaves a
 *       {@code .part} the next launch overwrites.</li>
 *   <li><b>Delete on verification failure</b>, so the next launch retries rather than being stuck on
 *       bad bytes.</li>
 * </ul>
 *
 * <p>The same directory matters: {@link Files#move} can only be atomic within one filesystem, and a
 * temp directory is routinely on another volume.</p>
 */
public final class CacheFiles {

    private CacheFiles() {
    }

    /**
     * Whether {@code file} is present and, if {@code md5} is given, matches it.
     *
     * <p>A null digest means "any non-empty file will do" — which is the honest posture for something
     * extracted out of our own jar, where there is no upstream digest to pin and the jar's own integrity
     * is already the JVM's problem. Empty still fails: a zero-length file is the classic shape of an
     * interrupted write, and treating it as present is how a cache becomes permanently wrong.</p>
     */
    public static boolean isValid(Path file, String md5) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) == 0) return false;
            return md5 == null || md5.equalsIgnoreCase(digestOf(file));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Installs {@code contents} at {@code target}, atomically, verifying against {@code md5} first.
     *
     * <p>The stream is consumed and closed. Returns whether the file is now valid at {@code target} —
     * false means the bytes did not match and nothing was installed, which is a caller's cue to report
     * rather than to retry in a loop.</p>
     */
    public static boolean install(Path target, InputStream contents, String md5) throws IOException {
        Path directory = target.getParent();
        if (directory != null) Files.createDirectories(directory);
        Path part = target.resolveSibling(target.getFileName() + ".part");

        try {
            try (InputStream in = contents; OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[8192];
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                }
            }
            if (!isValid(part, md5)) {
                // DELETED, not left for inspection. A `.part` that failed is indistinguishable from one
                // a crash left behind, and the next launch has to be free to overwrite either.
                Files.deleteIfExists(part);
                return false;
            }
            move(part, target);
            return true;
        } catch (IOException failed) {
            Files.deleteIfExists(part);
            throw failed;
        }
    }

    /**
     * Atomic where the filesystem allows it, replacing where it does not.
     *
     * <p>{@code ATOMIC_MOVE} is refused on some filesystems (and across volumes, which cannot happen
     * here because the {@code .part} is a sibling). Falling back to a plain replace is strictly better
     * than failing: the window it opens is one rename wide, and the alternative is a cache that cannot
     * be populated at all on those systems.</p>
     */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Lowercase hex MD5 of a file. */
    public static String digestOf(Path file) throws IOException {
        MessageDigest digest = md5();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(32);
        for (byte value : digest.digest()) hex.append(String.format("%02x", value));
        return hex.toString();
    }

    /**
     * MD5, which is the algorithm the upstream publishes and therefore the only one that can be checked.
     *
     * <p><b>Not a security claim and must not be read as one.</b> MD5 is broken for anything adversarial;
     * what it is being used for here is detecting a truncated download and a corrupted cache, which it
     * does perfectly well. The alternative is no check at all, because there is no SHA published beside
     * these artifacts to compare against.</p>
     */
    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM ships MD5; the checked exception is a formality of the API.
            throw new IllegalStateException("MD5 is unavailable", impossible);
        }
    }
}
