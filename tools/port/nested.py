"""Publish every package-private NESTED type in a scratch tree, and count them.

A nested type another sub-package names has to become public for exactly the reason a top-level one
does, and it is the same cheap category: the type was already visible to everything in its package,
so publishing it widens nothing that was ever encapsulated. It is separated out only because javac
reports it in the same shape as a constructor (`X is not public in Owner`) and one unfixed instance
suppresses every error downstream -- which is how a split that costs 159 published members measured
as costing none.
"""
import io
import os
import re
import sys

ROOT = sys.argv[1]
PAT = re.compile(r'^(    )(?!public\b|private\b|protected\b)'
                 r'((?:static |final |abstract |sealed |non-sealed )*)'
                 r'((?:class|interface|enum|record)\s+\w+)', re.M)

count = 0
for dirpath, _, names in os.walk(ROOT):
    for f in names:
        if not f.endswith('.java'):
            continue
        p = os.path.join(dirpath, f)
        text = io.open(p, encoding='utf-8').read()
        new, n = PAT.subn(lambda m: m.group(1) + 'public ' + m.group(2) + m.group(3), text)
        if n:
            io.open(p, 'w', encoding='utf-8', newline='\n').write(new)
            count += n
print('nested types published: %d' % count)
