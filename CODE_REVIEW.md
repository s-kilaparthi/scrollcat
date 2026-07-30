# ScrollCat Code Review Report

Generated: July 2026

## Memory Leaks Fixed

- **ReplyStore.kt** — `ReplyableMessage` no longer stores `Notification.Action` (which holds binder references to the original notification). Now stores only `PendingIntent`, `Array<RemoteInput>`, and extracted text fields at capture time.
- **OverlayService.kt** — `onDestroy()` now comprehensively tears down all overlay views: container, radial menu, reply panel, volume controls, translation bubble, cat message bubble, reaction emoji, and drag handle. All are removed via `safeRemoveView()` before nulling references.
- **OverlayService.kt** — `CatTouchListener` handler is cleaned up via `cleanup()` in `onDestroy()`; previously the inner-class `Handler` was never cleared.
- **OverlayService.kt** — `radialMenu?.destroy()` added to `onDestroy()`; radial menu WindowManager views were not always removed when the service stopped.
- **OverlayService.kt** — All handlers (`moodHandler`, `reactionHandler`, `volumeHandler`, `translationHandler`, `catMessageHandler`) now call `removeCallbacksAndMessages(null)` in `onDestroy()`.
- **CatAnimator.kt** — `stopAll()` now clears both the animation `handler` and `idleTimeoutHandler` with `removeCallbacksAndMessages(null)`.
- **CatAccessibilityService.kt** — `handler.removeCallbacksAndMessages(null)` added in `onDestroy()` to cancel pending gesture callbacks.
- **MusicDetector.kt** — `stop()` now calls `handler.removeCallbacksAndMessages(null)` instead of only removing the check runnable.
- **ReplyPanel.kt** — `destroy()` already called `dismiss()` which removes the panel view; added try-catch guard on `addView` to prevent orphaned views on failure.

## Null Pointer Crashes Fixed

- **ReplyStore.kt** — Added null checks for `notification.extras`, `action.actionIntent`, and `action.remoteInputs` before building `ReplyableMessage`.
- **ReplySender.kt** — Updated to use extracted `actionIntent` and `remoteInputs` from `ReplyableMessage` with empty-array guard instead of accessing `replyAction`.
- **CatNotificationListener.kt** — `sbn.notification` and `notification.extras` null-checked before reading title/text. Uses `Notification.EXTRA_*` constants instead of raw string keys.
- **CatNotificationListener.kt** — Auto-reply and badge logic guarded with `OverlayService.instance?.` safe calls (already present; verified).
- **BillingManager.kt** — `productDetailsList?.forEach` and `purchases?.forEach` null-safe iteration in async callbacks.
- **OverlayService.kt** — All `layoutParams`, `containerView`, `catView`, and `handleView` accesses guarded before WindowManager operations. `isDestroyed` flag prevents post-destroy view updates.
- **OverlayService.kt** — `CatTouchListener.longPressRunnable` checks `layoutParams == null` before showing radial menu.
- **CatAccessibilityService.kt** — No `rootInActiveWindow` usage found in codebase; all `AccessibilityEvent` access uses `event?.` null checks and early returns.

## Battery Optimizations

- **OverlayService.kt** — Screen-off receiver now calls `pauseBackgroundWork()`: stops cat animator, cancels idle timeout, clears mood handler callbacks, pauses music detector, and cancels battery slide animations.
- **OverlayService.kt** — Screen-on resumes animator, music detector, and mood tracking only when service is alive.
- **MusicDetector.kt** — Added `pause()` / `resume()` methods; polling stops entirely when screen is off (no more 1-second `isMusicActive` checks in background).
- **CatAnimator.kt** — Idle sleep timeout cancelled on screen off via existing `cancelIdleTimeout()` call from screen receiver.
- **OverlayService.kt** — `scheduleMoodCheck()` now skips scheduling when `!isScreenOn || isDestroyed`.
- **OverlayService.kt** — Battery slide `ValueAnimator` instances tracked in `batteryAnimator` and cancelled on destroy and screen off.

## Window Manager Crash Fixes

- **OverlayService.kt** — Added `safeAddView()`, `safeUpdateViewLayout()`, `safeRemoveView()` helpers. All wrap WindowManager calls in try-catch and check `view.parent` before add/remove/update.
- **OverlayService.kt** — `safeAddView` returns false if view is already attached (prevents duplicate-add crashes).
- **OverlayService.kt** — `safeUpdateViewLayout` returns false if view has no parent (prevents update-after-remove crashes).
- **OverlayService.kt** — Battery animations check `isDestroyed` in update listeners.
- **RadialMenu.kt** — `addView` wrapped in try-catch with `view.parent == null` guard; `removeView` checks parent before removing.
- **ReplyPanel.kt** — `addView` wrapped in try-catch; sets `isShowing = false` on failure.

## Notification Listener Fixes

- **CatNotificationListener.kt** — Added per-notification-key debounce (`processedKeys` map) to prevent duplicate processing of the same notification update within 2 seconds.
- **CatNotificationListener.kt** — Auto-reply blocked for own packages: both `com.skilaparthi.scrollcat` and `com.skilaparthi.scrollcat.debug`.
- **CatNotificationListener.kt** — `onNotificationRemoved` cleans `ReplyStore` entry and removes key from `processedKeys`.
- **CatNotificationListener.kt** — App-level debounce retained for badge increment; replyable capture still happens before debounce so multi-sender messages aren't lost.

## Remaining Concerns

- **ReplyStore RemoteInput array** — `Array<RemoteInput>` in `ReplyableMessage` may still hold binder references on some Android versions. A future improvement would serialize only `resultKey` strings and reconstruct RemoteInput at send time.
- **CatAnimator bitmap cache** — `bitmapCache` in `CatAnimator` is never cleared on destroy; sprite bitmaps remain in memory until GC. Consider calling `bitmapCache.clear()` in `stopAll()`.
- **ScreenTranslator ML Kit models** — Downloaded translation models persist on disk; no runtime issue but users on metered connections should be aware.
- **Release signing** — `assembleRelease` produces an unsigned APK; Play Store upload requires a signing keystore configured in CI or locally.
- **Gradle wrapper** — `gradlew` script generated for CI; ensure `gradle-wrapper.jar` is committed to the repository.
- **GitHub Pages baseurl** — Links use relative paths (`privacy.html`); absolute `/scrollcat/privacy` paths require Jekyll permalink configuration if clean URLs are desired.

## Code Quality Notes

- The codebase follows a consistent pattern of programmatic UI (no XML layouts for settings/overlay screens), which keeps the project lightweight but makes UI changes verbose.
- `OverlayService.kt` is the largest file (~1200 lines) and handles overlay lifecycle, gestures, mood, battery, music, translation, and replies. A future refactor could extract gesture handling and overlay view management into separate classes.
- Null-safe `OverlayService.instance?.` pattern is used consistently across services; the `isDestroyed` guard adds an additional layer for in-service async callbacks.
- AI reply fallback chain (Claude → Gemini Nano → Smart Reply → hardcoded) is well-structured with clear engine labels for debugging.
- SharedPreferences usage is fragmented across multiple pref files (`scrollcat_prefs`, `scrollcat_billing`, `scrollcat_stats`, etc.); consider a single settings facade long-term.
- ProGuard rules keep all app classes (`-keep class com.skilaparthi.scrollcat.** { *; }`) which prevents R8 from stripping code but limits APK size reduction. Tightening rules after release testing could yield additional savings.
