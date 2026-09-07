// Source du bookmarklet "Marvin — Réponse IA Easiware" (poste sans installation possible).
// Pas d'extension : ce code tourne uniquement pendant que la page reste ouverte.
// Il faut re-cliquer le favori après chaque rechargement de la page easiware.
//
// Deux modes :
//   - Alt+Clic               : cas normal. Réponse écrite puis envoyée automatiquement
//                               après un délai annulable.
//   - Alt+Shift+Clic          : cas sensible (signalement). Réponse orientée recueil
//                               d'informations, écrite dans le champ mais JAMAIS envoyée
//                               automatiquement — relecture et envoi manuels obligatoires.
//     L'IA gère elle-même la fin de l'échange : une fois les infos utiles recueillies
//     (ou si la personne n'a plus rien à ajouter), elle remercie et dit au revoir.
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

    let banniere = null;

    function creerBanniere(texte) {
        if (banniere) banniere.remove();
        banniere = document.createElement('div');
        banniere.style.cssText = 'position:fixed;top:12px;right:12px;z-index:2147483647;background:#1a1a1a;color:#fff;padding:10px 14px;border-radius:8px;font:13px/1.4 sans-serif;box-shadow:0 2px 10px rgba(0,0,0,.3);display:flex;align-items:center;gap:10px;';
        banniere.textContent = texte;
        document.body.appendChild(banniere);
        return banniere;
    }

    function retirerBanniere() {
        if (banniere) { banniere.remove(); banniere = null; }
    }

    function trouverBulleMessage(cible) {
        let el = cible;
        let precedent = cible;
        for (let i = 0; i < 6 && el.parentElement; i++) {
            const parent = el.parentElement;
            const enfantsAvecTexte = [...parent.children].filter(c => c.textContent.trim().length > 15);
            if (enfantsAvecTexte.length > 1) break;
            precedent = parent;
            el = parent;
        }
        return precedent.textContent.trim();
    }

    function trouverChampSaisie() {
        const candidats = [
            ...document.querySelectorAll('div[contenteditable="true"]'),
            ...document.querySelectorAll('textarea'),
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

    async function repondreAuMessage(messageDirect, modeSignalement) {
        const messageClient = messageDirect || window.getSelection().toString().trim();
        if (!messageClient) {
            creerBanniere("Sélectionne d'abord le message du client");
            setTimeout(retirerBanniere, 2500);
            return;
        }

        creerBanniere("L'IA rédige la réponse...");

        let reponse;
        try {
            reponse = await appellerClaude(messageClient, modeSignalement ? SYSTEM_PROMPT_SIGNALEMENT : SYSTEM_PROMPT_CLIENT);
        } catch (err) {
            creerBanniere('Erreur : ' + err.message);
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
        if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key.toLowerCase() === 'a') {
            e.preventDefault();
            repondreAuMessage(undefined, false);
        }
    }, true);

    document.addEventListener('click', function (e) {
        if (!e.altKey) return;
        const texte = trouverBulleMessage(e.target);
        if (!texte) return;
        e.preventDefault();
        e.stopPropagation();
        repondreAuMessage(texte, e.shiftKey);
    }, true);

    creerBanniere('Marvin IA actif — Alt+Clic (normal), Alt+Maj+Clic (signalement)');
    setTimeout(retirerBanniere, 3500);

})();
