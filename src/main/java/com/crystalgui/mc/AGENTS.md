# mc — Minecraft Host Adapter (Decoupled)

> Root guide: [`AGENTS.md`](../../../../../AGENTS.md)

## Purpose

Version-specific adapter utilities that bridge LWJGL 2 input/rendering into CrystalGUI's platform-agnostic event/input model. These adapters are **stateless utilities** — they do not extend Minecraft GUI classes, do not own `UIContainer` instances, and make no assumptions about how the host surface is managed.

CrystalGUI never defines itself as a `GuiScreen`. The host decides when to drain input and when to render.

## Files

| File | Role |
|------|------|
| `CgUiInputAdapter.java` | Stateless input translation: drain-based (`drainMouseEvents`, `drainKeyboardEvents`) and per-event (`forwardCurrentMouseEvent`, `forwardCurrentKeyEvent`, `forwardKeyEvent`) forwarding; shared `dispatchCurrentMouseEvent` helper eliminates duplication; caller-supplied coordinate transform and key filter |
| `CgUiRenderAdapter.java` | Computes layout + renders a `UIContainer` into the current GL surface; caller controls surface dimensions |
| `CgUiForgeEventHandler.java` | Ready-to-use Forge `@SubscribeEvent` handler: subscribes to `InputEvent.MouseInputEvent`, `InputEvent.KeyInputEvent`, and `RenderGameOverlayEvent.Post` to wire a `UIContainer` through the Forge event bus |
| `LwjglKeyTranslator.java` | Translates LWJGL 2 key codes → `CgUiKeyCodes` constants, reads modifier state → `Modifiers` bitmask |

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  Host (Forge event handler, GuiScreen, overlay, ...) │ ← MC-specific
│                                                      │
│  @SubscribeEvent onMouseInput(MouseInputEvent) {     │
│    CgUiInputAdapter.forwardCurrentMouseEvent(        │
│        container, transform);                        │
│  }                                                   │
│  @SubscribeEvent onKeyInput(KeyInputEvent) {         │
│    CgUiInputAdapter.forwardCurrentKeyEvent(          │
│        container, keyFilter);                        │
│  }                                                   │
│  @SubscribeEvent onRenderOverlay(Post event) {       │
│    renderAdapter.renderContainer(container, w, h);   │
│  }                                                   │
└──────────┬───────────────────────────────────────────┘
           │ calls
           ▼
┌──────────────────────────────────────────────────────┐
│  CgUiInputAdapter (stateless)                        │ ← mc/ package
│  CgUiRenderAdapter (layout + render invocation)      │
│  LwjglKeyTranslator (key code + modifier mapping)    │
└──────────┬───────────────────────────────────────────┘
           │ calls
           ▼
┌──────────────────────────────────────────────────────┐
│  UIContainer / UiInputManager /                      │ ← core (agnostic)
│  FocusManager / draw-list pipeline                   │
└──────────────────────────────────────────────────────┘
```

## Input Ingress Model

Forge fires `InputEvent.MouseInputEvent` and `InputEvent.KeyInputEvent` **inside** the `Mouse.next()` / `Keyboard.next()` while-loops in `Minecraft.runTick()`. This means:

- Each event fires once per LWJGL queue entry (per-event, not per-frame).
- The current LWJGL event data (`Mouse.getEventX()`, `Keyboard.getEventKey()`, etc.) is still available when the subscriber runs.
- `CgUiInputAdapter.forwardCurrentMouseEvent()` and `forwardCurrentKeyEvent()` read that current event data and translate it into CrystalGUI input calls.

This is preferred over `ClientTickEvent` because:
1. Events arrive in sync with the actual LWJGL drain, not delayed to end-of-tick.
2. No event batching or reordering — CrystalGUI sees events in the same order Minecraft processes them.
3. Mouse move + button + scroll are all handled per-event with correct coordinates.

## CgUiInputAdapter API Surface

| Method | When to use |
|--------|------------|
| `drainMouseEvents(container, transform)` | Caller owns the frame loop (harness, custom overlay) |
| `drainKeyboardEvents(container, keyFilter)` | Caller owns the frame loop |
| `drainAllEvents(container, transform, keyFilter)` | Convenience: both queues |
| `forwardCurrentMouseEvent(container, transform)` | Forge `InputEvent.MouseInputEvent` (per-event, LWJGL data still current) |
| `forwardCurrentKeyEvent(container, keyFilter)` | Forge `InputEvent.KeyInputEvent` (per-event, LWJGL data still current) |
| `forwardKeyEvent(container, lwjglKey, ch, pressed)` | Caller already extracted the event (harness `InputPauseHandler`) |

## Key Rules

- **No Minecraft class inheritance** — CrystalGUI does not extend `GuiScreen` or any other Minecraft class.
- **Per-event input forwarding** via Forge `InputEvent.*` is the recommended MC integration model.
- **Caller owns the coordinate transform** — `CoordinateTransform` lets the host supply `ScaledResolution` scaling, raw pixel passthrough, or any custom mapping.
- **Caller owns key filtering** — `KeyFilter` lets the host intercept keys (e.g. ESC to close) before CrystalGUI sees them.
- **Caller owns lifecycle** — `UIContainer` creation, disposal, and render timing are the host's responsibility.
- **LWJGL imports are allowed** in this package (it is version-specific, not core).
- **`LwjglKeyTranslator`** is shared between the MC adapters and the harness's `UiInputForwarder`.

## Usage — Forge Event Bus (Recommended)

```java
// Register on both buses (input events are on FML bus, render events on Forge bus):
CgUiForgeEventHandler handler = new CgUiForgeEventHandler();
MinecraftForge.EVENT_BUS.register(handler);
FMLCommonHandler.instance().bus().register(handler);

// Attach a container to start forwarding:
handler.attach(myContainer);
handler.setKeyFilter((key, ch, pressed) -> {
    if (pressed && key == Keyboard.KEY_ESCAPE) { /* close UI */ return true; }
    return false;
});

// Detach when done:
handler.detach();
```

## Usage — Direct Adapter (Custom Integration)

```java
// For hosts that own their own frame loop (e.g. GuiScreen, harness):
CgUiInputAdapter.drainAllEvents(container,
    (rawX, rawY) -> new float[]{ rawX / scale, (displayHeight - rawY) / scale },
    (key, ch, pressed) -> pressed && key == Keyboard.KEY_ESCAPE
);
new CgUiRenderAdapter().renderContainer(container, width, height);
```
