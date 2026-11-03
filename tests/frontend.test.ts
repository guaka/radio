const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const test = require('node:test');

const root = resolve(__dirname, '..');
const html = readFileSync(resolve(root, 'public/js/player/app.js'), 'utf8');

function loadRadioListenerSetup() {
  const source = readFileSync(resolve(root, 'public/js/radio/app.js'), 'utf8');
  const timers = new Map();
  const watchers = {};
  let timerId = 0;
  let audio = null;
  const state = { srcUrl: '', volume: 1 };
  const context = vm.createContext({
    state,
    document: { getElementById: () => audio },
    setTimeout(callback) { timers.set(++timerId, callback); return timerId; },
    clearTimeout(id) { timers.delete(id); }
  });
  state.$watch = (name, callback) => { watchers[name] = callback; };
  state.$nextTick = (callback) => callback();
  const teardown = source.slice(source.indexOf('teardownAudioListeners(audioElement) {'), source.indexOf('toggleChat() {'));
  state.teardownAudioListeners = vm.runInContext(`({${teardown}}).teardownAudioListeners`, context);
  const setup = source.slice(source.indexOf('const setupAudioListeners ='), source.indexOf('// Wait for router'));
  vm.runInContext(`(function () { ${setup} }).call(state)`, context);
  return {
    state, timers,
    select(url, element = null) {
      audio = element;
      state.srcUrl = url;
      watchers.srcUrl(url);
    },
    tick() {
      const pending = [...timers.values()];
      timers.clear();
      pending.forEach(callback => callback());
    }
  };
}

test('radio audio setup stays idle without a station and bounds missing-element retries', () => {
  const radio = loadRadioListenerSetup();
  assert.equal(radio.timers.size, 0);
  radio.select('https://station.example/stream');
  for (let attempt = 0; attempt < 20; attempt++) {
    assert.equal(radio.timers.size, 1);
    radio.tick();
  }
  assert.equal(radio.timers.size, 0);
  radio.select('https://station.example/other');
  assert.equal(radio.timers.size, 1);
  radio.select('');
  assert.equal(radio.timers.size, 0);
});

test('radio listeners do not accumulate and detach when the audio element changes or disappears', () => {
  function makeAudio() {
    const listeners = new Map();
    return {
      currentTime: 42, duration: 180, listeners,
      addEventListener(type, listener) {
        if (!listeners.has(type)) listeners.set(type, new Set());
        listeners.get(type).add(listener);
      },
      removeEventListener(type, listener) { listeners.get(type)?.delete(listener); }
    };
  }
  const radio = loadRadioListenerSetup();
  const first = makeAudio();
  radio.select('first', first);
  radio.select('second', first);
  assert.equal(first.listeners.size, 7);
  for (const listeners of first.listeners.values()) assert.equal(listeners.size, 1);
  assert.equal(radio.state.currentTime, 42);
  for (const listener of first.listeners.get('loadedmetadata')) listener();
  assert.equal(radio.state.duration, 180);
  const second = makeAudio();
  radio.select('third', second);
  for (const listeners of first.listeners.values()) assert.equal(listeners.size, 0);
  for (const listeners of second.listeners.values()) assert.equal(listeners.size, 1);
  radio.select('');
  for (const listeners of second.listeners.values()) assert.equal(listeners.size, 0);
  assert.equal(radio.state._audioElement, null);
});

test('chat relay cleanup runs every 30 seconds only when visible and resumes on visibility change', () => {
  const source = readFileSync(resolve(root, 'public/js/chat/app.js'), 'utf8');
  const start = source.indexOf('if (this._relayCleanupInterval)');
  const end = source.indexOf('// Set up relay status tracking', start);
  let cleanupCount = 0;
  let interval;
  let visibilityChange;
  const cleared = [];
  const state = { _relayCleanupInterval: 7, cleanupRelays() { cleanupCount++; } };
  const document = {
    hidden: false,
    addEventListener(type, callback) {
      assert.equal(type, 'visibilitychange');
      visibilityChange = callback;
    }
  };
  const context = vm.createContext({
    state, document,
    clearInterval(id) { cleared.push(id); },
    setInterval(callback, delay) { assert.equal(delay, 30000); interval = callback; return 8; }
  });
  vm.runInContext(`(function () { ${source.slice(start, end)} }).call(state)`, context);
  assert.deepEqual(cleared, [7]);
  assert.equal(state._relayCleanupInterval, 8);
  interval();
  assert.equal(cleanupCount, 1);
  document.hidden = true;
  interval();
  visibilityChange();
  assert.equal(cleanupCount, 1);
  document.hidden = false;
  visibilityChange();
  assert.equal(cleanupCount, 2);
});

