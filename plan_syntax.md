# plan_syntax.md — The language stack

**Status**: live plan, v2 (2026-08-12). v1 was a Q&A accretion — sections in the order questions were
asked, several claims now disproven. This is the architecture, rewritten after auditing the code and
verifying every external fact that carries weight. Companion to `plan_styling.md` (chrome colour) and
`plan.md` (workbench architecture).

**Goal**: two deliverables that share one foundation.

1. **A live scripting engine** — Java and JavaScript authored, compiled, resolved and run *inside*
   the host process, on any JVM from 8 to 25, with the diagnostics, type resolution and completion
   quality of an IDE and no language server.
2. **A syntax layer** — highlighting that matches IntelliJ's Islands Dark across `java`, `js`,
   `css`, `html`, `glsl`, sitting on the same seams so the semantic layer refines rather than
   replaces it.

**Standing rule**: nothing here is committed until explicitly asked.

---

# Part I — Ground truth

## 1. What exists — audited 2026-08-12, against code, not against v1's memory

v1 listed "multiple cursors" and "read-only regions" as Tier-1 gaps. Both **already exist**. An
inventory that is wrong in the optimistic direction wastes design; wrong in the pessimistic
direction it wastes a milestone. Re-audited:

| Piece | State | Evidence |
|---|---|---|
| Rope / `TextBuffer` / `ChangeSet` / `Edit`+`UndoStack` | ✅ solid, headless | `com.crystalgui.text` |
| **Multi-cursor** | ✅ **exists** | `SelectionModel` holds `List<Selection>`; every `MoveOperations`/`TypeOperations` op takes the list; per-caret indent in `TextEditor.insertNewlineWithIndent` |
| Read-only documents | ✅ exists | `TextEditor.setReadOnly`, enforced at `applyEdit`, the single mutation funnel |
| Bracket matching | ✅ exists | `bracketPair` ranges published as `::highlight(bracket)` |
| Folding | ✅ exists | `text/fold/`, indent-based provider, Monaco's default too |
| Search in document | ✅ exists | re-run from the buffer change signal (correct for search; see §17 for why diagnostics cannot afford the same) |
| `SyntaxToken` / `SyntaxTokenizer` SPI | ✅ right shape | range-scoped query, separate `edited()`, `close()` — Zed's split |
| `Language` / `LanguageRegistry` | ✅ | comments, bracket pairs, extension→entry |
| `KeywordTokenizer` | ✅ keep | the no-natives fallback; `core/` must load on a dedicated server |
| `TreeSitterTokenizer` | ⚠️ **works, does not scale** | four concrete defects, §9.1 |
| Grammars | ⚠️ java only | `lib/tree-sitter/`: binding jar + java jar, 5 platforms |
| Schemes | ⚠️ Dark+ (7 colours), not Islands | `ui/schemes/dark-plus.css` — its own header says so |
| Paint path | ✅ | captures → `::highlight(name)` → `CgStyleSpan`; per-view-line clipping in `refreshHighlights` |
| **Background work model** | ❌ **does not exist** | the only executor in `core/` is an SVG preload pool. No job queue, no cancellation, no versioned results |
| **Tracked ranges (decorations)** | ❌ does not exist | every range-owner is bespoke; nothing survives an edit except by re-derivation |
| **Per-line token cache** | ❌ does not exist | `refreshHighlights` re-queries and re-registers every realised line on every change |
| **Semantic layer** (resolver, diagnostics, completion) | ❌ does not exist | no SPI, no engine, no UI |
| Bold/italic in `::highlight()` | ❌ deliberately refused | `HighlightStyle.ALLOWED` = `{color, background-color, text-decoration-line}`; §11 carves the editor exception |

The three ❌ rows in bold are the real foundation work. Everything else is filling in.

## 2. Facts that died under verification

Each of these was asserted in v1 and is wrong. They are recorded because each one, built on,
would have failed late instead of early.

| # | v1 claimed | Verified truth (2026-08-12) | Consequence |
|---|---|---|---|
| 1 | "Rhino spans 8→25" | **Rhino 1.8.0+ requires Java 11.** 1.7.15 is the last Java-8-capable release. 1.9.x is current | no single Rhino artifact spans the range → version banding, §6 |
| 2 | "The ES5.1 trap mostly closes" with modern Rhino | `let`/`const`, arrows, template literals, destructuring: yes. **ES6 `class` syntax: still unimplemented** (mozilla/rhino#835, open). **ES modules: unsupported** | the grammar-ahead-of-engine gap is permanent for JS; §16 turns it into diagnostics instead of pretending it closes |
| 3 | "ECJ runs on 8 and 25" | **ECJ ≥ 4.28 (June 2023) requires Java 17 to run.** The 4.17–4.27 line runs on 11. Only the ≤ 4.16 era (mid-2020, compiles up to Java 14) runs on 8 | same consequence: banding, §6 — and it is *fine*, because a Java 8 host cannot load newer bytecode anyway |
| 4 | "ECJ is a single ~3MB jar" exposing `ITypeBinding` | the slim `org.eclipse.jdt:ecj` jar is the **batch compiler only — no DOM, no bindings API**. `ASTParser`/`ITypeBinding` live in `org.eclipse.jdt:org.eclipse.jdt.core` plus a handful of transitive `org.eclipse.platform` jars (~10–15MB total) | real dependency weight; isolated classloader per band, §6.3; never near `core/` |
| 5 | "each grammar needs a subproject added to the fork" — priced as the bulk of steps 3–5 | upstream `tree-sitter-ng` **already ships `tree-sitter-css`, `tree-sitter-javascript`, `tree-sitter-html`** (31 grammars, Zig cross-compile, 6 platforms, plus a codegen task for new ones). Only **GLSL** is genuinely new | grammar cost collapses: three languages are a fork-sync and a build; one is a codegen'd subproject. §12 |
| 6 | (unexamined) tokenizer converts every offset UTF-16↔UTF-8 | the vendored binding exposes **`parseStringEncoding(tree, source, TSInputEncoding)`** — tree-sitter parses UTF-16 natively | the entire conversion layer is deletable; byte offset = 2 × UTF-16 index, exactly. §9.2 |

Method note for future revisions: every claim above was one search or one `javap` away. Verify
before designing, not after.

## 3. References — what we take from whom

Settled in v1 and still correct; compressed here to what governs decisions.

- **Tokenization: Zed's model** (tree-sitter, `highlights.scm` capture names, range-scoped queries,
  edit-interpolate/reparse split). Already chosen, already in the javadoc, not re-litigated.
  Neither visual reference uses tree-sitter — IntelliJ is a decade of per-language JFlex+PSI,
  VS Code is TextMate-plus-Oniguruma producing a worse structure for the same native cost. Both
  hit the grammar ceiling and answer it with semantic tokens layered on top; so do we (§14), which
  is why the choice is stable under the new requirements.
