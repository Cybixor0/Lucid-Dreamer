# Changelog

## 0.1.0 — unreleased

First working version. Phases 1–4 of the planned build.

### Cue engine
- Alarm-driven overnight cue scheduling, every cue armed independently up front
- Eight timing modes: after sleep onset, after bedtime, before wake, absolute clock time, fraction
  of the night, repeating with jitter, random within a window, and after returning to bed from WBTB
- Per-cue sound, volume, fade in/out, repeat count, vibration and headphone requirements
- Six built-in tones, synthesised on the device — the repository ships no binary audio
- Imported audio files, voice recordings, and text-to-speech cues pre-rendered at session start
- Per-night limits: minimum sleep first, minimum gap, maximum count, stop before wake, quiet periods
- Deterministic replanning via a per-night persisted random seed

### Scheduling
- Bed time, sleep-onset latency and wake time kept as three independent values; onset always derived
  and always labelled as estimated
- Wake-time-based or duration-based schedules
- Day-specific schedule profiles with priority, plus one-off "tonight only" overrides
- Optional fully manual start ("I'm going to sleep now"), which replaces the estimate with a real
  onset anchor
- Correct handling of midnight wrap, daylight-saving transitions, timezone changes and day sleepers

### Reliability
- Foreground session service with lock-screen controls
- Ten-minute watchdog that re-arms dropped alarms and restarts a killed service
- Recovery after reboot, app update, clock change and timezone change
- Pre-flight checks for notifications, exact alarms, battery optimisation, standby bucket, Do Not
  Disturb, alarm volume and audio routing
- Morning reliability report reconstructing the night from heartbeats and attributing failures
- Samsung One UI detection with specific guidance and a direct settings link
- "Test a cue in 2 minutes" and "Dry run" self-tests

### Journal
- Full-text search, tags, dream signs with frequency counts, lucidity, mood, rating, favourites
- Voice dictation via the system recogniser, with an explicit privacy note
- Automatic linking of a dream to the session and cue that preceded it

### Reality checks
- Multiple reminder schedules: random, interval or fixed times, per day of week
- Custom prompts, optionally seeded from your own recurring dream signs

### WBTB
- Wake timing relative to onset, absolute, or before your alarm
- Configurable awake duration, confirmed or automatic return, spoken instructions, journal prompt

### Other
- Skippable, resumable onboarding
- Full settings tree, in-app privacy and permission explanations, JSON export
- Local statistics, disableable, framed as self-reported counts rather than measurements
- Developer mode with live diagnostics and an exportable event log
- Dark, light and a dim red "night" theme for looking at the phone at 4am

### Sleep sensing (experimental, off by default)
- Accelerometer actigraphy using the Cole–Kripke sleep/wake algorithm
- Optional microphone analysis: loudness, spectral features, and breathing-rhythm detection by
  autocorrelation of the loudness envelope
- Audio analysed in a reusable buffer and discarded immediately — never recorded, never written to
  disk, and no debug mode that saves raw audio
- Duty-cycled microphone (30s open per minute) with an in-app indicator and a one-tap "off for
  tonight" control
- Stage estimates as a probability distribution with a confidence value, labelled "possible REM"
  rather than "REM"
- The cycle model alone is capped below the confidence threshold the app will act on, so a
  population average can never drive cue placement
- Degrades honestly: no permission, no sensor, or a background-started session all reduce the mode
  and record why

### Adaptive cue timing (experimental, off by default)
- Per-cue sleep-stage conditions, held back or skipped according to the estimate
- An unusable estimate always means "play as scheduled" — a weak guess can never silently lose a cue
- Deferrals are persisted and re-armed, so they survive the process being killed

### Experiments
- A/B comparison of two cue profiles, with reproducible per-date arm assignment
- Six night outcomes including false awakening and "woke up", so a setup that costs sleep is visible
- Refuses to show a comparison below ten nights per arm, and frames results as "what happened"
  rather than "what works"

### Distribution
- GitHub Actions release workflow publishing an APK plus SHA-256 checksums
- Optional release signing from repository secrets; falls back to a debug build, labelled as such
- [DOWNLOAD.md](DOWNLOAD.md) covering install, Samsung specifics and download verification

### Not implemented
- Wear OS companion (designed for, not built)
