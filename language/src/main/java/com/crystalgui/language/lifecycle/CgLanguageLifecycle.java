package com.crystalgui.language.lifecycle;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.lifecycle.CgLifecycleListener;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.text.syntax.LanguageRegistry;

/**
 * The language stack's start, which no host names: it says which contributors are installed.
 *
 * <p>Declared {@code common} in {@code META-INF/crystalgraphics/listeners}.</p>
 *
 * <p>The log line is written once loading is over (a client) or the server is starting, never earlier: reading
 * {@code LanguageRegistry} bootstraps the engines, and each captures whether a {@code ScriptService} is registered
 * at that moment for the life of the process. Read before the platform's mod setup has run, it turns the live tier
 * off for good.</p>
 */
public final class CgLanguageLifecycle implements CgLifecycleListener {

    /** The one listener; what the engine's discovery registers. */
    public static final CgLanguageLifecycle INSTANCE = new CgLanguageLifecycle();

    private boolean contextUp;
    private boolean announced;

    private CgLanguageLifecycle() {
    }

    @Override
    public void onInit() {
        CgEngineLifecycle.onClientTick(this::clientTick);
    }

    @Override
    public void onContextInit(int width, int height) {
        contextUp = true;
    }

    @Override
    public void onServerStarting(@Nullable Object server) {
        announce();
    }

    private void clientTick() {
        if (!announced && contextUp && !CgLoadPlan.get().busy()) announce();
    }

    private synchronized void announce() {
        if (announced) return;
        announced = true;
        List<String> contributors = LanguageRegistry.contributors();
        CrystalGuiCore.LOGGER.info("language contributors: {}", contributors);
    }
}