- **Semantic layer: LSP's *contracts* without LSP.** `CompletionItem`, `Diagnostic`, semantic-token
  merge semantics — the shapes are right and porting them keeps a real language server possible
  later with no second merge path. The *answers* come from in-process engines, which is strictly
  better than a language server for this use case: the resolver's truth is the live runtime.
- **Ranking and UX details: IntelliJ.** Completion weigher chain, camel-hump matching, the
  Islands palette itself.
- **Editor internals: Monaco.** Decoration stickiness, per-line token storage, view-part protocol
  (already ported), `isIncomplete` re-query.

---

# Part II — Architecture

## 4. The layer model

Five layers, strict downward dependencies. Every arrow that skips a layer in today's code is listed
in §9/§17 as a rewrite target.

```
L3  presentation      TextEditor paint, ::highlight, completion popup, Problems panel, squiggles
     ↑ (drain queue only — no L1/L2 code runs on the UI thread except cheap, bounded queries)
L2  semantic          Resolver, DiagnosticSink, SemanticTokenProvider, CompletionProvider
     ↑                — per engine (ECJ, Rhino, reflection), consults L1's tree, never L3
L1  syntax            SyntaxTokenizer, folds/indents/locals/injections, per-line token cache
     ↑                — per grammar, no engine, no reflection
L0  document          Rope, TextBuffer, ChangeSet, versions, tracked ranges (decorations)
X   scheduler         crosscutting: priority lanes, keyed jobs, cancellation, version stamps
```

Three rules that make the layers real rather than aspirational:

1. **L0 is the only shared mutable state**, and it is only mutated on the UI thread. Everything
   above computes immutable values from immutable snapshots.
2. **Every async product is stamped with the document version it was computed from**, and every
   consumer declares its staleness policy (§8).
3. **`core/` owns every interface in L0–L2 and implements none of L1–L2's engines.** A dedicated
   server loads L0 + the SPIs + `KeywordTokenizer` and nothing else. Already true for L1; L2
   copies the same split.

## 5. Modules

### 5.1 `syntax-treesitter/` → `language/`

Renamed (decided v1 §16.1): package `com.crystalgui.language`, the HQ for everything below L3 that
is not `core/`'s interfaces. Sub-packages by concern so a later split is a move, not an untangling:

```
com.crystalgui.language
  .grammar      TreeSitterTokenizer (rewritten, §9), query loading, injections, folds/indents/locals
  .java         the ECJ adapter: compile, bindings, diagnostics, semantic tokens, completion providers
  .js           the Rhino adapter: execution service, parse diagnostics, runtime introspection
  .resolve      engine-neutral: type index, import table, fuzzy matcher, ranking, sandbox policy
```

### 5.2 What `core/` gains *for the language stack* (SPIs only — the full list, so scope creep is visible)

> This is the language stack's footprint in `core/`, and it is interfaces only. It is **not** a claim
> that `core/` gains nothing else: §7's scheduler and `TextBuffer.version()` are general infrastructure
> that predate any language work in kind — file listing and shader compilation want both — and they live
> in `core/` because the dependency runs one way. `core/` cannot reach into `language/`, so anything
> `core/` itself consumes cannot live there.


`com.crystalgui.text.syntax` already holds `SyntaxToken`/`SyntaxTokenizer`. A sibling package
`com.crystalgui.text.lang` gains the L2 contracts:

| Interface | One-line contract |
|---|---|
| `Diagnostic` | `(range, severity, message, source, code?)` — immutable value |
| `SemanticTokenProvider` | async; produces `SyntaxToken`-shaped spans in the same capture vocabulary, keyed per line (§14.2) |
| `Resolver` | `resolveAt(offset)`, `expectedTypeAt(offset)`, `membersOf(type, callingContext)` — all async, all versioned |
| `CompletionProvider` | `(context) → CompletionList{items, isIncomplete}` + `resolveItem(item)` |
| `CompletionItem` | the LSP field set (§18.2) including `additionalTextEdits` |
| `LanguageServices` | per-**document** façade bundling the above; lifecycle follows the document, not the editor — two tabs share it, closing the document drops it |

`TextEditor` consumes `LanguageServices` if present and behaves exactly as today if absent. That
absence *is* the dedicated-server story and the feature flag; there is no other flag.

### 5.3 Engines never touch `core/`

ECJ (~10–15MB with the DOM stack), Rhino (~1.5MB), grammar natives: all loaded by `language/`,
none on `core/`'s compile or runtime classpath. `core:headlessTest` remains the proof, as it is
for GL today.

And one split *inside* `language/`: **execution must not require the grammar natives.** A
dedicated server runs scripts (ECJ and Rhino are headless and GL-free — server-side execution is
in scope) but has no editor and needs no tree-sitter; `.java`/`.js`/`.resolve` therefore never
touch `.grammar`, lazy class-init is the mechanism, and a headless test that compiles-and-runs a
script with the grammar jars absent is the proof (an M7 exit criterion).

## 6. Engine strategy — version banding

### 6.1 The ceiling argument, which makes banding correct rather than a compromise

A Java 8 JVM cannot load class files newer than 52.0. So on a Java 8 host, scripts are Java-8-language
scripts *no matter what compiler we ship* — a compiler that accepts records would produce bytecode
the host rejects. The oldest band's engines are not a degraded experience; they are exactly the
ceiling the host already imposes. The same holds for 11 and 17. **Band by host JVM, and each band
is "the newest engine that runs there", which is also "the newest language the host can execute".**

### 6.2 The bands

| Host JVM | ECJ line (runs there) | Compiles up to | Rhino | ES level |
|---|---|---|---|---|
| 8–10 | ≤ 4.16 era (2020-06) | Java 14 (target ≤ host) | 1.7.15 | most of ES2015 minus classes/modules |
| 11–16 | 4.17 – 4.27 | Java 20 (target ≤ host) | 1.8.x+ | ES6 default level, still no classes/modules |
| 17+ | newest | newest Java | newest (1.9.x+) | best available |

Exact artifact versions pinned at M5 after the verification in §23; the table's shape is the decision.

### 6.3 One adapter, isolated classloaders

- **One adapter per engine**, compiled against the *oldest* band's API. Both APIs are stable enough
  to make this real: the JDT DOM (`ASTParser`, `ITypeBinding`) has been source-stable for over a
  decade; `org.mozilla.javascript` likewise. The JLS level passed to `ASTParser` is chosen at
  runtime (highest constant present), so the adapter never names a level the old jar lacks.
- **Each engine loads in an isolated, child-first classloader** over its band's jars. Three things
  fall out: no dependency clash with mods that ship their own Rhino (several do), the sandbox has a
  natural enforcement point, and an engine can be dropped wholesale.
- Band selection is one `System.getProperty("java.specification.version")` read at startup.

### 6.4 The alternative, recorded and rejected

