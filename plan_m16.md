# M16 — Pinned windows everywhere: one paint decision, and interaction over foreign GUIs

Follows W14 in `plan_windowing.md`, which shipped pinned windows and the HUD. Two things came out of
using it, and they have the same root.

1. **A visible flicker** when the desktop closes: the pinned window disappears for several frames before
   the HUD picks it up.
2. **Pinned windows are display-only over the game, and should be INTERACTIVE over another Minecraft
   GUI** — pin a window, press `T` for chat, and be able to click into the window while chat is open.

The root is the same: **the paint path is chosen by which screen is open, not by what state the desktop
is in.** Every handoff between those paths is a frame nobody paints, and every new situation needs a
fourth path bolted on beside the three.

## Status

| § | Item | State |
|---|---|---|
| 26.1 | The flicker | **done** — and the probe was never needed: the fix is structural and the result was observed directly (pin, Escape, no dropped frames). It took TWO changes, not one — see R1 |
| 26.2 | `DesktopPresentation` — one answer to "what is on screen, and is it live" | **done** — four arms, three properties (whole desktop / top layer / input) read off the value. `paintFrame` is the `DESKTOP` arm by its old name, kept for hosts with no screens |
| 26.3 | Paint over a foreign GUI | **done** — `GuiScreenEvent.DrawScreenEvent.Post`, guarded against our own screen |
| 26.4 | **The loader seam** — `ScreenOverlay`, arbitration in `core/` | **done** — a concrete class rather than an interface, since there is one implementation and loaders call in. Every parameter a primitive; no Minecraft type crosses |
| 26.4a | Input over a foreign GUI — the mixin | **done** — and it turned out to be TWO halves rather than one, which the plan had not seen. See R2 |
| 26.5 | Mouse arbitration | **done** — capture outranks the hit test, moves always delivered and never consumed. One bug found in the wild, R3 |
| 26.6 | Keyboard ownership | **done** — focus-follows-click, released on a press outside and on the foreign screen closing. One bug found in the wild, R3 |
| 26.7 | The top layer comes back | **done** — `paintsTopLayer()` on the presentation |
| 26.8 | Coordinates | **done** — the same bottom-up-to-top-down conversion `CgUiInput.pumpMouse` uses |
| 26.9 | Pause | **recorded, not code** — a screen that pauses the game stops server ticks, so a pinned Run console over an inventory keeps its caret and receives no output. Not ours to fix |
| 26.10 | Which screens | **all of them** — no exclusions written, and none wanted yet |

---

## The one idea

**Display-only was never about "not our screen". It was about the cursor being grabbed.**

W14 wrote the rule as *"in game the cursor is grabbed and the keyboard is the game's, so a HUD window can
receive no input"*, and every clause of that is true. What it attached the rule to is wrong: it attached
it to *whose screen is up*. The actual precondition is in the first half of the sentence — **the cursor**.
And whenever **any** `GuiScreen` is open, Minecraft ungrabs the cursor and has a real mouse position,
whether that screen is ours or the chat box.

So the rule generalises, and gets shorter:

> **A pinned window is interactive exactly when a cursor exists.**

Three states, and the third is the new one:

| Minecraft state | cursor | what is on screen | input |
|---|---|---|---|
| `CgUiScreen` open | free | the whole desktop | full |
| **another `GuiScreen` open** | free | pinned windows only | **full** |
| no screen | grabbed | pinned windows only | none |

Note what is *not* a distinguishing axis: whether the screen belongs to us. Our own screen differs from
chat's only in **what it shows** — the whole desktop rather than the pinned subset. That is one boolean,
not a separate code path, and collapsing it is §26.2.

---

## §26.1 The flicker, and why part of it is structural

**The sequence today**, on the frame the desktop closes:

```
frame N    world renders
           RenderGameOverlayEvent.Post fires  ->  currentScreen != null, HUD stands down
           CgUiScreen.drawScreen paints the whole desktop
           ...and closes the screen, from inside that very drawScreen
frame N+1  RenderGameOverlayEvent.Post fires  ->  currentScreen == null, HUD paints
```

`CgUiScreen` closes itself mid-frame — its own comment says so, and it has to, because the close is
requested from a widget during paint. So the overlay hook for frame N has **already run and stood down**
by the time the screen goes away. **That is one dead frame by construction**, and no amount of care
inside either path removes it, because the paths are chosen by a condition that changes between them.

