package com.crystalgui.workbench;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.dispose.Disposable;

/**
 * Where a {@link WorkbenchExtension} makes itself available — process-wide, like {@code ContentProviders}.
 *
 * <p>Contributing is how a module says <em>this exists on this host</em>; it is not what turns it on.
 * An application's manifest names the ids it wants and a workbench activates those.</p>
 *
 * <h3>They are DISCOVERED, not listed</h3>
 *
 * <p>Every {@code WorkbenchExtension} on the classpath is found through {@link ServiceLoader}, so being
 * available is a fact about the jars present rather than about which module remembered to make a call.
 * {@link #bootstrap()} used to be a method naming what {@code core/} ships, which had two costs and the
 * second is the one that matters: it could not reach a layer above it — the shader graph lives in
 * {@code app/}, so <em>an application had to contribute it</em>, and a mod's extension could never be
 * in that list at all because the list is in code it does not own.</p>
 *
 * <p>{@link #contribute} stays for what a service cannot serve: an extension built at run time, from
 * something only the running process knows. The language stack uses it, because whether the Run shell
 * is available is a question about an engine BAND rather than about a jar.</p>
 *
 * <h3>Every contributed extension is activated, for now</h3>
 *
 * <p>The end state is an application manifest naming the ids it wants, so two applications on one
 * desktop can enable different sets — that is what makes a second editor-shaped application possible
 * without the same four panels baked into it. Until an application concept exists there is nothing to
 * ask, so the engine activates everything contributed, which is exactly today's behaviour with the
 * host's {@code register(...)} calls removed. The one thing it already fixes is the question of
 * <em>which host remembered what</em>: the harness registered the Notes kind and the 1.7.10 loader did
 * not, so a file type shipped in this repository opened in one of them and not the other.</p>
 *
 * <h3>An id nothing contributed is a logged absence, never an error</h3>
 *
 * <p>The same three-tier degradation the language stack already follows. A host with no engine band
 * lists {@code crystalgui:scripting} and gets no Run panel, and that is a fact about the deployment
 * rather than a fault in it.</p>
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
