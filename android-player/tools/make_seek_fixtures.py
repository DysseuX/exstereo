# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-3.0-only

"""Small changing-tone seek fixtures. Run through scripts/run-limited.sh."""
from pathlib import Path
import subprocess, json
root=Path(__file__).resolve().parents[2]/'artifacts/seek-samples'
root.mkdir(parents=True,exist_ok=True)
def ff(*args):
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-threads','1','-y',*map(str,args)],check=True)
wav=root/'chirp.wav'
ff('-f','lavfi','-i','aevalsrc=0.2*sin(2*PI*(220*t+15*t*t))|0.18*sin(2*PI*(350*t+20*t*t)):s=48000:d=12','-c:a','pcm_s16le',wav)
outputs={
 'aac.m4a':['-c:a','aac','-b:a','192k'], 'alac.m4a':['-c:a','alac'],
 'audio.flac':['-c:a','flac'], 'cbr.mp3':['-c:a','libmp3lame','-b:a','192k'],
 'vbr-no-xing.mp3':['-c:a','libmp3lame','-q:a','3','-write_xing','0'],
 'audio.opus':['-c:a','libopus','-b:a','160k'], 'audio.ogg':['-c:a','libvorbis','-q:a','5'],
 'vbr.aac':['-c:a','aac','-q:a','2','-f','adts'],
 'opus.mka':['-c:a','libopus','-b:a','160k'], 'opus.webm':['-c:a','libopus','-b:a','160k'],
 'core.dts':['-c:a','dca','-strict','-2'],
}
for name,args in outputs.items():
    print(name,flush=True);ff('-i',wav,*args,root/name)
(root/'manifest.json').write_text(json.dumps(['chirp.wav',*outputs],indent=2)+'\n')