**The report is 2–4 frames, so something adds 1–3 more, and it must be measured rather than reasoned
about.** This document is being written in a session where six rounds went into a blur defect by
reasoning from the final picture; the standing lesson is that a probe closes these in one run. The
suspects, in order:

- **`setIngameFocus()`** grabs the mouse on close and is called from `displayGuiScreen(null)` — and
  `CgUiScreen` calls it a second time itself, deliberately, because the branch inside is conditional.
- **The first HUD frame paints against a layout the close just dirtied.** `enterHudMode` hides every
  unpinned window, and hiding is detaching: `unregisterElement` over whole subtrees, Taffy nodes
  destroyed, the pinned window's own box re-solved against a work area that just changed.
- **`CgUiPaintContext` is between frames.** `beginFrame` is what binds the material and resets the
  scissor stack; the HUD's first call is a cold one in a way the screen's never was.

**The probe:** one line per frame from each of the three sites — `onGuiClosed`, the overlay hook (both
the taken and the stood-down branch), and `paintHudFrame` — carrying the frame number, `currentScreen`'s
class, `hudMode`, and the pinned window's measured box. Run it, close the desktop, read the log. If the
gap is one frame, §26.2 removes it outright; if it is four, the last three are elsewhere and worth
knowing before claiming a fix.

---

## §26.2 `DesktopPresentation` — one answer, three callers

The fix is not a faster handoff. It is **not having a handoff**: the decision about what to paint stops
being encoded in *which hook fired* and becomes a value the desktop can be asked for.

```java
public enum DesktopPresentation {
    /** Our screen is up: the whole compositor, full input. */
    DESKTOP,
    /** Another GUI is up: pinned windows only, and they take input. */
    OVERLAY,
    /** No screen: pinned windows only, painted, no input. */
    HUD,
    /** Nothing to draw -- no pinned windows and no screen. */
    NONE
}
```

`UIWindow` gains one query and one paint entry:

```java
DesktopPresentation presentation(boolean ourScreenIsUp, boolean anyScreenIsUp);
void paint(DesktopPresentation presentation, int width, int height);
```

and the three loader sites collapse to the same two lines each — *ask, then paint if it is my turn*.
`paintFrame` and `paintHudFrame` become the `DESKTOP` and `HUD` arms of one method rather than two
entries with two sets of rules about the top layer and the input handler.

**Why this removes the flicker rather than hiding it.** The frame the screen closes, the overlay hook is
still the thing that fires — but it no longer asks *"is a screen up"* and stand down. It asks the desktop
what it should be showing, and the desktop's answer changed the moment `onGuiClosed` ran, which is before
the frame ended. There is no window in which both callers believe it is the other's turn, because there is
only one belief.

**The `OVERLAY` arm is where §26.3 onwards live**, and it is a third arm of an existing switch rather than
a fourth path — which is the whole reason to do this first.

> **The visible-set question is separate from the presentation question, and must not be folded in.**
> `enterHudMode`/`exitHudMode` decide *which windows are attached*; the presentation decides *what is
> painted and whether input runs*. They change together today, which makes them look like one thing. They
> are not: `OVERLAY` and `HUD` share a visible set and differ on input, and a future picture-in-picture
> mode would differ the other way. Keep the two as they are and let the loader call both.

---

## §26.3 Painting over a foreign GUI — the free half

`GuiScreenEvent.DrawScreenEvent.Post` exists in 1.7.10 (verified in the decompiled tree:
`InitGuiEvent`, `DrawScreenEvent`, `ActionPerformedEvent`, each with `Pre`/`Post`). `Post` fires after the
screen's own `drawScreen`, so our windows land on top of chat, the inventory, whatever it is. Register
alongside the overlay hook in `CgUiHud`; the GL discipline is identical and for the identical reason.

Two details that are not optional:

- **Skip our own screen.** `event.gui instanceof CgUiScreen` means the `DESKTOP` arm is already painting
  the whole compositor and this hook must stand down, or the pinned windows are drawn twice — the second
  pass winning the `localToWorld` reconciliation and breaking hit-testing, exactly as the taskbar-preview
  mirror rule records.
- **This path, not the overlay one, is what covers a screen with no world behind it.** The main menu and
  the world-loading screen set `skipRenderWorld`, so `RenderGameOverlayEvent` never fires there. Anything
  that must appear over those has to come through `DrawScreenEvent`.

