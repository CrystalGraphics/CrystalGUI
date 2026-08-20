package com.crystalgui.mc;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgui.lifecycle.CgUiLifecycle;
import com.crystalgui.mc.client.CgUiAutoTest;
import com.crystalgui.mc.client.CgUiInput;
import com.crystalgui.mc.client.Mc1710Workspace;
import com.crystalgui.mc.platform.service.script.ScriptService1710;

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
        // INTO THE PLATFORM STACK, not beside it. `ScriptServices.SERVICE` is a `CgService` slot, so
        // this is the same registry every other platform service goes through and `CgService.declared()`
        // can print it alongside them -- rather than a second, parallel registry a loader has to know to
        // look for. Registration is a statement of facts about this platform (a byte route, a cache
        // path, mapping coordinates), so it costs nothing and has no ordering requirement of its own.
        //
        // CLIENT-side only because of ONE member: `cacheRoot()` reads `Minecraft.getMinecraft().mcDataDir`.
        // The other four are installation-level, so when server-side scripting lands this moves to
        // CommonProxy and that one method grows a side-aware answer.
        CgPlatform.provide(ScriptServices.SERVICE, new ScriptService1710());
        // MAPPINGS ACQUIRED INSIDE A JOB, so the fetch reports into the status bar instead of being a
        // silent stall on first launch. PlatformMappings does not reach for the scheduler itself and must
        // not: UIWindow.paintFrame is the only thing that drains it, so a dedicated server -- which runs
        // scripts and needs readable names to compile them -- would queue the fetch for ever. Threading
        // is the caller's decision, and this caller has a UI.
        // CLAIMED NOW, DONE LATER, and the order is the whole point. The job does not run until the
        // scheduler is drained -- the first CrystalGUI paint -- and anything touching the mappings before
        // then would take the lazy daemon path instead. Both acquire correctly; only this one draws a bar,
        // so without claiming here which one ran was decided by whatever asked first.
        //
        // Safe to defer because a claim made here is always honoured: the job is already submitted, and a
        // client that never opens the editor never needs a mapping.
        if (PlatformMappings.claim()) {
            JobScheduler.shared().job(JobKey.of(ClientProxy.class, "mappings"), JobLane.BACKGROUND,
                    context -> {
                        PlatformMappings.acquireClaimed(context.progress(), context::isCancelled);
                        return null;
                    }).submit();
        }

        // LANGUAGES NOW, not on the first F6. Measured at 443 ms -- the engine band's loader, six
        // grammars and a native -- and it was being paid on a keystroke as part of a four-second freeze.
        // This is the same thread, so nothing becomes concurrent; it becomes EARLY, while a loading
        // screen is already up. It also starts the engines warming, which is the larger win: ECJ's first
        // analysis costs 515 ms and its second 11, and in a client the caller who paid it was a restored
        // editor tab on the frame after F6. @see Mc1710Workspace#registerLanguages
        Mc1710Workspace.registerLanguages();

        // AND THE GL WARM-UP, which cannot start itself.
        //
        // CgUiLifecycle is registered from CgUiPaintContext's STATIC INITIALISER, which runs on the first
        // paint -- so the hook that exists to warm the paint context was, by construction, never reached
        // until the paint context had already been built. Measured: adding the warm-up to onInit changed
        // the first frame's material bind by nothing at all, 286 ms before and 300 after.
        //
        // register() is public and idempotent precisely for this, and addListener delivers onInit
        // immediately when a context is already live -- which it is by FML init, since Minecraft creates
        // the display before mods initialise.
        CgUiLifecycle.register();

        CgUiInput.register();
        CgUiAutoTest.register();
    }
}
