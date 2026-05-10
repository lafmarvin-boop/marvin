#!/usr/bin/env bash
# Extrait les pins SPKI sha256 du certificat actuel d'api.anthropic.com.
# À lancer depuis macOS/Linux. Sur Windows, utilise Git Bash ou WSL.
#
# Usage:
#   ./extract-anthropic-pins.sh
#
# Colle les 2 lignes "sha256/..." dans CERT_PINS de ClaudeBackend.kt
# (val CERT_PINS = listOf<String>("sha256/...", "sha256/...")).
#
# IMPORTANT : refais ce script tous les ~6 mois pour suivre les rotations
# de cert d'Anthropic. Si l'app casse subitement avec "PINNING_FAILURE",
# c'est que les pins sont périmés — relance le script.

set -e

HOST="api.anthropic.com"

echo "Extraction des pins SPKI sha256 pour $HOST..."
echo

# 1. Récupère la chaîne complète des certificats
echo "Connexion en cours..."
CERTS=$(echo | openssl s_client -showcerts -servername $HOST -connect $HOST:443 2>/dev/null)

# 2. Extrait chaque certificat de la chaîne
echo "$CERTS" | awk '
  /-----BEGIN CERTIFICATE-----/ { capture=1; cert="" }
  capture { cert = cert $0 "\n" }
  /-----END CERTIFICATE-----/ {
    capture=0
    print cert
    print "===CERT_END==="
  }
' > /tmp/anthropic_chain.pem

# 3. Pour chaque cert, calcule le SPKI sha256 base64
COUNT=0
echo "Pins extraits :"
echo "------------------------------------------"
csplit --quiet --prefix=/tmp/anthropic_cert_ /tmp/anthropic_chain.pem '/===CERT_END===/' '{*}'
for f in /tmp/anthropic_cert_*; do
  PEM=$(grep -v "===" "$f")
  if [ -z "$PEM" ]; then continue; fi
  echo "$PEM" > /tmp/anthropic_one.pem
  PIN=$(openssl x509 -in /tmp/anthropic_one.pem -pubkey -noout 2>/dev/null \
    | openssl pkey -pubin -outform der 2>/dev/null \
    | openssl dgst -sha256 -binary \
    | base64)
  if [ -n "$PIN" ]; then
    SUBJECT=$(openssl x509 -in /tmp/anthropic_one.pem -subject -noout 2>/dev/null | sed 's/subject=//')
    echo "// $SUBJECT"
    echo "\"sha256/$PIN\","
    COUNT=$((COUNT + 1))
  fi
done
echo "------------------------------------------"
echo "$COUNT pins trouvés."

# Cleanup
rm -f /tmp/anthropic_chain.pem /tmp/anthropic_one.pem /tmp/anthropic_cert_*

echo
echo "Colle les 2 premières lignes 'sha256/...' dans CERT_PINS de :"
echo "  android/app/src/main/java/com/marvin/assistant/llm/ClaudeBackend.kt"
echo "Puis active le toggle dans Réglages → 'Certificate pinning'."
