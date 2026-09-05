package com.crystalgui.ui.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.DragEvent;

/**
 * <b>A drag is already off the mode stack when it reports its end — ask the drag, not the input.</b>
 *
 * <p>{@code Drag.end} pops the mode before calling {@code onDragEnd}, so
 * {@code input.mode(Drag.class)} inside that callback answers <b>null</b>. A listener written to ask
 * "did anything accept the drop?" that way gets no answer and, if it treats null as a refusal, acts as
 * though every drop were refused.</p>
 *
 * <p>That is what tore a tool window out of the region it had just been docked into: the Drop is
 * delivered <em>before</em> the listener, so the dock happened, and the stripe's tear-out then undid it —
 * the snap zone lit up, the panel landed, and it floated straight back out as a window.</p>
 *
 * <p>The answer is on the {@link Drag} that {@code start} returned, which stays readable afterwards.</p>
 */
public class DragEndReportsItsDropTest extends UiDocumentTestBase {

    /** A target that accepts, the only way a target can: {@code preventDefault} on every Over. */
    private static UIElement accepting() {
        UIElement target = new UIElement().layout(l -> l.width(200).height(200));
        target.events.getGroup(DragEvent.Over.class)
                .attachListener((element, event) -> event.preventDefault(), false, true);
        return target;
    }

    @Test
    public void theDragIsNoLongerTheLiveModeWhenItReportsTheEnd() {
        UIElement source = new UIElement().layout(l -> l.width(20).height(20));
        document.append(source);
        frame();

        AtomicReference<Drag> seenByInput = new AtomicReference<>();
        AtomicBoolean ended = new AtomicBoolean();
        Drag drag = Drag.start(source, 10f, 10f, CgMouseCodes.LEFT_BUTTON, "payload",
                Drag.DEFAULT_THRESHOLD_PX, new Drag.Listener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                    }

                    @Override
                    public void onDragEnd(float x, float y) {
                        ended.set(true);
                        seenByInput.set(document.input().mode(Drag.class));
                    }
                });
        drag.pointerMoved(100f, 100f);
        drag.end(100f, 100f);

        assertTrue("the drag never reported its end, so this test is measuring nothing", ended.get());
        assertNull("the drag was still the live mode inside onDragEnd -- if that ever becomes true, the "
                + "workaround this test exists for can be simplified", seenByInput.get());
    }

    /**
     * ...and the drag it started still knows whether anything accepted.
     *
     * <p>The half that makes the guard possible at all: a listener that keeps the {@code Drag} it was
     * given can answer the question the input no longer can.</p>
     */
    @Test
    public void theDragItselfStillKnowsWhetherTheDropWasAccepted() {
        UIElement source = new UIElement().layout(l -> l.width(20).height(20));
        UIElement target = accepting();
        document.append(source);
        document.append(target);
        frame();

        AtomicBoolean acceptedAtEnd = new AtomicBoolean();
        AtomicReference<Drag> handle = new AtomicReference<>();
        handle.set(Drag.start(source, 10f, 10f, CgMouseCodes.LEFT_BUTTON, "payload",
                Drag.DEFAULT_THRESHOLD_PX, new Drag.Listener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                    }

                    @Override
                    public void onDragEnd(float x, float y) {
                        acceptedAtEnd.set(handle.get().isDropAccepted());
                    }
                }));
        // OVER THE TARGET, which is what gives it the chance to accept.
        handle.get().pointerMoved(100f, 100f);
        frame();
        handle.get().end(100f, 100f);

        assertTrue("the drag forgot that its drop was accepted, so a listener cannot tell a successful "
                + "drop from a refused one", acceptedAtEnd.get());
    }

    /** The counter-control: a drop nothing accepted still reports refusal. */
    @Test
    public void aDropNothingAcceptedIsRefused() {
        UIElement source = new UIElement().layout(l -> l.width(20).height(20));
        UIElement plain = new UIElement().layout(l -> l.width(200).height(200));
        document.append(source);
        document.append(plain);
        frame();

        AtomicBoolean acceptedAtEnd = new AtomicBoolean(true);
        AtomicReference<Drag> handle = new AtomicReference<>();
        handle.set(Drag.start(source, 10f, 10f, CgMouseCodes.LEFT_BUTTON, "payload",
                Drag.DEFAULT_THRESHOLD_PX, new Drag.Listener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                    }

                    @Override
                    public void onDragEnd(float x, float y) {
                        acceptedAtEnd.set(handle.get().isDropAccepted());
                    }
                }));
        handle.get().pointerMoved(100f, 100f);
        frame();
        handle.get().end(100f, 100f);

        assertFalse("a drop nothing accepted reported itself accepted, which would stop every tear-out "
                + "and every 'dropped nowhere' path in the application", acceptedAtEnd.get());
    }
}
