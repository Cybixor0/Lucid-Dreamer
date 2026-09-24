# Lucid Dreamer

An open-source Android toolkit for practising lucid dreaming: timed audio cues through the night,
a dream journal, daytime reality checks, and Wake Back To Bed.

It is offline-first, free, and has **no internet permission at all** — the app is structurally
incapable of sending anything off your device, and you can verify that by reading
[the manifest](app/src/main/AndroidManifest.xml).

No accounts. No ads. No subscriptions. No analytics. No cloud. No Google Play.

---

## What it actually does

- **Plays quiet audio cues at times you choose** during the night — a soft bell, a tone, your own
  voice, or spoken text — so you can try to notice them inside a dream.
- **Keeps a dream journal** with full-text search, tags, dream signs, mood and lucidity.
- **Reminds you to do reality checks** during the day, with your own wording.
- **Wakes you for WBTB** and resumes cues when you go back to bed.
- **Tells you in the morning if the night did not go to plan**, and what to change.
- **Optionally estimates your sleep stage** from movement and, if you allow it, microphone
  analysis — as a probability with a confidence value, never as a measurement.
- **Optionally compares two cue setups** by alternating between them and asking how each night went.

## What it does not do

This matters more than the feature list.

- **It cannot measure your sleep.** A phone has no EEG. The optional sleep-stage estimation is a
  *guess*, shown with its confidence, and whenever that confidence is low the app says so and falls
  back to your fixed schedule rather than acting on noise.
- **It cannot make you lucid dream.** Nothing can. This is a scheduling, cueing and
  self-experimentation tool. Whether a cue helps you is something you find out by trying it.
- **It cannot guarantee it will run all night.** No Android app can — manufacturers stop background
  work in ways no API prevents. What it does instead is check everything checkable before you sleep,
  and tell you honestly in the morning when something stopped it.

See [docs/SLEEP_SCIENCE.md](docs/SLEEP_SCIENCE.md) for a longer, plainer account of what is and is
not knowable from a phone.

---

## Design principles

**1. Never conflate getting into bed with falling asleep.** These are separate settings, sleep onset
is derived from them, and it is labelled *estimated* everywhere it appears. A cue set for "4h 30m
after I fall asleep" would otherwise land half an hour early every single night.

**2. Sensible defaults, but never fight them.** Six-hour sleepers, ten-hour sleepers, day sleepers,
shift workers and irregular sleepers are all first-class. Almost everything is overridable —
including whether the app does anything at night at all.

**3. Say what you know, and how well you know it.** Estimates are marked as estimates. Statistics
are described as counts of what you recorded, not measurements. Experimental features explain what
they cannot do, not just what they can.

---

## Getting it

**[⬇ Download the latest APK from Releases](../../releases/latest)**

Then see **[DOWNLOAD.md](DOWNLOAD.md)** — installing takes about two minutes, and that page
covers allowing the install, the Samsung specifics, and verifying the download.

Requires Android 8.0 or newer. No Play Store, no account, no root.

Prefer to build it yourself? You do not have to trust a binary — there are no API keys and no
proprietary dependencies, so cloning it and pressing Run in Android Studio is all it takes:

- **[BUILDING.md](docs/BUILDING.md)** — build from source (Android Studio, or command line)
- **[INSTALL.md](docs/INSTALL.md)** — signing your own release build

## Documentation

| Document | What's in it |
|---|---|
| [DOWNLOAD.md](DOWNLOAD.md) | Downloading and installing the APK from Releases |
| [BUILDING.md](docs/BUILDING.md) | How to build the APK |
| [INSTALL.md](docs/INSTALL.md) | Installing a third-party APK, signing a release |
| [PERMISSIONS.md](docs/PERMISSIONS.md) | Every permission, why it exists, what breaks without it |
| [PRIVACY.md](docs/PRIVACY.md) | What is stored, where, and what leaves the device (nothing) |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | How it is built, and *why* the overnight design is what it is |
| [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Cues didn't play? Start here. Includes Samsung specifics |
| [SLEEP_SCIENCE.md](docs/SLEEP_SCIENCE.md) | Honest limits of what a phone can infer about sleep |
| [CONTRIBUTING.md](docs/CONTRIBUTING.md) | How to help |

## Requirements

- Android 8.0 (API 26) or newer
- No root
- No Google Play Services

Developed against a Samsung Galaxy in mind — One UI is unusually aggressive about stopping
background apps, and the app detects and explains that — but nothing is Samsung-specific and it
works on any Android phone.

## Status

Feature-complete for what it set out to be:

- Reliable overnight audio cue engine
- Flexible scheduling (eight timing modes, day-specific schedules, one-off overrides)
- Dream journal and daytime reality checks
- Wake Back To Bed
- Optional sleep-stage estimation from accelerometer and/or microphone
- Optional adaptive cue placement
- Personal A/B experiments and local statistics

Everything experimental is off by default and says in the UI what it cannot do.

Designed for but not built: a Wear OS companion. The sensor layer sits behind an interface so one
could be added without reworking the cue engine — see [ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Licence

[GPL-3.0-or-later](LICENSE). If you distribute a modified build, you must publish your source.
