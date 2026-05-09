import { StatusBar } from 'expo-status-bar';
import { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { Audio } from 'expo-av';
import { summarizeFeatures, translate } from './src/translator';

const RECORDING_OPTIONS = {
  android: {
    extension: '.m4a',
    outputFormat: Audio.AndroidOutputFormat.MPEG_4,
    audioEncoder: Audio.AndroidAudioEncoder.AAC,
    sampleRate: 44100,
    numberOfChannels: 1,
    bitRate: 128000,
  },
  ios: {
    extension: '.m4a',
    outputFormat: Audio.IOSOutputFormat.MPEG4AAC,
    audioQuality: Audio.IOSAudioQuality.HIGH,
    sampleRate: 44100,
    numberOfChannels: 1,
    bitRate: 128000,
    linearPCMBitDepth: 16,
    linearPCMIsBigEndian: false,
    linearPCMIsFloat: false,
  },
  web: {
    mimeType: 'audio/webm',
    bitsPerSecond: 128000,
  },
  isMeteringEnabled: true,
};

export default function App() {
  const [permission, setPermission] = useState(null);
  const [isRecording, setIsRecording] = useState(false);
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  const recordingRef = useRef(null);
  const meteringSamplesRef = useRef([]);
  const startedAtRef = useRef(0);

  useEffect(() => {
    (async () => {
      const { status } = await Audio.requestPermissionsAsync();
      setPermission(status === 'granted');
    })();
  }, []);

  async function startRecording() {
    try {
      setError(null);
      setResult(null);
      meteringSamplesRef.current = [];

      await Audio.setAudioModeAsync({
        allowsRecordingIOS: true,
        playsInSilentModeIOS: true,
      });

      const recording = new Audio.Recording();
      await recording.prepareToRecordAsync(RECORDING_OPTIONS);
      recording.setProgressUpdateInterval(80);
      recording.setOnRecordingStatusUpdate((status) => {
        if (status.isRecording && typeof status.metering === 'number') {
          meteringSamplesRef.current.push(status.metering);
        }
      });
      await recording.startAsync();

      recordingRef.current = recording;
      startedAtRef.current = Date.now();
      setIsRecording(true);
    } catch (e) {
      setError(`Impossible de démarrer l'enregistrement : ${e.message}`);
      setIsRecording(false);
    }
  }

  async function stopRecording() {
    const recording = recordingRef.current;
    if (!recording) return;
    setIsRecording(false);
    setIsAnalyzing(true);
    try {
      await recording.stopAndUnloadAsync();
      const durationMs = Date.now() - startedAtRef.current;
      const features = summarizeFeatures(
        meteringSamplesRef.current,
        durationMs,
      );
      const translation = translate(features);
      setResult({ features, translation });
    } catch (e) {
      setError(`Erreur d'analyse : ${e.message}`);
    } finally {
      recordingRef.current = null;
      setIsAnalyzing(false);
    }
  }

  const buttonLabel = isRecording
    ? 'Arrêter'
    : isAnalyzing
      ? 'Analyse...'
      : 'Enregistrer un miaulement';

  return (
    <View style={styles.container}>
      <StatusBar style="light" />
      <View style={styles.header}>
        <Text style={styles.title}>Traducteur de chat</Text>
        <Text style={styles.subtitle}>
          Appuie sur le bouton, fais miauler ton chat, relâche.
        </Text>
      </View>

      <View style={styles.center}>
        <Pressable
          onPress={isRecording ? stopRecording : startRecording}
          disabled={isAnalyzing || permission === false}
          style={({ pressed }) => [
            styles.button,
            isRecording && styles.buttonRecording,
            (isAnalyzing || permission === false) && styles.buttonDisabled,
            pressed && styles.buttonPressed,
          ]}
        >
          {isAnalyzing ? (
            <ActivityIndicator color="#fff" />
          ) : (
            <Text style={styles.buttonText}>{buttonLabel}</Text>
          )}
        </Pressable>
        {permission === false && (
          <Text style={styles.warning}>
            Permission micro refusée. Autorise-la dans les réglages du
            téléphone.
          </Text>
        )}
      </View>

      <ScrollView contentContainerStyle={styles.results}>
        {error && <Text style={styles.error}>{error}</Text>}
        {result && (
          <View style={styles.card}>
            <Text style={styles.category}>{result.translation.label}</Text>
            <Text style={styles.phrase}>“{result.translation.phrase}”</Text>
            <View style={styles.metrics}>
              <Metric
                label="Durée"
                value={`${(result.features.durationMs / 1000).toFixed(2)} s`}
              />
              <Metric
                label="Volume moyen"
                value={`${result.features.avgDb.toFixed(1)} dB`}
              />
              <Metric
                label="Pic"
                value={`${result.features.peakDb.toFixed(1)} dB`}
              />
              <Metric
                label="Variance"
                value={result.features.dbVariance.toFixed(0)}
              />
            </View>
          </View>
        )}
        {!result && !error && (
          <Text style={styles.hint}>
            Astuce : tiens le téléphone à environ 30 cm du chat pour une
            meilleure analyse.
          </Text>
        )}
      </ScrollView>
    </View>
  );
}

function Metric({ label, value }) {
  return (
    <View style={styles.metric}>
      <Text style={styles.metricLabel}>{label}</Text>
      <Text style={styles.metricValue}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#1a1a2e',
    paddingTop: 60,
    paddingHorizontal: 24,
  },
  header: { alignItems: 'center', marginBottom: 24 },
  title: { fontSize: 28, fontWeight: '700', color: '#fff' },
  subtitle: {
    fontSize: 14,
    color: '#a0a0c0',
    marginTop: 6,
    textAlign: 'center',
  },
  center: { alignItems: 'center', marginVertical: 32 },
  button: {
    backgroundColor: '#e94560',
    paddingVertical: 22,
    paddingHorizontal: 36,
    borderRadius: 999,
    minWidth: 240,
    alignItems: 'center',
  },
  buttonRecording: { backgroundColor: '#16213e', borderWidth: 2, borderColor: '#e94560' },
  buttonDisabled: { opacity: 0.5 },
  buttonPressed: { transform: [{ scale: 0.97 }] },
  buttonText: { color: '#fff', fontSize: 17, fontWeight: '600' },
  warning: { color: '#ffb86b', marginTop: 16, textAlign: 'center' },
  results: { paddingBottom: 40 },
  hint: { color: '#7878a0', textAlign: 'center', fontStyle: 'italic' },
  error: {
    color: '#ff7b7b',
    backgroundColor: '#2a1a2e',
    padding: 12,
    borderRadius: 8,
  },
  card: {
    backgroundColor: '#16213e',
    borderRadius: 16,
    padding: 20,
  },
  category: {
    color: '#e94560',
    fontSize: 13,
    fontWeight: '700',
    letterSpacing: 1.2,
    textTransform: 'uppercase',
  },
  phrase: {
    color: '#fff',
    fontSize: 22,
    fontWeight: '600',
    marginTop: 10,
    marginBottom: 18,
    lineHeight: 30,
  },
  metrics: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
  },
  metric: {
    backgroundColor: '#0f3460',
    paddingVertical: 8,
    paddingHorizontal: 12,
    borderRadius: 8,
    minWidth: 90,
  },
  metricLabel: { color: '#a0a0c0', fontSize: 11 },
  metricValue: { color: '#fff', fontSize: 14, fontWeight: '600' },
});
