// ScrollCat content script — floating cat button + inline reply suggestions.
// Runs on WhatsApp Web, Instagram, Messenger, Telegram, Gmail, Slack, Twitter/X.

(() => {
  'use strict';

  // ── Site detection ─────────────────────────────────────────────
  // Each site config: mode id, badge emoji, colorClass for the platform
  // glow, composer input selector, last-incoming-message selector, and
  // how text should be inserted ('execCommand' for contenteditable
  // composers, 'value' for plain inputs).

  const SITES = {
    'web.whatsapp.com': {
      mode: 'whatsapp',
      badge: '💬',
      colorClass: 'scrollcat-whatsapp',
      inputSelector: 'div[contenteditable="true"][data-tab="10"], footer div[contenteditable="true"]',
      lastMessageSelector: '.message-in .selectable-text span, .message-in .selectable-text',
      insertMethod: 'execCommand',
      watchNewMessages: true
    },
    'www.instagram.com': {
      mode: 'instagram',
      badge: '📱',
      colorClass: 'scrollcat-instagram',
      // DM composer + comment boxes
      inputSelector: 'div[contenteditable="true"][aria-label="Message..."], div[contenteditable="true"][aria-label*="Message"], textarea[aria-label*="comment" i]',
      lastMessageSelector: 'div[role="row"]:last-child span, div[role="row"] div[dir="auto"]',
      insertMethod: 'execCommand'
    },
    'www.facebook.com': {
      mode: 'messenger',
      badge: '💬',
      colorClass: 'scrollcat-messenger',
      inputSelector: 'div[contenteditable="true"][aria-label*="message" i]',
      lastMessageSelector: 'div[data-scope="messages_table"] span:last-child, div[role="row"] div[dir="auto"]',
      insertMethod: 'execCommand'
    },
    'web.telegram.org': {
      mode: 'telegram',
      badge: '✈️',
      colorClass: 'scrollcat-telegram',
      inputSelector: 'div.input-message-input[contenteditable="true"], div[contenteditable="true"].composer_rich_textarea',
      lastMessageSelector: '.message.last-in .text-content, .message .text-content',
      insertMethod: 'execCommand'
    },
    'mail.google.com': {
      mode: 'gmail',
      badge: '📧',
      colorClass: '',
      inputSelector: 'div[contenteditable="true"][aria-label*="Body" i], div[contenteditable="true"][role="textbox"]',
      lastMessageSelector: 'div.a3s, h2.hP',
      insertMethod: 'execCommand'
    },
    'app.slack.com': {
      mode: 'slack',
      badge: '💼',
      colorClass: '',
      inputSelector: 'div[contenteditable="true"].ql-editor, div[contenteditable="true"][role="textbox"]',
      lastMessageSelector: '[data-qa="message_content"] .p-rich_text_section',
      insertMethod: 'execCommand'
    },
    'twitter.com': {
      mode: 'twitter',
      badge: '🐦',
      colorClass: '',
      inputSelector: 'div[contenteditable="true"][data-testid^="tweetTextarea"], div[contenteditable="true"][data-testid="dmComposerTextInput"]',
      lastMessageSelector: 'article [data-testid="tweetText"]',
      insertMethod: 'execCommand'
    }
  };
  SITES['x.com'] = SITES['twitter.com'];

  const site = SITES[location.hostname];
  if (!site) return;

  let enabled = true;          // per-site toggle from popup settings
  let catButton = null;
  let badgeEl = null;
  let panel = null;
  let focusedInput = null;     // last focused editable element
  let generationSeq = 0;       // stale-response guard
  let lastIncomingCount = -1;  // new-message detection

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
    if (site.watchNewMessages) watchIncomingMessages();
    document.addEventListener('keydown', onKeydown, true);
  }

  function teardown() {
    catButton?.remove(); catButton = null;
    hidePanel();
    document.removeEventListener('keydown', onKeydown, true);
  }

  // ── Floating cat button (draggable, platform glow) ─────────────

  function createCatButton() {
    catButton = document.createElement('div');
    catButton.className = 'scrollcat-button' + (site.colorClass ? ' ' + site.colorClass : '');
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

    // SPAs swap the composer without focus events
    const observer = new MutationObserver(() => {
      if (focusedInput && !document.contains(focusedInput)) {
        focusedInput = null;
        hidePanel();
      }
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }

  /** Composer for this site — the focused element wins, else query selectors. */
  function findComposer() {
    if (focusedInput && document.contains(focusedInput)) return focusedInput;
    return document.querySelector(site.inputSelector);
  }

  // WhatsApp: excited bounce when a new incoming message appears
  function watchIncomingMessages() {
    const check = () => {
      const count = document.querySelectorAll('.message-in').length;
      if (lastIncomingCount >= 0 && count > lastIncomingCount) {
        bounceCat();
        showBadge();
      }
      lastIncomingCount = count;
    };
    const observer = new MutationObserver(check);
    observer.observe(document.body, { childList: true, subtree: true });
    check();
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
      const nodes = document.querySelectorAll(site.lastMessageSelector);
      if (nodes.length) text = nodes[nodes.length - 1].innerText;
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
    // Above the composer when we can find one, else near the cat button
    let left = window.innerWidth - 400;
    let top = window.innerHeight - 320;
    const composer = findComposer();
    if (composer) {
      const rect = composer.getBoundingClientRect();
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
    const input = findComposer();
    if (!input) {
      navigator.clipboard?.writeText(text).catch(() => {});
      return;
    }
    input.focus();
    if (input.isContentEditable) {
      // contenteditable composers (WhatsApp, Instagram, Messenger, Telegram…)
      const inserted = document.execCommand('insertText', false, text);
      if (!inserted) {
        input.textContent += text;
      }
      // Instagram/Messenger React composers need an explicit input event
      input.dispatchEvent(new Event('input', { bubbles: true }));
    } else {
      input.value = text;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }
  }

  // ── Stats ──────────────────────────────────────────────────────

  function recordUse() {
    chrome.runtime.sendMessage({ type: 'RECORD_USE', site: site.mode });
  }
})();
