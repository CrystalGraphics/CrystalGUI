package com.crystalgui.core.async;

/**
 * How urgent a job is, and therefore what it may push in front of.
 *
 * <p>Three, not a numeric priority, because a number invites a fourth value halfway between two
 * existing ones and nobody can then say what it means. Each of these answers a different question about
 * <em>who is waiting</em>, which is the only thing priority can legitimately encode.</p>
 *
 * <p>Declared in urgency order — {@link #ordinal()} is the comparison the scheduler uses, so the
 * declaration order here <b>is</b> the policy.</p>
 */
public enum JobLane {

    /**
     * A human is mid-gesture and blocked on the answer — a completion query after a keystroke.
     *
     * <p>Budgeted at well under 100ms end to end. Anything in this lane must be genuinely short: it runs
     * ahead of everything else, so a slow job here starves the rest of the system rather than merely
     * being slow itself.</p>
     */
    INTERACTIVE,

    /**
     * Visible, but tolerates being a few frames late — a reparse, or semantic tokens.
     *
     * <p>The user sees the old answer meanwhile and it is <em>positionally</em> correct (the edit was
     * interpolated on the UI thread), so lateness reads as the highlighter catching up rather than as
     * anything being wrong.</p>
     */
    LATENCY,

    /**
     * Nobody is watching — a classpath scan, the first compile of a file that was just opened.
     *
     * <p>Runs only when the lanes above are idle, <b>except</b> under the scheduler's starvation guard:
     * a job that has waited past the guard is promoted regardless of lane. Without that, a document
     * edited continuously would keep the index from ever being built, and the symptom would be
     * completion that silently never learns about unimported types.</p>
     */
    BACKGROUND
}
