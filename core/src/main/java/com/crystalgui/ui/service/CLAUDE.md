# `ui/service` — input, focus, animation, lifecycle, dismiss

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../../../../../../../AGENTS.md), which keeps the rules every session needs.

## Stack 4: the services — `ui/service`

**One 962-line input handler became four services with one job each**, and a live interaction became a
mode pushed onto a stack rather than another `if` at the top of the key handler.

| Service | Owns |
|---|---|
| `Input` | the platform sink, hit testing, three-phase dispatch over the COMPOSED tree with per-listener retargeting, pointer capture, keyboard activation, the cursor's `auto` rule, and the `Chords` seam a host fills |
| `Focus` | one owner, one traversal, focus navigation scopes, modality, `delegatesFocus` |
| `Animation` | timelines whose clock is the host's DELTA — so "the clock starts on the first tick" is structural — plus per-frame hooks OWNED by a node, and `afterLayout` for anything positioned from measured geometry |
| `Lifecycle` | freeze / thaw / destroy — a frozen subtree keeps its scroll, its text and its listeners |
| `Dismiss` | the popover stack, light dismiss, close watchers, Escape |

### Three-phase dispatch

`ui/event/` is **shared and unchanged**: `UIEvent` (`target`, `bubbles`, `phase`, `stopPropagation` /
`stopImmediatePropagation` / `preventDefault`), `PropagationPhase`, and the concrete `MouseEvent`,
`KeyboardEvent`, `FocusEvent`, `DragEvent`, `CloseEvent` types.

**Propagation is the DOM's.** `stopPropagation` ends the walk and the same node's remaining listeners
still run; `stopImmediatePropagation` ends those too. The old engine conflated the two, which is why a
widget stopping propagation in its own constructor pre-empted every later subscriber to that group.

`Enter`/`Leave` still dispatch to every node in the entered/left chain — outermost-first on entry,
innermost-first on exit — even though they do not bubble. Firing only on the precise hit target means a
container with children never hears about the pointer at all.

**A listener on a shadow host can never see its own parts**: `event.getTarget()` is retargeted before
the listener runs, and a listener attached to the host is OUTSIDE its own shadow root. The idiom the old
engine used everywhere — one listener on the widget, an if-chain comparing the target against its
shadow parts — compiles, runs, and takes the wrong branch forever. Attach inside the shadow tree.

### `InputMode` — a live interaction is a mode, not a special case

Drag, the window switcher, keyboard move and a modal each push an `InputMode`. The ladder that was four
hard-coded `if`s at the top of `consumeKeyboardEvent` is push order now, and `ModeStackTest` reads
`Input`'s constant pool to prove the service names no gesture.

**A modified chord goes to the keymap BEFORE content unless the target `claimsChord`** — which inverts
the old yield lists a widget could forget an entry from, and which cost `TextEditor` its Ctrl+Tab.

### `Focus`

One owner, and `focusable()` / `tabbable()` are still different questions — the first is "may this hold
focus at all" (focus delegation, arrow keys inside a composite), the second is "is it in the Tab
sequence". Click-focus tests `focusesOnClick()`, never `== CLICK`.

**Inertness is ONE predicate asked by two readers**, where the old engine enforced it at four points and
pinned each with its own test. A modal blocks the SCOPE CONTAINING it — a dialog is a scope itself, so
asking `scopeOf(modal)` answers the dialog and blocks nothing; a skipped box is not a CANDIDATE but its
children still are, or the modal itself goes out of reach; and opening a modal changes what is hittable
with no pointer movement and no frame, so it invalidates the hover itself.

### `Animation`

Timelines advance on the delta the host passes, so an animation cannot complete before it has rendered
a frame. An ordinary per-frame hook runs BEFORE layout — the frame is animation → style → layout — so
anything positioned FROM measured geometry needs `afterLayout` instead. A post-layout hook may move a
box and read a box and **may not add one**: a structural change would need another layout, and there is
no second pass.

A hook is OWNED by a node and stops when the node leaves the tree, which is what the old
the old one-way ticker registration could never guarantee.

---

## The frame's hover pass, and settling

From `AGENTS.md` § *Frame lifecycle*.

**`beginFrame()` only INVALIDATES the hover cache; it must never read it.** A mouse-move already
invalidated it before `beginFrame` ran, so reading there is an eager recompute against the NEW position
mislabelled as the old one. That was the original stuck-hover bug; the baseline is a plain field
snapshotted at the end of the dispatch.

**Settling is bounded** (`MAX_SETTLE_PASSES`), which is the whole difference from the old engine's
`while (isLayoutDirty())`: a post-layout pass that keeps dirtying layout terminates instead of
converging by luck.
