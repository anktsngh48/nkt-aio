# Fixed WebView app

A fullscreen Android WebView that opens one fixed address and nothing else. No toolbar, no address bar, no menus, and the status bar (time, battery, signal) is hidden.

Swipe down from the top edge of the screen to peek at the status bar. It hides itself again.

## Change the address

Open `app/src/main/java/com/example/fixedwebview/MainActivity.java` and edit the line:

    private static final String START_URL = "https://www.vedantu.com";

## Change the app name

Edit `app/src/main/res/values/strings.xml`.

## Behaviour

- Fills the whole screen with no action bar and no status bar.
- Back button goes back through pages, and closes the app on the first page.
- Logins and cookies are kept between launches.
- Links to other websites stay inside the app. tel:, mailto:, WhatsApp and UPI links open in the matching app.
- Rotating the phone does not reload the page.

## Not included

- File and photo upload pickers, and camera or microphone access inside pages.
- A custom icon (Android's default icon is used).
- Play Store signing. The APK is debug-signed, which is fine for installing directly on phones.

## Building the APK

The build is done by GitHub using the workflow file `.github/workflows/build-apk.yml`. Follow the step-by-step instructions you were given in chat.
