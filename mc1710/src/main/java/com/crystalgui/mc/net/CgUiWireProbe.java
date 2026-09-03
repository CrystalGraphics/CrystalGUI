package com.crystalgui.mc.net;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.wire.CgNetworkChannel;
import com.crystalgui.net.wire.FrameMultiplexer;

import com.crystalgraphics.platform.CgPlatform;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;

/**
 * Moves a genuinely large file over a <b>real socket</b>, in both directions, and reports the rate.
 *
 * <pre>
 *   ./gradlew :mc1710:runServer
 *   ./gradlew :mc1710:runClient -PcgJoin=localhost:25565 -PcgWireProbe
 * </pre>
 *
 * <h3>What this measures that a test cannot</h3>
 *
 * <p>{@code WireUnderLatencyTest} models the wire as two multiplexers in one JVM with an exact tick
 * delay. That found the real defect — {@code flush} emitting one frame per message per tick regardless
 * of credit — but it is a model, and three things about it are not true of a server:</p>
 *
 * <ul>
 *   <li><b>The frame ceiling is asymmetric and the model used one number.</b> Client→server is 32,766
 *       bytes on 1.7.10 and server→client is 2,097,050 — a factor of <b>64</b>. So an upload and a
 *       download of the same file are not the same transfer, and nothing has ever measured the
 *       difference.</li>
 *   <li>The tick loop is real, not simulated: a server tick that runs long delays credit as surely as
 *       network latency does, and it varies.</li>
 *   <li>There is a TCP stack under it, with its own buffering and Nagle, and Minecraft's own traffic
 *       competing for the same pipe.</li>
 * </ul>
 *
 * <p><b>It refuses to run in single player</b>, for the reason {@code CgUiRemoteWorkspaceProbe} already
 * records: an integrated server shares a JVM and a filesystem, so a pass there measures a method call.</p>
 */
public final class CgUiWireProbe {

    private static final boolean ENABLED = Boolean.getBoolean("crystalgui.wire.probe");

    /** Big enough to need thousands of frames upward, small enough not to stall a session. */
    private static final int PAYLOAD_BYTES = 4 * 1024 * 1024;

    /**
     * Under {@code WorkspaceBinding.INLINE_LIMIT}, so its read answers in ONE message.
     *
     * <p>The control. Above that cap a read becomes a chunked <b>pull</b> — the client asks for each
     * 256 KB piece and waits — and comparing the two is the only way to tell a slow wire from a serial
     * one. Without it the first run's numbers say "downloads are mysteriously slower" and invite the
     * wrong fix.</p>
     */
    private static final int INLINE_BYTES = 900 * 1024;

    private static final int DEADLINE_TICKS = 20 * 180;
    private static final String PROBE_FILE = "wire-probe.bin";
    private static final String SMALL_FILE = "wire-probe-small.bin";

    private static Workspace files;
    private static int ticks;

    private static volatile boolean writing;
    private static volatile boolean written;
    private static volatile int writeTicks;

    private static volatile boolean reading;
    private static volatile boolean readBack;
    private static volatile int readTicks;
    private static volatile int readBytes;

    private static volatile boolean smallWriting;
    private static volatile boolean smallWritten;
    private static volatile boolean smallReading;
    private static volatile boolean smallRead;
    private static volatile int smallReadTicks;

    private static volatile boolean reported;
    private static int startedAt;

    private CgUiWireProbe() {
    }

    public static void register() {
        if (!ENABLED) return;
        FMLCommonHandler.instance().bus().register(new Handler());
        CrystalGuiCore.LOGGER.info("[wire-probe] armed; waiting to join a DEDICATED server");
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
            if (mc.currentScreen != null) mc.displayGuiScreen(null);

            if (mc.isSingleplayer()) {
                finish(false, "this is the INTEGRATED server; a rate measured there is a method call. "
                        + "Launch with -PcgJoin against runServer.");
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
                reportCeilings();
            }

            step();

            if (ticks > DEADLINE_TICKS) finish(false, "timed out");
        }

