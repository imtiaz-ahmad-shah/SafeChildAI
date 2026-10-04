# SafeChildAI

SafeChildAI is an Android app for parent–child safety check-ins and location sharing. A parent and child connect by scanning a short-lived QR code. Once connected, the child can share their location, send a safe check-in or SOS, and the parent can view location history and configured trusted places.

> SafeChildAI is a project prototype. An SOS is an in-app event, not a call to emergency services. Location-based pattern alerts are prompts for a parent to review, not proof of danger.

## Contents

- [What it does](#what-it-does)
- [How it works](#how-it-works)
- [Core AI feature: location-pattern review](#core-ai-feature-per-child-location-pattern-review)
- [Technology](#technology)
- [Run the Android app](#run-the-android-app)
- [Configure Firebase](#configure-firebase)
- [Project layout](#project-layout)
- [Privacy and permissions](#privacy-and-permissions)
- [License](#license)

## What it does

### Parent or guardian

- Create an account with email and password.
- Connect a child by scanning the child’s pairing QR code.
- View connected children, their latest shared location, and location history on a map.
- Add, edit, enable, or disable trusted places and their geofence radii.
- Review safe check-ins, SOS events, and location-pattern review notifications.

### Child

- Set up a child profile and show a pairing QR code to a parent.
- Share location while sharing is enabled and the required Android permissions are granted.
- Send a safe check-in or manually confirmed SOS to the connected parent.
- Review location-sharing and privacy settings in the app.

Maps are rendered with OpenStreetMap data. The Android app does not need a Google Maps key.

## How it works

1. **Set up the parent.** The parent registers with Firebase Authentication and opens the child-connection screen.
2. **Prepare the child device.** The child starts a Firebase-authenticated session, enters a display name, and creates a pairing request. The app displays its request ID as a QR code. Requests expire after 15 minutes and can be used once.
3. **Connect the devices.** The parent scans the QR code with the in-app camera. The app checks that the Firestore request is still pending and unexpired, then creates the parent–child relationship and consumes the request. Firestore rules validate pairing and relationship operations and limit location and event access to account owners and linked family members.
4. **Share location.** The child enables sharing and grants location permissions. A foreground location service writes the latest location and timestamp to Firestore and appends history points. Updates are requested about once a minute; Android battery controls and network availability can delay them.
5. **Use safety features.** A child can send a safe check-in or confirm an SOS. The parent dashboard listens for events, displays them, and can resolve them. SOS notifications depend on the parent app being signed in, network access, and Android notification permission.
6. **Review trusted-place activity.** Enabled trusted places are registered as Android geofences. Entry and exit transitions are recorded for the parent to review.

```mermaid
flowchart LR
    C[Child app] -->|15-minute QR pairing request| F[(Firebase Authentication + Firestore)]
    P[Parent app] -->|Scan and confirm| F
    C -->|Opt-in location, check-ins, SOS| F
    F -->|Linked child data| P
    P -->|Trusted places| F
    C -->|Geofence transitions| F
```

## Core AI feature: per-child location-pattern review

SafeChildAI includes an on-device anomaly-detection feature that helps a parent notice when a child’s recent location and timing differ from that child’s own observed routine. It is designed to prompt a human review, not to decide whether a child is safe.

### How the AI works

1. **Collect the child’s location history.** While the parent is signed in, `ParentAiRoutineMonitor` watches active parent–child relationships, the child’s location history, and fresh current-location updates from Firestore. Location sharing must be enabled on the child’s device.
2. **Filter and encode readings.** The model discards invalid coordinates, invalid timestamps, and readings with missing or poor accuracy (over 100 meters). It keeps readings at least 10 minutes apart. Each retained point becomes four numeric features: north/east displacement from the child-specific origin, plus sine and cosine encodings of the minute within the week. The time encoding lets the model compare both place and weekly timing without a discontinuity at the week boundary.
3. **Learn a separate baseline for each child.** The Kotlin `IsolationForest` implementation needs at least 36 usable readings spread over at least 3 distinct days. It builds 64 randomized trees, using at most 256 samples per tree. Unusual points tend to be isolated in fewer tree splits and receive a higher anomaly score. A threshold is calibrated from that child’s training scores. The baseline refreshes after 6 hours or after at least 12 additional usable readings.
4. **Score fresh locations.** Once a baseline exists, the parent app scores a valid current location that is less than 20 minutes old. It shows either **Location pattern needs a check** or **No unusual pattern detected**, with an explanation and score. During the cold-start period, it reports how many readings and days have been collected instead of pretending the model is ready.
5. **Present a review signal.** A new unusual episode can create a notification and appears in the parent’s **AI & Activity** review history with its location and time. Repeated matching signals within 20 minutes are grouped. Parents can view the point on a map and label it **Expected** or **Needs attention**. Feedback is saved for prototype evaluation; it does not retrain the current model or establish ground truth.

### Where the AI runs and what it uses

The model is implemented in Kotlin in `IsolationForest.kt` and `RoutineDemoModel.kt`, and is orchestrated by `ParentAiRoutineMonitor.kt`. Training data is read from Firestore location history; scoring runs in the parent app. The model’s compact baseline data is stored in the parent app’s local preferences, while review records are also synced to the linked child’s Firestore record. The app does not send coordinates to a separate AI provider.

### Limits

This is a prototype anomaly detector, not a generative AI assistant, a medical or behavioral assessment, or an emergency detector. A high score can be caused by ordinary changes such as travel, a new schedule, or inaccurate GPS; familiar-looking data can also fail to flag a real concern. It does not automatically send an SOS or contact emergency services. Parents should check the map and contact the child when needed. Scoring depends on the parent app being active in a signed-in session, network access to Firebase, location sharing, and enough recent history to train the model.
## Technology

- Kotlin and Jetpack Compose
- AndroidX Navigation, CameraX, ML Kit barcode scanning, and ZXing QR generation
- Firebase Authentication, Cloud Firestore, and Firebase Cloud Functions dependencies
- Google Play location services for location updates and geofencing
- OpenStreetMap map tiles and attribution

The repository also contains callable child-access-code functions under `functions/`. The current app connection flow uses the QR/Firestore request flow described above; those callable functions are not required for that flow.

## Run the Android app

### Requirements

- Android Studio with an Android SDK that includes the compile SDK configured by this project
- Android 8.0 (API 26) or later for a device or emulator
- A Firebase project configured for the app (see below)

### Steps

1. Clone the repository and open its root folder in Android Studio:

   ```bash
   git clone --branch github-share https://github.com/imtiaz-ahmad-shah/SafeChildAI.git
   ```

2. Add your Firebase Android configuration at `app/google-services.json`. This file is intentionally excluded from the repository.
3. Let Android Studio sync Gradle, then run the `app` configuration on a device or emulator.
4. Grant camera permission to scan a child’s QR code. On the child device, grant location permissions if location sharing is needed. Background location availability depends on the Android version and the permission choice.

You can also build a debug APK from the project root:

```bash
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug`.

## Configure Firebase

1. Create a Firebase project and register an Android app with application ID `com.safechild.ai`.
2. Enable **Email/Password** and **Anonymous** sign-in providers in Firebase Authentication.
3. Create a Cloud Firestore database and review/deploy the rules in `firestore.rules` for that project.
4. Download that app’s `google-services.json` and save it to `app/google-services.json`.
5. Before deploying anything, check `.firebaserc` and make sure the Firebase project selected by the Firebase CLI is the intended project. The checked-in alias currently points to `safechild-ai`.

The callable functions in `functions/` are optional for the current QR connection flow. If you plan to deploy them, review their runtime and implementation for your Firebase project first. Never commit service-account credentials or private keys.

## Project layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/safechild/ai/` | Android app, screens, services, data access, and utilities |
| `app/src/main/res/` | App icons, themes, and Android resources |
| `functions/` | Firebase callable Cloud Functions |
| `firestore.rules` | Firestore access rules for profiles, relationships, locations, pairing, and safety events |
| `firebase.json` | Firebase CLI configuration |
| `gradle/` | Gradle wrapper and version catalog |

## Privacy and permissions

Location is sensitive. Location sharing is controlled from the child app and requires Android location permission. The foreground service shows an ongoing notification while tracking is active. Camera permission is used for the parent’s QR scanner; notification permission is used for parent-side alerts where required by Android.

Firestore rules are part of the security boundary. Review them and test your own Firebase configuration before using real family data. Do not put API keys, `google-services.json`, `local.properties`, or service-account files into commits. These local files are ignored by Git.

## License

No open-source license has been selected. Public visibility allows people to view and clone the repository, but it does not grant permission to reuse, modify, or redistribute the code. Ask the project owner before reusing it.

