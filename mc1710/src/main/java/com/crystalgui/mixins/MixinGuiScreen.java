package com.crystalgui.mixins;

import com.crystalgui.mc.client.CgUiOverlayInput;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets pinned CrystalGUI windows take input while somebody else's screen is open — M16 §26.4a.
 *
 * <h3>Why this is bytecode and not an event</h3>
 *
 * <p><b>1.7.10's Forge has no screen input event.</b> {@code GuiScreenEvent} carries {@code InitGui},
 * {@code DrawScreen} and {@code ActionPerformed} and nothing else — {@code MouseInputEvent} and
 * {@code KeyboardInputEvent} arrived in 1.8. So on this version alone there is nothing to subscribe to,
 * and every later version this project targets gets a cancellable event and needs no mixin at all. That
 * asymmetry is the whole reason the decision lives in {@code ScreenOverlay} in {@code core/}: this class
 * is a courier, and the rule it carries has one implementation for every Minecraft version.</p>
 *
 * <p>Polling {@code Mouse}/{@code Keyboard} from a render hook cannot substitute <em>on its own</em>,
 * and understanding why is what shapes this class. {@code GuiScreen.handleInput} drains the LWJGL event
 * queue from {@code Minecraft.runTick}, and <b>whoever drains it first is the only one who sees it</b> —
 * so an unaided render hook finds an empty queue and the foreign screen has already acted. What works is
 * both halves together: <b>this cancels the tick-time drain, and {@code CgUiOverlayInput} does the
 * draining on the render tick instead.</b> Cancelling without replacing would take the screen's input
 * away entirely; replacing without cancelling would never see an event.</p>
 *
 * <h3>It drains here AND on the render tick, and both are required</h3>
 *
 * <p><b>{@code handleInput} is called from {@code Minecraft.runTick}, which is driven by
 * {@code new Timer(20.0F)} — so a screen's input is pumped at 20 Hz while the game renders at 60+.</b>
 * For a click that is invisible; for anything CONTINUOUS it is not. So the real pump is on the render
 * tick, where both UIs get per-frame input. {@code CgUiScreen} reached the same place for itself and
 * says so in {@code pumpInput}.</p>
 *
 * <p><b>But cancelling without draining here breaks the inventory, and that is not obvious.</b>
 * {@code runTick} does not stop at {@code handleInput}: forty lines later it runs its OWN
 * {@code while (Mouse.next())} for in-world keybinds, guarded by
 * {@code currentScreen == null || currentScreen.allowUserInput} — and {@code GuiInventory} and
 * {@code GuiContainerCreative} both set that flag. In vanilla that block finds an empty queue, because
 * {@code handleInput} has already drained it. Cancel {@code handleInput} without replacing the drain and
 * the queue is still full when it gets there, so it eats the events: over an inventory, the pinned
 * window receives nothing AND the inventory's own slot clicks are lost.</p>
 *
 * <p>Chat hides this completely — it leaves {@code allowUserInput} false, so that block is skipped and
 * the render-tick drain gets everything. Testing over chat proves nothing about the inventory.</p>
 *
 * <p>So both: this drains whatever arrived since the last frame (leaving the block below an empty queue,
 * exactly as vanilla does), and the render tick drains again for everything that arrives between ticks.
 * They are the same function and neither is a duplicate of the other.</p>
 *
 * <p><b>When nothing is pinned this costs one boolean read.</b> {@code CgUiOverlayInput.wants()} answers
 * false and the method returns without cancelling, so Minecraft's own path runs untouched — which is
 * every frame of every session in which nobody has pinned anything.</p>
 */
@Mixin(GuiScreen.class)
public abstract class MixinGuiScreen {

    /**
     * @param ci cancelled only when the desktop is actually taking input, never otherwise
     */
    @Inject(method = "handleInput", at = @At("HEAD"), cancellable = true)
    private void cgui$handleInput(CallbackInfo ci) {
        if (!CgUiOverlayInput.wants()) return;
        // DRAIN, THEN CANCEL. Draining is what leaves runTick's own in-world loop an empty queue, which
        // is the state vanilla hands it whenever a screen is open.
        CgUiOverlayInput.drainInto((GuiScreen) (Object) this);
        ci.cancel();
    }
}
