package com.crystalgui.ui.elements.workbench;

import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.TypeSearch;
import com.crystalgui.text.lang.TypeSearchRegistry;
import com.crystalgui.ui.elements.chrome.QuickPickEntry;
import com.crystalgui.ui.elements.chrome.QuickPickItem;
import com.crystalgui.ui.elements.chrome.QuickPickSource;
import com.crystalgui.ui.text.TextRange;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link GoToFile} — one list over the workspace and the classpath together.
 *
 * <h3>What is at stake</h3>
 *
 * <p>Merging two pickers is not merging two lists: it is deciding that a file and a class <b>compete</b>,
 * which means one matcher, one ordering, and an id that says which kind of thing a row is. Each of those
 * fails quietly on its own — two matchers give an order nobody chose, and an ambiguous id opens the wrong
 * thing rather than failing.</p>
 *
 * <p>Driven through {@code rowsFor} rather than the popup. Opening the real picker needs a
 * {@code UIWindow} and a whole {@code Workbench} to assert what a row's <em>text</em> is, which tests the
 * shell rather than the ranking — and the ranking is the part with decisions in it.</p>
 */
public class GoToFileTest {

    @After
    public void clearRegistry() {
        // A STATIC, so it outlives the class -- without this the next test inherits these providers and
        // passes or fails by Gradle's ordering, which is re-decided every run.
        TypeSearchRegistry.resetForTesting();
    }

    /**
     * A provider that actually narrows, because a real one does.
     *
     * <p>The first version returned its whole list whatever was asked, which quietly made one assertion
     * meaningless: a query for {@code Main.java} "found" {@code ArrayList} too. A fixture that answers
     * everything cannot show that the picker asked the right question.</p>
     */
    private static void provideTypes(TypeSearch.Result... results) {
        TypeSearchRegistry.contribute((query, limit) -> {
            List<TypeSearch.Result> matched = new ArrayList<>();
            String needle = query.toLowerCase(Locale.ROOT);
            for (TypeSearch.Result result : results) {
                if (result.simpleName().toLowerCase(Locale.ROOT).contains(needle)) matched.add(result);
            }
            return new TypeSearch.Results(matched, false);
        });
    }

    private static TypeSearch.Result type(String pkg, String simple, SymbolKind kind) {
        return new TypeSearch.Result(simple, pkg, "jar:whatever", kind, false);
    }

    private static List<CgPath> files(String... paths) {
        List<CgPath> out = new ArrayList<>();
        for (String path : paths) out.add(CgPath.parse(path));
        return out;
    }

    /** Everything the picker would show, drained through the real sink. */
    private static List<QuickPickEntry> rows(String query, List<CgPath> files) {
        return batch(query, files).entries();
    }

    private static QuickPickSource.Batch batch(String query, List<CgPath> files) {
        // THE REAL DRAIN, not a hand-rolled collector: the cap and the truncation flag live in it, so a
        // test that collected into its own list would assert about a shape the widget never sees.
        return QuickPickSource.drain((q, sink) -> GoToFile.fetchInto(q, files, sink),
                SearchQuery.of(query), 1000);
    }

    private static List<String> labelsOf(List<QuickPickEntry> rows) {
        List<String> labels = new ArrayList<>();
        for (QuickPickEntry row : rows) labels.add(row.item().label());
        return labels;
    }

    // ── One list ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A project file and a classpath type appear in the same list.</b>
     *
     * <p>The whole point of the merge. Two pickers would mean knowing, before you start typing, whether
     * the thing you want is in the workspace — which is exactly the thing you are searching to find out.</p>
     */
    @Test
    public void filesAndTypesShareOneList() {
        provideTypes(type("java.util", "ArrayList", SymbolKind.CLASS));

        List<String> labels = labelsOf(rows("Arr", files("proj:src/ArrayHelper.java")));
        assertTrue("the type is missing: " + labels, labels.contains("ArrayList"));
        assertTrue("the file is missing: " + labels, labels.contains("ArrayHelper.java"));
    }

