# plan_workbench_rewrite.md — the shell, the product, and how anything attaches to either

**Status:** audit complete, not started.
**Measured against** `rewrite` @ `b26f0249`, 2026-09-04.

---

## 0. What this is about

Three questions, and they turn out to be one:

1. Should `ScriptWorkbench` exist? (No.)
2. Is `Workbench` production grade? (No — 3,378 lines, 83 public members, no sections.)
3. What is `CrystalEditor` for? (Nobody can say, including the code.)

They are one question because **there are three incompatible ways for a feature to attach to a
workbench**, and each of the three classes above is one of them. Fixing the seam is what makes the
other two answerable.

---

## 1. The measurements

### 1.1 `Workbench`

| | |
|---|---|
| lines | **3,378** — 2.7× the next largest file in the package, 12.6% of it |
| imports | 116 |
| public members | **83** (65 methods, 14 constants/fields, 3 signals, 1 `DataKey`) |
| private members | 89 (25 fields, 64 methods) |
| section headers (`// ──`) | **0** |

Zero section dividers in 3,378 lines is the tell. Every other large class in this repo is divided
(`UINode`, `TextEditor`, `CgUiPaintContext`); this one grew by accretion, and its comments are
incident reports rather than structure: 27 lines in it narrate a specific past bug (`used to`, `it was:`, `cost a`, `presented as`).

**Thirteen responsibilities**, taken from the public surface:

| # | Concern | Members |
|---|---|---|
| 1 | Element identity + CSS classes | 6 |
| 2 | Built-in panel type ids | 5 |
| 3 | Sub-widget accessors | 16 |
| 4 | `DataProvider` | 2 |
| 5 | Panel visibility | 8 |
| 6 | Tool windows | 2 |
| 7 | Opening things | 10 |
| 8 | Editor-kind binding | 6 |
| 9 | "What is active" queries | 9 |
| 10 | Saving / dirtiness | 6 |
| 11 | fs facades (`files`/`documents`/`editors`/`workspace`) | 4 |
| 12 | Signals | 3 |
| 13 | Loose setters (`setQuickOpen`, `setJobScheduler`, `setAutoReveal`, `markers`) | 4 |

### 1.2 The package around it

```
  5072 lines   6 files  workbench/(root)        <- 3378 of these are one class
  3455 lines   9 files  workbench/explorer
  2577 lines   4 files  workbench/dock
  1501 lines   5 files  workbench/toolwindow
  1394 lines   8 files  workbench/dock/layout
  1272 lines   7 files  workbench/region
  ... 17 more sub-packages ...
 26769 lines  89 files  TOTAL
```

The sub-packages are fine. **The root is the problem.**

### 1.3 Who depends on the 83 members

| module | calls | distinct methods |
|---|---|---|
| `core` | 176 | 38 |
| `language` | 22 | **11** |
| `gl-debug-harness` | 10 | 8 |
| `mc1710` | 3 | 3 |

The interesting row is `language`: the largest external consumer in the tree needs **eleven** methods
of eighty-three — `activeEditor`, `openPaths`, `openFile`, `documents`, `editorFor`, `activeFilePath`,
`toolWindowManager`, `panels`, `fileTree`, `isPanelOpen`, `showPanel`. That set is the shape of the
seam that should exist.

---

## 2. Three ways to attach a feature, and the drift they have already caused

**(a) Baked into the shell.** `Workbench` itself registers `PROJECT_TYPE`, `PROBLEMS_TYPE`,
`NOTIFICATIONS_TYPE`, constructs `ProjectFileTree`, `ProblemsPanel`, `ProjectIndex`,
`WorkspaceDocuments`, `EditorService` and wires eight signals between them — in the constructor.

**(b) Baked into the product.** `CrystalEditor` calls `ShaderGraphContribution.register(workbench)`
and registers the Inspector panel, in *its* constructor.

**(c) Installed by each host, by hand.** `ScriptWorkbench.install(registry, workbench, cacheRoot)` and
`NotesKind.register(kinds)`, called separately by every host that wants them.

Mechanism (c) has already drifted, exactly as an unenforced convention does:

| | `mc1710` | harness `cgui-desktop` | harness `cgui-dock` |
|---|---|---|---|
| Notes kind | ✗ | ✓ | ✓ |
| Scripting | ✓ | ✓ | ✗ |
| Reveal Run panel on start | ✓ | ✗ | ✗ |
| Script cache root | `config/crystalgui/script-cache` | `build/script-cache` | — |