jvmDowngrader *can* downgrade dependency jars — that is its purpose in `mc1710` — so "downgrade
newest ECJ to 8" is expressible. Rejected as the plan, kept as a permitted experiment: ECJ's
runtime behaviour includes reading `ct.sym`/jrt images and JPMS metadata, which API stubbing
cannot fake, and a compiler is the worst possible place to discover a stub at runtime. Banding is
boring and provable on each band's real JVM; boring wins. If the experiment ever proves the
downgraded jar on a real Java 8 host end-to-end, collapse band 1 into it and delete a row.

### 6.5 Licences

ECJ/JDT: EPL-2.0. Rhino: MPL-2.0. Both bundleable; both go in `THIRD-PARTY.md` with the vendored
notice, same convention as the icons and grammars. This is an obligation, not documentation.

## 7. The scheduler

The single largest unstated dependency in v1, now specified. Owned by the **service layer**, and
its API is added to `docs/CGUI_WORKBENCH_SERVICES.md` in the same commit it lands — that doc's own
rule. It is not syntax-specific: file listing and shader compilation want the same thing.

### 7.1 Shape

- **One shared pool** (N = cores-derived, small). Not one per feature — three pools compete for
  the same cores and none of them knows it.
- **Three priority lanes**: `INTERACTIVE` (completion queries — a human is mid-keystroke),
  `LATENCY` (reparse, semantic tokens — visible but tolerates ~100ms), `BACKGROUND` (type index,
  first compile of an opened file). Lanes are strict priority with the standard starvation guard
  (a BACKGROUND job that has waited long enough is promoted).
- **Keyed single-flight**: a job is keyed `(documentId, kind)`. Scheduling a key that is already
  queued *replaces* the queued job; one that is already running lets it finish (cancellation may
  also be requested) and queues the replacement. Consequence: convergence is structural — the last
  edit's job always runs, and nothing ever queues N compiles for N keystrokes.
- **Debounce is a property of the kind**, declared where the kind is: reparse `0ms` (just
  off-thread), semantic tokens `~100ms`, diagnostics compile `~300ms`, index build `once`.
- **Cooperative cancellation**: a token the job polls. ECJ compiles and jar scans are both long
  enough to need it; neither can be interrupted any other way.
- **Results re-enter on the frame tick**: a job returns an immutable value into a drain queue; a
  `UIFrameTicker` applies drained results on the UI thread. **Nothing off-thread ever touches UI
  or document state.** No locks in widgets, no second threading discipline to learn.

### 7.2 Testability is a design input, not a wish

The scheduler takes its executor and clock by injection. Tests run it with a same-thread executor
and a manual clock, so "debounced, superseded, cancelled, stale-discarded" are all deterministic
JUnit assertions. This is what makes every async feature in Parts III–IV testable headlessly, which
is what makes them testable at all under this project's rules.

### 7.3 Budgets (the numbers the design is accountable to)

| Path | Budget |
|---|---|
| UI-thread work per keystroke (apply edit + interpolate + adjust decorations + invalidate caches) | < 2ms typical, < 8ms worst |
| Viewport highlight from per-line cache, per frame | ~0 (array reads); < 1ms on a cache-miss line |
| Reparse (off-thread) landing → viewport re-collected | next frame after landing |
| Completion: list first paint after trigger | < 100ms, partials allowed via `isIncomplete` |
| Diagnostics after last keystroke (300ms debounce + script-sized ECJ compile) | < 700ms |
| Type index: cold scan of a large modpack classpath | background, seconds, once; warm load from cache < 500ms; memory < ~20MB |

## 8. The version spine

`TextBuffer` gains a monotonic `version()`, incremented per applied `ChangeSet`. Cheap, and
everything hangs off it:

- **Every job records the version it snapshotted; every result carries it.**
- Every consumer declares one of three staleness policies, at the consumer:
  - **discard** — a stale result is thrown away; single-flight guarantees a fresh one is coming.
    (Diagnostics lists, resolver answers, completion queries.)
  - **keep-adjusted** — the value is a set of ranges already installed as tracked decorations;
    the decoration layer moved them with the edits, so they stay right until replaced. (Squiggles
    between compiles.)
  - **keep-per-line** — semantic tokens: lines edited since the result's version fall back to
    grammar colouring; untouched lines keep semantic colour. (VS Code's behaviour — semantic
    lags, grammar covers the gap.)

Getting this wrong does not look like a race; it looks like an off-by-a-few-characters bug in the
compiler. Version stamps are how it never has to be debugged.

---

# Part III — The syntax layer

## 9. `TreeSitterTokenizer` — rewrite, not tune

### 9.1 The four defects, named against the current code

1. **`tokenize()` calls `document.toString()` and `text.equals(source)` on every query** — a full
   rope flatten plus an O(n) compare, per viewport query, per frame-with-changes. Replace with the
   §8 version stamp: reparse iff `documentVersion != parsedVersion`.
2. **Offset conversion is O(n) per token** — `utf8Offset` builds `substring(0,limit).getBytes()`
   and `utf16Offset` builds `new String(bytes, 0, limit)` **per token**. A viewport with 2,000
   captures over a 100KB file constructs 2,000 partial strings per repaint: O(n²) in practice and
   the first thing a profiler will find.
3. **`edited()` reparses synchronously on the keystroke.** The javadoc already says this is the
   half that moves to a worker; the scheduler (§7) is the worker it was waiting for.
4. **The editor has no token cache** — `refreshHighlights` re-runs the query and rebuilds every
   realised line's `HighlightRegistry` maps whenever anything changes.

### 9.2 The fixes

**A. Parse UTF-16.** `parseStringEncoding(oldTree, source, UTF-16)` exists in the vendored binding
(§2.6). Every byte offset becomes exactly `2 × utf16Index`; every `TSPoint` column likewise. The
whole conversion layer — both offset functions, both point functions — is deleted, not optimized.
Verify once that the JNI path and query cursor byte ranges agree on the encoding (one test with a
non-ASCII fixture, which is precisely the test the current code never had). Investigate the
`TSReader`-based `parse` overload for feeding rope chunks without materializing one big `String`;
adopt if it works, don't block on it.

**B. Reparse off-thread, double-buffered — Zed's actual model, which v1 only cited.**
- On edit (UI thread, synchronous, cheap): apply `TSInputEdit` to the *current* tree —
  interpolation, so existing highlights move with the text this frame — and schedule a reparse
  (`LATENCY` lane, keyed, superseding).
- Queries between keystroke and reparse-landing run against the interpolated old tree: structurally
  stale, positionally correct — exactly what every editor shows for those ~30ms.
