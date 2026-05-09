// Maps audio features (duration, average dB, peak dB, dB variance) to a fun
// translation category. Categories are inspired by feline behavior research:
// short greetings, demanding meows for food/attention, mournful long calls,
// and chirpy "conversation" patterns.

const CATEGORIES = {
  greeting: {
    emoji: 'wave',
    label: 'Salutation',
    phrases: [
      'Salut humain, content de te voir.',
      'Tiens, te voilà.',
      'Coucou, je passais par là.',
    ],
  },
  attention: {
    emoji: 'eyes',
    label: 'Demande d’attention',
    phrases: [
      'Caresse-moi, maintenant.',
      'Pose ce téléphone et occupe-toi de moi.',
      'Je suis là, tu m’as oublié ?',
    ],
  },
  hungry: {
    emoji: 'fork',
    label: 'Faim',
    phrases: [
      'Ma gamelle est tragiquement vide.',
      'Il est l’heure de manger. Tu es en retard.',
      'Du thon. Tout de suite.',
    ],
  },
  urgent: {
    emoji: 'alert',
    label: 'Urgence',
    phrases: [
      'OUVRE CETTE PORTE.',
      'C’est une URGENCE féline absolue.',
      'Lève-toi. Maintenant. Je ne plaisante pas.',
    ],
  },
  complaint: {
    emoji: 'sad',
    label: 'Plainte',
    phrases: [
      'Je m’ennuie profondément.',
      'La vie est dure pour un chat d’intérieur.',
      'Personne ne me comprend dans cette maison.',
    ],
  },
  conversation: {
    emoji: 'speech',
    label: 'Discussion',
    phrases: [
      'Laisse-moi te raconter ma journée en détail.',
      'J’ai vu un oiseau. Puis un autre. Puis encore un.',
      'Bref, voilà tout ce que j’avais à te dire.',
    ],
  },
  surprise: {
    emoji: 'spark',
    label: 'Surprise',
    phrases: [
      'Tu m’as fait peur !',
      'D’où sors-tu comme ça ?',
      'Je n’ai pas du tout sursauté, c’est toi.',
    ],
  },
  affection: {
    emoji: 'heart',
    label: 'Affection',
    phrases: [
      'Tu es mon humain préféré.',
      'Je t’aime bien, ne le dis à personne.',
      'Reste. Juste reste.',
    ],
  },
};

// duration in ms, dB values are negative (closer to 0 = louder)
function classify({ durationMs, avgDb, peakDb, dbVariance }) {
  const isShort = durationMs < 600;
  const isLong = durationMs > 1500;
  const isLoud = peakDb > -15;
  const isQuiet = avgDb < -35;
  const isVariable = dbVariance > 60;

  if (isVariable && durationMs > 1000) return 'conversation';
  if (isShort && isLoud) return 'surprise';
  if (isShort && isQuiet) return 'greeting';
  if (isLong && isLoud) return 'urgent';
  if (isLong && isQuiet) return 'complaint';
  if (isLoud) return 'hungry';
  if (isQuiet) return 'affection';
  return 'attention';
}

function pickPhrase(category, seed) {
  const phrases = CATEGORIES[category].phrases;
  const idx = Math.abs(Math.floor(seed)) % phrases.length;
  return phrases[idx];
}

export function translate(features) {
  const category = classify(features);
  const seed = features.durationMs + features.peakDb * 7 + Date.now() / 1000;
  return {
    category,
    label: CATEGORIES[category].label,
    emoji: CATEGORIES[category].emoji,
    phrase: pickPhrase(category, seed),
  };
}

export function summarizeFeatures(samples, durationMs) {
  if (!samples.length) {
    return { durationMs, avgDb: -60, peakDb: -60, dbVariance: 0, samples: 0 };
  }
  const avgDb = samples.reduce((s, x) => s + x, 0) / samples.length;
  const peakDb = samples.reduce((m, x) => (x > m ? x : m), -Infinity);
  const variance =
    samples.reduce((s, x) => s + (x - avgDb) ** 2, 0) / samples.length;
  return {
    durationMs,
    avgDb,
    peakDb,
    dbVariance: variance,
    samples: samples.length,
  };
}
