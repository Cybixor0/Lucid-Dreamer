# Architecture

This document explains how the app is put together, and — more usefully — *why* the overnight
parts are built the way they are. Most of those decisions look strange until you know which
platform behaviour forced them.

## The one rule that shapes everything

> **AlarmManager owns *when*. A service owns *what*. They are never coupled.**

A cue's time is held by the system alarm table, not by any running code. If every process of this
app is killed at 3am, the remaining cues still fire.

### Why not just run a service with a timer?

Because **a foreground service does not keep the CPU awake.** This is the most common false belief
about Android background work, and it fails silently.

In deep Doze the platform explicitly *ignores app wake locks* and the processor suspends. A
`while (true) { playIfDue(); delay(30_000) }` inside a foreground service does not tick. It stalls,
then fires a burst of late work when the device next wakes. A phone charging on a nightstand,
motionless, screen off, is the textbook deep-Doze scenario — which is to say, exactly our case.

### Why `setAlarmClock` for every cue, not just the alarm-like ones

| Mechanism | Fires in Doze? | Throttled? |
|---|---|---|
| `set` / `setWindow` | Deferred to maintenance windows | Heavily |
| `setExactAndAllowWhileIdle` | Yes | ~7 per hour |
| `setAlarmClock` | Yes — system leaves low-power mode to deliver | No |

Seven per hour cannot carry a night of cues 20 minutes apart. `setAlarmClock` is not throttled at
all, so it is used for every cue.

The cost is cosmetic, not functional: `setAlarmClock` populates the system's "next alarm" slot, so
the lock screen shows the next cue instead of the user's morning alarm. That is a genuine trade-off,
so it is a setting (**Precise timing**) with an honest explanation, defaulting to on — a cue that
does not fire is worse than a surprising lock screen.

The watchdog uses `setExactAndAllowWhileIdle` instead, at 10-minute intervals: deliberately inside
the ~7/hour budget, and deliberately not visible on the lock screen.

### Why every cue is armed up front

Cues are **never chained** ("when cue N fires, schedule N+1"). One process death mid-chain would
lose the entire remainder of the night. All cues are armed immediately, and re-armed idempotently
on every recovery event.

Idempotency comes from `CuePlanner.requestCode()`, which derives a stable alarm request code from
`(night, ruleId, indexWithinRule)`. Re-arming updates existing alarms instead of duplicating them.

### Why the random seed is persisted

A profile with randomised cue times generates them from a seed created **once per night and stored
on the session row**. Every subsequent replan — reboot, timezone change, app update, watchdog
repair — reuses it and reproduces the identical night.

Without this, a crash at 3am would silently reshuffle every remaining cue, and "the app moved my
cues" would be unreproducible.

## Foreground services

Two, with different jobs and different types.

**`SleepSessionService`** — `specialUse`. Runs for the night. It carries the ongoing notification,
its controls, and a proof-of-life heartbeat. It is *not* the timing mechanism.

