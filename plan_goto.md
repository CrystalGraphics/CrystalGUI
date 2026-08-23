# Search Everywhere — Research, and What We Should Build

**Milestone**: `plan_navigation.md` N1
**Status**: **CLOSED.** G1 + G2 shipped — one merged Go to File over workspace **and** classpath, over
a streaming source contract. **G3 and G4 are declined**, not pending: the user called the result
"everything I wanted and more" and stopped here. See §10.

> The request was "let's start with Go to File". The screenshots are **Search Everywhere** — Go to File
> is one *tab* inside it. That distinction shapes everything below, because the popup is not a file
> picker with extras: it is a merge of independent providers, and the file picker is one of them.

Every claim below is marked by how it was established:

| Mark | Means |
|---|---|
| **[src]** | Read out of `JetBrains/intellij-community@master` this session |
| **[doc]** | jetbrains.com/help |
| **[shot]** | Read off the two screenshots supplied |
| **[known]** | Prior knowledge, not verified this session — treat as a lead, not a fact |

---

## 1. Invocation

| Opens on tab | Shortcut | |
|---|---|---|
| All | **Shift, Shift** | [doc] |
| Classes | Ctrl+N | [doc] |
| Files | Ctrl+Shift+N | [doc] |
| Symbols | Ctrl+Alt+Shift+N | [doc] |
| Actions | Ctrl+Shift+A | [doc] |

One popup, six tabs, five entry points. **Tab / Shift+Tab cycles tabs** while it is open [doc].
Double-Shift *again*, while open, toggles "Include non-project items" [doc] — the same gesture that
opened it widens it.

That last one is worth copying and worth noting as a trap: it means the opening gesture has to be
debounced against *itself*, and a stray double-Shift while typing must not silently change scope.

---

## 2. The contributor model — what the "All" tab actually is

**[src]** `SearchEverywhereContributor`, EP `com.intellij.searchEverywhereContributor`. The methods
that matter:

| Method | Role |
|---|---|
| `getSearchProviderId()` | Identity |
| `getGroupName()` / `getFullGroupName()` | Section heading, and the filter's label |
| `getSortWeight()` | **Where this provider's block sits relative to the others** |
| `isShownInSeparateTab()` | Whether it earns a tab of its own (default `false`) |
| `isEmptyPatternSupported()` | Whether it answers an empty query at all |
| `isDumbAware()` | Whether it answers while indexing (default `true`) |
| `fetchElements(pattern, indicator, consumer)` | Streams results to a consumer until cancelled |
| `processSelectedItem(item, modifiers, searchText)` | Acts on Enter; `modifiers` is how Shift+Enter differs |
| `getElementsRenderer()` | The provider draws its own rows |
| `showInFindResults()` | Whether "open in Find window" is offered |
| `getAdvertisement()` | The grey hint in the search field |

The real sort weights, read from source **[src]**:

```
CalculatorSEContributor          0
TopHitSEContributor             50
RecentFilesSEContributor        70
ClassSearchEverywhereContributor       100
FileSearchEverywhereContributor        200
RunConfigurationsSEContributor         350
ActionSearchEverywhereContributor      400
```

So the intended order is **calculator → top hit → recent files → classes → files → run configs →
actions**. Symbols lives outside that directory and was not read; by weight it must sit between files
and run configurations.

Three structural facts fall out, and all three are things a first attempt gets wrong:

1. **`fetchElements` is a stream, not a list.** A provider pushes results into a consumer and is
   cancelled by an indicator. The popup renders partial results and keeps going.
2. **Weights order *groups*, not *rows*.** Within a group, ranking is by match quality
   (`WeightedSearchEverywhereContributor` returns `FoundItemDescriptor<T>` = item + score, and the
   list model sorts descending) **[src]**.
3. **Results are deduplicated across providers.** There is a whole family for it —
   `SEResultsEqualityProvider`, `PsiElementsEqualityProvider`, `ActionsEqualityProvider`,
   `OptionEqualityProvider` **[src]**. Without it the same class arrives from the class provider and
   the file provider and appears twice.

> **[shot]** In the modern UI the All tab is a *blended* list, not stacked group blocks: screenshot 1
> interleaves `.java` files, a `.class`, and a markdown file with no visible section headings, ending
> in a single `... more`. Weights still decide the base order; ML re-ranking (`SearchEverywhereMlService`)
> blends on top. **We are not doing ML re-ranking** — but we should know the flat look comes from it,
> so a grouped list is a deliberate divergence rather than an oversight.