        /** One transfer at a time, so a rate belongs to exactly one direction. */
        private void step() {
            CgPath probe = CgPath.of(CgUiWorkspaceHost.PROJECT_ID, PROBE_FILE);

            if (!written) {
                if (writing) return;
                writing = true;
                startedAt = ticks;
                byte[] payload = payload();
                CrystalGuiCore.LOGGER.info("[wire-probe] uploading {} bytes (client -> server)",
                        payload.length);
                // AN UNCONDITIONAL WRITE, which is what a null etag means: this probe measures a rate
                // and a leftover from a previous run must not make the second run fail for a reason
                // that has nothing to do with the wire.
                files.files().write(Resource.of(probe), payload, null)
                        .then(etag -> {
                            writeTicks = ticks - startedAt;
                            written = true;
                        })
                        .onError(failure -> finish(false, "upload failed: " + failure.code()));
                return;
            }

            if (!readBack) {
                if (reading) return;
                reading = true;
                startedAt = ticks;
                CrystalGuiCore.LOGGER.info("[wire-probe] downloading (server -> client)");
                // STREAMED, because a file this size does not fit in one message: the server answers a
                // transfer id and the chunks are pulled through it. The bytes are counted as they land,
                // which is also what makes a stall name the window it stalled in.
                int[] got = {0};
                files.files().readStream(Resource.of(probe))
                        .onPartial(chunk -> got[0] += chunk.length)
                        .onError(failure -> finish(false, "download failed: " + failure.code()))
                        .then(chunks -> {
                            readTicks = ticks - startedAt;
                            readBytes = got[0];
                            readBack = true;
                        });
                return;
            }

            CgPath small = CgPath.of(CgUiWorkspaceHost.PROJECT_ID, SMALL_FILE);

            if (!smallWritten) {
                if (smallWriting) return;
                smallWriting = true;
                byte[] payload = payload(INLINE_BYTES);
                files.files().write(Resource.of(small), payload, null)
                        .then(etag -> smallWritten = true)
                        .onError(failure -> finish(false, "small upload failed: " + failure.code()));
                return;
            }

            if (!smallRead) {
                if (smallReading) return;
                smallReading = true;
                startedAt = ticks;
                CrystalGuiCore.LOGGER.info("[wire-probe] downloading {} bytes INLINE (one message)",
                        INLINE_BYTES);
                files.files().read(Resource.of(small))
                        .then(document -> {
                            smallReadTicks = ticks - startedAt;
                            smallRead = true;
                        })
                        .onError(failure -> finish(false, "small download failed: " + failure.code()));
                return;
            }

            finish(true, "");
        }

        private byte[] payload() {
            return payload(PAYLOAD_BYTES);
        }

        private byte[] payload(int length) {
            byte[] value = new byte[length];
            for (int i = 0; i < value.length; i++) value[i] = (byte) (i * 31 + 7);
            return value;
        }
    }

    /**
     * The two ceilings, said out loud before anything moves.
     *
     * <p>They are the reason the two directions are timed separately, and a run whose numbers surprise
     * somebody should have them in the same log rather than in a comment somewhere else.</p>
     */
    private static void reportCeilings() {
        int ceiling = CgPlatform.get(CgNetworkChannel.SERVICE).maxFrameBytes();
        CrystalGuiCore.LOGGER.info("[wire-probe] connected. This side's frame ceiling is {} bytes; "
                        + "window {} bytes; reassembly cap {} bytes",
                ceiling, FrameMultiplexer.DEFAULT_WINDOW_BYTES, FrameMultiplexer.MAX_REASSEMBLY_BYTES);
    }

    private static void finish(boolean passed, String why) {
        if (reported) return;
        reported = true;

        StringBuilder out = new StringBuilder();
        out.append(System.getProperty("line.separator"));
        out.append("============== CrystalGUI wire probe (real socket) ==============")
                .append(System.getProperty("line.separator"));
        if (!passed) {
            out.append("RESULT: FAIL - ").append(why);
        } else {
            out.append(rate("upload   4 MB  (streamed)", PAYLOAD_BYTES, writeTicks))
                    .append(System.getProperty("line.separator"));
            out.append(rate("download 4 MB  (chunked)", readBytes, readTicks))
                    .append(System.getProperty("line.separator"));
            out.append(rate("download 900 KB (inline)", INLINE_BYTES, smallReadTicks))
                    .append(System.getProperty("line.separator"))
                    .append(System.getProperty("line.separator"));
            out.append("Frame ceilings are 32,766 up and 2,097,050 down -- a factor of 64 in the "
                            + "DOWNLOAD's favour.")
                    .append(System.getProperty("line.separator"));
            out.append("If the chunked download is nonetheless the slowest, the ceiling is not what "
                            + "binds: a read above")
                    .append(System.getProperty("line.separator"));
            out.append("WorkspaceBinding.INLINE_LIMIT is a PULL -- the client asks for each 256 KB "
                            + "piece and waits a round")
                    .append(System.getProperty("line.separator"));
            out.append("trip -- while a write of any size is one streamed message. Compare the two "
                            + "downloads to tell them apart.");
        }
        out.append(System.getProperty("line.separator"))
                .append("================================================================");

        System.out.println(out);
        System.out.flush();
        CrystalGuiCore.LOGGER.info(out.toString());

        // SHUT THE CLIENT DOWN, as CgUiRemoteWorkspaceProbe does. A probe that reports and then leaves
        // the game running is a task that never completes, so nothing downstream of it can be automated
        // -- and the run has to be killed by hand, which is exactly the loop a probe exists to remove.
        Minecraft.getMinecraft().shutdown();
    }

    private static String rate(String label, int bytes, int elapsedTicks) {
        double seconds = Math.max(elapsedTicks, 1) / 20.0;
        double perSecond = bytes / seconds;
        return String.format("%-28s %,10d bytes in %3d ticks (%5.2fs) = %,.0f B/s",
                label, bytes, elapsedTicks, seconds, perSecond);
    }
}
