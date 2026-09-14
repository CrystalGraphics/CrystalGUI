package com.crystalgui.app.uibuilder.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.KindInfo;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.Preview;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.text.UIText;

/** The B.4 probe: a sample in a card wears the document's rules and not the panel's, scales to fit, and is inert. */
public class PreviewCardTest extends UiDocumentTestBase {

    private UIElement panel;

    @Before
    public void panel() {
        UIElementRegistry.bootstrap();
        withDefaultStyles();
        panel = new UIElement().addClass("library-probe");
        document.append(panel);
    }

    @Test
    public void aSampleWearsADocumentRuleAndNotAPanelRule() {
        document.styles().addStylesheet(StyleSheet.parse("text { color: #112233; }"));
        document.styles().addStylesheet(StyleSheet.parse(".library-probe text { color: #445566; }"));
        UIText outside = new UIText("light");
        panel.append(outside);
        PreviewCard card = card(entry("text", Preview.sample(() -> new UIText("Text"))));
        settle();

        assertEquals("the panel rule reaches the panel's own text", 0xFF445566,
                (int) outside.getStyle().computed().get(StylePropertyRegistry.COLOR));
        assertEquals("the sample wears the document rule and the panel rule stops at the card", 0xFF112233,
                (int) card.sample().getStyle().computed().get(StylePropertyRegistry.COLOR));
    }

    @Test
    public void aSampleLargerThanTheStageIsScaledDownToFit() {
        PreviewCard card = card(entry("wide", Preview.sample(() -> sized("wide", 400f, 20f))));
        settle();

        assertTrue("scaled below 1, was " + card.scale(), card.scale() < 1f);
        assertFalse(card.isPlaceholder());
    }

    @Test
    public void aSampleThatLaysOutToNothingIsAPlaceholder() {
        PreviewCard card = card(entry("empty", Preview.sample(UIElement::new)));
        settle();

        assertTrue(card.isPlaceholder());
    }

    @Test
    public void theSampleIsInertAndThePointerLandsOnTheCard() {
        PreviewCard card = card(entry("button", Preview.sample(() -> sized("inner", 40f, 20f))));
        settle();

        int[] centre = centreOf(card);
        assertSame(card, hitTarget(centre[0], centre[1]));
        assertNotNull(card.sample());
    }

    /** The B.8 probe. */
    @Test
    public void anOverlaySampleDrawsOpenInsideItsCardWithoutPromoting() {
        Popover[] built = new Popover[1];
        PreviewCard card = card(entry("popover", Preview.sample(() -> {
            built[0] = new Popover();
            built[0].append(sized("inside", 40f, 20f));
            return built[0].presentInline();
        })));
        settle();

        assertFalse(card.isPlaceholder());
        assertFalse("the sample promoted itself out of its card", document.isPromoted(built[0]));
        assertNotNull("the open popover has no box in the card", built[0].box());
    }

    private PreviewCard card(LibraryCatalog.Entry entry) {
        PreviewCard card = new PreviewCard(PreviewStyles.of(document).group());
        card.show(entry);
        panel.append(card);
        return card;
    }

    private void settle() {
        PreviewStyles.of(document).sync();
        frame();
        frame();
    }

    private static LibraryCatalog.Entry entry(String local, Preview preview) {
        return new LibraryCatalog.Entry(Name.of("testmod", local), local, KindInfo.derived().preview(preview));
    }
}
