package com.crystalgui.app.machine.ui;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

/**
 * The two row shapes both panels in this example build — extracted when {@link EnginePanel} became
 * the second caller, and for no grander reason than that.
 *
 * <p>Worth saying because the alternative was tempting: a nested panel is a separate class in a
 * separate file, so copying eight lines of layout into it reads as harmless. It is the shape that
 * ends with two label columns whose widths drift apart, which in a demo about composition would be
 * the one thing a reader notices.</p>
 */
final class MachineRows {

    private MachineRows() {
    }

    /**
     * A label beside a control.
     *
     * <p>The fixed-width slot is the one pixel value in this package, and it is here because
     * {@link UIText} measures itself after layout and writes its own width back at {@code IMPORTANT}
     * origin — so a stylesheet {@code width} on the text loses to the text. Wrapping it in a sized
     * box is the standing idiom for keeping a column of labels aligned; every harness scene in the
     * repository does the same thing for the same reason.</p>
     */
    static UIElement row(String caption, UIElement control) {
        UIElement row = new UIElement();
        row.addClass(MachineStyles.ROW_CLASS);

        UIElement slot = new UIElement().layout(l -> l.width(90));
        UIText text = new UIText(caption);
        text.addClass(MachineStyles.LABEL_CLASS);
        slot.append(text);

        row.append(slot);
        row.append(control);
        return row;
    }

    /**
     * A fixed side badge and the line only that side writes.
     *
     * <p>The exclusivity is the design rather than tidiness — see the long comment at the call site in
     * {@link MachinePanel#layout}, where a single shared line produced a readout with the client's
     * badge above the server's sentence and no amount of care at the writers could have prevented
     * it.</p>
     */
    static UIElement authored(String badgeClass, String side, UIText line) {
        UIElement row = new UIElement();
        row.addClass(MachineStyles.ROW_CLASS);

        UIText badge = new UIText(side);
        badge.addClass(MachineStyles.KIND_CLASS);
        badge.addClass(badgeClass);
        row.append(badge);

        // neverSelfSizeWidth for the opposite reason to the method names elsewhere in the panel: this
        // is in a ROW and its text is long, so sizing itself would push the row past the panel edge.
        // Sized by the sheet, it wraps inside its box.
        line.addClass(MachineStyles.WIRE_CLASS);
        row.append(line);

        return row;
    }
}
