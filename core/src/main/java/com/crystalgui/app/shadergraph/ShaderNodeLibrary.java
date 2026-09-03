package com.crystalgui.app.shadergraph;

import com.crystalgui.app.shadergraph.node.ShaderColorFieldWidget;
import com.crystalgui.app.shadergraph.node.ShaderVectorFieldWidget;

import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.graph.NodeTypeRegistry;

/**
 * The shader node library, and the field widgets that make its values editable.
 *
 * <h3>Why this is a class and not two lines in {@link ShaderGraphBridge}</h3>
 *
 * <p>{@code asNodeLibrary} used to call {@link ShaderColorFieldWidget#install()} and
 * {@link ShaderVectorFieldWidget#install()} itself, and the reasoning it gave for the <em>timing</em>
 * was right and is kept verbatim below: building a shader node library is the moment the shader
 * domain's vocabulary has to exist, and a colour field silently falling back to a GLSL text box is
 * the kind of miss nobody reports as a bug.</p>
 *
 * <p>What was wrong was the placement. Those two calls were the only thing in
 * {@code ShaderGraphBridge} that named a widget, and they made an otherwise pure map from
 * {@code GraphDocument} onto CrystalGraphics' {@code CgShaderGraph} into a class that cannot be
 * compiled without the UI — so the compile bridge could not sit below the widgets that use it, and a
 * headless consumer wanting only the GLSL had to drag two controls in with it. <b>Keep the timing,
 * move the knowledge:</b> the library is assembled here, one call, and the bridge maps.</p>
 */
public final class ShaderNodeLibrary {

    private ShaderNodeLibrary() {
    }

    /**
     * The node library, with every field kind editable.
     *
     * <p>Registering here rather than at a call site, for the same reason the port types are: building
     * a shader node library is the moment the shader domain's vocabulary has to exist, and a colour or
     * vector field silently falling back to a GLSL text box is the kind of miss nobody reports as a
     * bug. {@code NodeFieldWidgets} has no generic default for either — see its class javadoc — so
     * skipping this is a visible regression (a text field) rather than a silent one.</p>
     *
     * <p>Idempotent: both installers replace their registration rather than adding to it, so opening a
     * second graph costs two map writes.</p>
     */
    public static NodeTypeRegistry of(CgShaderNodeRegistry shaderNodes) {
        ShaderColorFieldWidget.install();
        ShaderVectorFieldWidget.install();
        return ShaderGraphBridge.asNodeLibrary(shaderNodes);
    }
}
