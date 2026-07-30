# CrystalGUI — P6 Working TODO

**Editor workflow windows, and the node-graph substrate they will host.**
Split out of `CrystalGUI_TODO.md` on 2026-07-30, because P6 was two bullets holding roughly a dozen
real features and could not be planned from where it sat. That file stays the live plan for P0–P5 and
links here; this file is authoritative for everything P6.

---

## Scope — settled, and it decides most of what follows

> **Both.** This is a general-purpose editor framework, *and* the shader graph is its first client.
> **6.1 comes before 6.2** because the graph view is a document type that lives inside editor windows —
> panels, tabs, an inspector, a file/asset browser — not a standalone screen.

That answer is load-bearing, so it is worth being explicit about what it rules out. A *graph-serving-only*
reading would have made most of 6.1 unnecessary: node properties need a small inspector, not a Configurator
framework; picking a texture needs a filtered list, not file management; a GLSL snippet field needs about
300 lines of text with no virtualisation at all. Choosing "general-purpose" is choosing the larger list
deliberately, and the ordering below is what keeps that from becoming unbounded.

---

## Where we are — audited, not assumed

Everything in this section was checked against the code on 2026-07-30. Where something is inferred rather
than verified it says so.

### What already exists and is reusable as-is

| Need | Covered by |
|---|---|
| Splits, nesting, draggable dividers | `SplitView` |
| Tabbed document areas | `TabView` / `Tab` |
| Floating, movable, resizable windows | `Dialog` + `DialogManager` + CSS `resize` (8 handles) |
| Context menus, dropdowns, submenus | `Menu` / `MenuItem` / `Dropdown` / `Popover` |
| Modality, focus trapping, Escape | `showModal()`, `inert`, close watchers |
| Scrolling as an ambient capability | `overflow` on any element; `ScrollerView` for visible bars |
| Drag with payload, drop targets, ghost | `UIDragController` |
| Pan/zoom without reflow | CSS `transform` — layout-free by construction, and `applyTo` is shared by hit-test and pose, so clicks follow |
| Hover help | `Tooltip` |
| Persisting a layout | `UIDescriptionCodec` + `StateMap` already round-trip a tree |
| Server-authored panels | `serialization/` + `net/` |
| Clipboard, sound as platform seams | `UIClipboard`, `UISoundSystem` — the SPI pattern to copy |

### What is absent — verified

| Missing | Evidence |
|---|---|
| ~~**Styled text runs**~~ | **done** — shipped as the CSS Custom Highlight API, see 6.1.1 |
| **Multi-line text editing** | `TextField` contains no newline handling at all — not "single-line by default", single-line by construction |
| **Virtualisation** | No windowed/recycling view in `core/`; every element is a real Taffy node |
| **Keymap / accelerators** | No `Accelerator`/`KeyBinding`/`Keymap`/`Shortcut` type exists; keyboard events bubble and that is the whole story |
| **Tree** | No tree widget |
| **Table / data grid** | No table widget |
| **Command / undo stack** | Nothing |
| **File system access** | Nothing, and `core/` is platform-agnostic — this needs the `UIClipboard` treatment |
| **Line / curve / stroke rendering** | No drawable draws anything non-rectangular; `sdf.glsl` has rounded-box + coverage only; CrystalGraphics has no line topology in use |

### The one good surprise

`CgShapedParagraph`'s own javadoc describes its content as **"BiDi/style-span-split runs"**, and
`CgShapedRun.clusterIds` holds byte offsets into the original UTF-8 text, documented as being *"used for
cursor/selection mapping (back-mapping shaped glyphs to source characters)"*.

So the backend already shapes styled spans and already carries glyph→character mapping. Those are precisely
the two hard parts of a syntax-highlighted editor with a caret.

> **Verified 2026-07-30, and it was better than inferred.** Not only are spans drivable — `CgStyleSpan`,
> `CgStyledText` and BiDi ∩ span run splitting were already *finished*, with every field consumed. Colour
> does not even need one draw per run: `CgShapedRun` carries `argbColor` per run and the resolver applies
> `overrideColor != 0 ? overrideColor : rgba` per glyph. 6.1.1 landed as a translation layer with zero
> backend changes. The remaining half — `clusterIds` for caret mapping — is confirmed present and is
> 6.1.6's to consume.

---

## Standing decisions for P6

