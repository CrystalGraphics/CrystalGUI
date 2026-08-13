package com.crystalgui.language.java;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.decoration.DecorationSet;
import com.crystalgui.text.decoration.Stickiness;
import com.crystalgui.text.decoration.TrackedRange;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.lang.Resolver;
import com.crystalgui.text.lang.SemanticTokenProvider;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Java engine, attached to one document.
 *
 * <h3>Where the two async shapes meet</h3>
 *
 * <p>One analysis feeds both, and that is why they can be different shapes without duplicating work:</p>
 *
 * <ul>
 *   <li><b>Semantic tokens and diagnostics push.</b> Nobody asked for them; the document changed, so an
 *       analysis is scheduled, and when it lands the editor is <em>told</em>. It then pulls per row from
 *       what is held — the same shape {@code SyntaxTokenizer} has, so M1b's per-row cache works on it
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
 */
public final class JavaLanguageServices implements LanguageServices {

    /** The owner key this engine's diagnostics are filed under. @see LanguageServices#id() */
    public static final String ID = "java";

    /**
     * How long a burst of typing is allowed to be before an analysis starts.
     *
     * <p>300ms is the plan's figure for diagnostics and it is a compromise between two visible faults:
     * shorter and a fast typist starts a compile per word, longer and a genuine pause feels unresponsive.
     * Not a constant anybody should tune without measuring on a real document.</p>
     */
    private static final long DEBOUNCE_MILLIS = 300;

    private final TextBuffer buffer;
    private final JavaEngine engine;
    private final JobScheduler scheduler;
    private final String className;
    private final List<String> classpath;

    private final Connection bufferSubscription;
    private final List<Consumer<Versioned<List<Diagnostic>>>> diagnosticListeners = new ArrayList<>();
    private final SemanticTokens tokens = new SemanticTokens();

    /** The most recent analysis, and the only mutable state here. UI thread only. */
    private SourceAnalyzer.Analysis current;
    private boolean closed;

