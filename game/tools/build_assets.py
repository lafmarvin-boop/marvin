#!/usr/bin/env python3
"""Construit les assets de Pixel Realm a partir de :
 - Universal LPC Spritesheet Character Generator (clone git, arg 1)
 - LPC Base Assets d'OpenGameArt (dossier dezippe, arg 2)
Sortie : game/assets/ (arg 3)."""
import json, os, shutil, subprocess, sys
from PIL import Image

LPC, BASE, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
FS = 64
ANIMS = [('walk', 9, 4), ('slash', 6, 4), ('thrust', 8, 4), ('spellcast', 7, 4), ('hurt', 6, 1)]

# id: (dossier, zPos, variante, materiau de recoloration, base de palette)
LAYERS = {
 'body_male': ('body/bodies/male/', 10, None, 'body', 'light'),
 'body_female': ('body/bodies/female/', 10, None, 'body', 'light'),
 'body_muscular': ('body/bodies/muscular/', 10, None, 'body', 'light'),
 'body_skeleton': ('body/bodies/skeleton/', 10, None, None, None),
 'body_zombie': ('body/bodies/zombie/', 10, 'zombie', None, None),
 'head_human_male': ('head/heads/human/male/', 100, None, 'body', 'light'),
 'head_human_female': ('head/heads/human/female/', 100, None, 'body', 'light'),
 'head_orc_male': ('head/heads/orc/male/', 100, None, 'body', 'light'),
 'head_orc_female': ('head/heads/orc/female/', 100, None, 'body', 'light'),
 'head_skeleton': ('head/heads/skeleton/adult/', 100, None, None, None),
 'head_zombie': ('head/heads/zombie/adult/', 100, None, None, None),
 'head_goblin': ('head/heads/goblin/adult/', 100, None, 'body', 'light'),
 'head_minotaur': ('head/heads/minotaur/male/', 100, None, 'body', 'light'),
 'head_wolf': ('head/heads/wolf/male/', 100, None, 'body', 'light'),
 'ears_elven': ('head/ears/elven/adult/', 126, None, 'body', 'light'),
 'eyes': ('eyes/human/adult/default/', 105, 'blue', None, None),
 'beard': ('beards/beard/winter/male/', 110, None, 'hair', 'orange'),
 'beard_basic': ('beards/beard/basic/', 110, None, 'hair', 'orange'),
 'beard_medium': ('beards/beard/medium/', 110, None, 'hair', 'orange'),
 'beard_trimmed': ('beards/beard/trimmed/', 110, None, 'hair', 'orange'),
 'beard_5oclock': ('beards/beard/5oclock_shadow/', 110, None, 'hair', 'orange'),
 'stache_basic': ('beards/mustache/basic/', 111, None, 'hair', 'orange'),
 'stache_big': ('beards/mustache/bigstache/', 111, None, 'hair', 'orange'),
 'stache_handlebar': ('beards/mustache/handlebar/', 111, None, 'hair', 'orange'),
 'stache_walrus': ('beards/mustache/walrus/', 111, None, 'hair', 'orange'),
 'body_teen': ('body/bodies/teen/', 10, None, 'body', 'light'),
 'leather_teen': ('torso/armour/leather/teen/', 60, None, 'cloth', 'leather'),
 'plate_teen': ('torso/armour/plate/teen/', 60, None, 'metal', 'steel'),
 'hair_plain': ('hair/plain/adult/', 120, None, 'hair', 'orange'),
 'hair_long': ('hair/long/adult/', 120, None, 'hair', 'orange'),
 'hair_messy1': ('hair/messy1/adult/', 120, None, 'hair', 'orange'),
 'hair_pixie': ('hair/pixie/adult/', 120, None, 'hair', 'orange'),
 'hair_bob': ('hair/bob/adult/', 120, None, 'hair', 'orange'),
 'hair_shorthawk': ('hair/shorthawk/adult/', 120, None, 'hair', 'orange'),
 'hair_ponytail_fg': ('hair/ponytail/adult/fg/', 120, None, 'hair', 'orange'),
 'hair_ponytail_bg': ('hair/ponytail/adult/bg/', 4, None, 'hair', 'orange'),
 'leather_male': ('torso/armour/leather/male/', 60, None, 'cloth', 'leather'),
 'leather_female': ('torso/armour/leather/female/', 60, None, 'cloth', 'leather'),
 'plate_male': ('torso/armour/plate/male/', 60, None, 'metal', 'steel'),
 'plate_female': ('torso/armour/plate/female/', 60, None, 'metal', 'steel'),
 'pants_male': ('legs/pants/male/', 20, None, 'cloth', 'white'),
 'pants_thin': ('legs/pants/thin/', 20, None, 'cloth', 'white'),
 'boots_male': ('feet/boots/basic/male/', 25, None, 'cloth', 'white'),
 'boots_thin': ('feet/boots/basic/thin/', 25, None, 'cloth', 'white'),
 'cape_fg': ('cape/solid/fg/', 85, None, 'cloth', 'white'),
 'cape_bg': ('cape/solid/bg/', 5, None, 'cloth', 'white'),
 'helmet': ('hat/helmet/barbarian/adult/', 130, None, 'metal', 'steel'),
 'sword_fg': ('weapon/sword/longsword/', 140, 'longsword', 'metal', 'steel'),
 'sword_bg': ('weapon/sword/longsword/universal_behind/', 9, 'longsword', 'metal', 'steel'),
 'axe_fg': ('weapon/blunt/waraxe/', 140, 'waraxe', 'metal', 'steel'),
 'axe_bg': ('weapon/blunt/waraxe/behind/', 9, 'waraxe', 'metal', 'steel'),
 # equipement (coffres)
 'helm_kettle': ('hat/helmet/kettle/adult/', 135, None, 'metal', 'steel'),
 'helm_horned': ('hat/helmet/horned/adult/', 130, None, 'metal', 'steel'),
 'helm_armet': ('hat/helmet/armet/adult/', 130, None, 'metal', 'steel'),
 'legion_male': ('torso/armour/legion/male/', 60, None, 'metal', 'steel'),
 'legion_female': ('torso/armour/legion/female/', 60, None, 'metal', 'steel'),
 'legion_teen': ('torso/armour/legion/teen/', 60, None, 'metal', 'steel'),
 'gloves_male': ('arms/hands/gloves/male/', 70, None, 'metal', 'steel'),
 'gloves_thin': ('arms/hands/gloves/thin/', 70, None, 'metal', 'steel'),
 'armplate_male': ('arms/armour/plate/male/', 61, None, 'metal', 'steel'),
 'armplate_thin': ('arms/armour/plate/thin/', 61, None, 'metal', 'steel'),
 'legplate_male': ('legs/armour/plate/male/', 21, None, 'metal', 'steel'),
 'legplate_thin': ('legs/armour/plate/thin/', 21, None, 'metal', 'steel'),
 'feetplate_male': ('feet/armour/plate/male/', 15, None, 'metal', 'steel'),
 'feetplate_thin': ('feet/armour/plate/thin/', 15, None, 'metal', 'steel'),
 'boots_rimmed_male': ('feet/boots/rimmed/male/', 25, None, 'cloth', 'white'),
 'boots_rimmed_thin': ('feet/boots/rimmed/thin/', 25, None, 'cloth', 'white'),
 'shield_round_brown': ('shield/round/', 110, 'brown', None, None),
 'shield_round_gold': ('shield/round/', 110, 'gold', None, None),
 'shield_kite_male': ('shield/kite/male/', 110, 'kite_blue_gray', None, None),
 'shield_kite_female': ('shield/kite/female/', 110, 'kite_blue_gray', None, None),
 'shield_crusader_bg': ('shield/crusader/bg/', 2, 'crusader', None, None),
 'shield_crusader_male': ('shield/crusader/fg/male/', 110, 'crusader', None, None),
 'shield_crusader_female': ('shield/crusader/fg/female/', 110, 'crusader', None, None),
 'spear_fg': ('weapon/polearm/spear/foreground/', 140, 'iron', None, None),
 'spear_bg': ('weapon/polearm/spear/background/', 9, 'iron', None, None),
 'spear_gold_fg': ('weapon/polearm/spear/foreground/', 140, 'gold', None, None),
 'spear_gold_bg': ('weapon/polearm/spear/background/', 9, 'gold', None, None),
}
OVERSIZE = {  # attaques 192 px
 'sword_atk_fg': ('weapon/sword/longsword/attack_slash/', 150, 'longsword'),  # recoloriees en metal
 'sword_atk_bg': ('weapon/sword/longsword/attack_slash/behind/', -1, 'longsword'),
 'axe_atk_fg': ('weapon/blunt/waraxe/attack_slash/', 150, 'waraxe'),
 'axe_atk_bg': ('weapon/blunt/waraxe/attack_slash/behind/', -1, 'waraxe'),
}

