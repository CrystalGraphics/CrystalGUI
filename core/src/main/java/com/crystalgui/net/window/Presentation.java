package com.crystalgui.net.window;

import java.util.Locale;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * <b>Where a server would like its window to appear</b> — a hint carried on the open.
 *
 * <pre>{@code
 * ServerWindows.of(connection).open(MachinePanel.TYPE, machine, Presentation.EDITOR_TAB);
 * ServerWindows.of(connection).open(LogPanel.TYPE, log, Presentation.toolWindow("panel"));
 * }</pre>
 *
 * <p>A <b>hint</b>, and the word is doing work: the server says what it would like and the client says
 * what it has. A host with no workbench opens a window whatever this says, because the alternative is a
 * panel a player asked for that never appears — and "it opened somewhere else" is a far better failure
 * than "it did not open".</p>
 *
 * <h3>Why it travels rather than being decided on the client</h3>
 *
 * <p>Only the server knows what the panel is <em>for</em>. A machine's controls are a document you work
 * in — an editor tab. A live log is something you glance at — a tool window. The client cannot tell
 * those apart from the tree, and a mod that wanted the distinction would otherwise have to ship a
 * client-side table mapping type ids to placements, which is the registration this stack exists to
 * remove.</p>
 *
 * <h3>One string on the wire</h3>
 *
 * <p>{@code "window"}, {@code "tab"} or {@code "tool:<region>"}. String-shaped so it survives into a
 * saved layout as an ordinary {@code DockPanelRef} state entry, and so a client that predates a region
 * name it does not know can fall back rather than fail to parse.</p>
 */
public final class Presentation {

    /** What kind of place this is. */
    public enum Kind {
        /** A window on the desktop. What every server window was before this existed. */
        WINDOW,
        /** A tab in the editor area, beside the files. */
        EDITOR_TAB,
        /** A tool window on a rail — the region names which one. */
        TOOL_WINDOW
    }

    /** A desktop window. The default, and what a host with nowhere else to put one falls back to. */
    public static final Presentation WINDOW = new Presentation(Kind.WINDOW, null);

    /** A tab in the editor area. */
    public static final Presentation EDITOR_TAB = new Presentation(Kind.EDITOR_TAB, null);

    private final Kind kind;

    @Nullable
    private final String region;

    private Presentation(Kind kind, @Nullable String region) {
        this.kind = kind;
        this.region = region;
    }

    /**
     * A tool window in the named region — {@code "sidebar"}, {@code "panel"} or {@code "auxiliary"}.
     *
     * <p>A plain string rather than the workbench's own enum, because this type is on the wire and the
     * wire may not name the workbench: a dedicated server holds no dock. A region the client does not
     * recognise lands in its default one rather than failing.</p>
     */
    public static Presentation toolWindow(String region) {
        return new Presentation(Kind.TOOL_WINDOW, Objects.requireNonNull(region, "region"));
    }

    public Kind kind() {
        return kind;
    }

    /** Which rail, for a tool window. Null for every other kind. */
    @Nullable
    public String region() {
        return region;
    }

    /** The wire form. @see #parse */
    public String encode() {
        switch (kind) {
            case EDITOR_TAB:
                return "tab";
            case TOOL_WINDOW:
                return "tool:" + region;
            default:
                return "window";
        }
    }

    /**
     * Reads a wire form back, answering {@link #WINDOW} for anything unrecognised.
     *
     * <p>Never throws. This runs on a client decoding a window a newer server sent, and refusing to
     * parse a placement is refusing the window — which is exactly the failure the hint's whole design
     * avoids.</p>
     */
    public static Presentation parse(@Nullable String encoded) {
        if (encoded == null) return WINDOW;
        String text = encoded.trim().toLowerCase(Locale.ROOT);
        if (text.equals("tab")) return EDITOR_TAB;
        if (text.startsWith("tool:")) {
            String named = text.substring("tool:".length());
            return named.isEmpty() ? WINDOW : toolWindow(named);
        }
        return WINDOW;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Presentation)) return false;
        Presentation that = (Presentation) other;
        return kind == that.kind && Objects.equals(region, that.region);
    }

    @Override
    public int hashCode() {
        return 31 * kind.hashCode() + Objects.hashCode(region);
    }

    @Override
    public String toString() {
        return encode();
    }
}
