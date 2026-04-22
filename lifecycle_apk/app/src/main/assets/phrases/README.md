# Persona Signature Phrase Packs

Drop free MP3 or WAV audio files into the folder for the persona you want
to customize. The voice engine will play them *instead of* TTS whenever the
bot speaks a line that matches the filename.

## How matching works

The filename (minus extension) is lowercased and underscores/hyphens become
spaces. E.g.:

  `hell_yeah_brother.mp3`  →  matches `"Hell yeah brother"` (any case)
  `arrr-matey.wav`         →  matches `"Arrr matey"`
  `uwu.mp3`                →  matches `"uwu"`

A file matches if the phrase is equal to, starts with, or contains the slug.
Only short lines (≤ 80 chars) are eligible so regular sentences still use TTS.

## Per-persona folders

```
phrases/
  aate/
  irishman/
  batman/
  gentleman/
  frasier/
  wallstreet/
  zen/
  cockney/
  cowboy/
  hunter_s/
  narrator/
  pirate/
  waifu/
  cleetus/       <-- drop rowdy Southern hype clips here
  peter/
```

## Finding free audio

Any site that offers royalty-free or CC0 clips works — freesound.org,
archive.org, pixabay.com/sound-effects/, soundbible. Save short WAV/MP3
clips (1–3 seconds), name them by the phrase slug, drop them in the folder.

## Formats

MP3 and WAV both work. Keep files under 500KB so APK size stays sane.
