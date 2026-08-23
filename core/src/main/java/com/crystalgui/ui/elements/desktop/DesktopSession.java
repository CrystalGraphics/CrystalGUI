package com.crystalgui.ui.elements.desktop;

import com.crystalgui.fs.ConfigStorage;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.StateMap;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The desktop as it was left — CrystalOS <b>W12</b>, geometry persistence and session restore.
 *
 * <p>W8 persisted exactly one thing, a floating tool window's rect, because a float's frame is destroyed
 * on every hide and nothing else could say where it had been. This is the general case: which windows
 * were open, where each one was, which were put away, and in what order.</p>
 *
 * <h3>The INTENT pair, never the placement</h3>
 *
 * <p>{@link WindowFrame#getWantedLeft} is what was asked for; {@code getX()} is where it ended up after
 * clamping. Saving the placement writes the clamp into the record permanently — restore on a smaller
 * desktop, save again, and every launch pulls the window a little further in with nothing to attribute
 * the drift to. The frame's own javadoc says so, and this is the caller it was written for.</p>
 *
 * <h3>Two orders, because neither is derivable from the other</h3>
 *
 * <p>The taskbar reads open order and the switcher reads MRU, and a minimised window has left the
 * stacking order while keeping its place in the sequence. A record that kept only one would either move
 * every entry on the taskbar or offer the wrong window first after a restart.</p>
 *
 * <h3>A window with no key is not persisted, and that is not a limitation</h3>
 *
 * <p>A key is what a record can NAME a window by. {@link Desktop#persistTo} applies a placement to a
 * window as it opens, by matching {@link WindowFrame#key()} — so a frame nobody gave a key to can never
 * be matched to anything, and recording its geometry would produce a file full of rectangles that are
 * read on every launch and applied to nothing.</p>
 *
 * <h3>An old record is discarded, never migrated</h3>
 *
 * <p>{@code WorkbenchSession}'s policy and its reasoning: a layout record describes an arrangement, and
 * an arrangement invented by a migration is one nobody chose. Losing it costs a re-arrange; getting it
 * wrong is a desktop that looks broken.</p>
 */
public final class DesktopSession {

    /** Bump on any change that makes an older record mean something different. @see DesktopSession */
    public static final int VERSION = 1;

    private static final String KEY_VERSION = "version";
    private static final String KEY_WINDOWS = "windows";
    private static final String KEY_MRU = "mru";
    private static final String KEY_KEY = "key";
    private static final String KEY_LEFT = "left";
    private static final String KEY_TOP = "top";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_MAXIMIZED = "maximized";
    private static final String KEY_HIDDEN = "hidden";

    private final Desktop desktop;
    private final ConfigStorage storage;

    public DesktopSession(Desktop desktop, ConfigStorage storage) {
        this.desktop = desktop;
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

    public void save(String desktopId) {
        if (!storage.isWritable()) return;
        storage.write(fileNameFor(desktopId), toJson());
    }

    /** The record as text, without writing it — what a test asserts on. */
    public String toJson() {
        StateMap<JsonElement> out = new StateMap<>(JsonOps.INSTANCE);
        out.putInt(KEY_VERSION, VERSION);

        List<WindowFrame> keyed = new ArrayList<>();
        for (WindowFrame frame : desktop.registry().windows()) {
            if (isPersistable(frame)) keyed.add(frame);
        }
        out.putList(KEY_WINDOWS, keyed, DesktopSession::writeWindow);

        List<String> mru = new ArrayList<>();
        for (WindowFrame frame : desktop.registry().mruOrder()) {
            if (isPersistable(frame)) mru.add(frame.key());
        }
        out.putList(KEY_MRU, mru, (entry, key) -> entry.putString(KEY_KEY, key));

        return new GsonBuilder().setPrettyPrinting().create().toJson(out.encode()) + "\n";
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
    private static boolean isPersistable(WindowFrame frame) {
        return frame.key() != null && !frame.key().isEmpty()
                && frame.state() != WindowState.DESTROYED
                && !frame.isToolWindow();
    }

    /**
     * One window's record.
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
    private static void writeWindow(StateMap<JsonElement> entry, WindowFrame frame) {
        entry.putString(KEY_KEY, frame.key());
        boolean maximized = frame.isMaximized();
        entry.putFloat(KEY_LEFT, maximized ? frame.restoreLeft() : frame.getWantedLeft());
        entry.putFloat(KEY_TOP, maximized ? frame.restoreTop() : frame.getWantedTop());
        // recordedWidth, never the measured box: a HIDDEN window is detached, so its box is zero -- and
        // a 0x0 rect is refused on the way back in, so the window would simply not come back.
        entry.putFloat(KEY_WIDTH, maximized ? frame.restoreWidth() : frame.recordedWidth());
        entry.putFloat(KEY_HEIGHT, maximized ? frame.restoreHeight() : frame.recordedHeight());
        entry.putBoolIfNot(KEY_MAXIMIZED, maximized, false);
        entry.putBoolIfNot(KEY_HIDDEN, frame.state() == WindowState.HIDDEN, false);
    }

    // ── Restoring ───────────────────────────────────────────────────────────────────────────────

    /** One window's recorded geometry, as read back. */
    public record Placement(String key, float left, float top, float width, float height,
                            boolean maximized, boolean hidden) {

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
     * <p>This class is the RECORD and {@link Desktop} is the policy: reading, applying and writing all
     * belong to the compositor, which is what lets a host supply a storage and an id and nothing else.
     * Separating the two is what makes the record assertable — a test can ask what was written without
     * standing a desktop up to receive it, and the version and zero-rect gates below both live here for
     * the same reason.</p>
     *
     * @return the usable placements, or empty when there is no record this version understands
     */
    public List<Placement> read(String desktopId) {
        String raw = storage.read(fileNameFor(desktopId));
        if (raw == null || raw.isEmpty()) return List.of();
        StateMap<JsonElement> in;
        try {
            in = new StateMap<>(JsonOps.INSTANCE, new JsonParser().parse(raw));
        } catch (RuntimeException malformed) {
            // A record that will not parse is a record that describes nothing. Discarded rather than
            // reported: there is no user action, and the desktop simply opens at its defaults.
            return List.of();
        }
        if (in.getInt(KEY_VERSION, -1) != VERSION) return List.of();

        List<Placement> out = new ArrayList<>();
        for (Placement placement : in.getList(KEY_WINDOWS, DesktopSession::readWindow)) {
            if (placement != null && placement.isUsable()) out.add(placement);
        }
        return out;
    }

    /** The keys in most-recently-activated order, first is most recent. Empty when there is no record. */
    public List<String> readMruOrder(String desktopId) {
        String raw = storage.read(fileNameFor(desktopId));
        if (raw == null || raw.isEmpty()) return List.of();
        try {
            StateMap<JsonElement> in =
                    new StateMap<>(JsonOps.INSTANCE, new JsonParser().parse(raw));
            if (in.getInt(KEY_VERSION, -1) != VERSION) return List.of();
            return in.getList(KEY_MRU, entry -> entry.getString(KEY_KEY, ""));
        } catch (RuntimeException malformed) {
            return List.of();
        }
    }

    @Nullable
    private static Placement readWindow(StateMap<JsonElement> entry) {
        String key = entry.getString(KEY_KEY, "");
        if (key.isEmpty()) return null;
        return new Placement(key,
                entry.getFloat(KEY_LEFT, 0f), entry.getFloat(KEY_TOP, 0f),
                entry.getFloat(KEY_WIDTH, 0f), entry.getFloat(KEY_HEIGHT, 0f),
                entry.getBool(KEY_MAXIMIZED, false), entry.getBool(KEY_HIDDEN, false));
    }

}
