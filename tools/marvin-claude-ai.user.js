// ==UserScript==
// @name         Marvin — Graphify Context Injector
// @namespace    https://github.com/lafmarvin-boop/marvin
// @version      1.4
// @description  Injecte le contexte graphify au démarrage + intercepte /reprise-de-session
// @match        https://claude.ai/*
// @grant        GM_xmlhttpRequest
// ==/UserScript==

(function () {
    'use strict';

    const GIST_URL = 'https://gist.githubusercontent.com/lafmarvin-boop/36254227eb7908ce5c178193117fcb6c/raw/marvin-context.md';
    const COMMAND = '/reprise-de-session';
    const NEW_CHAT_RE = /claude\.ai\/(new|chat\/new)\b/;

    let lastUrl = '';
    let injectInProgress = false;

    function findEditor() {
        const composer = document.querySelector(
            'fieldset div[contenteditable="true"], div[aria-label*="Write"] [contenteditable="true"], div[aria-label*="prompt" i] [contenteditable="true"]'
        );
        if (composer) return composer;
        const all = document.querySelectorAll('div[contenteditable="true"]');
        return all.length ? all[all.length - 1] : null;
    }

    function getEditorText() {
        const ed = findEditor();
        return ed ? ed.innerText.replace(/ /g, ' ').trim() : '';
    }

    function clearEditor() {
        const ed = findEditor();
        if (!ed) return;
        if (ed.innerText.trim().length === 0) return;
        ed.focus();
        const sel = window.getSelection();
        const range = document.createRange();
        range.selectNodeContents(ed);
        sel.removeAllRanges();
        sel.addRange(range);
        document.execCommand('delete', false, null);
    }

    function injectText(text) {
        if (!text) return false;
        const ed = findEditor();
        if (!ed) return false;
        ed.focus();
        const ok = document.execCommand('insertText', false, text);
        if (!ok && ed.innerText.trim().length === 0) {
            const ev = new InputEvent('input', {
                bubbles: true, cancelable: true,
                data: text, inputType: 'insertText'
            });
            ed.dispatchEvent(ev);
        }
        return ed.innerText.trim().length > 0;
    }

    function fetchAndInject(url) {
        if (injectInProgress) return;
        injectInProgress = true;
        GM_xmlhttpRequest({
            method: 'GET',
            url: url,
            timeout: 6000,
            onload: function (res) {
                const text = (res && res.status === 200 && res.responseText) ? res.responseText.trim() : '';
                if (!text) {
                    injectInProgress = false;
                    return;
                }
                clearEditor();
                let tries = 0;
                const t = setInterval(() => {
                    tries++;
                    if (injectText(text) || tries > 20) {
                        clearInterval(t);
                        injectInProgress = false;
                    }
                }, 300);
            },
            onerror: function () {
                injectInProgress = false;
                console.warn('[Marvin] Impossible de charger le contexte depuis GitHub Gist');
            },
            ontimeout: function () {
                injectInProgress = false;
                console.warn('[Marvin] Timeout GitHub Gist');
            }
        });
    }

    function checkCommand() {
        if (getEditorText() !== COMMAND) return false;
        clearEditor();
        fetchAndInject(GIST_URL);
        return true;
    }

    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Enter' || e.shiftKey) return;
        if (checkCommand()) {
            e.preventDefault();
            e.stopImmediatePropagation();
        }
    }, true);

    document.addEventListener('click', function (e) {
        const btn = e.target.closest('button[type="submit"], button[aria-label*="Send"], button[data-testid*="send"]');
        if (!btn) return;
        if (checkCommand()) {
            e.preventDefault();
            e.stopImmediatePropagation();
        }
    }, true);

    let urlCheckScheduled = false;
    function onUrlChange() {
        const url = window.location.href;
        if (url === lastUrl) return;
        lastUrl = url;
        if (NEW_CHAT_RE.test(url)) {
            setTimeout(() => {
                if (getEditorText().length === 0) fetchAndInject(GIST_URL);
            }, 1500);
        }
    }

    function scheduleUrlCheck() {
        if (urlCheckScheduled) return;
        urlCheckScheduled = true;
        requestAnimationFrame(() => {
            urlCheckScheduled = false;
            onUrlChange();
        });
    }

    const observer = new MutationObserver(scheduleUrlCheck);
    observer.observe(document.body || document.documentElement, { childList: true, subtree: true });
    window.addEventListener('popstate', onUrlChange);
    onUrlChange();

})();
