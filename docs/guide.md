# Using Quell

A short tour of what the app is for and how a session goes, from a first
recording to a tuned damper. Everything here is a hobbyist's workflow, not a
measurement standard; see the caveats in the [README](../README.md).

## What the app measures

A phone accelerometer, fixed to something that vibrates, produces a time
series of acceleration in x, y and z. Quell turns that into a power spectral
density (PSD): how much vibration there is at each frequency. A resonance
shows up as a peak. A tuned mass damper fitted to the structure either lowers
that peak or splits it into two smaller ones with a notch between them. Most
of the app exists to make that comparison honest: same settings across runs,
an uncertainty figure next to every spectrum, and a baseline to compare with.

Three kinds of recording cover most uses:

- **General**: anything you want a spectrum of.
- **Drive**: the structure under its real excitation, for example a car on a
  road at steady speed. Drive runs take part in the Damper tab and in sweeps.
- **Tap test**: the structure struck sharply and left to ring. Each ring-down
  gives the natural frequency and damping ratio directly, in seconds rather
  than minutes, with no road needed.

## Getting data in

**Record on the phone.** Tap the sensor icon in the top bar. Choose Drive or
Tap test (or neither), mount the phone, and press Start. The screen shows the
live spectrum of the last four seconds, or in tap mode the ring-down fit of
each tap as it happens. Stop, name the run, save.

**Import.** The file icon opens the system picker; phyphox "Acceleration"
exports (zip or the CSV inside) and plain CSV files with a time column plus
x/y/z or magnitude columns both work, including semicolon or tab delimited
files with decimal commas. Files shared to Quell from Drive, Files or phyphox
arrive the same way.

**Mounting matters more than anything in the analysis.** The phone must move
with the structure. A rigid clamp or strong tape against a hard surface is
good; a seat cushion, a pocket or a phone holder on a flexible arm adds its
own resonances and hides the structure's.

## Reading a spectrum

The Spectrum tab overlays every visible run. The chips along the top choose
the channel (x, y, z, magnitude or Total, which sums the three axes and does
not care how the phone was oriented), the normalization, dB or linear, log or
linear frequency, and peak labels. The bar at the bottom sets the FFT segment
size, window and smoothing.

Two numbers in the footer deserve attention:

- **bin**: the frequency resolution. A 1024-point segment at 400 Hz gives
  0.4 Hz bins. Larger segments resolve closer peaks but average fewer times.
- **avg, +/- dB**: how many independent averages went into the estimate and
  the random error that follows. Two runs whose curves differ by less than
  that figure do not differ. The "Uncertainty band" switch in analysis options
  shades it around every curve.

Pinch to zoom, drag to pan, tap for a crosshair readout, double-tap to reset.
The overflow menu shares the chart as an image or exports every visible
spectrum as one CSV.

### Settling the analysis window

A drive recording usually starts before the drive does. On the Data tab, each
run's menu has "Trim time range": drag the handles over the envelope, or press
"Keep the steady part" and the app finds the longest stretch whose vibration
level stayed within 10 dB, which leaves the door slam and the idling outside.
Every tab uses the trimmed window from then on.

## Finding the problem mode

