package com.crystalgui.mc;

import com.crystalgui.language.platform.ScriptPlatforms;
import com.crystalgui.mc.client.CgUiAutoTest;
import com.crystalgui.mc.client.CgUiInput;
import com.crystalgui.mc.script.Mc1710ScriptPlatform;

/**
 * The client half: register the key binding and the input pump.
 *
 * <p>Deliberately does no GL work and touches no CrystalGraphics resource. Every GL object CrystalGUI
 * owns is built lazily on first paint, and the paint context registers itself with
 * {@code CgGraphicsLifecycle} from its own class initialiser — so there is nothing to set up here, and
 * anything that were set up would run before a context exists.</p>
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit() {
        super.preInit();
        // BEFORE anything language-related, and before the editor can be opened. Registration is a
        // statement of facts about this platform -- a byte route, a cache path, mapping coordinates --
        // so it costs nothing and has no ordering requirement of its own beyond being first.
        ScriptPlatforms.register(new Mc1710ScriptPlatform());
        CgUiInput.register();
        CgUiAutoTest.register();
    }
}
