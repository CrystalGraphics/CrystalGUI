package com.crystalgui.ui.service;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.LayoutGroup;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.UIEvent;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.util.function.Consumer;

/** A tree with boxes, and the four gestures a test needs to drive it. */
final class ServiceFixtures {

    static final float W = 800f;
    static final float H = 600f;

    private ServiceFixtures() {
    }

    static UIElement at(String id, float x, float y, float width, float height) {
        UIElement node = new UIElement().setId(id);
        layout(node, l -> l.positionType(TaffyPosition.ABSOLUTE).left(x).top(y).width(width).height(height));
        return node;
    }

    static void layout(UIElement node, Consumer<LayoutGroup> style) {
        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), style);
    }

    /** A whole frame: motion, cascade, layout, and the pointer diffed against what just laid out. */
    static void frame(UIDocument document) {
        document.frame(0.016f, W, H);
    }

    static void move(UIDocument document, float x, float y) {
        document.input().consumeMouseEvent(
                new CgSystemInput.Mouse.Event((int) x, (int) y, 0, 0, -1, false, 0f, now()));
    }

    static void press(UIDocument document, float x, float y) {
        press(document, x, y, 0);
    }

    static void press(UIDocument document, float x, float y, int button) {
        move(document, x, y);
        document.input().consumeMouseEvent(
                new CgSystemInput.Mouse.Event((int) x, (int) y, 0, 0, button, true, 0f, now()));
    }

    static void release(UIDocument document, float x, float y) {
        release(document, x, y, 0);
    }

    static void release(UIDocument document, float x, float y, int button) {
        move(document, x, y);
        document.input().consumeMouseEvent(
                new CgSystemInput.Mouse.Event((int) x, (int) y, 0, 0, button, false, 0f, now()));
    }

    static void wheel(UIDocument document, float notches) {
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                (int) document.input().pointer().x(), (int) document.input().pointer().y(),
                0, 0, -1, false, notches, now()));
    }

    static boolean key(UIDocument document, int keyCode, boolean pressed) {
        return document.input().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\0', keyCode, pressed, false, now()));
    }

    /** Attaches a listener for one concrete event class, at the TARGET only — what a widget does. */
    static <T extends UIEvent> void onTarget(UIElement node, Class<T> type, UIEvent.Listener<UIElement, T> listener) {
        node.events.getGroup(type).attachListener(listener, false, false);
    }

    /** Attaches a listener for one concrete event class, in every phase. */
    static <T extends UIEvent> void on(UIElement node, Class<T> type, UIEvent.Listener<UIElement, T> listener) {
        node.events.getGroup(type).attachListener(listener, true, true);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
