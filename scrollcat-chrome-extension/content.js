// ScrollCat content script — floating cat button + inline reply suggestions.
// Runs on Gmail, WhatsApp Web, Slack, Instagram and Twitter/X.

(() => {
  'use strict';

  // ── Site detection ─────────────────────────────────────────────

  const SITES = {
    'mail.google.com':  { mode: 'gmail',     badge: '📧' },
    'web.whatsapp.com': { mode: 'whatsapp',  badge: '💬' },
    'app.slack.com':    { mode: 'slack',     badge: '💼' },
    'www.instagram.com':{ mode: 'instagram', badge: '📱' },
    'twitter.com':      { mode: 'twitter',   badge: '🐦' },
    'x.com':            { mode: 'twitter',   badge: '🐦' }
  };

  const site = SITES[location.hostname];
  if (!site) return;

  let enabled = true;          // per-site toggle from popup settings
  let catButton = null;
  let badgeEl = null;
  let panel = null;
  let focusedInput = null;     // last focused editable element
  let generationSeq = 0;       // stale-response guard

  chrome.storage.sync.get({ siteToggles: {} }, (data) => {
    if (data.siteToggles[site.mode] === false) enabled = false;
    if (enabled) init();
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === 'sync' && changes.siteToggles) {
      const toggles = changes.siteToggles.newValue || {};
      const nowEnabled = toggles[site.mode] !== false;
      if (nowEnabled && !catButton) { enabled = true; init(); }
      if (!nowEnabled && catButton) { enabled = false; teardown(); }
    }
  });

  function init() {
    createCatButton();
    watchInputs();
    document.addEventListener('keydown', onKeydown, true);
  }

  function teardown() {
    catButton?.remove(); catButton = null;
    hidePanel();
    document.removeEventListener('keydown', onKeydown, true);
  }

  // ── Floating cat button (draggable) ────────────────────────────

  function createCatButton() {
    catButton = document.createElement('div');
    catButton.className = 'scrollcat-button';
    catButton.textContent = '🐱';
    catButton.title = 'ScrollCat — Alt+R for reply suggestions';

    badgeEl = document.createElement('div');
    badgeEl.className = 'scrollcat-badge';
    badgeEl.textContent = site.badge;
    badgeEl.style.display = 'none';
    catButton.appendChild(badgeEl);

    // Drag handling: distinguish click (toggle panel) from drag (move)
    let dragging = false;
    let moved = false;
    let startX = 0, startY = 0, origRight = 24, origBottom = 24;

    catButton.addEventListener('mousedown', (e) => {
      dragging = true;
      moved = false;
      startX = e.clientX;
      startY = e.clientY;
      const rect = catButton.getBoundingClientRect();
      origRight = window.innerWidth - rect.right;
      origBottom = window.innerHeight - rect.bottom;
      e.preventDefault();
    });

    document.addEventListener('mousemove', (e) => {
      if (!dragging) return;
      const dx = e.clientX - startX;
      const dy = e.clientY - startY;
      if (Math.abs(dx) > 4 || Math.abs(dy) > 4) moved = true;
      catButton.style.right = Math.max(0, origRight - dx) + 'px';
      catButton.style.bottom = Math.max(0, origBottom - dy) + 'px';
    });

    document.addEventListener('mouseup', () => {
      if (dragging && !moved) togglePanel();
      dragging = false;
    });

    document.body.appendChild(catButton);
    showBadge();
  }

  function showBadge() {
    if (!badgeEl) return;
    badgeEl.style.display = 'flex';
  }

  function bounceCat() {
    if (!catButton) return;
    catButton.classList.remove('scrollcat-bounce');
    // Force reflow so the animation restarts
    void catButton.offsetWidth;
    catButton.classList.add('scrollcat-bounce');
  }

  // ── Input watching ─────────────────────────────────────────────

  function isEditable(el) {
    if (!el) return false;
    if (el.tagName === 'TEXTAREA') return true;
    if (el.tagName === 'INPUT' && /^(text|search|email)$/.test(el.type)) return true;
    if (el.isContentEditable) return true;
    return false;
  }

  function watchInputs() {
    document.addEventListener('focusin', (e) => {
      if (isEditable(e.target)) focusedInput = e.target;
    }, true);

    // Some SPAs (WhatsApp/Instagram) swap the composer without focus events
    const observer = new MutationObserver(() => {
      if (focusedInput && !document.contains(focusedInput)) {
        focusedInput = null;
        hidePanel();
      }
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }

  function onKeydown(e) {
    if (e.altKey && (e.key === 'r' || e.key === 'R')) {
      e.preventDefault();
      togglePanel();
    }
  }

  // ── Context extraction (last message / email subject) ──────────

  function getContextText() {
    let text = '';
    try {
      switch (site.mode) {
        case 'gmail': {
          // Open email body, else subject line
          const body = document.querySelector('div.a3s');
          const subject = document.querySelector('h2.hP');
          text = (body?.innerText || subject?.innerText || '');
          break;
        }
        case 'whatsapp': {
          const msgs = document.querySelectorAll('.message-in .selectable-text, [data-pre-plain-text] .selectable-text');
          text = msgs.length ? msgs[msgs.length - 1].innerText : '';
          break;
        }
        case 'slack': {
          const msgs = document.querySelectorAll('[data-qa="message_content"] .p-rich_text_section');
          text = msgs.length ? msgs[msgs.length - 1].innerText : '';
          break;
        }
        case 'instagram': {
          const msgs = document.querySelectorAll('div[role="row"] div[dir="auto"]');
          text = msgs.length ? msgs[msgs.length - 1].innerText : '';
          break;
        }
        case 'twitter': {
          const tweet = document.querySelector('article [data-testid="tweetText"]');
          text = tweet?.innerText || '';
          break;
        }
      }
    } catch (err) {
      // selector drift on redesigns — degrade to defaults
    }
    return (text || '').trim().slice(0, 800);
  }

  // ── Suggestion panel ───────────────────────────────────────────

  function togglePanel() {
    if (panel) { hidePanel(); return; }
    showPanel();
  }

  function showPanel() {
    hidePanel();
    panel = document.createElement('div');
    panel.className = 'scrollcat-panel';

    const dismiss = document.createElement('button');
    dismiss.className = 'scrollcat-dismiss';
    dismiss.textContent = '✕';
    dismiss.addEventListener('click', hidePanel);
    panel.appendChild(dismiss);

    const title = document.createElement('div');
    title.className = 'scrollcat-panel-title';
    title.textContent = `${site.badge} ScrollCat suggests`;
    panel.appendChild(title);

    const thinking = document.createElement('div');
    thinking.className = 'scrollcat-thinking';
    thinking.textContent = '🐾 Cat is thinking...';
    panel.appendChild(thinking);

    positionPanel();
    document.body.appendChild(panel);

    const seq = ++generationSeq;
    const context = getContextText();

    chrome.runtime.sendMessage(
      { type: 'GENERATE_REPLIES', site: site.mode, context },
      (response) => {
        if (seq !== generationSeq || !panel) return; // stale or closed
        thinking.remove();
        const suggestions = (response && response.suggestions) || [];
        if (!suggestions.length) {
          const err = document.createElement('div');
          err.className = 'scrollcat-thinking';
          err.textContent = '😿 No suggestions available';
          panel.appendChild(err);
          return;
        }
        suggestions.forEach((s) => {
          const chip = document.createElement('button');
          chip.className = 'scrollcat-chip';
          chip.textContent = s;
          chip.addEventListener('click', () => {
            insertText(s);
            recordUse();
            hidePanel();
          });
          panel.appendChild(chip);
        });
        bounceCat();
      }
    );
  }

  function positionPanel() {
    // Above the focused input when we have one, else near the cat button
    let left = window.innerWidth - 400;
    let top = window.innerHeight - 320;
    if (focusedInput && document.contains(focusedInput)) {
      const rect = focusedInput.getBoundingClientRect();
      left = Math.max(12, Math.min(rect.left, window.innerWidth - 380));
      top = Math.max(12, rect.top - 220);
    }
    panel.style.left = left + 'px';
    panel.style.top = top + 'px';
  }

  function hidePanel() {
    panel?.remove();
    panel = null;
  }

  // ── Text insertion ─────────────────────────────────────────────

  function insertText(text) {
    const input = (focusedInput && document.contains(focusedInput)) ? focusedInput : null;
    if (!input) {
      navigator.clipboard?.writeText(text).catch(() => {});
      return;
    }
    input.focus();
    if (input.isContentEditable) {
      // contenteditable composers (WhatsApp, Slack, Instagram, Twitter)
      const inserted = document.execCommand('insertText', false, text);
      if (!inserted) {
        input.textContent += text;
        input.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
      }
    } else {
      input.value = text;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }
  }

  // ── Stats ──────────────────────────────────────────────────────

  function recordUse() {
    chrome.runtime.sendMessage({ type: 'RECORD_USE' });
  }
})();
