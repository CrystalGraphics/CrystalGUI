package com.crystalgui.language.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.language.platform.ScriptService;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgui.language.run.ScriptCommands;
import com.crystalgui.probe.AutoTest;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.syntax.LanguageRegistry;

/**
 * <b>The unattended run's scripting half</b> — the checks that need the language stack.
 *
 * <pre>{@code
 * LanguageProbe.register(myHost);   // once, from the language mod's client init
 * }</pre>
 *
 * <p>It lives in {@code language/} because every probe here names it: a script through the Run command,
 * live bytes out of the {@code ScriptService}, the member list a completion provider answers. The host's
 * own autotest opens the editor and photographs it, which needs none of them — so an install without
 * this jar still runs the whole capture.</p>
 *
 * <p>Each probe is registered on a painted frame through {@link AutoTest#onFrame}, which is the seam
 * that exists precisely because this ships in a <em>second jar</em> and cannot be called by name from
 * the host.</p>
 *
 * <h3>What a host has to answer</h3>
 *
 * <p>Two things, and both are honestly per-game: whether there is a pre-transform view of a class, and
 * which member a mixin has added to which receiver. Everything else — the script run, the classpath
 * report, the six completion shapes, the constant-pool diff — is the same question on every loader, and
 * was 1.7.10's alone until this moved.</p>
 */
public final class LanguageProbe {

    /** What only the running game can answer. @see LanguageProbe */
    public interface Host {

        /**
         * The <b>pre-transform</b> bytes of a class, or null where this platform has no such view.
         *
         * <p>The live-versus-raw diff is the whole of what the bytes probe proves: a member can exist
         * only because a transformer produced it, and no list of file paths can ever resolve it. That
         * needs both sides. LaunchWrapper hands out raw bytes publicly; ModLauncher and Knot expose no
         * equivalent, so a host there answers null and the probe reports the comparison as unavailable
         * rather than quietly proving nothing.</p>
         */
        @Nullable
        byte[] rawBytesOf(String internalName);

        /**
         * A receiver expression ending in {@code .} whose type a mixin has added a member to, or null.
         *
         * <p>e.g. {@code "net.minecraft.client.Minecraft.getMinecraft()."} — and the member is
         * {@link #mixinMember()}. Asked of the EDITOR rather than the compiler: a script calling it
         * compiles, and that says nothing about whether anyone could have <em>written</em> the call.
         * The compiler and the analyser reach live bytes through different entry points, and those two
         * disagreeing is the defect this was built for — every script resolved perfectly while the
         * member list beside it was empty.</p>
         */
        @Nullable
        String mixinReceiver();

        /** The member {@link #mixinReceiver()}'s type gained from a mixin. */
        @Nullable
        String mixinMember();
    }

    /**
     * A script to compile and run once the editor is up, or null to run nothing.
     *
     * <p>The EXTENSION picks the language, which is the point: the same probe run as {@code .java} and
     * as {@code .js} is the only honest comparison when one works and the other does not.</p>
     */
    private static final String SCRIPT = emptyToNull(System.getProperty("crystalgui.autotest.script"));

    /**
     * A class to compare LIVE bytes against pre-transform bytes, or null.
     *
     * <p>e.g. {@code net/minecraft/client/Minecraft}. The difference between the two sources IS the
     * capability being claimed, so this makes it visible rather than asserted.</p>
     */
    private static final String BYTES_PROBE = emptyToNull(System.getProperty("crystalgui.autotest.bytes"));

    /**
     * Whether to ask the live editor what its member list actually holds.
     *
     * <p>Every layer answers correctly everywhere it can be driven from a test — the analyser, the
     * provider on a fresh analysis and on a stale one, the whole services stack for a compilation unit
     * and for a bare snippet. Only the client disagreed, so the question is what is different about the
     * CLIENT, and a classpath assembled by the game's own launcher is the candidate no test JVM has.</p>
     */
    private static final boolean COMPLETE_PROBE =
            Boolean.parseBoolean(System.getProperty("crystalgui.autotest.complete", "false"));

    /** Which painted frame runs the script — before the capture, so one still happens. */
    private static final int RUN_SCRIPT_ON_FRAME =
            SCRIPT == null ? -1 : Integer.getInteger("crystalgui.autotest.scriptFrame", 5);

    /** A newline, spelled once — a probe source is written inline and every one of them needs one. */
    private static final String NL = String.valueOf((char) 10);

    private static Host host;
    private static boolean scriptRun;
    private static boolean bytesProbed;
    private static boolean completionProbed;

