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
  const filter = page.getByRole('searchbox', { name: 'Filter current source' });
  await filter.fill('groove salad');
  const groove = page.locator('#viewRadio [data-radio-play="groovesalad"]').first();
  // Catalogue labels and stable IDs are both searchable.
  await filter.fill('groovesalad');
  await expect(groove).toBeVisible();
  await expect(page.locator('#viewRadio [data-radio-play="dronezone"]')).toHaveCount(0);
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
      body: 'export const verifyEvent = window.NostrTools.verifyEvent;',
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
