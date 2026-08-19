# M10 — JavaScript on Rhino, feature for feature against the Java engine

Detail for the M10 row in `plan_syntax.md` §20. Read this, not the row, before starting.

The premise of this document is the one the row could not make when it was written: **the Java engine
is finished down to its smallest habits**, and it is now behind seams that name no language —
`LanguageServices` (core), `bridge.Analysis` + `AnalysedLanguageServices` (engine), `ScriptRuntime` +
`ScriptRuntimes` (run). So the JavaScript engine is not designed; it is *matched*. Every Java feature
below is listed with what Rhino can honestly do about it, at what fidelity, through which Rhino API, and
where in `.js` it lives. Where the answer is "not possible for a dynamically typed language", it says
so and says what is offered instead, because §16.2 makes best-effort the *contract* and a contract has
to be spelled out.

Two things this document is not. It is not a place to re-argue §16 or §19 — division of labour, the
trust model, the allowlist, the instruction observer are settled there and cited here. And it is not a
substitute for the probe: Rhino ships in two versions across three bands (`1.7.15.1` on band 8,
`1.9.1` on 11 and 17), and every claim below about what Rhino parses, warns about, or exposes is
verified by a per-band test **before** it is built on, exactly as M5's `EngineApiSurfaceTest` already
does for `Context`. Section 9 lists the claims that need it.

---

## 0. Status — the minor milestones

