package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.cursor.Cursor;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.Popover;

/** A window of an application with its own document, with the document really receiving what the shell forwards. */
public class ApplicationInputEndToEndTest extends UiDocumentTestBase {

    /** What a viewport does on the document's thread: hands each event to the document's input. */
    private static final class Live implements AppDocument.Backend {
        UIDocument own;

        @Override
        public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
            return own.input().consumeMouseEvent(event);
        }

        @Override
        public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
            return own.input().consumeKeyboardEvent(event);
        }

        @Override
        public boolean consumeComposition(String text, int caret) {
            composed.add(text);
            return own.input().consumeComposition(text, caret);
        }

        final java.util.List<String> composed = new java.util.ArrayList<>();

        @Override
        public void close() {
        }
    }

    private static final ApplicationKind KIND = ApplicationKind.of("test:live", "Live").standalone().separateDocument();

    private boolean animationsWere;
    private UIDocument own;
    private AppDocument application;
    private AppWindow window;
    private TextField field;
    private Live live;

    @Before
    public void open() {
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
        withDefaultStyles();
        document.boxes().setUiScale(2f);
        Desktop desktop = Desktop.of(document);
        own = new UIDocument();
        own.boxes().setUiScale(2f);
        own.styles().addStylesheet(StyleSheet.parse(ClientRoot.SHEET + PopupLayer.SHEET));
        own.useTopLayer(new PopupLayer());
        live = new Live();
        live.own = own;
        application = new AppDocument(own, live);
        field = new TextField();
        field.layout(l -> l.widthPercent(100f).heightPercent(100f));
        window = AppWindows.open(desktop, KIND, application, WindowSpec.titled("Live").content(field).size(300, 200));
        settle();
    }

    @After
    public void restore() {
        WindowAnimator.setEnabled(animationsWere);
    }

    /** Frames both documents as the host does: the application's at its window's size, then the shell's. */
    private void settle() {
        for (int i = 0; i < 6; i++) {
            ClientPortal portal = application.active();
            if (portal != null && portal.box() != null) portal.paintContent(null, portal.box());
            float w = application.windowWidth(), h = application.windowHeight();
            if (w > 0f) own.frame(0.016f, (int) w, (int) h);
            else own.frame(0.016f, 800, 600);
            application.committed();
            frame();
        }
    }

    private float[] fieldInShell() {
        Box inClient = field.box();
        Box portal = application.active().box();
        float scale = document.boxes().uiScale();
        return new float[] {portal.worldX() + inClient.worldX() + inClient.width() * scale / 2f,
                portal.worldY() + inClient.worldY() + inClient.height() * scale / 2f};
    }

    @Test
    public void aClickOnAFieldFocusesItAndTypingEntersText() {
        float[] at = fieldInShell();
        click(at[0], at[1]);
        settle();
        assertSame("the application's focus is the field", field, own.focus().focused());
        document.input().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event('a', CgKeyCodes.KEY_A, true, false, 1L));
        settle();
        assertEquals("a", field.getText());
    }

    @Test
    public void overATextFieldTheShellShowsTheApplicationsCursor() {
        float[] at = fieldInShell();
        move(at[0], at[1]);
        settle();
        assertEquals("the application's document says text", Cursor.TEXT, own.input().currentCursor());
        assertEquals("and the shell shows it over the window", Cursor.TEXT, document.input().currentCursor());
        move(5f, 5f);
        settle();
        assertEquals("off the window it is the shell's own", Cursor.DEFAULT, document.input().currentCursor());
    }

    @Test
    public void anInputMethodSeesTheApplicationsCaretWhereItIsOnTheScreen() {
        assertNull("no caret before anything is focused", document.input().textInputArea());
        // A text field knows its caret once it has painted, which a test does not; this stands in for one.
        UIElement caret = new UIElement() {
            @Override
            public float[] textInputArea() {
                return new float[] {10f, 20f, 1f, 12f};
            }
        };
        caret.setFocusPolicy(FocusPolicy.CLICK);
        own.append(caret);
        own.focus().requestFocus(caret);
        float[] at = fieldInShell();
        click(at[0], at[1]);                            // the shell's focus goes to the window
        own.focus().requestFocus(caret);
        settle();
        float[] area = document.input().textInputArea();
        assertNotNull("the application's caret is the shell's to give", area);
        Box portal = application.active().box();
        assertEquals("across: the window's corner plus the caret's", portal.worldX() + 10f, area[0], 0.5f);
        assertEquals("down", portal.worldY() + 20f, area[1], 0.5f);
        assertEquals("as tall as the caret", 12f, area[3], 0.5f);
    }

    @Test
    public void aRunAnInputMethodIsComposingGoesToTheApplicationsField() {
        float[] at = fieldInShell();
        click(at[0], at[1]);
        settle();
        assertTrue("taken", document.input().consumeComposition("ni", 2));
        assertEquals(java.util.List.of("ni"), live.composed);
    }

    @Test
    public void refocusingAfterAClickElsewhereStillTypes() {
        float[] at = fieldInShell();
        click(at[0], at[1]);
        settle();
        click(5f, 5f);                                  // on the desktop: the field is no longer focused
        settle();
        click(at[0], at[1]);
        settle();
        assertSame("the field again", field, own.focus().focused());
        document.input().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event('b', CgKeyCodes.KEY_B, true, false, 2L));
        settle();
        assertEquals("b", field.getText());
    }

    @Test
    public void aButtonInAPopupIsPressedAndKeysStillGoToTheApplication() {
        Button item = new Button("Item");
        int[] pressed = {0};
        item.onPressed.connect(() -> pressed[0]++);
        Popover menu = new Popover();
        menu.append(item);
        own.append(menu);
        menu.showAt(30f, 40f, null);
        settle();
        Box box = item.box();
        float scale = document.boxes().uiScale();
        Box portal = application.active().box();
        float x = portal.worldX() + box.worldX() + box.width() * scale / 2f;
        float y = portal.worldY() + box.worldY() + box.height() * scale / 2f;
        click(x, y);
        settle();
        assertEquals("the item took the press", 1, pressed[0]);
        assertSame("the shell's focus stayed on the window", application.active(), document.focus().focused());
    }

    @Test
    public void draggingAcrossATextFieldSelectsItsText() {
        field.setText("hello world");
        settle();
        float[] at = fieldInShell();
        Box inClient = field.box();
        float left = application.active().box().worldX() + inClient.worldX() + 6f;
        press(left, at[1]);
        settle();
        move(at[0], at[1]);
        settle();
        release(at[0], at[1]);
        settle();
        assertEquals("a selection was made", true, field.getSelectionEnd() > field.getSelectionStart());
    }
}
