# Troubleshooting

## "My cues didn't play"

**Open the app.** If a night did not go to plan, the dashboard tells you what happened and, usually,
exactly which setting caused it. That report is built from proof-of-life heartbeats recorded through
the night, so it is not guesswork.

Then work down this list.

### 1. Settings > Reliability

Checks everything checkable in one screen: notification permission, exact alarms, battery
optimisation, standby bucket, Do Not Disturb, alarm volume and audio routing. Anything failing gets
a one-tap link to the right settings page.

### 2. Battery optimisation

The most common cause by a wide margin. The app must be allowed unrestricted background use.

### 3. Do Not Disturb

Cues play on the **alarm** audio stream, which most Do Not Disturb configurations allow through. But
"total silence" mode blocks even alarms, and then everything works perfectly except that you hear
nothing. The Reliability screen detects this.

### 4. Alarm volume

Cues ride on the alarm stream, so if your alarm volume is at zero, nothing is audible. Per-cue
volume is applied *on top* of that, which is how a cue can still be much quieter than an alarm.

### 5. Bluetooth headphones

Earbuds regularly disconnect or go flat overnight. Each cue has a setting for what to do when the
headphones it expects are not connected: play through the speaker, play quietly through the speaker
(the default), or skip.

Every cue already gets ~700 ms of leading silence so Bluetooth has time to wake up — without it, the
front of a short cue gets clipped.

---

## Samsung Galaxy / One UI

Samsung is significantly more aggressive than stock Android, and its behaviour is the single most
common reason this kind of app "doesn't work".

**What One UI does:**

- **"Put unused apps to sleep"** is **on by default**. Apps you have not opened for about three days
  are put to sleep, and their background work is restricted.
- After roughly sixteen days, apps move to **"deep sleep"**, which stops them entirely until you
  open them again. The app cannot recover from this on its own.
- **Adaptive battery**, **Background usage limits** and per-app **Battery optimisation** apply on
  top.
- **Auto-optimise** can restart the phone on a schedule, which ends an in-progress session. The app
  re-arms after a reboot, but any cue due during the restart is lost.

**What to change:**

1. `Settings > Battery > Background usage limits > Never sleeping apps` → **add Lucid Dreamer**
2. On the same screen, turn **"Put unused apps to sleep"** off
3. `Settings > Apps > Lucid Dreamer > Battery` → **Unrestricted**
4. `Settings > Battery > More battery settings` → consider turning **Adaptive battery** off

The Reliability screen offers a direct link to the "Never sleeping apps" list where One UI exposes
one. That deep link is undocumented and varies between firmware versions, so if the button is
missing, use the path above.

**The cruel detail:** a ten-minute screen-off test passes trivially while a real eight-hour night
fails. Testing this properly means an actual overnight run with the phone stationary and unplugged.

## Other manufacturers

Xiaomi (MIUI), Huawei, OPPO, vivo and OnePlus have comparable systems under different names —
usually "Autostart", "Battery saver" or "App launch management". The generic equivalents:

- Allow autostart / background activity for the app
- Set battery behaviour to unrestricted, not optimised
- Lock the app in the recents screen where that is offered

[dontkillmyapp.com](https://dontkillmyapp.com) maintains per-manufacturer instructions.

---

## Diagnosing it properly

**Developer mode** — tap the version number in Settings > About seven times.

It shows live session state, how many cues are armed, the next cue, what the *system* thinks the
next alarm is, service status, battery and standby state, DND filter, audio routing, and a full
event log of every plan, arm, fire, skip, drop and error.

The event log is the right thing to attach to a bug report. It survives to the morning, which
logcat does not.

---

## Specific problems

**Cues fire, but late.** Check exact-alarm permission. Without it Android batches alarms into its
own maintenance windows.

**The lock screen shows my cue instead of my morning alarm.** Expected. Precise timing uses
alarm-clock alarms, which claim the system's "next alarm" slot. Turn off **Settings > Audio cues >
Precise timing** to use a lower-profile mechanism — at the cost of cues possibly drifting by up to
an hour.

**Spoken cues are silent.** Check a text-to-speech engine is installed with a downloaded voice.
Speech is rendered to a file when the session starts, so a failure is reported at bedtime rather
than silently at 4am.

**A cue sound vanished.** Imported files are copied into app storage precisely to stop that. If the
original was imported before this was the case, re-import it.

**The app woke me up.** Lower the per-cue volume (subtle cues live around 2–15%), lengthen the
fade-in, and use the Dry run to judge it in bed before trusting it overnight.

**Nothing at all happens at night.** Check that a cue profile is selected and has enabled rules, and
that **Audio cues** is on. The cue editor shows exactly what would play tonight, and the reason any
cue was dropped.

**The battery drained overnight.** The cue engine is cheap — it wakes the phone only at cue times.
If drain is severe, something else is responsible; check Android's battery usage screen.

---

## Reporting a bug

Include:

1. The exported event log (Developer > Export)
2. Phone model, Android version, One UI version if Samsung
3. What you expected and what happened
4. The Reliability screen's state

Please do not include your dream journal — the event log contains none of its contents.
