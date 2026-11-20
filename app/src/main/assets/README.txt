Generated model assets are added by scripts/export_models.py and the GitHub Actions workflow.
They export aisight.tflite and depth.tflite, and add the offline Vosk model directory.
The GitHub Actions workflow also stages bundled Piper English/Filipino male voices and eSpeak data under assets/piper/. These are downloaded at build time and spoken fully offline. See THIRD_PARTY_NOTICES.md for model and runtime licenses.
