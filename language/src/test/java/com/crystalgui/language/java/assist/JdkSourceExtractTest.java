package com.crystalgui.language.java.assist;

import com.crystalgui.core.async.Progress;
import com.crystalgui.language.cache.TarArchive;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M13 §25.5 — the JDK source extract, end to end apart from the network.
 *
 * <h3>The tar is written by hand, and that is the point</h3>
 *
 * <p>Java ships a reader and a writer for zip and neither for tar, so the fixture below lays out ustar
 * header blocks itself. That makes this test the specification {@link TarArchive} is read against rather
 * than a round-trip through one implementation — a reader tested against its own writer agrees with
 * itself about a field it has misplaced.</p>
 *
 * <p>What cannot be exercised here is the HTTP request, and it is the one part with no logic in it:
 * {@code Downloads.open} is three lines shared with the mapping fetch. Everything between the socket and
 * the cache file — the gzip, the tar, the layout rule, the package filter, the strip, the repack — is
 * driven below.</p>
 */
public class JdkSourceExtractTest {

    /** Where a JDK repository, a modular {@code src.zip} and a Java 8 one each put the same file. */
    @Test
    public void threeArchiveLayoutsAnswerToOneRule() {
        assertEquals("java/util/List.java", JdkSourceExtract.relativePathOf(
                "jdk-17/src/java.base/share/classes/java/util/List.java"));
        assertEquals("a modular src.zip keys by module", "java/util/List.java",
                JdkSourceExtract.relativePathOf("java.base/java/util/List.java"));
        assertEquals("a Java 8 src.zip keys by package", "java/util/List.java",
                JdkSourceExtract.relativePathOf("java/util/List.java"));
        assertEquals("a leading slash is not a module", "java/util/List.java",
                JdkSourceExtract.relativePathOf("/java/util/List.java"));
    }

    /**
     * <b>Most of the archive is not wanted</b>, and saying so is what turns 43 MB into single digits.
     *
     * <p>The test tree is the case worth naming: it contains files whose package path looks exactly like
     * a platform one, so a rule matching on {@code java/util/} alone would copy the JDK's own unit tests
     * into the extract and then quote one of them at a hover.</p>
     */
    @Test
    public void everythingOutsideTheWantedPackagesIsRefused() {
        assertNull("a module a script will never name",
                JdkSourceExtract.relativePathOf("src/jdk.compiler/share/classes/com/sun/tools/T.java"));
        assertNull("the build tooling",
                JdkSourceExtract.relativePathOf("make/langtools/tools/propertiesparser/Main.java"));
        assertNull("a test that shares a package path with the real thing",
                JdkSourceExtract.relativePathOf("test/jdk/java/util/ListTest.java"));
        assertNotNull("...while the real thing under /share/classes/ is kept",
                JdkSourceExtract.relativePathOf("src/java.base/share/classes/java/util/List.java"));
    }

    /** {@code 1.8} is 8 and {@code 17} is 17 — the spelling stopped being {@code 1.x} after 8. */
    @Test
    public void theFeatureVersionIsReadFromEitherSpelling() {
        String saved = System.getProperty("java.specification.version");
        try {
            System.setProperty("java.specification.version", "1.8");
            assertEquals(8, JdkSourceExtract.runningFeatureVersion());
            System.setProperty("java.specification.version", "17");
            assertEquals(17, JdkSourceExtract.runningFeatureVersion());
            System.setProperty("java.specification.version", "nonsense");
            assertEquals("an unreadable answer is 8, the floor this project supports",
                    8, JdkSourceExtract.runningFeatureVersion());
        } finally {
            if (saved != null) System.setProperty("java.specification.version", saved);
        }
    }

    /**
     * <b>A tar.gz in, a flat zip of stripped sources out.</b>
     *
     * <p>The whole producer, over an archive carrying one wanted file, one unwanted module, one test-tree
     * file with a colliding package path, and one non-Java entry. What comes out has to be readable by
     * the <em>ordinary</em> {@link SourceArchives.ZipArchive}, because keeping the fetched form
     * indistinguishable from a real {@code src.zip} is what stops a defect here becoming a defect in a
     * reader that every developer with a JDK exercises daily.</p>
     */
    @Test
    public void anArchiveBecomesAFlatZipOfStrippedSources() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("jdk-17/src/java.base/share/classes/java/util/List.java", ""
                + "package java.util;\n"
                + "/** An ordered collection. */\n"
                + "public interface List<E> extends Collection<E> {\n"
                + "    boolean add(E e);\n"
                + "    default void clear() { removeAll(this); }\n"
                + "}\n");
        entries.put("jdk-17/src/jdk.compiler/share/classes/com/sun/tools/Nope.java", "class Nope {}");
        entries.put("jdk-17/test/jdk/java/util/ListTest.java", "class ListTest {}");
        entries.put("jdk-17/README.md", "not java");
        // A SECOND WANTED FILE, AFTER the first -- see aFileReadInFullDoesNotDesynchroniseTheStream.
        entries.put("jdk-17/src/java.base/share/classes/java/io/Closeable.java",
                "package java.io;\npublic interface Closeable { void close(); }\n");

