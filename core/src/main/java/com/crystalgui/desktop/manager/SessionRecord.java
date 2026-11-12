package com.crystalgui.desktop.manager;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.storage.config.CgConfigStorage;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.taskbar.TaskbarPins;
import com.crystalgui.desktop.window.WindowRegistry;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgraphics.serialization.format.CgFiles;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;

/**
 * The session the window manager records and the desktop puts back: which windows were open, where each one was, which
 * were put away, in what order, which applications were running and what each said of itself.
 *
 * <pre>{@code
 * SessionRecord record = new SessionRecord(manager, storage);
 * record.save("main", waiting, pins, apps);              // when the desktop leaves the screen
 * for (SessionRecord.Placement p : record.read("main"))  // when it comes back: applied to windows as they open
 *     pending.put(p.key(), p);
 * for (SessionRecord.App app : record.readApplications("main")) relaunch(app);
 * }</pre>
 *
 * <ul>
 *   <li><b>The intent pair, never the placement.</b> {@code getWantedLeft} is what was asked for; saving the clamped
 *       placement would pull a window further in on every launch.</li>
 *   <li><b>Two orders.</b> The taskbar reads open order, the switcher MRU; neither derives from the other. The pinned
 *       applications' order is the third, the only one the user arranges by hand.</li>
 *   <li><b>A window with no key is not persisted</b>: a record can only name a window by its key.</li>
 *   <li><b>An application's own state</b> is the string it last gave {@code AppContext.saveState}; the record never reads it.</li>
 *   <li><b>An old record is discarded, never migrated</b>: an arrangement invented by a migration is one nobody chose.</li>
 * </ul>
 */
public final class SessionRecord {

    /** Bump on any change that makes an older record mean something different. */
    public static final int VERSION = 2;

    private static final String KEY_VERSION = "version";
    private static final String KEY_WINDOWS = "windows";
    private static final String KEY_MRU = "mru";
    private static final String KEY_PINS = "pins";
    private static final String KEY_KEY = "key";
    private static final String KEY_LEFT = "left";
    private static final String KEY_TOP = "top";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_MAXIMIZED = "maximized";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_APP = "app";
    private static final String KEY_APPS = "apps";
    private static final String KEY_ID = "id";
    private static final String KEY_STATE = "state";

    @Nullable
    private final WindowRegistry registry;
    private final CgConfigStorage storage;

    /** @param manager the window manager whose windows are recorded; null for a record that is only read */
    public SessionRecord(@Nullable WindowManager manager, CgConfigStorage storage) {
        this.registry = manager == null ? null : manager.registry();
        this.storage = storage;
    }

