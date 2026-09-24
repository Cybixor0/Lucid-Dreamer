# Permissions

Every permission the app declares, why it exists, and what happens if you refuse it.

The same information is in the app under **Settings > Privacy**, so you can check it before *and*
after installing.

**Not declared, deliberately: `android.permission.INTERNET`.** See [PRIVACY.md](PRIVACY.md).

---

## Granted automatically (normal permissions)

### `USE_EXACT_ALARM`
**What:** schedules each cue at an exact time.

**Why:** without exact alarms, Android defers the app's alarms to its own maintenance windows, which
can be up to an hour late — useless when the entire point is placing a cue at a particular moment in
the night. This permission is intended for alarm-clock applications, which this genuinely is: it
wakes you for WBTB.

It also exempts the app from the `RESTRICTED` App Standby bucket, which caps an app at one alarm
per day and would be fatal here.

**If refused:** it cannot be refused — it is granted at install and is not user-revocable. On
Android 12 the older `SCHEDULE_EXACT_ALARM` is used instead, which *can* be revoked; the app detects
that and warns you.

> Google Play restricts this permission to alarm and calendar apps. That is a Play publishing
> policy, not a platform mechanism, and this app is not distributed through Play.

### `SCHEDULE_EXACT_ALARM` (Android 12 only, `maxSdkVersion="32"`)
The equivalent on older versions, where `USE_EXACT_ALARM` does not yet exist. This one is
user-revocable; if it is revoked the app tells you cues may drift.

### `RECEIVE_BOOT_COMPLETED`
**What:** lets the app re-arm tonight's cues after a restart.

**Why:** Android cancels all pending alarms when a device shuts down.

**If refused:** cannot be refused. Without it, a restart mid-night would lose the remaining cues.

### `WAKE_LOCK`
**What:** keeps the CPU awake briefly around each cue.

**Why:** AlarmManager holds a wake lock only while the receiver runs. Without taking over, the
device can suspend before the sound is produced.

**If refused:** cannot be refused. Held for seconds at a time, never across the night.

### `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
**What:** allows the session service and the cue playback service to run in the foreground.

**Why:** modern Android blocks background audio outright unless a foreground service is running, and
the failure is silent — no error, no sound.

**If refused:** cannot be refused.

### `FOREGROUND_SERVICE_MICROPHONE`
**What:** allows the session service to use the microphone while it runs.

**Why:** only needed for optional microphone sleep sensing. It is claimed at runtime **only** when
you have switched that on *and* you started the session from the open app.

**Note:** Android will not grant microphone access to a service started from the background, so on
nights the session arms itself automatically the app falls back to movement-only sensing and records
that it did. If you never enable microphone sensing, this is never used.

### `VIBRATE`
**What:** optional vibration alongside or instead of a cue.

**Why:** a silent alternative if you share a bed.

**If refused:** cannot be refused. Only used if you enable it on a cue.

---

## You are asked (runtime permissions)

### `POST_NOTIFICATIONS` (Android 13+)
**What:** the ongoing session notification, WBTB wake-up, and reality-check reminders.

**Why:** the session notification is the control surface at 4am — pause, skip, stop, back-to-bed —
reachable without unlocking the phone. It is also part of what keeps Android from deciding the app
is idle.

**If refused:** the session still runs and cues still play, but you lose the controls, and the app
is meaningfully more likely to be stopped overnight. The app will keep working and will not nag.

### `RECORD_AUDIO`
**What:** three optional things — recording your own voice as a cue, voice dictation in the journal,
and microphone sleep sensing.

**Why:** some people find their own voice a far more effective cue than any tone. Sleep sensing uses
it to listen for movement and for slow rhythms that may be breathing.

**When:** only when you actually use one of those features. It is not requested at startup, and
sleep sensing is off by default.

**If refused:** every built-in sound, text-to-speech cue, typed journal entry and movement-only
sensing works exactly as before. Nothing else is affected.

> Microphone sensing **never records**. Audio is analysed in short windows in a reusable buffer and
> discarded immediately; only derived numbers are kept, and there is deliberately no debug mode that
> saves raw audio. See [PRIVACY.md](PRIVACY.md) for the precise details.

---

## Special access (you are sent to a settings screen)

### `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
**What:** asks Android to stop suspending the app while the screen is off.

**Why:** this is the single largest factor in whether overnight cues actually play. Without it,
Doze and the standby buckets can freeze the app for hours.

**If refused:** cues will frequently not play. The app detects this and tells you the next morning
rather than leaving you to guess.

> Google Play restricts requesting this directly. Again — Play policy, not a platform rule, and
> "core function adversely affected" is an accurate description of an overnight cueing app.

### `ACCESS_NOTIFICATION_POLICY` (optional)
**What:** reads your Do Not Disturb configuration.

**Why:** so the app can warn you **at bedtime** that DND is set to silence alarms, instead of you
discovering in the morning that the night was silent. Cues play on the alarm stream, which most DND
settings allow through — but "total silence" mode blocks even that.

**If refused:** everything works; you simply do not get that warning.

---

## Manufacturer settings (not Android permissions)

On Samsung and several other manufacturers, permissions alone are not enough. See
[TROUBLESHOOTING.md](TROUBLESHOOTING.md) — the in-app **Settings > Reliability** screen checks these
and links straight to them.
