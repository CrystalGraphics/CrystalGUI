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

# A host is named by its TAG here; `CLASS_HOSTS` is the other spelling, where a widget wears an
# identifying class instead. `.__search-field__ .__field__` is the shape: SearchField's own class on
# itself, then one of its parts -- and the twin is `.__search-field__::part(field)`. Skipping these
# left the field with no height rule at all, and a TextField that measures zero cannot be clicked.
CLASS_HOSTS = {
 '__search-field__': {'icon', 'field', 'clear', 'options'},
 '__window-icon__': {'monogram'},
 '__completion-icon__': {'completion-mark-static', 'completion-mark-final'},
 '__drag-ghost__': {'pre-icon', 'label'},
}

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
 'colorselector': {'wheel', 'ring', 'square', 'ring-handle', 'square-handle', 'left', 'side',
                   'channels', 'channel-row', 'hex-row', 'swatches', 'swatch-original', 'swatch-new'},
}

SEL = re.compile(r'^([a-z][a-z0-9-]*)([^ ]*)\s+\.__([a-z0-9-]+)__((?::[a-z-]+(?:\([^)]*\))?)*)$')
CLASS_SEL = re.compile(
    r'^\.(__[a-z0-9-]+__)([^ ]*)\s+\.__([a-z0-9-]+)__((?::[a-z-]+(?:\([^)]*\))?)*)$')


def twin(sel):
    sel = sel.strip()
    m = CLASS_SEL.match(sel)
    if m:
        host, rest, part, pseudo = m.groups()
        if host in CLASS_HOSTS and part in CLASS_HOSTS[host]:
            return '.%s%s::part(%s)%s' % (host, rest, part, pseudo)
        return None
    m = SEL.match(sel)
    if not m:
        return None
    tag, rest, part, pseudo = m.groups()
    if tag in HOSTS and part in HOSTS[tag]:
        return '%s%s::part(%s)%s' % (tag, rest, part, pseudo)
    return None


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
