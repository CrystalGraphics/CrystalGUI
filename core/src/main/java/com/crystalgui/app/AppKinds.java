package com.crystalgui.app;

import com.crystalgui.app.editor.CrystalEditor;
import com.crystalgui.app.shadergraph.ShaderGraphEditor;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.NodeKinds;
import com.crystalgui.ui.dom.UINodeRegistry;

/**
 * <b>The application layer's kinds</b> — the two shells that are named by a stylesheet.
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
        // CASCADE-ONLY, both of them: an editor is built by a host with a workspace client and a
        // shader graph editor with a document, and nothing describes either over a wire. What a
        // registration buys here is the TAG -- `ua/workbench.css` names both outright, so without one
        // every rule written for them would match nothing, silently.
        UINodeRegistry.registerTag(CrystalEditor.NAME, NodeContract.INERT);
        UINodeRegistry.registerTag(ShaderGraphEditor.NAME, NodeContract.INERT);
    }
}
