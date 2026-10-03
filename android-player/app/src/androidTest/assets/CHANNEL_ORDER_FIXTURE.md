`channel_order_7_1.opus` is an original synthetic test signal, not music.

It contains 3.6 seconds of 48 kHz, mapping-family-1 Opus 7.1. The source PCM
channel order is WAV/Android: FL, FR, FC, LFE, BL, BR, SL, SR. There are eight
450 ms slots; only the corresponding channel is active during each slot.
Its sine burst starts at 100 ms and ends at 350 ms, with 10 ms linear fades,
at amplitude 0.35. Frequencies in that same channel order are
320, 440, 580, 70, 760, 920, 1160, 1400 Hz. Other channels contain zero PCM.

The source uses signed 16-bit little-endian interleaved PCM. Encoding command:

```
ffmpeg -f s16le -ar 48000 -ac 8 -channel_layout 7.1 -i source.pcm \
  -c:a libopus -b:a 512k -mapping_family 1 -vbr on -frame_duration 20 \
  -application audio channel_order_7_1.opus
```

The test measures the interior 150–300 ms of each slot, after Media3's own
Opus/Vorbis-to-Android channel mapping. It checks both energy isolation and
quadrature correlation with the expected frequency. The test processor emits
stereo silence and confirms that the actual AudioTrack is PCM16 stereo.
