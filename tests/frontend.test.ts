const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const test = require('node:test');

const root = resolve(__dirname, '..');
const html = readFileSync(resolve(root, 'public/js/player/app.js'), 'utf8');

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
