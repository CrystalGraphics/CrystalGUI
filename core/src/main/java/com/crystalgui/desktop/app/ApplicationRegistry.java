package com.crystalgui.desktop.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;

/**
 * What is <b>installed</b> on this desktop, and what is <b>running</b> — the shell's side of an
 * application.
 *
 * <p>One per {@link Desktop}, reached with {@code desktop.applications()}. It is the answer to every
 * question a shell asks about products rather than about windows:</p>
 *
 * <ul>
 *   <li>{@link #installed()} — what a launcher lists. Answerable with nothing running.</li>
 *   <li>{@link #handlerFor} — which product opens this file, and {@link #open} launches it and shows it.</li>
 *   <li>{@link #launch(ApplicationKind, LaunchContext)} — start one, or activate the instance already
 *       there when the manifest says {@code singleInstance}.</li>
 *   <li>{@link #running()} — every live instance, which is what the taskbar groups by and what a
 *       "quit everything" walks.</li>
 *   <li>{@link #onDidChange} — fired when either list moves, so a launcher redraws itself.</li>
 * </ul>
 *
 * <h3>Nothing installs these; they are discovered</h3>
 *
 * <p>Every {@link ApplicationKinds} service on the classpath is run once against this registry, lazily,
 * on the first question anybody asks — so what a desktop offers is a function of the jars present rather
 * than of which loader started it, and a mod's application arrives through exactly the door ours does.
 * {@link #install} stays public for a manifest built at run time.</p>
 *
 * <h3>Installing is not launching</h3>
 *
 * <p>A manifest is data, so everything above can be answered with nothing running. That is what the
 * shell could not do while an application was a class a host constructed: the only way to know one
 * existed was to build it, and the only way to know what it opened was to build it and ask.</p>
 *
 * <h3>Each instance gets its own corner</h3>
 *
 * <p>{@link #launch} scopes the {@link ConfigStorage} it is given by the kind's id before handing over a
 * {@link LaunchContext}, so two applications on one desktop keep separate sessions and preferences with
 * neither of them doing anything about it.</p>
 */
public final class ApplicationRegistry {

    private final Desktop desktop;
    private final List<ApplicationKind> installed = new ArrayList<>();
    private final List<Application> running = new ArrayList<>();

    /** Something was installed, launched or quit — what a launcher and a taskbar redraw from. */
    public final Signal.Action onDidChange = new Signal.Action();

    public ApplicationRegistry(Desktop desktop) {
        this.desktop = desktop;
    }

    /** Whether the {@link ApplicationKinds} services have been run against this registry. */
    private boolean bootstrapped;

    /**
     * Runs every {@link ApplicationKinds} service once — what makes this desktop's offering a function
     * of the CLASSPATH rather than of which loader happened to start it.
     *
     * <p>Called at the top of every read below, so a launcher drawing a list, an "open with" lookup and
     * a launch all find the same set with nothing having been installed by hand.</p>
     *
     * <p><b>Loaded with THIS class's loader, never the context one.</b> On 1.7.10 the context
     * classloader is whatever the host left there, and LaunchWrapper's is not the one that defined
     * these classes — a {@code ServiceLoader} pointed at it finds nothing, or finds a second copy of
     * everything. The defining loader is the only one guaranteed to see the jar this interface came
     * from.</p>
     *
     * <p><b>Per registry, not per process</b>, because installing is per desktop: two shells in one
     * installation offer the same products and keep separate running instances.</p>
     */
    public void bootstrap() {
        if (bootstrapped) return;
        // SET BEFORE THE LOOP: a service installs, and `install` bootstraps, so anything else would
        // re-enter here and run every service twice.
        bootstrapped = true;
        Iterator<ApplicationKinds> services =
                ServiceLoader.load(ApplicationKinds.class, ApplicationRegistry.class.getClassLoader())
                        .iterator();
        while (true) {
            ApplicationKinds kinds;
            try {
                if (!services.hasNext()) break;
                kinds = services.next();
            } catch (ServiceConfigurationError | RuntimeException | LinkageError broken) {
                // A SERVICE THAT WILL NOT LOAD COSTS ITS OWN PRODUCTS AND NOT THE DESKTOP. The
                // iterator throws on the ENTRY, so this has to bracket `next()` rather than the body --
                // catching only around the call below leaves one mod's missing class emptying the
                // launcher.
                CrystalGuiCore.LOGGER.error("[cgui] an ApplicationKinds service could not be loaded; "
                        + "its applications are not installed", broken);
                continue;
            }
            try {
                kinds.register(this);
            } catch (RuntimeException | LinkageError failed) {
                CrystalGuiCore.LOGGER.error("[cgui] the ApplicationKinds service '{}' failed to install "
                        + "its applications: {}", kinds.getClass().getName(), failed.getMessage(), failed);
            }
        }
    }

