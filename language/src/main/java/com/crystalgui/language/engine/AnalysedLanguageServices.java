package com.crystalgui.language.engine;

import com.crystalgui.core.async.FrameProfile;
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
import com.crystalgui.language.map.ReadableSymbols;
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

    /**
     * Where work that is a pure function of a snapshot belongs, or <b>null for "do it here"</b>.
     *
     * <p>Null is what a test passes, and it is the whole reason a subclass may safely move something off
     * the frame thread: {@code JobScheduler}'s {@code onDone} runs during {@code drain()}, which only a
     * painting window performs — so a headless caller that scheduled an answer would wait for a frame
     * that never comes. A subclass therefore asks for the scheduler and stays synchronous without one.</p>
     */
    @Nullable
    protected JobScheduler scheduler() {
        return scheduler;
    }
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
        // FINDABLE ONLY ONCE IT IS BUILT. Registering from the constructor publishes `this` before the
        // subclass's own constructor body has run, so a runtime thread calling `attachedTo(file)` in that
        // window gets a half-built object -- and this is the one class whose whole purpose is to be found
        // from another thread. `start()` is the subclass's last line, by contract.
        if (file != null) ATTACHED.put(file, this);
        bufferSubscription = buffer.onChanged.connect(change -> schedule());
        // SYNCHRONOUS FOR A DOCUMENT THAT HAS TEXT, SCHEDULED FOR ONE THAT DOES NOT.
        //
        // "A document is analysed when the services are created" is a contract with a test named for it
        // and eight more resting on it, and it is a real one -- those tests hand this class a scheduler
        // and assert before draining it, so "analysed" means before the constructor returns. Turning the
        // whole thing into a `schedule()` fails nine of them, which is the contract doing its job.
        //
        // The discriminator is not the scheduler, it is whether there is anything to analyse. An EMPTY
        // buffer's analysis has no tokens and no diagnostics in it; the entire cost is ECJ building a
        // name environment over the classpath, and that environment travels with the analysis and is
        // released when the next one replaces it. Measured at 15ms on the frame thread, inside the
        // keystroke that opens a library viewer -- 15ms whose whole output is discarded.
        //
        // And a viewer is exactly that case by construction: `Workbench.viewerFor` builds the editor
        // empty and `readViewer` fills it from a job, so the text arriving fires `onChanged` and
        // schedules the analysis that matters. Deferring here does not lose an answer, it coalesces the
        // empty v0 into the v1 that has the document in it. A document created WITH text -- every test
        // above, and every path that opens a file whose bytes are already in hand -- still analyses
        // before this returns, because for it the eager pass is the only one there will be.
        if (buffer.length() > 0) {
            analyzeNow();
        } else {
            schedule();
        }
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
     * Re-runs the analysis because something the engine reads has changed, though the text has not.
     *
     * <p>There is exactly one such input today and it is the reason this exists: a JavaScript run leaves
     * globals behind, and a name that was unresolved before it is a global after it — so the colours and
     * every resolution answer are stale until the file is analysed again. Debounced like any other
     * trigger, because a run ending is no more urgent than a keystroke.</p>
     */
    protected final void reanalyse() {
        schedule();
    }

    /**
     * The seam's version of {@link #reanalyse}, for a change raised from outside the engine.
     *
     * <p>Same mechanism, different caller: {@link #reanalyse} is {@code protected} for an engine telling
     * itself something (a run finished), and this is what {@code core/} can reach when a project file's
     * text lands or a classpath grows. Debounced like any other trigger — a workbench announcing "the
     * world moved" cannot know whether this document cared, so it has to be cheap to be wrong.</p>
     */
    @Override
    public final void environmentChanged() {
        schedule();
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
        tokens.adopt(null, null);
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
        // NOTHING TO RETAIN FOR A DOCUMENT THAT REPORTS NOTHING, and retaining it anyway was measured at
        // 12ms on the frame that opens a decompiled class.
        //
        // The retained lane exists for one purpose: keep the optional warnings alive across a syntax
        // error so they are not withdrawn and re-announced every time the file stops parsing. That is a
        // statement about REPORTING, and a library document reports nothing -- compose() returns an empty
        // list on its very first line for exactly that reason. So this materialised every diagnostic
        // across the classloader bridge, wrote a tracked range into the buffer for each one, and handed
        // the result to a method that had already decided to discard it.
        //
        // Nothing observable changes: the lane is only ever read back by compose(), down a branch this
        // document cannot reach. @see #reportsDiagnostics
        if (reportsDiagnostics() && analysis.optionalProblemsAnalysed()) {
            remember(analysis.diagnostics());
        }
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
        // A DOCUMENT NOBODY CAN FIX REPORTS NOTHING. @see #reportsDiagnostics
        if (!reportsDiagnostics()) return Versioned.of(analysis.version(), List.of());
        List<Diagnostic> merged = new ArrayList<>(analysis.diagnostics());
        if (!analysis.optionalProblemsAnalysed()) merged.addAll(recalled(retainedLane));
        merged.addAll(recalled(runtimeLane));
        return Versioned.of(analysis.version(), merged);
    }

    /**
     * Whether this document's problems are worth telling anyone about. True for everything a person is
     * editing, which is why it is not a constructor parameter.
     *
     * <h3>The one kind that answers false</h3>
     *
     * <p>A library document — a JDK class, a decompiled one — is <b>read-only and not the author's</b>.
     * Its problems are ours: we approximate its classpath, we parse it at a compliance chosen for us,
     * and a decompiled body is a reconstruction that need not compile at all. Reporting them fills the
     * Problems panel with rows nobody can act on and marks somebody else's correct code as broken, which
     * reads as the analyser being wrong rather than as the document being borrowed.</p>
     *
     * <p><b>Resolution, hover and colouring stay on</b>, which is the whole point of analysing such a
     * document: what is suppressed is the <em>reporting</em>, not the analysis. IntelliJ draws the same
     * line — a decompiled file navigates and highlights and is never inspected.</p>
     */
    protected boolean reportsDiagnostics() {
        return true;
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
        reportRuntimeProblems(problems, null);
    }

    /**
     * @param sourceAsRun the exact text the runtime executed, when it still has it — so a report that
     *                    describes a document the buffer has moved on from is <b>dropped</b> rather than
     *                    drawn somewhere plausible and wrong.
     *
     *                    <p>A run takes time, and a file is edited while it runs. The row and column a
     *                    runtime reports are only meaningful against the text it compiled, so converting
     *                    them against the buffer as it is now puts the squiggle one row off for every line
     *                    typed above — the same "the conversion is only legal against the document the
     *                    analysis saw" rule the diagnostic lane is built on, broken by the one lane added
     *                    after it was written. With the source in hand the offsets are computed against it
     *                    and then checked: if the text at those offsets has changed, the evidence is about
     *                    something that no longer exists and the next run will speak for itself.</p>
     */
    public final void reportRuntimeProblems(List<Diagnostic> problems, @Nullable String sourceAsRun) {
        if (closed) return;
        List<DecorationSet.Entry> entries = new ArrayList<>();
        if (problems != null) {
            for (Diagnostic problem : problems) {
                if (!problem.hasPosition()) continue;
                int from;
                int to;
                if (sourceAsRun == null) {
                    from = offsetOf(problem.start());
                    to = Math.max(from, offsetOf(problem.end()));
                } else {
                    from = offsetIn(sourceAsRun, problem.start());
                    to = Math.max(from, offsetIn(sourceAsRun, problem.end()));
                    // THE SAME TEXT, OR NOTHING. `stillTrue` already withdraws a mark whose text changes
                    // under it; this is the same test applied at the moment of arrival, for the edits that
                    // happened while the script was running and which no lane was tracking yet.
                    if (to > buffer.length()
                            || !sourceAsRun.substring(from, Math.min(to, sourceAsRun.length()))
                                    .equals(textIn(from, to))) {
                        continue;
                    }
                }
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

    /** Row/column → offset in an arbitrary snapshot, clamped exactly as the buffer's own conversion is. */
    private static int offsetIn(String text, TextPoint point) {
        int row = 0;
        int at = 0;
        while (row < point.row() && at < text.length()) {
            int newline = text.indexOf('\n', at);
            if (newline < 0) break;
            at = newline + 1;
            row++;
        }
        int lineEnd = text.indexOf('\n', at);
        if (lineEnd < 0) lineEnd = text.length();
        // THE COLUMN IS CLAMPED BEFORE IT IS ADDED, never after. `Diagnostic.onRow` spells "to the end of
        // this line" as Integer.MAX_VALUE, so `at + column` OVERFLOWS to a negative number and the whole
        // mark collapses to a point — which reads as a diagnostic that was never widened rather than as
        // arithmetic. `Rope.pointToOffset` learned the same lesson; this is its second reader.
        return at + Math.max(0, Math.min(point.column(), lineEnd - at));
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
        scheduler.job(analysisKey, JobLane.LATENCY, context -> {
                    Analysis analysed = analyse(source, version);
                    // AND ITS TOKENS, while this thread still owns it exclusively. @see #semanticTokensOf
                    return new Analysed(analysed, semanticTokensOf(analysed));
                })
                .debounce(DEBOUNCE_MILLIS)
                .onDone(done -> {
                    if (done != null) install(done.analysis(), done.tokens());
                })
                .submit();
    }

    /**
     * An analysis and the work that was done on it before it was published.
     *
     * @param tokens every semantic token in the document, or null if the pull failed
     */
    private record Analysed(@Nullable Analysis analysis, @Nullable List<SyntaxToken> tokens) {
    }

    /** Analyses on the calling thread — construction, and any caller with no scheduler. */
    private void analyzeNow() {
        if (closed) return;
        install(analyse(buffer.document().toString(), buffer.version()));
    }

    /** UI thread. Swaps in the new analysis, releases the old, and tells everyone watching. */
    private void install(Analysis analysis) {
        install(analysis, null);
    }

    private void install(Analysis analysis, @Nullable List<SyntaxToken> materialisedTokens) {
        if (analysis == null) return;
        long profiled = FrameProfile.enter("install analysis v" + analysis.version() + " (" + id + ")");
        try {
            installInternal(analysis, materialisedTokens);
        } finally {
            FrameProfile.leave(profiled, "install analysis");
        }
    }

    /**
     * Every semantic token in the document, pulled across the bridge.
     *
     * <h3>Called from the WORKER that built the analysis, never from the install</h3>
     *
     * <p>It is a pure function of the analysis and it is not small: the engine lives behind a
     * classloader boundary, so this materialises tens of thousands of tokens for a 2,000-line class,
     * one crossing at a time. Doing it in {@code install} put all of it on the frame that publishes the
     * result — measured at <b>18.8ms of a 34ms install</b> on a decompiled class, which is the same
     * shape as the tree-sitter locals pass one layer down.</p>
     *
     * <p><b>Safe for exactly the reason that one is.</b> The analysis has just been built by this worker
     * and nothing else holds a reference to it yet; it is handed over only after this returns. That is
     * the whole safety argument, and it is why this belongs here rather than in a second reader thread —
     * an {@code Analysis} resolves its bindings lazily, so two threads reading one is a native race that
     * surfaces as a JVM crash rather than an exception.</p>
     *
     * <p>Null on failure rather than throwing: {@code adopt} then pulls them on the frame thread exactly
     * as it always did, which is slow rather than wrong. Colour must not be able to take an analysis
     * down with it.</p>
     */
    @Nullable
    private static List<SyntaxToken> semanticTokensOf(@Nullable Analysis analysis) {
        if (analysis == null) return null;
        try {
            return analysis.semanticTokens();
        } catch (RuntimeException failed) {
            return null;
        }
    }

    private void installInternal(Analysis analysis, @Nullable List<SyntaxToken> materialisedTokens) {
        if (closed) {
            // The document closed while this was in flight. Releasing it here rather than leaking is
            // the whole reason close() cannot simply drop the reference and walk away.
            analysis.close();
            return;
        }
        Analysis previous = current;
        current = analysis;
        if (previous != null) previous.close();

        long timed = FrameProfile.begin();
        // THE WHOLE DOCUMENT'S TOKENS, MATERIALISED ACROSS THE BRIDGE -- on the WORKER now, handed in.
        // Cheap for a script; a 2000-line decompiled class is tens of thousands of them, and pulling
        // them here spent 18.8ms of a 34ms install on the frame thread. @see #semanticTokensOf
        tokens.adopt(analysis, materialisedTokens);
        FrameProfile.step(timed, "tokens.adopt (materialised on worker: "
                + (materialisedTokens != null) + ")");
        // COMPUTED ONCE PER ANALYSIS, not once per listener. announcement() has a side effect -- it
        // replaces the retained-warning lane -- and its inputs are row/column positions that are only
        // meaningful against the document the analysis saw. Recomputing it later, when a listener happens
        // to attach, would map those positions against a buffer that has since been edited and overwrite
        // correctly-tracked ranges with wrong offsets. @see #announcement
        timed = FrameProfile.begin();
        lastAnnouncement = announcement(analysis);
        FrameProfile.step(timed, "announcement (retained lane + tracking)");
        timed = FrameProfile.begin();
        for (Consumer<Versioned<List<Diagnostic>>> listener : new ArrayList<>(diagnosticListeners)) {
            listener.accept(lastAnnouncement);
        }
        FrameProfile.step(timed, "diagnostics -> "
                + (lastAnnouncement.value() == null ? 0 : lastAnnouncement.value().size())
                + " problems, " + diagnosticListeners.size() + " listeners");
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

        /**
         * @param materialised the analysis's tokens, already pulled across the bridge by whoever built
         *                     the analysis, or null to pull them here. @see #semanticTokensOf
         */
        void adopt(@Nullable Analysis analysis, @Nullable List<SyntaxToken> materialised) {
            this.all = analysis == null ? Collections.<SyntaxToken>emptyList()
                    : materialised != null ? materialised : analysis.semanticTokens();
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
            // NOTHING, RATHER THAN A CONFIDENT ANSWER ABOUT TEXT NOBODY WROTE. @see Analysis#recoveredAt
            //
            // Applied HERE and deliberately not inside Analysis.resolveAt, which completion also calls.
            // The two ask the same question for opposite reasons: completion is asked about text that is
            // INCOMPLETE BY DEFINITION -- that is its whole job -- and it repairs the answer with a probe
            // re-parse of its own. Hover and go-to are asked about text the user believes is finished, and
            // have no such repair, so for them a recovered construct is simply not knowledge.
            if (analysis.recoveredAt(offset)) {
                answer.accept(Versioned.<SymbolInfo>none(buffer.version()));
                return;
            }
            // OFF THE FRAME THREAD, because answering can mean PARSING A WHOLE COMPILATION UNIT.
            //
            // For a symbol whose class has attached source, the answer quotes the real declaration -- so
            // resolving it parses that source file. Measured by hovering, with no scrolling at all:
            // `CgFrameBuffer` 159,360us in one frame, `CgBlendState` 29,000us. A class with no attached
            // source falls back to an assembled signature and costs ~400us, which is why the same gesture
            // looks free on some symbols and janks on others.
            //
            // The contract already allowed this: `Resolver` takes a CALLBACK, a caller is told it may
            // never fire, and the editor gates every answer on a serial and on buffer freshness. Nothing
            // at a call site changes.
            if (scheduler == null) {
                answer.accept(Versioned.of(analysis.version(), resolveUnderLock(analysis, offset)));
                return;
            }
            // ITS OWN KEY, never the analysis key: single-flight REPLACES, so sharing one would let a
            // hover cancel the analysis that hover is asking about. Single-flight among resolves is
            // exactly right, though -- the newest question is the only one anybody is waiting for.
            scheduler.job(JobKey.of(AnalysedLanguageServices.this, id + "-resolve"), JobLane.LATENCY,
                            context -> resolveUnderLock(analysis, offset))
                    .onDone(resolved -> answer.accept(Versioned.of(analysis.version(), resolved)))
                    .submit();
        }

        /**
         * The resolve itself, under the analysis's own monitor.
         *
         * <h3>Why a lock and not just a thread</h3>
         *
         * <p>JDT resolves bindings <b>lazily</b>, so {@code resolveAt} mutates the analysis as it answers
         * -- and {@code JavaCompletionProvider.memberItems} reads the same live {@code Analysis} from the
         * frame thread. Moving hover to a worker without this would be two threads mutating one JDT tree,
         * which is a crash rather than an exception.</p>
         *
         * <p>The monitor is the {@code Analysis} itself rather than a field here, so every reader can take
         * it without being handed anything: the rule is "hold the analysis to read the analysis", and it
         * is enforceable at any call site that has one.</p>
         *
         * <p>The frame thread therefore blocks only when a completion collides with a hover in flight --
         * typing and resting are close to mutually exclusive gestures, and the collision costs one
         * resolve rather than one per hover.</p>
         */
        private SymbolInfo resolveUnderLock(Analysis analysis, int offset) {
            long timed = FrameProfile.begin();
            SymbolInfo resolved;
            synchronized (analysis) {
                resolved = analysis.resolveAt(offset);
            }
            FrameProfile.step(timed, "engine.resolveAt" + (scheduler == null ? "" : " [worker]") + " -> "
                    + (resolved == null ? "nothing"
                            : resolved.kind() + " " + resolved.container() + "." + resolved.name()));
            // IN THE READABLE NAMESPACE, whatever the author spelled. The compile view declares a mapped
            // member under both names so a legacy script builds, and an engine quotes whichever
            // declaration it resolved -- so a script naming `func_71203_ab` got a popup saying exactly
            // that, where the whole point was to read it as `getConfigurationManager`. Free for anything
            // already readable, and here rather than in either engine because which namespace a
            // platform's members are SHOWN in is a fact about the runtime. @see ReadableSymbols
            return ReadableSymbols.of(resolved);
        }

        @Override
        public void describe(String name, Consumer<Versioned<SymbolInfo>> answer) {
            Analysis analysis = current;
            if (analysis == null || name == null || name.isEmpty()) {
                answer.accept(Versioned.<SymbolInfo>none(buffer.version()));
                return;
            }
            answer.accept(Versioned.of(analysis.version(), analysis.describe(name)));
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
