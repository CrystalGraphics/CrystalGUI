package com.crystalgui.language.java;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.language.engine.AnalysedLanguageServices;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.java.assist.JavaCompletionProvider;
import com.crystalgui.language.java.classpath.TypeIndex;
import com.crystalgui.language.java.exec.ScriptHost;
import com.crystalgui.language.java.exec.ScriptPrelude;
import com.crystalgui.language.java.fix.JavaCodeActions;
import com.crystalgui.language.run.ScriptBindings;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CodeActionProvider;
import com.crystalgui.text.lang.CompletionProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Java engine, attached to one document.
 *
 * <h3>What is left here is Java, and only Java</h3>
 *
 * <p>Everything about <em>being attached</em> — debounce, install, retention through a syntax error,
 * semantic tokens, resolution — is {@link AnalysedLanguageServices}, written once for every engine that
 * produces an {@link Analysis}. What this adds is the request shape ECJ needs (a class name, a classpath,
 * a release level), a type index over that classpath for the two providers that offer unimported types,
 * and the providers themselves. A JavaScript engine's counterpart will be about this long and will share
 * none of these lines, which is the test that the split landed in the right place.</p>
 */
public final class JavaLanguageServices extends AnalysedLanguageServices {

    /** The owner key this engine's diagnostics are filed under. @see LanguageServices#id() */
    public static final String ID = "java";

    private final JavaEngine engine;
    private final String className;
    private final List<String> classpath;

    /**
     * Built once per document, reading the current analysis through {@link #current()}.
     *
     * <p>A supplier rather than the analysis itself, because the held analysis is swapped on every compile
     * and a provider holding the instance would answer from whichever analysis existed when it was built —
     * silently going stale after the first edit, which is the worst version: the list is plausible and
     * describes a document from thirty seconds ago.</p>
     */
    private final JavaCompletionProvider completion;

    /** Same supplier arrangement, same reason. @see JavaCodeActions */
    private final JavaCodeActions actions;

    public JavaLanguageServices(TextBuffer buffer, JavaEngine engine, JobScheduler scheduler,
                                String className, List<String> classpath) {
        super(ID, buffer, scheduler);
        this.engine = engine;
        this.className = className;
        this.classpath = classpath == null ? Collections.emptyList() : new ArrayList<>(classpath);
        this.completion = new JavaCompletionProvider(buffer, this::current,
                typeIndexFor(this.classpath), this::analyseText);
        this.actions = new JavaCodeActions(this::current, typeIndexFor(this.classpath));
        start();
    }

    /**
     * ECJ over this document — <b>wrapped first when it is a script rather than a compilation unit</b>.
     *
     * <p>A Java script is a body: statements, with the host bindings already in scope. {@code ScriptHost}
     * has always wrapped one through {@link ScriptPrelude} before compiling it, and this path did not —
     * so a bare snippet <em>ran correctly</em> and the editor covered it in the parser trying to read
     * {@code System.out.println(...)} as a member declaration. Thirty syntax errors on a file with none.
     * The two paths now ask the same question of the same text.</p>
     *
     * <p>{@link ScriptPrelude#declaresType} is the same test {@code ScriptHost.compileSource} uses, so a
     * file cannot be a unit to one and a snippet to the other. And the bindings come from the same
     * registry the runtime injects from, which is what makes a name the host provides resolve in the
     * editor instead of reading as undefined.</p>
     */
    @Override
    protected Analysis analyse(String source, long version) {
        if (ScriptPrelude.declaresType(source)) {
            return engine.analyzer().analyze(className, source, classpath, engine.releaseLevel(), version);
        }
        ScriptPrelude.Wrapped wrapped = ScriptHost.preludeFor(className, ScriptBindings.types()).wrap(source);
        Analysis unit = engine.analyzer().analyze(wrapped.className(), wrapped.unitSource(), classpath,
                engine.releaseLevel(), version);
        // AND EVERY ANSWER TRANSLATED BACK, or the editor would be describing a document the author
        // cannot see. @see SnippetAnalysis
        return new SnippetAnalysis(unit, wrapped, source.length());
    }

    @Override
    public CompletionProvider completion() {
        return completion;
    }

    @Override
    public CodeActionProvider codeActions() {
        return actions;
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
    /**
     * The index for a classpath, shared by every consumer of it.
     *
     * <p>Public because the JavaScript engine completes {@code Java.type("a.b.C")} from the same index:
     * one scan of one classpath answers for both languages, and a second index would be the same fifty
     * thousand entries built twice. @see TypeIndex's visibility note</p>
     */
    public static synchronized TypeIndex typeIndexFor(List<String> classpath) {
        return TYPE_INDICES.computeIfAbsent(classpath, TypeIndex::new);
    }

    private static final Map<List<String>, TypeIndex> TYPE_INDICES = new HashMap<>();
}
