package com.crystalgui.gauntlet;

import java.util.Set;

import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.net.protocol.CgProtocols;
import com.crystalgraphics.serialization.CgContentHash;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * {@code crystalgui:server}: CrystalGUI's half of a dedicated server, beside CrystalGraphics' {@code crystalgraphics:server}
 * (the stack, and that no client class loaded): the workspace protocol is bound before any connection opens, and a UI
 * description round-trips with no GL anywhere.
 *
 * <pre>{@code
 * -Dcrystalgraphics.gauntlet=crystalgraphics:server,crystalgui:server
 * }</pre>
 */
public final class ServerTest extends CgGauntletController {

    @Override
    protected void onStateChange(CgGauntletState from, CgGauntletState to) {
        if (to.equals(CgGauntletState.LOADING) || to.equals(CgGauntletState.INITIALIZED)) return;
        if (!to.equals(CgGauntletState.SERVER)) {
            fail("server.dedicated", "a dedicated server's test, run in state " + to);
            return;
        }
        // A connection binds only contributors registered before it opens, and no peer exists yet: the last moment the
        // set can be wrong and the first at which it certainly is not.
        Set<String> contributors = CgProtocols.contributors();
        check("server.workspace-protocol", contributors.contains("workspace"), "contributors " + contributors);
        checkDescriptionRoundTrip();
        endTest();
    }

    /** Content-addressed, so two encodes hashing the same asserts determinism, not merely that the codec ran. */
    private void checkDescriptionRoundTrip() {
        String detail;
        boolean ok = false;
        try {
            UIElementRegistry.bootstrap();
            UIElement root = new UIElement();
            root.setId("smoke-root");
            root.addClass("panel");
            UIElement child = new UIElement();
            child.setId("smoke-child");
            root.append(child);

            Object encoded = new UIElementMirror<>(CgPlainOps.INSTANCE).describe(root);
            String hashA = CgContentHash.of(CgPlainOps.INSTANCE, encoded);
            String hashB = CgContentHash.of(CgPlainOps.INSTANCE, new UIElementMirror<>(CgPlainOps.INSTANCE).describe(root));
            UIElement decoded = new UIElementMirror<>(CgPlainOps.INSTANCE).decode(encoded);

            boolean stable = hashA.equals(hashB);
            boolean shape = decoded != null && "smoke-root".equals(decoded.id()) && decoded.children().size() == 1;
            ok = stable && shape;
            detail = "hash " + hashA + (stable ? "" : ", unstable across two encodes") + (shape ? "" : ", decoded wrong");
        } catch (Throwable failed) {
            detail = String.valueOf(failed);
        }
        check("server.description-round-trip", ok, detail);
    }
}
