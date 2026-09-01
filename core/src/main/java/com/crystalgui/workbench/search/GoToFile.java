package com.crystalgui.workbench.search;

import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.lang.TypeSearch;
import com.crystalgui.text.lang.TypeSearchRegistry;
import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.chrome.palette.QuickPick;
import com.crystalgui.core.collection.pick.QuickPickEntry;
import com.crystalgui.core.collection.pick.QuickPickItem;
import com.crystalgui.core.collection.pick.QuickPickSource.ResultSink;
import com.crystalgui.ui.text.TextRange;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Go to File — one list over everything you can open, workspace and classpath alike.
 *
 * <h3>One picker, not two</h3>
 *
 * <p>A separate Go to Class would be a second popup that looks identical and behaves differently, and the
 * question it answers is not a different question: <em>"open the thing called this"</em> does not become a
 * new gesture because the thing happens to live in a jar. Both references landed here too — IntelliJ's
 * Ctrl+N and Ctrl+Shift+N are two doors into one window, and VS Code's Ctrl+P is one list.</p>
 *
 * <p>So project files and classpath types are ranked <b>against each other</b> by one matcher. That is the
 * whole reason {@link TypeSearch} does not rank: a provider that scored its own results would be bringing a
 * second notion of "better match" into a list it shares, and the two orderings would interleave into
 * something neither of them meant.</p>
 *
 * <h3>What a row is addressed by</h3>
 *
 * <p>A {@link Resource}, stringified — {@code project:proj:src/Main.java} or
 * {@code library:java.util.ArrayList}. Not a bare path, because the list now holds two kinds of thing and
 * the id has to say which; and not a lookup table, because an id that is the address cannot go stale
 * between the list being built and a row being chosen. A file deleted in between simply fails to open, and
 * says so, rather than opening whatever has since taken its index.</p>
 */
public final class GoToFile {

    public static final String PLACEHOLDER = "Go to file or class";

    /** The header bar's text — and the surface the popup is dragged by. @see QuickPick#setTitle */
    public static final String TITLE = "Go to File";

    /**
     * How many types to ask for.
     *
     * <p>Larger than the forty {@code TypeIndex} returns per bucket, so <b>our</b> cap is never the one
     * that bites first — a picker that truncated an already-truncated list would report truncation for a
     * reason the index had nothing to do with, and the two limits would have to be kept in step forever.</p>
     */
    private static final int TYPE_LIMIT = 100;

    /**
     * Group weights, lower first: <b>workspace files 100, classpath types 200</b>.
     *
     * <h3>A partition, not a tie-break — and that distinction was measured</h3>
     *
     * <p>This is the PRIMARY sort key: every project file comes before every classpath type, and match
     * quality orders each group internally. The first attempt made it a tie-break under the match score,
     * on the reasoning that a class and the file declaring it match equally and only need a stable order.
     * <b>They do not match equally.</b> A class is {@code ArrayList} and its file is
     * {@code ArrayList.java}, so typing the name is an EXACT hit on the class and a mere prefix on the
     * file — the type wins on quality and the tie-break is never consulted at all. Typing {@code main}
     * therefore returned ten {@code Main} classes out of {@code com.sun.tools} before the {@code Main} in
     * the workspace, with the weights already "corrected" and doing nothing.</p>
     *
     * <p>So the rule has to be stated where it can bite. It is also the reference behaviour: IntelliJ does
     * not blend non-project items into the ranking either — it gates them behind "Include non-project
     * items" and appends them, which is this partition with a switch on it.</p>
     *
     * <p>The cost is real and accepted: a poorly-matching project file outranks a perfect classpath hit.
     * That is the right trade here, where the classpath is the JDK plus a few hundred jars nobody in this
     * workspace wrote, and the wrong one in a product whose classpath IS the project.</p>
     */
    private static final int WEIGHT_FILE = 100;
    private static final int WEIGHT_TYPE = 200;

    /** How many rows either half may contribute. @see #trimTo */
    private static final int MAX_PER_GROUP = 50;

    private GoToFile() {
    }

