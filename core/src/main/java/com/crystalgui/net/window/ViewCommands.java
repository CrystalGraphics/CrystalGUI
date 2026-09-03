package com.crystalgui.net.window;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.ViewCommand;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.TreeSource;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.overlay.Tooltip;
import javax.annotation.Nullable;

/**
 * <b>Carries out a {@link ViewCommand} on the client.</b>
 *
 * <p>Lives here rather than in {@code net} because doing any of it means touching {@code ui.elements} —
 * a dialog, a popover, a tooltip — and the session layer deliberately does not. The session checks the
 * command against the vocabulary and hands it over; this decides what it means.</p>
 *
 * <h3>Ignoring one is a legal outcome</h3>
 *
 * <p>Every method here may do nothing, and none of it is a failure: the element may not be a dialog,
 * the host may have no notion of a window title, the tree may have moved on since the command was
 * sent. A server asking is not a server instructing — the client is showing the UI and knows things
 * the server does not, starting with whether anyone is looking at it.</p>
 *
 * <p>What it must never do is <b>throw into the frame</b>. A malformed or stale command is one message
 * going nowhere, not a window falling over.</p>
 */
final class ViewCommands {

    private ViewCommands() {
    }

    /**
     * @param window the host's handle, for the commands that are about the window rather than the tree
     */
    static void apply(String command, StateMap<Object> in, TreeSource<UIElement> ids,
                      UIElement root, @Nullable WindowMount.MountedWindow window) {
        try {
            switch (command) {
                case ViewCommand.FOCUS:
                    onElement(in, ids, ViewCommands::focus);
                    break;
                case ViewCommand.SCROLL_INTO_VIEW:
                    onElement(in, ids, node -> {
                        // THE BOX'S, not the node's: geometry moved off the node with the three trees,
                        // and a node with no box has nothing to scroll into view.
                        if (node.box() != null) node.box().scrollIntoView();
                    });
                    break;
                case ViewCommand.SHOW_DIALOG:
                    onElement(in, ids, element -> {
                        if (element instanceof Dialog) ((Dialog) element).show();
                    });
                    break;
                case ViewCommand.HIDE_DIALOG:
                    onElement(in, ids, element -> {
                        if (element instanceof Dialog) ((Dialog) element).close();
                    });
                    break;
                case ViewCommand.OPEN_MENU:
                    openMenu(in, ids);
                    break;
                case ViewCommand.TOOLTIP:
                    tooltip(in, ids, in.getString(ViewCommand.TEXT, ""));
                    break;
                case ViewCommand.SET_TITLE:
                case ViewCommand.SET_ICON:
                case ViewCommand.GEOMETRY_HINT:
                case ViewCommand.NOTIFY:
                    // The host's, not the tree's. A window's caption, its icon, where it sits and what
                    // it says outside itself are all the compositor's business, and a host that has no
                    // windows at all -- one panel filling a screen -- legitimately does none of them.
                    if (window != null) window.viewCommand(command, in);
                    break;
                default:
                    break;   // already checked against the vocabulary; nothing to do here
            }
        } catch (RuntimeException failed) {
            CrystalGuiCore.LOGGER.warn("A view command '{}' could not be applied: {}",
                    command, failed.toString());
        }
    }

    private static void onElement(StateMap<Object> in, TreeSource<UIElement> ids,
                                  java.util.function.Consumer<UIElement> body) {
        UIElement target = ids.byId(in.getInt(ViewCommand.NID, -1));
        // A command about an element that has since gone is not an error. The tree moves and messages
        // take time; this is the ordinary end of that race.
        if (target != null) body.accept(target);
    }

    /**
     * Programmatic focus, deliberately — so it rings.
     *
     * <p>{@code :focus-visible} exists to mark focus the user did not place with a pointer, and focus
     * arriving from a server is the clearest instance of that there is. The pointer-focus path
     * ({@code requestPointerFocus}) is for a click and would be a lie here.</p>
     */
    private static void focus(UIElement element) {
        UIDocument window = element.document();
        if (window != null) window.focus().requestFocus(element);
    }

    private static void openMenu(StateMap<Object> in, TreeSource<UIElement> ids) {
        UIElement target = ids.byId(in.getInt(ViewCommand.NID, -1));
        if (!(target instanceof Popover)) return;
        UIElement anchor = ids.byId(in.getInt(ViewCommand.ANCHOR, -1));
        // An anchor is REQUIRED, not optional: a popover exists relative to something, and one opened
        // at no position would land wherever the layout happened to leave it. A server that means "in
        // the middle of the window" can anchor to the root.
        if (anchor == null) return;
        ((Popover) target).showFor(anchor, null);
    }

    private static void tooltip(StateMap<Object> in, TreeSource<UIElement> ids, String text) {
        UIElement target = ids.byId(in.getInt(ViewCommand.NID, -1));
        if (target == null || text.isEmpty()) return;
        Tooltip.attach(target, text);
    }
}