    /**
     * <b>A row is addressed by a {@link Resource}, so the two kinds cannot be confused.</b>
     *
     * <p>The id is what Enter acts on. With one kind of row a bare path was unambiguous; with two it is
     * not, and the failure would not be an error — {@code java.util.ArrayList} parsed as a path opens
     * <em>something</em>, or silently nothing. Round-tripping through {@code Resource} is what makes the
     * kind part of the address.</p>
     */
    @Test
    public void everyRowIsAddressedByAResource() {
        provideTypes(type("java.util", "ArrayList", SymbolKind.CLASS));

        for (QuickPickEntry row : rows("Arr", files("proj:src/ArrayHelper.java"))) {
            Resource resource = Resource.parse(row.item().id());
            assertNotNull("a row id did not parse as a resource: " + row.item().id(), resource);
            if ("ArrayList".equals(row.item().label())) {
                assertFalse("a classpath type claimed to be a project resource", resource.isProject());
                assertEquals("java.util.ArrayList", resource.path());
            } else {
                assertTrue("a workspace file did not claim to be a project resource", resource.isProject());
                assertEquals(CgPath.parse("proj:src/ArrayHelper.java"), resource.asPath());
            }
        }
    }

    /**
     * <b>Every project file comes before every classpath type, however well the type matched.</b>
     *
     * <p>A partition, not a tie-break, and the difference is the whole bug. A class is {@code ArrayList}
     * and its file is {@code ArrayList.java}, so typing the name is an <em>exact</em> hit on the class and
     * a mere prefix on the file: the type wins on quality outright and a tie-break beneath the score is
     * never consulted. That is why typing {@code main} listed ten {@code Main} classes from
     * {@code com.sun.tools} above the workspace's own — with the weights already set the "right" way
     * round and doing nothing at all.</p>
     *
     * <p>Asserted with the type matching BETTER than the file, because a fixture where they match equally
     * passes against the broken version.</p>
     */
    @Test
    public void everyProjectFileComesBeforeEveryClasspathType() {
        provideTypes(type("java.util", "ArrayList", SymbolKind.CLASS));

        // "ArrayList" is exact on the type and a prefix on the file -- the type is the better match.
        assertEquals(List.of("ArrayList.java", "ArrayList"),
                labelsOf(rows("ArrayList", files("proj:src/ArrayList.java"))));
    }

    /**
     * <b>Within a group, the better match still wins.</b>
     *
     * <p>The half the partition must not cost. Project files are ordered among themselves by quality, and
     * so are types — a partition that also flattened the ranking inside each half would have made file
     * search worse than it was before the merge.</p>
     */
    @Test
    public void withinAGroupTheBetterMatchStillWins() {
        // `BitArray` contains the letters mid-word; `Arrays` starts with them. Both are substring hits, so
        // the fixture returns both -- it narrows by substring where a real index also does subsequences,
        // which is why AbstractRowRenderer would NOT come back here for "Arr".
        provideTypes(type("java.util", "Arrays", SymbolKind.CLASS),
                type("x", "BitArray", SymbolKind.CLASS));

        List<String> all = labelsOf(rows("Arr", files("proj:src/Barr.java", "proj:src/Arr.java")));

        // Files first as a block, best first within it; then the types, best first within those.
        assertEquals(List.of("Arr.java", "Barr.java", "Arrays", "BitArray"), all);
    }

    // ── What a row says ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>A type row carries its package and its kind; a file row carries its folder and an icon name.</b>
     *
     * <p>Both fill the same two slots, which is what lets one list hold both without reading as two lists
     * glued together. A row that filled neither would be a bare name in a column of decorated ones.</p>
     */
    @Test
    public void bothKindsOfRowFillTheSameSlots() {
        provideTypes(type("java.util", "ArrayList", SymbolKind.CLASS));

        QuickPickItem type = rows("ArrayList", List.of()).get(0).item();
        assertEquals("ArrayList", type.label());
        assertEquals("java.util", type.description());
        assertEquals(SymbolKind.CLASS, type.kind());
        assertNull("a type row should draw its kind, not a named icon", type.iconName());

        QuickPickItem file = rows("Main", files("proj:src/Main.java")).get(0).item();
        assertEquals("Main.java", file.label());
        assertEquals("proj:src", file.description());
        assertNull("a file has no symbol kind", file.kind());
        assertNotNull("a file row should carry an icon name", file.iconName());

        // NEITHER USES THE LEADING SLOT. `category` renders BEFORE the label, so a package or folder there
        // would draw "java.util: ArrayList" -- which reads as a command category.
        assertNull(type.category());
        assertNull(file.category());
    }