    /**
     * Opens the picker over the workspace index and every registered {@link TypeSearch} provider.
     *
     * <h3>One instance, reused — and that is what makes the query survive</h3>
     *
     * <p>Rebuilt per invocation there is nothing to retain: closing would discard the text along with the
     * widget. So the workbench holds it ({@code Workbench.quickOpen}) and this wires it once. A reused
     * picker is also why {@code onClosed} disposes nothing — a disposed list cannot be reopened, and a
     * closed popover is already {@code display: none}, which costs no layout and no paint.</p>
     *
     * <p>Repeating a search is ordinary in a way that repeating a <em>command</em> is not, which is why
     * this retains and the command palette does not.</p>
     */
    public static QuickPick open(UIDocument window, Workbench workbench) {
        QuickPick existing = workbench.quickOpen();
        if (existing != null) return existing.open(window);

        QuickPick pick = new QuickPick();
        pick.setPlaceholder(PLACEHOLDER);
        pick.setTitle(TITLE);
        pick.setRetainQuery(true);
        // THE LIST, NOT THE WORKBENCH. Read per query rather than snapshotted at open, so a listing that
        // lands while the picker is up is searchable without reopening it.
        pick.setSource((query, sink) ->
                fetchInto(query, workbench.fileTree().source().knownFiles(), sink));
        pick.onAccepted.connect(id -> {
            long accepted = FrameProfile.enter("ENTER accepted " + id);
            try {
            String member = null;
            int carried = id.indexOf(MEMBER_SEPARATOR);
            if (carried >= 0) {
                member = id.substring(carried + 1);
                id = id.substring(0, carried);
            }
            // THE LOCATION COMES FROM THE QUERY, NOT THE ROW. `Main.java:42` narrows to `Main.java` for
            // matching, so every row is a match for the name and none of them carries the line — which
            // belongs to what was typed rather than to what was found. Read live at accept time rather
            // than stashed when the query ran: the two cannot then disagree.
            TextPoint at = QueryLocation.parse(pick.searchField().getText()).point();
            Resource resource = Resource.parse(id);
            if (resource.isProject()) workbench.openFileAt(resource.asPath(), at);
            else workbench.openResourceAt(resource, at, member);
            } finally {
                FrameProfile.leave(accepted, "ENTER accepted");
            }
        });
        workbench.setQuickOpen(pick);
        return pick.open(window);
    }

    /**
     * Ranks and highlights everything that could answer {@code query}.
     *
     * <h3>Matched on the name, and on the location — but only the name is lit</h3>
     *
     * <p>Both halves are worth having and neither is worth highlighting twice. A folder fragment is a real
     * way to find a file ({@code render/Cg}), and a package is a real way to find a class — so both are
     * matched, at {@code FIELD_CONTEXT}, below the name. Only the name's ranges are lit, which is what
     * both references do: lighting the path would claim it contributed to the ranking when a name hit
     * outranks it outright.</p>
     *
     * <p><b>Public and static so a test can assert the list without a window on screen</b> — which is the
     * part worth pinning, and it needs no pixels. The predecessor {@code itemsFor} was public for the same
     * stated reason; driving this through a real {@code UIDocument} would test the shell rather than the
     * ranking, and the ranking is the part with decisions in it.</p>
     */
    public static void fetchInto(SearchQuery query, List<CgPath> files, ResultSink sink) {
        // AN EMPTY QUERY LISTS NOTHING, which is the one place this diverges from the command palette's
        // "empty means everything". Everything, here, is the workspace plus sixty thousand types: not a
        // list, and not one that could be usefully ordered without a query to order it by.
        String typed = query == null ? "" : query.text();
        QueryLocation location = QueryLocation.parse(typed);
        String name = location.name();
        if (name.isEmpty()) return;

        // MATCH AGAINST THE STRIPPED NAME, not what was typed. Otherwise the first `:` of a pasted
        // `Main.java:42` empties the list, which reads as the search breaking on a keystroke.
        SearchQuery effective = name.equals(typed) ? query : SearchQuery.of(name);

        long profiled = FrameProfile.enter("GoToFile.fetchInto '" + name + "' over "
                + files.size() + " workspace files");
        List<Scored> fileRows = new ArrayList<>();
        long timed = FrameProfile.begin();
        collectFiles(files, effective, fileRows);
        FrameProfile.step(timed, "collectFiles -> " + fileRows.size());
        List<Scored> typeRows = new ArrayList<>();
        timed = FrameProfile.begin();
        collectTypes(name, effective, typeRows);
        FrameProfile.step(timed, "collectTypes -> " + typeRows.size());

        // RANKED WITHIN EACH GROUP, then pushed group by group -- which is the same order the single
        // sort produced and is cheaper to reason about, since the group is now the outer key.
        // @see WEIGHT_FILE for why the group leads and is not a tie-break.
        boolean cut = trimTo(fileRows, MAX_PER_GROUP) | trimTo(typeRows, MAX_PER_GROUP);
        if (cut) sink.markTruncated();

        long pushed = FrameProfile.begin();
        boolean more = push(fileRows, sink);
        if (more) push(typeRows, sink);
        FrameProfile.step(pushed, "push rows");
        FrameProfile.leave(profiled, "GoToFile.fetchInto");
    }

