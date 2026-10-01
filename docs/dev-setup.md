# Development setup (WSL2 / Linux, no Android Studio)

These are the steps used to set up the build machine (Ubuntu 24.04 on WSL2). Versions are the ones
installed on 2026-09-29.

## Toolchain summary

| Component | Version | Location |
|---|---|---|
| Android SDK command-line tools | 23.0 (`commandlinetools-linux-16111833_latest.zip`) | `~/Android/Sdk/cmdline-tools/latest` |
| Platform-tools (adb) | 37.0.1 | `~/Android/Sdk/platform-tools` |
| SDK platforms | `android-36` (r2), `android-37.0` (r2) | `~/Android/Sdk/platforms` |
| Build-tools | 36.1.0 (installed), 36.0.0 (auto-installed by AGP as its default) | `~/Android/Sdk/build-tools` |
| Gradle (bootstrap only) | 9.8.0 | `~/.local/opt/gradle-9.8.0` |
| Gradle wrapper | 9.8.0, distribution SHA-256 pinned | `gradle/wrapper/` |
| JDK for compilation | Temurin 21, provisioned by Gradle | `~/.gradle/jdks` |

Build versions (see `gradle/libs.versions.toml`): AGP 9.4.1 with built-in Kotlin, Kotlin 2.4.20,
compileSdk 37, minSdk 33, targetSdk 36.

## 1. JDK

Gradle itself only needs a Java runtime (`openjdk-21-jre` is enough). Compilation needs a full JDK:
`settings.gradle.kts` applies the `foojay-resolver-convention` plugin and `app/build.gradle.kts`
requests `jvmToolchain(21)`, so on the first build Gradle downloads a JDK 21 into `~/.gradle/jdks`
when none is installed. Installing one system-wide works too and is picked up automatically:

```sh
sudo apt install openjdk-21-jdk-headless
```

## 2. Android SDK command-line tools

The build number comes from the "Command line tools only" section of
https://developer.android.com/studio or from `cmdline-tools;latest` in
https://dl.google.com/android/repository/repository2-3.xml (which also lists the SHA-1).

```sh
mkdir -p ~/Android/Sdk/cmdline-tools
cd "$(mktemp -d)"
curl -fLO https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
echo "e025545c62a8e64c7559119566a569fb1dec5f60  commandlinetools-linux-16111833_latest.zip" | sha1sum -c
unzip -q commandlinetools-linux-16111833_latest.zip
mv cmdline-tools ~/Android/Sdk/cmdline-tools/latest
rm commandlinetools-linux-16111833_latest.zip
```

## 3. Environment variables

Appended to `~/.bashrc` (open a new shell afterwards):

```sh
# >>> wristline android sdk >>>
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
# <<< wristline android sdk <<<
```

## 4. SDK packages and licenses

```sh
yes | sdkmanager --licenses
yes | sdkmanager "platform-tools" "platforms;android-36" "platforms;android-37.0" "build-tools;36.1.0"
```

Notes:

- Running `yes | ...` accepts the Android SDK license non-interactively. Read it first if you have
  not accepted it before (`sdkmanager --licenses` without `yes`).
- In cmdline-tools 23.0, `sdkmanager` is a deprecated wrapper around the new `android` CLI
  (`android sdk ...`). It reports that `--licenses` is no longer needed; the license is accepted
  during the package install and recorded in `~/Android/Sdk/licenses/android-sdk-license`.
- `platforms;android-37.0` is required because compose-bom 2026.09.00, Wear Compose 1.7.0 and
  OkHttp 5.5.0 declare a minimum compileSdk of 37. The app still targets API 36 (Wear OS 6).
- AGP downloads any other SDK component it needs (it installed `build-tools;36.0.0`) as long as
  the license has been accepted.

## 5. Gradle and the wrapper

Gradle is only needed once to generate the wrapper; afterwards always use `./gradlew`.

```sh
cd "$(mktemp -d)"
curl -fLO https://services.gradle.org/distributions/gradle-9.8.0-bin.zip
echo "$(curl -fsSL https://services.gradle.org/distributions/gradle-9.8.0-bin.zip.sha256)  gradle-9.8.0-bin.zip" | sha256sum -c
mkdir -p ~/.local/opt && unzip -q -d ~/.local/opt gradle-9.8.0-bin.zip && rm gradle-9.8.0-bin.zip

cd /path/to/wristline
~/.local/opt/gradle-9.8.0/bin/gradle wrapper --gradle-version 9.8.0 --distribution-type bin \
  --gradle-distribution-sha256-sum bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c
echo "sdk.dir=$ANDROID_HOME" > local.properties   # git-ignored; optional when ANDROID_HOME is set
```

