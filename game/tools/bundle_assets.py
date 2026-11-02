#!/usr/bin/env python3
"""Emballe game/assets/ dans game/assets.js (data URI) : le jeu marche en double-cliquant index.html."""
import base64, json, os, sys
root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), '..')
A = os.path.join(root, 'assets')
files = {}
for d in ['char', 'tiles', 'monsters']:
    for f in sorted(os.listdir(os.path.join(A, d))):
        if f.endswith('.png'):
            files[f'{d}/{f}'] = 'data:image/png;base64,' + base64.b64encode(open(os.path.join(A, d, f), 'rb').read()).decode()
man = json.load(open(os.path.join(A, 'char', 'manifest.json')))
with open(os.path.join(root, 'assets.js'), 'w') as o:
    o.write('window.ASSETS=' + json.dumps({'manifest': man, 'files': files}, separators=(',', ':')) + ';\n')
print(len(files), 'images', os.path.getsize(os.path.join(root, 'assets.js')) // 1024, 'Ko')