    /**
     * Makes an application available on this desktop.
     *
     * @return a handle that withdraws it. Running instances are left alone: uninstalling is not quitting
     */
    public Disposable install(ApplicationKind kind) {
        if (kind == null) return () -> { };
        bootstrap();
        for (ApplicationKind already : installed) {
            if (already.id().equals(kind.id())) {
                // LAST ONE LOSES, said out loud -- the same rule WorkbenchExtensions follows for the same
                // reason: two manifests under one id is a packaging mistake, and the failure it produces
                // silently is one application's launcher entry opening another's product.
                CrystalGuiCore.LOGGER.warn("[cgui] an application is already installed under '{}'; "
                        + "the second manifest is ignored", kind.id());
                return () -> { };
            }
        }
        kind.freeze();
        installed.add(kind);
        onDidChange.emit();
        return () -> {
            if (installed.remove(kind)) onDidChange.emit();
        };
    }

    /** Everything installed, in installation order. */
    public List<ApplicationKind> installed() {
        bootstrap();
        return List.copyOf(installed);
    }

    @Nullable
    public ApplicationKind byId(String id) {
        bootstrap();
        for (ApplicationKind kind : installed) {
            if (kind.id().equals(id)) return kind;
        }
        return null;
    }

    /**
     * Starts one, or brings forward the instance a {@link ApplicationKind#singleInstance()} kind already
     * has.
     *
     * <p>Refuses, with a named reason, when the kind cannot run here: no factory, or a workspace that is
     * not connected under a manifest that says it needs one. Named rather than left to throw out of a
     * factory, because "this needs a world" and "the editor is broken" look identical from the outside
     * and the second is what a crash report says.</p>
     *
     * @return the instance, or null when it could not be started
     */
    @Nullable
    public Application launch(ApplicationKind kind, LaunchContext context) {
        bootstrap();
        if (kind.isSingleInstance()) {
            Application already = firstRunning(kind);
            if (already != null) {
                already.activate();
                // AND THE ARGUMENT STILL LANDS. A second launch carrying a file has to open it somewhere,
                // and for a single-instance application that somewhere is the instance already running --
                // which is what a second `open` on macOS and a second command line on Windows both do.
                for (Resource resource : context.open()) already.open(resource);
                return already;
            }
        }
        if (kind.requiresConnection() && context.workspace() == null) {
            // THE FILES LIVE ON THE SERVER, so there is genuinely nothing for a workbench to show
            // without one -- and this is the refusal `ensureEditorWindow` used to make in the 1.7.10
            // screen, which is why it is here rather than in each host. A manifest that says
            // `standalone()` is offered anyway.
            CrystalGuiCore.LOGGER.warn("[cgui] '{}' needs a server and there is none; it was not "
                    + "launched. The desktop is open and the application is not on it.", kind.id());
            return null;
        }
        if (kind.factory() == null) {
            CrystalGuiCore.LOGGER.warn("[cgui] the application '{}' declares no launch factory", kind.id());
            return null;
        }
        Application application;
        try {
            application = kind.factory().apply(context);
        } catch (RuntimeException failed) {
            // AN APPLICATION THAT THROWS COSTS ITS OWN LAUNCH AND NOTHING ELSE. A desktop is a shell:
            // one product failing to start must leave every other window on screen, the same isolation
            // an extension's activate and a dock banner provider each get.
            CrystalGuiCore.LOGGER.error("[cgui] the application '{}' failed to launch: {}",
                    kind.id(), failed.getMessage(), failed);
            return null;
        }
        if (application == null) return null;
        running.add(application);
        onDidChange.emit();
        return application;
    }

    /**
     * Launches onto <b>this desktop's own storage</b> — the usual call, and the one to reach for.
     *
     * <p>A caller supplies no directory at all: the host told the desktop once, with
     * {@code Desktop.useStorage}. The overloads below exist for a caller that genuinely needs a
     * different one, which is rare and deliberate.</p>
     *
     * <p>Refuses, with a named reason, when nobody has said where this desktop writes — rather than
     * inventing somewhere. An application whose preferences silently go to a directory nobody chose
     * looks exactly like one whose preferences do not work.</p>
     */
    @Nullable
    public Application launch(ApplicationKind kind, @Nullable Workspace workspace) {
        ConfigStorage storage = desktop.config();
        if (storage == null) {
            CrystalGuiCore.LOGGER.warn("[cgui] '{}' was not launched: this desktop has nowhere to "
                    + "write. Call Desktop.useStorage(installation) first.", kind.id());
            return null;
        }
        return launch(kind, workspace, storage, desktop.cacheRoot());
    }