---

## §26.4 The loader seam — one implementation, a thin SPI per version

**This has to be replicable on 1.7.10, 1.12.2 and 1.20.1**, and that constraint decides the shape rather
than decorating it. The mechanisms differ per version by a lot; what they differ in is *how the loader
learns things*, not *what the desktop does about them*. So the split is the one this project already
makes everywhere else — `CgPlatform`'s SPI, implemented once per loader, consumed blind by `core/`.

**Everything interesting lives in `core/`** and is written once: the presentation state machine (§26.2),
the arbitration rules (§26.5), keyboard ownership (§26.6), the top layer (§26.7). None of it names a
Minecraft type. A loader's whole job is four sentences:

```java
/** What a loader tells the desktop about the screen it cannot see. Implemented per MC version. */
public interface CgScreenOverlay {
    /** A foreign screen opened or closed -- anything that is not our own. */
    void onForeignScreenChanged(boolean open);

    /** Paint the overlay presentation now. Called from wherever that version draws after a screen. */
    void paintOverlay(int widthPx, int heightPx);

    /** Offer one pointer event. Returns true if the desktop consumed it and the screen must not see it. */
    boolean offerMouse(int xPx, int yPxTopDown, int button, boolean pressed, float wheel);

    /** Offer one key. Returns true if the desktop consumed it. */
    boolean offerKey(int keyCode, char typed, boolean pressed);
}
```

Two properties make this hold up rather than merely look tidy:

- **Every parameter is a primitive in a space `core/` already understands** — raw surface pixels, top-down
  Y, our own key codes. No `GuiScreen`, no `ScreenEvent`, no LWJGL type crosses. That is the same rule
  `CgSystemInput` follows and the reason the input handler is testable at all.
- **The return value is the arbitration**, so the *decision* is in `core/` and only the *forwarding* is in
  the loader. A loader that got the decision wrong would be a per-version bug in a rule that should have
  exactly one implementation.

### The mechanism per version

| | Learn a screen is up | Paint over it | Intercept input |
|---|---|---|---|
| **1.7.10** | `GuiOpenEvent`, or read `mc.currentScreen` from the draw hook | `GuiScreenEvent.DrawScreenEvent.Post` ✅ *verified in-tree* | **Mixin on `GuiScreen.handleInput`** — no event exists ✅ *verified* |
| **1.12.2** | `GuiOpenEvent` | `GuiScreenEvent.DrawScreenEvent.Post` | `GuiScreenEvent.MouseInputEvent.Pre` + `KeyboardInputEvent.Pre` — cancellable, **no mixin** |
| **1.20.1** Forge/NeoForge | `ScreenEvent.Opening` | `ScreenEvent.Render.Post` | `ScreenEvent.MouseButtonPressed.Pre`, `MouseScrolled.Pre`, `KeyPressed.Pre`, `CharacterTyped.Pre` |
| **1.20.1** Fabric | `ScreenEvents.BEFORE_INIT` | `ScreenEvents.afterRender` | `ScreenMouseEvents.allowMouseClick`, `ScreenKeyboardEvents.allowKeyPress` |

> ⚠️ **Only the 1.7.10 row is verified.** It was read out of the decompiled tree in this repo. The other
> three are from knowledge of those APIs and **must be checked against the actual sources before anything
> is built on them** — which is the whole reason the 1.7.10 row is marked: planning the input half against
> a `MouseInputEvent` that does not exist in 1.7.10 was the first draft of this document, and it would have
> cost an implementation session. Do not repeat that in the other direction.

### The mixin is 1.7.10's exception, not the pattern

**Input is the only asymmetric row, and only for the oldest version.** 1.8 added `MouseInputEvent` and
`KeyboardInputEvent`, so 1.12.2 and 1.20.1 get a cancellable event and need no bytecode at all. That is a
strong argument for this seam rather than against it: **the ugly mechanism is quarantined in one loader**,
behind an interface the other three satisfy with a two-line handler.

Why 1.7.10 needs it, concretely:

```
Minecraft.runTick()            if (currentScreen != null) currentScreen.handleInput();
GuiScreen.handleInput()        while (Mouse.next())    handleMouseInput();     // line 307, public
                               while (Keyboard.next()) handleKeyboardInput();
```