    private LanguageProbe() {
    }

    /**
     * Puts every probe on the frame it belongs to. Off unless the unattended run is, so the language jar
     * costs the host nothing on an ordinary launch.
     */
    public static void register(Host gameHost) {
        if (!AutoTest.ENABLED) return;
        host = gameHost;
        // BEFORE the capture frame, so a probe still leaves a photograph behind.
        if (RUN_SCRIPT_ON_FRAME > 0) AutoTest.onFrame(RUN_SCRIPT_ON_FRAME, LanguageProbe::runScriptOnce);
        AutoTest.onFrame(5, LanguageProbe::probeLiveBytesOnce);
        AutoTest.onFrame(6, LanguageProbe::probeCompletionOnce);
        // ...and asked much later, because the analysis behind each one is debounced onto a worker that
        // drains on THIS thread.
        AutoTest.onFrame(60, LanguageProbe::reportCompletionProbes);
    }

    /**
     * Compiles and runs one script, on the client thread, logging every step.
     *
     * <p><b>Through the Run command</b>, which is the path a user takes. It used to resolve a runtime and
     * compile by hand — a second way to start a script than the button, the keybinding and the palette,
     * and therefore a probe that could pass while the real one was broken.</p>
     *
     * <p><b>On the client thread deliberately.</b> That is where the Run command's compile happens, and a
     * probe on a worker would prove nothing about a failure that reaches the game loop: Minecraft catches
     * its own error silently and exits 0, with no crash report and nothing in the log to search for.</p>
     */
    static void runScriptOnce() {
        if (!AutoTest.ENABLED || SCRIPT == null || scriptRun) return;
        scriptRun = true;
        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST script: running {} through the Run command", SCRIPT);
        if (!CommandRegistry.global().run(ScriptCommands.RUN)) {
            CrystalGuiCore.LOGGER.error("CGUI AUTOTEST script: '{}' did not run -- no engine band, or "
                    + "nothing in front to run", ScriptCommands.RUN);
        }
    }

    /**
     * Reports every constant the LIVE runtime declares and the pre-transform class does not.
     *
     * <p>It needs no mixin of its own: the rendering backend already mixes into this client for reasons
     * that have nothing to do with scripting, which makes it a better witness than one written to pass —
     * the members it finds were put there by somebody else, before this feature existed.</p>
     *
     * <p>Both sides go through the SAME parse, so a difference cannot be an artefact of reading them
     * differently.</p>
     */
    static void probeLiveBytesOnce() {
        if (!AutoTest.ENABLED || BYTES_PROBE == null || bytesProbed) return;
        bytesProbed = true;
        try {
            ScriptService platform = CgPlatform.get(ScriptServices.SERVICE);
            if (platform == ScriptService.NONE) {
                CrystalGuiCore.LOGGER.error("CGUI AUTOTEST bytes: no platform registered");
                return;
            }
            byte[] live = platform.liveBytes().bytesOf(BYTES_PROBE);
            byte[] raw = host == null ? null : host.rawBytesOf(BYTES_PROBE);
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST bytes: {} live={} raw={}",
                    BYTES_PROBE, live == null ? -1 : live.length, raw == null ? -1 : raw.length);
            if (raw == null) {
                // SAID, not skipped: this platform has no pre-transform view, so the comparison cannot
                // be made here at all -- which is a fact about the loader, not a passing check.
                CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST bytes: this host offers no pre-transform view, "
                        + "so live-versus-raw proves nothing here");
                return;
            }
            if (live == null) return;

