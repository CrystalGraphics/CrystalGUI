# Javadoc Rendering — From Stripped Text to Marked-Up Documentation

*The dedicated plan for what the documentation popup shows: the parity audit against IntelliJ, the
markup layer that has to exist for the body to look like documentation rather than a wall of prose,
and the order the work is worth doing in. Sibling of `plan_syntax.md`, scoped to the popup's
content.*

*Reference target: IntelliJ's Quick Documentation popup, deliberately, with VS Code's hover as the
second opinion where the two disagree. Both render Javadoc's HTML; neither strips it.*

---

## 0. Where this came from

A side-by-side of our popup and IntelliJ's on `java.lang.String`. The two screenshots are the whole
brief: same header, same separator, same first signature line — and then ours becomes an
undifferentiated block of prose where IntelliJ has paragraphs, boxed code samples with syntax
colouring, and tinted inline code.

**The finding that reframed half the list:** our signature is not badly wrapped, it is *quoted*.
JDK 17's `String.java` reads

```java
public final class String
    implements java.io.Serializable, Comparable<String>, CharSequence,
               Constable, ConstantDesc {
```

— the same mid-list wrap, the same unqualified `Constable, ConstantDesc`. `JavaSignatures` prefers a
source quote and never re-wraps it, which is a deliberate rule (`AttachedSources`, and the
`MAX_SIGNATURE_LINE` note). IntelliJ does not quote: it renders a normalised declaration from the
PSI, one interface per line, everything outside `java.lang` fully qualified. Our own assembler
already does one-per-line — `appendSupertypes`'s `perLine` — it simply never runs when a quote is
available.

So the signature difference is a **policy** question, not a wrapping bug, and it is listed here as
one.

---

## 1. The parity audit

Numbered as they were reported, so the conversation and this file agree.

### A. The body — six symptoms, one cause

`JavaDocs.stripHtml` keeps `<p>`, `<br>` and `<li>` as bare newlines and drops every other tag;
`collapse()` then reduces runs of blank lines to at most one. `SymbolInfo.documentation` is declared
plain text, so there is nowhere for structure to survive even if the parser kept it.

| # | Gap | IntelliJ | Ours |
|---|---|---|---|
| 1 | Code blocks | `<pre>` becomes a panel with its own background | inlined into the prose — "For example: String str = "abc";" runs on as a sentence |
| 2 | Syntax colouring in doc code | `char`, `new`, `"abc"` coloured inside the panel | uniform body colour |
| 3 | Inline `{@code}` | tinted box around `String`, `"abc"` | indistinguishable from prose |
| 4 | Paragraphs | blank-line separated | one wall |
| 5 | Lists | bullet and indent | a bare newline |
| 6 | `{@link}` | coloured and clickable | plain subject text |

### B. The signature

| # | Gap | IntelliJ | Ours |
|---|---|---|---|
| 7 | Layout source | normalised from the PSI: one interface per line, qualified outside `java.lang` | the source quote, verbatim |
| 8 | Continuation alignment | each interface aligned under the first | inherits the source's own indent |

### C. Chrome and layout

| # | Gap | IntelliJ | Ours |
|---|---|---|---|
| 9 | Width | ~480px | **already capped at 441px** — measured, see §5. The screenshot predates it or was resized |
| 10 | Scrollbar | visible on the right | **already there** — the body caps at 220px under `overflow: auto`; see §5 |
| 11 | Footer | pencil + kebab, bottom right | `__doc-footer__` exists; not visible on a class hover |
| 12 | Prose vs code contrast | body dimmed slightly against code | uniform |
| 13 | Line spacing | looser prose | tighter |

---

## 2. Order of work

**Easy wins first (9, 10, 13)** — chrome only, no seam changes. Done in the commit that adds this
file, where 9 and 10 turned out to be already implemented and only 13 was real. See §5.

**Then the markup layer (1–6)** — one real piece of work, and the rest of this plan.

**Then the signature policy (7, 8)** and the small remaining chrome (11, 12).

---

## 3. How the references do it, and what may be ported

*Written from knowledge of these implementations rather than from reading their source: this repository
has no IntelliJ or VS Code checkout and the session had no network. Everything below that is a claim
about their code is marked as such, and the design does not depend on any of it being exact.*

### 3.1 IntelliJ

One class does the work: **`JavaDocInfoGenerator`** (`java-impl`). It walks the PSI doc comment and
*emits HTML into a StringBuilder* — `<p>`, `<pre>`, `<code>`, `<a href="psi_element://...">` — resolving
`{@code}`, `{@link}`, `{@literal}`, `{@value}` and the block tags as it goes. The result is handed to a
Swing HTML view (`JEditorPane` with a `StyledEditorKit`), which does the layout, the code-block
background and the link handling. `DocumentationHtmlUtil` supplies the stylesheet.

So IntelliJ does not parse the javadoc HTML at all in the general case: it **passes it through** into a
renderer that already speaks HTML, and only interprets the javadoc-specific tags. The HTML parsing is
Swing's.

### 3.2 VS Code

VS Code's Java hover is not VS Code's code. It is Red Hat's `vscode-java` talking to **Eclipse JDT
Language Server**, which converts the javadoc to **Markdown** (`JavaDoc2MarkdownConverter`, over
**jsoup** for the HTML) and sends that over LSP. VS Code then renders the Markdown with `marked` and its
own `MarkdownRenderer`, which is where the code fences get their background and their syntax colouring.

So VS Code's architecture is: *javadoc HTML → Markdown → structured render*. The middle step exists
because LSP's `MarkupContent` carries Markdown, not because Markdown is the better model.

### 3.3 What that means for us

