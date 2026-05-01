// ==UserScript==
// @name         Marvin — Graphify Context Injector
// @namespace    https://github.com/lafmarvin-boop/marvin
// @version      1.2
// @description  Injecte le contexte graphify au démarrage + intercepte /reprise-de-session
// @match        https://claude.ai/*
// @grant        GM_xmlhttpRequest
// ==/UserScript==

(function () {
    'use strict';

    const SERVER = 'http://localhost:7842';
    let lastUrl = '';

    // ── Editor helpers ──────────────────────────────────────────────────────

    function findEditor() {
        // Claude.ai uses ProseMirror; the main input is the deepest contenteditable
        const all = document.querySelectorAll('div[contenteditable="true"]');
        // Return the last one (the message input, not toolbar elements)
        return all.length ? all[all.length - 1] : null;
    }

    function getEditorText() {
        const ed = findEditor();
        return ed ? ed.innerText.replace(/ /g, ' ').trim() : '';
    }

    function clearEditor() {
        const ed = findEditor();
        if (!ed) return;
        ed.focus();
        // Select all and delete
        const sel = window.getSelection();
        const range = document.createRange();
        range.selectNodeContents(ed);
        sel.removeAllRanges();
        sel.addRange(range);
        document.execCommand('delete', false, null);
    }

    function injectText(text) {
        const ed = findEditor();
        if (!ed) return false;
        ed.focus();
        // Use execCommand insertText — works with ProseMirror
        const ok = document.execCommand('insertText', false, text);
        if (!ok) {
            // Fallback: dispatch InputEvent
            const ev = new InputEvent('input', { bubbles: true, cancelable: true, data: text, inputType: 'insertText' });
            ed.dispatchEvent(ev);
        }
        return ed.innerText.trim().length > 0;
    }

    // ── Fetch context from local server ─────────────────────────────────────

    function fetchAndInject(endpoint, onDone) {
        GM_xmlhttpRequest({
            method: 'GET',
            url: SERVER + endpoint,
            timeout: 3000,
            onload: function (res) {
                if (res.status !== 200 || !res.responseText.trim()) return;
                const text = res.responseText.trim();
                clearEditor();
                let tries = 0;
                const t = setInterval(() => {
                    tries++;
                    if (injectText(text)) { clearInterval(t); if (onDone) onDone(); }
                    else if (tries > 20) clearInterval(t);
                }, 300);
            },
            onerror: function () {
                console.warn('[Marvin] Serveur graphify non disponible sur localhost:7842');
                alert('[Marvin] Le serveur graphify n\'est pas démarré.\n\nLance :\n  python3 /home/user/marvin/tools/graphify-context-server.py &');
            },
            ontimeout: function () {
                console.warn('[Marvin] Timeout localhost:7842');
            }
        });
    }

    // ── Intercept /reprise-de-session ────────────────────────────────────────

    function checkCommand() {
        const text = getEditorText();
        if (text === '/reprise-de-session') {
            clearEditor();
            fetchAndInject('/sessions');
            return true;
        }
        return false;
    }

    // Listen on keydown (capture) to intercept Enter before claude.ai submits
    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Enter' || e.shiftKey) return;
        if (checkCommand()) {
            e.preventDefault();
            e.stopImmediatePropagation();
        }
    }, true);

    // Also watch for button click (claude.ai has a send button)
    document.addEventListener('click', function (e) {
        const btn = e.target.closest('button[type="submit"], button[aria-label*="Send"], button[data-testid*="send"]');
        if (!btn) return;
        if (checkCommand()) {
            e.preventDefault();
            e.stopImmediatePropagation();
        }
    }, true);

    // ── Auto-inject context on new conversation ──────────────────────────────

    function onUrlChange() {
        const url = window.location.href;
        if (url === lastUrl) return;
        lastUrl = url;
        if (url.match(/claude\.ai\/(new|chat\/new)/)) {
            setTimeout(() => fetchAndInject('/context'), 1500);
        }
    }

    const observer = new MutationObserver(onUrlChange);
    observer.observe(document.documentElement, { childList: true, subtree: true });
    onUrlChange();

})();
