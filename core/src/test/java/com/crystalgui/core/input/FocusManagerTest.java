package com.crystalgui.core.input;

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

public class FocusManagerTest {

    private UIContainer container;
    private UIElement root;
    private UIElement btnA;
    private UIElement btnB;
    private UIElement btnC;

    @Before
    public void setUp() {
        root = new UIElement();
        root.setId("root");
        root.getLayoutStyle().display = TaffyDisplay.FLEX;
        root.getLayoutStyle().flexDirection = FlexDirection.COLUMN;
        root.getLayoutStyle().size = TaffySize.of(TaffyDimension.length(200), TaffyDimension.length(150));

        btnA = new UIElement();
        btnA.setId("btnA");
        btnA.setFocusPolicy(FocusPolicy.CLICK);
        btnA.getLayoutStyle().size = TaffySize.of(TaffyDimension.length(200), TaffyDimension.length(50));

        btnB = new UIElement();
        btnB.setId("btnB");
        btnB.setFocusPolicy(FocusPolicy.CLICK);
        btnB.getLayoutStyle().size = TaffySize.of(TaffyDimension.length(200), TaffyDimension.length(50));

        btnC = new UIElement();
        btnC.setId("btnC");
        btnC.setFocusPolicy(FocusPolicy.CLICK);
        btnC.getLayoutStyle().size = TaffySize.of(TaffyDimension.length(200), TaffyDimension.length(50));

        root.addChild(btnA);
        root.addChild(btnB);
        root.addChild(btnC);

        container = UIContainer.headless(UIDocument.of(root));
        container.computeLayout(200, 150);
    }

    @Test
    public void shouldTraverseFocusableElementsInDocumentOrder() {
        FocusManager fm = container.getFocusManager();
        fm.requestFocus(btnA);
        Assert.assertSame(btnA, fm.getFocusedElement());

        fm.moveFocus(false);
        Assert.assertSame(btnB, fm.getFocusedElement());

        fm.moveFocus(false);
        Assert.assertSame(btnC, fm.getFocusedElement());

        fm.moveFocus(false);
        Assert.assertSame(btnA, fm.getFocusedElement());
    }

    @Test
    public void shouldReverseTraverseOnShiftTab() {
        FocusManager fm = container.getFocusManager();
        fm.requestFocus(btnA);

        fm.moveFocus(true);
        Assert.assertSame(btnC, fm.getFocusedElement());

        fm.moveFocus(true);
        Assert.assertSame(btnB, fm.getFocusedElement());
    }

    @Test
    public void shouldEmitFocusInAndFocusOut() {
        final List<String> events = new ArrayList<String>();
        btnA.addEventListener(UiEventType.FOCUS_IN, new UiEventListener() {
            @Override public void handle(UiEvent e) { events.add("A-in"); }
        });
        btnA.addEventListener(UiEventType.FOCUS_OUT, new UiEventListener() {
            @Override public void handle(UiEvent e) { events.add("A-out"); }
        });
        btnB.addEventListener(UiEventType.FOCUS_IN, new UiEventListener() {
            @Override public void handle(UiEvent e) { events.add("B-in"); }
        });

        FocusManager fm = container.getFocusManager();
        fm.requestFocus(btnA);
        Assert.assertTrue(events.contains("A-in"));

        fm.requestFocus(btnB);
        Assert.assertTrue(events.contains("A-out"));
        Assert.assertTrue(events.contains("B-in"));
    }

    @Test
    public void shouldClearFocusWhenFocusedElementDetaches() {
        FocusManager fm = container.getFocusManager();
        fm.requestFocus(btnB);
        Assert.assertSame(btnB, fm.getFocusedElement());

        btnB.setVisible(false);
        fm.validateFocus();
        Assert.assertNull(fm.getFocusedElement());
    }

    @Test
    public void shouldClearFocusWhenDisabled() {
        FocusManager fm = container.getFocusManager();
        fm.requestFocus(btnA);

        btnA.setEnabled(false);
        fm.validateFocus();
        Assert.assertNull(fm.getFocusedElement());
    }

    @Test
    public void shouldClearFocusWhenElementRemovedFromTree() {
        FocusManager fm = container.getFocusManager();
        fm.requestFocus(btnB);
        Assert.assertSame(btnB, fm.getFocusedElement());

        root.removeChild(btnB);
        fm.validateFocus();
        Assert.assertNull(fm.getFocusedElement());
    }

    @Test
    public void shouldSkipNonFocusableInTraversal() {
        btnB.setFocusPolicy(FocusPolicy.NONE);
        FocusManager fm = container.getFocusManager();
        fm.requestFocus(btnA);

        fm.moveFocus(false);
        Assert.assertSame(btnC, fm.getFocusedElement());
    }
}
