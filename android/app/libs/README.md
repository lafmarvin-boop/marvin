# Libs vendorées

Place ici les `.aar` qui ne viennent pas de Maven. Le `build.gradle.kts`
détecte leur présence automatiquement.

## Voice biometric — sherpa-onnx

Pour activer la vérif d'identité vocale (« Marvin n'écoute que toi ») :

1. Télécharge le dernier `sherpa-onnx-android.aar` depuis
   https://github.com/k2-fsa/sherpa-onnx/releases
   (cherche un asset nommé `sherpa-onnx-VERSION.aar` ou similaire,
   contenant les bindings Java + libs natives ARM64).

2. Renomme-le exactement `sherpa-onnx-android.aar` et place-le ici, à côté
   de ce README.

3. Resync Gradle. Tu dois voir au build :
   ```
   Voice biometric: sherpa-onnx AAR trouvé
   ```

4. Télécharge un modèle d'embedding vocal `.onnx` (recommandé:
   `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx` ~26 MB,
   marche bien sur voix française) :
   ```
   wget https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/main/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx
   ```
   Renomme en `speaker.onnx` et push :
   ```bash
   adb push speaker.onnx /sdcard/Android/data/com.marvin.assistant/files/
   ```

5. Dans l'app : `Réglages → Voice biometric → Enrôler ma voix` (5 samples
   de "Jarvis"), puis active le toggle.

Si le fichier n'est pas présent ici, l'app build quand même — le voice
biometric sera juste désactivé silencieusement, le reste fonctionne
normalement.
