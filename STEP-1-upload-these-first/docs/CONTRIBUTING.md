# Contributing

Contributions are welcome. A few things are worth knowing before you start.

## The non-negotiables

These are not style preferences; they are what the project is for.

**1. No network, ever.** The app does not declare `INTERNET` and never will. A pull request that
adds it, or any dependency that needs it, will be declined regardless of merit.

**2. Honesty about what the app knows.** No UI string may claim detection, measurement or accuracy
the app cannot deliver. Estimates are labelled as estimates. Where the app cannot determine
something, it says so rather than guessing. See [SLEEP_SCIENCE.md](SLEEP_SCIENCE.md).

**3. No analytics, telemetry, crash reporting, ads or accounts.** Also not negotiable.

**4. Don't fight the user's defaults.** New features are opt-in. If you find yourself writing "most
people want…", make it a setting.

**5. Keep dependencies minimal.** Each new one needs a real justification. The audio engine uses
platform APIs rather than ExoPlayer for exactly this reason.

## Before changing the overnight path

`AlarmScheduler`, `CueAlarmReceiver`, `CuePlaybackService` and `SessionManager` contain decisions
that look odd until you know which platform behaviour forced them —
`setAlarmClock` for every cue, `USAGE_ALARM` for all audio, arming every cue up front, the persisted
random seed.

Please read [ARCHITECTURE.md](ARCHITECTURE.md) first. Several of these guard against failures that
are **silent**: the app produces no error, no sound, and no log entry, and the user just has a bad
night. Changes here are hard to test and easy to get wrong.

## Testing

```bash
./gradlew test lintDebug assembleDebug
```

All three must pass.

New logic in `domain/` should come with unit tests — it is pure Kotlin with no Android dependencies
specifically so that it can be tested properly. The awkward cases are the valuable ones: DST
transitions, midnight wrap, day sleepers, constraint interaction, determinism under replanning.

For anything touching overnight behaviour, please also test on a real device, overnight, stationary
and unplugged. A ten-minute screen-off test passes trivially and proves very little.

## Code style

- Kotlin official style (`./gradlew lintDebug` will tell you)
- Comments explain *why*, not *what*. If a piece of code exists to work around a platform behaviour,
  say which one — that is the comment that will save someone an afternoon.
- User-facing strings: plain language, no jargon, and no exclamation marks at 4am.

## Adding a sleep-sensing implementation

This is the most-wanted missing piece, and the easiest to do badly.

If you take it on:

- It must be optional and off by default
- It must output probabilities with a confidence value, never a bare classification
- It must say when confidence is too low to act on, and fall back to the fixed schedule
- Raw audio must be processed in short windows and discarded immediately, unless the user has
  explicitly enabled a debug option
- The UI must state what it cannot do
- **The cue path must not depend on it.** If sensing fails, the full fixed schedule still runs. This
  is also a platform constraint, not only a design preference — a background-started service cannot
  acquire microphone access on modern Android.

## Licence

By contributing you agree your work is licensed under GPL-3.0-or-later. Please add the SPDX header
to new files:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
```

## Bug reports

See the reporting section of [TROUBLESHOOTING.md](TROUBLESHOOTING.md). The exported event log from
developer mode is by far the most useful thing you can attach — it is the only record that survives
to the morning.
