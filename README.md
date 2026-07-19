# BT Audio Keeper

[![ci](https://github.com/sky0hunter/bluetooth-audio-keeper/actions/workflows/ci.yml/badge.svg)](https://github.com/sky0hunter/bluetooth-audio-keeper/actions/workflows/ci.yml)

Keeps cheap Bluetooth speakers awake so they stop clipping the start of your audio.

## The problem

Many budget Bluetooth speakers put their amplifier into standby the moment the
audio stream goes silent — for example during the pauses between audiobook
sentences. Waking the amp back up takes a fraction of a second, so the first
syllable after every pause gets swallowed. Infuriating for audiobooks and podcasts.

## The fix

The app runs a foreground service that streams **sub-audible white noise** to the
speaker. The signal is far too quiet to hear, but it's not digital silence, so the
speaker never drops into standby and nothing gets clipped.

It deliberately does **not** register a media session or request audio focus — your
actual player (Audible, Spotify, …) keeps full control of play/pause, and media
buttons on the speaker or lockscreen keep working exactly as before.

## Features

- **Manual mode** — noise streams whenever you toggle it on.
- **Auto mode** — the service watches for other apps playing media and only streams
  while something is actually playing. No battery spent while nothing plays.
- **Signal level slider** — the default (4/32767 PCM amplitude) is inaudible. If
  your speaker still cuts out, raise it; around 200 the hiss becomes faintly
  audible, which doubles as confirmation the stream reaches the speaker.
- **Home-screen widget** — 1×1 toggle.

## Requirements

Android 8.0+ (API 26). Built against SDK 34.

## Building

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

No release signing is configured — build a debug APK and sideload it.

## How it works, technically

A daemon thread writes low-amplitude random PCM (44.1 kHz mono, 16-bit) to an
`AudioTrack` tagged `USAGE_MEDIA` — that tag matters, since notification-style
audio routes to the phone speaker on most Bluetooth stacks and never reaches the
A2DP sink. Auto mode uses `AudioManager.AudioPlaybackCallback` to detect when other
apps play `USAGE_MEDIA`/`USAGE_GAME` audio and starts/stops the noise accordingly.

## License

MIT — see [LICENSE](LICENSE).
