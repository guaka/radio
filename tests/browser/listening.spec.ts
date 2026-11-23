import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';

function silentWav() {
  const samples = 8000 * 15;
  const wav = Buffer.alloc(44 + samples * 2);
  wav.write('RIFF', 0);
  wav.writeUInt32LE(wav.length - 8, 4);
  wav.write('WAVEfmt ', 8);
  wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20);
  wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(8000, 24);
  wav.writeUInt32LE(16000, 28);
  wav.writeUInt16LE(2, 32);
  wav.writeUInt16LE(16, 34);
  wav.write('data', 36);
  wav.writeUInt32LE(samples * 2, 40);
  return wav;
}

test.beforeEach(async ({ page }) => {
  const wav = silentWav();
  await page.route('**/*', async (route) => {
    if (new URL(route.request().url()).hostname === '127.0.0.1') await route.continue();
    else if (route.request().resourceType() === 'media') await route.fulfill({ contentType: 'audio/wav', body: wav });
    else if (route.request().url().includes('nostr-tools@2.7.2')) await route.fallback();
    else await route.abort();
  });
});

test('filters Radio, preserves audio in Favorites and History, and restores paused', async ({ page }) => {
  await page.goto('/');
  const sources = page.getByRole('navigation', { name: 'Listening sources' });
  await expect(sources.getByRole('button', { name: 'Radio', exact: true })).toHaveAttribute('aria-current', 'page');
  const filter = page.getByRole('searchbox', { name: 'Search Radio, YouTube and Plex' });
  const groove = page.locator('#unifiedSearchPanel [data-unified-radio="groovesalad"]').first();
  await filter.fill('groovesalad');
  await expect(groove).toBeVisible();
  await expect(page.locator('#unifiedSearchPanel [data-unified-radio="dronezone"]')).toHaveCount(0);
  await groove.click();
  const audio = page.locator('#audio');
  await expect.poll(() => audio.evaluate((node: HTMLAudioElement) => !node.paused && node.currentTime > 0)).toBe(true);
  const sourceUrl = await audio.evaluate((node: HTMLAudioElement) => node.currentSrc);
  await page.locator('#viewRadio [data-radio-star="groovesalad"]').first().click();
  await sources.getByRole('button', { name: 'Favorites' }).click();
  await expect(page.locator('#viewLibrary [data-radio-play="groovesalad"]')).toBeVisible();
  await sources.getByRole('button', { name: 'History' }).click();
  await expect(page).toHaveURL(/#recent$/);
  await expect.poll(() => audio.evaluate((node: HTMLAudioElement) => [node.paused, node.currentSrc])).toEqual([false, sourceUrl]);
  await page.reload();
  await expect(page.locator('#npTitle')).toContainText(/groove\s*salad/i);
  await expect.poll(() => audio.evaluate((node: HTMLAudioElement) => node.paused)).toBe(true);
});

test('opens existing favorites links and keeps Plex setup in Plex', async ({ page }) => {
  await page.goto('/#favorites');
  await page.getByRole('button', { name: 'Connections', exact: true }).click();
  await expect(page.getByText('Connections', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Close', exact: true }).click();
  const sources = page.getByRole('navigation', { name: 'Listening sources' });
  await expect(sources.getByRole('button', { name: 'Favorites' })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('button', { name: 'Connect to Plex' })).toBeHidden();
  await sources.getByRole('button', { name: 'Plex', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Connect to Plex' })).toBeVisible();
  await page.getByRole('button', { name: 'Connect to Plex' }).click();
  await expect(page.getByText('Connections', { exact: true })).toBeVisible();
  await expect(page.getByRole('checkbox', { name: /share and sync radio favorites/i })).not.toBeChecked();
});

test('syncs encrypted history between devices and keeps a local clear pending until accepted', async ({ page, context, browser }) => {
  const bundle = require.resolve('nostr-tools').replace(/\/cjs\/[^/]+$/, '/nostr.bundle.js');
  const setupSigner = `
    window.NostrTools = NostrTools;
    const secret = new Uint8Array(32);
    secret[31] = 1;
    const pubkey = NostrTools.getPublicKey(secret);
    const key = NostrTools.nip44.v2.utils.getConversationKey(secret, pubkey);
    window.nostr = {
      getPublicKey: async () => pubkey,
      signEvent: async (event) => NostrTools.finalizeEvent(event, secret),
      nip44: {
        encrypt: async (peer, plaintext) => {
          if (peer !== pubkey) throw new Error('Unexpected encryption recipient');
          return NostrTools.nip44.v2.encrypt(plaintext, key);
        },
        decrypt: async (peer, ciphertext) => {
          if (peer !== pubkey) throw new Error('Unexpected decryption recipient');
          return NostrTools.nip44.v2.decrypt(ciphertext, key);
        },
      },
    };
  `;
  const tools = require('nostr-tools');
  const relay = 'wss://history.test/';
  const snapshots = new Map();
  let acceptWrites = true;
  async function installFixture(device) {
    await device.addInitScript({ content: readFileSync(bundle, 'utf8') + '\n' + setupSigner });
    await device.route('https://cdn.jsdelivr.net/npm/nostr-tools@2.7.2/+esm', (route) => route.fulfill({
      contentType: 'text/javascript',
      headers: { 'access-control-allow-origin': '*' },
      body: 'export const { verifyEvent, nip19 } = window.NostrTools; export class SimplePool { async querySync() { return []; } }',
    }));
    await device.routeWebSocket(relay, (socket) => {
      socket.onMessage((data) => {
        const [type, value, filter] = JSON.parse(String(data));
        if (type === 'REQ') {
          for (const event of snapshots.values()) {
            if (filter.authors.includes(event.pubkey)) socket.send(JSON.stringify(['EVENT', value, event]));
          }
          socket.send(JSON.stringify(['EOSE', value]));
        } else if (type === 'EVENT') {
          const valid = acceptWrites && tools.verifyEvent(value) && value.kind === 30078 &&
            !JSON.stringify(value).includes('groovesalad');
          if (valid) snapshots.set(value.tags.find(([tag]) => tag === 'd')[1], value);
          socket.send(JSON.stringify(['OK', value.id, valid, valid ? '' : 'blocked: test write disabled']));
        }
      });
    });
  }
  await installFixture(context);

  async function enableHistory(tab) {
    await tab.getByRole('button', { name: 'Connections', exact: true }).click();
    await tab.getByLabel('History relay URL').fill(relay);
    await tab.getByLabel('Sync listening history', { exact: true }).check();
    await expect(tab.locator('#historySyncStatus')).toHaveText('Listening history synced.');
    await tab.getByRole('button', { name: 'Close', exact: true }).click();
  }

  await page.goto('/');
  await enableHistory(page);
  await page.locator('#viewRadio [data-radio-play="groovesalad"]').first().click();
  await expect.poll(() => snapshots.size).toBe(1);
  const peer = await browser.newContext();
  await installFixture(peer);
  const second = await peer.newPage();
  await second.goto('http://127.0.0.1:4173/#recent');
  await enableHistory(second);
  await expect(second.locator('#viewLibrary [data-radio-play="groovesalad"]')).toBeVisible();

  acceptWrites = false;
  await second.getByRole('button', { name: 'Clear history', exact: true }).click();
  await expect(second.locator('#viewLibrary [data-radio-play="groovesalad"]')).toHaveCount(0);
  await second.getByRole('button', { name: 'Connections', exact: true }).click();
  await expect(second.locator('#historySyncStatus')).toContainText('Relay update pending');
  acceptWrites = true;
  await second.getByRole('button', { name: 'Retry history sync' }).click();
  await expect(second.locator('#historySyncStatus')).toHaveText('Listening history synced.');
  await expect.poll(() => snapshots.size).toBe(2);
  await page.getByRole('button', { name: 'Connections', exact: true }).click();
  await page.getByRole('button', { name: 'Retry history sync' }).click();
  await expect(page.locator('#historySyncStatus')).toHaveText('Listening history synced.');
  await page.getByRole('button', { name: 'Close', exact: true }).click();
  await page.getByRole('button', { name: 'History', exact: true }).click();
  await expect(page.locator('#viewLibrary [data-radio-play="groovesalad"]')).toHaveCount(0);
  await peer.close();
});

test('explains why replaying YouTube history on the static site needs a gateway', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('guakaRadioYouTubeHistory', JSON.stringify({
      v: 1, clearBefore: 0, entries: [['youtube:dQw4w9WgXcQ', Date.now()]],
    }));
  });
  await page.goto('/#recent');
  await page.getByRole('button', { name: 'YouTube · dQw4w9WgXcQ', exact: true }).click();
  await expect(page.locator('#historyReplayStatus')).toContainText('Go media gateway');
});

test('wraps the remote signer key at rest and deletes it on disconnect', async ({ page }) => {
  await page.goto('/');
  const testKey = Array.from({ length: 32 }, (_, i) => i + 1);
  const reference = await page.evaluate((key) => RadioRemoteSigner.saveTransportKey(Uint8Array.from(key)), testKey);
  await page.reload();
  const stored = await page.evaluate(async (id) => {
    const key = await RadioRemoteSigner.loadTransportKey(id);
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('guakaRemoteSigner', 1);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const record = await new Promise<{ wrappingKey: CryptoKey; iv: Uint8Array; ciphertext: ArrayBuffer }>((resolve, reject) => {
      const request = database.transaction('connections').objectStore('connections').get(id);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    database.close();
    return { key: Array.from(key), storedFields: Object.keys(record), extractable: record.wrappingKey.extractable,
      ciphertext: Array.from(new Uint8Array(record.ciphertext)) };
  }, reference);
  expect(stored.key).toEqual(testKey);
  expect(stored.storedFields).toEqual(['wrappingKey', 'iv', 'ciphertext']);
  expect(stored.extractable).toBe(false);
  expect(stored.ciphertext).toHaveLength(48);
  expect(stored.ciphertext.slice(0, testKey.length)).not.toEqual(testKey);
  await page.evaluate((id) => RadioRemoteSigner.removeTransportKey(id), reference);
  await expect(page.evaluate((id) => RadioRemoteSigner.loadTransportKey(id), reference)).rejects.toThrow('Saved remote signer key is unavailable');
});

test('uses APK artwork and searches Radio, YouTube and connected Plex from the same input', async ({ page }) => {
  await page.route('https://pipedapi.ducks.party/search?**', (route) => route.fulfill({
    headers: { 'access-control-allow-origin': '*' },
    json: { items: [
      { type: 'stream', title: 'Groove Salad on YouTube', uploaderName: 'SomaFM', url: '/watch?v=dQw4w9WgXcQ' },
      { type: 'stream', title: 'Invalid upstream result', url: 'https://other.example/?v=dQw4w9WgXcQ' },
    ] },
  }));
  await page.route('**/hubs/search?**', (route) => route.fulfill({
    json: { MediaContainer: { Hub: [{ Metadata: [
      { type: 'album', title: 'Groove Salad Plex Album', ratingKey: '1', librarySectionID: '2' },
      { type: 'track', title: 'Groove Salad Plex Track', ratingKey: '3', parentRatingKey: '1', librarySectionID: '2' },
    ] }] } },
  }));
  await page.route('**/library/sections?**', (route) => route.fulfill({
    json: { MediaContainer: { Directory: [] } },
  }));
  await page.addInitScript(() => {
    localStorage.setItem('plexServer', location.origin);
    localStorage.setItem('plexToken', 'test-token');
  });
  await page.goto('/#favorites');
  await expect(page.locator('header img.brand-app-logo')).toHaveAttribute('src', '/app-logo.svg');
  await expect(page.getByRole('button', { name: 'Radio', exact: true }).locator('use')).toHaveAttribute('href', '/app-icons.svg#radio');
  await page.getByRole('searchbox', { name: 'Search Radio, YouTube and Plex' }).fill('groove salad');
  await expect(page.locator('[data-unified-radio="groovesalad"]')).toBeVisible();
  await expect(page.getByRole('link', { name: /Groove Salad on YouTube/ })).toHaveAttribute('href', 'https://www.youtube.com/watch?v=dQw4w9WgXcQ');
  await expect(page.getByRole('button', { name: /Groove Salad Plex Track/ })).toBeVisible();
  await expect(page.getByRole('link', { name: /Invalid upstream result/ })).toHaveCount(0);
  await page.getByRole('button', { name: 'Clear search', exact: true }).click();
  await expect(page.locator('#unifiedSearchPanel')).toBeHidden();
  await expect(page.getByRole('button', { name: 'Favorites', exact: true })).toHaveAttribute('aria-current', 'page');
});

test('removes legacy private keys on startup and offers external signers', async ({ page }) => {
  await page.addInitScript(() => {
    if (sessionStorage.getItem('legacyKeySeeded')) return;
    sessionStorage.setItem('legacyKeySeeded', 'yes');
    localStorage.setItem('guakaNostrNsec', 'legacy-test-key');
    localStorage.setItem('radio_guaka_chat_private_key', 'legacy-chat-test-key');
  });
  await page.goto('/?connections=1');
  await expect(page.getByRole('button', { name: 'Connect browser signer (NIP-07)' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Connect remote signer (NIP-46)' })).toBeVisible();
  await expect(page.locator('#inNsec')).toHaveCount(0);
  expect(await page.evaluate(() => [
    localStorage.getItem('guakaNostrNsec'),
    localStorage.getItem('radio_guaka_chat_private_key'),
  ])).toEqual([null, null]);
});
