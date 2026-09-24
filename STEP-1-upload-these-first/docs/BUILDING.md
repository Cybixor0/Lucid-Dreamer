# Building

The app is a single Gradle module with no proprietary dependencies, no API keys and no Google
Play Services. Cloning and building should be entirely uneventful.

## Requirements

- **JDK 17** or newer
- **Android SDK** with platform API 36 and build-tools 36.0.0
- Internet access on the first build only, to download Gradle dependencies

## Option 1: Android Studio

1. Clone the repository.
2. Open the project folder in Android Studio.
3. Accept the prompt to install any missing SDK components.
4. **Run > Run 'app'**, or **Build > Build Bundle(s)/APK(s) > Build APK(s)**.

That is all. There is nothing to configure, no keys to obtain and no accounts to create.

## Option 2: Command line with an existing SDK

Create `local.properties` in the project root pointing at your SDK:

```properties
sdk.dir=/path/to/Android/Sdk
```

Then:

```bash
./gradlew assembleDebug        # debug APK, installable as-is
./gradlew test                 # unit tests
./gradlew lintDebug            # static analysis
./gradlew assembleRelease      # release build (needs signing, see INSTALL.md)
```

On Windows use `gradlew.bat`.

The debug APK appears at `app/build/outputs/apk/debug/app-debug.apk`.

## Option 3: Portable toolchain (no system install)

If you would rather not install a JDK or Android Studio system-wide, `tools/setup-toolchain.ps1`
downloads a JDK and the Android command-line tools into `.toolchain/` inside the project directory.
Nothing is added to `PATH`, nothing touches the registry, and no administrator rights are needed —
deleting `.toolchain/` reverts it completely.

```powershell
.\tools\setup-toolchain.ps1     # one-off, ~700 MB
.\build.ps1                     # assembleDebug
.\build.ps1 test lintDebug
```

`build.ps1` sets `JAVA_HOME` for that single invocation only.

## Versions

Everything is pinned in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml):

| | |
|---|---|
| Android Gradle Plugin | 8.13.2 |
| Gradle | 8.14.5 |
| Kotlin | 2.2.21 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 (Android 8.0) |

These are a combination verified to build, not the newest of everything. AGP 9.x is available but
brings DSL changes with no benefit for a project this size.

## Dependencies

Deliberately few: Compose, Lifecycle, Navigation, Room, DataStore, coroutines and
kotlinx-serialization. All are AndroidX or JetBrains.

Notably absent, and on purpose:

- **No DI framework** — a hand-rolled container avoids annotation processing entirely
- **No ExoPlayer** — platform `AudioTrack` is sufficient and far smaller
- **No charting library** — the few graphs use Compose `Canvas`
- **No WorkManager** — it is deferred in Doze, which makes it unsuitable for everything here

## Tests

```bash
./gradlew test
```

JVM unit tests cover the parts where a subtle bug would be invisible and expensive:

- Schedule resolution across midnight wrap, DST spring-forward and fall-back, day sleepers,
  duration-based versus wake-time-based schedules
- Every cue timing variant, constraint application, and drop reasons
- Plan determinism under a fixed seed, and alarm request-code stability
- Tone synthesis, PCM fades and gain, WAV round-tripping including odd-sized unknown chunks
- Reliability-report freeze detection and failure attribution
- Circular mean of times-of-day (a plain average of 23:50 and 00:10 gives midday)

## Reproducibility note

Debug builds are signed with the standard Android debug key, which is not reproducible between
machines. Release builds are signed with your own key — see [INSTALL.md](INSTALL.md).
