namespace RadioHistory {
  export const kind = 30078;
  export const topic = 'radio-guaka-history-v1';
  export const limit = 100;
  export const maxContent = 48 * 1024;
  export const devicePattern = /^radio-guaka-history-v1:[a-f0-9-]{36}$/;

  export type Snapshot = { v: 1; clearBefore: number; entries: [string, number][] };
  export const empty = (): Snapshot => ({ v: 1, clearBefore: 0, entries: [] });

  export function eligible(id: string) {
    return /^(radio:[A-Za-z0-9_-]{1,80}|youtube:[A-Za-z0-9_-]{11})$/.test(id);
  }

  export function merge(states: Snapshot[]): Snapshot {
    const clearBefore = Math.max(0, ...states.map((state) => state.clearBefore));
    const ordered = states.flatMap((state) => state.entries).filter(([, at]) => at > clearBefore)
      .sort(([a, at], [b, bt]) => bt - at || (a < b ? -1 : a > b ? 1 : 0));
    const seen = new Set();
    const entries = ordered.filter(([id]) => {
      if (seen.has(id)) return false;
      seen.add(id);
      return true;
    });
    return { v: 1, clearBefore, entries: entries.slice(0, limit) };
  }

  export function decode(text: string, now = Date.now()): Snapshot {
    if (typeof text !== 'string' || text.length > maxContent) throw new Error('Invalid history snapshot.');
    const state = JSON.parse(text);
    const validTime = (at) => Number.isSafeInteger(at) && at >= 0 && at <= now + 300_000;
    if (state?.v !== 1 || !validTime(state.clearBefore) || !Array.isArray(state.entries) || state.entries.length > limit ||
        state.entries.some((row) => !Array.isArray(row) || row.length !== 2 || typeof row[0] !== 'string' ||
          !eligible(row[0]) || !validTime(row[1]) || row[1] === 0)) throw new Error('Invalid history snapshot.');
    return merge([state]);
  }

  export function newestSnapshots(events, now = Math.floor(Date.now() / 1000)) {
    const latest = new Map();
    for (const event of events) {
      if (event.kind !== kind || typeof event.content !== 'string' || !event.content.length || event.content.length > maxContent ||
          !Number.isSafeInteger(event.created_at) || event.created_at <= 0 || event.created_at > now + 300) continue;
      if (!event.tags.some((tag) => tag[0] === 't' && tag[1] === topic)) continue;
      const tags = event.tags.filter((tag) => tag[0] === 'd');
      if (tags.length !== 1 || !devicePattern.test(tags[0][1])) continue;
      const address = tags[0][1];
      const previous = latest.get(address);
      if (!previous || event.created_at > previous.created_at ||
          (event.created_at === previous.created_at && event.id < previous.id)) latest.set(address, event);
    }
    return latest;
  }

  export function secureRelay(input: string) {
    const url = new URL(input.trim());
    if (url.protocol !== 'wss:' || !url.hostname || url.username || url.password || url.search || url.hash || url.href.length > 2048) {
      throw new Error('Enter a secure wss:// relay URL.');
    }
    return url.href;
  }

  export function isEncrypted(value) {
    if (typeof value !== 'string' || value.length < 132 || value.length > maxContent) return false;
    try { return atob(value.slice(0, 4)).charCodeAt(0) === 2; }
    catch (_) { return false; }
  }
}
