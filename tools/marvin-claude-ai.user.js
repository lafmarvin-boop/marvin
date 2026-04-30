// ==UserScript==
// @name         Marvin — Graphify Context Injector
// @namespace    https://github.com/lafmarvin-boop/marvin
// @version      1.0
// @description  Injecte le contexte graphify (résumé de session + graph stats) au démarrage de chaque nouvelle conversation claude.ai
// @match        https://claude.ai/*
// @grant        GM_xmlhttpRequest
// ==/UserScript==

(function () {
    'use strict';

    const SERVER = 'http://localhost:7842/context';
    let lastUrl = '';

    function findEditor() {
        return document.querySelector('div[contenteditable="true"]');
    }

    function injectText(text) {
        const editor = findEditor();
        if (!editor) return false;
        editor.focus();
        // ProseMirror accepts execCommand insertText
        document.execCommand('insertText', false, text);
        return true;
    }

    function fetchAndInject() {
        GM_xmlhttpRequest({
            method: 'GET',
            url: SERVER,
            onload: function (res) {
                if (res.status !== 200 || !res.responseText.trim()) return;
                const context = res.responseText.trim();
                // Retry until the editor is ready (max 8s)
                let tries = 0;
                const interval = setInterval(() => {
                    tries++;
                    if (injectText(context)) {
                        clearInterval(interval);
                    } else if (tries > 16) {
                        clearInterval(interval);
                    }
                }, 500);
            },
            onerror: function () {
                console.warn('[Marvin] Serveur graphify non disponible sur localhost:7842');
            }
        });
    }

    function onUrlChange() {
        const url = window.location.href;
        if (url === lastUrl) return;
        lastUrl = url;
        // Inject only on new conversation pages
        if (url.match(/claude\.ai\/(new|chat\/new)/)) {
            setTimeout(fetchAndInject, 1200);
        }
    }

    // Watch SPA navigation
    const observer = new MutationObserver(onUrlChange);
    observer.observe(document.documentElement, { childList: true, subtree: true });

    // Handle initial load
    onUrlChange();
})();
