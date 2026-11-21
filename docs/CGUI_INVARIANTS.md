# CrystalGUI — load-bearing invariants

**What is invisible from any single class and expensive to rediscover.** Read the section for what you are about to
touch, never front to back.

## What earns a row

A row states something **true now** that can bite. A rule the engine no longer has is deleted; a renamed mechanism
is rewritten. The one exception is a repair that was **tried and did not work**, marked, so it is not tried again.
A rule stated in `AGENTS.md` or a package guide is not repeated here.

## Threading, and what may run on the frame thread

| Invariant | Consequence if violated |
|---|---|
| **Nothing mutable is shared between documents.** A `Signal` or `Property` binds to the first sequence that uses it and throws elsewhere. Across sequences use a `Broadcast`; per-document state a `UIDocument.Local`; read-mostly shared state a `Published` generation or a `Contributions` list. `SharedStateTest` fails on a static `Signal` or `Property` | one application's listener runs on another's thread and the crash surfaces in the cascade a frame later. `-Dcrystalgui.ui.singleSequence=true` runs everything on one sequence: a bug that goes away with it is a threading bug |
| **The frame thread owns the tree, per tree**: `UiThread.require(what, treeOwner)` | a write from another thread corrupts the cascade's sets and crashes a frame later naming the victim, not the writer. A tree nothing paints has no owner, so headless tests and servers are free |
| **What runs on the frame thread is decided by ownership, not cost**: everything that touches the tree runs there; anything that is a pure function of a snapshot (I/O, archives, compiling, indexing, classpath probes) must not, and goes to `JobScheduler` | locking the tree serialises and deadlocks against paint; snapshotting costs more than the work |
| **The expensive call looks free at the call site, so a guard beats an audit**: `UiBudget` times every provider call at `Workspace.providerFor`, always on; `ProviderCallsAreBudgetedTest` asserts through the door | `symbolOf` once ran a 761 ms compile from a tab title, and `HostClasspath.detect()` opened every jar per call. A provider may answer "not yet" and announce through `ContentProvider.onDidResolveSymbol` |
| **A document on a `UiSequence` has its own `JobScheduler`**: `JobScheduler.shared()` answers the calling sequence's; the process-wide one routes calls from a sequence to it | a job submitted on a sequence and drained elsewhere is never delivered. Such a document's `CgUiViewport` answers "ours" for events and queues unhandled keys (`pollUnhandledKey`) for the host a frame late |
| **A signal emitted on a worker carries that thread into every listener** | one innocent setter reaches the cascade from a script thread. Pull per frame instead, or hop through `JobScheduler` (`RunIndicators`), or drain a `ConcurrentLinkedQueue` in the frame (`RunConsole`) |
| **An asynchronous document that misses a host frame records its next frame at once** (`CommitQueue`), with its own clock's delta | asked once per host frame, a 9 ms document ran at 60 fps. At most two commits wait, presented oldest first |

## The node tree, the box tree and coordinates