---

## 3. Query syntax — the part worth stealing verbatim

**[src]** `AbstractGotoSEContributor.kt`. A trailing location is stripped from the pattern before
matching, and the regex is more generous than anyone would guess:

```java
Pattern.compile(
  "(.+?)" +                                                  // name, non-greedy
  "(?::|@|,| |#|#L|\\?l=| on line | at line |:line |:?\\(|:?\\[)" +  // separator
  "(\\d+)?(?:\\W(\\d+)?)?" +                                 // line + column
  "[)\\]]?"                                                  // possible closing paren/brace
)
```

Guarded by a cheap pre-test so the regex almost never runs:

```kotlin
if (StringUtil.containsAnyChar(pattern, ":,;@[( #") ||
    pattern.contains(" line ") || pattern.contains("?l=")) { … }
```

So **all of these navigate to `Foo.java` line 42**: `Foo:42`, `Foo@42`, `Foo,42`, `Foo 42`, `Foo#42`,
`Foo#L42`, `Foo?l=42`, `Foo on line 42`, `Foo at line 42`, `Foo:line 42`, `Foo(42`, `Foo[42`,
`Foo(42:8)`. Column is the optional second group.

That list is not whimsy — it is every shape a file:line appears in when **pasted from somewhere
else**: a stack trace (`Foo.java:42`), a compiler message (`Foo.java(42,8)`), a GitHub URL
(`Foo.java?l=42`, `#L42`), a log line. The feature is "paste anything and it works".

Also **[src]**: `patternToDetectAnonymousClasses = ([.\w]+)((\$\d+)*(\$)?)` — so `Foo$1` resolves to
its enclosing class. We already made the same decision in the viewer (`plan_viewer.md` §1.9).

Other syntax:
- `*` **prefix wildcard** — `*ell` matches `HelloWorld.html` [doc/web]
- **CamelCase** — `HW` matches `HelloWorld.html`; `CgText` matches `CgTextRenderer` [doc, **[shot]**]
- `/` **prefix commands** — `/plugins`, `/appearance`, `/registry`, `/inspections`, `/intentions`,
  `/templates`, `/vcs` [doc]. The field advertises this: **[shot]** shows `Type / to see commands`
  right-aligned in the field.
- **Arithmetic** — `2^10`, `sqrt(2)` evaluate inline; that is `CalculatorSEContributor`, weight 0,
  i.e. always first when it matches [doc/src].

---

## 4. Matching and ranking

**[known/web]** `NameUtil.buildMatcher()` → `MinusculeMatcher`. Scores on three axes: **quality**
(matches at camel-hump and word-start boundaries beat mid-word), **position** (start beats middle),
and **gap size** (contiguous beats scattered). Results sorted by weight, descending.

This is materially the same model as our `SearchMatcher`, whose own javadoc already records the
tier-not-score rule — `AGENTS.md` states it twice: *"Rank by the match TIER, never by
`SearchMatch.score()`"*, because the score folds brevity in and `pr` then ranks a class `Printer` above
a local `precision`. **Our matcher is already the right shape; nothing here argues for changing it.**

---

## 5. Row anatomy — read off the screenshots

**[shot]** Left to right, one row:

```
[kind icon]  Name.java   secondary text                    ModuleName [module icon]
     ▲          ▲             ▲                                  ▲
  16px glyph  matched     package or path, dimmed          right-aligned, dimmed
              chars in
              amber
```

- **Kind icon** is the *symbol* kind, not the file type — screenshot 2 shows distinct glyphs for
  class (`C`), interface, enum (`E`), annotation (`@`), and a green-dot variant for test classes.
  We have this: `SymbolIcon`, and `TypeIndex.kindOf` answers without an engine call.
- **Matched characters are highlighted in-place** — screenshot 2 lights `CgText` amber inside
  `CgTextRenderer.java`. We have this: `SearchMatch.ranges` plus the `::highlight()` band
  `TreeSearch` already uses.
- **Secondary text varies by result kind, in the same list.** `of com.crystalgraphics.api.text` for a
  class; a full source path for a file; an absolute path plus a jar coordinate for a library entry.
  It is the provider's renderer, not one format.
