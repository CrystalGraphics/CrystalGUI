package com.crystalgui.desktop;

import com.crystalgui.desktop.window.WindowIcon;
import com.crystalgui.desktop.taskbar.WindowThumbnail;
import com.crystalgui.desktop.taskbar.WindowPreview;
import com.crystalgui.desktop.switcher.WindowSwitcher;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.taskbar.Taskbar;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.NodeKinds;
import com.crystalgui.ui.dom.UINodeRegistry;

/**
 * <b>The desktop layer's kinds</b> — the compositor's own nodes.
 *
 * <p>Its own service rather than an entry in {@code Widgets}, because the point of
 * {@link NodeKinds} is that a LAYER speaks for itself: {@code widget} does not know what a window is,
 * and a registry importing both would be the upward reference {@code LayeringTest} refuses.</p>
 */
public final class DesktopKinds implements NodeKinds {

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public DesktopKinds() {
    }

    @Override
    public void register() {
        UINodeRegistry.register(WindowIcon.NAME, WindowIcon::new, NodeContract.INERT);
        // THE THREE TAGS `ua/desktop.css` NAMES. A widget's cascade identity is its tag, so an
        // unregistered one matches nothing at all -- the ToolWindowFrame lesson, which cost a whole
        // unstyled widget. `windowswitcher` and `windowpreview` are deliberately absent: no shipped rule
        // names either, and both are built by the compositor rather than decoded from a description.
        UINodeRegistry.register(Desktop.NAME, Desktop::new, NodeContract.INERT);
        UINodeRegistry.register(Taskbar.NAME, Taskbar::new, NodeContract.INERT);
        // A frame takes a title, so it has no no-argument factory: a description that named `window`
        // would have to carry one, and nothing describes a window over a wire today. Registered with a
        // factory that opens an untitled frame rather than left out, so the TAG exists for the cascade.
        UINodeRegistry.register(WindowFrame.NAME, () -> new WindowFrame(""), NodeContract.INERT);
        // No shipped rule names these three -- their sheets key on classes -- and they are registered
        // regardless, because a concrete node declaring no kind inherits `crystalgui:element` and would
        // match every bare `element` rule there is. The switcher is built by the compositor and takes
        // one, so a decoded one has no desktop: it is inert rather than useless, which is what a
        // description of a switcher would mean anyway.
        UINodeRegistry.register(WindowSwitcher.NAME, () -> new WindowSwitcher(null), NodeContract.INERT);
        UINodeRegistry.register(WindowPreview.NAME, WindowPreview::new, NodeContract.INERT);
        UINodeRegistry.register(WindowThumbnail.NAME, WindowThumbnail::new, NodeContract.INERT);
        // A RESIZE HANDLE IS NOT A KIND. It is built by `Resizer.install` for a resizable node and never
        // decoded from a description, and no shipped rule names the tag -- the sheet keys on
        // `.__resizer-*__` classes. Registering one with a factory that throws is a registration whose
        // only effect is to fail the coverage walk that builds every kind.
    }
}
