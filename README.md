***NoteSafe***
NoteSafe is an Android application designed to detect fake currency using machine learning and securely store detection records in the cloud. It features OTP-based user authentication, a logout functionality, and support for Hindi localization. The app leverages TensorFlow Lite for on-device inference, Firebase for authentication and storage, and a user-friendly interface to report fake currency incidents.


**Features**

*Fake Currency Detection: Uses a TensorFlow Lite model (model-2.tflite) to identify counterfeit notes via photo capture or upload.
*OTP Login: Secure user authentication with Firebase Authentication using phone number verification.
*Logout Functionality: Allows users to sign out from the main activity.
*Cloud Storage: Saves detection records (timestamp,denomination,location) to Firebase Firestore and uploads images to Firebase Storage.
*Hindi Localization: Supports Hindi language for accessibility.
*Responsive UI: Includes activities for login (LoginActivity) and main operations (MainActivity).

*Prerequisites*

*Android Studio (latest stable version recommended)
*Android device/emulator running API 21 or higher
*Firebase project for authentication, Firestore, and Storage
*TensorFlow Lite model (model-2.tflite) placed in app/src/main/assets/

Setup Instructions
1. Clone the Repository
   git clone https://github.com/adil-ahmd/Notesafe.git
   cd Notesafe

2. Configure Firebase

Go to the Firebase Console.
Create a new project or use an existing one.
Add an Android app to your project:
Package name: com.example.notesafe
Download the google-services.json file.

Place google-services.json in the app/ directory.

Enable Firebase Authentication (Phone Auth), Firestore, and Storage in the Firebase Console.
Configure Firebase security rules as needed.

3. Open in Android Studio

Open Android Studio and select Open an existing project.
Navigate to the cloned Notesafe directory.
Sync the project with Gradle (File > Sync Project with Gradle Files).

4. Add TensorFlow Lite Model

Ensure model-2.tflite is in app/src/main/assets/.
If missing, obtain the model file and place it in the assets folder.

Verify the model is referenced correctly in MainActivity.kt:val assetFileDescriptor = assets.openFd("model-2.tflite")


5. Build and Run

Connect an Android device or start an emulator.
Build the project.
Run the app.


Test features: OTP login, fake currency detection, logout, and Firestore storage.

Project Structure

app/src/main/java/com/example/notesafe/
MainActivity.kt: Handles fake currency detection, Firestore storage, and logout.
LoginActivity.kt: Manages OTP-based authentication.
FakeCurrencyRecord.kt: Data model for Firestore records.


app/src/main/res/layout/
activity_main.xml: UI for main activity with logout button.
activity_login.xml: UI for OTP login.


app/src/main/res/values/
strings.xml: English strings.
values-hi/strings.xml: Hindi strings for localization.


app/src/main/assets/
model-2.tflite: TensorFlow Lite model for currency detection.


app/build.gradle.kts: Includes Firebase and TensorFlow Lite dependencies.
build.gradle.kts: Project-level Gradle configuration.

Dependencies

Firebase: Authentication, Firestore, Storage
TensorFlow Lite: On-device machine learning
AndroidX: Core, AppCompat, ConstraintLayout
Kotlin: Coroutines, standard library
Gradle: Build system

See app/build.gradle.kts for detailed dependency versions.
Usage

Login: Enter your phone number, receive an OTP, and verify to access the app.
Detect Fake Currency:
Capture a photo or upload an image of a note.
The app uses model-2.tflite to classify the note as genuine or fake.


Save Record:
If fake, the details (denomination, timestamp, confidence) is saved to Firestore, and the image is uploaded to Storage.


Logout: Click the logout button in the main activity to sign out.
Switch Language: Use device settings to switch to Hindi for localized text.

Contributing

Fork the repository.
Create a feature branch:git checkout -b feature/your-feature


Commit changes:git commit -m "Add your feature"


Push to your fork:git push origin feature/your-feature


Open a pull request on github.com:adil-ahmd/Notesafe.

Notes

Security: Ensure Firebase security rules restrict unauthorized access to Firestore and Storage.
Model Updates: If replacing model-2.tflite, update references in MainActivity.kt.
Localization: Add more languages by creating new values-<lang>/strings.xml files.
Sensitive Files: google-services.json is excluded from version control. Obtain it from Firebase Console.

License
This project is licensed under the MIT License. See LICENSE for details.
Contact
For issues or questions feel free to contact me at adil2000ahmed@gmail.com.