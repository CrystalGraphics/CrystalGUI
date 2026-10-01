package com.crystalgui.desktop.host;

import java.nio.file.Path;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.net.protocol.ProtocolConnection;

/**
 * <b>The four things a platform knows and the engine cannot</b> - implement it to run CrystalGUI on a
 * new host.
 *
 * <p>Where private files go, how big a pixel is, whether there is a server to talk to, and what language
 * the player reads. Answer those and {@link DesktopHost} gives you a compositor, a workspace that follows
 * the connection, and somewhere for a server's windows to land - none of which you write.</p>
 *
 * <pre>{@code
 * DesktopHost host = DesktopHost.create(new HostServices() {
 *     public Path installationDirectory() { return gameDir; }
 *     public float uiScale()        { return currentGuiScale(); }
 *     public String desktopId()     { return "client"; }
 *     public ProtocolConnection<Object> connection() { return liveConnectionOrNull(); }
 *     public Locale locale()        { return HostServices.gameLocale(options.languageCode); }
 * });
 * }</pre>
 *
 * <p>The measure of this interface is what it does <b>not</b> ask: not what the window is called, not
 * which application to open, not where a session goes, not when to ask for the project list. Every one
 * of those is the same answer on every host, so the engine decides them.</p>
 *
 * <h3>The connection is re-asked, never pushed</h3>
 *
 * <p>{@link #connection()} is read once per frame rather than announced, so it may return a different
 * object - or null - at any time and the engine copes. That is deliberate: a reconnect is a new
 * connection carrying the same workspace, and re-asking is what makes the rebind reachable. Answering
 * null simply means "no server right now", which is a supported state rather than an error.</p>
 */
public interface HostServices {

    /**
     * What a host answers {@link #uiScale()} with when it has nothing better to say.
     *
     * <p><b>Deliberately not the game's GUI Scale.</b> That setting sizes 16px widgets and an 8px
     * bitmap font; a desktop carrying an editor, a taskbar and tool windows has far more on it than an
     * inventory, so scaling the two by one number makes whichever the player did not choose for
     * unusable. The shipped sheets are authored and measured at this.</p>
     */
    float DEFAULT_UI_SCALE = 2f;

    /**
     * This installation's directory — {@code .minecraft}, a server directory, wherever the host lives.
     *
     * <p><b>Not the {@code crystalgui/} directory itself.</b> The engine owns every segment below this,
     * {@code crystalgui/} included: {@code workspace-config/} for what must survive, {@code cache/} for
     * what can be rebuilt, {@code projects/} for a workspace's own files. A host answers <em>where it
     * is</em>, never <em>what goes where</em>, so two hosts cannot drift into two layouts.</p>
     *
     * <pre>{@code
     * public Path installationDirectory() { return gameDir; }   // -> gameDir/crystalgui/...
     * }</pre>
     *
     * <p>Private, and never inside a workspace: a session record must not become part of a project
     * somebody ships.</p>
     */
    Path installationDirectory();

    /**
     * The directory of the world this process is <b>itself serving</b>, or null when it serves none.
     *
     * <p>Single-player is the case that answers non-null: the client is also the server, so a
     * workspace's session, backups and history can live beside the save and be deleted with it. A
     * client connected to someone else's server answers null — it cannot write there — and that
     * workspace's state goes in this installation's tree instead.</p>
     *
     * <pre>{@code
     * public Path localWorldDirectory() {
     *     IntegratedServer server = minecraft.getSingleplayerServer();
     *     return server == null ? null : server.getWorldPath(LevelResource.ROOT);
     * }
     * }</pre>
     *
     * <p>Re-asked rather than announced, like {@link #connection()}: worlds are joined and left.</p>
     */
    @Nullable
    Path localWorldDirectory();

    /** How many device pixels one logical pixel is. Applied once, to the box tree's root transform. */
    float uiScale();

    /**
     * The surface, in <b>device</b> pixels — the raw framebuffer, never a scaled or logical size.
     *
     * <pre>{@code
     * public int surfaceWidth()  { return minecraft.getWindow().getWidth(); }
     * public int surfaceHeight() { return minecraft.getWindow().getHeight(); }
     * }</pre>
     *
     * <p>Divided by {@link #uiScale()} wherever a logical size is wanted, which is the one conversion
     * and lives above this seam. Answer {@code 0} when there is no surface yet rather than guessing:
     * a session sizing its first window reads these, and zero is refused where a made-up number is
     * silently honoured.</p>
     *
     * <p>Re-asked rather than cached, so a resized game window reaches the next thing that measures.</p>
     */
    int surfaceWidth();

    /** @see #surfaceWidth() */
    int surfaceHeight();

    /**
     * Which desktop this is, for the arrangement record.
     *
     * <p>Two hosts in one installation — a game client and a dedicated tool — keep separate window
     * layouts, and neither should have to know the other exists.</p>
     */
    String desktopId();

    /** The connection to a server, or null when there is none. Re-asked per frame; see the class note. */
    @Nullable
    ProtocolConnection<Object> connection();

    /**
     * The language the player reads — Minecraft's language setting. It settles the one thing about text
     * no font stack does: whether a Han character is drawn Japanese, Simplified, Traditional or Korean.
     * Re-asked per frame, so changing the game's language reaches the next one.
     *
     * <pre>{@code
     * public Locale locale() { return HostServices.gameLocale(minecraft.options.languageCode); }  // "ja_jp"
     * }</pre>
     */
    Locale locale();

    /**
     * Gives the game a key the desktop dispatched and did not take, as though the desktop had never seen it. Only a
     * desktop running on its own thread hands keys back, a frame late: it takes every key while it holds the keyboard,
     * since it cannot answer in time, and returns the ones it left. Render thread.
     *
     * <pre>{@code
     * public void reinjectKey(CgSystemInput.Keyboard.Event key) {
     *     Screen screen = minecraft.screen;
     *     if (screen instanceof CgUiScreen own) own.keyLeftByDesktop(key);   // Escape closes our own screen
     *     else if (screen != null) deliver(screen, key);                      // chat, an inventory: its own handling
     * }
     * }</pre>
     *
     * <ul>
     *   <li>A key carries either a key code ({@code character} 0) or a character ({@code key} {@code KEY_NONE}), as
     *       {@code CgSystemInput.Keyboard.Event} does; give each to the matching handler.</li>
     *   <li>Releases come back too, so a screen that tracks held keys sees both halves.</li>
     * </ul>
     */
    void reinjectKey(CgSystemInput.Keyboard.Event key);

    /**
     * A game's language code as a {@link Locale}: Minecraft's {@code "ja_jp"} is {@code ja-JP}. A code
     * that names no language, like Minecraft's joke {@code "enws"}, answers the JVM's own.
     */
    static Locale gameLocale(@Nullable String code) {
        if (code == null || code.isEmpty()) {
            return Locale.getDefault();
        }
        Locale locale = Locale.forLanguageTag(code.replace('_', '-'));
        return locale.getLanguage().isEmpty() ? Locale.getDefault() : locale;
    }
}
