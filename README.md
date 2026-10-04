# SafeChildAI

SafeChildAI is an Android application for child safety and parent-child coordination. It is built with Kotlin, Jetpack Compose, Firebase, location services, and QR-based pairing.

## Open the project

1. Install Android Studio with a compatible Android SDK.
2. Clone this repository and open the project root in Android Studio.
3. Add your own Firebase Android configuration as `app/google-services.json`.
4. Add your Google Maps key to `local.properties` as `MAPS_API_KEY=your_key`.
5. Sync Gradle and run the `app` configuration on a device or emulator.

`local.properties`, Android Studio workspace files, and build outputs are local and are not part of the repository. Do not commit API keys or service account credentials. Restrict Firebase and Maps keys in their provider consoles.

## Firebase setup

The app and Cloud Functions use Firebase Authentication, Cloud Firestore, and Firebase Functions. Configure a Firebase project for your own deployment, then review `firestore.rules` and the functions in `functions/` before deploying.

## Project structure

- `app/` — Android application source
- `functions/` — Firebase Cloud Functions
- `firestore.rules` — Firestore security rules

## License

No license has been selected yet. Ask the project owner before reusing or redistributing this code.