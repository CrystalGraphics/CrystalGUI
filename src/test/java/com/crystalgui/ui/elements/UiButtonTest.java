package com.crystalgui.ui.elements;

import com.crystalgui.core.event.UiEvent;
import com.crystalgui.core.event.UiEventDispatcher;
import com.crystalgui.core.event.UiEventListener;
import com.crystalgui.core.event.UiEventType;
import com.crystalgui.core.event.UiMouseEvent;
import com.crystalgui.core.input.FocusPolicy;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIContainer;
import com.crystalgui.ui.UIDocument;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyDimension;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import org.junit.Assert;
import org.junit.Test;

public class UiButtonTest {

    @Test
    public void shouldEmitClickedSignalOnClick() {
        UiButton btn = createTestButton();
        final int[] clickCount = {0};
        btn.clicked.connect(new Runnable() {
            @Override public void run() { clickCount[0]++; }
        });

        UiMouseEvent click = UiMouseEvent.click(UiEventType.CLICK, 50, 25, 0, 1);
        UiEventDispatcher.dispatchDirectEvent(btn, click);

        Assert.assertEquals(1, clickCount[0]);
    }

    @Test
    public void shouldNotEmitClickedWhenDisabled() {
        UiButton btn = createTestButton();
        btn.setEnabled(false);
        final int[] clickCount = {0};
        btn.clicked.connect(new Runnable() {
            @Override public void run() { clickCount[0]++; }
        });

        UiMouseEvent click = UiMouseEvent.click(UiEventType.CLICK, 50, 25, 0, 1);
        UiEventDispatcher.dispatchDirectEvent(btn, click);

        Assert.assertEquals(0, clickCount[0]);
    }

    @Test
    public void shouldEmitHoverChangedOnEnterLeave() {
        UiButton btn = createTestButton();
        final int[] enterCount = {0};
        final int[] leaveCount = {0};
        btn.hoverChanged.connect(new Signal.Value.Listener<Boolean>() {
            @Override public void accept(Boolean hovered) {
                if (hovered) enterCount[0]++;
                else leaveCount[0]++;
            }
        });

        UiMouseEvent enter = UiMouseEvent.pointer(UiEventType.MOUSE_ENTER, 50, 25);
        UiEventDispatcher.dispatchDirectEvent(btn, enter);
        Assert.assertTrue(btn.isHovered());
        Assert.assertEquals(1, enterCount[0]);

        UiMouseEvent leave = UiMouseEvent.pointer(UiEventType.MOUSE_LEAVE, 50, 25);
        UiEventDispatcher.dispatchDirectEvent(btn, leave);
        Assert.assertFalse(btn.isHovered());
        Assert.assertEquals(1, leaveCount[0]);
    }

    @Test
    public void shouldHaveClickFocusPolicy() {
        UiButton btn = new UiButton(0xFF0000FF);
        Assert.assertEquals(FocusPolicy.CLICK, btn.getFocusPolicy());
    }

    private UiButton createTestButton() {
        UIElement root = new UIElement();
        root.getLayoutStyle().display = TaffyDisplay.FLEX;
        root.getLayoutStyle().flexDirection = FlexDirection.COLUMN;
        root.getLayoutStyle().size = TaffySize.of(TaffyDimension.length(200), TaffyDimension.length(100));

        UiButton btn = new UiButton(0xFF0000FF);
        btn.setId("test-btn");
        btn.getLayoutStyle().size = TaffySize.of(TaffyDimension.length(200), TaffyDimension.length(50));
        root.addChild(btn);

        UIContainer container = UIContainer.headless(UIDocument.of(root));
        container.computeLayout(200, 100);
        return btn;
    }
}
