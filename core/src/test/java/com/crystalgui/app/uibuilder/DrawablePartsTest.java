package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.attributes.StyleAttributes;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.attribute.AttributeSet;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.texture.TextureValue;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.render.texture.CgUiRect;

/**
 * <b>A drawable is filed under the function that produced it.</b>
 *
 * <p>Four properties share one Java type and nothing else: a gradient, a pane of glass and a nine-slice
 * sprite are three things a designer thinks about separately. Filing them all under "Appearance" asks
 * somebody to tick a box called {@code background} without saying what is in it.</p>
 *
 * <p>This is also the test that a drawable can be copied AT ALL. It could not before: the value had no
 * codec, so copying any element carrying one threw rather than skipping it.</p>
 */
public class DrawablePartsTest extends UiDocumentTestBase {

    private UiBuilderDocument model;
    private UIElement source;
    private UIElement target;

    @Before
    public void twoElements() {
        UIElementRegistry.bootstrap();
        model = new UiBuilderDocument(
                UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:a.cgui");
        source = new UIElement().layout(l -> l.width(120f).height(40f));
        target = new UIElement().layout(l -> l.width(60f).height(20f));
        model.root().append(source, target);
        document.append(model.root());
        document.update(W, H);
    }

    private static void background(UIElement node, String css) {
        CgUiDrawable drawable = new TextureValue(css).compute();
        StyleGroup.inlinePipeline(node.getStyle().getGeneralGroup(), g -> g.background(drawable));
    }

    private static List<String> idsOf(AttributeSet set) {
        List<String> ids = new ArrayList<>();
        for (AttributeSet.Entry entry : set.entries()) ids.add(entry.slot().id());
        return ids;
    }

    /** The section is the function; the row is the property that holds it. */
    @Test
    public void aGradientIsFiledUnderGradient() {
        background(source, "linear-gradient(to bottom, #FF0000FF, #0000FFFF)");

        AttributeSet copied = new StyleAttributes(source).copyAttributes();

        assertTrue("copied at all: " + idsOf(copied), idsOf(copied).contains("background/gradient"));
        assertTrue("and under its own heading: " + copied.groups(),
                copied.groups().contains("Gradient"));
        for (AttributeSet.Entry entry : copied.entries()) {
            if (!entry.slot().id().equals("background/gradient")) continue;
            assertEquals("the row is named after the property, since the section names the kind",
                    "background", entry.slot().label());
        }
    }

    /** A different function, a different section — that is the whole point of grouping by it. */
    @Test
    public void anIconIsFiledUnderIcon() {
        background(source, "icon(\"crystalgui:x\")");

        AttributeSet copied = new StyleAttributes(source).copyAttributes();

        assertTrue(idsOf(copied).toString(), idsOf(copied).contains("background/icon"));
        assertTrue(copied.groups().toString(), copied.groups().contains("Icon"));
    }

    /** And it lands, which is what the round trip through CSS text is for. */
    @Test
    public void pastingADrawableReproducesIt() {
        background(source, "linear-gradient(to bottom, #FF0000FF, #0000FFFF)");
        document.update(W, H);

        AttributeSet copied = new StyleAttributes(source).copyAttributes();
        assertTrue(new StyleAttributes(target).applyAsEdit(model,
                copied.keeping(slot -> slot.id().startsWith("background"))));
        document.update(W, H);

        CgUiDrawable landed = target.getStyle().computed().get(StylePropertyRegistry.BACKGROUND);
        assertEquals("the same CSS arrives on the other side",
                "linear-gradient(to bottom, #FF0000FF, #0000FFFF)", TextureValue.sourceOf(landed));
    }

    /**
     * <b>A flat colour built in Java still has a CSS spelling</b>, so it copies and files like any other.
     *
     * <p>It never went through a stylesheet, so nothing remembered a source for it — but a colour can
     * always say what it is, and {@code TextureProperty} writes one as {@code #RRGGBBAA}. That is the
     * whole difference between a drawable that can be written and one that cannot: a sprite or an SVG
     * holds resolved data and would have to guess, and refuses instead.</p>
     */
    @Test
    public void aColourBuiltInJavaStillCopies() {
        StyleGroup.inlinePipeline(source.getStyle().getGeneralGroup(),
                g -> g.background(CgUiRect.ofColor(0xFF00FF00)));

        AttributeSet copied = new StyleAttributes(source).copyAttributes();

        assertTrue("copyable at all, which it was not before: " + idsOf(copied),
                idsOf(copied).contains("background/colour"));
        assertTrue("and filed as the colour it is: " + copied.groups(),
                copied.groups().contains("Colour"));
    }
}
