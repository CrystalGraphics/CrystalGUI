package com.crystalgui.core.notify;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Something that happened and is worth telling the user about — VS Code's {@code INotification},
 * IntelliJ's {@code Notification}.
 *
 * <h3>Why this is not a String</h3>
 *
 * <p>It was one. {@code Workbench.onStatus} and {@code ShaderGraphEditor.onStatusChanged} were both
 * {@code Signal.Value<String>}, so "created folder" and "3 error(s): undefined variable" arrived
 * identically and were rendered identically. A severity is not decoration: it decides whether the
 * message is dismissed on the next one or held, whether it is coloured, and whether it belongs in a
 * history at all.</p>
 *
 * <h3>Actions, because the useful ones are not read-only</h3>
 *
 * <p>"copy failed" wants <em>Retry</em>; "generated file" wants <em>Open the graph</em>. Both references
 * carry actions on the notification for the same reason — without them the message names a problem and
 * leaves the user to find the fix, which is what makes a status line feel like logging rather than UI.</p>
 *
 * <p>Mutable-with-chaining rather than a record, matching {@link com.crystalgui.core.command.Command} —
 * the same builder shape, so the two read alike at a call site.</p>
 */
public final class Notification {

    public enum Severity {
        /** Ordinary progress. Transient — the next one replaces it. */
        INFO,
        /** Something is off but the operation continued. */
        WARNING,
        /** The operation failed. */
        ERROR
    }

    /** A thing the user can do about it. The label is what a button shows. */
    public record Action(String label, Runnable run) {
    }

    @Getter private final Severity severity;
    @Getter private final String message;

    private final List<Action> actions = new ArrayList<>();

    private Notification(Severity severity, String message) {
        this.severity = severity;
        this.message = message == null ? "" : message;
    }

    public static Notification info(String message) {
        return new Notification(Severity.INFO, message);
    }

    public static Notification warning(String message) {
        return new Notification(Severity.WARNING, message);
    }

    public static Notification error(String message) {
        return new Notification(Severity.ERROR, message);
    }

    /** Adds a thing the user can do about it. Order is the order they were added. */
    public Notification withAction(String label, Runnable run) {
        if (label != null && run != null) actions.add(new Action(label, run));
        return this;
    }

    public List<Action> actions() {
        return Collections.unmodifiableList(actions);
    }

    @Override
    public String toString() {
        return severity + ": " + message;
    }
}
