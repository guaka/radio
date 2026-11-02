import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const android = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = fs.readFileSync(path.join(android, '../src/frontend/channels.ts'), 'utf8');
// Read only the catalogue literal; artwork fetches and browser setup are not executed.
const start = source.indexOf('const channels = {');
const end = source.indexOf('\n};', start);
if (start < 0 || end < 0) throw new Error('Cannot locate station catalogue');
const channels = vm.runInNewContext(source.slice(start, end + 3) + '\nchannels;', {}, { timeout: 1000 });
function artworkUrl(id, entry) {
  if (Array.isArray(entry.artwork)) {
    const first = entry.artwork.find((item) => item && typeof item.src === 'string' && item.src.trim());
    if (first) return first.src.trim();
  } else if (typeof entry.artwork === 'string' && entry.artwork.trim()) {
    return entry.artwork.trim();
  }
  if (entry.tags?.includes('soma')) return '';
  const site = entry.site || entry.url || '';
  try {
    const host = new URL(site).hostname.replace(/^www\./, '');
    if (host) return `https://logo.clearbit.com/${encodeURIComponent(host)}`;
  } catch (_) {}
  return `https://api.dicebear.com/9.x/shapes/png?seed=${encodeURIComponent(id)}`;
}
const stations = Object.entries(channels).flatMap(([id, entry]) => {
  const url = entry.url || (entry.tags?.includes('soma') ? `https://ice1.somafm.com/${id}-128-mp3` : '');
  if (!/^https?:\/\//.test(url)) return [];
  return [{ id, title: entry.title || entry.name || id, url, tags: (entry.tags || []).join(', '), artwork: artworkUrl(id, entry) }];
});
const output = path.join(android, 'app/src/main/assets/stations.json');
const text = JSON.stringify(stations, null, 2) + '\n';
if (process.argv.includes('--check')) {
  if (fs.readFileSync(output, 'utf8') !== text) throw new Error('Run node android/scripts/sync-stations.mjs');
} else { fs.mkdirSync(path.dirname(output), { recursive: true }); fs.writeFileSync(output, text); }
console.log(`${stations.length} stations ${process.argv.includes('--check') ? 'verified' : 'exported'}`);
