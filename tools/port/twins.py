"""
Adds a `::part(x)` twin beside every `tag .__x__` rule whose name is a ported widget's shadow part.

COMMENT-SAFE BY CONSTRUCTION, which the first version was not. CSS comments here routinely contain
commas, braces and even the word the selector starts with, so anything that splits the raw text is
wrong in a way that only shows up on the files with the most prose -- `overlays.css` lost the `/*` off
a section header and had a paragraph reflowed onto its own commas, which is what took popover, menu,
dropdown and tooltip out. So: tokenize once, tracking whether we are inside a comment, and only ever
edit the span between the last `}` (or `*/`) and the `{` that follows it.
"""
import io, re, sys, glob

# A host named by its identifying CLASS rather than by its tag -- `.__search-field__ .__field__`,
# where the box's whole look lives on that class so a caller can borrow it.
CLASS_HOSTS = {
 '__search-field__': {'icon', 'field', 'clear', 'options'},
 '__window-icon__': {'monogram'},
 '__completion-icon__': {'completion-mark-static', 'completion-mark-final'},
 '__drag-ghost__': {'pre-icon', 'label'},
}

# NOTE `colorselector` is deliberately absent: it is D1 kind B all the way down -- its structure stays
# LIGHT, because 55 shipped rules reach a nested WIDGET through it and a part is a leaf nothing
# descends from. @see ColorSelector's own class comment. Its inner Sliders and Dropdowns are in this
# map though, and reached through it: `colorselector .__channel-row__ slider::part(thumb)`.
HOSTS = {
 'switch': {'spacer', 'knob'},
 'slider': {'fill', 'thumb', 'spacer'},
 'progressbar': {'fill'},
 'scroller': {'track', 'thumb', 'head', 'tail'},
 'scrollerview': {'v-scroller', 'h-scroller', 'corner'},
 'tooltip': {'label'},
 'menu': {'items', 'separator'},
 'menuitem': {'mark', 'checkable', 'accelerator', 'submenu-arrow'},
 'dropdown': {'chevron', 'menu'},
 'searchfield': {'icon', 'field', 'clear', 'options'},
 'checkbox': {'mark', 'label'},
 'button': {'label'},
}

PART_TAIL = re.compile(r'^\.__([a-z0-9-]+)__((?::[a-z-]+(?:\([^)]*\))?)*)$')
HOST_HEAD = re.compile(r'^([a-z][a-z0-9-]*|\.__[a-z0-9-]+__)')


def twin(sel):
    """
    `<anything> HOST .__part__` -> `<anything> HOST::part(part)`.

    Generalised over the WHOLE selector rather than anchored at its start, which is what the first
    two versions got wrong. A part is very often reached through something else -- `colorselector
    .__channel-row__ slider .__thumb__` styles the thumb of a slider a colour selector builds, and
    `.__side__ dropdown .__menu__` the menu of a dropdown inside a panel. Matching only `tag .__x__`
    left every one of those untwinned, so the sliders in a colour picker drew with the default knob
    while the rows around them were perfect: the rule was right, the host was right, and the last
    two words could not reach a shadow part.

    The HOST is the compound immediately before the part -- named by its tag, or by the identifying
    class a widget wears -- and everything before it is carried through untouched, because the engine
    matches the host with the ordinary descendant walk.
    """
    parts = sel.strip().split()
    if len(parts) < 2:
        return None
    tail = PART_TAIL.match(parts[-1])
    if not tail:
        return None
    part, pseudo = tail.groups()
    host = parts[-2]
    m = HOST_HEAD.match(host)
    if not m:
        return None
    key = m.group(1)
    allowed = CLASS_HOSTS.get(key.lstrip('.')) if key.startswith('.') else HOSTS.get(key)
    if not allowed or part not in allowed:
        return None
    return ' '.join(parts[:-1]) + '::part(%s)%s' % (part, pseudo)


def selector_spans(text):
    """Every (start, end) of a rule's SELECTOR LIST -- outside comments, at brace depth 0."""
    spans, i, n = [], 0, len(text)
    depth, anchor = 0, 0
    while i < n:
        if text.startswith('/*', i):
            end = text.find('*/', i + 2)
            i = n if end < 0 else end + 2
            if depth == 0:
                anchor = i          # a comment never belongs to the selector that follows it
            continue
        c = text[i]
        if c == '{':
            if depth == 0:
                spans.append((anchor, i))
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                anchor = i + 1
        i += 1
    return spans


def main():
    added = 0
    for path in sorted(glob.glob(
            'core/src/main/resources/assets/crystalgui/ui/**/*.css', recursive=True)):
        text = io.open(path, encoding='utf-8').read()
        edits = []
        for start, end in selector_spans(text):
            raw = text[start:end]
            sels = [x.strip() for x in raw.split(',') if x.strip()]
            if not sels or any('/*' in s or '*/' in s for s in sels):
                continue                       # a span we do not fully understand is left alone
            # IDEMPOTENT: a twin already in the list is not added again. Without this a second run
            # appends a duplicate for every rule the first one touched -- `checkbox::part(mark)` three
            # times over -- which parses and matches identically and is pure noise in a diff.
            twins = []
            for candidate in (twin(x) for x in sels):
                if candidate and candidate not in sels and candidate not in twins:
                    twins.append(candidate)
            # De-duplicate what is already there, in case an earlier non-idempotent run doubled it.
            deduped = []
            for x in sels:
                if x not in deduped:
                    deduped.append(x)
            if not twins and deduped == sels:
                continue
            sels = deduped
            lead = raw[:len(raw) - len(raw.lstrip())]
            edits.append((start, end, lead + ',\n'.join(sels + twins) + ' '))
            added += len(twins)
        for start, end, replacement in reversed(edits):
            text = text[:start] + replacement + text[end:]
        if edits:
            io.open(path, 'w', encoding='utf-8', newline='\n').write(text)
    print('added %d twins' % added)


main()
