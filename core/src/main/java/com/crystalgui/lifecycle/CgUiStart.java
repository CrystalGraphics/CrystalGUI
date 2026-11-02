package com.crystalgui.lifecycle;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.theme.ThemeRegistry;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * CrystalGUI's start, on a worker ahead of the first UI: the style properties declared, every widget kind registered,
 * the user-agent stylesheet parsed and the built-in themes registered (a host calling this ships the workbench, whose
 * theme picker registers them at its first use anyway). Half a second to two on the render thread otherwise, at the first UI built;
 * started at a host's client install, it runs while the host loads, as the loading screen's task {@code crystalgui:start}.
 *
 * <pre>{@code
 * CgUiStart.ahead();                     // a host's client install, as early as it can: any thread, once
 * if (CgUiStart.done()) buildFirstUi();  // a host with frames to draw meanwhile asks
 * CgUiStart.await();                     // one with none waits, before it builds its first UI
 * }</pre>
 *
 * <ul>
 *   <li>Everything it runs also starts itself at its first use, so a host that never calls it pays the start there.</li>
 *   <li>GL-free, so it may run before the graphics start; nothing in it touches a texture or a material.</li>
 *   <li>Build no UI on another thread while it runs: a widget's class initialising beside it could wait on a class it
 *       holds. {@link #await} first, or ask {@link #done}.</li>
 * </ul>
 */
public final class CgUiStart {

    @Nullable private static CompletableFuture<Void> started;

    private CgUiStart() {
    }

    /** Starts it on a background worker, the first call; the start either way. */
    public static synchronized CompletableFuture<Void> ahead() {
        if (started == null) {
            CgLoadPlan.Span task = CgLoadPlan.get().begin("crystalgui:start");
            started = CompletableFuture.runAsync(() -> {
                try {
                    run();
                } finally {
                    task.close();
                }
            }, CgJob.executor(CgJobLane.BACKGROUND));
        }
        return started;
    }

    /** Whether it has ended, or was never started. Any thread. */
    public static synchronized boolean done() {
        return started == null || started.isDone();
    }

    /** Waits for it, if it was started. */
    public static void await() {
        CompletableFuture<Void> f;
        synchronized (CgUiStart.class) {
            f = started;
        }
        if (f != null) f.join();
    }

    private static void run() {
        long begun = System.nanoTime();
        StringBuilder took = new StringBuilder("[start] CrystalGUI, on a worker:");
        step(took, "style properties", () -> touch(StylePropertyRegistry.class));
        step(took, "widget kinds", UIElementRegistry::bootstrap);
        step(took, "user-agent sheet", () -> touch(StyleSheet.class));
        step(took, "built-in themes", ThemeRegistry::registerBuiltins);
        CrystalGuiCore.LOGGER.info("{} ({} ms)", took, (System.nanoTime() - begun) / 1_000_000L);
    }

    /** One part, timed; a failure is logged and left to that part's own first use, which reports it again. */
    private static void step(StringBuilder took, String what, Runnable part) {
        long at = System.nanoTime();
        try {
            part.run();
        } catch (RuntimeException | LinkageError e) {
            CrystalGuiCore.LOGGER.warn("CrystalGUI's start: {} failed ahead; it starts at its first use", what, e);
        }
        took.append(' ').append(what).append(' ').append((System.nanoTime() - at) / 1_000_000L);
    }

    /** {@code type}'s class initializer, run now. */
    private static void touch(Class<?> type) {
        try {
            Class.forName(type.getName(), true, type.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
