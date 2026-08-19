package com.crystalgui.core.async;

import java.util.Objects;

/**
 * What makes two jobs "the same job" — the identity single-flight is keyed on.
 *
 * <p>An {@code owner} (the document, the workspace, whatever the work is <em>about</em>) and a
 * {@code kind} (what is being computed about it). Submitting a key that is already pending replaces it;
 * submitting one that is already running supersedes it. See {@link JobScheduler}.</p>
 *
 * <h3>Both halves are load-bearing</h3>
 * <p>Keying on the kind alone would make two open documents fight — the second one's reparse would
 * cancel the first's, and the symptom is one editor of a split pair never updating. Keying on the owner
 * alone would make a document's reparse cancel its own diagnostics, which is worse: the two are
 * different questions and both answers are wanted.</p>
 *
 * <p>Owner is compared by <b>identity</b>, not {@code equals}. The owner is a live object with a
 * lifetime — a {@code TextBuffer}, a session — and two distinct documents that happen to hold identical
 * text are emphatically not the same owner. Using {@code equals} here would silently collapse them,
 * which is the exact bug the paragraph above describes, arrived at from the other direction.</p>
 */
public final class JobKey {

    private final Object owner;
    private final String kind;

    private JobKey(Object owner, String kind) {
        this.owner = Objects.requireNonNull(owner, "a job key needs an owner");
        this.kind = Objects.requireNonNull(kind, "a job key needs a kind");
    }

    /**
     * @param owner what the work is about — compared by identity, so pass the live object
     * @param kind  what is being computed, e.g. {@code "reparse"}, {@code "diagnostics"}
     */
    public static JobKey of(Object owner, String kind) {
        return new JobKey(owner, kind);
    }

    public Object owner() {
        return owner;
    }

    public String kind() {
        return kind;
    }

    /** Identity on the owner, value equality on the kind — see the class note on why. */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof JobKey key && key.owner == this.owner && key.kind.equals(this.kind);
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(owner) * 31 + kind.hashCode();
    }

    @Override
    public String toString() {
        return kind + "@" + Integer.toHexString(System.identityHashCode(owner));
    }
}
