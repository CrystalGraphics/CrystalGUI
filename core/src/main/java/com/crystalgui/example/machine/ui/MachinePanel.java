package com.crystalgui.example.machine.ui;

import com.crystalgui.example.machine.session.MachineServer;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.ProgressBar;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.Switch;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;

/**
 * <b>Step 2 — the widget tree.</b>
 *
 * <p>Six controls in a column. This class is built on the <em>server</em>, in a process with no
 * OpenGL and no fonts, and that is fine: constructing a {@link Switch} allocates an object and
 * touches no GPU. Only <em>painting</em> needs a graphics backend, and a server never paints.</p>
 *
 * <h3>No sizes, no colours, no timings in here</h3>
 *
 * <p>Look for a pixel value below. There is one — a {@code width(90)} on the label column — and it
 * is marked as the exception it is, because {@link UIText} pushes its own measured width at
 * {@code IMPORTANT} origin and a stylesheet cannot beat that. Everything else is named and styled
 * from {@link MachineStyles}.</p>
 *
 * <p>This is not a house style. It is the difference between a panel a resource pack can re-theme
 * and a panel that looks the way one programmer left it: a value written in Java arrives at
 * {@code INLINE} origin, which outranks every stylesheet rule at any specificity, so the theme
 * author's rule is not overridden — it never applies at all, and nothing reports that.</p>
 *
 * <h3>Why the fields are public and final</h3>
 *
 * <p>{@link MachineServer} needs handles on individual widgets to attach behaviour and to write
 * state into. Hunting them back out with {@code querySelector} would work and would be worse: a
 * typo in a selector is a lookup that finds nothing at runtime, where a field is a compile error.
 * The tree is built once in the constructor and never reshaped, so the handles cannot go stale.</p>
 *
 * <h3>The ids are not decoration</h3>
 *
 * <p>{@code setId} is what {@code #power} in a stylesheet matches. It does <b>not</b> travel as an
 * addressing scheme — see {@link com.crystalgui.net.NetworkIds}, which derives a number for every
 * element from a document-order walk on both sides and sends nothing. The id is for the cascade;
 * the network id is for the protocol; they are unrelated and it is worth not confusing them.</p>
 */
public final class MachinePanel {

    /** The root the description is taken from, and the root the client rebuilds. */
    public final UIElement root;

    /** On/off. Reports {@code toggle}. */
    public final Switch power;

    /** 0..1 throughput. Reports {@code value}. */
    public final Slider throughput;

    /** The machine's name. Reports {@code text}. */
    public final TextField label;

    /** Cycle progress. Server-driven only — the client never writes to it. */
    public final ProgressBar progress;

    /** Abandons the cycle. Reports {@code activate}. */
    public final Button purge;

    /** A line of server-written text. Also server-driven only. */
    public final UIText status;

    public MachinePanel() {
        root = new UIElement();
        root.addClass(MachineStyles.PANEL_CLASS);

        UIText title = new UIText("Machine control");
        title.addClass(MachineStyles.TITLE_CLASS);
        root.addChild(title);

        power = new Switch();
        power.setId("power");
        root.addChild(row("Power", power));

        throughput = new Slider();
        throughput.setRange(0f, 1f);
        throughput.setId("throughput");
        root.addChild(row("Throughput", throughput));

        label = new TextField();
        label.setPlaceholder("name this machine");
        label.setId("label");
        root.addChild(row("Label", label));

        progress = new ProgressBar();
        progress.setId("progress");
        root.addChild(row("Cycle", progress));

        status = new UIText("");
        status.addClass(MachineStyles.STATUS_CLASS);
        root.addChild(status);

        purge = new Button("Purge");
        purge.setId("purge");
        root.addChild(purge);
    }

    /**
     * A label beside a control.
     *
     * <p>The fixed-width slot is the one pixel value in this class, and it is here because
     * {@link UIText} measures itself after layout and writes its own width back at {@code IMPORTANT}
     * origin — so a stylesheet {@code width} on the text loses to the text. Wrapping it in a sized
     * box is the standing idiom for keeping a column of labels aligned; every harness scene in the
     * repository does the same thing for the same reason.</p>
     */
    private static UIElement row(String caption, UIElement control) {
        UIElement row = new UIElement();
        row.addClass(MachineStyles.ROW_CLASS);

        UIElement slot = new UIElement().layout(l -> l.width(90));
        UIText text = new UIText(caption);
        text.addClass(MachineStyles.LABEL_CLASS);
        slot.addChild(text);

        row.addChild(slot);
        row.addChild(control);
        return row;
    }
}
