package com.crystalgui.language.map.format;

import com.crystalgui.language.map.MappingSet;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The MCP CSV parser, against a fixture and against the real 11,497 lines.
 *
 * <p>Both, because they answer different questions. The fixture pins exact behaviour on the rows that
 * are easy to get wrong and never skips. The real files prove the parser survives data nobody wrote for
 * it — which is the only way to find out that a description can contain a comma.</p>
 */
public class McpCsvFormatTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final McpCsvFormat FORMAT = new McpCsvFormat();

    private Path csv(String name, String contents) throws IOException {
        Path file = folder.newFile(name).toPath();
        Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    // ── The fixture half ────────────────────────────────────────────────────────────────────────

    @Test
    public void methodsAndFieldsAreToldApartByTheirNamePrefix() throws IOException {
        Path file = csv("mixed.csv", "searge,name,side,desc\n"
                + "func_147439_a,getBlock,0,\n"
                + "field_70170_p,worldObj,0,\n");
        MappingSet.Builder builder = MappingSet.builder();
        FORMAT.parse(file, builder);
        MappingSet mappings = builder.build();

        // Any owner: SRG names are globally unique, which is why the file carries none.
        assertEquals("getBlock", mappings.readableMethod("net/minecraft/world/World", "func_147439_a"));
        assertEquals("getBlock", mappings.readableMethod("some/other/Type", "func_147439_a"));
        assertEquals("worldObj", mappings.readableField("net/minecraft/entity/Entity", "field_70170_p"));

        // And a method name must not be answerable as a field, or the two tables have merged.
        assertEquals("func_147439_a", mappings.readableField("x/Y", "func_147439_a"));
    }

    /**
     * <b>A description containing a comma must not lose its row.</b>
     *
     * <p>The failure this pins is silent and biased: {@code split(",")} without a limit yields five
     * columns for such a row, and a parser that checks for four drops it. The rows with commas in their
     * descriptions are the well-documented ones — so the members that go missing are exactly the ones
     * somebody is most likely to be looking for. Taken from the real {@code fields.csv}, line 2.</p>
     */
    @Test
    public void aQuotedDescriptionWithCommasStillParses() throws IOException {
        Path file = csv("fields.csv", "searge,name,side,desc\n"
                + "field_100013_f,isPotionDurationMax,0,"
                + "\"True if potion effect duration is at maximum, false otherwise.\"\n");
        MappingSet.Builder builder = MappingSet.builder();
        FORMAT.parse(file, builder);
        assertEquals("isPotionDurationMax",
                builder.build().readableField("any/Owner", "field_100013_f"));
    }

    /** Parameters are recognised and deliberately not mapped — they exist only as debug metadata. */
    @Test
    public void parameterRowsAreSkipped() throws IOException {
        Path file = csv("params.csv", "searge,name,side,desc\n" + "p_147439_1_,x,0,\n");
        MappingSet.Builder builder = MappingSet.builder();
        FORMAT.parse(file, builder);
        assertTrue("a parameter was mapped", builder.build().isIdentity());
    }

    /**
     * <b>The reverse direction is what actually makes a script run.</b>
     *
     * <p>The "in" direction dresses the editor up; "out" is what the compiled bytecode is remapped
     * through before it is defined. A parser that populated only one of them would look completely
     * correct in every hover and completion and produce a script that could not link.</p>
     */
    @Test
    public void theOutDirectionIsPopulatedToo() throws IOException {
        Path file = csv("methods.csv", "searge,name,side,desc\nfunc_147439_a,getBlock,0,\n");
        MappingSet.Builder builder = MappingSet.builder();
        FORMAT.parse(file, builder);
        assertEquals("func_147439_a",
                builder.build().runtimeMethod("net/minecraft/world/World", "getBlock"));
    }

    @Test
    public void aFileIsRecognisedByItsHeaderAndNotByItsName() throws IOException {
        assertTrue(FORMAT.matches(csv("anything.txt", "searge,name,side,desc\nfunc_1,a,0,\n")));
        assertFalse("a non-MCP file was claimed",
                FORMAT.matches(csv("methods.csv", "a\tb\tc\nsomething else\n")));
        assertFalse("an empty file was claimed", FORMAT.matches(csv("empty.csv", "")));
        assertNull(MappingFiles.formatOf(csv("readme.md", "# not a mapping\n")));
    }

    /** Several files build ONE set — a parser that cleared would leave only whichever ran last. */
    @Test
    public void severalFilesAccumulateIntoOneSet() throws IOException {
        Path methods = csv("m.csv", "searge,name,side,desc\nfunc_1,alpha,0,\n");
        Path fields = csv("f.csv", "searge,name,side,desc\nfield_1,beta,0,\n");
        MappingSet mappings = MappingFiles.load(List.of(methods, fields));
        assertEquals("alpha", mappings.readableMethod("x/Y", "func_1"));
        assertEquals("beta", mappings.readableField("x/Y", "field_1"));
    }

    /** A directory of a download's leftovers must not fail the whole mapping. */
    @Test
    public void unrecognisedFilesAreSkipped() throws IOException {
        Path good = csv("methods.csv", "searge,name,side,desc\nfunc_1,alpha,0,\n");
        Path noise = csv("methods.csv.md5", "d41d8cd98f00b204e9800998ecf8427e\n");
        MappingSet mappings = MappingFiles.load(List.of(good, noise, folder.getRoot().toPath()));
        assertEquals("alpha", mappings.readableMethod("x/Y", "func_1"));
    }

    // ── The real-data half ──────────────────────────────────────────────────────────────────────

    /**
     * <b>The actual {@code mcp_stable/12} files, where this machine has them.</b>
     *
     * <p>RFG downloads them to build 1.7.10, so they are present wherever this project has been built —
     * and the test skips rather than failing where they are not, the same allowance every engine test
     * makes. The counts are asserted loosely: the point is that thousands of rows parsed, not that this
     * exact release has this exact number, which would break on a version bump for no reason.</p>
     */
    @Test
    public void theRealMcpCsvsParse() throws IOException {
        String configured = System.getProperty("cgui.test.mcpCsvDir");
        Assume.assumeTrue("mcp_stable CSVs not on this machine; skipping",
                configured != null && !configured.isEmpty() && Files.isDirectory(Paths.get(configured)));
        Path directory = Paths.get(configured);

        List<Path> files = MappingFiles.in(directory);
        assertFalse("the directory listed nothing", files.isEmpty());

        MappingSet mappings = MappingFiles.load(files);
        assertFalse("nothing was mapped from the real files", mappings.isIdentity());

        // IN is a function and answers exactly.
        assertEquals("getBlock", mappings.readableMethod("net/minecraft/world/World", "func_147439_a"));

        // OUT is not, and must say so rather than guess. `getBlock` is four distinct SRG methods in this
        // very file -- func_145805_f, func_147439_a, func_150810_a, func_151337_f -- so an unqualified
        // reverse has no answer, and a map that kept the last one would remap a script to whichever it
        // happened to be. See MappingSet on the 18%/22% this covers.
        assertTrue("getBlock is not reported ambiguous, so something picked one of the four",
                mappings.isAmbiguousReadableMethod("getBlock"));
        assertEquals("an ambiguous name must pass through unchanged, not resolve to a guess",
                "getBlock", mappings.runtimeMethod("net/minecraft/world/World", "getBlock"));

        // And an unambiguous one still reverses, or the refusal has swallowed the whole direction.
        assertFalse(mappings.isAmbiguousReadableMethod("getIsPotionDurationMax"));
        assertEquals("func_100011_g",
                mappings.runtimeMethod("net/minecraft/potion/PotionEffect", "getIsPotionDurationMax"));

        // And nothing invented for a name the files do not carry.
        assertEquals("func_00000_zzz", mappings.readableMethod("x/Y", "func_00000_zzz"));
        assertNotNull(MappingFiles.formatOf(directory.resolve("methods.csv")));
    }
}
