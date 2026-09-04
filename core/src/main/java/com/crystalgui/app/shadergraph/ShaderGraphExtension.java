package com.crystalgui.app.shadergraph;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.WorkbenchExtension;

/**
 * The shader graph, as a feature a manifest can enable.
 *
 * <p>{@link ShaderGraphContribution} already said everything the package declares about itself — which
 * extension opens as a graph, how to build one, what its generated source is, what it tells the
 * inspector. What it could not say was <b>who turns it on</b>: an application named the class and called
 * {@code register(workbench)}, so enabling it was a line of Java in a product rather than an id in a
 * list, and the only way to have a workbench without it was to write a second product.</p>
 *
 * <h3>D7: an action on an editor, never a second shell</h3>
 *
 * <p>A graph-only product is a second <em>manifest</em> naming a shorter list of extensions — not a
 * second application class, and not a second dock. Which is the point of the id being the unit.</p>
 */
public final class ShaderGraphExtension implements WorkbenchExtension {

    public static final String ID = "crystalgui:shadergraph";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(WorkbenchContext workbench) {
        return ShaderGraphContribution.register(workbench);
    }
}
