package com.crystalgui.widget.texteditor;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * The transient "Font size: 14px" panel, with its reset button.
 *
 * <p>Closest to one of Monaco's <b>overlay widgets</b> ({@code browser/viewParts/overlayWidgets/}) — chrome
 * positioned against the viewport rather than against the document, so it neither scrolls nor lives in the
 * text's coordinate space. It is built lazily: an editor that is never zoomed never creates it.</p>
 */
final class ZoomIndicatorPart extends EditorViewPart {

    /**
     * How long the indicator stays up after the last zoom, before it starts fading.
     *
     * <p>Roughly IntelliJ's. The <b>fade itself is not here</b> — this only holds a class on the indicator
     * and drops it when the hold expires; {@code default.css} owns how long the fade takes and what it
     * eases on. A timing in Java would be the same mistake the gutter's metrics were.</p>
     */
    private float holdSeconds = 4f;

    /** Seconds left on screen; zero means hidden. */
    private float secondsLeft;

    private UINode panel;
    private UIText label;
    private Button resetButton;

    ZoomIndicatorPart(TextEditor editor) {
        super(editor);
    }

    /** Seconds the indicator holds before fading. The fade's own duration is CSS. */
    void setHoldSeconds(float seconds) {
        this.holdSeconds = Math.max(0f, seconds);
    }

    /** Shows the current size and restarts the hold — pressing zoom again keeps it up. */
    void show(float baseFontSize) {
        secondsLeft = holdSeconds;
        panel();
        label.setText("Font size: " + Math.round(editor.getFontSize()) + "px");
        // The reset button names the size it will go back to, as IntelliJ's does. That is the whole reason
        // it is worth showing: "reset" alone does not say what you are getting, and after three presses
        // nobody remembers what the sheet said.
        resetButton.setText("Reset to "
                + Math.round(baseFontSize > 0f ? baseFontSize : editor.getFontSize()) + "px");
        // A faded indicator must not be clickable. Opacity is paint, not hit testing -- without this the
        // reset button stays live over the text for as long as the element exists.
        panel.setHitTest(true);
        // addClass invalidates the style match itself, so the transition sees the change.
        panel.addClass(TextEditor.SHOWN_CLASS);
        editor.markTreeDirty();
    }

    /**
     * Counts the hold down and hands the fade to the cascade.
     *
     * <p>Dropping the class rather than writing an opacity is what keeps the timing in the sheet: the
     * transition on {@code opacity} runs because the computed value changed, and the widget never learns
     * how long it takes.</p>
     */
    void tick(float deltaSeconds) {
        if (secondsLeft <= 0f) return;
        secondsLeft -= deltaSeconds;
        if (secondsLeft > 0f) return;
        secondsLeft = 0f;
        if (panel != null) {
            panel.removeClass(TextEditor.SHOWN_CLASS);
            panel.setHitTest(false);
        }
    }

    private UINode panel() {
        if (panel == null) {
            panel = new UINode();
            panel.addClass(TextEditor.ZOOM_INDICATOR_CLASS);
            panel.setScrollExempt(true);

            label = new UIText("");
            label.addClass(TextEditor.ZOOM_LABEL_CLASS);
            label.setHitTest(false);
            panel.append(label);

            resetButton = new Button("");
            resetButton.addClass(TextEditor.ZOOM_RESET_CLASS);
            // NEVER takes focus. A Button focuses on click by default, so pressing reset would move focus
            // out of the editor and the next keystroke would go nowhere -- from a control whose whole
            // purpose is to get you back to reading the text.
            resetButton.setFocusPolicy(FocusPolicy.NONE);
            resetButton.attachListener(editor::resetZoom);
            panel.append(resetButton);

            editor.append(panel);
        }
        return panel;
    }

