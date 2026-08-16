# The quick-fix catalogue — every correction worth having, ranked by what it costs us

Companion to `plan_quickfix.md`, which decided the **mechanism**. This decides the **content**: what
corrections exist in the two reference implementations, which of them ECJ can actually key, and what
each one costs to build *here*. It is a work queue, not a survey.

Written against `org.eclipse.jdt.core` **3.26.0** — band 8's floor, the version the adapter compiles
against, and therefore the only version whose constants may be named. Every `IProblem` constant below
was read out of that jar with `javap`, not recalled. It has 966 of them.

---

## 1. The two references, and what may be taken from each

| Source | Licence | What we may take |
|---|---|---|
| **IntelliJ IDEA Community** | Apache 2.0 | **The code.** Same terms the file-type icons already ship under — § 4 notice obligations, recorded in `THIRD-PARTY.md`. |
| **Eclipse JDT / JDT-LS** | **EPL-2.0** | **The mapping only.** EPL is file-level copyleft: porting `QuickFixProcessor.java` would put that file under EPL. |
| **VS Code's Java support** | — | Not a third thing. It *is* JDT-LS in a server; its quick fixes are `org.eclipse.jdt.ls.core.internal.corrections`. |

This distinction is load-bearing and cuts the opposite way from intuition. JDT-LS is the closest
reference by far — it is a switch on the same `IProblem` ids we already have in `Diagnostic.code`, so
its structure is the structure we want — and it is the one we may not copy. IntelliJ is a worse
structural match (it keys on javac's diagnostics and on its own inspections) and is the one we may
port from.

What survives the split is the part that matters: **which problem id deserves which correction is a
fact about ECJ, not an expression of it.** Reading JDT-LS to learn that `UnhandledException` deserves
both "surround with try/catch" and "add throws" is reading documentation. Writing our own
`addUnhandledException` is then ordinary work. Nothing in this file is a transcription, and nothing
below should become one.

> The third reference nobody names is **ECJ itself**. `IProblem`'s constant names are unusually
> descriptive — `OuterLocalMustBeEffectivelyFinal` states its own fix — and `getArguments()` already
> hands over the pieces the message was built from, which is where `nameOf(problem)` gets the
> identifier for every title in the current table.

---

## 2. Why this is keyed on `IProblem` and not on IntelliJ's menu

IntelliJ's Alt+Enter list is a **union of three unrelated things**, and conflating them is how a
catalogue like this turns into a thousand-row wishlist:

1. **Compiler-error fixes** — keyed on a javac diagnostic. These port, because ECJ reports the same
   language errors under its own ids.
2. **Inspection fixes** — keyed on one of ~1000 IntelliJ inspections that we do not have and are not
   going to write. `UnusedDeclaration` has an ECJ counterpart; `SimplifiableIfStatement` does not.
3. **Intentions** — keyed on *the caret's syntax*, with no problem at all. Flip an `if`, convert to a
   lambda, split a declaration. A different contributor kind entirely; see §7.

Only (1) is a catalogue with edges. (2) is bounded by what ECJ's optional-problem set happens to
contain, which is a much smaller and already-enumerated list. (3) is unbounded and is ranked
separately because its difficulty axis is different — an intention is never hard to *detect* and is
often hard to *apply*, which is the reverse of a fix.

---

## 3. The difficulty scale

The carrier is never the problem. `Change` is `(from, to, insert)` — a general replacement — so every
correction below is *representable* today. Difficulty is entirely in **knowing what text to write and
where**, and it climbs through six axes:

| Axis | What it adds | Where it bites |
|---|---|---|
| **A. Node offsets** | Nothing — `ASTNode.getStartPosition()` and the existing `deletion()` helper | — |
| **B. Separators** | A comma to absorb, or a list that must not be left with a trailing one | `throws A, B`, `implements A, B`, `int a, b;` |
| **C. Generated text** | Indentation matching the surrounding line | Anything inserted on a line of its own |
| **D. A type name** | A name written *into the file* — which may need an import, and must be spelled as the file will read it | `(List<String>) x`, `catch (IOException e)` |
| **E. Bindings** | Resolved types, not syntax — the receiver's members, the supertype's abstract methods | "Create method from usage" |
| **F. A second file** | A `ChangeSet` that is not one document's | "Create class 'Foo'", widening a member in a library |

**A–C are hand-rollable and already proven** — the current four entries are A (deletion) and C
(import insertion). **D onwards is where `ASTRewrite` starts paying for itself.** **F is blocked**:
`ChangeSet` is single-document by construction, so a cross-file action has no carrier at all today and
would need either a multi-document edit type or the `commandId` escape hatch.

Tiers below are named **T1…T5** and correspond to the highest axis a fix touches.

---

## 4. The structural fork: hand-rolled edits vs. `ASTRewrite`

The single decision that shapes everything past T2, and it is worth taking deliberately rather than
discovering halfway through a fix.

**What we found.** `org.eclipse.jdt.core.dom.rewrite.ASTRewrite` **is** in the jar we already use —
it is not a separate artifact. But its two exits are:

```java
public org.eclipse.text.edits.TextEdit rewriteAST(org.eclipse.jface.text.IDocument, java.util.Map);
public org.eclipse.text.edits.TextEdit rewriteAST() throws JavaModelException;   // needs an ICompilationUnit
```

Both name types from `org.eclipse.text`, and the adapter deliberately compiles against **jdt.core
alone** — `compileOnly("org.eclipse.jdt:org.eclipse.jdt.core:$jdtBand8")`, with the comment recording
that pulling the closure in "would let it reach APIs that happen to resolve here and are absent from a
real deployment's loader."

**But that reasoning does not apply to this jar.** `org.eclipse.platform:org.eclipse.text:3.11.0` is
already pinned in `platformBand8`, `platformBand11` and band 17's closure — it ships in every band, and
it is where `IDocument`, `Document` and `TextEdit` live (verified by listing the jar). So the cost of
reaching `ASTRewrite` is **one `compileOnly` line**, not a new dependency, not a band re-pin, and not a
risk to the band guarantee — the artifact is on the runtime side already.

| | Hand-rolled `Change` | `ASTRewrite` |
|---|---|---|
| Comma/separator handling | Ours, per fix | Free (`ListRewrite`) |
| Indentation of generated code | Ours, per fix | Free |
| Imports for a generated type | Ours | `ImportRewrite` (separate class, same jar) |
| Formatting fidelity | Whatever we write | JDT's formatter settings |
| Cost to reach | Zero | One `compileOnly`, plus `TextEdit` → `ChangeSet` conversion |
| Cost per fix at T1–T2 | **Low** | Higher — a rewrite is more ceremony than a line deletion |
| Cost per fix at T3+ | **Climbs badly** | Flat |

**The recommendation is: stay hand-rolled through T2, and adopt `ASTRewrite` as the entry ticket to
T3.** Not because T3 is impossible by hand — "add `@Override`" is genuinely a one-line insertion — but
because the *fourth* hand-rolled indentation helper is the point at which the file has quietly grown a
worse copy of `ASTRewrite`, and this codebase has a documented habit of that failure (two copies of the
stroke-cap logic; two spellings of `icon()`).

The conversion seam is small and worth naming now: `TextEdit` is a tree of `ReplaceEdit`/`InsertEdit`
with offsets into the *original* document, which is exactly `Change`'s contract, so
`TextEdit → List<Change>` is a flatten-and-sort. `ChangeSet.of` requires sorted, non-overlapping
changes and refuses otherwise, which is the right check to inherit.

---

## 5. T1 — node deletion and whole-line insertion *(axes A–B)*

The machinery for these exists and is tested. Each is **one `IProblem` case plus one test**, and the
test asserts on the resulting text, never on the title.

### Already done

| Fix | `IProblem` | Notes |
|---|---|---|
| Remove unused import | `UnusedImport` | + the batch variant when the file has ≥2 |
| Remove variable 'x' | `LocalVariableIsNeverUsed` | Refuses multi-fragment declarations |
| Remove field 'x' | `UnusedPrivateField` | Same refusal |
| Import 'java.util.List' | `UndefinedType`, `ImportNotFound` | One action per candidate; none preferred |

### The obvious next batch — pure `deletion()`, no new helper

| Fix | `IProblem` | Notes |
|---|---|---|
| Remove unused private method | `UnusedPrivateMethod` | `MethodDeclaration`; identical shape to the field case |
| Remove unused private constructor | `UnusedPrivateConstructor` | Same |
| Remove unused private type | `UnusedPrivateType` | `TypeDeclaration` — a nested one; the top-level type is never reported |
| Remove superfluous semicolon | `SuperfluousSemicolon` | Delete the problem's own range; no node lookup at all |
| Remove dead code | `DeadCode` | Statement or block. **Check the reported range** — ECJ points at the unreachable statement, but a dead `else` wants the `else` too |
| Remove unused assignment | `AssignmentHasNoEffect` | Delete the statement. Refuse when the RHS has a call in it — deleting `x = f()` drops the side effect |
| Remove unused allocation | `UnusedObjectAllocation` | `new Foo();` as a statement |
| Remove unnecessary NLS tag | `UnnecessaryNLSTag` | Trailing `//$NON-NLS-1$` comment |

### Needs separator handling first *(axis B)*

One shared helper — "delete this element and the comma that binds it" — unlocks all four. Write it
once; four hand-rolled versions is the smell §4 is about.

| Fix | `IProblem` | List |
|---|---|---|
| Remove unused thrown exception | `UnusedMethodDeclaredThrownException`, `UnusedConstructorDeclaredThrownException` | `throws A, B` |
| Remove redundant superinterface | `RedundantSuperinterface` | `implements A, B` |
| Remove unused type parameter | `UnusedTypeParameter` | `<T, U>` |
| Remove unused exception parameter | `ExceptionParameterIsNeverUsed` | Not a list — but it may not simply be deleted; a `catch` needs *some* parameter. Rename to `ignored` instead, which is what IntelliJ does |

> **`ArgumentIsNeverUsed` is in the constant set and must not get a "remove" fix.** Deleting a
> parameter changes the method's signature and breaks every caller — a cross-file consequence from a
> single-file edit. IntelliJ offers it only as a *refactoring*, behind a usage search. Left out
> deliberately, and worth a comment at the site so it is not "completed" later by someone tidying.

---

## 6. T2 — token replacement inside a line *(axes A–C)*

Still hand-rollable. The generated text is short and its indentation is either irrelevant (inline) or
copyable from the declaration's own line.

### Modifier edits — insert or remove one keyword

| Fix | `IProblem` | Edit |
|---|---|---|
| Make 'x' final | `OuterLocalMustBeFinal`, `OuterLocalMustBeEffectivelyFinal` | Insert `final ` at the declaration |
| Make field final | `FieldMustBeFinal` | Same |
| Remove 'final' modifier | `FinalFieldAssignment`, `NonBlankFinalLocalAssignment`, `FinalOuterLocalAssignment` | Delete the `final` token + its trailing space |
| Make method static | `MethodCanBeStatic`, `MethodCanBePotentiallyStatic` | Insert `static ` before the return type |
| Make class abstract | `AbstractMethodsInConcreteClass`, `AbstractMethodInAbstractClass` | Insert `abstract ` |
| Remove 'abstract' modifier | `BodyForAbstractMethod` | Delete the token |
| Change visibility | `NotVisibleType`, `NotVisibleField`, `NotVisibleMethod`, `NotVisibleConstructor` | **Same file only.** The declaration is usually in a jar — see T5 |

### Annotations — insertion on a line of its own *(axis C, the cheap end)*

One shared helper: *"insert this text on its own line above the declaration, indented to match it."*
Reading the indent is a scan back to the previous `\n`. This helper is the whole of the tier.

| Fix | `IProblem` | Inserts |
|---|---|---|
| Add missing '@Override' | `MissingOverrideAnnotation`, `MissingOverrideAnnotationForInterfaceMethodImplementation` | `@Override` |
| Remove '@Override' | `MethodMustOverride`, `MethodMustOverrideOrImplement` | Delete the annotation node |
| Add missing '@Deprecated' | `FieldMissingDeprecatedAnnotation`, `MethodMissingDeprecatedAnnotation`, `TypeMissingDeprecatedAnnotation` | `@Deprecated` |
| Add '@SafeVarargs' | `SafeVarargsOnFixedArityMethod`, `SafeVarargsOnNonFinalInstanceMethod` | `@SafeVarargs` — but read the constant names: both fire when it is **wrongly** applied, so the fix is removal |
| **Suppress with '@SuppressWarnings'** | *(any optional problem)* | `@SuppressWarnings("...")` — `CodeActionKind.SUPPRESS` |

> **The suppress row is why this helper is worth writing before anything above it needs it.**
> `SUPPRESS` is parked by decision, not by cost: it is *the same insertion* as `@Override`, keyed on
> the whole optional-problem set rather than on one id. Once the helper exists, suppress is a
> contributor of a dozen lines. That also settles where it belongs — it is not a T3 feature waiting on
> infrastructure, it is a T2 feature waiting on a product call.

### Expression replacement

| Fix | `IProblem` | Edit |
|---|---|---|
| Remove unnecessary cast | `UnnecessaryCast`, `UnnecessaryArgumentCast` | Replace the `CastExpression` range with its operand's source text. **Keep the parentheses when the operand is not atomic** — `((A) b).c()` unwrapped naively becomes `b.c()`, which is right, but `(A) b + c` as a whole expression is not |
| Replace 'instanceof' with its constant value | `UnnecessaryInstanceof` | Replace with `true` — ECJ only reports the always-true case |
| Remove redundant null check | the `RedundantNullCheckOn*` family (≈14 constants) | **Do the deletion, not the simplification.** Replacing the condition with `true` is honest and ugly; removing the enclosing `if` is what a user wants and is T3 |
| Qualify static access with the declaring type | `NonStaticAccessToStaticMethod`, `NonStaticAccessToStaticField`, `IndirectAccessToStaticField`, `IndirectAccessToStaticMethod`, `IndirectAccessToStaticType`, `StaticMethodShouldBeAccessedStatically` | Replace the receiver with the declaring type's simple name. **Axis D in disguise** — that name may need an import |

### "Did you mean" — nearly free, because the index already exists

The highest-value rows in this whole document per line of code, because `TypeIndex` was built for
Import X and is sitting right there.

| Fix | `IProblem` | Source of candidates |
|---|---|---|
| Change to 'List' | `UndefinedType` | `TypeIndex.matching(prefix)` — already written, already prefix/scattered-aware. Currently package-private and returns a `Match`; needs no more than exposure |
| Change to 'toString()' | `UndefinedMethod` | The receiver's members. `JavaCompletionProvider.membersFrom(...)` already collects exactly this and is `private` — extract it rather than write a second walker |
| Change to 'length' | `UndefinedField`, `UndefinedName` | Same |

> **Rank these by edit distance, and cap them.** An unresolved `Lst` should offer `List`; it should not
> offer forty types whose names contain those letters scattered. The completion matcher's subsequence
> tier is opt-in per consumer for exactly this reason — a fix list is the consumer that must refuse it.

---

## 7. T3 — generated statements *(axes C–D)*

Where a type name gets written into the file and may need an import. **The recommended `ASTRewrite`
boundary.** Each of these is genuinely doable by hand and each hand-rolled one makes the next worse.

| Fix | `IProblem` | Why it is T3 |
|---|---|---|
| Surround with try/catch | `UnhandledException`, `UnhandledExceptionInDefaultConstructor`, `UnhandledExceptionInImplicitConstructorCall`, `UnhandledExceptionOnAutoClose` | Re-indents the wrapped body; names the exception type; may import it |
| Add 'throws IOException' | *(same set)* | List insertion + possible import. Pairs with the above — IntelliJ offers both and so should we |
| Add cast to 'T' | `TypeMismatch`, `IllegalCast`, `ConstructionTypeMismatch`, `ParameterizedMethodArgumentTypeMismatch` | The target type comes from a binding and must be spelled importably |
| Change type of 'x' to 'T' | `TypeMismatch`, `VarLocalInitializedToVoid` | Replace the type token — same naming problem |
| Change return type to 'T' | `ReturnTypeMismatch`, `VoidMethodReturnsValue`, `MethodReturnsVoid` | Same |
| Add return statement | `ShouldReturnValue`, `ShouldReturnValueHintMissingDefault` | Needs a default value *for the return type* — `0`/`false`/`null` |
| Initialise variable | `UninitializedLocalVariable`, `UninitializedBlankFinalField`, `UninitializedNonNullField` | Same default-value problem |
| Add missing 'default' case | `MissingDefaultCase`, `MissingEnumDefaultCase`, `SwitchExpressionMissingDefaultCase`, `SwitchExpressionsYieldMissingDefaultCase` | Indented insertion at the end of a block |
| Add missing enum constants | `MissingEnumConstantCase`, `MissingEnumConstantCaseDespiteDefault`, `SwitchExpressionMissingEnumConstantCase` | Enumerate the enum's constants from its binding — axis E, strictly |
| Add serialVersionUID | `MissingSerialVersion` | `1L` is T3. The **computed** value is the JDK serialization hash and is T5 — a real algorithm over the class's members. Offer `1L`; do not pretend to the other |
| Remove redundant `if` | the `RedundantNullCheckOn*` family | Unwrapping a block re-indents its contents — the same helper as try/catch, from the other direction |
| Remove unnecessary 'else' | `UnnecessaryElse` | Unwraps the else body up one level |
| Remove 'final' from an unreachable finally | `FinallyMustCompleteNormally` | Structural; low value, listed for completeness |

---

## 8. T4 — structural generation *(axis E)*

Needs resolved bindings and real code generation. `ASTRewrite` + `ImportRewrite` is not optional here.
This tier is where IntelliJ earns its reputation, and it is also where a half-built version is worse
than nothing — a generated method with the wrong parameter types is a new error where there was one
before.

| Fix | `IProblem` | What it needs |
|---|---|---|
| **Create method 'foo(int, String)'** | `UndefinedMethod` | Parameter types inferred from the *argument expressions'* bindings; return type from the invocation's context. The highest-value single fix in this document |
| Create field 'x' | `UndefinedField`, `UndefinedName` | Type from the assignment's RHS or the use site |
| Create local variable 'x' | `UndefinedName` | Same, plus placement at the right scope |
| Create parameter 'x' | `UndefinedName` | Cross-caller consequence — same objection as removing one. Refuse, or offer only with a usage search |
| Create constructor | `UndefinedConstructor`, `UndefinedConstructorInDefaultConstructor`, `UndefinedConstructorInImplicitConstructorCall` | Parameter list from the call site |
| Implement unimplemented methods | `AbstractMethodMustBeImplemented`, `AbstractMethodMustBeImplementedOverConcreteMethod`, `EnumConstantMustImplementAbstractMethod`, `EnumAbstractMethodMustBeImplemented` | Walk the supertype's abstract methods; generate stubs with `@Override`; import every parameter and return type |
| Implement `hashCode()` | `ShouldImplementHashcode` | Fires when `equals` is overridden and `hashCode` is not. Generation from the field set |
| Add type arguments | `RawTypeReference`, `UnsafeRawMethodInvocation`, `UnsafeRawConstructorInvocation`, `UnsafeRawFieldAssignment` | Infer the arguments — often impossible without the user; IntelliJ offers only the `<>`-safe subset |
| Use diamond `<>` | `RedundantSpecificationOfTypeArguments` | The easy inverse; genuinely T2 (delete the type-argument range) and listed here only to sit beside its opposite |

> **`RedundantSpecificationOfTypeArguments` is a free T2 hiding in a T4 section**, and that is worth
> noticing: the *removal* direction of a generics fix is almost always a range deletion, and the
> *addition* direction is almost always inference. When both appear in one row of a reference's
> catalogue they read as one feature and are two.

---

## 9. T5 — blocked, and honestly so *(axis F)*

Not "hard". **Not representable.** `CodeAction.edit` is one `ChangeSet` and `ChangeSet` is one
document. Everything here needs either a multi-document edit type or a `commandId` that opens a
dialog and does the work outside the action model.

| Fix | `IProblem` | Blocker |
|---|---|---|
| Create class / interface / enum 'Foo' | `UndefinedType` (the branch Import X does not cover) | A new file |
| Rename file to match the public class | `PublicClassMustMatchFileName` | A file rename, and it is the *file* that is wrong, not the text |
| Change visibility of a member elsewhere | `NotVisibleType`, `NotVisibleField`, `NotVisibleMethod`, `NotVisibleConstructor` | The declaration is in another compilation unit — or in a jar, where there is nothing to edit at all |
| Move class to its own file | `DuplicateTypes` | New file |
| Add a dependency | `ImportNotFound` with no candidate in the index | Edits the build, not the source |
| Generated `serialVersionUID` | `MissingSerialVersion` | Needs the JDK's serialization hash algorithm |

> **The `NotVisible*` family is the one to watch**, because it *looks* like a T2 modifier edit and is
> the same-file case only sometimes. Implement the same-file branch; return nothing for the rest.
> Silently editing the wrong file is the failure this whole version-gated design exists to prevent.

---

## 10. Intentions — the other half of Alt+Enter *(a different axis entirely)*

No diagnostic, no `IProblem`, no key. These are contributed on **the caret's syntax** and are the
third contributor kind `plan_quickfix.md` §1 already provided for. They are ranked separately because
their difficulty inverts: detection is trivial, application is where the work is.

| Intention | Trigger | Tier by our scale |
|---|---|---|
| Split declaration and assignment | `VariableDeclarationStatement` with an initialiser | T2 |
| Join declaration and assignment | The inverse | T2 |
| Flip `if`/`else` | `IfStatement` with an else | T3 — re-indents both branches |
| Invert boolean condition | Any condition | T2–T3 (negation needs parenthesising) |
| Replace `if` chain with `switch` | `IfStatement` chain on one variable | T4 |
| Convert to enhanced `for` | Indexed `for` over a list/array | T4 |
| Convert to lambda / method reference | Anonymous class with one method | T4 |
| Convert to anonymous class | The inverse | T4 |
| Introduce variable / constant / field | Any expression | T3 + naming |
| Inline variable | A local with one initialiser | T3 + usage search *within the file* |
| Add/remove braces | `IfStatement`/loop with a bare statement | T2 |
| Generate getters / setters / `toString` / `equals`+`hashCode` / constructor | A `TypeDeclaration` | T4 — and better as **commands** on a menu than as caret intentions, which is where IntelliJ puts them too (Alt+Insert, not Alt+Enter) |
| Organise imports | Any file | T1 — **and mostly written already**: the batch "Remove unused imports" is two thirds of it. Sorting is the rest |

> **"Organise imports" is closer than it looks and should be finished before anything in T4.** It is
> `CodeActionKind.SOURCE`, it composes from the existing unused-import batch plus a sort, and it is
> the single action a Java user reaches for most often after "import this".

---

## 11. Deliberately not in the catalogue

Recording these so they are not "found missing" later.

- **The ~500 syntax-error ids** (`ParsingErrorInsertTokenAfter`, `ParsingErrorMergeTokens`, …). ECJ
  already emits a suggested repair in the message, but a file that does not parse has **no usable
  tree**, and every fix above needs one. Worse, `optionalProblemsAnalysed` records that a syntax error
  suppresses the entire optional-problem pass — so a broken file has *fewer* diagnostics, not more.
  Syntax repair is a separate feature with a separate mechanism.
- **Javadoc fixes** (`JavadocMissingReturnTag`, `JavadocParameterMismatch`, and ~40 more). Cheap and
  low value while nothing in this project enables Javadoc diagnostics.
- **Null-analysis fixes** (`NullLocalVariableReference`, `PotentialNullUnboxing`, and the `@NonNull`
  family). These require ECJ's null analysis to be switched on in `EcjOptions`, which is a product
  decision that comes first — the fixes are meaningless until the diagnostics exist.
- **Module fixes** (`UndefinedModule`, `UndefinedModuleAddReads`). Band 8 has no modules and the
  scripts this engine compiles are not modular.
- **`MissingSerialVersion`** — and the diagnostic itself is now switched off, which is the only thing
  this engine turns *down* from ECJ's default. The message ("does not declare a static final
  serialVersionUID field of type long") is about the binary compatibility of **serialized instances
  across builds**: with no explicit stamp the compiler derives one from the class's exact shape, so
  adding a field later breaks streams written by the previous build. Real for code that serializes, and
  never for a script. It cannot be avoided by not asking for it either — `Throwable` implements
  `Serializable`, so *every* custom exception is flagged — and its only achievable remedy is a magic
  constant nobody reads. A diagnostic whose fix is "write this line you do not understand" teaches
  nothing and costs a line on every exception class. Adding the `= 1L` correction instead was the other
  option and is a dozen lines if the warning is ever wanted back.
- **`ArgumentIsNeverUsed` → remove parameter**, and **create-parameter**, per the notes above: both
  are single-file edits with cross-file consequences.

---

## 12. Recommended order

Ranked by **value per unit of work**, which is not the same as ranked by tier.

0. **Settle which diagnostics are even reported** (§13). Cheap, and it re-ranks everything below it:
   a row whose problem defaults to `ignore` costs one line in `compilerOptions` *plus* the fix, and a
   row whose problem is an error costs only the fix. Doing this first stops the batch in step 1 from
   being half dead on arrival.
1. **The T1 deletion batch** — unused private method / constructor / type, superfluous semicolon,
   unused allocation. Five entries, no new machinery, one test each. This is what takes the feature
   from "four fixes" to "it handles the things I actually leave lying around".
2. **The annotation-insertion helper**, and `@Override` / `@Deprecated` on top of it. One helper,
   three fixes — and it is the thing `SUPPRESS` is waiting on, so it converts a parked feature into a
   product decision.
3. **"Did you mean"** for `UndefinedType` / `UndefinedMethod` / `UndefinedField`. Two extractions
   (`TypeIndex.matching`, `membersFrom`) and no new analysis. Very high value for a typo, which is
   the most common error there is.
4. **Remove unnecessary cast**, and the separator helper with its four list fixes.
5. **Organise imports**, finishing what the unused-import batch started.
6. **Decide the `ASTRewrite` question** — one `compileOnly` line, a `TextEdit → List<Change>`
   flatten, and a test. Do this *before* the first T3 fix, not during it.
7. **Try/catch and `throws`**, as the first pair of T3s and the proof that the seam works.
8. **Create method from usage**, as the first T4 and the one that justifies the tier.

Steps 1–5 need nothing that does not exist. Step 6 is the only architectural commitment in the list,
and it is a small one whose main cost is deciding it deliberately rather than by accident.

---

## 13. Verification notes

- Every `IProblem` constant named above was read from `org.eclipse.jdt.core-3.26.0.jar` via
  `javap org.eclipse.jdt.core.compiler.IProblem` and grepped from that output. Band 8 is the floor, so
  a constant present there is present in all three bands.
- `ASTRewrite`'s signatures were read the same way. `IDocument`, `Document` and `TextEdit` were
  confirmed present in `org.eclipse.text-3.11.0.jar` by listing it.
- **A fix can only exist for a problem the compiler is configured to report**, and we configure
  almost nothing. `EcjSourceAnalyzer.compilerOptions` sets **exactly one** severity —
  `org.eclipse.jdt.core.compiler.problem.deprecation = warning` — on top of `EcjOptions.forLevel`,
  which sets source/compliance/target and no `problem.*` at all. Everything else runs on JDT's
  defaults, and **a large part of the optional set defaults to `ignore`**.

  This is the trap that makes a catalogue row look done when it is dead. The fix compiles, the test
  passes if the test constructs the problem itself, and the popup never offers it — because ECJ was
  never asked to report the diagnostic. It is invisible from the fix's own code, which is the whole
  reason it is written down here.

  So **every row outside the always-on group carries a hidden prerequisite: turn the diagnostic on.**
  That is a product decision before it is an implementation one — "method can be static" is a real
  opinion to impose on someone's file — and it belongs in `compilerOptions` beside the `deprecation`
  line, with the same kind of comment saying why.

  Known-on (they back the four shipped fixes, and their tests pass end to end through the real
  engine): `UnusedImport`, `LocalVariableIsNeverUsed`, `UnusedPrivateField`, and the resolution errors
  (`UndefinedType`, `ImportNotFound`), which are errors and cannot be switched off.

  **Check each remaining row against a live analysis before building its fix.** The cheap check is a
  three-line fixture through `JavaQuickFixTest`'s existing harness asserting the diagnostic appears at
  all — if it does not, the work is one line in `compilerOptions`, not a day in `JavaQuickFixes`.

---

## 14. Scaling the infrastructure — what a hundred fixes need that four did not

The verdict first, because it is the thing most likely to be got wrong by someone reading the size of
this catalogue: **there is no rewrite to make.** The contract — `CodeAction` as data, `CodeActionProvider`
answering only for itself, the merge in `TextEditor.requestCodeActions`, the version gate in
`applyCodeAction`, the undo brackets — was designed for N contributors and holds at N = 100 without a
line changing. Every reference implementation has exactly this shape and none of them outgrew it.

What does **not** scale is *inside* the engine side, and it is all of the kind that is invisible at four
fixes and crippling at forty: a parameter that grows per fix, an if-chain that grows per fix, helpers that
get copied per fix, and a test that opens an engine per fix. Each item below is one of those, stated as
what breaks, when it breaks, and what to build instead. Sizes are honest — most of this is a day.

### A. The bridge parameter — one interface, not one argument per need

`Analysis.codeActionsIn(from, to, Function<String, List<String>> importCandidates)` threads **one**
piece of host-side knowledge across the classloader boundary for **one** fix. "Did you mean" needs a
second (fuzzy type candidates, capped); T3 needs a third (the editor's indent unit, for generated
lines); anything touching files needs a fourth. Each becomes a parameter on a bridge method, and a
bridge method is a signature both loaders must agree on.

**Build:** `engine/bridge/CodeActionContext` — one interface, host-implemented, passed once:

```java
interface CodeActionContext {
    List<String> importCandidates(String simpleName);       // exact — what exists today
    List<String> similarTypeNames(String name, int limit);   // "did you mean", edit-distance ranked
    String indentUnit();                                     // "    " or "\t" — from the editor
    String lineSeparator();
}
```

Lives in `bridge/` because both loaders must see it; the host implements it beside `JavaCodeActions`,
which already owns the `TypeIndex`. Small. Do it **first**, because every later item hangs a method on it.

### B. The if-chain — a contributor registry, grouped by family

`JavaQuickFixes.in()` is `if (id == X) … else if (id == Y) …` and it is four branches long. At forty it
is the file every fix edits, which is the failure `CodeActionProvider`'s own javadoc names as the thing
to avoid — the exhaustive table, arrived at by accident. JDT-LS uses `hasCorrections(int)` +
`getCorrections`; IntelliJ registers into a `QuickFixFactory`. Same idea, ours:

```java
interface Correction {
    int[] problems();                                          // the IProblem ids it answers for
    void contribute(FixContext ctx, IProblem problem, List<CodeAction> out);
}
```

`FixContext` bundles what every helper today takes as five parameters — `unit`, `source`, `version`,
`documentLength`, the host `CodeActionContext` from A. `JavaQuickFixes` becomes a `Map<Integer,
List<Correction>>` built once from a static list, and the corrections live in **files by family** —
`UnusedCorrections`, `ModifierCorrections`, `AnnotationCorrections`, `TypeMismatchCorrections`,
`ExceptionCorrections` — each a few lambdas or small classes. A new fix is a new entry in its family
file and touches nothing shared. Small; the existing four move over as the proof, with their tests
unchanged.

### C. The edit toolkit — `SourceEdits`, grown on demand

`deletion()`, `afterLine()`, `isBlank()`, `enclosing()` are private statics on the table today. They
are the beginnings of the T1–T2 toolkit and they need to be **one** class that every family file
reaches, because the alternative is what §4 warns about — the third copy of an indentation helper.

**Build:** `SourceEdits` (text-level, over `String source` and `ASTNode`), with the four that exist plus,
as each tier needs them: `indentOf(offset)`, `insertLineAbove(node, text)` (the annotation helper — the
whole of the T2 annotation group and of `SUPPRESS`), `deleteFromList(node, siblings)` (comma-aware —
the whole of the T1 separator group), `replaceNode(node, text)`, `unwrapBlock(block)` (redundant
`if`/`else`, from the other side of try/catch). **The rule: a helper is added when the *second* fix
needs it, never the first.** Medium, spread across the batches.

### D. Imports — ours, because `ImportRewrite` is not available

`ImportRewrite.create(CompilationUnit, boolean)` is in the jar, but — as far as I recall from the JDT
source, and it is worth a one-line probe before relying on it either way — it throws unless the unit was
built from an `ICompilationUnit`, i.e. the Java model. Ours is built from `char[]` and always will be.
So `importInsertOffset()` stays ours and grows into the axis-D enabler:

```java
final class ImportPlan {
    String nameFor(ITypeBinding type);   // the simple name to write, recording the import it needs
    List<Change> changes();               // the insertions, all at importInsertOffset
}
```

Every T3 fix that writes a type name asks `nameFor` and appends `changes()` to its own. Two things make
this compose cleanly and both are **verified**: `ChangeSet.of` rejects `from < previousEnd`, not `<=`,
so several insertions at one offset are legal and apply in list order; and `alreadyImported` plus
same-package and `java.lang` checks are the whole of the "does it need one" question. Small, and it is
the single most-reused piece in T3–T4.

### E. `ASTRewrite` — the T3 entry ticket, and what it actually costs

Per §4: one `compileOnly("org.eclipse.platform:org.eclipse.text:3.11.0")` (already in every band's
closure), a `Document` built from `source`, and a `Rewrites.toChanges(TextEdit)` that flattens the
edit tree into a sorted `List<Change>` — `ReplaceEdit`/`InsertEdit`/`DeleteEdit` carry offsets into the
original document, which is exactly `Change`'s contract, and `ChangeSet.of` inherits the sorted/non-
overlapping check. **One thing to know going in:** `rewriteAST(IDocument, options)` formats generated
code from the options map, and with none it uses JDT's defaults — tabs. The indent must come across the
bridge (A) and into the options, or generated code is indented differently from the file around it.
Medium, once, and then flat per fix.

### F. Problem policy — one table, and one map beside it

`compilerOptions` sets one severity. Everything in T2's optional group needs its diagnostic switched on
(§13), and a hundred `options.put` lines with a comment each is not a policy. **Build:**
`EcjProblemSeverities` — a table of `(option key, default severity, why)` applied in one loop, which is
also the thing Preferences wires to when it does. Beside it, `SUPPRESS` needs the `@SuppressWarnings`
token per problem — JDT computes that through `CompilerOptions.warningTokenFromIrritant` and
`ProblemReporter.getIrritant`, **both internal**, and an internal API is the one kind that may differ
between bands. A small map of our own for the problems *we* enable is more honest than reaching for it.
Small.

### G. The carrier — one gap, one non-need, one deliberate no

- **`commandId` carries no arguments.** `CodeAction.command(title, kind, commandId)` and
  `DiagnosticActions.run(editor, id)` — an action that runs a command cannot say *what* to run it on.
  "Create class 'Foo'" via a command needs `Foo`. LSP's `Command` has `arguments`; add
  `Map<String, String> arguments` to the record. Tiny, and it is the escape hatch that keeps every T5
  row *representable* without a multi-file edit type.
- **Lazy resolve (`codeAction/resolve`) is a non-need.** Fixes compute synchronously on the UI
  thread; `install()` swaps `current` on the UI thread too (verified), so there is no race and no
  reason to defer. Bindings are already resolved by the parse, so even T4's create-method is a walk,
  not a compile. Revisit only if a hover measurably lags.
- **Multi-document edits: no, and on purpose.** A `WorkspaceEdit` is a new carrier, a new apply path in
  `Workbench`, and cross-document undo — which the undo design (one stack per document, deliberately no
  window-level stack) has no answer for. That is the actual cost of T5, and it is why T5 is "blocked"
  rather than "hard". The `arguments` field above covers the cases that are really "open a dialog".

### H. Tests — the fixture that makes a fix's test three lines

`JavaQuickFixTest` opens an engine in `@Before` — **per test method**. Fine at seven; at a hundred it
is a hundred classloader builds. Move the engine to a per-class (or suite-level) fixture, and write the
helper the whole catalogue is tested through:

```java
FixFixture.assertFix(before, needle, "Remove unused import", after);
FixFixture.assertReported(before, IProblem.UnusedPrivateMethod);   // the §13 check, first
```

A family's tests become a table of `(before, title, after)` triples. The second assertion is what stops
a dead-on-arrival row from going green. Small, and it pays back on the first batch.

### I. UI — two things that turn from fine to wrong at scale

- **The Alt+Enter list needs sections once it passes ~6 rows.** Kinds already tier the sort; render a
  separator between tiers so quick fixes, refactorings and suppress read as three groups rather than one
  list. `MenuBuilder` already knows how to draw a section boundary.
- **`preferred` needs a rule, or every contributor marks its own.** At most one preferred per problem,
  and only when the fix is the unambiguous single answer — Import X with one candidate, yes; with four,
  no (it already does this). The stable sort makes the first-marked win, which is fine only while the
  rule is kept.
- **The bulb is diagnostic-driven, deliberately, and intentions would want it action-driven.** Recorded
  as the trade rather than resolved: an action-driven bulb means an async request per caret move, and
  the synchronous version was chosen so the bulb never flickers in behind the caret. When intentions
  land, that is the decision to reopen.

### J. Two enablers that are refactorings in disguise

- **`Renames.inFile(unit, binding)`** — every reference to a local, from bindings. Needed by "rename to
  `ignored`" (T1), "did you mean" on locals (T2), and later by a Rename refactoring outright. Write it
  once as a utility, not inside a fix.
- **"Did you mean" reuses two things that exist and are private:** `TypeIndex.matching` (host side,
  package-private, generous by design) and `JavaCompletionProvider.membersFrom` (the receiver walker).
  Expose, don't duplicate — a second member walker is how a panel's filter and its search came to
  disagree, per the invariants table.

### K. Bands — the constant trap, stated once

`IProblem` constants are `static final int` and **inline at compile time**, so the adapter can name only
those in 3.26.0 — which is why every row above was checked against that jar. A problem that a newer
band adds cannot be named at all; if one is ever wanted, gate it reflectively
(`IProblem.class.getField(name)`), which is the pattern `EcjOptions.jlsLevel()` already uses for
`AST.JLS*`. Not a today problem; a "do not be surprised" note.

### What to do, in what order

1. **A + B + C in one pass, as a refactor of the four shipped fixes** — no behaviour change, tests
   unchanged, and that is the proof the shape is right. Half a day.
2. **H** — the fixture and the per-class engine, before the first batch, or the batch is written
   without the §13 check.
3. **F, with the T1 batch** — the first fixes that need a diagnostic switched on, so the policy table
   is written against a real need.
4. **D, with the first "did you mean"** — `ImportPlan` and the fuzzy candidates on the context.
5. **E, before the first T3 fix, not during it.**
6. **G's `arguments` field, when the first command-shaped action needs it** — and not before, because
   nothing shipped reads it yet.

What is deliberately **not** on this list: the contract, the merge, the apply gate, the popup, and
`DiagnosticActions`. Those are done. The greedy version of this feature is a hundred small entries on top
of them, and the whole purpose of A–H is that each one costs a family-file entry and a three-line test.

---

## 15. The verification environment — how a hundred fixes get checked without a hand on the mouse

The constraint is stated plainly: nobody is going to try a hundred fixes by hand, and a fix that is
wrong is *silent* — it edits the file and compiles, or it edits the wrong text and compiles. So the
environment has to answer, for every fix and without a human, four questions in this order:

1. **Is the diagnostic reported at all?** (§13 — the dead-on-arrival trap)
2. **Does the fix produce exactly the text intended?**
3. **After the fix, is the problem gone — and is nothing new broken?**
4. **Does the whole thing survive real code it was not written against?**

Then two things a test cannot answer — does it *look* right, and does it *feel* right — which is
where the harness fixture files come in, and where a human is used only for what only a human can do.

### Layer 1 — the fixture: every fix is three lines *(`language/src/test`)*

The engine is real (band-loaded ECJ, `JavaEngine.open`), the document is a string, and the whole
pipeline from analysis to `ChangeSet` runs in-process. This is where 90% of the verification lives.

```java
public class UnusedCorrectionsTest extends FixFixture {
    @Test public void unusedPrivateMethodIsRemoved() {
        assertReported(BEFORE, IProblem.UnusedPrivateMethod);            // question 1
        assertFix(BEFORE, "helper", "Remove method 'helper'", AFTER);    // question 2
        assertResolves(BEFORE, "helper", "Remove method 'helper'",       // question 3
                       IProblem.UnusedPrivateMethod);
    }
}
```

`FixFixture` owns:

- **One engine per class**, `@BeforeClass`, not per method — `JavaQuickFixTest` opens one per test today.
  Fifteen family files at a hundred tests must not mean a hundred classloader builds.
- **`assertReported(source, id)`** — the analysis reports that id, somewhere. The whole reason it exists
  is that a fix for an `ignore`-severity problem is invisible from the fix's own code.
- **`assertFix(source, needle, title, expected)`** — offers an action with that title at the needle;
  applying it yields exactly `expected`. Text, never title alone.
- **`assertNoFix(source, needle, titlePrefix)`** — the refusals are contracts too: multi-fragment
  declarations, `ArgumentIsNeverUsed`, `NotVisible*` across files. A refusal that stops refusing is a
  regression that no positive test sees.
- **`assertResolves(source, needle, title, id)`** — **the oracle that catches what the others cannot.**
  Apply the fix, re-analyse the result through the same engine, assert the problem id is no longer
  reported at that location **and the error count did not grow.** IntelliJ's intention tests do exactly
  this. It catches "remove unused import" that leaves `import ;`, "add throws" that adds it to the wrong
  method, "remove cast" that changes overload resolution — every fix whose text looks right and is not.
- **`assertUndoable(source, needle, title)`** — one `TextBuffer`, apply through the same path the editor
  uses, one `undo()`, text equals `source`. Pins the two `breakUndoCoalescing` brackets from the outside.

Every family file is a table of `(before, needle, title, after)` and nothing else. The band it runs on
is whichever `EngineBand.detect()` finds; **running the whole `language:test` under a JDK 8, 11 and 17
in turn is the cross-band check**, and it needs no code — the jars for all three are already handed in
as `cgui.test.engineBand8/11/17`.

### Layer 2 — the corpus: real code the fixes were not written against *(slow, opt-in)*

Fixture strings are what the author imagined. Real files are what the author did not. `CorpusTest`:

- takes every `.java` under `core/src/main/java` and `language/src/main/java` — a few hundred files
  of real, idiomatic, sometimes odd code, **on this machine, no download**;
- analyses each **with an empty classpath**, which makes nearly every file report `UndefinedType`,
  `ImportNotFound`, unused imports and the rest — a rich, free source of the exact problems the
  catalogue keys on;
- for every problem, computes every action; for every action with an edit, **applies it and re-parses**;
- asserts three things and nothing finer: no exception out of any contributor, `ChangeSet.of` never
  refused a fix's changes (overlap or past-end), and the re-parse has **no more syntax errors** than the
  original — a fix may leave a semantic problem it could not know about, but it may never break the
  parse.

Gated behind a system property (`-Dcgui.test.corpus=true`) because it is minutes, not seconds, and run
before a batch is called done rather than on every build. This is the layer that finds the fix which
works on `int x = 1;` and throws on `int x = 1, y = 2;` in a file nobody wrote a test for.

### Layer 3 — the editor path, engine-free *(`core/src/test`)*

`CodeActionApplyTest` already covers `applyCodeAction` and the version gate. What it does not cover is
the **round trip through the widget**: diagnostics arriving, `diagnosticsAt`, the bulb, the request, the
merge with `DiagnosticActions`, the popup's primary/more split, the Alt+Enter menu, the apply. All of
that runs headlessly through a `UIWindow` with a **stub `CodeActionProvider`** that returns canned
actions — no engine, no band, so it lives in `core/` and runs in the ordinary suite. Add here:

- the menu shows sections between tiers once there are enough rows (§14-I);
- one preferred per problem, and it is the one the popup shows inline;
- a stale action (version moved) is refused and the popup asks again rather than applying;
- a `commandId` action with `arguments` reaches the command with them (once §14-G lands).

### Layer 4 — the harness fixture files: the corpus a human can open

**A `.java` file per family, seeded into the harness workspace**, so the file tree shows
`fixtures/Unused.java`, `fixtures/Modifiers.java`, `fixtures/Annotations.java`,
`fixtures/TypeMismatch.java`, `fixtures/Exceptions.java`, `fixtures/DidYouMean.java`, and so on. Each
file is **valid enough to parse and full of exactly the problems its family fixes** — one method per
catalogue row, named for it, with a `// → "Remove method 'helper'"` comment above stating what the
popup should offer. Open one, hover a squiggle, press Alt+Enter, and the whole family is in front of you
in the order the catalogue lists it.

Two rules that make these files pull double duty:

- **They live in `language/src/test/resources/fixtures/` and the harness seeds them from there.**
  `HarnessWorkspace.seedScratchProject` already writes `src/Main.java` with `writeIfAbsent`; it seeds
  these the same way, one file each, into `workspace/src/fixtures/`. `writeIfAbsent` means an existing
  workspace gains the new files and keeps its edits — and **the same files are Layer 2's first corpus
  entries**, so what a human opens is exactly what the corpus test chewed through.
- **One catalogue row is one method, and the method's name is the row.** When a fix is added, its
  method is added to the family file in the same commit. A row with no method in a fixture file is a
  row nobody can try. And because ECJ needs the file to *parse*, each file stays syntactically valid —
  the T3 fixtures that need a **type mismatch** get one by assignment, not by leaving a token out.

For the human pass, that is the entire cost: open a file, work down it. It is also what makes the
manual test *finite* — a family file has fifteen methods, not a hundred, and the catalogue says which
file each new fix landed in.

### Layer 5 — pixels, and only where pixels are the question

Two things a test cannot see: the popup's problem band with three real fixes in it, and the Alt+Enter
menu with sections. `CgUiDockScene` is interactive, so those are looked at by opening a fixture file. If
that ever needs to be repeatable, a **managed** scene (`HarnessSceneLifecycle`, single frame, PNG via
`ScreenshotUtil`) that opens `fixtures/Unused.java`, places the caret on a known squiggle, and captures
the popup is a morning's work — but it is worth building only once the popup stops changing, because a
PNG asserts one look and the look is still being tuned. Not now.

### What this environment costs, and what it buys

Layers 1, 3 and 4 are the price of admission and are small — `FixFixture` is a morning, the fixture
files grow one method per fix, and Layer 3 is a handful of tests on machinery that exists. Layer 2 is a
day, once, and is the only one that finds the bugs nobody wrote a fixture for. Layer 5 is deferred on
purpose.

What it buys is the thing the catalogue's size makes necessary: **a fix is done when its family file
gains a method, its test file gains a triple, `assertResolves` is green, and the corpus is quiet.** No
part of that needs a person, and the part that does — does it look and feel like IntelliJ's — has one
file to open per family and a comment above every squiggle saying what should appear.

---

## 16. Where this leaves the work — the next step, exactly

Everything above reduces to one ordered list, and the first item is the one to start on:

1. **`FixFixture` + `assertResolves` + a shared per-class engine**, and move `JavaQuickFixTest`'s seven
   tests onto it unchanged. This is §14-H and Layer 1 in one, and it is first because every later
   step is tested through it.
2. **§14 A + B + C** — `CodeActionContext`, the `Correction` registry, `SourceEdits` — as a pure move
   of the four shipped fixes. Same tests, same text, new shape.
3. **The fixture files** — `fixtures/Unused.java` first, seeded into the harness, with a method per row
   already shipped and per row in the T1 batch. From here on the human pass exists.
4. **§14-F, then the T1 batch** — the severity table written against the first fixes that need a
   diagnostic switched on; unused private method / constructor / type, superfluous semicolon, unused
   allocation, dead code, unused assignment. Each: one `Correction`, one triple, one method.
5. **The annotation helper**, `@Override` / `@Deprecated`, and — the moment it exists — the `SUPPRESS`
   decision put back in front of you as a product call, not an engineering one.
6. **`ImportPlan` and "did you mean"**, then the separator helper and its four fixes, then organise
   imports.
7. **`Rewrites` (§14-E) before the first T3**, then try/catch and `throws` as the pair that proves it.
8. **Layer 2, the corpus test**, run once the T1–T2 batches are in and before T3 starts.

---

## 17. Review — where the plan was settling, and what to build first instead

A pass over §14–§16 asking one question of each decision: *is this the best shape, or the shape our
infrastructure happened to make cheap?* Four of them were the second kind. Each is stated with what the
better state is, what stops us having it, and whether that obstacle is worth removing **before** the
first fix rather than after the fortieth. Three are; one is not. The rest of the plan survives the pass,
and the non-needs are re-affirmed with the reason each time, so nobody reopens them for free.

### R1. `ASTRewrite` is the substrate from the first fix — not the T3 entry ticket

§4 and §14-C recommended hand-rolled `Change`s through T2 and `ASTRewrite` from T3. Re-read cold, that
is the settling: it commits to **two edit substrates**, a `SourceEdits` toolkit that will grow six
helpers, and a line where every author has to decide which side a fix belongs on. JDT-LS — the one
reference built on this exact compiler — uses `ASTRewrite` for *everything*, including "remove unused
import", and its correction files are uniform because of it.

What was stopping us was one `compileOnly` line for a jar already in every band's closure, and an
unverified belief that a hand-rolled deletion is simpler. It is simpler for one fix and worse for
thirty: `ListRewrite` handles commas; generated nodes are formatted from the options; `remove` on a
statement takes its line; `createStringPlaceholder` still admits raw text where a node is overkill.
Every helper `SourceEdits` was going to grow is something `ASTRewrite` already does.

**The better state:** one `Rewrites` seam — `Rewrites.create(FixContext)` returning an `ASTRewrite`
bound to the unit, `Rewrites.toChangeSet(rewrite, ctx)` running `rewriteAST(Document, options)` and
flattening the `TextEdit` into `ChangeSet.of` (which inherits the sorted/non-overlap check). Every
correction, T1 to T4, is written on it. Hand-rolled `Change` survives only for the token-level cases
with no node — superfluous semicolon, the NLS tag — and `SourceEdits` shrinks to `enclosing()` and the
token helpers.

**What has to be true first, and is not yet proven:** that `ASTRewrite` behaves on a unit parsed from
`char[]` (it is documented to; JDT-LS happens to have a Java model, we do not); that removing an
`ImportDeclaration` yields a clean line rather than an empty one; and that the options map carries our
indent so generated code matches the file. **That is a spike — one test, half a day — and it is step 0.**
If it is green, §14-C is struck and the four shipped fixes are re-expressed on `Rewrites` as the proof.
If it is red, the plan as written stands and the spike has cost half a day. Both outcomes are cheap; the
outcome of *not* spiking is a second substrate that never goes away.

> **Spike result — done, and green with one carve-out.** Run against the band-8 jars directly, before any
> build change. `ASTRewrite` works on a `char[]`-parsed unit with `getJavaElement() == null`; one unit
> backs several independent rewrites, which is what a batch of candidate actions needs; `TextEdit` is a
> `MultiTextEdit` of `Insert`/`Delete`/`Replace` whose offsets address the original document, so the
> conversion to `ChangeSet` is a flatten and a sort; the indent **is** taken from
> `formatter.tabulation.char`/`size`. `ImportRewrite` is unusable exactly as §14-D suspected —
> *"AST must have been constructed from a Java element"*.
>
> **The carve-out is the import region, in both directions, and it is not a matter of taste.** JDT removes
> a list's elements together with the separators *between* them, so emptying a list that nothing precedes
> strands the final terminator: removing the only import of a package-less file yields a blank first line,
> identically through `remove` and `ListRewrite`. Inserting is worse — a `ListRewrite` on
> `IMPORTS_PROPERTY` produces `import java.util.List;public class Script { }` in the same shape. Both land
> on *a file with no package declaration*, which is what a script normally is, and both are what
> `ImportRewrite` exists upstream to handle. So the boundary is **the import region versus everything
> else** — one line, drawn once from two measurements, not a judgement per correction.
>
> The proof is behavioural rather than a spike test kept around: the four shipped fixes moved onto the
> substrate with their assertions unchanged, and the multi-fragment refusal — *"`int a = 1, b = 2;` with
> only `b` unused would lose `a` as well"* — **was lifted**, because removing one element of a list is now
> something the edit can say. That refusal existed only because a computed range could not express it, so
> it is also the cleanest evidence the substrate pays for itself.

### R2. A correction has an id, and tests key on it — titles are prose

Every test in §15 keys on the action's **title**. So does `preferred` policy, so would a keymap ("apply
fix by id"), and so does anyone reading a log. A title is prose: it carries the offending name, it will
be reworded, and if the UI is ever localised it will not be English. IntelliJ's `getFamilyName` and
LSP's `data` both exist for this reason.

**The better state:** `Correction.id()` — a stable dotted string, `"java.unused.removeImport"`,
`"java.imports.add"` — carried on the action as `CodeAction.id`. `assertFix` takes the id; the title
becomes something a test *may* check and never has to. **Cheap enough to do in step 0** (one field on a
record nothing serialises yet), and expensive to retrofit once thirty tests name titles.

### R3. The bridge context and the registry stand — with one sharpening

§14-A/B are right and are not settling. One sharpening from the review: `Correction.problems()` as
`int[]` is correct for the ECJ side, but the fourteen `RedundantNullCheckOn*` ids and the eight
`Unhandled*Exception` ids show that some corrections answer for a *family*, and a family is better named
once than listed at each site. `Problems.REDUNDANT_NULL_CHECKS`, `Problems.UNHANDLED_EXCEPTIONS` — small
constant groups in one file, alongside the severity table (§14-F), so a family is enabled and fixed from
the same place.

### R4. Cross-file: the honest ceiling, and why it is not worth raising yet

The greedy version of this feature wants "Create class 'Foo'", and §14-G said no to a multi-document
edit. Reviewed: still no, but the reason should be exact rather than general.

The better state is LSP's `WorkspaceEdit` — document changes plus create/rename/delete file operations,
applied atomically and undone as one. What stops us is not the carrier (a record with a map in it) but
**undo**: our history is one `UndoStack` per document, deliberately, with no window-level stack because
two tabs must not braid. A workspace edit that touches two documents and creates a third has no place to
be one undo entry, and VS Code's answer is a bulk-edit service with its own multi-file undo element that
took them years to make reliable. That is real infrastructure and it is not this feature's to build.

**So the ceiling is `commandId + arguments`** (§14-G's one-field addition): "Create class 'Foo'" is a
command that writes a file through the workspace client and opens it, **without undo**, which is exactly
what a user gets from New ▸ Java Class today. Stated in the action's title where it matters ("Create
class 'Foo'…", the ellipsis being the convention for "opens something"). Raising the ceiling is a
separate plan with cross-document undo as its first line.

### The non-needs, re-affirmed with the reason each

- **Lazy edit resolution** (LSP `codeAction/resolve`, IntelliJ's apply-time `applyFix`). Would let an
  action survive typing after the popup opened. Ours are eager and version-gated, and a stale one is
  re-requested — which is what VS Code does, and what `ChangeSet` forces: it has `mapPos`, `mapRange`
  and `compose` but **no `map(other)`** (CodeMirror's rebase-through), so an edit cannot be repaired,
  only recomputed. Eager stays; the cost per hover is a few `ASTRewrite` passes over changed subtrees.
- **Off-thread computation.** The callback interface already permits it; the analysis lifetime forbids
  it cheaply — `install()` closes the previous analysis on the UI thread, and a background reader would
  need it pinned. Sync until a hover measurably lags, and the interface will not have to change.
- **Retained-lane diagnostics get no fixes.** When the file does not parse, the popup shows warnings
  retained from the last good analysis; the unit behind them is gone, so no correction can key on them.
  Correct — fixing a file that does not parse is how a fix creates a second error — and worth one line in
  `JavaQuickFixes`' javadoc so it is not filed as a bug.

### The revised order — infrastructure first, then the batches

0. ~~**The `ASTRewrite` spike** (R1)~~ — **done, green.** `Rewrites` is the substrate; the import region
   keeps its own arithmetic. See the spike-result note in R1.
1. ~~**`FixFixture`** (§15 L1) with `assertResolves`, per-class engine, **keyed on correction id** (R2).~~
   **Done.** `CodeAction.id` landed with it, `IProblem` constants are nameable from tests via
   `testCompileOnly` (they inline, so no runtime coupling), and the first use of `assertReported` +
   `assertFix` on a previously untested correction found a real defect: `UnusedPrivateField`'s
   `getArguments()[0]` is the declaring **type**, so "Remove field 'count'" was titled
   *"Remove field 'Script'"*. Names now come from the declaration's own node.
2. ~~**§14-A/B + R3** — `CodeActionContext`, the `Correction` registry with `id()`, and `Problems`
   families — as a pure move of the shipped fixes onto the new shape.~~ **Done.** The bridge takes a
   `CodeActionContext` instead of a `Function`, so a future host-side need adds a method there rather
   than an argument to a signature both loaders must agree on; corrections register and are indexed by
   problem id; `ImportRegion` makes the import carve-out a place you can stand rather than a paragraph.
   The proof is that the tests' *logic* did not change — only which class the id constants come from.
   **`Problems` families were deliberately not created**: exactly one exists today (`UndefinedType` +
   `ImportNotFound`) and it is already the `problems()` array of the correction that uses it. It belongs
   beside the severity table (§14-F), which is where a family is both enabled and fixed.
3. ~~**The fixture files** (§15 L4), seeded into the harness.~~ **Done**, and they landed in the
   directory that already existed for exactly this — `language/src/test/resources/fixtures/`, whose
   README had documented the tracked-copy-plus-`cp` arrangement for the syntax-colouring documents.
   `installHarnessFixtures` replaces that `cp` and is **write-if-absent**, because the destination is a
   workspace somebody has been typing in. The `// FIX: "…"` lines are **assertions**, not comments:
   `FixtureFilesTest` reads every fixture containing one, asks the engine what it offers there, and
   fails naming the file, the line and what it got instead — so a fixture cannot quietly go stale. It
   selects files on the annotation rather than the extension, which is what keeps the 500-line
   colouring documents in the same directory out of a quick-fix analysis they make no claim about.
4. ~~**§14-F severity table, then the T1 batch.**~~ **Done**, and the ordering was backwards: eight of the
   relevant problems are reported with no configuration at all, so the batch never needed the table —
   the table needed the batch, and was written for the two entries that genuinely wanted switching on.
   Four corrections shipped (private method, constructor, nested type, redundant semicolon), each with a
   fixture site. `EcjProblemPolicy` holds the severities and the tags together because they are one
   decision made twice.
5. ~~Annotation insertion, `@Override`/`@Deprecated`, and the `SUPPRESS` product call.~~ **Replaced by
   the `UNNECESSARY` work**, which is what the enable-set decision made of it: with
   `MissingOverrideAnnotation` deliberately off — an override is a relationship, not a defect (§18.6) —
   the annotation helper had no consumer left. What shipped instead is the half of `DiagnosticTag` that
   was built and never connected: the analyzer produces tags, and the editor publishes an
   `unnecessary` highlight so dead code fades rather than gaining a sixth kind of squiggle. `SUPPRESS`
   stays parked.

   **Writing that test found a defect immediately**, which is the argument for having written it:
   `installDiagnostics` replaced the tracked lane without marking highlights dirty. `SquigglesPart`
   re-reads the lane every frame and never noticed, while the highlight cache is keyed on the visible
   range — so the fade would have appeared only when something else happened to scroll or type.
6. ~~`ImportPlan`, "did you mean", the list-element fixes, organise imports.~~ **Done, as three commits.**
   The "separator helper" turned out to be `ListRewrite` — remove from `throws`/`implements`/type
   parameters is one class reading the list off the node's own `getLocationInParent()`. "Did you mean"
   ranks by optimal-string-alignment distance (tolerance 2, or 1 for ≤ 3 characters, at most five, case
   counted only as the tie-break) with candidates from the tree for methods and names and from a new
   `TypeIndex.similar` walk for types; a rename to an unimported type imports it, which is why
   `ImportPlan` landed here. Organize imports is the registry's first **intention** — a correction with
   no problems, asked once per request about the range — and refuses over a comment.
7. ~~**`commandId + arguments`** with the first command-shaped action (R4).~~ **Done.** The first
   consumer already existed: "Copy problem message" re-read the problems at the *caret* when it ran,
   and the popup can be opened from a stripe mark nowhere near it. The action now carries the message.
8. ~~T3 pair (try/catch, `throws`), then the corpus test (§15 L2), then T4's create-method.~~ **Done, as
   three commits.** The T3 pair found the one thing the substrate could not yet express — a moved
   statement is a `MoveSourceEdit`/`MoveTargetEdit` pair, and `Rewrites` refused unknown edit types by
   design; the refusal did its job and the pair is now handled, re-indent modifier included. Create
   method infers parameter types and names from the arguments and the return type from the use site,
   only ever into a type declared in this file. The corpus test runs every action over every `.java`
   in the repository under `-Pcorpus`; see §20 for what it found.

---

## 18. Steps 4 and 5 — planned before written, because most of it is not an engineering decision

Steps 0–3 had right answers and a way to measure them. The next two do not, and that is the whole reason
this section exists: **every diagnostic switched on is a squiggle in somebody's file that was not there
before**, and nothing in the code can say whether "you allocated an object and discarded it" earns one.
What follows separates the parts that were measured from the parts that are a call.

### 18.1 What was measured

Every relevant problem, run through a real parse at the current option set. **Eight are already reported
with no configuration at all:**

| Reported today | Needs switching on |
|---|---|
| `UnusedImport`, `LocalVariableIsNeverUsed`, `UnusedPrivateField` | `MissingOverrideAnnotation` |
| **`UnusedPrivateMethod`**, **`UnusedPrivateConstructor`**, **`UnusedPrivateType`** | `SuperfluousSemicolon` |
| `DeadCode`, `AssignmentHasNoEffect` | `UnusedObjectAllocation` |

`unusedPrivateMember` is one JDT option covering field, method, constructor and nested type — and the
shipped field correction proves it is on. So **three of the T1 batch need no severity work whatever**,
and §14-F's ordering ("write the severity table, then the batch") is backwards: the table is not what
unblocks the batch, and building it first would have been building it for one entry.

### 18.2 The finding that matters more than the table: `UNNECESSARY` is half-built

`DiagnosticTag.UNNECESSARY` exists and its own javadoc argues the case — *"unused code is faded out …
folding them into the severity ladder would force a choice between showing a squiggle you cannot act on
and losing the rendering entirely."* `ProblemsPanel` consumes it and `panels.css` styles it.

**Nothing produces it.** `EcjSourceAnalyzer.diagnostics()` builds every `Diagnostic` through the six-arg
constructor, so the tag set is always empty. And the **editor ignores tags completely** — there is no
`DiagnosticTag` reference anywhere under `ui/elements/editor/`.

So today every unused import, local, field, method, constructor and nested type is an ordinary warning
squiggle, where IntelliJ greys the text and VS Code fades it. That is not a cosmetic gap: it is the
difference between "this file has nine problems" and "this file has nine bits of dead weight", and it is
why the reference implementations can afford to report all of them.

This lands on step 4 directly. The batch adds fixes to problems that are **already squiggling**, so it
adds no noise by itself — but it triples the number of squiggles anyone will actually notice, and the
natural response to a noisy editor is to turn the diagnostics off rather than to draw them properly.

### 18.3 Step 4 — what is actually buildable

**The batch, all on already-reported problems, no configuration:**

| Correction | `IProblem` | Node | Notes |
|---|---|---|---|
| Remove method 'x' | `UnusedPrivateMethod` | `MethodDeclaration` | |
| Remove constructor 'X' | `UnusedPrivateConstructor` | `MethodDeclaration` (`isConstructor`) | |
| Remove class 'X' | `UnusedPrivateType` | `TypeDeclaration` | Nested only; a top-level type is never reported |

One shared correction parameterised three ways, exactly as the local/field pair already is — they differ
only in the problem, the node type and the noun in the title. **Names come from the declaration's own
name node**, never from `getArguments()`, which is the lesson `UnusedPrivateField` cost.

**Two that look like they belong and do not:**

- **`DeadCode`.** ECJ points at the unreachable statement, but the unreachable statement is a
  *consequence* — `if (false) { … }` wants the condition simplified, not the block deleted, and deleting
  it leaves `if (false);`. IntelliJ treats this as a family of conditional simplifications rather than a
  removal. Not a deletion, so not this batch.
- **`AssignmentHasNoEffect`.** Fires for `n = n`, where deleting the statement is right, and the
  catalogue already records the case that makes it dangerous — an RHS with a call in it, where deleting
  drops the side effect. Narrow enough to need its own thinking, not a line in a batch.

Both stay in the catalogue; neither ships here. Deferring them is the point of having written §12's
exclusions down.

**`EcjProblemSeverities` is created only if something needs enabling**, and the only thing that does is
step 5's `MissingOverrideAnnotation` — which makes it step 5's cost, not step 4's.

### 18.4 Step 5 — the annotation helper, and what it unlocks

`@Override` needs `MissingOverrideAnnotation` switched on (measured: off). The insertion itself is a
`ListRewrite` on `MODIFIERS2_PROPERTY` — **and that must be spiked before it is trusted**, the same way
list removal was: the questions are whether the annotation lands on its own line, whether it takes the
declaration's indentation, and whether it sorts before the other modifiers rather than after `public`.

The helper is the point rather than the fix. Three things are the same insertion:

| | Annotation | Needs |
|---|---|---|
| Add missing `@Override` | `@Override` | `MissingOverrideAnnotation` on |
| Add missing `@Deprecated` | `@Deprecated` | three more options on |
| **Suppress** | `@SuppressWarnings("…")` | a problem → token map |

So the moment the helper exists, **`SUPPRESS` stops being an engineering question**. What remains is one
real problem: the `@SuppressWarnings` token for a given problem is computed by JDT through
`CompilerOptions.warningTokenFromIrritant` and `ProblemReporter.getIrritant`, **both internal API** — the
one kind that may differ between bands. A hand-written map covering only the problems we enable is
smaller, honest, and cannot break on a band upgrade.

### 18.5 How the fading is actually drawn — no new rendering machinery

Worth establishing before committing to it, because "fade the text" sounds like a change to how the
editor paints and is not one.

The editor already publishes **named highlight ranges** per view line — the CSS Custom Highlight API,
`::highlight(name)` — and that is how syntax tokens, semantic tokens, search matches, the bracket pair
and `search-excluded` all reach the screen. `search-excluded` is the exact precedent: it is struck
through by a `text-decoration-line` rule in the sheet, with no Java knowing what a strikethrough is.

`HighlightStyle.ALLOWED` carries `COLOR` and `TEXT_DECORATION_LINE`, so both tags are expressible:

```css
texteditor::highlight(unnecessary) { color: var(--editor-unnecessary-fg, …); }
texteditor::highlight(deprecated)  { text-decoration-line: line-through; }
```

So the whole of the rendering half is: publish two more named ranges from the diagnostic lane — whose
offsets are already tracked live through every edit — and write two CSS rules. **The colour stays in the
sheet**, which is what lets a scheme decide how faded "faded" is.

One consequence to accept deliberately: a character belongs to one highlight, and the last name written
wins it, so an unnecessary range publishes **after** the syntax tokens and therefore replaces their
colour rather than dimming it. That is IntelliJ's look (unused code goes flat grey) rather than VS
Code's (opacity, which keeps the hue). It is the one the mechanism gives for free, and the alternative
would mean per-character colour blending in the paint path for a difference nobody has asked for.

### 18.6 The decisions, as taken

| Question | Answer | Consequence |
|---|---|---|
| Enable `SuperfluousSemicolon` | **yes** | one more correction, and it tags as unnecessary |
| Enable `UnusedObjectAllocation` | **yes** | diagnostic only — see below |
| Enable `MissingOverrideAnnotation` | **no**, and for a better reason than taste | see below; step 5 becomes the tagging work instead |
| `UNNECESSARY` tagging + editor fading | **yes** | §18.5 |
| `SUPPRESS` | **stays parked** | unchanged |

**An override is not a problem, so it must not arrive as one.** The reason `MissingOverrideAnnotation`
stays off is not that the warning is annoying — it is that IntelliJ does not report overriding as a
diagnostic *at all*. It draws a **gutter marker** on the declaration ("Overrides method in `UIElement`
(com.crystalgui.ui)", Ctrl+U to navigate), which is information about a relationship rather than a
complaint about the code. Routing it through the diagnostic pipeline would put a squiggle, a Problems-panel
row and an error-stripe mark on every correctly-written override in the file.

That makes it a **separate feature, catalogued here so it is not lost**: a gutter decoration in the same
column the quick-fix bulb uses, fed by asking the resolver what the declaration at a row overrides, with
navigate-to-super on click. It needs no ECJ option, no correction and no severity — and it belongs with
the editor's other gutter parts (`FoldingDecorationsPart`, `GutterEdgePart`), not in this layer at all.
Its inverse, "is overridden by", is the same marker pointing the other way and wants a type hierarchy.

**`UnusedObjectAllocation` gets no correction, and that is the answer rather than an omission.** ECJ
reports `new FileWriter(f);` because the result was discarded, and the fix a user wants is almost never
"delete the line" — it is "assign it to something", which is the bug the diagnostic just found. Offering
deletion, and offering it as the *preferred* action, would turn a warning that catches a real mistake
into a one-keystroke way to discard the evidence. The diagnostic is the whole value here.

### 18.7 The three decisions that were not mine (answered above)

---

## 19. Steps 6, 7 and 8 — the calls, taken before the code

Measured first, as before. Four more problems need switching on and one shape question was settled by
the probe: `UnhandledException`'s argument is the **qualified** exception name, so the import plan works
from a string, and the unresolved-variable case reports as `UnresolvedVariable`, not `UndefinedName`.

| Call | Answer | Why |
|---|---|---|
| Enable `unusedDeclaredThrownException`, `redundantSuperinterface`, `unusedTypeParameter` | **yes**, tagged unnecessary | all three are dead weight by the §18 line, and IntelliJ reports all three by default |
| Enable `unusedExceptionParameter` | **no** | it fires on every `catch (Exception e)` that ignores `e`, which in a script is most of them; IntelliJ's own unused-declaration inspection excludes catch parameters by default. So the rename-to-`ignored` fix has no diagnostic and is not written |
| "Did you mean" ranking | edit distance ≤ 2 (≤ 1 for names of three characters or fewer), case-insensitive, at most five, exact-case match first | `Lst` must offer `List` and must not offer forty scattered matches; the completion matcher's subsequence tier is the wrong tool and its own javadoc says which consumers must refuse it |
| Where "did you mean" candidates come from | types: the host's `TypeIndex`, walked by distance (a new method beside `matching`); methods and fields: the receiver's binding, on the engine side; unresolved names: locals, parameters and fields in scope, from the tree | the split is the same one Import X drew — the tree knows what is in scope, only the host knows the classpath |
| Organise imports | an **intention**, offered whenever the request overlaps the import region, kind `SOURCE`; IntelliJ's default layout (other, blank, `javax`, `java`, blank, static); refused if a comment sits inside the region | it is the first caret-based contributor, so the registry gains the smallest possible extension: a correction whose `problems()` is empty is asked once per request about the range. Refusing over a comment is what stops a tidy from deleting somebody's note |
| `commandId + arguments` | `Map<String,String>` on the record, and **`copyMessage` is its first consumer** — a real defect: `DiagnosticActions.run` reads the problems at the *caret*, and the popup can be opened from a stripe mark or a hover nowhere near it | R4 said "when the first command-shaped action needs it"; one already did |
| Try/catch template | `throw new RuntimeException(e);` | IntelliJ's default since 2020, and it does not swallow. A declaration whose value is used afterwards is split — `Type x;` then `try { x = …; }` — which is what IntelliJ does and what keeps definite assignment satisfied |
| Add `throws` | on the enclosing method or constructor; **refused inside a lambda body and inside an initialiser** | a `throws` added to the method around a lambda is added to the wrong callable and compiles anyway, which is worse than nothing |
| Create method from usage | in the receiver's type **only if that type is declared in this file**; `private` when it is the enclosing type, package-private otherwise; parameter types from the argument bindings, names from the arguments when they are simple names; return type from the use site (`void` for a statement, the declared type for an initialiser, else `Object`) | anything else is a second file, which is §14-G's deliberate no |
| Corpus test | every `.java` under `core/` and `language/`, empty classpath, every action applied and re-parsed; ~~gated on `-Pcorpus`~~ **runs with the suite** — measured at twelve seconds, see §20 | it is the layer that finds what nobody wrote a fixture for |

---

## 20. What the corpus found

652 files, 21,095 problems, 942 actions offered, all 942 applied and re-parsed, **twelve seconds**. So the
gate the plan called for was unnecessary — the assumption was minutes — and it now runs with every
`:language:test`, `-PnoCorpus` to skip. Its first run produced four things worth writing down.

- **Zero correction failures.** Nothing threw, no edit was refused by `ChangeSet.of` or fell outside its
  document, and no file that parsed before a fix failed to parse after it. That is the assertion the pass
  exists to make, and it held on the first run against real code none of the fixtures resembled.
- **ECJ itself crashed on one file** — an `AssertionError` out of its binding layer, *"The constructor
  `<init>(List<CompletionItem>, boolean)` is wrongly tagged as containing missing types"*, on
  `CompletionList.java`: a record whose component types were unresolvable at an empty classpath. Not a
  correction's defect, and recorded as such rather than counted against them. **It is a production
  finding in its own right:** `EcjSourceAnalyzer.analyze` does not guard `createAST` against an
  `Error`, so a script declaring a record over a type that is not on the classpath — a mod class, on a
  server without it — would kill the analysis job rather than report anything. Open; belongs to the
  analyzer, not this layer.
- **Two real create-method defects, both fixed and both pinned by a test.** A lambda argument has no
  type of its own — it takes one from the parameter it is passed to, and that parameter is what does
  not exist yet — so writing `Object` produced a signature the call still could not use while looking
  finished; the correction now refuses when any argument is a lambda or method reference. And a call
  from inside an *interface* generated a private method with a body, which is Java 9 and the floor is
  8; the instance case is now an abstract method, which is also what IntelliJ generates there.
- **The 33 remaining "more errors after than before" are the empty classpath speaking**, and are printed,
  not asserted. A generated `getAttachedWindow()` copies its return type from the use site, and
  `UIWindow` is unresolvable in this run — so the new declaration is one more unresolvable name. With a
  real classpath the same fix compiles. The rest are "did you mean" choosing a same-file nested type or
  an out-of-scope local, which its own javadoc records as an accepted imprecision. Asserting on this count
  would fail the suite for judgement calls; reading it is what a person does before calling a batch done.

1. **Which of the three optional diagnostics to switch on.** `MissingOverrideAnnotation` is required for
   step 5 to have anything to fix. `SuperfluousSemicolon` and `UnusedObjectAllocation` are opinions —
   the first is tidiness, the second catches real bugs (`new FileWriter(f);` discarded).
2. **Whether `UNNECESSARY` tagging and editor fading are in scope**, or a separate piece of work. It is
   the difference between the unused family reading as errors and reading as dead weight, and it touches
   the analyzer and the editor's rendering rather than the correction layer.
3. **Whether `SUPPRESS` ships now.** Parked in the catalogue as a product call; the helper in step 5 is
   what it was waiting on.

Steps 0–2 are the infrastructure this review says to build *first*, and together they are two days.
After them the plan is what it was meant to be: a hundred entries, each a `Correction` in a family file,
a triple in a test file, and a method in a fixture file — with one substrate under all of them.

---

## 21. Anonymous class → lambda, planned in full

The first entry in this document that is **not keyed on a problem**, and that is measured rather than
assumed: probed against a real parse, a convertible anonymous class produces **no diagnostic at all** —
not a warning, not an info. Four shapes were tried (convertible, extra field, uses `this`, non-functional
interface) and every one reported nothing. "Anonymous can be replaced with lambda" is a JDT *UI*
clean-up and an IntelliJ inspection; it is not something a compiler reports. So this is an
**intention** — a `Correction` whose `problems()` is empty, asked once per request about the caret range
— which makes it the second consumer of a hook that has had exactly one (`Organize imports`).

### 21.0 Correction — the measurement was right and the conclusion was not

Shipped on the reading above and immediately reported as *"no quick fixes anywhere"* on a file where the
engine was answering correctly. No compiler emits this, which is true; **IntelliJ still reports it**, as a
warning in the Problems panel directly beside "Class 'Inner' is never used". A refactor nobody can see is
a refactor nobody applies.

So the engine reports it itself — `LambdaCorrections.reportIn` walks the unit and the analyser publishes
one finding per convertible site, at the **header** range the correction is offered on. The action becomes
an ordinary `QUICK_FIX`, because kind is about what an action *answers*: with a message above it in the
popup, it is the fix for that message. Routing is unchanged — our corrections key on `IProblem` ids and
this is not ECJ's to give one to, so the caret-range hook still finds it.

Drawn **faded rather than underlined** (`DiagnosticTag.UNNECESSARY`), which is the unused family's
treatment and IntelliJ's own here: `new Comparator<String>()` is ceremony the lambda does without, and a
yellow squiggle under every anonymous class in a file would be the loudest thing on screen for something
nobody has to act on.

Three editor gates had to give way with it, each the same rule written down somewhere else — the gutter
bulb, the documentation popup's action strip, and the popup's inline slot. All three assumed *action ⟹
diagnostic*, which held only while every action came from a problem.

### 21.1 What the references actually check

Read for the decision list, not for code. **IntelliJ Community is Apache 2.0** and portable with notice;
**Eclipse JDT is EPL-2.0 and is mapping only**. The two agree closely enough that their overlap is the
specification.

| Refusal | IntelliJ | Eclipse | Ours |
|---|---|---|---|
| Not a functional interface | ✅ | ✅ | ✅ |
| Abstract method is **generic** (`<T> T make(…)`) | via inference failure | ✅ stated | ✅ **measured fatal** |
| Any other member — field, initialiser, second method, nested type | ✅ | ✅ | ✅ |
| Unqualified `this` / `super` in the body | ✅ | ✅ | ✅ — qualified `Outer.this` is fine, **measured OK** |
| A recursive call to the method itself | ✅ | — | ✅ |
| The method carries a **Javadoc** comment | ✅ | — | ✅ |
| Runtime- or class-retained annotations on the method | ✅ | — | ✅ |
| `synchronized` / `strictfp` on the method | ✅ | — | ✅ |
| Annotations on the **base class type** (`new @Foo Runnable(){}`) | ✅ | — | ✅ |
| Target type cannot be inferred | ✅ | — | ✅ |

Two more that neither list spells out and that one parse settles:

- **Shadowing is fatal, and not only for parameters.** A lambda's parameters *and its body's locals*
  share the enclosing scope, where an anonymous class opened a new one. Both measured: *"Lambda
  expression's parameter `left` cannot redeclare another local variable defined in an enclosing scope"*,
  and the identical message for a body local `tally`. The anonymous form compiles in both cases, so this
  is a defect the conversion would introduce rather than one it would reveal.
- **Ambiguity is fatal, and only for same-arity overloads.** `take(Comparator)` beside `take(Runnable)`
  is decided by arity and converts cleanly; two interfaces of the *same* shape give *"The method
  take(Script.F1) is ambiguous"*. The anonymous form named its type; the lambda does not.

### 21.2 The two repairs, rather than two more refusals

Where the fix earns its keep, and what IntelliJ does rather than what is easiest.

**Shadowing → rename.** Every clashing name is renamed before the body moves, `name` → `name1` and
upward until free. One mechanism covers parameters and body locals alike: resolve the declaration to its
`IVariableBinding`, walk the body for every `SimpleName` resolving to that binding, rewrite them
together. The set to avoid is the locals and parameters **in scope at the `new` expression** — fields
are not in it, because a lambda may legally shadow a field.

**Ambiguity → cast.** `take((Comparator<String>) (a, b) -> 0)`, measured to resolve where the bare lambda
does not. IntelliJ writes the cast and then removes it when `RedundantCastUtil` says it was not needed;
we cannot re-resolve inside a code-action request, so the cast is written when the `new` sits in an
**argument whose invoked method has more than one candidate of that name and arity**, and not otherwise.
Conservative in the right direction — a cast that was not needed is ugly, a cast that was needed and
missing does not compile. The ugly one is then removable by `java.expression.removeCast`, which is the
two composing rather than either guessing.

### 21.3 The one correction that is NOT written on `ASTRewrite`

§4 makes the rewriter the substrate for everything, and this is where that stops paying. The body has to
arrive **unchanged** except for a rename or two inside it, and `createMoveTarget` will not take nested
edits: renaming `left` to `left1` produced **`1left`** at every use — the moved text and the nested edit
disagreeing about whose coordinates an offset was in. Copying the subtree instead does work, and silently
drops every comment in the body, which is a worse bug than the one it fixes.

Written as text the whole conversion is **three ranges on the original document**, which is what
`ChangeSet` wants anyway: the header up to the body's first character, the renames inside it, and
whatever trails the body. The author's own indentation and comments survive because nothing regenerates
them — which is strictly better output than the rewriter was producing, so this is not a compromise.

### 21.4 The edit

- Replace the whole `ClassInstanceCreation` with a `LambdaExpression`: the `new`, the type arguments and
  the class body all go, because the target type supplies every one of them.
- **Parameter types omitted**, always inferred. Parameter *names* kept, renamed if they clash.
- `@Override` dropped — source-retained, inherited, and not expressible on a lambda.
- **A single-statement body collapses to expression form.** `{ return expr; }` becomes `expr`; a void
  body of one expression statement becomes that expression. Anything longer keeps its block, which is why
  the screenshot that prompted this keeps its braces.
- Id `java.lambda.fromAnonymous`, title **"Replace with lambda"**, kind `REFACTOR` — which already exists
  and sorts below `QUICK_FIX`, so it never outranks a fix for something actually wrong.

### 21.5 Where it is offered

On the **header** — `new` to the opening brace — and not on the whole body. IntelliJ highlights exactly
that span, and the reason is practical: an intention offered anywhere inside a forty-line anonymous class
is in every popup that class contains, competing with the fixes for real problems on those lines.

### 21.6 Staging

1. **The conversion, refusing on everything in 21.1 and on shadowing.** Complete and safe; the common
   case — a `return`, an assignment, a field initialiser — is covered.
2. **The rename**, which turns the shadowing refusal into a conversion.
3. **The cast**, which turns the ambiguity refusal into a conversion.

Each is independently shippable and each strictly grows what converts, so a stage that has to come back
out costs offers rather than correctness.

### 21.7 Verification

The corpus suits this one unusually well: `core/` and `language/` are full of anonymous listeners,
comparators and `Runnable`s, so the pass exercises real inputs at scale — and its rule, *a file that
parsed still parses*, is precisely the property every refusal in 21.1 exists to protect. The fixture
carries one site per condition **including the ones that must be refused**, because a conversion firing
where it should not is the failure mode with no diagnostic to notice it.

### 21.8 Deliberately not here

- **Lambda → anonymous**, the inverse. IntelliJ ships it; it is a separate intention and needs nothing
  from here twice.
- **Lambda → method reference** (`(a, b) -> a.compareTo(b)` → `String::compareTo`). A second inspection
  in IntelliJ and a genuinely harder analysis: it has to prove every parameter is passed through
  untouched and in order.