- **Right column is the module** — `CrystalGUI.core.main`, `CrystalGUI.core.test`,
  `CrystalGUI.gl-debug-harness.main`, `< ms-21 (2) > (src.zip)`. Nearest thing we have is the
  `CgPath` project id.

### Row background colours are a real feature, not theming noise

**[shot]** Screenshot 2 tints test rows green; screenshot 1 tints library rows tan/brown.
**[known]** That is **File Colors** (Settings ▸ Appearance ▸ File Colors) over named *scopes*, with
`Tests` green and `Non-Project Files` tan by default — the same mechanism that tints an editor tab.

We shipped exactly this for viewer tabs last session (`.decoration-library`, `#281F1C`), and it is the
same colour family. **So the tint belongs to the scope, not to the widget** — which means a search row
and a tab should read it from one place, or they will drift.

---

## 6. Chrome

**[shot]**, top to bottom:

1. **Tab strip** — `All · Classes · Files · Symbols · Actions · Text`, left-aligned.
2. **Right of the strip**: `☐ Include non-project items` with an `n` mnemonic (Alt+N) [doc], then
   three icon buttons. **[doc]** names two of them: *open in Find tool window* (disabled on the
   Actions tab) and *filter* (narrows by type). The third is unidentified — `PreviewAction.kt` exists
   in the source directory, so a preview toggle is the likely candidate, unverified.
3. **Search field** — a magnifier with a chevron on the left (history [known]), the advertisement text
   right-aligned.
4. **Results list**, ending in `... more` per exhausted group. **[doc]** Ctrl+Down jumps to the
   `more` items, Ctrl+Up returns to the top.
5. **Footer** — full path of the selected row on the left; **`Open In Right Split`** as an action link
   on the right.

**The popup is draggable and resizable, and remembers both.** Stated by the user; the help pages do
not document it either way.

Other behaviours worth naming:

- **Enter opens.** Shift+Enter opens in right split [known — the footer link is present in **[shot]**
  but its shortcut is not shown]. `processSelectedItem` receiving `modifiers` **[src]** is the
  mechanism, so however it is bound, the provider decides.
- **`ScopeChooserAction`** exists **[src]** — the scope is a chooser, not only a checkbox, in tabs that
  support it (`ScopeSupporting`).
- **An empty query lists something**, per `isEmptyPatternSupported` **[src]** — recent files in the
  Files tab, frequently-used in Actions. **[shot]** Screenshot 1 has an empty field and thirteen rows.

---

## 7. What we already have

| Need | Have | Gap |
|---|---|---|
| Popup shell, list, keyboard nav | `QuickPick` (506 lines) | Not resizable/draggable; no tabs; no footer |
| Row model | `QuickPickItem` — id, label, category, accelerator, enabled | No icon, no per-row colour, no second dim column |
| Provider seam | `QuickPickSource.query(SearchQuery) → List<QuickPickEntry>` | **Synchronous.** No streaming, no cancellation |
| Matching + ranking + highlight ranges | `SearchMatcher`, `SearchMatch.ranges`, tiered | — |
| Match highlighting in a row | `TreeSearch` marks characters via `::highlight()` | Not wired into `QuickPick` |
| Kind icons | `SymbolIcon`, `TypeIndex.kindOf` | Not wired into `QuickPick` |
| Files source | `GoToFile` (76 lines) over the workspace | Project files only |
| Classes source | `TypeIndex.matching(prefix)` | **`core/` cannot see it** — needs the SPI from `plan_navigation.md` §2.2 |
| Actions source | `CommandPalette` (124 lines) over `CommandRegistry` | Already exists as its own popup |
| Open a library class | `Workbench.openResource(library:…)` | — |
| Scope tint | `.decoration-library` in `ua/widgets.css` | Keyed to tabs, not to a scope concept |

### The three real gaps

1. **`QuickPickSource` is synchronous.** Its own javadoc anticipates this — *"the files live on the
   server, the query goes over RPC, and the answer arrives later"* — and then returns a `List`.
   `fetchElements(pattern, indicator, consumer)` is the shape that survives; ours is not. Changing it
   later means changing every source and the widget together.
2. **`QuickPick` has one source.** Multiple providers means merge order (weights), per-group caps,
   `... more`, and **dedup across providers**, which IntelliJ needed four classes for.
3. **A row cannot draw what these rows draw** — no icon, no dim second column, no scope tint, no
   in-place match highlight.

