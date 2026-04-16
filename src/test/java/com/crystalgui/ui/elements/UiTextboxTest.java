package com.crystalgui.ui.elements;

import com.crystalgui.core.event.CgUiKeyCodes;
import com.crystalgui.core.event.Modifiers;
import com.crystalgui.core.event.UiEventDispatcher;
import com.crystalgui.core.event.UiEventType;
import com.crystalgui.core.event.UiKeyEvent;
import com.crystalgui.core.event.UiMouseEvent;
import com.crystalgui.core.input.FocusManager;
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

import java.util.ArrayList;
import java.util.List;

public class UiTextboxTest {

    @Test
    public void shouldHaveClickFocusPolicy() {
        UiTextbox tb = new UiTextbox(0x000000FF, 0xFFFFFFFF, 0x4488FFFF);
        Assert.assertEquals(FocusPolicy.CLICK, tb.getFocusPolicy());
    }

    @Test
    public void shouldStartWithEmptyText() {
        UiTextbox tb = new UiTextbox(0x000000FF, 0xFFFFFFFF, 0x4488FFFF);
        Assert.assertEquals("", tb.getText());
        Assert.assertEquals(0, tb.getCaretPosition());
    }

    @Test
    public void shouldAcceptTypedCharacters() {
        TestHarness h = createHarness();
        focusTextbox(h);

        typeChar(h, 'H');
        typeChar(h, 'i');

        Assert.assertEquals("Hi", h.textbox.getText());
        Assert.assertEquals(2, h.textbox.getCaretPosition());
    }

    @Test
    public void shouldEmitTextChangedSignal() {
        TestHarness h = createHarness();
        focusTextbox(h);

        final List<String> changes = new ArrayList<>();
        h.textbox.textChanged.connect(value -> changes.add(value));

        typeChar(h, 'A');
        typeChar(h, 'B');

        Assert.assertEquals(2, changes.size());
        Assert.assertEquals("A", changes.get(0));
        Assert.assertEquals("AB", changes.get(1));
    }

    @Test
    public void shouldHandleBackspace() {
        TestHarness h = createHarness();
        focusTextbox(h);

        typeChar(h, 'A');
        typeChar(h, 'B');
        typeChar(h, 'C');
        pressKey(h, CgUiKeyCodes.KEY_BACKSPACE);

        Assert.assertEquals("AB", h.textbox.getText());
        Assert.assertEquals(2, h.textbox.getCaretPosition());
    }

    @Test
    public void shouldHandleDelete() {
        TestHarness h = createHarness();
        focusTextbox(h);

        typeChar(h, 'X');
        typeChar(h, 'Y');
        typeChar(h, 'Z');

        // Move caret to position 1 (after 'X')
        pressKey(h, CgUiKeyCodes.KEY_HOME);
        pressKey(h, CgUiKeyCodes.KEY_RIGHT);
        Assert.assertEquals(1, h.textbox.getCaretPosition());

        pressKey(h, CgUiKeyCodes.KEY_DELETE);
        Assert.assertEquals("XZ", h.textbox.getText());
        Assert.assertEquals(1, h.textbox.getCaretPosition());
    }

    @Test
    public void shouldHandleCaretMovement() {
        TestHarness h = createHarness();
        focusTextbox(h);

        typeChar(h, 'A');
        typeChar(h, 'B');
        typeChar(h, 'C');
        Assert.assertEquals(3, h.textbox.getCaretPosition());

        pressKey(h, CgUiKeyCodes.KEY_LEFT);
        Assert.assertEquals(2, h.textbox.getCaretPosition());

        pressKey(h, CgUiKeyCodes.KEY_HOME);
        Assert.assertEquals(0, h.textbox.getCaretPosition());

        pressKey(h, CgUiKeyCodes.KEY_END);
        Assert.assertEquals(3, h.textbox.getCaretPosition());

        pressKey(h, CgUiKeyCodes.KEY_RIGHT);
        // Already at end, should stay
        Assert.assertEquals(3, h.textbox.getCaretPosition());
    }

    @Test
    public void shouldEmitSubmittedOnEnter() {
        TestHarness h = createHarness();
        focusTextbox(h);

        typeChar(h, 'O');
        typeChar(h, 'K');

        final int[] submitCount = {0};
        h.textbox.submitted.connect(new Runnable() {
            @Override
            public void run() {
                submitCount[0]++;
            }
        });

        pressKey(h, CgUiKeyCodes.KEY_ENTER);
        Assert.assertEquals(1, submitCount[0]);
    }

    @Test
    public void shouldInsertAtCaretPosition() {
        TestHarness h = createHarness();
        focusTextbox(h);

        typeChar(h, 'A');
        typeChar(h, 'C');
        pressKey(h, CgUiKeyCodes.KEY_LEFT); // caret between A and C
        typeChar(h, 'B');

        Assert.assertEquals("ABC", h.textbox.getText());
        Assert.assertEquals(2, h.textbox.getCaretPosition());
    }