## 6. Build and test

```sh
./gradlew --version
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleRelease          # R8 full mode + resource shrinking
./gradlew :app:lintRelease :app:installDebug
```

The first run downloads dependencies and the JDK; later runs work with `--offline`.
Lint treats `MissingTranslation` as an error: every string in `values/strings.xml` needs a
Korean entry in `values-ko/strings.xml`.

APKs land in `app/build/outputs/apk/{debug,release}/`.

### Release signing

`assembleRelease` signs with the debug key unless all four properties below are set in
`~/.gradle/gradle.properties` (never in the repository). Use an absolute path; `~` is not expanded.

```properties
wristline.storeFile=/home/<you>/.android-keys/wristline-upload.jks
wristline.storePassword=...
wristline.keyAlias=upload
wristline.keyPassword=...
```

## 7. Watch over wireless adb

1. On the watch: Settings > About watch > Software information > tap "Software version" 5 times to enable
   Developer options.
2. Developer options: turn on "ADB debugging" and "Wireless debugging".
3. Wireless debugging > "Pair new device": note the IP:port and pairing code.
4. In WSL2:

   ```sh
   adb pair <ip>:<pairPort> <code>
   adb connect <ip>:<port>        # port shown on the Wireless debugging screen (not the pairing port)
   adb devices
   ```

   WSL2 in NAT mode cannot see mDNS, so always give the IP explicitly. If WSL cannot reach the
   watch, run the same commands with the Windows `adb.exe` instead.

Useful commands:

```sh
adb shell am start -n dev.wristline.watch/.MainActivity
adb logcat --pid=$(adb shell pidof dev.wristline.watch)
adb exec-out screencap -p > shot.png
adb shell cmd locale set-app-locales dev.wristline.watch --locales ko-KR
```

### targetSdk 37 체크리스트

`targetSdk` stays 36 for now. Before raising it to 37 (Android 17):

- **Local network permission.** Apps targeting 37 need the runtime permission
  `ACCESS_LOCAL_NETWORK` for every connection to a local network address, HTTPS included; denied,
  a connection times out ([docs](https://developer.android.com/privacy-and-security/local-network-permission)).
  The manifest declares it; the request is still to be built (plan in `data/Address.kt`). Check
  whether a tailnet address (100.64.0.0/10) counts as local, which the docs do not say:

  ```sh
  # Wear OS 6 (Android 16): turn the enforcement on early, then reboot.
  adb shell am compat enable RESTRICT_LOCAL_NETWORK dev.wristline.watch && adb reboot
  # Android 17 with targetSdk 37: deny the permission, then pair with a LAN IP and with a 100.x address.
  adb shell pm revoke dev.wristline.watch android.permission.ACCESS_LOCAL_NETWORK
  adb shell am compat disable RESTRICT_LOCAL_NETWORK dev.wristline.watch   # undo
  ```

- **Background audio hardening.** Android 17 silences playback, fails audio focus and ignores
  volume calls from the background without a while-in-use foreground service
  ([docs](https://developer.android.com/about/versions/17/changes/bg-audio)). Read-aloud stops when
  the app is left or the screen goes off; confirm nothing tries to play after that:

  ```sh
  adb shell cmd audio set-enable-hardening throw    # all apps, any targetSdk; failures throw
  # start read-aloud on an answer, then press the side button or let the screen go off
  adb logcat | grep AudioHardening                  # expect no line for dev.wristline.watch
  adb shell cmd audio set-enable-hardening disable
  ```

## 8. Wear OS emulator (optional)

The emulator needs hardware acceleration through `/dev/kvm`. On this machine `/dev/kvm` exists
(`root:kvm`, mode 660) but the user is not in the `kvm` group, so the emulator is not installed yet.
To enable it:

```sh
sudo usermod -aG kvm "$USER"
# then restart WSL from Windows: wsl --shutdown, and reopen the terminal
test -w /dev/kvm && echo "KVM ok"
```

Then install the Wear OS 6 image and create a 480x480 round AVD (the size of Galaxy Watch Ultra).
For API 36 the Wear image is published as `android-wear-signed`, not `android-wear`:

```sh
yes | sdkmanager "emulator" "system-images;android-36;android-wear-signed;x86_64"
avdmanager create avd -n wear_ultra -k "system-images;android-36;android-wear-signed;x86_64" -d wearos_xl_round
```

`wearos_xl_round` is the 480x480, 1.5" round device definition (`avdmanager list device | grep -i wear`
shows the others: `wearos_large_round` 454x454, `wearos_small_round` 384x384).
Run headless with `emulator -avd wear_ultra -no-window -no-audio` and use the adb commands above.
