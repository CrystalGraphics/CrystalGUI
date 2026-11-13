package com.crystalgui.desktop.dialog;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.UIText;

/**
 * The desktop's "could not open" box: a real window, with a red mark, what failed, why, and an OK. Windows' error
 * message box, in the desktop's glass.
 *
 * <p>It is the failing application's window: it takes that application's taskbar button, flashes it, and closes, minimises
 * and switches like any other.</p>
 *
 * <pre>{@code
 * FailureWindow.open(desktop, MachineExample.KIND, "Machine Example", "Machine Example could not be opened.", failed);
 * }</pre>
 *
 * <ul>
 *   <li>Go through {@code Desktop#reportFailure}, which hops onto the document's sequence and keeps one per
 *       application; {@link #open} itself must already be on it.</li>
 *   <li>{@code kind} is optional; without one the box is a window of its own.</li>
 *   <li>{@code cause} is optional; its root message is shown under the sentence.</li>
 * </ul>
 */
public final class FailureWindow {

    public static final String CLASS = "__failure__";
    public static final String BODY_CLASS = "__failure-body__";
    public static final String ICON_CLASS = "__failure-icon__";
    public static final String TEXT_CLASS = "__failure-text__";
    public static final String MESSAGE_CLASS = "__failure-message__";
    public static final String DETAIL_CLASS = "__failure-detail__";
    public static final String REPORT_CLASS = "__failure-report__";
    public static final String SCROLL_CLASS = "__failure-scroll__";
    public static final String FOOTER_CLASS = "__failure-footer__";
    public static final String DEFAULT_CLASS = "__default__";

    private FailureWindow() {
    }

    /** Opens the box on {@code desktop}, active, and asks for attention. On the desktop's document's thread. */
    public static WindowFrame open(Desktop desktop, @Nullable ApplicationKind kind, String title, String message,
                                   @Nullable Throwable cause) {
        return open(desktop, kind, title, message, detail(cause), List.of(new Action("OK", () -> { })));
    }

    /**
     * One button of the box: it runs, then the box closes unless {@code closes} is false.
     *
     * <pre>{@code
     * new Action("Close", () -> { })                      // runs, then the box closes
     * Action.opening("Details", () -> showTheReport())    // runs; the box stays for the answer
     * }</pre>
     */
    public record Action(String label, Runnable run, boolean closes) {

        public Action(String label, Runnable run) {
            this(label, run, true);
        }

        /** A button that shows something beside the box, which stays until one of its closing buttons is pressed. */
        public static Action opening(String label, Runnable run) {
            return new Action(label, run, false);
        }
    }

    /**
     * The same box with {@code detail} under the sentence and a button per action, the first the default.
     *
     * <pre>{@code
     * FailureWindow.open(desktop, kind, "VFX Lab", "VFX Lab is not responding", "",
     *         List.of(new FailureWindow.Action("Wait", () -> { }), new FailureWindow.Action("Close", close)));
     * }</pre>
     */
    public static WindowFrame open(Desktop desktop, @Nullable ApplicationKind kind, String title, String message,
                                   String detail, List<Action> actions) {
        return open(desktop, kind, title, message, detail, actions, false);
    }

    /**
     * A report: like {@link #open} but resizable, its {@code detail} one line per line of text and scrolled where it
     * does not fit, for a stack trace that wrapping would make unreadable.
     */
    public static WindowFrame openReport(Desktop desktop, @Nullable ApplicationKind kind, String title, String message,
                                         String detail, List<Action> actions) {
        return open(desktop, kind, title, message, detail, actions, true);
    }

    private static WindowFrame open(Desktop desktop, @Nullable ApplicationKind kind, String title, String message,
                                    String detail, List<Action> actions, boolean report) {
        WindowFrame frame = new WindowFrame(title);
        frame.addClass(CLASS);
        if (report) {
            frame.addClass(REPORT_CLASS);
            // THE BODY MAY GIVE WAY, so the content fills the window's height: a content-sized slot never shrinks it.
            if (frame.content() instanceof ScrollerView view) view.setFitsMainAxis(true);
        }
        if (kind != null) {
            frame.setApplication(kind);
            if (kind.icon() != null) frame.setIcon(kind.icon());
        }
        frame.minimizeButton().setDisplayed(false);
        frame.maximizeButton().setDisplayed(false);
        frame.setMaximizable(false);

        UIElement body = new UIElement();
        body.addClass(BODY_CLASS);
        frame.content().append(body);

        UIElement icon = new UIElement();
        icon.addClass(ICON_CLASS);
        body.append(icon);

        UIElement text = new UIElement();
        text.addClass(TEXT_CLASS);
        body.append(text);

        UIText headline = new UIText(message);
        headline.addClass(MESSAGE_CLASS);
        text.append(headline);

        if (!detail.isEmpty()) {
            UIText why = new UIText(detail);
            why.addClass(DETAIL_CLASS);
            if (report) {
                ScrollerView scroll = new ScrollerView();
                scroll.addClass(SCROLL_CLASS);
                scroll.append(why);
                text.append(scroll);
            } else {
                text.append(why);
            }
        }

        UIElement buttons = new UIElement();
        buttons.addClass(FOOTER_CLASS);
        frame.content().append(buttons);
        Button first = null;
        for (Action action : actions) {
            Button button = new Button(action.label());
            if (first == null) {
                first = button;
                button.addClass(DEFAULT_CLASS);
            }
            button.onPressed.connect(() -> {
                action.run().run();
                if (action.closes()) frame.requestClose();
            });
            buttons.append(button);
        }

        desktop.addWindow(frame, true);
        frame.requestAttention();
        if (desktop.document() != null && first != null) desktop.document().focus().requestFocus(first);
        return frame;
    }

    /** The innermost cause's message, with its type when it has none. */
    private static String detail(@Nullable Throwable cause) {
        if (cause == null) return "";
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
