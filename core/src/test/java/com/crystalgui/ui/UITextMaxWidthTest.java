package com.crystalgui.ui;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.input.CgUiInputAdapter;
import com.crystalgui.ui.elements.UIText;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link UIText} honours its own {@code max-width} while self-sizing — CSS shrink-to-fit.
 *
 * <p>{@code width: auto} plus a {@code max-width} wraps at the max on the web. This engine used to
 * treat "self-sizing" as "unbounded", so a {@code max-width} could only ever clip the <em>box</em>
 * while the glyphs kept their full unwrapped run and spilled straight out of it. It showed up as a
 * tooltip that ran off the side of the screen, and it silently broke edge-clamping too — placement
 * measures the box, so a box that doesn't match what's drawn makes every placement decision wrong.</p>
 *
 * <p>These assert against {@link UIText} directly rather than through a widget: it is the element
 * that owns the wrap decision, and a test one layer up would pass or fail for reasons that have
 * nothing to do with it.</p>
 */
public class UITextMaxWidthTest {

    private static final String LONG = "Long enough that it has to wrap onto several lines instead of one.";

    @Before
    public void registerStubAdapter() {
        CrystalGuiCore.setAdapter(new CgUiInputAdapter() {
            @Override public int getCurrentModifiers() { return 0; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
        });
    }

    /** Root is wide and the text is a free-standing child, so nothing but max-width can bound it. */
    private UIText textIn(UIElement root, java.util.function.Consumer<UIText> configure) {
        UIText text = new UIText(LONG);
        configure.accept(text);
        root.addChild(text);
        UIWindow window = new UIWindow(Ui.of(root));
        window.init(1600, 1600); // uiScale 2 -> 800x800 logical
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
        return text;
    }

    @Test
    public void aMaxWidthWrapsSelfSizingText() {
        UIElement root = new UIElement().layout(l -> l.width(800).height(800));
        UIText text = textIn(root, t -> t.layout(l -> l.maxWidth(80)));

        assertTrue("must not exceed its max-width, was " + text.getRuntimeCache().getWidth(),
                text.getRuntimeCache().getWidth() <= 80.5f);
        assertTrue("wrapping must make it multiple lines tall, was " + text.getRuntimeCache().getHeight(),
                text.getRuntimeCache().getHeight() > 40f);
    }

    /** A tighter bound must wrap harder — proves the bound is actually feeding the shaper rather
     * than the box merely being clamped after the fact. */
    @Test
    public void aTighterMaxWidthProducesMoreLines() {
        UIElement wideRoot = new UIElement().layout(l -> l.width(800).height(800));
        float tallAt80 = textIn(wideRoot, t -> t.layout(l -> l.maxWidth(80)))
                .getRuntimeCache().getHeight();

        UIElement narrowRoot = new UIElement().layout(l -> l.width(800).height(800));
        float tallAt40 = textIn(narrowRoot, t -> t.layout(l -> l.maxWidth(40)))
                .getRuntimeCache().getHeight();

        assertTrue("40px must wrap to more lines than 80px (" + tallAt40 + " vs " + tallAt80 + ")",
                tallAt40 > tallAt80);
    }

    /** Only a definite length is a usable bound. A percentage would have to resolve against a
     * containing block that, by definition of self-sizing, never gave this element a width. */
    @Test
    public void anAutoMaxWidthLeavesTextUnbounded() {
        UIElement root = new UIElement().layout(l -> l.width(800).height(800));
        UIText text = textIn(root, t -> { });

        assertTrue("with no max-width it should stay on one line, was "
                        + text.getRuntimeCache().getHeight(),
                text.getRuntimeCache().getHeight() < 40f);
    }

    /** The bound is the content box, so the element's own padding comes off first — otherwise a
     * padded text element wraps exactly its horizontal padding too late and overflows. */
    @Test
    public void paddingIsSubtractedFromTheWrapBound() {
        UIElement bare = new UIElement().layout(l -> l.width(800).height(800));
        float heightWithoutPadding = textIn(bare, t -> t.layout(l -> l.maxWidth(80)))
                .getRuntimeCache().getHeight();

        UIElement padded = new UIElement().layout(l -> l.width(800).height(800));
        float heightWithPadding = textIn(padded, t -> t.layout(l -> l.maxWidth(80).paddingLeft(20).paddingRight(20)))
                .getRuntimeCache().getHeight();

        assertTrue("padding must shrink the usable wrap width, giving more lines ("
                        + heightWithPadding + " vs " + heightWithoutPadding + ")",
                heightWithPadding > heightWithoutPadding);
    }
}