**The web is still the reference, but it stops being the *only* one.** P0–P5 ported a browser: DOM, cascade,
top layer, popovers. An editor framework has no browser equivalent to copy — there is no `<tree>`, no
`<table>` worth having, no docking in HTML. So the reference set widens to the places these problems *are*
solved: **VS Code / Monaco** for the text editor and command palette, the **ARIA APG** for tree and grid
keyboard semantics (that *is* the web's answer, and it is a good one), **LDLib2** for the Configurator, and
conventional desktop IDEs for docking. Where the web has an answer we still take it.

**Virtualisation is a widget shape, not an optimisation.** It is tempting to treat "make lists fast" as
something to retrofit. It is not: a virtualised view is a window over a *data model* with element recycling,
which is a different API from "add children to a `ScrollerView`". Deciding it late means rewriting every
consumer. It is scheduled third for that reason, before anything that would consume it.

**The command/undo shape must be settled before the widgets exist.** Whether widgets mutate a model directly
or emit commands is close to impossible to reverse once a dozen widgets have picked one. This is not a large
piece of code; it is an early one.

**No pixel values in Java, still.** Everything below obeys the existing rule: structure and state in Java,
geometry in `default.css`, appearance in the theme.

**Every item lands with a harness scene.** Same as P1–P5 — visual confirmation is what has caught nearly
every real bug in this project, and none of these widgets are an exception.

---

# 6.1 — Editor workflow windows

The original bullet read *"resource view, action history, draggable panels, custom tab elements with
open/close affordances, and a Configurator interface"* with an instruction not to start from it. Re-planned
into the list below, ordered by what unblocks what.

### 6.1.1 Highlights — the CSS Custom Highlight API · `DONE` (2026-07-31) · **foundation**

Styling ranges of text without wrapping them in elements. The single mechanism behind syntax
highlighting, search matches, spell-check marks and diff runs.

**Shipped as** `TextRange` + `HighlightRegistry` in `com.crystalgui.ui.text`, `UIText.highlights()`, a
`::highlight(name)` pseudo-element in the selector engine, `HighlightStyle` + a pseudo-element cascade in
`StyleEngine`, and a `text-decoration-line` property. 16 tests in `HighlightTest`, 5 gallery rows.

```java
text.highlights().set("keyword", TextRange.of(0, 4));   // Java says WHERE
```
```css
::highlight(keyword) { color: #C678DD; }                /* CSS says WHAT */
```

#### This was built twice, and the first version was wrong

The first pass took a `List<TextSpan>` carrying literal colours from Java. It worked, it was tested, it
was on screen — and it was **not what the web does**, which is the standing directive for this project.

The web has exactly two mechanisms and this was neither:

1. **Rich text as content** — inline child elements (`<span>`, `<b>`, `<i>`) in an inline formatting
   context. Styling comes from the ordinary cascade, per element.
2. **Decoration over content** — the **CSS Custom Highlight API**: `Range` → `Highlight` →
   `CSS.highlights.set(name, hl)` → `::highlight(name)` in CSS. It exists *specifically* because editors
   like Monaco and CodeMirror cannot afford a `<span>` per token.

A span list on a text node is not in the DOM at all. And 6.1.1's actual consumer — 6.1.7's syntax
highlighting — is mechanism 2, so that is what it should have been from the start.

Three concrete faults in the first version, each fixed:

- **It allowed layout-affecting properties** (`bold`, `italic`, `baselineShift`). CSS Pseudo-Elements 4
  restricts highlight pseudo-elements to properties that *"do not affect layout"*, and the restriction
  **is the feature**: a highlight must never reflow the text it highlights, or typing in a search box
  would reshuffle the lines being searched. `StyleEngine` now drops anything outside
  `{color, text-decoration-line}` with a warning naming the spec.
- **It put colours in Java.** `.paint("vec3", 0xFFC678DD)` in a gallery scene — against the web *and*
  against this project's own rule that colours belong in the sheet. A syntax theme is now CSS.
- **It conflated the two web mechanisms**, offering bold/italic (mechanism 1's job) through mechanism 2's
  shape. Rich text as content is now explicitly out of scope until inline layout exists.

#### The engine's first pseudo-element

`::highlight(name)` required real selector work, and the two things most likely to be broken later:

- **A compound carrying a pseudo-element must never match the element itself.** `text::highlight(kw)`
  selects the overlay, not the `text`. Letting it match would repaint the whole paragraph — and look
  plausible, since the highlighted words would be the right colour too. `matches()` and
  `matchesOriginating()` are separate for this reason.
- **Specificity weight is 1, not 10.** CSS Selectors 4 counts pseudo-elements in the *type* component,
  not the class component. Easy to get wrong by analogy with pseudo-classes, which really are 10.

`::before`/`::after` are rejected at parse time with a message pointing at internal children — this
engine has no structural pseudo-elements and is not about to grow them.

#### Known divergence: we re-shape, the web overlays

Browsers paint highlights over text that is **already laid out**, so a highlight provably cannot change
metrics. Here the ranges become `CgStyleSpan`s at *shape* time, because the backend bakes glyph colour
into the shaped run (`CgResolvedGlyphs`: `overrideColor != 0 ? overrideColor : rgba`). A span boundary is
a shaping-run boundary and separately-shaped runs lose the kerning across them, so a highlight here can
move the measured width by a fraction of a pixel.

Mitigated, not hidden: **un-highlighted text stays on the unspanned path entirely**, so ordinary labels
measure exactly as they always did (`applyingAndClearingHighlightsRestoresTheExactPlainWidth`, zero
tolerance). Closing it properly needs draw-time per-range colour in CrystalGraphics driven by
`CgShapedRun.clusterIds` — the same mapping 6.1.6 needs for its caret, so the two should be done
together.

#### Other decisions worth not re-litigating

- **The registry is per element, not global.** `CSS.highlights` can be global because a DOM `Range`
  carries its container node; a `TextRange` is bare indices, so the element has to come from somewhere.
  The name→style mapping is still global, which is the half that matters for theming.
- **Overlapping highlights resolve by registration order**, last wins, and overlap *within* one name is
  rejected. The web layers by priority; ours is the rule a single shaped run per character can actually
  express.
- **Ranges are clipped, not rejected,** at the translation boundary — shortening the text and
  `text-overflow: ellipsis` both leave ranges past the end of the string being shaped, and
  `CgStyledText` rejects that from inside a paint with no caller code on the stack.
- **`text-shadow` needs a second shaped paragraph**, since a highlight colour beats the draw colour
  downstream and a red word would otherwise get a bright red shadow.
- **`text-decoration-line`, not the `text-decoration` shorthand.** The shorthand also sets `-color`,
  `-style` and `-thickness`, none of which the text stack can express; registering it would advertise
  three knobs that do nothing.

#### `background-color` and `text-shadow` are refused, not silently dropped

CSS allows both on a highlight pseudo-element. This engine cannot paint either, and the reason is the
same: both need **per-range geometry**. A background is a band behind a character range; a highlight's
own text-shadow is a second draw of just that range. `CgStyleSpan` carries colour and decorations and
nothing positional, so neither is expressible.

They were briefly in the allowed set, resolving through the cascade and then being dropped on the floor —
caught by eye, because a search-hit row on the gallery page showed no band. That is the failure mode
`CgStyleSpan`'s own javadoc records about three fields that were carried faithfully and then ignored: *"an
API that advertises a capability it does not have is worse than one that does not offer it."*

So `HighlightStyle` now splits `ALLOWED` from `NOT_YET_PAINTABLE`, and the two produce **different
warnings** — "CSS forbids this" versus "CSS allows this, we cannot draw it yet". Collapsing them would
tell an author to go and check a spec that agrees with them.
`backgroundColorIsRefusedUntilItCanBePainted` is written to be **flipped** when the geometry lands, which
is the same work 6.1.6 needs for a caret.

#### Deliberately not done here

- **Rich text as content** (bold/italic as document content). That is the web's *other* mechanism and
  needs an inline formatting context Taffy does not provide.
- **Markup** (`<b>`, `<color=#RRGGBB>`, `§l`). `CgMarkupParser.HTML`/`.MINECRAFT` exist and it is one
  call, but markup strips its own tags, so `getText()` and the painted string diverge — which
  `displayedText()`, the ellipsis search and every measurement path assume they do not.
- **Serializing highlights** through `UIDescriptionCodec`/`StateMap`. `TextRange` is headless-safe so a
  server *can* author them; the codec is the second half.
- **`::selection`.** Now clearly the same mechanism as `::highlight()`, which makes the existing ad-hoc
  `selection-color` property look like what it is — a one-off. Worth folding in when `TextField` next
  gets attention.


### 6.1.2 Keymap and accelerators · `MOSTLY DONE` (2026-07-31)

Chords, sequences, scoping, remapping and a conflict report. Cheapest item on the list and
disproportionately what makes an application *feel* like an editor — and placed second because
everything after it wants to register bindings. Retrofitting a keymap means revisiting every widget that
grew its own key handling in the meantime, which is exactly how `Button` would have ended up with
keyboard code if `UIInputHandler` had not synthesised activation centrally.

#### Is taking from five sources a Frankenstein? Only if you let it be

**A design is Frankenstein when two mechanisms can disagree about the same question. It is a synthesis
when each source answers a different question.** Counting the questions is therefore the whole discipline,
and here there are only four:

| Question | What the sources say | Contested? |
|---|---|---|
| What does a binding point at? | VS Code, Blender, Unity and Unreal **all** say a command/action ID | No — consensus, not a choice |
| What is it keyed on? | VS Code: chord sequences · Blender: event type | No — orthogonal axes of one key |
| How does a user change it? | Photoshop/Resolve: swappable presets | No — falls out of question 1 free |
| **What decides whether a binding is active?** | VS Code `when` · Unity action maps · **us: the focus path** | **Yes — the only one** |

Three of four cost nothing. For the fourth, pick one answer and record who lost and why: `when` clauses
exist because VS Code has no tree to walk, and Unity's action maps are the same idea at coarser
granularity. We have a real DOM, so the focus path wins and neither of the others is admitted.

The rule that keeps it honest going forward: **one question, one mechanism.** The first draft of this plan
broke it once — see the per-binding predicate, below — which is a fair indication of how easily it
happens.

#### Prior art, and the verdict

| Source | What it contributes |
|---|---|
| **VS Code** | The best-documented model there is: `{ key, command, when }` as **data**, commands as string IDs, chords as space-separated strokes, `-command` to remove a default. Resolution is bottom-to-top, first match on key **and** `when` wins. Also the closest thing to a "web" answer, being an Electron app with a public spec. |
| **Blender** | Keymaps are per-editor-context and per-event-*type* — press, release, click, double-click, drag. The event-type axis is the part worth stealing; nothing else has it and space-to-pan needs it. |
| **Photoshop / DaVinci Resolve** | Swappable **presets** (Resolve ships Premiere/FCP/Avid maps), and bare single-key tool shortcuts (`B` brush, `V` move) that must not fire while a text field has focus. |
| **Unity / Unreal** | Bind abstract **actions**, not keys; action *maps* enabled per context. Same idea as command IDs plus scoping, arrived at from the other direction. |
| **The web platform itself** | **Nothing usable.** `accessKey` is the only built-in and is universally avoided; everything real is a library (tinykeys, Mousetrap). This is a genuine case for the P6 standing decision that the reference set widens where the web has no answer. |

#### The design: element-scoped bindings, command IDs, no expression language

VS Code needs `when` clauses because its context is not a tree it can walk — `editorTextFocus`,
`listHasSelection` and friends are hand-maintained booleans. **We have a real DOM with focus**, so the
tree *is* the context, and scoping falls out of machinery that already exists:

```java
CommandRegistry.register(Command.of("edit.save", "Save").run(ctx -> ...));

element.keymap().bind("Mod+S", "edit.save");           // scoped to this subtree
element.keymap().bind("Mod+K Mod+S", "edit.saveAll");  // chord sequence
window.keymap().bind("Mod+Shift+P", "palette.open");   // application-wide = bound at the root
```

**Resolution walks the focus path outward, innermost scope first** — the same order events bubble, and
the right answer for the obvious conflict: a text field's `Mod+A` (select all text) must beat the
window's `Mod+A` (select all items) without either knowing about the other. That is `when`-clause
specificity, obtained structurally instead of by an expression language.

**No `when` expression language in v1.** A string mini-language over hand-maintained context keys is a
permanent maintenance burden and precisely the kind of non-web invention this project avoids.

**And no per-binding predicate either** — that was in the first draft of this plan and it was a second
activation mechanism wearing a disguise. The moment one exists, somebody writes a predicate that
duplicates scoping badly and two things decide whether a binding fires.

The replacement is better than what it replaces. "`Delete` needs a selection" is not a property of the
keystroke, it is a property of the **command** — and a greyed-out menu item needs the identical answer:

- **`KeyBinding` answers "where, and which keys."** Scope plus chord. Nothing else.
- **`Command` answers "can I run right now."** `isEnabled()`, consulted by the keymap, by menu items, and
  by the command palette.

One enablement mechanism, three consumers, no way for them to disagree. It is also what Photoshop and
Resolve actually do: they grey out the *command*, they do not disable the shortcut.

A binding loaded from a *file* therefore needs only scope + command — which is all a user remapping ever
wants anyway, since those apps let you rebind the key and never the condition.

#### Where it hooks in

**After** `KeyboardEvent.Down` has finished bubbling and was not `preventDefault()`ed. That is exactly
how a browser applies its own shortcuts: page handlers run first and may consume the event. Resolving
ahead of dispatch would let a global `Mod+F` steal a keystroke from a control that wanted it, with no way
for the control to object.

#### The types

| Type | Role |
|---|---|
| `KeyStroke` | One key + a `CgModifiers` bitmask. Parsed from `"Mod+Shift+P"`. Value type, interned-cheap. |
| `KeyChord` | A **sequence** of one or more strokes. VS Code caps at two; N is no harder and Blender/Emacs users expect it. |
| `Command` | String id + human label + handler. The label is what a menu item and the command palette render. |
| `CommandRegistry` | id → `Command`. One per… **window or global?** See open questions. |
| `KeyBinding` | chord → command id, plus optional predicate, event type, and source (for the conflict report). |
| `Keymap` | Per-element list of bindings. `UIElement.keymap()`, built lazily so an element that binds nothing costs one null field. |
| `KeymapResolver` | Owns pending-chord state and the focus-path walk. Lives on `UIInputHandler`, beside the focus it already tracks. |

#### Six things that will bite, named now

1. **A bare single-key binding must not fire while text input has focus.** `B` selects the brush in
   Photoshop and types a "b" in a filename box. `UIElement.consumesTextInput()` already exists and is
   exactly the guard; the default is to suppress unmodified single-key bindings when it returns true,
   with an explicit opt-out. Getting this wrong makes every tool shortcut corrupt every text field.
2. **`Mod`, not `Ctrl`.** One token resolving to Ctrl on Windows/Linux and Super on macOS, the way
   CodeMirror and tinykeys do it — rather than VS Code's parallel `mac:` bindings, which double the file.
   `CgModifiers` already has `SUPER`.
3. **Chords need a visible pending state and a timeout.** VS Code shows "(Ctrl+K) was pressed, waiting…"
   in the status bar. Without feedback a half-entered chord is indistinguishable from a dead keyboard.
   The pending state must also be cleared by focus changes and by any non-matching key.
4. **Press versus release, and only those two.** Space-to-pan (Photoshop, and 6.2.2's canvas) is a
   *hold*, not a press, so the event-type axis has to exist from the start rather than being bolted on as
   a second mechanism later. But Blender's full taxonomy is press/release/click/double-click/drag, and
   importing all five would be borrowing a vocabulary we do not need — click and drag are mouse concepts
   `UIInputHandler` already owns. **Take the axis, leave the enum.**
5. **Conflicts must be reported, not silently resolved.** Two bindings on the same chord in the same
   scope is a bug in the sheet, and innermost-first resolution would hide it. A `conflicts()` report that
   a test can assert on, and a warning at registration.
6. **A binding whose command id is not registered.** Real, because sheets and registries are edited
   separately — warn at bind time rather than failing silently on the keystroke.

#### Status

**Built and green (21 tests in `KeymapTest`, suite 809):** `KeyStroke`/`KeyChord`/`KeyBinding`/`Keymap`/
`KeymapResolver`, `Command`/`CommandContext`/`CommandRegistry`, `UIElement.keymap()`, resolution wired
into `UIInputHandler` after bubble, pending-chord cancellation on focus change, and a `keymap` gallery
page.

**Reverse lookup added 2026-07-31** — `Keymap.acceleratorFor` (what a menu item renders) and
`acceleratorsFrom` (what a palette lists), both walking the resolver's own path so a label cannot
disagree with what fires.

**Not yet built — the one remaining deliverable:** the **keymap sheet parser**. Bindings being data is a
premise of the whole design ("what makes them remappable"), and until a sheet can be parsed that claim is
theoretical: today bindings are only reachable from Java. It is additive — `{key, command}` entries plus
`-command` removal, over the existing `JsonOps` — and nothing else waits on it.

Two design points were confirmed by building rather than by argument:

- **`CommandRegistry` is per-`UIWindow`, not global** — the plan left this open. Deciding factor was not
  the server-driven case but tests: a global mutable registry leaks between them, so one test registering
  a command silently changes what another resolves.
- **A disabled command falls through to an outer scope** rather than stopping the walk. That makes
  enablement a routing decision, and is what lets a disabled editor command hand its key to an
  application-wide one.

#### Deliverables

- `core/input/keymap/`: `KeyStroke`, `KeyChord`, `KeyBinding`, `Keymap`, `KeymapResolver`.
- `core/command/`: `Command`, `CommandRegistry`. Deliberately its own package — 6.1.9's undo stack is the
  natural consumer, and `Command` must be able to grow an undoable-edit return without an API break.
- `UIElement.keymap()` + resolution wired into `UIInputHandler` after bubble.
- A parser for keymap **sheets** (VS Code-shaped `{key, command}` entries, `-command` to remove) so
  presets and user remapping are data. Reuses `serialization/`'s `JsonOps`.
- Tests: parsing, chord sequences and timeout, innermost-wins, the text-input guard, conflict reporting,
  `Mod` platform resolution, removal entries, press-vs-release.
- Harness: a `keymap` gallery page showing live chord state, a scoped binding that only fires inside one
  panel, and a tool-shortcut row that proves it stays out of the text field next to it.

#### Will this get rewritten once the editor actually uses it?

Asked directly on 2026-07-31, and worth an honest answer rather than a reassuring one. **My estimate: the
core survives, the edges get added.** What follows is what I would bet on, and what would change my mind.

**Likely safe.** The command-id indirection is the one genuinely load-bearing choice, and four independent
editors converged on it — VS Code, Blender, Unity, Unreal. Focus-path scoping covers every case that can
currently be enumerated, and it is *less* machinery than `when` clauses, so if it proves insufficient the
move is additive rather than an unwind.

**Three things could force real change, in descending order of risk:**

1. **Undo integration (6.1.9).** Adding an undoable-edit return is additive — a second registration path
   plus a push inside `Command.execute`, with existing callers untouched. The risk is not that. It is the
   decision, **still unmade**, of whether *every* mutation must go through a command. If it must, then
   every widget that currently mutates a model directly changes, and this stops being shortcut plumbing
   and becomes the application's spine. A spine designed as plumbing is the classic rewrite. **Settle the
   shape before 6.1.6**, not after there are fifty commands.
2. **Modal / layered keymaps.** Scoping cannot express "the same chord means something else in a different
   *mode*" — Vim-style editing, or a tool that captures keys while active. `Command.isEnabled` returning
   false falls through to an *outer scope*, not to a different binding in the same one, and two bindings
   on one chord in one scope is a reported conflict. Modes would need a keymap-layer concept. Not a
   resolver rewrite, but a genuine addition to the model. **Deliberately not built speculatively** — that
   is how `when`-clause complexity creeps back in through a side door.
3. **Key repeat.** Currently ignored globally, which is right for shortcuts and wrong for anything
   navigational. A per-binding opt-in when something asks; additive.

**One gap was found by asking this question and closed immediately:** there was no reverse lookup, so a
menu item could not render "Ctrl+S" beside its label and a palette could not list chords at all. Both
6.1.8 and 6.1.12 need it, and it is the kind of thing that gets improvised around at three call sites if
left absent. `Keymap.acceleratorFor` / `acceleratorsFrom` now walk **the same path, in the same order, as
`KeymapResolver`** — which is the property that matters: a cheaper implementation (flat registry,
first-found scan) can drift from resolution, and the failure mode is a menu confidently advertising a
shortcut that does something else. It is also why a chord is *not* stored on `Command`: the same id can be
bound in several scopes to different chords, so "the accelerator" is meaningless without asking from
somewhere.

#### Explicit non-goals

- **No `when` expression language.** See above.
- **No global OS hotkeys.** Out of scope for an in-game UI and a platform concern regardless.
- **No key-repeat semantics** beyond what the platform already delivers.
- **No chord *display* localisation.** Labels render the same tokens they parse.

#### Open questions

| Question | Why it matters |
|---|---|
| Is `CommandRegistry` global or per-`UIWindow`? | Global is simpler and matches VS Code; per-window is more correct for a server-driven UI where two windows could disagree about what `edit.save` means. Leaning global with a window-scoped override. |
| Do commands need arguments (VS Code's `args`)? | Cheap to add now, awkward later. Leaning yes, as an opaque payload the handler casts. |
| Should `default.css`-style *default bindings* ship in a user-agent keymap sheet? | Consistent with `StyleSheet.DEFAULT`, and it is where `Tab`/`Escape`/`Space` activation would eventually belong — but those live in `UIInputHandler` today and moving them is a separate, riskier change. |


### 6.1.3 Virtualised list view · `TODO` · **foundation**

A windowed view over a data model that materialises only the visible range plus a small overscan, recycling
elements as it scrolls.

- Fixed row height first; variable height (needed for wrapped code lines) as a second pass with a measured
  offset index.
- The recycling contract is the hard part: what is guaranteed about a recycled element's state, and how a
  consumer binds data to it without leaking the previous row's listeners.
- Interacts with `smooth scroll`, `scrollIntoView`, and `getMaxScroll*`, all of which currently assume real
  children.

### 6.1.4 Tree · `TODO`

Expand/collapse, selection (single and range), keyboard navigation per the ARIA APG tree pattern, and
drag-reorder. Built on 6.1.3 — a tree is a flattened virtualised list plus an expansion model.

Feeds the resource/file browser, the outline view, and the graph view's node-library palette.

### 6.1.5 Table / data grid · `TODO`

Resizable and reorderable columns, sortable headers, row selection. Also on 6.1.3. The "details" half of a
file browser, and the shape most property tables want.

`resize` already exists as an element capability and should be reused for the column dividers rather than
grown a second time — same reasoning that made `SplitView` use the drag controller.

### 6.1.6 Multi-line text buffer and editor · `TODO` · **the large one**

The plain-text half: a line-based buffer (piece table or gap buffer), caret and selection across lines,
word-wise and page navigation, soft-wrap toggle, and undo with edit coalescing.

Explicitly **not** an extension of `TextField`. That widget's caret and selection logic is single-line by
construction; sharing it would mean generalising every method on it while it stays in use. A common
`EditableText` seam underneath both is worth considering once the second one exists — not before.

> Honest scoping note: this is the largest widget in any UI toolkit. It is placed sixth so that it consumes
> three finished foundations rather than growing its own private versions of them, which is the failure mode
> to watch for.

### 6.1.7 Code editor · `TODO`

6.1.6 plus a gutter, line numbers, current-line highlight, syntax highlighting (6.1.1), bracket matching,
indent handling, and find/replace.

Highlighting wants a tokenizer seam that a caller supplies — GLSL first, since that is the shader graph's
need, but the widget must not know what GLSL is.

### 6.1.8 Configurator · `TODO`

Point it at an object, get an editing UI. Annotation-driven, concept borrowed from LDLib2 — which has the
full prior art checked in at `research_repos/LDLib2/src/main/java/com/lowdragmc/lowdraglib2/configurator/`:
a `ConfiguratorParser`, an `IConfiguratorAccessor` SPI with ~10 accessors, and ~20 concrete configurator
widgets (`NumberConfigurator`, `ColorConfigurator`, `SelectorConfigurator`, `ArrayConfiguratorGroup`, …).

Port and fine-tune rather than reinvent. Mostly composition over finished widgets, which is why it sits
after the Tree and Table rather than before them.

This is what a node's property panel is made of.

### 6.1.9 Command and undo system · `TODO`

A command stack with coalescing, labels for a history panel, and a decision on whether commands are
serializable (they should be, given `serialization/` exists and a server-authored editor is a real target).

Small, and could move earlier — see the standing decision above. Its *design* must precede 6.1.6, even if
its implementation lands alongside.

### 6.1.10 File system SPI and browser · `TODO`

An interface in `core/` with per-platform implementations, following `UIClipboard`/`UISoundSystem` exactly.

> **Open design question.** In a Minecraft context "the filesystem" is most likely resource packs, world
> data, or server-side storage rather than `java.io`. The SPI should be shaped around *what the client can
> actually be handed*, not around POSIX. Settle this before writing the browser UI on top.

### 6.1.11 Docking and workspace layout · `TODO`

`SplitView` + `TabView` + `Dialog` already cover roughly 80%. The remaining 20% is a dock manager: tear a
tab out into a floating window, drop it on an edge to split, drop it on a tab strip to join, and
serialise/restore the whole arrangement.

Serialisation is close to free — `UIDescriptionCodec` and `StateMap` already round-trip a tree.

### 6.1.12 Chrome · `TODO`

Toolbar, status bar, breadcrumbs, command palette. All composition over finished parts (the palette is a
`Dialog` + `TextField` + 6.1.3). Grouped as one item because none of them is individually interesting.

---

# 6.2 — Node graph view

The grand goal's actual substrate. Blocked on 6.1 for its container, and on 6.2.1 for its ability to draw
anything at all.

### 6.2.1 `CgCurveRenderer` · `TODO` · **the one true engine gap**

**Lives in CrystalGraphics**, per the ownership boundary: this is backend rendering capability, not UI
orchestration. Designed to mirror `CgQuadRenderer`'s API exactly, and instanced.

#### The primitive is a quadratic Bézier, and that is the whole design

- A **straight line** is a quadratic whose control point is the midpoint.
- A **cubic** splits into 2–4 quadratics on the CPU, exactly as font rasterizers do.
- **Arcs, polylines, rounded elbows** — sequences of quadratics.

One primitive genuinely covers everything, which is why the name is `CgCurveRenderer` and not
`CgLineRenderer`: `curve.line(...)` is a natural convenience, whereas `line.curve(...)` reads as a
contradiction.

The technical reason to draw the line at quadratic: it has an **exact analytic SDF** (Quilez's `sdBezier`,
one closed-form cubic solve). A cubic's distance is a quintic with no closed form, so choosing cubic as the
primitive means approximating per-pixel forever. Splitting once on the CPU is cheaper *and* exact.

#### Instance schema

```
vec3  p0, p1, p2      // quadratic control points, world space, pose baked in
vec4  color0, color1  // gradient along the curve
vec2  widths          // start/end half-width — tapered strokes
float feather         // AA softness
float flags           // caps (butt / round / square), packed
vec2  dash            // period, phase; 0 = solid
```

23 floats against the quad's 18. Same `STD430` `CgBufferFormat`, same class-wide `CgShaderBuffer`, same
per-instance CPU accumulation so independent callers batch on their own schedule.

Gradient and taper are near-free once the record exists, and a graph editor wants both — wires tinted from
source-port colour to destination-port colour is the standard idiom.

**The vertex shader computes its own bounding quad** from the three control points (a Bézier lies within the
convex hull of its controls), expanded by `max(width) + feather`. No CPU bounds maths, and correct under an
arbitrary `pose` — same unit-quad mesh, same `origin`/`right`/`up` trick, derived rather than supplied.

#### API

```java
CgCurveRenderer r = CgCurveRenderer.create();
r.useMaterial(material);            // same contract: rebind every frame, auto-flush on switch
r.begin();
r.curve().line(x0, y0, x1, y1).width(2f).color(argb).submit();
r.curve().from(x0, y0).via(cx, cy).to(x1, y1).width(4f, 1f).colors(a, b).submit();
r.curve().cubic(p0, c1, c2, p3).width(2f).submit();   // splits internally -> N instances, one call
r.flush();                          // the only upload + draw
r.end();
```

Plus `retainedCurve()` alongside `retainedQuad()`, `pose(Matrix4f)` baked at `submit()` into reused
`Vector3f` scratch, and the same "build and submit in one expression, never hold the scratch" rule.

#### Four supporting pieces, each with a trap already documented elsewhere

1. **`CgBindingPoints.CURVE_RENDERER`** — one line. Engine bindings allocate downward from the max
   (`new Binding(--maxSsboBindings, --maxTextureUnits)`), so a slot exists.
2. **`#pragma cg_use curve`** in `CgEngineBufferRegistry`, seeded with a **method reference** to
   `instanceBuffer()` — never a field read, or registering the token triggers static init before
   `CgBindingPoints.init` has run. This has genuinely fired for `CgTextRenderer`'s `TEXT_DATA_UBO`.
3. **`CG_CURVE_*` macros** in `cg_env.glsl`, hardcoding `MACRO_NAME = "CURVE_DATA"`. No Java-side attach
   helper — the one that existed for quads lost to anything that compiled the shader earlier, and surfaced
   as an unrelated `#pragma cg_feature` complaint.
4. **`sdf_bezier` in `sdf.glsl`**, placed **above** the `#ifndef CG_VERTEX_STAGE` guard. It is pure maths
   with no derivative builtins; only `sdf_coverage` needs the guard. Getting this backwards is what made the
   gallery unlaunchable on AMD while running fine on NVIDIA.

Harness-testable in isolation, independent of everything in 6.1, and roughly a day's work.

### 6.2.2 Pan/zoom canvas · `TODO`

A viewport widget: wheel-zoom about the cursor, middle-drag or space-drag to pan, fit-to-content, and
culling of off-screen nodes.

`transform` already does the hard part — it is layout-free, so scaling the canvas cannot reflow anything,
and `UITransform.applyTo` is shared between the hit-test chain and the render `PoseStack`, so clicks follow
the picture. What is missing is the widget, the input gestures, and the culling.

### 6.2.3 Node and port widgets · `TODO`

A node box (title, collapsible body, a column of input ports and output ports) and a port that can be
dragged from to start a connection.

Drag-to-connect is `UIDragController` with a payload and `preventDefault()` acceptance on `DragOver` — the
existing rejection-by-default protocol is exactly right for "this port will not accept a vec3".

### 6.2.4 Selection, marquee, and graph editing · `TODO`

Box-select, multi-select, move-many, delete, duplicate, and connection re-routing. Sits on 6.1.9's command
stack — this is where undo stops being optional.

### 6.2.5 Graph document model and serialization · `TODO`

Nodes, ports, typed connections, validation, and round-tripping through `serialization/`. The point at which
this stops being a UI demo and becomes the shader graph's actual data.

---

## Ordering, and what blocks what

```
6.1.1 highlights ──┬─────────────────────────────► 6.1.7 code editor
                    │                                    ▲
6.1.2 keymap ───────┼────────────────────────────────────┤
                    │                                    │
6.1.3 virtualised ──┼──┬── 6.1.4 tree ──┬── 6.1.8 configurator
                    │  │                │
                    │  └── 6.1.5 table ─┘
                    │
                    └── 6.1.6 text buffer ───────────────┘
                              ▲
6.1.9 command/undo (design) ──┘──────────────────────► 6.2.4

6.1.10 file SPI ──► 6.1.11 docking ──► 6.1.12 chrome

6.2.1 CgCurveRenderer ──► 6.2.2 canvas ──► 6.2.3 nodes/ports ──► 6.2.4 editing ──► 6.2.5 model
```

**Recommended sequence:** ~~6.1.1~~ → **6.1.2 (planned)** → 6.1.3 → 6.1.4 → 6.1.5 → 6.1.6 → 6.1.7 → 6.1.8, with 6.1.9's
*design* settled before 6.1.6 starts, then 6.1.10–12, then the 6.2 chain.

6.2.1 is the one item that can be pulled forward at any time — it is self-contained, depends on nothing in
6.1, and is harness-testable on its own.

---

## Open questions

| Question | Blocks | Notes |
|---|---|---|
| ~~Are `CgShapedParagraph`'s style spans drivable without backend work?~~ | ~~6.1.1~~ | **Answered: yes, entirely.** The backend was already complete; 6.1.1 was a translation layer. |
| What *is* the filesystem in a Minecraft context — resource packs, world data, server storage? | 6.1.10 | Shape the SPI around what the client can be handed, not around POSIX. |
| Do widgets mutate models directly, or emit commands? | 6.1.9, and the API of everything after it | Near-irreversible once a dozen widgets have chosen. |
| Fixed-height rows only for the first virtualised pass? | 6.1.3 | Variable height is needed for wrapped code lines, so it is deferred rather than skipped. |
| Does the code editor need multi-cursor? | 6.1.7 | Cheap to design for, expensive to retrofit. Worth an early yes/no. |

---

## Changelog

- **2026-07-31** — **6.1.1 rebuilt as the CSS Custom Highlight API, after it was challenged for not
  matching the web.** The first version shipped green and on screen, and was still the wrong design.
  - **The check that caught it was "is this what the web does?"** — asked about work that already looked
    finished. It was not: the DOM styles ranges either with inline child elements or with the Custom
    Highlight API, and a list of styled spans on a text node is neither.
  - **Three faults, all consequences of the same miss**: it allowed layout-affecting properties the spec
    forbids *precisely because* a highlight must not reflow its own text; it put colours in Java, against
    both the web and this project's own rule; and it offered bold/italic, which belongs to the other
    mechanism entirely.
  - **`::highlight()` is the engine's first pseudo-element.** The load-bearing detail is that a compound
    carrying one must never match the originating element — otherwise every highlight colour repaints the
    whole paragraph, and looks plausible while doing it. Specificity is 1, not 10: CSS counts
    pseudo-elements in the type component.
  - **The remaining divergence is documented rather than papered over**: browsers overlay highlights on
    already-laid-out text, we re-shape, so ours can move the measured width by a fraction of a pixel.
    Un-highlighted text stays on the unspanned path so ordinary labels are unaffected, and the real fix
    needs the same `clusterIds` mapping 6.1.6 wants for its caret.

- **2026-07-30** — **6.1.1 done: styled text runs.** `TextSpan`/`TextDecoration` + `UIText.setSpans`, 12
  tests, 5 gallery rows on the `text-css` page.
  - **The open question this item existed to answer came back "yes, entirely".** CrystalGraphics' span
    machinery was already finished. `UIText`'s javadoc said otherwise and was simply out of date, which is
    a reminder that a stale "not supported yet" note costs more than no note at all.
  - **A CrystalGUI span type, not `CgStyleSpan` exposed.** Servers author spans and have no
    CrystalGraphics on the classpath. Exposing the backend record would have made rich text client-only
    without anyone noticing until a server tried to use it.
  - **Two crash paths found by reasoning about ordering, both closed by clipping at the translation
    boundary**: shortening the text below a span's end, and `text-overflow: ellipsis` painting a prefix.
  - **One visual bug found by reading the backend's colour rule** rather than by running anything: a span
    colour beats the draw colour, so `text-shadow` would paint a red word's shadow in full red.
  - **Markup deferred deliberately**, with the reason recorded — it strips its own tags, so `getText()`
    and the painted string would diverge, and three code paths assume they do not.

- **2026-07-30** — **File created; P6 re-planned from two bullets into seventeen items.** The old P6 said
  *"re-plan this into real tasks when we get here; do not start from this bullet"*, so this is that re-plan,
  grounded in an audit of what actually exists rather than in the bullet's wish list.
  - **Scope settled: general-purpose editor framework, with the shader graph as its first client**, and
    6.1 before 6.2 because the graph view is a document type hosted inside editor windows.
  - **The audit's main finding is that two foundations carry almost everything**: styled text runs and
    virtualisation. Both are absent, both are consumed by four or more later items, and neither can be
    retrofitted cheaply — virtualisation in particular is a different widget *shape*, not an optimisation.
  - **The one good surprise**: CrystalGraphics already shapes style-split runs and already carries
    glyph→character cluster mapping, documented as being for cursor/selection. If that is drivable from
    CrystalGUI, the two hardest parts of a syntax-highlighted editor with a caret are already built.
  - **`CgCurveRenderer` designed in full.** The load-bearing choice is making the primitive a *quadratic*
    Bézier: it has an exact analytic SDF, whereas a cubic's distance is a quintic with no closed form. Since
    a line is a quadratic with a midpoint control and a cubic splits into 2–4 quadratics on the CPU, one
    primitive covers lines, curves, arcs and polylines exactly — which is what makes the API elegant rather
    than merely general.
