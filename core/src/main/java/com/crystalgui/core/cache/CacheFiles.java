package com.crystalgui.core.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
            return md5 == null || matches(file, md5);
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Checks a digest that may name its own algorithm — {@code gitblob:<sha1>}, or a bare MD5.
     *
     * <h3>Why a second algorithm, and why it is spelled into the value</h3>
     *
     * <p>The engine bands are pinned by MD5 because that is what is computed from the artifacts Gradle
     * resolved. The MCP mapping data had <b>no digest at all</b> — the note here said upstream published
     * none, which was true of {@code .md5} files and false of the repository itself: <b>git addresses
     * every blob by SHA-1</b>, GitHub's API reports it, and it is as pinnable as anything we could
     * compute. Two sources, two algorithms, and neither is ours to choose.</p>
     *
     * <p>Tagged in the value rather than carried as a separate field because a digest without its
     * algorithm is not a digest — the pair travels together or one of them ends up defaulted at a call
     * site that never thought about it. A bare value stays MD5, so nothing that already pins one changes.</p>
     */
    private static boolean matches(Path file, String digest) throws IOException {
        int tag = digest.indexOf(':');
        if (tag < 0) return digest.equalsIgnoreCase(digestOf(file));
        String kind = digest.substring(0, tag);
        String expected = digest.substring(tag + 1);
        if (kind.equalsIgnoreCase("gitblob")) return expected.equalsIgnoreCase(gitBlobSha1(file));
        if (kind.equalsIgnoreCase("md5")) return expected.equalsIgnoreCase(digestOf(file));
        // Mojang publishes a plain SHA-1 beside every download in its version manifest -- upstream's own
        // number for the exact bytes, which is the same argument `gitblob` makes.
        if (kind.equalsIgnoreCase("sha1")) return expected.equalsIgnoreCase(sha1Of(file));
        // AN ALGORITHM WE DO NOT KNOW IS A FAILURE, not a pass. Treating it as "no digest given" would
        // turn a typo in a pin into a silently unverified download.
        return false;
    }

    /**
     * Git's own hash of a file's contents: {@code sha1("blob " + length + NUL + bytes)}.
     *
     * <p>Which is why it can be checked against what GitHub's API reports without fetching anything
     * else, and why it is a real upstream pin rather than a hash we recorded and hope was right.</p>
     */
    public static String gitBlobSha1(Path file) throws IOException {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is required of every JVM", impossible);
        }
        sha1.update(("blob " + Files.size(file) + (char) 0).getBytes(StandardCharsets.US_ASCII));
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                sha1.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(40);
        for (byte value : sha1.digest()) hex.append(String.format("%02x", value));
        return hex.toString();
    }

    /**
     * Installs {@code contents} at {@code target}, atomically, verifying against {@code md5} first.
     *
     * <p>The stream is consumed and closed. Returns whether the file is now valid at {@code target} —
     * false means the bytes did not match and nothing was installed, which is a caller's cue to report
     * rather than to retry in a loop.</p>
     */
    public static boolean install(Path target, InputStream contents, String md5) throws IOException {
        return install(target, contents, md5, false);
    }

    /** Where the in-progress copy of {@code target} lives — a sibling, so the move can be atomic. */
    public static Path partOf(Path target) {
        return target.resolveSibling(target.getFileName() + ".part");
    }

    /**
     * How much of {@code target} is already on disk from an interrupted attempt, or zero.
     *
     * <p>What makes resume possible at all: the {@code .part} was always kept across a crash so the next
     * launch could overwrite it, and the same file answers "how far did we get" for nothing.</p>
     */
    public static long partialSize(Path target) {
        try {
            Path part = partOf(target);
            return Files.isRegularFile(part) ? Files.size(part) : 0L;
        } catch (IOException unreadable) {
            return 0L;
        }
    }

    /**
     * The same, <b>appending</b> to whatever the {@code .part} already holds.
     *
     * <p>For a resumed transfer, where the caller has asked the server to continue from
     * {@link #partialSize} and the server has agreed. The digest is still checked over the whole file
     * afterwards, which is what makes a resume safe to attempt at all: if the server ignored the range
     * and sent the whole body, or sent a different body, the check fails and the {@code .part} is
     * deleted — so the next attempt starts clean rather than compounding the error.</p>
     */
    public static boolean install(Path target, InputStream contents, String md5, boolean append)
            throws IOException {
        Path directory = target.getParent();
        if (directory != null) Files.createDirectories(directory);
        Path part = partOf(target);

        try {
            try (InputStream in = contents;
                 OutputStream out = append
                         ? Files.newOutputStream(part, StandardOpenOption.CREATE,
                                 StandardOpenOption.APPEND)
                         : Files.newOutputStream(part)) {
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

    /**
     * Lowercase hex SHA-1 of a file's bytes — what Mojang's version manifest pins each download by.
     *
     * <p>Plain, unlike {@link #gitBlobSha1}: no length header, because this is upstream's hash of the
     * content rather than git's hash of a blob object.</p>
     */
    public static String sha1Of(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is required of every JVM", impossible);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(40);
        for (byte value : digest.digest()) hex.append(String.format("%02x", value));
        return hex.toString();
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
