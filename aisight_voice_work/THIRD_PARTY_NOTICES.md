# AISight third-party voice notices

AISight's Male voice mode bundles the following on-device models and runtime at APK build time.

## English (United States) voice

- Model: `en_US-mike-medium` from the `rhasspy/piper-voices` collection.
- Language: English (United States); one speaker; medium quality; 22,050 Hz.
- Model card: https://huggingface.co/rhasspy/piper-voices/tree/main/en/en_US/mike/medium
- The model card identifies the training dataset as CC0. The Piper voice collection is published under MIT terms; retain the upstream model card and attribution when redistributing.

## Filipino / Tagalog voice

- Model: `tl_PH-filipino-medium-model` by Ihor Shevchuk.
- Model page: https://huggingface.co/IhorShevchuk/piper-voice-tl-filipino
- Language: Filipino / Tagalog (`tl_PH`); one speaker; medium quality; 22,050 Hz.
- License: Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International (CC BY-NC-SA 4.0).
- Attribution: Ihor Shevchuk, `piper-voice-tl-filipino`; training data includes the Filipino TTS dataset by Ridhesh Bhati.
- The voice's male-sounding classification is an estimate based on sample pitch, not a guaranteed author-provided gender label. It is a separate model from the English Mike voice, so the two languages do not guarantee identical speaker identity or timbre.
- The model card reports that it uses eSpeak Indonesian phonemization because eSpeak does not currently provide a Tagalog voice, and that quality may vary on mixed Tagalog-English input. The model is new and its own model card says native-speaker evaluation is pending.
- **Non-commercial restriction:** this model's CC BY-NC-SA license does not permit commercial use. Review the model card and license before publishing or distributing AISight commercially. Adaptations, if any, must follow the ShareAlike requirement.

## Piper Android runtime

- Runtime: `piper-kotlin` by Piper TTS Apps, https://github.com/piper-tts-apps/piper-kotlin
- License: GPL-3.0-or-later. Its native dependencies include Piper and eSpeak NG components with their own licenses; see the upstream repository and vendored license files.
- The build workflow fetches the runtime source and native dependencies from their upstream repositories. Keep their license texts and comply with applicable source-distribution obligations when redistributing APKs.

## Offline operation

Voice weights and the phonemizer data are fetched during the GitHub Actions build and packaged into the APK. AISight does not download voice data at runtime and does not need an Internet permission to speak with these bundled voices.
