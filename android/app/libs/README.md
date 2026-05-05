# Libs vendorées

Place ici les `.aar` qui ne viennent pas de Maven. Le `build.gradle.kts`
détecte leur présence automatiquement.

## sherpa-onnx — pour voice biometric ET Piper TTS

Le **même AAR** débloque deux fonctionnalités :

- **Voice biometric** : « Jarvis n'écoute que toi »
- **Piper TTS** : voix masculine grave naturelle (façon majordome)

### Installation

1. Télécharge le dernier `sherpa-onnx-VERSION.aar` depuis
   https://github.com/k2-fsa/sherpa-onnx/releases
   (cherche un asset incluant les bindings Kotlin/Java + libs natives ARM64)

2. Renomme-le exactement `sherpa-onnx-android.aar` et place-le ici, à côté
   de ce README.

3. Resync Gradle. Tu dois voir au build :
   ```
   Voice biometric: sherpa-onnx AAR trouvé
   ```

### Modèles à pousser sur le téléphone

#### Pour le voice biometric (~26 MB)

```bash
wget https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/main/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx
adb push 3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx \
  /sdcard/Android/data/com.marvin.assistant/files/speaker.onnx
```

#### Pour Piper TTS — voix masculine FR (~30 MB)

Recommandé : `vits-piper-fr_FR-tom-medium` (homme posé, grave, voix de
narration française). Page modèle :
https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits-piper.html

```bash
# Télécharge le bundle complet (.tar.bz2)
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fr_FR-tom-medium.tar.bz2
tar -xf vits-piper-fr_FR-tom-medium.tar.bz2
cd vits-piper-fr_FR-tom-medium

# Push les fichiers requis (le voice model, les tokens, et le dossier espeak-ng-data)
adb shell mkdir -p /sdcard/Android/data/com.marvin.assistant/files/piper
adb push fr_FR-tom-medium.onnx /sdcard/Android/data/com.marvin.assistant/files/piper/voice.onnx
adb push tokens.txt            /sdcard/Android/data/com.marvin.assistant/files/piper/tokens.txt
adb push espeak-ng-data        /sdcard/Android/data/com.marvin.assistant/files/piper/espeak-ng-data
```

Au prochain démarrage de Marvin, le `TtsEngineFactory` détecte automatiquement
les fichiers + l'AAR et bascule sur Piper. Sinon, fallback transparent vers
le TTS Android natif.

### Si l'AAR n'est pas là

L'app build et tourne quand même — voice biometric et Piper TTS sont juste
désactivés silencieusement, le TTS Android prend le relais.
