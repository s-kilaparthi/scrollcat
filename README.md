# ScrollCat 🐱

A floating cat overlay for Android. Flick the cat, and it scrolls the app underneath (Reels, Shorts, anything) via an injected accessibility gesture. Drag it slowly to move it around the screen.

## Setup on Ubuntu

1. Install Android Studio:
   ```bash
   sudo snap install android-studio --classic
   ```
2. Open Android Studio → **Open** → select this `scrollcat` folder.
3. Let Gradle sync (first sync downloads Gradle 8.7 + SDK 35 automatically — takes a few minutes).
4. On your phone: Settings → About phone → tap **Build number** 7 times → enable **USB debugging** in Developer options.
5. Plug the phone in, accept the debugging prompt, hit **Run ▶** in Android Studio.

You can also open the same folder in Cursor for editing — build/run from Android Studio or with `./gradlew installDebug` (run `gradle wrapper` once, or use Android Studio's terminal).

## Using the app

1. Open ScrollCat → **Grant overlay permission** → toggle it on.
2. **Enable accessibility service** → find "ScrollCat Gesture Helper" → toggle on.
3. **Summon the cat** → a 🐱 appears floating on screen.
4. Open Instagram/YouTube → **flick the cat up fast** → the reel scrolls. Slow drag just moves the cat.

## Files that matter

| File | What it does |
|---|---|
| `OverlayService.kt` | Floating cat window, drag vs fling detection |
| `CatAccessibilityService.kt` | `dispatchGesture()` swipe injection |
| `res/xml/accessibility_service_config.xml` | Must contain `canPerformGestures="true"` or injection silently fails |
| `AndroidManifest.xml` | Both service declarations — the accessibility one needs the `BIND_ACCESSIBILITY_SERVICE` permission AND the meta-data tag |

## Things to tune

- `FLING_VELOCITY_THRESHOLD` in `OverlayService.kt` (2500 px/s) — how hard you must flick.
- `SWIPE_DURATION_MS` in `CatAccessibilityService.kt` (180ms) — some apps want slower swipes to register as a fling.
- Swipe path is center-screen 70% → 30%. If the cat sits mid-screen, move it to an edge or adjust the path.

## Gotchas

- After every fresh install, the accessibility toggle resets — re-enable it in Settings.
- If `dispatchGesture` returns false, check logcat for the "ScrollCat" tag; it's almost always the config XML or the toggle being off.
- Test on a real device; emulators are unreliable for overlay + accessibility.
- Don't publish this to Google Play without framing it as an accessibility/ergonomics tool with the prominent-disclosure form — this API usage pattern gets apps rejected otherwise.

## Next steps (after MVP works)

1. Replace the emoji with a Lottie animation (add `com.airbnb.android:lottie:6.x`)
2. Cat reactions on fling (squash/stretch, meow)
3. Horizontal flicks → left/right swipes
4. Settings screen: sensitivity, swipe length, cat size
