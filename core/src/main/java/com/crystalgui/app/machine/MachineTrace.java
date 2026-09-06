package com.crystalgui.app.machine;

import com.crystalgui.core.CrystalGuiCore;

/**
 * Every line this example prints, stamped with <b>the thread it happened on</b>.
 *
 * <h3>Why the thread and not just the message</h3>
 *
 * <p>Because in a single-player world the server and the client are in one process, and that is the
 * configuration in which a threading mistake is invisible. A handler that touches the widget tree
 * from the wrong side <em>works</em> there — the two threads share a heap and the race is rare —
 * and then fails on a dedicated server, or on a laggy tick, as something that looks nothing like a
 * threading bug.</p>
 *
 * <p>This repository has already paid for that twice, and neither trace named the thread as the
 * problem. A script thread flipping a button's {@code setEnabled} reached {@code invalidateStyleMatch}
 * and threw {@code ArrayIndexOutOfBoundsException} from inside {@code HashMap.keysToArray} while the
 * UI thread was copying the dirty-match set — with nothing about the offending subsystem anywhere in
 * the stack. And an earlier version of {@code CgUiSessionProbe} drove both halves from the client
 * tick and passed, because a single-player integrated server shares the process.</p>
 *
 * <p>So the rule this exists to make visible: <b>the server thread owns the server's tree and the
 * client thread owns the client's.</b> When you run the in-game example, the console should show
 * {@code Server thread} for everything on the server side and {@code Client thread} for everything
 * on the client side, with no line crossing over. A line in the wrong column is a bug you can see
 * before it costs you anything.</p>
 *
 * <h3>Why {@code info} and not {@code debug}</h3>
 *
 * <p>An example nobody can see running is an example nobody believes. This is deliberately loud and
 * deliberately not something to copy into a shipping mod — a real panel logs when it opens and when
 * it fails, and says nothing per tick.</p>
 */
public final class MachineTrace {

    /** Column label for anything that happened on the authoritative side. */
    public static final String SERVER = "SERVER";

    /** Column label for anything that happened on the drawing side. */
    public static final String CLIENT = "CLIENT";

    /** Set false to quieten the example without unpicking the calls. */
    public static volatile boolean enabled = true;

    private MachineTrace() {
    }

    public static void log(String side, String message) {
        if (!enabled) return;
        // Padded so the two columns line up in a console, which is what makes a stray thread jump out.
        CrystalGuiCore.LOGGER.info("[machine/{}] {}", side,  message);
    }
}
