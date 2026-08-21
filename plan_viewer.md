# The Library Viewer — Ctrl+Click Into Code You Don't Own

*The plan for IntelliJ's viewer: Ctrl+B / Ctrl+Click on `ArrayList` opens a read-only editor tab
showing the class — its attached source when one exists (`src.zip`, a `-sources.jar`, bundled
`assets/<ns>/sources/`), decompiled bytecode when none does. Sibling of `plan_javadoc.md` and
`plan_syntax.md`; scoped to navigation into non-project code.*

*Reference target: IntelliJ's viewer (`EditorFactory.createViewer`, `EditorKind.VIEWER`) and its
decompiled-class view (Fernflower + the "Decompiled .class file" banner). VS Code's
`TextDocumentContentProvider` is the model for the seam, deliberately — a scheme names a provider,
and the workbench never learns what any scheme means.*

---

## 0. What exists already — the feature is mostly substrate

Verified against the tree, not recalled:

| Piece | Where | State |
|---|---|---|
| Read-only editor | `TextEditor.setReadOnly` / `isReadOnly` | Guards at `applyEdit`, `applyEditKeepingSelection`, `typeCharacter`, `moveLines`; `applyCodeAction` checks it; cut/paste `enabledWhen` reads it |
| Ctrl+B / Ctrl+Click | `EditorLanguageFeatures.goToDefinition()` | Splits same-document (jumps in place) from cross-document (`onDefinitionChosen`) |
| Cross-file jump target | `DeclarationSite(resource, start, end)` | Carries a `Resource`; null resource = this document |
| Scheme'd resources | `Resource` (`com.crystalgui.fs`) | VS Code's URI model; `project`, `untitled`, `output` exist; `library://java.util.List` parses today |
| Attached-source reading | `SourceArchives` / `AttachedSources` | Finds and reads `src.zip`, `-sources.jar`, Gradle siblings, bundled `assets/<ns>/sources/`; caches per classpath |
| Binding→declaration matching | `AttachedSources` | Matches across two parses by **binding key** — the exact machinery a cross-file site needs |
| Live class bytes | `TypeBytes` (engine bridge) | `readable(internalName)` — the runtime's own bytes, **remapped to readable names**, including classes that exist only because a mixin produced them |
| JS inherits for free | `RhinoResolution` | Already takes `described.declaration()` off the interop probe — the moment the Java engine produces library sites, JS Ctrl+Click gets them with no JS-side change |

What does **not** exist: any way for the engine to say "declared in a file you don't have open",
any way for the workbench to open a document that is not a `CgPath`, and any decompiler anywhere in
the repository.

---

## 1. The critical review — what the first sketch got wrong

The first sketch said "the workbench stops dropping non-project resources at line 476, and opens
them in a read-only tab". Reading the code, that sentence hides four real problems and the sketch
missed three more. Numbered so the milestones can cite them.

### 1.1 The workbench's open pipeline is `CgPath`-keyed END TO END — not just at one early return

`OpenDocuments.byPath` is `Map<CgPath, Entry>`. `refFor(CgPath)` builds the panel ref.
`client.read(CgPath, …)` does the IO. `activeFilePath()` parses `PATH_STATE` back into a `CgPath`.
Save, rename, decorations, recent files, and the close guard all speak `CgPath`. A `library://`
resource has no `CgPath`, so **there is no small patch** — either the whole pipeline generalises to
`Resource` (the VS Code / VirtualFile shape, and a large refactor touching save/rename/persistence),
or viewer documents get a **parallel lane**: their own panel type, their own `Resource`-keyed map,
no save, no rename, no decoration, no recent-files entry.

**Decision: the parallel lane.** Not because generalising is wrong — it is where this ends up
eventually — but because a viewer document genuinely has none of the obligations the path pipeline
exists to meet. It cannot be saved, renamed, decorated, or created; folding the two together buys
generality nobody consumes and puts `library://` strings through code that was written assuming a
file. The lane is small: a map, a panel type, an open method.

### 1.2 `PATH_STATE` is PERSISTED, and the restore path calls `CgPath.parse` on it

`Workbench` line ~1299: layout restore reads `ref.state(PATH_STATE, "")` and hands it to
`CgPath.parse`. Stuffing a resource string into `PATH_STATE` would ship a landmine that only
detonates on the **next session's restore** — the classic delayed failure. So viewer panels carry
their own `RESOURCE_STATE` under their own `VIEWER_TYPE`, and the restore path for that type goes
back through the content provider. A viewer tab **does** survive a restart (dropping half the tabs
on restore reads as data loss), but it restores by re-asking the provider, never by `client.read`.