---

## 8. What I would actually build

The value is in **reaching a class you do not have open**, and that needs almost none of the chrome.

### G1 — Go to File, over the workspace and the classpath — **SHIPPED**

`TypeIndex.matching()` behind `text.lang.TypeSearch` + `TypeSearchRegistry` (the core-side SPI, since
`core/` may never name the index); `ClasspathTypeSearch` answers it from `language/`, registered from
`JavaLanguage.register()`. Rows gained a `description` (package or folder), a `SymbolKind` glyph via
`SymbolIcon`, and an `iconName` for files via `FileIconTheme` — so one list holds both without reading as
two lists glued together. Ids are stringified `Resource`s, so a row says which kind of thing it is.
`QueryLocation` ports §3's regex verbatim; `Workbench.openFileAt` / `openResourceAt` are the two
destinations, and `routeDefinitionsOf` now delegates to both rather than carrying its own copies.

**Shipped**: `main` lists the workspace's own files first and the classpath behind them, each half ranked
by quality, kind glyphs on types and file icons on files, the query lit inside each name, and
`Main.java:42` opening at line 42.

### G2 — Streaming sources — **SHIPPED**

`QuickPickSource.fetch(query, ResultSink)` replaces `query(SearchQuery) → List`. The sink can refuse a
row, report cancellation, and be told there was more; `drain` is the synchronous collector today's only
consumer uses, and an async source can hold its sink and push later without the widget noticing. Changed
while there were four implementations rather than a dozen, which was the whole argument for doing it here.

**Truncation came with it**, because the sink is where a cap lives. `QuickPick` caps at 100 and shows
`100+ matches` in the header; `GoToFile` caps each half at 50 first, since every project file outranks
every classpath type and one shared cap is spent on files before a type is ever offered.

> **The non-obvious part**: a sink cannot infer truncation from refusing a row, because a well-behaved
> source stops the moment it is refused — so a source with exactly `limit` rows and one with ten thousand
> look identical. The limit-th row is therefore accepted with a `true`, the source offers one more, and
> *that* refusal is the evidence. Fetch-`n+1`-to-know-there-is-a-next-page, and the first version without
> it reported nothing at all.

### G3 — The rest of the merge, and the tabs

`... more` as a row that loads the next page, rather than G2's header count — which says there is more
and gives no way to reach it. Tabs as filters over the same merged model — `All`, `Files`, `Classes`,
`Actions` — which is what folds `CommandPalette` in as well. Per-group caps and dedup across providers
already exist (G2, and `TypeSearchRegistry` by qualified name).

### G4 — Chrome

Resizable, draggable, remembered geometry. Footer with the selected path and Open in Split. Scope
toggle. Row tint from a shared scope notion.

### Cut

| Cut | Why |
|---|---|
| ML re-ranking | A model, telemetry and a training pipeline for a list of forty rows |
| The `Text` tab (find in path) | A content index, not a name index. `plan_navigation.md` §5 already cut it |
| `/` commands | Genuinely nice, and it is a second command language layered on a search field. After G3 |
| Inline arithmetic | Charming, and unrelated to navigation |
| Preview pane | Doubles the popup and needs an editor instance per row |
| `Symbols` tab | Needs a member-level index; `TypeIndex` is types only |
| Open in Find tool window | We have no Find tool window |

---

## 9. Decisions

Taken rather than asked, at the user's direction ("any case will do").

