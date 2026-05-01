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
    let lastUrl = '';

    function findEditor() {
        const all = document.querySelectorAll('div[contenteditable="true"]');
        return all.length ? all[all.length - 1] : null;
    }

    function getEditorText() {
        const ed = findEditor();
        return ed ? ed.innerText.replace(/ /g, ' ').trim() : '';
    }

    function selectAll(ed) {
        ed.focus();
        const sel = window.getSelection();
        const range = document.createRange();
        range.selectNodeContents(ed);
        sel.removeAllRanges();
        sel.addRange(range);
    }

    function clearEditor() {
        const ed = findEditor();
        if (!ed) return;
        selectAll(ed);
        // Dispatch a real beforeinput/input pair so React/ProseMirror update their state.
        const before = new InputEvent('beforeinput', {
            bubbles: true, cancelable: true,
            inputType: 'deleteContentBackward',
        });
        ed.dispatchEvent(before);
        document.execCommand('delete', false, null);
        ed.dispatchEvent(new InputEvent('input', {
            bubbles: true, cancelable: true,
            inputType: 'deleteContentBackward',
        }));
    }

    function injectText(text) {
        const ed = findEditor();
        if (!ed) return false;
        ed.focus();

        // ProseMirror/Lexical (used by Claude.ai) ignore execCommand('insertText')
        // for state purposes — the DOM updates but React's internal value stays empty,
        // which makes the API reject the message with
        // "text content blocks must be non-empty".
        // A synthetic paste event with a DataTransfer is the reliable path.
        try {
            const dt = new DataTransfer();
            dt.setData('text/plain', text);
            const pasteEv = new ClipboardEvent('paste', {
                bubbles: true, cancelable: true, clipboardData: dt,
            });
            const delivered = ed.dispatchEvent(pasteEv);
            if (delivered && ed.innerText.trim().length > 0) return true;
        } catch (_) { /* fall through */ }

        // Fallback: beforeinput + insertText + input, which most rich editors honor.
        const beforeEv = new InputEvent('beforeinput', {
            bubbles: true, cancelable: true,
            data: text, inputType: 'insertText',
        });
        ed.dispatchEvent(beforeEv);
        document.execCommand('insertText', false, text);
        ed.dispatchEvent(new InputEvent('input', {
            bubbles: true, cancelable: true,
            data: text, inputType: 'insertText',
        }));
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
                let tries = 0;
                const t = setInterval(() => {
                    tries++;
                    // Clear before each attempt so a delayed-but-eventually-successful
                    // injection cannot duplicate content.
                    clearEditor();
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
