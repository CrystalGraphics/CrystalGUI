package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.UIText;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The transient half of a notification — IntelliJ's balloons, VS Code's toasts.
 *
 * <h3>Why both surfaces exist</h3>
 *
 * <p>A balloon is seen and a list is consulted. Neither replaces the other: a message that only ever
 * appears in a panel you were not looking at may as well not have arrived, and a message that only ever
 * flashed past cannot be gone back to. Both references show the same notification twice for that reason,
 * and this draws the {@link NotificationCard} the history draws, from the same model.</p>
 *
 * <h3>Everything fades, errors included</h3>
 *
 * <p>Every balloon leaves on its own after {@link #LINGER_MS}, and is held open while the pointer is on it.
 * An earlier pass made {@code WARNING} and {@code ERROR} sticky on the grounds that a failure removing
 * itself unseen is a failure never told about — see {@link #LINGER_MS} for why that reasoning does not hold
 * once there is a history and an unread mark to carry it.</p>
 *
 * <h3>Fading, and where the timing lives</h3>
 *
 * <p>Opacity is a <b>CSS transition</b>, not a Java tween — the same rule that makes {@code Switch}'s knob
 * a transition on {@code flex-grow}. A balloon is added carrying {@link #HIDDEN_CLASS}, which the sheet
 * gives {@code opacity: 0}; dropping the class on the next frame lets the cascade ease it to the resting
 * value. That ordering is not stylistic: this engine records that transitioning something into view needs
 * <b>a resting value in the sheet</b> rather than a one-frame write from Java, because the write is itself
 * transitionable and the engine would ease toward it and then retarget it back — nothing animates, and no
 * test sees it.</p>
 *
 * <p>The one genuine coupling is {@link #FADE_MS}, which must match the sheet's {@code transition}
 * duration: this class waits that long after starting a fade-out before detaching, and detaching early
 * would cut the animation off mid-way. Stated in both places rather than read from the cascade, because a
 * removal that depended on parsing a duration would fail silently when the theme changed it.</p>
 */
public class NotificationBalloons extends UIElement implements UIFrameTicker {

    public static final String LAYER_CLASS = "__balloons__";
    public static final String BALLOON_CLASS = "__balloon__";

    /** Carried while a balloon is transparent — on arrival, and again while it leaves. */
    public static final String HIDDEN_CLASS = "__hidden__";

    /** How long a fade takes. <b>Must match the sheet's transition duration.</b> @see NotificationBalloons */
    public static final float FADE_MS = 150f;

    /**
     * How long a balloon stays up before it starts to leave — <b>every severity, including errors</b>.
     *
     * <h3>Errors used to be sticky, and that was wrong</h3>
     *
     * <p>The argument for holding a failure until dismissed was that one which removed itself while you were
     * reading something else is a failure you were never told about. The answer is that it <em>is</em> still
     * told: the history keeps it and the bell carries an unread dot, so the balloon is not the record and
     * never needed to behave like one. IntelliJ fades errors and warnings for exactly that reason, and a
     * failure that demands a click before the screen is usable again is its own kind of noise — especially
     * when several arrive at once.</p>
     *
     * <p>Ten seconds rather than six because the same number now has to cover a title <em>and</em> a detail
     * line <em>and</em> reaching for an action. Anyone actually reading one is holding it open anyway — see
     * the hover hold in {@link #tickFrame}.</p>
     */
    public static final float LINGER_MS = 10_000f;

    /**
     * How many auto-dismissing balloons may be up at once.
     *
     * <p>A cap rather than a queue: these are transient, so holding one back until a slot frees means
     * showing it long after the thing it describes, which is worse than not showing it at all. The oldest
     * leaves to make room and is still in the history.</p>
     */
    public static final int MAX_VISIBLE = 4;


    /** One balloon on screen, and what it is waiting for. */
    private static final class Live {
        final Notification notification;
        final UIElement element;
        float remaining;
        boolean leaving;

        Live(Notification notification, UIElement element) {
            this.notification = notification;
            this.element = element;
            this.remaining = LINGER_MS;
        }
    }

    private final List<Live> live = new ArrayList<>();

    @Nullable
    private Connection arrivals;

    @Nullable
    private Connection repeats;
    private boolean ticking;

    public NotificationBalloons() {
        addClass(LAYER_CLASS);
        // NOT setHitTest(false): that applies to the whole subtree, so the balloons' own close buttons and
        // action links would stop taking the pointer too. The layer is sized to its content instead, so
        // there is nothing of it to click beside them.
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /** Subscribes while attached. @see NotificationsView#onLayoutChanged */
    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        UIWindow window = getAttachedWindow();
        if (window != null) {
            if (arrivals == null) arrivals = Notifications.onDidNotify.connect(this::show);
            if (repeats == null) repeats = Notifications.onDidRepeat.connect(this::restate);
            if (!ticking) {
                window.registerTicker(this);
                ticking = true;
            }
        } else if (arrivals != null) {
            arrivals.disconnect();
            arrivals = null;
            if (repeats != null) repeats.disconnect();
            repeats = null;
        }
    }

    /** Puts one up. Newest at the bottom, which is where the eye already is after the last one. */
    private void show(Notification notification) {
        // The close button needs the entry the card is about to go into, so the handler is given a holder
        // rather than the value -- the alternative is a second lookup by element every time it is pressed.
        Live[] holder = new Live[1];
        UIElement balloon = NotificationCard.build(notification,
                () -> { if (holder[0] != null) beginLeaving(holder[0]); });
        Live entry = new Live(notification, balloon);
        holder[0] = entry;
        balloon.addClass(BALLOON_CLASS);
        // TRANSPARENT ON ARRIVAL, revealed on the next frame -- see the class note on why the resting
        // value has to come from the sheet.
        balloon.addClass(HIDDEN_CLASS);
        addInternalChild(balloon);
        live.add(entry);

        enforceCap();
    }

    /**
     * Sends the oldest balloons away until the cap is met.
     *
     * <h3>Counts what is STILL ARRIVING, not what is on the list</h3>
     *
     * <p>This was {@code while (live.size() > MAX_VISIBLE) beginLeaving(live.get(0))}, and it is an
     * <b>infinite loop</b>: {@code beginLeaving} only marks an entry, because the element has to stay
     * mounted for the length of its fade — so the list does not shrink, the next pass finds the same
     * already-leaving entry at index 0, {@code beginLeaving} returns immediately, and the size never
     * changes. The window stops responding the moment a fifth balloon arrives.</p>
     *
     * <p>So the cap counts entries that are not already on their way out, and each pass marks one of them,
     * which is what makes it terminate. Cheap to state and impossible to get wrong by re-reading the size:
     * "how many are staying" is the question the cap was always asking.</p>
     */
    private void enforceCap() {
        evictOldest();
    }

    /**
     * Sends the oldest balloons of one kind away until that kind is within its cap.
     *
     * <h3>Counts what is STILL ARRIVING, not what is on the list</h3>
     *
     * <p>This was {@code while (live.size() > MAX_VISIBLE) beginLeaving(live.get(0))}, and it is an
     * <b>infinite loop</b>: {@code beginLeaving} only marks an entry, because the element has to stay
     * mounted for the length of its fade — so the list does not shrink, the next pass finds the same
     * already-leaving entry at index 0, {@code beginLeaving} returns immediately, and the size never
     * changes. The window stopped responding on the fifth file opened in a row.</p>
     *
     * <p>So the count is of entries that are not already on their way out, and each pass marks one of
     * them, which is what makes it terminate. "How many are staying" is the question a cap was always
     * asking; re-reading the list's size was the wrong proxy for it.</p>
     */
    private void evictOldest() {
        int staying = 0;
        for (Live entry : live) {
            if (!entry.leaving) staying++;
        }
        for (int i = 0; i < live.size() && staying > MAX_VISIBLE; i++) {
            Live entry = live.get(i);
            if (entry.leaving) continue;
            beginLeaving(entry);
            staying--;
        }
    }

    /**
     * A repeat re-texts the balloon already up and <b>restarts its clock</b>, rather than adding another.
     *
     * <p>Restarting is the difference from the history's handling: a balloon is a claim on your attention
     * right now, so a message that just happened again deserves the full linger. Without it a repeat could
     * arrive a fraction of a second before the card faded and be gone before it was read.</p>
     *
     * <p>If nothing is up for it — it faded, or was dismissed — the repeat is shown as a fresh balloon,
     * which is what "it happened again" means once the previous one is gone.</p>
     */
    private void restate(Notification notification) {
        for (Live entry : live) {
            if (entry.notification != notification || entry.leaving) continue;
            UIText label = NotificationCard.titleLabelOf(entry.element);
            if (label != null) label.setText(NotificationCard.titleOf(notification));
            entry.remaining = LINGER_MS;
            return;
        }
        show(notification);
    }

    /** Dismisses one by hand — the close button, and what the cap uses. */
    private void beginLeaving(Live entry) {
        if (entry.leaving) return;
        entry.leaving = true;
        entry.remaining = FADE_MS;
        entry.element.addClass(HIDDEN_CLASS);
    }

    @Override
    public boolean tickFrame(float deltaSeconds) {
        float deltaMs = deltaSeconds * 1000f;
        for (int i = live.size() - 1; i >= 0; i--) {
            Live entry = live.get(i);
            // REVEALED ON THE FRAME AFTER IT WAS ADDED, never in the same one. Adding the element and
            // removing the class together is a single style pass, so the cascade never sees the
            // transparent state and there is nothing to ease from -- the balloon simply appears.
            if (!entry.leaving && entry.element.hasClass(HIDDEN_CLASS)) {
                entry.element.removeClass(HIDDEN_CLASS);
                continue;
            }
            // NOT WHILE THE POINTER IS ON IT. Six seconds is enough to read a title and not always enough
            // to read a detail and reach for an action, so a balloon that kept counting down could vanish
            // out from under the cursor of someone about to click "Retry" — the oldest complaint about
            // toasts, and one both references answer by holding while hovered.
            //
            // `isHovered` is set on the whole entered CHAIN, not only the exact element hit, so this is
            // true for the pointer anywhere inside the card — including over its own action links, which is
            // precisely the moment it matters. A leaving balloon is deliberately not reprieved: it is
            // already fading and reversing that would need the transition retargeted mid-flight.
            if (!entry.leaving && entry.element.isHovered()) continue;

            entry.remaining -= deltaMs;
            if (entry.remaining > 0f) continue;
            if (!entry.leaving) {
                beginLeaving(entry);
                continue;
            }
            // The fade has run its course; only now is the element safe to detach.
            removeInternalChild(entry.element);
            live.remove(i);
        }
        // NEVER DROPPED. The layer has to be listening on the frame a notification arrives, and there is
        // no signal that says one is about to.
        return true;
    }

    /** How many are on screen, leaving ones included. For tests and diagnostics. */
    public int liveCount() {
        return live.size();
    }
}