### 1.3 Naive language services on a JDK source document POISON the whole file

The trap is already recorded in AGENTS.md for the popup's path: a file out of `src.zip` declares
`package java.util`, which `java.base` owns, so at compliance 9+ ECJ reports *"The package java.util
conflicts with a package accessible from another module"* — **and that one error poisons resolution
for the entire unit**. `AttachedSources` parses platform sources at compliance 8 for exactly this
reason. `JavaLanguage.servicesFor` knows nothing of it: give a viewer document ordinary services and
every JDK file opens under a full-width squiggle with nothing resolvable — *worse* than no services.

And even with the compliance fixed, a library document must never **announce diagnostics**: the JDK
compiles against a classpath we only approximate, its warnings are not the user's problem, and the
Problems panel flooding with `java.util.List` rows the user cannot act on is noise wearing the
uniform of information. IntelliJ runs limited inspection on read-only files for the same reason.

**Decision: two phases.** The viewer opens first with grammar colouring only (tree-sitter already
colours any `.java` text; the registry keys by file name, which a `library://…List` resource
provides). Library-mode services — compliance pinned by provenance, diagnostics suppressed,
resolution and semantic tokens on, so Ctrl+Click **inside** the viewer keeps drilling — are their
own milestone, because transitive navigation is the half of IntelliJ's feature people actually live
in, and shipping it broken-red first would burn the feature's reputation on arrival.

### 1.4 "Keep a full copy" interacts with TWO other decisions in `JdkSourceExtract`

