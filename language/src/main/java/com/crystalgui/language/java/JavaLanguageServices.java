package com.crystalgui.language.java;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.diagnostic.Diagnostic;
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
import java.util.List;
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
    private final List<Consumer<List<Diagnostic>>> diagnosticListeners = new ArrayList<>();
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
    public Connection onDiagnostics(Consumer<List<Diagnostic>> listener) {
        if (listener == null || closed) return Connection.DISCONNECTED;
        diagnosticListeners.add(listener);
        // FIRED IMMEDIATELY IF THERE IS ALREADY AN ANSWER. A view attached after the first analysis
        // landed would otherwise show nothing until the next edit -- and for a file nobody types in,
        // that is forever.
        if (current != null) listener.accept(current.diagnostics());
        return () -> diagnosticListeners.remove(listener);
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
        for (Consumer<List<Diagnostic>> listener : new ArrayList<>(diagnosticListeners)) {
            listener.accept(analysis.diagnostics());
        }
    }

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
            // NOT YET, AND SAID SO. Enumerating a type's members with accessibility computed from the
            // asking context is what completion after a dot is built from -- it needs the binding
            // walked with bridges filtered, which is real work and belongs with the completion that
            // consumes it (M9). An empty list is the honest answer meanwhile; a wrong one would be a
            // list that compiles nowhere.
            answer.accept(Versioned.of(
                    current == null ? buffer.version() : current.version(),
                    Collections.<SymbolInfo>emptyList()));
        }
    }
}
