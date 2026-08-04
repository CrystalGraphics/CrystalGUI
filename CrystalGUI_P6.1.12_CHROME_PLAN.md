# P6.1.12 — Chrome

> **Status: brainstorming. Nothing here is decided.** This document exists to work out what "chrome"
> should mean for *this* application before any of it is built. The roadmap line it replaces —
> *"Toolbar, status bar, breadcrumbs, command palette"* — is questioned below rather than assumed.

---

## Contents

- [What "chrome" means](#what-chrome-means)
- [The constraint that reframes everything](#the-constraint-that-reframes-everything)
- [IntelliJ, classified](#intellij-classified)
- [The finding — the roadmap line lists the decorative half](#the-finding--the-roadmap-line-lists-the-decorative-half)
- [What chrome is actually for here](#what-chrome-is-actually-for-here)
- [Candidates, ranked](#candidates-ranked)
- [What makes IntelliJ *look* like IntelliJ](#what-makes-intellij-look-like-intellij)
- [Open questions](#open-questions)

---

## What "chrome" means

Browser term. Firefox's UI layer is literally the "chrome" — everything that is not the page. VS Code
calls the same thing the *workbench*; IntelliJ calls the pieces *tool windows*, the *navigation bar* and
the *status bar*. It is the frame around the content: always present, never the thing you came for, and
the whole difference between a text box and a tool.

The dock (6.1.11) is already the largest piece of it. 6.1.12 is what is left.

---

## The constraint that reframes everything

> *"its not really a full blown IDE just a script engine editor for js nashorn and java janino scripts
> or css stylesheets or stuff of that sort. Theres no processes or daemons that run, no gradle builds or
> debugging processes, its all part of the games runtime."*

That is not a minor scoping note — it deletes roughly half of IntelliJ's chrome outright, and it
**transforms** several of the remaining pieces into something with the same shape and a different
meaning. An editor whose artifacts are live objects in a running host is a different machine from one
that spawns compilers and attaches debuggers.

Three consequences worth stating before the inventory:

1. **There is no "run".** A script is not launched; it is *recompiled and swapped into a process that is
   already running*. The nearest IntelliJ analogue is not the green play button — it is **Apply Code
   Changes** (hot reload), which is a much less prominent button doing a much more important job here.
2. **There is no build output, but there is absolutely compiler output.** GLSL fails to compile, Janino
   throws at compile time, Nashorn throws at parse time, a stylesheet has a bad declaration. Those
   messages have to go *somewhere* and right now there is nowhere for them to go. The screenshot
   provided shows exactly this case in IntelliJ's console:
   `0(278) : error C1503: undefined variable "cg_ShadowParams"`.
3. **The host is remote.** The workspace (6.1.10) is server-hosted over RPC even in singleplayer. So
   "connected / disconnected / saving" is real state a user needs to see — a status concern that a
   local-disk IDE does not have and therefore does not model.

---

## IntelliJ, classified

Walking the provided screenshot top to bottom, and marking each piece **KEEP** / **TRANSFORM** / **DROP**
against the constraint above.

### Top bar

| Piece | Verdict | Reasoning |
|---|---|---|
| Hamburger → main menu | **KEEP** | The discoverable home for every command. Cheap: `Menu`/`MenuItem` exist |
| Project switcher | **KEEP (thin)** | 6.1.10 has a project identity (D12). One dropdown |
| VCS branch widget | **DROP** | No git in the game runtime |
| Run configuration selector | **TRANSFORM** | Becomes *what am I editing against* — the target entity/material/scene a script is bound to. See [open questions](#open-questions) |
| Run / Debug / Stop | **TRANSFORM → one button** | Collapses to **Apply**. No process to stop, nothing to attach to |
| Search Everywhere (magnifier) | **KEEP** | The command palette, plus file/symbol search |
| Settings gear | **KEEP** | 6.1.8's configurator already is this |

### Left edge — tool window stripe

| Piece | Verdict | Reasoning |
|---|---|---|
| Icon rail, click to toggle a tool window | **KEEP — and it is already scoped** | This is 6.1.11's deferred **D9** (stripe rails + auto-hide). The 6.1.11 open questions flagged it as *possibly load-bearing rather than a nicety* because screen space in Minecraft is scarcer than on a desktop. That guess now looks correct |

### Editor area

| Piece | Verdict | Reasoning |
|---|---|---|
| Tab bar, splits | **DONE** | 6.1.11 |
| Gutter — line numbers, fold arrows | **DONE** | 6.1.6 / 6.1.7b |
| Gutter — breakpoints | **DROP** | No debugger |
| **Inspection widget** (top-right, `✓ 282` / `⚠ 1`, next/prev arrows) | **KEEP — high value** | Nothing like it exists |
| **Error stripe** in the scrollbar | **KEEP — high value** | Every problem in a 3000-line file, visible without scrolling. Arguably IntelliJ's single best affordance |
| Inline squiggles | **KEEP — high value** | 6.1.1's `::highlight()` is *exactly* the mechanism, already shipped and unused for this |
| Sticky lines / breadcrumbs in editor | **KEEP (later)** | Needs a symbol provider; see the grammar question |

### Right and bottom panels

| Piece | Verdict | Reasoning |
|---|---|---|
| Gradle tool window | **DROP** | No build system |
| Debug tool window — frames, threads, variables | **DROP** | No debugger |
| Debug tool window — **the console pane** | **TRANSFORM → Output panel** | Host logs, compile output, `print()` from a script. The one part of the debugger surface that survives, and it survives as the most-used panel in the app |

### Status bar

| Piece | Verdict | Reasoning |
|---|---|---|
| Navigation bar (`CrystalGUI > gl-debug-harness > .settings`) | **KEEP** | Cheap. File-path segments are free today |
| Caret `133:52` | **KEEP** | `TextEditor` already knows this |
| `LF`, `UTF-8` | **KEEP** | 6.1.10 resolved both (G1, G2) and currently shows neither |
| Memory / background-task progress | **TRANSFORM (thin)** | No background *tasks*, but FS operations are async RPC — "saving…", "reconnecting…" is real |
| Notification bell / event log | **KEEP** | Where an error goes when no panel is open |

---

## The finding — the roadmap line lists the decorative half

The roadmap says: *toolbar, status bar, breadcrumbs, command palette*.

Every one of those is **orientation** chrome — it tells you where you are and how to reach a command.
Not one of them is **feedback** chrome — none of them tells you whether the thing you just wrote works.

That omission is an artifact of when the line was written. 6.1.12 was drafted before there was an editor
to *have* problems in and before the workspace existed to *load* anything. Now that both exist, the gap
is visible: **there is no `Diagnostic` type anywhere in the codebase.** Verified — nothing under
`com.crystalgui` models a severity, a problem range, or a compiler message.

So the honest version of this item is larger than the roadmap line and differently weighted. That is the
main thing this document is for.

---

## What chrome is actually for here

One loop, and everything should be ranked by how much it shortens or clarifies it:

```
        ┌──────────────────────────────────────────┐
        ▼                                          │
    edit  ──►  apply  ──►  the host does something │
                              │                    │
                              ├── it worked ───────┤
                              └── it failed ───────┘
                                    │
                              WHERE DO I SEE WHY?
```

The last box is currently unanswered, and it is where nearly all the value is. A person writing GLSL
inside Minecraft does not need a VCS widget; they need to know that line 278 references a uniform that
does not exist, and they need to get to line 278 in one click.

Everything else — palette, breadcrumbs, menus — is genuinely useful and genuinely secondary.

---

## Candidates, ranked

Value is judged against the loop above. Cost is judged against what already exists in the repo.

### Tier 1 — the feedback loop

| # | Item | Value | Cost | Notes |
|---|---|---|---|---|
| C1 | **`Diagnostic` model** — severity, range, message, source, code | ★★★★★ | Low | A record plus a per-document registry. Everything below depends on it. **Not editor-specific**: a shader-graph node can carry one too |
| C2 | **Inline squiggles** | ★★★★★ | Low | `::highlight()` (6.1.1) is the mechanism and it already ships. Styling lives in CSS, ranges in Java — exactly what that API was built for |
| C3 | **Error stripe in the scrollbar** | ★★★★★ | Medium | A new `EditorViewPart`. Needs a model→stripe projection that survives folding (`ProjectedLines` already solves the equivalent problem for view lines) |
| C4 | **Inspection widget** — count + next/prev | ★★★★☆ | Low | Two commands and a small readout. Rides on C1 |
| C5 | **Output / console panel** | ★★★★★ | Medium | A dock panel over an append-only buffer. Wants ANSI-free styled runs, click-to-navigate on `file:line`, and a cap on retained lines |
| C6 | **Problems panel** | ★★★★☆ | Low–Medium | `TableView` (6.1.5) over the diagnostic set, grouped by file. Mostly wiring once C1 exists |
| C7 | **Apply action + result feedback** | ★★★★★ | ? | The single most important button in the app. Cost unknown — depends entirely on what the host exposes. **See open questions** |

### Tier 2 — orientation

| # | Item | Value | Cost | Notes |
|---|---|---|---|---|
| C8 | **Status bar** with a contribution API | ★★★★☆ | Low | Alignment + priority, like VS Code's `StatusBarItem`. Without a contribution API every feature that wants a segment has to reach into the layout |
| C9 | **Command palette** | ★★★★☆ | **Very low** | Every part exists: `SearchMatcher` (ported from VS Code `filters.ts`), `SearchField`, `ListView`, `Popover`, `Keymap.acceleratorsFrom`, `::highlight()`. This is assembly, not construction |
| C10 | **Navigation bar / breadcrumbs** | ★★★☆☆ | Low (paths) / Blocked (symbols) | File-path segments are free. The symbol tail needs a grammar — 6.1.7's open question |
| C11 | **Main menu** | ★★★☆☆ | Low | `Menu`/`MenuItem` exist. Discoverability for everything the palette hides |

### Tier 3 — space and structure

| # | Item | Value | Cost | Notes |
|---|---|---|---|---|
| C12 | **Tool window stripes + auto-hide** (6.1.11 D9) | ★★★★☆ | Medium | Screen space in-game is scarce. This may matter more here than it does in IntelliJ |
| C13 | **Toolbar** with overflow-to-menu | ★★★☆☆ | Medium | The overflow measurement pass is the only non-trivial part. Could ship without it |
| C14 | **Notifications / toasts** | ★★★☆☆ | Low | Where a failure goes when the Problems panel is closed |

### Tier 4 — deliberately noted as *not* now

Recently-used ordering in the palette (needs a preference store), symbol breadcrumbs (needs the grammar),
multi-row tab wrapping, floating tool windows.

---

## What makes IntelliJ *look* like IntelliJ

Worth separating, because the stated preference is about **looks**, and almost none of what produces that
look is a missing widget. It is restraint plus one repeated idiom.

1. **The tool-window frame.** Every panel is: a thin header bar with a title on the left, small action
   icons on the right, and a 1px separator — then content. Same frame for Project, Gradle, Debug. That
   single repeated shape is most of the visual identity, and it is a CSS rule over an element we already
   have.
2. **The editor dominates; everything else recedes.** Panels are one or two shades off the editor
   background, never bordered heavily, never accented. Contrast is spent on *code*, not on frames.
3. **One accent colour, used sparingly** — selection, the active tab underline, focus. Not on buttons,
   not on headers.
4. **Content density.** Small type, tight row heights, thin scrollbars. The status bar is ~22px and quiet.
5. **Icons carry the rails; text carries the content.** The left stripe is icon-only with tooltips.

None of that needs new engine capability. It is a stylesheet — an `ide.css` sitting beside `ore.css` and
`graph.css` — plus the discipline to keep pixel values out of Java, which the widget conventions already
enforce.

> **The one real widget gap for the look** is the tool-window header frame, and it is small: a titled,
> collapsible container with an action slot. Everything else is theming.

---

## Open questions

These change what gets built, so they are worth answering before anything is.

**Q1 — What does "Apply" actually call?**
The highest-value item (C7) is entirely shaped by this. Is there a host-side entry point that takes a
script/stylesheet/shader source and returns success-or-diagnostics? Is it synchronous, or async over the
same RPC as 6.1.10? Does it apply one file or the whole workspace? Without this the Apply button is a
placeholder.

**Q2 — Where do diagnostics come from, and in what format?**
GLSL comes back from the driver as `0(278) : error C1503: …`. Janino and Nashorn throw Java exceptions
with line info. A stylesheet warning currently just goes to `CrystalGuiCore.LOGGER`. Do these get
normalised server-side into one shape, or does the client parse each? **The `Diagnostic` record's fields
are a one-way door** — worth settling before C1 is written.

**Q3 — Is there an "active target"?**
IntelliJ's run-configuration slot is prime real estate in the top bar. Its analogue here would be *what
this script is attached to* — an entity, a material, a scene. If that concept exists, it belongs there.
If it does not, the top bar gets simpler and shorter.

**Q4 — One document concept or several?**
A `.glsl` file, a `.css` file, a Janino script and a shader **graph** all want to open as tabs in the same
dock. The dock already takes an opaque `typeId` + state, so it does not care — but the status bar,
breadcrumbs and Problems panel all want to ask a document "what are you?" Does a `Document` abstraction
appear here, or does each panel type answer for itself?

**Q5 — How much screen is this?**
Fullscreen-ish, or a windowed panel inside the game? It decides whether C12 (auto-hide stripes) is
essential or optional, and it decides the density targets for the theme.

**Q6 — Is the console one stream or several?**
Host log, script `print()`, and compile output are three different things. IntelliJ gives each a tab.
One merged stream is simpler and much worse to read once anything is noisy.

---

## Nothing is decided

The tiers above are a proposal to argue with, not an order to execute. The two things this document
asserts rather than proposes:

- **The roadmap line is incomplete** — it lists orientation chrome and omits feedback chrome entirely,
  and feedback chrome is where the value is.
- **The palette is nearly free** — every component exists, which makes it a good early win regardless of
  how the rest is prioritised.