Three hosts, three different products, none of it decided on purpose. A fourth host gets whatever its
author remembers to copy.

**Contrast:** the codebase already has ~20 process-wide contribution registries that work
(`ContentProviders`, `WidgetContracts`, `StylePropertyRegistry`, `TypeSearchRegistry`,
`ProjectSourcesRegistry`, `PortTypeRegistry`, …). There is no per-workbench equivalent, which is
precisely the gap (c) fills by hand.

---

## 3. `ScriptWorkbench` — why it is the wrong seam

It is 567 lines doing **five unrelated jobs**:

| Job | Lines | Belongs to |
|---|---|---|
| Register commands, console, panel, indicator; connect 6 signals | ~90 | a contribution |
| Compile orchestration — snapshot on the frame thread, compile on `JobScheduler` | ~150 | a launcher |
| Focus policy — `showConsole`, `inputWanted`, restore the caret | ~60 | a presenter |
| Error formatting — `refuse`, `report`, `refusalIn` | ~120 | a reporter |
| Accessors + `close()` | ~40 | — |

Beyond the sprawl, five concrete defects:

1. **`close()` is never called.** It unregisters three command sets and closes `ScriptRuntimes` — which
   holds the engine band, its classloaders and the compile cache. Every host leaks it. No
   `.close()` on it exists anywhere in `mc1710` or the harness.
2. **Two compile paths.** `compileFor`/`compileActive`/`compile` (sync) and
   `snapshotFor`/`compileForAsync`/`finish` (async) re-implement the same four refusals in the same
   order. The sync path is documented as the one that *cannot work* on a real host, and is still there.
3. **`install` returns null to mean "unavailable"**, so every caller must remember the null check; two
   of three hosts write a different comment explaining why.
4. **It reaches `Workbench` for 11 methods** it should be handed instead — and takes `CommandRegistry`
   as a *separate* argument, though the workbench could answer it.
5. **The cache root is a per-host `Path`.** The host already hands over a private directory
   (`useConfig`); scripting takes a second one, and the two hosts disagree about where it goes.

**It is also not a "workbench".** The name says it owns something; it owns a wiring call.

---

## 4. `CrystalEditor` — the limbo

Its javadoc states the intended line clearly:

> A `Workbench` is the **shell**: a dock, a file tree, editors, a Problems panel. This is the
> **product** built on it.

That line does not hold in either direction:

- the *shell* hard-codes four panel types, the explorer, the Problems panel and the project index;
- the *product* hard-codes the shader graph and the Inspector;
- and scripting — which is neither — is bolted on by the host.

What hosts actually use it for (`mc1710`, all 8 calls):

```
new CrystalEditor(workspace)   editor.useConfig(config)   editor.addClass(...)
editor.workbench()  ×4         editor.restoreSession(id)  editor.giveInitialFocus()
```

**Half the calls are `.workbench()`** — the host reaching *through* the product to get at the shell.
Its own surface is 23 members, of which the load-bearing ones are config, session, layout and focus.

And the teardown story is empty end to end:

| | |
|---|---|
| `CrystalEditor.dispose()` | body is a comment |
| `Workbench` | not `Disposable` at all |
| `EditorService.dispose()` | **no caller anywhere** |
| `ScriptWorkbench.close()` | **no caller anywhere** |

---

## 5. The proposal

### 5.1 One seam

```java
public interface WorkbenchContribution {
    void contribute(WorkbenchContext into);
    default void dispose() {}
}

public final class WorkbenchContributions {          // process-wide, like ContentProviders
    public static Disposable contribute(WorkbenchContribution c);
    public static List<WorkbenchContribution> all();
}
```

`Workbench` drains the registry at construction and disposes what it drained when it goes. A host
stops calling `install(...)` at all; a module registers itself once, from its own `register()`, and
**every** workbench gets it — which is what makes the drift table in §2 impossible rather than merely
discouraged.

### 5.2 The narrow context

`WorkbenchContext` is an interface `Workbench` implements, carrying roughly the eleven methods
`language` uses plus what the built-ins need — commands, panels, kinds, editors, documents, workspace,
tool windows, `showPanel`, `activeEditor`, `activeFilePath`, `openFile`, `openPaths`, and
`cacheDirectory(name)` (empty on a host with no private directory, which is what makes the script cache
one decision instead of three).

A contribution is written against `WorkbenchContext` and **may not name `Workbench`** — enforceable by
`LayeringTest`, which already does exactly this kind of scan.

### 5.3 Everything becomes a contribution