`handleInput` drains the LWJGL event queue, and **whoever drains it first is the only one who sees it** —
which rules out polling `Mouse`/`Keyboard` from a render hook, because by then `runTick` has already
emptied the queue into the foreign screen. The bootstrap is already present:
`mc1710/build.gradle.kts` declares `obfMixinBootstrap("io.github.legacymoddingmc:unimixins:0.2.1")` and
CrystalGraphics already ships a `mixins.crystalgraphics.json`, so this is a config file in an established
mechanism rather than new infrastructure.

**Why not the alternatives, on 1.7.10:**

- **`GuiOpenEvent` + a wrapper screen.** It can substitute a different `GuiScreen` instance, so in
  principle we could wrap a foreign screen in a delegating one. It does not survive contact: `GuiScreen`
  has public fields (`width`, `height`, `mc`, `buttonList`) Minecraft writes to directly, and other mods
  do `instanceof` checks on the current screen. A proxy breaks both, and breaks them in other people's code.
- **Overriding `handleInput` on our own screen.** Solves nothing — the foreign screen holds the input.

**The mixin does as little as possible**: ask the presentation, return immediately unless it is `OVERLAY`,
otherwise offer each event through `CgScreenOverlay` and forward what came back `false`. Everything that
could be wrong lives on our side of the seam, where it is testable without a game.

## §26.5 Mouse arbitration

**A press is ours if and only if it lands inside a pinned window.** That is a hit test against the window
layer, which the compositor already answers — no new geometry, no new rule about z. Everything else falls
out of it:

- **A press outside is forwarded and we hear nothing.** Not "forwarded and also delivered": a click that
  both closes our menu and presses a button in the inventory behind it is the light-dismiss defect wearing
  a different hat.
- **Moves are always offered to us and never consumed.** Our hover has to track the pointer or nothing
  ever highlights, and the foreign screen's own hover has to keep working over its own widgets. A move is
  the one event both sides can have.
- **An active pointer capture outranks the hit test.** This is the rule that will be got wrong: a drag
  started inside a pinned window must keep receiving moves and the button-up **after the pointer has left
  that window**, which is precisely what pointer capture is for and precisely what a per-event hit test
  destroys. Ask the drag controller first, the hit test second.
- **The wheel follows the pointer**, not focus — a scroll over a pinned list scrolls it, a scroll over the
  inventory behind scrolls that.

---

## §26.6 Keyboard ownership — the hard part

The mouse has a position, so "who is this for" has a geometric answer. **The keyboard has no position**,
and the two UIs both have a legitimate claim: chat has a text field with a caret, and our window has a
focused element. Dispatching to whoever is *hovered* would break typing in chat the moment the pointer
drifted, which is the worst possible failure because it is intermittent.

**The rule: focus follows the click, arbitrated at the boundary.** Our windows own the keyboard from the
moment a press lands inside one, and lose it the moment a press lands outside. Between those, every key
goes to the owner and the other side hears nothing. That is what every OS-level overlay does, and it is
the only rule that is stable under a stationary pointer.

Three edges that need deciding, not discovering:

- **Escape belongs to the owner.** If we own the keyboard and have a close watcher, Escape closes our
  popup or window. If we do not, it is forwarded and the foreign screen closes as it always did. What must
  not happen is Escape closing the chat *and* a menu in the pinned window — the cascade rule already says
  a live gesture takes it first, and this is the same rule across a wider boundary.
- **The foreign screen can close while we own the keyboard.** Chat closes on Enter, and the presentation
  drops from `OVERLAY` to `HUD` with our window still holding focus. Ownership has to be released on that
  transition or the next `OVERLAY` starts with a stale owner — the same shape as `UIInputHandler` forgetting
  a detached element, one level up.
- **The foreign screen may want a key we would swallow.** The inventory closes on `E`, and a pinned window
  with a focused text field would eat it. That is *correct* — a text field that ignores letters is not a
  text field — and it is exactly why ownership must be visible: a focused window looks focused, and the way
  out is a click on the world behind.

---

## §26.7 The top layer comes back

`paintHudFrame` deliberately skips the top layer, and the reason is sound for `HUD`: a tooltip, a menu, a
dropdown or a drag ghost cannot be summoned by a grabbed cursor, so painting the layer would only draw
whatever the desktop happened to leave on it.

