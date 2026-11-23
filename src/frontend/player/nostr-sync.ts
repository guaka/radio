namespace RadioNostrSync {
  export const favoriteRelays = ['wss://relay.trustroots.org', 'wss://relay.nomadwiki.org'];
  export const favoriteLifetime = 30 * 24 * 60 * 60;
  const stationIdPattern = /^[A-Za-z0-9_-]{1,80}$/;

  export function favoriteTemplate(id, title, starred, createdAt) {
    if (!stationIdPattern.test(id)) throw new Error('Invalid station ID.');
    const name = title.trim().slice(0, 160);
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

  export async function connectExtension(provider) {
    if (!provider?.getPublicKey || !provider?.signEvent) throw new Error('No NIP-07 browser signer found.');
    const pubkey = await provider.getPublicKey();
    if (!/^[a-f0-9]{64}$/.test(pubkey)) throw new Error('The browser signer returned an invalid account.');
    const nip44Encrypt = provider.nip44?.encrypt?.bind(provider.nip44);
    const nip44Decrypt = provider.nip44?.decrypt?.bind(provider.nip44);
    return {
      pubkey, signEvent: provider.signEvent.bind(provider),
      nip44Encrypt, nip44Decrypt,
      nip04Encrypt: provider.nip04?.encrypt?.bind(provider.nip04),
      nip04Decrypt: provider.nip04?.decrypt?.bind(provider.nip04),
      encryptHistory: nip44Encrypt && ((text) => nip44Encrypt(pubkey, text)),
      decryptHistory: nip44Decrypt && ((text) => nip44Decrypt(pubkey, text))
    };
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

namespace RadioRemoteSigner {
  const kind = 24133;
  const permissions = 'sign_event:1,sign_event:22242,sign_event:30078,nip44_encrypt,nip44_decrypt';

  async function withKeyStore(mode, access) {
    const database = await new Promise((resolve, reject) => {
      const request = indexedDB.open('guakaRemoteSigner', 1);
      request.onupgradeneeded = () => request.result.createObjectStore('connections');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    try {
      return await new Promise((resolve, reject) => {
        const transaction = database.transaction('connections', mode);
        const request = access(transaction.objectStore('connections'));
        transaction.oncomplete = () => resolve(request.result);
        transaction.onabort = transaction.onerror = () => reject(transaction.error || request.error);
      });
    } finally {
      database.close();
    }
  }

  export async function saveTransportKey(transportKey) {
    const reference = crypto.randomUUID();
    const wrappingKey = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, wrappingKey, transportKey);
    await withKeyStore('readwrite', (store) => store.add({ wrappingKey, iv, ciphertext }, reference));
    return reference;
  }

  export async function loadTransportKey(reference) {
    if (typeof reference !== 'string') throw new Error('Reconnect your remote signer.');
    const record = await withKeyStore('readonly', (store) => store.get(reference));
    if (!record) throw new Error('Saved remote signer key is unavailable. Disconnect and reconnect.');
    const { wrappingKey, iv, ciphertext } = record;
    return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, wrappingKey, ciphertext));
  }

  export async function removeTransportKey(reference) {
    if (typeof reference === 'string') await withKeyStore('readwrite', (store) => store.delete(reference));
  }

  export function parseBunker(input) {
    const url = new URL(input.trim());
    const remote = url.hostname.toLowerCase();
    const relays = [...new Set(url.searchParams.getAll('relay').map(RadioHistory.secureRelay))];
    if (url.protocol !== 'bunker:' || !/^[a-f0-9]{64}$/.test(remote) || !relays.length || relays.length > 8 ||
        url.username || url.password || url.port || (url.pathname && url.pathname !== '/') || url.hash) {
      throw new Error('Enter a valid bunker:// URL with a secure relay.');
    }
    return { remote, relays, secret: url.searchParams.get('secret') || '' };
  }

  export async function connect(tools, config, onAuth, isRestore) {
    const clientKey = Uint8Array.from(config.key);
    const client = tools.getPublicKey(clientKey);
    const pool = new tools.SimplePool();
    const conversationKey = tools.nip44.v2.utils.getConversationKey(clientKey, config.remote);
    let closed = false;
    const pending = new Map();
    const seenAuth = new Set();
    const subscription = pool.subscribeMany(config.relays, [{ kinds: [kind], authors: [config.remote], '#p': [client] }], {
      onevent(event) {
        if (closed || event.kind !== kind || event.pubkey !== config.remote || event.content.length > 90 * 1024 ||
            !event.tags.some((tag) => tag[0] === 'p' && tag[1] === client) || !tools.verifyEvent(event)) return;
        let response;
        try { response = JSON.parse(tools.nip44.v2.decrypt(event.content, conversationKey)); }
        catch (_) { return; }
        const request = pending.get(response?.id);
        if (!request) return;
        if (response.result === 'auth_url') {
          if (!seenAuth.has(event.id) && typeof response.error === 'string' && response.error.length <= 8192) {
            seenAuth.add(event.id);
            onAuth(response.error);
          }
        } else if (response.error || typeof response.result !== 'string') {
          request.reject(new Error('Remote signer denied the request.'));
        } else {
          request.resolve(response.result);
        }
      }
    });
    function close() {
      if (closed) return;
      closed = true;
      subscription.close();
      pool.close(config.relays);
      for (const request of pending.values()) request.reject(new Error('Remote signer disconnected.'));
    }
    async function request(method, params) {
      if (closed) throw new Error('Remote signer disconnected.');
      const id = crypto.randomUUID();
      let timer;
      const response = new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        timer = setTimeout(() => reject(new Error('Remote signer did not respond.')), 80_000);
      });
      try {
        const content = tools.nip44.v2.encrypt(JSON.stringify({ id, method, params }), conversationKey);
        const requestEvent = tools.finalizeEvent({
          kind, created_at: Math.floor(Date.now() / 1000), tags: [['p', config.remote]], content
        }, clientKey);
          const sending = Promise.any(pool.publish(config.relays, requestEvent));
        // An authenticated response is sufficient even if a relay failed to return an OK.
        void sending.catch(() => {});
        return await response;
      } finally {
        clearTimeout(timer);
        pending.delete(id);
      }
    }
    try {
      if (!isRestore) {
        const ack = await request('connect', [config.remote, config.secret || '', permissions]);
        if (ack !== 'ack' && (!config.secret || ack !== config.secret)) throw new Error('Remote signer did not confirm the connection.');
      }
      const pubkey = await request('get_public_key', []);
      if (!/^[a-f0-9]{64}$/.test(pubkey) || (isRestore && pubkey !== config.pubkey)) {
        throw new Error('Remote signer account does not match.');
      }
      return {
        pubkey, close,
        signEvent: async (event) => JSON.parse(await request('sign_event', [JSON.stringify(event)])),
        nip44Encrypt: (peer, plaintext) => request('nip44_encrypt', [peer, plaintext]),
        nip44Decrypt: (peer, ciphertext) => request('nip44_decrypt', [peer, ciphertext]),
        nip04Encrypt: (peer, plaintext) => request('nip04_encrypt', [peer, plaintext]),
        nip04Decrypt: (peer, ciphertext) => request('nip04_decrypt', [peer, ciphertext]),
        encryptHistory: (plaintext) => request('nip44_encrypt', [pubkey, plaintext]),
        decryptHistory: (ciphertext) => request('nip44_decrypt', [pubkey, ciphertext])
      };
    } catch (error) {
      close();
      throw error;
    }
  }
}
