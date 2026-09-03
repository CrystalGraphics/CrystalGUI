package com.crystalgui.ui.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.text.UIText;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import org.junit.Test;

/**
 * Text measured INSIDE the layout pass: a {@link UIText} under {@code flex-wrap: wrap} is asked
 * for its height at the width it was actually given, and the box comes out that tall — in one
 * pass, with nothing written back into the cascade (5.3, D5.10).
 *
 * <p>The old {@code MeasureFuncUnderFlexWrapTest} pinned the fork's fix at the element level: a
 * measured leaf in a wrapping row was told its container's width (200) where {@code nowrap} told
 * it its own (100). This is the same shape over the box tree, which has no other path to a
 * text's height.</p>
 */
public class MeasureThroughTaffyTest extends UiDocumentTestBase {

    private static final String TEXT = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda";

    private static UIText growing(String text) {
        UIText node = new UIText(text);
        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), l -> {
            l.flexGrow(1);
            l.flexBasis(0);
            l.flexShrink(1);
        });
        return node;
    }

    private static UIDocument rowOfTwo(FlexWrap wrap, UIText[] out) {
        UIDocument document = new UIDocument();
        UINode row = new UINode();
        StyleGroup.inlinePipeline(row.getStyle().getLayoutGroup(), l -> {
            l.width(200);
            l.flexDirection(FlexDirection.ROW);
            l.flexWrap(wrap);
        });
        UIText a = growing(TEXT);
        UIText b = growing(TEXT);
        row.append(a).append(b);
        document.append(row);
        document.update(800, 800);
        out[0] = a;
        out[1] = b;
        return document;
    }

    @Test
    public void aTextNodeIsMeasuredAtItsUsedWidthUnderWrap() {
        UIText[] wrapped = new UIText[2];
        UIDocument document = rowOfTwo(FlexWrap.WRAP, wrapped);
        UIText[] plain = new UIText[2];
        rowOfTwo(FlexWrap.NO_WRAP, plain);

        Box box = wrapped[0].box();
        assertNotNull(box);
        assertEquals("half the row", 100f, box.width(), 0.01f);
        assertTrue("it was asked to measure", !wrapped[0].measuredAt().isEmpty());
        float last = wrapped[0].measuredAt().get(wrapped[0].measuredAt().size() - 1);
        assertEquals("the final measure is at the width it was GIVEN, not the container's", 100f, last, 0.01f);

        Box plainBox = plain[0].box();
        assertNotNull(plainBox);
        assertEquals("wrap and nowrap agree on the height of a single-line-of-items row",
                plainBox.height(), box.height(), 0.01f);
        assertEquals("one pass -- nothing fed back", 1, document.boxes().layoutPasses());
    }

    @Test
    public void theBoxIsAsTallAsTheTextWrappedAtThatWidth() {
        UIText[] nodes = new UIText[2];
        rowOfTwo(FlexWrap.WRAP, nodes);
        UIText node = nodes[0];
        Box box = node.box();
        assertNotNull(box);

        CgFontFamily family = FontFamilyCache.resolve(
                node.getStyle().getGeneralGroup().fontFamily(),
                Math.round(node.getStyle().getGeneralGroup().fontSize()));
        CgTextLayout at100 = CgTextLayout.of(TEXT, family).shape().layout(100f, 0f);
        CgTextLayout unbounded = CgTextLayout.of(TEXT, family).shape().layout(0f, 0f);
        assertTrue("the fixture wraps: several lines at 100px", at100.totalHeight() > unbounded.totalHeight() * 2);
        assertEquals(at100.totalHeight(), box.height(), 0.01f);
    }

    @Test
    public void changingTheTextIsOneMorePassAndNoWalk() {
        UIText[] nodes = new UIText[2];
        UIDocument document = rowOfTwo(FlexWrap.WRAP, nodes);
        float before = nodes[0].box().height();
        int syncs = document.boxes().syncPasses();

        // Both, or the row stays as tall as the one that did not change and stretches the other to it.
        nodes[0].setText("short");
        nodes[1].setText("short");
        document.update(800, 800);
        assertEquals(2, document.boxes().layoutPasses());
        assertEquals("the structure did not move", syncs, document.boxes().syncPasses());
        assertTrue("and the box followed the text", nodes[0].box().height() < before);
    }
}
