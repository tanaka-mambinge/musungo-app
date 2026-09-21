This is a new [**React Native**](https://reactnative.dev) project, bootstrapped using [`@react-native-community/cli`](https://github.com/react-native-community/cli).

# Getting Started

> **Note**: Make sure you have completed the [Set Up Your Environment](https://reactnative.dev/docs/set-up-your-environment) guide before proceeding.

## Step 1: Start Metro

First, you will need to run **Metro**, the JavaScript build tool for React Native.

To start the Metro dev server, run the following command from the root of your React Native project:

```sh
# Using npm
npm start

# OR using Yarn
yarn start
```

## Step 2: Build and run your app

With Metro running, open a new terminal window/pane from the root of your React Native project, and use one of the following commands to build and run your Android or iOS app:

### Android

```sh
# Using npm
npm run android

# OR using Yarn
yarn android
```

### iOS

For iOS, remember to install CocoaPods dependencies (this only needs to be run on first clone or after updating native deps).

The first time you create a new project, run the Ruby bundler to install CocoaPods itself:

```sh
bundle install
```

Then, and every time you update your native dependencies, run:

```sh
bundle exec pod install
```

For more information, please visit [CocoaPods Getting Started guide](https://guides.cocoapods.org/using/getting-started.html).

```sh
# Using npm
npm run ios

# OR using Yarn
yarn ios
```

If everything is set up correctly, you should see your new app running in the Android Emulator, iOS Simulator, or your connected device.

This is one way to run your app — you can also build it directly from Android Studio or Xcode.

## Step 3: Modify your app

Now that you have successfully run the app, let's make changes!

Open `App.tsx` in your text editor of choice and make some changes. When you save, your app will automatically update and reflect these changes — this is powered by [Fast Refresh](https://reactnative.dev/docs/fast-refresh).

When you want to forcefully reload, for example to reset the state of your app, you can perform a full reload:

- **Android**: Press the <kbd>R</kbd> key twice or select **"Reload"** from the **Dev Menu**, accessed via <kbd>Ctrl</kbd> + <kbd>M</kbd> (Windows/Linux) or <kbd>Cmd ⌘</kbd> + <kbd>M</kbd> (macOS).
- **iOS**: Press <kbd>R</kbd> in iOS Simulator.

## Congratulations! :tada:

You've successfully run and modified your React Native App. :partying_face:

### Now what?

- If you want to add this new React Native code to an existing application, check out the [Integration guide](https://reactnative.dev/docs/integration-with-existing-apps).
- If you're curious to learn more about React Native, check out the [docs](https://reactnative.dev/docs/getting-started).

# Troubleshooting

If you're having issues getting the above steps to work, see the [Troubleshooting](https://reactnative.dev/docs/troubleshooting) page.

# Android build reference

This project has two co-installable Android builds:

| Build | Command | Application ID | Launcher label | Metro |
| --- | --- | --- | --- | --- |
| DEV/debug | `npm start`, then `npm run android` | `com.musungo.mheadphones.dev` | `Musungo Headphones DEV` | Required |
| PROD/release | `./gradlew :app:assembleRelease` | `com.musungo.mheadphones` | `Musungo Headphones` | Not required |

The Kotlin namespace stays `com.musungo.mheadphones` for both variants. Only the debug application ID receives the `.dev` suffix, so native integrations continue to use the same namespace while Android keeps the app data and launcher entries separate.

## Publish a signed PROD release

Release APKs are built and uploaded by GitHub Actions when a `v*` tag is pushed. Release signing is intentionally unavailable unless the required keystore and properties are present; the release variant never falls back to the debug keystore.

### One-time GitHub setup

1. Create a release keystore somewhere outside this repository. Keep the keystore and passwords backed up securely because the keystore must remain the same for future app updates.

   ```sh
   keytool -genkeypair -v -keystore musungo-release.keystore \
     -alias musungo-release -keyalg RSA -keysize 2048 -validity 10000
   ```

2. In the repository's **Settings > Secrets and variables > Actions**, add these repository secrets:

   - `ANDROID_KEYSTORE_BASE64`: the base64-encoded contents of `musungo-release.keystore` (`base64 -w 0 musungo-release.keystore` on Linux; use `base64 musungo-release.keystore | tr -d '\\n'` on macOS).
   - `ANDROID_KEYSTORE_PASSWORD`: the keystore password.
   - `ANDROID_KEY_ALIAS`: `musungo-release` (or the alias chosen above).
   - `ANDROID_KEY_PASSWORD`: the key password.

   The workflow writes the keystore and `android/keystore.properties` only on the GitHub runner, uses them to assemble the release APK, verifies its signature, and removes them before the job ends.

### Publish a release

Push a version tag from the commit that should be released:

```sh
git tag v1.0.5
git push origin v1.0.5
```

The `Android release` workflow creates a GitHub Release for that tag and attaches `app-release.apk`. Debug builds remain available through `npm run android` and continue to use the `.dev` application ID.

## Release native-build fix

React Native 0.87's Fabric/New Architecture component descriptors were crashing during PROD startup when the release C++ build defined `NDEBUG`. The app could install successfully but immediately died with a native `SIGSEGV` before the first screen rendered.

The release CMake configuration in `android/app/src/main/jni/CMakeLists.txt` keeps generated Fabric/codegen objects ABI-compatible with React Native's packaged production library while retaining the app's existing assertion workaround. Do not remove the custom CMake configuration or re-add `-DNDEBUG` to that release configuration without retesting startup on an emulator.

## Widget background monitoring

The Android widget uses a native connected-device foreground service so its controls do not depend on the React Native activity remaining alive. A single control tap queues the action, reconnects to the bonded earbuds when necessary, and sends the command after connection.

While at least one widget is installed and the earbuds are connected, the service:

- applies connection and control updates from Jieli events;
- refreshes battery immediately after connection, control commands, settings events, and app resume;
- performs a 60-second battery safety refresh without changing the connection label;
- shows a silent, low-priority notification only during active monitoring.

Monitoring stops after the real Bluetooth session disconnects or the last widget is removed. A system Bluetooth reconnect event wakes monitoring again when the widget is still installed, so no widget tap is needed to refresh the connection. The service does not continuously scan in the background. Android force-stop is unsupported because it blocks widgets and services until the app is opened again.

# Learn More

To learn more about React Native, take a look at the following resources:

- [React Native Website](https://reactnative.dev) - learn more about React Native.
- [Getting Started](https://reactnative.dev/docs/environment-setup) - an **overview** of React Native and how setup your environment.
- [Learn the Basics](https://reactnative.dev/docs/getting-started) - a **guided tour** of the React Native **basics**.
- [Blog](https://reactnative.dev/blog) - read the latest official React Native **Blog** posts.
- [`@facebook/react-native`](https://github.com/facebook/react-native) - the Open Source; GitHub **repository** for React Native.
