package com.crystalgui.mc;

import com.crystalgui.lifecycle.CgUiLifecycle;
import com.crystalgui.mc.client.CgUiAutoTest;
import com.crystalgui.mc.client.CgUiInput;

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
        
        CgUiLifecycle.register();
        CgUiInput.register();
        CgUiAutoTest.register();
    }
}
