# AISight Mobile

A phone version of AISight: it watches through the camera, listens for voice commands, and **speaks** what's around you
(what it is, which side, how far in meters, and in navigation mode where to step). Everything runs on the phone, offline.

## Put it in your repo

1. Delete the old `app/` folder and `.github/` from your repo.
2. Copy everything from this folder into the repo root (this adds `tools/` and `branding/` too).
3. Commit and push. GitHub Actions builds `app-debug.apk` (artifact **AISight-Mobile-debug-apk**).

Nothing changes in how the build works: models are still exported from `models/aisight.pt` in CI, and the Vosk voice model is
still downloaded in CI. `compileSdk 36` and the `android-36` / `build-tools 36.0.0` SDK step are already in.

## What changed

**The "Camera disconnected" spam (root cause).** The old watchdog asked "when did the analyzer *start* its last frame?",
but that same callback then ran YOLO + depth. Any frame taking more than 4 seconds looked like a dead camera, so it announced
it, rebound the camera (which also reset its own "already announced" flag), and announced again, about 22 times in 2 minutes
in a simulation of the old logic. Now:

* the camera callback only stamps a heartbeat and hands the frame over; heavy work is on its own thread and frames are
  *dropped*, never queued, while it's busy
* `CameraHealth` is a small state machine: one announcement per outage, at most once a minute, "restored" only if the
  outage was announced, rebind retries back off (3 s, 6 s, 12 s, 24 s, 30 s), nothing is judged in the background
* it also listens to CameraX's own state, so "another app has the camera" is reported correctly

**Speed.** Frames were converted from YUV to RGB pixel by pixel in Java. CameraX now does it natively; the model buffers are
reused instead of reallocated every frame; depth refreshes less often on a slow phone.

**Bugs fixed on the way**

* a retrained `aisight.pt` was ignored after an app update (the old copy in app storage was kept forever)
* an announcement could cut off an alert mid-sentence (everything used `QUEUE_FLUSH`); now alerts interrupt routine speech,
  routine speech never interrupts anything, like the Pi build
* the voice listener reloaded its whole model every time the app resumed; the wake word matched inside other words
* navigation advice could say "step right" when the right side was blocked too
* fall detection calibrated once at launch (wrong if you started the app while walking); it now uses real gravity

**UX.** One big START / PAUSE button, status chips (Camera / AI / Voice, tap for details), a big navigation instruction,
a one-tap SILENCE button on alarms, first-run permission screen that explains why, real settings (segmented choices instead
of a dialog), a "what can I say?" screen, wearable mode (black screen, long-press to exit, volume buttons as shortcuts),
edge-to-edge layout, TalkBack labels. The phone's real battery level replaces the old time estimate.

**Voice.** The recognizer now only knows the command words (like the Pi build), so it is far more accurate.
New: `repeat`, `help`, `wearable mode` / `show screen`.

## Voice commands

Say **"eyesight"** first. Full list is in the app (Settings > What can I say?).

| Say | Does |
|---|---|
| start / begin / launch / turn on | begin announcing |
| stop / pause / quiet | go quiet |
| pause person five minutes / resume person | silence one class (1-5 minutes) |
| read text | read what the camera sees |
| record ten seconds | video to Movies/AISight (5, 10, 30, 60 s or one minute); `stop recording` |
| repeat / say again | hear the last announcement |
| default / advance / navigation mode | announcement style |
| object detection one / two / three | how many things at once |
| switch to tagalog / english | language |
| male / female, fast / slow / normal, volume high / normal / low | voice |
| alarm stop | silence the alarm |
| battery | phone battery |
| wearable mode / show screen | black screen on / off |
| shut down | close the app (asks you to confirm) |

## Tuning

`Tuning.java` holds the numbers (distances, priorities, cooldowns, navigation limits). `CameraHealth.java` holds the camera
timeouts. `Lexicon.java` holds the classes, priority tiers and the Filipino wording.

## Tests

The announcement, voice-command and camera-health logic is plain Java with 39 unit tests, including a replay of the
camera-spam scenario. CI runs them (non-blocking). Locally: `gradle testDebugUnitTest`.

## Icons

`python3 tools/make_icons.py [--edsr EDSR_x4.pb]` rebuilds every launcher icon from `branding/aisight-logo-source.png`.
The source logo is only 177x191 px, so the icons are upscaled (AI super-resolution) and look good, but a larger or vector
logo would be better: replace the file and run the script again.

## Known limits

* Not detected: an unplugged earpiece or speaker (Android doesn't tell apps). The camera and microphone are covered.
* Recording needs the phone to run camera preview + analysis + video together; on phones that can't, the app says so.
* Wearable mode keeps the screen on at minimum brightness; Android doesn't allow camera access with the screen locked
  unless the app runs as a foreground service (a good next step).
* Navigation mode is experimental, as on the Pi.

## Offline male voices (new in 2.1)

Male mode now uses two bundled Piper neural voices: English (US) `en_US-mike-medium` and a Filipino/Tagalog `tl_PH` voice. The matching model is selected from the Language setting. Both model weights and the phonemizer data are downloaded during GitHub Actions and embedded in the APK; AISight does not download voices at runtime and does not require internet access to speak.

The voices are language-specific models, not one bilingual model, so the English and Tagalog voices may not sound exactly like the same speaker. The Filipino voice is new and experimental; its model card reports that it uses the Indonesian eSpeak phonemizer and that mixed Tagalog-English pronunciation may vary. Its male-sounding classification is inferred from sample pitch rather than stated as a guaranteed speaker-gender label. Read `THIRD_PARTY_NOTICES.md` before redistributing: the Filipino model is CC BY-NC-SA 4.0 (non-commercial), and the Android Piper runtime is GPL-3.0-or-later.

Female mode continues to use only an already-installed offline voice from the phone's Android TTS engine. Online TTS voices are not selected. The bundled Piper runtime requires Android 8.0 (API 26) or newer, so the app's minimum Android version is now 8.0.

The GitHub Actions workflow clones the Piper Android runtime, builds its native dependencies, stages English and Indonesian eSpeak dictionaries, and downloads the two voice models on each clean build. Local Android Studio builds need the same generated `vendor/piper-kotlin/`, `third-party/`, and `app/src/main/assets/piper/` content; running the GitHub Actions workflow is the easiest way to produce the complete APK.
