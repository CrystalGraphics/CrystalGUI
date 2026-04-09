package com.crystalgui.core.event;

import com.crystalgui.core.event.UiEvent;
import com.crystalgui.core.event.UiEventDispatcher;
import com.crystalgui.core.event.UiEventListener;
import com.crystalgui.core.event.UiEventType;
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

        UiEvent event = UiEvent.pointer(UiEventType.MOUSE_DOWN, 30.0f, 30.0f);
        UiEventDispatcher.dispatchPointerEvent(root, event);

        Assert.assertSame(target, event.getTarget());
        Assert.assertEquals(5, order.size());
        Assert.assertEquals("root-capture:CAPTURE:root", order.get(0));
        Assert.assertEquals("child-capture:CAPTURE:child", order.get(1));
        Assert.assertEquals("target:TARGET:target", order.get(2));
        Assert.assertEquals("child-bubble:BUBBLE:child", order.get(3));
        Assert.assertEquals("root-bubble:BUBBLE:root", order.get(4));
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
