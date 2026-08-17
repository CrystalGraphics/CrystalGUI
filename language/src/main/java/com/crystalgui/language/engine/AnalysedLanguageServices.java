package com.crystalgui.language.engine;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.decoration.DecorationSet;
import com.crystalgui.text.decoration.Stickiness;
import com.crystalgui.text.decoration.TrackedRange;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.lang.Resolver;
import com.crystalgui.text.lang.SemanticTokenProvider;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * An engine attached to one document — everything about being attached, with the engine left to fill in.
 *
 * <h3>Why a base class, and why it is exactly this much</h3>
 *
 * <p>Java's services were written first and every line of them that was not Java turned out to be
 * <em>attachment</em>: debounce a burst of keystrokes into one analysis, abandon the one in flight when
 * the next lands, install the answer on the UI thread and release the previous one, hold the tokens for
 * the editor to pull per row, answer resolution from the held tree, keep the optional warnings alive
 * through a syntax error, and stamp every announcement with the version the engine actually saw. None
 * of that mentions a class, a classpath or a compiler. A JavaScript engine would have copied all of it
 * and the two copies would have disagreed inside a release about something like whether a stale answer
 * is dropped or kept — which is precisely the kind of policy that must be decided once.</p>
 *
 * <p>So this holds the attachment and asks the subclass one question: {@link #analyse given this text,
 * what does the engine say?} Everything the answer is made of is an {@link Analysis}, the language-neutral
 * half of the bridge, which is why the same install path serves any engine that produces one.</p>
 *
 * <h3>Where the two async shapes meet</h3>
 *
 * <p>One analysis feeds both, and that is why they can be different shapes without duplicating work:</p>
 *
 * <ul>
 *   <li><b>Semantic tokens and diagnostics push.</b> Nobody asked for them; the document changed, so an
 *       analysis is scheduled, and when it lands the editor is <em>told</em>. It then pulls per row from
 *       what is held — the same shape {@code SyntaxTokenizer} has, so the per-row cache works on it
 *       unchanged.</li>
 *   <li><b>Resolution answers requests.</b> A hover has one asker and a caret that stops being
 *       interesting the moment it moves, so it takes a callback and may never fire.</li>
 * </ul>
 *
 * <h3>Debounced, keyed, superseding — the scheduler does all three</h3>
 *
 * <p>A run of keystrokes must produce one analysis at the end rather than one per key, and an analysis
 * still running when the next keystroke lands must be abandoned rather than raced. {@link JobScheduler}
 * already does exactly this: a {@link JobKey} keyed on <em>this service</em> means every submission
 * replaces the last, and the debounce window is what turns typing into one job.</p>
 *
 * <p><b>Answers land on the UI thread, during the drain.</b> Nothing here synchronises, because nothing
 * here is touched from two threads: the job body reads an immutable snapshot taken on the UI thread, and
 * its result is installed on the UI thread.</p>
 *
 * <h3>Staleness policy: keep, per line</h3>
 *
 * <p>The held analysis is not discarded when the document moves on. A line the edit did not touch still
 * has the right colours, so dropping everything on each keystroke would flicker the file back to grammar
 * colouring and restore it a few hundred milliseconds later — worse than a slightly stale colour, and
 * exactly the choice {@link Versioned} exists to let a consumer make.</p>
 *
 * <h3>Construction is two steps, and the second is not optional</h3>
 *
 * <p>A subclass builds whatever {@link #analyse} needs and then calls {@link #start()} as the last line of
 * its constructor. The base cannot run the first analysis from its own constructor: that would call
 * {@link #analyse} before the subclass's fields exist. Forgetting {@code start()} is visible — a document
 * with no colours and no problems until the first keystroke — which is better than a subclass silently
 * analysing over null fields.</p>
 */
public abstract class AnalysedLanguageServices implements LanguageServices {

    /**
     * How long a burst of typing is allowed to be before an analysis starts.
     *
     * <p>300ms is the plan's figure for diagnostics and it is a compromise between two visible faults:
     * shorter and a fast typist starts a compile per word, longer and a genuine pause feels unresponsive.
     * Not a constant anybody should tune without measuring on a real document.</p>
     */
    protected static final long DEBOUNCE_MILLIS = 300;

    private final String id;
    private final TextBuffer buffer;
    @Nullable private final JobScheduler scheduler;
    private final JobKey analysisKey;
    private final String retainedLane;
    private final String runtimeLane;
    @Nullable private final Resource file;

    private Connection bufferSubscription = Connection.DISCONNECTED;
    private final List<Consumer<Versioned<List<Diagnostic>>>> diagnosticListeners = new ArrayList<>();
    private final SemanticTokens tokens = new SemanticTokens();

    /** The most recent analysis, and the only mutable state here. UI thread only. */
    private Analysis current;
    private boolean closed;
    private boolean started;

    /** The last list handed out, replayed verbatim to a listener that attaches later. @see #install */
    private Versioned<List<Diagnostic>> lastAnnouncement;

    /**
     * @param id        the owner key this engine's diagnostics are filed under — {@code "java"}
     * @param scheduler where analyses run, or null to analyse synchronously on every change (tests)
     */
    protected AnalysedLanguageServices(String id, TextBuffer buffer, @Nullable JobScheduler scheduler) {
        this(id, buffer, scheduler, null);
    }

    /**
     * @param file the document this is attached to, so a <em>runtime</em> can find it —
     *             {@link #attachedTo}. Null for a document with no file, which nothing can run
     */
    protected AnalysedLanguageServices(String id, TextBuffer buffer, @Nullable JobScheduler scheduler,
                                       @Nullable Resource file) {
        this.id = id;
        this.buffer = buffer;
        this.scheduler = scheduler;
        this.file = file;
        this.analysisKey = JobKey.of(this, id + "-analysis");
        this.retainedLane = id + "-retained-warnings";
        this.runtimeLane = id + "-runtime-problems";
        if (file != null) ATTACHED.put(file, this);
    }

    // ── Which services a file has ───────────────────────────────────────────────────────────────

    /**
     * Every attached services object with a file, by that file.
     *
     * <p>Exists for one caller: a <b>runtime</b> that has just run the file and has something to say
     * about it — a thrown exception at a line — and holds nothing but the file's {@code Resource}. The
     * services belong to the document ({@code TextFileDocument} owns them), and there is exactly one per
     * open file, so the file is a valid key. Concurrent because a run reports from its own thread; the
     * lookup is thread-safe and what it answers is then only ever <em>called</em> on the UI thread.</p>
     */
    private static final ConcurrentHashMap<Resource, AnalysedLanguageServices> ATTACHED =
            new ConcurrentHashMap<>();

    /** The services attached to {@code file}, or null when it is not open with an engine behind it. */
    @Nullable
    public static AnalysedLanguageServices attachedTo(@Nullable Resource file) {
        return file == null ? null : ATTACHED.get(file);
    }

    /**
     * Subscribes to the buffer and analyses once, undebounced.
     *
     * <p>A document that is opened and not typed in would otherwise have no colours and no problems until
     * the first keystroke — which is exactly the state a file spends most of its time in.</p>
     */
    protected final void start() {
        if (started || closed) return;
        started = true;
        bufferSubscription = buffer.onChanged.connect(change -> schedule());
        analyzeNow();
    }

    // ── What the engine fills in ────────────────────────────────────────────────────────────────

    /**
     * The engine's answer for {@code source}, stamped {@code version}.
     *
     * <p><b>May run off the UI thread</b> — that is the point of the scheduler — so it must read only what
     * it was handed and what is immutable. Never null: an engine with nothing to say returns an analysis
     * with empty lists.</p>
     */
    protected abstract Analysis analyse(String source, long version);

    /** Told after {@link #close()} released everything here; a subclass drops what it holds. */
    protected void onClosed() {
    }

    // ── What the subclass may read ──────────────────────────────────────────────────────────────

    /** The analysis currently held, or null before the first lands. UI thread. */
    @Nullable
    protected final Analysis current() {
        return current;
    }

    protected final TextBuffer buffer() {
        return buffer;
    }

    protected final boolean isClosed() {
        return closed;
    }

    /**
     * Analyses arbitrary text on the calling thread — the completion probe's shape.
     *
     * <p>Version {@code -1}, because this describes text the document does not contain and must never be
     * mistaken for an answer about it. The caller closes it. Null once closed.</p>
     */
    @Nullable
    protected final Analysis analyseText(String text) {
        if (closed) return null;
        return analyse(text, -1L);
    }

    // ── The seam ────────────────────────────────────────────────────────────────────────────────

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final SemanticTokenProvider semanticTokens() {
        return tokens;
    }

    @Override
    public final Resolver resolver() {
        return new AnalysisResolver();
    }

    @Override
    public final Connection onDiagnostics(Consumer<Versioned<List<Diagnostic>>> listener) {
        if (listener == null || closed) return Connection.DISCONNECTED;
        diagnosticListeners.add(listener);
        // FIRED IMMEDIATELY IF THERE IS ALREADY AN ANSWER. A view attached after the first analysis
        // landed would otherwise show nothing until the next edit -- and for a file nobody types in,
        // that is forever.
        // REPLAYED, never recomputed -- see install. Recomputing would re-derive offsets from positions
        // that describe an older document.
        if (lastAnnouncement != null) listener.accept(lastAnnouncement);
        return () -> diagnosticListeners.remove(listener);
    }

    @Override
    public final void close() {
        if (closed) return;
        closed = true;
        if (file != null) ATTACHED.remove(file, this);
        bufferSubscription.disconnect();
        if (scheduler != null) scheduler.cancel(analysisKey);
        diagnosticListeners.clear();
        tokens.adopt(null);
        if (current != null) {
            current.close();
            current = null;
        }
        onClosed();
    }

    // ── Announcing ──────────────────────────────────────────────────────────────────────────────

    /**
     * The analysis's problems, stamped with the document version they describe.
     *
     * <p>The version is the <b>analysis's</b>, never {@code buffer.version()} read at announce time. Those
     * differ by exactly the typing that happened while the compile ran, which is the whole quantity the
     * stamp exists to measure — reading it here would make every list look fresh and the gate a no-op.</p>
     */
    private Versioned<List<Diagnostic>> announcement(Analysis analysis) {
        if (analysis.optionalProblemsAnalysed()) remember(analysis.diagnostics());
        return compose(analysis);
    }

    /**
     * The list an announcement carries: the analysis's own problems, the retained warnings when the
     * analysis could not produce them, and whatever the runtime last reported.
     *
     * <p>Split from {@link #announcement} because this half is safe to run again — it reads tracked
     * lanes, which say where their text is <em>now</em> — while that half writes one from positions
     * that are only meaningful against the document the analysis saw.</p>
     */
    private Versioned<List<Diagnostic>> compose(Analysis analysis) {
        List<Diagnostic> merged = new ArrayList<>(analysis.diagnostics());
        if (!analysis.optionalProblemsAnalysed()) merged.addAll(recalled(retainedLane));
        merged.addAll(recalled(runtimeLane));
        return Versioned.of(analysis.version(), merged);
    }

    // ── What the runtime says ───────────────────────────────────────────────────────────────────

    /**
     * Problems the language's <b>runtime</b> reported against this document — a thrown exception, at
     * the line it was thrown from. Replaces what it reported before; empty clears.
     *
     * <p>A run says "line 12 is broken" and the console shows it, but a console row is not a squiggle,
     * and the author is looking at the editor. So the runtime hands its verdict here and it rides the
     * same channel every listener already reads — filed under this engine's id, beside the analysis's
     * own problems, tracked through edits by the same lane machinery the retained warnings use, and
     * gone the moment the file is run again. VS Code's debugger draws its exception decoration the same
     * way and clears it on the next launch.</p>
     *
     * <p><b>UI thread.</b> A runtime reports from its own thread and must hop first — the lanes and the
     * listeners are the document's. Announced at the <em>current analysis's</em> version, because the
     * analysis's rows are part of the list: an editor whose buffer has moved on refuses the announcement
     * as stale, exactly as it should, and the analysis already pending for that edit carries the runtime
     * problems with it when it lands. Nothing is lost; nothing is announced against the wrong text.</p>
     */
    public final void reportRuntimeProblems(List<Diagnostic> problems) {
        if (closed) return;
        List<DecorationSet.Entry> entries = new ArrayList<>();
        if (problems != null) {
            for (Diagnostic problem : problems) {
                if (!problem.hasPosition()) continue;
                int from = offsetOf(problem.start());
                int to = Math.max(from, offsetOf(problem.end()));
                entries.add(DecorationSet.Entry.of(from, to,
                        new RuntimeProblem(problem, textIn(from, to))));
            }
        }
        buffer.decorations().replaceLane(runtimeLane,
                Stickiness.ALWAYS_GROWS_WHEN_TYPING_AT_EDGES, entries);
        if (current == null) return;
        lastAnnouncement = compose(current);
        for (Consumer<Versioned<List<Diagnostic>>> listener : new ArrayList<>(diagnosticListeners)) {
            listener.accept(lastAnnouncement);
        }
    }

    // ── Warnings survive a syntax error ─────────────────────────────────────────────────────────

    /**
     * Remembers this analysis's optional problems, <b>replacing</b> whatever was held.
     *
     * <p>Where they live is a lane in the <b>document's own</b> {@link DecorationSet}, which
     * {@code TextBuffer.applied} adjusts before any listener runs — the same machinery the editor's
     * squiggles use, and the reason this costs no tracking code of its own.</p>
     *
     * <p>Replacing rather than merging, and to empty when there are none: an analysis whose optional pass
     * ran is the complete answer, so a warning the user has just fixed has to leave. Merging would make
     * the set grow monotonically and never forget anything.</p>
     */
    private void remember(List<Diagnostic> reported) {
        List<DecorationSet.Entry> entries = new ArrayList<>();
        for (Diagnostic problem : reported) {
            if (problem.severity() == DiagnosticSeverity.ERROR) continue;
            if (!problem.hasPosition()) continue;
            int from = offsetOf(problem.start());
            int to = Math.max(from, offsetOf(problem.end()));
            entries.add(DecorationSet.Entry.of(from, to, problem));
        }
        // ALWAYS_GROWS, matching the editor's diagnostic lane: type at either edge of an underlined name
        // and the new character belongs to the same problem rather than sitting outside it.
        buffer.decorations().replaceLane(retainedLane,
                Stickiness.ALWAYS_GROWS_WHEN_TYPING_AT_EDGES, entries);
    }

    /**
     * The retained problems, re-stated <b>where their text is now</b>.
     *
     * <p>Rebuilt from the tracked offsets rather than re-announced with their original row and column,
     * which is the entire point of holding them in a decoration lane: a warning about line 17 that has
     * since become line 20 must say 20, or the Problems row navigates to innocent text and the squiggle
     * paints over it.</p>
     *
     * <p><b>A range that collapsed is dropped, and that is what keeps this honest.</b> Delete the unused
     * import while the file is broken and the range holding its warning shrinks to nothing, so the warning
     * goes with it rather than persisting until the file next parses. It is the difference between
     * retaining a problem and retaining a claim about text that is gone — and {@code collapsedByEdit} is
     * the distinction, since a diagnostic can legitimately be born empty.</p>
     */
    private List<Diagnostic> recalled(String lane) {
        List<Diagnostic> out = new ArrayList<>();
        for (TrackedRange range : buffer.decorations().inLane(lane)) {
            if (range.isRemoved() || range.collapsedByEdit()) continue;
            Diagnostic original = stillTrue(range);
            if (original == null) continue;
            out.add(new Diagnostic(pointOf(range.from()), pointOf(range.to()), original.severity(),
                    original.message(), original.source(), original.code()));
        }
        return out;
    }

    /**
     * The diagnostic a tracked range carries — or null when it has stopped describing the text it is on.
     *
     * <p>The two lanes make different promises, and this is where they differ. A retained <b>warning</b> is
     * the analyser's, re-stated only until the analyser can speak for itself, so it survives any edit that
     * does not destroy its range. A <b>runtime</b> problem is evidence about one specific piece of text —
     * "executing <em>this</em> threw" — so the moment that text changes the evidence is about something
     * that no longer exists.</p>
     *
     * <p>Commenting the line out is the case that makes it obvious: the statement cannot throw, and it is
     * not a deletion, so the range survives with the {@code //} now inside it and the mark sits there
     * claiming a line that does not run any more is broken. Fixing the line has the same shape and is the
     * more common one. The alternative is to wait for the next run, which means red text under a line you
     * have already dealt with — the "the analyser is lagging" failure this codebase has paid for twice.</p>
     *
     * <p>It is a comparison rather than a flag because it wants to be exact: an edit <em>elsewhere</em>
     * moves the range and leaves its text alone, so the mark travels; and an <b>undo</b> restores both the
     * text and the mark, which a one-way "invalidated" bit could not do.</p>
     */
    @Nullable
    private Diagnostic stillTrue(TrackedRange range) {
        RuntimeProblem witnessed = range.payload(RuntimeProblem.class);
        if (witnessed == null) return range.payload(Diagnostic.class);
        return witnessed.text().equals(textIn(range.from(), range.to()))
                ? witnessed.diagnostic() : null;
    }

    /**
     * A problem, and the exact text it was reported against.
     *
     * <p>The text rather than a hash of it: these are one line each and there are a handful, so the
     * comparison is cheap and what is stored can be read in a debugger — which matters for the one thing
     * that would be hard to diagnose otherwise, a mark that will not go away.</p>
     */
    private record RuntimeProblem(Diagnostic diagnostic, String text) {
    }

    private String textIn(int from, int to) {
        return buffer.document().slice(from, to).toString();
    }

    private int offsetOf(TextPoint point) {
        return buffer.pointToOffset(point);
    }

    private TextPoint pointOf(int offset) {
        return buffer.offsetToPoint(Math.max(0, Math.min(offset, buffer.length())));
    }

    // ── Scheduling ──────────────────────────────────────────────────────────────────────────────

    private void schedule() {
        if (closed) return;
        if (scheduler == null) {
            analyzeNow();
            return;
        }
        final String source = buffer.document().toString();
        final long version = buffer.version();
        scheduler.job(analysisKey, JobLane.LATENCY, context -> analyse(source, version))
                .debounce(DEBOUNCE_MILLIS)
                .onDone(this::install)
                .submit();
    }

    /** Analyses on the calling thread — construction, and any caller with no scheduler. */
    private void analyzeNow() {
        if (closed) return;
        install(analyse(buffer.document().toString(), buffer.version()));
    }

    /** UI thread. Swaps in the new analysis, releases the old, and tells everyone watching. */
    private void install(Analysis analysis) {
        if (analysis == null) return;
        if (closed) {
            // The document closed while this was in flight. Releasing it here rather than leaking is
            // the whole reason close() cannot simply drop the reference and walk away.
            analysis.close();
            return;
        }
        Analysis previous = current;
        current = analysis;
        if (previous != null) previous.close();

        tokens.adopt(analysis);
        // COMPUTED ONCE PER ANALYSIS, not once per listener. announcement() has a side effect -- it
        // replaces the retained-warning lane -- and its inputs are row/column positions that are only
        // meaningful against the document the analysis saw. Recomputing it later, when a listener happens
        // to attach, would map those positions against a buffer that has since been edited and overwrite
        // correctly-tracked ranges with wrong offsets. @see #announcement
        lastAnnouncement = announcement(analysis);
        for (Consumer<Versioned<List<Diagnostic>>> listener : new ArrayList<>(diagnosticListeners)) {
            listener.accept(lastAnnouncement);
        }
    }

    // ── Semantic tokens: push, with an invalidation range ───────────────────────────────────────

    /**
     * Holds the last analysis's tokens and answers per range.
     *
     * <p>The whole document's tokens are materialised once per analysis and filtered per query, rather
     * than asking the engine per range. The engine is across a classloader boundary and each call is a
     * real crossing; a viewport is a handful of rows and the list is small, so filtering a list beats
     * paying the crossing per row — which is the same reasoning the per-row cache in the editor rests on
     * one level up.</p>
     */
    private static final class SemanticTokens implements SemanticTokenProvider {

        private List<SyntaxToken> all = Collections.emptyList();
        private long version;
        private SyntaxTokenizer.InvalidationListener listener;

        @Override
        public List<SyntaxToken> tokensIn(int fromOffset, int toOffset) {
            List<SyntaxToken> overlapping = new ArrayList<>();
            for (SyntaxToken token : all) {
                if (token.start() < toOffset && fromOffset < token.end()) overlapping.add(token);
            }
            return overlapping;
        }

        @Override
        public long version() {
            return version;
        }

        @Override
        public void setInvalidationListener(SyntaxTokenizer.InvalidationListener newListener) {
            this.listener = newListener;
        }

        void adopt(@Nullable Analysis analysis) {
            this.all = analysis == null ? Collections.<SyntaxToken>emptyList()
                    : analysis.semanticTokens();
            this.version = analysis == null ? 0 : analysis.version();
            // EVERYTHING, not a computed range. A compile can change any line's colours -- adding a
            // field renames nothing and yet re-colours every use of that name in the file -- so a
            // narrower claim would be a wrong one. The editor's per-row cache makes the re-query cheap
            // and this is the honest input to it.
            if (listener != null) {
                listener.tokensChanged(0, SyntaxTokenizer.InvalidationListener.EVERYTHING);
            }
        }
    }

    // ── Resolution: request, with a callback that may never fire ────────────────────────────────

    /**
     * Answers from the analysis that is currently held.
     *
     * <p><b>Synchronously, and that is not a shortcut.</b> The expensive part — parsing and resolving —
     * already happened off-thread; what remains is a tree walk over a structure that is in memory. An
     * asynchronous hop here would add a frame of latency to a hover for no work saved. The callback
     * shape stays because the <em>contract</em> is what matters: a caller must not assume it fires, and
     * the day this consults an engine that has not analysed yet, nothing at the call sites changes.</p>
     */
    private final class AnalysisResolver implements Resolver {

        @Override
        public void resolveAt(int offset, Consumer<Versioned<SymbolInfo>> answer) {
            Analysis analysis = current;
            if (analysis == null) {
                answer.accept(Versioned.<SymbolInfo>none(buffer.version()));
                return;
            }
            answer.accept(Versioned.of(analysis.version(), analysis.resolveAt(offset)));
        }

        @Override
        public void expectedTypeAt(int offset, Consumer<Versioned<TypeRef>> answer) {
            Analysis analysis = current;
            if (analysis == null) {
                answer.accept(Versioned.<TypeRef>none(buffer.version()));
                return;
            }
            answer.accept(Versioned.of(analysis.version(), analysis.expectedTypeAt(offset)));
        }

        @Override
        public void membersOf(TypeRef type, int contextOffset,
                              Consumer<Versioned<List<SymbolInfo>>> answer) {
            Analysis analysis = current;
            if (analysis == null || type == null) {
                answer.accept(Versioned.of(buffer.version(), Collections.<SymbolInfo>emptyList()));
                return;
            }
            answer.accept(Versioned.of(analysis.version(), analysis.membersOf(type, contextOffset)));
        }
    }
}
