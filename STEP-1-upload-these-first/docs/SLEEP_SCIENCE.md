# What a phone can and cannot know about your sleep

This document exists because sleep apps routinely overclaim, and because the difference between
"measured" and "guessed" matters a great deal if you are going to make decisions based on it.

## The short version

**A phone cannot measure sleep stages.** Sleep staging is defined by electrical activity in the
brain, eye movement and muscle tone — EEG, EOG and EMG. A phone has none of these sensors and no
way to approximate them directly.

Anything any app tells you about your sleep stages is inference from indirect signals. That
inference can be better or worse, but it is never measurement.

## What this app currently does

By default, it schedules and plays cues at times **you** specify, using a model of your night built
from values **you** entered. Sleep sensing is off unless you switch it on.

When you do switch it on, it combines three things, none of which observes REM:

1. **A population cycle model** — roughly 90-minute cycles, REM lengthening towards morning, deep
   sleep concentrated early. This is arithmetic about people in general, not an observation of you.
   On its own it is **capped below the threshold the app will act on**, precisely because it knows
   nothing about tonight.
2. **Accelerometer actigraphy** — the Cole–Kripke algorithm, genuinely validated for sleep versus
   wake. It carries **no** information about REM versus non-REM.
3. **Breathing regularity from the microphone** — respiration is more irregular in REM, which is
   real and weak. It is weighted so it can never dominate the result on its own.

The output is a probability distribution with a confidence value, displayed as e.g. "Possible REM —
41% (low confidence)". Below a confidence threshold the app says it is too uncertain to act on and
falls back to your fixed schedule.

That is the honest ceiling of what a phone can do. It is a better-informed guess than the calendar
alone. It is not a measurement, and the app never calls it one.

## What sleep cycles actually look like

Sleep runs in cycles of roughly 90 minutes, though 70–120 is normal and it varies between people and
between nights.

Two things matter for cue timing:

- **Deep sleep (slow-wave sleep) dominates the first part of the night.** Cueing then is both least
  likely to reach you and most costly if it wakes you.
- **REM periods lengthen through the night.** The last few hours contain most REM, which is why
  virtually every lucid-dreaming technique targets the early morning.

This is why the app's defaults place cues from about five hours after sleep onset, and why WBTB is
conventionally done after five to six hours. It is a population average applied to you, not an
observation of you.

## What each sensor can honestly claim

The detail behind the three inputs above:

**Accelerometer (actigraphy)** — the only method here with real literature behind it. Movement
patterns distinguish sleep from wake reasonably well; published algorithms reach roughly 85–90%
sensitivity for sleep. But specificity for *wake* is poor — actigraphy tends to score quiet
wakefulness as sleep — and critically, **it cannot distinguish REM from non-REM at all.**

**Microphone** — could detect movement, breathing sounds and snoring. Breathing becomes more
irregular during REM, so a breathing-regularity index is a *weak, indirect* correlate. It is not REM
detection, and calling it that would be dishonest.

**Cycle modelling** — projecting 90-minute cycles forward from sleep onset. Population-average
arithmetic. Useful as a prior, worthless as evidence about a specific night.

Combined, these produce a sensible probability distribution over awake / light / deep /
possible-REM. That is a genuinely useful thing for placing a cue. It is not a sleep study, and this
app does not present it as one.

As implemented, it:

- is entirely optional and off by default
- expresses itself as probabilities with a confidence value — "possible REM, 41%", never
  "REM detected"
- says plainly when confidence is too low to be useful, and falls back to your fixed schedule
- processes everything on the device, discarding audio immediately and storing none
- explains what it cannot do, in the UI, where the feature is
- can never *lose* you a cue through a weak guess — an unusable estimate always means "play as
  scheduled"

## On lucid dreaming itself

Honest summary of the evidence:

- **Lucid dreaming is real** and has been verified in the laboratory, most famously through
  pre-agreed eye-movement signals from within REM sleep.
- **Induction techniques have modest and inconsistent support.** MILD and WBTB have the most
  encouraging results; effect sizes vary enormously between studies and between individuals.
- **External cues during REM have some experimental support**, mostly from sleep-laboratory work
  with equipment that can actually detect REM. A phone playing a sound at a guessed time is a much
  weaker version of that.
- **Dream recall is the prerequisite.** If you cannot remember dreams, you cannot know whether
  anything is working. Keeping a journal is the intervention with the best evidence-to-effort ratio
  in this entire field, and it needs no app at all.

## What this means for using the app

Treat it as an experiment you are running on yourself, with a sample size of one and no control
group. That is not a criticism — it is genuinely the correct frame.

- Change one thing at a time
- Give each configuration a couple of weeks
- Write everything down, including nights where nothing happened
- Expect the statistics to be noisy; the app deliberately refuses to report a "success rate" from a
  handful of nights

The app tries hard to be a good instrument. It cannot be evidence on its own.

## Sleep comes first

A practical point that lucid-dreaming material often skips: **the techniques cost sleep.** WBTB
deliberately fragments your night. Cues can wake you. Chronic sleep deprivation has well-documented
effects on mood, cognition and health, and they are considerably more certain than any of the
benefits discussed above.

If a configuration is costing you real sleep, that is a reason to change it, not to push through.
The defaults here are conservative — few cues, late, quiet — for exactly that reason.
