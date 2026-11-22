namespace RadioNostrSync {
  export const favoriteRelays = ['wss://relay.trustroots.org', 'wss://relay.nomadwiki.org'];
  export const favoriteLifetime = 30 * 24 * 60 * 60;
  const stationIdPattern = /^[A-Za-z0-9_-]{1,80}$/;

  export function favoriteTemplate(id, title, starred, createdAt) {
    if (!stationIdPattern.test(id)) throw new Error('Invalid station ID.');
    const name = title.trim().slice(0, 120);
    const tags = [
      ['t', 'radiostr'], ['t', 'favorite'], ['client', 'radio-guaka'],
      ['radiostr_station', id], ['radiostr_action', starred ? 'star' : 'unstar'],
      ['expiration', String(createdAt + favoriteLifetime)]
    ];
    if (name) tags.push(['radiostr_title', name]);
    return { kind: 1, created_at: createdAt, tags, content: (starred ? '⭐ ' : '☆ ') + (name || 'Station') + ' #radiostr #favorite' };
  }

  export function stationId(event) {
    const tags = event.tags.filter((tag) => tag[0] === 'radiostr_station');
    return tags.length === 1 ? tags[0][1] : '';
  }

  export function mergeFavorites(local, events, known, now) {
    const latest = new Map();
    for (const event of events) {
      if (event.kind !== 1 || !Number.isSafeInteger(event.created_at) || event.created_at <= 0 || event.created_at > now + 300) continue;
      const tags = event.tags;
      const tagValues = (name) => tags.filter((tag) => tag[0] === name).map((tag) => tag[1]);
      const topics = tagValues('t');
      const expiry = tagValues('expiration');
      const actions = tagValues('radiostr_action');
      const id = stationId(event);
      if (!known.has(id) || !stationIdPattern.test(id) || !topics.includes('radiostr') || !topics.includes('favorite') ||
          expiry.length !== 1 || Number(expiry[0]) !== event.created_at + favoriteLifetime || Number(expiry[0]) <= now ||
          actions.length !== 1 || !['star', 'unstar'].includes(actions[0])) continue;
      const previous = latest.get(id);
      if (!previous || event.created_at > previous.event.created_at ||
          (event.created_at === previous.event.created_at && event.id > previous.event.id)) {
        latest.set(id, { event, starred: actions[0] === 'star' });
      }
    }
    const remote = [...latest].filter(([, value]) => value.starred).sort(([, a], [, b]) =>
      b.event.created_at - a.event.created_at || b.event.id.localeCompare(a.event.id)).map(([id]) => id);
    return [...new Set([...remote, ...local.filter((id) => known.has(id) && !latest.has(id))])].slice(0, 500);
  }

  export async function signVerified(signer, template, tools) {
    const expected = JSON.stringify([template.kind, template.created_at, template.tags, template.content]);
    const event = await signer.signEvent(structuredClone(template));
    if (event?.pubkey !== signer.pubkey ||
        JSON.stringify([event.kind, event.created_at, event.tags, event.content]) !== expected ||
        !tools.verifyEvent(event)) throw new Error('The signer returned an invalid or changed event.');
    return event;
  }

  export function exchange(relay, signer, tools, filter, publish = null) {
    return new Promise((resolve, reject) => {
      const subscription = crypto.randomUUID();
      const request = publish ? ['EVENT', publish] : ['REQ', subscription, filter];
      const socket = new WebSocket(relay);
      const events = [];
      let done = false;
      let challengeSeen = false;
      let authId = '';
      const timer = setTimeout(() => finish('Nostr relay timed out.'), 45_000);
      function finish(error?) {
        if (done) return;
        done = true;
        clearTimeout(timer);
        if (!publish && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(['CLOSE', subscription]));
        socket.close();
        if (error) reject(new Error(error));
        else resolve(events);
      }
      socket.onopen = () => socket.send(JSON.stringify(request));
      socket.onerror = socket.onclose = () => finish('Nostr relay connection failed.');
      socket.onmessage = async ({ data }) => {
        if (done || typeof data !== 'string' || data.length > 128 * 1024) return;
        let message;
        try { message = JSON.parse(data); } catch (_) { return; }
        if (!Array.isArray(message)) return;
        const [type, id, value, detail] = message;
        if (type === 'AUTH' && !challengeSeen && typeof id === 'string' && id.length <= 4096) {
          challengeSeen = true;
          try {
            const auth = await signVerified(signer, {
              kind: 22242, created_at: Math.floor(Date.now() / 1000), content: '',
              tags: [['relay', relay], ['challenge', id]]
            }, tools);
            if (done) return;
            authId = auth.id;
            socket.send(JSON.stringify(['AUTH', auth]));
          } catch (_) { finish('Nostr relay authentication was not approved.'); }
        } else if (type === 'OK' && authId && id === authId) {
          if (value === true) socket.send(JSON.stringify(request));
          else finish('Nostr relay authentication failed.');
        } else if (publish && type === 'OK' && id === publish.id) {
          if (value === true) finish();
          else if (!String(detail).startsWith('auth-required:')) finish('Nostr relay rejected the update.');
        } else if (!publish && id === subscription) {
          if (type === 'EVENT' && events.length < 500 && value?.pubkey === signer.pubkey &&
              Array.isArray(value.tags) && value.tags.every(Array.isArray) && tools.verifyEvent(value)) events.push(value);
          if (type === 'EOSE') finish();
          if (type === 'CLOSED' && !String(value).startsWith('auth-required:')) finish('Nostr relay could not read the subscription.');
        }
      };
    });
  }
}
