package com.crystalgui.ui.elements.slot;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * <b>The seam a platform fills in order to draw content CrystalGUI cannot draw itself.</b>
 *
 * <p>Every other element in this engine paints through {@link com.crystalgui.render.CgUiPaintContext} —
 * instanced quads, one material bound for the whole frame. An item stack cannot: on 1.7.10 it is
 * fixed-function GL, on 1.20.1+ it is Minecraft's own core shaders, and on any version a mod may have
 * replaced the renderer for its own items. The host has to draw it, so the host is asked to.</p>
 *
 * <h3>A slot, not a method on {@code CgPlatformService}</h3>
 *
 * <p>{@code CgPlatformService} is the closed bundle: nine methods, no defaults, so the compiler forces a
 * new loader to answer every one. That enforcement is right for services the rendering framework itself
 * requires, and wrong here — a harness with no Minecraft in it genuinely has no item renderer and should
 * not be made to pretend. The rule this follows is the one M12's audit settled: <b>closed for what the
 * framework requires; slots for what its consumers require.</b></p>
 *
 * <h3>Three states, and the middle one is the point</h3>
 *
 * <p>{@link CgService} answers its absent-value rather than null, which alone would mean a forgotten
 * registration renders nothing and says nothing — the exact "live and inert look identical" failure this
 * codebase keeps paying for. So absence is split in two:</p>
 *
 * <table border="1">
 *   <caption>What a slot does, by what the platform said</caption>
 *   <tr><th>State</th><th>How</th><th>Result</th></tr>
 *   <tr><td>Available</td><td>{@code CgPlatform.provide(SERVICE, impl)}</td><td>Native draw.</td></tr>
 *   <tr><td>Declared unneeded</td><td>{@code CgPlatform.provide(SERVICE, UNSUPPORTED)}</td>
 *       <td>Placeholder. No crash — the platform said so on purpose.</td></tr>
 *   <tr><td>Nobody said anything</td><td>&#8212;</td><td><b>{@link #require()} throws.</b></td></tr>
 * </table>
 *
 * <p>A platform with no item renderer installs {@link #UNSUPPORTED} and is done. That is not a
 * workaround: the harness really does not render items, and saying so out loud is what separates it from
 * a Minecraft loader that forgot.</p>
 *
 * <h3>Why the check fires at paint and not at construction</h3>
 *
 * <p>A dedicated server legitimately builds an item slot in order to describe it to a client, and has no
 * GL, no renderer and no business crashing for it. Paint is also the first moment the absence has a
 * consequence — the same reasoning {@link CgService} gives for announcing from {@code get()} rather than
 * sweeping at a lifecycle checkpoint, where a slot whose declaring class had not loaded would be skipped
 * precisely because nobody had touched it.</p>
 */
public interface NativeContentService {

    /**
     * The sentinel a platform installs to say <b>"I do not render this, deliberately."</b>
     *
     * <p>Distinct from the slot being unfilled, and that distinction is the whole fast-fail mechanism:
     * this one is {@link CgPlatform#isProvided} true and {@link #isAvailable()} false, so
     * {@link #require()} passes it through and the slot draws a placeholder instead of throwing.</p>
     */
    NativeContentService UNSUPPORTED = new NativeContentService() {
        @Override public boolean isAvailable() { return false; }
        @Override public NativeContent resolve(String descriptor) { return NativeContent.EMPTY; }
        @Override public void draw(NativeSurface surface, NativeContent content) { }
        @Override public void drawTooltip(NativeContent c, float x, float y, int w, int h) { }
        @Override public String toString() { return "NativeContentService.UNSUPPORTED"; }
    };

    /**
     * The slot. Declared on the contract itself, as {@code CgNetworkChannel.SERVICE} is — fewer files
     * than a companion holder, and the slot is impossible to find without also finding the interface.
     *
     * <p>The absent-value behaves like {@link #UNSUPPORTED} but is deliberately <em>not</em> that
     * constant: {@link CgPlatform#isProvided} is what tells the two apart, and reusing the instance
     * would make an unfilled slot indistinguishable from a deliberate one at every call site that
     * bypassed {@link #require()}.</p>
     */
    CgService<NativeContentService> SERVICE = CgService.of("crystalgui:native-content",
            new NativeContentService() {
                @Override public boolean isAvailable() { return false; }
                @Override public NativeContent resolve(String descriptor) { return NativeContent.EMPTY; }
                @Override public void draw(NativeSurface surface, NativeContent content) { }
                @Override public void drawTooltip(NativeContent c, float x, float y, int w, int h) { }
                @Override public String toString() { return "NativeContentService(absent)"; }
            });

    /**
     * Resolves the installed service, or throws if this platform never declared a position.
     *
     * <p>Call from paint, never from a constructor. Returns an unavailable service unchanged — the
     * caller checks {@link #isAvailable()} and draws a placeholder.</p>
     *
     * @throws IllegalStateException if nothing filled the slot. The message names the slot and both
     *         remedies, because the two are genuinely different decisions and a reader hitting this has
     *         no way to guess which one they meant.
     */
    static NativeContentService require() {
        if (!CgPlatform.isProvided(SERVICE)) {
            throw new IllegalStateException(
                    "No item/fluid renderer is registered on this platform.\n"
                  + "  A CrystalGUI element asked to draw native content, but the platform service slot\n"
                  + "  " + SERVICE.name() + " was never filled, and this platform has not declared that\n"
                  + "  it does not need one. One of these two is missing from the loader's init:\n"
                  + "\n"
                  + "    CgPlatform.provide(NativeContentService.SERVICE, new MyItemRenderer());\n"
                  + "        -- this platform draws items and fluids natively; or\n"
                  + "    CgPlatform.provide(NativeContentService.SERVICE, NativeContentService.UNSUPPORTED);\n"
                  + "        -- this platform deliberately does not, and slots draw a placeholder.\n"
                  + "\n"
                  + "  Failing here rather than drawing nothing is deliberate: a silently blank slot is\n"
                  + "  indistinguishable from an empty one, so a forgotten registration would ship.");
        }
        return CgPlatform.get(SERVICE);
    }

    /**
     * Whether this service can actually draw. {@code false} for {@link #UNSUPPORTED}.
     *
     * <p>Checked per paint rather than cached — a loader may install its renderer after the first UI is
     * built, and last-write-wins on the slot is documented behaviour precisely because mod init order is
     * not guaranteed.</p>
     */
    boolean isAvailable();

    /**
     * Rebuilds a handle from {@link NativeContent#descriptor()}. Never null; answer
     * {@link NativeContent#EMPTY} for anything unrecognised.
     *
     * <p>Unrecognised is normal, not exceptional: a descriptor may name a container this client never
     * opened, or an item from a mod that is no longer installed. Throwing would turn a stale layout into
     * a crash.</p>
     */
    NativeContent resolve(String descriptor);

    /**
     * Draws {@code content} filling {@code surface}, honouring {@link NativeSurface#profile()}.
     *
     * <p>Called with GL already bracketed by {@code CgGlState.hostForeign(...)} and an offscreen target
     * already bound, so the implementation may disturb whatever state its renderer needs without saving
     * anything. What it <em>must</em> do is set up its own projection for the surface's box — {@code core}
     * cannot, having no fixed-function matrix API at all — and set any state Minecraft's own mirror
     * tracks through <em>Minecraft's</em> API rather than through {@code CgGL}, which that mirror cannot
     * see.</p>
     *
     * <p>Draw the host's native decorations too — stack counts, durability bars, cooldown overlays. The
     * promise of this element is that an item looks exactly as it does in a vanilla inventory, including
     * whatever other mods have hooked into that.</p>
     */
    void draw(NativeSurface surface, NativeContent content);

    /**
     * Draws {@code content}'s tooltip at a screen position, in the host's own style.
     *
     * <p>Native rather than reproduced, because an item's real tooltip carries rarity colouring,
     * enchantments, lore and every line other mods contribute through their own tooltip events — none of
     * which any widget of ours can reconstruct.</p>
     *
     * <p>Called after the whole UI tree and the top layer have painted, so an immediate-mode tooltip is
     * not painted over by elements drawn later. The implementation is responsible for keeping the box on
     * screen, which the host's own tooltip renderer already does.</p>
     *
     * <h3>Everything here is CrystalGUI's LOGICAL space, not the host's</h3>
     *
     * <p>The position and the two dimensions are all post-{@code uiScale} logical units, and an
     * implementation should set up a projection for that box rather than reaching for the host's own GUI
     * scale. This is the one native draw with no offscreen target to isolate it — a tooltip's size is
     * decided by its content, so there is nothing to size a target from — which makes it the one place
     * the host draws into our frame and therefore the one place the scale has to be stated.</p>
     *
     * <p>Rendering at the host's scale instead is the failure worth naming: on Minecraft the GUI scale is
     * a user setting that {@code CgUiScreen} deliberately ignores, so a tooltip drawn at it comes out a
     * different size from the UI it is labelling, by however far apart the two happen to be — correct on
     * the machine it was written on and wrong on the next one.</p>
     */
    void drawTooltip(NativeContent content, float x, float y, int logicalWidth, int logicalHeight);
}