        byte[] zip = build(entries);
        Map<String, String> produced = readZip(zip);

        assertEquals("both wanted files survive and nothing else does", 2, produced.size());
        assertNotNull(produced.get("java/io/Closeable.java"));
        String list = produced.get("java/util/List.java");
        assertNotNull("the flat path a src.zip would have used", list);
        assertTrue("the javadoc is the payload", list.contains("/** An ordered collection. */"));
        assertTrue("the declaration is what gets quoted", list.contains("public interface List<E>"));
        assertTrue("an abstract method has no body to cut", list.contains("boolean add(E e);"));
        assertTrue("and a default method's body is gone", list.contains("default void clear() {}"));
        assertFalse(list.contains("removeAll(this)"));
    }

    /**
     * <b>The same package path really does appear twice in a JDK tree</b> — once under {@code share} and
     * again under an OS-specific directory — and a duplicate entry makes {@code ZipOutputStream} throw.
     *
     * <p>So the failure is not a slightly larger file: it is an exception in the middle of the build, and
     * the extract never lands at all. First one wins, which is what a classpath would have done anyway.</p>
     */
    @Test
    public void aPackagePathAppearingTwiceDoesNotBreakTheBuild() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("src/java.base/share/classes/java/io/File.java", "package java.io; class File { int a; }");
        entries.put("src/java.base/windows/classes/java/io/File.java", "package java.io; class File { int b; }");

        // A THIRD, DISTINCT wanted file after the pair, so "1" cannot be the answer a reader that
        // stopped early would also give.
        entries.put("src/java.base/share/classes/java/io/Reader.java", "package java.io; class Reader {}");

        Map<String, String> produced = readZip(build(entries));
        assertEquals(2, produced.size());
        assertTrue("the first one wins, as a classpath would have decided",
                produced.get("java/io/File.java").contains("int a"));
        assertNotNull("the reader kept going past the duplicate", produced.get("java/io/Reader.java"));
    }

    /**
     * <b>Reading an entry in full must not desynchronise the stream</b> — the padding is the entry's, not
     * the unread remainder's.
     *
     * <p>Content is padded out to a whole 512-byte block. After a full read there is no remainder, so
     * computing the padding from what is left computes zero and the padding stays in the stream: every
     * header after the first read lands mid-block, the size field is nonsense, and the reader walks off
     * into the content and quietly ends. <b>Measured against a real JDK source tree it produced one file
     * out of 14,212 and threw nothing.</b></p>
     *
     * <p>The original fixtures passed against it <em>for the wrong reason</em> — everything after the
     * first entry turned to garbage and yielded nothing, which happened to equal the expected count. So
     * this asserts on several wanted files in a row, with the first deliberately not a multiple of 512.</p>
     */
    @Test
    public void aFileReadInFullDoesNotDesynchroniseTheStream() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        String[] names = { "List", "Map", "Set", "Deque", "Queue" };
        StringBuilder filler = new StringBuilder();
        while (filler.length() < 700) filler.append("// padding to push past one block\n");
        for (String name : names) {
            entries.put("src/java.base/share/classes/java/util/" + name + ".java",
                    "package java.util;\n" + filler + "public interface " + name + " { void f(); }\n");
        }

        Map<String, String> produced = readZip(build(entries));
        assertEquals("every entry after the first was lost to the padding", names.length,
                produced.size());
        for (String name : names) {
            assertNotNull(name + " went missing", produced.get("java/util/" + name + ".java"));
        }
    }

    /** GNU's long-name record, which is how a real tar spells a path over 100 characters. */
    @Test
    public void aGnuLongNameIsReadAsTheFollowingEntrysName() throws Exception {
        String longPath = "jdk-17/src/java.base/share/classes/java/util/concurrent/atomic/"
                + "AtomicIntegerFieldUpdaterWithAnAbsurdlyLongNameToForceTheLongNameRecord.java";
        assertTrue("the fixture must actually exceed the ustar name field", longPath.length() > 100);

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (OutputStream gzip = new GZIPOutputStream(raw)) {
            writeLongName(gzip, longPath);
            writeEntry(gzip, longPath, "package java.util.concurrent.atomic; class A { void f() { g(); } }");
            gzip.write(new byte[1024]);
        }

        Map<String, String> produced = readZip(buildFrom(raw.toByteArray()));
        assertEquals(1, produced.size());
        assertNotNull("the long name was lost",
                produced.get("java/util/concurrent/atomic/"
                        + "AtomicIntegerFieldUpdaterWithAnAbsurdlyLongNameToForceTheLongNameRecord.java"));
    }

    /**
     * <b>The producer, over a REAL JDK source tree.</b>
     *
     * <p>This exists because the synthetic fixtures above could not have caught what it caught. The
     * padding defect made the reader produce <b>one file out of 14,212</b> and throw nothing, and every
     * hand-written fixture passed against it — because everything after the first entry turned to garbage
     * and yielded nothing, which happened to equal the expected count. Real data has the property a
     * fixture is built without: nobody chose what is in it.</p>
     *
     * <p>Skips where no {@code src.zip} is reachable, which is a legitimate machine — that is the whole
     * premise of §25.5. It re-tars a couple of hundred entries into the layout an OpenJDK repository uses,
     * so the {@code /share/classes/} rule is the one under test rather than the {@code src.zip} one.</p>
     */
    @Test
    public void theProducerRunsOverARealJdkSourceTree() throws Exception {
        File srcZip = null;
        for (File candidate : SourceArchives.jdkSources()) {
            if (candidate.isFile() && candidate.getName().equals("src.zip")) {
                srcZip = candidate;
                break;
            }
        }
        Assume.assumeNotNull("no src.zip on this machine — a legitimate host, and §25.5's premise", srcZip);

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        int tarred = 0;
        try (ZipFile zip = new ZipFile(srcZip); OutputStream gzip = new GZIPOutputStream(raw)) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements() && tarred < 200) {
                ZipEntry entry = all.nextElement();
                String name = entry.getName();
                // One package, so the test stays quick. Both src.zip layouts put java/util somewhere in
                // the path, and the module prefix (if any) is re-written away below in any case.
                if (entry.isDirectory() || !name.endsWith(".java") || !name.contains("java/util/")) continue;
                String flat = name.substring(name.indexOf("java/util/"));
                if (flat.indexOf('/', "java/util/".length()) >= 0) continue;
                try (InputStream bytes = zip.getInputStream(entry)) {
                    writeEntry(gzip, "jdk/src/java.base/share/classes/" + flat,
                            new String(readAll(bytes), StandardCharsets.UTF_8));
                }
                tarred++;
            }
            gzip.write(new byte[1024]);
        }
        Assume.assumeTrue("this src.zip has no java/util sources to read", tarred > 20);

        Map<String, String> produced = readZip(buildFrom(raw.toByteArray()));
        assertEquals("every entry must survive the round trip", tarred, produced.size());

        String list = produced.get("java/util/List.java");
        if (list != null) {
            assertTrue("the javadoc is the whole reason for shipping this", list.contains("@param"));
            assertTrue("the declaration is what gets quoted", list.contains("boolean add("));
            assertTrue("and it is still Java", list.contains("public interface List"));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read = in.read(buffer); read > 0; read = in.read(buffer)) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    // ── Fixture: a tar.gz, written by hand ──────────────────────────────────────────────────────

    private static byte[] build(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (OutputStream gzip = new GZIPOutputStream(raw)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                writeEntry(gzip, entry.getKey(), entry.getValue());
            }
            // Two zero blocks end an archive.
            gzip.write(new byte[1024]);
        }
        return buildFrom(raw.toByteArray());
    }

    private static byte[] buildFrom(byte[] targz) throws IOException {
        try (TarArchive archive = TarArchive.gzip(new ByteArrayInputStream(targz))) {
            return JdkSourceExtract.buildToBytes(archive, Progress.NONE);
        }
    }

    private static void writeEntry(OutputStream out, String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        out.write(header(name, bytes.length, '0'));
        out.write(bytes);
        out.write(new byte[padding(bytes.length)]);
    }

    /** A GNU {@code L} record: its CONTENT is the name of the entry that follows it. */
    private static void writeLongName(OutputStream out, String name) throws IOException {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        out.write(header("././@LongLink", bytes.length, 'L'));
        out.write(bytes);
        out.write(new byte[padding(bytes.length)]);
    }

    private static int padding(int size) {
        int over = size % 512;
        return over == 0 ? 0 : 512 - over;
    }

    /**
     * One ustar header block.
     *
     * <p>The checksum is computed with its own field read as eight spaces, which is the rule that makes
     * the field self-consistent and the one every hand-rolled writer gets wrong first.</p>
     */
    private static byte[] header(String name, int size, char type) {
        byte[] block = new byte[512];
        String truncated = name.length() > 100 ? name.substring(name.length() - 100) : name;
        put(block, 0, truncated);
        put(block, 100, "0000644");
        put(block, 108, "0000000");
        put(block, 116, "0000000");
        put(block, 124, String.format("%011o", size));
        put(block, 136, String.format("%011o", 0));
        for (int at = 148; at < 156; at++) block[at] = ' ';
        block[156] = (byte) type;
        put(block, 257, "ustar");
        block[263] = '0';
        block[264] = '0';

        int sum = 0;
        for (byte b : block) sum += b & 0xFF;
        put(block, 148, String.format("%06o", sum));
        block[154] = 0;
        block[155] = ' ';
        return block;
    }

    private static void put(byte[] block, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, block, offset, bytes.length);
    }

    private static Map<String, String> readZip(byte[] zip) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                    bytes.write(buffer, 0, read);
                }
                out.put(entry.getName(), new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