    /** Launches with nothing asked of it, and nowhere to cache derived output. */
    @Nullable
    public Application launch(ApplicationKind kind, @Nullable Workspace workspace,
                              ConfigStorage storage) {
        return launch(kind, workspace, storage, null);
    }

    /** Launches with nothing asked of it. @param cacheRoot {@code crystalgui/cache}, or null for none */
    @Nullable
    public Application launch(ApplicationKind kind, @Nullable Workspace workspace,
                              ConfigStorage storage, @Nullable Path cacheRoot) {
        return launch(kind, LaunchContext.of(kind, desktop, workspace,
                scoped(kind, storage), workspacesIn(storage), cacheFor(kind, cacheRoot)));
    }

    /**
     * Launches with a file to open — and hands it to the instance already running when there is one.
     *
     * @see #handlerFor
     */
    @Nullable
    public Application open(Resource resource, @Nullable Workspace workspace,
                            ConfigStorage storage) {
        return open(resource, workspace, storage, null);
    }

    /** As {@link #open(Resource, Workspace, ConfigStorage)}, with somewhere to cache. */
    @Nullable
    public Application open(Resource resource, @Nullable Workspace workspace,
                            ConfigStorage storage, @Nullable Path cacheRoot) {
        ApplicationKind kind = handlerFor(resource);
        if (kind == null) return null;
        return launch(kind, new LaunchContext(kind, desktop, workspace, scoped(kind, storage),
                workspacesIn(storage), cacheFor(kind, cacheRoot), List.of(resource)));
    }

    /**
     * Which application opens {@code resource} — LaunchServices, from the manifests alone.
     *
     * <p>A running instance is preferred to an installed-but-not-running one when both declare it, so
     * "open with" lands in the window somebody already has open rather than starting a second product
     * over the same workspace.</p>
     */
    @Nullable
    public ApplicationKind handlerFor(Resource resource) {
        bootstrap();
        for (Application live : running) {
            if (live.kind().handles(resource)) return live.kind();
        }
        for (ApplicationKind kind : installed) {
            if (kind.handles(resource)) return kind;
        }
        return null;
    }

    /** Every running instance, in launch order. */
    public List<Application> running() {
        bootstrap();
        return List.copyOf(running);
    }

    /** Every running instance of one kind. */
    public List<Application> running(ApplicationKind kind) {
        List<Application> matches = new ArrayList<>();
        for (Application live : running) {
            if (live.kind().id().equals(kind.id())) matches.add(live);
        }
        return matches;
    }

    @Nullable
    private Application firstRunning(ApplicationKind kind) {
        List<Application> matches = running(kind);
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Drops a running instance from the list.
     *
     * <p>Called by the instance from its own {@code dispose()} rather than by the registry wrapping it:
     * an application is quit through the {@link Application} handle a caller already holds, and a
     * registry that only learned about it when <em>it</em> did the quitting would go on listing
     * everything anybody else shut down.</p>
     */
    public void forget(Application application) {
        if (running.remove(application)) onDidChange.emit();
    }

    /** D20: each application's own corner of a shared config directory. */
    private static ConfigStorage scoped(ApplicationKind kind, ConfigStorage storage) {
        return storage.scoped(StorageLayout.APPS).scoped(kind.id());
    }

    /**
     * The parent of every workspace's own store — {@code workspace-config/projects/}.
     *
     * <p>Handed over unscoped by workspace on purpose: the identity arrives with the greeting, so the
     * application scopes it. It is still not the config root, so one application cannot reach another's
     * preferences through it.</p>
     */
    private static ConfigStorage workspacesIn(ConfigStorage storage) {
        return storage.scoped(StorageLayout.PROJECTS);
    }

    /**
     * The same corner of {@code cache/}, sanitised by hand.
     *
     * <p>{@link ConfigStorage#scoped} does this substitution for us; a raw {@link Path} has nobody to
     * do it, and an application id is namespaced ({@code crystalgui:editor}) with a colon that cannot
     * be a directory on Windows.</p>
     */
    @Nullable
    private static Path cacheFor(ApplicationKind kind, @Nullable Path cacheRoot) {
        if (cacheRoot == null) return null;
        return cacheRoot.resolve(StorageLayout.APPS)
                .resolve(kind.id().replace(':', '.').replace('/', '.'));
    }
}
