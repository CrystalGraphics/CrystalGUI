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


### 6.1.2 Keymap and accelerators · `DONE` (2026-07-31)

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

**Sheet parser done 2026-07-31** — `KeymapSheet`, VS Code's `keybindings.json` shape:
`{key, command}` with optional `on: "release"` and `whileTyping`, plus `-command` to remove. Loaded
through `CgIO`, so a filesystem override and a resource pack both work exactly as they do for
stylesheets. 6 tests.

Two decisions in it worth keeping:

- **A leading minus removes, and removes only that chord/command pairing.** A user sheet is *appended* to
  the defaults rather than replacing them, so without a way to say "not that one" the only route to
  dropping a default would be to redefine the entire default sheet — which then silently stops tracking
  any later change to it. And the removal has to be targeted: taking some other extension's binding off
  the same key would be a surprise nobody could diagnose.
- **A malformed entry is skipped with a warning, never fatal.** The same call the stylesheet parser makes
  for a bad declaration, and for a stronger reason: this is a file a *user* edits, and losing an entire
  remapping to one typo is far worse than losing the line with the typo. An empty `command` is refused
  loudly rather than accepted and ignored — VS Code reads it as "disable this key", which would need a
  chord-suppression concept nothing has asked for.

**6.1.2 is now complete.**

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

#### Built

`ui/elements/list/`: `ListView<T>`, `ListRenderer<T>`, `ItemSizeStrategy`, `FixedHeightStrategy`.
13 tests in `ListViewTest`, a `list` gallery page over 100,000 rows with live counters.

**Selection and keyboard navigation landed 2026-07-31**, after the question "are we completely done?"
made the gap obvious: what existed was a *viewport*, not a list *control*. `SelectionMode`
(NONE/SINGLE/MULTIPLE), an anchor-based Shift-range, Ctrl-toggle, `selectAll`, an `onSelectionChanged`
signal, and the full ARIA listbox key set — arrows, Home/End, PageUp/PageDown, Space, Ctrl+A.

- **Selection follows focus in SINGLE mode**, because arrowing through a list you then have to press
  Space in is a keyboard experience nobody wants. Ctrl+arrow is the APG's escape hatch for reaching a row
  without selecting it.
- **Space and Enter are a pair, not duplicates.** After Ctrl+arrow a row is focused and unselected, so
  Space **adds** it to the selection and Enter **replaces** the selection with just it — and Enter also
  emits `onRowActivated`, because activation ("open this") is a different question from selection
  ("where am I"). Enter did nothing at all at first: `UIInputHandler` turns Space/Enter into a
  synthesized click on the focused element, and since selection here is driven by the *focus* event,
  clicking a row that already has focus changes nothing.
- **Keys are handled on the widget, not through the keymap.** A keymap binding names a *command id*, and
  ids are global to the window — two lists on one screen would need two sets of identical bindings.
  Widget-local keys belong on the widget, which is `TabView`'s existing idiom.
- **Selected rows carry `__selected__`**, not `:checked`. That pseudo-class reads `isChecked()`, which
  would need a row *subclass*, and wrapping every renderer template in one is the "eight wrappers deep"
  structure `TabView`'s javadoc exists to mock.
- **A guard stops the view's own focus moves re-entering the click path.** Without it Ctrl+arrow selected
  anyway, Shift+arrow collapsed a range to one row, and — the latent one, found by the same fix —
  restoring focus to a recycled row silently discarded whatever multi-selection had been built since.

**The model-listener leak is closed too**: an `ObservableList` outlives the views onto it, so a discarded
view kept itself, its pool and every item they referenced alive. It now detaches when it leaves the tree.
Two dead ends worth knowing: `Connection.isConnected()` defaults to `true` unless the concrete signal
overrides it, so it cannot observe a disconnect; and the `DOMEvent.ElementRemoved` route did not fire, so
the detach hangs off the ticker's own detach branch.

**Still deferred, deliberately:** variable row heights (6.1.7 needs them; `ItemSizeStrategy` makes it
additive), and serialization — `ListView` is not in `ElementRegistry` and cannot round-trip, because a
virtualised view should serialize its *model* rather than its window and that is undesigned.

**The three open questions above were all answered by building:**

- **`ListView` owns its scrolling.** Scrolling is an ambient element capability here, so *not* owning it
  — living inside a `ScrollerView` and reading an ancestor's offset every frame — would have been the
  divergence. One `getScrollHeight()` override, from the model rather than the children, is the entire
  seam; max scroll, clamping, smooth scroll and the scrollbar thumb all read through it and needed no
  changes at all.
- **Recycle by pool, and rows are hidden rather than removed.** Removing would destroy the Taffy node and
  every style candidate on it, so the next scroll step would pay to rebuild exactly what it just threw
  away.
- **Fixed overscan of 2.** Predictable and testable; velocity-based is an optimisation wanting a
  measurement first.

**One non-obvious thing the build forced**, and the reason it is worth reading `tickFrame`'s comment:
**the window is re-derived every frame, not on layout.** A scroll offset is state on the element applied
as a pose translate at paint time — nothing in the layout tree changes when it moves — so an
`onLayoutChanged` hook realises the window once and never again. It looks perfect until somebody scrolls.

**And one genuine bug, found by a test rather than by eye:** restoring focus to a recycled row scrolled
the list to where that row's *previous* occupant had been, because `requestFocus` scrolls its target into
view and layout had not yet run. That moved the window, which realised a different set, which restored
focus again — a loop that settled 7,940px from where the caller asked to be. The restore is now deferred
one frame, by which point the row is where it belongs and `scrollIntoView` is correctly a no-op.

Both mutation-checked: realising every row, or taking `getScrollHeight` from the children, each turns six
of the thirteen tests red.

#### Built

`ui/elements/table/`: `TableColumn<T>`, `TableCellRenderer<T>`, `SortOrder`, `TableView<T>`. 16 tests, a
`table` gallery page over 2,000 synthetic assets.

**All three open questions answered, and all three the fuller option:**

- **Three-state sort.** Ascending → descending → *original order*. Free, because sorting produces a view
  and never touches the source — so the unsorted order was never lost. Explorer and Finder offer two
  states and cannot get you back.
- **Selection keys on the ITEM, not the index** — and this is the one thing the table could not inherit
  from `ListView`. Indices are exactly what a sort invalidates: a user who selected three files would own
  three *different* ones after one header click. `ListView` stays index-based, correctly, because a
  `TreeRow` is a record rebuilt on every flatten and has no stable identity to key on.
- **Fixed and flexible widths both**, with weight deciding the leftover share. Dragging a flexible column
  **pins** it — a column that sprang back on release would be maddening, and it is what every file
  manager does.

**Two seams opened on `ListView`**: `invalidateWindow()` became protected, and a new
`setSelectedIndices(Collection)` so a subclass keying selection on something more stable can re-derive it
in one emit rather than one per row.

**The display-side assertion was written first this time**, per the note after 6.1.4 —
`cellsShowTheSortedOrderNotJustTheModel` is deliberately the first test in the file. It is the test that
would have caught all three of this session's re-bind bugs, which hid behind suites that only ever asked
the model.

Mutation-checked: making selection index-based, or the sort two-state, each turns exactly its own test
red.

#### Open questions

