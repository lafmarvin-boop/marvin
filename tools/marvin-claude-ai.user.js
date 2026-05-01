// ==UserScript==
// @name         Marvin — Graphify Context Injector
// @namespace    https://github.com/lafmarvin-boop/marvin
// @version      1.3
// @description  Injecte le contexte graphify au démarrage + intercepte /reprise-de-session
// @match        https://claude.ai/*
// @grant        GM_xmlhttpRequest
// ==/UserScript==

(function () {
    'use strict';

    const GIST_URL = 'https://gist.githubusercontent.com/lafmarvin-boop/36254227eb7908ce5c178193117fcb6c/raw/marvin-context.md';
    let lastUrl = '';

    function findEditor() {
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
        ed.focus();
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
        const ok = document.execCommand('insertText', false, text);
        if (!ok) {
            const ev = new InputEvent('input', {
                bubbles: true, cancelable: true,
                data: text, inputType: 'insertText'
            });
            ed.dispatchEvent(ev);
        }
        return ed.innerText.trim().length > 0;
    }

    function fetchAndInject(url) {
        GM_xmlhttpRequest({
            method: 'GET',
            url: url,
            timeout: 6000,
            onload: function (res) {
                if (res.status !== 200 || !res.responseText.trim()) return;
                const text = res.responseText.trim();
                clearEditor();
                let tries = 0;
                const t = setInterval(() => {
                    tries++;
                    if (injectText(text)) clearInterval(t);
                    else if (tries > 20) clearInterval(t);
                }, 300);
            },
            onerror: function () {
                console.warn('[Marvin] Impossible de charger le contexte depuis GitHub Gist');
            },
            ontimeout: function () {
                console.warn('[Marvin] Timeout GitHub Gist');
            }
        });
    }

    function checkCommand() {
        const text = getEditorText();
        if (text === '/reprise-de-session') {
            clearEditor();
            fetchAndInject(GIST_URL);
            return true;
        }
        return false;
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

    function onUrlChange() {
        const url = window.location.href;
        if (url === lastUrl) return;
        lastUrl = url;
        if (url.match(/claude\.ai\/(new|chat\/new)/)) {
            setTimeout(() => fetchAndInject(GIST_URL), 1500);
        }
    }

    const observer = new MutationObserver(onUrlChange);
    observer.observe(document.documentElement, { childList: true, subtree: true });
    onUrlChange();

})();