`specialUse` requires the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` manifest property; the service will not
start without it. `dataSync` is deliberately avoided everywhere — it is capped at six hours per day,
which would kill an eight-hour night outright.

**`CuePlaybackService`** — `mediaPlayback`. Started per cue, plays it, stops. This type is required
because modern Android silently blocks background audio unless a foreground service is running. It
also has no time limit and, not being a while-in-use type, can be legitimately started from the
background by an exact alarm.

### Why cues never play from a BroadcastReceiver

Two reasons, both fatal:

1. AlarmManager holds a CPU wake lock only for the duration of `onReceive`. Once it returns, the
   device may suspend again — possibly before any sound has been produced. `CueAlarmReceiver` takes
   its own wake lock *before* returning, and `CuePlaybackService` releases it when playback ends.
2. Background audio without a foreground service fails **silently**: no exception, no sound.

## Audio

All cues play with `USAGE_ALARM` + `CONTENT_TYPE_SONIFICATION`. This is not a style preference.
Recent Android hardens background audio, and the documented waiver is holding the exact-alarm
permission *and* acting on `USAGE_ALARM` streams. `USE_EXACT_ALARM` and `USAGE_ALARM` are a matched
pair; break one and cues stop making sound with no error anywhere.

Consequences, and how they are handled:

- **Subtlety cannot come from a quieter stream.** It comes from per-player gain
  (`CuePlayback.volume`, typically 0.02–0.15), applied to the samples themselves and calibrated per
  output route — a gain that is a whisper through a phone speaker is a shout in earbuds.
- **`setStreamVolume` is never called.** It is ignored in the background on recent Android, and it
  would trample the volume the user set for their real morning alarm.
- **Audio focus is `GAIN_TRANSIENT_MAY_DUCK`, and playback proceeds even if it is denied.** Plain
  `GAIN` on Android 12+ triggers a forced fade-out and leaves the other app muted until it
  re-requests focus — which would permanently kill the white-noise app this audience very likely
  runs all night.
- **Every cue gets 500–800 ms of leading silence.** Bluetooth earbuds take a few hundred ms to come
  out of sniff mode; without a run-up, the front of a one-second bell is simply missing.
- **Playback verifies itself.** The playback head is polled; if it never advances, the outcome is
  recorded as a failure rather than optimistically as success. Silent failure is the normal failure
  mode here and must be instrumented against.

Cues are **pre-decoded to PCM when the session arms**, not at fire time. No long-lived `MediaPlayer`
is held across the night — that *usually* works, which is the worst possible property for a 4am
alarm. Built-in tones are **synthesised on the device** (`ToneSynth`), so the repository contains no
binary audio assets at all.

Text-to-speech cues are rendered to a WAV file at arm time. A TTS engine cold-starting at 04:37 on a
Doze-suspended phone is a well-known way to get silence instead of a cue.

## Recovery

Nothing in memory survives the night on a real phone, so nothing important lives there.

- **Session and cue plan are persisted in Room.** Any cold start can reconstruct the whole night.
- **`replan()`** is the single idempotent recovery entry point, used by boot, package replacement,
  clock and timezone changes, and every watchdog heartbeat.
- **Cue rules are stored as wall-clock rules, not resolved timestamps.** This is precisely why a
  timezone change or DST transition can be handled by recomputing: the rules still mean what the
  user intended.
- **The watchdog** fires every 10 minutes, writes a heartbeat, re-arms anything dropped, restarts
  the session service if it was killed, and reschedules itself. It works even when the service is
  dead — which on some devices it will be.
- `START_STICKY` is used on the session service as free insurance, and relied upon for nothing: it
  does not apply to force-stops or most manufacturer kill paths.

## The morning reliability report

Heartbeats are written every 2 minutes by the service and every 10 by the watchdog. Each cue records
its intended and actual fire time.

`ReliabilityReport` reconstructs the night from those rows and attributes the failure — Samsung's
sleeping-app manager, battery optimisation, a restricted standby bucket, Do Not Disturb silencing
alarms, a missing audio route, or an admitted "cause unclear".

Treating the session start and end as implicit heartbeats matters: the characteristic Samsung
failure is suspension at 02:00 with nothing afterwards, which produces no gap *between* heartbeats,
only silence after the last one.

This is the highest-leverage feature in the app. It converts an invisible, unattributable failure —
which users experience as "the app is broken" — into a specific, one-tap fix.

## Layout

```
com.lucidreamer
  core/        time and serialisation helpers, event log, device status, notifications
  data/        Room entities/DAOs, DataStore settings, repositories, JSON backup
  domain/      schedule resolver, cue planner, WBTB config, reliability report
  audio/       PCM buffers, tone synthesis, WAV I/O, decoding, routing, playback
  service/     alarm scheduler, foreground services, receivers, session manager
  ui/          onboarding, dashboard, journal, cues, schedule, checks, stats, settings, dev
```

`domain/` is pure Kotlin over `java.time` with no Android imports, which is what makes the awkward
cases — DST, midnight wrap, day sleepers, constraint interaction — directly unit-testable. That is
where the test coverage is concentrated.

There is **no DI framework**. `AppContainer` is a hand-rolled service locator on `Application`: less
machinery, no annotation processing, one fewer thing that can break a build.

## Seams left for later phases

- `CueRule.stageGate` and `StageGate` already thread through the planner. Adaptive scheduling
  becomes a second resolution pass that nudges cues within their window and falls back to the fixed
  plan when confidence is low.
- A `SensorSource` interface over accelerometer, microphone and (potentially) a watch, emitting
  timestamped feature frames, plugs into the session service without touching the alarm path.
- The cue path deliberately has **no dependency on sensing**. If sensing fails or is disabled, the
  full fixed schedule still runs. This is not incidental — a background-started service cannot
  acquire microphone access on modern Android, so the sensing layer *must* be an optional refinement
  over a statically-armed baseline, never a prerequisite.
