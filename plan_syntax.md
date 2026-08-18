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

**Progress**: M0–M2 complete, M3 three languages of four. See §1's progress note for what landed and
what was learned; milestone rows in §20 carry ✅ / ◐ / (blank).

---

# Part I — Ground truth

## 1. What exists — audited 2026-08-12, against code, not against v1's memory

> **Re-checked 2026-08-17**, at the end of M10 and most of M11. The table below is the state at the
> audit date and is left as written; what has landed since is recorded in each section's own ✅ marks
> and in §20's milestone rows, which are the live record. Rewriting the audit in place would destroy
> the thing it is for — a snapshot of what was believed before the work began.

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
| Grammars | ✅ **six: java, css, javascript, html, glsl, xml** (M3) | `lib/tree-sitter/`, 5 platform/arch pairs each; injections wired. `folds.scm`, `indents.scm` and `locals.scm` landed at M11 (§13) |
| Schemes | ✅ **Islands Dark/Light** (M2), default | authored from the exported `ij-scheme/`; Dark+/Light+ still shipped and selectable |
| Paint path | ✅ | captures → `::highlight(name)` → `CgStyleSpan`; per-view-line clipping in `refreshHighlights` |
| **Background work model** | ✅ **M0** | `com.crystalgui.core.async` — lanes, keyed single-flight, debounce, cancellation, drain-on-tick |
| **Tracked ranges (decorations)** | ❌ still does not exist | every range-owner is bespoke; nothing survives an edit except by re-derivation. **M8** |
| **Per-row token cache** | ✅ **M1b** | keyed by model row; idle frames and scroll-back ask nothing |
| **Semantic layer** (resolver, diagnostics, completion) | ❌ does not exist | no SPI, no engine, no UI |
| Bold/italic in `::highlight()` | ❌ deliberately refused | `HighlightStyle.ALLOWED` = `{color, background-color, text-decoration-line}`; §11 carves the editor exception |

The remaining ❌ rows are the foundation work still outstanding. Everything else is filling in.

> **Progress, 2026-08-12.** M0–M3 landed. Keystroke cost on a 5,000-line file went **16.9ms →
> 0.63ms**, inside the §7.3 budget. The editor is painted in Islands Dark, and six languages have
> real grammars with injections wired.
>
> Four bugs in the capture→colour seam were found and fixed along the way, and they are worth
> remembering as a class rather than individually: **the harness never had tree-sitter on its
> classpath** (so a whole round of scheme tuning was aimed at a grammar that was not running);
> **predicate-carrying query patterns never fire** through this binding, which silently deleted the
> SCREAMING_CASE test that identifies a constant; **the dotted general-form fallback overrode instead
> of falling back**; and **capture precedence came from emission order rather than pattern index**,
> which is why fixing constants kept breaking method declarations and back again.
>
> Every one of them was invisible when wrong — the colours resolved correctly the whole time, and it
> was the *tokens* that were wrong. The lesson that generalises: **when highlighting looks wrong,
> dump the captures and the resolved colours before touching a scheme value.** Two rounds were lost
> to reasoning from screenshots.

## 2. Facts that died under verification

Each of these was asserted in v1 and is wrong. They are recorded because each one, built on,
would have failed late instead of early.

