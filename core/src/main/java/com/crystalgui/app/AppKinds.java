package com.crystalgui.app;

import com.crystalgui.app.shadergraph.ShaderGraphEditor;
import com.crystalgui.app.uibuilder.canvas.Artboard;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.NodeKinds;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>The application layer's kinds</b> — what a stylesheet names and a host builds.
 *
 * <p>Its own service rather than an entry in {@code WorkbenchKinds}, for the reason
 * {@link NodeKinds} exists: a LAYER speaks for itself, and the workbench does not know what an
 * application is. {@code LayeringTest} puts {@code app} above everything.</p>
 */
public final class AppKinds implements NodeKinds {

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public AppKinds() {
    }

    @Override
    public void register() {
        // CASCADE-ONLY: a shader graph editor is built with a document and nothing describes one
        // over a wire. What a registration buys here is the TAG -- `ua/workbench.css` names it
        // outright, so without one every rule written for it would match nothing, silently.
        //
        // The editor's own shell used to be the other entry. It is `WorkbenchApplication` now and
        // registered by `WorkbenchKinds`, because the element is the ENGINE's and what is left in
        // `app/` is a manifest -- which is data, and declares no kind at all.
        UIElementRegistry.registerTag(ShaderGraphEditor.NAME, NodeContract.INERT);
        // The builder's page frame, for the same reason: `artboard` is what a theme names to
        // draw the page edge, and a kind nothing registered matches nothing.
        UIElementRegistry.registerTag(Artboard.NAME, NodeContract.INERT);
    }
}
