package com.crystalgui.harness.scene;

import com.crystalgraphics.net.CgInMemoryTransport;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.serialization.CgStateMap;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.core.trace.UiTrace;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.FileOperations;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.protocol.FsError;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.fs.protocol.FsMethods;
import com.crystalgui.fs.provider.LocalFileSystem;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.js.JsLanguage;
import com.crystalgui.language.run.ScriptPolicy;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementTreeSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Both halves of a P6.1.10 workspace, in one process — server, client, and the transport between them.
 *
 * <p>Exists so a scene that wants real files does not have to reproduce the wiring. <b>Nothing here
 * shortcuts the protocol:</b> the client holds no reference to the filesystem, only a
 * {@link Workspace}, exactly as a client across a Minecraft connection would. That is the whole value
 * of the arrangement — a scene using this is exercising the real RPC path, not a local file API wearing
 * its clothes.</p>
 *
 * <h3>The scratch project is a real directory on disk</h3>
 *
 * <p>{@code workspace/} beside the harness, seeded once and never overwritten. Deliberately <b>not</b>
 * under {@code harness-output/}, which {@code gradle clean} deletes: everything in there is a regenerable
 * artifact, and this is the user's own scratch project. Losing it to an unrelated clean would be losing
 * work.</p>
 */
final class HarnessWorkspace {

    static final String PROJECT_ID = "harness.scratch";

    private final CgInMemoryTransport<Object> fromServer;
    private final CgInMemoryTransport<Object> fromClient;
    private final ServerUiSession<UIElement, Object> server;
    private final WorkspaceBinding<Object> binding;
    private final WatchHub hub;
    private final WorkspaceService service;
    private final ClientUiSession<UIElement, Object> session;
    private final Workspace workspace;

