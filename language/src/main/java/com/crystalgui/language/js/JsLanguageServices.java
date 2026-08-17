package com.crystalgui.language.js;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.engine.AnalysedLanguageServices;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.engine.bridge.JsSourceAnalyzer;
import com.crystalgui.language.engine.bridge.LiveScopeSnapshot;
import com.crystalgui.language.java.TypeIndex;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.TextBuffer;

import javax.annotation.Nullable;

/**
 * The JavaScript engine, attached to one document.
 *
 * <h3>How little is left here is the point</h3>
 *
 * <p>Everything about <em>being attached</em> — debouncing a burst of keystrokes into one analysis,
 * abandoning the one in flight, installing the answer on the UI thread and releasing the previous one,
 * holding tokens for the editor to pull per row, answering resolution from the held tree, keeping
 * warnings alive through a syntax error, stamping every announcement with the version the engine
 * actually saw — is {@link AnalysedLanguageServices}, written once and shared with Java.</p>
 *
 * <p>What is Javascript's is the <b>request</b>: a source name, and nothing else. No class name, because
 * there is no compilation unit whose public type must match a file name; no classpath, because there is
 * nothing to resolve against at parse time; no release level, because the band's Rhino is the level.
 * That the whole difference between two engines fits in one method is the seam working — and the
 * measure of it is that this file is about ninety lines shorter than the first draft of the Java one,
 * without either losing anything.</p>
 *
 * <h3>The source name is the file's, and it is load-bearing at run time</h3>
 *
 * <p>Rhino puts it in every stack frame — {@code at Main.js:12 (run)} — so it is what the console's link
 * filter matches and what {@code RunPanels.resolve} turns back into a workspace file. A services object
 * built for an unsaved buffer has no name to give and says {@code script.js}, which links to nothing;
 * that is correct, because there is no file to open.</p>
 */
public final class JsLanguageServices extends AnalysedLanguageServices {

    /**
     * The owner key this engine's diagnostics are filed under.
     *
     * <p>{@code "javascript"} rather than {@code "js"}, matching {@code Language.JAVASCRIPT.name()} —
     * the Problems panel groups by owner and the status bar names it, so the two must not disagree about
     * what this language is called. Java's is {@code "java"} for the same reason.</p>
     */
    public static final String ID = "javascript";

    private final JsSourceAnalyzer analyzer;
    private final String sourceName;

    /**
     * What the last run of <em>this document</em> left in scope — the top resolution tier.
     *
     * <p>Volatile because it is written on the UI thread when a run reports and read on whichever thread
     * the analysis job happens to be on. Per document rather than on the analyser, which is one object
     * shared by every open file: a global belongs to the script that defined it.</p>
     */
    private volatile LiveScopeSnapshot liveScope = LiveScopeSnapshot.EMPTY;

    /**
     * A supplier of the held analysis rather than the analysis itself, for the reason the Java services
     * record: the held one is swapped on every parse, and a provider holding an instance would answer
     * from whichever existed when it was built — plausibly, about a document from thirty seconds ago.
     */
    private final JsCompletionProvider completion;

    public JsLanguageServices(TextBuffer buffer, JsSourceAnalyzer analyzer,
                              @Nullable JobScheduler scheduler, String sourceName) {
        this(buffer, analyzer, scheduler, sourceName, null);
    }

    /**
     * @param file the document's file, so the <b>runtime</b> can find these services after a run and
     *             put a thrown exception on its line — {@code AnalysedLanguageServices.attachedTo}. Null
     *             for a buffer with no file, which cannot be run
     */
    public JsLanguageServices(TextBuffer buffer, JsSourceAnalyzer analyzer,
                              @Nullable JobScheduler scheduler, String sourceName,
                              @Nullable Resource file) {
        this(buffer, analyzer, scheduler, sourceName, file, null);
    }

    /**
     * @param types the classpath index a {@code Java.type("…")} string completes from, or null when this
     *              build has no Java engine — in which case interop still resolves through reflection and
     *              only the class-name list is absent
     */
    public JsLanguageServices(TextBuffer buffer, JsSourceAnalyzer analyzer,
                              @Nullable JobScheduler scheduler, String sourceName,
                              @Nullable Resource file, @Nullable TypeIndex types) {
        super(ID, buffer, scheduler, file);
        this.analyzer = analyzer;
        this.sourceName = sourceName == null || sourceName.isEmpty() ? "script.js" : sourceName;
        this.completion = new JsCompletionProvider(buffer, this::current, this::liveScope,
                analyzer::keywords, types, this::analyseText);
        start();
    }

    @Override
    protected Analysis analyse(String source, long version) {
        return analyzer.analyze(sourceName, source, version, liveScope);
    }

    /**
     * Takes what a run left behind, and re-analyses so the file is read against it.
     *
     * <p><b>UI thread</b> — {@code JsHost} hops through the scheduler to get here, because a run reports
     * from the script's own thread and everything below this call is the document's.</p>
     *
     * <p>The re-analysis is the visible half: a name a previous run defined stops being drawn as
     * unresolved, and a hover over it starts saying what it actually is. Without it the snapshot would be
     * held and consulted by nothing until the next keystroke.</p>
     */
    public void setLiveScope(@Nullable LiveScopeSnapshot snapshot) {
        LiveScopeSnapshot next = snapshot == null ? LiveScopeSnapshot.EMPTY : snapshot;
        if (next == liveScope) return;
        liveScope = next;
        reanalyse();
    }

    /** What the last run left in scope, or empty. */
    public LiveScopeSnapshot liveScope() {
        return liveScope;
    }

    @Override
    public CompletionProvider completion() {
        return completion;
    }
}