            if (live.length == raw.length) {
                CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST bytes: live and raw are the same size — "
                        + "no transformer changed this class, so it proves nothing");
            }
            Set<String> onlyLive = new LinkedHashSet<>(stringsIn(live));
            onlyLive.removeAll(stringsIn(raw));
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST bytes: {} constants exist in the LIVE class "
                    + "and in NO file on disk", onlyLive.size());
            int shown = 0;
            for (String constant : onlyLive) {
                if (shown++ >= 40) break;
                CrystalGuiCore.LOGGER.info("CGUI AUTOTEST bytes:     {}", constant);
            }
        } catch (Throwable failed) {
            CrystalGuiCore.LOGGER.error("CGUI AUTOTEST bytes: FAILED" + NL + "{}", describe(failed));
        }
    }

    /**
     * <b>What the member list holds in the CLIENT</b>, asked of the provider directly.
     *
     * <p>An empty popup that stays on screen is a specific thing, not merely "no answer": a session whose
     * filter empties the list closes itself, so a list rendering as nothing but a hint strip was answered
     * with zero items and marked INCOMPLETE. That is a provider answer, so this asks the provider.</p>
     *
     * <p>The classpath is logged first because it is the one input a test JVM cannot reproduce. Under a
     * game launcher the disk view is assembled for us, and on a Java 8 host the class library is
     * {@code rt.jar} inside {@code java.home} — on no URL list, in no system property, and in nothing
     * {@code getSources()} returns. Every JVM this has been driven from resolves {@code java.lang}
     * through the JRT filesystem instead, which needs no classpath entry and hides the gap completely.</p>
     */
    static void probeCompletionOnce() {
        if (!AutoTest.ENABLED || !COMPLETE_PROBE || completionProbed) return;
        completionProbed = true;
        try {
            List<String> classpath = HostClasspath.detect();
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST complete: java {} home {}",
                    System.getProperty("java.version"), System.getProperty("java.home"));
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST complete: classpath has {} entries", classpath.size());
            boolean library = false;
            for (String entry : classpath) {
                String lower = entry.toLowerCase(Locale.ROOT);
                if (lower.endsWith("rt.jar") || lower.endsWith("jce.jar") || lower.endsWith("jrt-fs.jar")) {
                    library = true;
                    CrystalGuiCore.LOGGER.info("CGUI AUTOTEST complete:   class library {}", entry);
                }
            }
            if (!library) {
                CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST complete: NO class library on the classpath — "
                        + "if this host has no JRT filesystem then java.lang resolves to nothing");
            }

            openProbe("a field receiver", "System.out." + NL, "System.out.");
            openProbe("a type receiver", "System." + NL, "System.");
            openProbe("a call receiver", "new java.util.ArrayList<String>()." + NL,
                    "new java.util.ArrayList<String>().");
            // THE MIXIN-ADDED MEMBER, and the one thing here a host has to spell. @see Host#mixinReceiver
            String receiver = host == null ? null : host.mixinReceiver();
            String member = host == null ? null : host.mixinMember();
            if (receiver != null && member != null) {
                openProbe("a game receiver", receiver + NL, receiver, member);
            }
            openProbe("a jdk-only line", "String s = \"x\"; int n = s.length(); s." + NL, "s.");
            // THE DISCRIMINATOR. Identical receiver, identical caret, the only difference being that this
            // one declares a type and so is analysed AS WRITTEN, where a bare body is wrapped in a
            // prelude and every offset translated back. If a unit answers fully and a snippet answers
            // with one interface method, the fault is in that translation and not in the member walk.
            openProbe("a unit, string receiver",
                    "class P { void m() { String s = \"x\"; s." + NL + " } }", "s.");
        } catch (Throwable failed) {
            CrystalGuiCore.LOGGER.error("CGUI AUTOTEST complete: FAILED" + NL + "{}", describe(failed));
        }
    }

    /** One pending probe: services kept alive so the debounced analysis can actually land. */
    private static final class Probe {
        final String what;
        final int caret;
        final LanguageServices services;
        /** A member the list must contain, or null. @see #reportCompletionProbes */
        final String expect;

        Probe(String what, int caret, LanguageServices services, String expect) {
            this.what = what;
            this.caret = caret;
            this.services = services;
            this.expect = expect;
        }
    }

    private static final List<Probe> PENDING = new ArrayList<>();

    private static void openProbe(String what, String source, String upTo) {
        openProbe(what, source, upTo, null);
    }

    /**
     * Opens services over {@code source} and <b>leaves them open</b>.
     *
     * <p>Asking on the same frame measures the wrong thing: the analysis is debounced and runs on a
     * worker that drains on the UI thread, so a probe that opens and asks within one call is guaranteed
     * to find no analysis — and the provider's answer to that is an EMPTY, COMPLETE list. Every shape
     * reported zero rows for that reason alone, which is indistinguishable in a log from the defect
     * being chased.</p>
     */
    private static void openProbe(String what, String source, String upTo, String expect) {
        LanguageRegistry.Entry entry = LanguageRegistry.forFileName("Probe.java");
        if (entry == null) {
            CrystalGuiCore.LOGGER.error("CGUI AUTOTEST complete: no Java entry registered");
            return;
        }
        TextBuffer buffer = new TextBuffer(source);
        LanguageServices services = entry.newServices(buffer, null);
        if (services == null) {
            CrystalGuiCore.LOGGER.error("CGUI AUTOTEST complete: no services for {}", what);
            return;
        }
        PENDING.add(new Probe(what, source.indexOf(upTo) + upTo.length(), services, expect));
    }

    /** Asks every pending probe, once the frames in between have let their analyses land. */
    @SuppressWarnings("unchecked")
    static void reportCompletionProbes() {
        if (!AutoTest.ENABLED || !COMPLETE_PROBE || PENDING.isEmpty()) return;
        for (Probe probe : PENDING) {
            try {
                final List<Diagnostic>[] problems = new List[]{null};
                probe.services.onDiagnostics(announced ->
                        problems[0] = announced.orElse(Collections.<Diagnostic>emptyList()));

                final CompletionList[] got = {CompletionList.EMPTY};
                probe.services.completion().complete(
                        CompletionProvider.Request.character(probe.caret, "", "."),
                        answer -> got[0] = answer.orElse(CompletionList.EMPTY));
                List<CompletionItem> items = got[0].items();
                StringBuilder first = new StringBuilder();
                for (int i = 0; i < Math.min(8, items.size()); i++) {
                    first.append(i == 0 ? "" : ", ").append(items.get(i).label());
                }
                CrystalGuiCore.LOGGER.info(
                        "CGUI AUTOTEST complete: {} — {} rows, incomplete={}, {} problems [{}]",
                        probe.what, items.size(), got[0].incomplete(),
                        problems[0] == null ? "no" : String.valueOf(problems[0].size()), first);
                if (probe.expect != null) {
                    boolean offered = false;
                    for (CompletionItem item : items) {
                        if (probe.expect.equals(item.filterKey())) offered = true;
                    }
                    CrystalGuiCore.LOGGER.info("CGUI AUTOTEST complete:     {} offered by the editor: {}",
                            probe.expect, offered ? "YES" : "NO");
                }
                if (problems[0] != null) {
                    int shown = 0;
                    for (Diagnostic problem : problems[0]) {
                        if (shown++ >= 4) break;
                        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST complete:     {}", problem.message());
                    }
                }
            } catch (Throwable failed) {
                CrystalGuiCore.LOGGER.error("CGUI AUTOTEST complete: {} FAILED" + NL + "{}",
                        probe.what, describe(failed));
            } finally {
                probe.services.close();
            }
        }
        PENDING.clear();
    }

    /**
     * Printable strings in a class file — its constant pool, without parsing one.
     *
     * <p><b>Not ASM</b>, deliberately. A loader module can be compiling against its game's own bundled
     * ASM, which on 1.7.10 is 5.0.3 — where {@code Opcodes.ASM9} does not exist and
     * {@code ClassRemapper} is still {@code RemappingClassAdapter}. A class visitor here would compile
     * against one ASM and, after relocation, run against another. This probe needs no parser to make
     * its point.</p>
     *
     * <p>Every method name, field name and descriptor is a UTF-8 constant, so a scan for printable runs
     * finds all of them plus some noise. Noise is harmless: it appears on <b>both</b> sides and cancels
     * in the difference. What survives is what one class file has and the other does not.</p>
     */
    private static Set<String> stringsIn(byte[] classFile) {
        Set<String> found = new LinkedHashSet<>();
        StringBuilder run = new StringBuilder();
        for (byte raw : classFile) {
            int character = raw & 0xFF;
            if (character >= 0x21 && character <= 0x7E) {
                run.append((char) character);
                continue;
            }
            if (run.length() >= 6) found.add(run.toString());
            run.setLength(0);
        }
        if (run.length() >= 6) found.add(run.toString());
        return found;
    }

    /**
     * A throwable as plain text, with its causes — safe to hand a logger.
     *
     * <p>Built here rather than with a {@code PrintWriter} because the point is that <b>no
     * {@code Throwable} object reaches log4j</b>. Frame classes are named as the strings they already
     * are; nothing is loaded to describe them.</p>
     */
    private static String describe(Throwable failed) {
        StringBuilder text = new StringBuilder();
        for (Throwable at = failed; at != null; at = at.getCause()) {
            text.append(at == failed ? "" : "Caused by: ")
                .append(at.getClass().getName()).append(": ").append(at.getMessage()).append('\n');
            StackTraceElement[] frames = at.getStackTrace();
            for (int i = 0; i < frames.length && i < 18; i++) {
                text.append("\tat ").append(frames[i]).append('\n');
            }
            if (at.getCause() == at) break;
        }
        return text.toString();
    }

    @Nullable
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