    HarnessWorkspace() {
        // WARMING THE LANGUAGES, and nothing more. `language/` declares its grammars, ECJ and Rhino as
        // a LanguageKinds service, so LanguageRegistry finds them on its own first read -- what this
        // line buys is paying for them HERE rather than on the keystroke that opens the first editor
        // (443ms on a Minecraft client, measured; see LanguageStack). Drop it and the harness still has
        // every language, just later.
        //
        // This used to be `LanguageStack.registerAll()`, which the 1.7.10 loader also called, and before
        // that it was three blocks here and three more in the client that had already diverged on the one
        // thing that mattered: this copy caught nothing, so a band that is present but UNOPENABLE threw
        // NoClassDefFoundError straight out of the constructor. Which engines exist, and what a missing
        // one means, are facts about language/ -- and now a host does not even say when.
        LanguageRegistry.bootstrap();

        applyScriptPolicy();

        Path root = seedScratchProject();
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject(PROJECT_ID, "Scratch", root)));

        // ALLOW_ALL, and worth being explicit about: there is no player here to guard against, and the
        // default is DENY_ALL precisely so a real host has to make this choice on purpose.
        service = new WorkspaceService(
                registry, new LocalFileSystem(registry), WorkspacePermission.ALLOW_ALL);
        hub = new WatchHub(service);

        CgInMemoryTransport<Object>[] pair = CgInMemoryTransport.pair();
        fromServer = pair[0];
        fromClient = pair[1];

        server = new ServerUiSession<>(1, new UIElementTreeSource(new UIElement()),
                new UIElementMirror<>(CgPlainOps.INSTANCE), fromServer, CgPlainOps.INSTANCE);
        binding = new WorkspaceBinding<>(service, hub, WorkspaceActor.LOCAL, PEER, CgPlainOps.INSTANCE);
        binding.installOn(server::onCall);
        server.open();

        session = new ClientUiSession<>(new UIElementMirror<>(CgPlainOps.INSTANCE), fromClient,
                CgPlainOps.INSTANCE);
        workspace = Workspace.over(session::call, session::onNotify, CgPlainOps.INSTANCE);
    }

    /** The one peer this process has. A hub keys its subscriptions by it, so it only has to be stable. */
    private static final Object PEER = new Object();

    Workspace workspace() {
        return workspace;
    }

    /**
     * Every change under the scratch project, as one recursive watch.
     *
     * <p>A watch is a real subscription over the wire, so this costs the same round trip a client in a
     * Minecraft world pays — which is the point of the arrangement.</p>
     */
    void onFileChanged(Consumer<FsMessages.FileChange> listener) {
        workspace.watch(Resource.of(CgPath.ofProject(PROJECT_ID)), true).onChanged
                .connect(changes -> changes.forEach(listener));
    }

    /**
     * True once the session has a window id.
     *
     * <p>Before that the server discards every packet addressed to another window, so a call made too
     * early is thrown away with <b>no error at all</b> -- and the tree simply stays empty with nothing to
     * explain it. Whoever asks for the project list has to wait for this.</p>
     */
    boolean isConnected() {
        return session.windowId() >= 0;
    }

    /** One network tick, and the watch hub's. Called once a frame. */
    void pump(float deltaSeconds) {
        try (CgTrace.Zone ignored = CgTrace.zone(UiTrace.FRAME, "workspace:transport")) {
            fromServer.deliver();
            fromClient.deliver();
            session.tick();
            server.tick();
        }

        // The hub decides everything about the watcher -- events, cadence, reconciling -- as it does for
        // WorkspaceHost; this host only carries the answer.
        try (CgTrace.Zone ignored = CgTrace.zone(UiTrace.FRAME, "workspace:hubUpdate")) {
            notifyChanges(hub.update(WorkspaceActor.LOCAL, deltaSeconds));
        }
    }

    private void notifyChanges(Map<Object, List<FsMessages.FileChange>> byPeer) {
        List<FsMessages.FileChange> mine = binding.changesFor(byPeer);
        if (mine.isEmpty()) return;
        // NOTIFY, not call. The client subscribes to CHANGED on the NOTIFICATION channel
        // (Workspace.over(session::call, session::onNotify)), so a request went out on a channel
        // nothing there listens to and was dropped -- silently, since onError was null. Every file
        // watch in the harness was dead: no reload of an open file, no tree refresh, and a conflict
        // discovered only at save time by the etag check. WorkspaceHost fans out with notify too.
        server.notify(FsMethods.CHANGED, new CgStateMap<>(CgPlainOps.INSTANCE,
                FsMessages.changedNotification().encode(CgPlainOps.INSTANCE,
                        new FsMessages.ChangedNotification(mine))));
    }

    void read(CgPath path, Consumer<FileOperations.Content> onLoaded, Consumer<String> onFailure) {
        workspace.files().readWhole(Resource.of(path))
                .then(onLoaded::accept)
                .onError(failure -> onFailure.accept(failure.code()));
    }

    /**
     * Saves, reporting a conflict distinctly — a stale write is the one failure with a recovery path
     * rather than an error message.
     */
    void save(CgPath path, String text, String etag, Runnable onSaved, Consumer<Boolean> onFailure) {
        workspace.files().write(Resource.of(path), text.getBytes(StandardCharsets.UTF_8), etag)
                .then(written -> onSaved.run())
                .onError(failure -> onFailure.accept(
                        failure instanceof FsError problem && problem.is(FsError.CONFLICT)));
    }

    /** Overrides the default filter — a prefix list, or {@code none} to switch it off. @see #applyScriptPolicy */
    public static final String POLICY_PROPERTY = "cgui.harness.scriptPolicy";

    /**
     * What the harness refuses unless told otherwise — {@code UNSAFE} plus file access.
     *
     * <p>{@code unsafe} is {@link ScriptPolicy#UNSAFE}: reflection, method handles, {@code ClassLoader},
     * {@code Runtime}, {@code ProcessBuilder}, {@code java.security} and the internals.</p>
     *
     * <p><b>{@code java.io.File} and not {@code java.io}</b>, which was the first spelling and was
     * unusable: {@code System.out} is a {@code java.io.PrintStream}, so refusing the package refuses
     * <em>printing</em> — every script that logs anything, which in a harness is all of them. The class
     * a demonstration actually wants is the one that touches the disk. It is a reminder that a denial is
     * a veto with no way to punch a hole in it: "{@code java.io} except {@code PrintStream}" cannot be
     * spelled, and should not be, so the entry has to be the narrow one.</p>
     */
    private static final String DEFAULT_POLICY = "unsafe,java.io.File";

    /**
     * Applies the script class filter — <b>on by default</b>, at {@link #DEFAULT_POLICY}.
     *
     * <p>On rather than opt-in because a filter nobody switches on is a filter nobody tests, and because
     * the harness is the only place this stack is exercised end to end. A deployment that means to run
     * scripts unguarded should say so; so should this one.</p>
     *
     * <pre>
     *   -Dcgui.harness.scriptPolicy=none                     # off
     *   -Dcgui.harness.scriptPolicy=unsafe                   # ScriptPolicy.UNSAFE only
     *   -Dcgui.harness.scriptPolicy=unsafe,java.io,java.net  # and these too
     * </pre>
     *
     * <p>{@code unsafe} expands to {@link ScriptPolicy#UNSAFE}. Anything else in the list is a package or
     * class prefix. <b>Both languages are restricted together</b>, because a filter that applies to one
     * engine and not the other is not a filter, it is a note about which engine somebody remembered.</p>
     *
     * <p><b>What being on by default costs, stated rather than discovered:</b> Java refuses a script as a
     * WHOLE FILE, before it starts, so one refused reach takes the file with it. {@code RunTest.java} has
     * a reflection section, so under the default it does not run at all — where {@code RunTest.js} would
     * lose only the reaches themselves, because Rhino's shutter is asked per access. That asymmetry is
     * real and is what the {@code SandboxTest} pair exists to show; {@code =none} is the way back.</p>
     */
    private static void applyScriptPolicy() {
        String requested = System.getProperty(POLICY_PROPERTY, DEFAULT_POLICY);
        if (requested.trim().isEmpty() || requested.trim().equalsIgnoreCase("none")
                || requested.trim().equalsIgnoreCase("off")) {
            System.out.println("[harness] script class filter OFF (-D" + POLICY_PROPERTY + "=none)");
            return;
        }

        List<String> denied = new ArrayList<>();
        for (String entry : requested.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.equalsIgnoreCase("unsafe")) {
                denied.addAll(ScriptPolicy.UNSAFE);
            } else {
                denied.add(trimmed);
            }
        }
        if (denied.isEmpty()) return;

        ScriptPolicy policy = ScriptPolicy.denying(denied);
        JavaLanguage.restrictTo(policy);
        JsLanguage.restrictTo(policy);
        System.out.println("[harness] script class filter on: " + policy);
    }

    private static Path seedScratchProject() {
        Path root = StorageLayout.projectsIn(Paths.get(".").toAbsolutePath().normalize())
                .resolve("scratch").normalize();
        try {
            Files.createDirectories(root.resolve("src"));
            writeIfAbsent(root.resolve("README.md"),
                    "# Scratch project\n\nEdit these on disk, then save here to see a conflict.\n");
            writeIfAbsent(root.resolve("src/Main.java"),
                    "public class Main {\n    public static void main(String[] args) {\n"
                            + "        System.out.println(\"hello\");\n    }\n}\n");
            writeIfAbsent(root.resolve("src/notes.txt"), "one\ntwo\nthree\n");
            // THE WORKED EXAMPLE'S FILE. `.notes` is registered by the scenes that build a
            // workbench, so this opens as a checklist -- which is the only way to tell a kind
            // that is wired from one that merely compiles.
            writeIfAbsent(root.resolve("todo.notes"),
                    "[x] read the example\nwrite a document kind\ntick this off\n");
            // THE JAVASCRIPT FIXTURE, from a resource rather than from a string literal here. It is a
            // page long and grows a section per M10 milestone, so inlining it would put a document
            // nobody can read inside a method about directory setup -- and, worse, would make the copy
            // that ships and the copy under review two different things. `writeIfAbsent` still applies:
            // once it is on disk it is the user's scratch file and a rebuild must not overwrite it.
            copyIfAbsent(root.resolve("src/Main.js"), "harness/workspace/Main.js");
            // AND THE ONE FOR RUNNING. `Main.js` is about what the EDITOR knows before anything runs;
            // this is a transcript of what the RUNTIME does, which is a different question and fails in
            // different ways -- an engine can look entirely correct in an editor and die at its first
            // `Java.type`. Same pairing `RunTest.java` has with `Main.java`.
            copyIfAbsent(root.resolve("src/RunTest.js"), "harness/workspace/RunTest.js");
            // AND THE PAIR WRITTEN TO BE REFUSED. Both reach for classes a locked-down deployment would
            // not allow, and both are inert until the filter is switched on -- see applyScriptPolicy.
            // They are a PAIR because the two engines refuse differently and that difference is the
            // thing worth looking at: Java is refused as a whole file before it starts, JavaScript one
            // reach at a time as the shutter is asked.
            copyIfAbsent(root.resolve("src/SandboxTest.java"), "harness/workspace/SandboxTest.java");
            copyIfAbsent(root.resolve("src/SandboxTest.js"), "harness/workspace/SandboxTest.js");
            // AND THE PAIR FOR DOCUMENTATION. `Main.java` and `Main.js` are the fixtures for COLOURS;
            // these are for what Mod+Q draws. Each holds every construct its language's doc comments
            // allow -- including the ones nothing here reads yet, marked PARITY, so a gap is visible
            // rather than merely absent. The .js half also carries the Java-interop section, since a
            // Java member's documentation reaches JavaScript through a bridge that can break on its own.
            copyIfAbsent(root.resolve("src/DocShowcase.java"), "harness/workspace/DocShowcase.java");
            copyIfAbsent(root.resolve("src/DocShowcase.js"), "harness/workspace/DocShowcase.js");
            // AND THE ONE FOR THE LIBRARY VIEWER. Three Ctrl+B targets taking three different routes --
            // a JDK type with attached source, a library type shipping none so it decompiles, and a
            // nested type that lives in its outer class file. One file, so which of the three works is
            // readable in one pass rather than assembled from three.
            copyIfAbsent(root.resolve("src/Viewer.java"), "harness/workspace/Viewer.java");

            // AND THE CROSS-FILE FIXTURE (M15), which is the first thing here laid out as a real project.
            //
            // Under `src/main/java` on purpose: a source root is what turns a path into a package, and
            // the project index derives every qualified name from the path. Seeded into `src/` like the
            // files above, these would be OUTSIDE every declared root -- so the index would decline to
            // name them, nothing would resolve, and the fixture would silently prove the opposite of what
            // it is for.
            //
            // Three files, three ways of reaching another one: Main imports Greeter across packages,
            // Greeter reaches Formatter with no import at all, and Main depends on Formatter without
            // ever naming it. @see Main.java's own comment for what should be true.
            copyIfAbsent(root.resolve("src/main/java/com/example/Main.java"),
                    "harness/workspace/imports/Main.java");
            copyIfAbsent(root.resolve("src/main/java/com/example/util/Greeter.java"),
                    "harness/workspace/imports/Greeter.java");
            copyIfAbsent(root.resolve("src/main/java/com/example/util/Formatter.java"),
                    "harness/workspace/imports/Formatter.java");

            // AND THE SAME SHAPE IN JAVASCRIPT (M15 S6/S7), under `src/main/js` -- the other declared
            // root, so `util/Greeter.js` is the name `util.Greeter` that an import statement writes.
            //
            // Deliberately the same three-file chain as the Java fixture above, so the two engines can be
            // compared on one workspace: App imports Greeter, Greeter imports Formatter, and Greeter
            // importing anything at all is the case that decided how modules are loaded.
            copyIfAbsent(root.resolve("src/main/js/App.js"),
                    "harness/workspace/imports/App.js");
            copyIfAbsent(root.resolve("src/main/js/util/Greeter.js"),
                    "harness/workspace/imports/Greeter.js");
            copyIfAbsent(root.resolve("src/main/js/util/Formatter.js"),
                    "harness/workspace/imports/Formatter.js");
        } catch (IOException e) {
            throw new IllegalStateException("could not create the scratch project at " + root, e);
        }
        return root;
    }

    private static void writeIfAbsent(Path file, String content) throws IOException {
        if (!Files.exists(file)) Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Seeds a file from a classpath resource, once.
     *
     * <p>A missing resource is reported and not fatal, because the scratch project is a convenience:
     * losing one fixture must not stop the harness booting, and a stack trace here would look like a
     * rendering failure. @see #writeIfAbsent</p>
     */
    private static void copyIfAbsent(Path file, String resource) throws IOException {
        if (Files.exists(file)) return;
        // THE DIRECTORIES TOO. Every seed until M15 landed directly in `src/`, so this never had to make
        // one -- and the first fixture with a package path failed on `Files.write` with a
        // NoSuchFileException naming the file rather than the directory that was missing.
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (InputStream stream =
                     HarnessWorkspace.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                System.err.println("[harness] no seed resource " + resource + "; skipping " + file);
                return;
            }
            Files.write(file, stream.readAllBytes());
        }
    }
}