function loadFunctions(names, globals = {}) {
  const context = vm.createContext({ URL, ...globals });
  for (const name of names) {
    const start = html.search(new RegExp(`    (?:async )?function ${name}\\(`));
    assert.ok(start >= 0, `Missing function: ${name}`);
    const end = html.indexOf('\n    }', start) + 6;
    vm.runInContext(html.slice(start, end), context);
  }
  return context;
}

test('radio image fallback uses station data and ignores URLs injected into DOM attributes', () => {
  const trusted = 'https://station.example/fallback.png';
  const { applyRadioImageFallback } = loadFunctions(['fallbackThumbDataUrl', 'applyRadioImageFallback'], {
    radioStations: [{ id: 'radio', fallbackImg: trusted }]
  });
  const img = {
    src: 'broken', onerror: () => {},
    getAttribute(name) { return name === 'data-radio-image' ? 'radio' : 'data:text/html,<script>alert(1)</script>'; }
  };
  applyRadioImageFallback(img);
  assert.equal(img.src, trusted);
  assert.equal(img.onerror, null);
  img.getAttribute = () => '<img src=x onerror=alert(1)>';
  applyRadioImageFallback(img);
  assert.match(img.src, /^data:image\/svg\+xml,/);
  assert.ok(!decodeURIComponent(img.src).includes('alert(1)'));
});

test('compiled classic scripts parse', () => {
  for (const file of ['player/bootstrap.js', 'player/app.js', 'player/visualizer.js', 'radio/bootstrap.js', 'radio/build-info.js', 'radio/app.js', 'chat/app.js', 'routing/not-found.js', 'channels.js']) {
    const source = readFileSync(resolve(root, 'public/js', file), 'utf8');
    new vm.Script(source, { filename: file });
  }
});

test('Spotify CSV parses quoted delimiters, escaped quotes and CRLF', () => {
  const { parseCsvRows } = loadFunctions(['parseCsvRows']);
  const rows = parseCsvRows('Track Name,Artist Name(s)\r\n"One, Two","A ""Band"""\r\n"Multi\nline",Artist\r\n');
  assert.deepEqual(JSON.parse(JSON.stringify(rows)), [
    ['Track Name', 'Artist Name(s)'], ['One, Two', 'A "Band"'], ['Multi\nline', 'Artist']
  ]);
});

test('YouTube accepts supported URL forms and rejects other sites', () => {
  const { extractYouTubeVideoId } = loadFunctions(['extractYouTubeVideoId']);
  for (const input of ['dQw4w9WgXcQ', 'https://youtu.be/dQw4w9WgXcQ?t=1', 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', 'https://youtube.com/shorts/dQw4w9WgXcQ']) {
    assert.equal(extractYouTubeVideoId(input), 'dQw4w9WgXcQ');
  }
  for (const input of ['', 'invalid', 'https://evil.example/watch?v=dQw4w9WgXcQ']) {
    assert.equal(extractYouTubeVideoId(input), '');
  }
});

test('audiobook identity stays stable across chapter changes', () => {
  const { computeAudiobookBookId } = loadFunctions(['computeAudiobookBookId']);
  const book = { sectionKey: '1', albumRatingKey: '42' };
  assert.equal(computeAudiobookBookId({ ...book, trackRatingKey: 'a' }), computeAudiobookBookId({ ...book, trackRatingKey: 'b' }));
});

test('NIP-44 uses a conversation key and falls back to NIP-04 for older data', async () => {
  const key = new Uint8Array(32);
  const conversation = new Uint8Array(32).fill(1);
  const context = loadFunctions(['encryptNostrDmContent', 'decryptNostrDmContent'], { nostrState: { secretKey: key } });
  const tools = { nip44: { v2: {
    utils: { getConversationKey(secret, peer) { assert.equal(secret, key); assert.equal(peer, 'peer'); return conversation; } },
    encrypt(plain, derived) { assert.equal(derived, conversation); return `encrypted:${plain}`; },
    decrypt(cipher, derived) { assert.equal(derived, conversation); return cipher.slice(10); }
  } } };
  const result = await context.encryptNostrDmContent(tools, 'progress', 'peer');
  assert.equal(result.algorithm, 'nip44');
  assert.equal(await context.decryptNostrDmContent(tools, result.content, 'peer', 'nip44'), 'progress');
  const legacy = { nip04: { decrypt: async () => 'legacy progress' } };
  assert.equal(await context.decryptNostrDmContent(legacy, 'old payload', 'peer', 'nip04'), 'legacy progress');
});
