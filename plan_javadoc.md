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

## 3. The markup layer — the shape of it

### 3.1 The seam has to change

`SymbolInfo.documentation` is a `String` and the popup draws it into one `UIText`. Neither can carry
a code block. Something structured has to cross, and it must obey the rules the seam already has:
`core/` may not know what Java is, the child side of the engine bridge may name only JDK types and
`com.crystalgui.text.*`, and every engine has to be able to produce it — JavaScript's JSDoc renders
the same shapes.

The candidate is a small document model in `com.crystalgui.text.lang` — a list of blocks, each a
paragraph, a code block, or a list, with inline runs carrying an optional style (plain, code, link).
Deliberately **not** an HTML DOM: the popup does not need one, and a general tree invites a general
renderer.

### 3.2 What has to be parsed

Javadoc's HTML is a small, badly-specified subset in practice. The tags that actually appear in the
JDK and in real code:

- Block: `<p>`, `<pre>`, `<ul>`/`<ol>`/`<li>`, `<blockquote>`, `<h1>`–`<h6>`, `<table>` (rare)
- Inline: `<code>`, `<tt>`, `<b>`/`<strong>`, `<i>`/`<em>`, `<a href>`, `<br>`
- Entities: `&lt;`, `&gt;`, `&amp;`, `&nbsp;`, `&#NN;`

Plus the Javadoc tags `JavaDocs` already resolves: `{@code}`, `{@literal}`, `{@link}`,
`{@linkplain}`, `{@value}`, and the block tags `@param`, `@return`, `@throws`, `@see`, `@since`,
`@deprecated`.

**`<pre>` is the one that must not be normalised.** Whitespace inside it is content; everywhere else
it is not. That is the whole reason `collapse()` cannot stay as a final pass over the output.

### 3.3 Syntax colouring inside a code block

The popup already draws coloured code — the signature band uses the editor's capture scheme through
`.__syntax__`. A doc code block is the same problem with a different source, so the colouring should
come from the same tokenizer rather than a second one. That is a real constraint on where the model
is built: the engine knows the language, `core/` does not.

### 3.4 What is deliberately out of scope

- `<table>` beyond the simplest two-column form. It appears in a handful of JDK classes and a table
  layout in a hover popup is a project of its own.
- Images. Javadoc supports `<img>`; a popup that fetches from disk to render a hover is not worth it.
- Arbitrary HTML. Anything unrecognised degrades to its text, which is what stripping already does
  and is the right failure.

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