**Neither passes an HTML string to a layout engine that speaks HTML, because we do not have one.** Our
popup lays out real elements, and a code block has to become an element with a background and coloured
spans. Both references end at a renderer that already does that for them; we have to produce the thing
their renderer consumes.

So the shape that fits is IntelliJ's *emitter* with VS Code's *structured target*: walk the doc comment,
resolve the javadoc tags, and emit into a small document model instead of into HTML or Markdown. That is
what `JavaDocs` already does — it emits into a `StringBuilder`. The change is the target, plus the HTML
subset it currently discards.

### 3.4 Licensing, which decides what "port" can mean

The repository already takes this seriously (`THIRD-PARTY.md`, `ui/icons/ATTRIBUTION.md`) and the answer
differs per reference:

| Source | Licence | What we may do |
|---|---|---|
| IntelliJ IDEA Community | **Apache 2.0** | Port, with the licence, a NOTICE and a statement of modifications. Precedent: the IntelliJ Platform file-type icons already ship this way |
| Eclipse JDT / JDT LS | **EPL 2.0** | Weak copyleft — a port makes *those files* EPL. A real decision, not a formality |
| VS Code / Monaco | MIT | Port freely with attribution — but its javadoc handling is not its own code, see §3.2 |
| jsoup (what JDT LS parses with) | MIT | Portable, but it is a full HTML5 parser and a dependency, not a snippet |
| **WHATWG HTML tokenizer** | **a specification** | Implementable by anyone; no licence encumbrance at all |

**The generic HTML layer therefore follows the WHATWG tokenizer's state machine**, whose states are named
in the spec and which is what jsoup, every browser and every serious parser implement. "Do not reinvent"
is satisfied by following the specified state machine rather than inventing an ad-hoc scanner — and it is
the only option here that is both a real reference and free of an obligation this repository has not
already taken on.

**The javadoc-specific half — which tag means what — follows IntelliJ**, which is Apache 2.0 and portable
with attribution if any of it is copied verbatim. In practice what transfers is the *rules*, and
`JavaDocs` already implements several of them.

### 3.5 The two pieces

**`com.crystalgui.text.markup`** — generic, reusable, and the thing this plan is really about. A
tokenizer over the HTML subset plus a small document model:

- `MarkupDocument` — a list of blocks
- `MarkupBlock` — paragraph, code, list, heading, quote
- `MarkupSpan` — a run of text with a style (plain, code, emphasis, strong, link) and an optional target

Deliberately **not** an HTML DOM. The popup does not need one, a general tree invites a general renderer,
and the model has to be producible by JSDoc and by a shader-graph node description too.

**`language/java/assist/JavaDocs`** — keeps owning what a javadoc *tag* means, and emits into the model
instead of into a string.

### 3.6 What has to be handled

Block: `<p>`, `<pre>`, `<ul>`/`<ol>`/`<li>`, `<blockquote>`, `<h1>`–`<h6>`.
Inline: `<code>`, `<tt>`, `<b>`/`<strong>`, `<i>`/`<em>`, `<a href>`, `<br>`.
Entities: named (`&lt;` `&gt;` `&amp;` `&nbsp;` `&quot;`) and numeric (`&#NN;`, `&#xNN;`).
Javadoc: `{@code}`, `{@literal}`, `{@link}`, `{@linkplain}`, `{@value}`, `{@inheritDoc}`, and the block
tags `@param`, `@return`, `@throws`, `@see`, `@since`, `@deprecated`.

**`<pre>` is the one that must not be normalised.** Whitespace inside it is content; everywhere else it
is collapsible. That is the whole reason `collapse()` cannot survive as a final pass over the output.

### 3.7 Deliberately out of scope

- `<table>` beyond the simplest two-column form. A table layout in a hover popup is a project of its own.
- Images. A popup that reads from disk to render a hover is not worth it.
- Arbitrary HTML. Anything unrecognised degrades to its text, which is what stripping already does and
  is the right failure.

---

## 4. Open questions

- Does the model cross the bridge as a value type, or does the engine render to a compact wire form
  the popup parses? The first is cleaner; the second keeps `com.crystalgui.text.lang` smaller.
- `{@inheritDoc}` — `JavaSignatures.documentationOf` already walks supertypes for a method with no
  comment of its own. Whether the tag is honoured *inside* a comment is a separate question.
- How much of this JavaScript gets for free. JSDoc uses Markdown rather than HTML, so the parser is
  not shared but the model is.

---

## 5. Log

**Chrome pass (9, 10, 13) — and two of the three were already done.**

Measured before changing anything, which is what stopped a "fix" that would have changed nothing. A
popup built with the real shape — a quoted three-line declaration beside a long body — lays out at
**441px wide**, with the body at 423px and its height capped at 220px under `overflow: auto`. That is
already IntelliJ's ~480, and `max-width: 440px` on `.__doc-body__` has been in the sheet since
`a141c00`, seventy-five commits before the screenshot.

So **9 and 10 are implemented and working**, and the screenshot shows either an older harness build or
a popup that had been dragged wider — `documentationpopup` carries `resize: both`, and a user drag
writes `width` at INLINE origin.

Worth recording as a trap rather than an embarrassment: the sheet's own comment on that rule explains
why the cap is on the CONTENT rather than the box (`max-width` on the popup made it unresizable past
the cap, because max-width is applied after width whatever origin the width came from). Anyone
reading the screenshot alone would have reached for exactly that and reintroduced it.

**13 was real.** The popup declares `line-height: 1.15` on itself so it reaches every band, and that
number was chosen for the DECLARATION — a short stack of code lines, where a generous line box reads
as looseness. Prose is the opposite case: a paragraph at 1.15 has no air in it and the eye loses its
place returning to the left margin. `.__doc-body__` now sets `1.45`, scoped so the declaration stays
tight.