The Diff tab subtracts a reference run from every other run in dB. Choose the
reference from the chip (or from a run's menu on the Data tab). The cards list
the RMS and mean difference and the frequencies where the runs part ways.

Two tools help decide what a peak is:

- **Wheel order markers** (analysis options) draw the wheel rotation
  frequency and its harmonics for a given speed and tire size. A peak that
  sits on a marker and moves with speed is a wheel, not a body mode.
- **Reference-band normalization** scales each run so that its power in a
  band well away from the mode under study is equal. Road surface and speed
  change the overall level between runs; this removes that before comparing
  the peak. The footer names any run that does not cover the band.

The Signal tab shows the raw waveform (zoomable to single samples) and a
spectrogram, which is the quickest way to see whether a peak was there the
whole time or came from one event.

## Characterizing the structure with taps

A tap test needs no road. Fix the phone, choose Tap test, start, and strike
the structure near the phone several times, leaving about three seconds of
quiet after each. The screen shows the natural frequency, damping ratio and Q
of each ring-down and the running average. Tap runs appear on the Damper tab
with those figures, and "Analyze taps" on the Data tab lists every tap.

A tap test tells you the frequency the damper must be tuned near, and the
damping ratio tells you how sharp the resonance is, which bounds how much a
damper can gain.

### Finding the antinode

A damper works where the mode moves most. With the phone fixed anywhere on
the panel except a node, choose "Find antinode", start, and tap one spot three
or more times, press "Next location", move, and repeat. Each tap's ring-down
amplitude is divided by its impact strength, so a harder tap does not pass for
a better spot. The card ranks the locations, shows each one's mode shape
relative to the best, and says whether the leader is distinguishable from the
runner-up or needs more taps. The ranking is kept with the recording and can
be reopened from the Data tab.

## Tuning a damper

### Reading the Damper tab

With a reference run chosen and a target band set around the mode (the "Auto
band" button centres it on the reference's tallest peak), the Damper tab lists
every drive run's band RMS, its change against the reference, the dominant
peak and its damping ratio. Runs that split the peak get the implied mass
ratio from the two frequencies, and a Den Hartog diagnosis says whether the
absorber looks under-damped, over-damped, detuned low or high, or near
optimal, with a sentence on what to change. Treat the diagnosis as a hint: it
assumes a single lightly damped mode and a classic absorber.

### Running a sweep

A sweep is the methodical version: one parameter (mass, stiffness, position or
anything else) stepped across several runs against a baseline. From the Damper
tab or the overflow menu, open Sweeps and create one. Pick the baseline run,
then press "Record at ..." for each value; the Record screen fixes the
experiment type, says which run this is, and pre-fills the value. Existing
runs can be added from the menu.

The sweep screen plots the in-band peak against the parameter (damping ratio
for tap sweeps), with error bars, a weighted parabola fit and a one-sigma band
around it. The reading underneath is deliberately cautious. It will say that:

- the values differ by less than their uncertainty, and how long a run, or how
  many repeats, would resolve a 1 dB difference at the current settings;
- the curve bends the wrong way and the sweep should be extended in a
  direction;
- the fitted optimum lies outside the range tried;
- an optimum has been found but no run has confirmed it yet, in which case the
  record button offers exactly that value.

Repeated runs at one value are pooled. The table lists every run; the chart
shows pooled points. "Export CSV" in the sweep menu writes the table.

### What limits the result

Random error falls with the square root of recording length. A 17 s drive at
400 Hz with 1024-point segments gives about 1.3 dB per spectrum, which is not
enough to rank masses a few grams apart. Longer steady drives help most;
reference-band normalization helps when the road changes between runs; and
tap sweeps sidestep the road entirely when the question is only the damper's
own frequency and damping.

## Keeping the data

Everything lives in the app's private storage until you say otherwise.

- **Cloud sync** (Settings): choose a folder in Google Drive, Dropbox,
  OneDrive or local storage through the system picker. The app mirrors every
  recording, its settings and the sweeps into it a few seconds after each
  change while it is open, optionally with a CSV per run. "Restore from
  folder" brings everything back on a new phone and never overwrites a
  folder's own data before it has been restored.
- **Export**: raw samples per run, spectra as a wide CSV, sweep tables, and
  any chart as a PNG through the share sheet.

## A typical session

1. Mount the phone rigidly. Record a 60 s drive at steady speed with no
   damper: the baseline. Trim it to the steady part.
2. Do a tap test at the same spot to get the mode's frequency and damping,
   and an antinode search to see where the damper belongs.
3. Build the damper for roughly that frequency and start a mass sweep with the
   baseline as reference. Record each mass under the same road and speed.
4. Read the sweep. If it says the runs do not differ beyond their
   uncertainty, make the runs longer rather than adding values.
5. Check the Damper tab's diagnosis for the best run: a split peak with a
   balanced pair and a modest notch is what a well-tuned absorber looks like.
6. Let the folder mirror keep the runs, and export the sweep table for notes.
