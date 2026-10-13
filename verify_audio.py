"""Cross-check firmware's ADPCM arithmetic against Python's independent decoder."""
import audioop
import json
import pathlib
import re
import struct
import hashlib
import subprocess
import zlib

r = pathlib.Path(__file__).resolve().parent
source = (r/'firmware/firmware.ino').read_text(encoding='utf-8')
steps = list(map(int, re.search(r'STEP\[89\]=\{([^}]+)', source)[1].split(',')))
index = [-1,-1,-1,-1,2,4,6,8]
data = (r/'generated/audio.bin').read_bytes()
header = (r/'firmware/audio_manifest.h').read_text(encoding='utf-8')
assert zlib.crc32(data) == int(re.search(r'AUDIO_CRC = 0x([0-9a-f]+)', header)[1], 16)
source_hashes = {hashlib.sha256(p.read_bytes()).hexdigest() for p in (r/'bell').glob('*.mp3')}
reports = []
for t in json.loads((r/'generated/manifest.json').read_text(encoding='utf-8')):
    assert t['source_sha256'] in source_hashes
    assert t['segments'][0][1] == round(t['split_seconds'] * 22050)
    assert sum(s[1] for s in t['segments']) == t['samples']
    for segment_index, (offset, count) in enumerate(t['segments']):
        packed = data[offset:offset+(count+1)//2]
        reference, _ = audioop.adpcm2lin(packed,2,None)
        pred=idx=0
        result=[]
        for b in packed:
            for code in (b>>4,b&15):
                s=steps[idx]
                d=(s>>3)+((s>>2) if code&1 else 0)+((s>>1) if code&2 else 0)+(s if code&4 else 0)
                pred=max(-32768,min(32767,pred+(-d if code&8 else d)))
                idx=max(0,min(88,idx+index[code&7]))
                result.append(pred)
        assert struct.pack('<'+'h'*len(result),*result)==reference
        assert len(packed)==(count+1)//2
        print(t['name'], count, 'samples: decoder PASS')
        if 'loudness' in t:
            # Measure the actual decoded ADPCM, not only the pre-encode PCM.
            measured = subprocess.run(['ffmpeg', '-hide_banner', '-nostats',
                '-f', 's16le', '-ar', '22050', '-ac', '1', '-i', 'pipe:0',
                '-af', 'loudnorm=I=-20:TP=-3:LRA=11:print_format=json',
                '-f', 'null', '-'], input=reference[:count*2], capture_output=True, check=True)
            text = measured.stderr.decode('utf-8', errors='replace')
            stats, _ = json.JSONDecoder().raw_decode(text[text.rfind('{'):])
            lufs, peak = float(stats['input_i']), float(stats['input_tp'])
            assert abs(lufs + 20) < 1.0, (t['name'], lufs)
            assert peak < -0.5, (t['name'], peak)
            reports.append({'name': t['name'], 'segment': segment_index,
                            'decoded_lufs': lufs, 'decoded_true_peak_db': peak})
if reports:
    (r/'generated/loudness_verification.json').write_text(
        json.dumps(reports, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Decoded loudness:', min(x['decoded_lufs'] for x in reports),
          'to', max(x['decoded_lufs'] for x in reports), 'LUFS; peaks PASS')
print('All eight segments verified; original MP3 files unchanged.')