Explorer, Problems, Notifications, shader graph, Inspector, Notes, scripting — all of them. Which ones
an application enables is then one list in one place, which is what `CrystalEditor` is *for*:

```java
CrystalEditor.of(workspace)
    .with(ExplorerContribution.INSTANCE)
    .with(ProblemsContribution.INSTANCE)
    .with(ShaderGraphContribution.INSTANCE)
    .with(ScriptContribution.INSTANCE)
    .useConfig(storage);
```

That is the sentence the class javadoc already promises and cannot currently keep.

### 5.4 Ledger — `ScriptWorkbench`

| Today | Lines | Becomes | Est. |
|---|---|---|---|
| `ScriptWorkbench` | 567 | **deleted** | — |
| — | | `ScriptContribution implements WorkbenchContribution` | ~120 |
| — | | `ScriptLauncher` (one compile path, snapshot + `JobScheduler`) | ~150 |
| — | | `RunReporting` (refuse / report / refusalIn) | ~120 |
| — | | `RunConsolePresenter` (showConsole, inputWanted, caret restore) | ~70 |

Net ≈ **−110 lines**, one compile path instead of two, and a `dispose()` that is actually called.
`RunPanel`, `RunPanels`, `RunRail`, `RunConsoleView`, `RunIndicators`, `MappingCommands`,
`RunDecorations`, `TailFollow` (2,906 lines) are **unchanged** — they are fine.

### 5.5 Ledger — `Workbench`

3,378 lines → a shell plus five extracted collaborators. Sketch, to be re-measured with
`tools/port/pricesplit.py` before committing to it (§6):

| Extracted | What moves | Est. |
|---|---|---|
| `WorkbenchOpener` | the 10 opening methods + `refFor`/`refForResource` | ~400 |
| `WorkbenchActive` | the 9 "what is active" queries + dirtiness/save (6) | ~350 |
| `WorkbenchPanels` | panel visibility (8) + built-in panel registration | ~300 |
| `WorkbenchPresence` | presence status entry, problem count, decorations sync | ~250 |
| `WorkbenchProjectIndex` | index construction + crawl bookkeeping | ~300 |
| `Workbench` (kept) | element, context, services, contribution draining | ~800 |

**The price is measured, not guessed.** §6 of `plan_m6.md` records that three separate attempts to
count a split from source were wrong — by a factor of two in one direction and 285-vs-78 in the other —
and that the only honest instrument is a scratch tree plus `javac`. Same rule here.

### 5.6 Ledger — `CrystalEditor`

Keeps: config, session, layout, focus, status flattening, `WindowChrome`, the contribution list.
Loses: `ShaderGraphContribution.register` (becomes a listed contribution), the Inspector panel
registration (same), and — if hosts stop needing it — the `.workbench()` pass-through.

---

## 6. Phasing

| Step | Contents | Risk |
|---|---|---|
| **W0** | `WorkbenchContext` extracted from today's `Workbench` as an interface it implements. No behaviour change. `LayeringTest` case forbidding contributions naming `Workbench`. | low |
| **W1** | `WorkbenchContribution` + `WorkbenchContributions`, drained at construction and disposed. Port **Notes** first — smallest, and it is the one that proves a host stops calling anything. | low |
| **W2** | `ScriptWorkbench` → the four classes in §5.4. Delete the sync compile path. Wire `dispose`. | medium |
| **W3** | Shader graph + Inspector become listed contributions; `CrystalEditor` becomes the list. | medium |
| **W4** | Explorer / Problems / Notifications out of `Workbench`'s constructor. | medium |
| **W5** | The `Workbench` split of §5.5, priced first. | high |

W0–W2 answer the question that was asked. W3–W5 are the rewrite.

---

## 7. Two hazards to check before writing code

1. **`language/` is checked out in another worktree** (`X:/projects/CrystalGUI-language`, branch
   `language-stack`). As of `57970910` that branch has touched **nothing** under
   `language/src/main/java/com/crystalgui/language/run` — so W2 is currently free of conflict. Re-check
   immediately before starting it; never enter that directory.
2. **`CrystalEditor` cannot be constructed in a test.** It installs into process-wide registries and
   retains itself through a static `Notifications` listener it never disconnects — four instances in
   one JUnit worker exhausted the heap and shifted the colours `ConfigKitTest` asserts (see
   `HotExitIsWiredTest`'s javadoc). Anything in W3 that wants coverage needs either that leak fixed
   first or a constant-pool scan.
