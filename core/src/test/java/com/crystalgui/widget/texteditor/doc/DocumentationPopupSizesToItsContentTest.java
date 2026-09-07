package com.crystalgui.widget.texteditor.doc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.text.UIText;

/**
 * <b>The documentation popup is a box beside its anchor, not a band across the window.</b>
 *
 * <p>Its width is chosen by its content, and the sheet reasons that this is safe because the engine
 * breaks a long DECLARATION across lines, so the widest line is short by construction. That holds for
 * the declaration and not for the documentation BODY: one prose string with no break the layout has to
 * honour, whose max-content width is the whole paragraph on a single line. An auto-width box takes the
 * space that asks for, so the popup opened as wide as the window — and {@code left} then clamped to
 * zero, because a box that wide no longer fits beside the symbol it describes.</p>
 *
 * <p>Sizes, not pixels. {@link DocumentationPopupTest} deliberately asserts nothing about geometry
 * because a pixel breaks on any legitimate restyle; what is asserted here is that the box is bounded and
 * that a reader who drags it is not, which are structural and survive a restyle.</p>
 */
public class DocumentationPopupSizesToItsContentTest extends UiDocumentTestBase {

    private static final float VIEWPORT_W = 1200f;
    private static final float VIEWPORT_H = 800f;

    /** The sheet's own bounds, which are the ramp's endpoints. @see DocumentationPopup */
    private static final float FLOOR = 250f;
    private static final float CEILING = 350f;

    /** `padding-all: 9px` on the popup. */
    private static final float POPUP_PADDING = 9f;

    private static String prose(int length) {
        StringBuilder text = new StringBuilder(length);
        while (text.length() < length) text.append("documentation prose that wraps ");
        return text.substring(0, length);
    }

    private DocumentationPopup popup;

    @Before
    public void openAPopup() {
        popup = new DocumentationPopup();
        UIElement root = new UIElement().layout(l -> l.width(VIEWPORT_W).height(VIEWPORT_H));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        viewport(VIEWPORT_W, VIEWPORT_H);
        for (int i = 0; i < 2; i++) frame();
    }

    /** Shows a symbol whose documentation is {@code chars} long, and returns the width it opened at. */
    private float widthFor(int chars) {
        popup.show(document, new SymbolInfo("System", SymbolKind.CLASS, null, "java.lang",
                prose(chars), Set.of(), null), 300f, 200f, 14f);
        for (int i = 0; i < 4; i++) frame();
        assertNotNull(popup.box());
        return popup.box().width();
    }

    @Test
    public void aLongDocumentationBodyDoesNotStretchTheBoxAcrossTheWindow() {
        assertTrue("the popup took the whole window", widthFor(4000) < VIEWPORT_W);
    }

    /**
     * <b>The width is a function of how much there is to read.</b>
     *
     * <p>IntelliJ's curve, thresholds included: under 200 characters the box opens at its floor, over
     * 1000 at its ceiling, and it ramps linearly between. A flat cap gave every symbol the same box, so
     * a two-line summary opened as wide as a page of prose with the difference left as empty space.</p>
     */
    @Test
    public void theWidthRampsWithTheAmountOfDocumentation() {
        float shortDoc = widthFor(40);
        float middling = widthFor(600);
        float longDoc = widthFor(4000);

        assertEquals("under the lower threshold, the box opens at its floor", FLOOR, shortDoc, 1f);
        assertEquals("over the upper one, at its ceiling", CEILING, longDoc, 1f);
        assertTrue("and between them it ramps: " + shortDoc + " < " + middling + " < " + longDoc,
                middling > shortDoc + 1f && middling < longDoc - 1f);
    }

    /** The endpoints are the sheet's, so the ramp cannot run outside them however long the text is. */
    @Test
    public void theRampStaysWithinTheSheetsBounds() {
        assertTrue("below the floor", widthFor(0) >= FLOOR - 1f);
        assertTrue("above the ceiling", widthFor(20000) <= CEILING + 1f);
    }

    /** ...and being bounded is what leaves room for it to sit where it was asked to. */
    @Test
    public void aBoundedBoxStillLandsBesideItsAnchor() {
        widthFor(4000);
        assertTrue("clamped to the left edge, which is what a full-width box forces: " + popup.box(),
                popup.box().x() > 0f);
    }

