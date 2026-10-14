"""Prevent firmware/app/web ID drift and preserve saved selections on upgrades."""
import ast
import json
import pathlib
import re

root=pathlib.Path(__file__).resolve().parent
catalog=json.loads((root/'tracks.json').read_text(encoding='utf-8'))
assert catalog[:4]==['etchujima','inage-kaigan','JK03-south','JT03_SGW'],'Legacy track IDs changed'
manifest=json.loads((root/'generated/manifest.json').read_text(encoding='utf-8'))
assert [track['name'] for track in manifest]==catalog
header=(root/'firmware/audio_manifest.h').read_text(encoding='utf-8')
assert re.findall(r'\{"([^"]+)"',header)==catalog
java=(root/'android-app/src/cn/departurebell/control/TrackCatalog.java').read_text(encoding='utf-8')
assert re.findall(r'"([^"]+)"',java)==catalog
html=(root/'ble_control.html').read_text(encoding='utf-8')
assert ast.literal_eval(re.search(r'const names=(\[[^;]+\]);',html)[1])==catalog
assert re.findall(r'<option value="T\d+">([^<]+)</option>',html)==catalog
print(f'All {len(catalog)} track IDs agree across firmware, Android and web; original four IDs preserved')
