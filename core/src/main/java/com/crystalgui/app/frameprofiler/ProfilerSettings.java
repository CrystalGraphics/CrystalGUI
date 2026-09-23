package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsCodec;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsModel;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.core.trace.UiTrace;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * What the frame profiler records and how it shows it, kept in the profiler's own {@code settings.json}.
 *
 * <pre>{@code
 * ProfilerSettings.useStorage(context.storage());   // once, from the application's autostart
 * int frames = ProfilerSettings.get(ProfilerSettings.FRAMES);
 * ProfilerSettings.set(ProfilerSettings.FRAMES, 10_000);   // saved, and the ring resized at once
 * }</pre>
 *
 * <h3>One per process, because the ring is</h3>
 *
 * <p>{@link CgTrace} holds a single ring for the whole process, so two sets of recording settings could
 * only fight over it. The store is loaded from the first desktop that offers storage — the application's
 * {@link FrameProfiler#KIND autostart} — and every change is written back and applied immediately.</p>
 *
 * <h3>What applies when</h3>
 *
 * <ul>
 *   <li><b>Frames kept from the start, newest frames kept and zones per frame</b> resize the ring, which <b>clears</b> what it
 *       holds. They apply the moment they change, and at startup.</li>
 *   <li><b>Stop after a slow frame</b> arms or disarms at once and clears nothing.</li>
 *   <li><b>Record from launch</b> is read at startup only; turning it on starts nothing now.</li>
 *   <li><b>Channels</b> is what Record switches on. The channel menu changes what is recording now and
 *       leaves this alone.</li>
 * </ul>
 */
public final class ProfilerSettings {

    public static final String FILE = "settings.json";

    // ── Recording ────────────────────────────────────────────────────────────────────────────

    public static final Setting<Boolean> RECORD_AT_LAUNCH = Setting.bool(
            "profiler.recording.atLaunch", "Record from launch", false)
            .description("Start recording as the desktop starts, before any window opens, so the first "
                    + "frames of the program are captured. Takes effect on the next launch.");

    // NOT crystalgui.blame by default: it walks a stack on every invalidation, which slows the very frames
    // being measured. NOR crystalgraphics.text, .gl or .misc: a zone per text draw, per draw call and per
    // buffer map, 3,000 to 8,000 a frame on the desktop scene, where the ring budgets 256. They overflow it
    // and bury the frame's own phases. All are asked for by name, from the channel menu.
    public static final Setting<String> CHANNELS = Setting.string(
            "profiler.recording.channels", "Channels",
            String.join(", ", UiTrace.FRAME.name(), UiTrace.FLOW.name(), CgGpuTrace.GPU.name(),
                    "crystalgraphics.async"))
            .description("What Record and Record from launch switch on, comma-separated. A name takes "
                    + "every channel beneath it, including ones that load later.");

    public static final Setting<Integer> FIRST_FRAMES = Setting.integer(
            "profiler.recording.firstFrames", "Frames kept from the start", 600)
            .description("The first frames of a recording, kept for good however long it runs, so the start "
                    + "is always there to go back to. 0 keeps none. Clears the recording.");

    public static final Setting<Integer> FRAMES = Setting.integer(
            "profiler.recording.frames", "Newest frames kept", 600)
            .description("The newest frames after those, the oldest overwritten. 0 stops recording once the "
                    + "first frames are full. Up to 100,000 each. Clears the recording.");

    public static final Setting<Integer> ZONES_PER_FRAME = Setting.integer(
            "profiler.recording.zonesPerFrame", "Zones per frame", 256)
            .description("The most zones a thread may keep per frame kept. A thread's buffer grows as it "
                    + "records and never past this, at 24 bytes a zone. Clears the recording.");

    // ── Hitches ──────────────────────────────────────────────────────────────────────────────

    public static final Setting<Integer> STOP_AFTER_MS = Setting.integer(
            "profiler.hitch.thresholdMs", "Stop after a frame slower than (ms)", 0)
            .description("0 is off. When a frame takes longer than this, recording stops a few frames "
                    + "later, so the hitch is still there when you look.");

    public static final Setting<Integer> FRAMES_AFTER = Setting.integer(
            "profiler.hitch.framesAfter", "Frames kept after it", 120)
            .description("How many frames to record after the slow one before stopping.");

    // ── View ─────────────────────────────────────────────────────────────────────────────────

    public static final Setting<String> TARGET = Setting.select(
            "profiler.view.target", "Frame budget", List.of("30 fps", "60 fps", "120 fps", "144 fps", "240 fps"),
            "60 fps")
            .description("Where the strip draws its budget lines, and what counts as a slow frame.");

    public static final Setting<Integer> REFRESH_MS = Setting.integer(
            "profiler.view.refreshMs", "Live refresh (ms)", 250)
            .description("How often a live window re-reads the ring. Faster costs frame time of its own.");

    /** In the order the page shows them, under the headings in {@link #SECTIONS}. */
    public static List<Setting<?>> all() {
        return List.of(RECORD_AT_LAUNCH, CHANNELS, FIRST_FRAMES, FRAMES, ZONES_PER_FRAME,
                STOP_AFTER_MS, FRAMES_AFTER, TARGET, REFRESH_MS);
    }

    /** A heading and the settings under it. */
    public record Section(String title, List<Setting<?>> settings) {
    }

    public static final List<Section> SECTIONS = List.of(
            new Section("Recording", List.of(RECORD_AT_LAUNCH, CHANNELS, FIRST_FRAMES, FRAMES, ZONES_PER_FRAME)),
            new Section("Hitches", List.of(STOP_AFTER_MS, FRAMES_AFTER)),
            new Section("View", List.of(TARGET, REFRESH_MS)));

    public static final int MAX_FRAMES = 100_000;

    private static final Settings SETTINGS = new Settings();
    @Nullable
    private static ConfigStorage storage;
    private static boolean listening;

    private ProfilerSettings() {
    }

    /** The live store — what the settings page binds its rows to. */
    public static Settings store() {
        listen();
        return SETTINGS;
    }

    public static <T> T get(Setting<T> setting) {
        return SETTINGS.get(setting);
    }

    public static <T> void set(Setting<T> setting, T value) {
        listen();
        SETTINGS.set(SettingsLayer.USER, setting, value);
    }

    /**
     * Loads {@code settings.json} from {@code appStorage} and applies it — once per process; a second
     * desktop's storage is ignored, since the ring it would configure is the same one.
     */
    public static synchronized void useStorage(ConfigStorage appStorage) {
        if (storage != null || appStorage == null) return;
        storage = appStorage;
        SettingsModel loaded = SettingsCodec.fromJson(appStorage.read(FILE));
        SETTINGS.replaceLayer(SettingsLayer.USER, loaded.asMap());
        listen();
        applyCapacity();
        applyHitch();
    }

    /** The profiler's autostart: load, apply, and start recording if asked to. */
    public static void atLaunch(ConfigStorage appStorage) {
        useStorage(appStorage);
        if (get(RECORD_AT_LAUNCH)) {
            for (String prefix : channels()) CgTrace.enable(prefix);
            CrystalGuiCore.LOGGER.info("[cgui] frame profiler recording from launch: {}", channels());
        }
    }

    private static synchronized void listen() {
        if (listening) return;
        listening = true;
        SETTINGS.onChanged.connect(change -> {
            if (change.layer() != SettingsLayer.USER) return;
            save();
            if (change.affects(FRAMES) || change.affects(ZONES_PER_FRAME) || change.affects(FIRST_FRAMES)) {
                applyCapacity();
            }
            if (change.affects(STOP_AFTER_MS) || change.affects(FRAMES_AFTER)) applyHitch();
        });
    }

    private static void save() {
        ConfigStorage target = storage;
        if (target == null) return;
        try {
            target.write(FILE, SettingsCodec.toJson(SETTINGS.layer(SettingsLayer.USER)));
        } catch (RuntimeException failed) {
            CrystalGuiCore.LOGGER.warn("[cgui] the frame profiler's settings could not be saved: {}",
                    failed.getMessage());
        }
    }

    // ── Reading, clamped ─────────────────────────────────────────────────────────────────────

    public static int frames() {
        return Math.max(0, Math.min(MAX_FRAMES, get(FRAMES)));
    }

    public static int firstFrames() {
        return Math.max(0, Math.min(MAX_FRAMES, get(FIRST_FRAMES)));
    }

    public static int zonesPerFrame() {
        return Math.max(16, Math.min(4096, get(ZONES_PER_FRAME)));
    }

    /** The budget in nanoseconds, from the target rate. */
    public static long budgetNanos() {
        String target = get(TARGET);
        int fps = 60;
        try {
            fps = Integer.parseInt(target.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            // an edited file naming no rate keeps 60
        }
        return 1_000_000_000L / Math.max(1, fps);
    }

    public static float refreshSeconds() {
        return Math.max(50, Math.min(5_000, get(REFRESH_MS))) / 1000f;
    }

    /** The channel prefixes Record switches on. */
    public static List<String> channels() {
        List<String> out = new ArrayList<>();
        for (String part : get(CHANNELS).split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    // ── Applying ─────────────────────────────────────────────────────────────────────────────

    /** Resizes the ring when it differs from what is asked. Clearing is the price of a resize. */
    static synchronized void applyCapacity() {
        String wanted = firstFrames() + ":" + frames() + ":" + zonesPerFrame();
        if (wanted.equals(applied)) return;
        applied = wanted;
        CgTrace.configure(firstFrames(), frames(), zonesPerFrame());
    }

    /** What {@link #applyCapacity} last configured, so an unrelated change does not clear the ring. */
    @Nullable
    private static String applied;

    static void applyHitch() {
        int ms = Math.max(0, get(STOP_AFTER_MS));
        CgTrace.stopAfterHitch(ms * 1_000_000L, Math.max(0, get(FRAMES_AFTER)));
    }
}