    /**
     * <b>The query is lit on the name and never on the location.</b>
     *
     * <p>A folder or package hit still <em>ranks</em> the row — that is how {@code render/Cg} finds a file
     * — but lighting it would claim it contributed to the ordering when a name hit outranks it outright.
     * Both references highlight the name alone.</p>
     */
    @Test
    public void onlyTheNameIsHighlighted() {
        provideTypes(type("java.util", "ArrayList", SymbolKind.CLASS));

        QuickPickEntry row = rows("Arr", List.of()).get(0);
        assertFalse("nothing was highlighted at all", row.labelRanges().isEmpty());
        TextRange first = row.labelRanges().get(0);
        assertEquals(0, first.start());
        assertEquals(3, first.end());
        assertTrue("the package was highlighted", row.categoryRanges().isEmpty());
    }

    /**
     * <b>A folder fragment finds a file, without lighting anything.</b>
     *
     * <p>The behaviour the merge could easily have dropped: the old picker matched the parent folder
     * because it sat in the {@code category} slot, which is matched. Moving it to {@code description} for
     * visual consistency would have silently removed path search — a feature that fails by returning
     * fewer rows, which looks like the file simply not being indexed.</p>
     */
    @Test
    public void aFolderFragmentStillFindsAFile() {
        List<QuickPickEntry> found = rows("render", files("proj:src/render/Quad.java"));

        assertEquals(List.of("Quad.java"), labelsOf(found));
        assertTrue("a location-only hit lit up the name", found.get(0).labelRanges().isEmpty());
    }

    // ── The query ───────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A pasted location does not empty the list, for either kind of row.</b>
     *
     * <p>Type or paste {@code Main.java:42} and, matched literally, every row vanishes the moment the
     * colon lands — which reads as the search breaking rather than as the query being over-literal,
     * because the row was there a character ago.</p>
     */
    @Test
    public void aTrailingLineNumberStillFindsBothKinds() {
        provideTypes(type("java.util", "ArrayList", SymbolKind.CLASS));

        assertEquals(List.of("ArrayList"), labelsOf(rows("ArrayList:42", List.of())));
        assertEquals(List.of("Main.java"), labelsOf(rows("Main.java:42", files("proj:src/Main.java"))));
    }

    /** <b>An empty query lists nothing</b> — the workspace plus sixty thousand types is not a list. */
    @Test
    public void anEmptyQueryListsNothing() {
        provideTypes(type("java.util", "ArrayList", SymbolKind.CLASS));

        assertTrue(rows("", files("proj:src/Main.java")).isEmpty());
        assertTrue(QuickPickSource.drain(
                (q, sink) -> GoToFile.fetchInto(q, files("proj:src/Main.java"), sink), null, 1000)
                .entries().isEmpty());
    }

    /**
     * <b>With no engine registered it is still a working file picker.</b>
     *
     * <p>The three-tier absence rule applied to a merged widget: a host that ships no language engine
     * loses the classpath half and keeps everything it had before. Merging must not have made Go to File
     * depend on something optional.</p>
     */
    @Test
    public void withNoTypeProviderItIsStillAFilePicker() {
        assertEquals(List.of("Main.java"), labelsOf(rows("Main", files("proj:src/Main.java"))));
    }

    /** <b>An unmatched file is not listed</b> — nothing narrowed the workspace index before this ran. */
    @Test
    public void aFileThatDoesNotMatchIsNotListed() {
        assertTrue(rows("zzzz", files("proj:src/Main.java")).isEmpty());
    }

    // ── Caps ──────────────────────────────────────────────────────────────────────