- The reparse job snapshots `(rope, version)`, parses off-thread, and lands `(newTree, version)`
  through the drain. Landing swaps the buffer's tree and invalidates the token cache for lines
  whose tokens changed (tree-sitter's `changedRanges(old, new)` gives them precisely).

**C. A per-line token cache in the editor.** Steady state: painting reads compact per-line arrays
(`int start, int end, short vocabularyId` — capture names interned to a vocabulary table, §10) and
touches the tokenizer not at all. Invalidation from exactly two sources: the edit (the touched
lines) and reparse-landing (`changedRanges`). `refreshHighlights` then updates only invalidated
realised lines instead of rebuilding all of them. This is the difference between highlighting
costing a query per frame and costing one per change — and it is Monaco's and Zed's shared design,
not an invention.

**D. Native lifecycle.** Trees, parsers, queries and cursors per *document* (per `LanguageServices`,
§5.2), closed when the document closes. Old trees replaced at swap are closed then. One test that
opens/closes 100 documents and asserts native handles do not grow.

## 10. The capture vocabulary

### 10.1 The contract

Capture names are the interface between grammars and schemes. Standard set (Zed's dialect —
the one the existing Java query already uses):

```
comment  comment.doc
string   string.escape  string.special
number   boolean  constant  constant.builtin
keyword  keyword.control  keyword.operator
function function.builtin  function.method
type     type.builtin
variable variable.builtin  variable.parameter  variable.member
property attribute  tag
operator punctuation  punctuation.bracket  punctuation.delimiter
```

`generalName()` dotted fallback stays as the safety net. Names are interned into a per-document
vocabulary table so the token cache stores a `short`, not a `String` (§9.2 C).

### 10.2 Normalization — the trap v1 missed

Vendored queries are the grammar authors' files (right, keep), but **grammar repos speak different
capture dialects** — nvim-treesitter renamed half its captures in 2023 (`@method` →
`@function.method`, `@parameter` → `@variable.parameter`, …), and upstream queries variously predate
or postdate that. So: vendor the author's query verbatim, and apply a small **per-grammar
normalization map** at load, folding whatever dialect it speaks onto §10.1. The map is data, it is
tested (§21), and it is the one place per grammar where "our Java looks subtly unlike Zed's Java"
can be fixed without touching a vendored file.

## 11. Schemes — Islands Dark, and the font-style carve-out

### 11.1 Step 0 — the carve-out (blocking, decided in v1, unchanged)

Islands italicises comments and bolds keywords; `HighlightStyle.ALLOWED` refuses `font-style`/
`font-weight` because a synthetic-bold highlight reflows wrapped text. The carve-out —
`ALLOWED_IN_EDITOR`, permitted because the editor lays one row per line under `nowrap`, where a
wider row changes `getScrollWidth` and nothing else — with the spec's reasoning written at the
definition. Then move `TextEditor`'s draw calls onto styled paragraphs, **including its measurement
caches**: measure on the paint path or the caret drifts under synthetic bold (the `AGENTS.md`
rule about `measureEllipsised`, same trap).

### 11.2 The schemes

- Every §10.1 name gets a `--syntax-*` token (dots→dashes) in **every** shipped scheme. This closes
  v1's gap A (`@operator`, `@attribute`, `@variable`, `@constant` currently render as body text —
  invisible, because an unstyled capture looks identical to one never produced).
- Author `schemes/islands-dark.css` from IntelliJ's Islands Dark against the honest subset
  (§14.2 note), and its light pair from Islands Light (IntelliJ ships one; match it rather than
  inventing).
- **Decided** (was v1 open question 3/4): `islands-dark` becomes the default scheme paired with
  `crystal-dark` — the chrome already draws IntelliJ's frame, and Dark+ tokens beside an IntelliJ
  frame is the mismatch this plan exists to fix. `dark-plus`/`light-plus` remain shipped and
  selectable.

## 12. Grammar production

Corrected economics (§2.5): upstream `tree-sitter-ng` already ships `css`, `javascript`, `html`
subprojects with the same Zig cross-compile the vendored jars came from. Per language:

| Language | Work | Cost class |
|---|---|---|
| `css`, `javascript`, `html` | sync the fork with upstream's existing subprojects, build, vendor jar + author's `highlights.scm` + licence | hours each, mostly build verification |
| `glsl` | new subproject via upstream's codegen task from `tree-sitter-grammars/tree-sitter-glsl` (the C parser; the lib.rs crate is Rust packaging of the same grammar — not usable, named so nobody reaches for it twice) | the one real build task |

First jar through the pipe (css — smallest, no injections) writes the recipe into
`lib/tree-sitter/README.md` so the second costs an hour, not a day. The Zig cross-compile is
confirmed reproducible locally (2026-08-11).

**Injections** (`html` blocker, decided v1): host tree → `injections.scm` → child parser per
injected range (tree-sitter's included-ranges API) → merged token list. Entirely inside
`language/.grammar`; `SyntaxTokenizer`'s flat document-offset token list is already the right
return shape, so no `core/` change. `html` ships only when `<style>`/`<script>` bodies highlight
as CSS/JS — an HTML file is mostly not markup, and the interim version reads as broken rather
than incomplete.

## 13. The other query families

A grammar directory is a folder of queries, loaded uniformly — plan the loader once:

| Query | Feeds | When |
|---|---|---|
| `highlights.scm` | §9–10 | now |
| `injections.scm` | §12 | with html |
| `locals.scm` | within-file scope colouring — `variable.parameter` vs `variable.member` with **no engine at all**; most of what makes IntelliJ's colouring look richer | with the grammar batch; superseded per-language when semantic tokens (§14) land, kept for engineless languages |
| `folds.scm` | syntax-aware folding, upgrading `IndentRangeProvider` behind the existing `FoldingRangeProvider` SPI | M11 |
| `indents.scm` | a real indent engine replacing the "line ends in `{`" rule (`TextEditor.insertNewlineWithIndent` names this plan as its successor) | M11 |

---

# Part IV — The semantic layer

## 14. The seams

### 14.1 Diagnostics

`Diagnostic(range, severity, message, source, code?)`. Producers: ECJ (Java), Rhino's parser (JS —
authoritative for "will this engine accept it", which is the *answer* to the grammar-ahead-of-engine
gap), the shader compiler (GLSL — it already reports; same seam, no new machinery; wired at M11). Consumers: the
squiggle pass, the gutter, the Problems panel — **which already exists and already renders
severities; it is wired, not built.**

### 14.2 Semantic tokens

Same value shape as `SyntaxToken`, same vocabulary (§10.1), produced per line by an async provider.
**Merge rule: semantic wins over grammar on overlap**, applied where the per-line cache is read —
one merge path, and an LSP could slot into it unchanged later. Staleness: keep-per-line (§8).
With ECJ bindings behind it, Java gets what v1's §3.3 marked unreachable: field vs local vs
parameter, unresolved symbol, deprecated (struck through — `text-decoration-line` is already
allowed in highlights). The honest-subset note in the scheme header shrinks to: *engineless
languages colour what the grammar and `locals.scm` can see.*

### 14.3 Resolver