`OVERLAY` is the opposite: every one of those is reachable, and a menu opened from a pinned window that
paints nowhere is worse than no menu. So the top layer is painted for `DESKTOP` and `OVERLAY` and skipped
for `HUD` — one more thing the presentation decides, and one more reason it is a value rather than a
branch spread across three call sites.

---

## §26.8 Coordinates

`Mouse.getEventX()` / `getEventY()` are **raw display pixels with Y measured from the bottom**, unrelated
to `ScaledResolution` and unrelated to the `mouseX`/`mouseY` a `GuiScreen` is handed. CrystalGUI wants raw
surface pixels with Y from the top, which is `mc.displayHeight - y`.

**`CgUiInput` already does this conversion** for our own screen. This is reuse of a working seam, not a new
frame of reference — and that matters, because the standing invariant is that a wrong coordinate space
places things *neatly somewhere wrong*, which reads as a placement bug rather than a conversion.

---

## §26.9 Pause — a decision, not code

**A foreign GUI may pause single-player.** The inventory does. `doesGuiPauseGame` is that screen's to
answer and none of our business.

What that means for a pinned window is worth writing down before somebody reports it as a bug: our
tickers, transitions and animations are driven by **render frames**, so the window stays alive and
responsive over a paused game. Anything fed by **server ticks** stops — so a pinned Run console over a
paused inventory keeps its caret blinking and stops receiving output.

This is the `doesGuiPauseGame` lesson from Phase 4 landing in a new place, and the same reasoning applies:
we cannot fix it from here, and pretending to would mean overriding another screen's decision about the
game. Record it; revisit only if somebody actually hits it.

---

## §26.10 Which screens get windows over them

**All of them, to start.** The motivating case is chat, the obvious second is the inventory, and a
blocklist written before any evidence is a list of guesses.

The candidates for exclusion later, when there is a reason: the death screen and the disconnect screen
(both are *about* an interruption, and a floating Run console over one is noise at the worst moment), and
the world-loading screen (nothing is live yet). Note that none of these is a technical problem — they are
all editorial, which is exactly why they should wait.

---

## What this deliberately does not do

- **Input on the HUD.** Unchanged and not a gap: with the cursor grabbed there is nothing to aim.
  This milestone makes the *rule* honest by attaching it to the cursor; it does not widen it.
- **Pin persistence across sessions.** A pin is per-run. Making it survive means putting it on the
  placement record — which is also what W14's undone "pin implies top-level" needs, so the two should land
  together or not at all.
- **Ship the other loaders.** `mc1201` is commented out of `settings.gradle.kts` and there is no 1.12.2
  module at all, so 1.7.10 is the only one that can be built today. §26.4 exists so that adding them is
  writing a handler per row of that table, not redesigning — but writing them is not this milestone.
- **A cursor of our own.** Minecraft leaves the OS cursor visible while a screen is up, which is what we
  draw against. Nothing to render.

---

## Steps

| # | Step | Depends on | Notes |
|---|---|---|---|
| 1 | The flicker probe (§26.1) | — | One run. Do it FIRST: it tells us how much of the flicker step 2 actually removes, and that is not knowable afterwards |
| 2 | `DesktopPresentation` + the unified paint entry (§26.2) | 1 | Core only, no loader change yet. `paintFrame`/`paintHudFrame` become arms of one method |
| 3 | Re-point the two existing callers at it | 2 | `CgUiScreen.drawScreen` and the overlay hook. **The flicker is expected to close here** — re-run the probe rather than assuming |
| 4 | `CgScreenOverlay` SPI + the core side of it (§26.4) | 2 | No loader code. This is what makes steps 5–8 a per-version handler rather than a per-version design |
| 5 | Paint over a foreign GUI (§26.3) | 4 | `DrawScreenEvent.Post` on 1.7.10. Visible immediately: pin a window, press `T`, see it over chat — still inert |
| 6 | `mixins.crystalgui.json` + the `handleInput` mixin (§26.4a) | 5 | **1.7.10 only.** New config in an existing bootstrap. Assert it applies: a mixin that silently does not apply looks exactly like a feature that does not work |
| 7 | Mouse arbitration (§26.5) | 6 | Capture first, hit test second. **In `core/`**, so it is unit-testable without a game |
| 8 | Keyboard ownership (§26.6) | 7 | Needs 7, because ownership is acquired by a press |
| 9 | The top layer in `OVERLAY` (§26.7) | 5 | Small, and the thing that makes menus in a pinned window work |

