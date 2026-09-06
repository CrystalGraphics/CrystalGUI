package com.crystalgui.widget.surface.extension;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.widget.surface.SurfaceContext;

/**
 * <b>Where every {@link SurfaceExtension} on the classpath is found</b> — process-wide, and read by each
 * surface as it is built.
 *
 * <p>You rarely call this directly: ship a services entry and your extension is discovered, and a
 * consumer names its id to enable it.</p>
 *
 * <pre>{@code
 * SurfaceExtensions.all();                        // everything this jar set offers
 * SurfaceExtensions.byId("crystalgui:select");
 * }</pre>
 *
 * <h3>Available is not enabled</h3>
 *
 * <p>{@link #activate(SurfaceContext, List)} takes the ids a consumer wants, so a graph and a builder on
 * one classpath enable different sets — which is what keeps the graph free of the builder's tools. A
 * null list means everything contributed, which is what a test or a bare surface means.</p>
 *
 * <h3>Nothing is fatal</h3>
 *
 * <p>An id nothing ships is a logged absence. An extension that throws while activating costs its own
 * feature and nothing else; the surface still opens.</p>
 */
public final class SurfaceExtensions {

    private SurfaceExtensions() {
    }

    private static final List<SurfaceExtension> CONTRIBUTED = new CopyOnWriteArrayList<>();

    private static boolean bootstrapped;

    /**
     * Adds an extension.
     *
     * @return a handle that withdraws it, so a mod that unloads takes its feature with it
     */
    public static Disposable contribute(SurfaceExtension extension) {
        if (extension == null) return () -> { };
        for (SurfaceExtension already : CONTRIBUTED) {
            if (already.id().equals(extension.id())) {
                // Two extensions claiming one id is a packaging mistake, and the failure it produces
                // otherwise is one feature silently replacing another's.
                CrystalGuiCore.LOGGER.warn("[cgui] a surface extension is already contributed under "
                        + "'{}'; the second one is ignored", extension.id());
                return () -> { };
            }
        }
        CONTRIBUTED.add(extension);
        return () -> CONTRIBUTED.remove(extension);
    }

    /** Everything contributed, in contribution order — which is activation order. */
    public static List<SurfaceExtension> all() {
        bootstrap();
        return List.copyOf(CONTRIBUTED);
    }

    /** The one contributed under {@code id}, or null. */
    @Nullable
    public static SurfaceExtension byId(String id) {
        bootstrap();
        for (SurfaceExtension each : CONTRIBUTED) {
            if (each.id().equals(id)) return each;
        }
        return null;
    }

    /**
     * Finds every extension on the classpath. Idempotent, and called by every read.
     *
     * <p><b>Loaded with THIS class's loader, never the context one.</b> On 1.7.10 the context
     * classloader is whatever the host left there, and LaunchWrapper's is not the one that defined these
     * classes.</p>
     */
    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        // Set before the loop: a service's constructor may legitimately read this, and re-entering
        // would run every service twice.
        bootstrapped = true;
        Iterator<SurfaceExtension> services =
                ServiceLoader.load(SurfaceExtension.class, SurfaceExtensions.class.getClassLoader())
                        .iterator();
        while (true) {
            SurfaceExtension extension;
            try {
                if (!services.hasNext()) break;
                extension = services.next();
            } catch (ServiceConfigurationError | RuntimeException | LinkageError broken) {
                // The iterator throws on the ENTRY, so this brackets next(): catching only around the
                // body would let one mod's missing class stop every extension after it in the file.
                CrystalGuiCore.LOGGER.error("[cgui] a SurfaceExtension service could not be loaded; "
                        + "its feature is absent on this host", broken);
                continue;
            }
            contribute(extension);
        }
    }

    /**
     * Activates the ones {@code wanted} names, in the order they were named.
     *
     * @param wanted the ids, or null for everything contributed
     * @return the handles, in activation order, for the caller to dispose
     */
    public static List<Disposable> activate(SurfaceContext surface, @Nullable List<String> wanted) {
        List<Disposable> active = new ArrayList<>();
        if (wanted == null) {
            for (SurfaceExtension extension : all()) {
                Disposable handle = activateOne(extension, surface);
                if (handle != null) active.add(handle);
            }
            return active;
        }
        for (String id : wanted) {
            SurfaceExtension extension = byId(id);
            if (extension == null) {
                CrystalGuiCore.LOGGER.info("[cgui] the surface extension '{}' is not present on this "
                        + "host; the surface opens without it", id);
                continue;
            }
            Disposable handle = activateOne(extension, surface);
            if (handle != null) active.add(handle);
        }
        return active;
    }

    @Nullable
    private static Disposable activateOne(SurfaceExtension extension, SurfaceContext surface) {
        try {
            return extension.activate(surface);
        } catch (RuntimeException failed) {
            CrystalGuiCore.LOGGER.error("[cgui] the surface extension '{}' failed to activate: {}",
                    extension.id(), failed.getMessage(), failed);
            return null;
        }
    }

    /** Empties the registry, bootstrap included. For tests that need isolation, never for production. */
    public static synchronized void resetForTesting() {
        CONTRIBUTED.clear();
        bootstrapped = false;
    }
}