```
resolveAt(offset)        → Symbol { kind, type, declarationSite?, documentation? }   // hover, go-to, completion receiver
expectedTypeAt(offset)   → Type?                                                     // lambda params, ranking, target typing
membersOf(type, from)    → List<Member>                                              // completion; `from` = calling context, for accessibility
```

All async, versioned, `discard`-policy. `expectedTypeAt` is the query v1 §12.3 identified as
missing everywhere it's needed — it is in the SPI from day one because completion written against
`resolveAt` alone cannot answer `list.forEach(x -> x.|)`.

## 15. Java — ECJ

### 15.1 What is adopted instead of built

The JDT DOM's resolved AST — `ITypeBinding`, `IMethodBinding`, `IVariableBinding` — *is* v1's §13:
generic substitution, overload resolution (all three phases), member lookup with bridges filtered
and accessibility computed, flow-sensitive pattern bindings, lambda target typing. **v1 §13.1–13.6
is retained as the acceptance checklist** — each row becomes "verify the binding answers this",
not "build this". The one paragraph of v1 worth its length was this trade; it survives intact.

Configuration that carries the whole works-while-typing story, so it is named here and not
discovered: `ASTParser` with `setResolveBindings(true)`, **`setStatementsRecovery(true)`,
`setBindingsRecovery(true)`** — JDT's shipped answer to "the file does not compile while it is
being typed", returning partial bindings on broken ASTs. This is a feature Eclipse has hardened
for twenty years; it is the reason resolution-on-broken-code (v1 §13.8.1) is a flag, not a project.

### 15.2 The classpath is the live loader's, plus an overlay

ECJ resolves against classpath entries (`setEnvironment`). Build them from the running process:
`LaunchClassLoader.getSources()` on 1.7.10, the loader's URL list on modern loaders, plain
`URLClassLoader.getURLs()` in the harness — a small per-platform probe in `language/.java`.
Classes that exist only in memory (runtime-generated) don't appear on any classpath; they are
served by the **reflection overlay** — the second provider behind the same resolver seam, which is
also what answers *after a script has run* (§16.3). Two sources of truth, one seam, semantic
merge order: reflection-of-the-live-object wins where both answer.

On Minecraft hosts, file-based entries are only the baseline — §15.5 replaces them with a live
name environment, because on MC the disk view is a lie.

### 15.3 Script shape: prelude-wrapped, constant-offset

A script is not a compilation unit; the host injects context (`graph`, `event`, …). Wrap the
script text in a synthesized unit — imports region, class header, typed fields for host bindings —
with the user's text spliced at a **fixed prefix**, so mapping ECJ's offsets back is `- constant`,
applied in exactly one place (the source mapper). JShell does precisely this. Import statements
the user types are hoisted into the prelude's import region by the mapper — which is also where
the import table (v1 §12.1's precedence model: implicit → single-type → on-demand → static →
local shadowing all) lives and is enforced by ECJ itself rather than reimplemented.

### 15.4 The type index (auto-import, unimported-type completion)

Background scan (`BACKGROUND` lane, once) of the classpath entries: `(simpleName → FQNs, package,
access flags)` into a compact sorted table. Persisted per jar, keyed `(path, size, mtime)`, so a
modpack pays the scan once ever. Consulted by completion for unimported types (insert = name +
`additionalTextEdits` import, one `CompositeEdit`) and filtered by the sandbox policy (§19) so it
never advertises what execution refuses. On MC hosts the scan reads through §15.5's remapped
views, so the index — like everything else — holds readable names.

### 15.5 Minecraft classes — compile against the live loader, author in readable names

**Scope hardened 2026-08-12: this is a Minecraft scripting engine.** MC classes, mod classes and
mixin-added members referenced in a script must compile *and link* at runtime. That promotes the
`INameEnvironment` seam from escape hatch to the design on MC hosts, and adds a mapping boundary.
Neither is novel — it is the architecture KubeJS's remapping Rhino fork proved for JS, moved to a
compiler.

**Why nothing simpler works.** The disk view lies on every MC platform: 1.7.10 production ships
Notch-obfuscated jars whose classes are remapped *as they load* (SRG members exist only in
memory), and every platform runs ASM/mixin transformers that add members no class file on disk
has. And runtime member names differ per environment — 1.7.10 prod `field_70170_p`, 1.7.10 dev
`theWorld`, Forge 1.20.1 prod `f_123_`, NeoForge/dev Mojmap — so a script written against any one
runtime's names (including deliberately writing SRG) breaks on the others, *and* still cannot be
compiled against disk, *and* is unauthorable without readable completion. Three failures; one
design answers all of them:

