package com.crystalgui.workbench.chrome.notification;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.ui.dom.UIElement;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A balloon has to be transparent for a whole style pass, or it has nothing to fade in from.
 *
 * <p><b>One tick is not one frame.</b> A notification raised from off-thread work is drained at the TOP
 * of the frame, ahead of the animation phase, so the balloon was added and revealed inside a single pass
 * and simply appeared; one raised from a click reached the layer after that hook had already run, and
 * faded. Whether a balloon animated depended on where its cause entered the frame.</p>
 */
public class BalloonFadesInTest extends UiDocumentTestBase {

    private static UIElement cardIn(NotificationBalloons layer) {
        for (UIElement child : layer.children()) {
            if (child instanceof NotificationCard) return child;
        }
        return null;
    }

    @Test
    public void aBalloonRaisedBeforeTheFrameIsStillTransparentDuringIt() {
        NotificationBalloons layer = new NotificationBalloons();
        document.append(layer);
        frame();

        // RAISED BETWEEN FRAMES, which is where off-thread work lands: JobScheduler drains at the top of
        // the frame, so this is ahead of the animation phase rather than after it.
        Notifications.show(Notification.info("File Changed").withDetail("a.java was changed"));
        frame();

        UIElement card = cardIn(layer);
        assertNotNull("the balloon was added", card);
        assertTrue("still transparent, so the cascade has a value to ease FROM",
                card.hasClass(NotificationBalloons.HIDDEN_CLASS));

        frame();
        assertFalse("and revealed on the frame after, which is what animates",
                card.hasClass(NotificationBalloons.HIDDEN_CLASS));
    }
}