    /**
     * Where a desktop's record lives.
     *
     * <p>Composed the same way {@code WorkbenchSession.fileNameFor} composes its own, and for the same
     * reason: an id may be validated on construction, but building a FILENAME from it is a different
     * question, and anything that could steer a write out of the config directory becomes an underscore.
     * Beside the session record, never in the workspace — a desktop arrangement is private to this
     * machine and must not become part of a project a resource pack could ship.</p>
     */
    public static String fileNameFor(String desktopId) {
        StringBuilder safe = new StringBuilder("desktop.");
        for (char c : desktopId.toCharArray()) {
            safe.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return safe.append(".json").toString().toLowerCase(Locale.ROOT);
    }

    // ── Saving ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Writes the record.
     *
     * @param waiting placements recorded but not yet claimed by a window: kept, or a window not reopened before the
     *                next save would lose its size for good
     * @param pins    the pinned application ids, in the bar's order
     * @param apps    the running applications and their state
     */
    public void save(String desktopId, Collection<Placement> waiting, List<String> pins, List<App> apps) {
        if (!storage.isWritable()) return;
        storage.write(fileNameFor(desktopId), toJson(waiting, pins, apps));
    }

    /** The record as text, without writing it: what a test asserts on. */
    public String toJson(Collection<Placement> waiting, List<String> pins, List<App> apps) {
        List<Placement> windows = new ArrayList<>();
        Set<String> open = new HashSet<>();
        for (WindowFrame frame : registry.windows()) {
            if (!isPersistable(frame)) continue;
            windows.add(Placement.of(frame));
            open.add(frame.key());
        }
        for (Placement placement : waiting) {
            if (open.add(placement.key())) windows.add(placement);
        }
        List<String> mru = new ArrayList<>();
        for (WindowFrame frame : registry.mruOrder()) {
            if (isPersistable(frame)) mru.add(frame.key());
        }
        return CgFiles.JSON.toText(Saved.CODEC, new Saved(VERSION, windows, mru, List.copyOf(pins), List.copyOf(apps)));
    }

    /** A running application at the time of the record, and the state it last gave to {@code saveState}; empty for none. */
    public record App(String id, String state) {

        static final CgCodec<App> CODEC = CgCodecs.record(
                CgCodecs.fieldOr(KEY_ID, CgCodecs.STRING, App::id, ""),
                CgCodecs.optionalField(KEY_STATE, CgCodecs.STRING, App::state, ""),
                App::new);
    }

    /** The whole file. */
    private record Saved(int version, List<Placement> windows, List<String> mru, List<String> pins, List<App> apps) {

        private static final CgCodec<String> MRU_ENTRY = CgCodecs.record(
                CgCodecs.fieldOr(KEY_KEY, CgCodecs.STRING, (String key) -> key, ""), (String key) -> key);

        static final CgCodec<Saved> CODEC = CgCodecs.record(
                CgCodecs.fieldOr(KEY_VERSION, CgCodecs.INT, Saved::version, -1),
                CgCodecs.fieldOr(KEY_WINDOWS, CgCodecs.listOf(Placement.CODEC), Saved::windows, List.of()),
                CgCodecs.fieldOr(KEY_MRU, CgCodecs.listOf(MRU_ENTRY), Saved::mru, List.of()),
                // A record from before pins were saved reads as none pinned, which is what it meant
                CgCodecs.fieldOr(KEY_PINS, CgCodecs.listOf(CgCodecs.STRING), Saved::pins, List.of()),
                CgCodecs.fieldOr(KEY_APPS, CgCodecs.listOf(App.CODEC), Saved::apps, List.of()),
                Saved::new);
    }

    /**
     * Whether this desktop's record is the right home for {@code frame}'s geometry.
     *
     * <h3>A tool window's geometry belongs to the PROJECT, not to the desktop</h3>
     *
     * <p>This record is per <em>host</em> — one file for the whole client, or the whole harness — while a
     * tool window's placement is per <em>project</em>, stored beside its mode in {@code ToolWindowState}
     * because opening a different project must not inherit the last one's arrangement.</p>
     *
     * <p>Both were writing it, which is one fact with two owners and therefore a bug waiting on an
     * ordering: {@code showInFrame} applied the project's bounds and <em>then</em> called
     * {@code openWindow}, so this record was applied second and won. The first windowed tool window to
     * open after launch landed wherever the previous project had left it. Floating ones were unaffected,
     * because {@code attachOwned} never reaches {@code addWindow} at all — so it presented as only some
     * of them being wrong, which reads as a placement bug rather than as a duplicated record.</p>
     */
    public static boolean isPersistable(WindowFrame frame) {
        return frame.key() != null && !frame.key().isEmpty()
                && frame.state() != WindowState.DESTROYED
                && !frame.isToolWindow();
    }

    // ── Restoring ───────────────────────────────────────────────────────────────────────────────

    /**
     * One window's recorded geometry.
     *
     * @param app the application whose main window it was, empty for any other: what a starting window opens at
     *            before the application has named its window's key
     */
    public record Placement(String key, float left, float top, float width, float height,
                            boolean maximized, boolean hidden, String app) {

        static final CgCodec<Placement> CODEC = CgCodecs.record(
                CgCodecs.fieldOr(KEY_KEY, CgCodecs.STRING, Placement::key, ""),
                CgCodecs.fieldOr(KEY_LEFT, CgCodecs.FLOAT, Placement::left, 0f),
                CgCodecs.fieldOr(KEY_TOP, CgCodecs.FLOAT, Placement::top, 0f),
                CgCodecs.fieldOr(KEY_WIDTH, CgCodecs.FLOAT, Placement::width, 0f),
                CgCodecs.fieldOr(KEY_HEIGHT, CgCodecs.FLOAT, Placement::height, 0f),
                CgCodecs.optionalField(KEY_MAXIMIZED, CgCodecs.BOOL, Placement::maximized, false),
                CgCodecs.optionalField(KEY_HIDDEN, CgCodecs.BOOL, Placement::hidden, false),
                CgCodecs.optionalField(KEY_APP, CgCodecs.STRING, Placement::app, ""),
                Placement::new);

        /**
         * {@code frame} as its record.
         *
         * <h4>The geometry is always the UN-maximised one</h4>
         *
         * <p>A maximised window's own box is the work area, which is not worth recording — it is whatever the
         * screen happens to be next launch. What has to survive is the rect it goes BACK to, so that is what
         * the four numbers mean, with the maximised state as a flag beside them. Recording the maximised box
         * as well would be a second set of keys describing something already known, and restoring a maximised
         * window without the rect leaves nothing to restore to.</p>
         *
         * <h4>Position is the INTENT pair; size is the measured box</h4>
         *
         * <p>Deliberately asymmetric. {@code getX()} is the CLAMPED placement, so saving it writes the clamp
         * into the record and every launch pulls the window further in — the frame's own javadoc says as
         * much. Size has no equivalent hazard: nothing stores a wanted size, and a size clamped up to a
         * minimum is idempotent, so asking for it again gives the same answer rather than compounding.</p>
         */
        /** The same geometry, shown rather than minimised: a closed window reopens on screen. */
        public Placement shown() {
            return new Placement(key, left, top, width, height, maximized, false, app);
        }

        public static Placement of(WindowFrame frame) {
            boolean maximized = frame.isMaximized();
            // recordedWidth, never the measured box: a HIDDEN window is detached, so its box is zero -- and
            // a 0x0 rect is refused on the way back in, so the window would simply not come back.
            return new Placement(frame.key(),
                    maximized ? frame.restoreLeft() : frame.getWantedLeft(),
                    maximized ? frame.restoreTop() : frame.getWantedTop(),
                    maximized ? frame.restoreWidth() : frame.recordedWidth(),
                    maximized ? frame.restoreHeight() : frame.recordedHeight(),
                    maximized, frame.state() == WindowState.HIDDEN,
                    frame.isApplicationMain() && frame.application() != null ? frame.application().id() : "");
        }

        /**
         * Whether this describes a window that can actually be put on screen.
         *
         * <p>A 0x0 frame at the origin is a legal encoding and an unusable window, and four floats cannot
         * tell it from "never placed" — the rule W8 already paid for, generalised. A record that fails
         * this is dropped rather than applied, so the window opens at its default instead of at nothing.
         * </p>
         */
        public boolean isUsable() {
            return width > 0f && height > 0f;
        }
    }

    /**
     * What the record says was there, in OPEN order — the reading half, with nothing applied.
     *
     * <p>This class is the RECORD and {@code Desktop} is the policy: reading, applying and writing all
     * belong to the compositor, which is what lets a host supply a storage and an id and nothing else.
     * Separating the two is what makes the record assertable — a test can ask what was written without
     * standing a desktop up to receive it, and the version and zero-rect gates below both live here for
     * the same reason.</p>
     *
     * @return the usable placements, or empty when there is no record this version understands
     */
    public List<Placement> read(String desktopId) {
        Saved saved = saved(desktopId);
        if (saved == null) return List.of();
        List<Placement> out = new ArrayList<>();
        for (Placement placement : saved.windows()) {
            if (!placement.key().isEmpty() && placement.isUsable()) out.add(placement);
        }
        return out;
    }

    /** The keys in most-recently-activated order, first is most recent. Empty when there is no record. */
    public List<String> readMruOrder(String desktopId) {
        Saved saved = saved(desktopId);
        return saved == null ? List.of() : saved.mru();
    }

    /** The applications that were running, in the order they started. Empty when there is no record. */
    public List<App> readApplications(String desktopId) {
        Saved saved = saved(desktopId);
        if (saved == null) return List.of();
        List<App> out = new ArrayList<>();
        for (App app : saved.apps()) if (!app.id().isEmpty()) out.add(app);
        return out;
    }

    /** The pinned application ids, in the bar's order. Empty when there is no record. @see TaskbarPins */
    public List<String> readPins(String desktopId) {
        Saved saved = saved(desktopId);
        return saved == null ? List.of() : saved.pins();
    }

    /** The record's codecs made and the JSON reader loaded, by a read of an empty record: {@code CgUiStart}'s worker. */
    public static void ahead() {
        CgFiles.JSON.fromText(Saved.CODEC, "{}");
    }

    /** The file, or null when there is none, it will not parse, or it is another version's. */
    @Nullable
    private Saved saved(String desktopId) {
        String raw = storage.read(fileNameFor(desktopId));
        if (raw == null || raw.isEmpty()) return null;
        try {
            Saved saved = CgFiles.JSON.fromText(Saved.CODEC, raw);
            return saved.version() == VERSION ? saved : null;
        } catch (RuntimeException malformed) {
            // A record that will not parse is a record that describes nothing. Discarded rather than
            // reported: there is no user action, and the desktop simply opens at its defaults.
            return null;
        }
    }

}
