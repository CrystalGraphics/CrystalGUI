package com.crystalgui.mc.net;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.net.protocol.ProtocolConnection;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;

/**
 * The workspace against a <b>genuinely separate server process</b> — {@code -PcgRemoteProbe}.
 *
 * <p>{@link CgUiSessionProbe} runs against the integrated server, and that is a real connection through
 * Forge's channel — but the two halves share a JVM, a heap and a filesystem. So it cannot answer the one
 * question B2 actually claims: <b>are the files on the server's machine?</b> In single-player the answer
 * is trivially yes and means nothing, because there is only one machine.</p>
 *
 * <p>This runs on a client that joined {@code runServer} over a socket. Every byte crosses a real
 * network stack, the server's disk is a different directory, and the proof is that a file this probe
 * creates appears under the <em>server's</em> {@code run/server/crystalgui/workspace} and nowhere near
 * the client's {@code run/client}.</p>
 *
 * <h3>It refuses to run in single-player, deliberately</h3>
 *
 * <p>A probe that silently degrades to the integrated server would report a pass that proves nothing —
 * which is worse than not running, because the report reads the same. {@code isSingleplayer()} is
 * checked and the probe fails outright if it is true.</p>
 *
 * <h3>Writes need an operator, and that is the feature</h3>
 *
 * <p>{@code CgUiWorkspaceHost.OperatorsMayWrite} lets anyone read and only operators write. On a
 * dedicated server the joining player is not an op unless {@code ops.json} says so, so a write failing
 * with {@code NO_PERMISSIONS} here is <b>B4 working</b>, not a bug — the run script ops the dev player
 * so the write half can be exercised at all.</p>
 */
public final class CgUiRemoteWorkspaceProbe {

    private static final boolean ENABLED = Boolean.getBoolean("crystalgui.remote.probe");

    /** Long: joining a real server takes a handshake, a world download and a spawn. */
    private static final int DEADLINE_TICKS = 20 * 90;

    private static final String PROBE_FILE = "remote-probe.txt";
    private static final String FIRST_TEXT = "written by the client, stored on the server\n";

    /** What step 4 writes, so the file on disk says which step last touched it. */
    private static final String EDITED_TEXT = "EDITED by the client, stored on the server\n";

    private static Workspace files;
    private static int ticks;

    private static volatile boolean listed;
    private static volatile boolean created;
    private static volatile boolean readBack;
    private static volatile boolean deltaApplied;
    private static volatile boolean reported;

    private static volatile boolean listing;
    private static volatile boolean creating;
    private static volatile boolean reading;
    private static volatile boolean deltaing;

    private CgUiRemoteWorkspaceProbe() {
    }

    public static void register() {
        if (!ENABLED) return;
        FMLCommonHandler.instance().bus().register(new Handler());
        CrystalGuiCore.LOGGER.info("[remote-probe] armed; waiting to join a DEDICATED server");
    }

