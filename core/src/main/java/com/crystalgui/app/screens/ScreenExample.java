package com.crystalgui.app.screens;

import com.crystalgraphics.platform.input.CgKeyBindings;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.screen.CgUiScreen;
import com.crystalgui.screen.ScreenKind;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * <b>The worked example of a game screen</b>: a panel over the game with no desktop, opened from a key or the launcher.
 *
 * <pre>{@code
 * ScreenExample.declareKey();        // once, at client start: "Screen example" in the game's controls, unbound
 * CgUiScreen.show(ScreenExample.SCREEN);
 * }</pre>
 *
 * <p>Escape and the Close button close it; the game runs behind. Copy it to start a screen of your own.</p>
 */
public final class ScreenExample {

    public static final String ID = "crystalgui:screen-example";

    private static final String PANEL = "__screen-example__";

    private static final String SHEET = "." + PANEL + " { align-self: center; margin-top: 192px; width: 640px; padding: 32px;"
            + " gap: 16px; background-color: var(--window-bg, #191A1C); outline: 2px var(--accent, #3574F0); }";

    /** What the screen is: built each time it is shown, on its own document. */
    public static final ScreenKind SCREEN = ScreenKind.of(ID, screen -> {
        screen.document().styles().addStylesheet(StyleSheet.parse(SHEET));
        UIElement panel = new UIElement();
        panel.addClass(PANEL);
        Button close = new Button("Close");
        close.onPressed.connect(screen::close);
        panel.append(new UIText("Screen example"),
                new UIText("A screen of the game with no desktop. Escape closes it, and the game keeps running."),
                close);
        screen.root().append(panel);
    });

    /** The launcher's entry: shows the screen in place of the desktop. */
    public static final ApplicationKind KIND = ApplicationKind.of(ID, "Screen Example")
            .keywords("screen", "game", "example")
            .action(context -> CgUiScreen.show(SCREEN));

    private ScreenExample() {
    }

    /** Lists the example in the game's controls, unbound until the player binds it. Client start. */
    public static void declareKey() {
        CgKeyBindings.declare(ID, CgKeyCodes.KEY_NONE, "crystalgui:keys", () -> CgUiScreen.show(SCREEN));
    }
}
