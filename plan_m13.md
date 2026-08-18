# M13 — Documentation and names in production

Detail for the M13 row in `plan_syntax.md` §20. Everything the Quick Documentation popup shows is
correct in a dev environment and mostly absent in a shipped one, and the two halves of that fail for
opposite reasons.

## Status

| § | Item | State |
|---|---|---|
| 25.1 | Parameter names from the class file | **done** — `ClassFileParameterNames`, read through the analysis classpath and then the running loader (which is what reaches the JDK's runtime image); `JavaSignatures` falls back to it when the unit does not declare the method. `-parameters` on `core` and `language` is the other half and is asserted, not trusted |
| 25.2 | The header transform | not started — built from two rules the engine already encodes |
| 25.3 | The provider chain | partly built — `SourceArchives` is already the shape |
| 25.4 | Bundling our own sources | not started — packaging blocked on M12 |
| 25.5 | The JDK, fetched rather than bundled | not started — licence question first |
| 25.6 | Rendering the doc body | **done** — `EcjOptions` enables doc-comment support, `JavaDocs` renders the node, `JavaSignatures.documentationOf` finds it here or in the attached source and **inherits it for an override**. 8 tests. Plain text; the styled version is still §24.1`s `CgMarkupParser` call |

### The two findings this milestone is built on, both measured

**Parameter names survive compilation.** `java.util.ArrayList.add` carries `e` in its
`LocalVariableTable`; `java.lang.String.format` carries `format` and `args`; our own `core.jar` carries
`newContainerKind` today with no build change, because Gradle passes `-g` by default. So the headline
benefit of source attachment — real parameter names — is reachable in production **on a JRE, with
nothing shipped**. This was assumed impossible for a full session on the grounds that `src.zip` is a JDK
artefact, which is true and was the wrong place to be looking.

**Javadoc does not survive compilation, and there is no attribute that carries it.** So prose is
categorically different: every route to it is "ship something or fetch something", and no amount of
cleverness with bytecode changes that. The two halves of this milestone are therefore priced completely
differently and should not be scheduled as one thing.

### What is NOT a problem here

`net.minecraft`. M12 already ships SRG↔MCP mapping data and names `params.csv` for parameter names in
its own row — and Parchment carries **javadoc** alongside the names for 1.20.x. So Minecraft's names and
docs arrive with work that is already scheduled for a different reason. The only open question there is
licence terms, which differ sharply between MCP and Parchment and are M12's to settle.

---

## 25.1 Parameter names from the class file

**No shipped artifact, no build change, and the insertion point already exists.**
`JavaSignatures.parameterNames(IMethodBinding)` returns null for exactly the symbols this would fill —
it was written to answer only for the unit being analysed, and the classpath case has been falling
through to types-only ever since.

Read the declaring class's bytes and take names from `MethodParameters` if present, else from
`LocalVariableTable`. **ASM is already an `api` dependency of `language/`** (`asm`, `asm-commons`,
`asm-tree`), so this adds nothing to the classpath.

| | concrete methods | abstract / interface |
|---|---|---|
| JDK | ✅ verified (`ArrayList.add` → `e`, `String.format` → `format`, `args`) | ❌ `java.util.List` has neither attribute — verified, zero `MethodParameters` in it |
| Ours | ✅ verified in `core.jar` today | ✅ — `-parameters` is on `core` and `language`, and `Resolver.resolveAt` names `offset`/`answer` in a test that fails without it |
| Mods and libraries built normally | ✅ | ❌ |
| Obfuscated MC, ProGuard'd mods | ❌ stripped | ❌ |

**An abstract method has no `Code` attribute, so it has no `LocalVariableTable`.** That is the whole
shape of the gap, and it matters more than the table suggests: idiomatic Java declares variables as the
interface, so `List.add`, `Map.put`, `Collection.stream` and `Comparator.compare` are exactly the hovers
a reader performs most.

**✅ Built.** The reader is `ClassFileParameterNames` in `language.java.classpath`; the insertion point
was where the plan said it was. Three notes from building it:

- **All three traps were real and all three were measured on the running JDK before being coded against.**
  `String.format` is the static case and its slot 0 is `format`; `ArrayList.add(int, Object)` reports
  `[this, index, element, s, elementData]`, so the trailing locals are not parameters; and a parameter
  after a `long` sits at slot 3.
- **Ambiguity answers null.** Two same-arity overloads this cannot separate give types-only — which is
  exactly what the caller did before — because a signature wearing another overload's names reads as
  authoritative.
- **It inverted an existing test, and the inversion is the point.**
  `aSymbolWithNoAttachedSourceIsAssembledInstead` used the *missing* parameter name as "the one
  difference visible from outside" between the assembled and quoted paths. That premise is the one this
  section corrects, so it is no longer a boundary marker; what still separates the two is the author's
  layout and, later, their javadoc. Its twin inverted the same way when `AttachedSources` landed.

**`-parameters` on `core`, `platform` and `language` is the other half, and only for our own code.**
`MethodParameters` needs no `Code` attribute, so it is the one mechanism that names an *interface*
method's parameters. For an SPI-heavy engine that is most of the interesting surface — `CgUiDrawable.draw`,
`SourceAnalyzer.analyze`, every service seam. One compiler flag, roughly 1% class-file growth.

### Three traps, each silent

- **Slot 0 is `this` for an instance method and the FIRST PARAMETER for a static one.** `String.format`
  is the static case and its slot 0 is `format`. Getting this wrong shifts every name by one and
  produces a signature that is plausible and wrong.
- **`long` and `double` occupy two slots each.** A method taking `(long, String)` has its second
  parameter at slot 3, not 2.
- **Matching the right overload needs the erased descriptor**, and `IMethodBinding.getKey()` already
  contains one — likely cheaper and more reliable than rebuilding a descriptor from `ITypeBinding`s,
  where generics erasure, inner-class synthetic parameters and varargs each have an edge case.

---

## 25.2 The header transform — one build step, every producer

**Strip method bodies; keep declarations, javadoc, and literal field initializers.**

The output is **still valid Java source**, which is the entire point: it flows through `SourceArchives`
and `AttachedSources` unchanged. No new format, no new parser, no second reader, nothing downstream
knows it happened.

And both rules already exist in the engine:

| Rule | Already implemented as |
|---|---|
| cut at the body brace | `JavaSignatures.quotedHeaderOf` |
| keep a literal initializer, drop an expression | `JavaSignatures.isValue` |

So this is a build-time application of decisions the engine has already made, not new judgement. That is
what makes it safe to apply to somebody else's source tree.

**Used where size or licence demands it, skipped where they do not.** Our own sources are small enough to
ship whole, and shipping them whole is *better* — full bodies mean the quoted declaration keeps the
author's real layout. The transform is what makes the JDK viable at all.

---

## 25.3 The provider chain — four producers, one seam

These are not four features. They are four inputs to the question `SourceArchives` already asks:
*where is the source for this type?*

| Producer | Route | Ships? |
|---|---|---|
| Ours | bundled in the mod jar, full sources | 2.8 MB |
| JDK | the player's own `src.zip` if present, else a fetched extract | nothing |
| Minecraft | Parchment/MCP mapping data | M12's, already scheduled |
| Third-party | whatever `-sources.jar` is beside a jar | nothing |

Precedence: **a real file on disk beats a bundled snapshot.** In a dev workspace the working tree and any
`-sources.jar` should win, with the shipped copy as the production fallback — so the resource-backed
producer goes *last* in `SourceArchives.discover`. Namespaces do not collide in practice, but the default
should still be that the more specific artefact wins.

---

## 25.4 Bundling our own sources

**Loose `.java` entries, not a zip inside the jar:**

```
crystalgui-1.0.jar
└── assets/crystalgui/sources/
    ├── com/crystalgui/text/lang/SymbolInfo.java
    └── … 888 files, ~2.8 MB
```

**Why not a nested `sources.zip`.** `ZipFile` requires a real file on disk and cannot open an archive
nested inside another. `ZipInputStream` works over `getResourceAsStream` but is strictly *sequential* —
no lookup by name — so each hover would decompress entries until it found the one it wanted. The only way
out is extracting to disk on first run, which buys a temp file, a write, and a staleness question at
every mod update.

Loose entries give random access **for free**, because the JVM indexed the jar's central directory when
the loader opened it. One `getResourceAsStream`, no scan, no extraction, no temp file.

**The new `Archive` kind is simpler than the existing one.** The file-backed archive has to open a zip,
walk every entry, build a package-path index, and do it lazily precisely because that is expensive. A
resource-backed one needs none of it: the lookup either answers or it does not, because the classloader
already did the indexing.

**Packaging is blocked on M12.** The Gradle side is one block — `from(sourceSets.main.allJava) { into(…) }` —
but it belongs on the **shadowJar** of each loader module, since that is what actually ships and every
bundled module must be named there explicitly rather than inherited. Both loader modules are commented out
of the build today. The *read path* does not have to wait: putting the sources into `core.jar` exercises
the identical code path in the harness, so it can be built, tested and pinned now and simply gains a second
producer later.

---

## 25.5 The JDK — fetched, not bundled

**Not a size problem.** A header+doc extract of the packages a script author actually touches
(`java.lang`, `util`, `io`, `nio`, `time`, `text`, `math`, `net` — 1256 files, 23.9 MB of source) zips to
**≤ 4.2 MB**, against 42.9 MB for the whole `src.zip`. And that 4.2 MB is an *upper bound*: the measuring
strip kept any line ending in `;`, which means it kept body statements too.

Against a jar that already carries **~42 MB of engines** — bands 8, 11 and 17 at 12, 13 and 17 MB, all
three shipped because the band is chosen at runtime from the host's Java version — 4.2 MB is under 10%.

**It is a licence problem.** OpenJDK source is GPLv2 with Classpath Exception. The CE covers *linking*,
not redistributing a derived extract, and a body-stripped transform is arguably a derivative work.
There is no LICENSE file in this repository at all today, so "GPL-compatible" is not established.

**So fetch it.** `plan_m11.md` §24.1 already lists **"Download documentation" for a library with no
sources attached** in the popup's kebab menu — the affordance exists in the design and is IntelliJ's own
model. Nothing GPL-derived in the jar, nothing added to its size, and it works offline after one fetch.

The chain degrades cleanly:

1. the player has a JDK → read their `src.zip` directly, costs nothing
2. else → the downloaded extract, cached under the game directory
3. else → bytecode parameter names for concrete methods (§25.1), and no prose

---

## 25.6 Rendering the body

`SymbolInfo.documentation` has **never been populated** — by any engine, in dev or production. The popup
is a declaration and a location and nothing else. `plan_m11.md` §24.1 called this out and deliberately
shipped without it: *"the popup ships useful on day one and gets a body when the ECJ side learns to read
`Javadoc` nodes off the AST."* This is that.

**ECJ's doc-comment support is off today.** `BodyDeclaration.getJavadoc()` answers null without
`org.eclipse.jdt.core.compiler.doc.comment.support = enabled`, and `JavaSignatures.quotedFragment` already
records the consequence in its own comment — it skips leading comments by *scanning the text* rather than
asking the API, because the node covered the comment while the accessor denied it existed. One line in
`EcjOptions`.

**`{@inheritDoc}` is the trap that would make a first version look broken.** An overriding method usually
carries `@Override` and no doc of its own — `Message.toString()` in the shipped fixture is exactly this
shape. Without walking to the supertype's declaration for the text, a large fraction of methods render an
empty body, which reads as the feature not working rather than as the method having no doc.

**Javadoc is not plain text**: `{@link}`, `{@code}`, `@param`, `@return`, embedded HTML, entities.
§24.1 already picked the tool and said why — `CgMarkupParser` is right for the *body* and wrong for the
*signature*, because it produces `CgStyledText` directly and so enters the pipeline downstream of the
cascade, where its colours are baked and a scheme switch cannot reach them. That reasoning is unchanged:
the body is prose and may be baked; the signature is code and may not.

---

## Sizes, measured

| | |
|---|---|
| engines already shipped (bands 8 + 11 + 17) | **42 MB** |
| our whole source tree (888 files, 8.2 MB raw) | **2.8 MB** zipped |
| javadoc as a share of our own source | **41%** — unusually high, so the payoff per shipped MB is too |
| JDK header+doc extract, 8 packages, 1256 files | **≤ 4.2 MB** zipped (upper bound) |
| full JDK `src.zip`, for contrast | 42.9 MB |

---

## Exit criteria

- Hovering a **concrete** classpath method shows its real parameter names with **no source attached** —
  asserted with `src.zip` deliberately out of reach, since that is the production shape and a dev run
  cannot tell the two apart.
- Hovering one of **our own interface** methods shows its parameter names, which only `-parameters` can
  deliver.
- The header transform's output **parses and quotes identically** to the source it came from, for a
  fixture containing a record, a sealed interface and a generic method with bounds.
- Our sources resolve **out of the mod jar** through the same `SourceArchives` chain, with a real
  `-sources.jar` taking precedence over the bundled copy where both exist.
- A javadoc body renders for a symbol with a doc comment, and for an `@Override` with none via
  `{@inheritDoc}`.
- The JDK producer is absent from the jar; with it fetched, `List.add` shows both its parameter name and
  its prose.

## Order

**25.1 first, alone.** It needs no artifact, no build change, no licence decision and no packaging, and it
delivers the single most visible production improvement on its own — a hover that names its arguments.
Everything else in this milestone is gated on a decision; this is gated on nothing.

Then **25.6's one-line flag plus 25.2**, because together they make our own sources *worth* shipping —
without doc-comment support there is no prose to ship, and without the transform there is no producer for
anything but us.

Then **25.4**, which can be built and tested against `core.jar` today and only needs M12 for the real
packaging. **25.5 last**, because it is the only part that requires a licence decision and a network
path, and because §25.1 will already have taken most of the sting out of a source-less JDK.
