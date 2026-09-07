// Source du bookmarklet "Marvin — Réponse IA Easiware" (poste sans installation possible).
// Pas d'extension : ce code tourne uniquement pendant que la page reste ouverte.
// Il faut re-cliquer le favori après chaque rechargement de la page easiware.
//
// La zone de conversation d'easiware bloque les clics/scripts injectés (testé : ni Alt+Clic
// ni les raccourcis clavier n'atteignent le texte des messages). On passe donc par un panneau
// flottant, créé par ce script lui-même (donc toujours cliquable) :
//   1. Sélectionne le message du client, Ctrl+C
//   2. Colle-le (Ctrl+V) dans le panneau
//   3. Clique « Réponse normale » (écrit + envoie automatiquement, délai annulable)
//      ou « Réponse signalement » (écrit seulement, jamais envoyé automatiquement)
//   4. Si l'écriture automatique dans le champ échoue, un bouton « Copier » permet
//      de coller la réponse à la main
//
// Pour régénérer le favori après une modification de ce fichier :
//   npx terser tools/easiware-ia-response.bookmarklet-source.js -c -m | \
//   (echo -n 'javascript:'; cat) > tools/easiware-ia-response.bookmarklet.txt

(function () {
    'use strict';

    if (window.__marvinIA) {
        alert('Déjà actif sur cette page.');
        return;
    }
    window.__marvinIA = true;

    // ==================== CONFIG ====================
    const CLAUDE_API_KEY = prompt('Colle ta clé API Claude (une seule fois par page ouverte) :');
    if (!CLAUDE_API_KEY) { window.__marvinIA = false; return; }
    const CLAUDE_MODEL = 'claude-sonnet-5';

    const SYSTEM_PROMPT_CLIENT = [
        "Tu es un policier qui répond aux messages des citoyens via un chat.",
        "Fais preuve d'empathie et d'écoute, comme un vrai policier bienveillant sur le terrain — jamais froid ni robotique.",
        "Réponds en français, de façon polie, claire et concise.",
        "Ne mets pas de formule d'introduction du type Bonjour si ce n'est pas nécessaire, réponds directement.",
    ].join(' ');

    const SYSTEM_PROMPT_SIGNALEMENT = [
        "Tu es un policier qui recueille, via un chat, le signalement d'une personne (trafic de stupéfiants ou tout autre fait sensible).",
        "Fais preuve d'empathie et de bienveillance, comme un vrai policier à l'écoute — jamais froid ni robotique, remercie la personne d'avoir signalé les faits.",
        "",
        "Ton objectif : recueillir, une question courte à la fois, adaptée à ce que la personne vient de dire, les informations suivantes (dans cet ordre de priorité, sans jamais insister si la personne ne sait pas ou ne veut pas répondre) :",
        "1. L'adresse complète du lieu concerné (rue, ville, code postal)",
        "2. La nature exacte des faits (ex : type de produit s'il s'agit de stupéfiants)",
        "3. Si elle sait où c'est caché",
        "4. L'identité des personnes impliquées si elle les connaît : nom, numéro de téléphone, pseudo ou compte sur les réseaux sociaux",
        "5. Si des véhicules sont utilisés : marque, couleur, plaque d'immatriculation",
        "6. Si elle accepte de communiquer ses propres coordonnées pour être recontactée par les enquêteurs (demande-le explicitement, une seule fois, sans insister en cas de refus)",
        "",
        "Ne pose qu'une seule question à la fois. Ne donne aucun conseil juridique, ne promets aucune action précise.",
        "Dès que tu as recueilli le maximum d'informations possible, ou que la personne indique ne plus rien avoir à ajouter, remercie-la chaleureusement pour son signalement et dis-lui au revoir clairement pour clore l'échange.",
    ].join('\n');

    const DELAI_ANNULATION_MS = 5000;
    // ==================================================

    function trouverChampSaisie() {
        const candidats = [
            ...document.querySelectorAll('div[contenteditable="true"]'),
            ...document.querySelectorAll('textarea:not(#marvin-input):not(#marvin-output)'),
        ].filter(el => el.offsetParent !== null);
        return candidats.length ? candidats[candidats.length - 1] : null;
    }

    function ecrireDansChamp(champ, texte) {
        champ.focus();
        if (champ.tagName === 'TEXTAREA') {
            const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
            setter.call(champ, texte);
            champ.dispatchEvent(new Event('input', { bubbles: true }));
        } else {
            const ok = document.execCommand('insertText', false, texte);
            if (!ok) {
                champ.dispatchEvent(new InputEvent('input', {
                    bubbles: true, cancelable: true, data: texte, inputType: 'insertText'
                }));
            }
        }
    }

    function simulerEntree(champ) {
        champ.focus();
        for (const type of ['keydown', 'keypress', 'keyup']) {
            champ.dispatchEvent(new KeyboardEvent(type, {
                key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
                bubbles: true, cancelable: true
            }));
        }
    }

    async function appellerClaude(messageClient, systemPrompt) {
        const res = await fetch('https://api.anthropic.com/v1/messages', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'x-api-key': CLAUDE_API_KEY,
                'anthropic-version': '2023-06-01',
                'anthropic-dangerous-direct-browser-access': 'true',
            },
            body: JSON.stringify({
                model: CLAUDE_MODEL,
                max_tokens: 500,
                system: systemPrompt,
                messages: [{ role: 'user', content: messageClient }],
            }),
        });
        const json = await res.json();
        if (json.content && json.content[0] && json.content[0].text) {
            return json.content[0].text.trim();
        }
        throw new Error(json.error ? json.error.message : 'Réponse API inattendue');
    }

    // ==================== PANNEAU FLOTTANT ====================
    const panneau = document.createElement('div');
    panneau.style.cssText = 'position:fixed;bottom:12px;right:12px;z-index:2147483647;background:#1a1a1a;color:#fff;padding:12px;border-radius:10px;font:13px/1.4 sans-serif;box-shadow:0 4px 20px rgba(0,0,0,.4);width:320px;';
    panneau.innerHTML = `
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
            <strong>Marvin IA</strong>
            <button id="marvin-fermer" style="background:none;border:0;color:#fff;font-size:16px;cursor:pointer;">✕</button>
        </div>
        <textarea id="marvin-input" placeholder="Colle ici le message du client (Ctrl+V)" style="width:100%;height:70px;box-sizing:border-box;margin-bottom:6px;border-radius:6px;border:1px solid #555;background:#222;color:#fff;padding:6px;font:12px/1.3 sans-serif;"></textarea>
        <div style="display:flex;gap:6px;margin-bottom:6px;">
            <button id="marvin-normal" style="flex:1;background:#2a7de1;color:#fff;border:0;border-radius:6px;padding:6px;cursor:pointer;">Réponse normale</button>
            <button id="marvin-signal" style="flex:1;background:#c77d1a;color:#fff;border:0;border-radius:6px;padding:6px;cursor:pointer;">Signalement</button>
        </div>
        <div id="marvin-status" style="min-height:16px;margin-bottom:6px;color:#ccc;"></div>
        <textarea id="marvin-output" readonly placeholder="La réponse générée s'affiche ici" style="width:100%;height:90px;box-sizing:border-box;margin-bottom:6px;border-radius:6px;border:1px solid #555;background:#222;color:#fff;padding:6px;font:12px/1.3 sans-serif;"></textarea>
        <button id="marvin-copier" style="width:100%;background:#3a3a3a;color:#fff;border:0;border-radius:6px;padding:6px;cursor:pointer;">Copier la réponse</button>
    `;
    document.body.appendChild(panneau);

    const elInput = panneau.querySelector('#marvin-input');
    const elOutput = panneau.querySelector('#marvin-output');
    const elStatus = panneau.querySelector('#marvin-status');

    panneau.querySelector('#marvin-fermer').addEventListener('click', () => {
        panneau.remove();
        window.__marvinIA = false;
    });

    panneau.querySelector('#marvin-copier').addEventListener('click', async () => {
        try {
            await navigator.clipboard.writeText(elOutput.value);
            elStatus.textContent = 'Copié !';
        } catch {
            elOutput.select();
            document.execCommand('copy');
            elStatus.textContent = 'Copié !';
        }
    });

    async function traiter(modeSignalement) {
        const messageClient = elInput.value.trim();
        if (!messageClient) {
            elStatus.textContent = "Colle d'abord le message du client";
            return;
        }

        elStatus.textContent = "L'IA rédige la réponse...";
        elOutput.value = '';

        let reponse;
        try {
            reponse = await appellerClaude(messageClient, modeSignalement ? SYSTEM_PROMPT_SIGNALEMENT : SYSTEM_PROMPT_CLIENT);
        } catch (err) {
            elStatus.textContent = 'Erreur : ' + err.message;
            return;
        }

        elOutput.value = reponse;

        const champ = trouverChampSaisie();
        if (!champ) {
            elStatus.textContent = 'Champ de réponse introuvable — copie la réponse toi-même';
            return;
        }
        ecrireDansChamp(champ, reponse);

        if (modeSignalement) {
            elStatus.textContent = 'Écrit dans le champ — relis puis envoie toi-même (Entrée)';
            return;
        }

        let annule = false;
        let secondesRestantes = Math.ceil(DELAI_ANNULATION_MS / 1000);
        elStatus.innerHTML = '';
        const spanCompte = document.createElement('span');
        spanCompte.textContent = `Envoi dans ${secondesRestantes}s `;
        const btnAnnuler = document.createElement('button');
        btnAnnuler.textContent = 'Annuler';
        btnAnnuler.style.cssText = 'background:#e63946;color:#fff;border:0;border-radius:5px;padding:2px 8px;cursor:pointer;';
        btnAnnuler.onclick = () => { annule = true; elStatus.textContent = 'Annulé'; };
        elStatus.appendChild(spanCompte);
        elStatus.appendChild(btnAnnuler);

        const intervalle = setInterval(() => {
            secondesRestantes--;
            if (annule || secondesRestantes <= 0) {
                clearInterval(intervalle);
                if (!annule) {
                    simulerEntree(champ);
                    elStatus.textContent = 'Envoyé';
                }
                return;
            }
            spanCompte.textContent = `Envoi dans ${secondesRestantes}s `;
        }, 1000);
    }

    panneau.querySelector('#marvin-normal').addEventListener('click', () => traiter(false));
    panneau.querySelector('#marvin-signal').addEventListener('click', () => traiter(true));

})();
