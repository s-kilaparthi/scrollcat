// ScrollCat service worker — reply generation via Claude API with
// per-site smart defaults when no API key is configured.

'use strict';

const CLAUDE_ENDPOINT = 'https://api.anthropic.com/v1/messages';
const CLAUDE_MODEL = 'claude-haiku-4-5';
const MAX_TOKENS = 150;
const ANTHROPIC_VERSION = '2023-06-01';

const SITE_DEFAULTS = {
  gmail:     ['Thanks for reaching out!', "I'll get back to you shortly.", 'Sounds good!'],
  whatsapp:  ['Sure!', 'On my way!', 'Let me check and get back to you'],
  slack:     ['On it!', 'Got it, thanks!', "I'll take a look shortly"],
  instagram: ['Thank you so much! 🙏', 'This means everything! 💕', 'So glad you enjoy it! ❤️'],
  twitter:   ['Thanks!', 'Great point!', 'Absolutely agree!']
};

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === 'GENERATE_REPLIES') {
    generateReplies(message.site, message.context)
      .then((suggestions) => sendResponse({ suggestions }))
      .catch(() => sendResponse({ suggestions: defaultsFor(message.site) }));
    return true; // async response
  }

  if (message.type === 'RECORD_USE') {
    bumpStat('used');
    return false;
  }

  if (message.type === 'TEST_API_KEY') {
    testApiKey(message.apiKey)
      .then((result) => sendResponse(result))
      .catch((err) => sendResponse({ ok: false, error: String(err && err.message || err) }));
    return true; // async response
  }

  return false;
});

function defaultsFor(site) {
  return SITE_DEFAULTS[site] || SITE_DEFAULTS.whatsapp;
}

async function generateReplies(site, context) {
  bumpStat('suggested');

  const { claudeApiKey } = await chrome.storage.local.get('claudeApiKey');
  if (!claudeApiKey || !context) {
    return defaultsFor(site);
  }

  try {
    const suggestions = await callClaude(claudeApiKey, context);
    return suggestions.length ? suggestions : defaultsFor(site);
  } catch (err) {
    console.warn('ScrollCat: Claude call failed, using defaults —', err);
    return defaultsFor(site);
  }
}

async function callClaude(apiKey, context) {
  const { replyTone = 'friendly', userType = 'personal' } =
    await chrome.storage.sync.get(['replyTone', 'userType']);

  const persona =
    userType === 'creator'  ? 'The user is a content creator replying to their audience.' :
    userType === 'business' ? 'The user is a business owner replying to customers.' :
    '';

  const response = await fetch(CLAUDE_ENDPOINT, {
    method: 'POST',
    headers: {
      'x-api-key': apiKey,
      'anthropic-version': ANTHROPIC_VERSION,
      'content-type': 'application/json'
    },
    body: JSON.stringify({
      model: CLAUDE_MODEL,
      max_tokens: MAX_TOKENS,
      system: `Generate 3 short reply options under 15 words each. Return JSON array of 3 strings only. No other text. Tone: ${replyTone}. ${persona}`,
      messages: [{ role: 'user', content: context }]
    })
  });

  if (!response.ok) {
    throw new Error(`Claude API HTTP ${response.status}`);
  }

  const data = await response.json();
  const text = (data.content && data.content[0] && data.content[0].text) || '';
  return parseSuggestions(text);
}

function parseSuggestions(text) {
  // Preferred: model returns a bare JSON array
  try {
    const parsed = JSON.parse(text);
    if (Array.isArray(parsed)) {
      return parsed.filter((s) => typeof s === 'string' && s.trim()).slice(0, 3);
    }
  } catch (e) { /* fall through */ }

  // Recovery: JSON array embedded in prose
  const match = text.match(/\[[\s\S]*?\]/);
  if (match) {
    try {
      const parsed = JSON.parse(match[0]);
      if (Array.isArray(parsed)) {
        return parsed.filter((s) => typeof s === 'string' && s.trim()).slice(0, 3);
      }
    } catch (e) { /* fall through */ }
  }

  // Last resort: one suggestion per line
  return text.split('\n')
    .map((line) => line.replace(/^\s*[-\d.)"\]]+\s*/, '').replace(/["\],]+\s*$/, '').trim())
    .filter(Boolean)
    .slice(0, 3);
}

async function testApiKey(apiKey) {
  if (!apiKey) return { ok: false, error: 'No API key provided' };
  try {
    const suggestions = await callClaudeWithKey(apiKey, 'Hey, are we still on for tomorrow?');
    return suggestions.length
      ? { ok: true, sample: suggestions[0] }
      : { ok: false, error: 'Empty response from Claude' };
  } catch (err) {
    return { ok: false, error: String(err && err.message || err) };
  }
}

async function callClaudeWithKey(apiKey, context) {
  const response = await fetch(CLAUDE_ENDPOINT, {
    method: 'POST',
    headers: {
      'x-api-key': apiKey,
      'anthropic-version': ANTHROPIC_VERSION,
      'content-type': 'application/json'
    },
    body: JSON.stringify({
      model: CLAUDE_MODEL,
      max_tokens: MAX_TOKENS,
      system: 'Generate 3 short reply options under 15 words each. Return JSON array of 3 strings only. No other text.',
      messages: [{ role: 'user', content: context }]
    })
  });
  if (!response.ok) throw new Error(`Claude API HTTP ${response.status}`);
  const data = await response.json();
  const text = (data.content && data.content[0] && data.content[0].text) || '';
  return parseSuggestions(text);
}

// ── Daily stats (suggested / used), reset each day ───────────────

async function bumpStat(kind) {
  const today = new Date().toISOString().slice(0, 10);
  const { stats = {} } = await chrome.storage.sync.get('stats');
  if (stats.date !== today) {
    stats.date = today;
    stats.suggested = 0;
    stats.used = 0;
  }
  stats[kind] = (stats[kind] || 0) + 1;
  await chrome.storage.sync.set({ stats });
}
