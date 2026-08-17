# CLEANUP plan — the quick-fix layer

A review pass over every correction family in `language/src/main/java/com/crystalgui/language/java/`
after the catalogue closed (2026-08-17). Every defect below was **reproduced with a probe**, not read
off the code; every duplication row was counted with `grep`. Section 1 is what is wrong, section 2 is
what is written more than once, section 3 is the shape it should have, section 6 is the order to do it in.

Standing rules for the work: commit only these files; keep the `java` package tests and the corpus
(`CorpusTest`) green at every commit; the corpus's "no file gains an error" line is the oracle for
sections 1 and 3.

---

## 1. Defects (all reproduced) — **DONE**, in `a8df75e`/`a30a917` (D1–D5) and the commit after (D6–D10)

Kept as written, because the diagnosis is the part worth having; the "what happens" column is now what
*used to* happen. Two things were found while fixing them and are done too:

- **The arrow conversion had D2's defect from the other side.** A `break` left inside a group has no arrow
  form at all — a switch rule may not complete abruptly with one (JLS 14.11.2) — so `groupsOf` refuses it
  through the same `containsBareBreak` the chain conversion uses.
- **`Indent` landed early** (it is §3.1's, and D5 needed `reindent`). All four `indentAt` copies are gone
  with it, so that row of §2 is closed as well.

`FixFixture.assertSameSemantics` is in, as §4 asks, and is what D3 and D4 are pinned with.

| # | Where | What happens | Class |
|---|---|---|---|
| **D1** | `LoopIntentions.fetchesOf` | `for (int i…) { xs[i] = 0; }` → `for (int i1 : xs) { i1 = 0; }`. **Compiles and does nothing.** A fetch that is an assignment target or a `++`/`--` operand is accepted as "only used to fetch". Also: `Names.declaredIn(method)` still contains the index being removed, so the derived name always collides with itself (`i` → `i1`). | semantic, silent |
| **D2** | `SwitchIntentions.IfChainToSwitch` | A chain inside a loop with a branch ending in `break;` converts; the `break` now exits the **switch**, not the loop. `leaves()` treats `break` as "already leaves" and adds nothing. Compiles, different program. | semantic, silent |
| **D3** | `IfChainToSwitch.appendBody` | Two branches each declaring `int a` → the switch body is one scope → **duplicate variable error**. Block bodies are unwrapped unconditionally. | new error |
| **D4** | `IfChainToSwitch.testOf` | Subject of type `long`/`float`/`double`/`Object` → `switch (long)` **new error**. No check on the subject binding, and no duplicate-label check. | new error |
| **D5** | `SwitchIntentions.appendArrowBody`, `IfChainToSwitch.appendBody`, `LambdaCorrections.ToAnonymousClass.bodyOf` | Every carried body is re-indented with `line.trim()` per line, so a **nested block is flattened** (`if (x) {` and its contents at one column). Three copies of the same wrong re-indent. | output, ×3 |
| **D6** | `CreateCorrections.typeFor` | A parallel Type-node spelling of a binding with **no `isRecovered` guard** — the fault `TypeNames.writtenName` now guards. Dormant only because `UndefinedMethod` is not reported when an argument is unresolvable. | latent |
| **D7** | `IntentionCorrections.SplitDeclaration` | `"var".equals(statement.getType().toString())` — the DOM has `Type.isVar()`. | wrong API |
| **D8** | `VariableIntentions.InlineVariable.assignedAfter` | By **name**, over the whole method — a same-named local in a sibling block or lambda causes a false refusal — and it is fully subsumed by `mutatedThrough`, which is by binding and already sees `x = …`, `x++`, `++x` at zero hops. Two mechanisms, one question. | false refusal |
| **D9** | `ValueCorrections.InitialiseBlankFinalField.assignedAnywhereIn` | Same fault: by name over the whole type, so a local `total = 1` anywhere refuses "Initialize field 'total'". | false refusal |
| **D10** | `VariableIntentions` ~L496 | Orphaned javadoc: `/** The line's first character… */` immediately followed by another `/**`, so `startOfLine` is undocumented and `mutatedThrough` carries the wrong header. | hygiene |

Probe transcript (kept for the fixtures that will pin these):

```
enhanced-for over xs[i] = 0      → for (int i1 : xs) { i1 = 0; }            errors 0 → 0   (D1)
if chain + break inside while    → case 1: n++; break;                       errors 0 → 0   (D2)
if chain declaring `a` twice     → case 1: int a = 1; … case 2: int a = 2;   errors 0 → 1   (D3)
if chain on a long               → switch (n) on long                        errors 0 → 1   (D4)
lambda → anonymous, nested block → if (x) { / System.out.println(1); / }     flattened      (D5)
arrow switch, nested block       → same flattening                                          (D5)
```

---

## 2. Duplication — the same rule written in N places

Each row is a place two families will disagree the first time one is edited.

| Concept | Copies | Where |
|---|---|---|
| `indentAt(source, pos)` | **4** identical | `IntentionCorrections`, `LambdaCorrections`, `SwitchIntentions`, `VariableIntentions` |
| Enclosing type of a node | **5** | `DidYouMean.enclosingTypeOf`, `Create.enclosingTypeOf`, `Value.enclosingType`, `Cast.receiverOf`, `EcjSourceAnalyzer.enclosingTypeAt` — three different answers about anonymous classes |
| Enclosing method / "the callable that owns this" | **5**, four stopping rules | `Cast.enclosingMethod` (stops at lambda), `Exception.enclosingMethod` (lambda/initializer/anonymous), `Loop.enclosingMethodOf` (walks to root), `VariableIntentions` ×3 inline (`usesOf`, `assignedAfter`, `freshName`), `DidYouMean.collectLocalsAbove` (method/lambda/initializer) |
| Static context | 2 | `Value.inStaticContext`, `Create.isStaticCall` tail |
| `KEYWORDS` | **2 identical 60-entry sets** | `Names`, `CreateCorrections` |
| Fresh-name derivation | 4 | `Names.derive`, `Create.parameterName` (own primitive→letter table that disagrees: boolean→`b` vs `flag`), `Lambda.freeName`, `Exception.freeExceptionName` |
| Names declared in a scope | 3 | `Names.declaredIn`, `Exception.freeExceptionName`'s visitor, `Lambda.namesInScopeAt` |
| Spelling a type binding | **2 parallel systems** | `TypeNames.writtenName` (String) vs `Create.typeFor` (Type node) — differ on type variables (null vs erasure), anonymous (null vs `Object`), recovered (guarded vs not) |
| Zero / default value | 2 | `TypeNames.defaultValue`, `Create.zeroOf` |
| Expected type from context | 3 | `Cast.expectedTypeOf`, `Create.returnTypeFor`, ad-hoc reads in `Value` |
| Precedence — "binds looser than a cast / needs wrapping" | **2 identical lists** | `Cast.bindsLooserThanACast` ≡ `VariableIntentions.needsParentheses` |
| Precedence — "is atomic / needs no parens" | 2 **different** lists | `Negation.parenthesised` vs `Expression.needsNoParentheses` |
| `hasCall` (side effect) | 2, differ by `ArrayCreation` | `Cast`, `VariableIntentions` |
| Comparison-operator set | 2 | `Negation.opposite`, `Intention.negatable` |
| `leaves(Statement)` | 2 **in one file** | `SwitchIntentions` inner + outer |
| `textOf` | 1 + 1 stray + ~6 inline substrings | `Negation.textOf(ASTNode)`; `Intention.textOf(Statement)`; `source.substring(start, start+len)` in Loop, Variable, Lambda, DeadCode, Unused |
| changes + import insertions → `ChangeSet` | 4 hand-rolled | `Value`, `Loop`, `Variable`, `Lambda` — `FixContext.changesFrom(rewrite, imports)` covers only the rewriter path |
| `NONE` / `new int[0]` | 8 spellings | should be one constant on `Correction` |
| `isRecovered` recursive check | dead duplicate | `ImplementCorrections.isRecovered` — the rule moved into `TypeNames.writtenName` (its own comment says so); the copy and both calls stayed |
| Cast building | 2 | `CastToExpectedType.contribute` inlines the body of `castInPlace` |
| Cast-argument walk | 2 | `CastArgument.contribute` and `mismatchedArgumentSpan` are one loop written twice |
| Last field / last constructor-or-field | 2 | `Value.lastFieldOf`, `Create.lastConstructorOrFieldOf` |
| Declaration lookup by binding | 2 ways | `Value.InitialiseVariable.declarationOf` walks the unit; `Modifier` uses `unit.findDeclaringNode` |
| Unused-import set | 2 | `ImportCorrections.OrganizeImports` (`HashSet`) and `Unused.RemoveAllUnusedImports` (`List` + `contains`) |
| Inline FQNs | ~14 | `Negation.parenthesised` ×5, `CastCorrections` ×2 (`org.eclipse.jdt.core.dom.Type`), `ImportPlan` ×1, `JavaCodeActions` `implements` clause, `EcjSourceAnalyzer` ×~10 |

---

## 3. The rewrite

Not a reshuffle: a small set of shared classes each owning **one question**, and every family calling
them. Family files lose their private helpers and get shorter.

### 3.1 New shared classes

- **`Scopes`** — the tree-walk questions. `enclosingType(node)` (declared or anonymous, one rule);
  `enclosingMethod(node, StopAt…)` with the stop rule as an explicit parameter (`LAMBDA`,
  `INITIALIZER`, `ANONYMOUS`) so the four current variants are four calls of one method;
  `enclosingStatement`; `isStaticContext`; `namesInScopeAt(node)`; `declaredIn(scope)` (moved from
  `Names`). Kills ~12 private copies.
- **`Precedence`** — `needsParenthesesWhenWrapped(expr)` (the loose-binding list) and `isAtomic(expr)`
  (the tight list), one each. `Cast`, `VariableIntentions`, `Negation` and `Expression` all read them.
- **`SideEffects.has(expr)`** — one list: calls, `new`, array creation, assignment, `++`/`--`.
- **`Indent`** — `at(source, pos)`, `ofLine`, and the missing piece **`reindent(text, toIndent)`**,
  which shifts by the *minimum common leading whitespace* rather than trimming. Fixes D5 in all three
  places at once; every body-carrying intention uses only this.
- **`Expected.typeOf(expression)`** — initialiser, assignment RHS, return (stopping at lambda),
  argument (via sole candidate), condition → boolean, cast operand. `Cast`, `Create` and `Value` all
  read it; `Create.returnTypeFor` becomes `Expected.typeOf(call)` + `TypeNames`.

### 3.2 Existing classes that grow or shrink

- **`TypeNames`** gains `typeNode(binding, ast, imports, at)` — `writtenName` rendered as a `Type` via
  `createStringPlaceholder` — so `Create.typeFor` and `Create.zeroOf` die and the
  recovered/typevar/anonymous rules exist once (fixes D6).
- **`Names`** absorbs `Create.parameterName` (delete its `KEYWORDS`), `Lambda.freeName`,
  `Exception.freeExceptionName` (`derive("e", …)` with `ex` as a second stem).
- **`Correction.NONE`** — one constant; delete the 8 spellings.
- **`FixContext`** gains `changeSet(List<Change>, ImportPlan)` (merge + sort + `ChangeSet.of`) and
  `text(node)`. `Negation` goes back to owning only negation.
- **`CastCorrections`** — `contribute` calls `castInPlace`; extract `mismatchedArguments(call)`
  returning `(argument, wantedType)` pairs so `CastArgument` and `mismatchedArgumentSpan` iterate one
  list.
- **`ImplementCorrections`** — drop `isRecovered` and its calls; `stubFor` shrinks.
- **`ImportCorrections` / `UnusedCorrections`** — one `unusedImportsIn(context)`.
- **`ValueCorrections`** — `declarationOf` → `unit.findDeclaringNode(binding)`;
  `assignedAnywhereIn` by binding (D9).
- **`VariableIntentions`** — delete `assignedAfter`; `inlinable` asks `mutatedThrough` over `usesOf`
  (D8); fix the orphaned javadoc (D10).
- **`IntentionCorrections`** — `Type.isVar()` (D7); drop private `textOf`, `indentAt`, `NONE`;
  `negatable` asks `Negation.isComparison`.
- **`SwitchIntentions`** — one `leaves`; guards for D2–D4: refuse a body containing an unlabeled
  `break` not inside a nested loop/switch; keep braces on a `Block` that declares a variable
  (IntelliJ's rule); require the subject binding to be `int`/`short`/`byte`/`char`, their boxes, or
  `String`; refuse duplicate labels.
- **`LoopIntentions`** — refuse a fetch whose parent is an assignment LHS or `++`/`--` operand (D1);
  remove the loop's own index from the taken-name set.
- **`DeadCodeCorrections`** — `id()` returns `SIMPLIFY_CONDITIONAL` but emits `REMOVE_BRANCH` half the
  time, breaking `Correction`'s "one correction one id" rule. Two corrections, or one id.
- **`Rewrites.formattingOptions` / `CodeActionContext`** — both still promise host-side indent
  detection ("§14-A") that never landed. Detect from the document (min indent of the first indented
  line) or delete the promise.
- **`EcjSourceAnalyzer.inspections`** hard-codes the lambda report. Add an **`Inspection` SPI** beside
  `Correction` — `reportIn(unit, source) → List<Diagnostic>` — and loop a list the way `JavaQuickFixes`
  does, *now*, while there is one, because the second author will copy the block.
- Import every inline FQN listed in §2.

### 3.3 Small per-file items

- `AddBraces`/`RemoveBraces`, `Loop.triggered`/`contribute`, `Switch.groupsOf` each compute their
  shape inside the `at` predicate and again afterwards. A `FixContext.at` overload that returns the
  predicate's payload removes the double walk.
- `LambdaCorrections.ToAnonymousClass` description ("Writes the lambda out as the interface it
  implements.") is the one description without a subject; consistency nit.

---

## 4. Test infrastructure

- `FixFixture` lacks **`assertSameSemantics(before, needle, id)`** — apply, re-analyse, assert the
  error count did not rise. D3 and D4 would have failed such a fixture. Add it and use it in every
  intention family.
- `CorpusTest` **prints** semantic regressions. Promote "no file gains an error" to an assertion for
  the intention families (they are the ones that generate code); keep it a print for the fix families
  until the count is zero.
- The corpus's 46 regressions have never been triaged by correction id. One run that histograms them
  is the cheapest next probe: D1–D4 are all shapes it would flag.

---

## 5. What is deliberately NOT in this plan

`SUPPRESS` (`@SuppressWarnings`), the override gutter marker (§18.6), the switched-off optional
problems, Alt+Insert generation, every T5/cross-file entry — parked, not started without confirmation.

---

## 6. Order — four commits, each green on the java package + corpus

*(Defects done. `Indent` is in and step 1 is what remains of it.)*

1. **Shared classes** — `Scopes`, `Precedence`, `SideEffects`, ~~`Indent` (with `reindent`)~~,
   `Correction.NONE`, `FixContext.changeSet(changes, imports)` and `text(node)`, `Names` absorbing its
   three copies. Pure moves; the existing tests are the net. Fixes D5, D10.
2. **`TypeNames.typeNode` + `Expected`** — retire `Create.typeFor`/`zeroOf`/`returnTypeFor`,
   `Cast.expectedTypeOf`; `Cast` shares `castInPlace` and `mismatchedArguments`; drop
   `Implement.isRecovered`. Fixes D6.
3. **Switch + Loop guards** — D1–D4 with a fixture each; by-binding rewrites of `assignedAfter` and
   `assignedAnywhereIn` (D8, D9); `isVar()` (D7). Add `assertSameSemantics` in the same commit and use
   it here.
4. **`Inspection` SPI + stale docs** — `Inspection` beside `Correction`; `DeadCode` id split; the
   indent-detection promise resolved one way or the other; FQNs imported.

The two urgent items are **D1 and D2**: both are the class of failure this layer treats as worst
(compiles, means something else), both are one-line guards, and both sit in *preferred* intentions —
the row the popup shows first.
