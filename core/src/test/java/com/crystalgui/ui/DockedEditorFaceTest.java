package com.crystalgui.ui;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiQuad;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.visual.OverflowClip;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.theme.ThemeRegistry;
import com.crystalgui.style.theme.UiThemeManager;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.Workbench;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A docked file editor's own face — the two properties that make it the island's face rather than a
 * rectangle sitting on one.
 *
 * <h3>Why this is asserted rather than looked at</h3>
 *
 * <p>Both halves fail <em>silently, and in opposite directions</em>. Drop the radius and nothing throws:
 * the editor squares off the panel beneath it, and only in a scheme whose paper differs from the frame,
 * so four of the five still look right. Keep the radius without the engine rule beneath it and the
 * other four go — a radius on a clipping element promotes it to {@link OverflowClip#MASK}, whose
 * default mask is the element's own <em>background</em>, which those four deliberately leave
 * transparent. A zero-alpha mask multiplies every child away, so the editor renders <b>blank</b> in
 * exactly the configurations the radius was not needed for.</p>
 */
public class DockedEditorFaceTest extends UiTestBase {

    private static final float RADIUS = 8f;

    private UIWindow window;

    @After
    public void tearDown() {
        UiThemeManager.getInstance().resetForTesting();
        ThemeRegistry.resetForTesting();
    }

    /**
     * A docked editor under the user-agent sheet, with the two tokens the rule reads supplied by a
     * throwaway theme.
     *
     * <p>Through {@link UiThemeManager} rather than by adding a sheet of {@code *} declarations, because
     * a token is <b>bound into a sheet</b> and not cascaded down the tree — a rule elsewhere defining
     * {@code --radius-panel} never reaches the {@code var()} in this one, and the first draft of this
     * fixture read a radius of zero for exactly that reason.</p>
     *
     * <p>One source carries both a theme's token and a scheme's, which the split forbids in shipped
     * files and is immaterial here: the merge is one table, and what is under test is the rule reading
     * from it.</p>
     */
    private TextEditor dockedEditor(String editorPaper) {
        ThemeRegistry.resetForTesting();
        UiThemeManager.getInstance().resetForTesting();
        ThemeRegistry.registerSource(""
                + "/* @theme Docked Editor Probe\n"
                + " * @id    test:docked-editor\n"
                + " * @kind  dark */\n"
                + "theme {\n"
                + "    --radius-panel: " + RADIUS + "px;\n"
                + "    --editor-bg: " + editorPaper + ";\n"
                + "}\n");

        TextEditor editor = new TextEditor("one\ntwo\n");
        editor.addClass(Workbench.FILE_EDITOR_CLASS);

        UIElement root = new UIElement().layout(l -> l.width(400).height(300));
        root.addChild(editor);

        window = new UIWindow(Ui.of(root));
        UiThemeManager.getInstance().installInto(window.getStyleEngine());
        UiThemeManager.getInstance().setTheme("test:docked-editor");
        window.init(400, 300);
        window.updateWithoutPainting();
        window.updateWithoutPainting();
        return editor;
    }

    /** Cast because {@code resolveOverflowClip} is package-private on {@link UIElement} and TextEditor
     * is not in this package — the visibility is right, the call site just has to say so. */
    private static OverflowClip clipOf(UIElement element) {
        return element.resolveOverflowClip();
    }

    private static float radiusOf(UIElement element, StyleProperty<LengthPercent> corner) {
        LengthPercent radius = element.getStyle().getComputed(corner);
        return radius == null ? 0f : radius.value;
    }

    /**
     * <b>The island's bottom corners are the editor's.</b> The top two stay square — the tab strip is
     * above, so those belong to it.
     */
    @Test
    public void aDockedEditorRoundsItsBottomCornersToTheIsland() {
        TextEditor editor = dockedEditor("#00000000");

        assertEquals("the bottom-left corner is the panel's",
                RADIUS, radiusOf(editor, BorderRadiusProperties.BOTTOM_LEFT_X), 0.01f);
        assertEquals("the bottom-right corner is the panel's",
                RADIUS, radiusOf(editor, BorderRadiusProperties.BOTTOM_RIGHT_X), 0.01f);
        assertEquals("the strip owns the top-left corner, not the document",
                0f, radiusOf(editor, BorderRadiusProperties.TOP_LEFT_X), 0.01f);
        assertEquals("the strip owns the top-right corner, not the document",
                0f, radiusOf(editor, BorderRadiusProperties.TOP_RIGHT_X), 0.01f);
    }

    /**
     * <b>And the mask that rounding costs must not erase the document.</b> This is the assertion that
     * catches a blank editor: with a transparent {@code --editor-bg} — what four of the five shipped
     * schemes set, deliberately — the mask inherited from the paper has zero alpha everywhere.
     *
     * <p>Asserted on {@code revealsNothing}, which is the exact question {@code paintDefaultMaskShape}
     * asks, rather than on a rendered frame: the failure is invisible until something paints, and then
     * it is total.</p>
     */
    @Test
    public void aTransparentPaperStillMasksToTheWholeShape() {
        TextEditor editor = dockedEditor("#00000000");

        assertTrue("a rounded clipper must be on the mask path, or the corner never reaches its children",
                clipOf(editor).isMask());
        assertTrue("the fixture must actually be the transparent-paper case",
                UIElement.revealsNothing(editor.getStyle().getGeneralGroup().background()));
    }

    /**
     * The rule itself, stated once. {@code background: none} and {@code background: #00000000} are the
     * same thing wearing different clothes — the drawable parser says so — and they used to mask
     * <b>oppositely</b>, because only the shared EMPTY instance took the "nothing to fill with" branch.
     */
    @Test
    public void anAuthoredTransparentFillMasksAsNoFillDoes() {
        assertTrue("no background at all reveals the whole shape",
                UIElement.revealsNothing(CgUiDrawable.EMPTY));
        assertTrue("and so must an authored transparent one — a fresh quad, not the shared instance",
                UIElement.revealsNothing(new CgUiQuad(0x00000000)));
        assertFalse("a paper with a colour masks with itself, which is what a 9-slice's holes rely on",
                UIElement.revealsNothing(new CgUiQuad(0xFF191A1C)));
    }

    /** The same widget outside a dock keeps square corners and the cheap scissor clip. */
    @Test
    public void anUndockedEditorIsUntouched() {
        TextEditor editor = dockedEditor("#00000000");
        editor.removeClass(Workbench.FILE_EDITOR_CLASS);
        window.updateWithoutPainting();

        assertEquals("only a docked editor pays for a corner the others do not have",
                OverflowClip.SCISSOR, clipOf(editor));
    }
}
