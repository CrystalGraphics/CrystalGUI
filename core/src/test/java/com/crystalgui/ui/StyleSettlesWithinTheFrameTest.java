package com.crystalgui.ui;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A class set from a ticker or a layout hook is <b>matched before the frame ends</b>.
 *
 * <p>{@code advanceFrame} used to be one pass each of style, tickers and layout, in that order — so a
 * class set by either of the last two was not re-matched until the following frame. The class landed and
 * its computed style did not, and whatever the element painted that frame was the previous answer.</p>
 *
 * <p>This is the general statement of a bug that took three attempts to find because it kept arriving
 * disguised as something else. A virtualised list binds its rows from inside layout and sets a selection
 * class as it goes, so expanding a folder handed a pooled row that used to be selected to an unrelated
 * file — and that file flashed highlighted while the row that really was selected painted plain. Nothing
 * about selection was wrong; every assertion about classes and indices passed. The tests here work in
 * plain colours on a bare element instead, because the mechanism has nothing to do with lists.</p>
 */
public class StyleSettlesWithinTheFrameTest extends UiTestBase {

    private UIWindow window;
    private UIElement target;

    @Before
    public void setUp() {
        target = new UIElement();
        UIElement root = new UIElement();
        root.addChild(target);

        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.parse(".lit { background-color: #FF0000; }"));
        window.init(400, 300);
        window.updateWithoutPainting();
    }

    private int background() {
        return target.getStyle().getGeneralGroup().backgroundColor();
    }

    /** Tickers run after {@code calculateStyle}, so this is the half a list's row binding hits. */
    @Test
    public void aClassSetFromATickerIsMatchedInTheSameFrame() {
        window.registerTicker(delta -> {
            target.addClass("lit");
            return false;
        });

        window.updateWithoutPainting();

        assertEquals("the class was set during the frame and its style was left a frame behind",
                0xFFFF0000, background());
    }

    /**
     * And the layout half, which is where {@code ListView} actually does it — {@code onLayoutChanged}
     * fires from inside {@code calculateLayout}, later still than a ticker.
     */
    @Test
    public void aClassSetFromALayoutHookIsMatchedInTheSameFrame() {
        UIElement reactive = new UIElement() {
            @Override
            protected void onLayoutChanged() {
                super.onLayoutChanged();
                target.addClass("lit");
            }
        };
        window.ui.rootElement.addChild(reactive);

        window.updateWithoutPainting();

        assertEquals("a class set from a layout hook was left unmatched until the next frame",
                0xFFFF0000, background());
    }

    /**
     * <b>Removal too</b> — and this is the half that actually painted wrong.
     *
     * <p>A pooled row keeps the fill of whatever it last showed. Binding it to something unselected removes
     * the class, so if only additions settled within the frame, the stale fill would survive one more paint
     * on top of the new contents. Asserting both directions is what stops a fix that re-matches on
     * {@code addClass} alone from looking complete.</p>
     */
    @Test
    public void aClassRemovedFromATickerIsAlsoMatchedInTheSameFrame() {
        target.addClass("lit");
        window.updateWithoutPainting();
        assertEquals("fixture wrong -- the class never applied at all", 0xFFFF0000, background());

        window.registerTicker(delta -> {
            target.removeClass("lit");
            return false;
        });

        window.updateWithoutPainting();

        assertEquals("the fill outlived the class it came from by a frame",
                StylePropertyRegistry.BACKGROUND_COLOR.initialValue.intValue(), background());
    }
}
