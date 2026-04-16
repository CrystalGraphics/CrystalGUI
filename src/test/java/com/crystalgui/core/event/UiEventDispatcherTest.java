package com.crystalgui.core.event;

import com.crystalgui.core.geometry.UiRect;
import com.crystalgui.ui.UIElement;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class UiEventDispatcherTest {

    @Test
    public void shouldDispatchPointerEventInCaptureTargetBubbleOrder() {
        UIElement root = new UIElement().setId("root");
        UIElement child = new UIElement().setId("child");
        UIElement target = new UIElement().setId("target");

        root.addChild(child);
        child.addChild(target);

        root.getLayoutState().setLayoutBox(new UiRect(0.0f, 0.0f, 200.0f, 200.0f));
        child.getLayoutState().setLayoutBox(new UiRect(10.0f, 10.0f, 150.0f, 150.0f));
        target.getLayoutState().setLayoutBox(new UiRect(20.0f, 20.0f, 50.0f, 50.0f));

        final List<String> order = new ArrayList<String>();

        root.addEventListener(UiEventType.MOUSE_DOWN, new RecordingListener("root-capture", order), true);
        child.addEventListener(UiEventType.MOUSE_DOWN, new RecordingListener("child-capture", order), true);
        target.addEventListener(UiEventType.MOUSE_DOWN, new RecordingListener("target", order));
        child.addEventListener(UiEventType.MOUSE_DOWN, new RecordingListener("child-bubble", order));
        root.addEventListener(UiEventType.MOUSE_DOWN, new RecordingListener("root-bubble", order));

        UiMouseEvent event = UiMouseEvent.pointer(UiEventType.MOUSE_DOWN, 30.0f, 30.0f);
        UiEventDispatcher.dispatchPointerEvent(root, event);

        Assert.assertSame(target, event.getTarget());
        Assert.assertEquals(5, order.size());
        Assert.assertEquals("root-capture:CAPTURE:root", order.get(0));
        Assert.assertEquals("child-capture:CAPTURE:child", order.get(1));
        Assert.assertEquals("target:TARGET:target", order.get(2));
        Assert.assertEquals("child-bubble:BUBBLE:child", order.get(3));
        Assert.assertEquals("root-bubble:BUBBLE:root", order.get(4));
    }

    @Test
    public void shouldStopImmediatePropagationOnCurrentTarget() {
        UIElement root = new UIElement().setId("root");
        root.getLayoutState().setLayoutBox(new UiRect(0, 0, 100, 100));

        final List<String> order = new ArrayList<>();

        root.addEventListener(UiEventType.MOUSE_DOWN, event -> {
            order.add("first");
            event.stopImmediatePropagation();
        });
        root.addEventListener(UiEventType.MOUSE_DOWN, event -> order.add("second-should-not-fire"));

        UiMouseEvent event = UiMouseEvent.pointer(UiEventType.MOUSE_DOWN, 50, 50);
        UiEventDispatcher.dispatchPointerEvent(root, event);

        Assert.assertEquals(1, order.size());
        Assert.assertEquals("first", order.get(0));
    }

    @Test
    public void shouldDispatchEnterLeaveOnlyOnTarget() {
        UIElement root = new UIElement().setId("root");
        UIElement child = new UIElement().setId("child");
        root.addChild(child);

        root.getLayoutState().setLayoutBox(new UiRect(0, 0, 200, 200));
        child.getLayoutState().setLayoutBox(new UiRect(10, 10, 50, 50));

        final List<String> order = new ArrayList<>();

        // Root should NOT see enter/leave
        root.addEventListener(UiEventType.MOUSE_ENTER, new RecordingListener("root-enter", order));
        root.addEventListener(UiEventType.MOUSE_ENTER, new RecordingListener("root-enter-capture", order), true);

        // Only target should see enter
        child.addEventListener(UiEventType.MOUSE_ENTER, new RecordingListener("child-enter", order));

        UiMouseEvent enter = UiMouseEvent.pointer(UiEventType.MOUSE_ENTER, 20, 20);
        UiEventDispatcher.dispatchDirectEvent(child, enter);

        Assert.assertEquals(1, order.size());
        Assert.assertEquals("child-enter:TARGET:child", order.get(0));
    }

    @Test
    public void shouldAllowListenerMutationDuringDispatch() {
        UIElement root = new UIElement().setId("root");
        root.getLayoutState().setLayoutBox(new UiRect(0, 0, 100, 100));

        final List<String> order = new ArrayList<>();

        // First listener adds another listener during dispatch
        root.addEventListener(UiEventType.MOUSE_DOWN, event -> {
            order.add("first");
            // Add a new listener during dispatch — should not fire in this dispatch
            root.addEventListener(UiEventType.MOUSE_DOWN, event3 -> order.add("added-during-dispatch"));
        });
        root.addEventListener(UiEventType.MOUSE_DOWN, event -> order.add("second"));

        // First dispatch: added listener should NOT fire
        UiMouseEvent event1 = UiMouseEvent.pointer(UiEventType.MOUSE_DOWN, 50, 50);
        UiEventDispatcher.dispatchPointerEvent(root, event1);
        Assert.assertEquals(2, order.size());
        Assert.assertEquals("first", order.get(0));
        Assert.assertEquals("second", order.get(1));

        // Second dispatch: now the added listener fires
        order.clear();
        UiMouseEvent event2 = UiMouseEvent.pointer(UiEventType.MOUSE_DOWN, 50, 50);
        UiEventDispatcher.dispatchPointerEvent(root, event2);
        Assert.assertEquals(3, order.size());
    }

    @Test
    public void shouldNotBubbleNonBubblingEvents() {
        Assert.assertFalse(UiEventType.MOUSE_ENTER.bubbles());
        Assert.assertFalse(UiEventType.MOUSE_LEAVE.bubbles());
        Assert.assertFalse(UiEventType.FOCUS_IN.bubbles());
        Assert.assertFalse(UiEventType.FOCUS_OUT.bubbles());
        Assert.assertTrue(UiEventType.MOUSE_DOWN.bubbles());
        Assert.assertTrue(UiEventType.CLICK.bubbles());
    }

    private static final class RecordingListener implements UiEventListener {
        private final String label;
        private final List<String> order;

        private RecordingListener(String label, List<String> order) {
            this.label = label;
            this.order = order;
        }

        @Override
        public void handle(UiEvent event) {
            order.add(label + ":" + event.getPhase().name() + ":" + event.getCurrentTarget().getId());
        }
    }
}