**1. `QuickPick` stays the widget — and there is no separate Go to Class at all.** *(Revised during
G1, at the user's direction: "I don't want a separate Go To Class different from the Go To File. Merge
both under Go To File. They should be one of the same.")* The first answer here was a sibling picker now
and a merge in G3. That was worse and the reason is plain once stated: two popups that look identical and
behave differently make you decide, before you start typing, whether the thing you want is in the
workspace — which is the thing you are searching to find out. **`GoToFile` now lists both**, ranked by one
matcher, and `Mod+P` / `Mod+T` / `Mod+Shift+T` are three doors into it, exactly as IntelliJ's `Ctrl+N` and
`Ctrl+Shift+N` are two doors into one window.

**1b. Project files are a PARTITION above classpath types, not a tie-break.** Learned from the running
build. A class is `ArrayList` and its file is `ArrayList.java`, so typing the name is an *exact* hit on the
class and a mere prefix on the file — the type wins on match quality outright and any tie-break beneath
the score is never consulted. Typing `main` listed ten `Main` classes from `com.sun.tools` above the
workspace's own, with the weights already set the "right" way round and doing nothing. So the group is the
primary sort key and quality orders each half. That is also IntelliJ's model: it does not blend non-project
items into the ranking either, it gates them behind "Include non-project items" and appends them.

**2. The scope tint has one definition, and it already exists.** `Workbench.LIBRARY_DECORATION` plus
the `decoration-*` class swap the file tree and the dock tab both use. Search rows read the same
predicate when they gain a tint — **in G4, not G1**: in a class picker nearly every row is on the
classpath, so a library tint would be close to universal and say nothing.

**3. The right-hand column is deferred; the package goes beside the name.** — screenshot 2's dominant
form is `CgTextAlign  of com.crystalgraphics.api.text`, dim and **unhighlighted**. So G1 adds a
`description` to a row and no third column, and needs no `descriptionRanges` on `QuickPickEntry`. The
module/jar column is G4, where `TypeIndex.Entry.container()` already has the answer.

**4. A chord, not double-Shift.** `Mod+T` (VS Code's Go to Symbol in Workspace) with `Mod+Shift+T`
(Eclipse's Open Type) as the alias — the same both-idioms convention `ChromeCommands` uses for the
palette. IntelliJ's `Mod+N` is taken by New File and `Mod+P` by Go to File. Double-tap needs a timing
threshold *and* has to not fire mid-typing; a chord has neither problem, and the gesture can be added
later without moving anything.

**5. Yes, the location suffix ships in G1.** One regex and one `containsAnyChar` guard, ported from
`AbstractGotoSEContributor` (§3), and `openResource(resource, onOpened)` already has the callback to
reveal a position once the text has landed. It is what makes a pasted stack-trace line work.

---

## 10. Why this stops at G2

G3 (tabs, a loadable "… more") and G4 (remembered geometry, a footer, a scope toggle) are **declined
rather than deferred**, which is a different thing and worth writing down so a later reader does not
treat them as a backlog.

What shipped answers the question the plan opened with — *reach code you do not own* — and the two
milestones left are both about a **fuller Search Everywhere**, not about reaching anything new. Tabs
partition a list that is already partitioned and already capped; a footer restates the path the row
already shows; remembered geometry is a preference store for a popup that reopens centred at the size you
left it.

Two pieces of G3/G4 landed early because they were load-bearing rather than polish, and their absence
would have been felt: **drag and resize** came in G1 (a popup with no chrome has nowhere to grab), and
**per-group caps** came in G2 (one shared cap is spent on files before a type is ever offered). What is
left is the part that is genuinely optional.

The one thing here a future reader might legitimately want is `... more` as a **loadable row**: G2's
header count says there is more and offers no way to reach it. Narrowing the query is the answer today,
and it is the answer both references expect too — but if this is ever reopened, that is the row to build.

---

## Sources

- [Search everywhere — JetBrains IDEA help](https://www.jetbrains.com/help/idea/searching-everywhere.html)
- [`SearchEverywhereContributor.java`](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-api/src/com/intellij/ide/actions/searcheverywhere/SearchEverywhereContributor.java)
- [`searcheverywhere/` package](https://github.com/JetBrains/intellij-community/tree/master/platform/lang-impl/src/com/intellij/ide/actions/searcheverywhere) — `AbstractGotoSEContributor.kt`, the contributors, `SEResultsEqualityProvider`, `ScopeChooserAction`, `PreviewAction`
- [Search Everywhere ML — DeepWiki](https://deepwiki.com/JetBrains/intellij-community/8.3-search-everywhere-ml)
- [`MinusculeMatcher`](https://dploeger.github.io/intellij-api-doc/com/intellij/psi/codeStyle/MinusculeMatcher.html) · [`NameUtil`](https://dploeger.github.io/intellij-api-doc/com/intellij/psi/codeStyle/NameUtil.html)
- [Go to File search pattern — JetBrains support](https://intellij-support.jetbrains.com/hc/en-us/community/posts/206271389-Go-to-File-search-pattern)
- [New Search Everywhere API — JetBrains Platform blog](https://blog.jetbrains.com/platform/2025/12/major-architectural-update-introducing-the-new-search-everywhere-api-built-for-remote-development/)
