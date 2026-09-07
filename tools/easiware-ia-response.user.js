// ==UserScript==
// @name         Marvin — Réponse IA Easiware
// @namespace    https://github.com/lafmarvin-boop/marvin
// @version      3.0
// @description  Alt+Clic (normal, envoi auto) ou Alt+Maj+Clic (signalement, envoi manuel) sur le message du client
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
    // Raccourci sélection manuelle (mode normal) : Alt+Maj+R
    // (Ctrl+Maj+A est réservé par Firefox — gestionnaire de modules — donc évité ici)
    const RACCOURCI = { key: 'r', shift: true, alt: true };
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

    function trouverBulleMessage(cible) {
        // Remonte depuis l'élément cliqué pour capturer tout le message (pas juste un mot ou un span),
        // sans dépasser vers un conteneur qui contiendrait plusieurs messages.
        let el = cible;
        let precedent = cible;
        for (let i = 0; i < 6 && el.parentElement; i++) {
            const parent = el.parentElement;
            const enfantsAvecTexte = [...parent.children].filter(c => c.textContent.trim().length > 15);
            if (enfantsAvecTexte.length > 1) break; // le parent contient plusieurs messages, on s'arrête là
            precedent = parent;
            el = parent;
        }
        return precedent.textContent.trim();
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

    function appellerClaude(messageClient, systemPrompt) {
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
                    system: systemPrompt,
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

    async function repondreAuMessage(messageDirect, modeSignalement) {
        const messageClient = messageDirect || window.getSelection().toString().trim();
        if (!messageClient) {
            creerBanniere('Sélectionne d\'abord le message du client');
            setTimeout(retirerBanniere, 2500);
            return;
        }
        if (CLAUDE_API_KEY === 'COLLE_TA_CLE_API_ICI') {
            creerBanniere('Clé API Claude non configurée dans le script');
            setTimeout(retirerBanniere, 3000);
            return;
        }

        creerBanniere('L\'IA rédige la réponse...');

        let reponse;
        try {
            reponse = await appellerClaude(messageClient, modeSignalement ? SYSTEM_PROMPT_SIGNALEMENT : SYSTEM_PROMPT_CLIENT);
        } catch (err) {
            creerBanniere('Erreur : ' + err);
            setTimeout(retirerBanniere, 4000);
            return;
        }

        const champ = trouverChampSaisie();
        if (!champ) {
            creerBanniere('Champ de réponse introuvable');
            setTimeout(retirerBanniere, 3000);
            return;
        }
        ecrireDansChamp(champ, reponse);

        if (modeSignalement) {
            // Jamais d'envoi automatique sur ce mode : relecture et envoi manuels obligatoires.
            creerBanniere('Réponse écrite — relis puis envoie toi-même (Entrée)');
            setTimeout(retirerBanniere, 4000);
            return;
        }

        // Compte à rebours annulable avant envoi automatique
        let annule = false;
        let secondesRestantes = Math.ceil(DELAI_ANNULATION_MS / 1000);
        const b = creerBanniere(`Envoi dans ${secondesRestantes}s (Échap pour annuler)`);
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
            if (banniere) banniere.firstChild.textContent = `Envoi dans ${secondesRestantes}s (Échap pour annuler)`;
        }, 1000);
    }

    document.addEventListener('keydown', function (e) {
        const altOk = RACCOURCI.alt ? (e.altKey || e.getModifierState('AltGraph')) : true;
        const shiftOk = RACCOURCI.shift ? e.shiftKey : true;
        if (altOk && shiftOk && e.key.toLowerCase() === RACCOURCI.key) {
            e.preventDefault();
            repondreAuMessage(undefined, false);
        }
    }, true);

    // Alt+Clic (normal, envoi auto) ou Alt+Maj+Clic (signalement, envoi manuel) directement sur un message
    document.addEventListener('click', function (e) {
        if (!e.altKey && !e.getModifierState('AltGraph')) return;
        const texte = trouverBulleMessage(e.target);
        if (!texte) {
            creerBanniere('Aucun texte trouvé ici — clique directement sur les mots du message');
            setTimeout(retirerBanniere, 3000);
            return;
        }
        e.preventDefault();
        e.stopPropagation();
        repondreAuMessage(texte, e.shiftKey);
    }, true);

})();
