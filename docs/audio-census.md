# Audio census

`preflight audio census` reports how much PCM a profile's sound would occupy if it were decoded, and
which files a prepared-audio cache would be allowed to hold. It reads container headers only. It
decodes nothing, writes no cache, and makes no eligibility decision.

```bash
java -jar preflight.jar audio census --game "/path/to/Starsector" --output audio-census.json --csv sounds.csv
```

`--game` is taken as the install root directly; a launcher is not required, because nothing is
launched. Without it the usual discovery runs. `--csv` writes one row per file for slicing a question
the aggregate report did not anticipate.

## How it measures

Two reads per file, no decode:

- **Format** — the first Ogg page and Vorbis identification packet give codec, channel count, sample
  rate, and block sizes (`OggVorbisIdentification`).
- **Length** — the final Ogg page's granule position gives the frame count
  (`OggVorbisStreamLength`). Decoded size is `frames × channels × 2`, signed 16-bit little-endian.

The whole reviewed profile — 2,141 files, 516 MB — measures in about two seconds.

The granule position is a **floor**. JOrbis never trims its final block, so a stream that does not end
on a block boundary decodes to slightly more than the container declares: `packet-boundary-mono-44100`
declares 8,193 frames and decodes to 8,320. The error is bounded by one maximum block, 8,192 frames,
and is under 0.1% on multi-second audio.

## How files are classified

From `data/config/sounds.json`, read from **every** provider rather than only the override winner,
because Starsector merges these by id across mods instead of replacing them.

| Kind | Meaning |
| --- | --- |
| `effect` | declared outside the top-level `"music"` object — fully decoded, the only prepared-audio candidate |
| `music` | declared inside it, by path or by source directory — streamed, never cached |
| `unreferenced` | present in the profile, declared nowhere — never loaded |

Directory naming is not used and would be wrong if it were: the reviewed profile declares
seven-minute themes as effects with no `music` in their path, and short effects that live under a
`music` directory. See [the evidence](evidence/2026-07-26-what-prepared-audio-would-have-to-hold.md).

An entry that names a `"source"` draws its `"file"` from that archive or directory, so the source is
recorded instead of the file. A directory source classifies everything beneath it. Commented-out
entries are ignored, since the game does not load them.

Only the override winner at each logical path is measured.

## Undecodable files

A file that is not decodable Vorbis is reported with a reason and contributes zero decoded bytes.
This is a classification, not an error — the reviewed profile contains a declared effect that is FLAC
in an Ogg container, and a zero-byte `.ogg`.

## Report

```text
reportFormat        starsector-preflight-audio-census-v1
measurementBasis    final-ogg-page-granule-position
profileFingerprint  <resource-index fingerprint>
byKind              effect / music / unreferenced totals
declaredEffects     totals, byDuration, bySampleRate, byProvider, largest
undecodable         count and per-file reasons
```

`byDuration` buckets at 1, 5, 15, and 60 seconds; `bySampleRate` descends. Both exist because they are
where the profile's cost concentrates — 17 effects over a minute hold 27% of the eligible bytes, and
195 files at 96 kHz or above hold 33%.

The report deliberately contains no eligibility, approval, or readiness field. Sizing a cache is not
authorising one.

## Measured against the reviewed installation (2026-07-26)

| Class | Files | Encoded | Decoded |
| --- | ---: | ---: | ---: |
| declared effects | 1,803 | 125.1 MB | 1,172.6 MB |
| declared music | 141 | 371.2 MB | 2,855.8 MB |
| declared nowhere | 197 | 19.7 MB | 226.1 MB |
| all | 2,141 | 516.1 MB | 4,254.5 MB |

## CI boundary

CI builds synthetic profiles from the committed Ogg fixtures and proves classification against
`sounds.json`, exact decoded byte counts, override-winner selection, undecodable handling, and both
output formats. The real installation run is the sizing evidence for Starsector.