The order below is the build order (§12 has each one's contents and tests). Two things about it are
deliberate: **10.2 puts a `.js` file into the harness workspace the moment services exist**, so every
milestone after it is visible in the same file the way `Main.java` shows the Java engine — and
**execution (10.5) comes before resolution and completion**, because the live-scope tier, the
provenance band and the runtime diagnostics all need something to have run, and because Run is the
most visible thing to have early.

| Milestone | Delivers | Visible in `workspace/src/Main.js` as | State |
|---|---|---|---|
| **10.1** Plumbing + probe | `Language.JAVASCRIPT`, `Grammar` mapping, keyword tier; `RhinoCapabilityProbeTest` and the per-band probe files everything later reads | comment toggle, auto-close, the backtick pair, the `.` trigger | **done** — `56dca9b` |
| **10.2** Bridge + registration + the fixture | `JsSourceAnalyzer`/`JsExecutor`; `RhinoSourceAnalyzer`/`RhinoExecutor`/`RhinoThread`; `JsLanguageServices`/`JsHost`/`JsLanguage` through `EngineHost.shared`; **`Main.js` seeded into the harness workspace, `HarnessWorkspace` registering JS** | the file opens with services (owner `javascript`), **a syntax error is already a real squiggle**, Run knows it is a script and refuses a broken one | **done** — `ef098fe` |
| **10.3** Diagnostics | IDE-mode parse errors, `RhinoProblemPolicy` (cascade suppression + refusal re-titling), unused-name warnings, retention through errors | one squiggle per problem, not five; `class`/`import`/`async` each say why | **done** — `77adc82`. **Compatibility band deferred to 10.3b** (see below) |
| **10.4** Semantic tokens + scopes | `RhinoScopes`, `RhinoSemanticTokens`, `RhinoGlobals` | parameter / local / const / reassigned / captured / builtin / unresolved, each drawn as itself | **done** — `77adc82`. JSDoc recording moves to 10.6, where the tiers read it |
| **10.3b** Compatibility band | ship band 8's probe file as a resource; detect the six constructs 1.9.1 accepts and 1.7.15.1 refuses (default params, spread, computed properties, destructuring defaults, `?.`, `??`); warn when the target band is older than the host | an author on Java 17 is told what a Java 8 player cannot load | **done** — `JsCompatibility`, `JsLanguage.compatibleWith(band)`, 6 tests. Off by default. Detected from **text over located nodes** rather than from accessors — see below |
| **10.5** Execution | `JsHost`, `RhinoExecutor.run/stop/currentLine/describe`, console/`print`/`readLine`/`Java.type` globals, `RhinoConsoleFormat`, `RhinoOrigin`, `RhinoStackFrameFilter`, runtime errors as `js-runtime` diagnostics through a new engine-neutral `AnalysedLanguageServices.reportRuntimeProblems` lane | Shift+F10 runs it; output attributed by line; Stop works; a thrown error squiggles its line | **done** — see "10.5 as built" below. `snapshotScope` moves to 10.6, beside its only consumer |
| **10.6** Resolution + interop | the four tiers (`RhinoResolution`), `RhinoJsDoc`, `RhinoInference`, `InteropResolver` over the Java probe unit + reflection fallback, `LiveScopeSnapshot` from `snapshotScope`, `JsTypeRef`, **`RhinoTokens`** | hover says what a name is and which tier said so; a Java receiver's members are the Java engine's; after a run a global is typed by what it became | **done** — see "10.6 as built" |
| **10.7** Completion | `JsCompletionProvider`, `JsKeywords` (measured per band), the probe re-parse, `Java.type("…")` names from the shared `TypeIndex`, live-object completion with the inherited half | `.` after a Java object lists its members; after a run, `settings.` lists what it has; no refused keyword offered | **done** — see "10.7 as built" |
| **10.8** Quick Documentation | `JsSignatures`, the per-member interop probe (`InteropResolver.describeMember`) so a Java member is quoted through `AttachedSources`, declaration sites both ways | Mod+Q shows `function join(name: string, count: number): string`, and `public boolean add(E e)` for a Java member | **done** — see "10.8 as built" |
| **10.9** Quick fixes + intentions | `JsRewrites` (text edits over Rhino's absolute positions), `JsQuickFixes` — eleven families, `SimilarNames` shared with the Java catalog | Alt+Enter offers a repair for an unused name, a misspelt one, `var`→`let`/`const`, `==`→`===`, either `Java.type` spelling, a template literal, and try/catch | **done** — see "10.9 as built" |
| **10.10** Sandbox | `ScriptPolicy` in `language.run`; the class shutter, `InteropResolver`, the completion list and `TypeIndex.filtered` all read it, through **one** entry point | a refused class is absent from `membersOf`, from the popup and from the index, and throws when called | **done** — see "10.10 as built" |
| **10.11** Remap seam | `MemberNameMapper` bridge hook; `RhinoRemapping` — a **membrane** over Rhino's own wrapper rather than a patched `JavaMembers`; `InteropResolver` renaming what it lists | a readable-name call runs against a renamed member, and the member list shows the readable name | **done** — see "10.11 as built" |
| **10.12** Parity audit + docs | every matrix row tested or documented; AGENTS.md rows; `plan_syntax.md` §16.1/§20 updates | `RunTest.js` beside `Main.js` — a transcript of what the engine executes, where `Main.js` is what the editor knows | **done** — §12a is the audit of 10.1–10.11 (56 findings) and every one of them is closed; see "12a as built" below for what the fixes turned up in turn |

Exit criteria (the row's four, plus what matching the Java engine adds):

- `class` syntax gets an **engine** diagnostic with Rhino's own message, on the band that refuses it;
  a band that accepts it gets none — the probe decides, not a constant.
- Post-run completion on a live object: run `var w = new java.util.ArrayList()`, type `w.` → members.
- A readable-name member call links in a fake-obfuscated fixture (`world.getBlock` runs against a
  `MappingSet` that renames it), through the remapped lookup layer.
- A refused type is absent from execution **and** completion, one test proving both.
- **Parity:** every row of the §2 matrix marked *Full* or *Partial* has a test; every row marked
  *Best-effort* has a test that shows the fallback rather than a wrong answer; every *No* row is
  documented in the SPI javadoc as absent by design.
- **The shell names no language.** `RunShellIsEngineNeutralTest` stays green — which is the criterion
  that was meant, and is the one that test actually pins. *(The original wording, "no file under
  `language.run` changes", was false by design when it was written: §10 puts `ScriptPolicy` there on
  purpose because three of its four consumers are not JavaScript, `ScriptRuntime` gained `restrictTo`,
  and `ScriptRuntimes.consoleFilters` learned to dedupe by filter class. None of those names a language,
  which is the property worth defending.)*

---

## 1. Ground truth — what is being matched

### 1.1 The Java engine, as a list of responsibilities

This is the inventory the matrix in §2 is built from. Each row is a class in `language.java` and the
one thing it does; the JS column names its counterpart in `language.js`.

| Java class | Responsibility | JS counterpart |
|---|---|---|
| `JavaLanguage` | the one call: open the band, `withServices` on the `.java` entry, contribute the runtime | `JsLanguage` |
| `JavaLanguageServices` (~90 lines over `AnalysedLanguageServices`) | request shape (class name, classpath, level) + providers | `JsLanguageServices` — request shape is (source name); providers are JS's |
| `EcjSourceAnalyzer` → `SourceAnalyzer.Analysis` | diagnostics, semantic tokens, `resolveAt`, `expectedTypeAt`, `membersOf`, `symbolsInScope`, `codeActionsIn`, `optionalProblemsAnalysed` | `RhinoSourceAnalyzer` → `JsSourceAnalyzer.Analysis` |
| `EcjProblemPolicy` / `EcjOptions` | what to report, how to draw it; the level to compile at | `RhinoProblemPolicy` — which parser warnings are on, which are hidden, severity per message id |
| `ProblemSpans` | the range to draw vs the range a fix acts on | `JsProblemSpans` — Rhino gives `(offset, length)`; the two questions still differ |
| `JavaCompletionProvider` | members after `.`, open code (locals → params → fields → keywords → unimported types via `TypeIndex`), probe re-parse for an unresolved receiver | `JsCompletionProvider` — same two questions; the "unimported type" answer becomes **live-scope globals + Java packages/classes** |
| `TypeIndex` (shared per classpath) | every classpath type name | **reused as-is** for `Java.type("…")` / `Packages.…` completion |
| `JavaCodeActions` + `JavaQuickFixes` + `*Corrections`/`*Intentions` (16 families) + `Rewrites`/`FixContext`/`ImportRegion`/`Names` | the fix catalog and its substrate | `JsCodeActions` + `JsQuickFixes` + a JS catalog (§8); `JsRewrites` over Rhino AST positions, since Rhino has no rewriter |
| `JavaSignatures` + `AttachedSources`/`SourceArchives`/`SourcePackages` | the declaration line for the popup: quoted from source, else assembled | `JsSignatures` — assembled from the AST for JS declarations (there is no compiled form to quote *from*); **Java members reached from JS quote through the same `AttachedSources`** |
| `ReflectionOverlay` | in-memory types visible to the compiler | not needed — Rhino reflects at call time |
| `HostClasspath` | what to compile against | reused for `TypeIndex` and for the Java probe unit |
| `ScriptPrelude` | wrap a snippet in a class, constant offset | **none** — a JS file is a script; bindings are scope properties, offsets are the file's |
| `ScriptHost implements ScriptRuntime` (+ `Safepoints`, `ScriptClassLoader`, `ScriptCache`, `InheritanceAwareRemapper`) | compile/run/stop/replace, per-run loader, kill switch, cache, remap | `JsHost implements ScriptRuntime` (host side) ↔ `RhinoExecutor` (child side): compile = `Context.compileString`, run = fresh scope, stop = instruction observer, no cache, remap at **lookup** (§11) |
| `JavaStackFrameFilter` | `at pkg.Type.m(File.java:12)` → link | `RhinoStackFrameFilter` — `\tat script.js:12 (fn)` → link, plus the JVM one for Java frames underneath |
| `ScriptRef.ClassOrigin` | which line is printing, from the JVM stack | `RhinoOrigin` — which line is printing, from Rhino's own stack (§9.4) |

### 1.2 What Rhino is, for this purpose

Rhino is the execution and truth layer (§16.1). What matters here is that it is also a **parser with an
IDE mode**: `CompilerEnvirons.ideEnvirons()` (or the four flags it sets — `setRecoverFromErrors`,
`setIdeMode`, `setRecordingComments`, `setRecordingLocalJsDocComments`, plus an `ErrorCollector` as
the reporter) makes `Parser.parse` return an `AstRoot` **for broken source**, with every problem
collected rather than thrown, every comment attached, JSDoc attached to the declaration it precedes,
and — the part §16.1 did not weigh — a **symbol table on every `Scope` node**: `Scope.getSymbolTable()`
maps a name to a `Symbol` carrying its declaration kind (`Token.VAR`, `LET`, `CONST`, `FUNCTION`,
`LP` for a parameter), and every `Name` node answers `getDefiningScope()`. That is scope resolution
for free, from the engine's own parser, on the tree the diagnostics came from.

**Decision (a revision of §16.1's "static structure from tree-sitter"):** static structure comes from
Rhino's AST. §16.1's rule was "do not build a second JS analyzer on Rhino's AST", and this does not:
the parse already happens for diagnostics, the symbol tables are Rhino's own, and reading them is not
building anything. Using tree-sitter + `locals.scm` instead would be a *third* view of the file (grammar
tokens, Rhino diagnostics, tree-sitter scopes) that disagrees with the engine exactly where it matters —
Rhino's parser knows which syntax Rhino accepts, and `locals.scm` would happily scope a `class` body the
engine will refuse. `locals.scm` keeps its M11 place for the *engineless* languages, where there is no
engine to ask.

Everything else about Rhino that this milestone rests on, in the order it is used:

| Need | Rhino API | Notes |
|---|---|---|
| Parse for diagnostics + AST | `Parser(CompilerEnvirons, ErrorReporter)`, `ErrorCollector`, `ParseProblem` (`getFileOffset()`, `getLength()`, `getLineNumber()`, `getMessage()`, `getType()`) | Positions are **absolute offsets** plus a length — better than JDT's, which is why `JsProblemSpans` is small |
| Walk it | `AstNode`, `NodeVisitor`, `getAbsolutePosition()`, `getLength()`, `getType()`, `Scope`, `Symbol`, `Name`, `FunctionNode` (`getParams()`, `getFunctionName()`, `getJsDoc()`), `VariableDeclaration`, `PropertyGet`, `ElementGet`, `FunctionCall`, `NewExpression`, `ObjectLiteral`, `ArrayLiteral`, `StringLiteral`, `NumberLiteral` | Rhino's AST is fully positioned; there is no need for a second parse to find anything |
| Doc comments | `CompilerEnvirons.setRecordingLocalJsDocComments(true)`, `AstNode.getJsDoc()`, `AstRoot.getComments()`, `Comment.getCommentType()` | JSDoc's `@param {T} name`, `@returns {T}`, `@type {T}`, `@deprecated` are the closest thing JS has to a declaration |
| Compile | `Context.compileString(source, sourceName, 1, null)` → `Script` | Same parse; a syntax error throws `EvaluatorException` with line/column — but the analysis has already reported it, so a compile that throws is a compile that was refused upstream |
| Run | `Context.enter()`/`exit()` per thread; `cx.initStandardObjects(null, false)` per run; `ScriptableObject.putProperty(scope, name, Context.javaToJS(value, scope))` for bindings; `script.exec(cx, scope)` | Fresh scope per run is what "re-run replaces" means in JS: nothing from the previous run is reachable |
| Language level | `cx.setLanguageVersion(Context.VERSION_ES6)` — pinned by `EngineApiSurfaceTest` on every band | Same value on both Rhinos; **what it accepts differs**, which is what §2 (probe) is for |
| Interpreted vs compiled | `cx.setOptimizationLevel(-1)` | **Interpreted, decided** (§9.1): the instruction observer is unconditional there, the interpreter never emits bytecode so band-8's class-file ceiling is moot, and there is nothing to cache or to remap post-hoc — Rhino resolves Java members at call time either way |
| Stop | `ContextFactory.observeInstructionCount(Context, int)` overridden to throw; `cx.setInstructionObserverThreshold(n)` | Rhino's interpreter treats a `java.lang.Error` thrown from Java as **uncatchable by script `catch`** (finally still runs) — which is why `ScriptStoppedException` being an `Error` already, for the Java reason in §19.3, is the same class here |
| Which line is printing | Rhino's interpreter frame — `Context.getSourcePositionFromStack(int[] linep)` is package-private; the public routes are `new EvaluatorException("").lineNumber()` (constructed on the script thread it reads the current frame) and `RhinoException.getScriptStack()` | Verified by probe (§9). If neither is acceptable, a same-package accessor class in our child-loaded adapter is the honest answer — the band jars are ours to shade, which §16.1 already relies on for `JavaMembers` |
| Java access | `Packages.*`, bare `java.*` package roots, `importClass`/`importPackage` via `ImporterTopLevel`, `JavaAdapter`, `NativeJavaObject`/`NativeJavaClass`/`NativeJavaPackage` | Rhino has **no `Java.type()`** — that is Nashorn's; §16.1 names it as though it exists. Provided as a host function (§6.4) because a call with a string literal is *statically* resolvable in a way `Packages.a.b.C` also is, and KubeJS authors expect it |
| Whose classes | `cx.setApplicationClassLoader(hostLoader)` | **Load-bearing**: a binding value's class was defined by the host loader, and a script naming `Packages.com.crystalgui.…` must resolve to the same class or the two are different types with the same name. The child loader must never be the application loader |
| Sandbox | `cx.setClassShutter(ClassShutter)`; `JavaMembers` refuses to reflect a class the shutter hides, so it covers passed-in objects too, not only `Packages` lookups (verify) | The §19.2 allowlist's real enforcement layer |
| Wrapping | `WrapFactory` (`setJavaPrimitiveWrap(false)` so a Java `String` is a JS string) | KubeJS's choice and every author's expectation |
| Runtime introspection | `ScriptableObject.getIds()`, `getAllIds()`, `getPrototype()`, `has`/`get`, `NativeJavaObject.unwrap()`, `NativeFunction.getArity()`/`getFunctionName()`/`getParamCount()` (verify names per band) | The live-scope tier of §5 |
| Member lookup (the remap seam) | `JavaMembers` (package-private, `org.mozilla.javascript`) — `reflect(...)` builds the name → member map from `Class.getMethods()`/`getFields()` | The one place a readable name has to become a runtime name (§11) |

---

## 2. The matrix — Java feature, JS counterpart, honestly

Fidelity legend: **Full** — same feature, same seam, engine answers authoritatively. **Partial** — same
feature; the answer is authoritative in the cases named and absent otherwise. **Best-effort** — the
answer is a heuristic or a runtime observation and is labelled so in the SPI docs. **No** — not possible
or not honest for JS; what is shown instead is stated.

| Java feature (as shipped) | JS counterpart | Fidelity | Mechanism | Where |
|---|---|---|---|---|
| **Diagnostics** — ECJ errors and warnings with real ranges, gated on `optionalProblemsAnalysed` | Rhino parse errors + parser warnings + strict-mode warnings; **runtime errors from the last run** as a second owner | Partial | `ErrorCollector` in IDE mode; `RhinoProblemPolicy` maps message ids to severity and decides which warnings are on. `optionalProblemsAnalysed()` is **false when the parse had errors**, same retention rule as Java. Runtime `RhinoException`s (line/column from `lineNumber()`/`columnNumber()`) are pushed under owner `"js-runtime"` by `JsHost` and cleared on the next successful run — they are facts about a *run*, not the text, and must never survive an edit at that line (a `TrackedRange` in a lane, same as retained warnings) | `RhinoSourceAnalyzer`, `RhinoProblemPolicy`, `JsHost` |
| Type errors (`String s = 5`) | **No** — there are no static types | No | Nothing is drawn. The nearest honest thing is the *runtime* error the run produced, above | — |
| Unresolved name (`Foo` does not resolve) | Unresolved **free** name — a `Name` whose defining scope is null and which is not a JS global, not a host binding, not a Java package root, and (after a run) not in the live scope | Partial | Rhino's symbol tables answer scoping exactly; the "known globals" list is `initStandardObjects`' own ids, read from a scratch scope once per band rather than typed in | `RhinoSourceAnalyzer.freeNames()` |
| Unused local / unused import | Unused local, parameter, function (declared in a scope, never referenced) | Full | Symbol table has the declaration; a `Name` walk has the references. Same "hidden while broken, retained through it" behaviour via the base class | `RhinoSourceAnalyzer` |
| Unreachable code, dead branch, no-effect expression | **Constant-condition branches only** — `if (true)` / `if (false)`, from an AST walk over literal conditions | Partial | `RhinoSourceAnalyzer.withConstantConditions`, gated on the file having parsed like every other optional problem. `while (true)` is deliberately exempt: it is the idiomatic spelling of a deliberate loop, and warning about it would mark correct code in every event loop ever written. **Rhino's own warnings are NOT enabled** — `msg.no.side.effects`, `msg.trailing.comma`, `msg.missing.semi` and the rest are strict-mode output, and `environs()` sets `setStrictMode(false)` on purpose: which style opinions are worth showing is a policy decision nobody has made, and turning them on wholesale would decide it by accident. Deferred to 10.3b, where a message policy is the work | `RhinoSourceAnalyzer` |
| **`class` / `import` / `export` / `async`** — n/a in Java | The engine's own refusal, with the engine's message | Full | Rhino's parser reports it as a syntax error; the policy re-titles the message so `class` reads "classes are not supported by this engine (Rhino 1.7.15)" rather than "missing ; before statement". `async` needs the *source* rather than the message, since it lexes as an ordinary identifier | `RhinoProblemPolicy` |
| `?.` / `??` — refused on band 8, accepted on 11+ | **Warned, not re-titled** | Yes (10.3b) | On a band that refuses them these are ordinary syntax errors and need no re-title. The interesting case is the other one: a host that *accepts* them, targeting one that does not. `JsCompatibility` reports both, plus default parameters, array spread, destructuring defaults and computed properties — as **warnings**, because the code is valid where it was written and the finding is about somewhere else | `JsCompatibility` |
| **Semantic tokens** — parameter / local / field / static / captured / reassigned / unresolved / deprecated / call | parameter (`variable.parameter`), local (`variable`), function name (`function`), `const` binding (`constant`), reassigned parameter/local (`….reassigned` — assigned after declaration), captured local (`variable.captured` — referenced from an inner `FunctionNode`), free name that is a JS global (`variable.builtin`), free name that is a **host binding** (`variable.global`), unresolved free name (`variable.unresolved`), unresolved call (`function.unresolved`), **Java package segments (`module`) and the type at the end of a chain (`type`)** | Full for scope-derived and for package chains | Symbol tables + a `NodeVisitor` over `Name`/`PropertyGet`/`FunctionCall`; `markJavaChains` colours only the OUTERMOST chain, since overlapping tokens are decided by paint order rather than by intent. **`property`/`function.call` on a resolved Java member and `deprecated` from Java's `@Deprecated` are not emitted** — both need a per-node interop lookup during the token walk, which is a bridge crossing per member access on every keystroke; the same information is on the hover and in the completion list, where it is asked for deliberately. Same overlay contract as Java: only what the grammar cannot know | `RhinoSemanticTokens` |
| **`resolveAt`** — one binding, exact | Four tiers, best first: (1) **live scope** after a run — the actual value; (2) **JSDoc** on the declaration — `@type`/`@param`/`@returns`; (3) **syntactic inference** — literal kinds, `new X`, `Java.type("…")`/`Packages.…`, `function` → FUNCTION with parameter names, a Java call whose receiver resolved → the Java resolver's return type; (4) declared-but-unknown → `SymbolInfo` with kind and declaration, `type == null` | Partial (1–3), Best-effort (declared/unknown) | Each tier is a class with one method, tried in order; the tier that answered is recorded on the answer so the popup can say "(from last run)" — see §7 | `JsResolver` = `LiveScopeTier`, `JsDocTier`, `InferenceTier`, `DeclarationTier` |
| `expectedTypeAt` (what type fits here) | A **Java** callee's declared parameter type (`list.add(|)`), and a JSDoc `@param {T}` on a JavaScript one; else null | Partial | `RhinoResolution.javaParameterTypeAt` reads the same member list the popup and the hover read, so the three cannot disagree; an overload set resolves to the first member of that name with enough parameters — exact for the common case and a stated guess otherwise | `RhinoResolution` |
| **`membersOf(type)`** — generic-substituted, accessibility-checked | For a **Java** type: the Java resolver's own `membersOf`, through a probe unit (§5.3) — same items, same signatures, same quoting. For a **JS** value: the live object's own ids and prototype chain (post-run), or an object literal's properties (static), or a JSDoc-typed shape (`@typedef` — not in scope for M10) | Full for Java, Partial for JS objects (live), Best-effort (literal) | `NativeJavaObject.unwrap().getClass()` gives the Java class; everything else is `getIds()` up the prototype chain with the standard-object ids read once per band | `InteropResolver`, `LiveScope` |
| `symbolsInScope(offset)` — nearest scope first | Same, from the enclosing `Scope` chain, then host bindings, then JS globals, then Java package roots | Full | Symbol tables give declaration order and nesting; the base class's ranking chain does the rest | `RhinoSourceAnalyzer.symbolsInScope` |
| **Completion: members after `.`** | Same two questions (receiver → members), receiver resolved through the four tiers | Full/Partial as `resolveAt` | `JsCompletionProvider.memberItems`; a receiver that resolves to nothing yields the live-scope names, never nothing (§6.2) | `JsCompletionProvider` |
| Completion: open code — locals, params, fields, keywords, unimported types | locals, params, functions, **host bindings**, JS globals *(read from the engine, not tabled)*, **the JS keyword set minus what the band refuses**, Java package roots; inside `Java.type("…"` the `TypeIndex`; **after a package chain's dot (`java.util.`) the sub-packages and classes under it** | Full | Keyword set and global set are both *measured* against the band's own parser and scope — do not offer `class` on band 8, and do not mark `Proxy` unresolved on band 11 (§16.1's rule made mechanical). `TypeIndex.allUnder` answers the package question, which is a different query from `matching` and cannot be phrased as one | `JsCompletionProvider`, `JsKeywords`, `RhinoGlobals` |
| Completion: auto-import as one undo step | **No import statement in JS.** The counterpart is `Java.type("java.util.ArrayList")` accepted from a bare `ArrayList` completion — inserting the qualified call *is* the import | Partial | `additionalTextEdits` is empty; the primary edit is the whole `Java.type("…")` (or `Packages.…`) form, one undo step by construction | `JsCompletionProvider.javaTypeItem` |
| Completion: probe re-parse for an unresolved receiver (`foo.` with a trailing dot) | Same trick: insert the probe identifier, re-parse in IDE mode, resolve | Full | `AnalysedLanguageServices.analyseText` is already the seam | `JsCompletionProvider` |
| Completion: `inheritedFromObject` de-emphasis | Same flag, `Object.prototype`'s ids | Full | The engine's root — precisely what today's `builderFrom` refactor made the engine's decision | `JsCompletionProvider` |
| Completion after a run on a live object | New — the row's headline | Full (for what ran) | `LiveScope` snapshot taken **on the script thread at run end** and published to the UI thread through the same drain-on-tick path everything else uses (`JobScheduler.onDone`) — never read live from the UI thread; the scope is Rhino's and Rhino is single-threaded per `Context` | `JsHost` → `LiveScope` → `JsLanguageServices` |
| **Quick Documentation** — engine signature with tokens, owner path with kind, docs, quoted from source archives | JS declaration assembled from the AST (`function add(a, b)`, `const x`, `let y`), JSDoc body as the doc; a **Java** member reached from JS shows *exactly* the Java popup, quoted from `src.zip`/`-sources.jar` through the same `AttachedSources` | Full for Java members; Partial for JS (no visibility, no return type unless JSDoc says) | `JsSignatures` builds a `Signature` the same way `JavaSignatures` does; `containerKind` for a JS member is `SymbolKind.OBJECT`… — see §7 for the kind mapping. The tier that answered is written into the popup's owner band ("from last run", "from JSDoc") | `JsSignatures`, `InteropResolver` |
| Go-to-definition | Same, to a JS declaration in the file; to a Java declaration through the Java `DeclarationSite` when the member is Java's | Full | Symbol table has the declaration node; `AttachedSources` has the Java one | `JsResolver` |
| **Quick fixes** — 16 correction families over JDT `ASTRewrite` | A JS catalog (§8): the ones Rhino's diagnostics can name, plus intentions on the AST | Partial | Text-level `ChangeSet`s built from node positions; no rewriter exists, so `JsRewrites` is the small substrate (`replace(node, text)`, `insertBefore/After`, `delete(node)`, `wrap(node, prefix, suffix)`) | `JsCodeActions`, `JsQuickFixes`, `Js*Corrections` |
| **Run** — compile always, run explicit, replace, stop, cache, remap | Same lifecycle; no cache; remap at lookup; stop by observer | Full | `JsHost implements ScriptRuntime` (host) ↔ `RhinoExecutor` (child) — §9 | `language.js` |
| Output attribution to the script's own line | Same, from Rhino's frame | Full | `RhinoOrigin` (§9.4) | `JsHost` |
| Stack frames as links | `\tat script.js:12 (fn)` plus the JVM frames under a Java exception | Full | `RhinoStackFrameFilter` in the chain *before* `JavaStackFrameFilter` — Rhino's format is more specific | `RhinoStackFrameFilter` |
| Prelude / bindings as typed fields | Bindings as scope properties at run time, **and as typed globals in the editor**: `ScriptBindings.types()` crosses to the analyser, so a binding is coloured `variable.global`, hovers with its declared Java type, and offers that class's members before anything has run. **`console`** and **`print`** are host functions writing through `ScriptOutput.write(level, text)` with the level known | Full | No prelude means no offset shifting anywhere — every JS offset is the file's | `RhinoExecutor.bind`, `JsSourceAnalyzer.useHostBindings` |
| `System.in` prompt | `readLine()`/`prompt()` host function over `ScriptInput` | Full | Same routing; JS has no `System.in` idiom, so it is a named function | `RhinoExecutor.bind` |
| Sandbox — allowlist as guardrail | Allowlist as **real interception** — `ClassShutter`, scope curation, resolver filter, index filter | Full (and stronger than Java's, which §19.1 says) | One `ScriptPolicy` object; §10 | `ScriptPolicy`, all four consumers |
| Readable↔runtime mapping — output remap of class files | Lookup remap in `JavaMembers` (§16.1); resolver + completion read through the same `MappingSet` | Full in the fixture; production is M12 | §11 | shaded `org.mozilla.javascript.JavaMembers` |

**What is not on the list, and why**: `expectedTypeAt`-driven **smart completion** (Java's ranking by
expected type) is Partial by inheritance from `expectedTypeAt`. **Rename**, **find usages**,
**signature help** are not Java features yet either — when they land, JS gets them from the same symbol
tables, and this document does not pre-build them.

---

## 3. Architecture — `language.js`, and the crossing

### 3.1 The package, mirrored on `language.java`

```
language.js                              host side (parent loader)      child side (band loader)
  JsLanguage                              ✓  register(): host, entry, runtime, policy
  JsLanguageServices                      ✓  extends AnalysedLanguageServices
  JsCompletionProvider                    ✓
  JsCodeActions, JsQuickFixes, Js*Corrections, JsRewrites  ✓  (over bridge.Analysis + a JS AST view)
  JsSignatures                            ✓
  JsResolver + tiers, InteropResolver     ✓
  JsHost implements ScriptRuntime         ✓
  RhinoStackFrameFilter, RhinoOrigin      ✓
  ScriptPolicy                            ✓  (host owns the policy — §19.2)
  RhinoSourceAnalyzer implements bridge.JsSourceAnalyzer          ✓  names org.mozilla
  RhinoExecutor        implements bridge.JsExecutor               ✓  names org.mozilla
  RhinoProblemPolicy, RhinoScopes, RhinoJsDoc, RhinoInference     ✓  the analyser's parts
  LiveScopeSnapshot (a bridge-typed value built here)             ✓
language.engine.bridge
  JsSourceAnalyzer  (interface)  ─ analyze(sourceName, source, version, policy) → Analysis
  JsExecutor        (interface)  ─ compile / run / stop / currentLine / snapshotScope
  JsAstView         (interface)  ─ what the host-side fix catalog is allowed to ask of the tree
```

The child/host split is not a style choice; it is the same law `JavaEngine` obeys. **Anything that
names `org.mozilla` must be defined by the band loader** (child-first, and the only loader that has
Rhino), and **anything the shell holds must be defined by the host loader** (`ScriptRuntime`,
`ScriptRef`, `RunSessions`, `ScriptOutput` all live in `language.run`, which is *not* in
`EngineClassLoader.PARENT_FIRST` and must not be added — see 3.3). So `JsHost` is host-side and
implements `ScriptRuntime`; `RhinoExecutor` is child-side and implements a **bridge** interface; the
two meet through `EngineHost.adapter("com.crystalgui.language.js.rhino.exec.RhinoExecutor", JsExecutor.class)`,
exactly as `JavaEngine.over(host)` reaches `EcjScriptCompiler`. Getting this wrong fails with
`ClassCastException: ScriptRuntime cannot be cast to ScriptRuntime` — the same message the bridge note
in `EngineClassLoader` already predicts.

### 3.2 The two bridge interfaces, and one view

```java
// engine.bridge — parent-first, so both sides mean the same type
public interface JsSourceAnalyzer {
    Analysis analyze(String sourceName, String source, long version, ScriptPolicy policy);
    interface Analysis extends com.crystalgui.language.engine.bridge.Analysis {
        /** The parsed tree, behind the questions the fix catalog asks — never the Rhino nodes. */
        JsAstView ast();
        /** Every JSDoc the parse recorded, by the offset of the declaration it precedes. */
        Map<Integer, String> jsDocByDeclaration();
    }
}

public interface JsExecutor {
    Compiled compile(String sourceName, String source);          // parse+compile; messages on failure
    Object run(Compiled script, Map<String, Object> bindings,   // on the CALLING thread
               Consumer<String> out, Consumer<String> err,       // console.log / console.error
               Supplier<String> readLine,                        // prompt()/readLine()
               ScriptPolicy policy) throws Throwable;
    void stop();                                                 // arms the observer to throw
    int currentLine();                                           // this thread's script line, or -1
    LiveScopeSnapshot snapshotScope();                           // the last run's globals, walked once
    interface Compiled { boolean successful(); List<String> messages(); }
}
```

`JsAstView` exists for the same reason `Analysis` does: the host-side fix catalog cannot hold Rhino
node types, so what it may ask is enumerated — `nodeAt(offset)`, `kindOf(node)`, `rangeOf(node)`,
`children(node)`, `textOf(node)`, `enclosingFunction(offset)`, `enclosingStatement(offset)`,
`declarationOf(name, offset)`, `referencesTo(declaration)`. Nodes are opaque `long` handles or small
records; the view is closed with the analysis. This is more surface than Java needed because Java's
fixes run **inside** the child (`Correction`s are JDT-typed and live in `language.java`, which the
child loads); a JS catalog written host-side against a view is the alternative, and it is the better
one: the fixes become testable without a band on the classpath, and the child stays a parser. If a
fix genuinely needs Rhino's tree, it moves to the child and returns a `CodeAction` like Java's do —
both shapes are legal, and `codeActionsIn` on the bridge already carries the second.

### 3.3 `PARENT_FIRST` is not widened

`language.run` stays child-loadable. It is tempting to add it to `PARENT_FIRST` so the child adapter
could call `ScriptOutput.write` directly, and it would work — but it hands the child a view of the
shell, which is exactly the coupling `RunShellIsEngineNeutralTest` exists to refuse from the other
side. The adapter takes `Consumer<String>` callbacks (JDK types, parent-first by definition) and
`JsHost` wires them to `ScriptOutput.write(RunLevel.OUT, …)` on the host side, inside the
`ScriptOutput.enter/exit` bracket it already owns. Same reasoning for `stop`: `RhinoExecutor.stop()`
arms the observer; the `Error` it throws is Rhino-side and is translated to `ScriptStoppedException` by
`JsHost` when it surfaces — the child never names the run package's exception.

### 3.4 One shared host, two engines

`JsLanguage.register()` calls `EngineHost.shared(source)` — the same call `JavaLanguage.register()`
makes — and asks it for `RhinoSourceAnalyzer` and `RhinoExecutor`. Order of registration is free.
`JsLanguage.shutdown()` drops its adapters and does **not** close the host; `EngineHost.shutdown()`
is process end. This is the whole reason today's `EngineHost` refactor was done before M10 rather than
inside it.

---

## 4. Analysis — `RhinoSourceAnalyzer`

One parse per analysis, in IDE mode, yielding: the `AstRoot`, an `ErrorCollector` full of
`ParseProblem`s, JSDoc per declaration, and symbol tables. From that one parse:

**Diagnostics.** Each `ParseProblem` → `Diagnostic` at `pointOf(fileOffset)`…`pointOf(fileOffset+length)`,
severity from `RhinoProblemPolicy` (error for `getType() == "Error"`; warnings by message id — on, off,
or promoted). `optionalProblemsAnalysed()` is `false` when any error was collected, which is the same
retention contract Java has: warnings the parse could not reach are kept from the last clean parse
rather than vanishing. The **band-refusal re-titling** is a policy table keyed on message id and the
first token at the position — `class` at an error position becomes "classes are not supported by
Rhino <version>"; `import`/`export`, `async`, `await`, `?.`, `??`, `**` likewise, **only if the probe
found the band refuses them**. A band that accepts `class` produces no diagnostic and no re-title.

**Compatibility band.** ✅ **Built — with one design decision the row did not anticipate.** The row
says "detect the six constructs … **from the AST**", and that is the half that had to change.
`JsCompatibility` uses the AST only to *locate* a candidate and asks the question of the source text it
covers, because this module compiles against band 8's Rhino and runs against the host's — a gap that has
now produced four distinct failures (an inlined `Token` constant that renumbered, two accessors present
on one band and absent on the other, and `getFirstChild()` answering **null** because a node's parts are
fields rather than child-list entries). An accessor for a construct band 8 refuses is also, fairly
often, an accessor band 8's jar does not have, so the obvious implementation would not compile.

Two things that fell out of building it, both of which would have made the feature untrustworthy:
**a `??` inside a string or a comment is not an operator** (spans taken off Rhino's own lex rather than
a scanner written here, since a second lexer is a second thing to get wrong about escapes), and **it
only ever warns downward** — a target at or above the host reports nothing, because a construct the
local engine refuses is already a syntax error and saying it twice in two severities is worse than
saying it once.

The refusal table has a second column: the *target* band. `RhinoProblemPolicy`
takes a compatibility band (a setting, default "this host"), and a construct the target band's probe
file lists as refused becomes a **warning** — "not supported on Java 8 hosts" — even when the local
Rhino parsed it happily. Band 8's probe output therefore ships as a resource, not only as a build
artefact. This is what stops an author on Java 17 shipping a script that band-8 players cannot load
(§13a).

**Semantic tokens.** A `NodeVisitor` over `Name`, `PropertyGet`, `FunctionCall`, `NewExpression`,
`VariableInitializer`, `Assignment`. The rules, each one line: a `Name` whose defining scope's `Symbol`
is `LP` → `variable.parameter`; `VAR`/`LET` → `variable`; `CONST` → `constant`; `FUNCTION` →
`function`; assigned after declaration → `.reassigned` variant; referenced from a nested
`FunctionNode` → `variable.captured`; no defining scope and in the standard-object ids → `variable.builtin`;
in host bindings → `variable.global`; else `variable.unresolved` — **unless the live scope has it**, in
which case `variable.global` (a name a previous run defined is not unresolved). Java-side: a
`PropertyGet` chain rooted at `Packages` or a Java package root, or a `Java.type("…")` call → segments
`module`, last `type`; a member on a receiver the resolver typed as Java → `property`/`function.call`;
`@Deprecated` on that member → `deprecated`.

**Scopes.** `RhinoScopes` wraps the symbol tables: `enclosingScope(offset)`, `symbolsVisibleAt(offset)`
in nearest-first order, `declarationNode(symbol)`, `referencesTo(symbol)`. This is what
`symbolsInScope`, unused-detection, go-to-definition, `variable.captured` and the fix catalog all read.

**JSDoc.** `RhinoJsDoc` parses the recorded comment text into `{description, params: name→type,
returns: type, type, deprecated}` — a small tag grammar, not a Markdown renderer. Types are strings
(`{string}`, `{java.util.List}`, `{Array<number>}`) resolved by `JsDocTier`: a bare JS type name maps
to a `TypeRef` with no members beyond the standard object's; a qualified Java name goes to the Java
resolver.

---

## 5. Resolution — the tiers, and the Java resolver behind them

### 5.1 The four tiers, in the order they are asked

| Tier | Answers when | What it knows | Confidence shown |
|---|---|---|---|
| `LiveScopeTier` | a run has completed and the name is a global (or reachable from one by the property chain the caret is on) | the value's actual kind: function (name, arity), Java object (its class → Java resolver), primitive, array, plain object (its own ids) | "from last run" |
| `JsDocTier` | the declaration carries a JSDoc tag for it | declared type string, description, `@deprecated` | "from JSDoc" |
| `InferenceTier` | the initializer is a literal, `new X(...)`, `Java.type("…")`/`Packages.…`, a function expression, or a call on a receiver another tier typed as Java | literal kind; the Java class; parameter names and count; the Java method's return type | none (it is the ordinary answer) |
| `DeclarationTier` | the name is declared and nothing above answered | kind (parameter/local/const/function) and declaration site, `type == null` | none |

A tier answering `null` falls through; the first non-null answer wins and is stamped with the tier so the
popup and completion can say which kind of truth it is. **Live beats JSDoc beats inference** because it
is the order of certainty: what the object *is* outranks what the author *said*, which outranks what the
syntax *suggests* — and it is also the order a REPL user expects, which §16.1 already names as the goal.

### 5.2 Live scope — taken once, on the script thread, published like everything else

`RhinoExecutor.snapshotScope()` walks the run's global scope once and builds a `LiveScopeSnapshot`
(bridge-typed: name → `{kind, javaClassName?, functionName?, arity?, ownIds?}` to a bounded depth,
say 2, so a huge object graph is not copied). `JsHost` takes it at run end **on the script thread**,
hands it to `JsLanguageServices` through a `JobScheduler` job whose `onDone` runs on the UI thread,
and the services swap it in and re-announce semantic tokens (a previously "unresolved" name may now be
a global). Never read Rhino objects from the UI thread — a `Context` is single-threaded and the run's
scope is that context's.

### 5.3 Java interop — one probe unit, the Java resolver, everything else for free

For a Java class reached from JS — a `NativeJavaObject`'s class in the live scope, `Java.type("…")`,
`Packages.a.b.C`, `new java.util.ArrayList()`, a JSDoc `{java.util.List}` — the answer to
`membersOf`, `expectedTypeAt`, the signature, the doc, the quoting from `src.zip` and the
`inheritedFromObject` flag are all questions the **Java** resolver already answers better than
reflection would (generic substitution, accessibility, binding keys for `AttachedSources`). So
`InteropResolver` asks it: it analyses a tiny synthetic unit through the *Java* engine's
`SourceAnalyzer` — `class $Probe { <fqn> $x; }` — resolves `$x`, and holds that `Analysis` per class
name in a small LRU (they hold an AST each; a dozen is plenty). `membersOf(type, contextOffset)` is
then the Java answer verbatim, and every `CompletionItem`/`SymbolInfo` that comes back is the one the
Java popup would have shown. Generic arguments known from a `new ArrayList<String>()`-style JS
expression are not knowable — JS has no diamond — so the probe declares the raw type; that is the
one place JS members are less precise than Java's, and it is inherent.

Requires the Java engine to be present (the M10 row's "M6 (Java resolver for interop)" dependency).
When it is not — a build that ships Rhino without ECJ — `InteropResolver` falls back to reflection
over the host loader (`Class.forName(name, false, hostLoader)`, `getMethods()`), assembling
signatures the way `JavaSignatures`' fallback does. Reflection is also what `JavaMembers` will do at
run time, so the fallback shows exactly what the script can call.

---

## 6. Completion — `JsCompletionProvider`

Same shape as `JavaCompletionProvider`, same seam, same session/ranking/popup above it, and the same
two questions drawn from the *text*: receiver ending at the word start → members; otherwise open code.

### 6.1 Members after `.`

Receiver resolved through the four tiers. Java receiver → `InteropResolver.membersOf`; JS function →
`Function.prototype` ids + `name`/`length`; live plain object → its own ids and prototype chain,
`inheritedFromObject` for `Object.prototype`'s; object literal receiver (`var o = {a:1}` then `o.`) →
the literal's property names (`InferenceTier`); string/number/array literal → the standard prototype's
ids, read once per band from a scratch scope (never typed in — the probe writes the fixture). A
receiver that resolved to nothing yields the **live-scope names**, marked partial, so the popup opens
and narrows rather than staying shut — the JS equivalent of Java's `unresolvedReceiver` re-ask.

### 6.2 Open code

Order, nearest first, before ranking re-sorts by match: enclosing scopes' symbols → host bindings →
live-scope globals from the last run → JS globals → **keywords minus the band's refusals** → Java
package roots (`java`, `javax`, `Packages`, and every root the `TypeIndex` knows) → after `Java.type("`
or `Packages.` (or any package prefix): `TypeIndex` names, sampled and marked partial as Java's are.

### 6.3 Accepting

A method → `name(` + `$0` + `)` snippet as Java. A Java type from the index accepted from a bare name →
inserts `Java.type("a.b.C")` — one primary edit, no additional edits, one undo step by construction; the
alternative `Packages.a.b.C` form is a setting, default `Java.type`. Never insert `import`.

### 6.4 `Java.type` — provided, because it is what people expect and what the resolver can read

Rhino has no `Java` global. `RhinoExecutor.bind` installs one — `Java.type(name)` returning
`NativeJavaClass` via `Context.javaToJS(Class.forName(name, true, applicationLoader), scope)` through
the `ClassShutter` — and `Java.extend` deferred. Statically, `Java.type("…")` with a string literal is
a `FunctionCall` whose callee is `PropertyGet(Name("Java"), "type")`, which `InferenceTier` reads
directly; `Packages.a.b.C` and bare `java.util.List` are `PropertyGet` chains from a known root and are
read the same way. Both spellings resolve; the completion inserts the setting's.

---

## 7. Quick Documentation — `JsSignatures`

The popup is unchanged. What JS puts into `SymbolInfo`:

| Field | JS declaration | Java member reached from JS |
|---|---|---|
| `name` | the identifier | as Java |
| `kind` | `FUNCTION`, `LOCAL_VARIABLE`, `PARAMETER`, `CONSTANT` (`const`), `PROPERTY` (object member), `VARIABLE` (global from a run) | as Java |
| `type` | tier's answer or null | as Java |
| `container` | enclosing function name, or the file name for a top-level, or `Object.prototype` etc. for a live member | as Java (`java.util.List`) |
| `containerKind` | `FUNCTION` / `MODULE` (the file) / `OBJECT`… — `SymbolKind` may need `OBJECT` if it lacks one (verify; `PROPERTY` exists) | as Java |
| `documentation` | JSDoc description | as Java (M13's body, still unpopulated) |
| `modifiers` | `DEPRECATED` from `@deprecated`; `FINAL` for `const` | as Java |
| `declaration` | the symbol's node range, same document | `AttachedSources` site |
| `parameters` | JSDoc `{T}` per param when present, else `TypeRef.of("?")` — **names are shown in the signature**, which JS has and Java's compiled path lacks | as Java |
| `signature` | `function add(a, b)` / `const RATE` / `let count` / `x: 42` for a live value, tokens in the same capture vocabulary | quoted from source through `AttachedSources`, exactly Java's |

`JsSignatures` renders with the same `Signature.Builder` and the same rules `JavaSignatures` uses
(`MAX_SIGNATURE_LINE`, hanging indent for long parameter lists). One addition to the popup contract,
not to the popup: the **tier** is written into the owner band's text — `count — from last run`,
`add — from JSDoc` — because a JS answer's provenance is information a Java answer never needed to
carry, and a wrong-looking type without it reads as a bug.

---

## 8. Quick fixes and intentions — the JS catalog

Java's catalog is sixteen families over JDT's rewriter and its problem ids. JS's is smaller because
Rhino reports less and there is no static type to be wrong about; it is not zero, and the intentions
half is nearly as rich because it is AST-driven. Same registry shape (`JsQuickFixes`: message id →
correction), same `CodeAction` types, same popup, same Alt+Enter, same version gate.

**Corrections (from a diagnostic):**

| Diagnostic | Fix | Substrate |
|---|---|---|
| unresolved free name | "Did you mean 'x'" over scope names + host bindings + live globals (`SimilarNames` reused); "Declare 'x' as a local"; for a capitalised name that matches a `TypeIndex` type: "Import as Java.type("a.b.X")" | `JsRewrites`, `TypeIndex`, `SimilarNames` |
| unused local / function / parameter | "Remove 'x'" (declaration and, for `var x = f()`, keep the call), "Rename to '_x'" for a parameter | `JsRewrites` |
| `msg.var.redecl` | "Change to assignment" (`var x = 2` → `x = 2`) | `JsRewrites` |
| `msg.missing.semi` (strict warning) | "Add ';'" | `JsRewrites` |
| `msg.trailing.comma` | "Remove trailing comma" | `JsRewrites` |
| `msg.no.side.effects` / unreachable after `return` | "Remove statement" | `JsRewrites` |
| `class` refused on this band | **no fix** — a class cannot be rewritten as a function honestly; the diagnostic's message says which band accepts it | — |
| runtime `TypeError: Cannot call method "x" of undefined` at line N (from the last run) | "Guard with `if (obj)`" — offered from the run diagnostic, which is why runtime errors are diagnostics | `JsRewrites` |
| Java member on a refused type (policy) | none; the diagnostic names the policy | — |

**Intentions (no diagnostic; on the caret):**

| Intention | Condition | Notes |
|---|---|---|
| `var` → `let` / `const` | band accepts `let`/`const` (probe); `const` only when never reassigned (symbol table knows) | the JS twin of `ModifierCorrections`' `final` |
| `==` → `===` (and `!=`) | operand not `null` literal | the classic |
| function expression → arrow | band accepts arrows; body does not use `this`/`arguments` (a `Name` walk) | the twin of `LambdaCorrections` |
| string concatenation → template literal | band accepts templates; a `+` chain with at least one string literal | |
| `if`/`else if` chain → `switch` | one variable against literals — **`SwitchIntentions`' rule, ported** | |
| invert `if`, flip comparison, negate | **`Negation` shared as-is** — its edit is textual and JS's operators are Java's | reuse, not port |
| index `for` → `for…of` | band accepts `for…of`; index used only to index (`LoopIntentions`' rule, ported) | |
| wrap in `try/catch` | statement under caret | the twin of `ExceptionCorrections`' second half |
| extract to local | expression under caret; name from `Names` (reused: it derives from the expression the same way) | |
| `Packages.a.b.C` ↔ `Java.type("a.b.C")` | either spelling under caret | the settings' preferred form |

`JsRewrites` is the whole substrate — replace/insert/delete/wrap on `JsAstView` ranges, producing
`ChangeSet`s stamped with the analysis version like Java's — and it stays small because Rhino gives
absolute positions for every node. Every correction is tested through the same `FixFixture` shape Java
uses (a fixture file per family under `fixtures/js/`), and the catalog file `plan_quickfix_catalog.md`
gains a JS column rather than a second document. **— Revised: it does not, and should not.** Every row
in that file is keyed on an `IProblem` id and Rhino reports none, so a JS column would be empty for
nearly every row. The two catalogues divide differently: Java's is mostly corrections answering a
diagnostic, JavaScript's mostly intentions answering the caret — a different key, not a second column
under the same one. The JS catalogue is the class javadoc on `JsQuickFixes` and `JsIntentions`, and
§8 below is its reasoning.

---

## 9. Execution — `JsHost implements ScriptRuntime`

### 9.1 Decisions

- **Interpreted mode** (`setOptimizationLevel(-1)`), everywhere, both Rhinos. Instruction counting is
  unconditional in the interpreter (compiled mode needs `setGenerateObserverCount` and a codegen that
  band 8's Rhino may not honour identically); no class files means the band-8 ceiling and
  `ScriptCache` do not apply; and the readable→runtime question is answered at lookup (§11), which is
  the same in both modes. The cost is throughput, and a tick script that needs Rhino's compiler is a
  measurement to make later, not a default to guess now.
- **A fresh scope per run**, `initStandardObjects(null, false)`, sealed standard objects (`sealed=true`
  is a probe question — it may refuse the `Java` global's installation; if so, install then seal). The
  previous run's scope is unreachable the moment the new one starts, which is what "replace" means.
- **Application class loader = the host loader**, always. See §1.2.
- **`ScriptStoppedException` is the stop**, thrown out of `observeInstructionCount` when the executor
  has been asked to stop; Rhino's interpreter does not let script `catch` take an `Error`, so it
  unwinds through `finally` blocks and out — the same class and the same reasoning as Java's, without
  needing a second exception type. `JsHost.stop()` also `interrupt()`s the thread for the blocked
  half (a `readLine()` prompt, a Java call that sleeps), which is `ScriptInput`'s existing contract.
- **No cache.** `ScriptRuntime` never promised one; `ScriptCache` is Java's.

### 9.2 `RhinoExecutor.run`, on the calling thread

```
Context cx = factory.enterContext();          // our ContextFactory: observer threshold, VERSION_ES6, opt -1
cx.setApplicationClassLoader(hostLoader); cx.setClassShutter(policy.shutter()); cx.setWrapFactory(...)
Scriptable scope = cx.initStandardObjects(null, false);
bind(scope, bindings)                          // host objects: javaToJS; policy checked per binding
installConsole(scope, out, err); installPrompt(scope, readLine); installJava(scope)   // console.log/error/warn, print, readLine, Java.type
try { return script.exec(cx, scope); }
finally { lastScope = scope; Context.exit(); }
```

`JsHost.runAsync` wraps that in exactly what `ScriptHost.runAsync` does — daemon thread named
`cgui-script-js`, `ScriptOutput.enter(ref)` around it, `RunSessions` reporting `RUNNING`/`FINISHED`/
`STOPPED`/`FAILED`, `compareAndSet` clearing, `onFailure(ref, throwable)` — and then one more thing:
on any completion it takes `snapshotScope()` and hands it to the services (§5.2), and on failure it
converts a `RhinoException`'s `lineNumber()`/`columnNumber()`/`getScriptStackTrace()` into a
`"js-runtime"` diagnostic list for the document, pushed through the same `onDiagnostics` the analyser
uses. That last part is what makes "the run said line 12 is broken" a squiggle rather than only a
console line, and it is what the runtime-error quick fix in §8 hangs on. It is cleared by the next run
that gets past that line, and it is tracked so an edit above it moves it.

### 9.3 Console

`console.log/info/warn/error/debug` and `print` are host functions writing through the two
`Consumer<String>`s the executor was given; `JsHost` maps them to `ScriptOutput.write(RunLevel.OUT|ERROR|WARN, …)`
— the level is *known*, not inferred from a stream, which is what `ScriptOutput.write` was written for
(its javadoc names `console.log` as the intended caller). `System.out` inside a Java call from JS is
still routed by the marker, so a Java library the script uses prints into the console too. Objects are
formatted the way Node does for one level (`{ a: 1, b: 'x' }`), Java objects by `toString()`.

### 9.4 Which line is printing — `RhinoOrigin`

`ScriptRef.Origin.currentLine()` for a JS run asks `RhinoExecutor.currentLine()` on the calling thread.
The implementation is a probe question (§1.2 row): `Context.getSourcePositionFromStack` is
package-private; the public route on a live thread is to construct an `EvaluatorException` and read
`lineNumber()` (Rhino fills it from the current interpreter frame — cheap in the interpreter, since it
is a field read, not a stack walk). If a band does not fill it, the fallback is `-1`, and the console
row simply has no origin, which is what `ScriptOutput.message` already handles. **A same-package
accessor** (`org.mozilla.javascript.CgFramePeek`, compiled into our jar and loaded child-first beside
Rhino) is the honest option if the public route is too slow — the band jars are ours to shade, which
§11 already assumes.

### 9.5 Stack frames as links — `RhinoStackFrameFilter`

Rhino's `getScriptStackTrace()` lines are `\tat <sourceName>:<line> (<function>)`; the JVM frames under
a Java exception are `JavaStackFrameFilter`'s. `JsHost.consoleFilters()` returns both, Rhino's first —
the console's chain matches a line by whichever filter recognises it, and Rhino's format is the more
specific. `RunPanels.resolve` turns `app.js` into the workspace file exactly as it turns `Main.java`
today; nothing there is edited. Failure reporting (`ScriptWorkbench.report`) already prints the trace
line by line; for a `RhinoException` the script stack is printed **before** the Java one because the
script line is what the author needs first.

---

## 10. The sandbox — `ScriptPolicy` at four layers

§19.2 decides the shape: one allowlist object, host-owned, consulted wherever a name could leak. M10
builds it and consults it in all four places, and the exit criterion is one test that proves execution
and completion refuse the same type.

```java
public final class ScriptPolicy {              // language.js? no — language.run: it is not JS's
    boolean allowsClass(String binaryName);    // the one question
    boolean allowsPackage(String packageName); // for completion roots and Packages.*
    static ScriptPolicy allowAll();            // tests, and the harness's default posture
    static ScriptPolicy of(List<String> allowedPrefixes);
}
```

It lives in `language.run` because §19.2's four layers include the Java type index and Java
compilation, so it is not JS's; the JS runtime is merely the first consumer with real teeth. Consumers:

| Layer | Where consulted | What refusal looks like |
|---|---|---|
| JS execution | `ClassShutter.visibleToScripts` → `policy.allowsClass`; bindings whose class is refused are not put in scope | Rhino's own `msg.access.prohibited` at call time; a `js-runtime` diagnostic |
| Java compilation | *advisory* — out of M10's scope beyond documenting §19.1's caveat again | — |
| Completion & hover | `InteropResolver` drops refused classes and members whose declaring class is refused; `TypeIndex` queries pass through `allowsClass` | absent from the list |
| Type index | `TypeIndex` gains a filter view — `TypeIndex.filtered(policy)` — so a refused type is never *offered*; the index itself stays unfiltered because it is shared per classpath | absent from the list |

Default posture is `allowAll` in the harness — the harness is a dev tool — and the M12 platform sets
the real one. `RunPanel` gets nothing; a refused call is a run failure like any other.

---

## 11. Member-lookup remapping — the seam now, the patch when M12 has data

§16.1's rule stands and is not optional: without it the JS engine is dev-only. But mapping *data* is
M12's (§15.5 C), so M10 builds the **mechanism against a fixture** and proves it with the row's third
exit criterion.

Mechanism: the shaded per-band Rhino gains a patched `org.mozilla.javascript.JavaMembers` whose
`reflect()` consults a `MappingSet` (via a static, host-installed `MemberNameMapper` hook — the child
cannot see `language.map`, so the hook is a bridge interface with `String runtimeName(String owner,
String readable, String descriptor)`), so that a script's `world.getBlock(...)` looks up the runtime
name the class actually declares. The JS resolver and completion read the same `MappingSet` in the
other direction (runtime → readable) when listing a Java class's members, so the author sees readable
names in the list and writes readable names in the call. Test: a fixture class compiled with an
"obfuscated" method name, a `MappingSet` naming the readable one, a script calling the readable one —
it runs. Same fixture the Java remap round-trip already uses (`RemapRoundTripTest`), which is the
proof that the two languages agree about the mapping.

The alternative named in §16.1 — KubeJS's Rhino fork — is recorded again here as the fallback if
patching `JavaMembers` proves brittle across the two Rhino versions; it decides nothing until the
patch is attempted, and the patch is one method.

---

## 12. The milestones, each with its contents and its test

Numbered as §0 numbers them. Each is one commit-sized unit with its own exit, and each after 10.2 is
checked in the harness against `workspace/src/Main.js` as well as by its tests.

**10.1 — Plumbing + probe.** `Language.JAVASCRIPT` in core (`cFamily` brackets + a self-closing
backtick pair; `.` trigger falls out); `Grammar.JAVASCRIPT` → `Language.JAVASCRIPT`;
`KeywordTokenizer.javascript` for the engineless tier and its `LanguageRegistry` entry (so a dedicated
server colours `.js` keywords as it colours `.java`'s). Then `RhinoCapabilityProbeTest` in
`language.engine`, beside `EngineApiSurfaceTest`: per band, parse a fixture per construct (`class`,
`import`, `export`, `async`, `await`, `=>`, template, `let`/`const`, destructuring, rest/spread,
`for…of`, `?.`, `??`, `**`, generators) and record accepted/refused into
`build/probe/rhino-<band>.properties`; assert the shape (every construct has an answer) and pin the API
surface this milestone uses (`Parser`, `CompilerEnvirons.ideEnvirons`, `ErrorCollector`,
`ParseProblem.getFileOffset`, `Scope.getSymbolTable`, `AstNode.getJsDoc`,
`ContextFactory.observeInstructionCount`, `Context.setApplicationClassLoader`, `WrapFactory`,
`RhinoException.getScriptStack`, `NativeJavaObject.unwrap`, and the §15 questions). The properties file
is what `RhinoProblemPolicy`, `JsKeywords` and the compatibility-band warning read; nothing about a
band's syntax is a constant, and band 8's file ships as a resource. *Tests:*
`LanguageRegistry.forFileName("a.js").language() == JAVASCRIPT` headless; the probe files exist and
every construct in §13a's table has an answer.

**10.2 — Bridge + registration + the fixture.** `JsSourceAnalyzer`, `JsExecutor`, `JsAstView` in
`engine.bridge`; `RhinoSourceAnalyzer` and `RhinoExecutor` skeletons (parse, no policy yet; compile,
no run yet); `JsLanguageServices extends AnalysedLanguageServices` with `analyse` wired;
`JsLanguage.register()` through `EngineHost.shared`, `withServices` on the `.js`/`.mjs`/`.cjs`
entries, and a `JsHost` skeleton contributed to `ScriptRuntimes` so Run recognises the file. **And the
harness:** `HarnessWorkspace` calls `JsLanguage.register()` beside `JavaLanguage.register()`, and
`gl-debug-harness/workspace/src/Main.js` is created — the JS twin of `Main.java`, a deliberately
over-featured file whose sections are added milestone by milestone (a section per thing to look at,
headed with what should be visible). `app.js` stays as the small fixture it is. *Tests:*
`JsLanguageRegistrationTest` mirrors `JavaLanguageRegistrationTest` — front door, registry, services
non-null with id `javascript`, `ScriptRuntimes.open(null).forFile("Main.js")` non-null.

**10.3 — Diagnostics.** `RhinoProblemPolicy` from the probe file; re-titled band refusals; retention
through errors (`optionalProblemsAnalysed` false on a parse error); the **compatibility band** setting
(§4). *Tests:* `class` on band 8 → one error with the engine's message, and on a band that accepts it a
compatibility warning when the target is 8; a stray `.` keeps the last parse's unused warning where its
text now is; Problems rows navigate to the offset Rhino reported.

**10.4 — Semantic tokens + scopes.** `RhinoScopes`, the `NodeVisitor`, JSDoc recording. *Tests:* a
`BindingChecklistTest` twin — parameter/local/const/captured/reassigned/builtin/global/unresolved
each asserted on a fixture, including on broken source; unused local/parameter/function reported.

**10.5 — Execution.** `JsHost implements ScriptRuntime` (host) ↔ `RhinoExecutor.run/stop/currentLine/
snapshotScope` (child); fresh scope per run, application loader = host loader, `console.*`/`print`/
`readLine`/`Java.type` globals; `RhinoOrigin`; `RhinoStackFrameFilter` ahead of the JVM one; runtime
`RhinoException`s pushed as `js-runtime` diagnostics and cleared by the next run; the live-scope
snapshot taken on the script thread and published through the scheduler. *Tests:* `ScriptHostTest`
twin (runs, replaces, stops a spinning loop **and** a blocked `readLine`, 100 runs pin nothing — the
scope must be collectable), `ScriptConsoleTest` twin (a line lands with its origin), `ScriptCommandsTest`
unchanged against `ScriptRuntimes.of(javaHost, jsHost)` picking the right runtime per file;
`RunShellIsEngineNeutralTest` green throughout.

**10.5 as built** — five things differ from the sketch above, each for a reason found by running it:

- **`stop(Thread)`, not `stop()`.** One `RhinoExecutor` serves every `JsHost` in the process (it is the
  shared band adapter), so a stop has to name its run; the thread is the JDK-typed handle the host
  already holds. The observer reads a per-run flag filed on the `Context`, **and** the thread is
  interrupted — the flag rather than the interrupt status alone because `Thread.sleep` clears the status
  when it throws, so a script that swallowed the resulting exception would otherwise run on unstoppable.
- **A stopped run ends in `InterruptedException` at the bridge**, the JDK's own type for it; the child
  cannot name `ScriptStoppedException` and `JsHost` translates. Rhino's interpreter refuses a script's
  `catch` an `Error` thrown from Java, which is what makes the stop uncatchable — asserted by
  `aStopCannotBeCaughtByTheScript`. (It also skips `finally`; the sketch's "finally still runs" was wrong
  and nothing depends on it.)
- **The application loader is the host's loader plus `org.mozilla.*` from the band.** Rhino refuses an
  application loader that cannot resolve Rhino's own classes (`"Loader can not resolve Rhino classes"`),
  and the host by design cannot; the child loader would define its own copy of every host class. So
  `RhinoExecutor.APPLICATION_LOADER` is parent-first over the *bridge interface's* loader (which is the
  host's, by parent-first construction) and answers only Rhino's package itself.
- **A `Class` handed to a script — a binding, `Java.type`'s answer — is wrapped as `NativeJavaClass`**,
  never through `Context.javaToJS`, which wraps it as an ordinary object whose members are `getName()`
  and friends: `Sink.write(...)` was "Cannot find function write" until it went through
  `WrapFactory.wrapJavaClass`.
- **The runtime's verdict reaches the document through a second tracked lane on
  `AnalysedLanguageServices`** (`reportRuntimeProblems`, engine-neutral — Java's host can use it the day
  it wants to), keyed to the file through `AnalysedLanguageServices.attachedTo(Resource)`, hopped through
  the scheduler `JsHost` is given at registration. Announced at the current analysis's version, so a
  stale editor refuses it and the pending analysis carries it instead — never against the wrong text. The
  diagnostic's *source* is `js-runtime`; the *owner* stays `javascript`, because the editor files one
  owner per services object and a second owner would need a second channel through core's SPI.
- `console.warn` goes to the error consumer, as Node sends it to stderr — the bridge keeps two consumers.
  `readLine()`/`prompt()` read `System.in`, which `ScriptInput` routes to the console's input row by the
  same marker; a stop while waiting ends the script at the read rather than ten thousand instructions later.

**10.6 — Resolution + interop.** `JsResolver` with the four tiers; `InteropResolver` over the Java
probe unit with a small LRU; the reflection fallback when the Java engine is absent. *Tests:*
`new java.util.ArrayList()` → `membersOf` equals the Java analyser's for `ArrayList` (same list, same
order); JSDoc `@param {string}` types a parameter; the live scope types a global after a run; the tier
that answered is on the `SymbolInfo`.

**10.6 as built** — the tiers, and four corrections found by measuring:

- **The tiers are child-side**, in `RhinoResolution`, not host-side over a `JsAstView` as §3.1 sketched.
  Three of the four read the tree, so the sketch means a bridge crossing per node walked on every hover;
  the Java engine answers `resolveAt` on its own side and sends one `SymbolInfo` over, which is what the
  bridge is for. ~~`JsAstView` is still the right shape for the 10.9 fix catalog, which is host-side.~~
  **It is not** — 10.9 built the catalog child-side too, and for the same reason: `JsQuickFixes` walks the
  Rhino AST. There is no `JsAstView` and there should not be.
- **A `Token` constant cannot be compared across bands** — they are inlined by javac and the bands
  renumbered them, so `getType() == Token.TRUE` matches a *number literal* on band 11+. This had already
  shipped in `RhinoScopes.isAssignmentTarget` (`count++` stopped being a reassignment). `RhinoTokens` is
  the named home for the rule; `anIncrementIsAReassignmentToo` is the net that was missing.
- **Provenance rides the owner band's text** (`summarise — from JSDoc`, `count — from last run`) rather
  than a new field on core's `SymbolInfo`. §7 already specified that rendering; doing it this way keeps
  the language-neutral SPI unchanged, and inference deliberately carries no label because it is the
  ordinary answer — labelling it would put a note on nearly every symbol and hide the two that matter.
- **`Java.type("a.b.C")` is the class object and `new a.b.C()` an instance**, carried as a flag on
  `JsTypeRef` rather than as two names, so `qualifiedName()` stays the thing a cache is keyed on. The
  member sets are then filtered by `STATIC` — offering the wrong one is worse than offering none, since
  every row would be something the script cannot call there.
- The live scope is a **difference against a baseline** taken after the globals are installed: the
  standard library lives on the same scope object the script's `var`s land on, so a plain listing would
  report `Math`, `parseInt` and `console` as things the run had just created.
- `expectedTypeAt` answers only the JSDoc `@param` case. The Java-callee case is knowable and lands with
  10.7, where it has a consumer.

**10.7 — Completion.** `JsCompletionProvider`, `JsKeywords` (band-filtered), `Java.type` insertion as
one edit, live-object completion, the probe re-parse for an unresolved receiver. *Tests:* the
`JavaMemberCompletionTest` twin; "post-run completion on a live object" (the row's criterion); no
`class` keyword offered on band 8; `inheritedFromObject` set for `Object.prototype`'s ids.

**10.7 as built** — three corrections, all found by the tests:

- **The probe re-parse is needed here too**, and leaving it out was the mistake worth recording. §6.1
  argued a dynamic language can fall back to the live scope for an unresolved receiver, so `list.` was
  meant to need no probe. But a trailing dot is not a parseable expression in *any* language — there is no
  node at that offset — so the fallback fired for **every** statically typed receiver: a list appeared, it
  was the wrong list, and nothing failed. The probe is now first and the live-scope fallback is the last
  resort it was meant to be, for a receiver that genuinely has no knowable type.
- **`Object.prototype`'s ids need `getAllIds()`.** `getIds()` answers only *enumerable* properties and
  every prototype member is non-enumerable by specification, so the obvious accessor reports the root
  prototype as having no members and the inherited half of a member list silently disappears.
- **`builderFrom` already writes the call snippet**, and better than the copy this class started with: it
  puts the caret *between* the brackets when there is an argument and *after* them when there is not. The
  override always wrote `name($0)`, so accepting a no-argument method left the caret inside empty
  brackets. One converter, per the Java provider's own note.
- **Keywords are measured**, not tabled: each one whose support has ever depended on the version is put to
  the band's own parser once and kept if it survives. `class`, `import`, `export`, `async` and `await`
  fail on both shipped Rhinos and are therefore never offered — a completion row is a promise that
  accepting it produces something that runs.
- **`TypeIndex` became public** (its query surface only). "Which types are on the classpath" stopped being
  a Java-only question when a `.js` file gained `Java.type("…")` completion, and a second index would be
  the same fifty thousand entries and the same filesystem walk, duplicated, to answer identically.
- **Class names are offered only inside the `Java.type("…")` string.** A bare Java class name is not
  something JavaScript can write, so offering the index in open code would fill the popup with rows that
  are all syntax errors where they would land. Inside the literal every row is right and needs no second
  edit — the one place this interop is simpler than Java's, which has an import to bring.

**10.7, after the harness found the rest of it** — three more, and one of them was Java's:

- **A call is a receiver**, and neither engine handled it. `Files.emptyList().` and `list.get(0).` put a
  `)` before the dot; JS looked only for a `Name` and Java walked up for a `SimpleName`. Both now resolve
  the enclosing expression, whose type for a call is its callee's — so a chain composes to any depth. A
  **function declaration's `type` is therefore its return type**, never the string `function`, which is
  what a `SymbolInfo` already means for a Java method; a *variable* holding a function keeps `function`.
- **`NodeFinder` with a zero-length range answers the node ending at the offset**, so the first Java fix
  resolved `get(0)`'s `)` to `int` — and being non-null, it stopped the provider falling through to its
  probe. Length 1, and the provider now probes on an empty member list as well as on a missing type.
- **`JsLanguage.register` skipped the Java lend when already registered**, so registering JS before Java
  left interop unlent forever. Test-order dependent, which is why the suite passed scoped and failed whole.

**10.8 — Quick Documentation.** `JsSignatures`, tier provenance in the owner band, Java members quoted
through `AttachedSources`. *Tests:* `DocumentationPopupTest` twin over JS symbols; a Java member from
JS quotes `src.zip` when present; go-to-definition to a JS declaration and to a Java one.

**10.8 as built** — two things the sketch did not anticipate:

- **A Java member needs its own probe unit.** §7 said Java members are "quoted through `AttachedSources`,
  exactly Java's", which reads as free — but quoting needs the member's **binding key**, and `membersOf`
  deliberately carries no signature (it answers with hundreds for a completion list that would never read
  one) while a `SymbolInfo` carries no binding at all. So the only way to get the Java engine's own answer
  about one member is to hand it a unit in which that member is *named*:
  `class $Probe { java.util.ArrayList $x; void $m(java.lang.Object $p0) { $x.add($p0); } }` — a parameter
  of each of the member's declared types, passed at the call, which makes overload resolution exact rather
  than a guess. Parameters rather than casts, because a cast of `null` is ambiguous for a primitive and a
  cast to a type variable does not parse. Asked only on a hover, cached, and guarded end to end: it
  produces `public boolean add(E e)`, parameter name included, which no class file carries.
- **The probe contributes the signature and the declaration site ONLY** — never the whole description. It
  resolves against the *generic* declaration, so it reports the container as `java.util.ArrayList<E>` where
  `membersOf` says `java.util.ArrayList`; returning it wholesale made one member describe itself two
  different ways depending on whether a hover or a completion had asked. Caught by a 10.6 test.
- **Parameter NAMES are passed to `JsSignatures`, not carried on `SymbolInfo`.** Core's seam holds parameter
  types and deliberately not names, because JDT reports `arg0` for a classpath member — a field populated
  with a placeholder by the engine that has most members is worse than no field. JavaScript always has the
  real names because the declaration is in the file, so whoever holds the AST hands them over.
- The tier provenance §7 asks for was already done at 10.6, in the owner band's text.

**10.9 — Quick fixes + intentions.** `JsRewrites`, `JsQuickFixes`, the §8 catalog, fixtures under
`fixtures/js/`; the catalogue is the class javadoc, not a column in `plan_quickfix_catalog.md` — see §8. *Tests:* one fixture per family through
the `FixFixture` shape; `Negation`, `Names`, `SimilarNames`, `SwitchIntentions`' rule reused rather
than copied — a test asserting the JS intention and the Java one agree on the same shape.

**10.9 as built** — the families delivered, and the four the sketch listed that are not:

*Corrections:* remove an unused local (whole statement and its line; one of several names loses only its
own initializer), "did you mean" over what is in scope plus the live globals, declare a free name as a
local. *Intentions:* `var`→`let`, `var`→`const` when nothing reassigns it, `==`→`===` and `!=`→`!==`,
`Packages.a.b.C` ↔ `Java.type("a.b.C")` both ways, string concatenation → template literal, surround with
try/catch. Each with a fixture asserting the **text the edit produces**, which is the only assertion that
cannot pass against an edit at the wrong offsets.

- **`JsRewrites` is a hundred lines where `Rewrites` drives `ASTRewrite`**, and that is the language rather
  than a shortcut: Rhino reports an absolute position for every node, so a JavaScript fix is a substring
  replacement at coordinates the parser already gave. It also means no round trip through a printer that
  would re-format code the author wrote — the one thing an unasked-for edit must never do.
- **`Negation` could not be shared** as §8 claimed. Its `of(Expression, String)` takes a *JDT node*, so
  "its edit is textual" was true of the output and not of the input. `SimilarNames` genuinely is string-only
  and is now shared (made public, with a visibility note): two tolerances for "close enough" would drift
  until one engine suggested a name the other would not.
- **A template literal is measured but never offered.** `JsKeywords` gained a `template` probe so the
  intention is not offered on a band that would refuse the result — and it is filtered out of the completion
  list, because it is punctuation and a row there is a promise that accepting it produces something.
- **~~Deferred~~ — all four landed**, in `JsIntentions` beside the catalog. They were deferred as a group
  because each has to prove something about a whole body before it may fire (whether `this`/`arguments`
  appear, whether an index is used only to index, whether every arm tests one variable), and extract-to-local
  additionally waited on `Names`, whose deriving half took a JDT binding — it is now `DerivedNames` in
  `core`, split where the rule stops needing a type, so `getDisplayName()` names itself `displayName` in
  both languages from one implementation. `JsKeywords` gained an `arrow` probe beside `template`, and for
  the same reason.
  **The proofs are the work, and three of them were wrong on the first pass in ways that still compiled:**
  the `this` check compared a source span, and a `KeywordLiteral` in `this.x` measures five characters
  (`"this."`), so the conversion was offered on exactly the shape it exists to refuse; the write-through
  check excluded plain `=`, so `xs[i] = 0` read as a fetch and `for…of` silently stopped storing; and
  `break` was matched by text against a node whose source carries its semicolon. Found by dumping twelve
  fixtures through the real analyser rather than by reading the conditions.
  **It also fixed a pre-existing one:** Rhino's loops extend `Scope`, so the structural statement walk
  stopped on a loop's own header — "Surround with try/catch" offered to wrap a `while` condition. Both fix
  classes now share one walk that excludes `Loop`.
- **A refused keyword still gets no fix**, which is the one catalog entry that is an absence: `class` cannot
  be rewritten as a function honestly, and a repair that silently changed semantics is worse than none.

**10.10 — Sandbox.** `ScriptPolicy` in `language.run`, consulted by the shutter, the bindings, the
resolver and `TypeIndex.filtered`. *Test:* the row's fourth criterion — one test, refused class absent
from `membersOf`, from the completion list, from the index, and the call throws at run.

**10.10 as built** — one entry point, and why that is the whole design:

- **`JsLanguage.restrictTo` is the only way to set it**, and `JsHost.restrictTo` forwards there. It began as
  a field on the host, which is a bug in waiting rather than a style question: the executor would have obeyed
  the host's while resolution, completion and the index obeyed the language's, so a class could be **offered
  by the popup and refused at run time** — worse than either restriction alone, because the editor would be
  actively wrong. One policy, four readers. It is process-wide for the same reason: an allowlist is a
  deployment decision, so there is one deployment's worth of it.
- **The type index is filtered by a VIEW, never a copy.** It holds fifty thousand entries and is shared per
  classpath, and the policy belongs to the asker rather than to the classpath — so `TypeIndex.filtered` wraps
  the answer and two callers with different postures read one index.
- **`InteropResolver` filters on the way out**, not at the probe: the Java engine's answer about a class is
  the same whatever the policy is, so the cached analysis stays reusable and the refusal lives in one place.
  A member whose *declaring* class is refused goes too — an inherited `toString()` is still a call into the
  type that declared it. The member cache is cleared when the policy changes; the analysis cache is not,
  because it is policy-free.
- **An array is its element type and a primitive is always reachable.** Refusing `java.util.List[]` while
  admitting `java.util.List` refuses a spelling rather than a class, and refusing `int` makes every method
  taking one undescribable.
- **An empty allowlist refuses everything.** A host that means "no Java at all" must be able to say it, and
  silently widening a policy is the worst thing this class could do.
- **It is not a security boundary and the javadoc says so.** A script runs in the game's JVM, so a determined
  author has reflection; this stops accidents and casual reach, which is what the trust model asks for.
  Saying it beats letting a reader infer a sandbox that is not one.

**10.11 — Remap seam.** The `JavaMembers` patch behind a `MemberNameMapper` bridge hook;
resolver/completion reading the reverse mapping. *Test:* the row's third criterion on the
`RemapRoundTripTest` fixture — a readable-name call runs against a renamed member.

**10.11 as built** — §11's mechanism is replaced, and the reason is worth reading:

- **No patched `JavaMembers`, and no `NativeJavaObject` subclass either.** The plan proposed shading a
  patched copy of an *internal* Rhino class into each band — a fork to re-derive whenever a band moves, with
  KubeJS's Rhino fork named as the fallback. The obvious alternative, subclassing `NativeJavaObject` and
  overriding `get`, was tried and **is not available**: its `(Scriptable, Object, Class)` constructor exists
  on band 8 and not on the band we run against, so it compiles and throws `NoSuchMethodError` at the first
  binding. That is the `ObjectProperty.getLeft()` divergence for the third time.
- So it is a **membrane**: a `Scriptable` + `Wrapper` that holds whatever wrapper Rhino made and forwards to
  it, translating the name on the way through. Those two are the interfaces the engine is built around and
  cannot change shape. `Wrapper` is load-bearing rather than tidy — `NativeJavaMethod.call` unwraps its
  receiver through it, so a membrane that was only a `Scriptable` is found by the lookup and rejected by the
  call. And the membrane is **also a `Function`** when the delegate is one, or `new java.util.ArrayList()`
  fails with "is not a function": a class object is callable.
- **Overriding `wrapAsJavaObject` alone does nothing.** Rhino's own `wrap` on this band constructs the
  wrapper directly rather than calling that hook, so the feature was silently inert with the factory
  correctly installed and the mapping correctly non-identity. `wrap` is overridden and the *result* is
  wrapped, which is right whichever hook a version calls. `NativeJavaArray` is excluded, since a membrane in
  front of one would intercept its indexing to rename members an array does not have.
- **The declared name is tried first**, so an unobfuscated build and a mapping that is stale in the harmless
  direction both keep working — and it is the fast path for every unmapped lookup. The walk climbs the
  hierarchy, because a mapping names the type that *declares* a member while a script holds whatever it
  holds.
- **Both directions or neither.** `InteropResolver` renames what it lists and `getIds` renames what
  `for…in` enumerates, through the same mapper: a completion list offering `func_147439_a` beside a runtime
  that accepts `getBlock` is an editor working against its user. One entry point
  (`JsLanguage.useMemberNames`), for the reason the sandbox has one.
- **`SymbolInfo.withName`** was added to core — language-neutral, since any engine with a mapping layer
  between declared and written names needs exactly it.

**10.12 — Parity audit + docs.** Every §2 matrix row: *Full*/*Partial* has a test, *Best-effort* has a
fallback test, *No* is documented in the SPI javadoc. AGENTS.md: the `language/` row gains `.js`; new
invariants for "interpreted mode + `Error` is the stop", "application loader is the host loader",
"`PARENT_FIRST` is not widened for JS". `plan_syntax.md` §16.1: record the static-structure decision
(§1.2) and the `Java.type` correction; §20 M10 row: point here (done). `RunTest.js` beside `Main.js`.

Order matters in two places: 10.1 before 10.3/10.7 (the policy and keyword set are *read from* the
probe), and 10.5 before 10.6's live tier and 10.8's provenance (there is no live scope until something
runs).

---

## 12a. The M10 review — findings before 10.12 (2026-08-17)

Every file M10 produced or touched was read end to end against the §2 matrix, the as-built notes above and
the engine's own rules (`AGENTS.md`, `RhinoTokens`, the loader law) — the 26 files of `language.js`, the
bridge additions (`JsSourceAnalyzer`, `JsExecutor`, `LiveScopeSnapshot`, `MemberNameMapper`),
`AnalysedLanguageServices`' new lane, `ScriptPolicy`, the `TypeIndex`/`SimilarNames`/`ScriptRuntimes`
changes, the twelve JS test classes, `RhinoCapabilityProbeTest`, `HarnessWorkspace` and `Main.js`.
**Every finding below is now closed** — the list is kept verbatim as the record of what was wrong, because
half of these were invisible in a green suite and the reasoning is worth more than the diff. Each item has an
id the fix commits cite. What the fixing itself turned up is in *12a as built*, after the list.

The one-line summary, for a reader deciding whether to read on: the machinery is sound and the tests are real,
but (A) two of the four resolution tiers quietly break each other after a run, host bindings are invisible to
the analyser, and a handful of edits and lookups are wrong in the way that survives a green suite; (B) six
matrix rows are marked at a fidelity the code does not deliver; (C) the keystroke path does more full-tree
walks and whole-document copies than it needs to; (D) the package has three hand-typed lists that were each
supposed to be read from the engine, four dead members, unused imports and a dozen inlined FQNs.

### A. Correctness — wrong answers, or breakage the suite cannot see

- **R-01 · The live tier destroys what JSDoc and inference knew, after any run.** `RhinoResolution.fromDeclaration`
  asks the live scope first for every top-level declaration and, when it answers, builds the symbol from the
  live *kind* alone: a top-level `function join(name, count)` documented with `@param {string}` and
  `@returns {string}` hovers as type `function`, with no description and `?` parameters, the moment the file
  has been run — and because "a call's type is its callee's", `join('a', 1).` stops completing. The plan's rule
  ("what a value *is* outranks what the author said") is right for a *variable* whose live value is more
  informative than its declaration; for a declared function the live tier adds nothing and takes everything.
  Fix: merge rather than override — the live tier supplies the type only when the declaration tier's type is
  null or the live kind contradicts it (a `var x;` later assigned an object), and never replaces a JSDoc
  description or parameter types. Test: hover and `.`-complete a documented function *after* `run()`. Size M.
- **R-02 · Host bindings are unknown to the analyser.** `RhinoExecutor.bind` puts them in scope *before* the
  baseline is taken, so `snapshotOf` excludes them; `RhinoSemanticTokens.of` is always handed
  `hostBindings = Set.of()` (`ParsedScript.semanticTokens`); and `JsHost.compileScript` drops
  `bindingTypes` outright ("BINDING TYPES ARE IGNORED, and that is not an omission" — it is one). Result on a
  real host: a binding a mod offers is coloured `variable.unresolved`, is offered "Declare 'world' as a local"
  and "Change to …", hovers as nothing and completes to nothing — while `ScriptBindings.types()` carries its
  exact Java type, which the interop tier could type it with statically. The matrix row "bindings as scope
  properties" is marked Full and the `variable.global` capture it promises is dead code. Fix: hand
  `ScriptBindings.types()` across the bridge with the request (a `Map<String,String>` beside `liveScope` on
  `JsSourceAnalyzer.analyze`), declare each as a known global of `JsTypeRef.javaInstance(type)`, colour it
  `variable.global`, keep it out of the "did you mean" candidates. Invisible in the harness only because it
  registers no bindings. Size M.
- **R-03 · `remove-unused` on the first of several names deletes across statements.** `JsQuickFixes.removeUnused`
  finds the comma to take with `edits.textIn(0, start).lastIndexOf(',')` — the last comma *anywhere in the
  file* before the initializer. For `var unused = 1, kept = 2;` there is none inside the statement, so it
  takes one from an earlier line and the edit deletes from there to `unused = 1`. Fixture covers only the
  second-position case (`oneUnusedNameOfSeveralLosesOnlyItsOwnInitializer`). Fix: bound the search to the
  statement; when the initializer is first, remove the comma *after* it. Add the first-position fixture. Size S.
- **R-04 · `concatenationToTemplate` changes arithmetic.** `1 + 2 + 'a'` is `"3a"`; the intention rewrites it
  to `` `${1}${2}a` `` = `"12a"`. The guard "contains a string literal" is not the rule; the rule is that the
  leftmost `+` must already be string concatenation, i.e. `parts[0]` or `parts[1]` is a string literal. Fixture
  `anArithmeticSumIsNotOfferedATemplate` tests a chain with *no* string and so cannot see this. Size S.
- **R-05 · A `catch (e)` parameter is a free name.** `RhinoScopes.collectDeclarations` declares `var`/`let`/
  `const`, function names and parameters — not a catch clause's name (nor `for (x in …)`'s undeclared
  iterator, nor destructuring targets, the last documented as skipped). Rhino resolves `e` to the catch
  `Scope`, no declaration is keyed there, so `e` inside every `catch` block is `variable.unresolved` and gets
  a rename/declare fix offered. Untested — no analysis fixture contains a `catch`. Fix: declare
  `CatchClause.getVarName()` (kind PARAMETER, owner = enclosing function) and, separately, decide what to
  do about a destructuring pattern's names (declare each `Name` under it, at least). Size S/M.
- **R-06 · Block scope is not modelled, and it shows up as duplicates and wrong picks.** `Declaration.owner` is a
  `FunctionNode`, so `visibleAt`/`visibleDeclaration` treat every `let`/`const` as function-scoped: two
  sibling-block `let x`s are two declarations (correct, keyed by `Scope`) that both appear in the completion
  list, and `visibleDeclaration` picks the *first* for a hover in the second block. `resolveName(Name, …)`
  has the `Name` in hand and should ask `scopes.declarationOf(name)` — the parser's own answer — before
  falling back to the depth heuristic, which is only needed where there is no node. Then `visibleAt` should
  walk the `Scope` chain from the offset. Size M.
- **R-07 · The membrane blinds two Java-class checks.** Under a mapping every Java class object is a
  `MappedFunction`, not a `NativeJavaClass`; `RhinoExecutor.describe` reaches the `Wrapper` branch and
  reports `var W = Java.type('…')` as `JAVA_OBJECT` of `java.lang.Class`, so after a run `W.` lists
  `Class`'s instance members; `RhinoConsoleFormat.inspect` prints `class java.util.ArrayList` — the exact
  "reads as a typo" the javadoc guards against. Both branches should test `unwrap() instanceof Class` rather
  than the wrapper's class. Size S.
- **R-08 · The membrane is not a `SymbolScriptable`.** `NativeJavaObject` implements it (per band — verify with
  the probe), and it is how `for (x of javaList)` and `Symbol.iterator` reach a Java `Iterable`. A membrane
  that only forwards `Scriptable` loses that for every mapped deployment. Forward `get/has/put/delete(Symbol,…)`
  when the delegate implements it. Size S, plus one probe row.
- **R-09 · `RhinoExecutor.stop(Thread)` interrupts a thread it has no run on.** `if (thread.isAlive()) thread.interrupt()`
  runs whether or not `RUNS.get(thread)` found anything ("harmless when nothing of ours is running" — it is
  not: it sets the interrupt status of a foreign thread). Reachable: `JsHost.run` (sync) after the executor
  has removed its `RUNS` entry but before `running` is cleared, or a Stop that lands in the window between
  `running.set(prepared)` and `prepared.thread = …` in both `run` and `runAsync`. Fix: interrupt only when a
  run of ours is registered; set `prepared.thread` before publishing `running`; and consider clearing the
  re-armed interrupt status in `JsHost` when the caller was synchronous. Size S.
- **R-10 · `describeMember`'s cache key is arity, not signature.** `binaryName + "." + name + "/" + parameters.size()`
  makes `Math.max(int,int)` and `max(double,double)` one entry: the second hover shows the first's quoted
  line. Key on the parameter qualified names. Size S.
- **R-11 · Nested classes disagree between editor and runtime.** `RhinoInference.javaNameOf` yields
  `java.util.Map.Entry`, which the Java probe resolves (source form) — while `Java.type("java.util.Map.Entry")`
  and the reflection fallback need `Map$Entry` and throw "no such class". Nashorn's `Java.type` retries by
  replacing the last dots with `$`; do the same in `installGlobals`' `type` and in `InteropResolver.loadClass`.
  Size S.
- **R-12 · Two tokens on one range for an unresolved call.** `RhinoSemanticTokens.of` emits `variable.unresolved`
  for the callee `Name` (it is a free name) and `markUnresolvedCalls` emits `function.unresolved` on the same
  span; which paints is order-dependent — the exact overlap `AGENTS.md` warns about. Skip the free-name token
  when the name is a call target. Size S.
- **R-13 · `RhinoGlobals` is short of what the executor installs.** `prompt` (installed beside `readLine`),
  and the package roots `edu` and `net` (installed by `initStandardObjects` and listed in
  `RhinoInference.PACKAGE_ROOTS`) are missing, so `prompt('…')` and `net.minecraft.…` — the most likely first
  line a mod author writes — are marked unresolved and offered a rename. Root cause is three lists that must
  agree (see R-32). Size S once R-32 is done.
- **R-14 · Runtime problems are mapped against the wrong document.** `JsHost.publishFailure` reports a row/column
  from the text *as compiled*; `AnalysedLanguageServices.reportRuntimeProblems` converts it against the buffer
  *now*. A line typed above during the run puts the squiggle one row off — the "conversion is only legal
  against the document the analysis saw" rule, broken by the one lane that was added after it was written.
  Fix: carry the buffer version the compile saw on the `ScriptRef`/`Compiled`, and either refuse a stale report
  or map it through the intervening `ChangeSet`s. Size M.
- **R-15 · The Rhino "measured" claims about `WrapFactory` contradict Rhino's published source.** `RhinoRemapping`
  and `RhinoExecutor.wrap` both state that on the band we run against `WrapFactory.wrap` "constructs the
  wrapper directly rather than calling `wrapAsJavaObject`" and that `Context.javaToJS` "does not route through
  the context's factory". In every Rhino source since 1.7 `wrap()` ends in `return wrapAsJavaObject(...)` and
  `javaToJS` calls `cx.getWrapFactory().wrap(...)`. The code is correct either way (both hooks are overridden),
  but the comments teach a false fact; the earlier "inert" behaviour is more plausibly explained by the
  `NativeJavaObject`-subclass constructor that was failing at the same time. Either pin the claim in the probe
  (call `wrap` on each band with a factory whose `wrapAsJavaObject` records) or soften the two comments to
  "overridden at both hooks so the question does not arise". Size S.
- **R-16 · `resolveExpression` only knows calls.** A dot after `"str"`, `[1,2]`, `(x)` or a parenthesised
  chain resolves nothing and falls to the live-names sample. `nodeAt(offset, AstNode.class)` then
  `typeOf(node)` covers all of them for free. Size S. (Its usefulness depends on R-22.)
- **R-17 · `JsTypeRef.object(keys)` (uncommitted, this session) breaks the class's own invariant.** Two literals
  share `qualifiedName() == "Object"` and differ in `keys`, and `keys` is the one mutable field in an
  otherwise immutable value type. Make it a constructor argument; and since the live tier's `OBJECT` entries
  carry `ownIds`, `typeOfLive` should build the same shape so `membersOf` answers live objects too and
  `JsCompletionProvider.memberItems` loses its second code path for them. Size S.
- **R-18 · `AnalysedLanguageServices` publishes `this` from its constructor.** `ATTACHED.put(file, this)`
  runs before the subclass constructor body; a runtime thread that calls `attachedTo(file)` in that window
  sees a half-built services object. Reachable only across threads and only if a run for that file is already
  in flight when its document reopens; still, register from `start()`. Size S.

### B. Design and parity — where the matrix says one thing and the code another

- **R-20 · Six matrix rows overstate what shipped.** Recorded here so 10.12 audits against the truth rather than
  the table: *Rhino's own warnings* (`msg.no.side.effects`, `msg.var.redecl`, `msg.dup.parms` …) — the policy
  turns nothing on and strict mode is off, so none is ever emitted; *constant-condition dead branches* — not
  implemented; *`?.`/`??` refusals* — no re-title (10.3b); *Java-derived semantic tokens* (`module` on
  package segments, `type` on a `Packages`/`Java.type` result, `property`/`function.call` on a resolved
  Java member, `deprecated` from Java's `@Deprecated`) — none emitted; *`expectedTypeAt` for a Java callee* —
  still only JSDoc, though 10.6's note promised it "with 10.7"; *`Packages.`/`java.util.` completion from the
  `TypeIndex`* — only the `Java.type("` string is served. Each is either built (most are S–M) or the row is
  re-marked and the "No" documented in the SPI javadoc, per 10.12's own rule. Size: decision first.
- **R-21 · The JS standard prototypes are never listed.** `"abc".`, `[1,2].`, `settings.label.` complete to
  nothing (or, worse, to the live-names sample — R-22). `RhinoGlobals` already reads `Object.prototype` per
  band; read `String/Array/Number/Function/RegExp.prototype` the same way (`getAllIds`) and let
  `RhinoResolution.membersOf` answer them for `JsTypeRef.js(...)`. This is what makes R-16 worth doing. Size M.
- **R-22 · The "untypable receiver" fallback fires for typed receivers with no members.** A receiver that
  resolved to `string`/`number`/`Array` yields an empty `membersOf`, `probedMembers` is skipped (the type was
  non-null), no live entry, so `memberItems` marks the answer partial and offers *every global the run left*
  as members of a string. Restrict the sample to a receiver whose type is genuinely unknown. Size S (with R-21).
- **R-23 · `SimilarNames` is loaded twice.** `JsQuickFixes` (child-side, imports `org.mozilla`) imports
  `com.crystalgui.language.java.SimilarNames`; the band loader is child-first for `com.crystalgui.language.*`
  and carries our class files, so it defines its own copy — a second class with the same name on the far side
  of the bridge, silently, and the first breach of "the child names only JDK types, `text.*` and the bridge".
  Harmless today (stateless), and a precedent. Move it to a package the loader shares (it is language-neutral
  string ranking; `com.crystalgui.text.lang` in core would do) or add a bytecode scan that fails when a
  Rhino-importing class names `language.java`/`language.run`. Size S.
- **R-24 · The Rhino-side/host-side split is no longer legible from the names.** `Js*` was host, `Rhino*` was
  child; now `JsQuickFixes`/`JsRewrites` are child (they import the AST) and `RhinoOrigin`/`RhinoStackFrameFilter`
  are host. Either rename (`RhinoQuickFixes`, `RhinoRewrites`, `JsOrigin`, `JsStackFrameFilter`) or put a
  two-column table in the package's javadoc; the loader law is the one thing a newcomer must not guess. Size S.
- **R-25 · TCCL is the engine loader for the whole of every run.** `RhinoThread.with` wraps the entire script
  execution, so every host callback a script makes runs with the band loader as context loader — any
  `ServiceLoader`/TCCL lookup in mod or CrystalGUI code called from a script now resolves against the child
  loader and can define duplicate host classes. The class note says the answer is cached at Rhino class
  initialisation, which means one warm-up under `with(...)` at adapter construction (touch `Context`,
  compile one regex) would make every later entry safe *without* swapping TCCL for the run's duration. Keep
  `with` for the parse and the compile; drop it from `run`, or narrow it to `enterContext`. Size S, plus a test
  that a script's Java call sees the host's TCCL. Verify on band 11/17 first (the regex symptom).
- **R-26 · `InteropResolver` serialises hovers behind analyses, and compiles on the UI thread.** Every method is
  `synchronized`; `describe`/`exists`/`describeMember` run an ECJ analysis inside the lock, from the analysis
  job *and* from `resolveAt` on the UI thread. And with `MAX_CACHED = 12` a file whose JSDoc names more than
  twelve Java classes thrashes the LRU on every `symbolsInScope` (each declaration → `parametersOf` →
  `typeNamed` → `exists`). Cache the *derived* facts (type name, member list — plain values) unbounded and keep
  the `Analysis` LRU small (or close an analysis right after its members are read; `javaTypeOf` is the only
  reader of the live `TypeRef` and it is unused — R-40); take the probe out of the lock. Size M.
- **R-27 · `ScriptPolicy` handles `Foo[]` but not the JVM's `[LFoo;`.** The shutter is asked with binary names;
  arrays never reach it today, so it is latent — but the javadoc claims arrays are handled and the one form a
  JVM produces would be refused. Normalise `[L…;`/`[[I` too, or say which spelling is meant. Size S.
- **R-28 · `JsCompletionProvider.itemFor` marks only `Object.prototype` inherited.** For a Java receiver the root
  is `java.lang.Object`, and Java's own provider sinks those; from JS `toString`, `hashCode`, `wait`,
  `notify` sit at full rank in every `new java.util.ArrayList().` list. Ask the symbol's container against
  both roots. Size S.
- **R-29 · The engineless keyword/global lists have grown a host-side twin.** `JsCompletionProvider.GLOBALS` is
  a hand-typed 26-name array — the exact list `RhinoGlobals` exists to *not* be — and already differs from the
  engine's (no `Map`, `Set`, `Symbol`, `Promise`, `Infinity`, `Reflect`, `Packages`). Cross it the way
  `keywords()` crosses: a `globals()` default on `JsSourceAnalyzer`, or fold the builtins into
  `symbolsInScope`. Size S.
- **R-30 · "Did you mean" ignores the builtins.** `consle.log` — the commonest typo — offers nothing, because
  `suggestSimilarNames` ranks over declarations and live names only. Add `RhinoGlobals.names()`. Size S.
- **R-31 · `let` is recorded as `var`.** `RhinoScopes` keeps only `isConst()`; `JsSignatures.keywordFor` then
  prints `var` for every `let`, and `varToLetOrConst` cannot tell whether the keyword it is offering to change
  is already `let` except by reading three characters of text. Record `isLet()` on `Declaration`. Size S.
- **R-32 · Three lists that must agree, in three files.** Package roots: `RhinoInference.PACKAGE_ROOTS`
  (7), `JsCompletionProvider.PACKAGE_ROOTS` (7, host-side copy), `RhinoGlobals` (5 of them). Host globals:
  `RhinoExecutor.installGlobals` installs `console/print/readLine/prompt/Java`, `RhinoGlobals` lists four of
  the five. One constant per fact, on the child side, crossed once (`JsSourceAnalyzer.globals()`), and
  `RhinoExecutor` and `RhinoGlobals` reading the same `HOST_GLOBALS`. Closes R-13 and R-29 properly. Size S.
- **R-33 · Two definitions of "the host loader".** `RhinoExecutor.APPLICATION_LOADER` and
  `InteropResolver.HOST_LOADER` are both `JsExecutor.class.getClassLoader()` with different decoration; the
  editor's `exists()` and the runtime's `Java.type` can therefore disagree about a class (R-11 is one way).
  One package-private holder. Size S.
- **R-34 · `unusedDeclarationAt` re-states `withUnusedWarnings`' rule.** Which unused declarations are reported
  (not parameters, not top level) is spelled twice, once in the analyser and once in the fix catalog; the day
  one changes the fix appears on a warning that is not there. One predicate on `Declaration`. Size S.
- **R-35 · `MAX_SIGNATURE_LINE = 72` and `isPrimitive` are each defined twice.** `JsSignatures`/`JavaSignatures`
  and `InteropResolver`/`ScriptPolicy`. Share the constant (core's `Signature`?) and the primitive test. Size S.
- **R-36 · The 10.6 note about `JsAstView` is contradicted by 10.9.** "still the right shape for the 10.9 fix
  catalog, which is host-side" — the catalog was built child-side (`JsQuickFixes` walks the Rhino AST). Fix
  the note; there is no `JsAstView` and there should not be. Size S (docs).
- **R-37 · §0's "the shell is not edited" exit criterion is already false, by design.** `ScriptPolicy` and
  `ScriptRuntime.restrictTo` are in `language.run` (§10 says why) and `ScriptRuntimes.consoleFilters` dedupes.
  Rewrite the criterion as what it now means: no file under `language.run` *names a language*, which is what
  `RunShellIsEngineNeutralTest` actually pins. Size S (docs).

### C. Performance on the keystroke and console paths

- **R-40 · Whole-tree walks per query, several per gesture.** `RhinoResolution.nodeAt` and
  `JsQuickFixes.nodeCovering` are two copies of the same unpruned visitor; `actionsIn` calls its copy five
  times per Alt+Enter, and a completion pays `nameAt` + `resolveExpression` + the probe re-parse's own two.
  One `nodeAt` shared by both, computed once per gesture; prune subtrees that end before the offset (the
  "recovered tree may not cover its children" caveat only forbids pruning by *parent extent*, not by
  position of siblings already passed). Size S.
- **R-41 · `RhinoTokens.isIncrementOrDecrementOf` renders the parent's whole subtree per reference.**
  `parent.toSource()` for every `Name` visited in `collectReferences` — a name that is an argument of a large
  call, or an element of a long array literal, re-renders the entire parent each time: quadratic on the file
  the analyser runs on at every keystroke. Cheap gate first: `parent.getLength() == name.length() + 2` before
  any `toSource`. And verify on the probe whether `org.mozilla.javascript.ast.UpdateExpression` exists on
  1.7.15.1 — if it does, `parent instanceof UpdateExpression && operand == name` is a class test needing no
  operator constant at all, and the text path can go. Size S.
- **R-42 · `buffer.toString()` up to five times per completion.** `receiverEndingAt`, `memberItems`,
  `identifierEndingAt`, `probedMembers`, `typeNamesFor` each materialise the rope. Read once per
  `complete()`. Size S.
- **R-43 · `JsQuickFixes.length()` copies the document to measure it, inside a loop.**
  `edits.textIn(0, Integer.MAX_VALUE).length()` is the loop bound of `operatorOffsetIn` (per character of the
  gap) and `charAt` allocates a one-character substring. `JsRewrites` should expose `length()` and `charAt()`.
  Size S.
- **R-44 · `symbolsInScope` does JSDoc + signature work for every declaration on every keystroke.**
  `fromDeclaration` parses the doc comment (a scan of the whole comment list) and renders a `Signature` per
  visible declaration; the popup then filters most away. Either build the `SymbolInfo` lazily behind the
  filter or cache `RhinoJsDoc.forDeclaration` per declaration on the `Declaration`. Size S–M.
- **R-45 · `currentLine()` builds an exception per console line.** `new EvaluatorException("origin")` fills a
  JVM stack trace (`Throwable.fillInStackTrace`) as well as the interpreter's frame chain — the JsExecutor
  javadoc calls it cheap and it is not, per line of a script that prints in a loop. Rhino's `RhinoException`
  cannot disable stack-trace filling from outside; measure, and if it shows, throttle (attribute per burst) or
  accept a same-package accessor in the shaded jar as §9.4 already contemplates. Size S to measure.
- **R-46 · Membrane translation allocates per hop.** `translateIn` builds `getName().replace('.', '/')` for
  every class in the hierarchy on every *miss*; `getIds` does it per id. A `ClassValue<String>` for the
  internal name makes it a lookup. Only matters under a mapping; note and defer. Size S.
- **R-47 · `getIds()`/`readable()` walk superclasses only while `translate` walks interfaces too** — and
  neither walks super-interfaces. A mapping on `Collection` is missed for a class implementing `List`.
  Symmetric walk over the full type hierarchy, once, cached per class. Size S.

### D. Cleanliness — dead code, drift, style

- **R-50 · Dead members:** `RhinoThread.run`, `RhinoInference.isJavaTypeCall`, `InteropResolver.javaTypeOf`,
  `RhinoSourceAnalyzer.ParsedScript.source()`, `JsQuickFixes.wrapInTryCatch`'s `to` parameter, and
  `InteropResolver.hasJavaEngine` (test-only; keep or move behind a testing door). Size S.
- **R-51 · Unused imports:** `TextPoint` in `RhinoSourceAnalyzer`; `Token` and `UnaryExpression` in
  `RhinoScopes` (both named only in comments). Size S.
- **R-52 · Inlined fully-qualified names**, against the standing rule (import, never inline):
  `JsCompletionProvider` ×4 (`java.util.function.Function`, `Consumer`), `JsLanguageServices` ×1
  (`Supplier`), `JsKeywords` ×2 (`java.util.Set`), `RhinoSourceAnalyzer` ×2 (`Predicate`), `RhinoInference`
  ×1 (`org.mozilla.javascript.Node`), `TypeIndex` ×3 (`Predicate`), and in tests `JsSandboxTest` ×3
  (`TypeIndex`), `JsRemapTest` ×1 (`TypeRef`), `JsAnalysisTest`/`JsResolutionTest` (`java.util.List`). Size S.
- **R-53 · Explicit type witnesses** (`List.<String>of()`, `Set.<String>of()`, `Collections.<X>emptyList()`)
  in `RhinoExecutor`, `RhinoResolution`, `RhinoSourceAnalyzer`, `JsSignatures`, `JsLanguageServices` —
  fourteen sites the compiler infers unaided. Size S.
- **R-54 · Orphaned and doubled javadoc:** `JsKeywords` (a javadoc for "Every keyword this engine accepts"
  sits above `NOT_OFFERED`, whose own doc follows it), `RhinoGlobals.isBuiltin` (two stacked doc blocks),
  `JavaLanguageServices.typeIndexFor` (two stacked), `TypeIndex.matching`'s doc now sits above `filtered`
  and `matching` has none. Size S.
- **R-55 · Stale prose:** `RhinoSourceAnalyzer`'s class note ("Resolution and completion are M10.6 and M10.7
  and answer empty until then"); `JsSourceAnalyzer.useJavaEngine` ("Called once at registration" — it is now
  retried on every document open); plan §1.2 table ("finally still runs" — 10.5 as built says it does not);
  `Main.js` header ("Still to come: M10.9") with no 10.9/10.10/10.11 sections; `JsKeywords.UNCONDITIONAL`
  claims "statement and declaration starters only" while listing `in`, `instanceof`, `typeof`, `void`,
  `delete`. Size S.
- **R-56 · Small shapes:** four telescoping constructors on `JsLanguageServices` (one full one plus a test
  convenience would do); `JsCompletionProvider.sampled` is per-request state kept in a field; `JsHost` has a
  triple blank line at its field block; `RhinoStackFrameFilter.apply` declares `links = null` then assigns;
  `ParsedScript`'s constructor assigns `fixes` first out of order; `RhinoConsoleFormat.inspect` tests
  `ConsString` after `CharSequence`, which already covers it; `JsLanguage.shutdown()` resets the mapping
  but not the policy. Size S.
- **R-57 · `JsRewrites` says it stamps the version and does not.** The class note ("every edit is one
  `ChangeSet`, stamped with the analysis version") describes what `JsQuickFixes.fix/refactor` do with
  `edits.version()`; `ChangeSet.of` carries no version. Move the sentence to where it is true. Size S.
- **R-58 · `LineIndex` splits on `\n` only.** Correct for the buffer's normalised text; say so, since the Java
  side never had the question (ECJ reports rows). Size S (docs).

### E. Tests and the probe — what is unpinned

- **R-60 · Three band divergences the code is built around are not in `RhinoCapabilityProbeTest`:** the
  `Token` renumbering (`RhinoTokens`' whole reason), the `NativeJavaObject(Scriptable, Object, Class)`
  constructor's absence on band 11+ (`RhinoRemapping`'s reason), and whether `WrapFactory.wrap` routes through
  `wrapAsJavaObject` (R-15). Each is one reflective row; the plan's own rule (§15) is that every band claim is
  pinned before it is built on. Add `NativeJavaObject implements SymbolScriptable` (R-08) and
  `UpdateExpression` presence (R-41) while there. Size S.
- **R-61 · Missing fixtures for A-tier items:** hover/complete a documented function after a run (R-01);
  a host binding coloured and typed (R-02); first-of-several `remove-unused` (R-03); `1 + 2 + 'a'` (R-04);
  a `catch (e)` body (R-05); sibling-block `let` hover (R-06); `Java.type` of a nested class (R-11);
  a mapped `Java.type` result in the live scope and in `console.log` (R-07); `for…of` over a Java list under
  a mapping (R-08). Size: with the fixes.
- **R-62 · `JsRemapTest` and `JsSandboxTest` restore process-wide state in `@After`, `JsLanguage.shutdown()`
  does not.** A test that fails before its `@After` leaks a mapping into every later test in the JVM (the
  test says so itself). `shutdown()` — or a `resetForTesting()` — should restore *both* the policy and the
  mapping, and the two test classes should call that. Size S.

### F. 10.12, unchanged — plus what this review adds to it

The 10.12 list in §12 stands (matrix audit, AGENTS rows, the three invariants, `plan_syntax.md` §16.1,
`RunTest.js`). This review adds: audit the matrix against R-20 first, so rows are re-marked before tests are
written for them; add the AGENTS invariants this review found the hard way — *host bindings must reach the
analyser as well as the executor* (R-02), *the live tier merges, it does not override* (R-01), *the child may
name only JDK/`text.*`/bridge types, and a shared utility is moved rather than imported across* (R-23),
*a `Rhino` prefix means child-side* (R-24); and fix the plan's own three inconsistencies (R-36, R-37, and
§1.2's `finally` row).

Delivered in five commits, in that order: `a8fc16f` (the live tier and scopes), `8797c03` (the executor, the
membrane, the interop caches), `392fc7e` (host bindings, the prototypes, the shared-utility move),
`297cacf` (three more band divergences the new tests found), `b1d42f2` (the matrix rows, the AGENTS
invariants, `RunTest.js`).

### 12a as built — what fixing it turned up

Four things worth recording, because each was found *by* a fix rather than by the review:

- **Three more band divergences, and one of them answers null rather than throwing.** `CatchClause.getVarName()`
  is on band 8 and not on the band we run against — the `ObjectProperty` shape for the third time. Reaching for
  the obvious structural alternative, `getFirstChild()`, then found the fourth and nastiest: on this band a
  node's parts are **fields** rather than entries in the generic child list, so it answers **null** with nothing
  thrown. It took the object literals with it — `leftOf` had just been switched to it for the same good reason,
  found no key for any property in any file, and `o.` silently offered the prototype's members and none of the
  object's own. **`visit` is the one reading true on both bands**: overridden per node type, reaches the fields
  however they are stored, and goes in source order.
- **R-16 reintroduced the `NodeFinder` trap in Rhino's spelling.** Answering `resolveAt` for *any* expression
  rather than only a call is right, but the inclusive containment test also matches every node that merely
  **ends** at the offset — so `s.append('a').append('b').` resolved to `string`, off the argument. Strict
  containment for a receiver; inclusive for a hover, which legitimately means "the identifier I am just past".
  Caught by the chain test, which is exactly why that test exists.
- **Fixing an overlap created another one, one pass further along.** The free-name pass was made to stand aside
  for `markUnresolvedCalls`; then the new package-chain colouring gave `java` a second token, because the
  "is it a builtin" test ran *first* and claimed it. The rule is not "skip calls" but **stand aside for any more
  specific pass, before deciding what kind of free name this is**.
- **`Integer.MAX_VALUE` is a value, not a sentinel.** `Diagnostic.onRow` spells "to the end of this line" that
  way, so the new offset conversion overflowed and every runtime mark collapsed to a point. `Rope.pointToOffset`
  has a test named for this; the runtime lane became its second reader and repeated it verbatim.

And two things the review recommended were **not** done, deliberately:

- **Rhino's own warnings stay off.** The matrix row is re-marked rather than built: they are strict-mode output,
  and which style opinions are worth showing is a policy decision nobody has made. Turning them on wholesale
  would decide it by accident, in the wrong file.
- **`property`/`function.call` on a resolved Java member is still not a semantic token.** It needs a per-node
  interop lookup during the token walk — a bridge crossing per member access, on every keystroke — and the same
  information is already on the hover and in the completion list, where it is asked for deliberately.

---

## 13. Decisions recorded, and revisions to `plan_syntax.md`

- **Static structure from Rhino's AST, not tree-sitter `locals.scm`** — §1.2. Revises §16.1's third
  bullet; the reason is that it is the engine's own view of the file and it comes from the parse the
  diagnostics already needed. `locals.scm` remains M11's, for the engineless languages.
- **`Java.type` is provided by us**, Rhino does not have it — §6.4. Corrects §16.1's wording.
- **Interpreted mode, no cache** — §9.1.
- **`ScriptStoppedException` is the JS stop too** — §9.1; §19.3's "throw an Error" and Rhino's
  uncatchable-Error rule are the same fact seen from both sides.
- **`PARENT_FIRST` is not widened**; the child talks in JDK types and bridge interfaces — §3.3.
- **`ScriptPolicy` lives in `language.run`** — §10 — because three of its four consumers are not JS.
- **Runtime errors are diagnostics** with their own owner and lane — §9.2.
- **The tier that answered is shown** — §7 — because a JS answer without provenance reads as wrong.
- **Fixture files stay out of `engine.bridge`.** The Java demo files (`Main.java`, `Ask.java`,
  `QuickFix*.java`) sit in main source of the parent-first bridge package today; JS fixtures go under
  `language/src/test/resources/fixtures/js/` and the harness `workspace/`, and the Java ones should
  follow when their owner is done with them.

## 13a. Why Rhino — and what would move us off it

Asked before this document was committed, and worth answering in it: Rhino is the oldest of the three
candidates (1997; Nashorn 2014; GraalJS 2019) and still actively maintained (1.7.15 in 2024, 1.8/1.9 in
2025). It was chosen for reasons that have nothing to do with age and everything to do with *where it
runs* — inside a Minecraft process whose JVM flags we do not control, spanning Java 8 to 17+, shipped
in a mod jar.

| | **Rhino** (pinned at M5) | **GraalJS** | **Nashorn** |
|---|---|---|---|
| Language level | ES2015-ish; no `class`/modules/async on either pinned version | ES2023+, complete | ES5.1 (+ partial ES6), **frozen** |
| Java 8 host (1.7.10 on the vanilla launcher — a real band) | 1.7.15.1, full support | last Java-8 build was GraalVM 21.3 (2021, EOL) | built into JDK 8, but see the rest of the row |
| Java 11–16 / 17+ hosts | 1.9.1 | 22.3 for 11 (EOL), 24.x for 17+ — three Graal/Truffle generations across our bands | standalone `nashorn-core` 15.4 (2022, maintenance-only) |
| Size per band | **~1.4 MB, zero deps** | ~30–45 MB (`icu4j` alone ~13 MB) | ~2.5 MB + ASM |
| Performance without JVM flags | its own interpreter, no flags | Truffle *without* the Graal compiler is a plain AST interpreter, several× slower, and prints a warning; the compiler needs `-XX:+EnableJVMCI --upgrade-module-path …`, which a mod cannot set for players | JIT'd bytecode, fast |
| Loading child-first from a mod jar | plain jar, no services, no JPMS — M5 smoked it on all three bands | JPMS/`ServiceLoader`-based; class-path "fallback runtime" with warnings; friction with Forge's module layers | fine |
| Cooperative stop | `observeInstructionCount`; a Java `Error` is uncatchable by script `catch` | `Context.close(true)` + `ResourceLimits` — genuinely **better** | none without instrumenting its own generated classes |
| Sandbox | `ClassShutter` + `WrapFactory` — real, call-time | `HostAccess` + class-lookup predicate — **better** and finer | `ClassFilter` — comparable |
| Member-lookup remap (§16.1, non-negotiable in production) | one package-private class (`JavaMembers`); **KubeJS's fork is the existence proof** | no obvious single seam in Truffle host interop; nobody has done it in MC | Dynalink custom linker — possible, deep, dead upstream |
| A parser we can *ask* things of | IDE mode, public AST, symbol tables, JSDoc | **no public parser API** — syntax errors with a location, no tree | internal |
| Precedent in the exact use case | KubeJS, ProbeJS, CraftTweaker's JS era | none at scale in MC | 1.7.10-era mods, all abandoned with JDK 15 |

**Why Rhino, in one line:** size, the Java 8 band, no JVM flags, no module system, KubeJS having already
paid for the remapping fork, and a parser IDE features can be built on. GraalJS wins on the language and
on the sandbox and loses on everything about being shipped inside somebody else's JVM. The cost is the
one the M5 table already records: authors write JS without `class`/modules/`async` — which is what the
whole MC scripting ecosystem writes today, on this engine line.

**Does it have everything the use case needs?** Feature-wise, yes for what §16/§19 ask: LiveConnect
interop (overloads, varargs, bean properties, a JS function converting to a single-method Java
interface, `JavaAdapter`), the shutter, the observer, runtime introspection, an IDE-mode parser, and
even a debugger API for later. Performance-wise: Rhino interpreted is roughly 10–50× slower than V8 on
pure JS and compiled mode ~3–10× faster than interpreted, **but the ceiling for MC scripts is Java
interop through reflection** — ~hundreds of ns per Java call, the same in both modes — which is
comfortable for event handlers and tick scripts making hundreds to low thousands of Java calls per tick,
and not for a script touching a million blocks per tick. That script is a **Java** script; the
two-engine design *is* the performance answer, and JS does not need to be fast because Java is beside
it with the same bindings, console and Run button.

**ES6 on JVM 8, specifically:** it is a Rhino-jar limit, not a JVM one, and it is **frozen**. The 1.7.x
line is the last that runs on Java 8; 1.8+ needs Java 11. So band 8 gets whatever 1.7.15.1 implements
and will never get more; bands 11/17 (1.9.1) are closer to complete ES2015 and can move up.
**Measured** by `RhinoCapabilityProbeTest` (M10 §1) against the real jars — this table is generated from
`build/probe/rhino-<band>.properties` and six of its cells contradict what this document first guessed,
which is the probe earning its place:

| Construct | 1.7.15.1 (JVM 8) | 1.9.1 (JVM 11/17) |
|---|---|---|
| `let` / `const` | yes | yes |
| Arrow functions | yes | yes |
| Template literals, tagged | yes | yes |
| **Default parameters** (`f(a = 1)`) | **no** | yes |
| Rest parameters (`f(...a)`) | yes | yes |
| **Spread in a call** (`f(...a)`) | **no** | **no** |
| Spread in an array literal | no | yes |
| Destructuring, array + object | yes | yes |
| Destructuring with defaults | no | yes |
| `for…of` | yes | yes |
| Generators (`function*`) | yes | yes |
| Shorthand properties | yes | yes |
| Computed properties (`{[k]: v}`) | no | yes |
| Getters / setters | yes | yes |
| `**` | yes | yes |
| Trailing comma in a call | yes | yes |
| **`?.` / `??`** | no | **yes** |
| **`class`** | **no** | **no** |
| **`import` / `export`** | **no** | **no** |
| **`async` / `await`** | **no** | **no** |
| `Symbol`, `Map`, `Set`, `WeakMap`, `Promise`, typed arrays | yes | yes |
| `Proxy`, `Reflect` | **no** | yes |
| `RegExp`, regex literals | yes | yes — **but see below** |
| `Java` global | no (we install it — §6.4) | no (we install it) |

What the guesses got wrong, recorded because the pattern is instructive: rest parameters and generators
were expected to be shaky on band 8 and are fine; **default parameters, spread and computed properties**
were expected to be fine and are refused there; `**` was expected to be refused and is not; and `?.`/`??`
turn out to be **available on 1.9.1**, which makes them the most valuable thing an author gains by
running on Java 11+. Note also that *spread in a call* is refused by **both** Rhinos while spread in an
array literal is accepted by the newer one — the two are one feature in the spec and two in this engine.

> **Two behavioural findings from the same probe, both load-bearing.**
>
> **1. The engine loader must be the thread context classloader before Rhino is touched at all.** Rhino
> 1.8+ resolves its regular-expression engine through `ServiceLoader` on
> `org.mozilla.javascript.RegExpLoader`, and the no-argument `ServiceLoader.load` reads the *thread's*
> loader, not the caller's — which for a child-first engine loader is the host's, and cannot see the
> service file inside the band jar. **And the answer is cached at class initialisation**, so setting the
> loader late does nothing: the probe swapped it inside its own `enter()` and still got no regexes,
> because reading `Context.VERSION_ES6` one line earlier had already cached the negative answer. The
> symptom is not a load error — it is `"Regular expressions are not available."` thrown from the first
> regex a script evaluates, on bands 11 and 17 only, while band 8 works either way because its Rhino
> predates the lookup. `RhinoExecutor` installs the loader before its first Rhino call and restores it
> after, and §15 keeps this as a standing question for any future engine with a `ServiceLoader` seam
> (ECJ has one too).
>
> **2. Promises drain on their own, and a sealed scope still takes globals.** §15's two open behavioural
> questions, both answered "no work needed": a resolved promise's continuation has run by the time
> `evaluateString` returns, so `JsHost` needs no microtask pump; and `initStandardObjects(null, true)`
> still accepts `putProperty`, so `console`/`Java` may be installed after sealing rather than before.

Two consequences, both built here:Two consequences, both built here:

- **A script's syntax ceiling follows the *player's* band, not the author's.** Somebody authoring on Java
  17 can write what band 8 refuses and never see it, because the diagnostics come from the host's own
  Rhino. So the band-8 probe output ships as a resource and `RhinoProblemPolicy` takes a **compatibility
  band** setting: on a pack targeting a Java 8 host, a construct 1.7.15.1 refuses becomes a *warning*
  ("not supported on Java 8 hosts") even where the local Rhino accepts it. Same table, one more column.
- If ES2020+ ergonomics ever become a hard requirement *for Java 8 players*, no Rhino answers — and
  neither does GraalJS. That is a limit of the host, not of the engine choice.

**What would move us off Rhino:** authors needing modern syntax badly enough to carry ~40 MB per band on
Java 11+, or an MC host where JVMCI is enabled by default. The swap is contained by design — the shell
sees `ScriptRuntime`, the editor sees `Analysis`; a Graal engine replaces `RhinoExecutor` and
`RhinoSourceAnalyzer` (§3), and because Graal has no parser API its analyser would take static
structure from tree-sitter + `locals.scm` — §16.1's original wording, which is why the Rhino-AST choice
in §1.2 is recorded as a bet on Rhino confined to one class, not an architectural commitment.

**Interpreted by default, compiled as an opt-in** (revises §9.1's "decided"): a hot handler may ask for
`setOptimizationLevel(9)` per script; Rhino's codegen emits Java-6-era class files, so band 8 is fine,
and the observer works in compiled mode when `CompilerEnvirons.setGenerateObserverCount(true)` is set
(probe). Default stays interpreted for the reasons in §9.1.

## 14. Non-goals for M10

`Java.extend`/`JavaAdapter` completion; `@typedef`/`@callback` JSDoc shapes; TypeScript-style
inference across calls; ES modules; a debugger (Rhino has one, and it is a milestone of its own); Rhino
compiled mode; memory policing (§19.3 — trust model); player-submitted scripts (§22, permanently);
rename/find-usages/signature help for JS ahead of Java having them.

## 15. Verify before building on it (the probe's list — §23's discipline)

*Audited 2026-08-19 against the code, because a list of open questions nobody closes is a list that
stops being read. Each row below is now answered, struck as superseded, or explicitly still open —
and the audit found one that was neither: step 11 had never been done, and doing it is the whole
reason a band divergence keeps being found by a person rather than by the build.*

1. ~~`Context.getSourcePositionFromStack` visibility per band~~ — **answered: package-private on all
   three**, so the direct route is out. **And the fallback works**, which is the half that was left
   open: `RhinoExecutor.currentLine()` builds `new EvaluatorException("origin")` on the script thread
   and reads `getScriptStack()`. No same-package accessor, nothing shaded beside Rhino.
2. **Open.** `JavaMembers` refuses a `ClassShutter`-hidden class for an object *passed in* as a
   binding, not only for `Packages` lookups. `JsSandboxTest` covers the `Java.type` and call routes;
   the passed-in binding is the one shape nothing asks about.
3. **Half answered.** `aStopCannotBeCaughtByTheScript` pins that Rhino's interpreter refuses to let a
   script `catch` the stop — on the running band. That a `finally` still runs, and that both bands
   agree, is untested.
4. ~~`initStandardObjects(null, true)` (sealed) permits installing `Java`/`console` afterwards~~ —
   **answered by 9a**, which measured it directly: a sealed scope still takes globals, so install
   order is free. Duplicate row, kept struck rather than deleted so the question is not asked a third
   time.
5. **Half answered, half decided.** `setRecordingLocalJsDocComments` does attach JSDoc to `var`
   initialisers — the JSDoc tier reads them and `JsAnalysisTest` covers it. The parser-warning
   message-id list per band was **not** measured and no longer needs to be: §12a settled that Rhino's
   own warnings stay off, because they are strict-mode output and which style opinions to show is a
   policy decision nobody has made.
6. ~~`SymbolKind` has a kind for a plain JS object container~~ — **answered: no core edit needed.**
   `PROPERTY` and `MODULE` cover it; there is no `OBJECT` constant and nothing wanted one.
7. **Open, and moot for now.** `NativeFunction`'s arity/name accessors — nothing in the module names
   that type today, so the question has no consumer. It returns the moment one appears.
8. ~~Promises drain at the end of an evaluation~~ — **answered: they do**, on every band. No pump.
9. ~~`?.`, `??`, `**`, rest parameters, generators per band~~ — **answered**, and six of the guesses
   were wrong; see the measured table in §13a. The probe file is what the policy, the keyword set and
   the compatibility-band warning read — and since 10.3b, band 8's copy **ships as a resource** with a
   test asserting it still matches the jars it describes.
9a. ~~Whether a sealed scope still takes new globals~~ — **answered: it does**, so install order is free.
9b. **Open, and deliberately kept open.** A standing question for every engine, raised by the regexp
   finding: does any other adapter reach a `ServiceLoader` seam? ECJ has service files of its own, so
   the "engine loader must be the thread context classloader before first class-init" rule may already
   apply to the Java engine without anything having noticed. Nothing it does today needs one, and that
   is not a guarantee.
10. ~~`setGenerateObserverCount(true)` makes the observer fire in compiled mode~~ — **not applicable.**
   §9.1 chose interpreted mode (`setOptimizationLevel(-1)`) and the executor sets it unconditionally,
   so there is no compiled path for the observer to fire on. The question returns only if that
   decision does.
11. ~~Extend `EngineApiSurfaceTest` with the parser and interop surface~~ — **done, and it had not
   been.** The row said step 2 would extend it "so a band bump fails the build rather than the popup";
   the block still pinned only what M5 needed — seven types about evaluating a string — while
   everything the editor is built on arrived afterwards and was pinned by nothing.
   `everyBandCarriesTheRhinoApiTheEDITORUses` now covers the IDE-mode parse, the tree accessors
   (including `visit`, the one reading true on both bands), the symbol table, the twenty-six node
   types the fix catalog and the compatibility band locate by class, and the execution seam.
   **All four band divergences this module has hit were the kind this catches** — `ObjectProperty.getLeft`,
   the `Token` constants, `CatchClause.getVarName`, `NativeJavaObject`'s three-argument constructor —
   and every one of them was found by a person running it on the other band.
   *(One shape worth recording: `ContextFactory.observeInstructionCount` is `protected`, so a
   `getMethod` check reports it missing on every band. A member an adapter **overrides** rather than
   calls needs `getDeclaredMethod` — the question is whether it is there to override, not whether it
   is callable.)*
