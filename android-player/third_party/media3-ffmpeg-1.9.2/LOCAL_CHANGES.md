# Vendored AndroidX Media3 FFmpeg audio decoder

Source: AndroidX Media3 tag `1.9.2`, `libraries/decoder_ffmpeg`, Apache 2.0.
Only four Java audio classes and the JNI bridge are included. No video
renderer or upstream build scripts are executed. The exception class was
retrieved from the `release` source view because the tagged web view was
unavailable; its two constructors only delegate to `DecoderException`.
`UPSTREAM.json` records URLs and the hashes of the locally adapted files.
These are hashes of the normalized/adapted files, not pristine upstream blobs.

Sources were retrieved through the web tool's complete text/plain numbered
source views after local network download approval timed out. Line breaks
were normalized; copyright notices and the Apache license were preserved.

Local changes:

- JNI builds on Linux too, using stderr instead of Android logging, so the
  same decoder implementation can be exercised by host integration tests.
- Decoder threads are limited to one. Direct-buffer lengths and padding are
  checked; compressed packet padding and codec extradata padding are zeroed.
- PCM frame dimensions and output buffer arithmetic are checked; the
  swresample output capacity is passed in samples, and only returned samples
  are published. Buffer growth retains the accumulated output offset.
- Failed swresample initialization frees its allocation.
- Opus speaker mapping families 0/1 are accepted; ambisonic and arbitrary
  mappings are rejected explicitly. Invalid compressed packets fail instead
  of silently skipping the entire track.
- Any failed constructor releases the decoder thread and native context.
- For MP4 Opus only, convert the pinned Media3 parser's copied dOps v0 body
  (big endian) to an OpusHead v1 header (little endian) before FFmpeg sees it.
  This fixes the pre-skip, original rate and gain; other containers and already
  normalized v1 headers remain untouched. See Media3 1.9.2 BoxParser and
  https://www.opus-codec.org/docs/opus_in_isobmff.html section 4.3.2.
- The cached version uses the existing AndroidX Nullable annotation instead
  of introducing Checker Framework for one compile-time annotation. The
  Javadoc refers only to the included audio renderer.

FFmpeg is the project's existing pinned `8.1.3`. Enabled audio decoders:
AAC, ALAC, FLAC, Opus, Vorbis, MP3, AC-3, E-AC-3, DTS/DTS-HD (dca) and Monkey’s Audio (ape). E-AC-3 JOC uses the
compatible channel-based bed, without Atmos object rendering.
No new FFmpeg demuxers, protocols or
network inputs are enabled; container parsing stays in Media3.

Exstereo v23 local additions: map audio/x-ape to ape and forward APE initialization data;
validate and unpack the local bit-depth extension in JNI before opening the decoder.
The added Kotlin APE demuxer and bounded JNI DTS parser are in app/src/main,
documented in ../../CODEC_ADDITIONS.md. Previous adapted-file hashes are retained
as pre_exstereo_v23_sha256 in UPSTREAM.json.

Exstereo v24 seeking fixes:
- The indexed MP3 reader uses private `audio/x-exstereo-mpeg`, mapped to the
  same FFmpeg MP3 decoder. Media3's public `audio/mpeg` is classified as all
  sync samples, which discards bit-reservoir preroll before decoding. The
  private MIME keeps those frames in SampleQueue; independent Layer III
  frames are identified by main_data_begin == 0. Other MP3 callers retain
  the standard MIME mapping. There is no format conversion or new codec.
- If a decoded PCM buffer straddles the requested output start time, retain
  only its suffix, advance the output timestamp and clear shouldBeSkipped.
  This avoids SimpleDecoder dropping the entire long APE frame, including
  the last frame near EOF. Binary search uses the parent's synchronized
  output-time predicate; trimming changes ByteBuffer.position without a
  copy and supports both PCM16 and float output.
- Previous adapted hashes are kept in pre_exstereo_v24_sha256; new hashes
  describe these two intentionally changed Java files.
