package com.crystalgui.workbench.dock.banner;

import com.crystalgui.core.notify.Notification;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * The strip a {@link DockBannerProvider} produces — IntelliJ's {@code EditorNotificationPanel}.
 *
 * <p>A message and its actions, above the panel's content and inside the tab rather than over it. That
 * placement is the whole point: a toast tells you something happened, a banner tells you what this tab
 * <em>is</em>, and it has to still be there when you come back to the tab tomorrow.</p>
 *
 * <h3>Severity is a class, not a colour here</h3>
 *
 * <p>Per the project rule — no colours in Java. The bar carries {@code __info__}, {@code __warning__} or
 * {@code __error__} and {@code default.css} decides what each looks like, so a theme can restyle all
 * three without touching this.</p>
 */
public class DockBannerBar extends UINode {
    /** The strip a {@link DockBannerProvider} produces. */
    public static final Name NAME = Name.of("dockbannerbar");


    public static final String BANNER_CLASS = "__dock-banner__";
    public static final String MESSAGE_CLASS = "__message__";
    public static final String ACTION_CLASS = "__action__";

    /** One per {@link Notification.Severity}, lower-cased — {@code __info__} and friends. */
    public static String severityClass(Notification.Severity severity) {
        return "__" + severity.name().toLowerCase(java.util.Locale.ROOT) + "__";
    }

    public DockBannerBar(Notification banner) {
        addClass(BANNER_CLASS);
        addClass(severityClass(banner.getSeverity()));
        UIText message = new UIText(banner.getMessage());
        message.addClass(MESSAGE_CLASS);
        // The bar is not a click target; only its buttons are. Without this the text eats presses that
        // were aimed past it, which on a strip spanning the whole tab is most of them.
        message.setHitTest(false);
        append(message);

        for (Notification.Action action : banner.actions()) {
            Button button = new Button(action.label());
            button.addClass(ACTION_CLASS);
            button.onPressed.connect(action.run());
            append(button);
        }
    }
}
