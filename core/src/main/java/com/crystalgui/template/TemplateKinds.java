package com.crystalgui.template;

import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.NodeKinds;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * The one kind this layer declares: {@code instance}, a document placed inside another.
 *
 * <p>Its own service rather than an entry in {@code Widgets}, for the reason {@link NodeKinds} exists: a
 * layer speaks for itself, and the template loader is not part of the widget layer.</p>
 */
public final class TemplateKinds implements NodeKinds {

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public TemplateKinds() {
    }

    @Override
    public void register() {
        // INERT and buildable: an instance travels as one node -- the template it places is the far
        // side's to inflate, which is what makes a window that places one forty times describe it once.
        UIElementRegistry.register(TemplateInstance.NAME, TemplateInstance::new, NodeContract.INERT);
    }
}
