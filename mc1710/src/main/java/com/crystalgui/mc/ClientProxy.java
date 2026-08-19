package com.crystalgui.mc;

import com.crystalgraphics.platform.CgPlatform;
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
        // INTO THE PLATFORM STACK, not beside it. `ScriptPlatforms.SERVICE` is a `CgService` slot, so
        // this is the same registry every other platform service goes through and `CgService.declared()`
        // can print it alongside them -- rather than a second, parallel registry a loader has to know to
        // look for. Registration is a statement of facts about this platform (a byte route, a cache
        // path, mapping coordinates), so it costs nothing and has no ordering requirement of its own.
        //
        // CLIENT-side only because of ONE member: `cacheRoot()` reads `Minecraft.getMinecraft().mcDataDir`.
        // The other four are installation-level, so when server-side scripting lands this moves to
        // CommonProxy and that one method grows a side-aware answer.
        CgPlatform.provide(ScriptPlatforms.SERVICE, new Mc1710ScriptPlatform());
        CgUiInput.register();
        CgUiAutoTest.register();
    }
}