files = set(subprocess.check_output(['git', '-C', LPC, 'ls-tree', '-r', '--name-only', 'HEAD'], text=True).split('\n'))

def resolve(d, anim, var):
    for c in [f'spritesheets/{d}{anim}/{var}.png', f'spritesheets/{d}{anim}.png']:
        if c in files: return c
    sub = sorted(f for f in files if f.startswith(f'spritesheets/{d}{anim}/') and f.count('/') == (f'spritesheets/{d}{anim}/').count('/'))
    return sub[0] if sub else None

need = {}
for lid, (d, z, var, *_) in LAYERS.items():
    for a, *_ in ANIMS:
        need[(lid, a)] = resolve(d, a, var)
for lid, (d, z, var) in OVERSIZE.items():
    need[(lid, 'slash')] = resolve(d, '', var) or (f'spritesheets/{d}{var}.png' if f'spritesheets/{d}{var}.png' in files else None)
paths = sorted({p for p in need.values() if p})
subprocess.check_call(['git', '-C', LPC, 'checkout', 'HEAD', '--'] + paths)

os.makedirs(f'{OUT}/char', exist_ok=True)
manifest = {'frame': FS, 'anims': {}, 'layers': {}, 'oversize': {}, 'palettes': {}}
row = 0
for a, n, r in ANIMS:
    manifest['anims'][a] = {'row': row, 'frames': n, 'dirs': r}; row += r
