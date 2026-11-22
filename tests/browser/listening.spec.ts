import { expect, test } from '@playwright/test';

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
