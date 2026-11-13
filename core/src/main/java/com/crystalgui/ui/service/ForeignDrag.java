package com.crystalgui.ui.service;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.event.EventTarget;

/**
 * A drag that began in another document, as this document's elements see it: the same Enter, Over, Drop and Leave a
 * drag of their own gets, with {@link #SOURCE} as its source. The document's thread; the host drives it.
 *
 * <pre>{@code
 * ForeignDrag in = document.input().foreignDrag();
 * in.move(payload, x, y);      // each pointer move over the document, in its surface pixels
 * in.drop(x, y);               // the button came up here: true when an element took the drop
 * in.leave();                  // the pointer went elsewhere, or the drag was cancelled
 * }</pre>
 *
 * <p>A target accepts as it does a local drag, with {@code preventDefault()} on its {@code Over}. Only a portable
 * payload ({@link TransferData#portable}) is ever offered here.</p>
 */
public final class ForeignDrag {

    /** What {@link DragEvent#getSource()} is for a drag that did not start in this document. */
    public static final EventTarget SOURCE = new EventTarget() {
        @Override
        public String toString() {
            return "foreign-drag-source";
        }
    };

    private final Input input;
    private final UIDocument document;
    @Nullable
    private UIElement target;
    @Nullable
    private Object payload;
    private boolean accepted;

    ForeignDrag(Input input, UIDocument document) {
        this.input = input;
        this.document = document;
    }

    /** Whether a drag is over this document now. */
    public boolean active() {
        return payload != null;
    }

    /** The pointer is at ({@code x}, {@code y}) with {@code payload}: enters, leaves and asks the element under it. */
    public void move(Object payload, float x, float y) {
        this.payload = payload;
        Focus focus = document.focus();
        Box box = document.boxes().hitTest(x, y, b -> focus.isInert(b.node()));
        UIElement under = box == null ? null : box.node();
        if (under != target) {
            UIElement common = commonAncestor(target, under);
            for (UIElement at = target; at != null && at != common; at = at.composedParent()) {
                input.send(at, new DragEvent.Leave(at, input.pointer(), SOURCE, payload));
            }
            List<UIElement> entered = new ArrayList<>();
            for (UIElement at = under; at != null && at != common; at = at.composedParent()) entered.add(at);
            for (int i = entered.size() - 1; i >= 0; i--) {
                UIElement at = entered.get(i);
                input.send(at, new DragEvent.Enter(at, input.pointer(), SOURCE, payload));
            }
            target = under;
        }
        if (target == null) {
            accepted = false;
            return;
        }
        DragEvent.Over over = new DragEvent.Over(target, input.pointer(), SOURCE, payload);
        input.send(target, over);
        accepted = over.isDefaultPrevented();
    }

    /** The button came up over the document: drops on the element that accepted, if one did. @return whether one did */
    public boolean drop(float x, float y) {
        if (payload == null) return false;
        Object dropped = payload;
        UIElement on = accepted ? target : null;
        if (on != null) input.send(on, new DragEvent.Drop(on, input.pointer(), SOURCE, dropped));
        leave();
        return on != null;
    }

    /** The drag is no longer over the document: every element it entered hears a Leave. */
    public void leave() {
        Object left = payload;
        UIElement was = target;
        payload = null;
        target = null;
        accepted = false;
        if (left == null) return;
        for (UIElement at = was; at != null; at = at.composedParent()) {
            input.send(at, new DragEvent.Leave(at, input.pointer(), SOURCE, left));
        }
    }

    @Nullable
    private static UIElement commonAncestor(@Nullable UIElement a, @Nullable UIElement b) {
        if (a == null || b == null) return null;
        for (UIElement up = a; up != null; up = up.composedParent()) {
            for (UIElement down = b; down != null; down = down.composedParent()) {
                if (up == down) return up;
            }
        }
        return null;
    }
}
