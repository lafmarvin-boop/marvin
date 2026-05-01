// ==UserScript==
// @name         Marvin — Graphify Context Injector
// @namespace    https://github.com/lafmarvin-boop/marvin
// @version      1.1
// @description  Injecte le contexte graphify au démarrage + intercepte /reprise-de-session
// @match        https://claude.ai/*
// @grant        GM_xmlhttpRequest
// ==/UserScript==

(function () {
    'use strict';

    const SERVER = 'http://localhost:7842';
    let lastUrl = '';

    function findEditor() {
        return document.querySelector('div[contenteditable="true"]');
    }

    function clearEditor() {
        const editor = findEditor();
        if (!editor) return;
        editor.focus();
        document.execCommand('selectAll', false, null);
        document.execCommand('delete', false, null);
    }

    function injectText(text) {
        const editor = findEditor();
        if (!editor) return false;
        editor.focus();
        document.execCommand('insertText', false, text);
        return editor.innerText.trim().length > 0;
    }

    function fetchAndInject(endpoint) {
        GM_xmlhttpRequest({
            method: 'GET',
            url: SERVER + endpoint,
            onload: function (res) {
                if (res.status !== 200 || !res.responseText.trim()) return;
                const context = res.responseText.trim();
                let tries = 0;
                const interval = setInterval(() => {
                    tries++;
                    if (injectText(context)) clearInterval(interval);
                    else if (tries > 16) clearInterval(interval);
                }, 500);
            },
            onerror: function () {
                console.warn('[Marvin] Serveur graphify non disponible sur localhost:7842');
            }
        });
    }

    // Intercept /reprise-de-session typed in the editor
    function watchForCommand() {
        document.addEventListener('keydown', function (e) {
            if (e.key !== 'Enter') return;
            const editor = findEditor();
            if (!editor) return;
            const text = editor.innerText.trim();
            if (text === '/reprise-de-session') {
                e.preventDefault();
                e.stopPropagation();
                clearEditor();
                fetchAndInject('/sessions');
            }
        }, true);
    }

    function onUrlChange() {
        const url = window.location.href;
        if (url === lastUrl) return;
        lastUrl = url;
        if (url.match(/claude\.ai\/(new|chat\/new)/)) {
            setTimeout(() => fetchAndInject('/context'), 1200);
        }
    }

    const observer = new MutationObserver(onUrlChange);
    observer.observe(document.documentElement, { childList: true, subtree: true });

    watchForCommand();
    onUrlChange();
})();
