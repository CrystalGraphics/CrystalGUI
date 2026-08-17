package com.crystalgui.language.js;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.engine.AnalysedLanguageServices;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.engine.bridge.JsSourceAnalyzer;
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
        super(ID, buffer, scheduler, file);
        this.analyzer = analyzer;
        this.sourceName = sourceName == null || sourceName.isEmpty() ? "script.js" : sourceName;
        start();
    }

    @Override
    protected Analysis analyse(String source, long version) {
        return analyzer.analyze(sourceName, source, version);
    }
}
