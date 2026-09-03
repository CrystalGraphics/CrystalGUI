package com.crystalgui.fs.client;

import com.crystalgui.core.async.ReplyError;
import com.crystalgui.core.signal.Signal;

import org.jetbrains.annotations.Nullable;

import java.util.function.LongSupplier;

/**
 * <b>How the connection is doing</b> — round trip, what is outstanding, and the last thing that failed.
 *
 * <h3>Why a UI over a wire needs this and a local one does not</h3>
 *
 * <p>{@code plan_fs_rewrite.md} D24. Every operation in this workspace crosses a socket, and when one
 * is slow there is nothing on screen that says so: a tab that will not open, a save that has not come
 * back and a tree that has not refreshed all look identical to an application that has hung. The
 * measured round trip in singleplayer is ~58 ms and a busy server is far worse, so "is it slow or is it
 * broken" is a question a person asks routinely and could not answer.</p>
 *
 * <p>It is also the measurement that decides whether optimistic explorer updates are ever worth
 * building: below a threshold they are needless machinery, and only a number can say which side of it a
 * deployment is on.</p>
 *
 * <h3>An estimate, not a measurement of the network</h3>
 *
 * <p>What is timed is request to answer, which includes the server's own tick alignment — both ends
 * drain on tick start, so a singleplayer round trip carries two 0–50 ms waits that have nothing to do
 * with the wire. That is the honest number anyway: it is what a person waits.</p>
 */
public final class Health {

    /** How much of the estimate a new sample replaces. TCP's own smoothing factor, for its reason. */
    private static final double ALPHA = 0.125;

    private final LongSupplier clockMillis;

    private double roundTripMillis;
    private int samples;
    private int inFlight;
    private long sent;
    private long received;
    @Nullable
    private ReplyError lastError;

    /** Any of the figures moved. What a status item redraws from. */
    public final Signal.Action onDidChange = new Signal.Action();

    public Health() {
        this(System::currentTimeMillis);
    }

    public Health(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** A request went out. Answers the stamp {@link #answered} needs back. */
    public long asked() {
        inFlight++;
        sent++;
        onDidChange.emit();
        return clockMillis.getAsLong();
    }

    /** It came back. */
    public void answered(long stamp) {
        settle();
        received++;
        long elapsed = Math.max(0, clockMillis.getAsLong() - stamp);
        // Exponentially smoothed, so one slow answer moves the estimate without defining it -- which is
        // what a raw last-value readout does, and why a raw one flickers uselessly.
        roundTripMillis = samples == 0 ? elapsed : roundTripMillis + ALPHA * (elapsed - roundTripMillis);
        samples++;
        onDidChange.emit();
    }

    /** It failed. The error is kept because "what went wrong last" is the second question asked. */
    public void failed(ReplyError error) {
        settle();
        lastError = error;
        onDidChange.emit();
    }

    private void settle() {
        if (inFlight > 0) inFlight--;
    }

    /** The smoothed round trip in milliseconds, or 0 before anything has been answered. */
    public long roundTripMillis() {
        return Math.round(roundTripMillis);
    }

    /** How many requests are outstanding. A number that only grows is a connection that has gone. */
    public int inFlight() {
        return inFlight;
    }

    public long requestsSent() {
        return sent;
    }

    public long answersReceived() {
        return received;
    }

    @Nullable
    public ReplyError lastError() {
        return lastError;
    }

    /** Cleared on a reconnect: the last error described a peer nobody is talking to. */
    public void reset() {
        inFlight = 0;
        lastError = null;
        onDidChange.emit();
    }

    /** {@code 58 ms · 2 in flight}, which is what a status item shows. */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        out.append(samples == 0 ? "—" : roundTripMillis() + " ms");
        if (inFlight > 0) out.append(" · ").append(inFlight).append(" in flight");
        if (lastError != null) out.append(" · last error ").append(lastError.code());
        return out.toString();
    }
}