---

## How to look at it

| Step | In the harness | In game |
|---|---|---|
| 1–3 | Game mode already flips instantly; a flicker there would be a second bug | Pin a window, close the desktop, watch the seam. This is the whole point |
| 4 | — (no foreign screens exist in the harness) | Pin, press `T`, the window is over chat |
| 5–7 | — | Pin, press `T`, click into the window, type in it, then click chat and type there. **Both must work without closing anything** |
| 8 | — | Open a menu in a pinned window over chat |

**The harness cannot reach §26.3 onwards, and that is worth stating rather than working around.** There
are no foreign `GuiScreen`s there, and simulating one would be simulating the thing under test. The
harness's job in this milestone is steps 1–3; the rest is a `runClient` feature, which is what
`serverSmoke` and the probe flags exist to make bearable.

---

## Things that will bite

- **A mixin that does not apply is indistinguishable from a feature that does not work.** Log once on
  first invocation, the same way the engine-band work learned to announce that a capability is live —
  "live" and "inert" look identical from outside, and that cost a whole release there.
- **Painting a pinned window twice corrupts hit-testing.** If both `DrawScreenEvent` and the `DESKTOP` arm
  run for our own screen, the second pass wins the `localToWorld` reconciliation and clicks land where the
  window is not. `CgUiPaintContext.mirrored` exists for the legitimate case; this is the illegitimate one,
  and the guard is an identity check on `event.gui`.
- **A per-event hit test destroys drags.** §26.5 says capture first for a reason: a drag that leaves the
  window it started in is the common case, not the edge case.
- **Ownership must be released on the `OVERLAY` → `HUD` transition**, or the next foreign screen opens with
  our window silently holding the keyboard.
- **Do not let a per-version mechanism leak a per-version RULE.** The temptation on 1.20 is to answer
  `MouseButtonPressed.Pre` directly with a hit test, because the event is right there and it is three
  lines. Do that in two loaders and the arbitration has two implementations that will diverge — and the
  divergence will be a click that works in one version and not another, which is the least debuggable
  bug this feature can produce. The loader forwards; `core/` decides.
- **`handleInput` runs from `runTick` at 20 Hz, not per frame.** The existing `pumpInput` note in
  `CgUiScreen` says exactly this and drains per frame instead, because a continuous gesture sampled at 20 Hz
  reads as the UI being slow to paint. **The mixin inherits that problem** — a drag over a foreign GUI would
  be sampled at tick rate — and the same answer applies: drain on the render path, not the tick path. This
  is the one place where §26.4's "do as little as possible in the mixin" is in tension with correctness, and
  it should be resolved deliberately rather than discovered.


---

## Revisions, recorded — what building it changed

### R1 — the flicker was two problems, and unifying the decision only fixed one

**Verified fixed in game: pin a window, Escape out of the desktop, and not a frame is dropped.**

The plan said the gap was "at least one frame by construction" and that the rest had to be measured.
Both halves turned out to matter, and the second is the one the plan did not name:

1. **Two hooks each deciding whether it was their turn.** Fixed by {@code DesktopPresentation}, as
   designed — there is now one belief rather than two.
2. **Minecraft renders the overlay BEFORE it draws the current screen.** So on the frame `CgUiScreen`
   closes itself, that frame's overlay hook has *already* run and stood down — and the close branch
   returned without painting, leaving the frame to nobody. No amount of unifying the decision reaches
   that, because by then the only caller left for that frame is the one that is returning.

So the close branch now paints the HUD presentation directly, in the frame that would have dropped it:
by that point `displayGuiScreen` has run `onGuiClosed` (which entered HUD mode) and nulled the current
screen, so the presentation is already `HUD` and the windows can simply be drawn.

**The probe in step 1 was never run**, and that is the right outcome rather than a skipped step: it
existed to tell us how much of the flicker the unification removed, and the answer arrived from the
thing the probe was a proxy for. Had the seam still stuttered, it would have been the next move.

### R2 — the mixin cancels and drains NOTHING

The plan had the mixin drain the queue and forward what the desktop declined. That works and is wrong,
for a reason `CgUiScreen` had already written down about itself: **`handleInput` is called from
`Minecraft.runTick`, which is driven by `new Timer(20.0F)`** — so a screen's input is pumped at 20 Hz
while the game renders at 60+. A drag would update three times in twelve frames of motion, which reads
as the window being slow to paint rather than as input being sampled coarsely.

