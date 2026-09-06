package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>Clicking a {@code .cgui} focuses its editor.</b>
 *
 * <p>The tab looked alive — the tree drew, the artboard was there — and nothing the builder binds
 * worked, because every one of its commands resolves the builder from the focused element and the
 * surface held no focus. Two separate causes, and each hid the other: the surface asked for no focus
 * policy at all, and the mode that owns a press consumed it before {@code Focus.pressed} could run.</p>
 */
public class BuilderTakesFocusOnClickTest extends UiDocumentTestBase {

    private static final String SOURCE = "{\n"
            + "  \"cgui\": 1,\n"
            + "  \"root\": { \"kind\": \"element\", \"id\": \"root\",\n"
            + "    \"children\": [ { \"kind\": \"text\", \"id\": \"title\","
            + " \"state\": { \"text\": \"mao\" } } ] }\n"
            + "}\n";

    private BuilderEditor editor;

    @Before
    public void openTheDocument() {
        UIElementRegistry.bootstrap();
        UiBuilderDocument model =
                new UiBuilderDocument(SOURCE.getBytes(StandardCharsets.UTF_8), "test:page");
        editor = new BuilderEditor(model);
        UIElement root = new UIElement().layout(l -> l.width(800).height(500));
        root.append(editor.view());
        document.append(root);
        document.update(W, H);
        frame();
    }

    /** Empty plane, outside the artboard. */
    @Test
    public void clickingThePlaneFocusesTheBuilder() {
        press(760f, 460f);
        frame();

        assertSame(editor.view(), document.focus().focused());
    }

    /**
     * <b>And clicking the artboard, which is the click a user actually makes.</b>
     *
     * <p>The builder's policy answers <em>surface</em> for everything inside the artboard — the thing
     * being designed must not react to being designed — so this is the press the mode claims, and the
     * one that used to move no focus at all.</p>
     */
    @Test
    public void clickingTheArtboardFocusesTheBuilder() {
        press(40f, 40f);
        frame();

        UIElement focused = document.focus().focused();
        assertTrue("focus is the builder, or something inside it",
                focused == editor.view() || editor.view().contains(focused));
    }
}
