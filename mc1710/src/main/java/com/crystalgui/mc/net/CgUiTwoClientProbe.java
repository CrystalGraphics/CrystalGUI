package com.crystalgui.mc.net;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.net.protocol.ProtocolConnection;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Two clients on one server — {@code -PcgTwoClientProbe}, run on <b>both</b> of them.
 *
 * <h3>What one client cannot show</h3>
 *
 * <p>Everything the watcher, presence and the conflict path exist for is a statement about a SECOND
 * client, and a single client is exactly the fixture that passes against all of it: a change you made
 * yourself needs no notification to be on screen, and a save quoting an etag nobody else moved is never
 * refused. The in-memory suite runs two {@code Workspace}s over one service and covers the logic; what
 * it cannot cover is two processes, two sockets and a server deciding who hears what.</p>
 *
 * <h3>Run it twice against one server</h3>
 *
 * <pre>
 *   ./gradlew :mc1710:runServer
 *   ./gradlew :mc1710:runClient -PcgJoin=localhost:25565 -PcgTwoClientProbe=writer
 *   ./gradlew :mc1710:runClient -PcgJoin=localhost:25565 -PcgTwoClientProbe=watcher
 * </pre>
 *
 * <p>The <b>writer</b> creates a file, waits, then edits it. The <b>watcher</b> watches the folder and
 * reports what reached it and how long each notification took. Neither talks to the other: the server
 * is the only thing between them, which is the point.</p>
 *
 * <p>Start the watcher first — a watch is a subscription, and a change that happened before anybody
 * subscribed is not a change anybody missed. The watcher says so in its own log line rather than
 * leaving a reader to wonder which run was at fault.</p>
 */
public final class CgUiTwoClientProbe {

    /** {@code writer} or {@code watcher}; anything else disables the probe. */
    private static final String ROLE = System.getProperty("crystalgui.twoclient.probe", "");

    private static final boolean WRITER = "writer".equals(ROLE);
    private static final boolean WATCHER = "watcher".equals(ROLE);

    /** Long: joining a real server takes a handshake, a world download and a spawn. */
    private static final int DEADLINE_TICKS = 20 * 120;

    /** Ticks between the create and the edit, so the watcher's two reports are distinguishable. */
    private static final int BETWEEN_WRITES = 20 * 5;

    private static final String SHARED_FILE = "two-client-probe.txt";
    private static final String FIRST = "created by the writer\n";
    private static final String SECOND = "edited by the writer\n";

    private CgUiTwoClientProbe() {
    }

    public static void register() {
        if (!WRITER && !WATCHER) return;
        FMLCommonHandler.instance().bus().register(new Handler());
        CrystalGuiCore.LOGGER.info("[two-client] armed as {}", ROLE);
    }

    private static final class Handler {

        private Workspace workspace;
        private int ticks;
        private boolean reported;

        // Writer
        private boolean created;
        private boolean creating;
        private int createdAt = -1;
        private boolean edited;
        private boolean editing;

        // Watcher
        private final List<String> heard = new ArrayList<>();
        private boolean watching;

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || reported) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null) return;

            if (mc.isSingleplayer()) {
                finish(false, "this is the INTEGRATED server; two clients need two processes. "
                        + "Launch with -PcgJoin against runServer.");
                return;
            }

            ticks++;
            if (workspace == null) {
                ProtocolConnection<Object> connection = CgUiConnections.client();
                if (connection == null) {
                    if (ticks > DEADLINE_TICKS) finish(false, "joined, but no CrystalGUI connection");
                    return;
                }
                workspace = Workspace.of(connection);
                CrystalGuiCore.LOGGER.info("[two-client] connected as {}", ROLE);
            }

            if (WRITER) writerStep();
            else watcherStep();

            if (ticks > DEADLINE_TICKS) finish(false, "timed out");
        }

        // ── The writer ──────────────────────────────────────────────────────────────────────────

        private void writerStep() {
            Resource shared = Resource.of(CgPath.of(CgUiWorkspaceHost.PROJECT_ID, SHARED_FILE));

            if (!created) {
                if (creating) return;
                creating = true;
                workspace.files().write(shared, FIRST.getBytes(StandardCharsets.UTF_8), null)
                        .then(etag -> {
                            CrystalGuiCore.LOGGER.info("[two-client] wrote {} — the watcher should "
                                    + "hear about it", SHARED_FILE);
                            created = true;
                            createdAt = ticks;
                        })
                        .onError(failure -> {
                            CrystalGuiCore.LOGGER.error("[two-client] create failed: {} — if this is "
                                    + "NOT_PERMITTED the player is not an op", failure.code());
                            creating = false;
                        });
                return;
            }

            // A GAP BETWEEN THEM, so the watcher's two reports are two events rather than one
            // coalesced change. Coalescing per path is correct and is exactly what would hide the
            // second write if they landed in one tick.
            if (ticks - createdAt < BETWEEN_WRITES) return;

            if (!edited) {
                if (editing) return;
                editing = true;
                workspace.files().write(shared, SECOND.getBytes(StandardCharsets.UTF_8), null)
                        .then(etag -> {
                            CrystalGuiCore.LOGGER.info("[two-client] edited {}", SHARED_FILE);
                            edited = true;
                        })
                        .onError(failure -> {
                            CrystalGuiCore.LOGGER.error("[two-client] edit failed: {}", failure.code());
                            editing = false;
                        });
                return;
            }

            finish(true, "");
        }

        // ── The watcher ─────────────────────────────────────────────────────────────────────────

        private void watcherStep() {
            if (!watching) {
                watching = true;
                Resource root = Resource.of(CgPath.ofProject(CgUiWorkspaceHost.PROJECT_ID));
                // RECURSIVE, because the writer's file is at the project root and a folder watch that
                // did not descend would be a different assertion by accident.
                workspace.watch(root, true).onChanged.connect(changes -> {
                    for (FsMessages.FileChange change : changes) {
                        if (!change.path().endsWith(SHARED_FILE)) continue;
                        heard.add(change.kind().name());
                        CrystalGuiCore.LOGGER.info("[two-client] heard {} on {} at tick {}",
                                change.kind(), change.path(), ticks);
                    }
                });
                CrystalGuiCore.LOGGER.info("[two-client] watching the project — start the writer now "
                        + "if you have not; a change before this line is one nobody was subscribed to");
                return;
            }

            // TWO of them: a create and a modify. One would pass against a server that reported the
            // first change and then stopped, which is the failure a coalescing bug produces.
            if (heard.size() >= 2) finish(true, "");
        }

        private void finish(boolean pass, String why) {
            reported = true;
            String health = workspace == null ? "no workspace" : workspace.health().toString();
            if (pass && WRITER) {
                CrystalGuiCore.LOGGER.info("[two-client] writer PASS — created and edited {} on the "
                        + "server. The watcher's log is the other half. Health: {}", SHARED_FILE, health);
            } else if (pass) {
                CrystalGuiCore.LOGGER.info("[two-client] watcher PASS — heard {} about another "
                        + "client's writes, through the server and nothing else. Health: {}",
                        heard, health);
            } else {
                CrystalGuiCore.LOGGER.error("[two-client] {} FAIL ({}) — heard={} created={} edited={}. "
                        + "Health: {}", ROLE, why, heard, created, edited, health);
            }
            Minecraft.getMinecraft().shutdown();
        }
    }
}
