namespace RadioAndroidSigner {
  const maxResponseLength = 128 * 1024;
  const timeoutMs = 120_000;
  const publicKeyPattern = /^[0-9a-f]{64}$/i;
  const signaturePattern = /^[0-9a-f]{128}$/i;
  const requestPattern = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;

  type Method = 'get_public_key' | 'sign_event' | 'nip44_encrypt' | 'nip44_decrypt' | 'nip04_encrypt' | 'nip04_decrypt';
  type Prompt = {
    url: string;
    clipboardUrl: string;
    method: Method;
    cancel: () => void;
    acceptResult: (value: string) => void;
  };
  type Presenter = (prompt: Prompt) => (() => void) | void;

  function normalizePublicKey(value: string, tools): string {
    const supplied = String(value ?? '').trim();
    if (publicKeyPattern.test(supplied)) return supplied.toLowerCase();
    if (supplied.startsWith('npub1')) {
      const decoded = tools.nip19.decode(supplied);
      if (decoded.type === 'npub' && publicKeyPattern.test(decoded.data)) return decoded.data.toLowerCase();
    }
    throw new Error('Android signer returned an invalid public key.');
  }

  function callbackChannel(requestId: string): string {
    return 'radio-guaka-nip55-' + requestId;
  }

  function requestUrl(method: Method, payload: string, peer: string, pubkey: string, requestId?: string): string {
    const query = new URLSearchParams({
      type: method,
      returnType: 'signature',
      appName: 'Radio Guaka',
      compressionType: 'none'
    });
    if (requestId) {
      const callback = new URL('/nip55-return.html', location.origin);
      // Amber decodes the entire intent before splitting its parameters on "&".
      // A single fragment keeps the correlation ID and result together and out of HTTP logs.
      callback.hash = requestId + '.';
      query.set('callbackUrl', callback.href);
    }
    if (peer) query.set('pubkey', peer);
    if (pubkey) query.set('current_user', pubkey);
    return 'nostrsigner:' + encodeURIComponent(payload) + '?' + query.toString();
  }

  function signedEvent(result: string, template, tools) {
    const id = tools.getEventHash(template);
    let event;
    try {
      event = signaturePattern.test(result)
        ? { ...template, id, sig: result.toLowerCase() }
        : JSON.parse(result);
    } catch (_) {
      throw new Error('Android signer returned an invalid event.');
    }
    if (!event || event.pubkey !== template.pubkey || event.kind !== template.kind ||
      event.created_at !== template.created_at || event.content !== template.content ||
      JSON.stringify(event.tags) !== JSON.stringify(template.tags) || event.id !== id || !tools.verifyEvent(event)) {
      throw new Error('Android signer returned an invalid or modified event.');
    }
    return event;
  }

  export async function connect(tools, presentRequest: Presenter, savedPubkey?: string) {
    let pubkey = '';
    let closed = false;
    let cancelActive: undefined | (() => void);
    let queue = Promise.resolve();

    function request(method: Method, payload = '', peer = ''): Promise<string> {
      const result = queue.then(() => {
        if (closed) throw new Error('Android signer disconnected.');
        return new Promise<string>((resolve, reject) => {
          const requestId = crypto.randomUUID();
          const url = requestUrl(method, payload, peer, pubkey, requestId);
          const clipboardUrl = requestUrl(method, payload, peer, pubkey);
          const channel = new BroadcastChannel(callbackChannel(requestId));
          let removePrompt;
          let settled = false;
          const timer = setTimeout(() => finish(new Error('Android signer did not respond.')), timeoutMs);

          function finish(error: Error | null, value = '') {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            channel.close();
            cancelActive = undefined;
            if (error) reject(error);
            else resolve(value);
            removePrompt?.();
          }

          function acceptResult(value: string) {
            if (typeof value !== 'string' || value.length > maxResponseLength) return;
            if (method !== 'nip04_decrypt' && method !== 'nip44_decrypt') value = value.trim();
            if (!value) finish(new Error('Android signer did not return a result.'));
            else finish(null, value);
          }

          cancelActive = () => finish(new Error('Android signer request cancelled.'));
          channel.onmessage = ({ data }) => {
            if (data?.request !== requestId) return;
            acceptResult(data.result);
          };
          try {
            removePrompt = presentRequest({ url, clipboardUrl, method, cancel: cancelActive, acceptResult });
            if (settled) removePrompt?.();
          } catch (error) {
            finish(error);
          }
        });
      });
      queue = result.then(() => {}, () => {});
      return result;
    }

    pubkey = normalizePublicKey(savedPubkey || await request('get_public_key'), tools);
    const encryption = (method: Method, peer: string, text: string) =>
      request(method, text, normalizePublicKey(peer, tools));
    return {
      pubkey,
      signEvent: async (event) => {
        const template = {
          pubkey,
          kind: event.kind,
          created_at: event.created_at,
          tags: structuredClone(event.tags),
          content: event.content
        };
        // Some Android signers decode the request before splitting its query.
        // Escaping question marks inside JSON preserves the event's exact content.
        const payload = JSON.stringify(template).replace(/\?/g, '\\u003f');
        return signedEvent(await request('sign_event', payload), template, tools);
      },
      nip44Encrypt: (peer: string, text: string) => encryption('nip44_encrypt', peer, text),
      nip44Decrypt: (peer: string, text: string) => encryption('nip44_decrypt', peer, text),
      nip04Encrypt: (peer: string, text: string) => encryption('nip04_encrypt', peer, text),
      nip04Decrypt: (peer: string, text: string) => encryption('nip04_decrypt', peer, text),
      encryptHistory: (text: string) => encryption('nip44_encrypt', pubkey, text),
      decryptHistory: (text: string) => encryption('nip44_decrypt', pubkey, text),
      close() {
        closed = true;
        cancelActive?.();
      }
    };
  }

  export function deliverCallback() {
    const fragment = location.hash.slice(1);
    history.replaceState(null, '', location.pathname);
    const separator = fragment.indexOf('.');
    if (separator < 0) return false;
    const request = fragment.slice(0, separator);
    if (!requestPattern.test(request)) return false;
    let result;
    try {
      result = decodeURIComponent(fragment.slice(separator + 1));
    } catch (_) {
      return false;
    }
    if (!result || result.length > maxResponseLength) return false;
    const channel = new BroadcastChannel(callbackChannel(request));
    channel.postMessage({ request, result });
    channel.close();
    return true;
  }
}
