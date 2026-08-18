package com.crystalgui.mc;

/**
 * The server-side half: nothing.
 *
 * <p>Everything CrystalGUI does on 1.7.10 is a screen, and a dedicated server has no screens. This
 * class exists so {@link ClientProxy} has something to extend and so the client-only classes are
 * unreachable from common code — {@code CgUiScreen} imports {@code GuiScreen}, which does not exist
 * server-side, and a static reference from a common class is enough to fail class loading there.</p>
 *
 * <p>{@code core/} is headless-clean by construction (its build fails on a {@code net.minecraft.*}
 * import), and that property is worth not undoing at the loader.</p>
 */
public class CommonProxy {

    /** FML preInit. Nothing common to do. */
    public void preInit() {
    }

    /** FML init. Nothing common to do. */
    public void init() {
    }
}
