# Installing

This app is not distributed through Google Play. You install it as a third-party APK, which you
either built yourself or obtained from someone you trust.

## Installing an APK

1. Copy the APK to your phone (USB, or any file transfer you like).
2. Open it with a file manager.
3. Android will ask whether to allow installing unknown apps from that file manager. Allow it.
4. Install.

On **Samsung One UI** the prompt is: *Settings > Apps > [your file manager] > Install unknown
apps > Allow from this source*. You can turn this back off afterwards; it only affects installing.

Android may warn that the app was not scanned by Play Protect, or offer to send it to Google for
scanning. That is expected for any sideloaded app. You may decline.

### Via adb

```bash
adb install -r app-debug.apk
```

## Debug build or release build?

The **debug APK** (`./gradlew assembleDebug`) installs immediately and works fully. It is larger,
slower to start, and has debugging enabled. For personal use it is perfectly fine.

The **release APK** is smaller and faster, but must be signed before Android will install it.

## Signing your own release build

Because this app is not on Play, there is no shared signing key — you use your own.

**1. Generate a keystore** (once; keep it safe, and keep a backup):

```bash
keytool -genkey -v -keystore lucid-release.jks \
        -keyalg RSA -keysize 4096 -validity 10000 -alias lucid
```

**2. Create `keystore.properties`** in the project root — it is gitignored, and must never be
committed:

```properties
storeFile=/absolute/path/to/lucid-release.jks
storePassword=...
keyAlias=lucid
keyPassword=...
```

**3. Add a signing config** to `app/build.gradle.kts`. It is deliberately not included by default,
so that the repository never implies a key it does not have:

```kotlin
android {
    val keystoreProperties = java.util.Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

**4. Build:**

```bash
./gradlew assembleRelease
```

The APK is at `app/build/outputs/apk/release/app-release.apk`.

### Keep the key

If you lose it, you cannot upgrade an installed copy in place — Android will refuse an update signed
with a different key, and you would have to uninstall first, **which deletes your dream journal**.
Export your data before ever doing that (Settings > Privacy > Export).

## Upgrading

Installing a newer APK signed with the same key upgrades in place and keeps all your data. The app
re-arms any in-flight session automatically after being replaced.

## Uninstalling

Uninstalling deletes everything — the journal included. There is no cloud copy, by design. Export
first if you want to keep it.

## First run

After installing, before trusting it with a real night:

1. Work through setup, or skip it — the defaults are safe either way.
2. Go to **Settings > Reliability**. This checks permissions, battery settings, Do Not Disturb,
   alarm volume and audio routing, and links directly to whatever needs changing.
3. Use **"Play a cue in 2 minutes"**, lock the phone, and put it down. If you hear it, the alarm
   path works on your device.
4. Use **"Dry run"** to hear the whole night's cues in three minutes and judge the volume.

Step 2 is not optional on a Samsung. See [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
