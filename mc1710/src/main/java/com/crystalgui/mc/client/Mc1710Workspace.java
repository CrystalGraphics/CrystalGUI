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
import com.crystalgui.language.LanguageStack;
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
 * <h3>The language stack is switched on here, but not decided here</h3>
 *
 * <p>{@link LanguageStack#registerAll()} — which grammars exist, which engines, in what order and what
 * a missing one means are facts about {@code language/}, not about this platform. This constructor's
 * only contribution is the <em>moment</em>: before anything opens a document. {@code ClientProxy} calls
 * it earlier still, at FML init, and the two overlapping is free because it is idempotent.</p>
 */
public final class Mc1710Workspace {

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
        LanguageStack.registerAll();
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