The plan flagged this in "Things that will bite" and said it should be resolved deliberately rather than
discovered. It was: **the mixin cancels and nothing more**, and `CgUiOverlayInput` drains on the render
tick instead. Both UIs then get per-frame input — the foreign screen included, since it is handed
whatever the desktop declined from that same loop. Cancelling without replacing would take the screen's
input away entirely; replacing without cancelling would never see an event.

### R3 — two bugs the plan's rules were right about and the code was not

Both were found by playing, both were in `ScreenOverlay`, and **both had a comment next to them
asserting the thing was handled.**

**A move is not a button release.** `deliver` clamped the button with `Math.max(0, button)`. A move
carries `-1`, so every single move became a left-button event with `pressed == false` — a RELEASE. The
drag was being cancelled by the very events that should have driven it. It presented as "dragging and
resizing do not work in overlay mode", which sounds like capture or coordinates and is neither; presses
and clicks were fine throughout. `CgUiInput.pumpMouse` passes the button through and gives a move a
`-1` timestamp so the multi-click counter cannot drift, and this now does both.

**Keyboard ownership was one-way.** The early return for a press outside a pinned window fired *before*
the line that gives the keyboard back — and the comment beside it read "the outside case never reaches
here", which was true of the code and wrong about what the code had to do. Click into a pinned window
once and every keystroke for the rest of the session went there: a chat box you can click, that shows a
caret, and that will not take a character. Now a press outside releases the keyboard, **blurs our
focused element** (or the editor goes on drawing itself focused while somebody else has the keys — the
"looks focused, is cold" state `WindowFrame.restoreFocus` exists to prevent one level down), and light
dismisses any open popover, since light dismiss otherwise only ever sees presses we consumed.


### R4 — "whatever is painted is clickable" had to be written once, not three times

The arbitration's hit test was wrong three times, each time for the same reason and each time fixed only
for the instance in front of me:

1. **Pinned frames.** The window switcher shows a window the HUD had hidden — painted, because the
   overlay draws the whole window layer, and dead, because input accepted only pinned frames.
2. **Any visible frame.** Better, and still half the picture: a Preferences dialog, a menu, a dropdown
   and the command palette are all **promoted into the top layer** and are not `WindowFrame`s at all.
   The overlay paints the top layer too, so all of them painted and none of them could be clicked.
3. **Under the window layer.** Correct about the top layer at last, and it SHIPPED BROKEN: the layer is
   full-size — its box *is* the work area — and nothing turns its hit-testing off, so `getHoveredElement`
   answers with the LAYER for any point not over a window. Matching that claimed the entire screen, and
   Minecraft's own Game Menu buttons stopped responding while anything was pinned. Every press was being
   consumed before it could be forwarded.
4. **Inside a `WindowFrame`, or inside a promoted top-layer element.** The precise question. It excludes
   the bare layer, and excludes the taskbar for free — desktop chrome this presentation does not paint,
   so a click at the bottom of the screen belongs to the game.

**The lesson is not the bug, it is that the invariant was already stated.** After (1) the comment said
"whatever is painted is clickable" and the code went on asking a different question three more times —
because each fix was written where the symptom was rather than where the rule belongs. A rule stated in a
comment beside code that does something else is not a rule.

**And the test that would have caught (3) did not exist until after it shipped.** Every version had a test
for what the overlay SHOULD accept and none for what it should REFUSE, so a hit test that said yes to the
whole screen passed everything. `aPressOnBareDesktopIsNotOurs` is that missing counter-assertion; it is
the same shape as `leavingTheHudPinsNothing`, and both exist because the positive case alone cannot tell
a correct answer from an indiscriminate one.

Built on `getHoveredElement`, which brings the modal case for free: it answers `null` when a modal blocks
the hit, so a dialog over a pinned window swallows clicks aimed past it rather than letting them fall
through into the game.

**And a dropped minus sign.** `CgUiOverlayInput` normalised the wheel with `1/120f`, copying the
magnitude from `CgUiInput.MOUSE_SCROLL_NORMALIZE` and not the `NORMALIZE_TOP_LEFT_ORIGIN` factor beside
it — so scrolling in an overlay window ran backwards. The engine's convention (a positive notch means
DOWN) is written down on `ScrollerView` and nowhere else, which is exactly how a constant gets copied
without it.
