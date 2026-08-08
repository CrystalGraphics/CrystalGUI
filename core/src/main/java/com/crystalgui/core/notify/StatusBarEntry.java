package com.crystalgui.core.notify;

import javax.annotation.Nullable;

/**
 * What one status bar entry says — VS Code's {@code IStatusbarEntry}.
 *
 * <p>Ported from {@code vs/workbench/services/statusbar/browser/statusbar.ts}. Dropped from that
 * interface: {@code color}/{@code backgroundColor} (a colour written from Java is a palette the cascade
 * cannot reach — {@link #kind()} carries a class instead, the same split {@code graph.css} makes for port
 * types), {@code showProgress}, {@code compact}, and the ARIA fields, which need a screen-reader surface
 * this engine does not have yet.</p>
 *
 * <h3>{@code name} and {@code text} are two different things</h3>
 *
 * <p>Straight from the reference, and the reason is not obvious until you need it: {@code text} is what
 * the bar shows and changes constantly ({@code "51:39"}), while {@code name} is what the entry <em>is</em>
 * ({@code "Cursor position"}) and never changes. VS Code needs the second for the context menu that lets
 * you hide individual entries — you cannot offer "hide 51:39" as a checkbox. Carried here so that menu is
 * a view away rather than a model change away, and because a tooltip-less entry can fall back to it.</p>
 *
 * <h3>{@code command} is an id, not a callback</h3>
 *
 * <p>Both references make almost every entry clickable: VS Code's encoding entry runs
 * {@code workbench.action.editor.changeEncoding}, IntelliJ's line-separator widget opens a popup. Naming a
 * command rather than holding a {@code Runnable} is what keeps that reachable from a keymap and a palette
 * as well as from the bar — the entry states <em>what</em> to do, and {@code CommandRegistry} owns how.</p>
 *
 * @param name    what the entry is, for a hide menu and as the tooltip fallback. Never changes.
 * @param text    what the bar shows right now
 * @param tooltip the longer form shown on hover, or null to fall back to {@link #name()}
 * @param command the id of a command to run when the entry is clicked, or null for an inert entry
 * @param kind    how it should read — see {@link Kind}
 */
public record StatusBarEntry(String name, String text, @Nullable String tooltip,
                             @Nullable String command, Kind kind) {

    /**
     * How an entry should read — VS Code's {@code StatusbarEntryKind}.
     *
     * <h3>Ambient does not mean unimportant</h3>
     *
     * <p>"compiled 12n/9e" and "1 error(s)" are both ambient — both describe how things are right now, and
     * both are replaced by the next compile — so both belong here rather than in {@link Notifications}. But
     * they are not the same news, and rendered identically the failure reads as a statistic. The kind
     * travels so the view can say so, and the view says it with a <b>class</b> rather than a colour, which
     * is what lets one palette serve this, the Problems rows and the notification cards.</p>
     */
    public enum Kind {
        STANDARD,
        WARNING,
        ERROR
    }

    public StatusBarEntry {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("a status entry needs a name");
        if (text == null) text = "";
        if (kind == null) kind = Kind.STANDARD;
    }

    /** An entry with no tooltip, no command and no severity — the common case. */
    public static StatusBarEntry of(String name, String text) {
        return new StatusBarEntry(name, text, null, null, Kind.STANDARD);
    }

    public StatusBarEntry withText(String value) {
        return new StatusBarEntry(name, value, tooltip, command, kind);
    }

    public StatusBarEntry withTooltip(@Nullable String value) {
        return new StatusBarEntry(name, text, value, command, kind);
    }

    /** @see StatusBarEntry */
    public StatusBarEntry withCommand(@Nullable String commandId) {
        return new StatusBarEntry(name, text, tooltip, commandId, kind);
    }

    public StatusBarEntry withKind(Kind value) {
        return new StatusBarEntry(name, text, tooltip, command, value);
    }

    /** What to show on hover: the tooltip when there is one, otherwise what the entry is. */
    public String hoverText() {
        return tooltip != null && !tooltip.isEmpty() ? tooltip : name;
    }
}
