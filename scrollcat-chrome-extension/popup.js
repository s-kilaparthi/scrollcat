// ScrollCat popup — settings, stats and API key management.
// Settings → chrome.storage.sync; API key → chrome.storage.local only.

'use strict';

// Primary messaging platforms (WhatsApp + Instagram ON by default)
// followed by the rest. All toggles default ON unless turned off.
const SITE_GROUPS = [
  {
    header: 'Messaging Apps (Primary)',
    sites: [
      { id: 'whatsapp',  label: '💬 WhatsApp' },
      { id: 'instagram', label: '📱 Instagram' },
      { id: 'messenger', label: '💬 Messenger' },
      { id: 'telegram',  label: '✈️ Telegram' }
    ]
  },
  {
    header: 'Other Apps',
    sites: [
      { id: 'gmail',   label: '📧 Gmail' },
      { id: 'slack',   label: '💼 Slack' },
      { id: 'twitter', label: '🐦 Twitter/X' }
    ]
  }
];

const SITE_LABELS = {};
SITE_GROUPS.forEach((g) => g.sites.forEach((s) => { SITE_LABELS[s.id] = s.label; }));

const $ = (id) => document.getElementById(id);

document.addEventListener('DOMContentLoaded', async () => {
  // ── API key (local storage only — never synced) ──
  const { claudeApiKey = '' } = await chrome.storage.local.get('claudeApiKey');
  $('apiKey').value = claudeApiKey;

  $('saveKey').addEventListener('click', async () => {
    const key = $('apiKey').value.trim();
    await chrome.storage.local.set({ claudeApiKey: key });
    setStatus(key ? 'API key saved ✓' : 'API key cleared — using smart defaults', 'ok');
  });

  $('testKey').addEventListener('click', async () => {
    const key = $('apiKey').value.trim();
    if (!key) { setStatus('Enter an API key first', 'err'); return; }
    setStatus('Testing…', '');
    chrome.runtime.sendMessage({ type: 'TEST_API_KEY', apiKey: key }, (result) => {
      if (result && result.ok) {
        setStatus(`Works! Sample: "${result.sample}"`, 'ok');
      } else {
        setStatus(`Failed: ${(result && result.error) || 'unknown error'}`, 'err');
      }
    });
  });

  // ── Tone + user type (synced) ──
  const { replyTone = 'friendly', userType = 'personal' } =
    await chrome.storage.sync.get(['replyTone', 'userType']);

  document.querySelector(`#toneRow input[value="${replyTone}"]`)?.click();
  document.querySelector(`#typeRow input[value="${userType}"]`)?.click();

  $('toneRow').addEventListener('change', (e) => {
    chrome.storage.sync.set({ replyTone: e.target.value });
  });
  $('typeRow').addEventListener('change', (e) => {
    chrome.storage.sync.set({ userType: e.target.value });
  });

  // ── Stats (totals + per-platform breakdown) ──
  const today = new Date().toISOString().slice(0, 10);
  const { stats = {} } = await chrome.storage.sync.get('stats');
  const isToday = stats.date === today;
  $('statSuggested').textContent = isToday ? (stats.suggested || 0) : 0;
  $('statUsed').textContent = isToday ? (stats.used || 0) : 0;

  const breakdown = $('statBreakdown');
  const perSite = (isToday && stats.perSite) || {};
  const activeSites = Object.keys(perSite);
  if (!activeSites.length) {
    breakdown.textContent = 'No activity yet today';
  } else {
    activeSites.forEach((id) => {
      const row = document.createElement('div');
      row.className = 'breakdown-row';
      const s = perSite[id];
      row.innerHTML = '';
      const name = document.createElement('span');
      name.textContent = SITE_LABELS[id] || id;
      const nums = document.createElement('span');
      nums.textContent = `${s.suggested || 0} suggested · ${s.used || 0} used`;
      row.appendChild(name);
      row.appendChild(nums);
      breakdown.appendChild(row);
    });
  }

  // ── Site toggles, grouped by section ──
  const { siteToggles = {} } = await chrome.storage.sync.get('siteToggles');
  const list = $('sitesList');
  SITE_GROUPS.forEach((group) => {
    const header = document.createElement('div');
    header.className = 'site-group-header';
    header.textContent = group.header;
    list.appendChild(header);

    group.sites.forEach((site) => {
      const row = document.createElement('div');
      row.className = 'site-row';

      const label = document.createElement('span');
      label.textContent = site.label;
      row.appendChild(label);

      const switchLabel = document.createElement('label');
      switchLabel.className = 'switch';
      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.checked = siteToggles[site.id] !== false;
      const slider = document.createElement('span');
      slider.className = 'slider';
      switchLabel.appendChild(checkbox);
      switchLabel.appendChild(slider);
      row.appendChild(switchLabel);

      checkbox.addEventListener('change', async () => {
        const { siteToggles: current = {} } = await chrome.storage.sync.get('siteToggles');
        current[site.id] = checkbox.checked;
        await chrome.storage.sync.set({ siteToggles: current });
      });

      list.appendChild(row);
    });
  });
});

function setStatus(text, cls) {
  const el = $('keyStatus');
  el.textContent = text;
  el.className = 'status' + (cls ? ' ' + cls : '');
}