| # | v1 claimed | Verified truth (2026-08-12) | Consequence |
|---|---|---|---|
| 1 | "Rhino spans 8→25" | **Rhino 1.8.0+ requires Java 11.** 1.7.15 is the last Java-8-capable release. 1.9.x is current | no single Rhino artifact spans the range → version banding, §6 |
| 2 | "The ES5.1 trap mostly closes" with modern Rhino | `let`/`const`, arrows, template literals, destructuring: yes. **ES6 `class` syntax: still unimplemented** (mozilla/rhino#835, open). **ES modules: unsupported** | the grammar-ahead-of-engine gap is permanent for JS; §16 turns it into diagnostics instead of pretending it closes |
| 3 | "ECJ runs on 8 and 25" | **ECJ ≥ 4.28 (June 2023) requires Java 17 to run.** The 4.17–4.27 line runs on 11. Only the ≤ 4.16 era (mid-2020, compiles up to Java 14) runs on 8 | same consequence: banding, §6 — and it is *fine*, because a Java 8 host cannot load newer bytecode anyway |
| 4 | "ECJ is a single ~3MB jar" exposing `ITypeBinding` | the slim `org.eclipse.jdt:ecj` jar is the **batch compiler only — no DOM, no bindings API**. `ASTParser`/`ITypeBinding` live in `org.eclipse.jdt:org.eclipse.jdt.core` plus a handful of transitive `org.eclipse.platform` jars (~10–15MB total) | real dependency weight; isolated classloader per band, §6.3; never near `core/` |
| 5 | "each grammar needs a subproject added to the fork" — priced as the bulk of steps 3–5 | upstream `tree-sitter-ng` **already ships `tree-sitter-css`, `tree-sitter-javascript`, `tree-sitter-html`** (31 grammars, Zig cross-compile, 6 platforms, plus a codegen task for new ones). Only **GLSL** and (later) **XML** were genuinely new | grammar cost collapses: three languages are a fork-sync and a build; two are codegen'd subprojects. §12 |
| 6 | (unexamined) tokenizer converts every offset UTF-16↔UTF-8 | the binding exposes `parseStringEncoding(tree, source, TSInputEncoding)`, so this looked like a free win — **and it does not work.** Measured 2026-08-12 against the Java grammar: both `UTF16LE` and `UTF16BE` report a byte length matching the *UTF-8* encoding and produce a tree containing `ERROR` nodes, i.e. the string reaches the native side as UTF-8 whatever it is told | the conversion layer **stays** and is made fast instead (ASCII fast path + per-line index). See §9.2 A |

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

### 5.1 `syntax-treesitter/` → `language/` ✅ done (M4)

Renamed (decided v1 §16.1): package `com.crystalgui.language`, the HQ for everything below L3 that
is not `core/`'s interfaces. Sub-packages by concern so a later split is a move, not an untangling:

```
com.crystalgui.language
  .grammar      TreeSitterTokenizer (rewritten, §9), query loading, injections, folds/indents/locals
  .java         the ECJ adapter: compile, bindings, diagnostics, semantic tokens, completion providers
  .js           the Rhino adapter: execution service, parse diagnostics, runtime introspection
  .resolve      engine-neutral: type index, import table, fuzzy matcher, ranking, sandbox policy
```

> `.grammar` exists; the other three are named in `language/build.gradle.kts`'s header and are empty
> until their milestone. Creating them now would be three empty directories asserting work that has
> not started.

**One thing landed that the plan did not ask for, and it is the reason the rename was worth doing on
its own schedule.** `TreeSitterLanguages` held six near-identical `registerExtensions` blocks and
`TreeSitterTokenizer` six near-identical factories, with the per-language facts split across both —
parser, query directory, `Language`, extensions, injections. Adding XML meant getting the same six
facts right in two files, with nothing but care stopping a mismatch. They are now rows in a
`Grammar` enum: **a seventh language is one row**, and the two consumers cannot disagree because
there is nothing left to disagree with. Three tests pin the table itself (every row registers every
extension it claims; no two rows claim one extension; an injecting row names grammars we ship).

> The parser is held as a `Supplier`, which is load-bearing rather than tidy: an enum constant's
> fields are built at class-init, so holding `TSLanguage` instances would load **every** native the
> first time anything touched the table — including a lookup for a language the process never opens.

### 5.2 What `core/` gains *for the language stack* (SPIs only — the full list, so scope creep is visible)

> This is the language stack's footprint in `core/`, and it is interfaces only. It is **not** a claim
> that `core/` gains nothing else: §7's scheduler and `TextBuffer.version()` are general infrastructure
> that predate any language work in kind — file listing and shader compilation want both — and they live
> in `core/` because the dependency runs one way. `core/` cannot reach into `language/`, so anything
> `core/` itself consumes cannot live there.


`com.crystalgui.text.syntax` already holds `SyntaxToken`/`SyntaxTokenizer`. A sibling package
`com.crystalgui.text.lang` gains the L2 contracts:

| Interface | One-line contract | State |
|---|---|---|
| `Diagnostic` | `(range, severity, message, source, code?)` — immutable value | ✅ **already existed** in `text.diagnostic`, LSP-shaped, with a per-owner `DiagnosticSet`. Not rewritten, not mirrored |
| `SemanticTokenProvider` | produces `SyntaxToken`-shaped spans in the same capture vocabulary | ✅ M4 |
| `Resolver` | `resolveAt(offset)`, `expectedTypeAt(offset)`, `membersOf(type, callingContext)` — all async, all versioned | ✅ M4 |
| `CompletionProvider` | `(request) → CompletionList{items, incomplete}` + `resolveItem(item)` | ✅ M4 |
| `CompletionItem` | the LSP field set (§18.2) including `additionalTextEdits` | ✅ M4 |
| `LanguageServices` | per-**document** façade bundling the above; lifecycle follows the document, not the editor — two tabs share it, closing the document drops it | ✅ M4 |
| `CodeActionProvider` | `(range) → List<CodeAction>`, each a `ChangeSet` stamped with the version its offsets were computed against | ✅ M9.5-era, consumed by Alt+Enter |
| `IndentationProvider` | `levelsAfterRow(document, row)` → an indent **level**, or `-1` for no opinion | ✅ M11 §24.4. Levels rather than characters, so a provider need not know whether this file uses tabs — that is `IndentStyle`'s question and it already owns it |
| `SourceChecker` | `(name, source) → List<Diagnostic>` — a producer that is **not** a language engine | ✅ M11 §24.6. The shader compiler answers one question and is none of the other things `LanguageServices` is; `core/` may hold this because it names nothing |

`TextEditor` consumes `LanguageServices` if present and behaves exactly as today if absent. That
absence *is* the dedicated-server story and the feature flag; there is no other flag.

**Three corrections from building it**, each of which removed something the plan had budgeted for.
*(A fourth, from M11: a producer that is not a language is a **fourth** shape beside the two async ones
below — it neither pushes per-line colours nor answers a caret question, it checks a whole document
slowly and files the result. `SourceChecker` is that shape, and the reason it is not a
`LanguageServices` is that a shader compiler cannot answer any of the rest: it cannot tell a parameter
from a local, and a `.glsl` include fragment is not a translation unit it can be handed at all.)*

1. **`Diagnostic` was already there**, and so was the per-owner `DiagnosticSet` that independent
   engines need. An engine publishes with `set.changeOne(services.id(), list)` and the Problems
   panel, the inspection widget and the status bar all read it through paths that already work. So
   `LanguageServices` has **no** `diagnostics()` accessor — mirroring the list would be two copies
   with no rule about which is authoritative. Likewise `Change` is already LSP's `TextEdit`, and
   `SymbolKind` serves as `CompletionItemKind`, so neither needed inventing. The rule that produced
   all three: **an SPI that duplicates a type the codebase already has is worse than one that
   reuses it**, because the two drift and no caller can tell which is authoritative.

2. **There are two async shapes, not one, and "all async" hid the distinction.** Continuous
   background analysis **pushes** with an invalidation range — `SemanticTokenProvider` mirrors
   `SyntaxTokenizer` exactly, so the per-row cache from M1b works on it unchanged. A user-initiated
   question **requests** with a callback *that may never fire* — a superseded hover must be able to
   produce no answer at all, which a future cannot express without being completed with something.
   LSP splits these the same way (`publishDiagnostics` is a notification, `hover` is a request), and
   getting it wrong would have meant either putting a viewport query back on the frame or leaking a
   promise per keystroke.

3. **A type must not cross the seam as a string.** `membersOf(String typeName, …)` was the obvious
   signature and it is lossy in the one direction that matters: an engine holding an
   `ITypeBinding` would stringify to answer `resolveAt` and have to parse it back to answer
   `membersOf`, so `List<String>` survives as text and its members come back as `E get(int)`. Hence
   `TypeRef` — an interface exposing only `displayName()`/`qualifiedName()`, with the engine's own
   binding intact behind it. `TypeRef.of(name)` is there for the engines that genuinely have nothing
   more (JavaScript's runtime introspection, a test fake).

**And one bug the reshape uncovered rather than introduced.** Nothing in the application had ever
called `SyntaxTokenizer.close()`. The method has existed since the seam did and the tree-sitter
backend's own test opens and closes a hundred documents to prove it releases natives — but
`OpenDocuments.close` disposes a document only when it implements `Disposable`, and
`TextFileDocument` did not. Every text document's parse tree, parser and query cursor survived until
the process ended. "Lifecycle follows the document" is the fix for both that and `LanguageServices`,
which is why they are one change.

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

✅ **Pinned and measured at M5.** Every version below is the newest whose *base* class files sit within
the band's ceiling, found by reading the class-file major out of the published jar rather than from a
compatibility statement. The table's shape survived; three of its numbers did not.

| Host JVM | `org.eclipse.jdt.core` | Compiles up to | Rhino | ES level |
|---|---|---|---|---|
| 8–10 | **3.26.0** (Eclipse 4.21, 2021-09) | **JLS16** (target ≤ host, so Java 8 in practice) | **1.7.15.1** | most of ES2015 minus classes/modules |
| 11–16 | **3.33.0** (Eclipse 4.28, 2023-06) | **JLS19** | **1.9.1** | ES6 default level, still no classes/modules |
| 17+ | **3.46.0** (newest, 2026-08) | **JLS26** | **1.9.1** | best available |

**Four corrections, all in our favour except the last:**

1. **Band 8 gets JDT 3.26.0, not the "≤ 4.16 era" the plan expected** — five more releases of fixes on
   the oldest band, and JLS16 rather than the budgeted Java 14. (The ceiling argument still caps what a
   Java 8 host can *execute* at Java 8; the extra levels buy analysis, not execution.)
2. **"ECJ ≥ 4.28 requires Java 17" was wrong.** 4.28 *is* 3.33.0 and runs on Java 11 fine; **3.34.0**
   (4.29) is where 17 becomes mandatory. §2 row 3's claim was off by one release.
3. **Rhino 1.9.1's class files are Java 11, not 17**, so bands 11 and 17 share it: three bands, two
   Rhinos. And 1.7.15 has a patch — **1.7.15.1** — which is the actual last Java 8 release.
4. **The JLS level is discovered, never named** (§6.3's mechanism, now real as `JlsLevel`). Each band's
   `AST.JLS*` constants are read reflectively and the highest non-deprecated one wins. Naming `JLS21`
   would not compile against band 8; naming `JLS8` would compile everywhere and silently cap band 17 at
   Java 8 syntax, which is worse because it *works*.

> **The one that cost real time, and it is not about versions at all.** `org.eclipse.jdt.core` declares
> its platform dependencies as **open ranges** — `[3.14.0,4.0.0)` — so pinning jdt.core alone is not a
> pin. Band 8 resolved `org.eclipse.osgi-3.24.200` and `jna-5.18.1` beside it: 2024-era jars at class
> major 53+, which cannot load on Java 8 at all. **The top artifact was correct and the closure was
> unloadable**, and it would have failed only on a Java 8 host — nowhere near whoever built it. It also
> means the same build resolves differently in six months with no commit to blame. Every transitive
> platform artifact is therefore pinned explicitly per band (13 of them), and `checkEngineBands` runs as
> part of `:language:check`, re-deriving each jar's floor from the bytes and failing the build.

> **And then the fix's own fix, which is the more interesting bug.** Pinning each artifact to *the newest
> version that loads on Java 8* is mechanically right and semantically wrong: **Eclipse rotated its
> signing certificate between 4.19 and 4.20**, and the `org.eclipse.core.runtime` package is split across
> `org.eclipse.core.runtime`, `org.eclipse.equinox.common` and `org.eclipse.equinox.registry`. The
> per-artifact rule took jars from both sides of the rotation, and **a JVM refuses a package whose
> classes come from differently-signed jars**. Everything resolved, every class file was major 52, the
> ceiling check was green — and Java 8 threw `SecurityException: signer information does not match` on
> the first `ASTParser` construction. Nowhere else, because that pairing can only arise in band 8.
>
> So `checkEngineBands` also groups classes by package and compares signing certificates, and pinning is
> now constrained by *era* as well as by class-file major. Two things about writing that check are worth
> keeping:
> - **`JarEntry.getCertificates()` is the obvious API and it answers null** unless the entry's stream has
>   been fully drained — and answered null even then for several of these jars. A check built on it
>   reported every Eclipse jar as unsigned and passed unconditionally. The PKCS#7 block is read directly
>   instead.
> - **Hashing "the first certificate" made the check pass on the exact pin it was written to catch.** The
>   block holds the leaf *and* its issuers, `generateCertificates` does not promise leaf-first, and both
>   Eclipse eras share one DigiCert intermediate. It hashes the whole chain, order-independently. Proven
>   by putting the bad pin back and watching it fail — a verification check that has never been seen to
>   fail is a verification check that does not work, and this one silently did not, twice.

### 6.3 One adapter, isolated classloaders

- **One adapter per engine**, compiled against the *oldest* band's API. ✅ **Verified at M5** rather
  than assumed: `EngineApiSurfaceTest` loads each band's real jars and asserts the whole surface the
  adapter will use — `ASTParser` including `setBindingsRecovery`/`setStatementsRecovery`/`setEnvironment`,
  `ITypeBinding` including `isAssignmentCompatible`, and on the Rhino side `Context.setClassShutter`,
  `ClassShutter.visibleToScripts`, `ContextFactory` and `VERSION_ES6`. That closes §23 rows 3 and 8.
  The JLS level is chosen at runtime by `JlsLevel.highestAvailable`, so the adapter names no level.
- **Each engine loads in an isolated, child-first classloader** over its band's jars ✅ —
  `EngineClassLoader`. Three things fall out: no dependency clash with mods that ship their own Rhino
  (several do), the sandbox has a natural enforcement point, and an engine can be dropped wholesale.
  - **Child-first needs a parent-delegated list, and that list is the design.** A loader that asked its
    own URLs for *everything* would load a second copy of the types host and engine must share, and a
    correct cast then fails with `ClassCastException: X cannot be cast to X`. So the JDK and one
    **bridge package** delegate upward; everything else does not. Anything added to that list stops
    being isolated, which is why it is a constant with a warning on it rather than a convenience.
  - **The adapter is loaded by the child, not the host** — it references engine types directly, so only
    a loader that can see them can load it. Host loads the bridge interface, child loads the
    implementation, and they meet at the one package they share.
  - Resources follow the same rule: ECJ reads `messages.properties` for every diagnostic, and
    parent-first there finds the host's copy if it has one — diagnostics rendering as raw keys rather
    than anything that throws.
- Band selection is one `System.getProperty("java.specification.version")` read at startup ✅
  `EngineBand.detect()`. **The legacy spelling is the trap**: Java 8 reports `"1.8"`, so a naive parse
  reads **1**, which is below every band's minimum and therefore *still selects band 8* — correct by
  accident, and wrong from Java 9 onward, where `"9"` parses on a different scale.
- **Where the jars come from is a separate question** — `EngineSource`, because the answer differs per
  platform (bundled in a mod jar, a loader's `libraries/`, a Gradle configuration in a dev run, absent)
  and none of those changes which version is correct. `EngineSource.NONE` is a real deployment.

> ✅ **The seam carries traffic — a script compiles and runs.** Built at the end of M5 rather than left
> to M7, because a bridge nothing has crossed is a bridge nobody has checked. `ScriptCompiler` is the
> bridge interface, `EcjScriptCompiler` the child-loaded adapter, `JavaEngine` the one place they meet,
> and `ScriptClassLoader` a **host-owned, parent-first** loader for the produced bytes. Seven tests plus
> the per-band smoke: a script returns a value, uses the JDK, **calls back into a host class**, brings
> back its nested and anonymous classes, fails legibly when broken, and is *replaced* rather than
> accumulated on re-run — while the host still cannot name a single ECJ type.
>
> **Three findings worth keeping:**
> 1. **`javax.tools` is not available.** ECJ's `EclipseCompiler` — its `JavaCompiler` implementation,
>    and the obvious way to compile in memory through a standard API — **is absent from band 8**, along
>    with the `META-INF/services` registration. An adapter written against it compiles, passes on a
>    modern JVM, and fails on the one host banding exists for. `BatchCompiler` is public and present in
>    all three, so that is what the first working path uses.
> 2. **Compiled bytes cross the bridge, not `Class` objects.** A script must load in a loader the *host*
>    owns and can drop, or every version of every script is pinned for the engine's lifetime — and the
>    engine is meant to be long-lived. This is what makes M7's "re-run replaces" achievable rather than
>    retrofitted; the test asserts the two runs are genuinely different types.
> 3. **`ScriptClassLoader` is parent-first — the opposite of `EngineClassLoader`, for the opposite
>    reason.** The engine must beat the host's classpath; a script must never beat it, or a script class
>    could shadow the API it was handed.
>
> **What this is not**: the compiler the editor will use. `BatchCompiler` writes class files to a temp
> directory, which is fine for "run this" and far too slow per keystroke. M6's path is
> `org.eclipse.jdt.internal.compiler.Compiler` with an `ICompilerRequestor` collecting bytes and a
> custom `INameEnvironment` supplying types — which is also exactly where §15.5's obfuscated-name
> mapping hooks in, and where §23 row 10 gets answered. All three are present in every band (measured).

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

✅ **Recorded at M5**, with the two things the one-liner above left out. Both licences are **file-level**
copyleft, so the obligation attaches to *modifying* their files — consuming them as unmodified binaries
across a classloader boundary is what keeps them out of our source, and that is a consequence of the
isolation `EngineClassLoader` exists for rather than a coincidence. And band 17 pulls **JNA**
(Apache-2.0 / LGPL-2.1 dual; we take Apache-2.0), which was not in the plan's accounting at all.

> **Nothing distributes an engine yet**, so this is an obligation in waiting: the configurations resolve
> for tests and for the band-floor check, and both loader modules are commented out of
> `settings.gradle.kts`. The licence texts have to travel in the jar the moment one of them bundles a
> band — said in `THIRD-PARTY.md` too, because a row in a table is an index and not a discharge.

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

**A. Make the conversion fast — UTF-16 parsing is not available.** ~~Parse UTF-16.~~ Probed and
disproven (§2 row 6): the binding accepts the encoding argument and ignores it. So the conversion
layer stays, and the actual defect — that it was O(n) *per token* — is fixed directly, in
`Utf8Offsets`: an **ASCII fast path** (checked once per parse; source code is overwhelmingly ASCII,
and when it is both directions are the identity) plus a **per-line index** otherwise (two ints per
line; a conversion binary-searches the line then walks within it). Memory is sized by line count
rather than character count. The non-ASCII fixture test is written first regardless — it is what the
old code never had, and it must assert captured *text*, not merely that ranges are in bounds, since
a range shifted by the encoding delta is still a valid range.

**B. Reparse off-thread, double-buffered — Zed's actual model, which v1 only cited.**
- On edit (UI thread, synchronous, cheap): apply `TSInputEdit` to the *current* tree —
  interpolation, so existing highlights move with the text this frame — and schedule a reparse
  (`LATENCY` lane, keyed, superseding).
- Queries between keystroke and reparse-landing run against the interpolated old tree: structurally
  stale, positionally correct — exactly what every editor shows for those ~30ms.
- The reparse job snapshots `(rope, version)`, parses off-thread, and lands `(newTree, version)`
  through the drain. Landing swaps the buffer's tree and invalidates the token cache for lines
  whose tokens changed (tree-sitter's `changedRanges(old, new)` gives them precisely).

**C. A per-row token cache in the editor.** ✅ **Landed.** Measurement said it was required rather than
optional: the async path cost ~4.2ms avg on the UI thread against a 2ms budget, and the remainder was
almost entirely the viewport query itself (**3.3ms each**, paid on every keystroke and every scroll
step). Interning the capture names — thousands of JNI `String` builds per query — moved it under 2%,
localising the cost to tree-sitter's own `exec`, so nothing but not asking could remove it.

Keyed by **model row**, not view line, which is what makes it survive folding, wrapping and resizes
with no invalidation at all — those change which view line a row is drawn on and change nothing about
the row's tokens. It is also `measuredRows`' key, so both invalidate on one rule. Offsets are stored
row-relative so an edit on one row does not shift every entry below it. A row present with an empty
list means "asked, nothing there", distinct from absent — conflating them re-queries blank lines
forever.

Invalidation follows `measuredRows`' rule: one row at a time **only** when the edit left the line
count alone, because adding or removing a line renumbers every row below. On a reparse landing,
`TSTree.getChangedRanges(old, new)` gives the changed span precisely — which is why the invalidation
callback carries a range rather than being a bare "something changed": during a run of typing a
reparse lands every few keystrokes, so re-querying the viewport each time would have bought almost
nothing.

Measured after (5,000-line file, 100 operations each):

| | queries | text asked about |
|---|---|---|
| idle frames | **0** | 0 |
| scroll steps | 8 | 344 chars |
| scroll **back** over seen rows | **0** | 0 |
| keystrokes | 100 | ~91 chars each — one row, not a viewport |

Row-sized query **0.14ms** against a viewport-sized **3.7ms**. **Keystroke on the UI thread: 4.2ms →
0.63ms**, inside the §7.3 budget. Pinned by `EditorHighlightCacheTest`, which counts queries rather
than timing them — an integer is not flaky on someone else's machine.

Steady state: painting reads compact per-line arrays
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

> **Landed at M3, and it is not a map.** `Queries` applies **seven load-time rewrites**, and only
> one of them (`@delimiter` → `@punctuation.delimiter`) is the string→string substitution this
> section imagined. The other six are *structural*, because the grammars disagree about more than
> spelling:
>
> | Rewrite | Why a name map cannot do it |
> |---|---|
> | `splitMethodDeclarationsFromCalls` | Java's query gives declarations and invocations one capture; the split is by **node type**, not by name |
> | `captureBinaryLiterals` | `binary_integer_literal` is a node the author's query simply never names — nothing to rename |
> | `captureObjectLikeDefines` | Adds a SCREAMING_CASE `@constant` rule and `preproc_params`/`preproc_extension` captures to the C family |
> | `promoteBuiltinTypes` | `primitive_type` → `@type.builtin` is a *predicate-free* promotion the authors leave flat |
> | `liftUnambiguousPredicates` | The binding evaluates no predicates at all, so `#match?` patterns silently never fire — they are lifted into Java regex at load and evaluated by us |
> | XML's `@property`-for-attribute-name | **Deliberately not folded** — this grammar already uses `@attribute` for something else, so the fold would collide rather than translate |
>
> The correction that matters for future grammars: **budget for a per-grammar rewrite pass, not a
> per-grammar rename table.** The rewrites are still data-shaped, still tested one method each
> (`LiftedPredicateTest`, `NumericLiteralsTest`), and still keep the vendored file untouched — but
> a `Map<String,String>` would have delivered one of seven.

## 11. Schemes — Islands Dark, and the font-style carve-out

### 11.1 Step 0 — the carve-out ✅ done (M2), and cheaper than planned

Islands italicises comments and bolds keywords; `HighlightStyle.ALLOWED` refused `font-style`/
`font-weight` because a synthetic-bold highlight reflows wrapped text.

**Landed differently from the plan, in both directions.** There is **no `ALLOWED_IN_EDITOR`**: the
two properties are simply in `ALLOWED`, one rule everywhere, because the spec's premise is false
here. On the web a highlight is a pure overlay over already-laid-out glyphs, so a wider face could
move the text it highlights; in this engine a highlight **already re-shapes** (a span boundary is a
shaping-run boundary), so the restriction protects a property we do not have. Allowing them and
then dropping them where reflow is possible would be the *resolves-but-paints-nothing* class this
file exists to prevent — and a scoped variant is a rule somebody has to remember.

**And the `TextEditor` migration was unnecessary.** The plan budgeted moving its draw calls onto
styled paragraphs including the measurement caches; in fact editor lines are already `UIText`
elements and `UIText` *is* the styled path, threading the element's weight through every
`CgStyleSpan`. The only gap was the **highlight's own** weight — two entries in `ALLOWED` and one
line in `toCgSpan`. The caret-drift trap was never reached.

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
| `css`, `javascript`, `html` | ✅ **done (M3)** — two `include` lines in the fork's `settings.gradle`, a `jar` task, vendor the jar and the author's query | cheaper than priced: the natives were already built for all five platform/arch pairs, and `downloadSource` supplies `queries/` intact |
| `glsl` | ✅ **done (M3)** — the fork had no subproject, so it was generated from `tree-sitter-grammars/tree-sitter-glsl` (the C parser; the lib.rs crate is Rust packaging of the same grammar — not usable, named so nobody reaches for it twice) and cross-compiled | the one real build task, and it came in at the priced cost. `downloadZig` fetches its own toolchain; ~3 min for five targets |
| `xml` | ✅ **done (M3, unplanned)** — same codegen recipe, plus the multi-grammar trap below | an hour, as the README predicted for the second generated grammar |

**Six grammars ship**, each with all five platform/arch pairs. Three build traps are recorded in
`lib/tree-sitter/README.md` because each cost real time and none is discoverable from the error:

1. The generator emits `implements TSLanguage` where this fork's `TSLanguage` is a **class**, and a
   publishing block wanting `ossrhUsername`. Both fail at compile time, so neither is subtle.
2. **`buildNative` must run before `jar`**, and `jar` does not depend on it — building only the jar
   produces one with no natives inside and *no error*.
3. **A multi-grammar repo needs its sources pointed at.** `tree-sitter-xml` ships `xml/` and `dtd/`
   with no top-level `src/`, so the default glob found no parser, linked a native containing
   nothing, and failed as `undefined symbol: tree_sitter_xml`. `BuildNativeTask` exposes
   `additionalCFiles`/`additionalIncludeDirs` for exactly this.

**Injections** ✅ **done (M3)**: host tree → `injections.scm` → child parser per injected range
(tree-sitter's included-ranges API) → merged token list. Entirely inside `language/.grammar`;
`SyntaxTokenizer`'s flat document-offset token list was already the right return shape, so no
`core/` change was needed — the prediction held.

**One thing the plan did not anticipate**: the injected sub-parse is *not* recursive by
construction, and making it so would be a re-entrancy question, not a loop. `appendInjected` runs
one level — HTML hosting CSS and JS — which is every case the six shipped grammars produce. A
grammar that injects into an injection (markdown → html → js) would need the depth guard that does
not exist yet, and the tokenizer says so at the method rather than leaving it to be discovered.

## 13. The other query families

A grammar directory is a folder of queries, loaded uniformly — plan the loader once:

| Query | Feeds | When |
|---|---|---|
| `highlights.scm` | §9–10 | ✅ M3 |
| `injections.scm` | §12 | ✅ M3 |
| `locals.scm` | within-file scope colouring — `variable.parameter` vs `variable.member` with **no engine at all** | ✅ **M11** — `LocalScopes`. Grammar-tier, so an engine still outranks it; and within that tier it refines only the **catch-all**, since `PI` is `@constant` because a rule tested its spelling and a `@local.definition.var` arriving later would overwrite it with `variable` purely by being last |
| `folds.scm` | syntax-aware folding, behind the existing `FoldingRangeProvider` SPI | ✅ **M11** — on `TreeSitterTokenizer`, which already owns a tree; a separate provider would mean a second parser and a second reparse per keystroke for one document |
| `indents.scm` | a real indent engine replacing the "line ends in `{`" rule (`TextEditor.insertNewlineWithIndent` named this plan as its successor) | ✅ **M11** — `IndentationProvider` + `TreeIndents`, Neovim's dialect. `@indent.align` is read and **ignored**: it needs a column rather than a level, so wrapped argument lists indent one level in rather than aligning under the bracket |

> **All three are vendored from nvim-treesitter under Apache-2.0, and the loader rule above does not
> answer for them.** "Take the grammar author's own file" is right for `highlights.scm` and
> `injections.scm` and applies to none of these: upstream grammar repos ship highlights and tags, and the
> richer families live in editor *runtime* repos. So it was a licence choice rather than a provenance one
> — Helix's indent dialect is smaller and better specified, and nvim-treesitter is the only source of
> maintained files for all six languages under terms this repository already satisfies. `THIRD-PARTY.md`
> carries the notice and the statement of modifications. Two deviations are recorded there: the
> `; inherits:` chain is resolved by concatenation at vendoring time, and upstream's ECMAScript
> `locals.scm` captures **no parameters at all**, so three patterns are added at load.

---

# Part IV — The semantic layer

## 14. The seams

### 14.1 Diagnostics

`Diagnostic(range, severity, message, source, code?)`. Producers: ECJ (Java), Rhino's parser (JS —
authoritative for "will this engine accept it", which is the *answer* to the grammar-ahead-of-engine
gap), Rhino's **runtime** (JS — a thrown exception, on its own line, ✅ M10.5), and the shader compiler
(GLSL, ✅ M11 §24.6). Consumers: the squiggle pass, the gutter, the Problems panel — **which already
exists and already renders severities; it is wired, not built.**

> **"Same seam, no new machinery" was true of this side and false of the other.**
> `CgShaderParseException` named the *file* and nothing else, with no line field and none of its sixty-odd
> throw sites carrying one — so an adapter could only report everything at line 1, which points a squiggle
> at innocent text. The backend gained a position first (placed once, on the way out of `parse`, by
> locating the token the message already quotes). Two further corrections: the parser throws on the
> **first** violation and has no collecting mode — `--mode=shader-compile-audit` collects across *files*,
> not within one — and real driver errors arrive on the GL thread and are not available to a background
> check at all, so what is checked is the `.shader` **format**. `SourceChecker` is the seam and
> `CheckedDocument` the debounce and version gate; `core/` names no CrystalGraphics type.

### 14.2 Semantic tokens

Same value shape as `SyntaxToken`, same vocabulary (§10.1), produced per line by an async provider.
**Merge rule: semantic wins over grammar on overlap**, applied where the per-line cache is read —
one merge path, and an LSP could slot into it unchanged later. Staleness: keep-per-line (§8).
With ECJ bindings behind it, Java gets what v1's §3.3 marked unreachable: field vs local vs
parameter, unresolved symbol, deprecated (struck through — `text-decoration-line` is already
allowed in highlights). ✅ And since M11 the engineless half is real too: `locals.scm` separates
parameter from local from field with no engine anywhere, which is what the scheme header's
honest-subset note was waiting for.

> **The merge rule has a third tier now, and the order is load-bearing.** Grammar tokens, then
> `locals.scm` refining *only* the catch-all among them, then semantic tokens replacing whatever they
> overlap. Getting the middle one wrong is invisible in the worst way: both names resolve to real
> colours, so a scope answer overwriting a `@constant` reads as a colour-scheme bug rather than an
> ordering one.

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

✅ **The checklist is now twelve tests** (`BindingChecklistTest`), and the trade held on every row:
`List<String>.get` answers `String`; nested arguments survive (`Map<String, List<Integer>>.get` →
`List<Integer>`); overload resolution picks by argument type and puts **widening before boxing**;
the compiler-generated `compareTo(Object)` bridge is filtered out; a library type's private fields
are absent while its public members are present; inherited members are walked; a lambda parameter is
typed from its target; an `instanceof` pattern variable is typed inside its scope. **And all of it
again on source truncated mid-statement**, which is the row the whole works-while-typing story rests
on. Nothing on that list is implemented in this repository — which was the point.

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

✅ **Built** (`ReflectionOverlay`). A `Class` is read reflectively and a class *file* is synthesized
with the same supertypes, members and signatures and **no method bodies** — the compiler resolves
against the stub, the JVM links against the real one, and nothing has to generate code. The
load-bearing test is §16.3's actual question: **a script written against a type a previous script
defined**, which exists, has no file anywhere, and is on no classpath.

Three decisions inside it worth naming:

- **"Already resolvable" is decided by the code source, not the package name.** A class from a jar or
  a directory has a `CodeSource` with a location; one defined from bytes does not. That answers
  correctly for a script's class and for a runtime proxy regardless of what they are called, where a
  package-prefix test would not.
- **Generic signatures are reconstructed, not erased.** Reflection retains them, and emitting only
  descriptors would make a script's own type raw — so `get(0)` would answer `Object` where the author
  wrote `List<String>`, the script would *still compile*, and completion would be useless on exactly
  the types scripts define. The rebuild is mechanical (JVMS 4.7.9.1 from `java.lang.reflect`) and it
  is the difference between the overlay being useful and being a fallback.
- **Supertypes are stubbed transitively.** A stub whose superclass cannot be found is worse than no
  stub: the compiler reports an error about a class the author never mentioned.

The merge order is the caller's — the overlay's directory goes first on the classpath, so where a
type is both on disk and live, the live view wins.

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

> ◐ **The mapping boundary is built and proven; the live name environment is not.**
>
> ✅ **Built** (`com.crystalgui.language.map`): `MappingSet` — readable ⇄ runtime, keyed by owner
> because a member name is only unique within its declaring type, and `IDENTITY` as the common case so
> a dev environment pays nothing and takes the same path. `ReadableView` — the **in** direction,
> generating readable types by remapping runtime bytes. `InheritanceAwareRemapper` — the **out**
> direction, ~180 lines on plain ASM (§23 row 13).
>
> ✅ **The round-trip is nine tests.** A script authored in readable names compiles, links and runs
> against a fixture whose runtime members are `m_1234`/`f_5678`; a script naming the runtime spelling
> is *refused*, which is what makes it one authoring namespace rather than two; and an override stays
> an override — **with a negative control against a real plain `ClassRemapper`**, which returns the
> wrong string with no exception, no verify error and nothing to search for.
>
> > Writing that control taught something worth keeping. Its first form was our remapper with the
> > hierarchy blinded, and it **did not fail** — `withLocalClasses` always reads the supertypes of the
> > classes being remapped out of their own bytes, which is correct and load-bearing (it is what makes
> > a script's nested class extending an MC type work) and meant the control proved nothing. Its
> > second form failed *loudly* with `NoSuchMethodError`, because calling through `new Script()` makes
> > the call site's owner the script class too. Only calling through a mapped-type reference isolates
> > the override as the single difference. **A negative control that cannot fail is the same trap as
> > an assertion that cannot** — the M5 lesson, arriving in a new shape.
>
> ❌ **Still outstanding: the live name environment — now scheduled as M12.** `ReadableView` writes remapped classes to a
> directory and hands the path to the compiler, because `ASTParser.setEnvironment` takes file paths.
> That is correct anywhere bytes are obtainable and it is what the round-trip proves. It is **not**
> what a live MC host needs: there the bytes come from the launch classloader through the transformer
> chain, per platform, and feeding them to the compiler means an `INameEnvironment` rather than a
> directory — no writing, no staleness, and it works for a class whose bytes exist only because a
> mixin produced them. That piece cannot be written or validated without the platform. The remapping
> itself — the part with the hard logic — is shared by both routes and is done.
>
> `HostClasspath` (§15.2) is the file-based baseline this replaces on MC hosts, and its javadoc says
> so, because a file list that looks complete is exactly how this gets forgotten.

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
   component changes, the entry dies. ✅ **Delivered at M7** (`ScriptCacheKey`, `ScriptCache`), in
   memory and on disk. Two things the one-liner left out: **the classpath is deliberately not part of
   the key** — hashing a modded launch's thousands of entries would cost more than the compile, and
   bytecode embeds symbolic references rather than what it was compiled against, so a classpath change
   that matters surfaces as a linkage error, loudly, which is the right place for it. And **only a
   successful compile is cached**: caching a failure would serve it back after the author fixed the
   file, which reads as the editor refusing to notice an edit.
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
- **Static structure** (scopes, references for completion before first run): ~~tree-sitter's JS tree
  + `locals.scm`~~ — **revised at M10, and the reasoning is in `plan_m10.md` §1.2.** It comes from
  **Rhino's own AST**, and that is not the thing this bullet warned against: the parse already happens
  for diagnostics, `Name.getDefiningScope()` is the parser's own resolution, and reading it is not
  building anything. `locals.scm` would have been a *third* view of one file — grammar tokens, Rhino
  diagnostics, tree-sitter scopes — disagreeing with the engine exactly where it matters, since a query
  will happily scope a `class` body this engine refuses to run. `locals.scm` keeps its M11 place for the
  **engineless** languages, where there is no engine to ask.
  *(One trap, recorded because it looks like the right answer: `Scope.getSymbolTable()` is populated and
  every `Symbol.getNode()` is null in IDE mode, so there is no position to colour. Declarations come from
  the declaring node; the table is only good for the name.)*
- **Runtime introspection** (after a run): the live scope — walk actual objects and prototype
  chains, a REPL's answer, better than inference. Java interop values (`Java.type(...)`,
  `Packages.*`, a returned Java object) resolve **into the Java resolver** — the same
  `membersOf`, the same completion items. v1's observation stands: resolving `Java.type(...)`
  well buys more than resolving JS well.
  *(Two corrections from M10. **Rhino has no `Java.type`** — that is Nashorn's, and this plan named it as
  though it existed; we install it as a host function, because a call with a string literal is statically
  readable and is one expression a completion list can insert where `Packages.a.b.C` is a chain of four.
  And the live scope **contributes a type, it does not replace a symbol**: rebuilding a declared function
  from its live entry cost the JSDoc description, the parameter types and the return type after any run.)*
- **The §15.5 mapping boundary applies to JS too, and at *call time*.** Rhino resolves Java
  members by reflection when the call executes — against runtime names — so a JS script calling
  `world.getBlock(...)` fails in production with §15.5 fully built, because no compiler ever sees
  a JS member access. Member lookup itself must remap. ~~Patch the lookup layer (`JavaMembers`) in our
  shaded per-band Rhino, or adopt KubeJS's maintained remapping fork~~ — **neither, as built at M10.11.**
  `JavaMembers` is internal and differs between the two Rhinos we ship, so a patched copy is a fork to
  re-derive at every band move; and subclassing `NativeJavaObject` is *unavailable*, since its
  `(Scriptable, Object, Class)` constructor exists on band 8 and not on band 11+. It is a **membrane**
  over whatever wrapper Rhino makes — a `Scriptable` + `Wrapper` + `SymbolScriptable` (+ `Function` for a
  class object) that forwards everything and translates the name on the way through. That the fork exists
  is still the proof this is the required shape (§23.12). Without it the JS engine is dev-only; this is
  not optional. The JS resolver and completion read through the
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
implemented with rename in **M14** — build linked-edit once, for both. That milestone exists
because this sentence used to say "later" and name nobody, which is precisely what §20's
completeness contract is written to catch.

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

One policy object, consulted at every layer that could leak a name, so the tool never teaches an API
the runtime refuses:

| Layer | Enforcement | Built |
|---|---|---|
| JS execution | Rhino `ClassShutter` + scope curation — real, call-time interception | ✅ |
| Java execution | `RefusedTypes` scans the compiled constant pool and refuses the **whole script before it starts**; `ScriptClassLoader.loadClass` gates what links late | ✅ |
| Java compilation | name-environment curation: refused types don't resolve (advisory — see §19.1) | ❌ |
| Completion & hover | provider-side filter | ✅ JS only |
| Type index | refused types never indexed | ✅ JS only |

Default posture: allow-all until a host calls `restrictTo`. The host owns the policy; `language/`
owns the mechanism.

**Revised: a denylist as well, and it is now the expected spelling.** The rule was allowlist-only,
because *a denylist is unsound the moment a new class appears*. That is still true and is still why a
denylist may never be what a security claim rests on — but it stopped being the whole argument once
§19.1 settled the honest posture. For a **guardrail**, the allowlist that would actually be needed is
the host API plus the MC surface plus a usable slice of `java.*`: thousands of entries. *A control
nobody will write is worse than a leaky one that gets used.* So the two **compose** — a denial is a
veto, checked before the allowlist — and `ScriptPolicy.denying(ScriptPolicy.UNSAFE)` is the posture
this was built for. `UNSAFE` is reflection, method handles, `ClassLoader`, `Runtime`,
`ProcessBuilder`, `java.security` and the internals: without those refused, a class filter is
decorative, because every one of them turns a permitted name into an arbitrary one.

**And a floor the host cannot widen.** `ScriptPolicy.ALWAYS_REFUSED` covers `com.crystalgui.language`
and is checked ahead of both lists. Without it the whole thing was one line deep: `restrictTo` is
`public static`, the class is on the host classpath, and `ScriptClassLoader` is parent-first, so under
"deny `java.io`" the name `com.crystalgui.language.java.JavaLanguage` was not denied and a script
could simply switch the filter off for every script after it. It applies to any policy that restricts
anything — under allow-all there is nothing to relax, and a script installing a policy of its own can
only narrow, after which the floor refuses anything that would widen it again.

Two exemptions, both narrow and both load-bearing. A script's **own classes** are never asked about —
they exist in no policy, so asking would refuse every script under any allowlist that did not name the
package the prelude invented. And `ScriptControl`, because `Safepoints` injects a call to it into every
method of every script: policing that made the **kill switch** the thing that refused the script, with
a message naming an internal the author never wrote. It exposes one `public static void` that reads
the calling thread's own interrupt status.

### 19.3 Runaway scripts

An infinite loop in a script freezes the game, and Java has no safe preemption (`Thread.stop` is
broken by design). Decided:

- **JS**: Rhino's instruction observer — count-based cooperative interrupt, built in, cheap.
- **Java**: the output remap pass (§15.5 B) is already rewriting every script class, so it also
  injects a **cooperative safepoint check at backward branches and method entries** — one static
  volatile read, JIT-friendly, letting the host kill a runaway script cleanly. This is only free
  because the ASM pass exists anyway; it is the second consumer that justifies it.
  ✅ **Delivered at M7** (`Safepoints`), with three corrections worth keeping:
  - **The injected instruction is a CALL, not a read-and-branch.** A new branch target in a Java 7+
    class file needs a new `StackMapTable` entry, which means `COMPUTE_FRAMES`, which means ASM
    calling `getCommonSuperClass` — **loading classes at instrumentation time**. On an MC host that is
    loading Minecraft classes while compiling, and it fails outright for a type that is not loadable
    yet. A single `invokestatic` of a void no-arg method adds no branch, no local and no stack depth,
    so every existing frame and max stays valid. The branch still happens inside `checkpoint()`, where
    HotSpot inlines it back to exactly the volatile read the obvious version would have emitted.
  - **The flag is the thread's own interrupt status**, not a private static. It is already volatile,
    already an intrinsic — and decisively, it is the flag the JDK uses, so one `interrupt()` reaches a
    *spinning* script through an injected check and a *blocked* one through `InterruptedException`. A
    private flag would cover only the busy half.
  - **The stop is an `Error`.** Scripts are full of `catch (Exception e)` around exactly the loop a
    stop has to break out of. `catch (Throwable)` still defeats it, and nothing cooperative can beat
    that — §19.1 is the answer rather than a cleverer exception type.
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
| **M0** ✅ | Scheduler + version spine: service-layer scheduler (lanes, keyed single-flight, debounce, cancellation, drain-on-tick), `TextBuffer.version()`, `WORKBENCH_SERVICES.md` updated | — | deterministic tests: superseded, cancelled, stale-discarded, drained-on-tick — all under manual clock |
| **M1** ✅ | Tokenizer rewrite (§9): UTF-16 parse, conversion layer deleted, off-thread double-buffered reparse, per-line interned token cache, native lifecycle per document | M0 | non-ASCII fixture correct; typing a 5k-line file: UI cost within §7.3 budgets, measured and recorded; 100-open/close leak test |
| **M2** ✅ | The scheme axis (§11): font-style carve-out in `HighlightStyle.ALLOWED` — no scoped variant and no editor migration needed, see §11.1 — full `--syntax-*` vocabulary, `islands-dark` + light authored from the exported scheme, default swap | — (parallel to M0/M1) | side-by-side with IntelliJ on the same Java fixture; italic comments and constants; governance tests green |
| **M3** ✅ | Grammars (§12–13): **six** — `css`, `javascript`, `html`, `glsl`, `xml` beside `java` — vendored with all five platform/arch pairs, registered by extension, fixtured; `injections.scm` wired (html hosts css + js); §10.2's normalization landed as **seven load-time query rewrites**, not a rename map; `EveryShippedGrammarTest` covers parse + capture + registration per grammar. `locals.scm` deferred to M11 with a reason (§13) | M1 | ✅ one fixture per language in `workspace/src/`; html `<style>`/`<script>` bodies coloured as CSS/JS |
| **M4** ✅ | Module reshape (§5): `language/` rename + `.grammar`, `text.lang` SPIs in `core/` (12 types, interfaces and records only), `LanguageServices` per-document façade, editor consumes-if-present and **overlays semantic tokens over grammar tokens**, document-owned lifecycle (which also fixed `SyntaxTokenizer.close()` never being called), six registrations collapsed to a `Grammar` table | — | ✅ `core:headlessTest` green with no new deps — `LanguageSpiTest` runs the whole SPI with no engine and no grammar on the classpath; harness wires Java end-to-end unchanged; `SemanticOverlayTest` proves absent-services behaves exactly as before |
| **M5** ✅ | Engine loading (§6): band detection (`EngineBand`), isolated child-first loader with a parent-delegated bridge (`EngineClassLoader`), jar-location seam (`EngineSource`), runtime JLS discovery (`JlsLevel`), pinned ECJ+Rhino per band **including all 13 transitive platform artifacts, constrained by signing era as well as by class-file major**, `checkEngineBands` (floor + signer) in `:language:check`, `smokeEngineBands` under real per-era launchers, `THIRD-PARTY.md` | M4 | ✅ band-selection unit tests incl. the `"1.8"` trap; ✅ isolation proven with two real Rhinos; ✅ **smoke compile+eval green on a real Java 8 JVM and on 17** — Rhino arithmetic, ES2015 and a working `ClassShutter` refusal; JDT resolving `java.util.List<java.lang.String>` against the running VM, and doing it **from broken source**; ✅ **and a script compiles to bytecode and RUNS on each band's own JVM**, through the bridge (`ScriptCompiler` → `EcjScriptCompiler` → `ScriptClassLoader`), including a call back into a host class; ✅ §23 rows 3, 4 and 8 closed |
| **M6** ◐ | Java semantics (§15): ✅ ECJ diagnostics with real ranges, ✅ semantic tokens, ✅ `resolveAt`/`expectedTypeAt`/`membersOf`, ✅ `JavaLanguageServices` on the scheduler with diagnostics pushed into the document's `DiagnosticSet`, ✅ prelude mapper (`ScriptPrelude`), ✅ classpath probe (`HostClasspath`). ✅ reflection overlay (`ReflectionOverlay`), ✅ §15.5's **mapping boundary** (`MappingSet`, `ReadableView`, `InheritanceAwareRemapper` on plain ASM). ❌ remaining: **only** §15.5's **live name environment**, which needs a Minecraft platform to write or validate — see §15.5 | M0, M4, M5 | ✅ param/field/local coloured, unresolved flagged, deprecated struck; ✅ **the §13 checklist is twelve tests** (`BindingChecklistTest`), all passing, including on broken source; ✅ **the remap round-trip runs** — readable-named script → compiled → remapped → linked against `m_1234`, with an override staying an override and a negative control proving a naive remapper does not |
| **M7** ✅ | **Java execution service — the product** (`com.crystalgui.language.run`): per-script child classloader over the band loader, prelude/host-binding injection at runtime, compile-always/run-explicit lifecycle, the output remap pass wired for real, **safepoint injection + host kill switch** (§19.3), compiled-script cache `(source hash, mappings hash, band)` (§15.5 D.3) in memory and on disk, run/stop commands via `CommandRegistry`, disposal | M5, M6 | ✅ a script runs on explicit command and its effect is observable; ✅ re-run replaces the instance, and a *running* one is stopped first; ✅ **stop interrupts a deliberate infinite loop** — and a blocked one, from the same call; ✅ **100 compile/run/dispose cycles pin no classloaders**, measured by the scripts reporting their own loaders weakly; ✅ the §5.3 proof, as a bytecode scan with a negative control. ✅ and reachable from the application since M7a |
| **M7a** ✅ | **Wire the engine into the harness.** `stageEngines` writes the bands into the per-band directory layout `EngineSource.directory` reads; `JavaLanguage.register()` adds a `LanguageServices.Factory` to the existing `.java` entry (adds, never replaces, so registration order does not matter); `HarnessScriptRunner` installs `ScriptCommands` against the front tab. Plus two things a real `.java` file needs that a script body does not: **compile-as-is when the source declares a type**, and **unit and binary names taken from the file's own `package` declaration** | M7 | ✅ `JavaLanguageRegistrationTest` goes through the front door — `JavaLanguage.register()` reading the same property a deployment sets, then asking the **registry** for services — and gets diagnostics on the right line, three-colour identifiers, and an ordinary `main(String[])` file that runs; ✅ `RunTestFixtureTest` compiles, analyses and runs the shipped `RunTest.java` and checks every one of its 19 sections reached the transcript |
| **M8** ✅ | **Decorations + diagnostics UI (§17).** `text/decoration/` — `Stickiness` (Monaco's four modes, each a pair of `ChangeSet.mapPos` assoc values), `TrackedRange` (mutable, identified by reference, recording collapse separately from emptiness), `DecorationSet` (sorted array + binary search, lanes that replace). Adjusted **inside `TextBuffer.applied`**, before any listener runs. `LanguageServices.onDiagnostics` now carries `Versioned`, and the editor gates on it. `SquigglesPart` reads tracked offsets instead of re-resolving row/column each frame | M0; M6 for real input | ✅ 16 stickiness cases incl. both asymmetric modes and the re-sort trap; ✅ a mark stays on its word when a line is inserted above it, and grows when its word is extended; ✅ Problems navigation lands on the word as it is **now**; ✅ a stale announcement is refused outright; ✅ every producer is tracked, not only the engine — the tracking hangs off `DiagnosticSet.onChanged` |
| **M9** ✅ | **Completion (§18).** `SearchMatcher` gains an **opt-in** `SUBSEQUENCE` tier (a bounded DP, so the banded characters are the ones a reader would say matched) rather than a second matcher; `CompletionSession` is the whole state machine with no widget in it; `CompletionRanking` is the weigher **chain**; `CompletionPopup` draws IntelliJ's anatomy (kind icon, banded label, right-aligned detail); `JavaCompletionProvider` + `Analysis.symbolsInScope` + `TypeIndex` (classpath names from paths, never loaded) | M6, M8 | ✅ 14 session cases: local filtering without a round trip, `isIncomplete` re-query, a late answer from a superseded request ignored, a keystroke before the first answer not killing the session; ✅ `fMS` → `fooMethodStuff` while a prefix hit still outranks it; ✅ auto-import accepted as **one** undo step; ✅ verified in the harness by capture **and** log — which is where the ranking bug was found |
| **M9.5** ✅ | **Designed in detail in [`plan_m9_5.md`](plan_m9_5.md)** — read that, not this row, for what shipped. **The Run panel**: a dock panel beside Problems carrying running scripts' output, plus the indicator saying which files are live. **IntelliJ's Run window is the wrong reference and the section says why** — it assumes a process boundary, a termination and one run at a time, and only the third is true here. Unity's Console is the right one: one process, many live scripts, per-frame execution, no exit codes. So: one **workspace** console filtered by script rather than a tab per run; a rail of live scripts in states (`Live (3 handlers)`, never an exit code) where `Running` is reserved for one-shots so a tick script does not strobe; ~~collapse by call site~~ (**cut** — collapsing is a list affordance and the console became a `TextEditor`, because a console needs character-level selection across lines, drag-select and copy-exactly-what-was-dragged, none of which a row list can offer; the ring bounds the transcript instead); and output captured through a **thread-local marker `ScriptHost` sets around every invocation**, which is the only construction that makes `System.out.println` work in a one-shot *and* in a tick handler on the game thread without swallowing Minecraft's logging. Output **survives a stop but is bounded** — a cycle buffer sized in KB, which is IntelliJ's own answer and whose docs warn about "chatty processes", our normal case rather than an edge one. The running indicator is a `FileDecorationProvider` — free in the tree, plus a dot on the Run stripe button; **the editor-tab mark is cut** (three statements of one fact, in the place with the least room, and IntelliJ does not mark tabs because it is run-configuration based). **Neither reference marks a file**, so the mark means "this file's compiled instance is live" and never "this text is running" | M7 (execution + §19.3 kill flag), M9 (UI vocabulary) — both ✅ | `println` from a one-shot **and** from a game-thread tick handler both reach the console and MC's logging reaches neither; a per-tick script does not flood, and an overflow is reported rather than dropped; a tick script shows `Live` without strobing; a stopped script **keeps** its transcript; a stack frame opens the file at the line; a live script is marked on its tree row and on the Run stripe button, with the folder taking the colour and not the badge; a runtime exception raises **no** Problems row |
| **M10** ✅ | **Designed in detail in [`plan_m10.md`](plan_m10.md)** — read that, not this row. Every Java feature matched to its Rhino counterpart at an honest fidelity, the bridge/loader split, interpreted mode, `Java.type` (which **Rhino does not have** — we install it), and the revision of §16.1's static-structure source. Delivered as 10.1–10.12: plumbing + the per-band capability probe, the bridge and registration, diagnostics with the engine's own refusals re-titled, semantic tokens over Rhino's scopes, **execution** (`JsHost` ↔ `RhinoExecutor`, console, stop, runtime errors as diagnostics), four-tier resolution with the **Java engine** behind the interop one, completion, Quick Documentation, eleven quick-fix families, the sandbox at four layers, and the readable↔runtime **membrane**. §12a of that file is the review of all eleven and the fifty-six findings it closed | M5, M7 (execution substrate), M6 (Java resolver for interop), M9 (UI) | ✅ all four: `class` gets an engine diagnostic with the engine's own message; post-run completion on a live object; a readable-name call links in a fake-obfuscated fixture; a refused type is absent from execution *and* completion. 201 JavaScript tests |
| **M11** ✅ | **Designed in detail in [`plan_m11.md`](plan_m11.md)** — read that, not this row. ✅ Quick Documentation (`Mod+Q` + hover, engine-rendered `Signature`, and **source attachment**: a classpath symbol is quoted from its `-sources.jar` or the JDK's `src.zip`); ✅ go-to-definition (`Mod+B`, Ctrl+Click); ✅ the navigation primitive, Go To Line, and clicking a problem; ✅ **`folds.scm`**, **`indents.scm`** and **`locals.scm`**, vendored from nvim-treesitter under Apache-2.0 (§13 records why the "author's own file" rule answers none of them); ✅ **GLSL diagnostics**, which needed a position on `CgShaderParseException` first — §14.1 records why "no new machinery" was true of this side only. ✅ and the **footer** band, which hides when the declaration is in the document already open | M3, M6, M8 | ✅ hover shows type + **declaration** for a Java symbol (**not** doc — the body is M13's, and §24.1 says so deliberately: `SymbolInfo.documentation` has never been populated by any engine, so "type + doc" was this row overreaching its own detail file); ✅ go-to jumps within the script; ✅ Java and GLSL fixtures fold at their blocks rather than their indentation; ✅ a GLSL parameter and a local colour differently with no engine loaded; ✅ a shader error appears as a squiggle and a Problems row |
| **M12** | **Platform integration** — the one thing every milestone above deliberately stopped short of. Bring `mc1710/` into the build (it has no `settings.gradle` today and is commented out of the root one), then wire what the plan has been building against a stand-in: §15.5 A's **live name environment** reading post-transform bytes from `LaunchClassLoader` through the transformer chain, §15.5 C's **mapping data** (1.7.10 SRG↔MCP CSVs, `params.csv` for parameter names) with sourcing and licences settled, and the platform's `LanguageServices`/`ScriptHost` wiring. Then the same for `mc1201/` | M7, M11 | a script written in readable names compiles, runs and links **inside a real 1.7.10 client**, against MC classes and a mixin-added member; completion never shows `func_147439_a`; the same script runs unchanged in dev and prod |

| **M13** | **Designed in detail in [`plan_m13.md`](plan_m13.md)** — read that before starting. **Documentation and names in production**, which is where every milestone above is quietly weakest: the popup is correct in a dev environment and mostly absent in a shipped one. Two halves priced completely differently. **Parameter names survive compilation** — `ArrayList.add` carries `e` and our own `core.jar` carries its names today, so a class-file reader over `MethodParameters`/`LocalVariableTable` (ASM is already an `api` dependency) needs no shipped artifact at all; `-parameters` on `core`/`platform`/`language` adds the interface methods it cannot reach. **Javadoc does not survive compilation and no attribute carries it**, so prose is ship-or-fetch: one build-time header transform (built from `quotedHeaderOf`'s cut and `isValue`'s rule, output still valid Java so `SourceArchives` is unchanged) feeding four producers — our own sources bundled as loose `.java` under `assets/`, the JDK **fetched** rather than bundled on GPL-derivation grounds, Minecraft's arriving with M12's mappings, third-party best-effort. Plus the one-line ECJ flag that makes `getJavadoc()` answer at all | M11; M12 for the loader packaging and for Minecraft's half | a **concrete** classpath method names its parameters with `src.zip` deliberately out of reach; one of our own **interface** methods does too; the transform's output quotes identically to the source it came from for a record, a sealed interface and a bounded generic; our sources resolve out of the mod jar through the same chain, with a real `-sources.jar` still winning; a javadoc body renders, including for an `@Override` with none via `{@inheritDoc}` |
| **M14** | **Rename, and the three things that share its substrate.** The completeness contract's own failure mode, found by auditing against it: §18.4 says linked-edit mode is "shaped in the item model now and implemented with rename later — build linked-edit once, for both", and `CompletionItem.InsertTextFormat.SNIPPET` repeats the promise in code ("`$1`/`$2` tab stops arrive with rename"). **Rename appeared in no milestone row and in no §22 line**, so "later" resolved to nothing — which is exactly what the contract was written to stop. Nor is it refused: it is wanted, and two places already carry design for it. Grouped as one milestone because all four want the same missing primitive — **`Resolver` can answer `resolveAt` and knows nothing about the other direction**, so *find every reference to this binding* is a new SPI method both engines implement, and rename, find-usages and the highlight-all-occurrences that falls out of it are its consumers. Signature help is the odd one and belongs here anyway: it is `expectedTypeAt`'s sibling and reuses completion's popup substrate. **Linked edit is the widget half** and is built once for the pair, per §18.4 — `SNIPPET` implements only `$0` today, deliberately, and an unimplemented placeholder is inserted literally so it is wrong in a way somebody reports rather than one that silently swallows text | M6 (Java bindings), M9 (the popup and the item model), M10 (so JavaScript arrives with it rather than after it — `plan_m10.md`'s non-goals defer all three "ahead of Java having them", and this is Java having them) | renaming a local updates every reference in one undo step and none of a same-named field's; renaming a method reaches its overrides and its callers, and refuses when one is outside the workspace; find-usages lists the same set the rename would touch, which is the assertion that stops the two drifting; a completion item with `$1`/`$2` puts the caret through the stops in order; signature help shows the overload the arguments so far actually select |

Critical path: M0 → M1 → M3, and M0/M4 → M5 → M6 → M7 → M8/M9 → M9.5 → M10 → M11 → M12 → M13 → M14. M2 is
the early visible win and touches none of it.

> **M9.5 sits before M10 for a reason that is not sequencing tidiness.** M10's exit criteria include
> post-run completion on a live object, which means running a script and observing what it did — and
> until there is a console, running a script produces nothing anybody can see. M10 would otherwise be
> built and demonstrated blind, and its `console.log` binding would have nowhere to go on day one.

> **M13's first item is not on that path and should not wait for it.** Reading parameter names out of the
> class file needs no artifact, no build change, no licence decision and no packaging — it is gated on
> nothing, and it is the single most visible production improvement in the milestone. The rest of M13 is
> gated on a decision; that part is gated on writing it.

> **M7a exists because the exit criterion above says "observable in the harness" and the tests are
> headless.** The distinction is not pedantic: an engine that works in a test and is not reachable from
> the application is an engine nobody can use, and "the tests pass" is exactly the sentence that hides
> it. The same gap existed at M3 — the harness had no tree-sitter on its classpath, so a whole round of
> scheme tuning was aimed at a grammar that was not running — which is the second time this shape has
> appeared and the reason it now gets a row of its own rather than a note.

> **M12 is deliberately last, and it is the only milestone that cannot be verified from this build.**
> Everything before it is provable headlessly — which is why every seam it will plug into was built
> against a stand-in and says so in its own javadoc: `HostClasspath` names itself the baseline the live
> name environment replaces, `ReadableView` names the directory it writes as the thing an
> `INameEnvironment` removes, and `MappingSet.IDENTITY` is the case that makes every non-MC host take
> the same code path. Doing the platform work earlier would have meant writing code no test in this
> repository could exercise.

**Completeness contract**: every deliverable named in Parts II–IV either appears in a milestone
row above or is listed in §22 as deferred/refused. A future edit that adds a promise adds a row
or a §22 line in the same edit — an unscheduled promise is how v1's "after the batch" resolved
to nothing.

## 21. Governance and testing

The styling work's law — a rule that can be broken silently will be — applied to a stack that is
mostly invisible when wrong:

1. ✅ **Every capture has a colour** (`StyleGovernanceTest.everyCaptureInAShippedGrammarHasAColour`):
   scans shipped `highlights.scm` names **post-normalization** — it runs the same `Queries` rewrites
   the tokenizer does, or it would check names the editor never emits — and asserts each has a
   `--syntax-*` token or a coloured general form, in every scheme. (v1 §6.1, and it earned its keep:
   it is what caught the schemes missing `markup` and `error` when xml landed.)
2. ✅ **Vocabulary conformance**: normalized capture names ⊆ §10.1's set — a new grammar cannot
   introduce a name no scheme has heard of. Same test, other direction.
3. ✅ **Scheme pairing and scope**: `eachThemeAndSchemePairDefinesTheSameKeys`,
   `everySchemeDefinesTheSameKeysAsEveryOther`, `theSchemeAndThemeAxesStayApart`, and
   `nothingIsDrawnInTheColourOfWhatItSitsOn` extended to `--syntax-*` vs `--editor-bg`.
4. ✅ **Scheduler determinism** under manual clock (§7.2) — supersede, cancel, stale-discard, drain.
5. ✅ **Offset correctness on non-ASCII** — the test the UTF-8 layer never had. Three of them, and
   the shape matters: `everyCaptureLandsOnTheTextItNamesAcrossMultiByteCharacters` asserts captured
   **text**, not that ranges are in bounds, because a range shifted by the encoding delta is still a
   valid range. `SurrogateSafetyTest` covers the astral-plane half, which is where the real crash was.
6. ✅ **Decoration stickiness goldens** — Monaco's boundary-insertion cases, all four modes. Sixteen of them, including both asymmetric modes and the re-sort trap that falls out of mapping being monotonic only for a fixed assoc. (M8)
7. ✅ **The §13 checklist as tests** — one JUnit method per row (generic substitution, overload
   phases, bridge filtering, accessibility-from-context, pattern-variable regions, lambda target
   typing), against ECJ bindings, headless. Twelve of them in `BindingChecklistTest`, and they
   run on broken source too, which is the state a file spends most of its life in. (M6)
8. ✅ **Native leak test** — §9.2 D: `openingAndClosingManyDocumentsDoesNotAccumulateNatives`,
   plus `closingIsIdempotent`, since double-close is the failure mode a leak fix introduces.
9. ✅ **Sandbox symmetry** — a refused type is absent from execution, completion, hover and index.
   **Three of those four were covered and hover was not**, though this row had claimed it since
   M10 — a row claiming coverage that does not exist is worse than one admitting a gap, and
   writing the missing assertion found a real leak: `InteropResolver` gates `describe` and
   `membersOf`, but the INFERENCE tier reads `Java.type('java.lang.System')` straight off the
   syntax and asked nobody, so a variable holding one hovered as `s : java.lang.System` under a
   policy refusing `java.lang`. That is the sandbox's own failure mode exactly — offered by the
   editor, refused at run time. Filtered at the seam now, like the member list. (M10)
10. ✅ **The semantic vocabulary is coloured too** (`StyleGovernanceTest.everySymbolKindNamesACaptureTheSheetColours`)
    — added at M4, and it is the same rule as (1) arriving from a third direction. A
    `SemanticTokenProvider` names its colours through `SymbolKind.captureName()` rather than
    spelling them, so that bridge is a capture producer exactly like a `highlights.scm` — and it has
    no file to scan, so rule (1) cannot see it. A kind whose capture nothing styles renders the
    resolved symbol as body text: the engine ran, the answer was right, the screen is unchanged.
11. ✅ **The grammar table is consistent** — every row registers every extension it claims and
    resolves to its own `Language`; no two rows claim one extension (registration *replaces*, so a
    collision is silent and the later row simply wins); an injecting row names grammars we ship.
12. ✅ **An engine band is loadable, coherent and functional** — three checks, because each catches
    something the others cannot. `checkEngineBands` reads class-file majors (loadable) and compares
    signing certificates per package (coherent); `EngineApiSurfaceTest` reflects over the real jars
    (the adapter's surface is present in every band); `smokeEngineBands` runs each band **under a
    launcher of its own era** and compiles and evaluates for real. The middle one would have passed
    the signer bug and the first would have passed the broken-source question — the point is that
    "the jars are fine" is three different claims.

Everything in `language/` tests headlessly — no GL, no MC. That is a consequence of the layer
rules, and it is also the enforcement of them.

## 22. Non-goals

- **LSP.** The seams are LSP-shaped so one could arrive; none is planned.
- **Hot swap** of running script instances (v1 §16.3: compile always, run explicitly, re-run
  replaces — unchanged).
- **TextMate/Monarch grammars**; re-litigating tree-sitter (§3).
- **User-supplied grammars at runtime** — grammars are vendored jars; a resource pack must not
  load native code.
- **Nested injections** — the injected sub-parse runs **one level**, which covers every case the six
  shipped grammars produce (HTML hosting CSS and JavaScript). Markdown hosting HTML hosting
  JavaScript would need a depth guard that does not exist. Listed here rather than left to be
  discovered; `TreeSitterTokenizer.withInjections` says so at the method.
- **IME/composition input, bidi/RTL caret movement, screen-reader support** — declared unsupported
  rather than discovered; each reaches `CgSystemInput` or the coordinate model and is its own plan.
- **Minimap, diff view, overview ruler** — consumers of §17.1, which reserves them a payload lane;
  not built here.
- **TypeScript-style JS type inference** — §16.2 is the contract.
- **`KeywordTokenizer` retirement** — it is the engineless fallback `core/`'s no-natives guarantee
  rests on; its javadoc says so, and **M4 re-checked it: still true, and sharpened.** The file used
  to say a lexer "cannot tell a type from a variable, a call from a declaration, or a field from a
  local — all of which need a parse", which conflates two tiers. A parse gives you call-vs-declaration;
  a field-vs-local needs an *engine*, because nothing in the shape of `count` says which it is. The
  javadoc now names all three tiers, since which one is missing decides where a missing colour has to
  be fixed.
- **Player-submitted scripts executing on a server** — permanently, per §19.1. Not a missing
  feature; a refused one.
- **Remapping reflection helper** for scripts that reflect on MC members (§15.5 D.1) — v1 declares
  it unsupported; the helper is the later fix if real scripts demand it.
- **Cross-version portability of MC-touching scripts** (§15.5 D.4) — a script that names MC
  classes is bound to that version's API; only host-API scripts are portable.

## 23. Verify before the milestone that depends on it

| # | Question | Blocks | How |
|---|---|---|---|
| 1 | ~~UTF-16 encoding agreement in the vendored binding~~ | ~~M1~~ | **Answered: it does not work** (§2 row 6). Probed before building on it, which is what this row existed for. The conversion layer stays and was made fast instead |
| 2 | `TSReader` chunked parse works (nice-to-have; String path is the fallback) | — | not attempted; the String path is adequate and the rope is now handed to the worker rather than flattened on the frame |
| 3 | ~~Exact pinned versions per band; the DOM adapter compiles against the oldest band's API~~ | ~~M5~~ | **Answered (§6.2).** Measured from class-file majors, not release notes: jdt.core **3.26.0 / 3.33.0 / 3.46.0**, Rhino **1.7.15.1 / 1.9.1 / 1.9.1**. `EngineApiSurfaceTest` asserts the adapter's whole surface exists in all three. **The toolchain matrix is still owed** — it needs an adapter, so it moves to M6 |
| 4 | ~~Old-band ECJ honours `setBindingsRecovery` well enough for §15.1's broken-code story~~ | ~~M5/M6~~ | **Answered on the real JVM.** `BandSmoke` parses a class whose last statement is truncated mid-expression, on band 8 under a **Java 8 launcher**, and still resolves the field's binding to `java.util.List<java.lang.String>` — generic argument intact. The §13-checklist tests still run per band at M6; the story they depend on is no longer a guess |
| 5 | Classpath discovery on each loader (`LaunchClassLoader.getSources()`, Knot, ModDev, harness) | M6 | per-platform probe with a unit test where reachable; harness first |
| 6 | ~~Fork-sync effort for upstream's css/js/html subprojects~~ | ~~M3~~ | **Answered.** Two lines in the fork's `settings.gradle` and a `jar` task; natives were already built for all five pairs, and `downloadSource` supplies the authors' `queries/`. Cheaper than the plan priced it |
| 7 | ~~Type-index scale (count, scan time, table size vs §7.3 budget)~~ | ~~M9~~ | **Answered, and it took three attempts to measure honestly** (`TypeIndexScaleBenchmark`, `-Pbench`). **~331 bytes per type**, so the 60k `MAX_TYPES` cap is **~18MB against §7.3's ~20MB** — inside it, with about 10% headroom. 10,992 types off this JVM's own classpath in **294ms**. A modpack is a stand-in this repository does not have; the scan is linear in entries and does no per-jar work beyond opening one, so the per-type cost is the figure that transfers. **Two things the measurement exposed.** The naive heap delta either side of the build reads ~14MB — five times what a record of three strings can weigh — because it counts the scan's `ZipFile` churn; nulling the reference instead reads ~0KB, because the caller's local still holds it. Both are wrong and neither looks it, so retention is measured by building several and holding them all. And **§7.3's own row half-describes something that does not exist**: there is no persisted cache, so "warm load from cache < 500ms" has no subject, and the scan is not "background" — `TypeIndex` pays for it on the first query, deliberately and with a reason in its javadoc, which on a modpack means the first completion keystroke of a session wears the whole scan |
| 8 | ~~Rhino 1.7.15 ↔ 1.9.x API intersection for the single adapter (ClassShutter, scope, Context factory)~~ | ~~M5~~ | **Answered.** `EngineApiSurfaceTest` loads both real jars and asserts `Context.enter/exit/initStandardObjects/evaluateString/setLanguageVersion/setOptimizationLevel/setClassShutter`, `ClassShutter.visibleToScripts`, `ContextFactory.getGlobal/enterContext`, `ErrorReporter`, `EvaluatorException`, `ScriptableObject`, and `VERSION_ES6 == 200` — identical across 1.7.15.1 and 1.9.1. One adapter is real |
| 9 | Per-loader route to **post-transform class bytes** (1.7.10 `LaunchClassLoader` + transformer chain; Fabric launcher; Forge/Neo SecureJar) | M6 | probe per platform, 1.7.10 first — it is the hardest and the one that motivated §15.5 |
| 10 | ~~Every band's ECJ accepts a custom name environment serving remapped/synthesized types~~ | ~~M5/M6~~ | **Answered: all three** — `everyBandsCompilerAcceptsTheRemappedView` reports `[JAVA_8, JAVA_11, JAVA_17]`, both directions per band. The row named exactly the right test and it had never been run that way: every other case in `RemapRoundTripTest` opens `EngineBand.detect()`, which is the machine's own band — so a developer on 17 or 21 exercised 17, and **band 8, the one a 1.7.10 client runs, was the band nobody was testing**. Loops in one JVM, which the pinning makes safe: bands are pinned by class-file major so an *old* host can load them, and a new host loads all three. Both directions because they fail differently — a view the compiler cannot read errors on the readable name, while a view that is not *shadowing* the real class errors on nothing, and that is the silent one |
| 11 | Mapping data sourcing and licences (1.7.10 MCP CSVs incl. `params.csv`, Mojang official mappings terms, Parchment, Fabric tiny) | M6 | resolve, cache strategy decided, recorded in `THIRD-PARTY.md` |
| 12 | ~~Rhino member-lookup remapping route per band~~ | ~~M5/M10~~ | **Answered: neither candidate — a MEMBRANE**, and all three forced it. `JavaMembers` is internal and differs per band, so a patched copy is a fork to re-derive. Subclassing `NativeJavaObject` compiles and throws `NoSuchMethodError` at the first binding, because its `(Scriptable, Object, Class)` constructor is on band 8 and not on band 11. And overriding `wrapAsJavaObject` does nothing at all, since Rhino constructs the wrapper directly — the feature sat silently inert with the factory installed and the mapping non-identity. `RhinoRemapping` overrides `wrap` and wraps the result; see the AGENTS row for what a membrane must forward and why `Wrapper` is load-bearing |
| 13 | ~~Output remapper with inheritance propagation: adopt tiny-remapper vs write the propagation walk on plain ASM~~ | ~~M6~~ | **Answered: plain ASM**, on three measurements. ASM's real classes are class-file **major 49** (only `module-info.class` is 53, which a Java 8 JVM never reads), so it runs on every band; the three jars total **0.24 MB**; and it has **no transitive dependencies**. tiny-remapper is a similar size alone but pulls `asm-util` and `mapping-io` behind it, and its API is built around remapping jars on disk with a thread pool — a workflow, where what is needed is one focused walk. `InheritanceAwareRemapper` is ~180 lines and the round-trip includes the override case **with a negative control** |
| 14 | ~~Safepoint-injection overhead (§19.3) on a hot script loop~~ | ~~M7~~ | **Answered: 1.06x, +0.014 ns per iteration**, on a 400M-iteration counted loop, best of five, both paths warmed equally (`SafepointOverheadBenchmark`, `-Pbench`). It does vanish. **The row's own premise was stale** — the injection is not a volatile read but a single `invokestatic` of a void no-arg method, because a read-and-branch needs a new branch target → a new `StackMapTable` entry → `COMPUTE_FRAMES` → ASM loading classes at instrumentation time, which is fatal on an MC host. So the real question was whether HotSpot inlines the callee back to that read, and it does. What guards it from here is not the number: `injectingChangesNeitherTheStackNorTheLocals` asserts the structural property that makes it free, deterministically |

---

*v1's §12.1–§13.9 research content (import precedence, completion field rationale, the full Java
resolution checklist) is preserved in spirit above and in letter in git history; the checklist
rows live on as §21.7's tests. v1's decisions that survived verification — tree-sitter (§3),
vendored author queries (§12), the carve-out (§11.1), css-before-html (§12), module HQ +
scheduler + lifecycle + allowlist (§5–7, §19) — are incorporated rather than re-decided.*