| Invariant | Consequence if violated |
|---|---|
| **Paint and hit order are CSS stacking order, and `StackingOrder` is the one definition**; a stacking trigger is added in `Box.reclassify` only | a second order draws one thing and hit-tests another. `CGUI_STYLE_RENDER_PIPELINE.md` §8e |
| **A negative `z-index` needs its parent to be the stacking context** (`isolation: isolate`) | it paints under the parent's background and vanishes |
| **A raise is a `z-index` write, never a child-list move** (`Desktop.raise`, IMPORTANT origin) | `remove`/`append` unregisters and rebuilds the whole window subtree on a click |
| **Build a widget's children in its constructor**, never from `onWindowChanged` or a refresh during attach | `Index (is 1) should be < child_count (0)` (`UINode.taffyChildIndex`) |
| **A measured leaf under `flex-wrap: wrap` is measured with its used main size**: the vendored Taffy fork deleted the `isWrap ? NaN` branch (`taffy/MODIFICATIONS.md` #1; `TaffyWrapMeasureTest`, `MeasureFuncUnderFlexWrapTest`) | text clipped short, aspect-ratio boxes zero-high |
| **The mod jar is half relocated fastutil, for seven types Taffy uses** (~22 MB) | a known cheap win; replace them under their own tests, never inside other work |
| **A project dependency publishes several variants**: 1.7.10's shadow configuration names `LibraryElements.JAR` and an explicit `dependsOn(":taffy:jar")` | `cannot choose between the following variants of project :taffy` |
| **A bundled library the host already ships older must be relocated** (ASM under LaunchWrapper's 5.0.3); never one whose types appear in another jar's signatures (JOML) | the host's copy wins and `NoClassDefFoundError` names a class it lacks |
| JOML + Taffy stay on the headless classpath | field descriptors resolve at class load; `UINode`/`ElementStyle` have fields of those types |
| A drag's own delta is the truth at drag end, **never a re-read of the layout** | the last move is not laid out yet: a short drag records zero and no undo step |
| A widget never rebuilds the elements it is being clicked or dragged on: update in place (`ChildList`) | the handler's element detaches and the drag reads garbage |
| Resize is clamped to `resizeContainingBlock()`, for out-of-flow elements only | a parked box grows out through the corner, or a dialog stops short |
| **A search marks the matched characters**, as `::highlight()` ranges, never the row or real spans | the eye does the work; spans grow Taffy nodes |
| **`registerCommands` runs from `UINode`'s instance initialiser, once per class**: register per-instance things in the constructor | fields are still null, and "no provider" looks like "a provider that knows nothing" |
| **`box()` is nullable**: guard at the call site with the answer that fits (zero thickness, `false`, early return), and never a zero divisor | an NPE the first time a measured part is switched off; a NaN poisons a whole layout |
| **A listener on a shadow host never sees its own parts**: attach inside the shadow tree and `stopPropagation()` there | the target is retargeted to the host and an if-chain takes the wrong branch forever |
| **`Box.x()` is parent-relative**: use `Box.centreIn(box, space)` / `Box.originIn(box, space)`, adding half-extents after, in the target's units | subtracting two boxes in different parents is silently wrong by their depth |
| **An inset places the margin box, inside the containing block's border; `Box.x()` is the border box**: `MoveOutOfFlow.leftInset`/`topInset` convert (`MarginedMoveTest`) | a margined node moves by its margin on every gesture |
| **A hook name must not collide with `UINode`'s public surface** | a concrete class method beats an interface default: `requestClose()` silently vetoed every close. Cover a default with a fixture that overrides nothing |
| **A `hidden` or `display` toggle re-syncs only its subtree** (`UIDocument.StructureListener`); a new kind of structural change reports null unless provably local | a full sync per frame re-hosts and restyles the whole document |

## The cascade

| Invariant | Consequence if violated |
|---|---|
| **The desktop's window layer means something only once it is `.__windows__`** | without the class it measures 0×0 and every guarded rule quietly stands down |
| **A desktop-scoped close watcher is unreachable while a window is active**: the active frame answers Escape first | Escape aimed at desktop chrome minimises the window. Desktop-level Escape belongs on the live-drag rung |
| **Elements whose ancestor chains agree share one match** (`StyleEngine.SharingKey`): a new matching input (`:nth-child`, a sibling combinator, an attribute selector) must go into `ChainKey` | siblings silently take each other's styles. `-Dcrystalgui.style.share=false` rules it out |
| **A large round is matched on workers and applied on the document's thread** (`StyleEngine.prematch`); anything matching reads stays pure and thread-safe; `StyleValue.compute` is once-only | a drawable compares by identity, so two copies read as a changed style. `-Dcrystalgui.style.parallelMin=0` and `-Dcrystalgui.style.warm=false` turn the workers off |
| **`ElementStyle.getComputed` is null for an unwritten property**; `ElementStyle.computed()` (`ComputedStyle`) never is | a test asserting a default passes by accident |
| **A window thumbnail samples the window's surface** (`WindowThumbnail`, `Surface.drawThumbnail`), bilinear, no mips, at most a frame old | a rough preview is aliasing, not a paint bug |
| **A backdrop is pinned to the screen**: its placement is an affine map through the pose, bounded by all four corners (`CgCanvas.pane`) | a rotated pane shows the world turned with the glass |
| **An 8-digit hex in a sheet is `#RRGGBBAA`**; the engine's int is `0xAARRGGBB` | a pin in int order is another colour at another alpha, with no error |
| **Never name `__content__` in a descendant selector** | it reaches every nested `__content__` and zeroes their heights |
| **`font-size` does not effectively inherit**: `* { font-size: 10 }` is a candidate on every element; put it on the text element itself | a wrapper computes right and the label inside renders at 10 |
| **A `font-size` written after matching re-resolves the `em`s** (`StylePropertyRegistry.FONT_SIZE`'s listener) | zoom at INLINE or IMPORTANT leaves every `em` at its old pixels |
| **A policy filters the type index through a view** (`TypeIndex.filtered`), and member lists on the way out (`InteropResolver`) | copying a fifty-thousand-entry index per asker |
| **A remap test's fixture uses inheritance**, not composition | a delegating fixture never exercises the hierarchy walk |
| **A prototype's members are non-enumerable**: `ScriptableObject.getAllIds()`, not `getIds()` | the inherited half of a member list vanishes |
| **`core/`'s language SPI names no language's facts**: `CompletionItem.builderFrom(symbol).inheritedFromObject(…)`; every `SymbolInfo.with*` carries every field | an order-sensitive wither drops a field for the second engine |
| **`icon()` keeps the icon's name; its file is chosen as it draws** (`CgUiImage.Icon.path()`) | a theme swap changes no icon |
| A widget's cascade identity is its **tag**, never its Java supertype | `button {}` does not match `dropdown` |
| `TransitionEngine` advances on `System.nanoTime()`, ignoring the delta | assert inputs (resting value, state class, ANIMATION candidate), not intermediate values |
| `requestFocus` rings; pointer focus uses `requestPointerFocus` | every click outlines the widget |
| **A state drawn as `border-color` on something focusable sets `outline-color` too** | the 1px focus ring covers the border while focused |
| A wire's colour is read back from the dot's computed `border-color` (`NodePort.typeColor()`) | the per-type palette would live in Java and CSS both |
| `nodeport:blank` means unconnected, and a connect must `invalidateStyleMatch()` | the dot stays hollow under a live wire |
| A `graphnode` paints no background; each region paints one, the port band none | wires look cut off at the border |
| The plane's `transform-origin` is pinned to `0 0` at IMPORTANT | every world↔screen conversion is off by half a viewport times the zoom |
| A recycled row **swaps** its data-driven classes (`ProjectFileTree.swapPrefixedClass`) | stale classes resolve to a random colour |
| A compound with `::highlight()` never matches the originating element | every highlight repaints the paragraph |
| Pseudo-elements weigh **1**, not 10 | a `::highlight()` rule outranks the class rules around it |
| `::highlight()` paints background bands from the shaped runs (`UIText.paintHighlightBands`); `text-shadow` stays refused | — |
| **`min-height` is not `height`**: restyling a `button` into a glyph clears `min-height` and the base padding | the base rule's minimum holds it open |
| **`:disabled` and `:hover` tie on specificity**: put the disabled rule after the hover one, and dim the glyph rather than the face | a disabled control lights up under the pointer |
| **A disabled control leaves hit testing** (`setHitTest(false)` with `setEnabled(false)`) | it keeps hovering and showing its tooltip |
| **Escape is a cascade**: a control stops consuming it once it has nothing left to do | a permanent search bar swallows every Escape and its dialog never closes |
| **State a widget flips from its own listener goes on a class** (`__on__`, `__off__`), not a pseudo-class | the pseudo-class is re-evaluated on the engine's terms and draws the old state |
| `::highlight()` accepts only non-layout properties | a highlight would reflow the text being searched |
| `text-overflow` does not inherit: it sits on the `UIText` | a wrapper's never arrives |
| **The Gradle sibling-directory search runs only where the directory looks like a cache entry** (forty hex characters) | a `mods/` folder repeats one listing hundreds of times |
| **Openability is declared per deployment at registration, never on the `UiType`**; the default is refusal | a client could open any panel anywhere |
| **Every state setter is idempotent** (an `applied` flag, not a bare equality): assert on traffic (`CgInMemoryTransport.sent()`) | a panel mirroring each tick sends a delta per tick forever, every one correct |
| **A shadow's `currentcolor` stays unresolved until paint** (`StyleColor`, resolved in `CssTextStyle`) | an inherited shadow keeps the parent's colour |
| **A value with bare-number lengths reads its colour with `ColorValue.parseCssColor`**, never `parseColor` | `0 0 8px red` misparses and every glow vanishes |
| **A child keeps its `ComputedStyle` while nothing it inherits moved** (`ComputedStyle.sameInherited`) | a hover restyles and relayouts the whole subtree |

## Layout, flex and CSS in the sheets

| Invariant | Consequence if violated |
|---|---|
| **The desktop takes no space until a window is open**: only `UIDocument.desktop()` builds it, and `Desktop` sizes itself from whether it holds a frame; the last window closing gives the surface back | an empty full-size overlay eats every click on the background |
| **A drag never completes a click, so select on the press** | a dragged unselected tab lit two tabs and dropped as a background tab |
| **Minecraft's GL cache is put back at the host section's close**: the state provider's section slots (`HostStateModern`, `HostStateLegacy`) cover what `GlStateManager` caches, the active unit and its texture table (`Blaze3dTextureUnits`) included | its elided `_activeTexture(0)` lands the next bind on our unit, and unit 0 samples its default: the composite floods white, an editor blanks. `CgBindingPoints` reserves units counting down from the driver's limit |
| **1.7.10 leaves the fixed-function alpha test on** (`GL_GREATER, 0.1`), and a compatibility profile applies it to shaders: the frame disables it, and again for the composite. **1.7.10 only**: on a core profile the disable is a GL error | every fragment under 10% output alpha is cut: subtle fills vanish, gradient shoulders and AA edges go hard. The harness cannot reproduce it |
| `flex-shrink` defaults to 0, so a `flex-grow: 1` child **overflows its parent**: give it a zero basis (`FlexShrinkOverflowTest`) | it keeps its content size and spills, only at small window sizes |
| **The column fill idiom is `width: 100%; height: 0; flex-grow: 1`, and it never touches `flex-shrink`** (`.__inspector__`, the dock's pane host) | the box measures nothing; turning shrink on collapses every `ConfiguratorGroup` |
| **A `SplitView` pane is a flex column whatever the split's orientation** | a child grown by width collapses and its rows cannot be clicked |
| **A `SplitView` cannot go below two panes, nor make one occupy nothing**: `removePane` returns `false`; use `WorkbenchRegions.single` for one part | looping on it never terminates |
| **`flex-grow` summing to under 1 leaves the rest undistributed**: normalise weights | hiding one leaves a blank strip |
| **`gap` applies between every pair of children**: a gap wanted in one place is a margin there | a space inside a signature |
| `System.nanoTime()` has an arbitrary, possibly negative origin: never `Long.MIN_VALUE` as "long ago" | the subtraction overflows and "never" becomes "now" |
| A leading-edge resize derives its origin from the size **achieved**, not the pointer delta | the box shrinks to `min-height` and keeps travelling |
| **A `flex-shrink: 1` item contributes zero to its row's min-content width**, and a `width: 0` basis on a `UIText` latches "does not size itself" (`UIText.forceSelfSizeWidth()`) | a row sized to content truncates its columns |
| **`getScrollWidth()`/`getScrollHeight()` measure direct children only** | a descendant spilling out of a row cannot be scrolled to |
| `HighlightStyle` splits `ALLOWED` from `NOT_YET_PAINTABLE` | a property that resolves and never paints leaves nothing to search for |
| **A hidden child still counts for a `gap`**: create optional containers on first use | every consumer gains a gap |
| **The row fill idiom is `width: 0; flex-grow: 1`**, and check the parent has a width first | a parent sized to its children has no free space for any flex property |
| **`background-color` paints under `background-image`**: remove an image with `background-image: none` (or `background: none`), from the image's origin or later; tint one with `background-tint` | a colour-only rule is hidden by a theme's sprite |
| **In a packed row exactly one element absorbs every squeeze**: give inputs a `min-width` and let the losable part shrink | the input collapses to four characters |
| **`Integer.MAX_VALUE` means "to the end of the line"** (`Diagnostic.onRow`): clamp a column before adding it to an offset | the mark overflows to a point |
| **A slot shrinks to its content unless told**: the viewport slot is `width: 100%` at DEFAULT origin, its height unconstrained | rows resolve `100%` against a 42 px slot |
| **A contract declares its primary slot** (`WidgetContract.primary()`); `null` is a real answer | a convention guesses wrong for a `Slider`'s four floats |
| **Compose is confined to marked boxes**: anything moving a box's matrix without a relayout calls `BoxTree.transformsChanged(box)` | the content scrolls only when something unrelated recomposes |

## Input, focus and dispatch

| Invariant | Consequence if violated |
|---|---|
| **Modality is scoped to the window** (the nearest `WindowFrame` ancestor; outside any frame it blocks the document), enforced at hit-testing, Tab, `requestFocus` and the top-layer skip; the Tab walk also skips what a modal blocks | Tab escapes a modal, or one window's modal traps another |
| **An ancestor walk from a hit uses `composedParent()`**, never `parentElement()` | it stops at a shadow root: a click inside a pinned window counted as outside and the keys went to Minecraft |
| **The compositor takes pointer input only under a screen**; with none the pointer is the camera | breaking a block presses a pinned window |
| **A drop position is raw surface pixels; a float's insets are its owner's local space** | the window lands off by `uiScale` or by the caption |
| **A maximised window restores on the first movement of a drag**, never the press | double-clicking the caption does nothing |
| **A window's edges belong to its eight resize handles**: a test presses an element's centre | the press lands on a resizer and reads as a focus bug |
| **A floating panel is unhittable while it animates across its anchor** | it steals the hover from its own entry |
| **A press focuses an owned window** (`attachOwned`), which has no desktop to activate against | the press does nothing, and a drag never starts |
| **A full-size overlay is hittable only if it is `display: none` whenever not drawn** | an invisible overlay eats every click |
| **A gesture that must work anywhere in a window takes the press in the capture phase** and stops propagation; the modifier comes from the desktop (Alt-drag) | the bubble phase sees only the gaps between controls |
| **Gson HTML-escapes `'`**: assert absence on strings without `'<>&`, and pair it with a positive control | an "is absent" test passes against everything |
| **A window activates a frame before deferred content exists**: answer the focus delegate at activation and again when the layout first has content | focus falls back to the dock |
| **A band derived from a hideable element vanishes with it**: `DockGroup.stripBandContains` uses the strip's own box | dragging a group's only tab offers a split instead of a reorder |
| **A widget declares its own rate**, and `commitOnRelease` keeps the last value | a server-side panel gets a packet per keystroke, or loses the release |
| `Enter`/`Leave` dispatch to every element in the entered/left chain | a container never hears hover |
| `setHitTest(false)` applies to the subtree, like `pointer-events: none` | hit testing looks random |
| While a pointer is captured, no boundary events reach anything else | `:hover` flickers along a drag |
| **A scrub's rate is a hundredth of the field's range, or one unit per pixel with none** (`DragScrub.unitsPerPixel`); `ConfigDescriptor.scrubRate` overrides; Shift ×10, Ctrl ÷10 | a value-following rate feels stuck at one end |
| **A modifier pressed mid-scrub re-anchors the gesture** (`NumberControl.scrubUpdate`); Escape restores the press value | Shift re-prices the travel already made and the number leaps |
| **"Was that release a drop?" is `Input.releaseEndedDrag()`**, never `mode(Drag.class)` | the drag has popped itself before the release arrives, so every drop reads as a click |
| **A cursor and the art at the pointer are asked for** (`CursorSource`, walked outward from the hover); `Input.setCursorOverride` only for a live gesture | a pushed cursor outlives the pointer leaving |
| **A find bar closes when dismissed**, never when its query is empty | backspacing the last character hides the box |
| **A blur raised by row recycling is not a gesture**: ask `ListView.isRecyclingRow()` | a recycled row's editor commits on its own |
| **A row's inline editor is primed once per edit**, keyed on the row element | priming per bind resets the caret every frame |
| **A tree's inline editor is built in `createTemplate` and shown with `display`** | building it in `bind` rebuilds the element the key press is dispatched through |
| **A placeholder path uses `U+0001`**; `CgPath` refuses `U+0000` | `path contains a NUL character` |
| **A menu bar resolves against the focused element; a context menu against the clicked one** | the bar greys out entirely |
| **The registry carries `enabled` and never filters; menus dim** | a palette that hid disabled commands listed 1 of 9 |
| **Never hand log4j 2.0-beta9 a `Throwable` whose stack holds a band-loaded frame**: build the text yourself | its `ThrowableProxy` loads the class on the app loader, throws inside the logging call and kills the game |
| **`consumeKeyboardEvent` reports consumption to the host** | a consumed Escape also closes the 1.7.10 screen behind it |
| **A command finds a panel through the panel's `DataKey`** (`ProjectFileTree.PROJECT_TREE`), not a field on the engine; most reaches for a widget want its model | the engine holds fields for panels an application may not enable |
| `attachListener`'s booleans are additive, and `(false, false)` is target-only | a container never hears a descendant's events |
| The popover stack and the close-watcher stack are separate | a modal is Escape-closable but not light-dismissable |
| Light dismiss runs after the mouse-down dispatch, spares the invoker, and considers the popovers open **before** it | the dropdown flickers, or a popover opened by the press closes at once |
| **What a popup holds answers to its opener**: `UINode.commandParent()` and `settingsParent()` step to `UIElement.opener` | a field in an overflow reaches no history |
| A widget whose keys are commands must be able to hold focus | its commands are silently disabled |
| `Input` forgets a detached element: hover, press target, capture, an anchored drag | the hover diff walks two trees and never converges |
| A press on an already-selected node keeps the selection; Shift toggles | dragging five selected nodes moves one |
| Click-focus targets the exact element hit | a composite with interactive parts must focus itself (`GraphNode`) |
| A drag ends on the button that started it (`startDrag`'s button) | a middle-button pan never ends |
| A pan drag's source is the viewport, never the transformed plane | the view accelerates away from the cursor |
| A drag ghost is registered per drag | a ghost outlives its drag |
| **A transform-and-opacity animation goes to the compositor**: `Animation.playOnCompositor(CompositorAnimation)` | an async document advances it only on commits |
| **A drag that moves an element one for one says so**: `Drag.follows(element[, container])` | the element lags the pointer by the document's frame time |
| Hit-testing an inert subtree falls through to what is behind it | — |
| **Arrowing through results moves the selection, never the focus** (ARIA combobox) | the caret leaves the box |
| **A search bar is transient or permanent, as its host says** (`TreeSearch.Presentation`); a permanent one has no close and Escape clears it | the panel's main affordance vanishes |
| **A paint method may skip the draw, never the method** | an early return strands a scissor: `Unbalanced scissor stack` |
| **A `TextField` refuses Alt chords as well as Ctrl** | Alt shortcuts type a letter and die |
| **A menu mnemonic does not fire while a text field has focus** | Alt+E opens Edit instead of toggling an option |
| **Restore focus on the mouse-down**, not the press | the field flickers unfocused |
| **A tooltip reads its accelerator from the keymap** (`Keymap.acceleratorFor`) | a spelled chord is wrong after a rebind |
| **`Tooltip.attach` adds listeners**: keep the `Tooltip` and `setText` | the first text stays showing |
| **"The user sized this" is latched by movement** (`onPercentageChanged`), never a press | a click switches auto-sizing off for the session |
| Tab gates on `tabbable()`; focus delegation on `focusable()` | a composite is N tab stops, or a dialog skips its first control |
| Folding a block the caret is in moves the caret to the header | the editor looks focused and does nothing |
| **A viewer is not echoed what it just sent, while the state still matches it**; a clamp or another writer is always told. The fix is on the server | a dragged control is pulled back to round-trip-old values |
| **Never a raw NUL byte in a `.java` file**: write `\0` | git treats the file as binary and loses its diff and blame |
| **A press finding no click-focusable ancestor clears focus** (Blink's `HandleMouseFocus`); `setRetainsFocus(true)` opts a subtree out | pressing a caption left the window with no focus owner |
| **`requestFocus` rings and scrolls its target into view; `requestPointerFocus` does neither** | handing focus back after a click scrolls the popup |
| **A key claim reads live state, so it is asked before dispatch** (`KeyClaims.of(document).claims(...)`); a document on another thread acknowledges and re-injects | asked after, every claim of a dirty field reads false |

## Rendering, GL and shaders

| Invariant | Consequence if violated |
|---|---|
| **A `Pass` helper is declared above `void vertex`, and a shader that fails to compile still runs its pass** | a fragment helper between the stages fails the fragment compile, and the pass still clears and submits: dark and blown-out regions. Search the log for the compile error |
| **A quad takes its material's values when it is submitted: set them first**; a block written in place is re-given (`overrideBlock`) | a property set between two quads reaches only the second |
| **Two disjoint regions of one shape composite as a union, never source-over** (`text.shader`: `max(ringOuter, fillCoverage)`) | a quarter-coverage hairline inside every stroked letter, even in one colour |
| **A stroke closes the letterform where the letterform is thinner than the stroke, in both directions: geometry, not a field defect** (`CgStrokeFieldRangeTest`, `CgMsdfInteriorBandTest`). A path stroker is less accurate here and was removed; wide effects want a field with more range | an `h` has no counter to lose, so blaming reconstruction leads nowhere. Narrow the stroke or use `stroke-align: inset`/`center` |
| **Diagnostic tracking hangs off `DiagnosticSet.onChanged`**, never the engine's push | every producer but the engine goes untracked |
| **View state never reaches a shared file**: `DocumentEditor.writeViewState`/`readViewState`, stored per client; `encode()` is the only route to the file. Test by opening the file in a second editor and asserting the camera does not follow | the last saver imposes their view, and two editors conflict over a field neither touched |
| **A panel's filter and its search share one notion of "matches"** | a row the search would mark is filtered away |
| **Pass the `SearchQuery`, never the text** | Match Case, Words and Regex are silently dropped |
| **A filtered tree matches only what is written on the row**, not descriptions | rows survive for reasons nobody can see |
| **No GL object is created during FML mod loading on 1.7.10**: the splash runs it in a second, shared context, and VAOs and FBOs are not shared. The first render stage initialises lazily; `CgMeshPool` warns `[cg-vao]` on a reused name | one VAO with two owners: the whole UI blank with a clean error queue, in an installed client only |
| A `.shader`'s `#include` reaches the vertex stage: fragment-only code self-guards with `#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)` | NVIDIA compiles it, AMD refuses |
| A failed material compile latches (`hasCompileFailed`) until `markDirty()` | a retry per draw, or hot-reload can never fix it |
| **A text stroke's ceiling is the face's**: `CgFontRegistry.maxStrokeWidthEm(family)` or `UIText.maxStrokeWidthEm()`, not `CgTextLayer.MAX_FIELD_WIDTH_EM` (the narrow band for dense scripts) | half a Latin label's reach given away, silently clamped |
| **A stroke width crosses into the shader in atlas texels** (`CG_QUAD_CUSTOM1.x`), never screen pixels | a staircased outline under any non-uniform transform |
| **A text shadow's paint order is the sort key's stage, the outermost field**, and batch identity ignores it (`CgTextSortKeyTest`) | a shadow paints over letters on another atlas page |
| **A shadow reads the field only within the field's reach; past it, a cell** (`CgTextShadowPlan`); a blurred inset prefers the field and clamps | the saturation shoulder staircases; a close-up inset's clip steps |
| **A shadow cell is built on a worker only; meanwhile the glyph draws its last cell** (`resolveShadowCell`, `peekShadowCell`, `degradedDrawCount`); cells unasked for two frames are dropped | the shadow blinks off at every slider step |
| **An icon resolves through `FileIconTheme.withVariant`, default `Variant.DARK`**: convert both halves to `currentColor`, or drop the twin | the light file's conversion is never seen |
| **A command's icon is `currentColor` unless its palette carries meaning** (`ActionIcons.carriesItsOwnPalette`; `CommandIconsResolveTest`) | a menu mark cannot dim or take the theme |

## Widgets

| Invariant | Consequence if violated |
|---|---|
| **`AnchoredPlacement.resolve` left-aligns on the cross axis**: centre in the consumer, then re-clamp | every menu depends on the left alignment |
| **A package cannot be nested in a tier it ranks below**: `LayeringTest` matches by prefix | the package fails against its own parent |
| **A window command resolves its subject from the context** (`WindowFrame.WINDOW_FRAME`), never the desktop's active window | one of its four routes acts on the wrong window |
| **Restore and Maximize are two rows**, each greyed where it does not apply | a row whose label changes is never in the same place |
| **Cut/Copy/Paste is one command asking the position** (`ClipboardActions`) | a per-widget command is greyed everywhere else |
| **A search box is a `TextField`**, never a `UIText` readout | it cannot hold a caret, and Ctrl+A selects the list |
| **`MenuBuilder` is the only thing that turns commands into rows** | a second builder drifts on separators and greying |
| **A `MenuId.submenu` declaration is permanent**: tests use a fresh `MenuId.of(name + counter)` | a stray section in every later test |
| **A subsequence tier is opt-in per consumer**, one matcher with a flag | a create menu returns a long tail; two matchers disagree |
| **A model never calls a widget**: it announces (`WorkspaceTreeSource.onDidInvalidate`) and the widget subscribes | the feature cannot be extracted from the panel |
| **A headless reflection walk asks for the one field it wants** (`findStaticGetter(type, "NAME", Name.class)`), never `getDeclaredFields()` | `NoClassDefFoundError` on the headless classpath, or every text widget silently skipped |
| A widget that called `refusePublicChildren()` throws from `append` | — |
| **A positive `MouseEvent.Scroll` notch is the wheel rolling down** (`ScrollerView`) | a new consumer is inverted |
| A `FileDecorations` change reaches rows through the deferred refresh | already-bound rows show stale decorations |
| **A search box owns every caret key**; navigation gets Up/Down and Enter only | — |
| **A tree restoring selection by item clears the index selection first** | expanding leaves one more row selected each time |
| **A host that navigates its own rows turns match-stepping off** (`setArrowNavigation(false)`) | the arrows fight the host's |
| A view part sits beside `TextEditor` in its package and reaches it through package-private accessors | — |
| **A player is not the entity they wear**: respawn and dimension change build a new one. Key on the `GameProfile` UUID; resolve the route (`NetHandlerPlayServer`) at send time (`Mc1710Peer`) | inbound dies for the session while outbound keeps working, and logout cleanup misses |
| **`args` from a client is a claim**: a resolver re-derives and checks; `null` (or a throw) is a refusal | a client chooses which object the server hands it |
| **What a legal gesture could produce is sanitized, by the widget; what it could not is refused** | `NaN` from a forged slider poisons the model for every viewer |
| **A rate policy may drop intermediate values, never the last**, flushed by the connection's tick | a control works except when you stop moving it |
| **A listener on a model outlives its window**: keep the `Connection` and disconnect it where the window ends | the window tree is retained after close |
| **A control showing a live value is bound to it** (`ValueControl.bind(Property)`), never pushed per frame | a field cannot be typed in |
| **Options that change with the tool take over one row** (`ContextToolbar`), sized by the sheet | the canvas jumps when the mode starts |
| **A modal tool's history is `Tool.history()`, reached by `edit.undo`**; such a mode lets the undo chords through | Ctrl+Z from a field undoes the document |
| **Every text draw asks `CssTextStyle`** and declares its `inkOverflow` (`EveryTextDrawConsidersStrokeAndShadowTest`) | outline and shadow silently missing, or clipped in an opacity layer |
| **An explicit `TreeView.setExpanded` drops a toggle queued in the same frame** | a queued fold undoes the expand |
| **`setExpanded(open)` refuses an item with no children**: `ProjectFileTree.beginNew` adds the placeholder first | a new file in an empty folder is invisible |
| **A menu row draws a mark or a checkmark, never both**: `Command.whenToggled(icon, label)` names the action it will perform | an open eye offering to open an eye |
| **An on-demand hook is held in a field and registered with `everyIfAbsent` / `afterLayoutIfAbsent`**, never behind a flag | it never registers again, or runs twice |

## The workbench, the dock and tool windows

| Invariant | Consequence if violated |
|---|---|
| **A tool window's mode lives on its placement record** (`ToolWindowState.type`); the frame is built per show and destroyed per hide. A float's bounds are captured on the way out, and an absent one is omitted, not zeroed | a float reopens docked, or restores as a 0×0 window |
| **An `overlay` paints into its own element's box**: put an icon on the sized slot itself (`Taskbar.applyIcon`) | a fresh child is 0×0 and shows nothing |
| **One kind of motion gets one mechanism** | two drift and only one is ever debugged |
| **A torn-out editor window is persisted through its home** (`DockArea.home()`: `allPanels`, `activePanel`, `shownPanels`, `windows()`) | it is in neither record and does not come back |
| **A test asserting `isAnimating()` opens its window with animations off**, enables them for the gesture, restores in `finally` | the open timeline makes it pass against no fix |
| **A session record naming an unregistered tool window is kept and never shown**: `showPanel` refuses an unregistered kind; `registerToolWindow` applies a stored record ahead of `openByDefault` | a raw-id empty pane, or the panel stays closed when its extension returns |
| **Ask `ToolWindowManager.isPanelOpen`**, never a closed panel's frame | the frame is `DESTROYED` |
| **A removal names only the subtree root; an insertion names every node, parents first** | a message per descendant for one deletion |
| **Map a path to the tree through `visibleRowFor`** under compact folders | reveal walks down and selects nothing |
| **A diagnostic is row/column, a squiggle is offsets**: `LanguageServices.onDiagnostics` refuses a list for an older version; `DecorationSet` moves marks between compiles | marks below the caret point at shifted text |
| **A feature resolves its own preference where it uses it**; nothing pushes it onto the engine through a setter | the engine holds fields for panels, updated on every settings change |
| **Anything that asks once across a wire logs the ask and the answer** | "never asked", "dropped", "refused" and "empty" all look like an empty panel |
| Leading (top/left) resize handles exist only for out-of-flow elements | `left`/`top` on an in-flow box slides over its neighbour |
| **A shared row component reaches the rows itself** (`TreeSearch` decorates the renderer); a host's own `markRow` wins | the bar works and highlights nothing. A renderer set after install loses the decorator |
| **A panel that exists to be read opens its groups**, each auto-opened once | the Problems view shows only counts, or a group cannot be folded |
| A collapsed region's first row stays visible (`hiddenRows()` starts at `startRow + 1`) | the block has no handle left |
| **ECJ reports nothing optional for a file that does not parse**: ask `Analysis.optionalProblemsAnalysed`; `JavaLanguageServices` retains the last set only when the compiler says the pass was skipped | warnings vanish on a syntax error, or fixed ones come back |
| **`readResponse` is the shape, `readWhole` the bytes and the etag**, coalesced per resource. Test with a file over the transfer limit and assert the content | a large file reads as empty: reloads, copies, merges and the index all lose data |
| **A debounced job whose trigger fires every frame never runs**: an announcement redraws rows (`announceRowsChanged`) rather than re-listing, and `environmentChanged()` coalesces; an edit always re-submits | the Problems panel shows an analysis of text that no longer exists |
| **A method matching an interface the class does not declare is invisible**: declare it and use `@Override` | `headerContent()` was built and never placed |
| **An overlay is parented inside the panel it was raised from**; anything centred on the window passes null (`InputDialog`) | the row behind a popup hears its double-clicks and drags |
| **A New ▸ row is a `NewDocumentKind`**; the built-ins use the same seam | — |
| **The dock follows the tab closing** (`EditorService.onDidClose`), not only the document | a deleted file's tab stays in the strip |

## Windows, the desktop and motion

| Invariant | Consequence if violated |
|---|---|
| **A window's modal is owned by it** (`WindowFrame.attachOwned`), never promoted; the slot is sized only while showing | a dialog floats over another window, or an empty slot eats clicks |
| **A driven animation writes its start value in its constructor** | a one-frame flash of the end state |
| **A reconnect repairs the protocol at once and the view when it is shown** | lost notifications, or invisible work for hidden windows |
| **A first `openWindow` does not go through `show()`**: what happens when a window appears is written at both | the entry animation never plays on first open |
| **Fullscreen is maximise plus a hidden strip**; remember how it was entered, and ask the whole registry whether the strip returns | — |
| **A provider's order is not a ranking, and an empty prefix is still sorted** (only the matcher is skipped) | useful members land below the window |
| Transitioning into view needs a resting value in the sheet, never a one-frame write from Java | nothing animates |
| An `UndoStack` belongs to a document, not a `UIDocument` | two tabs braid histories |
| A detached modal is popped from the modal stack | the window is inert forever |
| **A bound method reference captures the field's value**: write `path -> field.get(path)` for a reassignable receiver | every later value is invisible |
| **A request the far side may refuse is a request** (`ui/requestOpen`), not a notification; the window arrives by the ordinary path, and a timeout answers like a refusal | a refusal is indistinguishable from a lost packet |
| **A client-opened window's screen must not pause the game** | the integrated server stops answering: works in multiplayer only |
| **A refusal counter is per viewer, and its threshold stops that viewer** | one bad peer closes the window for everyone |
| **A dirty set is encoded, and the far side applies it**: assert on the far side's element (`clientChild(1).isEnabled()`), never the traffic | identity changes vanish with no error |
| **Suppressing a hidden window's traffic gates the whole flush**, never only the send (`aWindowReshapedWhileHiddenComesBackWithItsNumberingIntact`) | the two sides' numbering diverges silently |

## Documents, the filesystem and the wire

| Invariant | Consequence if violated |
|---|---|
| **`:runtime:mc:1710:compileJava` belongs in every check that deletes anything from `core/`**: no core task builds it | the module stays broken while every core check is green |
| **An etag (`mtime + size`) is not server-scoped**: a content cache is keyed by server too (`InMemoryFileSystem`'s counter clock reproduces the collision) | another server's file shown under this server's name |
| **Installing a tree observer reports nothing** | consumers mistake installation for insertions |
| **`JsLanguage.register` lends the Java engine on the already-registered path too**, and every document open lends it again | JS member lists depend on registration order |
| **A Rhino statement is a node whose parent is a `Block`, a `Scope` or the `AstRoot`**, `Block` named explicitly; the root is not a statement | "insert above this statement" goes to the top of the file |
| **The interop probe cache belongs to the analyser** (`InteropResolver`, LRU 12, closed on eviction); a `ParsedScript.close()` does not empty it | every keystroke re-analyses each Java class it mentions |
| **A runtime's verdict rides `AnalysedLanguageServices.reportRuntimeProblems`**, found through `attachedTo(Resource)` and hopped through the scheduler, in a tracked lane at the current version | a second owner, or a stale mark |
| **`MinecraftRegular.otf` has no U+2026 and no U+22EE**: draw a kebab as an icon | tofu |
| **Guard against NaN with `!(x > 0)`**: `x <= 0` and `Math.max(1f, x)` both pass it. A row's top is `topOfViewLine`, written once | every row drawn at one y, with nothing thrown |
| **A completion session re-anchors when the caret leaves its word** (`WordClassifier`) | a `.` filters members against a prefix containing it |
| **Asking for the project list before the greeting is dropped, and the pending call holds the asker**: check `Workspace.hasGreeted()` | an empty tree, and a disposed application kept alive |
| CrystalGUI has no platform registry: input, sound, clipboard and cursor come from `CgPlatform` | a working GL backend and a dead keyboard |
| **A client screen holding a `CgProtocolConnection` must not pause the game** (`ConnectionTest`, on an integrated server) | the server it asks has stopped: an empty workspace, nothing in the log |
| **Document state goes through `Edit`s; view state is mutated directly** | Ctrl+Z scrolls |
| A marquee selects what it touches | a node larger than the viewport cannot be marquee-selected |
| Trademarks survive every licence: the IntelliJ logo stays in test resources | — |
| A row's slots are built in `createTemplate`, never in `bind` | an element made during bind misses that frame's layout |
| **An absolute child of a scroller still scrolls**: `setScrollExempt(true)` pins it | a find bar scrolls away |
| **Scroll-exempt still counts toward the scroll width** unless `scrollExtent` leaves it out | a band sized from the scroll width keeps its old width |
| Highlights re-shape here: un-highlighted text stays on the unspanned path | every label shifts by a fraction of a pixel |
| **Folding is view state**: it never touches `UndoStack` | Ctrl+Z unfolds instead of undoing |
| **`TextEditor.getScrollWidth` is a pure accessor**; the scan is `measureWidestRealisedLine`, once a frame | a dozen field-looking reads fan into one loop over every realised line |
| **An announcement with a side effect is computed once per analysis**, then replayed (`JavaLanguageServices.announcement`; `aLateListenerDoesNotCorruptTheRetainedPositions`) | a late listener remaps stale row/columns over correct ranges |
| **A live-scope tier contributes a type; it never replaces the declaration** | a run strips a function of its description and parameter types |
| **A runtime's report is converted against the text that ran**; a report whose text has changed is dropped | the squiggle moves a row per line typed above |
| **Measure retained memory by scaling** (build several, hold them all), never a heap delta or nulling a reference | churn reads as 4× the budget, or retention reads as zero |
| **An override cannot weaken access**, so publishing a base method publishes every override | a split's bill is larger than its access errors |
| **A hidden viewer may miss state, never structure** | a withheld tree delta renumbers one side only |
| **A returning viewer gets a fresh description**, rebuilt first | it resyncs to values from when it looked away |
| **A document has two versions**: `TextBuffer.version()` only goes forward (analysis freshness); `alternativeVersion()` returns with undo (`DocumentModel.contentVersion()`, dirtiness). Never the undo depth. A test saves first | a keystroke and its undo leave the file dirty forever, or a divergent edit reads clean |

## The editor and the text stack

| Invariant | Consequence if violated |
|---|---|
| **A `{@link}` across a layer boundary is a dependency a bytecode scan cannot see**: use `{@code Name}` (`LayeringTest.theHeadlessSideNamesNoUi` checks imports) | a moved class recreates the edge it was moved to remove |
| **A method reference resolves its types when the enclosing method runs**: client subscriptions live in a class a server never loads (NeoForge `ClientBus`) | a dist guard around the call does nothing: `BootstrapMethodError` on a dedicated server |
| **`mods { sourceSet(...) }` makes classes visible and builds nothing**: add the `dependsOn` | `NoClassDefFoundError` for a class that exists |
| **A silent exit 0 is `Minecraft.run`'s swallowed `MinecraftError`** | nothing in the log to find: catch closer to the fault |
| **Execution never touches the grammar natives**: `.java`/`.map`/`.run`/`.engine` name no `org.treesitter` or `.grammar` (`ExecutionNeedsNoGrammarTest`, a bytecode scan) | a dedicated server loads five natives it cannot use |
| **Assert completion on `filterKey()`**, never `label()`; `CompletionItem.builderFrom` writes the insert snippet | — |
| **A trailing dot needs a probe re-parse in every language**; the live scope only for a receiver with no knowable type | `list.` shows the wrong list |
| **Never compare a Rhino `Token` constant with a node's type**: they are inlined and renumbered across bands. Ask the node's class, or its text (`RhinoTokens`); enum members are safe | a number literal reads as `true` |
| **A JSDoc tag is split on tag boundaries**, and the comment is found from the statement's start | one-line doc comments and every `var` declaration lose their types |
| **`core/` never depends on `language/`**: the SPIs live in `text/lang/` | tree-sitter and ECJ land on a dedicated server |
| **A language capability is absent in three tiers, each a null** | a setting would disagree with what loaded |
| **Completion ranks by match tier, then proximity, brevity last**: never by `SearchMatch.score()` | `pr` ranks `Printer` over a local `precision` |
| **`Stickiness` is chosen when a range is created** | an insertion at a boundary joins the wrong range |
| **A `DecorationSet` re-checks its order after mapping** | `overlapping` misses ranges in an unsorted list |
| **A range collapsed by deletion is not one born empty** (`TrackedRange.collapsedByEdit()`) | widening it marks innocent text |
| **A resolved ECJ unit resolves bindings lazily, so its name environment lives until `EcjAnalysis.close()`** | on Java 8, binary classes report no methods |
| A bubbled decoration keeps the colour and drops the badge | a folder claims to be modified |
| **"Jump to the first match" belongs to `ProjectFileTree.setFilter`**, the one place a query changes | the keystroke behaves differently by route |
| **Search results are re-run from the buffer's change signal** | undo leaves stale highlights |
| `Property.set()` drops re-entrant sets from inside its own emit | — |
| **`ProjectedLines.modelAt` tests the half-open span**, never `Arrays.binarySearch` (VS Code's `PrefixSumComputer`) | a folded row's duplicate prefix sum lands off screen |
| `Rope.fromChildren` never returns a lone child bare | `concat` throws `Leaf cannot be cast to Internal` at ~8 KB multiples |
| `measuredRows` is invalidated per row only when the line count is unchanged (`aLineCountChangeDropsEveryCachedRowMeasurement`) | cached widths describe another row's text |
| **A path crossing the engine bridge travels as a system property, in every cache key that depends on it** | a static is invisible in the band; a finished download is never seen |
| **A declaration is matched across parses by binding key, over the same classpath**, using the declaration binding | `List<String>.add` matches nothing |
| **A retained diagnostic is re-announced from its tracked offsets**, and a collapsed one dropped | warnings point at moved text, or outlive their import |
| **A host binding reaches the analyser** (`ScriptBindings` carries its Java type) | every binding is drawn unresolved |
| **`JsLoaders` is the one host-class lookup**, retrying the nested spelling | `java.util.Map.Entry` is offered and throws at run time |

## The language stack

| Invariant | Consequence if violated |
|---|---|
| **"Has this class been loaded" is asked of the JVM** (`-Xlog:class+load`): `findLoadedClass` is unreachable from a mod's named module on 1.20.x | a reflective check determines nothing and passes |
| **A dev server cannot show what a production server lacks** (no client classes, `jar:` code sources, FML's halt trap): run `server_smoke.py` | three faults a dev server passes |
| **Every transitive engine artifact is pinned per band**; `:language:checkEngineBands` re-derives the floor | open ranges resolve newer jars that Java 8 cannot load |
| **A module bundled as class files brings no dependencies**: ship each runtime dependency explicitly | no `.java` script ran on 1.7.10 (ASM missing) |
| **A band-loaded class names only JDK types, the bridge and `com.crystalgui.text.*`**: compose host-side and cross as JDK types (`TypeBytes`); `BridgeCrossingTest` | the band redefines a static registry and resolution silently goes file-only |
| **The game answers before `LanguageRegistry` is first read**: every era provides `CgGameClasses.HOST` in `PlatformService<Era>.install` | scripts never resolve Minecraft live |
| **A mapping fetch runs on `CgJob.executor(BACKGROUND)`**, never a `JobScheduler` | it waits forever on a server with no frames |
| **MCPConfig's srg class names run nowhere**: Forge's join uses `MappingSet.withoutClassRenames()`; Fabric's must not. Fixtures come from the real files | `NoClassDefFoundError: net/minecraft/src/C_3391_` |
| **On 1.20.x a mapping is a join through obf** (`MappingSet.then`, dropping missing entries; `then(IDENTITY)` is `IDENTITY`) | obf names shown as readable ones |
| **A game host's `CgGameClasses.bytes` never answers for the JDK** (`GameClassesModern`; `ScriptNameEnvironment.find`) | `java.lang.Object cannot be resolved` from compliance 9 |
| **A capability that can be silently skipped says it is on**, one log line (`resolving against the live runtime through …`) | live and inert look the same |
| **`isPackage` on an obfuscated host: a name that is not a type is a package** | every Minecraft type fails at its first segment |
| A script-level `fun` in `.gradle.kts` is declared before its use | the forward reference does not resolve |
| **The band loader is opened once (`EngineHost.shared`)**: `JavaEngine.over(host)` borrows, `open(band, source)` owns, only the owner closes | the other engine loses its classes |
| **A call is a receiver**: both engines resolve the enclosing expression at a `)` before a dot | `list.get(0).` shows an empty popup |
| **A stale `language/build/classes` looks like an adapter failure**: `--stop`, `:language:clean`, rebuild | `NoClassDefFoundError` on a class in source |
| **`RefusedTypes` scans the constant pool before a script runs, and the loader refuses late names**: both | late, or blind |
| **The JS resolution tiers live child-side**, beside the tree; only the answer crosses | a bridge crossing per node walked |
| **The kill flag is the thread's interrupt status, and a stop is an `Error`** | a blocked script never stops, or `catch (Exception)` swallows it |
| **Semantic tokens replace grammar tokens where they overlap** (`ensureRowSyntax`); test with a pair where neither is the other's general form | the wrong colour reads as a scheme bug |
| A shipped third-party asset needs a notice file (`THIRD-PARTY.md`), not a javadoc comment | the licence is not satisfied |
| **A contextual keyword (`record`) declares only when a name follows it** | `SourceHeaders` empties every method body |
| **A child-side class names only JDK types, `com.crystalgui.text.*` and the bridge**: a shared utility is moved, never imported across. A class importing `org.mozilla.javascript` is child-side: `js.rhino`; the rest is `js.host` | a second copy is defined across the bridge and stops being assignable |
| **The engine loader is the context loader only for calls into the engine**, up to `initStandardObjects`, never for the script's own run | every `ServiceLoader` in the application resolves against the engine |
| **A completion fallback fires on no type**, never an empty member list | a string offers the last run's globals as members |

## Building, packaging and the loaders

| Invariant | Consequence if violated |
|---|---|
| **A loader module defines wiring and decides nothing** (`runtime/mc/CLAUDE.md`) | each loader makes a different decision, each internally consistent |
| **A dependency that ships as a mod is never also on the consumer's runtime classpath** | Fabric loads two copies of a class with static state: "registered" then "not yet registered" |
| **`LaunchClassLoader.getClassBytes` takes the untransformed name**: untransform through the `IClassNameTransformer` in `getTransformers()` | every Minecraft type absent in production, present in dev |
| **A class in a modern `common` node lives under the relocated package** (`...mc.modern.platform.*`) | the merge keeps one node's copy: `NoSuchMethodError` everywhere else, seen only by `prodSmoke` |
| **A stale `core.jar` looks like "package ... does not exist"**: `:core:jar` and retry | a hundred errors in untouched files |
| **Kotlin block comments nest**: never a slash-star inside a `.gradle.kts` comment | everything after it silently stops registering |
| CrystalGraphics `platform` stays on the headless classpath (the excluded module is CG core) | `Input` implements `CgSystemInput`: `NoClassDefFoundError` |
| **A class file is inert until something defines it** (F1) | a bootstrapper that loads all four hosts dies on the first loader |
| **Every class in the merged jar is major ≤ 52, with no multi-release escape** (F2); `checkSingleJar` asserts it | FML 1.7.10's ASM 5 throws before any mod loads |
| **`shouldApplyMixin` is asked before the target class is looked up** (F3); on 1.7.10 the plugin compiles against `org.spongepowered.asm.lib` | `NoSuchMethodError` at the first applied mixin |
| **No relocated library's type appears in a signature another jar defines**: JOML is relocated identically in both repos; tree-sitter not at all (JNI names) | the two jars' types no longer match, or every native unbinds |
| **A remapper rewrites compiled references, never strings**: call Minecraft in compiled code | reflection works on NeoForge and fails on Forge and Fabric |
| **Never ship `org/objectweb/asm` unrelocated** | Forge and NeoForge die silently after "Initialized transformers"; Fabric reports a loader constraint |
| **Discover services through `Providers.forEach`**, never `ServiceLoader` | ModLauncher 5 lists nothing inside a mod: no tool windows, no languages |
| **A library the merged jar needs is declared into `<name>JarLibs`** | a retired fat jar's dependency reaches nothing |
| **The optional half's library goes on the `lang` source set only** | the host could name the language stack |
| **A runtime download is an id; its addresses live in `download/locations.json`**, and only the jar's own copy pins a digest (`DownloadUrlsLiveInOneFileTest`, `checkDownloadLocations`; `download/CLAUDE.md`) | a URL in code cannot move; a branch file could change what clients accept |
| **`requestJvm` goes on every classpath a plugin resolves**, extended ones included; check with `dependencyInsight` on what the run reads | `UnsupportedClassVersionError ... 69.0` |
| **ModLauncher ≤ 8 names a mod root `modjar://<modid>` without a slash**: `EngineHost.asClasspathRoot` restores it (`EngineHostRootTest`) | 1.13–1.16 clients run with no engines |
| **Below 1.17, a class naming Minecraft is in a mod's roots, never a library**, and each mod lists its resources first (`cgbuildlogic.uniminedDevRun`) | `LinkageError: loader constraint violation` |

## Testing

| Invariant | Consequence if violated |
|---|---|
| **A fix's test asserts the text the edit produces**, applying the `ChangeSet` back to front | an edit at the wrong offsets passes |
| A `CompositeEdit` undoes in reverse | an edge is restored into an occupied input |
| Truncation changes no geometry: `UIText.displayedText()` observes it | — |
| A closed `Dialog` measures 0: `show()` first | "does it fit?" passes as `0 <= 0` |
| **A test detaches what it built** (`UiDocumentTestBase`; `removeAll()` otherwise; `DetachedDocumentsAreCollectedTest`) | process-wide signals keep documents alive until the heap runs out |

## Everything else

| Invariant | Consequence if violated |
|---|---|
| **Jars splitting a Java package share a signing certificate**: pinning is constrained by signing era too. Compare leaf certificates, after draining the entry | `SecurityException: signer information does not match` after every check passed |
| **A denylist is a veto asked before the allowlist**, never a security claim | `allow java.lang` re-permits `deny java.lang.reflect` |
| **A cooperative safepoint is injected as a call** to a void no-arg method | a new branch needs frames, which load classes at instrumentation time |
| **A modifier overlay is a full-size layer** at the icon's size | the mark draws too large in the wrong corner |
| **A hint strip is derived from the key table its handler reads** | the strip lies once a binding moves |
| Only `AnchoredPlacement` writes `left`/`top` on an anchored popup | writers fight every frame |
| Selection is not undoable; an edit's undo restores the selection it applied to | — |
| An input port takes one edge: connecting to an occupied one replaces, through `disconnect` | rewiring takes two gestures, or undo misses the displaced edge |
| The canvas culls with `opacity: 0`, not `display: none` | a culled node can never be un-culled |
| An icon's colour comes from the `.filetype-*` class, never the theme JSON | languages sharing a glyph share a colour |
| `inert` keeps its box | — |
| Exactly one tab in a `TabView` strip is tabbable | the tablist vanishes from the keyboard |
| A pooled gutter arrow's row is read per frame | it toggles whatever row its slot first served |
| **List the assignment operators**; never "ends with `=` and does not begin with one" | plain `=` reads as a fetch and the refactoring discards writes |
| **A `transform` list applies its rightmost function first** (`SvgTransformOrderTest`) | a nested group lands off the canvas |
| **An arc's segment count is per quarter turn** | a circle at the small tier is an octagon |
