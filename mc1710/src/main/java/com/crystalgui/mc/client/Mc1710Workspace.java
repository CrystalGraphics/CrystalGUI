package com.crystalgui.mc.client;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.LocalFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceProject;
import com.crystalgui.fs.WorkspaceRpc;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.grammar.TreeSitterLanguages;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.js.JsLanguage;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.UIElement;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Both halves of a real workspace, in the client process.
 *
 * <p>Modelled on the harness's {@code HarnessWorkspace} and deliberately not simplified: the client
 * here has <b>no reference to the filesystem</b>, only to a {@link WorkspaceClient}, and every listing,
 * read and write crosses {@link InMemoryTransport} as a real packet. Shortcutting that would make the
 * later phase — the same client against a workspace on a dedicated server — a rewrite rather than a
 * transport swap.</p>
 *
 * <h3>The language stack is registered here</h3>
 *
 * <p>Same order as {@code HarnessWorkspace}: grammars, then the engines, before a document exists.
 * See {@link #registerLanguages()} — every piece degrades on its own, and none is fatal.</p>
 */
final class Mc1710Workspace {

    static final String PROJECT_ID = "minecraft.workspace";

    private final InMemoryTransport<Object> fromServer;
    private final InMemoryTransport<Object> fromClient;
    private final ServerUiSession<Object> server;
    private final ClientUiSession<Object> session;
    private final WorkspaceClient<Object> client;
    private final WorkspaceRpc<Object> rpc;

    /** Seconds until the next filesystem watcher poll. @see #pump */
    private float untilPoll;

    Mc1710Workspace(Path root) {
        registerLanguages();
        seed(root);

        ProjectRegistry registry = new ProjectRegistry().register(() -> Collections.singletonList(
                new WorkspaceProject(PROJECT_ID, "Workspace", root)));

        // ALLOW_ALL, and worth being explicit about: the default is DENY_ALL precisely so a host has to
        // make this choice on purpose. This is a single-player client, against local disk, with no other
        // actor to guard against -- there is no one to deny. THIS IS THE LINE THAT CHANGES when a
        // server-hosted workspace lands, and it is deliberately easy to find.
        WorkspaceService service = new WorkspaceService(
                registry, new LocalFileSystem(registry), WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        fromServer = pair[0];
        fromClient = pair[1];

        server = new ServerUiSession<Object>(1, new UIElement(), fromServer, PlainOps.INSTANCE);
        rpc = new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL);
        rpc.installOn(server::onCall);
        server.open();

        session = new ClientUiSession<Object>(fromClient, PlainOps.INSTANCE);
        client = new WorkspaceClient<Object>(session, PlainOps.INSTANCE);
    }

    /**
     * Grammars first, then the analysis engines — <b>before anything opens a document</b>.
     *
     * <p>{@code LanguageRegistry} is consulted when an editor is built, so a file already open would
     * keep whichever tokenizer it was handed. {@code core/} ships word-list lexers so it loads with no
     * natives at all; this puts the real parsers in front of them, which is what makes a declaration
     * distinguishable from a call and a constant from an identifier.</p>
     *
     * <p>Each half degrades on its own and neither is fatal. Without the engines the editor colours and
     * nothing else — no diagnostics, a parameter and a field take one colour because only resolution can
     * tell them apart, and Run has nothing to run. None of that reports itself, which is why the misses
     * are logged: {@code LanguageServices} being absent is the feature flag the whole stack degrades
     * through, so it reads as those features not existing rather than as not being switched on.</p>
     */
    private static void registerLanguages() {
        probeTreeSitterNative();
        try {
            TreeSitterLanguages.register();
        } catch (Throwable t) {
            // A missing native is the expected failure here, and it must not take the editor with it.
            CrystalGuiCore.LOGGER.warn("tree-sitter grammars are off; the built-in lexers will colour", t);
        }
        registerEngine("Java", JavaLanguage::register);
        registerEngine("JavaScript", JsLanguage::register);
    }

    /**
     * Registers one engine, and <b>never lets it take the editor down</b>.
     *
     * <p>{@code EngineHost} already treats an absent band as legitimate — it returns false and prints a
     * line. What it does not promise is that a band which is <em>present but unopenable</em> fails the
     * same way, and under LaunchWrapper that is exactly what happens: the staged jars are found, so
     * registration proceeds past the early return, and then {@code EngineHost.adapter} throws
     * {@code NoClassDefFoundError: org/mozilla/javascript/ErrorReporter} out of
     * {@code EngineClassLoader} — a loader-visibility problem between the band's isolation and
     * Minecraft's own class loader.</p>
     *
     * <p>An {@code Error} is not caught by anything upstream, so it propagated out of {@code initGui}
     * and killed the client. <b>That is strictly worse than having no engines</b>, which is a supported
     * configuration: the editor is meant to colour and not analyse. Catching {@code Throwable} rather
     * than {@code Exception} is the point — {@code NoClassDefFoundError} is an {@code Error}.</p>
     */
    private static void registerEngine(String name, java.util.concurrent.Callable<Boolean> register) {
        try {
            if (Boolean.TRUE.equals(register.call())) return;
            CrystalGuiCore.LOGGER.warn("{} analysis is off: no engine band under {}", name,
                    System.getProperty(EngineHost.ENGINES_DIRECTORY_PROPERTY, "<unset>"));
        } catch (Throwable t) {
            CrystalGuiCore.LOGGER.warn("{} analysis is off: the engine band did not open. The editor "
                    + "will colour but not analyse.", name, t);
        }
    }

    /**
     * Forces the tree-sitter native to load, and says what happened.
     *
     * <p>{@code TreeSitterLanguages.register()} is LAZY — it records grammars and builds no parser — so
     * a silent registration proves nothing about whether the JNI library can load at all. On this
     * platform that is the open question: upstream {@code tree-sitter-ng} returns {@code JNI_VERSION_10}
     * from {@code JNI_OnLoad}, and a Java 8 VM rejects any version above {@code JNI_VERSION_1_8}
     * outright, so {@code System.loadLibrary} throws before a single byte is parsed.</p>
     *
     * <p>Reflective on purpose: {@code org.treesitter} is bundled into the mod jar but is not on this
     * module's compile classpath, and putting it there to run one probe would be the wrong trade.</p>
     */
    private static void probeTreeSitterNative() {
        try {
            Class.forName("org.treesitter.TSParser").newInstance();
            CrystalGuiCore.LOGGER.info("tree-sitter native loaded - grammars are live on this JVM");
        } catch (Throwable t) {
            CrystalGuiCore.LOGGER.warn("tree-sitter native did NOT load: {}", String.valueOf(t));
        }
    }

    WorkspaceClient<Object> client() {
        return client;
    }

    /**
     * True once the session has a window id.
     *
     * <p>Before that the server discards every packet addressed to another window, so a call made too
     * early is thrown away with <b>no error at all</b> — and the file tree simply stays empty with
     * nothing to explain it. Whoever asks for the project list has to wait for this.</p>
     */
    boolean isConnected() {
        return session.windowId() >= 0;
    }

    /** One network tick, plus the watcher poll when it is due. Called once a frame. */
    void pump(float deltaSeconds) {
        fromServer.deliver();
        fromClient.deliver();
        session.tick();
        server.tick();

        untilPoll -= deltaSeconds;
        if (untilPoll <= 0f) {
            untilPoll = 0.5f;
            rpc.pollAndNotify((method, args) -> server.call(method, args, null, null), PlainOps.INSTANCE);
        }
    }

    /**
     * Creates the workspace directory, and a README the first time.
     *
     * <p>An empty file tree and a broken file tree look identical, which is the whole reason for the
     * README: the first launch needs something in it that proves a listing crossed the transport.</p>
     */
    private static void seed(Path root) {
        try {
            Files.createDirectories(root);
            Path readme = root.resolve("README.md");
            if (!Files.exists(readme)) {
                List<String> lines = java.util.Arrays.asList(
                        "# CrystalGUI workspace",
                        "",
                        "This directory is served to the editor through the same RPC protocol a remote",
                        "workspace would use — the client holds no filesystem handle of its own.",
                        "",
                        "Anything you put here shows up in the file tree.");
                Files.write(readme, lines, Charset.forName("UTF-8"));
            }
        } catch (IOException e) {
            // Not fatal: the tree will simply be empty, and the editor still opens. Failing the whole
            // screen because a README could not be written would be a worse trade.
            CrystalGuiCore.LOGGER.warn("Could not seed the workspace at " + root, e);
        }
    }
}