    public static final class Handler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (!ENABLED || event.phase != TickEvent.Phase.END || reported) return;

            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.thePlayer == null) {
                if (++ticks > DEADLINE_TICKS) finish(false, "never joined a server");
                return;
            }
            // A screen would pause nothing here -- there is no integrated server to pause -- but the
            // editor is not what this probe is testing, and leaving one open only adds noise.
            if (mc.currentScreen != null) mc.displayGuiScreen(null);

            if (mc.isSingleplayer()) {
                finish(false, "this is the INTEGRATED server; a pass here would prove nothing about "
                        + "where the files live. Launch with -PcgJoin against runServer.");
                return;
            }

            ticks++;
            if (files == null) {
                ProtocolConnection<Object> connection = CgUiConnections.client();
                if (connection == null) {
                    if (ticks > DEADLINE_TICKS) finish(false, "joined, but no CrystalGUI connection");
                    return;
                }
                files = Workspace.of(connection);
                CrystalGuiCore.LOGGER.info("[remote-probe] connected to a REMOTE server "
                        + "(isSingleplayer=false), CrystalGUI connection is live");
            }

            step();

            if (ticks % 40 == 0) {
                CrystalGuiCore.LOGGER.info("[remote-probe] listed={} created={} readBack={} delta={}",
                        listed, created, readBack, deltaApplied);
            }
            if (listed && created && readBack && deltaApplied) finish(true, "");
            else if (ticks > DEADLINE_TICKS) finish(false, "timed out");
        }

        /** One request in flight at a time, so a stall names the step it stalled on. */
        private void step() {
            CgPath root = CgPath.ofProject(CgUiWorkspaceHost.PROJECT_ID);
            CgPath probe = CgPath.of(CgUiWorkspaceHost.PROJECT_ID, PROBE_FILE);

            if (!listed) {
                if (listing) return;
                listing = true;
                files.files().list(Resource.of(root))
                        .then(answer -> {
                            CrystalGuiCore.LOGGER.info("[remote-probe] 1/4 listed {} entries from the "
                                    + "REMOTE server", answer.entries().size());
                            listed = true;
                        })
                        .onError(failure -> {
                            CrystalGuiCore.LOGGER.error("[remote-probe] listing failed: {}", failure.code());
                            listing = false;
                        });
                return;
            }

            if (!created) {
                if (creating) return;
                creating = true;
                // create() is unconditional, so a leftover from a previous run is overwritten rather
                // than making the second run of this probe fail for a reason unrelated to the protocol.
                files.files().write(Resource.of(probe), FIRST_TEXT.getBytes(StandardCharsets.UTF_8), null)
                        .then(etag -> {
                            CrystalGuiCore.LOGGER.info("[remote-probe] 2/4 created {} on the server "
                                    + "(etag {})", PROBE_FILE, etag);
                            created = true;
                        })
                        .onError(failure -> {
                            CrystalGuiCore.LOGGER.error("[remote-probe] create failed: {} — if this is "
                                    + "NOT_PERMITTED then the permission check is working and the "
                                    + "player is not an op", failure.code());
                            creating = false;
                        });
                return;
            }

            if (!readBack) {
                if (reading) return;
                reading = true;
                files.files().readWhole(Resource.of(probe))
                        .then(document -> {
                            String text = new String(document.bytes(), StandardCharsets.UTF_8);
                            readBack = FIRST_TEXT.equals(text);
                            CrystalGuiCore.LOGGER.info("[remote-probe] 3/4 read it back: {} bytes, "
                                    + "matches={}", text.length(), readBack);
                            if (!readBack) reading = false;
                        })
                        .onError(failure -> {
                            CrystalGuiCore.LOGGER.error("[remote-probe] read failed: {}", failure.code());
                            reading = false;
                        });
                return;
            }

            if (!deltaApplied) {
                if (deltaing) return;
                deltaing = true;
                // A CONDITIONAL WRITE OVER A REAL SOCKET, quoting the etag the read handed back -- which
                // is the property this step exists for: the server re-stats before it writes, so a file
                // that moved underneath us is refused rather than clobbered.
                Resource resource = Resource.of(probe);
                files.files().stat(resource)
                        .then(stat -> files.files()
                                .write(resource, EDITED_TEXT.getBytes(StandardCharsets.UTF_8),
                                        stat.etag())
                                .then(etag -> files.files().readWhole(resource)
                                        .then(after -> {
                                            String text = new String(after.bytes(),
                                                    StandardCharsets.UTF_8);
                                            deltaApplied = text.startsWith("EDITED ");
                                            CrystalGuiCore.LOGGER.info("[remote-probe] 4/4 after the "
                                                    + "conditional write the file starts \"{}\"",
                                                    text.substring(0, Math.min(16, text.length())));
                                        })
                                        .onError(failure -> {
                                            CrystalGuiCore.LOGGER.error("[remote-probe] re-read "
                                                    + "failed: {}", failure.code());
                                            deltaing = false;
                                        }))
                                .onError(failure -> {
                                    CrystalGuiCore.LOGGER.error("[remote-probe] conditional write "
                                            + "failed: {}", failure.code());
                                    deltaing = false;
                                }))
                        .onError(failure -> {
                            CrystalGuiCore.LOGGER.error("[remote-probe] stat failed: {}", failure.code());
                            deltaing = false;
                        });
            }
        }

        private void finish(boolean pass, String why) {
            reported = true;
            if (pass) {
                CrystalGuiCore.LOGGER.info("[remote-probe] PASS — listed, created, read back and "
                        + "conditionally re-wrote a file on a SEPARATE server process. Confirm on disk: "
                        + "mc1710/run/server/crystalgui/workspace/{} should exist and start with "
                        + "\"EDITED\", and mc1710/run/client must have no such file.", PROBE_FILE);
            } else {
                CrystalGuiCore.LOGGER.error("[remote-probe] FAIL ({}) — listed={} created={} readBack={} "
                        + "delta={}", why, listed, created, readBack, deltaApplied);
            }
            Minecraft.getMinecraft().shutdown();
        }
    }
}