    @Test
    public void shouldIgnoreControlCharacters() {
        TestHarness h = createHarness();
        focusTextbox(h);

        typeChar(h, 'A');
        // Inject KEY_TYPED with control character
        UiKeyEvent typed = UiKeyEvent.typed('\t', 0);
        h.focus.dispatchKeyEvent(typed);

        Assert.assertEquals("A", h.textbox.getText());
    }

    @Test
    public void shouldSetTextProgrammatically() {
        UiTextbox tb = new UiTextbox(0x000000FF, 0xFFFFFFFF, 0x4488FFFF);
        tb.setText("Hello");
        Assert.assertEquals("Hello", tb.getText());
        Assert.assertEquals(0, tb.getCaretPosition()); // caret stays within bounds
    }

    @Test
    public void shouldClampCaretOnSetText() {
        TestHarness h = createHarness();
        focusTextbox(h);

        typeChar(h, 'A');
        typeChar(h, 'B');
        typeChar(h, 'C');
        Assert.assertEquals(3, h.textbox.getCaretPosition());

        h.textbox.setText("X");
        Assert.assertEquals(1, h.textbox.getCaretPosition());
    }

    @Test
    public void shouldBackspaceDoNothingAtStart() {
        TestHarness h = createHarness();
        focusTextbox(h);

        typeChar(h, 'A');
        pressKey(h, CgUiKeyCodes.KEY_HOME);
        pressKey(h, CgUiKeyCodes.KEY_BACKSPACE);

        Assert.assertEquals("A", h.textbox.getText());
        Assert.assertEquals(0, h.textbox.getCaretPosition());
    }

    @Test
    public void shouldDeleteDoNothingAtEnd() {
        TestHarness h = createHarness();
        focusTextbox(h);

        typeChar(h, 'A');
        pressKey(h, CgUiKeyCodes.KEY_DELETE);

        Assert.assertEquals("A", h.textbox.getText());
        Assert.assertEquals(1, h.textbox.getCaretPosition());
    }

    @Test
    public void shouldHandleCtrlA() {
        TestHarness h = createHarness();
        focusTextbox(h);

        typeChar(h, 'H');
        typeChar(h, 'i');
        pressKey(h, CgUiKeyCodes.KEY_HOME);
        Assert.assertEquals(0, h.textbox.getCaretPosition());

        // Ctrl+A moves caret to end (select all behavior)
        UiKeyEvent ctrlA = UiKeyEvent.key(UiEventType.KEY_DOWN, CgUiKeyCodes.KEY_A, Modifiers.CTRL);
        h.focus.dispatchKeyEvent(ctrlA);
        Assert.assertEquals(2, h.textbox.getCaretPosition());
    }

    // ── Test infrastructure ──

    private static class TestHarness {
        UIContainer container;
        UiTextbox textbox;
        FocusManager focus;
    }

    private TestHarness createHarness() {
        UIElement root = new UIElement();
        root.getLayoutStyle().display = TaffyDisplay.FLEX;
        root.getLayoutStyle().flexDirection = FlexDirection.COLUMN;
        root.getLayoutStyle().size = TaffySize.of(TaffyDimension.length(400), TaffyDimension.length(200));

        UiTextbox textbox = new UiTextbox(0x000000FF, 0xFFFFFFFF, 0x4488FFFF);
        textbox.setId("textbox");
        textbox.getLayoutStyle().size = TaffySize.of(TaffyDimension.length(400), TaffyDimension.length(30));
        root.addChild(textbox);

        UIContainer container = UIContainer.headless(UIDocument.of(root));
        container.computeLayout(400, 200);

        TestHarness h = new TestHarness();
        h.container = container;
        h.textbox = textbox;
        h.focus = container.getFocusManager();
        return h;
    }

    private void focusTextbox(TestHarness h) {
        h.focus.requestFocus(h.textbox);
    }

    private void typeChar(TestHarness h, char c) {
        // Simulate KEY_DOWN then KEY_TYPED as a real keyboard would
        UiKeyEvent keyDown = UiKeyEvent.key(UiEventType.KEY_DOWN, 0, 0);
        h.focus.dispatchKeyEvent(keyDown);
        UiKeyEvent typed = UiKeyEvent.typed(c, 0);
        h.focus.dispatchKeyEvent(typed);
    }

    private void pressKey(TestHarness h, int keyCode) {
        UiKeyEvent keyDown = UiKeyEvent.key(UiEventType.KEY_DOWN, keyCode, 0);
        h.focus.dispatchKeyEvent(keyDown);
    }
}