H = row * FS; Wd = 9 * FS
used = []
for lid, (d, z, var, mat, base) in LAYERS.items():
    sheet = Image.new('RGBA', (Wd, H))
    y = 0
    for a, n, r in ANIMS:
        p = need[(lid, a)]
        if p:
            im = Image.open(f'{LPC}/{p}').convert('RGBA').crop((0, 0, n * FS, r * FS))
            sheet.paste(im, (0, y)); used.append(p)
        else:
            print('manque', lid, a)
        y += r * FS
    sheet.save(f'{OUT}/char/{lid}.png', optimize=True)
    manifest['layers'][lid] = {'z': z, 'mat': mat, 'base': base}
for lid, (d, z, var) in OVERSIZE.items():
    p = need[(lid, 'slash')]
    im = Image.open(f'{LPC}/{p}').convert('RGBA').crop((0, 0, 6 * 192, 4 * 192))
    im.save(f'{OUT}/char/{lid}.png', optimize=True); used.append(p)
    manifest['oversize'][lid] = {'z': z, 'frame': 192, 'mat': 'metal', 'base': 'steel'}
for mat, f in [('body', 'body/body_ulpc'), ('hair', 'hair/hair_ulpc'), ('cloth', 'cloth/cloth_ulpc'), ('metal', 'metal/metal_ulpc')]:
    manifest['palettes'][mat] = json.loads(subprocess.check_output(['git', '-C', LPC, 'show', f'HEAD:palette_definitions/{f}.json'], text=True))
json.dump(manifest, open(f'{OUT}/char/manifest.json', 'w'), separators=(',', ':'))

# Base assets : tuiles + monstres
os.makedirs(f'{OUT}/tiles', exist_ok=True); os.makedirs(f'{OUT}/monsters', exist_ok=True)
for t in ['grass', 'grassalt', 'dirt', 'dirt2', 'water', 'watergrass', 'brackish', 'treetop', 'trunk', 'rock', 'chests',
          'castlefloors', 'castlefloors_outside', 'lava', 'lavarock', 'hole', 'signs', 'barrel', 'castle_lightsources', 'cement']:
    shutil.copy(f'{BASE}/tiles/{t}.png', f'{OUT}/tiles/{t}.png')
for m in ['slime', 'bat', 'snake', 'bee', 'eyeball', 'ghost', 'pumpking', 'man_eater_flower', 'small_worm', 'big_worm']:
    shutil.copy(f'{BASE}/sprites/monsters/{m}.png', f'{OUT}/monsters/{m}.png')
shutil.copy(f'{BASE}/CREDITS.TXT', f'{OUT}/CREDITS-base-assets.txt')

# Credits des couches LPC utilisees
creds = {}
for root, _, fs in os.walk(f'{LPC}/sheet_definitions'):
    pass
defs = [f for f in files if f.startswith('sheet_definitions/') and f.endswith('.json')]
subprocess.check_call(['git', '-C', LPC, 'checkout', 'HEAD', '--'] + defs)
for f in defs:
    try: d = json.load(open(f'{LPC}/{f}'))
    except Exception: continue
    for c in d.get('credits', []):
        pre = 'spritesheets/' + c['file'].rstrip('/')
        if any(u.startswith(pre + '/') or u.startswith(pre + '.') for u in used):
            creds[c['file']] = c
with open(f'{OUT}/CREDITS.md', 'w') as o:
    o.write('# Crédits des graphismes\n\nTous les graphismes viennent du projet Liberated Pixel Cup (LPC).\n'
            'Licences : CC-BY-SA 3.0 / GPL 3.0 / OGA-BY 3.0 (voir chaque entrée).\n\n'
            '## LPC Base Assets (tuiles, monstres)\n\nVoir `CREDITS-base-assets.txt`. Source : '
            'https://opengameart.org/content/liberated-pixel-cup-lpc-base-assets-sprites-map-tiles\n\n'
            '## Universal LPC Spritesheet Character Generator (personnages)\n\n'
            'Source : https://github.com/LiberatedPixelCup/Universal-LPC-Spritesheet-Character-Generator\n\n')
    for k in sorted(creds):
        c = creds[k]
        o.write(f"- **{k}** — {', '.join(c.get('authors', []))} — {', '.join(c.get('licenses', []))}\n")
        for u in c.get('urls', []): o.write(f'  - {u}\n')
print('ok', len(used), 'fichiers,', len(creds), 'credits')