    public JavaLanguageServices(TextBuffer buffer, JavaEngine engine, JobScheduler scheduler,
                                String className, List<String> classpath) {
        this.buffer = buffer;
        this.engine = engine;
        this.scheduler = scheduler;
        this.className = className;
        this.classpath = classpath == null ? Collections.emptyList() : new ArrayList<>(classpath);
        this.completion = new JavaCompletionProvider(buffer, () -> current,
                typeIndexFor(this.classpath), this::analyseText);
        this.bufferSubscription = buffer.onChanged.connect(change -> schedule());
        // ONE ANALYSIS AT CONSTRUCTION, undebounced. A document that is opened and not typed in would
        // otherwise have no colours and no problems until the first keystroke -- which is exactly the
        // state a file spends most of its time in.
        analyzeNow();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SemanticTokenProvider semanticTokens() {
        return tokens;
    }

    @Override
    public Resolver resolver() {
        return new AnalysisResolver();
    }

    @Override
    public CompletionProvider completion() {
        return completion;
    }

    /**
     * Built once per document, reading {@code current} through a supplier.
     *
     * <p>A supplier rather than the analysis itself, because {@code current} is swapped on every compile and
     * a provider holding the instance would answer from whichever analysis existed when it was built —
     * silently going stale after the first edit, which is the worst version: the list is plausible and
     * describes a document from thirty seconds ago.</p>
     */
    private final JavaCompletionProvider completion;

    @Override
    public Connection onDiagnostics(Consumer<Versioned<List<Diagnostic>>> listener) {
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

    /**
     * The analysis's problems, stamped with the document version they describe.
     *
     * <p>The version is the <b>analysis's</b>, never {@code buffer.version()} read at announce time. Those
     * differ by exactly the typing that happened while the compile ran, which is the whole quantity the
     * stamp exists to measure — reading it here would make every list look fresh and the gate a no-op.</p>
     */
    private Versioned<List<Diagnostic>> announcement(SourceAnalyzer.Analysis analysis) {
        List<Diagnostic> reported = analysis.diagnostics();
        if (analysis.optionalProblemsAnalysed()) {
            remember(reported);
            return Versioned.of(analysis.version(), reported);
        }
        List<Diagnostic> merged = new ArrayList<>(reported);
        merged.addAll(recalled());
        return Versioned.of(analysis.version(), merged);
    }

    // ── Warnings survive a syntax error ─────────────────────────────────────────────────────────

    /**
     * Where the retained optional problems live, so they follow the edits made while the file is broken.
     *
     * <p>A lane in the <b>document's own</b> {@code DecorationSet}, which {@code TextBuffer.applied}
     * adjusts before any listener runs — the same machinery the editor's squiggles use, and the reason
     * this costs no tracking code of its own.</p>
     */
    private static final String RETAINED_LANE = "java-retained-warnings";

    /**
     * Remembers this analysis's optional problems, <b>replacing</b> whatever was held.
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
        buffer.decorations().replaceLane(RETAINED_LANE,
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
    private List<Diagnostic> recalled() {
        List<Diagnostic> out = new ArrayList<>();
        for (TrackedRange range : buffer.decorations().inLane(RETAINED_LANE)) {
            if (range.isRemoved() || range.collapsedByEdit()) continue;
            Diagnostic original = range.payload(Diagnostic.class);
            if (original == null) continue;
            out.add(new Diagnostic(pointOf(range.from()), pointOf(range.to()), original.severity(),
                    original.message(), original.source(), original.code()));
        }
        return out;
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
        scheduler.job(JobKey.of(this, "java-analysis"), JobLane.LATENCY,
                        context -> engine.analyzer().analyze(className, source, classpath,
                                engine.releaseLevel(), version))
                .debounce(DEBOUNCE_MILLIS)
                .onDone(this::install)
                .submit();
    }

    /**
     * One index per distinct classpath, shared by every document that has it.
     *
     * <p>Per-document would rescan tens of thousands of jar entries for every file opened, for an answer
     * that cannot differ — the classpath does not change while the process runs. Keyed on the list itself
     * rather than on the engine, because two engines on one classpath should still share one scan.</p>
     *
     * <p>Unbounded, and that is fine: a process has one or two classpaths, so this is a map with one or two
     * entries whose lifetime is the process's. Evicting would mean rescanning.</p>
     */
    private static synchronized TypeIndex typeIndexFor(List<String> classpath) {
        return TYPE_INDICES.computeIfAbsent(classpath, TypeIndex::new);
    }

    private static final Map<List<String>, TypeIndex> TYPE_INDICES = new HashMap<>();

    /**
     * Analyses arbitrary text on the calling thread, for the completion probe.
     *
     * <p>Version {@code -1}, because this describes text the document does not contain and must never be
     * mistaken for an answer about it. The caller closes it.</p>
     */
    private SourceAnalyzer.Analysis analyseText(String text) {
        if (closed) return null;
        return engine.analyzer().analyze(className, text, classpath, engine.releaseLevel(), -1L);
    }

    /** Analyses on the calling thread — construction, and any caller with no scheduler. */
    private void analyzeNow() {
        if (closed) return;
        install(engine.analyzer().analyze(className, buffer.document().toString(), classpath,
                engine.releaseLevel(), buffer.version()));
    }

    /** UI thread. Swaps in the new analysis, releases the old, and tells everyone watching. */
    private void install(SourceAnalyzer.Analysis analysis) {
        if (analysis == null) return;
        if (closed) {
            // The document closed while this was in flight. Releasing it here rather than leaking is
            // the whole reason close() cannot simply drop the reference and walk away.
            analysis.close();
            return;
        }
        SourceAnalyzer.Analysis previous = current;
        current = analysis;
        if (previous != null) previous.close();

        tokens.adopt(analysis);
        // COMPUTED ONCE PER ANALYSIS, not once per listener. announcement() has a side effect now -- it
        // replaces the retained-warning lane -- and its inputs are row/column positions that are only
        // meaningful against the document the analysis saw. Recomputing it later, when a listener happens
        // to attach, would map those positions against a buffer that has since been edited and overwrite
        // correctly-tracked ranges with wrong offsets. @see #announcement
        lastAnnouncement = announcement(analysis);
        for (Consumer<Versioned<List<Diagnostic>>> listener : new ArrayList<>(diagnosticListeners)) {
            listener.accept(lastAnnouncement);
        }
    }

    /** The last list handed out, replayed verbatim to a listener that attaches later. @see #install */
    private Versioned<List<Diagnostic>> lastAnnouncement;

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        bufferSubscription.disconnect();
        if (scheduler != null) scheduler.cancel(JobKey.of(this, "java-analysis"));
        diagnosticListeners.clear();
        tokens.adopt(null);
        if (current != null) {
            current.close();
            current = null;
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

        void adopt(SourceAnalyzer.Analysis analysis) {
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
            SourceAnalyzer.Analysis analysis = current;
            if (analysis == null) {
                answer.accept(Versioned.<SymbolInfo>none(buffer.version()));
                return;
            }
            answer.accept(Versioned.of(analysis.version(), analysis.resolveAt(offset)));
        }

        @Override
        public void expectedTypeAt(int offset, Consumer<Versioned<TypeRef>> answer) {
            SourceAnalyzer.Analysis analysis = current;
            if (analysis == null) {
                answer.accept(Versioned.<TypeRef>none(buffer.version()));
                return;
            }
            answer.accept(Versioned.of(analysis.version(), analysis.expectedTypeAt(offset)));
        }

        @Override
        public void membersOf(TypeRef type, int contextOffset,
                              Consumer<Versioned<List<SymbolInfo>>> answer) {
            SourceAnalyzer.Analysis analysis = current;
            if (analysis == null || type == null) {
                answer.accept(Versioned.of(buffer.version(), Collections.<SymbolInfo>emptyList()));
                return;
            }
            answer.accept(Versioned.of(analysis.version(), analysis.membersOf(type, contextOffset)));
        }
    }
}