    /**
     * Sorts a group and cuts it to {@code max}.
     *
     * <h3>A cap PER GROUP, because one overall cap starves the second one</h3>
     *
     * <p>Project files are pushed first and every one of them outranks every classpath type
     * ({@link #WEIGHT_FILE}), so a single shared cap is spent on files before a type is ever offered —
     * and a query matching a few hundred filenames would list no classes at all, which reads as the
     * classpath half having broken rather than as a cap. Each group gets its own budget.</p>
     *
     * @return whether anything was cut, which is the only way the caller can know to say so
     */
    private static boolean trimTo(List<Scored> group, int max) {
        group.sort(Comparator.comparing((Scored s) -> s.match,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(s -> s.item.label())
                .thenComparing(s -> s.item.id()));
        if (group.size() <= max) return false;
        group.subList(max, group.size()).clear();
        return true;
    }

    /** Pushes a ranked group. Returns false once the sink has had enough. */
    private static boolean push(List<Scored> group, ResultSink sink) {
        for (Scored s : group) {
            if (!sink.accept(new QuickPickEntry(s.item, rangesOf(s.labelMatch), List.of()))) return false;
        }
        return true;
    }

    /**
     * Separates a resource id from the MEMBER to land on — {@code library://…WorldSettings#GameType}.
     *
     * <p>A {@code #} because it cannot occur in a binary name and reads as a fragment does everywhere
     * else. {@code Resource.parse} never sees it: the split happens before the parse.</p>
     */
    private static final String MEMBER_SEPARATOR = "#";

    /**
     * The row's second line — {@code in WorldSettings of net.minecraft.world} for a nested type.
     *
     * <p>IntelliJ's phrasing, and it says the thing that matters: for a member type the package alone is
     * misleading, because the name a reader has to write goes through the enclosing class. A flat
     * {@code net.minecraft.world.WorldSettings} reads as a package with an odd last segment.</p>
     */
    private static String describe(TypeSearch.Result result) {
        if (!result.isNested()) return result.packageName();
        String enclosing = result.enclosingName();
        String pkg = result.packageOnly();
        return pkg.isEmpty() ? "in " + enclosing : "in " + enclosing + " of " + pkg;
    }

    private static void collectTypes(String name, SearchQuery effective, List<Scored> out) {
        // THE CLASSPATH INDEX -- tens of thousands of types, asked on every keystroke.
        long searched = FrameProfile.begin();
        TypeSearch.Results found = TypeSearchRegistry.search(name, TYPE_LIMIT);
        FrameProfile.step(searched, "TypeSearchRegistry.search");
        for (TypeSearch.Result result : found.results()) {
            // THE FILE THE TYPE LIVES IN, which for a nested type is not the type. A member has no class
            // file of its own, so addressing a `library:` resource by `WorldSettings.GameType` asked the
            // decompiler for a class nothing is called and opened an empty tab. Nested types were absent
            // from the index until recently, which is why one spelling served for both until now.
            //
            // THE MEMBER RIDES ON THE ID after a `#`, because the picker hands back an id and nothing
            // else. Splitting it at accept time keeps the whole round trip inside this class rather than
            // widening QuickPickItem with a field only one of its callers would ever set.
            String id = Resource.of(Resource.SCHEME_LIBRARY, result.topLevelName()).toString()
                    + (result.isNested() ? MEMBER_SEPARATOR + result.simpleName() : "");
            QuickPickItem item = new QuickPickItem(id,
                    result.simpleName(), describe(result), null, null, true,
                    result.kind(), result.isAbstract(), null);
            // OUR MATCHER HAS THE LAST WORD, and the first version did not let it.
            //
            // `TypeIndex.matching` answers in two buckets -- prefix hits, then arbitrary SUBSEQUENCE hits
            // -- and keeping everything it returned meant the subsequence bucket landed in the list
            // wholesale. Typing `main` listed `AlgorithmConstraints`, `AMDMultiDrawIndirect` and
            // `AWTCanvasImplementation`: all of them genuinely contain m-a-i-n in order, and none of them
            // is what anybody meant. `SearchMatcher` refuses scattered subsequences unless a consumer
            // opts in, exactly because "over a few hundred short labels the same rule returns a long tail
            // nobody meant" -- and this list is sixty thousand. So a row the matcher scores as no match
            // is not listed, which is what the file half already did.
            Scored candidate = score(item, effective, WEIGHT_TYPE);
            if (candidate != null) out.add(candidate);
        }
    }

    private static void collectFiles(List<CgPath> files, SearchQuery effective, List<Scored> out) {
        if (files == null) return;
        for (CgPath path : files) {
            CgPath parent = path.parent();
            QuickPickItem item = new QuickPickItem(
                    Resource.of(path).toString(), path.name(),
                    parent == null ? null : parent.toString(), null, null, true,
                    null, false, FileIconTheme.getDefault().iconFor(path.name(), false, false));
            Scored candidate = score(item, effective, WEIGHT_FILE);
            // A FILE IS ONLY LISTED IF IT MATCHED. Unlike a type, nothing narrowed it first -- the whole
            // workspace index is walked here -- so keeping the unmatched ones would list every file in
            // the project under every query.
            if (candidate != null) out.add(candidate);
        }
    }

    /** Scores one candidate against the name and, failing that, its location. Null when neither hit. */
    @Nullable
    private static Scored score(QuickPickItem item, SearchQuery query, int weight) {
        SearchMatch onLabel = SearchMatcher.match(query, item.label(), SearchMatch.FIELD_PRIMARY);
        SearchMatch onWhere = SearchMatcher.match(query, searchableLocation(item.description()),
                SearchMatch.FIELD_CONTEXT);
        SearchMatch best = SearchMatch.best(onLabel, onWhere);
        if (best == null) return null;
        // ONLY THE LABEL'S RANGES ARE KEPT. A description hit ranks the row and does not light anything --
        // lighting it would need ranges against a field the row deliberately never highlights.
        return new Scored(item, best, best == onLabel ? onLabel : null, weight);
    }

    /**
     * The part of a location worth searching — everything after the project id.
     *
     * <h3>A name every row shares can only ever be noise</h3>
     *
     * <p>A file's location is a {@code CgPath}, which reads {@code project:dir/dir}. The project id is
     * common to every file in the workspace, so matching against it makes every query that happens to
     * hit the project name return the ENTIRE workspace — and the partition above then puts all of it
     * ahead of the classpath. Typing {@code Minecraft} in a workspace called {@code minecraft.workspace}
     * listed {@code README.md}, {@code shader.shadergraph} and every {@code .js} file in it, with
     * {@code net.minecraft.client.Minecraft} — an exact hit on the name — last.</p>
     *
     * <p>The rest of the path is genuinely worth matching and is kept: {@code util} finding the files
     * under {@code util} is the reason a location is searched at all, and it is how a qualified query
     * like {@code util/Greeter} lands.</p>
     *
     * <p>Harmless for a type, whose location is a package and carries no colon — so this is one rule
     * rather than a branch on which half of the list a row came from.</p>
     */
    private static String searchableLocation(@Nullable String description) {
        if (description == null) return "";
        int projectEnd = description.indexOf(':');
        return projectEnd < 0 ? description : description.substring(projectEnd + 1);
    }

    private static List<TextRange> rangesOf(@Nullable SearchMatch match) {
        if (match == null) return List.of();
        List<TextRange> ranges = new ArrayList<>(match.ranges().size());
        for (SearchMatch.Range range : match.ranges()) {
            ranges.add(TextRange.of(range.start(), range.end()));
        }
        return ranges;
    }

    /** A candidate, its ranking match, the match to light up (if any), and its tie-break weight. */
    private record Scored(QuickPickItem item, @Nullable SearchMatch match,
                          @Nullable SearchMatch labelMatch, int weight) {
    }
}