The fetched extract is not just header-stripped — it is **package-filtered** to ten `java.base`
prefixes (measured as the module's public API), and it lands in a cache file named
`jdk-<feature>-sources.zip`. Keeping full bodies therefore means:

- `SourceHeaders.strip` stops being applied at line ~322. `SourceHeaders` itself **stays** — it is
  tested directly, and a memory-constrained path may want it back.
- The package filter **stays**. The user accepted ~43 MB for full bodies; un-filtering would pull
  Swing and the XML stack in for a script author who reaches for neither. The one-line rule
  ("`java.base`'s public API") survives unchanged.
- The cache file is renamed (`jdk-<feature>-sources-full.zip`), because an already-fetched stripped
  extract from an earlier session is **indistinguishable by name** from a full one. The adoption
  path (`JdkSourceCommands`) treats a stripped-era file as absent; deleting it is a courtesy, not a
  correctness requirement.
- A real on-disk `src.zip` (any JDK install, the `M13 §25.5` search roots) was never stripped and
  needs nothing.

### 1.5 The decompiler goes in the ENGINE BAND, and the bytes come from `TypeBytes`

Three placements were considered and two are wrong:

- **Not `core/`** — the import-guard/headless rule; a dedicated server runs scripts and views nothing.
- **Not a plain `implementation` dep of `language/`** — `language/` is bundled into loader jars as
  *class files*, so every runtime dependency must be shipped explicitly and possibly relocated (the
  ASM lesson, paid for once already).
- **The band staging directory**, where twenty pinned jars already live behind `EngineClassLoader`,
  a loader already exists, and absence already degrades cleanly. CFR is written in **Java 6**, so
  unlike ECJ and Rhino one artifact serves band 8 and band 11+ — it is the first band entry that
  needs no per-band pin. `checkEngineBands`' bytecode-floor check passes trivially. CFR has zero
  transitive dependencies, so the open-ranges trap cannot fire, but `isTransitive = false` is
  written anyway because that is the band's rule, not a per-artifact judgement.

The adapter is child-side (`.java` tree — it imports `org.benf.cfr`), reached through a new bridge
interface (`engine.bridge.Decompiler`: `String decompile(String binaryName)` plus a byte source),
instantiated via `EngineHost.adapter` like every other adapter. Child-side rules apply: it may name
JDK types, the bridge, and `com.crystalgui.text.*` — nothing else of ours.

**The byte source is `TypeBytes.readable`, with a classpath fallback.** This is the part IntelliJ
cannot do: on a live Minecraft host the decompiled view shows the class **as the runtime has it** —
post-transformer, post-mixin, in readable names — because `TypeBytes` reads live bytes and remaps
them. On the harness and in tests, where no platform registers `TypeBytes`, the fallback reads the
class file off the analysis classpath. CFR's `ClassFileSource` API accepts exactly this shape, and
it is also how inner classes and supertypes are supplied (CFR asks for them by name during a
decompile; answering from the same source keeps `Map$Entry` rendering inside `Map`).

### 1.6 CFR 0.152 is from December 2021, and that is acceptable ON THE EVIDENCE, not by hope

Verified: MIT, runs on Java 6, last release 0.152 (Dec 2021), ceiling ≈ Java 17 features (records,
sealed classes, `instanceof` patterns, switch expressions; **not** Java 21 record patterns). The
author resumed work in March 2026 with no published artifact yet. This is fine **because of what
actually reaches the decompiler**: mod jars are Java 8 bytecode on 1.7.10 and Java 17 on 1.20.x —
a jar cannot be newer than the JVM that loads it — and the JDK never reaches it at all, because the
JDK comes from `src.zip`. The seam is one bridge method, so a Vineflower swap (band 11+ only, since
it needs Java 11) is a jar change, not a rewrite. Recorded so the day someone feeds it Java 21
bytecode, the failure is a known ceiling and not a mystery.

MIT obligations: `THIRD-PARTY.md` row + the copyright notice travelling with the distribution —
same treatment as the Chromium `RateEstimator` row. A javadoc credit is good practice and satisfies
nothing.

### 1.7 Read-only is enforced at the WIDGET, and the plan hardens it at the DOCUMENT

Every current mutation path checks `TextEditor.readOnly` — verified: `applyEdit`,
`applyEditKeepingSelection`, `typeCharacter`, `moveLines`, `applyCodeAction`, cut/paste enablement.
But the guard lives on the widget, and `buffer().edit(...)` from any future caller bypasses it
silently. A viewer document additionally sets a refusal at the document layer (its buffer rejects
edits), so the invariant is "this document cannot change" rather than "this widget currently
declines to change it". Cheap, and it converts a future bug from silent corruption into a loud
refusal. The close guard needs nothing: a buffer that never edits never dirties, so `confirmClose`
falls through.

### 1.8 Everything here is ASYNC, and the declaration must resolve against the text the viewer shows

Decompilation is hundreds of milliseconds; archive reads are IO; both run off the UI thread through
`JobScheduler` with the UI hop on `onDone` (the `RunIndicators` reference shape). Results cache:
attached source is already cached per classpath by `AttachedSources`; decompiled output gets a
bounded LRU keyed `(binary name, bytes hash)`.

The subtler rule is positional: a `DeclarationSite` into a library file carries row/columns that are
only legal against **the text the provider will serve**. Both the engine (computing the site) and
the provider (serving the document) must read through the same `SourceArchives` cache — same
`Found`, same string — or the caret lands on the wrong line in a file the user cannot edit to
correct it. For a decompiled target no positions exist until the decompile runs, so the site names
the type and the viewer resolves the **member** after opening, by analysing its own text (best-effort;
falling back to the type's declaration line).

### 1.9 Inner classes resolve to their TOP-LEVEL file

`Map.Entry`'s declaration lives in `Map.java`; a `library://` resource names the **top-level** type
and the site carries the range of the nested declaration within it. `AttachedSources` already does
the nested→top-level walk for quoting; the site construction reuses it rather than re-deriving.

### 1.10 What JavaScript gets, and what 1.7.10 production gets

- **JS**: `InteropResolver.describeMember`/`describe` return the probe's `SymbolInfo`, and
  `RhinoResolution` already carries `declaration()` through. When the Java engine starts producing
  library sites, Ctrl+Click on `list.add` in a `.js` file opens `ArrayList.java` with **zero
  JS-side changes**. One test pins it so it stays true by construction rather than by luck.
- **Obfuscated 1.7.10**: `TypeBytes.readable` feeds the decompiler readable-name bytes, so the
  decompiled view reads `getBlock`, not `func_147439_a` — the remap happens before CFR ever sees
  the class. On a host with no mapping configured, obfuscated in means obfuscated out, which is
  honest.

---

## 2. Design decisions, stated once

1. **The seam is a scheme-keyed content provider registry in `core/.../text/lang/`** —
   `ResourceContents.contribute(scheme, provider)`, mirroring `ScriptRuntimes.contribute` exactly:
   adds never replaces, the shell never names a language, `JavaLanguage.register` contributes the
   `library` provider. The provider answers async with `(text, title, provenance, banner)`.
2. **Provenance travels with the answer.** `SOURCE` (attached source), `DECOMPILED` (banner:
   *"Decompiled from bytecode — CFR 0.152"*), later `STRIPPED` if a memory path returns. The banner
   is an element above the editor in the viewer panel, IntelliJ-style; absent for plain source.
3. **A viewer tab is a normal tab**, not a preview tab. Tab-lifetime semantics (transient/italic)
   are a separate feature the user explicitly deferred.
4. **`declarationOf` gains a fallback, not a rewrite**: when `unit.findDeclaringNode` answers null
   and the binding's declaring class resolves through `AttachedSources`, build the site from the
   attached unit's positions and a `library://<top-level binary name>` resource. Null remains the
   answer when there is no source AND no decompiler — the documented ordinary case shrinks, it does
   not vanish.

---

## 3. Milestones

### V1 — The engine knows where things live
`declarationOf` fallback through `AttachedSources` by binding key; `library://` sites for types,
members, fields; nested→top-level mapping (§1.9). No UI change — observable through
`Analysis.resolveAt(...).declaration()`.
**Exit:** a test resolves `List#add` from a script and gets a non-null site whose resource names
`java.util.List` and whose range covers `add` in the exact text `SourceArchives` serves. JS interop
test: the same site arrives through `RhinoResolution` untouched (§1.10).

### V2 — The workbench opens it
`ResourceContents` registry in core; `JavaLanguage` contributes the source-backed provider (no
decompiler yet); the viewer lane in `Workbench` (§1.1): `openResource(Resource, Runnable)`,
`VIEWER_TYPE` + `RESOURCE_STATE` (§1.2), read-only document with document-level refusal (§1.7),
grammar colouring, banner plumbing, session restore through the provider. `onDefinitionChosen`
routes non-project resources here instead of returning.
**Exit:** Ctrl+Click on `ArrayList` in the harness opens `ArrayList.java` read-only at the
declaration; typing is refused; closing prompts nothing; restart restores the tab; the Problems
panel never mentions it.

### V3 — Full JDK bodies
`JdkSourceExtract` stops stripping, cache renamed, stripped-era extracts treated as absent (§1.4).
Package filter unchanged.
**Exit:** on a JRE-only host (or with the cache cleared), the fetched extract serves `ArrayList`
with real method bodies; `Analysis.optionalProblemsAnalysed` still passes over a fetched file.

### V4 — The decompiler
CFR 0.152 into both band configurations; `engine.bridge.Decompiler`; child-side CFR adapter fed by
`TypeBytes.readable` with classpath fallback (§1.5); provider falls back source → decompile; LRU
cache; banner names the provenance; member located post-open (§1.8). `THIRD-PARTY.md` row.
**Exit:** Ctrl+Click into a class of a jar with no `-sources.jar` opens decompiled output with the
banner; the same class twice costs one decompile; a band with no CFR staged degrades to V2
behaviour with a status message, not an error.

### V5 — Library-mode services
The viewer document gets services configured by provenance: compliance 8 for platform sources
(§1.3), diagnostics **suppressed entirely**, resolution + semantic tokens + hover on. Ctrl+Click
inside a library file keeps drilling.
**Exit:** open `ArrayList.java` from `src.zip`: no diagnostics anywhere, interfaces colour as
interfaces, Ctrl+Click on `RandomAccess` inside it opens `RandomAccess.java`. The module-conflict
error is asserted absent, by the test that would have caught it.

---

## 4. Deliberately not doing

- **Preview-tab lifetime** (italic, replaceable) — separate feature, separate conversation.
- **A bytecode view** (javap-style) — IntelliJ ships one; nothing here needs it yet.
- **Readable-name remap of decompiled output beyond what `TypeBytes` already does** — a full
  `MemberResolution` pass over decompiled text is V2-of-the-decompiler territory.
- **An assembled-stub fallback** when there is neither source nor decompiler — `JavaSignatures` could
  synthesise a header view, but a third content shape triples the banner/testing matrix for the
  rarest case. The status message names what was missing instead.
- **Editing library sources** — IntelliJ allows unlocking; we do not. The document refuses, full stop.
- **Generalising the workbench pipeline to `Resource`** — the right end-state, taken up when a second
  non-file document kind exists to justify it (§1.1).

## 5. Risks

| Risk | Contained by |
|---|---|
| CFR chokes on some class (older tool, exotic bytecode) | Per-class try/catch; the viewer reports "could not decompile" for that class alone; cache stores the failure so it is not retried per click |
| A provider answering slowly under a click | Async open with the same continuation shape `openFile` already has; a second click while pending re-activates, never double-opens |
| Positional drift between engine text and viewer text | One `SourceArchives` read serves both (§1.8); a test opens the site's range and asserts the identifier under it |
| Stripped-era cache adopted as full | Cache renamed; old name ignored (§1.4) |
| Band without CFR (older staged dir) | `Decompiler` adapter resolves null → source-only behaviour; one stderr line names the absence, per the "a capability that can be silently skipped must SAY it is on" rule |
