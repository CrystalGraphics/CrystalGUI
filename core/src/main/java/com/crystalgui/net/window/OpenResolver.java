package com.crystalgui.net.window;

import com.crystalgui.serialization.StateMap;
import javax.annotation.Nullable;

/**
 * <b>Decides whether a client may open a window, and of what.</b>
 *
 * <p>Registered per type with {@link ServerWindows#openable}, and it <b>is</b> the authority — not a
 * hint the caller may override, not a filter running after the decision. Nothing else consults it and
 * nothing opens without it.</p>
 *
 * <h3>Openability belongs to a deployment, not to a class</h3>
 *
 * <p>It is declared here rather than on the {@code UiType} because the same panel class may be
 * openable in one context and not another — a debug panel on a test server and not a live one, an
 * admin screen for some players and not others. Putting it on the type would make a deployment
 * decision into a property of a class, and every consumer of that class would inherit it.</p>
 *
 * <h3>{@code args} is untrusted</h3>
 *
 * <p>It came from a client, so it is a claim rather than a fact. <b>Re-derive the model from it; never
 * dereference it.</b> A position is a position to look up, an id is an id to resolve — and each has to
 * be checked against what this player may actually reach:</p>
 *
 * <pre>{@code
 * ServerWindows.openable(FurnacePanel.TYPE, (viewer, args) -> {
 *     BlockPos pos = readPos(args);                    // a CLAIM
 *     if (!world.isBlockLoaded(pos)) return null;      // ...checked
 *     if (player(viewer).getDistanceSq(pos) > 64) return null;
 *     return furnaceAt(pos);                           // ...and re-derived
 * });
 * }</pre>
 *
 * <p>The same posture the widget contracts take for event payloads: the far side may be lying, so
 * nothing it sends is used as anything but an input to a lookup this side controls.</p>
 *
 * <h3>Refusing is an ordinary answer</h3>
 *
 * <p>Return {@code null}. Not an exception — a client asking for something it may not have is expected
 * traffic, not a fault, and a refusal has to be as cheap to write as a grant or the check will be
 * skipped. Throwing is reserved for a resolver that is actually broken, and is treated as a refusal
 * with a log line.</p>
 *
 * @param <M> the model a granted window is opened with
 */
@FunctionalInterface
public interface OpenResolver<M> {

    /**
     * @param viewer who is asking — the connection's peer, the same handle an event's context carries
     * @param args   what they sent. <b>Untrusted.</b> Never empty; a client that sends nothing sends
     *               an empty map rather than null, so a resolver need not check
     * @return the model to open with, or {@code null} to refuse
     */
    @Nullable
    M resolve(@Nullable Object viewer, StateMap<Object> args);
}
