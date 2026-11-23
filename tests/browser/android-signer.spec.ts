import { expect, test } from '@playwright/test';
import { finalizeEvent, getPublicKey, nip19 } from 'nostr-tools';

const secret = Uint8Array.from({ length: 32 }, (_, index) => index + 1);
const pubkey = getPublicKey(secret);
const draft = { kind: 1, created_at: 1750000000, tags: [['t', 'radioguaka']], content: 'NIP-55 & sign?' };

function signerRequest(url: string) {
  const queryIndex = url.indexOf('?');
  const query = new URLSearchParams(url.slice(queryIndex + 1));
  const callback = query.get('callbackUrl');
  if (!callback) throw new Error('Missing NIP-55 callback');
  return { payload: decodeURIComponent(url.slice('nostrsigner:'.length, queryIndex)), query, callback };
}

test.beforeEach(async ({ page }) => {
  await page.goto('/nip55-return.html');
  const path = require.resolve('nostr-tools').replace(/\/cjs\/[^/]+$/, '/nostr.bundle.js');
  await page.addScriptTag({ path });
  await page.evaluate(() => {
    window.presentSignerRequest = (prompt) => {
      window.signerPrompt = prompt;
      return () => { delete window.signerPrompt; };
    };
  });
});

test('connects an Android signer by callback and verifies a signature-only response', async ({ page, context }) => {
  await page.evaluate(() => {
    window.signerConnection = RadioAndroidSigner.connect(window.NostrTools, window.presentSignerRequest);
  });
  await expect.poll(() => page.evaluate(() => window.signerPrompt?.method)).toBe('get_public_key');
  const connection = signerRequest(await page.evaluate(() => window.signerPrompt.url));
  expect(connection.payload).toBe('');
  expect(new URL(connection.callback).search).toBe('');
  const callbackTab = await context.newPage();
  await callbackTab.goto(connection.callback + encodeURIComponent(nip19.npubEncode(pubkey)));
  await expect(callbackTab).toHaveURL(/\/nip55-return\.html$/);
  await expect.poll(() => page.evaluate(async () => (await window.signerConnection).pubkey)).toBe(pubkey);

  await page.evaluate((event) => {
    window.signedReply = window.signerConnection.then((signer) => signer.signEvent(event));
  }, draft);
  await expect.poll(() => page.evaluate(() => window.signerPrompt?.method)).toBe('sign_event');
  const pending = signerRequest(await page.evaluate(() => window.signerPrompt.url));
  expect(pending.query.get('current_user')).toBe(pubkey);
  expect(JSON.parse(pending.payload)).toEqual({ ...draft, pubkey });
  const signed = finalizeEvent(draft, secret);
  await callbackTab.goto(pending.callback + encodeURIComponent(signed.sig));
  const reply = await page.evaluate(() => window.signedReply);
  expect(reply).toEqual(JSON.parse(JSON.stringify(signed)));
  expect(await page.evaluate(() => !!window.signerPrompt)).toBe(false);
});

test('discards a signed event with altered content and cancels queued signer operations', async ({ page }) => {
  await page.evaluate(async ({ key, event }) => {
    window.activeSigner = await RadioAndroidSigner.connect(window.NostrTools, window.presentSignerRequest, key);
    window.signedReply = window.activeSigner.signEvent(event).catch((error) => error.message);
  }, { key: pubkey, event: draft });
  await expect.poll(() => page.evaluate(() => window.signerPrompt?.method)).toBe('sign_event');
  const altered = finalizeEvent({ ...draft, content: 'Modified' }, secret);
  await page.evaluate((signed) => window.signerPrompt.acceptResult(JSON.stringify(signed)), altered);
  expect(await page.evaluate(() => window.signedReply)).toContain('invalid or modified event');

  await page.evaluate((peer) => {
    window.decryptReply = window.activeSigner.nip44Decrypt(peer, 'secret Box==').catch((error) => error.message);
    window.queuedReply = window.activeSigner.nip04Encrypt(peer, 'next').catch((error) => error.message);
  }, pubkey);
  await expect.poll(() => page.evaluate(() => window.signerPrompt?.method)).toBe('nip44_decrypt');
  const pending = signerRequest(await page.evaluate(() => window.signerPrompt.url));
  expect(pending.payload).toBe('secret Box==');
  expect(pending.query.get('pubkey')).toBe(pubkey);
  await page.evaluate(() => window.activeSigner.close());
  expect(await page.evaluate(() => window.decryptReply)).toContain('cancelled');
  expect(await page.evaluate(() => window.queuedReply)).toContain('disconnected');
  expect(await page.evaluate(() => !!window.signerPrompt)).toBe(false);
});
