package com.crystalgui.language.run;

import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.LanguageRegistry;

import javax.annotation.Nullable;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The runtimes a workbench can run scripts through — and the one place a language says it has one.
 *
 * <h3>Two halves: what is contributed, and what a workbench opened</h3>
 *
 * <p>The static half is the registry, shaped like {@link ScriptBindings} and for the same reason: which
 * languages can run is not something the shell can know. {@code JavaLanguage.register()} contributes a
 * {@link Provider} when its engine opens; a JavaScript language will contribute its own; a dedicated
 * server contributes both and never opens a panel. <b>Adds, never replaces</b>, so registration order
 * does not matter — except that a later contribution for the same {@link Language} wins, which at least
 * makes the behaviour orderable and describable.</p>
 *
 * <p>The instance half is what one workbench holds: every contributed runtime, {@linkplain #open opened}
 * with the workbench's own cache root, closed together when the workbench goes. The shell asks it which
 * runtime a file belongs to, whether anything anywhere is running, and to stop everything — the three
 * questions that used to be asked of one concrete Java host.</p>
 *
 * <h3>Files are matched through the {@link LanguageRegistry}, not through extensions kept here</h3>
 *
 * <p>Which language a file is written in already has one answer, and it is that registry's; keeping a
 * second extension table here is the two-switches-that-disagree-about-{@code .frag} problem the registry
 * exists to prevent. So a runtime names its {@link Language} and the file's registry entry names the
 * same one, and {@link #forFile} joins them.</p>
 */
public final class ScriptRuntimes implements Closeable {

    /** Opens a runtime for one workbench, given where it may cache compiled scripts (null: memory only). */
    @FunctionalInterface
    public interface Provider {
        ScriptRuntime open(@Nullable Path cacheRoot);
    }

    private record Contribution(Language language, Provider provider) {
    }

    /** Copy-on-write: read on every install, written once per language at startup. */
    private static final List<Contribution> CONTRIBUTIONS = new CopyOnWriteArrayList<>();

    /** Says a language can run scripts. Called by the language's own {@code register()}, once. */
    public static void contribute(Language language, Provider provider) {
        if (language == null || provider == null) return;
        CONTRIBUTIONS.add(new Contribution(language, provider));
    }

    /** For a test, and for a host tearing the stack down. */
    public static void clearContributions() {
        CONTRIBUTIONS.clear();
    }

    /** Whether any language has said it can run scripts. */
    public static boolean anyContributed() {
        return !CONTRIBUTIONS.isEmpty();
    }

    /**
     * Opens every contributed runtime for one workbench.
     *
     * <p>Later wins on a language collision, per the class note. A provider that throws costs that
     * language its runtime and not the workbench its console — the same rule {@link ScriptBindings}
     * applies to a supplier that throws.</p>
     */
    public static ScriptRuntimes open(@Nullable Path cacheRoot) {
        Map<Language, ScriptRuntime> opened = new LinkedHashMap<>();
        for (Contribution contribution : CONTRIBUTIONS) {
            try {
                ScriptRuntime runtime = contribution.provider().open(cacheRoot);
                if (runtime == null) continue;
                ScriptRuntime displaced = opened.put(contribution.language(), runtime);
                if (displaced != null) displaced.close();
            } catch (RuntimeException failed) {
                System.err.println("[crystalgui] the " + contribution.language().name()
                        + " runtime did not open; scripts in that language will not run: " + failed);
            }
        }
        return new ScriptRuntimes(opened);
    }

    /** Exactly these runtimes — a test, or a host that assembles its own set. */
    public static ScriptRuntimes of(ScriptRuntime... runtimes) {
        Map<Language, ScriptRuntime> byLanguage = new LinkedHashMap<>();
        for (ScriptRuntime runtime : runtimes) {
            if (runtime != null) byLanguage.put(runtime.language(), runtime);
        }
        return new ScriptRuntimes(byLanguage);
    }

    private final Map<Language, ScriptRuntime> byLanguage;

    private ScriptRuntimes(Map<Language, ScriptRuntime> byLanguage) {
        this.byLanguage = byLanguage;
    }

    public boolean isEmpty() {
        return byLanguage.isEmpty();
    }

    public List<ScriptRuntime> all() {
        return Collections.unmodifiableList(new ArrayList<>(byLanguage.values()));
    }

    /** The runtime for a language, or null. */
    @Nullable
    public ScriptRuntime forLanguage(@Nullable Language language) {
        return language == null ? null : byLanguage.get(language);
    }

    /** The runtime for the language a file's name resolves to, or null when nothing here runs it. */
    @Nullable
    public ScriptRuntime forFile(@Nullable String fileName) {
        return forLanguage(LanguageRegistry.forFileName(fileName).language());
    }

    /**
     * The languages that can run, as the file registry names them — for a message that says what would
     * have worked: {@code java, javascript}.
     */
    public String languageNames() {
        StringBuilder names = new StringBuilder();
        for (Language language : byLanguage.keySet()) {
            if (names.length() > 0) names.append(", ");
            names.append(language.name());
        }
        return names.toString();
    }

    /** Whether any runtime has a live run. The Stop command's {@code enabledWhen}. */
    public boolean isAnyRunning() {
        for (ScriptRuntime runtime : byLanguage.values()) {
            if (runtime.isRunning()) return true;
        }
        return false;
    }

    /** Stops every live run. Returns whether there was anything to stop. */
    public boolean stopAll() {
        boolean stopped = false;
        for (ScriptRuntime runtime : byLanguage.values()) stopped |= runtime.stop();
        return stopped;
    }

    /** Every runtime's console filters, in registration order. */
    public List<ConsoleFilter> consoleFilters() {
        List<ConsoleFilter> filters = new ArrayList<>();
        for (ScriptRuntime runtime : byLanguage.values()) filters.addAll(runtime.consoleFilters());
        return filters;
    }

    /** Points every runtime at the same sessions. */
    public ScriptRuntimes reportTo(@Nullable RunSessions sessions) {
        for (ScriptRuntime runtime : byLanguage.values()) runtime.reportTo(sessions);
        return this;
    }

    @Override
    public void close() {
        for (ScriptRuntime runtime : byLanguage.values()) runtime.close();
    }
}
