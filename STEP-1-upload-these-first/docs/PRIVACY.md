# Privacy

## The short version

The app **does not declare the `INTERNET` permission**. Not "does not use the network" — it is not
permitted to. Android will refuse any network call the app attempts.

That is a stronger guarantee than a privacy policy, because you do not have to take anyone's word
for it. Check [`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) yourself, or
inspect the installed APK:

```bash
aapt dump permissions app-debug.apk
```

There is no `android.permission.INTERNET` line. There is no way for your dream journal to leave
your phone.

## What the app does not do

- No user accounts, sign-in or identifiers
- No analytics, telemetry or usage statistics of any kind
- No crash reporting
- No advertising, ad IDs or trackers
- No third-party SDKs
- No cloud storage or sync
- No Google Play Services dependency
- **No recording, ever** — including during optional microphone sleep sensing (see below)

## What is stored, and where

Everything lives in the app's private storage on your phone:

| Data | Where |
|---|---|
| Dream journal, tags, dream signs | Local SQLite database |
| Sessions, cue history, heartbeats | Local SQLite database |
| Reality-check log | Local SQLite database |
| Settings and schedules | Local DataStore |
| Sleep-stage estimates (if sensing is on) | Local SQLite — probabilities and confidence only, never audio |
| Experiment assignments and outcomes | Local SQLite |
| Imported cue sounds, voice recordings, rendered speech | App-private files directory |
| Event log (developer diagnostics) | Local SQLite, bounded ring buffer |

Automatic cloud backup is **disabled** (`allowBackup="false"` plus explicit data-extraction rules),
so your journal is not swept into a Google account backup without you choosing it.

## The one thing that does leave the device

**Voice dictation in the dream journal.**

The "Dictate" button uses Android's own speech-recognition service. That is a system component, not
part of this app, and on many devices it sends audio to the platform's speech service for
processing. The app requests on-device recognition (`EXTRA_PREFER_OFFLINE`), but that is a request,
not a guarantee.

This is disclosed at the point of use, in the app, every time. If it matters to you, type instead —
typed text never leaves the phone.

## Microphone

`RECORD_AUDIO` is declared and used for three optional things:

1. Recording your own voice as a cue sound
2. Voice dictation for a dream entry
3. **Microphone sleep sensing** — off by default

### Microphone sleep sensing, precisely

This is the only feature that opens the microphone while you are asleep. It is **off by default**
and requires you to choose it on the Sleep sensing screen, which explains all of the following
before you can enable it.

When it is running:

- The microphone is open for **30 seconds out of every minute**, and closed the rest of the time.
- Audio is read into **one reusable buffer**, which the next read overwrites. There is no
  accumulation.
- Each ~64 ms window is reduced to a handful of numbers — loudness, brightness, spectral change —
  and the buffer is then reused. No copy is kept.
- What is stored per minute is a probability distribution over sleep stages, a confidence value, and
  occasionally an estimated breathing rate. Nothing else.
- **Nothing is written to disk.** No file, no cache, no buffer that outlives the window.

The stored numbers cannot be turned back into audio and cannot recover speech — they are summary
statistics over whole seconds.

### There is deliberately no raw-audio debug mode

Some sleep apps offer an option to save recordings for debugging. This one does not, on purpose. It
would be one bad default, one bug, or one mistaken tap away from turning a sleep app into a bedroom
recorder, and the diagnostic value does not justify that risk. The developer screen shows the derived
numbers instead, which is enough to tell whether the analysis is working.

If you want the microphone never touched at all, leave sensing on **Off** or **Movement only** —
movement sensing needs no permission whatsoever.

Your phone's own microphone indicator will appear while sensing is listening. That is correct, and
the app also shows its own indicator on the dashboard, with a one-tap control to turn the microphone
off for the rest of the night.

## Your data is yours

- **Export** (Settings > Privacy) writes a plain, readable JSON file. Open it in a text editor; your
  dreams are right there in it. Copy it, sync it yourself, or edit it. The app does not move it
  anywhere.
- **Erase** (Settings > Privacy) deletes everything on the device. Because there is no copy
  elsewhere, it cannot be undone — export first.
- **Statistics** can be switched off entirely, in which case nothing is counted.

## Permissions

Each permission, why it exists and what breaks without it, is listed in
[PERMISSIONS.md](PERMISSIONS.md) and mirrored on the in-app Privacy screen.

## Licence and verifiability

The app is GPL-3.0. The full source is in this repository, and you can build the APK yourself —
see [BUILDING.md](BUILDING.md). None of the claims above require trusting the author.
