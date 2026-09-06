package com.crystalgui.desktop.window;

import com.crystalgui.ui.dom.UIElement;

import javax.annotation.Nullable;

/**
 * Content that has chrome of its own for the caption of the window it is in —
 * <b>client-side decorations</b>, the pattern every desktop toolkit ends up with.
 *
 * <h3>The problem it solves</h3>
 * <p>An application with its own top bar, put inside a window, has <b>two headers</b>: the window's
 * caption and its own. Two rows of furniture stacked on each other, one of them nearly empty, eating
 * height that belongs to the document. Every mature toolkit answers it the same way — the application
 * puts its chrome <em>in</em> the caption:</p>
 * <ul>
 *   <li>GTK's {@code GtkHeaderBar}, which is the canonical name for it: the app owns the title bar and
 *       the window manager contributes only the buttons.</li>
 *   <li>VS Code's {@code window.titleBarStyle: "custom"} with {@code menuBarVisibility: "compact"} —
 *       the menu becomes a hamburger inside the caption.</li>
 *   <li>IntelliJ's New UI, where the main menu, the project widget and the run configurations all live
 *       in the title bar row.</li>
 *   <li>WinUI's {@code ExtendsContentIntoTitleBar} plus {@code SetTitleBar(element)}, which is the same
 *       arrangement stated as an API.</li>
 * </ul>
 *
 * <h3>ONE element, moved — never a copy, and never a "hide yours" flag</h3>
 * <p>The chrome an implementer returns is <b>reparented</b> into the caption and put back when the
 * window lets go of it. That is what makes the two-headers problem disappear rather than be hidden:
 * there is only ever one menu bar, and it is either in the application's own layout or in the caption.
 * A flag that told the content to hide its header would leave a second, invisible one — and every
 * listener, command and piece of state on it would then exist twice or in the wrong place.</p>
 *
 * <p>It is the rule {@code plan/shell-windowing.md} already states for the dock↔window bridge, arriving one
 * level up: <i>"the instance is the same element in all three presentations; a presentation change is
 * a reparent of that instance"</i>.</p>
 *
 * <h3>What stays the window's</h3>
 * <p>The icon, the title and the controls, and — the part that is easy to lose — <b>the drag</b>. The
 * caption's move gesture is target-only, so anything hit-testable the application puts there keeps its
 * own presses and the space left over still drags the window. That is WinUI's "drag region" by
 * construction rather than by configuration, and it is why the chrome does not need to opt out of
 * anything.</p>
 */
public interface WindowChrome {

    /**
     * The element to host in the window's caption, or {@code null} for none.
     *
     * <p>Must be a real element in this content's own tree — it is moved, so returning something built
     * on demand would put a fresh one in the caption every time and orphan the last.</p>
     */
    @Nullable
    UIElement captionChrome();
}
