# Brook

An Android reader for [freshgo](https://github.com/juev/freshgo) and other servers that speak the Google Reader API.

## Build

Requirements: JDK 21 and the Android SDK with platform 37.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # or any JDK 21
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest              # with an emulator or device attached
```

`local.properties` points Gradle at the SDK (`sdk.dir=...`); Android Studio writes it on its own.

## Run against a local freshgo

Start freshgo on all interfaces so the emulator can reach it:

```sh
export FRESHGO_DATABASE_URL=sqlite:///tmp/freshgo.sqlite
freshgo user create alice        # asks for the password
freshgo feed add -user alice https://example.org/
freshgo serve -listen 0.0.0.0:8080
```

In Brook, sign in with the server `http://10.0.2.2:8080`, the user name and the API password. Plain HTTP is allowed for `10.0.2.2` and `localhost` in debug builds only.