    /**
     * <b>...and a reader who drags it wider is not capped.</b>
     *
     * <p>The half most likely to be lost. {@code max-width} is applied after {@code width} whatever
     * origin the width came from, so a cap left in place silently wins over a drag and the handle reads
     * as dead — which is the trap the sheet records about the height bound, one axis over. The class the
     * resizer adds is what stands the cap down.</p>
     */
    @Test
    public void aWidthTheReaderChoseIsNotCapped() {
        float bounded = widthFor(4000);

        popup.addClass(UIElement.USER_SIZED_WIDTH_CLASS);
        StyleGroup.inlinePipeline(popup.getStyle().getLayoutGroup(), l -> l.width(1000f));
        for (int i = 0; i < 3; i++) frame();

        assertTrue("the cap was still applied over the width a drag wrote: " + popup.box(),
                popup.box().width() > bounded);
        assertEquals("...and it took exactly the width asked for", 1000f, popup.box().width(), 1f);
    }

    /**
     * <b>A popup that was dragged wide comes back on the ramp for the next symbol.</b>
     *
     * <p>The reused-instance trap this class is full of, and the one condition a fresh fixture does not
     * have. {@code show} clears a dragged size, but the class it removes is the one the ramp reads its
     * endpoints through — so within that same call the bounds still say "released" and there is no ramp
     * to draw.</p>
     */
    @Test
    public void aPopupThatWasDraggedComesBackOnTheRamp() {
        widthFor(4000);

        // A DRAG: what UIResizer writes, and the class it writes alongside.
        popup.addClass(UIElement.USER_SIZED_WIDTH_CLASS);
        StyleGroup.inlinePipeline(popup.getStyle().getLayoutGroup(), l -> l.width(1400f));
        for (int i = 0; i < 3; i++) frame();
        assertEquals("the drag took", 1400f, popup.box().width(), 1f);

        popup.hide();
        for (int i = 0; i < 2; i++) frame();

        assertEquals("a short symbol after a drag opens at the floor, not at the dragged width",
                FLOOR, widthFor(40), 1f);
    }

    /**
     * <b>The prose takes the measure of the box it is in.</b>
     *
     * <p>The body used to carry {@code max-width: 402px} — the popup's floor less its padding — so that
     * text, which is {@code Measurable} and answers max-content, could not drag the box out to the width
     * of the surface. The ramp made that unnecessary: an explicit width cannot be widened by content. It
     * also made it wrong, and the sheet's own note about the 300px cap before it says how — the box grew
     * and the text did not, leaving a widening empty margin beside every paragraph. Measured in the
     * running harness at a 645px popup with its body pinned to 402, filling only once a drag released
     * the cap.</p>
     */
    @Test
    public void theProseFillsTheWidthTheRampChose() {
        widthFor(4000);
        UIElement prose = deepestBody();
        assertNotNull("the body band is on screen", prose);
        assertEquals("the prose is still measured against the floor, not against the box",
                popup.box().width() - POPUP_PADDING * 2f, prose.box().width(), 2f);
    }

    /** ...and at the other end of the ramp, where box and floor happen to agree. */
    @Test
    public void theProseFillsAShortPopupToo() {
        widthFor(40);
        UIElement prose = deepestBody();
        assertNotNull(prose);
        assertEquals(popup.box().width() - POPUP_PADDING * 2f, prose.box().width(), 2f);
    }

    /** The markup band, found by class so the test does not name the popup's structure. */
    private UIElement deepestBody() {
        for (UIElement node : popup.composedSubtree()) {
            if (node.hasClass(DocumentationPopup.BODY_CLASS)) return node;
        }
        return null;
    }

    /**
     * The control: a popover with ordinary content was never affected, which is what says the defect is
     * about content width rather than about promotion or placement.
     */
    @Test
    public void aPopoverWithShortContentIsUnaffected() {
        Popover plain = new Popover();
        plain.append(new UIText("short"));
        document.append(plain);
        plain.showAt(300f, 400f, null);
        for (int i = 0; i < 4; i++) frame();

        assertTrue("a short popover sizes to its content: " + plain.box(),
                plain.box().width() < 200f);
        assertEquals("...and lands where it was put", 300f, plain.box().x(), 1f);
    }
}
