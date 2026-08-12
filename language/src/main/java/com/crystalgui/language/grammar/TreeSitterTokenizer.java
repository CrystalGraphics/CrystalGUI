package com.crystalgui.language.grammar;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Rope;
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

import java.nio.charset.StandardCharsets;
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
public final class TreeSitterTokenizer implements SyntaxTokenizer {

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
        this(language, new Queries.Prepared(highlightQuery, java.util.Collections.emptyMap()), scheduler);
    }

    TreeSitterTokenizer(TSLanguage language, Queries.Prepared prepared, JobScheduler scheduler) {
        this.language = language;
        this.parser = new TSParser();
        this.parser.setLanguage(language);
        this.query = new TSQuery(language, prepared.text());
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
        if (tree == null || (stale && scheduler == null)) reparse(document.toString());
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
                Pattern filter = captureFilters.get(name);
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
        return sorted;
    }

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
        // Applied against the offsets of the text the tree currently describes, and in the order the
        // changes were made, because each edit's coordinates are relative to the document the previous
        // one left behind.
        if (tree != null) {
            for (Change one : change.changes()) {
                tree.edit(inputEditFor(one));
            }
        }

        // PHASE 2 -- reparse. The expensive half: measured at ~17ms average and ~26ms worst per keystroke
        // on a 5,000-line file, against a budget of 2ms. It goes to a worker when there is one, and is
        // otherwise deferred to the next query so that a burst of keystrokes still costs one parse rather
        // than one per key.
        stale = true;
        if (scheduler != null) scheduleReparse(after);
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
    private void scheduleReparse(Rope document) {
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
            String text = document.toString();
            context.throwIfCancelled();
            TSTree parsed = workerParser.parseString(snapshot, text);
            // Built here rather than on delivery: it is an O(n) pass over the document and belongs on the
            // thread that already has the document in hand, not on the frame that receives the answer.
            return new Parsed(parsed, Utf8Offsets.of(text));
        }).onDone(result -> {
            if (result == null) return;
            TSTree replaced = this.tree;
            this.tree = result.tree();
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
    private record Parsed(TSTree tree, Utf8Offsets offsets) {
    }

    private void reparse(String text) {
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
    private TSInputEdit inputEditFor(Change change) {
        int startByte = offsets.toUtf8(change.from());
        int oldEndByte = offsets.toUtf8(change.to());
        int newEndByte = startByte + change.insert().getBytes(StandardCharsets.UTF_8).length;
        return new TSInputEdit(startByte, oldEndByte, newEndByte,
                pointAt(change.from()), pointAt(change.to()),
                pointAfterInsert(change.from(), change.insert()));
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
