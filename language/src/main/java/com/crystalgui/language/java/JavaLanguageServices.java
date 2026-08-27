package com.crystalgui.language.java;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
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
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.Versioned;

import java.util.ArrayList;
import java.util.function.Consumer;
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
    private final CompletionProvider completion;

    /** Same supplier arrangement, same reason. @see JavaCodeActions */
    private final JavaCodeActions actions;

    /**
     * Services for a document the reader cannot edit — a class opened in the library viewer.
     *
     * <p>Two differences from an ordinary document, and both are load-bearing.</p>
     *
     * <p><b>Diagnostics are suppressed entirely.</b> @see #reportsDiagnostics</p>
     *
     * <p><b>A platform source is parsed at compliance 8</b>, which is the last level with no module
     * system. Above it, a file declaring {@code package java.util} conflicts with {@code java.base} and
     * that single error stops the whole unit resolving — so the class would open fully underlined with
     * nothing hoverable, which is strictly worse than opening it with no services. {@code AttachedSources}
     * has parsed platform sources this way all along for the documentation popup; this is the same rule
     * reaching the same files through a different door.</p>
     *
     * @param platform whether the text came out of the JDK's own {@code src.zip} — asked of the archive
     *                 by {@code AttachedSources.isPlatformSource}, never of the package name
     */
    public static JavaLanguageServices forLibrary(TextBuffer buffer, JavaEngine engine,
                                                  JobScheduler scheduler, String className,
                                                  List<String> classpath, boolean platform) {
        return new JavaLanguageServices(buffer, engine, scheduler, className, classpath, true, platform);
    }

    /** True when this document is a borrowed one. @see #forLibrary */
    private final boolean library;

    /** True when it came out of {@code src.zip}, which decides the compliance. @see #forLibrary */
    private final boolean platform;

    @Override
    protected boolean reportsDiagnostics() {
        return !library;
    }

    /**
     * The compliance this document is analysed at.
     *
     * <p>The band's ceiling for everything a person writes, and 8 for a platform source. Derived rather
     * than configurable: there is exactly one file shape that needs the older level and exactly one
     * reason, and a setting for it would be a way to get it wrong.</p>
     */
    private int releaseLevel() {
        return library && platform ? 8 : engine.releaseLevel();
    }

    public JavaLanguageServices(TextBuffer buffer, JavaEngine engine, JobScheduler scheduler,
                                String className, List<String> classpath) {
        this(buffer, engine, scheduler, className, classpath, false, false);
    }

    private JavaLanguageServices(TextBuffer buffer, JavaEngine engine, JobScheduler scheduler,
                                 String className, List<String> classpath,
                                 boolean library, boolean platform) {
        super(ID, buffer, scheduler);
        this.engine = engine;
        this.library = library;
        this.platform = platform;
        this.className = className;
        this.classpath = classpath == null ? Collections.emptyList() : new ArrayList<>(classpath);
        long timed = FrameProfile.begin();
        this.completion = offThread(new JavaCompletionProvider(buffer, this::current,
                typeIndexFor(this.classpath), this::analyseText));
        FrameProfile.step(timed, "new JavaCompletionProvider (+ typeIndexFor)");
        timed = FrameProfile.begin();
        this.actions = new JavaCodeActions(this::current, typeIndexFor(this.classpath));
        FrameProfile.step(timed, "new JavaCodeActions");
        timed = FrameProfile.begin();
        start();
        FrameProfile.step(timed, "start() -- first analysis (inline only when there is no scheduler)");
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
        // A LIBRARY DOCUMENT IS ALWAYS A UNIT, never a snippet. It declares a type by construction --
        // it is somebody's compilation unit or a decompiler's rendering of one -- and running it through
        // the prelude would wrap a whole class inside a synthesized method.
        if (library || ScriptPrelude.declaresType(source)) {
            return engine.analyzer().analyze(className, source, classpath, releaseLevel(), version);
        }
        // THE SIMPLE NAME, because the prelude writes a class DECLARATION with it and `class com.x.Main`
        // is not Java. A qualified name reaches here whenever the file sits under a source root (M15 S4
        // names such a unit by its path), and a snippet is precisely the case with nothing to disagree
        // about: it declares no type and no package, so the path has no claim to be authoritative over.
        int lastDot = className.lastIndexOf('.');
        String simple = lastDot < 0 ? className : className.substring(lastDot + 1);
        ScriptPrelude.Wrapped wrapped = ScriptHost.preludeFor(simple, ScriptBindings.types()).wrap(source);
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
    /**
     * The same provider, <b>answering from a worker</b> — or unchanged when there is no scheduler.
     *
     * <h3>A completion is a pure function of a snapshot, and it was running on the frame thread</h3>
     *
     * <p>Every keystroke into a live list re-asks the provider, correctly: the type index is sampled, so
     * the answer is honestly partial and narrowing can reach items the sample never sent. Measured in the
     * harness, that cost <b>5-21ms per letter</b> and <b>~100ms for a member query after a dot</b>, all of
     * it synchronous on the thread that draws — which is the one thing {@code AGENTS.md} says work of this
     * shape must not do.</p>
     *
     * <p>Nothing above had to change to allow it. {@code CompletionProvider} is callback-shaped,
     * {@code CompletionSession.accept} carries a serial and drops anything superseded, and answers are
     * {@code Versioned} — the session was written for a late answer from the start, and this is the first
     * caller to give it one.</p>
     *
     * <p><b>The snapshot is taken here, on the calling thread</b>, and the work reads only that. It is
     * also why the provider itself stays synchronous: tests drive it directly, and moving the threading
     * inside would make every one of them wait for a frame.</p>
     *
     * <p>{@code JobLane.INTERACTIVE} because that lane's own documentation names this case — "a human is
     * mid-gesture and blocked on the answer, a completion query after a keystroke". One key, so a
     * keystroke arriving while the previous query is still running supersedes it rather than queueing
     * behind it; the session would have dropped the older answer regardless.</p>
     *
     * <p>Without a scheduler the provider is returned untouched, which is what keeps a headless caller
     * working: {@code onDone} runs during a window's {@code drain()}, so a scheduled answer in a process
     * that never paints would never be delivered at all.</p>
     */
    private CompletionProvider offThread(JavaCompletionProvider provider) {
        JobScheduler jobs = scheduler();
        if (jobs == null) return provider;
        JobKey key = JobKey.of(JavaLanguageServices.class, "completion");
        // AN ANONYMOUS CLASS, not a lambda: `CompletionProvider` has a second abstract method, and
        // `resolveItem` belongs to the provider unchanged -- it fills in one already-chosen row and is
        // not the thing that costs.
        return new CompletionProvider() {
            @Override
            public void complete(Request request, Consumer<Versioned<CompletionList>> answer) {
                JavaCompletionProvider.Snapshot snap = provider.snapshot();
                jobs.<Versioned<CompletionList>>job(key, JobLane.INTERACTIVE,
                                context -> provider.completeFrom(snap, request))
                        .onDone(answer::accept)
                        .submit();
            }

            @Override
            public void resolveItem(CompletionItem item, Consumer<CompletionItem> answer) {
                provider.resolveItem(item, answer);
            }
        };
    }

    public static synchronized TypeIndex typeIndexFor(List<String> classpath) {
        return TYPE_INDICES.computeIfAbsent(classpath, TypeIndex::new);
    }

    private static final Map<List<String>, TypeIndex> TYPE_INDICES = new HashMap<>();
}