| Question | Why it matters |
|---|---|
| Is `CommandRegistry` global or per-`UIWindow`? | Global is simpler and matches VS Code; per-window is more correct for a server-driven UI where two windows could disagree about what `edit.save` means. Leaning global with a window-scoped override. |
| Do commands need arguments (VS Code's `args`)? | Cheap to add now, awkward later. Leaning yes, as an opaque payload the handler casts. |
| Should `default.css`-style *default bindings* ship in a user-agent keymap sheet? | Consistent with `StyleSheet.DEFAULT`, and it is where `Tab`/`Escape`/`Space` activation would eventually belong — but those live in `UIInputHandler` today and moving them is a separate, riskier change. |


### 6.1.3 Virtualised list view · `DONE` (2026-07-31) · **foundation**

A windowed view over a data model that materialises only the visible range plus a small overscan,
recycling elements as it scrolls. The Tree, the Table, the code editor's lines and the command palette
all sit on this, which is why it is scheduled before any of them.

**It is a widget *shape*, not an optimisation.** Tempting to treat "make lists fast" as something to
retrofit; it is not. A virtualised view is a window over a *model* with element recycling, which is a
different API from "add children to a `ScrollerView`" — deciding it late means rewriting every consumer.

#### The web has a native answer now, and it is not this

`content-visibility: auto` plus `contain-intrinsic-size` is Chromium's own mechanism: it turns on layout,
style and paint containment and **skips both layout and paint** for off-screen content, while keeping the
elements in the DOM and in the accessibility tree. `contain-intrinsic-size` supplies a placeholder size so
the scrollbar does not jump as content is realised.

That is a genuinely better answer than virtualisation *for the cases it covers*, because nothing lies:
find-in-page works, Tab reaches the content, `querySelector` finds it, focus survives scrolling.

**It is not sufficient here, for one reason:** every element remains a real Taffy node. A 200k-line file or
a directory of 10k entries costs 200k nodes of memory and 200k entries in every tree walk, whatever we skip
per frame. `content-visibility` scales to *hundreds*; the editor branch needs *hundreds of thousands*.

> **Two mechanisms, but not a Frankenstein** — they answer different questions.
> `content-visibility` answers *"should I do rendering work for an element that exists?"*; virtualisation
> answers *"should this element exist at all?"* Both is coherent. What must stay crisp is the boundary,
> and the boundary is memory: if the element count is bounded and modest, keep it real.

**`content-visibility` is worth having and is NOT part of this item.** It is a style property, ambient on
any element, and it would help every long page in the engine rather than only lists. Filed as its own item
(6.1.3b) because implementing it means teaching Taffy to substitute an intrinsic size for a skipped
subtree, which has nothing in common with the work below.

#### Prior art, and the verdict

| Source | What it contributes |
|---|---|
| **VS Code's `ListView`** | The **renderer-template split**, and it is the single best idea available: `renderTemplate(container)` builds the row's structure and wires its listeners **once**; `renderElement(item, index, template)` binds data into an existing template; `disposeTemplate` tears it down. This is what makes recycling safe rather than a listener leak waiting to happen. |
| **UIKit's `UITableView`** | Originated cell reuse (`dequeueReusableCell`) and, more usefully, `prepareForReuse` — the explicit acknowledgement that a recycled cell carries state it must shed. |
| **Anchor-based virtual lists** ([judi.systems](https://judi.systems/shirei/blog/virtual-list/)) | The best treatment of the genuinely hard case — variable heights that are not known until measured. An anchor `(index, offset)` plus an average-height estimate, walked forward or backward, with distinct handling for *smooth* scrolling (keep the anchor, accumulate) versus *random-access* scrolling (re-derive the anchor from the average). |
| **TanStack Virtual / react-window** | Prefix-sum array + binary search for the *measured* case; `resetAfterIndex` for invalidation. The maths, once heights are actually known. |
| **Flutter slivers, Qt model/view** | Confirmation that model/view separation with a per-row delegate is the universal shape. No new ideas beyond VS Code's, and a heavier vocabulary. |
| **Angular CDK** | `itemSize` *strategies* as a pluggable axis — fixed, then autosize — rather than one algorithm trying to cover both. Worth copying as a shape. |

#### The design

```java
ListView<String> list = new ListView<>(model);            // model is an ObservableList<T>
list.setRenderer(new ListRenderer<String>() {
    public UIElement createTemplate() { … }               // ONCE per recycled element — wire listeners here
    public void bind(String item, int index, UIElement template) { … }   // per row, per scroll — data only
});
```

- **The model is `ObservableList<T>`**, which already exists in `core/property` with a `Change<T>` signal.
  No new model type, and its change events are exactly the invalidation the view needs.
- **`ListRenderer` is VS Code's split**, renamed. The whole contract lives in which method you put things
  in: structure and listeners in `createTemplate`, data in `bind`. That is what makes "how does a consumer
  bind data without leaking the previous row's listeners" a non-question rather than a rule to remember.
- **Rows are internal children** of the view, like every other widget's structure, so public traversal and
  `UIDescriptionCodec` do not see the realised window and mistake it for the content.

#### Height strategies, fixed first

`FixedHeightStrategy` covers the overwhelming majority and makes offset→index a division. Variable height
is a second pass, and is deferred rather than skipped because **wrapped code lines need it** and 6.1.7
therefore does. The anchor-based approach above is the design to port when that lands; the prefix-sum
version only applies once every height is genuinely known, which for wrapped text it never is.

Strategies are pluggable (Angular CDK's shape) so the second one is an addition rather than a rewrite of
the first.

#### What virtualisation genuinely breaks, and the answer for each

This is the honest cost, and every item is a place a consumer will be surprised:

| Breaks | Answer |
|---|---|
| **Focus on a scrolled-away row** — the element is recycled out from under it | Track the focused **index**, not the element; restore focus when that index is realised again. VS Code does exactly this, and without it a keyboard user loses their place on every scroll. |
| **`querySelector` cannot find unrealised rows** | Accept and document. It is inherent: the element does not exist. A consumer wanting "the row for item X" asks the view by index, not the tree. |
| **`scrollIntoView` on a row that does not exist** | An index-based `scrollToIndex(int)` on the view. The existing element-based method stays for realised rows. |
| **`getScrollHeight` assumes real children** | Comes from the model — `count × itemSize`, or the strategy's estimate. This is the one place the existing scroll machinery must be taught something new. |
| **Serialization** | A virtualised view serializes its *model state*, never its realised window. Writing out the visible fifteen rows of a ten-thousand-row list is worse than writing nothing. |
| **Smooth scroll and `clampScroll`** | Already route through `getMaxScroll*`, so they follow from the row above for free. Worth a test each rather than an assumption. |

#### Deliverables

- `ui/elements/ListView<T>`, `ListRenderer<T>`, `ItemSizeStrategy` + `FixedHeightStrategy`.
- Recycling pool, overscan, and the model-change → invalidation path.
- Focus-by-index restoration.
- Tests: only the visible window is realised; scrolling recycles rather than allocates; a model change
  updates without a full rebuild; `scrollHeight` tracks the model not the children; focus survives a row
  being recycled and returns when it is realised again; an empty model; a model shorter than the viewport.
- Harness: a `list` gallery page with 100k rows, a live realised-element count, and a focus-survives-scroll
  row — the count is the only way to *see* that virtualisation is happening at all.

#### Open questions

| Question | Why it matters |
|---|---|
| Does `ListView` own its scrolling, or sit inside a `ScrollerView`? | Scrolling is an ambient element capability here, so owning it would be the divergence. But the view must know its own scroll offset to decide the window, which means reading it from an ancestor every frame. |
| Recycle by pool, or realise/destroy? | A pool is faster and is what every reference does; destroy-and-recreate is far simpler and may be fast enough given how cheap a `UIElement` is. Worth measuring before assuming. |
| Overscan: fixed count or measured from scroll velocity? | Fixed is predictable; velocity-based hides more latency. Start fixed, and only revisit with a real measurement. |


### 6.1.3b CSS `content-visibility` · `TODO`

The web's own answer to "stop doing rendering work for what nobody can see": layout, style and paint
containment plus skipping the subtree entirely when it is off-screen, with `contain-intrinsic-size`
standing in for its size so the scrollbar does not jump.

Split out of 6.1.3 because it shares nothing with it in implementation. Virtualisation decides whether an
element **exists**; this decides whether an existing element is **worked on**. It is a style property,
ambient on any element, and it helps every long page in the engine rather than only lists — a tall
`Dialog`, a deep settings panel, a `SplitView` pane scrolled out of view.

The work is teaching Taffy to substitute `contain-intrinsic-size` for a subtree it is told to skip, and
teaching paint to bail on it. Cheap to describe, and genuinely useful the moment any page gets long.

Not scheduled against anything — it blocks nothing, and nothing blocks it.

### 6.1.4 Tree · `DONE` (2026-07-31)

Expand/collapse, selection, ARIA keyboard navigation, over a virtualised list. Feeds the resource/file
browser, the outline view, and the graph view's node-library palette.

#### A tree is a flattened list, and that is not a shortcut

[VS Code's own stack](https://github.com/microsoft/vscode/wiki/Lists-And-Trees) is a composition over its
list: `IndexTree` maps tree splices onto list splices, `ObjectTree` wraps that in a friendlier
`setChildren`, and `AsyncDataTree` adds lazily-discovered models. Four layers, all resting on the virtual
list at the bottom.

We want the outcome rather than the layering. `TreeView<T>` flattens the currently-visible nodes into a
linear model and hands it to {@code ListView}, which already provides virtualisation, recycling,
selection, focus-by-index and the scroll machinery. Expanding a node re-flattens; that is the entire
mechanism.

**The consequence worth stating**: everything 6.1.3 fought for is inherited rather than re-fought. A tree
over a hundred thousand nodes realises a dozen rows, focus survives recycling, and the scrollbar reflects
the flattened count — none of which is Tree code.

#### Pull-based data source, which is `AsyncDataTree`'s idea minus the async

```java
TreeDataSource<Path> source = new TreeDataSource<>() {
    public List<Path> roots()                { … }
    public List<Path> children(Path parent)  { … }
    public boolean hasChildren(Path item)    { … }
};
```

A file explorer does not know a folder's children until it is opened, so children are **asked for on
expand** rather than supplied up front. A fully-known tree is expressible this way too, at no cost — so
pull-based is strictly more general and no harder. `hasChildren` is separate from `children().isEmpty()`
precisely because a folder can be known to be expandable without being read.

**Not async.** VS Code needs promises because a file system is remote-ish and a UI thread cannot block;
here the realistic sources are in-memory. When something genuinely needs it, the seam is this interface —
which is why it is an interface rather than a concrete node type.

#### The keyboard contract, from the APG, exactly

[The tree pattern](https://www.w3.org/WAI/ARIA/apg/patterns/treeview/) is fiddlier than the listbox and
the asymmetry is the part that gets implemented wrong:

| Key | On a collapsed node | On an expanded node | On a leaf |
|---|---|---|---|
| **Right** | opens it, **focus does not move** | moves focus to the first child | nothing |
| **Left** | moves focus to the parent | closes it | moves focus to the parent |

Up/Down move through *visible* nodes without opening or closing anything — which is exactly
{@code ListView}'s existing behaviour over the flattened model, so it costs nothing. Home/End likewise.
Enter activates. `*` expands every sibling at the current level (the APG marks it optional; cheap here
because it is one re-flatten).

#### Deliverables

- `ui/elements/tree/`: `TreeDataSource<T>`, `TreeRow<T>`, `TreeRenderer<T>`, `TreeView<T>`.
- Flattening with an expansion set; re-flatten on expand/collapse and on a source change.
- Depth indentation applied by the view, plus `__expanded__`/`__collapsed__`/`__leaf__` state classes so a
  theme draws the twisty without the renderer knowing the rules.
- Tests: flattening respects expansion; the APG table above, row by row; expanding a huge subtree does not
  realise it; selection and focus survive a collapse that removes the focused row.
- Harness: a `tree` gallery page over a deep synthetic hierarchy, with the realised counter visible.

#### Built

`ui/elements/tree/`: `TreeDataSource<T>`, `TreeRow<T>`, `TreeRenderer<T>`, `TreeView<T>`. 16 tests, a
`tree` gallery page over a synthetic 8,000-node hierarchy.

**Building it on the list paid exactly as hoped.** `TreeView` is one class: flatten, expand/collapse,
Left/Right, indentation. Virtualisation, recycling, selection, focus-by-index, the scroll machinery and
the whole Up/Down/Home/End/Space/Enter key set are *inherited*, and the APG's "Up/Down move through
visible nodes without opening anything" is satisfied by the flattening rather than by any code — the
model **is** the visible set.

Two seams opened on `ListView` for it, both small and both justified: `handleNavigationKey` became
protected so a subclass can take a key first, and `moveFocusTo` likewise so Left/Right route through the
same path arrows do — otherwise those two keys would skip the scroll and the selection-follows-focus rule
every other key obeys.

**Re-flattening is wholesale, not incremental**, and that is a deliberate trade. An incremental splice
would be less work on a large tree, but every operation would need its own correct splice computation —
which is precisely the "sub-optimal API" VS Code's own wiki says `IndexTree` suffers from. One code path
cannot get out of step, and the list on the other side is virtualised, so the cost is a list of records
rather than of elements.

Mutation-checked on the part most likely to be "simplified" later: making Right always move focus and
Left always collapse — the naive reading — turns exactly `rightOnACollapsedNodeOpensItWithoutMovingFocus`
and `leftOnAChildMovesToItsParent` red.

#### Deliberately not built

- **Async loading.** The seam exists; nothing needs it.
- **Compressed nodes** (VS Code collapses single-child chains like `a/b/c` into one row). A genuine
  nicety, and pure addition later.
- **Typeahead.** The APG asks for it, and it belongs on `ListView` rather than here — it is as useful in a
  flat list — so it is filed as a `ListView` follow-up rather than a Tree feature.
- **Drag-reorder.** Wants the drag controller and a drop-position model; separable.


### 6.1.5 Table · `DONE` (2026-07-31)

Columns, a header, resizable widths, sortable headers, row selection — over the virtualised list. The
"details" half of a file browser, and the shape most property tables want.

#### Row-focused, not cell-focused — and that is the decision

The [APG separates two roles](https://www.w3.org/WAI/ARIA/apg/patterns/grid/), and they are not
interchangeable:

| Role | Focus | Keyboard |
|---|---|---|
| **`grid`** | a **cell** | arrows move cell-by-cell; Home/End go to the first/last cell *in the row*; Ctrl+Home/End to the grid's corners; **Shift+Space** selects the row containing the focused cell |
| **`table`** | nothing — static content | none |

Our two motivating cases pull opposite ways. A file browser's details view selects **files**: arrowing
should move between rows, and no user has ever wanted to arrow into the "date modified" column. A
property inspector genuinely edits **cells**.

**Build the row-focused one.** It is what a details view, a search-results list and a diff summary all
are — a list that happens to have columns — and it inherits `ListView`'s entire keyboard and selection
contract unchanged, exactly as the Tree did. A property inspector is closer to a *form* than a grid, and
6.1.8's Configurator is the right home for it.

**Cell-level focus is the ARIA `grid` role and a later addition, not a different design.** The seam is a
`TableColumn` already knowing how to render a cell; making cells focusable is additive. Building it
speculatively would mean a roving tabindex across two axes for a case nothing has asked for.

#### A table is a sorted list with columns

Same composition the Tree used, which is the point of having built the list first:

```java
TableView<Person> table = new TableView<>(people);      // an ObservableList
table.addColumn(TableColumn.of("Name", Person::name).width(120).sortable());
table.addColumn(TableColumn.of("Size", Person::size).width(60).sortable(comparingLong(...)));
```

- **`TableView<T>` owns a derived model.** Sorting produces a *view order* rather than mutating the
  caller's list — a table must not reorder somebody else's data because a header was clicked. Same shape
  as `TreeView`'s flattened model: the caller supplies a source, the view supplies what the list sees.
- **Columns own cell rendering.** A default text cell from a value function covers most of it; a column
  may supply its own renderer for an icon, a progress bar or a colour swatch.
- **The header is a sibling of the list, not a row in it.** A header that scrolled away with the content
  would be wrong, and making row 0 special would poison every index in the selection model.

#### Column resize uses the drag controller, not CSS `resize`

**Correcting an earlier note in this file**, which said to reuse the `resize` element capability. That
was wrong on inspection: CSS `resize` changes *one* element's own size by dragging its edges. A column
divider **redistributes width between two adjacent columns** — a different operation, and the one
`SplitView`'s divider already performs. So it uses `UIDragController` the way `SplitView` does, and the
`__divider__` idiom comes with it.

#### Deliverables

- `ui/elements/table/`: `TableColumn<T>`, `TableView<T>`, `TableCellRenderer<T>`.
- Header with sort indicators; click to sort, click again to reverse, and a third state worth deciding
  (see open questions).
- Draggable dividers, with a minimum width so a column cannot be dragged to nothing.
- Tests: sorting reorders the view and **never the source**; a column's width change re-lays the rows;
  selection survives a re-sort by *item* rather than by index; the header does not scroll; a model change
  re-binds visible cells (the bug that cost 6.1.4 — this time asserted on the display first).
- Harness: a `table` gallery page over a synthetic file listing, sortable and resizable.

#### Open questions

| Question | Why it matters |
|---|---|
| Does clicking a sorted header a third time restore the *unsorted* order? | Explorer and Finder say no (two states); VS Code and most data grids say yes (three). Three needs the view to remember source order, which it already does. |
| Does selection survive a re-sort? | It must, and that means selection has to key on the **item**, not the flattened index — a divergence from `ListView`, whose selection is index-based. Possibly the one place this cannot simply inherit. |
| Flexible column widths, or fixed only? | Fixed is predictable and enough for a details view. A `weight` axis is additive, but only if decided before the resize maths is written. |


### 6.1.6 Multi-line text buffer and editor · `DONE` (2026-07-31) · **the large one**

> **Shipped.** `com.crystalgui.text` — `TextSummary`, `TextPoint`, `Rope`, `Change`, `ChangeSet`,
> `TextBuffer` — plus `ui/elements/editor/TextEditor`, `texteditor` rules in `default.css`, an `editor`
> gallery page, and 47 tests across `headlessTest` (the document model) and `test` (the widget).
>
> **The design below held, with one correction that mattered.** It claimed Zed's `SumTree` could be taken
> without its CRDT and anchors would come along anyway. They do not: Zed's `Anchor` is a CRDT artifact —
> a Lamport timestamp naming the insertion that produced the surrounding text — so dropping the CRDT drops
> the mechanism. CodeMirror's `mapPos`/`compose`/`invert` supplied the replacement, which is what the split
> between the two projects in this section is actually for.
>
> Five things the plan did not anticipate:
>
> - **Composition coarsens position mapping, and cannot not.** Mapping through `compose(a, b)` does *not*
>   always equal mapping through `a` then `b`: when two replaced regions end up adjacent with no surviving
>   original text between them, they merge — correctly, in original coordinates — and a position on the
>   former boundary becomes interior to a deletion. The composed edit still produces a byte-identical
>   document, which is the property undo rests on. Pinned as intended behaviour rather than deleted, along
>   with what *is* guaranteed: a coalesced run of keystrokes maps the caret exactly.
> - **`line-height` is a unitless multiplier of font size**, as in CSS — not a pixel height. Reading it as
>   pixels compiles, runs, and draws every row on top of the last.
> - **`markAsInternal()` is for a widget's parts, never the widget.** Calling it on `TextEditor` itself hid
>   the whole thing from traversal and focus. `ListView` made the same mistake once already.
> - **`updateWithoutPainting()` does no input handling**, so it never sets `firstFrameOver` — and
>   `consumeKeyboardEvent` early-returns until that is set. A test that only advances frames drops every
>   key before dispatch, and the widget looks completely dead while being perfectly correct.
> - **Modifiers come from the platform, not the event.** `UIInputHandler` reads
>   `CgPlatform.input().getCurrentModifiers()`, so synthesising a Shift key-down does nothing; a test sets
>   the mask by being the platform. `MouseEvent` carries no mask at all, which is why shift-click asks the
>   platform directly.
>
> **Soft wrap is deliberately absent rather than stubbed.** Wrapping makes a line occupy a variable number
> of visual rows, so the window can no longer be derived by dividing scroll offset by row height — it needs
> the variable-height virtualisation 6.1.3 deferred. There is no `setSoftWrap`, because a toggle that
> silently did nothing is worse than an absent one; this engine already paid for that with highlight
> properties that resolved and never painted. **This is the one item of 6.1.6's stated scope not
> delivered**, and 6.1.7's wrapped code lines are what will force it.
>
> **Known duplication, deliberately left.** `TextEditor` windows its own lines instead of extending
> `ListView`, which windows over an `ObservableList` — mirroring rope-derived rows into a list would be a
> second copy of the document that can drift. The seam worth extracting is "window over N fixed-height
> rows"; it becomes worth extracting when 6.1.7's gutter needs a third copy.

<details><summary>Original design</summary>


> **Design settled 2026-07-31, after research.** Storage is a **rope over a summary B+ tree** (Zed's
> `SumTree`). Edits, anchors and undo are **change sets with position mapping** (CodeMirror 6). Those are
> two projects' answers to two *different* problems and the split is deliberate — the reasoning, including
> the part where the first draft of this decision was wrong, is below.

#### Storage: a rope over a summary tree, not a piece table

Every node caches a **summary** of its subtree, so a seek by any summarised dimension — byte offset,
UTF-16 offset, **line/column** — is O(log n) with no side structure. Line/column is the one that decides
it: 6.1.3 already gives us a virtualised list, the editor renders through it, and its hottest query is
therefore *"lines 4000-4050 and their extents"*.

**Why not the piece table, given the standing "closest to the web" directive.** Because VS Code's own
write-up says why they picked it, and the reasons do not transfer:

- They chose it on **open-time memory**. Their line array cost ~600 MB for a 35 MB file — 20x — because
  every line was an object. We are not competing against that baseline.
- Line lookup in a piece table is **O(n)** — it walks characters from the start. They fixed it by bolting
  on a **red-black tree caching line-break counts per node**. That is precisely the second structure a
  summary tree makes unnecessary, and a second structure is a second thing to keep correct across every
  edit.
- It **degrades with edit count**: pieces are never coalesced, so a long session becomes tens of thousands
  of nodes. They judged that acceptable because `getLineContent` was under 1% of their frame. Ours is not
  the same frame — we re-shape through `CgShapedParagraph`.

So this is a case where the directive loses, and it loses on *evidence* rather than taste: Monaco's
structure is a good answer to a question about V8 string limits and file-open memory, and we are asking a
different question.

#### Edits, anchors and undo: change sets, not CRDT anchors

**The first draft of this decision said "take Zed's SumTree, not its CRDT, and we get anchors anyway."
That was wrong, and it is recorded because the error is instructive.** Zed's `Anchor` is a *CRDT artifact*:
a Lamport timestamp identifying the insertion that produced the surrounding text, plus an offset into it
and a bias. Anchors survive edits there **because** every insertion has a globally unique identity. Drop
the CRDT and that mechanism goes with it.

The non-CRDT answer, and the better fit here, is CodeMirror 6's:

| Piece | Role |
|---|---|
| `ChangeSet` | A described edit: positions plus inserted text. Data, not a closure. |
| `mapPos(pos, assoc, mapMode)` | Maps any position *through* a change. `assoc` is the insertion bias (before/after); `mapMode` decides what a deletion spanning the position means — a valid position, or nothing. |
| `compose` | Two sequential changes become one. |
| `invert` | The opposite change, given the document it applied to. |
| `RangeSet.map(changes)` | A whole set of decorated ranges mapped through one change. |

An anchor stops being a stored identity and becomes *a position plus a rule for mapping it*. Less powerful
than Zed's — it cannot reconcile concurrent edits — and exactly as powerful as we need.

#### Coordinates are UTF-16, and that is not arbitrary

Zed measures in **UTF-8 bytes** because Rust strings are UTF-8. CodeMirror measures in **UTF-16 code
units** because JS strings are. **Java strings are UTF-16**, so the CodeMirror coordinate model maps 1:1
onto `String`/`CharSequence` while Zed's would need a conversion at every boundary — including every call
into `CgShapedParagraph`. The summary should still carry a UTF-8 dimension for anything that serialises,
which is free once summaries compose.

#### What this hands the already-shipped work

`RangeSet.map` is the same operation `ui/text/TextRange` + `HighlightRegistry` (6.1.1) need in order to
survive an edit — a highlight *is* a decorated range. So 6.1.1's ranges become mappable rather than needing
recomputation on every keystroke, which is what a find-as-you-type highlight actually requires.

#### The genuinely unsolved part

Not the rope. `UIText` today retains one `CgShapedParagraph` and rebuilds it only when the text or the
resolved font family changes. An editor invalidates **one line out of thousands** per keystroke, so that
model inverts: shaping has to become per-line and cached against a line identity that survives edits —
another consumer of anchors, and the reason it is called out here rather than discovered in 6.1.7.

Everything else stands: caret and selection across lines, word-wise and page navigation, soft-wrap toggle.

Explicitly **not** an extension of `TextField`. That widget's caret and selection logic is single-line by
construction; sharing it would mean generalising every method on it while it stays in use. A common
`EditableText` seam underneath both is worth considering once the second one exists — not before.

> Honest scoping note: this is the largest widget in any UI toolkit. It is placed sixth so that it consumes
> three finished foundations rather than growing its own private versions of them, which is the failure mode
> to watch for.

</details>

### 6.1.7 Code editor · `DONE` (2026-07-31), except the GLSL grammar

6.1.6 plus a gutter, line numbers, current-line highlight, syntax highlighting (6.1.1), bracket matching,
indent handling, and find/replace. The widget must not know what any particular language is.

> **Shipped.** `VariableHeightStrategy`; `Selection`/`SelectionModel` and multi-cursor throughout
> `TextEditor`; gutter, line numbers and current-line highlight; the `SyntaxTokenizer` SPI with
> `KeywordTokenizer` (Java + GLSL); bracket matching; indent, outdent and auto-indent; find/replace; and
> the `syntax-treesitter` module running the real Java grammar against the local fork. 195 tests across
> the item, all green.
>
> **What the plan got right, and what it did not anticipate:**
>
> - **A `HighlightRegistry` belongs to a `UIText`, not to a document.** The plan assumed capture names
>   could be published once per document; they cannot. Ranges are offsets into *one element's* string, so
>   document-relative tokens are clipped to each line and rebased onto it. That is also what makes a block
>   comment work: one token, distributed as one clipped range per line it crosses.
> - **A pooled line must have its highlights cleared on recycle.** A line reused for another row keeps
>   ranges that are offsets into a string that no longer exists — worse than no highlighting.
> - **The lexer must start scanning at a line boundary, and look back for an open block comment.** A
>   viewport-bounded query starting wherever the first visible row happens to begin would read the inside
>   of a string literal as code. Scrolling into the middle of a long comment is the case that proves it.
> - **`setSelection` had an inlined copy of the post-change work**, so bracket matching silently never ran
>   from it. Collapsed into one path. Two code paths doing "the same thing" is how one of them rots.
> - **The tree-sitter query resource path needs its `assets/` prefix**, and the failure mode was a *skipped*
>   test suite rather than a red one — the `Assume` that makes a missing native honest also hides a plain
>   bug behind it. Worth knowing that the two look identical from the outside.
>
> **Not delivered: the GLSL grammar (step 8).** `tree-sitter-glsl` is not in the fork, and adding it means
> adding a subproject there and cross-compiling its native with Zig — a change to a different repository
> and a toolchain this session cannot verify. `KeywordTokenizer.glsl()` covers GLSL in the meantime, which
> is the fallback path working as designed rather than a stub.
>
> **Also still absent: soft wrap.** `VariableHeightStrategy` is the piece it was blocked on and it is now
> here, but the editor does not yet wrap — that is 6.1.6 scope, unblocked rather than done.

#### The two gates, decided

**Variable-height rows — do this first.** 6.1.3 shipped fixed-height virtualisation, 6.1.6 dropped soft
wrap because of it, and 6.1.7 cannot avoid it: a wrapped line occupies several visual rows, and the gutter
has to draw one number spanning all of them. It is a change to `ItemSizeStrategy` plus the editor's window
arithmetic, and everything else here sits on top of it. **This is the last time it can be deferred.**

**Multi-cursor — design it in now.** `TextEditor` holds `caret`/`anchor` as two `int` fields and every
movement method reads and writes them directly; converting that to a list of ranges later means touching
all of them. The layer underneath is already ready — several non-overlapping changes in one `ChangeSet` is
exactly what a multi-cursor edit is, and `ChangeSet.of` already refuses overlaps, which is the invariant
multi-cursor has to maintain anyway.

#### Syntax highlighting: tree-sitter, behind an SPI

**Decision: use tree-sitter, but the editor must never reference it.**

The blocker was Java. The official `jtreesitter` needs **JDK 23+** and the Foreign Function & Memory API,
which is impossible here. That is resolved: there is a **local fork of `tree-sitter-ng` v0.26.6 at
`X:/projects/tree-sitter`**, JNI-based, `sourceCompatibility`/`targetCompatibility` **1.8**, with 25
grammars, `TSQuery` for `highlights.scm`, and prebuilt natives committed for x86_64 Windows/Linux/macOS
and aarch64 Linux/macOS.

Why it earns a native dependency, rather than a hand-written lexer:

- **Incremental reparse.** `TSInputEdit` reuses unchanged subtrees, and a `ChangeSet` is already exactly
  the edit description it wants. A lexer would re-scan from the last known-good line, which is fine for
  highlighting and useless for everything below.
- **Error recovery.** Half-typed code still yields a usable tree. A lexer degrades here in the case that
  matters most — while typing.
- **It is not only highlighting.** Bracket matching, folding, indent rules and structural selection all
  want a tree. And the grand goal is a shader graph that *generates* GLSL: a real CST is worth having for
  validating node code and extracting uniforms, well outside the editor.
- **`highlights.scm` capture names map straight onto 6.1.1.** A capture of `@keyword` becomes
  `::highlight(keyword)` and the theme styles it in CSS. The Custom Highlight API was built for ranges
  supplied from outside; this is the consumer it was waiting for.

**Java grammar first, GLSL second.** The fork bundles `tree-sitter-java` with its natives and
`queries/highlights.scm` already built — so the pipeline can be proven end to end with no new native work.
`tree-sitter-glsl` exists upstream (`tree-sitter-grammars/tree-sitter-glsl`, an extension of the C
grammar) but is **not** in the fork, so it needs adding and cross-compiling; that is a second step, not a
prerequisite.

#### How Zed does it, and what to take

Read from `crates/language/src/syntax_map.rs`, since Zed is the closest analogue we have — GPU-rendered
editor, rope buffer, tree-sitter highlighting. Four things there change this plan:

- **Edits are handled in two phases, and only the first is synchronous.** `interpolate()` applies
  tree-sitter `InputEdit`s to the existing trees immediately — updating node *coordinates* only, no
  parsing — so highlights stay attached to the right text the instant a key lands. The real
  `reparse()` happens afterwards, off the UI thread. Applying only one of the two is the trap:
  reparse-only makes typing stutter, interpolate-only leaves the tree structurally stale.
  <br>**For v1 take interpolate synchronously and reparse synchronously too** — our documents are shader
  snippets and single files, not Zed's repositories — but keep the two as separate steps so the reparse
  can move to a worker later without touching the edit path.
- **Queries are bounded to a byte range, not run over the document.** Zed caps a single query at
  `MAX_BYTES_TO_QUERY = 16 * 1024` and only queries layers overlapping the requested range. This fits our
  virtualisation exactly and for free: the editor already knows its realised row window, so it can convert
  that to a byte range and query only what is on screen. **Highlighting cost becomes proportional to the
  viewport rather than the file**, which is the same argument the virtualised list is built on.
- **Query cursors are pooled** (`QueryCursorHandle`). They are not cheap to allocate and there is one per
  query per frame.
- **Dropping a deep tree is slow enough that Zed does it on a background thread.** Worth knowing before
  it shows up as a frame spike on closing a large file, which is the kind of thing that gets blamed on
  rendering.

Also noted and deliberately *not* taken for v1: **injection layers** (`SyntaxLayerEntry` at multiple
depths, `Single` vs `Combined`), which is how Zed highlights one language embedded in another. Out of
scope now, but the shader graph will want exactly this — GLSL embedded in a node definition — so the
tokenizer SPI should return ranges without assuming a single grammar produced them.

#### The SPI, and why it is not optional

`core/` must stay loadable on a dedicated server with no GL and no natives. So:

| Layer | Lives in | Knows about |
|---|---|---|
| `SyntaxTokenizer` — text in, captured ranges out | `core/` | nothing but text and range names |
| Built-in lexer | `core/` | one language's keywords, no dependency |
| tree-sitter backend | its own module | `org.treesitter`, natives, `highlights.scm` |

**The built-in lexer is not throwaway work, it is the degradation path.** A native load *will* fail
somewhere — the fork has no aarch64-Windows build, and a Minecraft client is the least predictable
deployment target there is. An editor that falls back to lexer highlighting is far better than one that
throws, and the fallback also gives the tree-sitter backend a differential oracle: for valid code the two
must agree on token boundaries.

**Byte offsets again.** tree-sitter speaks UTF-8 byte offsets; this engine speaks UTF-16 code units. The
conversion already exists in `TextEditor.utf16IndexByUtf8Byte` for caret positioning, and wants extracting
rather than writing a second time in the other direction. Getting it wrong is invisible in ASCII.

#### Order of work

1. Variable-height rows in `ItemSizeStrategy` + the editor window, unblocking soft wrap in 6.1.6 too.
2. Multi-cursor selection model in `TextEditor`.
3. Gutter, line numbers, current-line highlight — all of which need (1).
4. `SyntaxTokenizer` SPI + the built-in lexer, wired to `HighlightRegistry`.
5. tree-sitter module, Java grammar, `highlights.scm` → `::highlight()` captures. Edit path is
   interpolate-then-reparse as two separate steps; queries bounded to the realised row window's byte
   range; query cursors pooled.
6. Bracket matching and indent handling from the tree.
7. Find/replace.
8. GLSL grammar added to the fork. **Outstanding** — needs a subproject in the fork and a Zig
   cross-compile of its native, which is a change to a different repository.

#### Risk

The native shipping story is the real one. Loader jars already bundle JNI for
`freetype-msdfgen-harfbuzz`, so there is precedent and infrastructure — but that is per-loader build
configuration across four targets, and the mc modules are not in this build today. Keeping tree-sitter in
its own module means `core/` and the harness stay unaffected either way.

### 6.1.7b Code editor — the rest of the foundations · `DONE` (2026-07-31), except the remaining view items

6.1.7 was marked done against this plan's checklist rather than against "is this an editor anyone would
use". Audited against that question instead, and the gaps were real — one of them undercut a feature
already built.

**Keybindings follow VS Code's defaults** throughout. These are conventions, not derivable answers, and a
wrong one is felt immediately and cannot be named — the same reasoning 6.2.4 used for its shortcut set.

> ### None of the behaviour here was invented. It was ported.
>
> **`com.crystalgui.text.cursor` is a port of VS Code's `vs/editor/common/cursor/`** — `CursorColumns`,
> `MoveOperations`, `TypeOperations`, `LineOperations`, `MouseSelection`, all MIT, all attributed in their
> class javadoc against the file they came from. `WordClassifier` is `wordCharacterClassifier.ts`, down to
> `USUAL_WORD_SEPARATORS` and the decision that `_` is a word character. The `Rope`/`TextSummary` pair is
> Zed's `SumTree` shape (read, not copied — Zed is GPL-3.0 and copying it would impose GPL on this repo),
> and `ChangeSet` is CodeMirror 6's, which is MIT and which uses UTF-16 offsets exactly as Java does.
>
> **This is now the standing rule for the whole project.** Every one of the rules below was learned by
> someone shipping it to millions of users, and none of them is derivable — that auto-close fires on an
> allowlist rather than a denylist, that a plain arrow collapses a selection to its edge regardless of
> which way the gesture went, that a mixed comment block comments out rather than half-toggling, that a
> backwards word-drag unions with the anchor word. Each is one line, each is invisible when wrong, and each
> would have been got wrong from first principles.

#### A. The text model is wrong for real files · `DONE`

- **CRLF** — `LineEnding` detects the dominant ending on load, normalises to `\n` internally, restores it
  in `TextBuffer.textWithOriginalLineEndings()`. The model stays single-ending, which is what keeps every
  offset in the engine meaning one thing.
- **Tabs** — `CursorColumns` expands to the next tab **stop**, not a fixed advance, and carries both maps
  (`columnToDisplay`, `displayToColumn`) so a click inside a tab lands on one side of it rather than in the
  middle of nothing.
- **Read-only** — `setReadOnly`.

#### B. Multi-cursor is unreachable · `DONE`

`Ctrl+D` next occurrence, `Ctrl+Shift+L` every occurrence, `Ctrl+Alt+Up/Down` a caret on the adjacent line.

> **Each caret keeps its own goal column** — `MoveOperations.vertical` takes and returns an `int[]`, and
> drops it only when a merge makes the indices stop lining up. Shared goals look correct until the second
> vertical press, which is exactly why this needed a headless test to see.

#### C. Line operations · `DONE`

`Alt+Up/Down` move, `Shift+Alt+Up/Down` copy, `Ctrl+Shift+K` delete, `Ctrl+Enter`/`Ctrl+Shift+Enter` insert
below/above, `Ctrl+L` select line, join, indent/outdent, `Ctrl+/`.

> **A line move is one replacement of the span covering both rows, not two edits.** Two would be two undo
> steps, and the second would be described against a document the first had already changed.

#### D. Typing aids · `DONE`

Auto-close with type-over, surround, smart backspace, dedent on close. `AUTO_CLOSE_BEFORE` is VS Code's
allowlist verbatim.

#### E. Comments need a language descriptor · `DONE`

`Language` — comment tokens, bracket pairs, self-closing quotes — separate from the `SyntaxTokenizer` that
colours it. Two different questions about the same language; conflating them would make the tree-sitter
backend responsible for things a grammar does not describe.

#### F. Search · `DONE`, except regex

`Ctrl+F`, `F3`/`Shift+F3`, `Ctrl+H`, `Ctrl+F3` (word under caret), and the count is whole-document because
"3 of 47" cannot be computed from what is on screen. **Regex and whole-word are still not implemented** —
`find` is plain substring, and search-within-selection has no UI.

#### G. View · **soft wrap `DONE`** (2026-07-31); the rest deferred

**Soft wrap is delivered.** `com.crystalgui.text.wrap` — a port of VS Code's view model, kept headless:

| Class | Ported from |
|---|---|
| `LineProjection` | `modelLineProjectionData.ts` — the affinity binary search, operator-for-operator |
| `MonospaceLineBreaks` | `monospaceLineBreaksComputer.ts` — `canBreak`, the scan loop |
| `WrapIndent` | `computeWrappedTextIndentLength` |
| `ProjectedLines` | `ViewModelLinesFromProjectedModel` over `PrefixSumComputer` |
| `LineBreaksComputer` | `ILineBreaksComputer` |

> **The projection is unconditional — wrap off is the identity projection, not a second code path.**
> A wrapped/unwrapped branch through painting, hit testing, caret, gutter and scrolling is six places for
> the two to drift, and the unwrapped half is the one exercised constantly, so it would stay right while
> the other rotted. This was not theoretical: an `if (!softWrap) return` shortcut in the per-edit
> reprojection left the index stale with wrap **off**, and the window stopped growing when a line was
> added. An existing test caught it. The shortcut looks free precisely because the unwrapped case appears
> to need nothing.

> **`PositionAffinity` is the load-bearing import.** An offset exactly at a wrap is genuinely two places —
> the end of one visual line and the start of the next — and which is meant depends on how the caret got
> there. Without it the caret at a wrap point flickers between them, which is the most visible way a soft
> wrap implementation is wrong. The caret resolves `LEFT`; Home resolves `RIGHT`.

> **Breaking is measured in pixels, not columns — and the first version was not.** It divided the
> viewport by the advance of a **space** and handed the quotient to the column computer, which is exact
> in a monospaced font and badly wrong in the proportional one the theme uses: a space is far narrower
> than an average glyph, so the budget came out far too generous and wrapped lines *still* ran off the
> right edge and were clipped. It looked like wrapping was broken; it was measuring that was.
> `ShapedLineBreaks` measures against the same cached row widths the caret and selection bands are placed
> with, so a break decision and the paint that follows it cannot disagree. **Only a visual check found
> this** — every test passed — so `noWrappedLineIsWiderThanTheViewport` now measures painted text against
> the box it goes in, and the original bug reinstated verbatim is one of the mutants it catches.
>
> The two computers share `BreakOpportunities`: only the *has it run out of room* test differs, and a
> duplicated copy of the *where may it break* rules is the mistake `stroke.glsl` already records.

> **Tab stops after a wrap were solved by rebasing, not by more state.** A tab's stop depends on its
> position in the *row*, so measuring a continuation line's own text puts every tab after a wrap at the
> wrong stop. VS Code carries a parallel `breakOffsetsVisibleColumn` array for this; here the view line
> takes a slice of the row's already-expanded display string and its x positions come from the row's
> prefix widths minus the view line's origin. Correct by construction, and no second array to go stale.

**Not ported: VS Code's injected-text layer**, which threads inlay hints through every conversion and is
most of `modelLineProjectionData.ts`. There are no inlay hints here, and it would leave four coordinate
spaces where two suffice.

**Still deferred:** indent guides, visible whitespace, a column ruler, scroll-past-end. None started.

#### H. All of it through the keymap · `DONE` (2026-07-31)

`EditorCommands` — 24 commands under an `editor.` prefix, registered against **6.1.2's existing registry**
and bound on the editor element's own `keymap()`. Shaped after `UndoCommands`, which is the established
pattern: `register` / `bindDefaults` / `install`.

> **6.1.2 had already built the whole keymap** — `KeyStroke`, `KeyChord`, `KeyBinding`, `Keymap`,
> `KeymapResolver`, `KeymapSheet`, wired into `UIInputHandler`, with 702 lines of tests. I wrote a second
> one in `core/keymap/` before finding it, and threw it away. §H's own text says "against 6.1.2's
> registry" and I did not read my own plan. **The standing "do not reinvent" rule applies to this
> repository too, and checking takes one `find`.** The existing design is also better: scope is the DOM
> tree, so a binding is live whenever focus is inside its element, and a text field's `Mod+A` beats the
> window's without either knowing the other exists — `when`-clause specificity obtained structurally.

**Where the line falls, and why it is not arbitrary.** It is the resolver's own typing guard:

| | Handled by | Why |
|---|---|---|
| **Modified chords** — `Mod+D`, `Alt+Up`, `Mod+Shift+K` | commands | They are *named actions*, and a non-Shift modifier fires unambiguously inside a text field |
| **Bare keys** — arrows, Home, End, Backspace, Enter, Tab, typing | `TextEditor.handleKey` | They are what the widget *is*, as Space is for `Button`. The resolver skips bare bindings while typing precisely because a bare key belongs to the thing being typed into |

> **The native cases had to be deleted, not left as a fallback.** The resolver runs *after* dispatch and
> only on an unconsumed event, so a `return true` in `handleKey` eats the key and the binding can never
> fire — remapping would silently do nothing. For the same reason the surviving bare-key cases now yield
> when Alt is held (`Alt+Up` is always somebody's binding) and on `Ctrl+Enter`, while `Ctrl+Arrow` and
> `Ctrl+Home/End` stay native because they genuinely *are* movement.

> **A wrong key name throws at bind time.** `"Mod+Enter"` does not parse — `CgKeyCodes` has no
> `KEY_ENTER`, only `KEY_RETURN` and `KEY_NUMPADENTER`. Loud beats a binding that never fires.

**Installed by the editor itself**, unlike `UndoCommands` which is explicitly never automatic. Undo is an
*application* concern bound at the root, and a window that acquired it silently would surprise anything
enumerating commands; these are the widget's own keys on the widget's own element, and an editor that does
nothing on `Mod+D` is broken rather than neutral.

> **Undo moved too, and it was the last holdout.** `Ctrl+Z`/`Ctrl+Y` stayed in `handleKey` on the
> reasoning that a widget may pre-empt an application command — which is exactly the shape this section
> deleted everywhere else, and it made `edit.undo` **the one command in the engine that could be remapped
> and still not move**. The editor now calls `UndoCommands.register` and binds `Mod+Z`/`Mod+Shift+Z`/`Mod+Y`
> on its own element, reusing `edit.undo`/`edit.redo` rather than inventing `editor.undo` beside them —
> two commands for one concept would put two entries in every menu with nothing to say which the keystroke
> ran. Moving it also surfaced a latent gap: **the selection clamp was a hand-written line in the `Ctrl+Z`
> handler**, so undo from a menu or the palette never clamped at all. It now sits on the buffer's change
> signal, where every route in is covered.

Four tests pin what §H is actually *for* — every other key test would pass equally against the hard-coded
version: the actions are registered with labels, an action can be **remapped** (and the old chord stops
working), a menu can ask for the accelerator, and a command that cannot run says so rather than firing
empty. All four mutation-checked.

#### The extraction, and why it is not cosmetic

`TextEditor` reached **2556 lines — larger than the entire `com.crystalgui.text` package combined** —
because the ported algorithms went in as private methods. Pulling them into `text/cursor/` took it to 2237
and made them reachable without a `UIWindow`, fonts, a style engine or an input handler.

> **The new headless suite found a real bug within minutes of existing.** `LineOperations.delete` left a
> blank line behind when deleting the **last** row: it has no trailing newline to take, so it must swallow
> the one *before* it instead. The widget test never caught it because it only ever deleted a middle line,
> and as a window test the last-line case would have been another simulated key sequence — as a direct call
> it is one assertion. Porting the algorithms without porting the module boundaries kept the algorithms and
> threw away the testability that keeps them correct.

#### Explicitly out of scope

Debugger, LSP, IntelliSense, minimap, git gutter. Folding is deferred with them: done properly it wants the
syntax tree, and indentation-based folding is the kind of approximation that is wrong exactly where code is
interesting.

### 6.1.8 Configurator · `TODO`

Point it at an object, get an editing UI. Annotation-driven, concept borrowed from LDLib2 — which has the
full prior art checked in at `research_repos/LDLib2/src/main/java/com/lowdragmc/lowdraglib2/configurator/`:
a `ConfiguratorParser`, an `IConfiguratorAccessor` SPI with ~10 accessors, and ~20 concrete configurator
widgets (`NumberConfigurator`, `ColorConfigurator`, `SelectorConfigurator`, `ArrayConfiguratorGroup`, …).

Port and fine-tune rather than reinvent. Mostly composition over finished widgets, which is why it sits
after the Tree and Table rather than before them.

This is what a node's property panel is made of.

### 6.1.9 Command and undo system · `DONE` (2026-07-31)

> **Both halves have now landed, from opposite ends.** The *command* half shipped with 6.1.2 —
> `core/command/` holds `Command` (a named, id-addressable action), `CommandContext` and
> `CommandRegistry`, which is what a key binding, a menu item and the palette all point at. The *undo*
> half is `core/undo/`: `Edit`, `CompositeEdit`, `UndoStack`, with **20 tests in `headlessTest`**,
> because a dedicated server authors documents and the history mechanism has to run with no GL context.
>
> Built out of the 6.2 track rather than the 6.1 one, since 6.2.4 is blocked on it and 6.1.6/6.1.7 are
> heavy. The design below is what was implemented; four things it did not say:
>
> - **An `Edit` is not a `Command`, and the names are close enough to need saying.** A command is what
>   the user asked for; an edit is what that did to the document. One command may produce no edits (a
>   scroll), one (a keystroke), or several (paste over a selection).
> - **The stack owns the clock, the edit owns the intent.** `UndoStack` measures the pause and offers
>   the previous edit a merge; the edit decides whether the two are the same kind of thing. Neither half
>   can implement coalescing alone, and splitting it this way means no edit type ever carries a
>   timestamp. The window matches `TextBuffer`'s own 500ms, so a user cannot tell which mechanism
>   handled their Ctrl+Z.
> - **Two entry points, deliberately.** `execute()` applies and records; `push()` records something the
>   caller already applied — which is the shape `TextBuffer` has, since it applies a `ChangeSet` and
>   emits it afterwards. What would not be legitimate is a stack that guessed which had happened.
> - **Re-entrancy throws.** A listener that reacts to a document change by editing it again would push
>   onto a stack that is mid-unwind, leaving a history that no longer describes the document. The
>   exception lands at the call site instead of the corruption being found three actions later.
>
> **One bug worth recording, caught by a test that existed to catch it.** The "no merge run open"
> sentinel was `lastPushNanos = Long.MIN_VALUE`, and `System.nanoTime() - Long.MIN_VALUE` **overflows**
> — so "never merge again" evaluated as "merge immediately" and every edit coalesced into one step.
> `nanoTime()` has an arbitrary origin and may be negative; it is not a value you can pick a sentinel
> against. It is now an explicit boolean, and the trap is in `AGENTS.md`.
>
> **Not done, and not mine to do:** `TextBuffer` keeps its own internal undo stack. It predates this and
> works; adopting `UndoStack` is a small refactor for whoever owns 6.1.6, not something to do underneath
> them mid-rewrite. Nothing is blocked by the duplication — the two never see the same document.

<details>
<summary>The design, as settled — implemented as written</summary>

> **Design settled 2026-07-31**, because 6.1.6 could not start without it.

**Document state is edited through commands. View state is mutated directly.** The boundary: anything a
reload should give back is a command; anything that is purely how you are *looking* at the document is not.
Scroll offset, selection, column widths, expanded tree nodes — not commands. This is where VS Code,
Photoshop and Godot's `UndoRedo` all draw it, and it is why re-sorting a table is undoable in none of them.

That boundary is chosen partly because it **grandfathers the three widgets already shipped**: everything
`ListView`, `TreeView` and `TableView` mutate today is view state, so none of them changes. Had the answer
been "everything is a command", the retrofit would have been real — it is cheap only because there are
three of them rather than a dozen, which is exactly why this was the gate on 6.1.6.

**Undo is not a separate mechanism.** A text command carries a `ChangeSet`; undo is `changeSet.invert(doc)`
and redo is the change itself. Coalescing is `compose` over a time-and-intent window, not a bespoke merge
rule per command type. The stack stores changes, so it cannot drift from the document the way a stack of
closures can.

**Commands are serializable, and that is why the question was easy.** A `ChangeSet` is *data* — offsets and
inserted text — not a lambda, so it goes through `serialization/` unchanged, which is what the
server-authored editor target needs. A command shaped as "a closure that knows how to undo itself" would
have made this hard; this shape makes it not a question.

Labels for a history panel come free, since a command is already a record rather than a pair of function
pointers.

</details>

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

### 6.2.1 `CgCurveRenderer` · `DONE` (2026-07-31) · **the one true engine gap**

> **Shipped, and wired through to CrystalGUI.** `CgCurveRenderer` + `CgCurveSplitter` in `gl/render/`,
> `CgBindingPoints.CURVE_RENDERER`, the `curve` `#pragma cg_use` token, `CG_CURVE_*` in `cg_env.glsl`,
> `sdf_bezier`/`sdf_segment` in `sdf.glsl`, the shared `lib/stroke.glsl`, shipped
> `crystalgraphics:shaders/curve.shader` **and** `crystalgui:shaders/gui_curve.shader`,
> `CgUiRenderer.curve()` + `CgUiPaintContext.curve()`, 11 GL-free tests in `CgCurveRendererSplitTest`,
> a `curve-renderer-test` harness scene and a 15-row scrolling `curve` gallery page. Both suites green.
>
> Three things landed on the CrystalGUI side that the backend plan did not anticipate:
>
> - **Quads and curves cannot both be live**, so `CgUiPaintContext` flushes the outgoing path on every
>   switch. That is painter's order, not tidiness: queued quads surviving a switch would draw *after*
>   the curves regardless of submission order, so a stroke under a panel would jump on top — and only
>   when the two happened to batch together, which reads as a widget z-order bug. Because every switch
>   flushes, at most one path holds pending work, which is what makes `flush()` safe over both in any
>   order **and** what keeps `pushScissor`/`popScissor` (which both flush) correct for curves.
> - **`endFrame()` ended the batch without flushing**, silently dropping anything still queued. Harmless
>   while every draw path flushed eagerly, but `ctx.quad()`/`ctx.curve()` are public and documented as
>   "submit queues, flush draws", so a caller batching strokes and letting the frame end was using the
>   API exactly as described and losing them. Now flushes first.
> - **`CgBufferWriter.color(field, argb)`** replaced four hand-rolled shift-and-divide helpers in each
>   of the two renderers. Pinned bit-identical to the `/ 255f` it replaced, because `CgQuadRenderer` is
>   the path every glyph in the engine draws through.
>
> **`gui_curve.shader` is not redundant with `curve.shader`, and this was challenged and checked.** They
> differ in exactly three things — `DepthTest ALWAYS` vs `LEQUAL`, the `_LayerOpacity` property, and the
> one line that multiplies it in — and a Pass's `RenderState` is fixed at author time, so a keyword
> variant cannot express the difference. What *was* wrong is that the fragment body was duplicated
> verbatim; it now lives once in `lib/stroke.glsl`. The cap logic in there was wrong three separate
> times, so two copies of it was a live hazard rather than a stylistic one.
>
> **A fourth bug, found only by watching an animation.** A narrow band of missing stroke travelled
> along the gallery's node wires for a frame or two, roughly every 2.8 seconds. Two causes, both in
> `sdf_bezier`'s three-real-roots branch:
>
> - **`acos()` of an argument marginally outside `[-1,1]` is NaN**, and analytically-valid does not
>   mean valid in floating point near the branch boundary. The NaN reached `t`, and from there both
>   the coverage and the caller's gradient mix, so the fragment vanished rather than being imprecise.
>   Now clamped.
> - **The real trigger was flatness.** The wires animate `sag` through zero every 2.86s — matching the
>   observed period exactly — and at `sag ≈ 0` the split segments flatten, `bb = |A-2B+C|²` shrinks,
>   `kk = 1/bb` blows up and the solve destabilises. The degenerate test only caught *exact*
>   degeneracy, leaving a band of near-flat curvature where the answer was unstable. There is now an
>   absolute sub-pixel-flatness test alongside the relative one, routing those to the exact segment
>   distance.
>
> **Nearly-flat is not a rare shape** — splitting a cubic into quadratics produces flat segments by
> construction, so everything drawn with `cubic()` lives in that band. Which is why only the node wires
> showed it, and why "it works on the arcs" proved nothing.

> **Known untested path: the TBO fallback.** The curve buffer is read from the *fragment* stage, which
> no other engine buffer does. Both stages' sources are covered by `ShippedShaderStagePurityTest` on
> both paths, but only whichever path the dev machine's GPU selects has actually run — on GL 3.3
> hardware this becomes a `samplerBuffer` fetch in fragment that nothing has executed.
> `--mode=shader-compile-audit` on such a GPU is the check.
>
> The design below held up as written. Five things it did not anticipate, each of which cost a
> real debugging step or would have:
>
> - **A straight line divides by zero.** Quilez's quadratic solve divides by `dot(b,b)` where
>   `b = A - 2B + C`, which is *exactly* zero when the control point is the midpoint — i.e. what
>   `line()` constructs on every call. So the degenerate case is the **common** path, not an edge
>   case. `sdf_bezier` falls back to `sdf_segment` below a scale-relative threshold; without it every
>   straight stroke is NaN, which most drivers render as invisible or as garbage, with nothing logged.
> - **The fragment stage needs the control points, and the v2f DSL has no `flat` qualifier.** Only the
>   compiler-generated `cg_InstanceId` is flat. Resolved by having the fragment re-read
>   `CURVE_DATA(CG_INSTANCE_ID)` directly, which works on both buffer paths with no compiler change —
>   `appendAttachedBuffers` already runs for the fragment source. This is the one structural
>   difference from `CG_QUAD_*`, which is vertex-only.
> - **A round cap is the zero-work case; butt and square are the ones that cost something.** Clamping
>   `t` to `[0,1]` means anything past an endpoint measures distance *to* that endpoint — which is a
>   half-disc. So the enum order is misleading: `CAP_BUTT` needs an extra half-plane cut, `CAP_ROUND`
>   needs nothing.
>   - **And that cut must be applied in *signed* space** — after subtracting the half-width, not
>     against the raw unsigned distance. Intersecting a half-plane against an unsigned distance
>     compares two quantities on different scales, so the cut only bites more than a half-width past
>     the endpoint, where the radial term has already hidden the pixel. **`CAP_BUTT` becomes an exact
>     no-op and all three styles render as round.** This shipped, passed every test, logged nothing,
>     and was caught only by looking at the harness — the standing "every item lands with a harness
>     scene" rule earning its keep on the first item that tested it.
>   - **And then square caps were still wrong, for a second, unrelated reason.** Past an endpoint the
>     clamped `t` makes the SDF radial, so the cap region is a *disc* of radius `halfWidth` — and
>     cutting a disc with a half-plane at exactly `halfWidth` cuts it at its own tangent point, which
>     removes nothing. `CAP_SQUARE` was therefore forced to be pixel-identical to `CAP_ROUND`, not
>     approximately but necessarily. The cap region needs a **box metric** (perpendicular distance
>     across, tangential distance along); butt is that box ending flush, square the same box extended.
>   - **A square cap also cannot be bounded by `halfWidth` of padding.** Its corner is at
>     `h·tangent + h·normal`, so on a 45° stroke it reaches `h·√2` along a single axis while the
>     derived bounding box is axis-aligned. Padding is now `h·√2`. The failure is invisible for
>     horizontal and vertical strokes — i.e. exactly what a test row reaches for first.
>   - The caps row now draws hairline ticks at each bar's nominal endpoints, and a second group of
>     45° capped strokes. Without the ticks, "all three look rounded" and "all three are correct"
>     look the same; without the diagonal group, nothing in the scene can see the padding bug.
>   - **Three cap bugs in a row, each surviving the previous fix, all found by eye and none by a
>     test.** Worth remembering when 6.2.3 draws its first port connector: an SDF that is subtly wrong
>     renders something confident and plausible, and the harness is the only thing that disagrees.
> - **Pure maths on the renderer class is unreachable without a GL context.** `CgCurveRenderer` holds
>   a `static final CgShaderBuffer`, so *calling a static method on it at all* triggers class-init and
>   throws before `CgRenderPipeline.init()`. The cubic splitting therefore lives in its own
>   `CgCurveSplitter` — the same hazard `CgEngineBufferRegistry`'s method-reference seeding exists to
>   dodge, reached from the other direction. Found by ten tests failing identically with
>   `ExceptionInInitializerError`.
> - **Widths do not transform with a baked pose.** Baking the pose into the control points scales the
>   geometry while a scalar half-width beside it does not, so a zoomed canvas would draw correct
>   curves wearing wrong-thickness strokes. `submit()` scales widths and feather by the pose's uniform
>   scale; widths are documented as **post-pose units**, and a non-uniform scale takes `max(sx, sy)`
>   so a stroke thickens rather than vanishing under an anisotropic zoom.
>
> Two deliberate departures from the sketch below:
>
> - **No `vec2 dash` field.** Dashing needs arc length plus the closest-point parameter, which is a
>   materially larger fragment shader than everything else here, and nothing in 6.2 consumes it. The
>   record is 21 logical floats rather than 23 — and since STD430 pads each `vec3` to 16 bytes, it
>   lands on exactly **96 bytes, the same stride as `CgQuadRenderer`'s**, with no trailing waste. An
>   unused field that silently does nothing is the failure mode `CgStyleSpan`'s javadoc is quoted
>   about; adding dash later is an ordinary additive change.
> - **`curve.shader` ships in CrystalGraphics**, alongside `text.shader` rather than as a
>   harness-only asset. `ShippedShaderStagePurityTest` and `--mode=shader-compile-audit` both walk
>   *shipped* shaders only, so shipping it is what puts automated coverage on the very AMD
>   vertex-stage trap point 4 below warns about. It reads `cg_ProjMatrix` like `gui_quad.shader`
>   rather than carrying a private UBO like `text.shader`, so it costs **no** third UBO binding point.
>
> **v1 is planar.** The vertex stage derives its bounding quad as the AABB of the control hull, which
> is correct and conservative for any 2D pose including a rotating one, but degenerates for a pose
> that rotates out of the XY plane. Control points stay `vec3` so 3D is additive rather than a schema
> break. Nothing in 6.2 needs a 3D wire.

<details>
<summary>Original design sketch — retained; accurate except where the notes above supersede it</summary>

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

</details>

### 6.2.2 Pan/zoom canvas · `DONE` (2026-07-31)

> **Shipped as `CanvasView` + `WorldRect` in `ui/elements/canvas/`**, with 18 tests, a `canvasview`
> rule in `default.css`, and a **canvas page in `cgui-gallery`** that draws node wires through
> `ctx.curve()` — the first thing in the engine that looks like a node graph.
>
> It began as a standalone `cgui-canvas` scene and was folded into the gallery instead. Worth
> recording as the standing preference: the gallery is the front door, a scene that only one person
> ever remembers to run is a scene nobody runs, and a page costs one line in `createDemo` against a
> whole scene class with its own lifecycle, input plumbing and registry entry. A separate scene earns
> its keep only when it needs something the gallery cannot give it — a scripted capture burst, a
> different `uiScale`, or thousands of elements.
>
> **The sketch below was right about the hard part being done already**, and that held: zoom is a
> CSS `transform` on one internal `__content__` child, so the plane scales without reflowing anything
> and clicks follow the picture with no code in the widget at all. What the sketch did not anticipate
> was any of the following.
>
> - **A drag only ever ended on button 0.** `UIInputHandler` hard-coded it, which is invisible for
>   every drag the engine had until now — Slider, Scroller, SplitView, the resizers and the payload
>   drags are all left-button — and fatal for the first one that is not. A middle-drag pan starts
>   correctly, pans correctly, and then never ends: the release arrives, nothing tells the controller,
>   and the implicit pointer-capture release still fires because no button is down. The result is a
>   live drag consuming every mouse move with nothing held, i.e. the canvas sliding around on its own.
>   `startDrag` now takes the button, defaulting to left, and the handler compares against it. Found by
>   the one test that released the middle button rather than assuming symmetry.
> - **The wheel's sign is not guessable, and it shipped inverted.** A positive `MouseEvent.Scroll`
>   notch means the wheel rolled *down* — the only statement of that anywhere is `ScrollerView`'s
>   `setScrollTop(before + delta)`. Taken at face value, the canvas zoomed *in* when you scrolled down.
>   No test failed, because a test written from the implementation agrees with the implementation; it
>   took one person and one second at the harness. Now pinned by a test that asserts *both* consumers
>   in one method, so if the platform's sign ever flips they fail together instead of drifting apart.
>   The general lesson is the one this file keeps relearning: a convention that lives in exactly one
>   call site is a trap for the second call site, and the fix is to write it down where the next
>   author will be standing.
> - **The drag source must be the viewport, never the plane.** Every coordinate a `DragListener`
>   receives is converted through the source's own transform — so dragging *from* the thing being
>   panned moves the frame the delta is measured in, and the view accelerates away from the cursor
>   instead of following it. Cheap to get wrong, because the plane is the obvious source.
> - **`transform-origin` defaults to 50%, and that silently breaks every conversion.** The plane must
>   scale about its own top-left or world↔screen is off by half a viewport times the zoom. Pinned at
>   IMPORTANT rather than compensated for: one answer instead of two, and a theme cannot take it back.
>   Pinned by its own test, because the failure looks *internally consistent* — the picture is fine,
>   the clicks are fine, and only the relationship between them is wrong.
> - **Culling wants `opacity: 0`, not `display: none`.** This is the finding worth keeping. A culled
>   node's layout rect is precisely the input its own cull decision is computed from, so collapsing its
>   layout means it can never be un-culled without a cache of where it used to be — and that cache goes
>   stale the moment anything moves a node while it is invisible. `opacity: 0` short-circuits
>   `drawSubtree` at the top, keeps layout live, and makes the decision self-correcting on every tick.
>   It also costs no relayout as nodes cross the viewport edge, which panning does constantly. What is
>   given up is layout cost for off-screen nodes, and that is the smaller half: layout recomputes only
>   when dirty, paint happens every frame.
> - **Pan is measured after zoom**, because the transform is `translate(pan) scale(zoom)` in CSS's
>   left-to-right order. That makes a pan drag a plain addition of the pointer delta at any zoom, with
>   no division to get subtly wrong at the extremes. `centerOnWorld` covers the cases that genuinely
>   want world units.
> - **A bare left-drag deliberately does not pan.** It is 6.2.4's marquee, and handing it to panning
>   now would mean taking it back later. Middle-drag always pans; Space+left is the escape hatch, read
>   as held state through `CgPlatform.input()` rather than as an event. The gesture is read in the
>   **capture** phase, so it beats whatever node is under the cursor — bubbling would make space-drag
>   work everywhere except over the nodes, i.e. everywhere except where you want to grab.
>
> **Deliberately not done here.** No grid background: a tiled background on `__content__` is in world
> space and therefore scales and slides with the view for free, so it is a theme's line of CSS rather
> than a widget feature. No zoom-to-fit-selection (there is no selection until 6.2.4), no inertia, and
> no minimap.

<details>
<summary>Original sketch — retained; accurate as far as it went</summary>

A viewport widget: wheel-zoom about the cursor, middle-drag or space-drag to pan, fit-to-content, and
culling of off-screen nodes.

`transform` already does the hard part — it is layout-free, so scaling the canvas cannot reflow anything,
and `UITransform.applyTo` is shared between the hit-test chain and the render `PoseStack`, so clicks follow
the picture. What is missing is the widget, the input gestures, and the culling.

</details>

### 6.2.3 Node and port widgets · `DONE` (2026-07-31)

> **Shipped as `ui/elements/graph/`** — `GraphView`, `GraphNode`, `NodePort`, `NodeWireLayer`,
> `GraphConnection`, `PortType`/`BasicPortType`/`PortTypeRegistry`, `PortDirection` — with 18 tests, a
> new `crystalgui:graph` theme, functional geometry in `default.css`, and the gallery's canvas page
> rebuilt as the reference graph (Position + Normal Vector → Perlin noise → Add).
>
> **The plan held: all six named traps were paid for in advance and none of them bit.** What the plan
> did *not* contain is the interesting part.
>
> - **Nodes could not be moved, because nobody had said they should be.** The plan listed dragging a
>   node under "traps" — deltas belong to the viewport — and never as a deliverable, so it was built
>   with wires draggable and nodes nailed down. Found in three seconds by the first person to look at
>   it. The lesson is not about nodes: a trap list is not a feature list, and an item whose
>   *deliverables* section omits something will ship without it however carefully the risk was
>   analysed.
> - **A node is a safe drag source, and the reasoning is worth keeping.** 6.2.2 established that a pan
>   must drag from the viewport, because the drag changes the plane's *transform* and every listener
>   coordinate is converted through the source's transform. Moving a node changes `left`/`top`, which
>   are layout, and layout is not in the matrix — so the node's own space stands still while the node
>   moves through it. The two cases look identical and are opposites.
> - **`FocusPolicy.CLICK` does not focus a composite**, because the input handler focuses the *exact*
>   element hit — and a press on a node lands on its title bar. Every other composite dodges this by
>   making its parts `setHitTest(false)`, which is unavailable when a part is itself interactive. The
>   DOM's rule is to focus the nearest focusable *ancestor*; ours does not walk. `GraphNode` calls
>   `requestFocus` itself and the engine gap is recorded as an open question below.
> - **The empty preview slot made every node a tall dark box.** Shipping the slot but not the pipeline
>   was the right call; attaching it unconditionally was not. `preview()` now creates it on first ask,
>   so a node that has nothing to show is the height of its ports.
> - **A long port label overflowed into the opposite column**, because `flex-shrink` is 0 in this
>   engine: "Sampling Coordinates(3)" printed straight across "Value(1)". Zero basis plus `flex-grow`
>   plus `text-overflow: ellipsis` on the label — which has to sit on the `UIText` itself, since it does
>   not inherit.
> - **The node paints no background of its own.** Asked for after seeing it: the port band must be
>   transparent so a wire, drawn under every node, shows through and reads as *plugged into* its dot
>   rather than stopping at the border. Each region paints itself instead, and the title bar and preview
>   carry the corner radii the root no longer paints — otherwise a square bar sits over a rounded border
>   at exactly the two corners a user looks at first.
>
> **The palette seam works exactly as designed**, which is the part worth reusing: `NodePort.typeColor()`
> reads the dot's computed `border-color` back out of the cascade, so `CgCurveRenderer` gets its ARGB int
> and the colours still live in a stylesheet. Adding a type is a `PortType` and a CSS rule, with no Java
> colour anywhere — and a `float → vec3` promotion draws as a light-blue-to-yellow gradient for free,
> because the instance record already carried two colours.
>
> **Deliberately not done here.** No marquee, no multi-select beyond Shift-click, no delete, no undo —
> all 6.2.4, which is the one item that genuinely waits for 6.1.9's command stack. No node library
> (6.2.6) and no preview pipeline (6.2.7).

<details>
<summary>The design, as planned — accurate except where the notes above supersede it</summary>

**The look is the specification, and it is Unity Shader Graph's.** Settled 2026-07-31 against three
reference screenshots plus Unity's own documentation. This is not "inspired by" — the target is that a
screenshot of ours and a screenshot of theirs differ in content, not in construction. That is worth
being literal about, because "make it look like Unity" is otherwise the kind of instruction that
produces something 80% right and permanently unsatisfying.

#### Anatomy

```
┌───────────────────────────────┐  ← 1px border, ~4px radius. SELECTED: cyan ring, same radius
│ Position                    ⌄ │  ← title bar: lighter than body, collapse chevron at top-right
├───────────────────────────────┤
│                      Out(3) ● │  ← output row: label right-aligned, dot ON the border
├───────────────────────────────┤
│ ● A(3)               Out(3) ● │  ← a row can carry one input and one output
│ ● B(3)                        │
├───────────────────────────────┤
│ Space            [ World  ▾ ] │  ← a CONTROL: no port, just a widget. Dropdown/TextField/Slider
├───────────────────────────────┤
│ ▓▓▓▓▓▓ preview ▓▓▓▓▓▓         │  ← preview slot, its own toggle, revealed on hover
└───────────────────────────────┘
     ● X  0.9 ●─────                ← an UNCONNECTED input carries an inline editor of its own type
```

#### The palette, and why none of it may be written in Java

Unity assigns a colour per data type and applies it **to the port and to every edge leaving it**, which
is what makes a dense graph readable at a glance — you can see that a float is feeding a vec3 slot
without reading a single label.

| Data type | Colour | | Data type | Colour |
|---|---|---|---|---|
| Float | light blue | | Boolean | purple |
| Vector 2 | green | | Texture 2D / 3D / Array / Cubemap | red |
| Vector 3 | **yellow** | | Gradient, SamplerState, Virtual Texture | grey |
| Vector 4 | pink | | Matrix 2 / 3 / 4 | blue |

Both reference screenshots agree with that table — `Out(3)` and its wires are yellow, `Noise Scale(1)`
and its wire are light blue — which is the useful confirmation that the docs describe the shipping
product rather than an older revision.

**Every one of these is a CSS class, not a constant.** `.port.type-vec3 { background: … }` in a
`graph.css` theme, with `default.css` carrying only the functional geometry (dot size, row height, the
fact that a port row is `flex-direction: row`). This is the standing no-colours-in-Java rule, and here
it pays twice over: the type set is CrystalShader's, not CrystalGUI's, so a hard-coded palette would
put GLSL's type system inside a general-purpose editor framework — the exact mistake `CgStyleSpan`'s
javadoc is quoted about elsewhere in this file.

#### Four behaviours the screenshots cannot show, taken from Unity's docs

These are the ones that would have been discovered late and expensively.

1. **One edge per input port; many per output.** So connecting to an occupied input is a *replace*, not
   a reject — and the implicit disconnect has to travel through the same command as a manual one, or
   undo quietly forgets it.
2. **An unconnected input carries an inline editor of its own type** — the `X 0.9` field in the third
   screenshot. Not decoration: it is how a graph is usable before everything is wired. Bound ports
   instead show a purpose-built control (Unity's example is a UV-channel dropdown), which is the same
   seam as our `PortType` supplying its own editor.
3. **Collapsing a node hides its unconnected ports**, not just its body. Getting this wrong gives a
   collapsed node that is still a metre tall in a busy graph, which defeats the feature entirely.
4. **Dropping an edge on empty canvas opens the node-creation menu**, pre-filtered to types that can
   accept it. That is 6.2.6's job, but the drop path has to leave room for it now.

#### The widgets

| Class | Role |
|---|---|
| `GraphNode` | The box. Composite, refuses public children. `titleBar()`, `addInput/addOutput`, `addControl`, `preview()`, `setCollapsed` |
| `NodePort` | One port: direction, `PortType`, label, the `__dot__` internal child, `isConnected()` |
| `PortRow` | The left-input/right-output pairing — one Taffy row rather than two columns, so a row's label baselines line up |
| `NodeWireLayer` | Paints every wire, and owns wire picking |
| `PortType` | SPI: id, label, compatibility, and the editor an unconnected input should show |
| `GraphConnection` | A source/target port pair — the view-side edge, distinct from 6.2.5's model edge |

LDLib2's own toolkit decomposes almost identically (`NodeElement`, `NodeTitleElement`,
`PortContainerElement`, `PortElement`, `PortConstantEditorElement`, `WireElement`, `WireDragHelper`),
which is good evidence the split is the natural one rather than ours.

#### Types are data, not an enum

`PortType` is an interface with an id, a display label, `isCompatibleWith(other)`, and a factory for the
inline editor. CrystalGUI ships nothing but the interface and a registry; CrystalShader registers
`float`, `vec2`, `vec3`, `vec4`, `mat3`, `mat4`, `sampler2D`. The manifesto is explicit that the graph
is *"a visual editor for the `.shader` file format"* whose nodes each compile to a GLSL function — so
the type system is GLSL's, and `core/` must not learn it. The registry maps id → CSS class, which is
what keeps the palette in the theme.

#### Drag-to-connect

`UIDragController` with the source port as payload; a target accepts by calling `preventDefault()` on
`DragOver` when `isCompatibleWith` says so. **Rejection is already the default** — the one good idea
kept from HTML5 DnD — so "this port will not accept a vec3" needs no new mechanism, and acceptance is
re-evaluated every frame rather than latched, which is exactly right for a rule that depends on what is
under the cursor.

The live wire is drawn by the wire layer, **not** by a drag ghost. A ghost is an element that follows
the pointer; a wire is a curve between two points, one of which is the pointer. Escape already cancels
a drag before anything else sees it.

#### Wires: one layer, not an element each

An element per wire would give `:hover` and `:checked` for free, and costs a Taffy node, a layout pass
and a draw-call switch per edge. One layer paints every wire in a single `ctx.curve()` batch and owns
picking analytically — distance to a quadratic, the CPU twin of `sdf_bezier`. A thousand-edge graph is
then a thousand instances in one flush rather than a thousand elements.

What that gives up is per-wire CSS state, which has to be re-implemented as data the layer holds. Worth
it, and worth writing down as a deliberate trade rather than discovering later that wires cannot be
styled by a stylesheet the way everything else in this engine can.

#### Six traps, named now

1. **A wire's endpoint is the port dot's live world centre**, read through the transform chain — never a
   cached point. The same lesson as `resizeOriginLeft()` reading the live Taffy inset, and as
   `AnchoredPlacement` taking anchor geometry from the transform chain rather than the layout box.
2. **A connect must update the node in place, never rebuild it.** The mouse is *on* the port at that
   moment: rebuilding detaches the element under the cursor and every later frame feeds the drag
   garbage. This froze the table header once already.
3. **Stroke width scales with the pose**, so a 2px wire is 2 *logical* px at any zoom — which means at
   zoom 0.2 it is a fifth of a pixel and the graph looks empty. Wants a screen-space minimum width.
   Open question below.
4. **Pointer deltas belong to the viewport, not the plane** — 6.2.2's lesson, and dragging a node is the
   next place it applies.
5. **A port's hit target must be larger than its dot.** The dot is ~8px; a fingertip target is not. Pad
   the port element and keep the dot decorative, or connecting becomes a precision exercise.
6. **The wire layer must declare the region it paints**, or culling — which tests a node's *box* — bins
   every wire the moment the view leaves world origin. Already hit and fixed once, in the gallery.

#### Deliverables

- `ui/elements/graph/`: `GraphNode`, `NodePort`, `PortRow`, `NodeWireLayer`, `PortType`, `PortRegistry`.
- Functional geometry in `default.css`; the Unity palette and chrome in a new `graph.css`.
- Tests: compatible/incompatible drop, replace-on-occupied-input, collapse hides unconnected ports,
  port anchors correct under pan+zoom, wire picking, and the port hit target being bigger than the dot.
- The gallery's `canvas` page grown from coloured boxes into a real four-node graph.

#### Open questions

| Question | Notes |
|---|---|
| ~~Minimum wire width in screen space?~~ | **Answered: clamped in Java, against the canvas's zoom** (`GraphView.getWireWidth`). The shader stays linear and every other `curve()` consumer is unaffected. |
| ~~Does selection live on the node or in a model?~~ | **Deferred honestly**: a boolean on the node plus `GraphView.selectNode`, which is the smallest click behaviour that works. 6.2.4's model replaces the list rather than fighting a second source of truth. |
| ~~`:checked` for a selected node, or a class?~~ | **Answered: `:checked`.** `GraphNode` overrides `isChecked()`, so the cyan ring is a theme rule. |
| **Should click-focus walk up to the nearest focusable ancestor?** | New, and an engine question rather than a graph one. The DOM does walk — that is why clicking a `<button>`'s inner text focuses the button. `emitMouseDown` focuses the exact element hit, so any composite with an *interactive* child (a node's title bar carries the collapse chevron, so it cannot be `setHitTest(false)`) never gets focus from a click. `GraphNode` compensates by calling `requestFocus` itself. Fixing the handler would cover every composite at once and changes focus behaviour engine-wide, so it wants doing deliberately — and not while the text editor is mid-rewrite. |

</details>

### 6.2.4 Selection, marquee, and graph editing · `DONE` (2026-07-31)

> **Shipped**: `GraphSelection` (the model that replaces 6.2.3's per-node boolean), the marquee,
> move-many, delete with undo, wire picking and deletion, and `GraphCommands` — `graph.delete`,
> `selectAll`, `clearSelection`, `frameSelection`, `frameAll`. 20 tests in `GraphEditingTest`.
>
> **The research was the work**, and it held: touched-not-enclosed, Shift adds / Alt subtracts,
> selection stays out of the undo history, duplicate deferred because a `GraphNode` is a widget the
> caller assembled and cannot be cloned. Every one of those is in the plan above with its reasoning.
>
> **What the harness found that seventeen passing tests did not.** Five bugs, and the pattern is worth
> more than any of them: *every single one was a seam between the widget and the engine* — focus,
> hover, key names, layout timing — and not one was in the graph logic the tests covered.
>
> - **The graph could not hold focus at all.** `FocusPolicy` defaults to `NONE`, so `requestFocus`
>   silently refused it, and every command — which resolves the nearest `GraphView` *from the focused
>   element* — disabled itself. Delete, Ctrl+A and Escape all did nothing while the widget looked
>   entirely alive. `F` worked, because pressing a node happens to focus the node, which made it present
>   as "some keys work and some do not" rather than as one broken thing.
> - **Then focusing it drew a ring around the whole viewport**, because `requestFocus` is
>   `PROGRAMMATIC` — the one focus source `:focus-visible` exists to ring. The engine already had
>   `requestPointerFocus` for this, with a javadoc that says exactly why. Pointer-driven focus must use
>   it; the ring is for keyboard focus, which is the whole point of the pseudo-class.
> - **Deleting the node under the pointer crashed the next frame.** `UIInputHandler` kept
>   `lastFrameHover` pointing into the detached subtree, so the hover diff asked for a common ancestor
>   between two elements in *different trees* — the walk never converges and runs off the end of both.
>   Fixed at both ends: the handler now forgets a removed element (hover, press target, pointer capture,
>   any drag anchored on it — focus already did this), and `commonAncestor` returns null rather than
>   walking off a tree. **An engine bug, exposed by being the first widget that deletes what you are
>   pointing at.**
> - **`bind("Backspace")` threw at construction and took the whole scene down.** Key names are reflected
>   from `CgKeyCodes`, where the constant is `KEY_BACK` — an LWJGL2 spelling leaking into a user-facing
>   string. There was already an alias table (`ENTER`, `ESC`, `DEL`); `BACKSPACE` joins it. **No test
>   caught it because every test drove the API directly and never installed the commands.**
> - **A move recorded a delta of zero.** Drag end re-read `worldBoundsOf()`, but the final `moveNode` of
>   a drag writes insets Taffy has not resolved yet — so the position was one frame stale and a short
>   drag recorded nothing at all. The drag's own reported delta is the only non-stale source.
>
> **And one design fix from watching it**: framing computed the literal fit, which for one small node in
> a large viewport is an eight-times blow-up filling the screen with a single box. Framing now never
> magnifies past 1:1 — it zooms out to fit and stops at natural size.
>
> #### Known gap: Escape
>
> `Escape` is bound to `graph.clearSelection` and works in isolation, but did nothing in the gallery.
> The cause is almost certainly correct behaviour rather than a bug: the engine gives Escape to a live
> drag, then to the **topmost close watcher**, and only then to the keymap — and the gallery's Dialog
> page leaves dialogs open, each holding a watcher. Not chased further because the consumer does not
> want the feature; recorded so the next person does not mistake it for an untested path. The same
> ordering is why Escape does not cancel a live marquee there.
>
> #### Deliberately not done
>
> Duplicate, copy and paste — they need 6.2.5's document model or 6.2.6's node factory, per the plan.

<details>
<summary>The plan, as researched — implemented as written except where noted above</summary>

#### What the research settled

**1. Selection is not undoable — and this is a real disagreement, not an obvious call.** Blender records
selection in its undo history, and is criticised for it in almost the same words every time: it is
*"counter to basically all other applications"*. Figma carries an open request for a *preference*. Silo
splits the difference by putting selection undo on its own shortcut (`Ctrl+Shift+Z`). The case in favour
is real and worth stating: losing a laborious multi-selection to one misclick is genuinely painful.

We follow the majority — selection is view state, exactly as 6.1.9's boundary already says — and answer
the real complaint the way VS Code does instead: **undoing an edit restores the selection that edit
applied to**, because the edit knows what it touched. That gives back the case people actually lose
without putting a click in the history.

**2. Marquee modifiers are settled across the industry**: plain drag replaces the selection,
`Shift+drag` adds (toggles), `Alt+drag` subtracts. Same in Blender, Unreal and Figma.

**3. A node is selected by being *touched*, not by being enclosed.** No vendor documents this, so it is
a decision: at any zoom where a node is larger than the viewport, an enclose-only rule makes it
unselectable by marquee. CAD's direction-dependent convention (left-to-right encloses, right-to-left
crosses) is rejected — it is powerful, unguessable, and belongs to a domain where precision beats
discoverability.

**4. Unity's shortcut set is worth matching**, since it is the reference for the whole look: `Ctrl+D`
duplicate, `Ctrl+C`/`X`/`V`, `Delete` removes, **`F` frames the selection**, **`A` frames everything**,
`Space` opens the create-node menu, `Ctrl+P` toggles collapsed. Two of those land free — `F`/`A` are
`fitToContent` over a rect we already compute, and `Ctrl+P` is the collapse 6.2.3 shipped.

**5. Duplicated nodes become the new selection.** Unity's own issue tracker files the opposite as a bug,
and Figma and Illustrator agree. The copy is offset so it does not hide the original.

#### The finding that changes the scope: duplicate cannot be built yet

**A `GraphNode` is a widget the caller built.** It has whatever ports, controls and preview the caller
gave it, in whatever arrangement — so the view cannot clone one. There is no general "copy this element
tree" in this engine and there should not be: a control inside a node may hold a binding, a listener, or
a reference to something outside the graph entirely.

Duplicate therefore needs either the **document model** (6.2.5 — copy the data, rebuild the widget) or
the **node library** (6.2.6 — a registered factory per node type). Both are a mechanism 6.2.4 does not
have, so **duplicate, copy and paste are deferred**, and 6.2.4 ships selection, marquee, move-many and
delete. That is the honest split, and it is better found here than three hours into an implementation
that had assumed a clone.

Delete is unaffected: removing a node needs no factory, only its wires unwound in one transaction — and
`removeNode` already does exactly that.

#### Deliverables

| Piece | Notes |
|---|---|
| `GraphSelection` | An insertion-ordered set plus a signal, owned by `GraphView`. Replaces the boolean-per-node placeholder 6.2.3 left, which was explicitly the smallest thing that worked |
| Marquee | A `__marquee__` element in the **viewport** (screen space, so it stays a crisp 1px at any zoom) over a **world-space** hit test against each node's `WorldRect` |
| Move-many | Dragging any selected node moves the whole selection, as one transaction. Dragging an *unselected* node selects it first — every editor does this, and the alternative is a drag that appears to do nothing |
| Delete | `graph.delete`, on `Delete` and `Backspace`. Selected nodes, their wires, and any selected wires, in one transaction |
| Select all / none | `graph.selectAll` on `Ctrl+A`; `Escape` clears |
| Frame | `graph.frameSelection` on `F`, `graph.frameAll` on `A` — `fitToContent` over the selection's union |
| Wire selection | Analytic picking — distance to the quadratic, the CPU twin of `sdf_bezier`. The layer is `hitTest(false)`, so the canvas asks it to pick before starting a marquee |

#### Traps, named now

1. **A press on a node must not clear the selection it is part of.** Clicking one of five selected nodes
   to drag them all is the single most common gesture in a graph editor, and the naive "press selects
   only this" implementation breaks it. The rule everyone uses: on *press*, select-only if the node is
   not already selected; on *release without a drag*, select-only regardless.
2. **The marquee must not start on a node** — 6.2.2 gave the canvas the left-drag, and the node claims
   it in the capture phase, so the ordering already works. It has to keep working.
3. **Selection survives an undo, and must not be reconstructed from stale elements.** A deleted node's
   widget is out of the tree; the selection has to drop it or it pins a detached subtree and re-adds it
   on the next move.
4. **Escape is already spoken for** by a live drag and the close-watcher stack. Clearing the selection
   is the lowest-priority claim on it and must not pre-empt either.

</details>

### 6.2.5 Graph document model and serialization · `DONE` (2026-07-31)

> **The view migration landed too.** `GraphView` now projects a `GraphDocument`: `GraphNode` carries a
> `nodeId`, `NodePort` a `portId` distinct from its drawn label, all three `Edit`s key on ids, and
> `syncFromDocument()` applies a changeset in place. 13 tests in `GraphDocumentViewTest`, plus save and
> reload buttons on the gallery's graph page so the round-trip is testable by hand.
>
> **Behaviour deliberately unchanged** — every 6.2.3/6.2.4/6.2.6 test passed without edits.
>
> #### Four bugs, and three of them were about identity rather than data
>
> The topology was right on the first try; what kept going missing was *which node this is*.
>
> 1. **`NodeWidgetFactory` never bound what it built.** Every node reloaded as a placeholder titled
>    `crystalgui:widget` with its controls gone. The binding belongs in the factory, not at the call
>    sites: a registered builder is a consumer's lambda that ignores the data and just builds a widget,
>    so every builder that forgot would reproduce it.
> 2. **Ports added after `addNode` never reached the document.** `addNode(n, x, y); n.addOutput(...)` is
>    the order 6.2.3's own examples use, and the `NodeData` was derived once at add time — so the port
>    existed on screen, could be wired, and was absent from every save.
> 3. **That fix's first guard rejected exactly what it served.** It tested `widget.getTypeId() != null`,
>    but binding *sets* the type id to `WIDGET_AUTHORED_TYPE`. The **document** decides whether a node is
>    widget-authored, not the widget.
> 4. **The changeset net-collapses, which the write-through broke.** The view's own add sat pending, so a
>    later remove cancelled it instead of recording a removal — the changeset said "nothing happened"
>    while the view still held the widget. The view now drains the changeset whenever it writes through,
>    so it only ever holds changes the view has *not* applied.
>
> #### The re-entrancy worth remembering
>
> `CanvasView.addNode` calls `moveNode` **polymorphically**, so applying a changeset re-entered the
> override, wrote through, and drained the changeset mid-drain — adding the first node silently wiped the
> pending edges. `syncFromDocument` snapshots every list before applying anything.
>
> #### The honest limit
>
> A node built as a widget rather than registered as a type keeps its ports and (now) its title, but its
> controls and preview cannot come back — those are Java the document never saw. Register a type if you
> want a node to survive a reload whole.

<details>
<summary>The original plan, kept for the research it records</summary>

> **The model has landed; the view migration has not.** Shipped: `com.crystalgui.graph` —
> `GraphDocument`, `NodeData`, `PortSpec`, `PortRef`, `EdgeData`, `GraphChangeset`, `GraphIds`,
> `TypeCompatibility`, `GraphCodecs` — with **16 tests in `headlessTest`**, so a server can author and
> validate a graph with no GL context. Cycle rejection at connect time, topological order, byte-identical
> round-trip under both `PlainOps` and `JsonOps`, unknown node types surviving a load, and `copyOf` /
> `merge` (which is what duplicate and paste are made of).
>
> **`PortDirection` moved to `com.crystalgui.graph`** — direction is a fact about the data, and the
> widget now imports it rather than the reverse.
>
> **Still to do: repointing `GraphView` at a document.** It currently *is* the model — it owns the edge
> list, and 6.2.4's edits hold widget references. The migration is the risky half, because those edits
> must start holding **ids**, and an id has to survive delete-then-undo or every edge referencing it
> breaks. Left as its own step rather than rushed into the end of the session that built the model.

Nodes, ports, typed connections, validation, and round-tripping through `serialization/`. The point at
which this stops being a UI demo and becomes the shader graph's actual data. **Researched 2026-07-31**
against Unity's `.shadergraph` format and `GraphData`, LDLib2's node toolkit, our own
`serialization/` layer and the CrystalShader manifesto.

#### First, a contradiction to resolve: the manifesto says there is no graph format

> *"The text-based `.shader` format is not a 'lower-level alternative' to the node graph. It **IS** the
> node graph's serialization format, just written by hand. … No separate formats."*

Taken literally that forbids this item. Taken correctly it does not, and the distinction matters
enough to write down before any code exists:

- **The user's file stays `.shader`.** That claim is about the artefact on disk, and it survives intact.
- **CrystalGUI ships a generic typed graph document** — nodes, ports, edges, values — because P6's
  settled scope is *a general-purpose editor framework, with the shader graph as its first client*. A
  framework whose document model is GLSL is not a framework. And undo, copy/paste, a server-authored
  graph and a "missing node type" placeholder all need a document, not a compiler.
- **CrystalShader maps that document to and from `.shader`.** The model is the in-memory IR both the
  editor and the compiler talk about — which is exactly what the manifesto's Cornerstone 8 already
  calls for (`CgShaderNode`, `CgShaderGraph`, `CgGraphCompiler`).

The obligation this puts on 6.2.5 is concrete: the document must be able to **express what the
compiler needs**, or the mapping is lossy and the contradiction becomes real. That means stable ids
usable as a GLSL namespace prefix, node *type* ids, typed ports, per-node property values, and room for
a `domain` attribute (the manifesto's vertex/fragment split) — none of which requires `core/` to learn
a single GLSL type.

#### What the research settled

**1. A flat table with stable ids, not a nested tree.** Unity's `.shadergraph` is a flat list of JSON
objects, each carrying a string `objectId`, referencing each other by id — with `JsonData<T>` meaning
*ownership* and `JsonRef<T>` meaning *reference*. That ownership/reference split is worth stealing
outright: it is the difference between "the document contains this node" and "this edge points at it",
and it decides what a delete removes.

A graph is not a tree, so nesting it is a lie that costs you at the first node with two consumers.

**2. Ids are stored, and that is the opposite of what the UI tree does.** `NetworkIds` derives ids from
a document-order walk and transmits nothing — and our own doc states the trade-off plainly: *"this is
why there is no structural delta yet. Inserting an element renumbers everything after it."* For a
graph that is fatal, because edges reference ids: adding one node would re-point every edge in the
file. Ids must be **stored in the document**, generated once, and stable for the life of the node.

Unity's `objectId` is "letters and numbers" and is documented as usable *during shader code
generation* — which is also our namespacing prefix, per the manifesto's `node_multiply_out` example.
One id, three jobs: reference, diff, and generated-code namespace.

**3. The view syncs from a changeset; it never rebuilds.** Unity's `GraphData` accumulates
`m_AddedEdges`/`m_RemovedEdges` for the view to drain; LDLib2 has a `GraphChangeset` doing the same.
Two independent implementations arriving at the same shape is good evidence — and our own trap list
demands it anyway: **rebuilding detaches the element under the pointer**, which is how the table header
froze and what 6.2.3 was warned about.

**4. Validation belongs to the model.** Type compatibility, one-edge-per-input, and **cycle rejection
at connect time**. The compiler topologically sorts, so a cycle is not a rendering artefact — it is a
graph that cannot compile, and refusing it at the moment of connection is the only place the user can
still see which wire caused it.

#### The model

| Type | Shape |
|---|---|
| `GraphDocument` | id → `NodeData`, plus the edge list. Headless, no `ui/` imports |
| `NodeData` | `id`, `typeId`, world `x`/`y`, declared ports, property values |
| `PortSpec` | `portId`, direction, `typeId` — the port's *identity*, not its widget |
| `PortRef` | `nodeId` + `portId`; what an edge points at |
| `EdgeData` | `from` (output `PortRef`) → `to` (input `PortRef`) |
| `GraphChangeset` | added/removed nodes and edges, plus moved ids — what the view drains |

**Ports are stored per node, even though the node *type* declares them.** The alternative — store only
the type id and look the ports up — keeps documents smaller and makes a type change authoritative, and
it is wrong for the case that matters: a document whose node types are not registered (a plugin absent,
a mod not loaded) must still **open**, keep its edges, and round-trip unchanged rather than being
silently emptied. Storing the ports is what makes a "missing node" placeholder possible instead of data
loss. Unity stores them too.

> **This is a deliberate divergence from `ElementRegistry`, which throws on an unknown tag.** That is
> right for a *UI description*, where an unknown tag means the two sides disagree about code that
> should be identical. It is wrong for a *document*, where an unknown type means someone opened a file
> without a plugin — and eating their graph is a far worse outcome than showing a grey box.

#### Serialization

- A `Codec<GraphDocument>` over the existing `Codecs.map(...)` / `Codecs.read(...)` pair, `PlainOps` by
  default (no Gson on the server path), `JsonOps` for tests and for the clipboard.
- **Content-addressed like `UIDescriptionCodec`**: fixed field order, insertion-ordered maps, absent
  optionals omitted rather than written null. That is what makes `ContentHash` meaningful, and a graph
  can then be sent by hash exactly as a UI description is.
- Enums by **name, never ordinal** — `Codecs.enumOf` already insists.
- **A schema version field**, which `UIDescriptionCodec` deliberately does not need: a description is
  regenerated from live code every time, while a document is written to disk and outlives the code that
  wrote it. Version it from the first commit or the first format change is a migration with no anchor.

#### The seam, and the migration it implies

`GraphView` currently **is** the model: it owns the edge list, and 6.2.4's edits mutate it directly.
6.2.5 inverts that — the document owns the data, commands mutate the document, and the view applies the
resulting changeset in place. Two consequences worth stating before starting:

- **6.2.3/6.2.4's `Edit`s become document edits.** `ConnectEdit`, `AddNodeEdit` and `MoveNodeEdit`
  already hold data rather than closures, so they translate rather than being rewritten — but they must
  hold **ids**, not widget references, or an undo after a delete restores an edge pointing at a widget
  that no longer exists.
- **An id must survive delete-and-undo.** Deleting a node and undoing must restore the *same* id, or
  every edge that referenced it breaks. That is a property of the edit, not of the document.

#### What this unblocks: duplicate, copy and paste

The reason 6.2.4 could not build them. With a document they are ordinary:

- **Duplicate** — copy a subgraph of `NodeData` with **fresh ids**, keep edges whose *both* ends are in
  the copied set, drop the rest, offset the positions, and select the copies. (Unity's own issue tracker
  files "nodes not selected after duplicating" as a bug; Figma and Illustrator agree.) Dropping external
  edges is a choice — Blender keeps incoming links — made because a duplicate that silently re-feeds the
  original's upstream is a graph the user did not draw.
- **Copy/paste, including across documents** — the same operation with the clipboard as the transport,
  encoded through `JsonOps`. The clipboard is already a platform seam on `CgInputService`.

#### Deliverables

- `ui/elements/graph/model/`: `GraphDocument`, `NodeData`, `PortSpec`, `PortRef`, `EdgeData`,
  `GraphChangeset`, `GraphCodecs`.
- Validation: type compatibility, one-edge-per-input, cycle rejection with the offending edge named.
- `GraphView` re-pointed at a document, applying changesets in place.
- Tests in **`headlessTest`** — the absence of CrystalGraphics is the assertion that a server can author
  and validate a graph.
- Round-trip and hash-stability tests: encode → decode → encode is byte-identical, and two documents
  built in different orders hash the same.

#### Open questions

| Question | Notes |
|---|---|
| Where do graph-level properties (a blackboard) live? | Unity has one, LDLib2 has `IVariable`, and the manifesto needs *"properties declared in nodes bubble up to the `.shader` Properties block"*. Probably a document-level list, but it is a second kind of thing and can wait for a consumer. |
| Subgraphs? | LDLib2 has `ISubgraphNode`; the manifesto implies them. A node whose type resolves to another document. Deferred, but the id scheme must not preclude it. |
| Does the document own node *positions*? | Yes here — but note that position is view state by 6.1.9's boundary, and a moved node is nonetheless something a reload should give back. The resolution is that position is document data with no undo-relevance debate: `MoveNodeEdit` already records it. |

</details>

### 6.2.6 Node library and creation menu · `DONE` (2026-07-31)

> **Shipped**: `NodeType` + `NodeTypeRegistry` (headless, 10 tests), `NodeWidgetFactory` with its
> placeholder path, `NodeCreationMenu` (14 tests), `graph.createNode` on Space, the contextual menu on a
> wire dropped over empty canvas, and the gallery's graph page rebuilt on a real six-type library.
>
> **The research held.** Entries are **(type, port) pairs** rather than types, so choosing one creates
> the node *and* lands the wire in one undo step; search matches synonyms because the type declares
> them; and the filter asks the document's own compatibility rule, so a float output is offered the vec3
> inputs it may legally promote into.
>
> **The placeholder path earned its keep immediately.** Four of the gallery's six types have no custom
> widget at all — they are built from the document's stored ports — which is the same mechanism as
> "opened without the plugin" and means a library is usable before anyone writes a factory.
>
> #### Eleven bugs, all found by looking, and they fall into two families
>
> Not one was in the library logic the headless tests cover. They were **seams between a promoted popup
> and the canvas underneath it**, and **CSS idioms applied in the wrong container context** — and the
> second family is the more embarrassing, because this file already contains the notes that predict them.
>
> **The promoted-child family.** The menu is the graph's DOM child, promoted to the top layer, so its
> input still travels *through* the graph:
>
> - A press on the search box or the resize handle was stolen by the canvas, which treated it as a press
>   on empty background, cleared the selection and started a marquee with pointer capture. The rows
>   worked, because their own handler stops propagation — which made it look arbitrary rather than
>   systematic.
> - The wheel over the menu zoomed the graph. `ScrollerView` only claims the wheel *while it actually
>   scrolls*, deliberately, so a short list or one at its end chains outward.
> - Light dismiss never fired, because the graph was named as the menu's **invoker** — and an invoker
>   counts as part of its own popover, the carve-out that stops a dropdown button being dismissed by the
>   press that opens it. Naming the whole canvas made every press "inside" the menu.
>
> The guard now lives on `CanvasView`, because it is one rule for every background gesture: **pan, zoom
> and marquee all ask it.** Three gestures, three separate discoveries of the same thing.
>
> **The wrong-container family:**
>
> - The list carried `height: 0` + `flex-grow: 1` — correct inside a *definite*-height container, a no-op
>   inside a content-sized popover. It collapsed to nothing and the menu opened as a search box above a
>   void.
> - Entries were centred and clipped at both ends because the row had no `flex-direction`, and Taffy's
>   default here is COLUMN, so `align-items: center` centred them *across* the row.
> - Making it resizable without `overflow: hidden` let the rows paint past the border — which this file
>   already says: *anything resizable should normally also set overflow*. Clipping it then needed a
>   `min-height`, or it could be dragged smaller than its own search box.
> - A fixed width clipped the longest labels; it now sizes to its widest row between a floor and a
>   ceiling.
>
> #### Two design corrections that came from the reference, not from a crash
>
> - **The port band is translucent, not absent.** Painting nothing let the canvas through as a hole; the
>   ask was "somewhat transparent" and the answer was an alpha, not a presence/absence choice.
> - **Stacking is interaction history, not selection state.** `graphnode:checked { z-index }` raised a
>   node only while selected, so deselecting dropped it back behind whatever was added after it.
>   `GraphView.raise` now assigns an ever-increasing z-index on press and leaves it there, which is what
>   every editor does.
>
> #### One test that was worse than no test
>
> The first regression guard for the collapsed list measured the *rows* — and a 13px row inside a
> 0-height parent still measures 13px, it is merely clipped. It passed with the bug in place. It was only
> caught by **re-introducing the bug to check the test failed**, which is now the habit worth keeping:
> counting elements proves they exist, not that anyone can see them, and anything whose failure mode is
> "renders as nothing" needs a measurement *of the thing that collapsed*.
>
> #### Deliberately not done
>
> A **category tree** — `NodeType.category` exists, search already matches it, and the paths are already
> hierarchical (`Input/Geometry`), so `TreeView` would slot straight in, flattening while a query is
> typed as Unity does. Six types fit a flat list; sixty will not. And a **draggable** menu: compatible
> with light dismiss (a press on its own title bar is a press *inside*), but it only pays off if the menu
> is something you keep open — which is a docked library panel, and 6.1.11's territory.

<details>
<summary>The plan, as researched — implemented as written</summary>

#### What the research settled

**1. The contextual menu filters by the dragged edge's type — and lists ports, not just nodes.** Unity
*"filters the available nodes and only shows those that use the Data Type of a selected edge, listing
every available Port on nodes that match"*. So an entry is a **(type, port) pair**, and picking one
creates the node *and* makes the connection in a single step. That is a materially better interaction
than "create a node, then wire it yourself", and it is a different data shape: the menu is built from
ports, not from types.

**2. Search matches names and synonyms.** Unity's box *"filters the listed nodes by name parts and
synonyms based on industry terms"* — so `Add` is findable by typing `plus`, and a shader author who
learned the word somewhere else still finds the node. Synonyms are a field on the type, not a search
feature bolted on later.

**3. Two triggers, both already reserved.** `Space` opens it at the cursor (Unity's shortcut reference),
and dropping a wire on empty canvas opens it filtered (the path 6.2.3 deliberately left open).

#### The registry is the missing piece three items need

`NodeTypeRegistry` is not only the menu's backing store. **The view migration needs it too** — a
document knows a `typeId`, and turning that into a `GraphNode` widget is exactly what a factory does —
and so does duplicate, which is why 6.2.4 had to defer. Building it here unblocks both.

The split follows the model/view line already drawn:

| Where | What |
|---|---|
| `com.crystalgui.graph.NodeType` | id, label, category, synonyms, port template, default properties. **Headless** — `create(x, y)` returns a `NodeData`, so a server can build a node without a widget in sight |
| `com.crystalgui.graph.NodeTypeRegistry` | id → type, plus the two queries the menu asks: text search, and "which (type, port) pairs accept a `vec3` output?" |
| `ui.elements.graph.NodeWidgetFactory` | typeId → `GraphNode`. The UI half, registered separately, so the model never learns what a widget is |

**A missing factory is not an error.** It falls back to a placeholder built from the document's stored
ports — the same mechanism that makes an unknown node type survive a load. A graph with no factories at
all still renders structurally, which is both the "mod not installed" case and the cheapest way to see a
document on screen.

#### Deliverables

- `NodeType`, `NodeTypeRegistry` with search and compatible-port filtering, tested in `headlessTest`.
- `NodeCreationMenu` — a `Popover` with a `TextField` and a filtered list, built from `Menu`/`MenuItem`.
- Both triggers, and create-and-connect as **one** undo transaction.
- The gallery's graph page gets a real library to add from.

#### Traps

1. **The menu must open where the wire was dropped**, not where the pointer ended up after the popup
   placed itself — `AnchoredPlacement` owns position, and nothing else may write `left`/`top`.
2. **Create-and-connect is one step.** Two undo presses to remove a node you just made is the same
   failure as forty presses to undo one drag.
3. **The filter asks the document's `TypeCompatibility`**, not equality — or a float output would offer
   nothing on a graph whose consumer promotes.

</details>

### 6.2.6b Category tree, wire polish, and the Unity fidelity pass · `DONE` (2026-07-31)

> **Shipped**: `NodeMenuTree` (headless, 11 tests) files the create menu's offers under their categories;
> the menu now drives a `TreeView` with a draggable "Create Node" header, keyboard, and search that
> flattens. Wire hover, additive node ring states, and a palette matched to Unity by pixel-sampling.

#### Four engine bugs, none of them in the graph

1. **`UIDragController` fired `onDragEnd` before `DragEvent.Drop`.** A wire dropped on a valid port
   connected *and* opened the create menu, because the port decides "did I land?" by comparing its
   connection count against a drag-start snapshot and always read it before the drop. Now drop-then-end,
   which is the web's order and the only one where that question is answerable.
2. **Keyboard activation was indistinguishable from a click.** Space/Enter synthesize a `MouseEvent.Down`
   so `Button` gets keyboard support free; `GraphView` must be focusable for its commands, so Enter
   started a marquee that could not be released (a marquee ends through the real pointer-up path).
   Synthesized clicks now carry `detail == 0`, the DOM's own signal.
3. **Wire picking measured distance to the sample points, not the segments between them.** Evenly-spaced
   dead zones along every long wire; 141 of 201 points on the curve were unpickable. Tolerance is now
   zoom-independent too.
4. **`GraphNode.setSelected` was public.** The gallery called it directly, so a node painted as selected
   while `GraphSelection` had never heard of it — and nothing could clear it, since `clearSilently()`
   only walks the set. Package-private now, as `Tab.setSelected` already was.

#### What the fidelity pass taught, and it is all one lesson

Matching a reference by **sampling pixels** rather than by eye found things no amount of looking would:
that the header and input panel are the same colour, that the preview matches the body, that Unity's
surface alpha is 0.824 (solvable from one surface over two backdrops). But the recurring trap was
compositing arithmetic — **a base colour is not the colour you see**, and changing the alpha silently
moves every colour unless the bases are re-solved. That went wrong twice, and the second time only
because the two port columns paint over the *band*, not the canvas: the backdrop of a translucent
surface is whatever is directly behind it.

Three geometry traps worth the same billing:

- **A separator cannot be drawn by either of the two things it separates** — padding a column paints the
  strip in the column's own colour, so it reads as a taller panel. Tried at 1px and 3px; both looked
  like no padding at all.
- **`UIText` decides once whether it self-sizes** (`contentBoxWidth() <= 0` on its first pass), so
  "give this text a minimum size" and "let the box grow to fit it" are mutually exclusive on one element.
  That is why the port floor sits on the row.
- **A virtualised row's geometry is written from Java at DEFAULT origin**, which every stylesheet origin
  beats — a `height` or `padding-left` rule does not adjust a `TreeView`, it breaks it.

#### Deliberately not done

Category **sorting by frequency**, and a preview seam matching the reference's fourth separator. The one
knowing divergence from Unity is surface alpha: `0x76` against their `0xD2`, so wires stay visible behind
a node.

### 6.2.7 Node previews · `MOVED to 6.3.7` (2026-07-31)

> **Moved rather than done, and the reason is ordering.** A preview is *a live render of the graph up to
> that node* — so it needs a graph **compiler** to exist, and until one does a preview can only be a
> placeholder. It also needs the one genuinely new GL capability in the area (per-node offscreen
> targets), which belongs with the rest of the CrystalGraphics work rather than stranded at the end of a
> widget track.
>
> **6.2 closes here**, 6.2.1–6.2.6 done. See `CrystalGUI_P6.3_SHADER_NODE_GRAPH_TODO.md`.

<details>
<summary>The original 6.2.7 plan</summary>

### 6.2.7 Node previews · **has an engine question in it**

The thumbnail in every reference screenshot. The widget half is trivial — a preview slot that paints a
`CgUiDrawable`, exactly like every other background in this engine. The real work is upstream: each
preview is *a live render of the graph up to that node*, which means an offscreen target per visible node
and a compile per node.

That is CrystalShader's problem, not CrystalGUI's, and the seam is already the right shape:
`CgUiPaintContext` owns a layer FBO pool, and a preview is a texture. **6.2.3 should ship the slot and
leave it empty** rather than inventing a preview pipeline the graph compiler will later replace.

</details>

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
6.1.9 command/undo (DONE) ────┘──────────────────────► 6.2.4  (now unblocked)

6.1.10 file SPI ──► 6.1.11 docking ──► 6.1.12 chrome

6.2.1 CgCurveRenderer ──► 6.2.2 canvas ──► 6.2.3 nodes/ports ──┬─► 6.2.4 editing ──► 6.2.5 model
      (done)              (done)             (done)         (done)      ▲
                                                               ├─► 6.2.6 node library (done)
                                                               └─► 6.2.7 previews (slot only;
6.1.9 command/undo ────────────────────────────────────────────────────┘   the pipeline is
                                                                            CrystalShader's)
```

**Recommended sequence:** ~~6.1.1~~ → ~~6.1.2~~ → ~~6.1.3~~ → ~~6.1.4~~ → ~~6.1.5~~ → ~~6.1.6~~ →
~~6.1.7~~ → ~~6.1.7b~~ (bar soft wrap and the keymap) → **6.1.8 (next)**, then 6.1.10-12, then the 6.2
chain. ~~6.1.9's *design* settled before 6.1.6 starts~~ — done, see 6.1.9; its implementation lands with
6.1.6.

~~6.2.1 is the one item that can be pulled forward at any time~~ — and it was, along with **6.2.2**, which
turned out to share the same property: the canvas depends on `transform`, the drag controller and the curve
renderer, none of which 6.1 touches. The 6.2 chain is now unblocked as far as **6.2.3**, which is the next
item that can run in parallel with 6.1's remaining work.

---

## Open questions

| Question | Blocks | Notes |
|---|---|---|
| Do the MC loader jars ship tree-sitter's natives, and for which platforms? | 6.1.7 step 5 | The fork has no aarch64-Windows build. The built-in lexer fallback makes this a degradation rather than a failure, but the answer decides how much of 6.1.7 is usable in-game. |
| ~~Are `CgShapedParagraph`'s style spans drivable without backend work?~~ | ~~6.1.1~~ | **Answered: yes, entirely.** The backend was already complete; 6.1.1 was a translation layer. |
| What *is* the filesystem in a Minecraft context — resource packs, world data, server storage? | 6.1.10 | Shape the SPI around what the client can be handed, not around POSIX. |
| ~~Do widgets mutate models directly, or emit commands?~~ | ~~6.1.9~~ | **Answered: both, split on document vs view state.** See 6.1.9. Settled while three widgets had chosen rather than a dozen, which is the whole reason it was the gate. |
| ~~Fixed-height rows only for the first virtualised pass?~~ | ~~6.1.3, 6.1.7~~ | **Answered: `VariableHeightStrategy` shipped with 6.1.7 step 1.** Soft wrap itself is still not implemented in the editor — unblocked rather than done. |
| ~~Does the code editor need multi-cursor?~~ | ~~6.1.7~~ | **Answered: yes, designed in from the start.** `TextEditor` holds caret/anchor as two ints and every movement method touches them, so retrofitting means rewriting all of them; `ChangeSet` already models a multi-cursor edit exactly. |
| ~~What should a node look like?~~ | ~~6.2.3~~ | **Answered: Unity Shader Graph's, literally.** Anatomy, the type→colour palette, and four behaviours the screenshots could not show are all recorded in 6.2.3. |
| How does a wire stay visible when zoomed out? | 6.2.3 | A pose-scaled stroke is sub-pixel at low zoom. Clamp in Java against the canvas's zoom, not in the shader. |
| Who owns selection — the node, or a selection model? | 6.2.3 / 6.2.4 | 6.2.4 decides, but 6.2.3 must not settle it by accident with a boolean field on `GraphNode`. |

---

## Changelog

- **2026-07-31** — **6.1.7b done: the editor's foundations, ported rather than invented.** CRLF, tabs,
  read-only, multi-cursor creation, the line operations, the typing aids, `Language`, and search's
  keyboard. Soft wrap and the keymap registration are deliberately left; see 6.1.7b §G and §H.
  - **The standing rule this established: do not reinvent what VS Code or Zed already got right.**
    `com.crystalgui.text.cursor` is a port of `vs/editor/common/cursor/` with per-class attribution,
    `WordClassifier` is `wordCharacterClassifier.ts`, `ChangeSet` is CodeMirror 6's. Zed was **read, not
    copied** — it is GPL-3.0 and copying it would impose GPL on this repo, so `Rope`/`TextSummary` take
    its `SumTree` *shape* and nothing else. VS Code, Monaco and CodeMirror are MIT and safe to port.
  - **Every rule ported is one line and invisible when wrong.** Auto-close fires on an *allowlist*
    (`;:.,=}])> \n\t`) not a denylist, so it still opens before `$foo` and `#define`. A plain arrow key
    collapses a selection to its edge regardless of which way the gesture went. A partly-commented block
    comments *out* rather than half-toggling. A backwards word-drag unions with the anchor word so the
    anchor stays whole. None of these are derivable, and all of them would have been got wrong.
  - **`TextEditor` hit 2556 lines — larger than the whole of `com.crystalgui.text` — because the ported
    algorithms went in as private methods.** Extracting them to `text/cursor/` (2237 lines, five classes,
    589) made them reachable without a `UIWindow`, fonts, a style engine or an input handler.
  - **The extraction paid for itself immediately.** The new headless suite's first run failed on
    `deletingTheLastLineTakesThePrecedingNewline`: the last row has no trailing newline to take, so it
    must swallow the one *before* it, and it did not. The widget test never saw it because it only ever
    deleted a middle line. The same suite pins the per-caret goal column, which needed *two* simulated key
    presses to expose through the widget and is one assertion as a direct call — the first vertical move
    behaves identically whether goals are shared or per-caret.
  - **Porting the algorithms without porting the module boundaries is only half the port.** The
    boundaries are what keeps the algorithms correct; VS Code has `cursorMoveOperations.ts` as its own
    file for the same reason.

- **2026-07-31** — **6.2.5's model and 6.2.6 done: the graph is a document, and nodes can be created
  from a library.** `com.crystalgui.graph` (17 tests) plus `NodeType`/`NodeTypeRegistry` (10) and the
  create menu (14).
  - **The manifesto's "no separate formats" had to be resolved before any code existed**: the user's
    file stays `.shader`, CrystalGUI ships a generic typed document because the settled scope is a
    general-purpose editor framework, and CrystalShader maps between them. The obligation is that the
    document must *express* what the compiler needs — stable ids usable as a GLSL prefix, type ids,
    typed ports — with no GLSL in `core/`.
  - **Ids are stored, unlike the UI tree's**, whose own docs record that inserting renumbers everything
    after it. For a graph that is fatal, because edges reference ids.
  - **Unknown node types survive a load**, ports and edges intact — a deliberate divergence from
    `ElementRegistry`, which throws on an unknown tag. Eating somebody's graph because a plugin is
    missing is far worse than showing a grey box, and the same mechanism gives the create menu its
    placeholder widgets.
  - **Eleven bugs in the menu, every one found by looking rather than by CI**, and they were two
    families: a promoted popup's input travelling through the canvas beneath it (press, wheel, and
    light dismiss, each discovered separately), and CSS idioms applied in the wrong container context —
    a zero flex basis where nothing could grow, a missing `flex-direction` against a COLUMN default, a
    resizable box with no `overflow`. The second family is predicted by notes already in this file.
  - **One regression test was worse than none.** It measured the rows rather than the container that
    collapsed, and passed with the bug in place; it was caught only by re-introducing the bug to check
    the test failed. Worth making a habit.

- **2026-07-31** — **6.1.9 done: the undo half, built from the 6.2 track.** `core/undo/` — `Edit`,
  `CompositeEdit`, `UndoStack` — with 20 tests in `headlessTest`, so the mechanism is provably free of
  GL and safe for a server that authors documents. Taken up here because 6.2.4 is blocked on it and the
  6.1 agent is deep in 6.1.6/6.1.7.
  - **The command half already existed** (6.1.2's `core/command/`), so the item was half done and looked
    untouched. An `Edit` is not a `Command`: a command is what the user asked for, an edit is what it did
    to the document, and one command may produce none, one, or several.
  - **Coalescing splits cleanly**: the stack owns the clock, the edit owns the intent. No edit type
    carries a timestamp, and the window matches `TextBuffer`'s 500ms so a user cannot tell which
    mechanism served their Ctrl+Z.
  - **`Long.MIN_VALUE` is not a valid "long ago" sentinel against `System.nanoTime()`.** The subtraction
    overflows, so "never merge again" evaluated as "merge immediately" and the whole session collapsed
    into one undo step. Caught by a test written to pin that a pause breaks a typing run — the failure
    would otherwise have been undo occasionally swallowing two actions, on some machines only.
  - **`TextBuffer` keeps its own stack for now**, deliberately: adopting this one is a small refactor
    for whoever owns 6.1.6, not something to do underneath them mid-rewrite.

- **2026-07-31** — **6.2.3 done: nodes, ports and wires, to Unity Shader Graph's spec.** 18 tests, a
  `crystalgui:graph` theme, and the gallery's canvas page rebuilt as the reference graph.
  - **The palette seam works**, which is the reusable part: `NodePort.typeColor()` reads the port dot's
    computed `border-color` back out of the cascade, so the curve renderer gets its ARGB int while the
    colours stay in a stylesheet. Adding a type is a `PortType` plus a CSS rule and no Java colour — and
    a `float -> vec3` promotion draws as a gradient for free, because the instance record already
    carried two colours.
  - **Every one of the six named traps was paid for in advance and none of them bit.** What shipped
    broken was the thing that was in the *trap* list and not the *deliverable* list: nodes could not be
    moved. A risk analysed carefully is still not a feature.
  - **A node is a safe drag source; the plane is not** — and the two look identical. A pan changes the
    plane's transform, and listener coordinates are converted through the source's transform; moving a
    node changes `left`/`top`, which are layout, and layout is not in the matrix.
  - **`FocusPolicy.CLICK` does not focus a composite**, because the handler focuses the exact element
    hit and a press on a node lands on its title bar. Other composites dodge it with
    `setHitTest(false)`, which is unavailable when the part is itself interactive. Recorded as an open
    engine question rather than fixed engine-wide mid-flight.
  - **The node paints no background** so the port band is transparent and a wire reads as plugged into
    its dot rather than stopping at the border. Each region paints itself; the title bar and preview
    carry the corner radii the root gave up.

- **2026-07-31** — **6.2.3–6.2.7 planned in full, and the node's appearance settled: Unity Shader
  Graph's, literally.** Grounded in three reference screenshots, Unity's own Port/Node/Data-Types
  documentation, and LDLib2's node toolkit.
  - **The palette is the load-bearing part of the look**, not the box: a colour per data type, applied
    to the port *and to every edge leaving it*, is what makes a dense graph readable without reading a
    label. It therefore lives in a `graph.css` theme — a hard-coded palette would also put GLSL's type
    system inside a general-purpose editor framework, which is the wrong module for it.
  - **Four behaviours came from the docs that the screenshots could not show**, each of which would have
    been expensive to discover late: one edge per input but many per output (so connecting to an
    occupied input is a *replace*, and the implicit disconnect must be the same command as a manual
    one); an unconnected input carries an inline editor of its own type; collapsing hides unconnected
    ports rather than only the body; and dropping an edge on empty canvas opens a filtered
    node-creation menu.
  - **Wires are one layer, not an element each** — a deliberate trade recorded as a trade: it buys one
    batched `ctx.curve()` flush and no Taffy node per edge, and it costs per-wire CSS state, which has
    to be re-implemented as data.
  - **Two items split out rather than smuggled in**: the node library (6.2.6) and previews (6.2.7). The
    preview thumbnail is in every screenshot, but it is a live render of the graph up to that node —
    CrystalShader's pipeline, not a widget. 6.2.3 ships the slot empty.
  - **Six traps named in advance**, five of them already paid for elsewhere in this project: live
    anchors from the transform chain, never rebuilding what is under a drag, deltas measured in the
    viewport, the wire layer declaring the region it paints, and a hit target bigger than its dot.

- **2026-07-31** — **6.2.2 pan/zoom canvas done, built in parallel with 6.1.6.** `CanvasView` +
  `WorldRect`, 18 tests, and a **canvas page in `cgui-gallery`** whose wires are drawn with 6.2.1's
  `ctx.curve()` from inside the transformed plane. New widgets land as gallery pages, not as their own
  scenes — a scene nobody remembers to run is coverage nobody has.
  - **The design's premise held**: zoom is one CSS `transform` on one internal child, and because
    `UITransform.applyTo` is shared by the render pose and the hit-test chain, clicks follow the picture
    with no widget code. The load-bearing test is the one that takes a world coordinate through the
    widget's own conversion and asks the *engine's* hit-tester what is under the resulting pixel.
  - **An engine bug fell out of it: a drag only ever ended on button 0.** Every drag in the engine so
    far is left-button, so nothing had exercised it. A middle-drag pan started, panned, and then never
    stopped — the release told nobody while pointer capture was released anyway, leaving a live drag
    eating mouse movement with no button held. `startDrag` now takes the button.
  - **Cull by skipping paint, not layout.** `display: none` collapses the very layout rect the cull
    decision reads, so a culled node can only come back via a cache that goes stale whenever something
    moves. `opacity: 0` keeps the decision self-correcting and costs no relayout at the viewport edge,
    which is where panning spends all its time.
  - **`transform-origin` had to be pinned**, because its 50% default breaks every conversion *without
    breaking the picture* — the canvas stays internally consistent and only the world↔screen mapping is
    wrong, which is the shape of bug that survives a demo.
  - **The bare left-drag was left alone on purpose**, reserved for 6.2.4's marquee rather than spent on
    a second way to pan.

- **2026-07-31** — **6.2.1 `CgCurveRenderer` done, pulled forward and built in parallel with 6.1.2.**
  Entirely inside CrystalGraphics, so it shared no file with the 6.1 work except this one.
  - **The design survived contact intact** — quadratic-as-primitive, the instance schema, and all four
    supporting pieces landed as sketched. What the sketch missed were consequences, not choices.
  - **The degenerate case turned out to be the common case.** A straight line is a quadratic whose
    control point is the midpoint, which is precisely the input that makes the analytic Bézier SDF
    divide by zero. Every `line()` call hits it. Guarded with a segment fallback; unguarded it is NaN,
    which draws as nothing or as garbage and logs neither.
  - **A curve needs its instance data in the fragment stage**, unlike a quad — the stroke is a
    per-pixel SDF. The `.shader` v2f DSL offers no `flat` qualifier, so the fragment re-reads the
    buffer through `CG_INSTANCE_ID`. No compiler change was needed; `appendAttachedBuffers` already
    emits into both stages. Verified by reading the emitter rather than by running a driver.
  - **Pure maths on a class holding a static GPU buffer is unreachable headlessly.** Ten unit tests
    failed identically with `ExceptionInInitializerError` because calling *any* static method on
    `CgCurveRenderer` initializes its `CgShaderBuffer`. Split into `CgCurveSplitter`. This is the same
    hazard `CgEngineBufferRegistry`'s method-reference seeding exists to avoid, arrived at from the
    opposite direction — worth expecting the next time a renderer grows a helper.
  - **Dash was cut rather than reserved.** It needs arc length, nothing consumes it, and a field that
    is carried faithfully and then ignored is the exact failure `CgStyleSpan`'s javadoc records.
  - **`curve.shader` ships in CrystalGraphics**, not the harness — because the stage-purity test and
    the compile audit only walk shipped shaders, so shipping it is what makes the AMD vertex-stage
    trap a caught regression rather than a documented hope.

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
