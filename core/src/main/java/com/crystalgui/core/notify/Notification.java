package com.crystalgui.core.notify;

import lombok.Getter;

import javax.annotation.Nullable;

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

    /** The group nothing named one belongs to. @see #getGroupId() */
    public static final String DEFAULT_GROUP = "general";

    @Getter private final Severity severity;

    /** Revisable through {@link NotificationHandle#updateMessage}, and only through it. */
    @Getter private String message;

    /**
     * When it happened, as wall-clock milliseconds.
     *
     * <h3>Stamped at construction, and with the wall clock</h3>
     *
     * <p>A notification without one cannot be ordered, grouped by day, or shown as "6:51 AM" beside
     * "Yesterday" — which is most of what makes a history readable rather than a pile. Reading the clock
     * at <em>render</em> time instead would stamp every message with the moment you happened to open the
     * panel.</p>
     *
     * <p><b>{@code currentTimeMillis}, never {@code nanoTime}.</b> This codebase already records that
     * {@code nanoTime} has an arbitrary origin and may be negative — it measures durations and says
     * nothing about when. A timestamp is a wall-clock fact.</p>
     */
    @Getter private final long timestamp = System.currentTimeMillis();

    /**
     * The lines under the title, or empty.
     *
     * <p>IntelliJ's split, and it is what makes a list scannable: the title is what you read going down
     * the column, the detail is what you read when one of them stops you. A single string forces every
     * producer to choose between a title too long to skim and a message too short to act on.</p>
     */
    @Getter private String detail = "";

    /**
     * Which producer this came from — IntelliJ's {@code NotificationGroup}.
     *
     * <p>Carried now although nothing reads it yet, because the two things it enables are exactly what
     * would otherwise force a model change later: per-group display settings (balloon, sticky, log-only)
     * and a "from" line in the list. A chatty producer owning the screen is the failure mode groups exist
     * to prevent.</p>
     */
    @Getter private String groupId = DEFAULT_GROUP;

    /**
     * How many times this same message has arrived — 1 for the ordinary case.
     *
     * <p>Not a count of anything the producer said: it is the collapse of a repeat, so a card can read
     * "Save failed ×4" instead of four identical cards. @see Notifications#show
     */
    @Getter private int repeats = 1;

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

    /** The lines under the title. @see #getDetail() */
    public Notification withDetail(String text) {
        this.detail = text == null ? "" : text;
        return this;
    }

    /** Which producer this came from. @see #getGroupId() */
    public Notification inGroup(String id) {
        this.groupId = id == null || id.isEmpty() ? DEFAULT_GROUP : id;
        return this;
    }

    /** @see NotificationHandle#updateMessage */
    void setMessage(String replacement) {
        this.message = replacement == null ? "" : replacement;
    }

    /** Counts another arrival of the same message. @see Notifications#show */
    void markRepeated() {
        repeats++;
    }

    /**
     * Whether {@code other} says the same thing this does — severity, title, detail and group.
     *
     * <p><b>Actions are deliberately not compared.</b> Two arrivals of the same failure carry equivalent
     * actions by construction, and the {@code Runnable}s are fresh lambdas every time, so comparing them
     * would make every notification unique and the collapse would never fire. The timestamp is excluded for
     * the same reason — it is what differs between two repeats, not what distinguishes them.</p>
     */
    public boolean saysTheSameAs(@Nullable Notification other) {
        return other != null
                && other.severity == severity
                && other.message.equals(message)
                && other.detail.equals(detail)
                && other.groupId.equals(groupId);
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
