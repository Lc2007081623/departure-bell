import audioop
import hashlib
import json
import math
import pathlib
import re
import subprocess
import zlib

ROOT = pathlib.Path(__file__).resolve().parent
OUT = ROOT / 'generated'
OUT.mkdir(exist_ok=True)
RATE = 22050
TARGET_LUFS = -20
TRUE_PEAK_DB = -3

def normalize_segment(pcm):
    """Two-pass loudness normalization; retain the original segment length."""
    base = ['ffmpeg', '-hide_banner', '-nostats', '-f', 's16le',
            '-ar', str(RATE), '-ac', '1', '-i', 'pipe:0']
    target = f'loudnorm=I={TARGET_LUFS}:TP={TRUE_PEAK_DB}:LRA=11'
    analysis = subprocess.run(base + ['-af', target + ':print_format=json',
        '-f', 'null', '-'], input=pcm, capture_output=True, check=True)
    stats_text = analysis.stderr.decode('utf-8', errors='replace')
    stats, _ = json.JSONDecoder().raw_decode(stats_text[stats_text.rfind('{'):])
    if not all(math.isfinite(float(stats[key])) for key in
               ('input_i', 'input_tp', 'input_lra', 'input_thresh', 'target_offset')):
        raise ValueError('Audio segment has no measurable loudness')
    measured = ':'.join(f'{out}={stats[key]}' for out, key in (
        ('measured_I', 'input_i'), ('measured_TP', 'input_tp'),
        ('measured_LRA', 'input_lra'), ('measured_thresh', 'input_thresh'),
        ('offset', 'target_offset')))
    result = subprocess.run(base + ['-af', target + ':' + measured +
        ':linear=true:print_format=json', '-ar', str(RATE), '-ac', '1',
        '-f', 's16le', '-'], input=pcm, capture_output=True, check=True)
    normalized = result.stdout[:len(pcm)].ljust(len(pcm), b'\0')
    output_text = result.stderr.decode('utf-8', errors='replace')
    output_stats, _ = json.JSONDecoder().raw_decode(output_text[output_text.rfind('{'):])
    return normalized, {'input_lufs': float(stats['input_i']),
                        'output_lufs': float(output_stats['output_i']),
                        'output_true_peak_db': float(output_stats['output_tp']),
                        'mode': output_stats['normalization_type']}

blob = bytearray(b'BELLV5A1')
rows = []
tracks = []
for path in sorted((ROOT / 'bell').glob('*.mp3')):
    match = re.fullmatch(r'(.+)[（(](\d+(?:\.\d+)?)[）)]', path.stem)
    if not match:
        raise ValueError(f'Missing split seconds: {path.name}')
    name, split = match[1], float(match[2])
    pcm = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(path),
        '-f', 's16le', '-acodec', 'pcm_s16le', '-ac', '1', '-ar', str(RATE), '-'])
    cut = round(split * RATE) * 2
    if not 0 < cut < len(pcm):
        raise ValueError(f'Invalid split: {path.name}')
    segments = []
    loudness = []
    for part in (pcm[:cut], pcm[cut:]):
        part, report = normalize_segment(part)
        loudness.append(report)
        count = len(part) // 2
        if count % 2:
            part += b'\0\0'
        encoded, _ = audioop.lin2adpcm(part, 2, None)
        decoded, _ = audioop.adpcm2lin(encoded, 2, None)
        assert len(decoded) >= count * 2
        segments.append((len(blob), count))
        blob.extend(encoded)
    tracks.append({'name': name, 'split_seconds': split, 'samples': len(pcm)//2,
                   'segments': segments, 'loudness': loudness,
                   'source_sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    rows.append('  {"%s", {%d, %d}, {%d, %d}},' % (name, *segments[0], *segments[1]))
assert len(tracks) == 4
assert len(blob) < 0x300000
(OUT / 'audio.bin').write_bytes(blob)
(OUT / 'manifest.json').write_text(json.dumps(tracks, ensure_ascii=False, indent=2), encoding='utf-8')
header = '#pragma once\nstruct Segment { uint32_t offset, samples; };\nstruct Track { const char *name; Segment music, warning; };\n'
header += 'constexpr Track TRACKS[] = {\n' + '\n'.join(rows) + '\n};\n'
header += f'constexpr uint32_t AUDIO_BYTES = {len(blob)};\nconstexpr uint32_t AUDIO_CRC = 0x{zlib.crc32(blob):08x};\n'
(ROOT / 'firmware' / 'audio_manifest.h').write_text(header, encoding='utf-8')
print(json.dumps({'bytes': len(blob), 'tracks': tracks}, ensure_ascii=False, indent=2))
