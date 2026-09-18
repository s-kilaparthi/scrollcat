# ScrollCat 🐱

Your AI cat companion that never lets a message go unanswered. ScrollCat is a floating cat overlay for Android that sits on top of every other app, delivering AI-powered smart replies for WhatsApp, Instagram, Telegram, and more — plus fun scroll gestures for Reels/Shorts.

Built for nano/micro Instagram influencers and small business owners who can't keep up with DMs.

## What it does

- **AI Smart Replies** — get suggested replies to incoming messages across messaging apps, with keyword-based auto-replies for common questions
- **On-device AI** — runs a local LLM (Gemma, via Google AI Edge LiteRT) on capable devices for privacy and zero-latency replies; falls back to Groq cloud inference on lower-RAM devices
- **Voice-to-text & translation** — dictate replies in your language, auto-translate/romanize output, works in any app via Accessibility
- **Smart Notifications** — filter and prioritize by app, sender, or keyword, with a permanent banking/finance exclusion for safety
- **Auto-Reply Tracker** — full log of what was auto-sent and when, so nothing goes out unaccounted for
- **Cat personality** — moods, sleep mode, reactions, and gesture-based scrolling (flick the cat to scroll Reels/Shorts underneath)

## Status

Live on Google Play Internal Testing. Package: `com.skilaparthi.scrollcat`.

## Tech stack

- Kotlin 2.2.0, min SDK 26, target SDK 35, Gradle 8.7
- On-device inference: `com.google.ai.edge.litertlm` (LiteRT), GPU-backed
- Cloud fallback: Groq API
- Firebase Analytics + Crashlytics
- Key components: `OverlayService`, `CatAccessibilityService`, `CatNotificationListener`, `ReplyStore`, `AiReplyGenerator`, `OnDeviceAiEngine`, `AutoReplyManager`

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

1. Open ScrollCat → grant overlay + notification access.
2. Set up AI (on-device or Groq) from the dashboard's "Add your AI" banner.
3. Enable Accessibility for voice dictation and gesture scrolling.
4. Summon the cat — it floats over any app, badges show pending messages, tap to reply or use AI-suggested replies.
5. Flick the cat fast to scroll Reels/Shorts; slow drag just moves it.

## Gotchas

- Test on a real device; emulators are unreliable for overlay + accessibility + GPU inference.
- On-device AI requires ~7GB+ RAM devices; lower-RAM devices use Groq automatically.
- Google Play requires the accessibility/ergonomics prominent-disclosure form for this permission pattern.

## Roadmap

- Chrome extension (WhatsApp Web)
- iOS keyboard extension
- AI agent layer
- Custom cat skins
