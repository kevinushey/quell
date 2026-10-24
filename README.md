# Quell

An Android app for comparing vibration spectra from accelerometer recordings,
built to evaluate tuned mass dampers: overlay a baseline run against runs with
different damper masses and watch the resonance peak get quelled.

> **Experimental hobby project.** This was made for fun by someone who is not
> an NVH engineer, not a signal-processing expert, and not an Android
> developer. The physics is textbook (Welch PSDs, half-power bandwidth, Den
> Hartog's absorber optimum) but the implementation has had no professional
> review, the diagnoses are heuristics, and a phone taped to a car is not a
> calibrated measurement system. Treat every number as a hint, not a result.
> Issues and corrections are very welcome; expectations of support are not.
It was built around [phyphox](https://phyphox.org) "Acceleration" exports but
reads any CSV with a time column and x/y/z (or magnitude) columns, and can also
record directly from the phone's own accelerometer.

## Features

- Import one or many `.zip` / `.csv` files via the system picker, or share
  files to the app from phyphox, Drive or a file manager.
- Frequency response (power spectral density) via Welch's method with
  configurable segment size, window, overlap and smoothing.
- Overlay any number of runs with per-run colors, a legend, show/hide,
  rename, reorder and notes.
- Channels: X, Y, Z, |a| and "Total" (sum of per-axis power, orientation
  independent).
- Normalizations: none, peak, total power, median, z-score (dB).
- Diff view: pick a reference run, see each other run minus the reference in
  dB, with RMS / mean difference / correlation and the frequencies where they
  diverge most.
- Signal tab with raw waveform and spectrogram (time vs frequency) views.
- Damper tab: band RMS per run with percent change against a reference,
  dominant-peak frequency and damping ratio (half-power bandwidth), split-peak
  detection with the implied mass ratio, a Den Hartog tuning diagnosis
  (under-damped, over-damped, detuned, near optimal), band RMS versus damper
  mass, and ring-down results for tap tests.
- Tap-test recording mode: strike the structure, each ring-down is fitted for
  natural frequency and damping ratio on the spot; beating between two decay
  frequencies is flagged as a coupled absorber.
- Per-dataset analysis window (trim), experiment type (drive or tap) and
  damper mass.
- Reference-band normalization to correct for differing road input between
  runs; the footer reports averages and the resulting dB uncertainty.
- Wheel-order markers: enter speed and tire size to mark 1x/2x/3x wheel
  rotation frequency on the spectrum.
- Peak labels, pinch zoom, pan, tap crosshair readout, double-tap reset.
- Share any chart as PNG; export spectra or raw samples as CSV.
- Settings persist across launches; light/dark/dynamic color themes.

## Building

Requirements: JDK 17 and the Android SDK (platform 35, build-tools 35.0.0).

    export JAVA_HOME=/opt/homebrew/opt/openjdk@17   # or any JDK 17
    echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
    ./gradlew assembleDebug testDebugUnitTest

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install
with `adb install -r` or open the project in Android Studio.

## Layout

- `dsp/` -- pure Kotlin FFT, Welch PSD, resampling, normalization, peaks,
  spectrum diff, spectrogram. Covered by JVM unit tests in `app/src/test`.
- `data/` -- file parsing (phyphox zip / CSV), on-disk dataset store, settings.
- `record/` -- on-device accelerometer capture.
- `ui/` -- Jetpack Compose screens, custom canvas charts, view model.
- `export/` -- PNG / CSV sharing through a `FileProvider`.

## Status and caveats

- Tested on one Galaxy S23 and an emulator, against one person's phyphox
  exports. Other sensors, sample rates and CSV layouts may well break it.
- The tuning diagnosis assumes a single lightly damped mode with a classic
  tuned absorber under broadband excitation. Real cars are not that.
- Random error of each spectrum is shown in the footer. If a difference
  between runs is smaller than that, it is noise.
- No release signing, no Play Store listing, no promises.

## License

MIT, see `LICENSE`.
