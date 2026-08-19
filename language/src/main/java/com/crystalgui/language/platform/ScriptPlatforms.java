package com.crystalgui.language.platform;

import com.crystalgraphics.platform.CgService;

/**
 * <b>The declaration of the script-platform slot.</b> Nothing else — read and written through
 * {@code CgPlatform}.
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
     * The slot. The only member here, because a wrapper around it would be a second way to say the same
     * thing — and a second way is how two callers end up disagreeing about which one is authoritative.
     *
     * <pre>{@code
     * CgPlatform.provide(ScriptPlatforms.SERVICE, new ScriptService1710());  // a loader, once
     * CgPlatform.get(ScriptPlatforms.SERVICE).liveBytes();                   // everyone else
     * }</pre>
     */
    public static final CgService<ScriptPlatform> SERVICE =
            CgService.of("crystalgui:script-platform", ScriptPlatform.NONE);

    private ScriptPlatforms() {
    }
}