**A. The live name environment.** ECJ's `INameEnvironment` for MC hosts answers from
**post-transform bytecode** fetched from the launch classloader (each loader exposes a route —
per-platform probe beside §15.2's; 1.7.10 = raw bytes through the transformer chain), with a
reflection-synthesized type stub as fallback for classes with no retrievable bytes. What the
compiler resolves against is exactly what will execute, mixin-added members included — this closes
the transformed-class caveat and the obfuscation-linkage caveat in one mechanism.

**B. One authoring namespace, remapped at the boundary.** Scripts are authored in the **readable
namespace** — MCP names for 1.7.10, Mojmap for modern — in every environment. The boundary remaps
both directions:

- **in**: the name environment presents type views with members remapped runtime→readable, so ECJ
  bindings, diagnostics, completion, hover and the type index all live in readable names with no
  further work anywhere;
- **out**: compiled script bytecode is remapped readable→runtime before `defineClass` — the *only*
  place the runtime namespace appears. **Not a naive `ClassRemapper`**: a script class overriding
  an MC method declares that method under its own name, and only inheritance-aware propagation
  (tiny-remapper's model — walk the supertypes, detect that the declaration overrides
  `World.getBlock`, remap it too) keeps the override an override. A naive pass compiles clean and
  the method silently never gets called — the worst failure shape there is (§23.13).

Where runtime already speaks the readable namespace — dev environments, NeoForge's
Mojmap-at-runtime, non-MC hosts like the harness — the mapping is identity and the boundary
disappears. The same script runs in dev and prod; completion never shows `func_147439_a`.

**C. Mappings are per-platform data**, shipped or fetched once and cached: 1.7.10 SRG↔MCP CSVs,
Forge 1.20.1 SRG↔Mojmap, Fabric intermediary↔Mojmap (tiny format), NeoForge identity. Sourcing
and licence terms are a §23 verification and a `THIRD-PARTY.md` entry like everything else
vendored. **Parameter names ride the same data** — production bytecode has none, so completion's
`getBlock(int x, int y, int z)` comes from MCP's `params.csv` (1.7.10) / Parchment (modern), not
from class files.

**D. Four consequences of the boundary, decided now rather than discovered:**

1. **Reflection *inside* a script sees runtime names.** The remapper rewrites symbolic references,
   not strings — `clazz.getMethod("getBlock")` returns null in prod. Declared **unsupported in
   v1** (KubeJS's longest-standing pain point); a remapping reflection helper in the host API is
   the later fix if scripts genuinely need it.
2. **Stack traces point at script lines.** The prelude mapper (§15.3) owns line numbers as well as
   offsets: the synthesized unit keeps the user's text at a fixed line offset and the emitted
   `SourceFile`/line table is adjusted, so a runtime exception and a compile diagnostic name the
   same line the author sees. An engine whose traces point into an invisible wrapper is undebuggable.
3. **Compiled scripts are cached** keyed `(source hash, mappings hash, band)` — a world with fifty
   scripts must not recompile fifty units every launch. Invalidation is structural: any key
   component changes, the entry dies. (Delivered at M7.)
4. **A script naming MC classes is bound to that MC version's API** — only host-API-only scripts
   are portable across 1.7.10/1.20.x. Stated as the expectation; nothing here papers over an API
   that genuinely differs.

## 16. JavaScript — Rhino

### 16.1 Division of labour (sharpened from v1)

Rhino is the **execution and truth** layer, not the analysis layer:

- **Execution**: contexts, scopes, host bindings, `ClassShutter` (§19).
- **Parse diagnostics**: Rhino's own parser errors are authoritative for what the engine accepts —
  this is the *mechanism* that closes the grammar-ahead-of-engine gap. `class` syntax and
  `import`/`export` highlight beautifully (tree-sitter parses modern JS) and are flagged by the
  engine the moment they're typed, with the real message. Also: no `class` snippet in the JS
  completion set — do not teach what cannot run.
- **Static structure** (scopes, references for completion before first run): tree-sitter's JS tree
  + `locals.scm`. Do not build a second JS analyzer on Rhino's AST.
- **Runtime introspection** (after a run): the live scope — walk actual objects and prototype
  chains, a REPL's answer, better than inference. Java interop values (`Java.type(...)`,
  `Packages.*`, a returned Java object) resolve **into the Java resolver** — the same
  `membersOf`, the same completion items. v1's observation stands: resolving `Java.type(...)`
  well buys more than resolving JS well.
- **The §15.5 mapping boundary applies to JS too, and at *call time*.** Rhino resolves Java
  members by reflection when the call executes — against runtime names — so a JS script calling
  `world.getBlock(...)` fails in production with §15.5 fully built, because no compiler ever sees
  a JS member access. Member lookup itself must remap: patch the lookup layer (`JavaMembers`) in
  our shaded per-band Rhino, or adopt KubeJS's maintained remapping fork where its band coverage
  allows — which fork *existing* is the proof this is the required shape (§23.12). Without it the
  JS engine is dev-only; this is not optional. The JS resolver and completion read through the
  same mapping tables, so authors see readable names in both languages.

### 16.2 Best-effort is the contract

Everything else in JS — untyped parameters, `this`, closures over not-yet-run code — is best-effort
by construction. Stated in the SPI docs so it reads as the design, not as incompleteness.

## 17. Diagnostics and decorations

### 17.1 Tracked ranges — the one new L0 primitive

The missing piece under every squiggle: **a range that is still right after you type above it.**
A decoration set per document — `(range, stickiness, payloadKey)` — adjusted synchronously on the
UI thread at the same call site that already announces edits to the tokenizer. Monaco's four
stickiness modes, ported with their names, because insert-at-boundary is the entire difficulty and
each mode exists for a consumer. Storage: sorted array with binary search first (documents are
script-sized); the interval-tree upgrade is an internal swap behind the same API if profiling ever
asks — do not build the tree speculatively.

Consumers now: diagnostics. Consumers later, designed-for now (a lane/kind field in the payload,
nothing more): bracket-pair ranges, overview-ruler marks, minimap, git gutter. **Search stays on
its re-run rule** — re-deriving on the buffer signal is *correct* for search (the matches
themselves change) and unaffordable for diagnostics (a compile per keystroke); the two rules
coexist because they answer different questions.

### 17.2 The pipeline

```
edit → (debounce 300ms, keyed, superseding) compile job @ version v
     → List<Diagnostic> @ v  → drain
     → if v == current: install as decorations; else discard (a fresh job is already queued)
     → decorations track subsequent edits until the next list replaces them
     → squiggle view-part paints from decorations; Problems panel consumes the same list
```

Squiggles are a new editor view part (the ten-part protocol already exists for exactly this),
severity-coloured from scheme tokens (`--editor-error`, `--editor-warning`, `--editor-info` — the
scheme axis, not the theme's, because they paint on the document).

## 18. Completion

v1 §12.2's research was sound; this is its spec form plus the end-to-end path it lacked.

### 18.1 Session

Trigger: explicit (Ctrl+Space) or trigger characters (`.`). **Grammar-level suppression first**:
no session inside comments or strings — one tree query, the cheapest wrong-popup filter there is.
A session survives typing; each keystroke re-filters locally **unless** the active list said
`isIncomplete`, which re-queries. Session dies on caret-leave-range, Escape, or accept.

### 18.2 Items

The LSP field set, ported whole: `label`, `kind`, `detail`, `documentation` (nullable —
resolve-on-demand for the selected item only), `sortText`, `filterText`, `insertText`, `textEdit`,
`additionalTextEdits`, `commitCharacters`, `command`. The four `*Text` fields are distinct because
a method shows `foo(int)`, filters on `foo`, sorts under `foo`, inserts `foo(`.

### 18.3 Matching and ranking

Port VS Code's `fuzzyScore` (MIT — port it, per the standing rule): subsequence with contiguity,
prefix and word-boundary/camel-hump bonuses, so `fMS` reaches `fooMethodStuff`. Rank by IntelliJ's
weigher chain: match quality → **expected-type conformance** (`expectedTypeAt` — the single best
behaviour in either IDE) → proximity (local > field > static import > unimported) → recency →
alphabetical. A lone `sortText` cannot express proximity; the chain is the port.

### 18.4 Accept

One `CompositeEdit`: the primary `textEdit` (replace-vs-insert range decided by the session) plus
`additionalTextEdits` (the auto-import), one undo step. `command` runs after (re-indent). Snippet
tab stops and linked-edit mode are **shaped in the item model now** (`insertTextFormat`) and
implemented with rename later — build linked-edit once, for both.

### 18.5 UI

Generalise the `QuickPick` substrate — it already implements the ARIA combobox rule (selection
moves, focus does not) and `::highlight()` match banding. Genuinely new on top: `isIncomplete`
re-query, lazy documentation pane (`SplitView`), kind icons (the `FileIconTheme` class-per-kind
idea). Popup via `Popover` + `AnchoredPlacement` at the caret — nothing else writes its position.

## 19. Trust model, sandboxing, and runaway scripts

### 19.1 The trust model, first — it decides what the sandbox may honestly claim

**Scripts are author-trusted content: the same trust class as a mod jar.** A pack or world author
installs them; players run what they installed. **Player-submitted scripts executing on a server
are out of scope permanently** (§22) — that is remote code execution as a feature, and nothing
below makes it safe.

This must be stated because **compiled Java has no runtime enforcement layer**. Rhino's
`ClassShutter` intercepts every member access at call time; compiled Java bytecode calls whatever
it links to, `SecurityManager` is deprecated-for-removal and gone in JDK 24, and
`Class.forName("java.lang.Runtime")` launders past any compile-time check. So for Java the
allowlist is a **guardrail** — it keeps honest scripts honest and completion truthful — and must
never be documented as a security boundary. Claiming otherwise is a lie with a CVE number waiting.

### 19.2 The allowlist

One **allowlist** policy object (never a denylist — the auto-close precedent), consulted at every
layer that could leak a name, so the tool never teaches an API the runtime refuses:

| Layer | Enforcement |
|---|---|
| JS execution | Rhino `ClassShutter` + scope curation — real, call-time interception |
| Java compilation | name-environment curation: refused types don't resolve (advisory — see §19.1) |
| Completion & hover | provider-side filter |
| Type index | refused types never indexed |

Default posture: the host's own API surface, the MC surface (§15.5), and a conservative `java.*`
slice. The host owns the policy; `language/` owns the mechanism.

### 19.3 Runaway scripts

An infinite loop in a script freezes the game, and Java has no safe preemption (`Thread.stop` is
broken by design). Decided:

- **JS**: Rhino's instruction observer — count-based cooperative interrupt, built in, cheap.
- **Java**: the output remap pass (§15.5 B) is already rewriting every script class, so it also
  injects a **cooperative safepoint check at backward branches and method entries** — one static
  volatile read, JIT-friendly, letting the host kill a runaway script cleanly. This is only free
  because the ASM pass exists anyway; it is the second consumer that justifies it. (Delivered at
  M7.)
- **Memory is not policed.** An in-process engine cannot meter allocation; the trust model (§19.1)
  is the answer, and saying so beats pretending.

---

# Part V — Execution

## 20. Milestones

Each independently landable and verifiable; none breaks current behaviour (absence of
`LanguageServices` is the off-switch). Order chosen so foundations land before their consumers and
user-visible value lands early.

| M | Delivers | Depends on | Exit criteria |
|---|---|---|---|
| **M0** | Scheduler + version spine: service-layer scheduler (lanes, keyed single-flight, debounce, cancellation, drain-on-tick), `TextBuffer.version()`, `WORKBENCH_SERVICES.md` updated | — | deterministic tests: superseded, cancelled, stale-discarded, drained-on-tick — all under manual clock |
| **M1** | Tokenizer rewrite (§9): UTF-16 parse, conversion layer deleted, off-thread double-buffered reparse, per-line interned token cache, native lifecycle per document | M0 | non-ASCII fixture correct; typing a 5k-line file: UI cost within §7.3 budgets, measured and recorded; 100-open/close leak test |
| **M2** | The scheme axis (§11): `ALLOWED_IN_EDITOR` carve-out, styled-paragraph editor draw path (+measurement), full `--syntax-*` vocabulary, `islands-dark` + light, default swap | — (parallel to M0/M1) | side-by-side with IntelliJ on the same Java fixture; italic comments; caret does not drift under bold; governance tests green |
| **M3** | Grammars (§12–13): fork sync; `css` → recipe documented → `javascript` → `glsl` (codegen) → `html` + injections; normalization maps; `locals.scm` wired | M1 | one golden-fixture highlight test per language; html `<style>`/`<script>` bodies coloured as CSS/JS |
| **M4** | Module reshape (§5): `language/` rename + sub-packages, `text.lang` SPIs in `core/`, `LanguageServices` per-document façade, editor consumes-if-present | — | `core:headlessTest` green with no new deps; harness wires Java end-to-end unchanged |
| **M5** | Engine loading (§6): band detection, isolated child-first loaders, pinned ECJ+Rhino per band, `THIRD-PARTY.md` | M4 | band-selection unit tests; smoke compile+eval on a Java 8 toolchain and on 17+ (Gradle toolchains — no MC needed); §23 verifications closed |
| **M6** | Java semantics (§15): ECJ diagnostics + semantic tokens + `resolveAt`/`expectedTypeAt`, prelude mapper, classpath probe, reflection overlay, **live name environment + mapping boundary (§15.5)** | M0, M4, M5 | fixture script: param/field/local coloured, unresolved flagged, deprecated struck; broken-code partial answers pass the §13-checklist tests; **remap round-trip: a script authored in readable names compiles, links and runs against a fixture class whose runtime members carry synthetic "obfuscated" names, through a fake mapping set** — all headless |
| **M7** | **Java execution service — the product**: per-script child classloader over the band loader (§6.3), prelude/host-binding injection at runtime, compile-always/run-explicit lifecycle, the output remap pass wired for real (not just M6's fixture) including safepoint injection + host kill switch (§19.3), compiled-script cache `(source hash, mappings hash, band)` (§15.5 D.3), run/stop commands via `CommandRegistry`, disposal — a re-run replaces the loader and nothing pins the old one | M5, M6 | a script authored in the editor runs on explicit command, effect observable in the harness; re-run replaces the instance; kill interrupts a deliberate infinite loop; 100 compile/run/dispose cycles leak no classloaders (heap assertion); the §5.3 proof — compile-and-run with the grammar jars absent, headless |
| **M8** | Decorations + diagnostics UI (§17): tracked ranges with stickiness, squiggle view part, Problems wiring | M0; M6 for real input | stickiness golden tests (Monaco's cases); squiggles stay attached while typing above them; Problems row ↔ document range round-trip |
| **M9** | Completion (§18): substrate generalisation, matcher+ranking ports, Java providers, type index + auto-import | M6, M8 | `list.forEach(x -> x.|)` completes String members; unimported `ArrayList` inserts import as one undo step; latency within budget on the indexed modpack fixture |
| **M10** | JS + sandbox (§16, §19): Rhino execution service (reusing M7's lifecycle/commands), parse diagnostics, runtime-introspection completion, member-lookup remapping (§16.1), policy object at all four layers | M5, M7 (execution substrate), M6 (Java resolver for interop), M9 (UI) | `class` syntax gets an engine diagnostic; post-run completion on a live object; a readable-name member call links in a fake-obfuscated fixture; refused type absent from execution *and* completion, one test proving both |
| **M11** | Resolver affordances + query-family tail: hover popup (`resolveAt` → the `Tooltip`/`Popover` substrate), go-to-definition (declaration site → open at range), `folds.scm` behind the existing `FoldingRangeProvider` SPI, `indents.scm` replacing the "line ends in `{`" rule (`insertNewlineWithIndent` already names its successor), GLSL diagnostics adapter over the shader compiler's existing error output | M3, M6, M8 | hover shows type + doc for a Java symbol; go-to jumps within the script; Java/GLSL fixture folds match the tree, not the indent; a GLSL error appears as a squiggle with no new machinery |

Critical path: M0 → M1 → M3, and M0/M4 → M5 → M6 → M7 → M8/M9 → M10 → M11. M2 is the early
visible win and touches none of it.

**Completeness contract**: every deliverable named in Parts II–IV either appears in a milestone
row above or is listed in §22 as deferred/refused. A future edit that adds a promise adds a row
or a §22 line in the same edit — an unscheduled promise is how v1's "after the batch" resolved
to nothing.

## 21. Governance and testing

The styling work's law — a rule that can be broken silently will be — applied to a stack that is
mostly invisible when wrong:

1. **Every capture has a colour**: scan shipped `highlights.scm` names (post-normalization);
   assert each has a `--syntax-*` token or a coloured general form, in every scheme. (v1 §6.1,
   still the single highest-value test here.)
2. **Vocabulary conformance**: normalized capture names ⊆ §10.1's set — a new grammar cannot
   introduce a name no scheme has heard of.
3. **Scheme pairing and scope**: extend the existing `StyleGovernanceTest` pair/scope checks to
   new schemes; extend `nothingIsDrawnInTheColourOfWhatItSitsOn` to `--syntax-*` vs `--editor-bg`
   (a dozen colours authored at once is exactly where that bug re-enters).
4. **Scheduler determinism** under manual clock (§7.2) — supersede, cancel, stale-discard, drain.
5. **Offset correctness on non-ASCII** — the test the UTF-8 layer never had; one fixture with
   accents, emoji and CJK asserting token ranges.
6. **Decoration stickiness goldens** — Monaco's boundary-insertion cases, all four modes.
7. **The §13 checklist as tests** — one JUnit method per row (generic substitution, overload
   phases, bridge filtering, accessibility-from-context, pattern-variable regions, lambda target
   typing), against ECJ bindings, headless.
8. **Native leak test** — §9.2 D.
9. **Sandbox symmetry** — a refused type is absent from execution, completion, hover and index in
   the same test.

Everything in `language/` tests headlessly — no GL, no MC. That is a consequence of the layer
rules, and it is also the enforcement of them.

## 22. Non-goals

- **LSP.** The seams are LSP-shaped so one could arrive; none is planned.
- **Hot swap** of running script instances (v1 §16.3: compile always, run explicitly, re-run
  replaces — unchanged).
- **TextMate/Monarch grammars**; re-litigating tree-sitter (§3).
- **User-supplied grammars at runtime** — grammars are vendored jars; a resource pack must not
  load native code.
- **IME/composition input, bidi/RTL caret movement, screen-reader support** — declared unsupported
  rather than discovered; each reaches `CgSystemInput` or the coordinate model and is its own plan.
- **Minimap, diff view, overview ruler** — consumers of §17.1, which reserves them a payload lane;
  not built here.
- **TypeScript-style JS type inference** — §16.2 is the contract.
- **`KeywordTokenizer` retirement** — it is the engineless fallback `core/`'s no-natives guarantee
  rests on; its javadoc says so (M4 re-checks that it still does).
- **Player-submitted scripts executing on a server** — permanently, per §19.1. Not a missing
  feature; a refused one.
- **Remapping reflection helper** for scripts that reflect on MC members (§15.5 D.1) — v1 declares
  it unsupported; the helper is the later fix if real scripts demand it.
- **Cross-version portability of MC-touching scripts** (§15.5 D.4) — a script that names MC
  classes is bound to that version's API; only host-API scripts are portable.

## 23. Verify before the milestone that depends on it

| # | Question | Blocks | How |
|---|---|---|---|
| 1 | UTF-16 encoding agreement across `parseStringEncoding`, `TSInputEdit`, and query-cursor byte ranges in the vendored binding | M1 | the non-ASCII fixture test, written first |
| 2 | `TSReader` chunked parse works (nice-to-have; String path is the fallback) | M1 | spike |
| 3 | Exact pinned versions per band: last ECJ line running on 8 and on 11; last Rhino on 8 (1.7.15) — and that the DOM adapter compiles against the oldest band's API | M5 | resolve artifacts, compile the adapter three times in CI-style toolchain matrix |
| 4 | Old-band ECJ (≤4.16 era) honours `setBindingsRecovery` well enough for §15.1's broken-code story | M5/M6 | the §13-checklist tests run against *each* band's jar |
| 5 | Classpath discovery on each loader (`LaunchClassLoader.getSources()`, Knot, ModDev, harness) | M6 | per-platform probe with a unit test where reachable; harness first |
| 6 | Fork-sync effort for upstream's css/js/html subprojects (upstream moved since the fork) | M3 | attempt css first — it is also the recipe-writing step |
| 7 | Type-index scale on a real large modpack (count, scan time, table size vs §7.3 budget) | M9 | measure during M9, not before |
| 8 | Rhino 1.7.15 ↔ 1.9.x API intersection for the single adapter (ClassShutter, scope, Context factory) | M5 | compile the adapter against both |
| 9 | Per-loader route to **post-transform class bytes** (1.7.10 `LaunchClassLoader` + transformer chain; Fabric launcher; Forge/Neo SecureJar) | M6 | probe per platform, 1.7.10 first — it is the hardest and the one that motivated §15.5 |
| 10 | Every band's ECJ accepts a custom `INameEnvironment` serving remapped/synthesized `IBinaryType`s | M5/M6 | the M6 remap-round-trip fixture, run against each band's jar |
| 11 | Mapping data sourcing and licences (1.7.10 MCP CSVs incl. `params.csv`, Mojang official mappings terms, Parchment, Fabric tiny) | M6 | resolve, cache strategy decided, recorded in `THIRD-PARTY.md` |
| 12 | Rhino member-lookup remapping route per band: patch `JavaMembers` in our shaded Rhino vs adopt KubeJS's fork (does it cover band 1?) | M5/M10 | compile both candidates against the band matrix; pick per band |
| 13 | Output remapper with inheritance propagation: adopt (tiny-remapper — does it run on Java 8?) vs write the propagation walk on plain ASM | M6 | spike; the M6 remap-round-trip fixture includes an override case either way |
| 14 | Safepoint-injection overhead (§19.3) on a hot script loop | M7 | measure; one volatile read per backward branch should vanish in JIT — verify, don't assume |

---

*v1's §12.1–§13.9 research content (import precedence, completion field rationale, the full Java
resolution checklist) is preserved in spirit above and in letter in git history; the checklist
rows live on as §21.7's tests. v1's decisions that survived verification — tree-sitter (§3),
vendored author queries (§12), the carve-out (§11.1), css-before-html (§12), module HQ +
scheduler + lifecycle + allowlist (§5–7, §19) — are incorporated rather than re-decided.*
