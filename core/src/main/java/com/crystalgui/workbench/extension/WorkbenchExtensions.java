package com.crystalgui.workbench.extension;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.workbench.WorkbenchContext;

/**
 * <b>Where every {@link WorkbenchExtension} on the classpath is found</b> - process-wide, and read by
 * each workbench as it is built.
 *
 * <p>You rarely call this directly. Ship a services entry and your extension is discovered; name its id
 * in an {@code ApplicationKind} and that application activates it. This class is what joins the two.</p>
 *
 * <pre>{@code
 * WorkbenchExtensions.all();               // everything this jar set offers
 * WorkbenchExtensions.byId("crystalgui:problems");
 * }</pre>
 *
 * <h3>Available is not enabled</h3>
 *
 * <p>Being on the classpath makes a feature <em>available</em>; a manifest naming its id turns it
 * <em>on</em>. {@link #activate(WorkbenchContext, List)} takes that list, so two applications on one
 * desktop enable different sets - which is what makes a second, differently-shaped product possible
 * without the same panels baked into it. A null list means everything contributed, and that is not a
 * transitional default: it is what {@code new Workbench(workspace)} still means for a test or a scene
 * with no application around it.</p>
 *
 * <h3>Nothing is fatal</h3>
 *
 * <p>An id nothing ships is a logged absence, so a manifest can name a feature that is simply not
 * present on some hosts - which is how {@code crystalgui:scripting} behaves where there is no engine
 * band. An extension that throws while activating costs its own feature and nothing else; the workbench
 * still comes up.</p>
 *
 * <h3>{@link #contribute} is for what a service cannot serve</h3>
 *
 * <p>An extension built at run time, from something only the running process knows. Nothing in this
 * repository needs it today. Two extensions claiming one id is a packaging mistake and is said out
 * loud: the second is ignored rather than silently replacing the first.</p>
 */
public final class WorkbenchExtensions {

    private WorkbenchExtensions() {
    }

    private static final List<WorkbenchExtension> CONTRIBUTED = new CopyOnWriteArrayList<>();

    private static boolean bootstrapped;

    /**
     * Adds an extension.
     *
     * @return a handle that withdraws it, so a mod that unloads takes its feature with it
     */
    public static Disposable contribute(WorkbenchExtension extension) {
        if (extension == null) return () -> { };
        for (WorkbenchExtension already : CONTRIBUTED) {
            if (already.id().equals(extension.id())) {
                // LAST ONE LOSES, and it is said out loud. Two extensions claiming one id is a packaging
                // mistake -- a mod shipped twice, or two mods that picked the same string -- and the
                // failure it produces otherwise is one feature silently replacing another's.
                CrystalGuiCore.LOGGER.warn("[cgui] a workbench extension is already contributed under "
                        + "'{}'; the second one is ignored", extension.id());
                return () -> { };
            }
        }
        CONTRIBUTED.add(extension);
        return () -> CONTRIBUTED.remove(extension);
    }

    /** Everything contributed, in contribution order — which is activation order. */
    public static List<WorkbenchExtension> all() {
        bootstrap();
        return List.copyOf(CONTRIBUTED);
    }

    /** The one contributed under {@code id}, or null. */
    @Nullable
    public static WorkbenchExtension byId(String id) {
        bootstrap();
        for (WorkbenchExtension each : CONTRIBUTED) {
            if (each.id().equals(id)) return each;
        }
        return null;
    }

    /**
     * Finds every extension on the classpath. Idempotent, and called by every read.
     *
     * <p>Here rather than in a static initialiser on each extension, because a class nobody has touched
     * has not initialised — the trap {@code UIElementRegistry} records for element kinds, one layer
     * down. And a {@code ServiceLoader} rather than a list, because a list can only name what the
     * class holding it is allowed to see.</p>
     *
     * <p><b>Loaded with THIS class's loader, never the context one.</b> On 1.7.10 the context
     * classloader is whatever the host left there, and LaunchWrapper's is not the one that defined
     * these classes.</p>
     */
    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        // SET BEFORE THE LOOP: `contribute` reads nothing that bootstraps, but a service's constructor
        // legitimately might, and re-entering here would run every service twice.
        bootstrapped = true;
        Iterator<WorkbenchExtension> services =
                ServiceLoader.load(WorkbenchExtension.class, WorkbenchExtensions.class.getClassLoader())
                        .iterator();
        while (true) {
            WorkbenchExtension extension;
            try {
                if (!services.hasNext()) break;
                extension = services.next();
            } catch (ServiceConfigurationError | RuntimeException | LinkageError broken) {
                // A SERVICE THAT WILL NOT LOAD COSTS ITS OWN FEATURE AND NOT THE WORKBENCH. The
                // iterator throws on the ENTRY, so this brackets `next()` -- catching only around the
                // body would let one mod's missing class stop every extension after it in the file.
                CrystalGuiCore.LOGGER.error("[cgui] a WorkbenchExtension service could not be loaded; "
                        + "its feature is absent on this host", broken);
                continue;
            }
            contribute(extension);
        }
    }

    /**
     * Activates the ones {@code wanted} names, in the order the manifest named them.
     *
     * <p>An id nothing contributed is a <b>logged absence</b>, never an error — the three-tier
     * degradation the language stack already follows, and what lets {@code crystalgui:scripting} be
     * listed by every application and be simply missing on a host with no engine band. An extension that
     * is contributed and not listed is not activated: that is how two applications on one desktop enable
     * different sets, which is the whole reason the manifest names ids rather than the engine deciding.
     * </p>
     *
     * @param wanted the ids, or null for everything contributed — which is what a {@code Workbench}
     *               built directly, by a test or a scene with no application around it, still means
     */
    public static List<Disposable> activate(WorkbenchContext workbench, @Nullable List<String> wanted) {
        if (wanted == null) return activateAll(workbench);
        List<Disposable> active = new ArrayList<>();
        for (String id : wanted) {
            WorkbenchExtension extension = byId(id);
            if (extension == null) {
                CrystalGuiCore.LOGGER.info("[cgui] the extension '{}' is not present on this host; "
                        + "the application runs without it", id);
                continue;
            }
            Disposable handle = activateOne(extension, workbench);
            if (handle != null) active.add(handle);
        }
        return active;
    }

    /**
     * Activates every contributed extension against {@code workbench}.
     *
     * <p><b>An extension that throws costs its own feature and nothing else.</b> Activation runs while a
     * workbench is being built, so letting one out would take the whole application down over a mod's
     * mistake — the same isolation a dock banner provider gets, for the same reason.</p>
     *
     * @return the handles, in activation order, for the caller to dispose
     */
    public static List<Disposable> activateAll(WorkbenchContext workbench) {
        List<Disposable> active = new ArrayList<>();
        for (WorkbenchExtension extension : all()) {
            Disposable handle = activateOne(extension, workbench);
            if (handle != null) active.add(handle);
        }
        return active;
    }

    @Nullable
    private static Disposable activateOne(WorkbenchExtension extension, WorkbenchContext workbench) {
        try {
            return extension.activate(workbench);
        } catch (RuntimeException failed) {
            CrystalGuiCore.LOGGER.error("[cgui] the workbench extension '{}' failed to activate: {}",
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