    private static List<CgPath> manyFiles(int count) {
        List<CgPath> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(CgPath.parse("proj:src/Match" + i + ".java"));
        return out;
    }

    /**
     * <b>A flood of file matches does not starve the classpath half.</b>
     *
     * <p>The reason the cap is per group rather than one shared bound. Every project file outranks every
     * classpath type by construction, so a single cap is spent entirely on files before a type is ever
     * offered — and a query matching a few hundred filenames would list no classes at all. That reads as
     * the classpath half having broken, not as a cap doing its job.</p>
     */
    @Test
    public void aFloodOfFilesDoesNotStarveTheClasspath() {
        provideTypes(type("java.util", "Matcher", SymbolKind.CLASS));

        // DRAINED AT A LIMIT THE FILES ALONE WOULD FILL. At the generous limit the other tests use,
        // four hundred files and one type all fit and nothing is starved -- so the first version of this
        // passed with the per-group cap removed entirely, proving nothing. The number has to be one the
        // file half could swallow whole.
        List<QuickPickEntry> drained = QuickPickSource.drain(
                (q, sink) -> GoToFile.fetchInto(q, manyFiles(400), sink),
                SearchQuery.of("Match"), 60).entries();

        List<String> labels = new ArrayList<>();
        for (QuickPickEntry row : drained) labels.add(row.item().label());
        assertTrue("the type was pushed off the end by files: " + labels.size() + " rows",
                labels.contains("Matcher"));
    }

    /**
     * <b>Cutting a group is reported, so the list can say it is not everything.</b>
     *
     * <p>A list that silently stops is the worst answer a search can give — a file that exists but fell
     * past the cap looks exactly like one that does not. The picker turns this into the header's
     * "100+ matches"; here it is asserted at the seam, because the flag is what the header reads.</p>
     */
    @Test
    public void cuttingAGroupIsReported() {
        assertTrue("400 files were cut to a capped group and nothing said so",
                batch("Match", manyFiles(400)).truncated());
    }

    /** <b>...and a list that fits reports nothing.</b> The half that fails noisily if it regresses. */
    @Test
    public void aListThatFitsIsNotReportedAsCut() {
        assertFalse(batch("Match", manyFiles(3)).truncated());
    }

    /**
     * <b>The project id is not searchable</b>, because every file shares it.
     *
     * <p>A row's location is a {@code CgPath}, which reads {@code project:dir/dir}, and it was matched
     * whole. So in a workspace called {@code minecraft.workspace}, typing {@code Minecraft} matched the
     * location of EVERY file in it — {@code README.md}, {@code shader.shadergraph}, every {@code .js} —
     * and the file-before-type partition then put all of them ahead of the classpath, leaving
     * {@code net.minecraft.client.Minecraft}, an exact hit on the name, at the bottom of the list.</p>
     *
     * <p>The counter-assertion below is the half that keeps this honest: the rest of the path is still
     * matched, because finding files by the folder they are in is the reason a location is searched.</p>
     */
    @Test
    public void theProjectIdIsNotMatched() {
        provideTypes(type("net.minecraft.client", "Minecraft", SymbolKind.CLASS));
        List<String> labels = labelsOf(rows("Minecraft", files(
                "minecraft.workspace:README.md",
                "minecraft.workspace:src/main/js/App.js",
                "minecraft.workspace:test.java")));

        assertFalse("a file matched only by the shared project id: " + labels,
                labels.contains("README.md"));
        assertFalse("a file matched only by the shared project id: " + labels,
                labels.contains("App.js"));
        assertTrue("the type that actually matches the name was dropped: " + labels,
                labels.contains("Minecraft"));
    }

    /** ...and the rest of the path still matches, which is what a location search is FOR. */
    @Test
    public void theFolderPartOfALocationStillMatches() {
        List<String> labels = labelsOf(rows("util", files(
                "minecraft.workspace:src/main/java/com/example/util/Greeter.java",
                "minecraft.workspace:README.md")));

        assertTrue("a folder fragment no longer finds the file under it: " + labels,
                labels.contains("Greeter.java"));
        assertFalse("an unrelated file was matched: " + labels, labels.contains("README.md"));
    }

}
