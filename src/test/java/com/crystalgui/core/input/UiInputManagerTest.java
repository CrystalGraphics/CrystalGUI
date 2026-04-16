package com.crystalgui.core.input;

import com.crystalgui.core.input.FocusManager;
import com.crystalgui.core.input.FocusPolicy;
import com.crystalgui.core.input.UiInputManager;
import com.crystalgui.core.event.UiEvent;
import com.crystalgui.core.event.UiEventListener;
import com.crystalgui.core.event.UiEventType;
import com.crystalgui.ui.UIContainer;
import com.crystalgui.ui.UIDocument;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyDimension;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class UiInputManagerTest {

    private UIContainer container;
    private UIElement root;
    private UIElement childA;
    private UIElement childB;

    @Before
    public void setUp() {
        root = new UIElement();
        root.setId("root");
        root.getLayoutStyle().display = TaffyDisplay.FLEX;
        root.getLayoutStyle().flexDirection = FlexDirection.COLUMN;
        root.getLayoutStyle().size = TaffySize.of(TaffyDimension.length(200), TaffyDimension.length(100));

        childA = new UIElement();
        childA.setId("childA");
        childA.getLayoutStyle().size = TaffySize.of(TaffyDimension.length(200), TaffyDimension.length(50));

        childB = new UIElement();
        childB.setId("childB");
        childB.getLayoutStyle().size = TaffySize.of(TaffyDimension.length(200), TaffyDimension.length(50));

        root.addChild(childA);
        root.addChild(childB);

        container = UIContainer.headless(UIDocument.of(root));
        container.computeLayout(200, 100);
    }

    @Test
    public void shouldSynthesizeEnterLeaveAndMoveFromSingleHitTest() {
        final List<String> events = new ArrayList<String>();
        childA.addEventListener(UiEventType.MOUSE_ENTER, new UiEventListener() {
            @Override public void handle(UiEvent e) { events.add("A-enter"); }
        });
        childA.addEventListener(UiEventType.MOUSE_LEAVE, new UiEventListener() {
            @Override public void handle(UiEvent e) { events.add("A-leave"); }
        });
        childB.addEventListener(UiEventType.MOUSE_ENTER, new UiEventListener() {
            @Override public void handle(UiEvent e) { events.add("B-enter"); }
        });

        UiInputManager mgr = container.getInputManager();
        mgr.processMouseMove(100, 25, 0);
        Assert.assertTrue(events.contains("A-enter"));

        mgr.processMouseMove(100, 75, 0);
        Assert.assertTrue(events.contains("A-leave"));
        Assert.assertTrue(events.contains("B-enter"));
    }

    @Test
    public void shouldSynthesizeClickOnMatchingPressRelease() {
        final List<String> events = new ArrayList<String>();
        childA.addEventListener(UiEventType.CLICK, new UiEventListener() {
            @Override public void handle(UiEvent e) { events.add("click"); }
        });

        UiInputManager mgr = container.getInputManager();
        mgr.processMouseDown(100, 25, 0, 0);
        mgr.processMouseUp(100, 25, 0, 0);
        Assert.assertEquals(1, events.size());
        Assert.assertEquals("click", events.get(0));
    }

    @Test
    public void shouldFocusNearestFocusableAncestorOnMouseDown() {
        childA.setFocusPolicy(FocusPolicy.CLICK);
        UiInputManager mgr = container.getInputManager();
        FocusManager fm = container.getFocusManager();

        mgr.processMouseDown(100, 25, 0, 0);
        Assert.assertSame(childA, fm.getFocusedElement());
    }

    @Test
    public void shouldNotFocusNonFocusableOnMouseDown() {
        UiInputManager mgr = container.getInputManager();
        FocusManager fm = container.getFocusManager();

        mgr.processMouseDown(100, 25, 0, 0);
        Assert.assertNull(fm.getFocusedElement());
    }

    @Test
    public void shouldResetClickCountAfterDoubleClick() {
        final List<String> events = new ArrayList<String>();
        childA.addEventListener(UiEventType.CLICK, new UiEventListener() {
            @Override public void handle(UiEvent e) { events.add("click"); }
        });
        childA.addEventListener(UiEventType.DOUBLE_CLICK, new UiEventListener() {
            @Override public void handle(UiEvent e) { events.add("dblclick"); }
        });

        UiInputManager mgr = container.getInputManager();
        mgr.processMouseDown(100, 25, 0, 0);
        mgr.processMouseUp(100, 25, 0, 0);
        mgr.processMouseDown(100, 25, 0, 0);
        mgr.processMouseUp(100, 25, 0, 0);

        Assert.assertTrue(events.contains("dblclick"));

        events.clear();
        mgr.processMouseDown(100, 25, 0, 0);
        mgr.processMouseUp(100, 25, 0, 0);

        Assert.assertTrue(events.contains("click"));
        Assert.assertFalse(events.contains("dblclick"));
    }
}
