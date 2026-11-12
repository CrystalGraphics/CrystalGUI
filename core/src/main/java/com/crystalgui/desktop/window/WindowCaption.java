package com.crystalgui.desktop.window;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;

/**
 * A window's title bar: the icon, the slot an application's menu bar is adopted into, the title and the pin,
 * minimise, maximise and close controls. It is the shell's chrome, built apart from the frame and from whatever the
 * window shows, and it knows nothing about the window it sits in: each control calls back what it was given.
 *
 * <pre>{@code
 * WindowCaption caption = new WindowCaption("Planner",
 *         () -> togglePin(), () -> minimise(), () -> toggleMaximise(), () -> close());
 * frame.append(caption);
 * caption.maximizeTooltip().setText(WindowFrame.RESTORE_TOOLTIP);
 * }</pre>
 *
 * <ul>
 *   <li>Every part exists from construction and is hidden or empty until used, so setting an icon or adopting a menu
 *       bar mid-gesture creates no element.</li>
 *   <li>The title label takes no hits: a press on the title text falls through and drags the bar.</li>
 *   <li>Tooltips are attached once; relabel with {@link #pinTooltip()} and {@link #maximizeTooltip()}, never by
 *       attaching another.</li>
 * </ul>
 */
final class WindowCaption extends UIElement {

    private final WindowIcon icon = new WindowIcon();
    private final UIElement captionChrome = new UIElement();
    private final UIText titleLabel;
    private final UIElement controls = new UIElement();
    private final Button pinButton = new Button("");
    private final Button minimizeButton = new Button("");
    private final Button maximizeButton = new Button("");
    private final Button closeButton = new Button("");
    private final Tooltip pinTooltip;
    private final Tooltip maximizeTooltip;

    WindowCaption(String title, Runnable togglePin, Runnable minimise, Runnable toggleMaximise, Runnable close) {
        addClass(WindowFrame.TITLE_BAR_CLASS);

        titleLabel = new UIText(title);
        titleLabel.addClass(WindowFrame.TITLE_CLASS);
        // A press on the title text must reach the bar, or the bar drags everywhere but over its title
        titleLabel.setHitTest(false);

        controls.addClass(WindowFrame.CONTROLS_CLASS);

        // Pin first, left of the minimise/maximise/close triplet: it is not about presence on the desktop
        pinButton.addClass(WindowFrame.PIN_CLASS);
        pinButton.attachListener(togglePin);
        pinTooltip = Tooltip.attach(pinButton, WindowFrame.PIN_TOOLTIP);
        pinTooltip.addClass(Tooltip.WAIT_CLASS);
        controls.append(pinButton);

        // Through the animation, which then hides: hiding is detaching, and a detached subtree paints nothing
        minimizeButton.addClass(WindowFrame.MINIMIZE_CLASS);
        minimizeButton.attachListener(minimise);
        Tooltip.attach(minimizeButton, WindowFrame.MINIMIZE_TOOLTIP).addClass(Tooltip.WAIT_CLASS);
        controls.append(minimizeButton);

        maximizeButton.addClass(WindowFrame.MAXIMIZE_CLASS);
        maximizeButton.attachListener(toggleMaximise);
        // Kept: its text follows the state, and a second Tooltip.attach would leave the first one showing
        maximizeTooltip = Tooltip.attach(maximizeButton, WindowFrame.MAXIMIZE_TOOLTIP);
        maximizeTooltip.addClass(Tooltip.WAIT_CLASS);
        controls.append(maximizeButton);

        closeButton.addClass(WindowFrame.CLOSE_CLASS);
        closeButton.attachListener(close);
        Tooltip.attach(closeButton, WindowFrame.CLOSE_TOOLTIP).addClass(Tooltip.WAIT_CLASS);
        controls.append(closeButton);

        icon.addClass(WindowFrame.ICON_CLASS);

        // After the icon, before the title: where an application's menu bar sits. Empty and hidden until adopted.
        captionChrome.addClass(WindowFrame.CAPTION_CHROME_CLASS);
        captionChrome.setDisplayed(false);

        append(icon);
        append(captionChrome);
        append(titleLabel);
        append(controls);
    }

    WindowIcon icon() {
        return icon;
    }

    UIElement captionChrome() {
        return captionChrome;
    }

    UIText titleLabel() {
        return titleLabel;
    }

    UIElement controls() {
        return controls;
    }

    Button pinButton() {
        return pinButton;
    }

    Button minimizeButton() {
        return minimizeButton;
    }

    Button maximizeButton() {
        return maximizeButton;
    }

    Button closeButton() {
        return closeButton;
    }

    Tooltip pinTooltip() {
        return pinTooltip;
    }

    Tooltip maximizeTooltip() {
        return maximizeTooltip;
    }
}