    /**
     * Parks the indicator at the bottom of the viewport, centred.
     *
     * <p>Where IntelliJ puts it, and it is the right place for the same reason: the caret is almost never
     * there, so the thing telling you about the text does not sit on top of the text you are reading.</p>
     */
    @Override
    void render(int firstViewLine, int lastViewLine) {
        if (panel == null) return;
        // NOTHING TO PLACE WHILE IT IS HIDDEN, and this runs every frame for as long as the part exists.
        // Both label widths are SHAPED text measurements, so an indicator nobody has summoned was paying
        // for two of them sixty times a second -- the same trap the gutter's digit and the editor's space
        // advance each record, arrived at from the third direction.
        if (!panel.hasClass(TextEditor.SHOWN_CLASS)) return;
        // AND ONLY WHEN SOMETHING IT MEASURES HAS MOVED. The two labels change on a zoom gesture and the
        // chrome size on a theme change; between those the answer is last frame's.
        final float chrome = label.getStyle().getGeneralGroup().fontSize();
        String key = label.getText() + ' ' + resetButton.getText() + ' ' + chrome
                + ' ' + editor.box().clientWidth();
        if (key.equals(placedKey)) return;
        placedKey = key;
        // THE THREE MULTIPLIERS BELOW STAY IN JAVA, and `em` does not retire them -- which is worth
        // saying, because at first look they are exactly what `em` is for.
        //
        // They are multiples of the LABEL's font size, and the panel's `em` would resolve against the
        // PANEL's -- two different elements, since font-size does not effectively inherit here. They
        // happen to be the same number today (the sheet pins the label at 10 and ua/core.css gives the
        // panel 10), so an `em` here would be right by coincidence and would quietly stop being right
        // the first time a theme restyled the label. And the width they contribute to is measured
        // shaped text, which no sheet can compute at all.
        //
        // THE INDICATOR'S OWN SIZE, from the sheet -- never the editor's. It is chrome describing the
        // text, not part of it, so scaling it with the zoom made it unreadable at 4px and oversized at 40.
        // IntelliJ's stays put for the same reason. The widget pushes no font here at all; it reads what
        // the cascade gave the label and measures against that.
        // Measured from both children, because the reset button's label changes with the default size and
        // a box narrower than its content clips it -- the same definite-width rule the lines follow.
        final float width = textWidthOf(label.getText(), chrome)
                + textWidthOf(resetButton.getText(), chrome) + chrome * 4f;
        final float height = chrome * 2f;
        // Centred on the CLIENT box, not on the code area. textOriginX moves with the gutter, which grows
        // with the font -- so anchoring to it made the indicator slide sideways on the very gesture it is
        // reporting.
        final float left = Math.max(0f, (editor.box().clientWidth() - width) / 2f);
        // A BOTTOM inset, not a computed top. Every position here is derived from the PREVIOUS frame's
        // layout, which is fine for anything anchored to the top -- a height change does not move it. This
        // is anchored to the bottom, so a resize moved it by the full delta for one frame and then
        // corrected: the visible flick downwards and back. Taffy resolves a bottom inset against the
        // container's height at layout time, so there is no stale value to be wrong with.
        //
        // And a CONSTANT one. It used to add horizontalBarThickness(), which is zero or eight depending on
        // whether the content currently overflows -- a term that TOGGLES on the very gesture this reports,
        // since zooming changes how wide the text is. That is what made it flick on every zoom rather than
        // only on a resize. Sized to clear the bar outright instead, so the answer never depends on
        // whether the bar is there.
        final float bottom = chrome * 1.6f;

        StyleGroup.defaultPipeline(panel.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(left).bottom(bottom).width(width).height(height));
    }

    /** What the last placement was computed from — see {@link #render}. */
    private String placedKey = "";

    /** The shaped width of a string at a given size, in the editor's family. */
    private float textWidthOf(String text, float size) {
        if (text == null || text.isEmpty()) return 0f;
        CgFontFamily family = FontFamilyCache.resolve(
                editor.getStyle().getGeneralGroup().fontFamily(), Math.round(Math.max(1f, size)));
        return CgTextLayout.of(text, family).build().totalWidth();
    }
}
