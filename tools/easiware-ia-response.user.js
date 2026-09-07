// ==UserScript==
// @name         Marvin — Réponse IA Easiware
// @namespace    https://github.com/lafmarvin-boop/marvin
// @version      1.0
// @description  Sélectionne le message client, appuie sur le raccourci, l'IA répond et envoie (avec délai d'annulation)
// @match        *://*.easiware.fr/*
// @grant        GM_xmlhttpRequest
// @connect      api.anthropic.com
// ==/UserScript==

(function () {
    'use strict';

    // ==================== CONFIG ====================
    // Colle ta clé API Anthropic ici (garde-la secrète, ne partage jamais ce fichier rempli).
    const CLAUDE_API_KEY = 'COLLE_TA_CLE_API_ICI';
    const CLAUDE_MODEL = 'claude-sonnet-5';
    const SYSTEM_PROMPT = "Tu es un agent du service client. Réponds au message du client en français, de façon polie, claire et concise. Ne mets pas de formule d'introduction du type « Bonjour, » si ce n'est pas nécessaire, réponds directement.";
    const DELAI_ANNULATION_MS = 5000;
    // Raccourci : Ctrl+Shift+A (Cmd+Shift+A sur Mac)
    const RACCOURCI = { key: 'a', shift: true, ctrlOrCmd: true };
    // ==================================================

    let banniere = null;

    function creerBanniere(texte) {
        if (banniere) banniere.remove();
        banniere = document.createElement('div');
        banniere.style.cssText = `
            position: fixed; top: 12px; right: 12px; z-index: 999999;
            background: #1a1a1a; color: #fff; padding: 10px 14px;
            border-radius: 8px; font: 13px/1.4 sans-serif; box-shadow: 0 2px 10px rgba(0,0,0,.3);
            display: flex; align-items: center; gap: 10px;
        `;
        banniere.textContent = texte;
        document.body.appendChild(banniere);
        return banniere;
    }

    function retirerBanniere() {
        if (banniere) { banniere.remove(); banniere = null; }
    }

    function trouverChampSaisie() {
        const candidats = [
            ...document.querySelectorAll('div[contenteditable="true"]'),
            ...document.querySelectorAll('textarea'),
        ].filter(el => el.offsetParent !== null); // visibles seulement
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

    function appellerClaude(messageClient) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method: 'POST',
                url: 'https://api.anthropic.com/v1/messages',
                headers: {
                    'Content-Type': 'application/json',
                    'x-api-key': CLAUDE_API_KEY,
                    'anthropic-version': '2023-06-01',
                    'anthropic-dangerous-direct-browser-access': 'true',
                },
                data: JSON.stringify({
                    model: CLAUDE_MODEL,
                    max_tokens: 500,
                    system: SYSTEM_PROMPT,
                    messages: [{ role: 'user', content: messageClient }],
                }),
                onload: function (res) {
                    try {
                        const json = JSON.parse(res.responseText);
                        if (json.content && json.content[0] && json.content[0].text) {
                            resolve(json.content[0].text.trim());
                        } else {
                            reject(json.error ? json.error.message : 'Réponse API inattendue');
                        }
                    } catch (e) {
                        reject('Erreur de lecture de la réponse API');
                    }
                },
                onerror: function () { reject('Erreur réseau vers l\'API Claude'); },
                ontimeout: function () { reject('Timeout API Claude'); },
            });
        });
    }

    async function repondreAuMessageSelectionne() {
        const messageClient = window.getSelection().toString().trim();
        if (!messageClient) {
            creerBanniere('⚠️ Sélectionne d\'abord le message du client');
            setTimeout(retirerBanniere, 2500);
            return;
        }
        if (CLAUDE_API_KEY === 'COLLE_TA_CLE_API_ICI') {
            creerBanniere('⚠️ Clé API Claude non configurée dans le script');
            setTimeout(retirerBanniere, 3000);
            return;
        }

        creerBanniere('⏳ L\'IA rédige la réponse...');

        let reponse;
        try {
            reponse = await appellerClaude(messageClient);
        } catch (err) {
            creerBanniere('❌ Erreur : ' + err);
            setTimeout(retirerBanniere, 4000);
            return;
        }

        const champ = trouverChampSaisie();
        if (!champ) {
            creerBanniere('❌ Champ de réponse introuvable');
            setTimeout(retirerBanniere, 3000);
            return;
        }
        ecrireDansChamp(champ, reponse);

        // Compte à rebours annulable avant envoi automatique
        let annule = false;
        let secondesRestantes = Math.ceil(DELAI_ANNULATION_MS / 1000);
        const b = creerBanniere(`Envoi dans ${secondesRestantes}s… (Échap pour annuler)`);
        const btnAnnuler = document.createElement('button');
        btnAnnuler.textContent = 'Annuler';
        btnAnnuler.style.cssText = 'background:#e63946;color:#fff;border:0;border-radius:5px;padding:4px 8px;cursor:pointer;';
        btnAnnuler.onclick = () => { annule = true; retirerBanniere(); };
        b.appendChild(btnAnnuler);

        function surEchap(e) {
            if (e.key === 'Escape') { annule = true; retirerBanniere(); }
        }
        document.addEventListener('keydown', surEchap, true);

        const intervalle = setInterval(() => {
            secondesRestantes--;
            if (annule || secondesRestantes <= 0) {
                clearInterval(intervalle);
                document.removeEventListener('keydown', surEchap, true);
                if (!annule) {
                    simulerEntree(champ);
                    retirerBanniere();
                }
                return;
            }
            if (banniere) banniere.firstChild.textContent = `Envoi dans ${secondesRestantes}s… (Échap pour annuler)`;
        }, 1000);
    }

    document.addEventListener('keydown', function (e) {
        const ctrlOk = RACCOURCI.ctrlOrCmd ? (e.ctrlKey || e.metaKey) : true;
        const shiftOk = RACCOURCI.shift ? e.shiftKey : true;
        if (ctrlOk && shiftOk && e.key.toLowerCase() === RACCOURCI.key) {
            e.preventDefault();
            repondreAuMessageSelectionne();
        }
    }, true);

})();
