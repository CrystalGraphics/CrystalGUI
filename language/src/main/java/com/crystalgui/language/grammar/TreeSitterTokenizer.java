package com.crystalgui.language.grammar;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Rope;
import com.crystalgui.text.cursor.IndentationProvider;
import com.crystalgui.text.fold.FoldingRangeProvider;
import com.crystalgui.text.fold.FoldingRegions;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import org.treesitter.TSInputEdit;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSPoint;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSQueryPredicate;
import org.treesitter.TSRange;
import org.treesitter.TSTree;

import javax.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Syntax highlighting from a real parse tree.
 *
 * <p>The reason this is worth a native library, when a lexer would colour keywords perfectly well, is
 * that highlighting is not the only consumer of a tree: bracket matching, folding, indent rules and
 * structural selection all want one, and the shader graph will want a GLSL CST for validating node code
 * well outside the editor.</p>
 *
 * <h3>Edits are two phases, and this class keeps them apart</h3>
 * <p>Zed's {@code SyntaxMap} splits an edit into <em>interpolate</em> — apply a {@code TSInputEdit} so
 * every existing node's coordinates move with the text, no parsing — and <em>reparse</em>, which is the
 * expensive part and which it runs off the UI thread. Applying only one of the two is the trap:
 * reparse-only stutters while typing, interpolate-only leaves the tree structurally stale.</p>
 *
 * <p>{@link #edited} does both, synchronously, because our documents are shader snippets and single files
 * rather than repositories. They are kept as <b>separate statements</b> so the reparse can move to a
 * worker later without touching the edit path — which is the whole reason for noting it here rather than
 * writing one line that does both.</p>
 *
 * <h3>Everything is UTF-8 byte offsets</h3>
 * <p>tree-sitter counts bytes; this engine counts UTF-16 code units. Every crossing is converted, through
 * {@link Utf8Offsets} — which also records why parsing UTF-16 directly is not available here, and why the
 * conversion this class used to do inline was quadratic.</p>
 *
 * <h3>Reparsing is gated on identity, not on comparison</h3>
 * <p>{@link #tokenize} used to flatten the rope with {@code toString()} and compare it to the last parsed
 * text on <b>every query</b> — an O(n) copy plus an O(n) compare per viewport repaint, to answer a
 * question the caller already knew the answer to. The document announces its changes through
 * {@link #edited}; a query now trusts that and re-parses only when something said so.</p>
 */
public final class TreeSitterTokenizer
        implements SyntaxTokenizer, FoldingRangeProvider, IndentationProvider {

    /**
     * How much text a single query may cover.
     *
     * <p>Zed caps its own at 16KB for the same reason: a query over a whole large file is slow enough to
     * be felt, and the editor only ever renders a viewport's worth. The caller already asks for a bounded
     * range; this is the backstop for one that does not.</p>
     */
    private static final int MAX_BYTES_TO_QUERY = 16 * 1024;

    private final TSLanguage language;
    private final TSQuery query;
    private final TSParser parser;

    /**
     * Where reparses run, or {@code null} to parse on the calling thread.
     *
     * <p>Opt-in rather than mandatory: a caller that just wants tokens out of a string — every test in
     * this module, and the shader graph validating a snippet — should not have to own a scheduler and
     * pump a frame loop to get an answer.</p>
     */
    private final JobScheduler scheduler;

    /**
     * The parser reparses run on, distinct from {@link #parser} because the two are used from different
     * threads. Never shared: {@code TSParser} holds mutable native state for the parse in progress.
     *
     * <p>One is enough, and only because the scheduler guarantees single-flight per key — at most one
     * reparse for this tokenizer is ever in flight, so this needs no lock of its own. If that guarantee
     * ever weakens, this becomes a race that manifests as a JVM crash rather than an exception.</p>
     */
    private TSParser workerParser;

    private InvalidationListener invalidationListener;

    /**
     * Capture names by index, resolved once.
     *
     * <p>{@code getCaptureNameForId} is a JNI call that builds a {@code String}, and it was being made
     * <b>per captured token</b> — thousands of times per viewport query — to look up one of a dozen fixed
     * names that cannot change for the life of the query. Interning them here also means the strings
     * handed out are reference-equal, which is what lets a consumer key a cache on them.</p>
     */
    private final String[] captureNames;

    /**
     * Text conditions lifted out of the query, applied here because the pattern that carried them could
     * not fire at all. See {@code Queries.liftUnambiguousPredicates} — a name is only in this map when
     * every use of it in the query was guarded by the same test.
     */
    private final Map<String, Pattern> captureFilters;

    /**
     * The lifted {@code #match?} tests, keyed by the PATTERN that carried each one.
     *
     * <p>By pattern rather than by capture name, which is what lets a capture used both guarded and bare
     * keep both meanings — see {@code Queries.filtersByPattern}. Keyed by name alone,
     * {@code @type}’s four guarded patterns could not be lifted at all and contributed nothing.</p>
     */
    private final Map<Integer, Map<String, Pattern>> patternFilters;

    /**
     * The injections query, or {@code null} for a language that embeds nothing.
     *
     * <p>HTML is not one language: {@code <style>} is CSS and {@code <script>} is JavaScript, and a
     * highlighter that colours those bodies as markup text is worse than one that leaves them plain —
     * it asserts something false about them.</p>
     */
    private TSQuery injectionQuery;

    /** Child tokenizers by injected language name, built on first use and reused. */
    private final java.util.Map<String, TreeSitterTokenizer> injected = new java.util.HashMap<>();

    private TSTree tree;

    /** The text the current {@link #tree} describes, and the offset index over it. */
    private Utf8Offsets offsets = Utf8Offsets.EMPTY;

    /**
     * Whether the tree is known to be behind the document.
     *
     * <p>Set by {@link #edited}, cleared by a reparse. This is what replaced comparing the whole document
     * against the last parsed text on every query — the caller already tells us when it changes, so
     * asking the text again was answering a question we had been handed.</p>
     */
    private boolean stale = true;

    /** Reused across queries — cursors are not cheap and there is one per query per frame. */
    private final TSQueryCursor cursor = new TSQueryCursor();

    public TreeSitterTokenizer(TSLanguage language, String highlightQuery) {
        this(language, highlightQuery, null);
    }

    /**
     * @param scheduler where reparses run. With one, {@link #edited} returns as soon as the tree has been
     *                  interpolated and the parse lands a few frames later; without one, everything is
     *                  synchronous. See the field note.
     */
    public TreeSitterTokenizer(TSLanguage language, String highlightQuery, JobScheduler scheduler) {
        this(language, new Queries.Prepared(highlightQuery, java.util.Collections.emptyMap(),
                java.util.Collections.emptyList()), scheduler);
    }

    TreeSitterTokenizer(TSLanguage language, Queries.Prepared prepared, JobScheduler scheduler) {
        this.language = language;
        this.parser = new TSParser();
        this.parser.setLanguage(language);
        this.query = new TSQuery(language, prepared.text());
        this.patternFilters = Queries.filtersByPattern(this.query, prepared);
        this.captureFilters = prepared.captureFilters();
        this.scheduler = scheduler;

        this.captureNames = new String[query.getCaptureCount()];
        for (int i = 0; i < captureNames.length; i++) {
            String name = query.getCaptureNameForId(i);
            captureNames[i] = name == null ? null : name.intern();
        }
    }

    /**
     * Java, parsing on the calling thread — the convenience the tests and the harness use.
     *
     * <p>The only per-language factory left. Every other one was identical but for two values, so they
     * became rows in {@link Grammar} and the way to get a tokenizer is
     * {@code Grammar.CSS.newTokenizer(scheduler)}. This one survives because {@code java()} with no
     * argument reads better than the table lookup at the dozens of call sites that want exactly it.</p>
     */
    public static TreeSitterTokenizer java() {
        return Grammar.JAVA.newTokenizer(null);
    }

    /**
     * Turns on embedded-language highlighting, using the grammar's own {@code injections.scm}.
     *
     * <p>Children come from the host's own row rather than being named here, so HTML is not a special
     * case in this class — a second injecting grammar needs no code, only a row.</p>
     *
     * <p>Failure is deliberately quiet: a missing or unparseable injections query leaves the host
     * highlighting exactly as it was, which is degraded rather than broken. The alternative is a language
     * that fails to open because one of its two queries did not compile.</p>
     *
     * <p><b>One level, not recursive.</b> A child is built with no injections of its own, which covers
     * every case the shipped six produce. Markdown hosting HTML hosting JavaScript would need a depth
     * guard that does not exist yet — said here rather than left to be discovered.</p>
     */
    void withInjections(Grammar host, JobScheduler scheduler) {
        try {
            this.injectionQuery = new TSQuery(language, Queries.load(host.queryPath("injections.scm")));
            for (Grammar child : host.injectedGrammars()) {
                this.injected.put(child.directory(), new TreeSitterTokenizer(child.newParser(),
                        Queries.loadForHighlighting(child.queryPath("highlights.scm")), scheduler));
            }
        } catch (RuntimeException unavailable) {
            this.injectionQuery = null;
        }
    }

    // ── Folding ───────────────────────────────────────────────────────────────

    /**
     * {@code folds.scm}, or null for a grammar that ships none.
     *
     * <p>Built on the first fold query rather than in the constructor, because most tokenizers are made
     * for highlighting and never asked: a {@code TSQuery} is a native compile of a text file, and paying
     * for it per document to answer a question nobody asks is the kind of cost that only shows up as a
     * slow open.</p>
     */
    private TSQuery foldQuery;
    private boolean foldQueryBuilt;

    /**
     * Foldable regions from the parse tree, replacing the indentation guess.
     *
     * <h3>Why this is on the tokenizer rather than beside it</h3>
     *
     * <p>Folding needs the same tree highlighting needs, and this class already owns one: it is parsed
     * on open, interpolated on every keystroke and reparsed off the UI thread. A separate provider would
     * mean a second parser, a second tree and a second reparse per keystroke for the same document — and
     * the two would disagree for the handful of frames between them, which is the window a fold arrow is
     * most likely to be clicked in.</p>
     *
     * <p>The convention is the simple one and is agreed across Neovim, Helix and Pulsar: a single
     * {@code @fold} capture on a node, and the region is that node's own extent. Nothing here decides
     * <em>how</em> a fold is shown — a collapsed region keeping its first row visible, and the
     * prefix-sum walk that must not binary-search over hidden rows, are both {@code FoldingRegions}'
     * and were already true of the indentation provider.</p>
     */
    @Override
    public FoldingRegions compute(Rope document, int tabSize) {
        TSQuery folds = foldQuery();
        if (folds == null) return FoldingRegions.empty();
        if (deferInitialParse(document)) return FoldingRegions.empty();
        if (tree == null || (stale && scheduler == null)) reparse(forParser(document.toString()));
        if (tree == null) return FoldingRegions.empty();

        // ALREADY COMPUTED, on the worker that built this tree. @see #foldsOnWorker
        if (foldRanges != null) return foldingRegionsOf(foldRanges, document.lineCount());
        return foldingRegionsOf(foldRangesOf(folds, tree, cursor), document.lineCount());
    }

    /**
     * The fold query over the whole tree — the expensive half, extracted so a worker can run it.
     *
     * <p><b>The whole document, unlike highlighting.</b> A fold arrow is drawn in the gutter for a region
     * that may start above the viewport and end below it, and the outline a reader folds against is the
     * file's rather than the screen's — so the byte cap that bounds a per-viewport highlight query would
     * silently truncate the outline of any file longer than 16KB.</p>
     */
    private static List<int[]> foldRangesOf(TSQuery folds, TSTree tree, TSQueryCursor cursor) {
        cursor.setByteRange(0, Integer.MAX_VALUE);
        cursor.exec(folds, tree.getRootNode());

        List<int[]> ranges = new ArrayList<>();
        TSQueryMatch match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            for (TSQueryCapture capture : match.getCaptures()) {
                TSNode node = capture.getNode();
                if (node == null || node.isNull()) continue;
                int startRow = node.getStartPoint().getRow();
                int endRow = node.getEndPoint().getRow();
                // A NODE THAT ENDS ON ITS OWN LINE IS NOT FOLDABLE, whatever the query says: there is
                // nothing to hide, and an arrow beside it is an affordance that does nothing when clicked.
                if (endRow <= startRow) continue;
                ranges.add(new int[]{startRow, endRow});
            }
        }
        return ranges;
    }

    /**
     * The fold query, run on the worker that just built the tree — the third pass to move here.
     *
     * <h3>Why it is safe, and why it was worth moving</h3>
     *
     * <p>Same argument as {@link #localsOnWorker}: the tree has just been parsed by this worker, nothing
     * else holds a reference, and it is handed over only afterwards. That is what makes an off-thread
     * query of a native tree safe at all — and it is why folding could NOT simply be scheduled from
     * {@code EditorFolding}, where the provider may be this tokenizer and the frame thread is querying
     * the same tree.</p>
     *
     * <p>Measured on the frame that opens a 2,020-line class: {@code ed:refreshFolding} at <b>12.4ms</b>,
     * the largest unattributed part of {@code ed:updateWindow}. It is a pure function of the tree —
     * {@code tabSize} appears in {@code compute}'s signature and nowhere in its body, so the result
     * cannot depend on anything the worker does not have.</p>
     */
    private List<int[]> foldsOnWorker(TSTree parsed) {
        TSQuery folds = foldQuery();
        if (folds == null) return null;
        try {
            return foldRangesOf(folds, parsed, new TSQueryCursor());
        } catch (RuntimeException failed) {
            // Null means "ask on the frame thread as before" -- slow rather than wrong. An outline is a
            // decoration and must not be able to take a parse down with it.
            return null;
        }
    }

    /** Fold ranges for the current tree, or null to query on demand. @see #foldsOnWorker */
    private List<int[]> foldRanges;

    /**
     * The captured ranges as {@link FoldingRegions} wants them: sorted by start row and strictly nested.
     *
     * <p>Both properties are load-bearing rather than tidy — {@code FoldingRegions} binary searches the
     * list and packs parent indices on the assumption, so an out-of-order provider produces wrong answers
     * instead of an exception. A query yields matches in the cursor's own order, which is neither.</p>
     *
     * <p><b>One region per start row.</b> Several nodes routinely begin on one line — a method
     * declaration and its body block, a rule set and its block — and they would be two arrows in one
     * gutter cell and two entries claiming the same handle. The widest wins, which is the outer construct
     * and the one a reader means.</p>
     */
    private static FoldingRegions foldingRegionsOf(List<int[]> ranges, int lineCount) {
        ranges.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(b[1], a[1]));

        List<int[]> kept = new ArrayList<>(ranges.size());
        int lastStart = -1;
        // The end rows of the regions still open above this one, so nesting can be enforced by construction
        // rather than checked afterwards.
        List<Integer> openEnds = new ArrayList<>();
        for (int[] range : ranges) {
            int start = range[0];
            int end = Math.min(range[1], Math.max(0, lineCount - 1));
            if (start == lastStart || end <= start) continue;

            while (!openEnds.isEmpty() && openEnds.get(openEnds.size() - 1) < start) {
                openEnds.remove(openEnds.size() - 1);
            }
            // A REGION THAT ESCAPES ITS PARENT IS DROPPED, not clamped. Overlapping-but-not-nested ranges
            // are what a query produces when two patterns describe the same construct differently, and
            // clamping one to fit invents a region the grammar never claimed.
            if (!openEnds.isEmpty() && end > openEnds.get(openEnds.size() - 1)) continue;

            kept.add(new int[]{start, end});
            openEnds.add(end);
            lastStart = start;
        }

        int[] starts = new int[kept.size()];
        int[] ends = new int[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            starts[i] = kept.get(i)[0];
            ends[i] = kept.get(i)[1];
        }
        return new FoldingRegions(starts, ends);
    }

    @Nullable
    private TSQuery foldQuery() {
        if (foldQueryBuilt) return foldQuery;
        foldQueryBuilt = true;
        foldQuery = compileFamily("folds");
        return foldQuery;
    }

    /**
     * A query family for this grammar, or null.
     *
     * <p>Quiet on failure, deliberately: a grammar that ships no {@code folds.scm}, or one whose file
     * names a node this parser version does not have, keeps the fallback it already had. The alternative
     * is a language that fails to open because one of its optional queries did not compile.</p>
     */
    /** For the test that asserts every vendored family compiles against the grammar it was written for. */
    @Nullable
    TSQuery compileFamilyForTesting(String family) {
        return compileFamily(family);
    }

    @Nullable
    private TSQuery compileFamily(String family) {
        if (grammarDirectory == null) return null;
        String text = Queries.loadFamily(grammarDirectory, family);
        if (text == null || text.isBlank()) return null;
        try {
            return new TSQuery(language, text);
        } catch (RuntimeException unusable) {
            return null;
        }
    }

    /**
     * Which vendored query directory this tokenizer reads its optional families from, or null.
     *
     * <p>Null for a tokenizer built straight from a query string — every test that passes its own query,
     * and the injected children, which are built for one embedded region and never asked to fold.</p>
     */
    @Nullable
    private String grammarDirectory;

    /** Called by {@link Grammar#newTokenizer}, which is the only place that knows the directory. */
    void readFamiliesFrom(Grammar grammar) {
        this.grammarDirectory = grammar.directory();
    }

    @Override
    public void setInvalidationListener(InvalidationListener listener) {
        this.invalidationListener = listener;
    }

    // ── Parsing ─────────────────────────────────────────────────────────────────────────────────

    @Override
    public List<SyntaxToken> tokenize(Rope document, int from, int to) {
        // With a scheduler, a stale tree is answered from anyway: it was interpolated on the keystroke, so
        // it is structurally behind but positionally correct, which is exactly what every editor shows for
        // the handful of frames a parse takes. Blocking here instead would put the whole ~17ms back on the
        // frame and defeat the point of having somewhere else to run it.
        if (deferInitialParse(document)) return List.of();
        if (tree == null || (stale && scheduler == null)) reparse(forParser(document.toString()));
        if (tree == null) return List.of();

        int startByte = offsets.toUtf8(from);
        int endByte = offsets.toUtf8(to);
        endByte = Math.min(endByte, startByte + MAX_BYTES_TO_QUERY);

        cursor.setByteRange(startByte, endByte);
        cursor.exec(query, tree.getRootNode());

        // Paired with their pattern index, because that -- not the order the cursor happens to yield
        // them in -- is what decides precedence. See the sort below.
        List<int[]> order = new ArrayList<>();
        List<SyntaxToken> tokens = new ArrayList<>();
        TSQueryMatch match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            if (!predicatesHold(match)) continue;
            int patternIndex = match.getPatternIndex();
            for (TSQueryCapture capture : match.getCaptures()) {
                int index = capture.getIndex();
                String name = index >= 0 && index < captureNames.length ? captureNames[index] : null;
                if (name == null || name.isEmpty()) continue;
                TSNode node = capture.getNode();
                // The lifted predicate, re-applied. Without it the SCREAMING_CASE test that separates a
                // constant from an ordinary identifier would be gone entirely, and every identifier in
                // the file would be captured as a constant.
                Map<String, Pattern> guards = patternFilters.get(patternIndex);
                Pattern filter = guards == null ? captureFilters.get(name) : guards.get(name);
                if (filter != null && !filter.matcher(textOf(node)).find()) continue;
                int start = offsets.toUtf16(node.getStartByte());
                int end = offsets.toUtf16(node.getEndByte());
                if (end > start) {
                    order.add(new int[]{patternIndex, tokens.size(), isCatchAll(name) ? 0 : 1});
                    tokens.add(new SyntaxToken(start, end, name));
                }
            }
        }

        // THE CATCH-ALL LOSES; everything else is ordered by pattern index.
        //
        // A consumer resolves two captures on one node by taking the later one, so this order IS the
        // precedence. The cursor's own order does not encode it -- it yields matches in node order, which
        // interleaves patterns arbitrarily: measured, one document gave `label` as
        // [function.method, variable] and `TRACE` as [variable, constant], so taking the last made a
        // constant purple and a method plain from the same rule.
        //
        // Sorting by pattern index alone was the first attempt and is WRONG, because where a grammar puts
        // its blanket capture is a matter of that author's taste rather than a convention. Java writes
        // `(identifier) @variable` as its FIRST pattern and refines it afterwards; GLSL writes the same
        // line 79th, AFTER the function and property patterns. Index order therefore made GLSL's catch-all
        // outrank everything and the whole file went one colour -- a regression that looked exactly like
        // the bug it was meant to fix, from the opposite direction.
        //
        // What both authors mean is the same thing: the catch-all is what a grammar says when it has
        // nothing more specific to offer, so it must lose to anything that has. Encoding that is stating
        // the convention rather than working around it, and it is stable under either house style.
        //
        // Stable on the original index within a rank, so the result is deterministic rather than merely
        // correct on average.
        order.sort((left, right) -> {
            if (left[2] != right[2]) return Integer.compare(left[2], right[2]);
            if (left[0] != right[0]) return Integer.compare(left[0], right[0]);
            return Integer.compare(left[1], right[1]);
        });
        List<SyntaxToken> sorted = new ArrayList<>(tokens.size());
        for (int[] entry : order) sorted.add(tokens.get(entry[1]));

        // AFTER the host's own tokens, so an injected range wins on the shared last-write-wins rule. The
        // host captures the whole <script> body as raw text; the injected language then says what is
        // actually in it, and that is the more specific answer.
        appendInjected(sorted, startByte, endByte);

        // AND WHAT THE SCOPES SAY, LAST, so it wins over the blanket `@variable` a highlight query gives
        // every identifier. That is the whole point of the family: `count` the parameter and `count` the
        // field are one shape and three colours, and only a resolution answer separates them.
        //
        // Still GRAMMAR-tier, though, which is what putting it here rather than in a SemanticTokenProvider
        // means: the editor clears every grammar token under a semantic one before adding any, so an
        // engine's answer still outranks this by construction. Getting that backwards would read as a
        // colour-scheme bug rather than an ordering one, because both names resolve to real colours.
        appendLocals(sorted, startByte, endByte);
        return sorted;
    }

    // ── Indentation ─────────────────────────────────────────────────────────────────────────────

    /** {@code indents.scm}, or null for a grammar that ships none. @see #foldQuery */
    private TSQuery indentQuery;
    private boolean indentQueryBuilt;
    private String[] indentCaptureNames;

    @Override
    public int levelsAfterRow(Rope document, int row) {
        if (!prepareIndents(document) || row < 0 || row >= document.lineCount()) return -1;
        int lineStart = document.lineStartOffset(row);
        int endOfRow = lineStart + document.line(row).length();
        return TreeIndents.levelsAfterRow(tree, indentQuery, indentCaptureNames, row,
                offsets.toUtf8(Math.max(lineStart, endOfRow - 1)));
    }

    @Override
    public int levelsAtRow(Rope document, int row) {
        if (!prepareIndents(document) || row < 0 || row >= document.lineCount()) return -1;
        return TreeIndents.levelsAtRow(tree, indentQuery, indentCaptureNames, row,
                offsets.toUtf8(document.lineStartOffset(row)));
    }

    /** Whether there is a tree and an indent query to ask. Builds both on first use. */
    private boolean prepareIndents(Rope document) {
        if (!indentQueryBuilt) {
            indentQueryBuilt = true;
            indentQuery = compileFamily("indents");
            if (indentQuery != null) {
                indentCaptureNames = new String[indentQuery.getCaptureCount()];
                for (int i = 0; i < indentCaptureNames.length; i++) {
                    String name = indentQuery.getCaptureNameForId(i);
                    indentCaptureNames[i] = name == null ? null : name.intern();
                }
            }
        }
        if (indentQuery == null) return false;
        // A STALE TREE IS NOT GOOD ENOUGH HERE, unlike for highlighting. Enter is pressed once and the
        // answer is written into the document, where a wrong indent stays until somebody fixes it by
        // hand -- so this is the one query that waits for the parse rather than showing a frame of
        // slightly-behind colour.
        if (tree == null || stale) reparse(forParser(document.toString()));
        return tree != null;
    }

    /** {@code locals.scm}, or null for a grammar that ships none. @see #foldQuery */
    private TSQuery localsQuery;
    private boolean localsQueryBuilt;

    /**
     * Adds the scope-resolved colours, when this grammar ships a {@code locals.scm}.
     *
     * <p>A cursor of its own, because the shared one is mid-walk: this runs from inside {@code tokenize},
     * which has just finished with it, but the query underneath is executed over the WHOLE tree rather
     * than the queried span (a reference is defined by a declaration that is usually off screen) — so
     * reusing it would leave the byte range set to the file for whoever asked next.</p>
     */
    private void appendLocals(List<SyntaxToken> tokens, int startByte, int endByte) {
        if (localsCaptureNames == null) {
            if (localsQueryBuilt) return;
            localsQueryBuilt = true;
            localsQuery = compileFamily("locals");
            if (localsQuery == null) return;
            localsCaptureNames = new String[localsQuery.getCaptureCount()];
            for (int i = 0; i < localsCaptureNames.length; i++) {
                String name = localsQuery.getCaptureNameForId(i);
                localsCaptureNames[i] = name == null ? null : name.intern();
            }
        }
        if (localsQuery == null || tree == null) return;

        for (SyntaxToken local : localsForTree()) {
            if (local.end() <= startByteToUtf16(startByte) || local.start() >= startByteToUtf16(endByte)) {
                continue;
            }
            // ONLY WHERE THE GRAMMAR HAD NOTHING BETTER TO SAY. A scope answer is "which KIND of variable
            // this is", which is a refinement of the blanket `@variable` and NOT a second opinion about
            // whether something is a constant or a method: `PI` is captured `@constant` by a rule that
            // tested its spelling, and `@local.definition.var` would overwrite that with `variable` purely
            // by arriving later. The catch-all is the one answer this may improve on -- which is the same
            // rule the pattern-index sort already encodes for the highlight query itself.
            if (!coveredOnlyByCatchAll(tokens, local)) continue;
            tokens.add(local);
        }
    }

    /**
     * Whether every token already covering {@code local}'s span is a catch-all.
     *
     * <p>Asked of the SPAN rather than of an exact range match, because a highlight query and a locals
     * query need not agree about node boundaries — one may capture the identifier and the other the
     * declarator around it.</p>
     */
    private static boolean coveredOnlyByCatchAll(List<SyntaxToken> tokens, SyntaxToken local) {
        for (SyntaxToken existing : tokens) {
            if (existing.end() <= local.start() || existing.start() >= local.end()) continue;
            if (!isCatchAll(existing.name())) return false;
        }
        return true;
    }

    /**
     * The scope-derived tokens for the current tree, computed once per parse.
     *
     * <p><b>Cached, and that is not an optimisation.</b> The locals query runs over the WHOLE file by
     * necessity — a reference in the viewport is declared somewhere that usually is not — while
     * {@code tokenize} is called per viewport repaint. Running it per call put a whole-document query on
     * every paint: measured at 1000 repeat queries taking 87 seconds against a first query of 137ms,
     * which is the shape of the reparse-per-query bug this class already has a test for.</p>
     */
    private List<SyntaxToken> localsForTree() {
        if (localsTokens != null) return localsTokens;
        TSQueryCursor localsCursor = new TSQueryCursor();
        localsTokens = LocalScopes.tokensIn(tree, localsQuery, localsCaptureNames, localsCursor, offsets,
                0, Integer.MAX_VALUE);
        return localsTokens;
    }

    /** Cleared whenever the tree is replaced. @see #localsForTree */
    private List<SyntaxToken> localsTokens;

    private int startByteToUtf16(int byteOffset) {
        return byteOffset == Integer.MAX_VALUE ? Integer.MAX_VALUE : offsets.toUtf16(byteOffset);
    }

    private String[] localsCaptureNames;

    /**
     * Runs each injected region through its own grammar and adds the result, rebased onto this document.
     *
     * <p><b>The injected text is tokenized standalone and its offsets shifted</b>, rather than parsed in
     * place through tree-sitter's included-ranges API. Both are correct; this one is a great deal simpler,
     * and the cost is re-parsing a region that is small by construction — a {@code <style>} or
     * {@code <script>} body, not a document. Included ranges become worth it when a language injects
     * hundreds of fragments (a templating language interleaving expressions with text), which none of the
     * grammars here does.</p>
     *
     * <p>Bounded to the queried range for the same reason the host query is: an HTML file may hold several
     * script blocks and only the visible one should be parsed.</p>
     */
    private void appendInjected(List<SyntaxToken> tokens, int startByte, int endByte) {
        if (injectionQuery == null || tree == null) return;

        TSQueryCursor injectionCursor = new TSQueryCursor();
        injectionCursor.setByteRange(startByte, endByte);
        injectionCursor.exec(injectionQuery, tree.getRootNode());

        TSQueryMatch match = new TSQueryMatch();
        while (injectionCursor.nextMatch(match)) {
            String languageName = match.getMetadata() == null
                    ? null : match.getMetadata().get("injection.language");
            TreeSitterTokenizer child = languageName == null ? null : injected.get(languageName);
            if (child == null) continue;

            for (TSQueryCapture capture : match.getCaptures()) {
                TSNode node = capture.getNode();
                int from = offsets.toUtf16(node.getStartByte());
                int to = offsets.toUtf16(node.getEndByte());
                if (to <= from) continue;

                String fragment = offsets.text().substring(from, to);
                for (SyntaxToken token : child.tokenize(Rope.of(fragment), 0, fragment.length())) {
                    // Rebased onto the host document. Without the shift every injected colour lands at the
                    // top of the file, which reads as the injection working and the offsets being random.
                    tokens.add(new SyntaxToken(from + token.start(), from + token.end(), token.name()));
                }
            }
        }
    }

    /**
     * Whether a match's {@code #match?} / {@code #eq?} predicates hold.
     *
     * <h3>tree-sitter does not evaluate these, and that is not a gap in the binding</h3>
     * <p>The C library matches <em>structure</em>; predicates are text conditions and are the client's
     * job by design — every consumer (Neovim, Zed, the Rust {@code tree-sitter-highlight} crate) evaluates
     * them itself. Skipping that step does not produce a warning; it produces a query that quietly means
     * something else.</p>
     *
     * <p><b>What it cost here.</b> The Java grammar identifies constants with
     * {@code ((identifier) @constant (#match? @constant "^_*[A-Z][A-Z\\d_]+$"))} — a SCREAMING_CASE test,
     * because a grammar cannot otherwise tell {@code MAX_RETRIES} from {@code retries}. With the predicate
     * unevaluated that pattern never contributed a usable capture, so every constant, enum constant and
     * static field in the language rendered as a plain identifier. Against IntelliJ, whose palette makes
     * those purple and italic, that is one of the most visible differences on screen — and it looked like
     * a missing colour rather than a missing predicate.</p>
     *
     * <p>Text is fetched per node rather than handed the whole document: a predicate tests one identifier,
     * and the alternative is materialising the file for every match.</p>
     */
    private boolean predicatesHold(TSQueryMatch match) {
        List<TSQueryPredicate> predicates = query.getPredicatesForPattern(match.getPatternIndex());
        if (predicates == null || predicates.isEmpty()) return true;
        for (TSQueryPredicate predicate : predicates) {
            try {
                if (!predicate.test(match, this::textOf)) return false;
            } catch (RuntimeException unsupported) {
                // An unrecognised predicate must not delete the capture: over-reporting shows a colour
                // that may be slightly wrong, under-reporting shows none at all and looks like the
                // grammar failing. The same asymmetry the invalidation path already argues.
                return true;
            }
        }
        return true;
    }

    /**
     * Whether a capture name is the vocabulary's catch-all — the thing a grammar says about an identifier
     * when it has nothing more specific to say.
     *
     * <p>Exactly {@code variable}, and deliberately not its specialisations: {@code variable.builtin} and
     * {@code variable.parameter} are statements, not shrugs, and must outrank the bare form the same way
     * {@code constant} does.</p>
     */
    private static boolean isCatchAll(String captureName) {
        return "variable".equals(captureName);
    }

    /** The source text a node covers, in the document last parsed. */
    private String textOf(TSNode node) {
        String source = offsets.text();
        int start = offsets.toUtf16(node.getStartByte());
        int end = offsets.toUtf16(node.getEndByte());
        if (start < 0 || end > source.length() || end < start) return "";
        return source.substring(start, end);
    }

    @Override
    public void edited(Rope after, ChangeSet change) {
        if (change == null || change.isEmpty()) return;

        // PHASE 1 -- interpolate. Move every existing node's coordinates so the tree still describes the
        // text, without parsing anything. Cheap, and what keeps highlights attached to the right
        // characters the instant a key lands.
        //
        // Applied against the offsets of the text the tree currently describes, LAST CHANGE FIRST --
        // every Change in a set is expressed against the document the set applies to, so an earlier one
        // must not be allowed to move a later one's coordinates. @see the note on the loop below.
        long interpolated = FrameProfile.begin();
        // A FULL REPLACEMENT HAS NOTHING TO INTERPOLATE. Phase 1 exists to keep an existing tree
        // describing the text while a reparse catches up -- move every node's coordinates so highlights
        // stay attached to the right characters. When the change replaces the ENTIRE document that
        // premise is gone: not one node survives, and the shifted tree describes text nobody will ever
        // query before the reparse replaces it wholesale.
        //
        // It is also the expensive case, because the edit describes the whole file. Measured on a
        // document load: ts.inputEditFor 3,907us of a 4,221us interpolate, while the native tree.edit
        // beneath it never reached 100us -- the cost is walking the insert to describe an edit that
        // will be thrown away. Rewriting that walk to one allocation-free pass moved it by 64us, which
        // is what said the work itself was not the problem: DOING it was.
        if (tree != null && replacesWholeDocument(change)) {
            FrameProfile.note("ts.interpolate skipped -- whole document replaced");
            // AND THE TREE IS DROPPED, not merely left uninterpolated.
            //
            // Skipping phase 1 skips `tree.edit()`, and an unedited tree is exactly what tree-sitter
            // reads as "this document did not change". `ts_parser_parse` takes the old tree as the
            // description of what the text used to be and reuses every node the edits did not touch --
            // so handing it one that was never told about the edit does not merely lose the speed-up,
            // it makes the parser REUSE THE WHOLE OLD TREE. The result parses as the previous document,
            // however different the text is.
            //
            // It is the same fact from both ends: the reason there was nothing to interpolate is the
            // reason there is nothing to parse incrementally from. Phase 1 and the snapshot are two
            // halves of one mechanism and either both apply or neither does.
            //
            // What that looked like: a viewer whose grammar colours never arrived. Keywords, comments
            // and primitive types plain, while types, methods and fields were correct -- because those
            // come from the ENGINE, and semantic tokens replace grammar tokens where they overlap
            // rather than depending on them. So the file looked half-highlighted rather than unparsed,
            // which reads as a colour-scheme fault. Trace: `ts.firstParse deferred to a worker, 0 chars`
            // (the tokenizer was queried while the buffer was still empty), then
            // `ts.interpolate skipped -- whole document replaced` when the text arrived, and `ed:tokenize`
            // never called again -- the reparse landed a tree identical to the empty one it was given, so
            // `announceChanged` found nothing to announce and nothing re-queried.
            //
            // Nulling it rather than passing a flag also fixes the announcement: `announceChanged` diffs
            // against the tree that was replaced, and two unrelated trees have no meaningful
            // `changed_ranges`. A null one is documented to report the whole document, which is the truth
            // here.
            tree = null;
            stale = true;
            if (scheduler != null) scheduleReparse(after);
            return;
        }
        if (tree != null) {
            // SPLIT: building the edit is ours, applying it is native. Removing two full UTF-8 encodings
            // of the insert changed ts.interpolate by 64us of 4,341us, so the cost is not on our side --
            // but "not the encoding" is not the same as knowing which, and ts_tree_edit walks the tree to
            // shift every node's position.
            long built = 0L;
            long applied = 0L;
            // BACK TO FRONT, and this is the whole of what a ChangeSet means.
            //
            // Every Change in a set is expressed against the document the set applies TO -- its own
            // javadoc says so, "never in the document it produces" -- and that is what lets a set be
            // composed and inverted without carrying a document around. `ChangeSet` enforces that they
            // are sorted and non-overlapping, so applying the LAST one first leaves every earlier one's
            // offsets still valid, while applying the first one first moves the tree out from under
            // every offset behind it.
            //
            // Front to back, one edit desynchronised the tree by the length of the one before it, and
            // tree-sitter has no way to notice: `ts_tree_edit` is told where text changed and believes
            // it. The parse that follows reuses nodes at coordinates that no longer describe anything,
            // so every token after the first change comes back at the wrong offsets -- for the rest of
            // the session, since nothing re-parses from scratch.
            //
            // Reached by ACCEPTING A COMPLETION for an unimported type, which is one ChangeSet of two:
            // the import statement at the top of the file and the name at the caret. The name's own row
            // then coloured as `[0,1)` over a space and `[11,15)` over the tail of the word -- reported
            // as the typed prefix staying red while the inserted remainder was correct. Any multi-change
            // edit reaches it: a multi-caret insert, a replace-all, any quick fix that also writes an
            // import.
            List<Change> ordered = change.changes();
            for (int i = ordered.size() - 1; i >= 0; i--) {
                long t0 = FrameProfile.begin();
                TSInputEdit edit = inputEditFor(ordered.get(i));
                long t1 = FrameProfile.begin();
                tree.edit(edit);
                if (t0 != 0L) {
                    built += t1 - t0;
                    applied += System.nanoTime() - t1;
                }
            }
            FrameProfile.report(built, "ts.inputEditFor x" + change.changes().size());
            FrameProfile.report(applied, "ts.tree.edit x" + change.changes().size() + " (NATIVE)");
        }
        FrameProfile.step(interpolated, "ts.interpolate x" + change.changes().size()
                + (tree == null ? " (no tree)" : ""));

        // PHASE 2 -- reparse. The expensive half: measured at ~17ms average and ~26ms worst per keystroke
        // on a 5,000-line file, against a budget of 2ms. It goes to a worker when there is one, and is
        // otherwise deferred to the next query so that a burst of keystrokes still costs one parse rather
        // than one per key.
        stale = true;
        long scheduled = FrameProfile.begin();
        if (scheduler != null) scheduleReparse(after);
        // SUBMITTING should be nothing, and this is here to prove it rather than assume it: tree.copy()
        // is native and the job carries a Rope, so "handing the work over" is itself work, on the frame.
        FrameProfile.step(scheduled, "ts.scheduleReparse");
    }

    /**
     * Parses off-thread and swaps the result in.
     *
     * <p><b>The tree handed to the worker is a copy.</b> {@code ts_tree_copy} exists precisely so a tree
     * can be used from another thread, and the copy is cheap — trees share their nodes. Handing over the
     * live one instead would let the UI thread call {@code edit()} on it, which <em>mutates</em>, while
     * the worker is reading it: a data race in native memory, which surfaces as a JVM crash rather than an
     * exception.</p>
     *
     * <p>Single-flight on the key is what makes one worker parser enough, and it is also what makes a
     * burst of keystrokes collapse to one parse: each new edit replaces the queued job and asks the
     * running one to stop.</p>
     */
    /**
     * Sends the <b>first</b> parse of a document to the worker, and answers nothing until it lands.
     *
     * <h3>The one parse that never went off-thread was the largest one</h3>
     *
     * <p>{@link #edited} has moved reparses to a worker since this class was written, and the query
     * paths read {@code if (tree == null || (stale && scheduler == null)) reparse(...)} — so a
     * <em>stale</em> tree is answered from while a worker catches up, and <b>no</b> tree parsed
     * synchronously on the caller. That is backwards with respect to cost. A reparse interpolates from an
     * existing tree and was measured at ~17ms; a first parse has nothing to interpolate from and is the
     * whole file.</p>
     *
     * <p>Measured on a 1,980-line decompiled class, on the frame that first draws it:
     * <b>{@code ed:tokenize rows 0..25 (26 of 1980), span 1100 chars -> 221 tokens 214996us}</b> — 215ms
     * to answer a <em>1,100-character</em> query, because the parse behind it was 73KB. The next call on
     * the identical span cost 4,975us and a later one 260us, which is what says the span was never the
     * expense. That single frame is the reported "opening a big class takes 120fps to 55".</p>
     *
     * <p>It hid behind the shape of the condition. Every reading of that line says the synchronous branch
     * is the fallback for having no scheduler, and for the stale half it is; the {@code tree == null} half
     * is unconditional and sits in front of it, so a tokenizer built <em>with</em> a scheduler still
     * blocks exactly once per document — and once per document is once per file opened, which is the only
     * moment anybody is watching.</p>
     *
     * <p><b>Returning no tokens for a few frames is the correct answer, not a compromise.</b> The class
     * already ships uncoloured text whenever a grammar is absent, and every consumer handles it; the
     * document then colours in when {@code announceChanged} reports — with no old tree to diff against it
     * falls back to the whole document, which is precisely right here. VS Code does the same thing and
     * calls it background tokenization.</p>
     *
     * @return whether the caller should answer with nothing this time
     */
    private boolean deferInitialParse(Rope document) {
        if (tree != null || scheduler == null) return false;
        // SINGLE-FLIGHT IS WHY THIS NEEDS A FLAG. scheduleReparse keys on JobKey.of(this, "reparse"), and
        // that key's contract is that a new submission REPLACES the queued job and asks the running one to
        // stop -- which is exactly right for a burst of keystrokes and fatal here, because this runs from
        // a paint. Re-submitting every frame would cancel the parse every frame and it would never finish:
        // a document that stays uncoloured forever, with a worker busy the whole time.
        if (!parsePending) {
            // ONCE PER SCHEDULING, not once per call: this runs from a paint and returns true every
            // frame until the tree lands, so noting it unconditionally is a log line per frame.
            FrameProfile.note("ts.firstParse deferred to a worker, " + document.length() + " chars");
            scheduleReparse(document);
        }
        return true;
    }

    /**
     * Whether a parse is in flight. @see #deferInitialParse for why a paint-driven caller needs this.
     *
     * <p>Set for every scheduled parse rather than only the first, because "is one running" is a fact
     * about this tokenizer and not about which caller asked. Only the no-tree path reads it — a stale
     * tree is answered from regardless.</p>
     */
    private boolean parsePending;

    private void scheduleReparse(Rope document) {
        parsePending = true;
        // The ROPE is handed over, not its text. Flattening a 200KB document is a 200KB copy, and doing it
        // here would put one on the UI thread per keystroke -- the exact cost this method exists to move.
        // Safe because a Rope is persistent: applying a change returns a new one rather than mutating this
        // one, so the worker's snapshot cannot be edited underneath it. The tree, which is NOT persistent,
        // is copied instead.
        TSTree snapshot = tree == null ? null : tree.copy();
        scheduler.job(JobKey.of(this, "reparse"), JobLane.LATENCY, context -> {
            if (workerParser == null) {
                workerParser = new TSParser();
                workerParser.setLanguage(language);
            }
            String text = forParser(document.toString());
            context.throwIfCancelled();
            TSTree parsed = workerParser.parseString(snapshot, text);
            // Built here rather than on delivery: it is an O(n) pass over the document and belongs on the
            // thread that already has the document in hand, not on the frame that receives the answer.
            Utf8Offsets parsedOffsets = Utf8Offsets.of(text);
            context.throwIfCancelled();
            // AND THE LOCALS PASS FOR THE SAME REASON, which is the larger of the two. @see #localsOnWorker
            // AND THE FOLD QUERY, third pass on the worker that owns this tree. @see #foldsOnWorker
            return new Parsed(parsed, parsedOffsets, localsOnWorker(parsed, parsedOffsets),
                    foldsOnWorker(parsed));
        }).onDone(result -> {
            // CLEARED BEFORE THE NULL CHECK. A cancelled or failed job delivers null, and leaving the flag
            // set on that path would mean no parse is ever scheduled again -- a document uncoloured for
            // the rest of the session, from the one code path that produces no error to look at.
            parsePending = false;
            if (result == null) return;
            TSTree replaced = this.tree;
            this.tree = result.tree();
            // WHAT THE PARSE ACTUALLY COVERED, against what it was asked about. A tree whose root ends
            // well short of the document is the signature of an incremental parse handed a base tree
            // that was never edited -- it reuses the old nodes and reports the OLD extent, silently.
            // One line, and it is the difference between seeing that and inferring it from missing
            // colour. @see #edited
            FrameProfile.note("ts.parsed root 0.." + result.tree().getRootNode().getEndByte()
                    + " bytes (incremental base: " + (replaced == null ? "none" : "a tree") + ")");
            // A new tree means new scopes -- ALREADY COMPUTED, on the worker that built the tree. Null
            // only when the grammar ships no locals.scm or the pass failed, and localsForTree then falls
            // back to computing it here exactly as it always did.
            this.localsTokens = result.locals();
            // Fold ranges for THIS tree, computed beside it. Null falls back to querying on demand,
            // exactly as before. @see #foldsOnWorker
            this.foldRanges = result.folds();
            this.offsets = result.offsets();
            this.stale = false;
            // Nothing about the DOCUMENT changed, so no existing signal would tell the view to re-query --
            // the highlighting would just stay one edit behind until something else happened to repaint.
            if (invalidationListener != null) {
                announceChanged(replaced, result.tree(), result.offsets());
            }
        }).submit();
    }

    /**
     * Reports exactly which part of the document the new tree disagrees with the old one about.
     *
     * <p>Precision matters more than it looks. During a run of typing a reparse lands every few
     * keystrokes, so a consumer told only "something changed" re-queries its whole viewport at nearly the
     * rate a per-line cache exists to avoid — the cache would then buy almost nothing. tree-sitter
     * already knows the answer, so the alternative is discarding information rather than saving work.</p>
     *
     * <p>Falls back to the whole document when there is no old tree to compare against, or when the
     * comparison itself fails: over-reporting is merely slow, and under-reporting leaves stale colour on
     * screen with nothing left to correct it.</p>
     */
    /**
     * Whether the smallest node covering this range had to be recovered around.
     *
     * <p>{@code hasError} is a flag the parser sets on a node when it or anything below it is an
     * {@code ERROR}, so this is a field read rather than a walk. Asked of the <b>smallest node covering
     * the range</b>, which is what scopes it: a broken statement elsewhere in the file leaves this row's
     * own node clean, while a row swallowed by the recovery around an unfinished statement above it does
     * not.</p>
     */
    @Override
    public boolean recoveredAround(int fromOffset, int toOffset) {
        if (tree == null) return false;
        try {
            TSNode node = tree.getRootNode()
                    .getDescendantForByteRange(offsets.toUtf8(fromOffset), offsets.toUtf8(toOffset));
            return node != null && !node.isNull() && node.hasError();
        } catch (RuntimeException outOfRange) {
            // A range the current tree does not cover -- it is mid-edit. Not a reason to hold anything.
            return false;
        }
    }

    private void announceChanged(TSTree replaced, TSTree parsed, Utf8Offsets newOffsets) {
        if (replaced == null) {
            invalidationListener.tokensChanged(0, InvalidationListener.EVERYTHING);
            return;
        }
        try {
            TSRange[] ranges = TSTree.getChangedRanges(replaced, parsed);
            if (ranges == null || ranges.length == 0) {
                // Structurally identical -- the edit was inside a token, e.g. another character typed into
                // an identifier. Nothing to re-query, so say nothing rather than invalidating a viewport.
                return;
            }
            // The union, not each range: they are typically one small region, and a consumer that has to
            // union them anyway is better served by one call than by n.
            int lowByte = Integer.MAX_VALUE;
            int highByte = 0;
            for (TSRange range : ranges) {
                lowByte = Math.min(lowByte, range.getStartByte());
                highByte = Math.max(highByte, range.getEndByte());
            }
            invalidationListener.tokensChanged(newOffsets.toUtf16(lowByte), newOffsets.toUtf16(highByte));
        } catch (RuntimeException comparisonFailed) {
            invalidationListener.tokensChanged(0, InvalidationListener.EVERYTHING);
        }
    }

    /** A finished parse and the offset index over the text it describes — swapped in together. */
    private record Parsed(TSTree tree, Utf8Offsets offsets, List<SyntaxToken> locals, List<int[]> folds) {
    }

    /**
     * The whole-document locals pass, run on the worker beside the parse it belongs to.
     *
     * <h3>Why this was the 225ms, and why it did not look like it</h3>
     *
     * <p>{@link #localsForTree} is cached per tree and its javadoc already explains why it must scan the
     * whole file: a reference in the viewport is declared somewhere that usually is not. What the cache
     * settles is that the pass runs <em>once per parse</em> rather than once per paint. It does not
     * settle <em>where</em>, and once per parse on the frame thread is still a whole-document query on a
     * frame.</p>
     *
     * <p>Measured on a 1,980-line decompiled class: the first {@code tokenize} after a parse cost
     * <b>225ms</b> for a 1,100-character viewport query, and the next call on the identical tree and span
     * cost <b>4.8ms</b>. That ratio is the whole diagnosis — the span was never the expense, and the one
     * thing that happens on the first call and not the second is this.</p>
     *
     * <p>It also survived the first fix aimed at it. Moving the initial <em>parse</em> off-thread was
     * correct and changed the number by nothing, because by the time the expensive {@code tokenize} ran
     * the tree was already there: the cost sat one step past where the evidence pointed.</p>
     *
     * <h3>Its own query object, like {@link #workerParser}</h3>
     *
     * <p>Same rule this class already applies to the parser: two threads, two natives. The tree being
     * queried here has just been built by this worker and nothing else holds a reference to it yet, which
     * is what makes querying it off-thread safe at all — and it is handed over only after this returns.
     * Sharing {@link #localsQuery} instead would put the frame thread's fallback and this on one native
     * object, which is the shape that surfaces as a JVM crash rather than an exception.</p>
     */
    private List<SyntaxToken> localsOnWorker(TSTree parsed, Utf8Offsets parsedOffsets) {
        if (!workerLocalsBuilt) {
            workerLocalsBuilt = true;
            workerLocalsQuery = compileFamily("locals");
            if (workerLocalsQuery != null) {
                workerLocalsCaptureNames = new String[workerLocalsQuery.getCaptureCount()];
                for (int i = 0; i < workerLocalsCaptureNames.length; i++) {
                    String name = workerLocalsQuery.getCaptureNameForId(i);
                    workerLocalsCaptureNames[i] = name == null ? null : name.intern();
                }
            }
        }
        // A grammar that ships no locals.scm is the ordinary case for several of them, and null here
        // means the frame thread's appendLocals will find its own query null too and return early.
        if (workerLocalsQuery == null) return null;
        try {
            return LocalScopes.tokensIn(parsed, workerLocalsQuery, workerLocalsCaptureNames,
                    new TSQueryCursor(), parsedOffsets, 0, Integer.MAX_VALUE);
        } catch (RuntimeException failed) {
            // Answering null leaves localsForTree to compute it on the frame thread, which is the old
            // behaviour: slow rather than wrong. A locals pass is a refinement of colour, never
            // load-bearing, so it must not be able to take the parse down with it.
            return null;
        }
    }

    /** The worker's own locals query. @see #localsOnWorker */
    private TSQuery workerLocalsQuery;

    private String[] workerLocalsCaptureNames;

    private boolean workerLocalsBuilt;

    /**
     * Text a grammar cannot parse, neutralised before it sees it — <b>length-preserving, always</b>.
     *
     * <h3>Why a grammar needs one at all</h3>
     *
     * <p>A JavaScript file may carry {@code import a.b.C;}, which this engine supports and which
     * tree-sitter's grammar cannot parse: {@code import} there is an ES module declaration expecting a
     * string or a {@code from}, so the line becomes an ERROR node. The damage is not local — measured,
     * the same file parsed with and without it colours {@code var} as {@code keyword} in one and
     * {@code variable} in the other, and {@code TEXT_MATERIAL} as {@code property} against
     * {@code constructor}. One unparseable line at the top corrupts the whole document's colouring.</p>
     *
     * <h3>The contract, and it is not negotiable</h3>
     *
     * <p><b>The filter must return a string of exactly the same length.</b> Every offset this class
     * reports — every token, every fold, every indent — is an offset into the text it parsed, and the
     * editor reads them as offsets into the document. A filter that shortened by one character would move
     * every colour in the file below it. Blanking to spaces is the only shape that satisfies this, and it
     * is the shape both {@code JsImports} and {@code ScriptPrelude} already use.</p>
     *
     * <p>Which grammar needs one, and why it is declared on {@link Grammar} rather than by the language
     * whose dialect it is, is recorded on {@code Grammar#sourceFilter} — the short version being that a
     * bytecode scan forbids the language packages from naming this one.</p>
     */
    private UnaryOperator<String> sourceFilter;

    /** @see #sourceFilter */
    public void filterSourceWith(UnaryOperator<String> filter) {
        this.sourceFilter = filter;
    }

    /**
     * The text the parser should see.
     *
     * <p>The length check is an assertion rather than a repair, and it fails loudly: a filter that
     * changed the length would produce colouring that is subtly wrong everywhere below the edit, which is
     * far harder to diagnose than a thrown exception naming the filter.</p>
     */
    private String forParser(String text) {
        if (sourceFilter == null) return text;
        String filtered = sourceFilter.apply(text);
        if (filtered == null || filtered.length() != text.length()) {
            throw new IllegalStateException("a grammar source filter must preserve length: "
                    + text.length() + " became " + (filtered == null ? "null" : filtered.length()));
        }
        return filtered;
    }

    private void reparse(String text) {
        this.localsTokens = null;   // @see #localsForTree -- the cache belongs to one tree
        // AND THE FOLDS WITH THEM, for the same reason and it is the sharper one: locals only tint a
        // token, while a fold range decides which ROWS are on screen. Carrying a previous tree's ranges
        // into a new one hides rows that no longer correspond to anything. @see #foldsOnWorker
        this.foldRanges = null;
        TSTree previous = tree;
        this.tree = previous == null
                ? parser.parseString(null, text)
                : parser.parseString(previous, text);
        // AFTER the parse: the interpolated edits above were expressed in the OLD text's coordinates, so
        // replacing the index first would convert them against a document the tree had not seen yet.
        this.offsets = Utf8Offsets.of(text);
        this.stale = false;
    }

    /** Converts one change from UTF-16 offsets into the byte offsets and points tree-sitter wants. */
    /**
     * The edit tree-sitter needs, computed in <b>one pass</b> over the inserted text.
     *
     * <h3>It used to encode the whole insert to bytes twice, and scan it a third time</h3>
     *
     * <p>{@code getBytes(UTF_8).length} to learn a byte count materialises the entire encoding and
     * throws it away; {@link #pointAfterInsert} then scanned the same text for newlines and encoded its
     * tail. Free for a keystroke, which is what this was written against — and a document LOAD arrives
     * here as one {@code Change} whose insert is the whole file. Measured on a 108KB decompiled class:
     * {@code ts.interpolate} at <b>4.3ms</b> for a single change, which is two 108KB encodings and two
     * 108KB allocations on the frame that opens the file.</p>
     *
     * <p>Everything needed — the byte length, the newline count, and the byte length of the tail after
     * the last newline — falls out of one walk with no allocation at all. The three were always the same
     * walk; they were simply written as three.</p>
     */
    private TSInputEdit inputEditFor(Change change) {
        int startByte = offsets.toUtf8(change.from());
        int oldEndByte = offsets.toUtf8(change.to());
        String inserted = change.insert();

        int utf8Length = 0;
        int newlines = 0;
        int tailBytes = 0;
        for (int i = 0; i < inserted.length(); i++) {
            char c = inserted.charAt(i);
            // A surrogate PAIR encodes to four bytes, so two per surrogate char totals four -- which is
            // why the pair needs no special case and a lone surrogate still counts something sane.
            int bytes = c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isSurrogate(c) ? 2 : 3;
            utf8Length += bytes;
            if (c == '\n') {
                newlines++;
                tailBytes = 0;
            } else {
                tailBytes += bytes;
            }
        }

        TSPoint start = pointAt(change.from());
        TSPoint afterInsert = newlines == 0
                ? new TSPoint(start.getRow(), start.getColumn() + utf8Length)
                : new TSPoint(start.getRow() + newlines, tailBytes);
        return new TSInputEdit(startByte, oldEndByte, startByte + utf8Length,
                start, pointAt(change.to()), afterInsert);
    }

    /**
     * Whether this change replaces every character of the document. @see #edited
     *
     * <p>One change spanning {@code [0, lengthBefore)} — which is exactly what {@code TextBuffer.load}
     * produces, and nothing a person types ever does.</p>
     */
    private static boolean replacesWholeDocument(ChangeSet change) {
        List<Change> changes = change.changes();
        if (changes.size() != 1) return false;
        Change only = changes.get(0);
        return only.from() == 0 && only.to() == change.lengthBefore();
    }

    private TSPoint pointAt(int utf16Index) {
        return new TSPoint(offsets.rowAt(utf16Index), offsets.byteColumnAt(utf16Index));
    }

    /** Where the caret lands after {@code inserted} is typed at {@code utf16Index}, in tree-sitter's terms. */
    private TSPoint pointAfterInsert(int utf16Index, String inserted) {
        TSPoint start = pointAt(utf16Index);
        int newlines = 0;
        int lastBreak = -1;
        for (int i = 0; i < inserted.length(); i++) {
            if (inserted.charAt(i) == '\n') {
                newlines++;
                lastBreak = i;
            }
        }
        if (newlines == 0) {
            int extra = inserted.getBytes(StandardCharsets.UTF_8).length;
            return new TSPoint(start.getRow(), start.getColumn() + extra);
        }
        int tailBytes = inserted.substring(lastBreak + 1).getBytes(StandardCharsets.UTF_8).length;
        return new TSPoint(start.getRow() + newlines, tailBytes);
    }

    @Override
    public void close() {
        // The tree, the parser, the query and the cursor all hold native memory, and this class is created
        // per document -- so "closed when the document closes" is the whole lifecycle. Dropping only the
        // tree, as this used to, left a parser and a compiled query per file ever opened.
        //
        // Zed drops deep trees on a background thread because it is slow enough to be felt; at our
        // document sizes it is not, but the note belongs here for whoever finds a frame spike on closing a
        // large file and starts looking at the renderer.
        tree = null;
        offsets = Utf8Offsets.EMPTY;
        stale = true;
        // The injected children are whole tokenizers, each with its own parser, query and cursor. Leaving
        // them would leak one set per embedded language per HTML document opened -- the same per-document
        // native leak close() was extended to fix, one level down.
        for (TreeSitterTokenizer child : injected.values()) child.close();
        injected.clear();
        closeQuietly(injectionQuery);
        closeQuietly(cursor);
        closeQuietly(query);
        closeQuietly(parser);
        // THE OPTIONAL FAMILIES AND THE WORKER'S HALF. The comment above says "the query" as though there
        // were one; there are five, each compiled lazily and each per document, plus the worker's own
        // parser and locals query. Releasing only the highlight query leaves the rest to accumulate one
        // set per file ever opened -- the leak this method was extended to fix, from the same distance.
        closeQuietly(localsQuery);
        closeQuietly(foldQuery);
        closeQuietly(indentQuery);
        closeQuietly(workerLocalsQuery);
        closeQuietly(workerParser);
    }

    /**
     * Releases a native handle if the binding exposes a way to.
     *
     * <p>Reflective because {@code tree-sitter-ng}'s types do not share a common closeable supertype and
     * not all of them expose the same method — and a binding upgrade that adds one should start being used
     * without this class needing to hear about it. A handle that cannot be released is left to the
     * binding's own finalization rather than being an error: leaking it is the status quo, and throwing
     * here would turn closing a tab into a crash.</p>
     */
    private static void closeQuietly(Object nativeHolder) {
        if (nativeHolder == null) return;
        try {
            if (nativeHolder instanceof AutoCloseable closeable) closeable.close();
        } catch (Exception ignored) {
            // Nothing actionable: the process is either exiting or the handle was already released.
        }
    }
}
