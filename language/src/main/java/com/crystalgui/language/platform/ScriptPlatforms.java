package com.crystalgui.language.platform;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * The one registered {@link ScriptPlatform}, and the only way to reach it.
 *
 * <h3>A slot in the platform stack, not a registry of its own</h3>
 *
 * <p>This used to hold a static field and a setter, which made it a <b>second platform registry</b>
 * beside {@code CgPlatform} — the exact shape this project deleted once already. {@code CrystalGuiCore}
 * had four such fields, and the reason they went is that a loader has to find every registry there is:
 * it can wire up one and forget another, leaving a working backend beside a dead service with nothing to
 * report it. Rebuilding that one layer out is no better for being smaller.</p>
 *
 * <p>So the declaration below is a {@link CgService} slot. Registration goes through the same stack every
 * other platform service does, {@code CgService.declared()} can print this one along with the rest, and
 * a loader has one place to look. CrystalGraphics never names {@link ScriptPlatform} — a slot is generic
 * in its contract, which is what lets a consumer own a service the framework has never heard of.</p>
 *
 * <h3>Absent is the default and answers {@link ScriptPlatform#NONE}</h3>
 *
 * <p>Never null, and stated <b>once</b> — here, beside the contract, rather than at each call site. That
 * is the difference between a slot and a lookup returning an {@code Optional}: the fallback is part of
 * what this service means, so consumers that each supplied their own could disagree about it. A caller
 * that had to null-check a platform is a caller that will forget, and the behaviour it would forget into
 * — read the classloader, no mappings — is exactly what {@code NONE} already does. Off a Minecraft host
 * this class is invisible.</p>
 */
public final class ScriptPlatforms {

    /**
     * The slot. Public because a loader provides into it directly:
     * {@code ScriptPlatforms.SERVICE.provide(new Mc1710ScriptPlatform())}.
     */
    public static final CgService<ScriptPlatform> SERVICE =
            CgService.of("crystalgui:script-platform", ScriptPlatform.NONE);

    private ScriptPlatforms() {
    }

    /**
     * Registers the running platform. Called once, from a loader's init.
     *
     * <p>Kept beside {@link #SERVICE} because most callers read rather than write, and a null here has
     * always meant "back to none" — which {@code CgPlatform.provide(slot, null)} also means, so the two
     * agree. A loader may equally call the façade directly; this is the same write.</p>
     */
    public static void register(ScriptPlatform platform) {
        CgPlatform.provide(SERVICE, platform);
    }

    /** The running platform, never null. */
    public static ScriptPlatform current() {
        return CgPlatform.get(SERVICE);
    }

    /** Restores the no-platform default. For tests that registered a fake. */
    public static void reset() {
        CgPlatform.provide(SERVICE, null);
    }
}
