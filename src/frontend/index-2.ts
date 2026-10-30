(function () {
  const LS = {
    server: 'plexServer',
    token: 'plexToken',
    serverCandidatesCache: 'plexServerCandidatesCache',
    section: 'plexSection',
    playback: 'plexPlaybackState',
    playbackMode: 'plexPlaybackMode',
    viewMode: 'plexAlbumViewMode',
    starredTracks: 'plexStarredTracks',
    starredAlbums: 'plexStarredAlbums',
    starredTrackItems: 'plexStarredTrackItems',
    starredAlbumItems: 'plexStarredAlbumItems',
    recentTracks: 'plexRecentTracks',
    searchIndexMeta: 'plexSearchIndexMeta',
    starredStations: 'guakaStarredStations',
    nostrNsec: 'guakaNostrNsec',
    nostrNpub: 'guakaNostrNpub',
    nostrActivity: 'guakaNostrActivityCache'
  };
  const $ = (id) => document.getElementById(id);
  function isMacPlatform() {
    const uaDataPlatform = navigator.userAgentData && navigator.userAgentData.platform;
    const platform = uaDataPlatform || navigator.platform || navigator.userAgent || '';
    return /mac/i.test(platform);
  }
  function updateShortcutModifierLabels() {
    const mod = isMacPlatform() ? '⌘' : 'Ctrl';
    const settingsEl = $('kbdModSettings');
    const searchEl = $('kbdModSearch');
    const vizEl = $('kbdModViz');
    if (settingsEl) settingsEl.textContent = mod;
    if (searchEl) searchEl.textContent = mod;
    if (vizEl) vizEl.textContent = mod;
  }
  function bindClick(id, handler) {
    const el = $(id);
    if (el) el.onclick = handler;
    return el;
  }
  function bindEvent(id, type, handler, options) {
    const el = $(id);
    if (el) el.addEventListener(type, handler, options);
    return el;
  }
  const PLEX_PRODUCT = 'Local Plex Music Player';
  const PLEX_CLIENT_ID = 'plex-player-' + Math.random().toString(36).slice(2, 10);
  const PLEX_SERVER_CACHE_TTL_MS = 1000 * 60 * 30;
  const CROSSFADE_DURATION_MS = 1200;
  const CROSSFADE_STEP_MS = 50;
  /** Plex part streams: avoid false “ORB blocked” toast while the browser is still loading / retrying. */
  const PLEX_STREAM_ORB_ERROR_MIN_MS = 3000;
  const SEARCH_INDEX_SCHEMA_VERSION = 1;
  const SEARCH_DB_NAME = 'plexSearchCache';
  const SEARCH_DB_VERSION = 2;
  const SEARCH_STORE = 'albumSearch';
  const ALBUM_LIST_STORE = 'albumListPages';
  const PLAYBACK_MODE_OFF = 'off';
  const PLAYBACK_MODE_ALBUM_SHUFFLE = 'albumShuffle';
  const PLAYBACK_MODE_SONG_SHUFFLE = 'songShuffle';
  /** Same fixed pair as Radio Guaka chat — do not add other relays */
  const NOSTR_RELAYS = Object.freeze([
    'wss://relay.nomadwiki.org',
    'wss://relay.trustroots.org'
  ]);
  const NOSTR_TAG = 'plextr';
  const NOSTR_MAX_ACTIVITY = 40;
  /** Machine-readable favorite tags (see PLEXTR-FAVORITES-NOSTR.md) */
  const PLEXTR_FAVORITE_SPEC_VER = '1';
  const PLEXTR_AUDIOBOOK_PROGRESS_SPEC_VER = '1';
  const NOSTR_FAVORITES_LIMIT = 2000;
  const NOSTR_RECENT_LIMIT = 400;
  const NOSTR_AUDIOBOOK_PROGRESS_LIMIT = 120;
  const NOSTR_AUDIOBOOK_PROGRESS_SYNC_MS = 30000;
  const NOSTR_AUDIOBOOK_PROGRESS_MIN_DELTA_SECONDS = 3;
  const WIKIPEDIA_API_URL = 'https://en.wikipedia.org/w/api.php';
  const WIKIPEDIA_REST_SEARCH_URL = 'https://en.wikipedia.org/w/rest.php/v1/search/page';
  const wikiLookupCache = new Map();
  const wikiLookupInflight = new Map();
  let nowPlayingWikiRequestId = 0;
  let nostrToolsPromise = null;
  let nostrState = {
    ready: false,
    nsec: '',
    npub: '',
    pubkey: '',
    secretKey: null,
    pool: null,
    lastPostKey: '',
    lastPostAt: 0
  };

  function buildNostrErrorBlob(value, depth) {
    if (depth > 2 || value == null) return '';
    if (typeof value === 'string') return value;
    const out = [];
    if (typeof value.message === 'string') out.push(value.message);
    if (typeof value.reason === 'string') out.push(value.reason);
    if (typeof value.name === 'string') out.push(value.name);
    if (typeof value.stack === 'string') out.push(value.stack);
    if (typeof value.filename === 'string') out.push(value.filename);
    if (typeof value.toString === 'function') {
      try {
        const asString = String(value);
        if (asString && asString !== '[object Object]') out.push(asString);
      } catch (_) {}
    }
    if (value.error && value.error !== value) out.push(buildNostrErrorBlob(value.error, depth + 1));
    if (Array.isArray(value.errors)) {
      value.errors.forEach(function (item) {
        out.push(buildNostrErrorBlob(item, depth + 1));
      });
    }
    if (value.cause && value.cause !== value) out.push(buildNostrErrorBlob(value.cause, depth + 1));
    return out.filter(Boolean).join('\n');
  }

  function isBenignNostrRelayError(value) {
    const blob = buildNostrErrorBlob(value, 0);
    return /nostr-tools(\.bundle|@)|esm\.sh\/nostr-tools|nr-relay-to-rabbit|nr-relay|relay-to-rabbit|dial tcp:|lookup [^ ]+ on [\d.]+:53|server misbehaving|http request failed:|uncaught \(in promise\) error:/i.test(blob);
  }

  function shouldSuppressNostrConsoleNoise(args) {
    if (!Array.isArray(args) || !args.length) return false;
    for (let i = 0; i < args.length; i++) {
      if (isBenignNostrRelayError(args[i])) return true;
    }
    return false;
  }

  function installNostrConsoleNoiseFilter() {
    if (window.__plextrNostrConsoleFilterInstalled) return;
    window.__plextrNostrConsoleFilterInstalled = true;
    const originalError = console.error;
    const originalWarn = console.warn;
    console.error = function () {
      const args = Array.prototype.slice.call(arguments);
      if (shouldSuppressNostrConsoleNoise(args)) return;
      return originalError.apply(console, args);
    };
    console.warn = function () {
      const args = Array.prototype.slice.call(arguments);
      if (shouldSuppressNostrConsoleNoise(args)) return;
      return originalWarn.apply(console, args);
    };
  }

  /** nostr-tools runs relay I/O in internal queues; rejections often bypass try/catch. */
  (function installNostrUnhandledRejectionGuard() {
    installNostrConsoleNoiseFilter();
    if (window.__plextrNostrRejectionGuard) return;
    window.__plextrNostrRejectionGuard = true;
    window.addEventListener('unhandledrejection', function (ev) {
      if (isBenignNostrRelayError(ev.reason)) {
        ev.preventDefault();
        if (typeof ev.stopPropagation === 'function') ev.stopPropagation();
        if (typeof ev.stopImmediatePropagation === 'function') ev.stopImmediatePropagation();
        // Intentionally silent: avoid resurfacing noisy relay internals in console.
      }
    });
    window.addEventListener('error', function (ev) {
      const payload = [ev && ev.message, ev && ev.filename, ev && ev.error].filter(Boolean).join('\n');
      if (isBenignNostrRelayError(payload) || isBenignNostrRelayError(ev && ev.error)) {
        ev.preventDefault();
        if (typeof ev.stopImmediatePropagation === 'function') ev.stopImmediatePropagation();
        // Intentionally silent: avoid resurfacing noisy relay internals in console.
      }
    }, true);
  })();

  async function safeNostrQuery(pool, relayUrls, filter) {
    try {
      if (typeof pool.list === 'function') {
        return (await pool.list(relayUrls, [filter])) || [];
      }
      if (typeof pool.querySync === 'function') {
        return (await pool.querySync(relayUrls, filter)) || [];
      }
    } catch (err) {
      if (!isBenignNostrRelayError(err)) {
        console.warn('[pleXtr] Nostr query:', err && err.message ? err.message : err);
      }
    }
    return [];
  }

  function safeNostrPoolPublish(pool, relayUrls, signedEvent) {
    if (!pool || typeof pool.publish !== 'function') return;
    try {
      const out = pool.publish(relayUrls, signedEvent);
      if (out && typeof out.then === 'function') {
        out.catch(function (err) {
          if (!isBenignNostrRelayError(err)) {
            console.warn('[pleXtr] Nostr publish:', err && err.message ? err.message : err);
          }
        });
      }
    } catch (err) {
      if (!isBenignNostrRelayError(err)) {
        console.warn('[pleXtr] Nostr publish:', err && err.message ? err.message : err);
      }
    }
  }
  let isChatVisibleMobile = false;

  function cfg() {
    return {
      server: (localStorage.getItem(LS.server) || '').replace(/\/$/, ''),
      token: localStorage.getItem(LS.token) || '',
      sectionKey: localStorage.getItem(LS.section) || ''
    };
  }

  function getNostrStatusEl() {
    return $('nostrStatus');
  }

  function setNostrStatus(text, kind) {
    const el = getNostrStatusEl();
    if (!el) return;
    el.textContent = text || '';
    el.style.color = kind === 'error' ? '#e8a0a0' : (kind === 'ok' ? '#7ddda5' : '');
  }

  async function getNostrTools() {
    if (!nostrToolsPromise) {
      // Avoid esm.sh transport shim that occasionally emits nr-relay-to-rabbit DNS errors.
      nostrToolsPromise = import('https://cdn.jsdelivr.net/npm/nostr-tools@2.7.2/+esm');
    }
    return nostrToolsPromise;
  }

  function normalizeSecretKey(data, tools) {
    if (data instanceof Uint8Array) return data;
    if (typeof data === 'string' && tools.hexToBytes) return tools.hexToBytes(data);
    throw new Error('Invalid nsec payload');
  }

  function buildNostrIdentityText() {
    if (!nostrState.pubkey) return 'Not connected.';
    return 'Connected as ' + (nostrState.npub || nostrState.pubkey.slice(0, 16) + '...');
  }

  function updateNostrSaveButtonVisibility() {
    const btn = $('btnSaveNostr');
    const genBtn = $('btnGenerateNostr');
    const clearBtn = $('btnClearNostr');
    const input = $('inNsec');
    if (!btn || !input) return;
    const typed = String(input.value || '').trim();
    const saved = String(localStorage.getItem(LS.nostrNsec) || '').trim();
    const alreadySavedAndConnected =
      !!typed &&
      typed === saved &&
      !!nostrState.ready &&
      !!nostrState.pubkey &&
      !!nostrState.npub;
    const hasAnyNsecSet = !!typed || !!saved;
    const hasSavedNsec = !!saved;
    btn.style.display = alreadySavedAndConnected ? 'none' : '';
    if (genBtn) genBtn.style.display = hasAnyNsecSet ? 'none' : '';
    if (clearBtn) clearBtn.style.display = hasSavedNsec ? '' : 'none';
  }

  function updateNostrIdentityUi() {
    const identityEl = $('nostrIdentity');
    if (identityEl) identityEl.textContent = buildNostrIdentityText();
    updateNostrSaveButtonVisibility();
  }

  function postToChat(message) {
    try {
      const iframe = $('chatIframe');
      if (iframe && iframe.contentWindow) iframe.contentWindow.postMessage(message, '*');
    } catch (_) {}
  }

  function syncNostrNsecToChatIframe() {
    const n = localStorage.getItem(LS.nostrNsec);
    if (n && String(n).trim().startsWith('nsec')) {
      postToChat({ type: 'setChatNsec', nsec: String(n).trim() });
    } else {
      postToChat({ type: 'clearChatNsec' });
    }
  }

  function sendChatContext() {
    const currentStation = isRadioMode && currentRadioIdx >= 0 && radioStations[currentRadioIdx]
      ? radioStations[currentRadioIdx]
      : null;
    const channelName = currentStation ? currentStation.name : '';
    postToChat({
      type: 'channelChange',
      channel: String(channelName || '')
    });
  }

  function setChatVisibility(visible) {
    const chatContainer = $('chatContainer');
    const chatBtn = $('btnChatToggle');
    if (!chatContainer || !chatBtn) return;
    if (window.innerWidth <= 720) {
      isChatVisibleMobile = !!visible;
      chatContainer.classList.toggle('visible', isChatVisibleMobile);
      chatBtn.textContent = isChatVisibleMobile ? '✕' : '💬';
      chatBtn.title = isChatVisibleMobile ? 'Close chat' : 'Open chat';
      chatBtn.style.display = '';
    } else {
      chatContainer.classList.add('visible');
      chatBtn.style.display = 'none';
    }
  }

  function cacheNostrActivity(items) {
    try {
      localStorage.setItem(LS.nostrActivity, JSON.stringify((items || []).slice(0, NOSTR_MAX_ACTIVITY)));
    } catch (_) {}
  }

  async function loadNostrHistory() {
    if (!nostrState.ready || !nostrState.pool || !nostrState.pubkey) return;
    try {
      const since = Math.floor(Date.now() / 1000) - 60 * 60 * 24 * 30;
      const pool = nostrState.pool;
      const relayUrls = [...NOSTR_RELAYS];
      const tagVariants = [NOSTR_TAG, 'radioguaka'];
      const byId = new Map();
      for (let i = 0; i < tagVariants.length; i++) {
        const filter = {
          kinds: [1],
          authors: [nostrState.pubkey],
          '#t': [tagVariants[i]],
          since: since,
          limit: NOSTR_MAX_ACTIVITY
        };
        const batch = await safeNostrQuery(pool, relayUrls, filter);
        (batch || []).forEach((ev) => {
          if (ev && ev.id) byId.set(ev.id, ev);
        });
      }
      const sorted = Array.from(byId.values())
        .sort((a, b) => Number(b.created_at || 0) - Number(a.created_at || 0))
        .slice(0, NOSTR_MAX_ACTIVITY);
      cacheNostrActivity(sorted);
      setNostrStatus('', '');
    } catch (err) {
      setNostrStatus('Could not load Nostr history: ' + (err && err.message ? err.message : String(err)), 'error');
    }
  }

  async function initNostrFromNsec(nsec) {
    const key = String(nsec || '').trim();
    if (!key) {
      nostrState = Object.assign({}, nostrState, {
        ready: false,
        nsec: '',
        npub: '',
        pubkey: '',
        secretKey: null,
        pool: null
      });
      updateNostrIdentityUi();
      setNostrStatus('Nostr disabled.', '');
      syncNostrNsecToChatIframe();
      return false;
    }
    try {
      const tools = await getNostrTools();
      const decoded = tools.nip19.decode(key);
      if (!decoded || decoded.type !== 'nsec' || !decoded.data) throw new Error('Invalid nsec');
      const secretKey = normalizeSecretKey(decoded.data, tools);
      const pubkey = tools.getPublicKey(secretKey);
      const npub = tools.nip19.npubEncode(pubkey);
      const pool = nostrState.pool || new tools.SimplePool();
      nostrState = {
        ready: true,
        nsec: key,
        npub: npub,
        pubkey: pubkey,
        secretKey: secretKey,
        pool: pool,
        lastPostKey: '',
        lastPostAt: 0
      };
      localStorage.setItem(LS.nostrNsec, key);
      localStorage.setItem(LS.nostrNpub, npub);
      updateNostrIdentityUi();
      setNostrStatus('Nostr connected: ' + npub.slice(0, 18) + '…', 'ok');
      await loadNostrHistory();
      await applyNostrFavoritesToLocalStorage();
      await applyNostrRecentToLocalStorage();
      syncNostrNsecToChatIframe();
      return true;
    } catch (err) {
      nostrState.ready = false;
      setNostrStatus('Could not initialize Nostr: ' + (err && err.message ? err.message : String(err)), 'error');
      return false;
    }
  }

  async function publishNostrNote(text, tag, extraTags) {
    if (!nostrState.ready || !nostrState.secretKey || !nostrState.pool) return;
    const content = String(text || '').trim();
    if (!content) return;
    const extra = Array.isArray(extraTags) ? extraTags : [];
    const dedupKey = tag + '::' + content + '::' + JSON.stringify(extra);
    const now = Date.now();
    if (nostrState.lastPostKey === dedupKey && now - nostrState.lastPostAt < 10000) return;
    nostrState.lastPostKey = dedupKey;
    nostrState.lastPostAt = now;
    try {
      const tools = await getNostrTools();
      // Include #radioguaka so Radio Guaka + legacy subscribers see the same notes
      const baseTags =
        tag === 'nowplaying'
          ? [['t', NOSTR_TAG], ['t', 'radioguaka'], ['t', 'nowplaying']]
          : [['t', NOSTR_TAG], ['t', 'radioguaka'], ['t', tag]];
      const tags = extra.length ? baseTags.concat(extra) : baseTags;
      const eventTemplate = {
        kind: 1,
        created_at: Math.floor(now / 1000),
        content: content,
        tags: tags
      };
      const signed = tools.finalizeEvent(eventTemplate, nostrState.secretKey);
      safeNostrPoolPublish(nostrState.pool, NOSTR_RELAYS, signed);
      setNostrStatus('Posted note to Nostr.', 'ok');
      await loadNostrHistory();
    } catch (err) {
      setNostrStatus('Nostr publish failed: ' + (err && err.message ? err.message : String(err)), 'error');
    }
  }

  function buildNowPlayingText(track, station) {
    if (station && station.name) return station.name + ' #plextr #radio';
    if (!track) return '';
    const artist = String(track.grandparentTitle || (currentAlbum && currentAlbum.parentTitle) || '').trim();
    let title = String(track.title || '').trim();
    if (!title) {
      const fp = getTrackFilePath(track);
      const h = filenameDisplayHints(fp);
      title = h.label || h.title || pathBasename(fp) || '';
    }
    if (!artist && !title) return '';
    const human = artist && title ? (artist + ' - ' + title) : (artist || title);
    return human + ' #plextr';
  }

  function buildFavoriteText(track, station, isStarred) {
    const prefix = isStarred ? '⭐ ' : '☆ ';
    if (station && station.name) return prefix + station.name + ' #plextr #favorite #radio';
    if (!track) return '';
    const artist = String(track.grandparentTitle || (currentAlbum && currentAlbum.parentTitle) || '').trim();
    let title = String(track.title || '').trim();
    if (!title) {
      const fp = getTrackFilePath(track);
      const h = filenameDisplayHints(fp);
      title = h.label || h.title || pathBasename(fp) || '';
    }
    const human = artist && title ? (artist + ' - ' + title) : (artist || title);
    if (!human) return '';
    return prefix + human + ' #plextr #favorite';
  }

  function buildFavoriteAlbumText(album, isStarred) {
    const prefix = isStarred ? '⭐ ' : '☆ ';
    if (!album || typeof album !== 'object') return '';
    const artist = String(album.parentTitle || '').trim();
    const title = String(album.title || '').trim();
    if (!artist && !title) return '';
    const human = artist && title ? artist + ' - ' + title : title || artist;
    return prefix + human + ' #plextr #favorite #album';
  }

  function nowPlayingTagTrack(track) {
    if (!track || track.ratingKey == null) return [];
    const artist = String(track.grandparentTitle || (currentAlbum && currentAlbum.parentTitle) || '').trim();
    const title = String(track.title || '').trim();
    const tags = [
      ['client', 'plextr'],
      ['plextr_ver', PLEXTR_FAVORITE_SPEC_VER],
      ['plextr_item', 'track'],
      ['plextr_rating_key', String(track.ratingKey)]
    ];
    if (artist) tags.push(['plextr_artist', artist]);
    if (title) tags.push(['plextr_title', title]);
    if (track.parentRatingKey != null) tags.push(['plextr_parent_rating_key', String(track.parentRatingKey)]);
    if (track.grandparentRatingKey != null) tags.push(['plextr_grandparent_rating_key', String(track.grandparentRatingKey)]);
    return tags;
  }

  function nowPlayingTagStation(station) {
    if (!station || station.id == null) return [];
    const tags = [
      ['client', 'plextr'],
      ['plextr_ver', PLEXTR_FAVORITE_SPEC_VER],
      ['plextr_item', 'station'],
      ['plextr_station_id', String(station.id)]
    ];
    const name = String(station.name || '').trim();
    if (name) tags.push(['plextr_title', name]);
    return tags;
  }

  function favoriteTagTrack(track, isStarred) {
    if (!track || track.ratingKey == null) return [];
    const artist = String(track.grandparentTitle || (currentAlbum && currentAlbum.parentTitle) || '').trim();
    const title = String(track.title || '').trim();
    const tags = [
      ['client', 'plextr'],
      ['plextr_ver', PLEXTR_FAVORITE_SPEC_VER],
      ['plextr_item', 'track'],
      ['plextr_rating_key', String(track.ratingKey)],
      ['plextr_action', isStarred ? 'star' : 'unstar']
    ];
    if (artist) tags.push(['plextr_artist', artist]);
    if (title) tags.push(['plextr_title', title]);
    if (track.parentRatingKey != null) tags.push(['plextr_parent_rating_key', String(track.parentRatingKey)]);
    if (track.grandparentRatingKey != null) tags.push(['plextr_grandparent_rating_key', String(track.grandparentRatingKey)]);
    return tags;
  }

  function favoriteTagAlbum(album, isStarred) {
    if (!album || album.ratingKey == null) return [];
    const artist = String(album.parentTitle || '').trim();
    const title = String(album.title || '').trim();
    const tags = [
      ['client', 'plextr'],
      ['plextr_ver', PLEXTR_FAVORITE_SPEC_VER],
      ['plextr_item', 'album'],
      ['plextr_rating_key', String(album.ratingKey)],
      ['plextr_action', isStarred ? 'star' : 'unstar']
    ];
    if (artist) tags.push(['plextr_artist', artist]);
    if (title) tags.push(['plextr_title', title]);
    return tags;
  }

  function favoriteTagStation(station, isStarred) {
    if (!station || station.id == null) return [];
    const tags = [
      ['client', 'plextr'],
      ['plextr_ver', PLEXTR_FAVORITE_SPEC_VER],
      ['plextr_item', 'station'],
      ['plextr_station_id', String(station.id)],
      ['plextr_action', isStarred ? 'star' : 'unstar']
    ];
    const name = String(station.name || '').trim();
    if (name) tags.push(['plextr_title', name]);
    return tags;
  }

  function notifyFavoriteAlbum(album, isStarred) {
    const text = buildFavoriteAlbumText(album, isStarred);
    if (!text) return;
    publishNostrNote(text, 'favorite', favoriteTagAlbum(album, isStarred));
  }

  function notifyNowPlaying(track, station) {
    const text = buildNowPlayingText(track, station);
    if (!text) return;
    const extra = station && station.id != null ? nowPlayingTagStation(station) : nowPlayingTagTrack(track);
    publishNostrNote(text, 'nowplaying', extra);
  }

  /** Wait ~10s into playback before posting now-playing (skip rapid skip-through). */
  const NP_NOTE_MIN_SECONDS = 10;
  let npNoteSchedule = { radioTimerId: 0, timeupdateHandler: null };

  function clearNowPlayingNoteSchedule() {
    if (npNoteSchedule.radioTimerId) {
      clearTimeout(npNoteSchedule.radioTimerId);
      npNoteSchedule.radioTimerId = 0;
    }
    const audio = $('audio');
    if (npNoteSchedule.timeupdateHandler && audio) {
      audio.removeEventListener('timeupdate', npNoteSchedule.timeupdateHandler);
      npNoteSchedule.timeupdateHandler = null;
    }
  }

  function scheduleNotifyNowPlaying(track, station) {
    clearNowPlayingNoteSchedule();
    const audio = $('audio');
    if (!audio) return;

    if (station && station.id != null) {
      const sid = String(station.id);
      const idxAtStart = currentRadioIdx;
      npNoteSchedule.radioTimerId = setTimeout(() => {
        npNoteSchedule.radioTimerId = 0;
        if (!isRadioMode || currentRadioIdx !== idxAtStart) return;
        const st = radioStations[currentRadioIdx];
        if (!st || String(st.id) !== sid) return;
        notifyNowPlaying(null, st);
      }, NP_NOTE_MIN_SECONDS * 1000);
      return;
    }

    if (track && track.ratingKey) {
      const rk = String(track.ratingKey);
      const idxAtStart = playingIdx;
      const handler = () => {
        if (audio.paused || audio.currentTime < NP_NOTE_MIN_SECONDS) return;
        if (isRadioMode || playingIdx !== idxAtStart) return;
        const cur = currentTracks[playingIdx];
        if (!cur || String(cur.ratingKey) !== rk) return;
        audio.removeEventListener('timeupdate', handler);
        npNoteSchedule.timeupdateHandler = null;
        notifyNowPlaying(cur, null);
      };
      npNoteSchedule.timeupdateHandler = handler;
      audio.addEventListener('timeupdate', handler);
    }
  }

  function notifyFavorite(track, station, isStarred) {
    const text = buildFavoriteText(track, station, isStarred);
    if (!text) return;
    const extra =
      station && station.name ? favoriteTagStation(station, isStarred) : favoriteTagTrack(track, isStarred);
    publishNostrNote(text, 'favorite', extra);
  }

  function loadPlaybackState() {
    try {
      const raw = localStorage.getItem(LS.playback);
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === 'object' ? parsed : null;
    } catch (_) {
      return null;
    }
  }

  function savePlaybackState(state) {
    if (!state || typeof state !== 'object') return;
    try {
      localStorage.setItem(LS.playback, JSON.stringify(state));
    } catch (_) {}
  }

  function getStarredTracks() {
    try {
      const raw = localStorage.getItem(LS.starredTracks);
      if (!raw) return new Set();
      const arr = JSON.parse(raw);
      return new Set(Array.isArray(arr) ? arr.map(String) : []);
    } catch (_) {
      return new Set();
    }
  }

  function getStarredAlbums() {
    try {
      const raw = localStorage.getItem(LS.starredAlbums);
      if (!raw) return new Set();
      const arr = JSON.parse(raw);
      return new Set(Array.isArray(arr) ? arr.map(String) : []);
    } catch (_) {
      return new Set();
    }
  }

  function getStarredStations() {
    try {
      const raw = localStorage.getItem(LS.starredStations);
      if (!raw) return new Set();
      const arr = JSON.parse(raw);
      return new Set(Array.isArray(arr) ? arr.map(String) : []);
    } catch (_) {
      return new Set();
    }
  }

  function getStarredTrackItems() {
    try {
      const raw = localStorage.getItem(LS.starredTrackItems);
      if (!raw) return {};
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
    } catch (_) {
      return {};
    }
  }

  function getStarredAlbumItems() {
    try {
      const raw = localStorage.getItem(LS.starredAlbumItems);
      if (!raw) return {};
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
    } catch (_) {
      return {};
    }
  }

  function nostrGetFirstTag(ev, name) {
    const tags = (ev && ev.tags) || [];
    for (let i = 0; i < tags.length; i++) {
      const t = tags[i];
      if (t && t[0] === name && t[1] != null && String(t[1]) !== '') return String(t[1]);
    }
    return '';
  }

  /**
   * Parse kind:1 pleXtr favorite with machine tags (legacy text-only notes return null).
   */
  function parsePlextrFavoriteEvent(ev) {
    if (!ev || ev.kind !== 1) return null;
    const tags = ev.tags || [];
    const hasT = (v) => tags.some((t) => t && t[0] === 't' && t[1] === v);
    if (!hasT('favorite') || (!hasT('plextr') && !hasT('radioguaka'))) return null;
    const item = nostrGetFirstTag(ev, 'plextr_item');
    if (!item) return null;
    const action = nostrGetFirstTag(ev, 'plextr_action');
    let starred = true;
    if (action === 'unstar') starred = false;
    else if (action === 'star') starred = true;
    else {
      const c = String(ev.content || '').trimStart();
      if (c.startsWith('☆')) starred = false;
    }
    const artist = nostrGetFirstTag(ev, 'plextr_artist');
    const title = nostrGetFirstTag(ev, 'plextr_title');
    if (item === 'track') {
      const rk = nostrGetFirstTag(ev, 'plextr_rating_key');
      if (!rk) return null;
      return {
        item: 'track',
        ratingKey: rk,
        starred: starred,
        snapshot: {
          ratingKey: rk,
          title: title,
          grandparentTitle: artist,
          parentTitle: '',
          parentRatingKey: nostrGetFirstTag(ev, 'plextr_parent_rating_key') || undefined,
          grandparentRatingKey: nostrGetFirstTag(ev, 'plextr_grandparent_rating_key') || undefined
        }
      };
    }
    if (item === 'album') {
      const rk = nostrGetFirstTag(ev, 'plextr_rating_key');
      if (!rk) return null;
      return {
        item: 'album',
        ratingKey: rk,
        starred: starred,
        snapshot: {
          ratingKey: rk,
          title: title,
          parentTitle: artist,
          leafCount: 0
        }
      };
    }
    if (item === 'station') {
      const sid = nostrGetFirstTag(ev, 'plextr_station_id');
      if (!sid) return null;
      return { item: 'station', stationId: sid, starred: starred, snapshot: null };
    }
    return null;
  }

  function parsePlextrNowPlayingTrackEvent(ev) {
    if (!ev || ev.kind !== 1) return null;
    const tags = ev.tags || [];
    const hasTag = (name, val) => tags.some((t) => t && t[0] === name && t[1] === val);
    if (!hasTag('t', 'plextr') && !hasTag('t', 'radioguaka')) return null;
    if (hasTag('t', 'favorite')) return null;
    if (hasTag('t', 'radio')) return null;
    const item = nostrGetFirstTag(ev, 'plextr_item');
    const rk = nostrGetFirstTag(ev, 'plextr_rating_key');
    if (item && item !== 'track') return null;
    if (!rk && item === 'track') return null;
    let title = nostrGetFirstTag(ev, 'plextr_title') || '';
    let artist = nostrGetFirstTag(ev, 'plextr_artist') || '';
    if (!title) {
      // Legacy note fallback: "Artist - Title #plextr"
      const raw = String(ev.content || '');
      const withoutTags = raw.replace(/#[\w-]+/g, '').trim();
      const sep = withoutTags.indexOf(' - ');
      if (sep > 0) {
        artist = artist || withoutTags.slice(0, sep).trim();
        title = withoutTags.slice(sep + 3).trim();
      } else {
        title = withoutTags.trim();
      }
    }
    if (!rk && !title && !artist) return null;
    return {
      createdAt: Number(ev.created_at || 0),
      ratingKey: rk || '',
      snapshot: {
        ratingKey: rk || '',
        title: title || '',
        grandparentTitle: artist || '',
        parentTitle: '',
        parentRatingKey: nostrGetFirstTag(ev, 'plextr_parent_rating_key') || undefined,
        grandparentRatingKey: nostrGetFirstTag(ev, 'plextr_grandparent_rating_key') || undefined,
        albumRatingKey: nostrGetFirstTag(ev, 'plextr_parent_rating_key') || ''
      }
    };
  }

  function hasNostrTag(ev, name, val) {
    const tags = (ev && ev.tags) || [];
    for (let i = 0; i < tags.length; i++) {
      const t = tags[i];
      if (!t || t[0] !== name) continue;
      if (val == null) return true;
      if (String(t[1] || '') === String(val)) return true;
    }
    return false;
  }

  function normalizeProgressState(state, source, eventMeta) {
    if (!state || typeof state !== 'object') return null;
    const albumRatingKey = String(state.albumRatingKey || '');
    const trackRatingKey = String(state.trackRatingKey || '');
    const partKey = String(state.partKey || '');
    const sectionKey = String(state.sectionKey || '');
    if (!albumRatingKey || (!trackRatingKey && !partKey) || !sectionKey) return null;
    const currentTime = Number(state.currentTime);
    if (!Number.isFinite(currentTime) || currentTime < 0) return null;
    const normalized = {
      sectionKey: sectionKey,
      albumRatingKey: albumRatingKey,
      albumTitle: String(state.albumTitle || ''),
      albumParentTitle: String(state.albumParentTitle || ''),
      trackIndex: Number.isInteger(Number(state.trackIndex)) ? Number(state.trackIndex) : -1,
      trackRatingKey: trackRatingKey,
      trackTitle: String(state.trackTitle || ''),
      partKey: partKey,
      currentTime: currentTime,
      wasPlaying: !!state.wasPlaying,
      savedAt: Number(state.savedAt) || Date.now(),
      updatedAt: Number(state.updatedAt) || Number(state.savedAt) || Date.now(),
      source: String(source || 'local')
    };
    normalized.audiobookBookId = String(state.audiobookBookId || computeAudiobookBookId(normalized));
    if (!normalized.audiobookBookId) return null;
    if (eventMeta && typeof eventMeta === 'object') {
      normalized.nostrEventId = String(eventMeta.id || '');
      normalized.nostrKind = Number(eventMeta.kind) || 0;
      normalized.nostrCreatedAt = Number(eventMeta.createdAt) || 0;
    }
    return normalized;
  }

  async function encryptNostrDmContent(tools, plaintext, peerPubkey) {
    const secretKey = nostrState.secretKey;
    const plain = String(plaintext || '');
    if (!plain) throw new Error('Empty message');
    if (!secretKey || !peerPubkey) throw new Error('Missing Nostr key material');

    const nip44 = tools && tools.nip44 && (tools.nip44.v2 || tools.nip44);
    if (nip44 && nip44.utils && typeof nip44.utils.getConversationKey === 'function') {
      const conversationKey = nip44.utils.getConversationKey(secretKey, peerPubkey);
      return { algorithm: 'nip44', content: nip44.encrypt(plain, conversationKey) };
    }

    if (tools && tools.nip04 && typeof tools.nip04.encrypt === 'function') {
      const nip04Candidates = [secretKey];
      if (tools.bytesToHex) {
        try {
          nip04Candidates.push(tools.bytesToHex(secretKey));
        } catch (_) {}
      }
      for (let i = 0; i < nip04Candidates.length; i++) {
        try {
          const encrypted = await tools.nip04.encrypt(nip04Candidates[i], peerPubkey, plain);
          if (typeof encrypted === 'string' && encrypted) return { algorithm: 'nip04', content: encrypted };
        } catch (_) {}
      }
    }
    throw new Error('No supported Nostr encryption API (need NIP-44 or NIP-04).');
  }

  async function decryptNostrDmContent(tools, ciphertext, peerPubkey, preferredAlgo) {
    const secretKey = nostrState.secretKey;
    const payload = String(ciphertext || '');
    if (!payload || !secretKey || !peerPubkey) return '';
    const attempts = preferredAlgo === 'nip04' ? ['nip04', 'nip44'] : ['nip44', 'nip04'];
    for (let i = 0; i < attempts.length; i++) {
      const algo = attempts[i];
      if (algo === 'nip44' && tools && tools.nip44) {
        const nip44 = tools.nip44.v2 || tools.nip44;
        try {
          const conversationKey = nip44.utils.getConversationKey(secretKey, peerPubkey);
          return nip44.decrypt(payload, conversationKey);
        } catch (_) {}
      }
      if (algo === 'nip04' && tools && tools.nip04 && typeof tools.nip04.decrypt === 'function') {
        const keys = [secretKey];
        if (tools.bytesToHex) {
          try {
            keys.push(tools.bytesToHex(secretKey));
          } catch (_) {}
        }
        for (let k = 0; k < keys.length; k++) {
          try {
            const plain = await tools.nip04.decrypt(keys[k], peerPubkey, payload);
            if (typeof plain === 'string') return plain;
          } catch (_) {}
        }
      }
    }
    return '';
  }

  function toNostrDeleteTags(eventIds) {
    const out = [];
    const ids = Array.isArray(eventIds) ? eventIds : [];
    for (let i = 0; i < ids.length; i++) {
      const id = String(ids[i] || '').trim();
      if (!id) continue;
      out.push(['e', id]);
    }
    return out;
  }

  async function publishNostrDeleteEvents(eventIds, reason) {
    if (!nostrState.ready || !nostrState.secretKey || !nostrState.pool) return;
    const tags = toNostrDeleteTags(eventIds);
    if (!tags.length) return;
    try {
      const tools = await getNostrTools();
      const eventTemplate = {
        kind: 5,
        created_at: Math.floor(Date.now() / 1000),
        tags: tags.concat([['t', NOSTR_TAG], ['t', 'audiobook_progress']]),
        content: String(reason || 'Superseded progress')
      };
      const signed = tools.finalizeEvent(eventTemplate, nostrState.secretKey);
      safeNostrPoolPublish(nostrState.pool, NOSTR_RELAYS, signed);
    } catch (_) {}
  }

  async function queryNostrAudiobookProgressEvents(bookId, limit) {
    if (!nostrState.ready || !nostrState.pool || !nostrState.pubkey) return [];
    const lim = Number.isFinite(Number(limit)) ? Math.max(1, Number(limit)) : NOSTR_AUDIOBOOK_PROGRESS_LIMIT;
    const pool = nostrState.pool;
    const relayUrls = [...NOSTR_RELAYS];
    const byId = new Map();
    const baseFilter = {
      kinds: [4, 5, 14],
      authors: [nostrState.pubkey],
      '#t': ['audiobook_progress'],
      limit: lim
    };
    if (bookId) baseFilter['#d'] = [String(bookId)];
    const batch = await safeNostrQuery(pool, relayUrls, baseFilter);
    (batch || []).forEach((ev) => {
      if (ev && ev.id) byId.set(ev.id, ev);
    });
    const deleted = new Set();
    const list = Array.from(byId.values());
    list.forEach((ev) => {
      if (!ev || ev.kind !== 5) return;
      const tags = ev.tags || [];
      for (let i = 0; i < tags.length; i++) {
        const t = tags[i];
        if (t && t[0] === 'e' && t[1]) deleted.add(String(t[1]));
      }
    });
    return list
      .filter((ev) => ev && (ev.kind === 4 || ev.kind === 14))
      .filter((ev) => !deleted.has(String(ev.id || '')))
      .filter((ev) => hasNostrTag(ev, 't', NOSTR_TAG) || hasNostrTag(ev, 't', 'radioguaka'))
      .filter((ev) => hasNostrTag(ev, 't', 'audiobook_progress'))
      .sort((a, b) => Number(b.created_at || 0) - Number(a.created_at || 0));
  }

  async function parseNostrAudiobookProgressEvent(ev) {
    if (!ev || (ev.kind !== 4 && ev.kind !== 14)) return null;
    const targetPubkey = String(nostrGetFirstTag(ev, 'p') || ev.pubkey || '').trim();
    if (!targetPubkey || !nostrState.pubkey || targetPubkey !== nostrState.pubkey) return null;
    const algoTag = String(nostrGetFirstTag(ev, 'encryption') || '').toLowerCase();
    const preferredAlgo = algoTag === 'nip04' ? 'nip04' : 'nip44';
    const tools = await getNostrTools();
    const plain = await decryptNostrDmContent(tools, ev.content, targetPubkey, preferredAlgo);
    if (!plain) return null;
    const payload = parseNostrMaybeJson(plain);
    if (!payload || payload.kind !== 'audiobook_progress') return null;
    const normalized = normalizeProgressState(payload.state || payload, 'nostr', {
      id: ev.id,
      kind: ev.kind,
      createdAt: Number(ev.created_at || 0)
    });
    if (!normalized) return null;
    const createdAtMs = Number(ev.created_at || 0) * 1000;
    if (createdAtMs > 0 && createdAtMs > normalized.updatedAt) normalized.updatedAt = createdAtMs;
    return normalized;
  }

  function refreshAllStarButtonsFromStorage() {
    updateNowPlayingFavButton(nowPlayingTrackKey || '');
    const grid = $('albumGrid');
    if (grid) {
      const albumSet = getStarredAlbums();
      grid.querySelectorAll('.album .star-btn[data-rating-key]').forEach((btn) => {
        const key = String(btn.dataset.ratingKey || '');
        if (!key) return;
        const starred = albumSet.has(key);
        btn.classList.toggle('starred', starred);
        btn.setAttribute('aria-label', starred ? 'Unstar album' : 'Star album');
        btn.setAttribute('title', starred ? 'Unstar' : 'Star');
        btn.innerHTML = starIconHtml(starred);
      });
    }
    const vt = $('viewTracks');
    if (vt) {
      const trackSet = getStarredTracks();
      vt.querySelectorAll('.tracks li .star-btn[data-rating-key]').forEach((btn) => {
        const key = String(btn.dataset.ratingKey || '');
        if (!key) return;
        const starred = trackSet.has(key);
        btn.classList.toggle('starred', starred);
        btn.setAttribute('aria-label', starred ? 'Unstar track' : 'Star track');
        btn.setAttribute('title', starred ? 'Unstar' : 'Star');
        btn.innerHTML = starIconHtml(starred);
      });
    }
    const detailStar = $('btnAlbumDetailStar');
    if (detailStar && detailStar.dataset.ratingKey) {
      const ak = String(detailStar.dataset.ratingKey);
      syncAlbumDetailStarButton(ak, getStarredAlbums().has(ak));
    }
    const radioWrap = $('viewRadio');
    if (radioWrap) {
      const stSet = getStarredStations();
      radioWrap.querySelectorAll('button.star-btn[data-radio-star]').forEach((btn) => {
        const id = String(btn.getAttribute('data-radio-star') || '');
        if (!id) return;
        const starred = stSet.has(id);
        btn.classList.toggle('starred', starred);
        btn.setAttribute('aria-label', starred ? 'Unfavorite station' : 'Favorite station');
        btn.setAttribute('title', starred ? 'Unfavorite station' : 'Favorite station');
        btn.innerHTML = starIconHtml(starred);
      });
    }
  }

  async function applyNostrFavoritesToLocalStorage() {
    if (!nostrState.ready || !nostrState.pool || !nostrState.pubkey) return;
    try {
      const pool = nostrState.pool;
      const relayUrls = [...NOSTR_RELAYS];
      const filter = {
        kinds: [1],
        authors: [nostrState.pubkey],
        '#t': ['favorite'],
        limit: NOSTR_FAVORITES_LIMIT
      };
      const batch = await safeNostrQuery(pool, relayUrls, filter);
      const list = (batch || []).filter((ev) => {
        const t = ev.tags || [];
        const pill = (name, val) => t.some((x) => x && x[0] === name && x[1] === val);
        return pill('t', 'favorite') && (pill('t', 'plextr') || pill('t', 'radioguaka'));
      });
      list.sort((a, b) => Number(a.created_at || 0) - Number(b.created_at || 0));
      const trackMap = new Map();
      const albumMap = new Map();
      const stationMap = new Map();
      for (let i = 0; i < list.length; i++) {
        const p = parsePlextrFavoriteEvent(list[i]);
        if (!p) continue;
        if (p.item === 'track') trackMap.set(p.ratingKey, p);
        else if (p.item === 'album') albumMap.set(p.ratingKey, p);
        else if (p.item === 'station') stationMap.set(p.stationId, p);
      }
      const trSet = getStarredTracks();
      const trSnaps = Object.assign({}, getStarredTrackItems());
      const alSet = getStarredAlbums();
      const alSnaps = Object.assign({}, getStarredAlbumItems());
      const stSet = getStarredStations();
      trackMap.forEach((p, rk) => {
        if (p.starred) {
          trSet.add(rk);
          if (p.snapshot && typeof p.snapshot === 'object') trSnaps[rk] = Object.assign({}, trSnaps[rk] || {}, p.snapshot);
        } else {
          trSet.delete(rk);
          delete trSnaps[rk];
        }
      });
      albumMap.forEach((p, rk) => {
        if (p.starred) {
          alSet.add(rk);
          if (p.snapshot && typeof p.snapshot === 'object') alSnaps[rk] = Object.assign({}, alSnaps[rk] || {}, p.snapshot);
        } else {
          alSet.delete(rk);
          delete alSnaps[rk];
        }
      });
      stationMap.forEach((p, id) => {
        if (p.starred) stSet.add(id);
        else stSet.delete(id);
      });
      localStorage.setItem(LS.starredTracks, JSON.stringify([...trSet]));
      localStorage.setItem(LS.starredTrackItems, JSON.stringify(trSnaps));
      localStorage.setItem(LS.starredAlbums, JSON.stringify([...alSet]));
      localStorage.setItem(LS.starredAlbumItems, JSON.stringify(alSnaps));
      localStorage.setItem(LS.starredStations, JSON.stringify([...stSet]));
      refreshAllStarButtonsFromStorage();
      updateFilterState();
      if (navView === 'favorites') renderLibraryView();
    } catch (err) {
      console.warn('applyNostrFavoritesToLocalStorage', err);
      setNostrStatus('Could not sync favorites from Nostr: ' + (err && err.message ? err.message : String(err)), 'error');
    }
  }

  function recentTrackMergeId(entry) {
    if (!entry || typeof entry !== 'object') return '';
    const rk = String(entry.ratingKey || '').trim();
    if (rk) return 'track:' + rk;
    const title = String(entry.title || entry.trackTitle || '').trim().toLowerCase();
    const artist = String(entry.grandparentTitle || entry.artist || entry.albumParentTitle || '').trim().toLowerCase();
    if (!title && !artist) return '';
    return 'fallback:' + title + '|' + artist;
  }

  async function applyNostrRecentToLocalStorage() {
    if (!nostrState.ready || !nostrState.pool || !nostrState.pubkey) return;
    try {
      const pool = nostrState.pool;
      const relayUrls = [...NOSTR_RELAYS];
      const tagVariants = ['nowplaying', NOSTR_TAG, 'radioguaka'];
      const byId = new Map();
      for (let i = 0; i < tagVariants.length; i++) {
        const filter = {
          kinds: [1],
          authors: [nostrState.pubkey],
          '#t': [tagVariants[i]],
          limit: NOSTR_RECENT_LIMIT
        };
        const batch = await safeNostrQuery(pool, relayUrls, filter);
        (batch || []).forEach((ev) => {
          if (ev && ev.id) byId.set(ev.id, ev);
        });
      }
      const nostrRecent = Array.from(byId.values())
        .map(parsePlextrNowPlayingTrackEvent)
        .filter(Boolean)
        .sort((a, b) => Number(b.createdAt || 0) - Number(a.createdAt || 0))
        .map((item) => item.snapshot);

      if (!nostrRecent.length) return;

      const merged = [];
      const seen = new Set();
      nostrRecent.forEach((entry) => {
        const id = recentTrackMergeId(entry);
        if (!id || seen.has(id)) return;
        seen.add(id);
        merged.push(entry);
      });
      getRecentTracks().forEach((entry) => {
        const id = recentTrackMergeId(entry);
        if (!id || seen.has(id)) return;
        seen.add(id);
        merged.push(entry);
      });
      saveRecentTracks(merged);
      if (navView === 'recent') renderLibraryView();
    } catch (err) {
      console.warn('applyNostrRecentToLocalStorage', err);
      setNostrStatus('Could not sync recently played from Nostr: ' + (err && err.message ? err.message : String(err)), 'error');
    }
  }

  function toggleTrackStar(ratingKey, trackData) {
    const key = String(ratingKey);
    if (!key || key === 'undefined' || key === 'null') return false;
    const set = getStarredTracks();
    const snapshots = getStarredTrackItems();
    if (set.has(key)) set.delete(key);
    else {
      set.add(key);
      const fallback = trackLookup.get(key);
      const source = trackData || fallback;
      if (source && typeof source === 'object') snapshots[key] = source;
    }
    if (!set.has(key)) delete snapshots[key];
    try {
      localStorage.setItem(LS.starredTracks, JSON.stringify([...set]));
      localStorage.setItem(LS.starredTrackItems, JSON.stringify(snapshots));
    } catch (_) {}
    if (navView === 'favorites') renderLibraryView();
    return set.has(key);
  }

  function toggleAlbumStar(ratingKey, albumData) {
    const key = String(ratingKey);
    if (!key || key === 'undefined' || key === 'null') return false;
    const set = getStarredAlbums();
    const snapshots = getStarredAlbumItems();
    if (set.has(key)) set.delete(key);
    else {
      set.add(key);
      const fallback = albumLookup.get(key);
      const source = albumData || fallback;
      if (source && typeof source === 'object') snapshots[key] = source;
    }
    if (!set.has(key)) delete snapshots[key];
    try {
      localStorage.setItem(LS.starredAlbums, JSON.stringify([...set]));
      localStorage.setItem(LS.starredAlbumItems, JSON.stringify(snapshots));
    } catch (_) {}
    if (navView === 'favorites') renderLibraryView();
    return set.has(key);
  }

  function toggleStationStar(stationId) {
    const key = String(stationId || '');
    if (!key) return false;
    const set = getStarredStations();
    if (set.has(key)) set.delete(key);
    else set.add(key);
    try {
      localStorage.setItem(LS.starredStations, JSON.stringify([...set]));
    } catch (_) {}
    if (navView === 'favorites') renderLibraryView();
    return set.has(key);
  }

  function getRecentTracks() {
    try {
      const raw = localStorage.getItem(LS.recentTracks);
      if (!raw) return [];
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed : [];
    } catch (_) {
      return [];
    }
  }

  function saveRecentTracks(items) {
    try {
      localStorage.setItem(LS.recentTracks, JSON.stringify(items.slice(0, 40)));
    } catch (_) {}
  }

  let searchDbPromise = null;

  function idbWrapRequest(req) {
    return new Promise((resolve, reject) => {
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error || new Error('IndexedDB request failed'));
    });
  }

  function openSearchDb() {
    if (searchDbPromise) return searchDbPromise;
    searchDbPromise = new Promise((resolve, reject) => {
      if (!window.indexedDB) {
        reject(new Error('IndexedDB is not available in this browser.'));
        return;
      }
      const req = window.indexedDB.open(SEARCH_DB_NAME, SEARCH_DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        let store = null;
        if (!db.objectStoreNames.contains(SEARCH_STORE)) {
          store = db.createObjectStore(SEARCH_STORE, { keyPath: 'id' });
        } else {
          store = req.transaction.objectStore(SEARCH_STORE);
        }
        if (store && !store.indexNames.contains('sectionKey')) {
          store.createIndex('sectionKey', 'sectionKey', { unique: false });
        }
        if (store && !store.indexNames.contains('updatedAt')) {
          store.createIndex('updatedAt', 'updatedAt', { unique: false });
        }
        if (!db.objectStoreNames.contains(ALBUM_LIST_STORE)) {
          db.createObjectStore(ALBUM_LIST_STORE, { keyPath: 'id' });
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error || new Error('Could not open IndexedDB.'));
    }).catch((err) => {
      console.warn(err);
      return null;
    });
    return searchDbPromise;
  }

  function searchRecordId(sectionKey, albumKey) {
    return String(sectionKey || '') + '::' + String(albumKey || '');
  }

  function loadSearchIndexMeta() {
    try {
      const raw = localStorage.getItem(LS.searchIndexMeta);
      if (!raw) return {};
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === 'object' ? parsed : {};
    } catch (_) {
      return {};
    }
  }

  function saveSearchIndexMeta(meta) {
    try {
      localStorage.setItem(LS.searchIndexMeta, JSON.stringify(meta || {}));
    } catch (_) {}
  }

  function activeSectionKey() {
    return String($('inSection').value || cfg().sectionKey || '');
  }

  async function readSectionSearchRecords(sectionKey) {
    const db = await openSearchDb();
    if (!db || !sectionKey) return [];
    const tx = db.transaction(SEARCH_STORE, 'readonly');
    const store = tx.objectStore(SEARCH_STORE);
    const index = store.index('sectionKey');
    const range = IDBKeyRange.only(String(sectionKey));
    return idbWrapRequest(index.getAll(range));
  }

  async function readAllSearchRecords() {
    const db = await openSearchDb();
    if (!db) return [];
    const tx = db.transaction(SEARCH_STORE, 'readonly');
    const store = tx.objectStore(SEARCH_STORE);
    return idbWrapRequest(store.getAll());
  }

  function invalidateAllSearchRecordsCache() {
    allSearchRecordsCache = null;
    allSearchRecordsCacheLoadedAt = 0;
  }

  async function readAllSearchRecordsCached(opts) {
    const force = !!(opts && opts.force);
    const now = Date.now();
    if (
      !force &&
      Array.isArray(allSearchRecordsCache) &&
      now - allSearchRecordsCacheLoadedAt < SEARCH_RECORD_CACHE_TTL_MS
    ) {
      return allSearchRecordsCache;
    }
    const records = await readAllSearchRecords();
    allSearchRecordsCache = Array.isArray(records) ? records : [];
    allSearchRecordsCacheLoadedAt = Date.now();
    return allSearchRecordsCache;
  }

  async function putSearchRecords(records) {
    if (!records || !records.length) return;
    const db = await openSearchDb();
    if (!db) return;
    const tx = db.transaction(SEARCH_STORE, 'readwrite');
    const store = tx.objectStore(SEARCH_STORE);
    records.forEach((record) => {
      store.put(record);
    });
    await new Promise((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error || new Error('IndexedDB write failed.'));
      tx.onabort = () => reject(tx.error || new Error('IndexedDB write aborted.'));
    }).catch((err) => {
      console.warn(err);
    });
    if (Array.isArray(allSearchRecordsCache)) {
      const byId = new Map(allSearchRecordsCache.map((r) => [String(r.id || ''), r]));
      records.forEach((record) => {
        if (!record) return;
        const id = String(record.id || '');
        if (!id) return;
        byId.set(id, record);
      });
      allSearchRecordsCache = Array.from(byId.values());
      allSearchRecordsCacheLoadedAt = Date.now();
    }
  }

  async function getSearchRecord(sectionKey, albumKey) {
    const db = await openSearchDb();
    if (!db || !sectionKey || !albumKey) return null;
    const tx = db.transaction(SEARCH_STORE, 'readonly');
    const store = tx.objectStore(SEARCH_STORE);
    const id = searchRecordId(sectionKey, albumKey);
    const record = await idbWrapRequest(store.get(id)).catch(() => null);
    return record || null;
  }

  function plexAlbumCacheNs() {
    const { server, token } = cfg();
    const s = String(server || '') + '\0' + String(token || '');
    let h = 5381;
    for (let i = 0; i < s.length; i++) h = (h * 33 + s.charCodeAt(i)) | 0;
    return String(h);
  }

  function albumListCacheId(ns, sectionKey, listMode, start) {
    return String(ns) + '::' + String(sectionKey) + '::' + String(listMode) + '::' + String(start);
  }

  const ALBUM_LIB_META_KIND = 'libAlbumTotal';

  function albumLibraryMetaRowId(ns, sectionKey) {
    return String(ns) + '::' + String(sectionKey) + '::' + ALBUM_LIB_META_KIND;
  }

  async function readAlbumLibraryMeta(ns, sectionKey) {
    try {
      const db = await openSearchDb();
      if (!db || !sectionKey || !db.objectStoreNames.contains(ALBUM_LIST_STORE)) return null;
      const id = albumLibraryMetaRowId(ns, sectionKey);
      const tx = db.transaction(ALBUM_LIST_STORE, 'readonly');
      const rec = await idbWrapRequest(tx.objectStore(ALBUM_LIST_STORE).get(id)).catch(() => null);
      if (!rec || rec.ns !== ns || rec.kind !== ALBUM_LIB_META_KIND) return null;
      return rec;
    } catch (_) {
      return null;
    }
  }

  function readAlbumLibraryMetaWithTimeout(ns, sectionKey, ms) {
    const cap = typeof ms === 'number' ? ms : 500;
    return Promise.race([
      readAlbumLibraryMeta(ns, sectionKey),
      new Promise((resolve) => setTimeout(() => resolve(null), cap))
    ]);
  }

  async function putAlbumLibraryMeta(ns, sectionKey, totalAlbums, listMode) {
    try {
      const n = Number(totalAlbums);
      if (!Number.isFinite(n) || n <= 0) return;
      const db = await openSearchDb();
      if (!db || !db.objectStoreNames.contains(ALBUM_LIST_STORE)) return;
      const id = albumLibraryMetaRowId(ns, sectionKey);
      const tx = db.transaction(ALBUM_LIST_STORE, 'readwrite');
      const store = tx.objectStore(ALBUM_LIST_STORE);
      store.put({
        id,
        ns,
        sectionKey: String(sectionKey),
        kind: ALBUM_LIB_META_KIND,
        totalAlbums: n,
        listMode: String(listMode || 'albums'),
        updatedAt: Date.now()
      });
      await new Promise((resolve, reject) => {
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error || new Error('lib meta write failed'));
        tx.onabort = () => reject(tx.error || new Error('lib meta write aborted'));
      }).catch(() => {});
    } catch (_) {}
  }

  async function readAlbumListPage(ns, sectionKey, listMode, start) {
    try {
      const db = await openSearchDb();
      if (!db || !sectionKey) return null;
      if (!db.objectStoreNames.contains(ALBUM_LIST_STORE)) return null;
      const id = albumListCacheId(ns, sectionKey, listMode, start);
      const tx = db.transaction(ALBUM_LIST_STORE, 'readonly');
      const store = tx.objectStore(ALBUM_LIST_STORE);
      const rec = await idbWrapRequest(store.get(id)).catch(() => null);
      if (!rec || rec.ns !== ns || !Array.isArray(rec.items)) return null;
      return rec;
    } catch (_) {
      return null;
    }
  }

  /** Avoid blocking album load if IndexedDB never settles (stale connection, browser quirks). */
  function readAlbumListPageWithTimeout(ns, sectionKey, listMode, start, ms) {
    const cap = typeof ms === 'number' ? ms : 800;
    return Promise.race([
      readAlbumListPage(ns, sectionKey, listMode, start),
      new Promise((resolve) => setTimeout(() => resolve(null), cap))
    ]);
  }

  async function putAlbumListPage(ns, sectionKey, listMode, start, totalSize, items) {
    if (!ns || !sectionKey || !listMode || !Array.isArray(items)) return;
    try {
      const db = await openSearchDb();
      if (!db || !db.objectStoreNames.contains(ALBUM_LIST_STORE)) return;
      const id = albumListCacheId(ns, sectionKey, listMode, start);
      const tx = db.transaction(ALBUM_LIST_STORE, 'readwrite');
      const store = tx.objectStore(ALBUM_LIST_STORE);
      store.put({
        id,
        ns,
        sectionKey: String(sectionKey),
        listMode: String(listMode),
        start: Number(start) || 0,
        totalSize: Number(totalSize) || 0,
        items,
        cachedAt: Date.now()
      });
      await new Promise((resolve, reject) => {
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error || new Error('IndexedDB album cache write failed.'));
        tx.onabort = () => reject(tx.error || new Error('IndexedDB album cache write aborted.'));
      }).catch((err) => {
        console.warn(err);
      });
    } catch (_) {}
  }

  async function deleteAlbumListPage(ns, sectionKey, listMode, start) {
    try {
      const db = await openSearchDb();
      if (!db || !sectionKey || !db.objectStoreNames.contains(ALBUM_LIST_STORE)) return;
      const id = albumListCacheId(ns, sectionKey, listMode, start);
      const tx = db.transaction(ALBUM_LIST_STORE, 'readwrite');
      const store = tx.objectStore(ALBUM_LIST_STORE);
      store.delete(id);
      await new Promise((resolve, reject) => {
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error || new Error('IndexedDB album cache delete failed.'));
        tx.onabort = () => reject(tx.error || new Error('IndexedDB album cache delete aborted.'));
      }).catch(() => {});
    } catch (_) {}
  }

  function formatClock(seconds) {
    const n = Number(seconds);
    if (!Number.isFinite(n) || n < 0) return '0:00';
    const s = Math.floor(n);
    const mm = Math.floor(s / 60);
    const ss = s % 60;
    return mm + ':' + (ss < 10 ? '0' : '') + ss;
  }

  function runCrossfade(audio, targetVolume, onFadeOutDone) {
    const startVol = audio.volume;
    const steps = Math.max(1, Math.round(CROSSFADE_DURATION_MS / 2 / CROSSFADE_STEP_MS));
    let step = 0;
    const fadeOut = () => {
      step++;
      const t = step / steps;
      audio.volume = Math.max(0, startVol * (1 - t));
      if (step < steps) {
        setTimeout(fadeOut, CROSSFADE_STEP_MS);
      } else {
        audio.volume = 0;
        onFadeOutDone();
      }
    };
    fadeOut();
  }

  function runFadeIn(audio, targetVolume, onDone) {
    const steps = Math.max(1, Math.round(CROSSFADE_DURATION_MS / 2 / CROSSFADE_STEP_MS));
    let step = 0;
    const fadeIn = () => {
      step++;
      const t = step / steps;
      audio.volume = Math.min(1, t * targetVolume);
      if (step < steps) {
        setTimeout(fadeIn, CROSSFADE_STEP_MS);
      } else {
        audio.volume = targetVolume;
        if (onDone) onDone();
      }
    };
    fadeIn();
  }

  function streamUrl(partKey) {
    const { server, token } = cfg();
    const key = String(partKey || '');
    if (!key) return '';
    if (sameOriginPlexProxyReady) {
      const proxied = '/api/plex' + (key.startsWith('/') ? key : '/' + key);
      return token ? proxied + (proxied.includes('?') ? '&' : '?') + 'X-Plex-Token=' + encodeURIComponent(token) : proxied;
    }
    const sep = key.includes('?') ? '&' : '?';
    return server + key + sep + 'X-Plex-Token=' + encodeURIComponent(token);
  }

  function metadataDownloadUrl(track) {
    if (!track || track.ratingKey == null) return '';
    const { server, token } = cfg();
    const rk = encodeURIComponent(String(track.ratingKey));
    if (sameOriginPlexProxyReady) {
      // Keep caller-owned credentials when using the same-origin download fallback.
      const path = '/api/plex/library/metadata/' + rk + '/download';
      return token ? path + '?X-Plex-Token=' + encodeURIComponent(token) : path;
    }
    const base = server + '/library/metadata/' + rk + '/download';
    return token ? base + '?X-Plex-Token=' + encodeURIComponent(token) : base;
  }

  function metadataUrlForRatingKey(ratingKey) {
    if (ratingKey == null || ratingKey === '') return '';
    const { server, token } = cfg();
    const rk = encodeURIComponent(String(ratingKey));
    if (sameOriginPlexProxyReady) {
      const proxied = '/api/plex/library/metadata/' + rk;
      return token ? proxied + '?X-Plex-Token=' + encodeURIComponent(token) : proxied;
    }
    const path = '/library/metadata/' + rk;
    const base = server + path;
    return token ? base + (base.includes('?') ? '&' : '?') + 'X-Plex-Token=' + encodeURIComponent(token) : base;
  }

  function extractFirstPlayablePartKeyFromMetadataPayload(payload) {
    const mc = payload && payload.MediaContainer ? payload.MediaContainer : payload;
    const meta = Array.isArray(mc && mc.Metadata) ? mc.Metadata : [];
    if (!meta.length) return '';
    return getFirstPlayablePartKey(meta[0]);
  }

  function extractFirstPlayablePartKeyFromMetadataXml(xmlText) {
    const raw = String(xmlText || '').trim();
    if (!raw) return '';
    try {
      const doc = new DOMParser().parseFromString(raw, 'application/xml');
      const parserErr = doc.getElementsByTagName('parsererror');
      if (parserErr && parserErr.length) return '';
      const partEl = doc.querySelector('MediaContainer > Track > Media > Part[key], Track > Media > Part[key], Part[key]');
      if (!partEl) return '';
      const key = partEl.getAttribute('key');
      return key ? String(key) : '';
    } catch (_) {
      return '';
    }
  }

  async function fetchFreshPartKeyForTrack(track) {
    if (!track || track.ratingKey == null || track.ratingKey === '') return '';
    const { token } = cfg();
    const url = metadataUrlForRatingKey(track.ratingKey);
    if (!url) return '';
    const res = await fetch(url, {
      headers: {
        Accept: 'application/json',
        'X-Plex-Token': token || ''
      }
    });
    if (!res.ok) throw new Error('metadata status ' + res.status);
    const body = await res.text();
    if (!body) return '';
    try {
      const data = JSON.parse(body);
      return extractFirstPlayablePartKeyFromMetadataPayload(data);
    } catch (_) {
      return extractFirstPlayablePartKeyFromMetadataXml(body);
    }
  }

  function mediaErrorName(code) {
    if (code === MediaError.MEDIA_ERR_ABORTED) return 'MEDIA_ERR_ABORTED';
    if (code === MediaError.MEDIA_ERR_NETWORK) return 'MEDIA_ERR_NETWORK';
    if (code === MediaError.MEDIA_ERR_DECODE) return 'MEDIA_ERR_DECODE';
    if (code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED) return 'MEDIA_ERR_SRC_NOT_SUPPORTED';
    return 'UNKNOWN_MEDIA_ERR';
  }

  function maskPlexToken(url) {
    const s = String(url || '');
    return s.replace(/([?&]X-Plex-Token=)[^&]+/gi, '$1<redacted>');
  }

  function plexDebug(event, data) {
    let enabled = false;
    try {
      enabled =
        /\bplexDebug=1\b/.test(String(location.search || '')) ||
        localStorage.getItem('plextr_stream_debug') === '1' ||
        window.__PLEXTR_STREAM_DEBUG__ === true;
    } catch (_) {}
    if (!enabled) return;
    const important = {
      'audio-error': true,
      'retry-with-album-refresh-start': true,
      'retry-with-album-refresh': true,
      'retry-with-album-refresh-failed': true,
      'retry-with-fresh-metadata-part-start': true,
      'retry-with-fresh-metadata-part': true,
      'retry-with-fresh-metadata-part-failed': true,
      'retry-with-metadata-download': true,
      'cross-origin-failure': true,
      'orb-timer-check': true,
      'final-failure-no-fallback': true
    };
    if (!important[String(event || '')]) return;
    try {
      console.debug('[pleXtr][stream]', event, data || {});
    } catch (_) {}
  }

  function isPlexLibraryPartsStreamUrl(url) {
    return String(url || '').indexOf('/library/parts/') !== -1;
  }

  function normalizedMediaUrl(url) {
    try {
      return new URL(url, location.href).href;
    } catch (_) {
      return String(url || '');
    }
  }

  function isCrossOriginMediaUrl(url) {
    try {
      const u = new URL(url, location.href);
      return u.origin !== location.origin;
    } catch (_) {
      return false;
    }
  }

  function trackFormatHint(track) {
    if (!track || !Array.isArray(track.Media)) return '';
    for (let mi = 0; mi < track.Media.length; mi++) {
      const media = track.Media[mi];
      if (!media) continue;
      const codec = String(media.audioCodec || media.codec || '').trim();
      const container = String(media.container || '').trim();
      if (codec && container) return codec + '/' + container;
      if (codec) return codec;
      if (container) return container;
    }
    return '';
  }

  function plexMissingMediaHelpText() {
    return (
      'Plex reports this track as missing (404). This usually means stale/moved files in Plex for this album. ' +
      'In Plex, run "Scan Library Files" for this library and verify the library folder paths/mounts still match where the files live.'
    );
  }

  /**
   * Cross-origin Plex part streams often fail in Firefox (OpaqueResponseBlocking) while fetch() to the API works.
   * Retry once with CORS mode if the server sends Access-Control-Allow-Origin on streams; otherwise show proxy hint.
   */
  function setAudioElementSource(audio, url) {
    if (!audio || !url) return;
    if (audio._plexOrbErrorTimer) {
      clearTimeout(audio._plexOrbErrorTimer);
      audio._plexOrbErrorTimer = null;
    }
    audio._plexStreamGen = (audio._plexStreamGen || 0) + 1;
    const gen = audio._plexStreamGen;
    const want = normalizedMediaUrl(url);
    const activeTrack = audio._plexTrackForSource || (currentTracks && currentTracks[playingIdx]) || null;
    const baseCtx = {
      gen: gen,
      trackRatingKey: activeTrack && activeTrack.ratingKey ? String(activeTrack.ratingKey) : '',
      trackTitle: activeTrack && activeTrack.title ? String(activeTrack.title) : '',
      wantedUrl: maskPlexToken(want),
      crossOrigin: isCrossOriginMediaUrl(url)
    };
    plexDebug('set-source', baseCtx);
    const applySrc = (nextUrl) => {
      audio._plexExpectedUrl = normalizedMediaUrl(nextUrl);
      audio.src = nextUrl;
    };

    if (!isPlexLibraryPartsStreamUrl(url)) {
      audio._guakaCorsAttempt = 0;
      const onCorsErr = () => {
        if (audio._plexStreamGen !== gen) return;
        if (normalizedMediaUrl(audio.currentSrc || audio.src) !== want) return;
        const me = audio.error;
        if (me && (me.code === 1 || me.code === MediaError.MEDIA_ERR_ABORTED)) return;
        if (audio._guakaCorsAttempt) return;
        audio._guakaCorsAttempt = 1;
        audio.removeAttribute('crossorigin');
        applySrc(url);
      };
      audio.crossOrigin = 'anonymous';
      audio.addEventListener('error', onCorsErr, { once: true });
      applySrc(url);
      plexDebug('non-parts-source-applied', Object.assign({}, baseCtx, {
        appliedUrl: maskPlexToken(normalizedMediaUrl(audio.currentSrc || audio.src))
      }));
      return;
    }

    audio._plexStreamLoadStartedAt = Date.now();
    audio._plexOrbAttempt = 0;
    audio._plexMetadataDownloadFallbackTried = false;
    audio._plexRefetchPartTried = false;
    audio._plexAlbumRefreshTried = false;
    audio.removeAttribute('crossorigin');

    const cancelPlexOrbErrorTimer = () => {
      if (audio._plexStreamGen !== gen) return;
      plexDebug('ready', Object.assign({}, baseCtx, {
        readyState: audio.readyState,
        currentSrc: maskPlexToken(normalizedMediaUrl(audio.currentSrc || audio.src))
      }));
      if (audio._plexOrbErrorTimer) {
        clearTimeout(audio._plexOrbErrorTimer);
        audio._plexOrbErrorTimer = null;
      }
    };
    audio.addEventListener('playing', cancelPlexOrbErrorTimer, { once: true });
    audio.addEventListener('canplay', cancelPlexOrbErrorTimer, { once: true });

    const tryShowPlexOrbError = () => {
      audio._plexOrbErrorTimer = null;
      if (audio._plexStreamGen !== gen) return;
      if (audio._plexExpectedUrl && normalizedMediaUrl(audio.currentSrc || audio.src) !== audio._plexExpectedUrl) return;
      const me = audio.error;
      plexDebug('orb-timer-check', Object.assign({}, baseCtx, {
        readyState: audio.readyState,
        currentSrc: maskPlexToken(normalizedMediaUrl(audio.currentSrc || audio.src)),
        mediaErrorCode: me ? me.code : 0,
        mediaErrorName: me ? mediaErrorName(me.code) : '',
        mediaErrorMessage: me && me.message ? String(me.message) : ''
      }));
      if (me && (me.code === 1 || me.code === MediaError.MEDIA_ERR_ABORTED)) return;
      if (audio.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA && !me) return;
      showCors(true);
      showError(
        'Plex audio is blocked by the browser (often Firefox OpaqueResponseBlocking): this page and Plex must share the same origin, or use a reverse proxy. Expand the yellow note above or see Settings → Server URL.'
      );
    };

    const onErr = () => {
      if (audio._plexStreamGen !== gen) return;
      if (audio._plexExpectedUrl && normalizedMediaUrl(audio.currentSrc || audio.src) !== audio._plexExpectedUrl) return;
      const me = audio.error;
      if (me && (me.code === 1 || me.code === MediaError.MEDIA_ERR_ABORTED)) return;
      const crossOriginStream = isCrossOriginMediaUrl(url);
      plexDebug('audio-error', Object.assign({}, baseCtx, {
        orbAttempt: audio._plexOrbAttempt || 0,
        readyState: audio.readyState,
        networkState: audio.networkState,
        currentSrc: maskPlexToken(normalizedMediaUrl(audio.currentSrc || audio.src)),
        mediaErrorCode: me ? me.code : 0,
        mediaErrorName: me ? mediaErrorName(me.code) : '',
        mediaErrorMessage: me && me.message ? String(me.message) : '',
        duration: Number(audio.duration) || 0,
        currentTime: Number(audio.currentTime) || 0
      }));
      const format = trackFormatHint(activeTrack);
      const tryMetadataDownloadFallback = () => {
        if (audio._plexMetadataDownloadFallbackTried) return false;
        const fallbackUrl = metadataDownloadUrl(activeTrack);
        if (!fallbackUrl || normalizedMediaUrl(fallbackUrl) === want) return false;
        audio._plexMetadataDownloadFallbackTried = true;
        plexDebug('retry-with-metadata-download', Object.assign({}, baseCtx, {
          fallbackUrl: maskPlexToken(fallbackUrl)
        }));
        audio.removeAttribute('crossorigin');
        audio._plexOrbAttempt = 0;
        audio.addEventListener('error', onErr, { once: true });
        applySrc(fallbackUrl);
        return true;
      };
      // Firefox ORB/CORS on cross-origin Plex parts often reports MEDIA_ERR_SRC_NOT_SUPPORTED (code 4).
      // Only treat code 4 as "unsupported format" when stream is same-origin.
      if (
        me &&
        me.code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED &&
        !crossOriginStream &&
        audio._plexMetadataDownloadFallbackTried
      ) {
        const suffix = format ? ' (' + format + ')' : '';
        showError(
          'This track format is not supported by this browser' +
            suffix +
            '. Try Plex Web for this file, or enable a transcode/proxy path that serves a browser-playable stream.'
        );
        return;
      }
      if (!crossOriginStream) {
        if (!audio._plexAlbumRefreshTried && currentAlbum && currentAlbum.ratingKey != null) {
          audio._plexAlbumRefreshTried = true;
          plexDebug('retry-with-album-refresh-start', baseCtx);
          const preferredIdx = Number.isInteger(playingIdx) ? playingIdx : 0;
          refreshCurrentAlbumTracksForPlayback()
            .then((ok) => {
              if (audio._plexStreamGen !== gen) return;
              if (!ok || !Array.isArray(currentTracks) || !currentTracks.length) {
                throw new Error('album refresh yielded no tracks');
              }
              const pick = findRetryTrackAfterRefresh(activeTrack, currentTracks, preferredIdx);
              const retryTrack = pick.track;
              const retryIdx = pick.index;
              const retryPartKey = getFirstPlayablePartKey(retryTrack);
              const retryUrl = streamUrl(retryPartKey);
              if (!retryTrack || !retryPartKey || !retryUrl || normalizedMediaUrl(retryUrl) === want) {
                throw new Error('album refresh had no alternate stream');
              }
              playingIdx = retryIdx >= 0 ? retryIdx : playingIdx;
              audio._plexTrackForSource = retryTrack;
              plexDebug('retry-with-album-refresh', Object.assign({}, baseCtx, {
                retryTrackRatingKey: retryTrack && retryTrack.ratingKey ? String(retryTrack.ratingKey) : '',
                retryPartKey: retryPartKey,
                retryUrl: maskPlexToken(retryUrl)
              }));
              audio.removeAttribute('crossorigin');
              audio._plexOrbAttempt = 0;
              audio.addEventListener('error', onErr, { once: true });
              applySrc(retryUrl);
            })
            .catch((err) => {
              if (audio._plexStreamGen !== gen) return;
              plexDebug('retry-with-album-refresh-failed', Object.assign({}, baseCtx, {
                reason: String((err && err.message) || err || '')
              }));
              if (!audio._plexRefetchPartTried && activeTrack && activeTrack.ratingKey != null) {
                audio._plexRefetchPartTried = true;
                plexDebug('retry-with-fresh-metadata-part-start', baseCtx);
                fetchFreshPartKeyForTrack(activeTrack)
                  .then((freshPartKey) => {
                    if (audio._plexStreamGen !== gen) return;
                    const freshUrl = streamUrl(freshPartKey);
                    if (!freshPartKey || !freshUrl || normalizedMediaUrl(freshUrl) === want) {
                      throw new Error('no alternate part');
                    }
                    plexDebug('retry-with-fresh-metadata-part', Object.assign({}, baseCtx, {
                      freshPartKey: freshPartKey,
                      freshUrl: maskPlexToken(freshUrl)
                    }));
                    audio.removeAttribute('crossorigin');
                    audio._plexOrbAttempt = 0;
                    audio.addEventListener('error', onErr, { once: true });
                    applySrc(freshUrl);
                  })
                  .catch((err2) => {
                    if (audio._plexStreamGen !== gen) return;
                    plexDebug('retry-with-fresh-metadata-part-failed', Object.assign({}, baseCtx, {
                      reason: String((err2 && err2.message) || err2 || '')
                    }));
                    if (tryMetadataDownloadFallback()) return;
                    plexDebug('final-failure-no-fallback', baseCtx);
                    showError(plexMissingMediaHelpText());
                  });
                return;
              }
              if (tryMetadataDownloadFallback()) return;
              plexDebug('final-failure-no-fallback', baseCtx);
              showError(plexMissingMediaHelpText());
            });
          return;
        }
        if (!audio._plexRefetchPartTried && activeTrack && activeTrack.ratingKey != null) {
          audio._plexRefetchPartTried = true;
          plexDebug('retry-with-fresh-metadata-part-start', baseCtx);
          fetchFreshPartKeyForTrack(activeTrack)
            .then((freshPartKey) => {
              if (audio._plexStreamGen !== gen) return;
              const freshUrl = streamUrl(freshPartKey);
              if (!freshPartKey || !freshUrl || normalizedMediaUrl(freshUrl) === want) {
                throw new Error('no alternate part');
              }
              plexDebug('retry-with-fresh-metadata-part', Object.assign({}, baseCtx, {
                freshPartKey: freshPartKey,
                freshUrl: maskPlexToken(freshUrl)
              }));
              audio.removeAttribute('crossorigin');
              audio._plexOrbAttempt = 0;
              audio.addEventListener('error', onErr, { once: true });
              applySrc(freshUrl);
            })
            .catch((err) => {
              if (audio._plexStreamGen !== gen) return;
              plexDebug('retry-with-fresh-metadata-part-failed', Object.assign({}, baseCtx, {
                reason: String((err && err.message) || err || '')
              }));
              if (tryMetadataDownloadFallback()) return;
              plexDebug('final-failure-no-fallback', baseCtx);
              showError(plexMissingMediaHelpText());
            });
          return;
        }
        if (tryMetadataDownloadFallback()) return;
        plexDebug('final-failure-no-fallback', baseCtx);
        showError(plexMissingMediaHelpText());
        return;
      }
      if (!audio._plexOrbAttempt) {
        audio._plexOrbAttempt = 1;
        plexDebug('retry-with-cors-anonymous', baseCtx);
        audio.crossOrigin = 'anonymous';
        audio.addEventListener('error', onErr, { once: true });
        applySrc(url);
        return;
      }
      const started = audio._plexStreamLoadStartedAt || Date.now();
      const wait = Math.max(0, PLEX_STREAM_ORB_ERROR_MIN_MS - (Date.now() - started));
      if (audio._plexOrbErrorTimer) {
        clearTimeout(audio._plexOrbErrorTimer);
        audio._plexOrbErrorTimer = null;
      }
      plexDebug('cross-origin-failure', Object.assign({}, baseCtx, { delayMs: wait }));
      if (wait <= 0) tryShowPlexOrbError();
      else audio._plexOrbErrorTimer = setTimeout(tryShowPlexOrbError, wait);
    };

    audio.addEventListener('error', onErr, { once: true });
    applySrc(url);
    plexDebug('parts-source-applied', Object.assign({}, baseCtx, {
      appliedUrl: maskPlexToken(normalizedMediaUrl(audio.currentSrc || audio.src))
    }));
  }

  function thumbUrl(thumbPath) {
    if (!thumbPath) return '';
    const { server, token } = cfg();
    const path = thumbPath.startsWith('http') ? thumbPath : server + thumbPath;
    return path + (path.includes('?') ? '&' : '?') + 'X-Plex-Token=' + encodeURIComponent(token);
  }

  function metadataUrl(path) {
    if (!path) return '';
    const { server, token } = cfg();
    if (!server) return '';
    const full = path.startsWith('http') ? path : server + path;
    if (!token) return full;
    return full + (full.includes('?') ? '&' : '?') + 'X-Plex-Token=' + encodeURIComponent(token);
  }

  function albumMetadataPath(album) {
    if (!album) return '';
    if (album.parentRatingKey) return '/library/metadata/' + encodeURIComponent(String(album.parentRatingKey));
    if (album.parentKey) return album.parentKey;
    if (album.ratingKey) return '/library/metadata/' + encodeURIComponent(String(album.ratingKey));
    return album.key || '';
  }

  function artistMetadataPath(album) {
    if (!album) return '';
    if (album.grandparentRatingKey) return '/library/metadata/' + encodeURIComponent(String(album.grandparentRatingKey));
    if (album.grandparentKey) return album.grandparentKey;
    if (album.parentRatingKey) return '/library/metadata/' + encodeURIComponent(String(album.parentRatingKey));
    return album.parentKey || '';
  }

  function albumHash(ratingKey) {
    if (ratingKey == null || ratingKey === '') return '';
    return '#album/' + encodeURIComponent(String(ratingKey));
  }

  function artistHash(ratingKey) {
    if (ratingKey == null || ratingKey === '') return '';
    return '#artist/' + encodeURIComponent(String(ratingKey));
  }

  function directoryHash(directoryPath) {
    const p = String(directoryPath || '').trim();
    if (!p) return '';
    const segments = normalizePathSeparators(p).split('/').filter(Boolean);
    if (!segments.length) return '#directory/';
    return '#directory/' + segments.map((seg) => encodeURIComponent(seg)).join('/');
  }

  function directoryPathFromRouteSegment(routeSegment) {
    const raw = String(routeSegment || '').trim();
    if (!raw) return '';
    const parts = raw.split('/').filter(Boolean);
    if (!parts.length) return '';
    const decoded = parts.map((part) => {
      try {
        return String(decodeURIComponent(part)).replace(/^\/+|\/+$/g, '');
      } catch (_) {
        return String(part).replace(/^\/+|\/+$/g, '');
      }
    }).filter(Boolean);
    if (!decoded.length) return '';
    return '/' + decoded.join('/');
  }

  function radioHash(stationId) {
    if (stationId == null || stationId === '') return '#radio';
    return '#radio/' + encodeURIComponent(String(stationId));
  }

  function youtubeHash(videoId) {
    const id = String(videoId || '').trim();
    if (!id) return '#youtube';
    return '#youtube/' + encodeURIComponent(id);
  }

  function isRadioRouteHash(hash) {
    const h = (hash || '').trim();
    return /^#radio(?:\/[^/]*)?$/.test(h);
  }

  function isYouTubeRouteHash(hash) {
    const h = (hash || '').trim();
    return /^#youtube(?:\/[^/]*)?$/.test(h);
  }

  /** Deep-link to folder browser (#directory/volume3/Media/...) — must run applyHashRoute, not restorePlaybackFromState. */
  function isDirectoryRouteHash(hash) {
    const h = (hash || '').trim();
    return /^#directory\//.test(h);
  }

  function youtubeRouteSegment(hash) {
    const h = (hash || '').trim();
    const m = h.match(/^#youtube\/([^/]+)$/);
    if (!m) return '';
    try {
      const out = decodeURIComponent(m[1]);
      ytDebug('route-segment-decoded', { hash: h, segment: out });
      return out;
    } catch (_) {
      ytDebug('route-segment-decode-failed', { hash: h, raw: m[1] });
      return m[1];
    }
  }

  function makeMetaLinkHtml(label, href, kind) {
    if (!label) return '';
    if (!href) return escapeHtml(label);
    const isInternal = String(href).startsWith('#');
    const targetAttr = isInternal ? '' : ' target="_blank" rel="noopener"';
    return (
      '<a class="meta-link" data-meta-link="' +
      escapeHtml(kind || 'meta') +
      '" href="' +
      escapeHtml(href) +
      '"' + targetAttr + '>' +
      escapeHtml(label) +
      '</a>'
    );
  }

  function setNowPlayingWikiLink(id, url) {
    const el = $(id);
    if (!el) return;
    if (url) {
      el.href = url;
      el.style.display = 'inline-flex';
    } else {
      el.href = '#';
      el.style.display = 'none';
    }
  }

  function clearNowPlayingWikiLinks() {
    setNowPlayingWikiLink('npTitleWiki', '');
    setNowPlayingWikiLink('npAlbumWiki', '');
    setNowPlayingWikiLink('npSubWiki', '');
  }

  function wikipediaSearchQueries(label, kind, context) {
    const cleanLabel = String(label || '').trim();
    if (!cleanLabel) return [];
    const artist = String(context && context.artist || '').trim();
    const raw = [];
    if (kind === 'song') {
      if (artist) raw.push(cleanLabel + ' ' + artist + ' song');
      raw.push(cleanLabel + ' song');
      raw.push(cleanLabel);
    } else if (kind === 'album') {
      if (artist) raw.push(cleanLabel + ' ' + artist + ' album');
      raw.push(cleanLabel + ' album');
      raw.push(cleanLabel);
    } else if (kind === 'artist') {
      raw.push(cleanLabel + ' musician');
      raw.push(cleanLabel);
    } else {
      raw.push(cleanLabel);
    }
    const out = [];
    const seen = new Set();
    raw.forEach(function (q) {
      const key = q.toLowerCase();
      if (!key || seen.has(key)) return;
      seen.add(key);
      out.push(q);
    });
    return out;
  }

  async function wikipediaTopHitUrl(query) {
    const q = String(query || '').trim();
    if (!q) return '';
    try {
      const params = new URLSearchParams({
        action: 'query',
        list: 'search',
        srlimit: '1',
        srsearch: q,
        format: 'json',
        origin: '*'
      });
      const res = await fetch(WIKIPEDIA_API_URL + '?' + params.toString());
      if (!res.ok) return '';
      const data = await res.json();
      const hit = data && data.query && Array.isArray(data.query.search) ? data.query.search[0] : null;
      const title = hit && hit.title ? String(hit.title).trim() : '';
      if (!title) return '';
      return 'https://en.wikipedia.org/wiki/' + encodeURIComponent(title.replace(/\s+/g, '_'));
    } catch (_) {
      // Fall through to REST search fallback below.
    }
    try {
      const params = new URLSearchParams({
        q: q,
        limit: '1'
      });
      const res = await fetch(WIKIPEDIA_REST_SEARCH_URL + '?' + params.toString());
      if (!res.ok) return '';
      const data = await res.json();
      const first = data && Array.isArray(data.pages) ? data.pages[0] : null;
      const key = first && (first.key || first.title) ? String(first.key || first.title).trim() : '';
      if (!key) return '';
      return 'https://en.wikipedia.org/wiki/' + encodeURIComponent(key.replace(/\s+/g, '_'));
    } catch (_) {
      return '';
    }
  }

  async function wikipediaUrlFor(label, kind, context) {
    const cleanLabel = String(label || '').trim();
    if (!cleanLabel) return '';
    const artist = String(context && context.artist || '').trim().toLowerCase();
    const cacheKey = (kind || 'meta') + '|' + cleanLabel.toLowerCase() + '|' + artist;
    if (wikiLookupCache.has(cacheKey)) return wikiLookupCache.get(cacheKey);
    if (wikiLookupInflight.has(cacheKey)) return wikiLookupInflight.get(cacheKey);
    const lookup = (async function () {
      const queries = wikipediaSearchQueries(cleanLabel, kind, context);
      for (let i = 0; i < queries.length; i += 1) {
        const url = await wikipediaTopHitUrl(queries[i]);
        if (url) {
          wikiLookupCache.set(cacheKey, url);
          return url;
        }
      }
      wikiLookupCache.set(cacheKey, '');
      return '';
    })();
    wikiLookupInflight.set(cacheKey, lookup);
    try {
      return await lookup;
    } finally {
      wikiLookupInflight.delete(cacheKey);
    }
  }

  async function refreshNowPlayingWikipediaLinks(meta) {
    const requestId = ++nowPlayingWikiRequestId;
    clearNowPlayingWikiLinks();
    const song = String(meta && meta.song || '').trim();
    const album = String(meta && meta.album || '').trim();
    const artist = String(meta && meta.artist || '').trim();
    if (!song && !album && !artist) return;
    const ctx = { artist: artist };
    const urls = await Promise.all([
      wikipediaUrlFor(song, 'song', ctx),
      wikipediaUrlFor(album, 'album', ctx),
      wikipediaUrlFor(artist, 'artist', ctx)
    ]);
    if (requestId !== nowPlayingWikiRequestId) return;
    setNowPlayingWikiLink('npTitleWiki', urls[0]);
    setNowPlayingWikiLink('npAlbumWiki', urls[1]);
    setNowPlayingWikiLink('npSubWiki', urls[2]);
  }

  function starIconHtml(starred) {
    const star =
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5" fill="' +
      (starred ? 'currentColor' : 'none') +
      '" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/></svg>';
    return star;
  }

  function albumCardPlayIconHtml() {
    return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z" fill="currentColor"></path></svg>';
  }

  function fallbackThumbDataUrl() {
    return (
      'data:image/svg+xml,' +
      encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" fill="%232a2a30"><rect width="100%" height="100%"/></svg>')
    );
  }

  function albumDetailArtUrl(album, tracks) {
    const firstTrack = tracks && tracks[0] ? tracks[0] : null;
    const thumbPath =
      (album && (album.thumb || album.parentThumb || album.grandparentThumb)) ||
      (firstTrack && (firstTrack.parentThumb || firstTrack.thumb || firstTrack.grandparentThumb)) ||
      '';
    return thumbUrl(thumbPath) || fallbackThumbDataUrl();
  }

  /** Prefer the playing track's Plex thumbs so VA / compilation rows match the track, not a stale album node. */
  function nowPlayingArtUrl(track, album, tracks) {
    if (track) {
      const fromTrack = thumbUrl(track.parentThumb || track.thumb || track.grandparentThumb || '');
      if (fromTrack) return fromTrack;
    }
    return albumDetailArtUrl(album || {}, tracks) || fallbackThumbDataUrl();
  }

  async function plexFetch(path) {
    const { server, token } = cfg();
    if (!server || !token) throw new Error('Configure token and select a Plex server in Settings.');
    const url = server + path + (path.includes('?') ? '&' : '?') + 'X-Plex-Token=' + encodeURIComponent(token);
    const res = await fetch(url, {
      headers: { Accept: 'application/json', 'X-Plex-Token': token }
    });
    if (!res.ok) throw new Error(res.status + ' ' + res.statusText);
    return res.json();
  }

  async function plexFetchAtServer(path, server, token, timeoutMs) {
    const base = String(server || '').replace(/\/$/, '');
    const tk = String(token || '');
    if (!base || !tk) throw new Error('Configure token and select a Plex server in Settings.');
    const url = base + path + (path.includes('?') ? '&' : '?') + 'X-Plex-Token=' + encodeURIComponent(tk);
    const ms = Number(timeoutMs);
    const useTimeout = ms > 0 && typeof AbortController === 'function';
    const ctrl = useTimeout ? new AbortController() : null;
    const timer = useTimeout ? setTimeout(() => ctrl.abort(), ms) : 0;
    try {
      const res = await fetch(url, {
        headers: { Accept: 'application/json', 'X-Plex-Token': tk },
        signal: ctrl ? ctrl.signal : undefined
      });
      if (!res.ok) throw new Error(res.status + ' ' + res.statusText);
      return res.json();
    } catch (err) {
      if (err && err.name === 'AbortError') {
        throw new Error('Plex request timed out');
      }
      throw err;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  async function probeSameOriginBackend() {
    const healthUrl = '/api/health';
    const ctrl = typeof AbortController === 'function' ? new AbortController() : null;
    const timer = ctrl ? setTimeout(() => ctrl.abort(), 2500) : null;
    try {
      const res = await fetch(healthUrl, {
        headers: { Accept: 'application/json' },
        signal: ctrl ? ctrl.signal : undefined
      });
      if (!res.ok) {
        sameOriginPlexProxyReady = false;
        setSettingsStatus('Backend health check unavailable (' + res.status + '). Running standalone mode.', '');
        return false;
      }
      const data = await res.json();
      if (!data || !data.ok) {
        sameOriginPlexProxyReady = false;
        setSettingsStatus('Backend responded but is not ready. Running standalone mode.', '');
        return false;
      }
      sameOriginPlexProxyReady = !!data.plexProxyReady;
      const status = [];
      status.push('Same-origin backend detected');
      if (data.ownerConfigured) status.push('friend policy ready');
      if (data.wrapsterPlexReady) status.push('Wrapster Plex ready');
      else if (data.plexConfigured) status.push('plex upstream configured');
      setSettingsStatus(status.join(' - '), 'ok');
      return true;
    } catch (err) {
      sameOriginPlexProxyReady = false;
      if (err && (err.name === 'AbortError' || /Failed to fetch|NetworkError|Load failed/i.test(String(err.message || err)))) {
        setSettingsStatus('No backend at /api/health. Running standalone mode.', '');
        return false;
      }
      return false;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  async function plexFetchWithTimeout(path, timeoutMs) {
    const ms = Number(timeoutMs);
    if (!(ms > 0) || typeof AbortController !== 'function') {
      return plexFetch(path);
    }
    const { server, token } = cfg();
    if (!server || !token) throw new Error('Configure token and select a Plex server in Settings.');
    const url = server + path + (path.includes('?') ? '&' : '?') + 'X-Plex-Token=' + encodeURIComponent(token);
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), ms);
    try {
      const res = await fetch(url, {
        headers: { Accept: 'application/json', 'X-Plex-Token': token },
        signal: ctrl.signal
      });
      if (!res.ok) throw new Error(res.status + ' ' + res.statusText);
      return res.json();
    } catch (err) {
      if (err && err.name === 'AbortError') {
        throw new Error('Plex request timed out');
      }
      throw err;
    } finally {
      clearTimeout(timer);
    }
  }

  function getPlexClientId() {
    try {
      const stored = sessionStorage.getItem('plexLoginPinClientId');
      if (stored) return stored;
    } catch (_) {}
    return PLEX_CLIENT_ID;
  }

  function plexHeaders(extra) {
    const base = {
      Accept: 'application/json',
      'X-Plex-Product': PLEX_PRODUCT,
      'X-Plex-Client-Identifier': getPlexClientId()
    };
    return Object.assign(base, extra || {});
  }

  async function plexSignInGetToken() {
    const statusEl = $('plexLoginStatus');
    const btn = $('btnPlexLogin');
    statusEl.textContent = 'Creating Plex login session...';
    btn.disabled = true;
    try {
      const pinRes = await fetch('https://plex.tv/api/v2/pins?strong=true', {
        method: 'POST',
        headers: plexHeaders()
      });
      if (!pinRes.ok) throw new Error('Unable to start Plex login (' + pinRes.status + ').');
      const pin = await pinRes.json();
      if (!pin || !pin.id || !pin.code) throw new Error('Unexpected response from Plex login.');

      const authUrl =
        'https://app.plex.tv/auth#?' +
        'clientID=' + encodeURIComponent(PLEX_CLIENT_ID) +
        '&code=' + encodeURIComponent(pin.code) +
        '&context%5Bdevice%5D%5Bproduct%5D=' + encodeURIComponent(PLEX_PRODUCT);

      let popup = window.open(authUrl, 'plex-login', 'popup=yes,width=550,height=700');
      // Fallback for environments where app.plex.tv auth is blocked/redirected.
      if (!popup) popup = window.open('https://plex.tv/link', 'plex-login', 'popup=yes,width=550,height=700');
      if (!popup) {
        // Safari iOS often blocks window.open; open in same tab and resume polling when user returns.
        try {
          sessionStorage.setItem('plexLoginPinId', String(pin.id));
          sessionStorage.setItem('plexLoginPinClientId', PLEX_CLIENT_ID);
          sessionStorage.setItem('plexLoginPinExpiry', String(Date.now() + 10 * 60 * 1000));
        } catch (_) {}
        statusEl.textContent = 'Opening Plex login… Return here after signing in.';
        window.location.href = authUrl;
        return;
      }

      statusEl.textContent = 'Complete login in the popup...';

      const start = Date.now();
      const timeoutMs = 3 * 60 * 1000;
      while (Date.now() - start < timeoutMs) {
        await new Promise((r) => setTimeout(r, 2000));
        const checkRes = await fetch('https://plex.tv/api/v2/pins/' + encodeURIComponent(pin.id), {
          headers: plexHeaders()
        });
        if (!checkRes.ok) continue;
        const check = await checkRes.json();
        if (check && check.authToken) {
          $('inToken').value = check.authToken;
          localStorage.setItem(LS.token, check.authToken);
          updatePlexSettingsButtonsVisibility();
          statusEl.textContent = 'Token received from Plex.';
          setServerDiscoveryStatus('Token received. Detecting server/IP...');
          try {
            await detectServersFromToken();
          } catch (detectErr) {
            setServerDiscoveryStatus(
              'Token OK, but server auto-detect failed: ' + (detectErr.message || String(detectErr)),
              'error'
            );
          }
          try { popup.close(); } catch (_) {}
          return;
        }
        if (popup.closed) {
          statusEl.textContent = 'Finishing login...';
        }
      }
      throw new Error('Login timed out. Try again.');
    } finally {
      btn.disabled = false;
    }
  }

  async function resumePlexPinPoll(pinId) {
    const statusEl = $('plexLoginStatus');
    const btn = $('btnPlexLogin');
    btn.disabled = true;
    try {
      statusEl.textContent = 'Checking for Plex authorization…';
      const start = Date.now();
      const timeoutMs = 3 * 60 * 1000;
      while (Date.now() - start < timeoutMs) {
        await new Promise((r) => setTimeout(r, 2000));
        const checkRes = await fetch('https://plex.tv/api/v2/pins/' + encodeURIComponent(pinId), {
          headers: plexHeaders()
        });
        if (!checkRes.ok) continue;
        const check = await checkRes.json();
        if (check && check.authToken) {
          try {
            sessionStorage.removeItem('plexLoginPinId');
            sessionStorage.removeItem('plexLoginPinClientId');
            sessionStorage.removeItem('plexLoginPinExpiry');
          } catch (_) {}
          $('inToken').value = check.authToken;
          localStorage.setItem(LS.token, check.authToken);
          updatePlexSettingsButtonsVisibility();
          statusEl.textContent = 'Token received from Plex.';
          setServerDiscoveryStatus('Token received. Detecting server/IP...');
          try {
            await detectServersFromToken();
          } catch (detectErr) {
            setServerDiscoveryStatus(
              'Token OK, but server auto-detect failed: ' + (detectErr.message || String(detectErr)),
              'error'
            );
          }
          return;
        }
      }
      statusEl.textContent = 'Login timed out. Try Sign in with Plex again.';
    } finally {
      btn.disabled = false;
      try {
        sessionStorage.removeItem('plexLoginPinId');
        sessionStorage.removeItem('plexLoginPinClientId');
        sessionStorage.removeItem('plexLoginPinExpiry');
      } catch (_) {}
    }
  }

  let albumStart = 0;
  /** Plex audiobook (and some) sections list books via /all?type=9 instead of /albums */
  let sectionAlbumListMode = { sectionKey: '', mode: 'albums' };
  /** Albums per Plex request — larger = fewer round-trips for big libraries */
  const PAGE = 120;
  let currentAlbum = null;
  let currentArtistKey = null;
  let currentTracks = [];
  let sameOriginPlexProxyReady = false;
  let searchQuery = '';
  let isLoadingAlbums = false;
  let hasMoreAlbums = false;
  let albumTotalCount = 0;

  function plexMediaContainerTotal(mc) {
    if (!mc || typeof mc !== 'object') return null;
    const raw = mc.totalSize != null ? mc.totalSize : mc.TotalSize != null ? mc.TotalSize : null;
    if (raw == null) return null;
    const n = Number(raw);
    return Number.isFinite(n) && n >= 0 ? n : null;
  }

  /** Plex often omits totalSize on page 2+; keep the real library total for counts and “load more”. */
  function resolveAlbumListTotal(mediaContainer, metaLength, append) {
    const fromApi = plexMediaContainerTotal(mediaContainer);
    if (fromApi != null) return fromApi;
    if (append && albumTotalCount > 0) return albumTotalCount;
    const im = loadSearchIndexMeta();
    const sk = activeSectionKey();
    const saved = Number(im.totalCount);
    if (Number.isFinite(saved) && saved > 0 && String(im.sectionKey || '') === String(sk || '')) {
      return saved;
    }
    if (!append && albumTotalCount > 0) return albumTotalCount;
    return metaLength || 0;
  }

  /** Cached pages may store a stale total; never show fewer than we already know from meta / index. */
  function mergeAlbumListTotalHint(cachedTotal, itemCount, sectionKey) {
    let t = Number(cachedTotal);
    if (!Number.isFinite(t) || t < 0) t = 0;
    const im = loadSearchIndexMeta();
    const metaHint =
      im && String(im.sectionKey || '') === String(sectionKey || '') ? Number(im.totalCount) : 0;
    const hints = [t, itemCount, albumTotalCount, Number.isFinite(metaHint) ? metaHint : 0];
    return Math.max.apply(null, hints);
  }

  /** Cached Plex section track count (type=10) for library-wide shuffle */
  let libraryTrackTotalCache = { sectionKey: '', total: 0 };
  /** Recent track ratingKeys for song shuffle — reduces immediate repeats across a huge library */
  let songShuffleRecentKeys = [];
  const SONG_SHUFFLE_RECENT_CAP = 200;

  function randomUniformIntBelow(maxExclusive) {
    const n = Math.floor(Number(maxExclusive));
    if (!(n > 0)) return 0;
    if (n === 1) return 0;
    try {
      const buf = new Uint32Array(1);
      const limit = Math.floor(0x100000000 / n) * n;
      let x;
      do {
        crypto.getRandomValues(buf);
        x = buf[0];
      } while (x >= limit);
      return x % n;
    } catch (_) {
      return Math.floor(Math.random() * n);
    }
  }

  function rememberSongShufflePick(ratingKey) {
    const k = String(ratingKey || '');
    if (!k) return;
    const i = songShuffleRecentKeys.indexOf(k);
    if (i !== -1) songShuffleRecentKeys.splice(i, 1);
    songShuffleRecentKeys.unshift(k);
    if (songShuffleRecentKeys.length > SONG_SHUFFLE_RECENT_CAP) songShuffleRecentKeys.pop();
  }

  function isSongShuffleRecentlyPlayed(ratingKey) {
    return songShuffleRecentKeys.indexOf(String(ratingKey || '')) !== -1;
  }
  let autoLoadAlbumsTimer = 0;
  let filterVersion = 0;
  let filterDebounceTimer = 0;
  let hubSearchFallbackTimer = 0;
  let trackPrefetchTried = new Set();
  let trackPrefetchTriedForQuery = '';
  let navView = 'albums';
  const playlistCsvState = {
    loaded: true,
    loading: false,
    error: '',
    playlists: [],
    selectedId: '',
    matchesById: {},
    searchCache: new Map()
  };
  let radioStations = [];
  let radioSections = [];
  let currentRadioIdx = -1;
  let isRadioMode = false;
  let youtubeSelection = null;
  const albumTrackSearchCache = new Map();
  const albumTrackFetchCache = new Map();
  const albumSearchIndexCache = new Map();
  const albumFolderNameCache = new Map();
  const albumFolderNameFetchCache = new Map();
  const trackLookup = new Map();
  const albumLookup = new Map();
  const SEARCH_RECORD_CACHE_TTL_MS = 12000;
  let allSearchRecordsCache = null;
  let allSearchRecordsCacheLoadedAt = 0;
  let settingsStatusFadeTimer = 0;
  let settingsStatusClearTimer = 0;
  let errorClearTimer = 0;
  let plexConnectionWorking = false;
  let activeSearchIndexSection = '';
  /** Plex sections from last loadSections — used for multi-library hub search. */
  let plexSectionListCache = [];
  let plexSectionNameByKey = {};
  let plexSectionMetaByKey = {};
  let unifiedSearchTimer = 0;
  let unifiedSearchSeq = 0;
  let playbackMode = PLAYBACK_MODE_OFF;

  function ytDebug(event, data) {
    if (!/[?&]youtubeDebug=1(?:&|$)/.test(location.search)) return;
    try {
      const payload = data && typeof data === 'object' ? data : { value: data };
      console.log('[YouTubeDebug]', event, payload || {});
    } catch (_) {}
  }

  function audioDebugSnapshot(audio) {
    const a = audio || $('audio');
    if (!a) return {};
    const err = a.error;
    return {
      currentSrc: String(a.currentSrc || ''),
      networkState: a.networkState,
      readyState: a.readyState,
      paused: !!a.paused,
      ended: !!a.ended,
      currentTime: Number(a.currentTime || 0),
      duration: Number.isFinite(a.duration) ? Number(a.duration) : null,
      mediaErrorCode: err ? err.code : null,
      mediaErrorMessage: err && err.message ? String(err.message) : ''
    };
  }

  function showError(msg) {
    const el = $('error');
    if (errorClearTimer) {
      clearTimeout(errorClearTimer);
      errorClearTimer = 0;
    }
    el.textContent = msg;
    el.style.display = msg ? 'block' : 'none';
    if (msg) {
      errorClearTimer = setTimeout(() => {
        el.textContent = '';
        el.style.display = 'none';
        errorClearTimer = 0;
      }, 4200);
    }
  }

  function showCors(b) {
    $('corsHint').style.display = b ? 'block' : 'none';
  }

  function setSettingsStatus(msg, kind) {
    const el = $('settingsStatus');
    if (settingsStatusFadeTimer) clearTimeout(settingsStatusFadeTimer);
    if (settingsStatusClearTimer) clearTimeout(settingsStatusClearTimer);

    const hasMsg = msg != null && String(msg).trim() !== '';
    el.textContent = msg || '';
    el.style.display = hasMsg ? 'block' : 'none';
    el.classList.remove('fade-out');
    el.classList.remove('ok', 'error');
    if (kind === 'ok') el.classList.add('ok');
    if (kind === 'error') el.classList.add('error');

    // Auto-fade non-error status messages so they don't linger.
    if (hasMsg && kind !== 'error') {
      const fadeDelay = kind === 'ok' ? 2400 : 1800;
      settingsStatusFadeTimer = setTimeout(() => {
        el.classList.add('fade-out');
        settingsStatusClearTimer = setTimeout(() => {
          el.textContent = '';
          el.style.display = 'none';
          el.classList.remove('fade-out');
        }, 650);
      }, fadeDelay);
    }
  }

  function openSettingsModal(opts) {
    const options = opts && typeof opts === 'object' ? opts : {};
    $('settingsModal').classList.add('open');
    syncModalBodyScrollLock();
    const c = cfg();
    $('inToken').value = c.token;
    updatePlexLoginButtonVisibility();
    updatePlexSettingsButtonsVisibility();
    setServerDiscoveryStatus('');
    if (c.token) autoDetectServersFromToken().catch(() => {});
    var card = $('settingsModal').querySelector('.modal-card');
    if (card) card.scrollTop = 0;
    setTimeout(function () {
      var inp = options.focusNostr ? $('inNsec') : $('inToken');
      if (inp) inp.focus();
    }, 150);
  }

  function closeSettingsModal() {
    $('settingsModal').classList.remove('open');
    syncModalBodyScrollLock();
  }

  function syncModalBodyScrollLock() {
    const any = $('settingsModal').classList.contains('open') || $('artworkModal').classList.contains('open');
    document.body.style.overflow = any ? 'hidden' : '';
  }

  function openArtworkModalFromSrc(src, alt) {
    const modal = $('artworkModal');
    const modalImg = $('artworkModalImg');
    const imageSrc = String(src || '').trim();
    if (!modal || !modalImg || !imageSrc) return;
    modalImg.src = imageSrc;
    modalImg.alt = String(alt || 'Expanded artwork').trim() || 'Expanded artwork';
    modal.classList.add('open');
    modal.setAttribute('aria-hidden', 'false');
    syncModalBodyScrollLock();
  }

  function openArtworkModal() {
    const npArt = $('npArt');
    if (!npArt || !npArt.src) return;
    openArtworkModalFromSrc(npArt.src, npArt.alt || 'Expanded now playing artwork');
  }

  function closeArtworkModal() {
    const modal = $('artworkModal');
    if (!modal) return;
    modal.classList.remove('open');
    modal.setAttribute('aria-hidden', 'true');
    syncModalBodyScrollLock();
  }

  function setServerDiscoveryStatus(msg, kind) {
    const el = $('serverDiscoveryStatus');
    el.textContent = msg || '';
    el.classList.remove('ok', 'error');
    if (kind === 'ok') el.classList.add('ok');
    if (kind === 'error') el.classList.add('error');
  }

  function updatePlexLoginButtonVisibility() {
    const btn = $('btnPlexLogin');
    if (!btn) return;
    const c = cfg();
    const hasValidPlexConnection = !!(plexConnectionWorking && c.server && c.token);
    const shouldShow = !hasValidPlexConnection;
    const wrap = btn.closest('.inline-actions');
    if (wrap) wrap.style.display = shouldShow ? '' : 'none';
    else btn.style.display = shouldShow ? '' : 'none';
  }

  function updatePlexSettingsButtonsVisibility() {
    const deleteBtn = $('btnDeletePlexSettings');
    const c = cfg();
    const hasStoredPlexSettings = !!(c.server || c.token || c.section);
    const display = hasStoredPlexSettings ? '' : 'none';
    if (deleteBtn) deleteBtn.style.display = display;
  }

  function disconnectPlexSession() {
    localStorage.removeItem(LS.server);
    localStorage.removeItem(LS.token);
    localStorage.removeItem(LS.section);
    localStorage.removeItem(LS.serverCandidatesCache);
    const tokenInput = $('inToken');
    if (tokenInput) tokenInput.value = '';
    const sectionInput = $('inSection');
    if (sectionInput) sectionInput.value = '';
    const loginStatus = $('plexLoginStatus');
    if (loginStatus) loginStatus.textContent = '';
    renderServerCandidates([]);
    setServerDiscoveryStatus('');
    plexConnectionWorking = false;
    updatePlexLoginButtonVisibility();
    updatePlexSettingsButtonsVisibility();
    plexSectionListCache = [];
    plexSectionNameByKey = {};
    const nav = $('plexLibraryLinks');
    if (nav) nav.innerHTML = '<p class="sidebar-plex-note">Not connected</p>';
    refreshMobileLibraryPicker();
    if (!isRadioRouteHash(location.hash)) location.hash = '#radio';
    applyHashRoute();
    setSettingsStatus('Disconnected from Plex. Token removed.', 'ok');
  }

  function renderServerCandidates(candidates, selectedServer) {
    const wrap = $('serverCandidates');
    if (!candidates || !candidates.length) {
      wrap.style.display = 'none';
      wrap.innerHTML = '';
      return;
    }
    const normalizedSelectedServer = String(selectedServer || '').replace(/\/$/, '');
    wrap.style.display = 'flex';
    wrap.innerHTML = '';
    candidates.forEach((c) => {
      const b = document.createElement('button');
      b.type = 'button';
      const candidateServer = String(c.uri || '').replace(/\/$/, '');
      const isSelected = normalizedSelectedServer && candidateServer === normalizedSelectedServer;
      b.className = 'server-candidate' + (isSelected ? ' selected' : '');
      const localLabel = c.local ? 'local' : 'remote';
      b.innerHTML =
        '<div class="server-candidate-head"><div>' +
        escapeHtml(c.deviceName) +
        ' - ' +
        escapeHtml(c.address || c.uri) +
        ' (' +
        localLabel +
        ')</div>' +
        (isSelected ? '<span class="server-selected-chip">Selected</span>' : '') +
        '</div>' +
        '<div class="sub">' +
        escapeHtml(c.uri) +
        '</div>';
      b.onclick = () => {
        const chosenServer = (c.uri || '').replace(/\/$/, '');
        localStorage.setItem(LS.server, chosenServer);
        // Keep account token unchanged; per-device tokens can fail on plex.tv discovery.
        updatePlexSettingsButtonsVisibility();
        renderServerCandidates(candidates, chosenServer);
        setServerDiscoveryStatus(
          'Using ' + (c.address || c.uri) + (c.local ? ' (local).' : '.'),
          'ok'
        );
      };
      wrap.appendChild(b);
    });
  }

  function renderCurrentServerPlaceholderDuringDetect() {
    const currentServer = String((cfg().server || '')).trim().replace(/\/$/, '');
    if (!currentServer) return;
    let host = '';
    try {
      host = new URL(currentServer).host || '';
    } catch (_) {}
    renderServerCandidates(
      [
        {
          deviceName: 'Current server',
          deviceToken: String(cfg().token || ''),
          uri: currentServer,
          address: host,
          local: false,
          relay: false
        }
      ],
      currentServer
    );
  }

  function scoreConnection(c) {
    let s = 0;
    if (c.local) s += 4;
    if ((c.uri || '').startsWith('http://')) s += 1;
    if (c.relay) s -= 2;
    return s;
  }

  function normalizePlexServerCandidates(raw) {
    if (!Array.isArray(raw)) return [];
    const out = [];
    for (let i = 0; i < raw.length; i++) {
      const item = raw[i];
      if (!item || typeof item !== 'object') continue;
      const uri = String(item.uri || '').trim();
      if (!uri) continue;
      out.push({
        deviceName: String(item.deviceName || 'Plex server'),
        deviceToken: String(item.deviceToken || ''),
        uri: uri,
        address: String(item.address || ''),
        local: !!item.local,
        relay: !!item.relay
      });
    }
    out.sort((a, b) => scoreConnection(b) - scoreConnection(a));
    return out;
  }

  function cachePlexServerCandidates(token, candidates) {
    if (!token || !Array.isArray(candidates) || !candidates.length) return;
    try {
      localStorage.setItem(
        LS.serverCandidatesCache,
        JSON.stringify({
          token: String(token),
          savedAt: Date.now(),
          candidates: normalizePlexServerCandidates(candidates)
        })
      );
    } catch (_) {}
  }

  function getCachedPlexServerCandidates(token) {
    if (!token) return null;
    try {
      const raw = localStorage.getItem(LS.serverCandidatesCache);
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      if (!parsed || typeof parsed !== 'object') return null;
      if (String(parsed.token || '') !== String(token)) return null;
      const savedAt = Number(parsed.savedAt || 0);
      if (!Number.isFinite(savedAt) || savedAt <= 0) return null;
      if (Date.now() - savedAt > PLEX_SERVER_CACHE_TTL_MS) return null;
      const candidates = normalizePlexServerCandidates(parsed.candidates);
      if (!candidates.length) return null;
      return candidates;
    } catch (_) {
      return null;
    }
  }

  function applyDetectedCandidates(candidates) {
    if (!Array.isArray(candidates) || !candidates.length) {
      throw new Error('No Plex servers found for this token.');
    }
    const currentServer = String((cfg().server || '')).trim().replace(/\/$/, '');
    const chosen = candidates.find((c) => String(c.uri || '').replace(/\/$/, '') === currentServer) || candidates[0];
    const chosenServer = (chosen.uri || '').replace(/\/$/, '');
    localStorage.setItem(LS.server, chosenServer);
    // Keep account token unchanged; per-device tokens can fail on plex.tv discovery.
    renderServerCandidates(candidates, chosenServer);
    setServerDiscoveryStatus(
      'Detected ' +
        candidates.length +
        ' server connection(s). Selected ' +
        (chosen.address || chosen.uri) +
        (chosen.local ? ' (local).' : '.'),
      'ok'
    );
  }

  function parsePlexResourcesCandidates(xml) {
    const doc = new DOMParser().parseFromString(xml, 'application/xml');
    if (doc.querySelector('parsererror')) throw new Error('Unexpected Plex resources response.');
    const devices = Array.from(doc.querySelectorAll('Device')).filter((d) =>
      String(d.getAttribute('provides') || '').toLowerCase().includes('server')
    );
    const candidates = [];
    devices.forEach((d) => {
      const deviceName = d.getAttribute('name') || 'Plex server';
      const deviceToken = d.getAttribute('accessToken') || '';
      const conns = Array.from(d.querySelectorAll('Connection'));
      conns.forEach((c) => {
        const uri = c.getAttribute('uri') || '';
        if (!uri) return;
        candidates.push({
          deviceName,
          deviceToken,
          uri,
          address: c.getAttribute('address') || '',
          local: c.getAttribute('local') === '1',
          relay: c.getAttribute('relay') === '1'
        });
      });
    });
    return normalizePlexServerCandidates(candidates);
  }

  function parsePlexResourcesCandidatesJson(payload) {
    const container = payload && payload.MediaContainer ? payload.MediaContainer : payload;
    const devices = Array.isArray(container && container.Device) ? container.Device : [];
    const candidates = [];
    devices.forEach((d) => {
      const provides = String((d && d.provides) || '').toLowerCase();
      if (!provides.includes('server')) return;
      const deviceName = String((d && d.name) || 'Plex server');
      const deviceToken = String((d && d.accessToken) || '');
      const conns = Array.isArray(d && d.Connection) ? d.Connection : [];
      conns.forEach((c) => {
        const uri = String((c && c.uri) || '').trim();
        if (!uri) return;
        candidates.push({
          deviceName,
          deviceToken,
          uri,
          address: String((c && c.address) || ''),
          local: String((c && c.local) || '') === '1' || c.local === true,
          relay: String((c && c.relay) || '') === '1' || c.relay === true
        });
      });
    });
    return normalizePlexServerCandidates(candidates);
  }

  function parsePlexResourcesCandidatesAny(rawText, contentType) {
    const type = String(contentType || '').toLowerCase();
    const text = String(rawText || '').trim();
    if (!text) return [];
    if (type.includes('json') || text[0] === '{' || text[0] === '[') {
      try {
        return parsePlexResourcesCandidatesJson(JSON.parse(text));
      } catch (_) {}
    }
    return parsePlexResourcesCandidates(text);
  }

  async function fetchPlexResourcesCandidatesWithFallback(token) {
    const tokenParam = encodeURIComponent(String(token || ''));
    const attempts = [
      {
        url: 'https://plex.tv/api/resources?includeHttps=1&includeRelay=1',
        headers: plexHeaders({ 'X-Plex-Token': token })
      },
      {
        // Some environments accept token via query string more reliably.
        url: 'https://plex.tv/api/resources?includeHttps=1&includeRelay=1&X-Plex-Token=' + tokenParam,
        headers: plexHeaders()
      },
      {
        // Legacy Plex resources endpoint fallback.
        url: 'https://plex.tv/pms/resources.xml?includeHttps=1&includeRelay=1&X-Plex-Token=' + tokenParam,
        headers: plexHeaders()
      }
    ];
    const errs = [];
    for (let i = 0; i < attempts.length; i++) {
      const req = attempts[i];
      try {
        const res = await fetch(req.url, { headers: req.headers });
        if (!res.ok) {
          errs.push('Attempt ' + (i + 1) + ': HTTP ' + res.status);
          continue;
        }
        const body = await res.text();
        const candidates = parsePlexResourcesCandidatesAny(body, res.headers.get('content-type'));
        if (Array.isArray(candidates) && candidates.length) return candidates;
        errs.push('Attempt ' + (i + 1) + ': no server connections in response');
      } catch (e) {
        errs.push('Attempt ' + (i + 1) + ': ' + ((e && e.message) || String(e)));
      }
    }
    throw new Error('Could not query Plex resources. ' + errs.join('; '));
  }

  async function detectServersFromToken() {
    const token = $('inToken').value.trim();
    if (!token) throw new Error('Enter token first.');
    const cached = getCachedPlexServerCandidates(token);
    if (cached && cached.length) {
      applyDetectedCandidates(cached);
      setServerDiscoveryStatus(
        'Loaded ' +
          cached.length +
          ' cached server connection(s).',
        'ok'
      );
      return cached;
    }
    renderCurrentServerPlaceholderDuringDetect();
    if (String((cfg().server || '')).trim()) {
      setServerDiscoveryStatus('Detecting Plex servers from token... Showing current server meanwhile.');
    } else {
      setServerDiscoveryStatus('Detecting Plex servers from token...');
    }
    const candidates = await fetchPlexResourcesCandidatesWithFallback(token);
    applyDetectedCandidates(candidates);
    cachePlexServerCandidates(token, candidates);
    return candidates;
  }

  async function autoDetectServersFromToken() {
    const token = $('inToken').value.trim();
    if (!token) {
      renderServerCandidates([]);
      setServerDiscoveryStatus('');
      return [];
    }
    return detectServersFromToken();
  }

  async function fetchSectionsViaServerFallback(token, failedServer) {
    if (!token) return null;
    let candidates = [];
    try {
      candidates = await detectServersFromToken();
    } catch (_) {
      return null;
    }
    if (!Array.isArray(candidates) || !candidates.length) return null;
    const failed = String(failedServer || '').replace(/\/$/, '');
    for (let i = 0; i < candidates.length; i++) {
      const c = candidates[i] || {};
      const candidateServer = String(c.uri || '').replace(/\/$/, '');
      if (!candidateServer || candidateServer === failed) continue;
      const candidateToken = String(c.deviceToken || token || '');
      try {
        const data = await plexFetchAtServer('/library/sections', candidateServer, candidateToken, 3500);
        return { data, server: candidateServer, token: candidateToken };
      } catch (_) {}
    }
    return null;
  }

  function looksLikeMusicLibrary(d) {
    const type = String(d.type || '').toLowerCase();
    const title = String(d.title || '').toLowerCase();
    const scanner = String(d.scanner || '').toLowerCase();
    const agent = String(d.agent || '').toLowerCase();
    return (
      type === 'artist' ||
      type === 'album' ||
      type === 'music' ||
      type === 'audiobook' ||
      scanner.includes('music') ||
      scanner.includes('audiobook') ||
      agent.includes('music') ||
      agent.includes('audiobook') ||
      title.includes('music') ||
      title.includes('audiobook') ||
      title.includes('audio book')
    );
  }

  function textLooksLikeAudiobook(value) {
    const s = String(value || '').toLowerCase();
    return s.includes('audiobook') || s.includes('audio book');
  }

  function activeSectionLooksLikeAudiobook() {
    const sectionKey = String($('inSection').value || cfg().sectionKey || '');
    if (!sectionKey) return false;
    const meta = plexSectionMetaByKey[sectionKey] || null;
    if (!meta) return false;
    return (
      textLooksLikeAudiobook(meta.type) ||
      textLooksLikeAudiobook(meta.title) ||
      textLooksLikeAudiobook(meta.scanner) ||
      textLooksLikeAudiobook(meta.agent)
    );
  }

  function trackLooksLikeAudiobook(track) {
    if (!track || isRadioMode) return false;
    const typ = String((track && track.type) || '').toLowerCase();
    if (typ === 'audiobook' || typ === 'chapter') return true;
    if (typ === 'episode') return activeSectionLooksLikeAudiobook();
    if (activeSectionLooksLikeAudiobook() && isPlexAudioLeafForPlayback(track)) return true;
    return (
      textLooksLikeAudiobook(track && track.title) ||
      textLooksLikeAudiobook(track && track.parentTitle) ||
      textLooksLikeAudiobook(track && track.grandparentTitle) ||
      textLooksLikeAudiobook(currentAlbum && currentAlbum.title)
    );
  }

  function computeAudiobookBookId(stateLike) {
    const src = stateLike || {};
    const sectionKey = String(src.sectionKey || $('inSection').value || cfg().sectionKey || '');
    const albumRatingKey = String(src.albumRatingKey || src.parentRatingKey || '');
    const partKey = String(src.partKey || '');
    const trackRatingKey = String(src.trackRatingKey || '');
    if (!sectionKey || !albumRatingKey || (!partKey && !trackRatingKey)) return '';
    return sectionKey + '::' + albumRatingKey;
  }

  function parseNostrMaybeJson(value) {
    try {
      const parsed = JSON.parse(String(value || ''));
      return parsed && typeof parsed === 'object' ? parsed : null;
    } catch (_) {
      return null;
    }
  }

  function resolvePlexTotalSize(mc) {
    if (!mc || typeof mc !== 'object') return 0;
    const keys = ['totalSize', 'TotalSize', 'size', 'Size'];
    for (let i = 0; i < keys.length; i++) {
      const raw = Number(mc[keys[i]]);
      if (Number.isFinite(raw) && raw >= 0) return raw;
    }
    return 0;
  }

  async function hasAlbumsInSection(sectionKey) {
    const key = String(sectionKey || '');
    if (!key) return false;
    try {
      const albums = await plexFetch(
        '/library/sections/' +
          encodeURIComponent(key) +
          '/albums?X-Plex-Container-Start=0&X-Plex-Container-Size=1'
      );
      const mc = albums && albums.MediaContainer ? albums.MediaContainer : {};
      if (resolvePlexTotalSize(mc) > 0) return true;
      const fallback = await plexFetch(
        '/library/sections/' +
          encodeURIComponent(key) +
          '/all?type=9&X-Plex-Container-Start=0&X-Plex-Container-Size=1'
      );
      const fallbackMc = fallback && fallback.MediaContainer ? fallback.MediaContainer : {};
      return resolvePlexTotalSize(fallbackMc) > 0;
    } catch (_) {
      return false;
    }
  }

  function updatePlexLibraryLinkActive() {
    const nav = $('plexLibraryLinks');
    if (!nav) return;
    const cur = String($('inSection').value || '');
    const plexBrowsing = navView === 'albums';
    nav.querySelectorAll('.sidebar-plex-row').forEach((row) => {
      const btn = row.querySelector('.sidebar-nav-btn[data-section-key]');
      if (!btn) return;
      const match = plexBrowsing && String(btn.dataset.sectionKey || '') === cur;
      row.classList.toggle('active', match);
    });
    nav.querySelectorAll('.sidebar-nav-btn[data-section-key]').forEach((btn) => {
      btn.classList.remove('active');
    });
    refreshMobileLibraryPicker();
  }

  function refreshMobileLibraryPicker(state) {
    const picker = $('mobileLibrarySelect');
    if (!picker) return;
    const mode = String(state || '');
    const cur = String(($('inSection') && $('inSection').value) || '');
    const list = Array.isArray(plexSectionListCache) ? plexSectionListCache : [];
    picker.innerHTML = '';
    if (mode === 'loading') {
      picker.disabled = true;
      picker.innerHTML = '<option value="">Loading libraries…</option>';
      return;
    }
    if (mode === 'error') {
      picker.disabled = true;
      picker.innerHTML = '<option value="">Could not load libraries</option>';
      return;
    }
    if (!list.length) {
      picker.disabled = true;
      picker.innerHTML = '<option value="">No libraries found</option>';
      return;
    }
    for (let i = 0; i < list.length; i++) {
      const ent = list[i];
      const opt = document.createElement('option');
      opt.value = String(ent.key || '');
      opt.textContent = String(ent.title || ent.key || '');
      picker.appendChild(opt);
    }
    picker.disabled = false;
    const hasCur = !!(cur && list.some((ent) => String(ent.key) === cur));
    picker.value = hasCur ? cur : String(list[0].key || '');
  }

  async function handlePlexSectionPick(sectionKey) {
    const hidden = $('inSection');
    const key = String(sectionKey || '');
    if (!hidden || !key) return;
    const prev = hidden.value;
    hidden.value = key;
    updatePlexLibraryLinkActive();
    const onAlbumsGrid =
      prev === key &&
      navView === 'albums' &&
      (!$('viewTracks') || $('viewTracks').style.display === 'none') &&
      currentArtistKey == null &&
      (location.hash || '').trim() === '#albums';
    if (onAlbumsGrid) return;
    await applyPlexSectionUserChange();
  }

  async function applyPlexSectionUserChange() {
    const section = $('inSection').value;
    localStorage.setItem(LS.section, section || '');
    activeSearchIndexSection = '';
    albumSearchIndexCache.clear();
    albumTrackSearchCache.clear();
    invalidateAllSearchRecordsCache();
    libraryTrackTotalCache = { sectionKey: '', total: 0 };
    songShuffleRecentKeys = [];
    if (!section) {
      setSettingsStatus('Pick a Plex library in the sidebar.', 'error');
      return;
    }
    if (navView !== 'albums') {
      setNavView('albums', { updateLocation: true });
    } else {
      const h = (location.hash || '').trim();
      const tracksHidden = !$('viewTracks') || $('viewTracks').style.display === 'none';
      const inDetail =
        !tracksHidden || currentArtistKey != null || /^#(album|artist)\//.test(h);
      if (inDetail || h !== '#albums') backToAlbums({ updateLocation: true, fullLibrary: true, skipLoad: true });
    }
    await loadAlbums(false);
  }

  async function loadSections() {
    const hidden = $('inSection');
    const nav = $('plexLibraryLinks');
    nav.innerHTML = '<p class="sidebar-plex-note"><span class="spinner"></span> Loading…</p>';
    refreshMobileLibraryPicker('loading');
    try {
      let data = await plexFetch('/library/sections');
      plexConnectionWorking = true;
      updatePlexLoginButtonVisibility();
      const dirs = data.MediaContainer.Directory || [];
      const music = dirs.filter(looksLikeMusicLibrary);
      const list = music.length ? music : dirs;
      const sectionHasAlbums = await Promise.all(
        list.map((d) =>
          hasAlbumsInSection(d && d.key).then((ok) => ({ key: String((d && d.key) || ''), ok }))
        )
      );
      const albumsByKey = {};
      for (let ai = 0; ai < sectionHasAlbums.length; ai++) {
        const item = sectionHasAlbums[ai];
        if (!item || !item.key) continue;
        albumsByKey[item.key] = !!item.ok;
      }
      const listWithAlbums = list.filter((d) => albumsByKey[String((d && d.key) || '')]);
      nav.innerHTML = '';
      if (!listWithAlbums.length) {
        hidden.value = '';
        plexSectionListCache = [];
        plexSectionNameByKey = {};
        plexSectionMetaByKey = {};
        nav.innerHTML = '<p class="sidebar-plex-note">No libraries with albums found</p>';
        refreshMobileLibraryPicker();
        setSettingsStatus('Connected, but no libraries with albums were returned.', 'error');
        return;
      }
      listWithAlbums.forEach((d) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'sidebar-nav-btn';
        btn.dataset.sectionKey = String(d.key);
        const suffix = music.length ? '' : ' (' + String(d.type || 'unknown') + ')';
        btn.textContent = String(d.title || '') + suffix;
        btn.addEventListener('click', async () => {
          clearSearchInput();
          await handlePlexSectionPick(String(d.key));
        });
        const row = document.createElement('div');
        row.className = 'sidebar-plex-row';
        const mark = document.createElement('span');
        mark.className = 'sidebar-plex-mark';
        mark.setAttribute('aria-hidden', 'true');
        mark.textContent = 'PLEX';
        row.appendChild(btn);
        row.appendChild(mark);
        row.addEventListener('click', (e) => {
          if (e.target === btn || btn.contains(e.target)) return;
          btn.click();
        });
        nav.appendChild(row);
      });
      plexSectionListCache = listWithAlbums.map((d) => ({ key: String(d.key), title: String(d.title || d.key) }));
      plexSectionNameByKey = {};
      plexSectionMetaByKey = {};
      for (let si = 0; si < plexSectionListCache.length; si++) {
        const ent = plexSectionListCache[si];
        plexSectionNameByKey[ent.key] = ent.title;
      }
      for (let mi = 0; mi < listWithAlbums.length; mi++) {
        const s = listWithAlbums[mi] || {};
        const key = String(s.key || '');
        if (!key) continue;
        plexSectionMetaByKey[key] = {
          type: String(s.type || ''),
          title: String(s.title || ''),
          scanner: String(s.scanner || ''),
          agent: String(s.agent || '')
        };
      }
      setSettingsStatus('');
      const saved = cfg().sectionKey;
      if (saved && listWithAlbums.some((item) => String(item.key) === String(saved))) hidden.value = String(saved);
      else if (listWithAlbums[0]) hidden.value = String(listWithAlbums[0].key);
      refreshMobileLibraryPicker();
      updatePlexLibraryLinkActive();
    } catch (e) {
      const c = cfg();
      const fallback = await fetchSectionsViaServerFallback(c.token, c.server);
      if (fallback && fallback.data && fallback.data.MediaContainer) {
        localStorage.setItem(LS.server, fallback.server);
        if (fallback.token) {
          localStorage.setItem(LS.token, fallback.token);
          const tokenInput = $('inToken');
          if (tokenInput) tokenInput.value = fallback.token;
        }
        setSettingsStatus('Switched to a reachable Plex connection and loaded libraries.', 'ok');
        return loadSections();
      }
      plexConnectionWorking = false;
      updatePlexLoginButtonVisibility();
      hidden.value = '';
      plexSectionListCache = [];
      plexSectionNameByKey = {};
      plexSectionMetaByKey = {};
      if (nav) nav.innerHTML = '<p class="sidebar-plex-note error">Could not load libraries.</p>';
      refreshMobileLibraryPicker('error');
      throw e;
    }
  }

  function escapeHtml(s) {
    if (!s) return '';
    const d = document.createElement('div');
    d.textContent = s;
    return d.innerHTML;
  }

  function flattenMetaToSearchText(value, chunks, seen) {
    if (value == null) return;
    const kind = typeof value;
    if (kind === 'string') {
      if (value) {
        chunks.push(value);
        if (value.includes('/') || value.includes('\\')) {
          value.split(/[\\/]+/).forEach((segment) => {
            if (segment) chunks.push(segment);
          });
        }
      }
      return;
    }
    if (kind === 'number' || kind === 'boolean') {
      chunks.push(String(value));
      return;
    }
    if (seen.has(value)) return;
    seen.add(value);
    if (Array.isArray(value)) {
      value.forEach((entry) => flattenMetaToSearchText(entry, chunks, seen));
      return;
    }
    Object.entries(value).forEach(([key, val]) => {
      chunks.push(key);
      if (/file|folder|path|directory|dir/i.test(key) && typeof val === 'string') {
        chunks.push(val);
      }
      flattenMetaToSearchText(val, chunks, seen);
    });
  }

  function makeSearchBlob(meta) {
    const chunks = [];
    flattenMetaToSearchText(meta, chunks, new Set());
    return chunks.join(' ').toLowerCase();
  }

  function normalizeSearchText(s) {
    return String(s || '')
      .toLowerCase()
      .normalize('NFKD')
      .replace(/[\u0300-\u036f]/g, '')
      .replace(/[\u2010-\u2015\u2212\ufe58\ufe63\uff0d]/g, '-')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function searchQueryVariants(rawQ) {
    const q = normalizeSearchText(rawQ);
    if (!q) return [];
    const variants = new Set([q]);
    let stripped = q
      .replace(/\([^)]{0,160}\)/g, ' ')
      .replace(/\[[^\]]{0,160}\]/g, ' ');
    stripped = normalizeSearchText(stripped);
    if (stripped && stripped !== q) variants.add(stripped);
    const noDash = normalizeSearchText(q.replace(/\s*-\s*/g, ' '));
    if (noDash && noDash !== q) variants.add(noDash);
    const strippedNoDash = normalizeSearchText(stripped.replace(/\s*-\s*/g, ' '));
    if (strippedNoDash) variants.add(strippedNoDash);
    return Array.from(variants).filter(Boolean);
  }

  function searchTokensForMatch(rawQ) {
    const q = normalizeSearchText(rawQ);
    const tokens = q.split(/[^a-z0-9]+/i).filter((t) => t.length >= 2);
    const longOnes = tokens.filter((t) => t.length >= 3);
    if (longOnes.length >= 2) return longOnes;
    return tokens.length >= 2 ? tokens : longOnes;
  }

  /** True if query is empty, or blob matches substring / relaxed variants / token-AND (for long queries). */
  function searchMatchesBlob(blobRaw, queryRaw) {
    if (!normalizeSearchText(queryRaw)) return true;
    const blob = normalizeSearchText(blobRaw);
    if (!blob) return false;
    const variants = searchQueryVariants(queryRaw);
    for (let i = 0; i < variants.length; i++) {
      if (variants[i] && blob.includes(variants[i])) return true;
    }
    const tokens = searchTokensForMatch(queryRaw);
    if (tokens.length >= 2 && tokens.every((t) => blob.includes(t))) return true;
    return false;
  }

  /** Strip chat / Nostr prefixes so Plex search uses artist + title only. */
  function normalizeChatSearchQuery(q) {
    let s = String(q || '').trim();
    s = s.replace(/^\s*now playing:\s*/i, '');
    s = s.replace(/^\s*unfavorited:\s*/i, '');
    s = s.replace(/^\s*favorited:\s*/i, '');
    s = s.replace(/^\s*⭐\s*/, '').replace(/^\s*☆\s*/, '').replace(/^\s*🌟\s*/, '');
    s = s.replace(/^\s*👍\s+/, '');
    return s.trim();
  }

  function collectTracksFromPlexJson(node, out) {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) {
      for (let i = 0; i < node.length; i++) collectTracksFromPlexJson(node[i], out);
      return;
    }
    const typ = node.type;
    const typStr = String(typ || '').toLowerCase();
    if (
      node.ratingKey &&
      (typ === 'track' ||
        typ === 10 ||
        typ === '10' ||
        typStr === 'episode' ||
        typStr === 'chapter' ||
        typStr === 'audiobook' ||
        isPlexAudioLeafForPlayback(node))
    ) {
      out.push(node);
    }
    const meta = node.Metadata || node.metadata;
    if (meta) {
      const arr = Array.isArray(meta) ? meta : [meta];
      for (let i = 0; i < arr.length; i++) collectTracksFromPlexJson(arr[i], out);
    }
    const hub = node.Hub || node.hub;
    if (hub) {
      const arr = Array.isArray(hub) ? hub : [hub];
      for (let i = 0; i < arr.length; i++) collectTracksFromPlexJson(arr[i], out);
    }
    if (node.MediaContainer) collectTracksFromPlexJson(node.MediaContainer, out);
  }

  async function plexSearchHubTracks(searchText) {
    const q = normalizeSearchText(normalizeChatSearchQuery(searchText));
    if (q.length < 2) return [];
    const section = activeSectionKey();
    const encoded = encodeURIComponent(String(searchText || '').trim());
    const paths = [];
    if (section) {
      paths.push(
        '/hubs/search?query=' + encoded + '&limit=50&sectionId=' + encodeURIComponent(section)
      );
    }
    paths.push('/hubs/search?query=' + encoded + '&limit=50');
    const byKey = new Map();
    for (let p = 0; p < paths.length; p++) {
      try {
        const data = await plexFetch(paths[p]);
        const buf = [];
        collectTracksFromPlexJson(data, buf);
        for (let i = 0; i < buf.length; i++) {
          const t = buf[i];
          byKey.set(String(t.ratingKey), t);
        }
        if (byKey.size) break;
      } catch (_) {}
    }
    return Array.from(byKey.values());
  }

  function collectHubSearchMetadata(node, out) {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) {
      for (let i = 0; i < node.length; i++) collectHubSearchMetadata(node[i], out);
      return;
    }
    const meta = node.Metadata || node.metadata;
    if (meta) {
      const arr = Array.isArray(meta) ? meta : [meta];
      for (let i = 0; i < arr.length; i++) {
        const m = arr[i];
        if (!m) continue;
        const typ = String(m.type || '').toLowerCase();
        if (typ === 'album') {
          out.push({
            kind: 'album',
            ratingKey: m.ratingKey,
            title: String(m.title || ''),
            parentTitle: String(m.parentTitle || ''),
            librarySectionID: m.librarySectionID != null ? String(m.librarySectionID) : ''
          });
        } else if (typ === 'track') {
          out.push({
            kind: 'track',
            ratingKey: m.ratingKey,
            title: String(m.title || ''),
            parentRatingKey: m.parentRatingKey,
            parentTitle: String(m.parentTitle || ''),
            grandparentTitle: String(m.grandparentTitle || ''),
            librarySectionID: m.librarySectionID != null ? String(m.librarySectionID) : ''
          });
        }
      }
    }
    const hub = node.Hub || node.hub;
    if (hub) {
      const arr = Array.isArray(hub) ? hub : [hub];
      for (let i = 0; i < arr.length; i++) collectHubSearchMetadata(arr[i], out);
    }
    if (node.MediaContainer) collectHubSearchMetadata(node.MediaContainer, out);
  }

  function ingestHubSearchData(data, albumsByKey, tracksByKey) {
    const items = [];
    collectHubSearchMetadata(data, items);
    for (let i = 0; i < items.length; i++) {
      const it = items[i];
      if (it.kind === 'album' && it.ratingKey != null) albumsByKey.set(String(it.ratingKey), it);
      if (it.kind === 'track' && it.ratingKey != null) tracksByKey.set(String(it.ratingKey), it);
    }
  }

  async function plexHubSearchEverySection(rawQuery) {
    const encoded = encodeURIComponent(String(rawQuery || '').trim());
    const albumsByKey = new Map();
    const tracksByKey = new Map();
    const section = activeSectionKey();
    const paths = [];
    if (section) {
      paths.push('/hubs/search?query=' + encoded + '&limit=40&sectionId=' + encodeURIComponent(section));
    }
    paths.push('/hubs/search?query=' + encoded + '&limit=72');
    for (let i = 0; i < paths.length; i++) {
      try {
        const data = await plexFetchWithTimeout(paths[i], 3500);
        ingestHubSearchData(data, albumsByKey, tracksByKey);
      } catch (_) {}
    }
    return {
      albums: Array.from(albumsByKey.values()),
      tracks: Array.from(tracksByKey.values())
    };
  }

  function countVisibleAlbumCards(grid) {
    if (!grid) return 0;
    let n = 0;
    grid.querySelectorAll('.album').forEach((el) => {
      if (el.style.display === 'none') return;
      n++;
    });
    return n;
  }

  function isUnknownAlbumTitle(title) {
    const raw = String(title || '').trim().toLowerCase();
    return raw === '[unknown album]' || raw === 'unknown album';
  }

  function getTrackFilePath(track) {
    const part = getFirstPlayablePart(track);
    return part && part.file ? String(part.file) : '';
  }

  const PATH_DISPLAY_TRIM_PREFIX = /^\/volume\d+\/Media(?=\/|$)/i;

  function normalizePathSeparators(filePath) {
    return String(filePath || '').replace(/\\/g, '/').trim();
  }

  function trimPlexDisplayPath(pathValue) {
    const normalized = normalizePathSeparators(pathValue);
    if (!normalized) return '';
    const trimmed = normalized.replace(PATH_DISPLAY_TRIM_PREFIX, '');
    if (!trimmed) return '/';
    return trimmed.startsWith('/') ? trimmed : '/' + trimmed;
  }

  function breadcrumbLinkHtmlFromPath(pathValue, linkLastSegment) {
    const normalized = normalizePathSeparators(pathValue);
    if (!normalized) return '';
    const displayPath = trimPlexDisplayPath(normalized);
    const fullSegments = normalized.split('/').filter(Boolean);
    const displaySegments = displayPath.split('/').filter(Boolean);
    if (!displaySegments.length) return escapeHtml(displayPath || '/');
    const skipCount = Math.max(0, fullSegments.length - displaySegments.length);
    const rootPrefix = normalized.startsWith('/') ? '/' : '';
    const sep = '<span class="path-sep">/</span>';
    const parts = [];
    if (displayPath.startsWith('/')) parts.push('<span class="path-sep">/</span>');
    for (let i = 0; i < displaySegments.length; i++) {
      const label = displaySegments[i];
      const isLast = i === displaySegments.length - 1;
      const fullIndex = skipCount + i;
      const targetPath = rootPrefix + fullSegments.slice(0, fullIndex + 1).join('/');
      if (!isLast || linkLastSegment) {
        parts.push(makeMetaLinkHtml(label, directoryHash(targetPath), 'directory'));
      } else {
        parts.push(escapeHtml(label));
      }
      if (!isLast) parts.push(sep);
    }
    return parts.join('');
  }

  function pathDirnameNorm(filePath) {
    const norm = normalizePathSeparators(filePath);
    const i = norm.lastIndexOf('/');
    return i <= 0 ? '' : norm.slice(0, i);
  }

  /** Preserve original separators (e.g. Windows paths) for display. */
  function pathDirnameDisplay(filePath) {
    if (!filePath) return '';
    const m = String(filePath).match(/^(.+)[\\/][^\\/]+$/);
    return m ? m[1] : '';
  }

  function pathBasename(filePath) {
    const norm = normalizePathSeparators(filePath);
    const parts = norm.split('/').filter(Boolean);
    return parts.length ? parts[parts.length - 1] : '';
  }

  /** First Plex Part with a stream key (some items use Media[1] or a non-zero Part index). */
  function getFirstPlayablePart(track) {
    if (!track || !Array.isArray(track.Media)) return null;
    for (let mi = 0; mi < track.Media.length; mi++) {
      const media = track.Media[mi];
      if (!media || !Array.isArray(media.Part)) continue;
      for (let pi = 0; pi < media.Part.length; pi++) {
        const part = media.Part[pi];
        if (part && part.key) return part;
      }
    }
    return null;
  }

  function getFirstPlayablePartKey(track) {
    const part = getFirstPlayablePart(track);
    return part && part.key ? String(part.key) : '';
  }

  /** Music + audiobook: playable items may be track, episode, chapter, or a single-file book (album with Media). */
  function isPlexAudioLeafForPlayback(m) {
    if (!m || !getFirstPlayablePartKey(m)) return false;
    const t = String(m.type || '').toLowerCase();
    if (t === 'movie' || t === 'show' || t === 'season' || t === 'collection') return false;
    return true;
  }

  /** Guess artist/title from Part.file basename when Plex tags are empty (e.g. "06. Artist - Title.ogg"). */
  function filenameDisplayHints(filePath) {
    const base = pathBasename(filePath);
    if (!base) return { artist: '', title: '', label: '' };
    const stem = base.replace(/\.[^.]+$/i, '').trim();
    const raw = stem || base;
    let body = raw
      .replace(/^\d+\.\s+/, '')
      .replace(/^\d+\.(?=\S)/, '')
      .replace(/^\d+\s+/, '')
      .trim();
    if (!body) body = raw;
    const dash = body.indexOf(' - ');
    if (dash === -1) return { artist: '', title: body, label: body };
    const artist = body.slice(0, dash).trim();
    const title = body.slice(dash + 3).trim();
    const label = artist && title ? artist + ' - ' + title : body;
    return { artist, title: title || artist || body, label };
  }

  function effectiveTrackListName(t) {
    const titled = String((t && t.title) || '').trim();
    if (titled) return titled;
    const fp = getTrackFilePath(t);
    const h = filenameDisplayHints(fp);
    return h.label || h.title || pathBasename(fp) || 'Untitled';
  }

  /** Directory + file context for album header (Plex Part.file). */
  function formatAlbumFilesystemHeader(tracks) {
    const paths = (tracks || []).map(getTrackFilePath).filter(Boolean);
    if (!paths.length) return '';
    const unique = [...new Set(paths)];
    if (unique.length === 1) {
      const only = unique[0];
      const basename = pathBasename(only);
      const looksLikeFile = /\.[a-z0-9]{1,10}$/i.test(basename);
      return (
        '<p class="album-detail-path">' +
        breadcrumbLinkHtmlFromPath(only, !looksLikeFile) +
        '</p>'
      );
    }
    const normDirs = paths.map(pathDirnameNorm);
    const dirKey = mostFrequentValue(normDirs.filter(Boolean));
    const sameDir =
      dirKey && normDirs.length && normDirs.every((d) => d === dirKey);
    if (sameDir) {
      const sample = paths.find((p) => pathDirnameNorm(p) === dirKey);
      const displayDir = sample ? pathDirnameDisplay(sample) : dirKey;
      return (
        '<p class="album-detail-path">' +
        breadcrumbLinkHtmlFromPath(displayDir, true) +
        '</p><p class="album-detail-path-note">' +
        escapeHtml(String(unique.length) + ' files') +
        '</p>'
      );
    }
    return (
      '<p class="album-detail-path">' +
      breadcrumbLinkHtmlFromPath(unique[0], false) +
      '</p><p class="album-detail-path-note">' +
      escapeHtml('+' + String(unique.length - 1) + ' other paths') +
      '</p>'
    );
  }

  function extractAlbumFolderFromTrack(track) {
    const filePath = getTrackFilePath(track);
    if (!filePath) return '';
    const parts = String(filePath)
      .split(/[\\/]+/)
      .map((p) => p.trim())
      .filter(Boolean);
    if (parts.length < 2) return '';
    return parts[parts.length - 2];
  }

  function mostFrequentValue(values) {
    const counts = new Map();
    let best = '';
    let bestCount = 0;
    values.forEach((value) => {
      if (!value) return;
      const next = (counts.get(value) || 0) + 1;
      counts.set(value, next);
      if (next > bestCount) {
        best = value;
        bestCount = next;
      }
    });
    return best;
  }

  function albumDisplayTitleFromTracks(album, tracks) {
    if (!isUnknownAlbumTitle(album && album.title)) return String((album && album.title) || '');
    const folderName = mostFrequentValue((tracks || []).map(extractAlbumFolderFromTrack));
    return folderName || '[Unknown Album]';
  }

  async function resolveAlbumFolderName(albumKey) {
    if (!albumKey) return '';
    if (albumFolderNameCache.has(albumKey)) return albumFolderNameCache.get(albumKey);
    if (albumFolderNameFetchCache.has(albumKey)) return albumFolderNameFetchCache.get(albumKey);
    const promise = plexFetch('/library/metadata/' + albumKey + '/children')
      .then((data) => {
        const tracks = (data.MediaContainer.Metadata || []).filter(isPlexAudioLeafForPlayback);
        const folder = mostFrequentValue(tracks.map(extractAlbumFolderFromTrack));
        albumFolderNameCache.set(albumKey, folder || '');
        albumFolderNameFetchCache.delete(albumKey);
        return folder || '';
      })
      .catch((err) => {
        albumFolderNameFetchCache.delete(albumKey);
        throw err;
      });
    albumFolderNameFetchCache.set(albumKey, promise);
    return promise;
  }

  function albumsViewVisible() {
    return $('viewAlbums').style.display !== 'none';
  }

  function getAlbumViewMode() {
    return localStorage.getItem(LS.viewMode) || 'grid';
  }

  function getPlaybackMode() {
    const mode = localStorage.getItem(LS.playbackMode) || PLAYBACK_MODE_OFF;
    if (
      mode !== PLAYBACK_MODE_OFF &&
      mode !== PLAYBACK_MODE_ALBUM_SHUFFLE &&
      mode !== PLAYBACK_MODE_SONG_SHUFFLE
    ) {
      return PLAYBACK_MODE_OFF;
    }
    return mode;
  }

  function playbackModeLabel(mode) {
    if (isRadioMode) {
      return mode === PLAYBACK_MODE_OFF ? 'Shuffle off' : 'Shuffle on';
    }
    if (mode === PLAYBACK_MODE_ALBUM_SHUFFLE) return 'Shuffle: whole albums, then random album';
    if (mode === PLAYBACK_MODE_SONG_SHUFFLE) return 'Shuffle: random track from entire library';
    return 'Shuffle off';
  }

  function playbackModeShortText(mode) {
    if (isRadioMode) {
      return mode === PLAYBACK_MODE_OFF ? 'OFF' : 'ON';
    }
    if (mode === PLAYBACK_MODE_ALBUM_SHUFFLE) return 'ALBUM';
    if (mode === PLAYBACK_MODE_SONG_SHUFFLE) return 'SONG';
    return 'OFF';
  }

  function updateNextAlbumButtonVisibility() {
    const nextAlb = $('btnNextAlbum');
    if (!nextAlb) return;
    const show = playbackMode === PLAYBACK_MODE_ALBUM_SHUFFLE && !isRadioMode;
    nextAlb.style.display = show ? '' : 'none';
  }

  function applyPlaybackModeUI() {
    const btn = $('btnShuffleMode');
    const textEl = $('shuffleModeText');
    if (!btn) return;
    if (textEl) textEl.textContent = playbackModeShortText(playbackMode);
    btn.classList.toggle('shuffle-active', playbackMode !== PLAYBACK_MODE_OFF);
    btn.classList.toggle(
      'shuffle-album',
      !isRadioMode && playbackMode === PLAYBACK_MODE_ALBUM_SHUFFLE
    );
    btn.classList.toggle(
      'shuffle-song',
      !isRadioMode && playbackMode === PLAYBACK_MODE_SONG_SHUFFLE
    );
    const label = playbackModeLabel(playbackMode);
    btn.setAttribute('aria-label', label);
    btn.setAttribute('title', label);
    updateNextAlbumButtonVisibility();
  }

  function cyclePlaybackMode() {
    if (isRadioMode) {
      if (playbackMode === PLAYBACK_MODE_OFF) {
        setPlaybackMode(PLAYBACK_MODE_ALBUM_SHUFFLE);
      } else {
        setPlaybackMode(PLAYBACK_MODE_OFF);
      }
      return;
    }
    if (playbackMode === PLAYBACK_MODE_OFF) {
      setPlaybackMode(PLAYBACK_MODE_ALBUM_SHUFFLE);
      return;
    }
    if (playbackMode === PLAYBACK_MODE_ALBUM_SHUFFLE) {
      setPlaybackMode(PLAYBACK_MODE_SONG_SHUFFLE);
      return;
    }
    setPlaybackMode(PLAYBACK_MODE_OFF);
  }

  function setPlaybackMode(mode) {
    if (
      mode !== PLAYBACK_MODE_OFF &&
      mode !== PLAYBACK_MODE_ALBUM_SHUFFLE &&
      mode !== PLAYBACK_MODE_SONG_SHUFFLE
    ) {
      return;
    }
    playbackMode = mode;
    try {
      localStorage.setItem(LS.playbackMode, mode);
    } catch (_) {}
    applyPlaybackModeUI();
    if (playingIdx >= 0 && currentTracks[playingIdx]) {
      updateNowPlayingUI(currentTracks[playingIdx], playingIdx);
    }
  }

  function setAlbumViewMode(mode) {
    if (mode !== 'list' && mode !== 'grid') return;
    localStorage.setItem(LS.viewMode, mode);
    applyAlbumViewMode();
  }

  function applyAlbumViewMode() {
    const grid = $('albumGrid');
    const isList = getAlbumViewMode() === 'list';
    if (grid) grid.classList.toggle('list-view', isList);
    const btn = $('viewModeToggleBtn');
    if (!btn) return;
    const switchTo = isList ? 'grid' : 'list';
    const label = switchTo === 'list' ? 'Switch to list view' : 'Switch to grid view';
    btn.classList.toggle('active', isList);
    btn.setAttribute('aria-label', label);
    btn.setAttribute('title', label);
    btn.innerHTML = isList
      ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6h18"></path><path d="M3 12h18"></path><path d="M3 18h18"></path></svg>'
      : '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="7" height="7" rx="1"></rect><rect x="14" y="3" width="7" height="7" rx="1"></rect><rect x="3" y="14" width="7" height="7" rx="1"></rect><rect x="14" y="14" width="7" height="7" rx="1"></rect></svg>';
  }

  function scheduleAlbumAutoLoad(delayMs) {
    if (autoLoadAlbumsTimer) clearTimeout(autoLoadAlbumsTimer);
    autoLoadAlbumsTimer = setTimeout(() => {
      autoLoadAlbumsTimer = 0;
      autoLoadMoreAlbums().catch((err) => {
        console.error(err);
      });
    }, delayMs == null ? 60 : delayMs);
  }

  function shouldAutoLoadAlbumsForViewport() {
    if (!albumsViewVisible() || !hasMoreAlbums || isLoadingAlbums) return false;
    const marker = $('loadMoreAlb');
    if (!marker) return false;
    const rect = marker.getBoundingClientRect();
    // Trigger before the user hits the bottom, and when filtered results leave empty space.
    return rect.top <= window.innerHeight + 220;
  }

  async function autoLoadMoreAlbums() {
    let loops = 0;
    while (loops < 12 && shouldAutoLoadAlbumsForViewport()) {
      loops += 1;
      const loaded = await loadAlbums(true);
      if (!loaded) break;
    }
  }

  function getCachedTrackSearchPayload(albumKey) {
    if (!albumKey) return null;
    const key = String(albumKey);
    if (albumTrackSearchCache.has(key)) return albumTrackSearchCache.get(key);
    const indexed = albumSearchIndexCache.get(key);
    if (!indexed || !indexed.trackSearch) return null;
    const payload = {
      search: String(indexed.trackSearch || ''),
      titles: Array.isArray(indexed.titles) ? indexed.titles : []
    };
    albumTrackSearchCache.set(key, payload);
    return payload;
  }

  async function ensureActiveSearchIndexLoaded() {
    const section = activeSectionKey();
    if (!section) return;
    if (activeSearchIndexSection === section && albumSearchIndexCache.size) return;
    activeSearchIndexSection = section;
    albumSearchIndexCache.clear();
    const records = await readSectionSearchRecords(section);
    records.forEach((record) => {
      const key = String(record.albumKey || '');
      if (!key) return;
      albumSearchIndexCache.set(key, {
        albumSearch: String(record.albumSearch || ''),
        trackSearch: String(record.trackSearch || ''),
        titles: Array.isArray(record.titles) ? record.titles : []
      });
      if (record.trackSearch) {
        albumTrackSearchCache.set(key, {
          search: String(record.trackSearch || ''),
          titles: Array.isArray(record.titles) ? record.titles : []
        });
      }
    });
  }

  function formatIdbAlbumTitleForSearch(record) {
    const ak = String(record.albumKey || '');
    const fromLookup = albumLookup.get(ak);
    if (fromLookup && fromLookup.title && !isUnknownAlbumTitle(fromLookup.title)) return String(fromLookup.title);
    const blob = String(record.albumSearch || '').trim();
    if (blob.length > 76) return blob.slice(0, 73) + '…';
    return blob || 'Album';
  }

  function idbRecordsMatchingQuery(records, queryRaw) {
    if (!normalizeSearchText(queryRaw)) return [];
    const hits = [];
    const seenAlbum = new Set();
    for (let i = 0; i < records.length; i++) {
      const r = records[i];
      const blob = String(r.albumSearch || '') + ' ' + String(r.trackSearch || '');
      if (!searchMatchesBlob(blob, queryRaw)) continue;
      const ak = String(r.albumKey || '');
      if (!ak || seenAlbum.has(ak)) continue;
      seenAlbum.add(ak);
      const sk = String(r.sectionKey || '');
      hits.push({
        kind: 'album',
        ratingKey: ak,
        sectionKey: sk,
        title: formatIdbAlbumTitleForSearch(r),
        parentTitle: '',
        subtitle: plexSectionNameByKey[sk] || ''
      });
    }
    return hits;
  }

  function buildUnifiedSearchHtml(radioHits, albums, tracks, rawQuery) {
    const parts = [];
    if (radioHits.length) {
      parts.push('<section class="unified-search-block"><h4>Radio (channels.js)</h4><ul class="unified-hit-list">');
      for (let i = 0; i < radioHits.length; i++) {
        const st = radioHits[i];
        const sub = st.tags.length ? st.tags.slice(0, 5).join(' · ') : 'Live stream';
        parts.push(
          '<li><button type="button" class="unified-hit-btn" data-unified-radio="' +
            escapeHtml(st.id) +
            '">' +
            '<span class="unified-hit-title">' +
            escapeHtml(st.title) +
            '</span>' +
            '<span class="unified-hit-sub">' +
            escapeHtml(sub) +
            '</span></button></li>'
        );
      }
      parts.push('</ul></section>');
    }
    if (albums.length || tracks.length) {
      parts.push('<section class="unified-search-block"><h4>Plex (all libraries)</h4><ul class="unified-hit-list">');
      for (let i = 0; i < albums.length; i++) {
        const a = albums[i];
        const bits = [];
        if (a.subtitle) bits.push(a.subtitle);
        if (a.parentTitle) bits.push(a.parentTitle);
        const sub = bits.join(' · ');
        parts.push(
          '<li><button type="button" class="unified-hit-btn" data-unified-plex-album data-section-key="' +
            escapeHtml(String(a.sectionKey || '')) +
            '" data-rating-key="' +
            escapeHtml(String(a.ratingKey || '')) +
            '">' +
            '<span class="unified-hit-title">' +
            escapeHtml(String(a.title || 'Album')) +
            '</span>' +
            (sub ? '<span class="unified-hit-sub">' + escapeHtml(sub) + '</span>' : '') +
            '</button></li>'
        );
      }
      for (let j = 0; j < tracks.length; j++) {
        const t = tracks[j];
        const titleLine = [t.title, t.parentTitle, t.grandparentTitle].filter(Boolean).join(' · ');
        const ak = String(t.parentRatingKey || '');
        parts.push(
          '<li><button type="button" class="unified-hit-btn" data-unified-plex-track data-section-key="' +
            escapeHtml(String(t.librarySectionID || '')) +
            '" data-album-key="' +
            escapeHtml(ak) +
            '">' +
            '<span class="unified-hit-title">' +
            escapeHtml(titleLine || 'Track') +
            '</span>' +
            '<span class="unified-hit-sub">Track · open album</span></button></li>'
        );
      }
      parts.push('</ul></section>');
    }
    if (!parts.length) {
      const norm = normalizeSearchText(rawQuery);
      const hint =
        norm.length < 2
          ? 'Type at least 2 characters to search Plex across every library. Radio matches can appear with 1+ characters.'
          : 'No matches in channels.js or Plex hub search for this phrase.';
      return '<p class="unified-search-empty">' + escapeHtml(hint) + '</p>';
    }
    return parts.join('');
  }

  function playUnifiedRadioStation(id) {
    if (!id) return;
    if (!radioStations.length) buildRadioData();
    const idx = radioStations.findIndex((s) => s.id === id);
    if (idx < 0) return;
    const rh = radioHash(id);
    if (location.hash !== rh) location.hash = rh;
    else playRadioIndex(idx);
  }

  async function unifiedOpenPlexAlbum(sectionKey, ratingKey) {
    if (!ratingKey) return;
    const sk = String(sectionKey || '');
    if (sk && $('inSection') && $('inSection').value !== sk) {
      $('inSection').value = sk;
      localStorage.setItem(LS.section, sk);
      updatePlexLibraryLinkActive();
    }
    if (navView !== 'albums') {
      setNavView('albums', { updateLocation: true });
    } else {
      const h = (location.hash || '').trim();
      const inDetail =
        ($('viewTracks') && $('viewTracks').style.display !== 'none') ||
        currentArtistKey != null ||
        /^#(album|artist)\//.test(h);
      if (inDetail || (h !== '#albums' && h !== '')) {
        backToAlbums({ updateLocation: true, fullLibrary: true, skipLoad: true });
      }
    }
    try {
      await openAlbumByKey(String(ratingKey));
    } catch (e) {
      console.error(e);
    }
  }

  function wireUnifiedSearchPanelOnce() {
    const panel = $('unifiedSearchPanel');
    if (!panel || panel.dataset.wiredUnified === '1') return;
    panel.dataset.wiredUnified = '1';
    panel.addEventListener('click', (e) => {
      const rb = e.target.closest('[data-unified-radio]');
      if (rb) {
        e.preventDefault();
        playUnifiedRadioStation(rb.getAttribute('data-unified-radio'));
        return;
      }
      const pa = e.target.closest('[data-unified-plex-album]');
      if (pa) {
        e.preventDefault();
        unifiedOpenPlexAlbum(pa.getAttribute('data-section-key'), pa.getAttribute('data-rating-key'));
        return;
      }
      const pt = e.target.closest('[data-unified-plex-track]');
      if (pt) {
        e.preventDefault();
        unifiedOpenPlexAlbum(pt.getAttribute('data-section-key'), pt.getAttribute('data-album-key'));
        return;
      }
    });
  }

  function scheduleUnifiedSearch(rawQuery, filterV) {
    if (unifiedSearchTimer) clearTimeout(unifiedSearchTimer);
    const q = String(rawQuery || '').trim();
    if (!q) {
      const p = $('unifiedSearchPanel');
      if (p) {
        p.hidden = true;
        p.innerHTML = '';
      }
      return;
    }
    unifiedSearchTimer = setTimeout(() => {
      unifiedSearchTimer = 0;
      runUnifiedSearchPanel(q, filterV).catch((err) => {
        console.error(err);
      });
    }, 300);
  }

  async function runUnifiedSearchPanel(rawQuery, filterV) {
    const panel = $('unifiedSearchPanel');
    if (!panel) return;
    const inp = $('inSearch');
    const normInput = normalizeSearchText(inp && inp.value.trim());
    if (normInput !== normalizeSearchText(rawQuery)) return;
    const seq = ++unifiedSearchSeq;
    panel.hidden = false;
    panel.innerHTML = '<p class="unified-search-loading"><span class="spinner"></span> Searching…</p>';

    if (!radioStations.length) buildRadioData();
    const qLower = rawQuery.trim().toLowerCase();
    const radioHits = [];
    for (let ri = 0; ri < radioStations.length; ri++) {
      const station = radioStations[ri];
      if (
        station.title.toLowerCase().includes(qLower) ||
        station.id.toLowerCase().includes(qLower) ||
        station.tags.join(' ').toLowerCase().includes(qLower)
      ) {
        radioHits.push(station);
      }
    }

    let idbHits = [];
    try {
      const allRec = await readAllSearchRecordsCached();
      if (filterV !== filterVersion) return;
      idbHits = idbRecordsMatchingQuery(allRec, rawQuery);
    } catch (_) {}

    if (filterV !== filterVersion || seq !== unifiedSearchSeq) return;
    panel.innerHTML = buildUnifiedSearchHtml(radioHits, idbHits, [], rawQuery);

    let hubAlbums = [];
    let hubTracks = [];
    const canPlex = !!(cfg().server && cfg().token);
    const normQ = normalizeSearchText(rawQuery);
    if (canPlex && normQ.length >= 2) {
      try {
        const h = await plexHubSearchEverySection(rawQuery);
        if (filterV !== filterVersion || seq !== unifiedSearchSeq) return;
        hubAlbums = h.albums;
        hubTracks = h.tracks;
      } catch (_) {}
    }

    if (filterV !== filterVersion || seq !== unifiedSearchSeq) return;

    if (!hubAlbums.length && !hubTracks.length) return;

    const byKey = new Map();
    for (let hi = 0; hi < idbHits.length; hi++) {
      const h = idbHits[hi];
      byKey.set(String(h.ratingKey), h);
    }
    for (let ai = 0; ai < hubAlbums.length; ai++) {
      const a = hubAlbums[ai];
      const k = String(a.ratingKey);
      const sec = String(a.librarySectionID || '');
      const prev = byKey.get(k) || {};
      let title = String(a.title || prev.title || '');
      if (!title) {
        const lu = albumLookup.get(k);
        if (lu && lu.title) title = String(lu.title);
      }
      if (!title) title = 'Album';
      const sectionKey = sec || String(prev.sectionKey || '');
      const libLabel =
        (sec && plexSectionNameByKey[sec]) ||
        (prev.sectionKey && plexSectionNameByKey[String(prev.sectionKey)]) ||
        prev.subtitle ||
        '';
      const parentTitle = String(a.parentTitle || prev.parentTitle || '');
      byKey.set(k, {
        kind: 'album',
        ratingKey: k,
        sectionKey,
        title,
        parentTitle,
        subtitle: libLabel
      });
    }

    const mergedAlbums = Array.from(byKey.values());
    const trackSlice = hubTracks;
    panel.innerHTML = buildUnifiedSearchHtml(radioHits, mergedAlbums, trackSlice, rawQuery);
  }

  function updateFilterState() {
    const activeVersion = ++filterVersion;
    const searchInput = $('inSearch');
    searchQuery = searchInput.value.trim().toLowerCase();
    const query = searchQuery;
    const rawSearchInput = searchInput.value.trim();
    syncSearchUi();
    if (!rawSearchInput) {
      if (unifiedSearchTimer) {
        clearTimeout(unifiedSearchTimer);
        unifiedSearchTimer = 0;
      }
      unifiedSearchSeq++;
      const usp = $('unifiedSearchPanel');
      if (usp) {
        usp.hidden = true;
        usp.innerHTML = '';
      }
    } else {
      scheduleUnifiedSearch(rawSearchInput, activeVersion);
    }
    const albumGrid = $('albumGrid');
    const tracksWrap = $('viewTracks');
    let albumMatches = 0;
    let trackMatches = 0;
    const feedback = $('searchFeedback');
    if (navView === 'radio') {
      renderRadioView();
      if (feedback) {
        const count = $('viewRadio').querySelectorAll('.radio-item').length;
        const parts = [];
        if (query) parts.push('query "' + query + '"');
        parts.push(count + ' station' + (count === 1 ? '' : 's'));
        feedback.textContent = parts.join(' · ');
      }
      return;
    }

    const updateAlbumCards = async () => {
      if (!albumGrid) return;
      if (trackPrefetchTriedForQuery !== query) {
        trackPrefetchTriedForQuery = query;
        trackPrefetchTried.clear();
      }
      const cards = Array.from(albumGrid.querySelectorAll('.album'));
      if (query) await ensureActiveSearchIndexLoaded();
      if (activeVersion !== filterVersion) return;

      const prefetchKeys = [];
      cards.forEach((card) => {
        const key = String(card.dataset.key || '');
        const albumHit = !query || searchMatchesBlob(card.dataset.search || '', query);
        const trackData = query ? getCachedTrackSearchPayload(card.dataset.key) : null;
        const trackHit = !albumHit && !!trackData && searchMatchesBlob(trackData.search, query);
        const hit = albumHit || trackHit;
        card.style.display = hit ? '' : 'none';
        if (hit) albumMatches++;
        renderAlbumSongMatches(card, query, trackData, albumHit);
        if (query && !albumHit && !trackHit && key && !trackPrefetchTried.has(key)) {
          const td = getCachedTrackSearchPayload(key);
          if (!td) prefetchKeys.push(key);
        }
      });

      if (query && prefetchKeys.length) {
        const slice = prefetchKeys.slice(0, 56);
        for (let pi = 0; pi < slice.length; pi++) trackPrefetchTried.add(slice[pi]);
        Promise.all(slice.map((k) => ensureAlbumTrackSearch(k)))
          .then(() => {
            if (activeVersion === filterVersion) updateFilterState();
          })
          .catch(() => {});
      }

      let empty = $('albumFilterEmpty');
      if (!empty) {
        empty = document.createElement('p');
        empty.className = 'empty-state';
        empty.id = 'albumFilterEmpty';
        empty.textContent = 'No albums or songs matched this search.';
        albumGrid.parentElement.appendChild(empty);
      }
      empty.style.display = albumMatches ? 'none' : '';
      scheduleAlbumAutoLoad(40);
    };

    const updateTrackRows = () => {
      if (tracksWrap.style.display === 'none') return;
      tracksWrap.querySelectorAll('.tracks li').forEach((row) => {
        const hit = !query || searchMatchesBlob(row.dataset.search || '', query);
        row.style.display = hit ? '' : 'none';
        if (hit) trackMatches++;
      });
      let emptyTracks = $('trackFilterEmpty');
      if (!emptyTracks) {
        emptyTracks = document.createElement('p');
        emptyTracks.className = 'empty-state';
        emptyTracks.id = 'trackFilterEmpty';
        emptyTracks.textContent = 'No tracks matched this search.';
        const list = tracksWrap.querySelector('.tracks');
        if (list) list.after(emptyTracks);
      }
      if (emptyTracks) emptyTracks.style.display = trackMatches ? 'none' : '';
    };

    if (navView === 'favorites' || navView === 'recent') {
      renderLibraryView();
    }
    updateTrackRows();
    updateAlbumCards().catch((err) => {
      console.error(err);
    });
    if (feedback) {
      const parts = [];
      if (query) parts.push('query "' + query + '"');
      const tracksPaneVisible = tracksWrap && tracksWrap.style.display !== 'none';
      if (tracksPaneVisible) {
        if (query) {
          parts.push(trackMatches + ' track' + (trackMatches === 1 ? '' : 's'));
        }
      } else if (albumGrid || (navView === 'albums' && albumTotalCount > 0)) {
        const showLibraryTotalOnly =
          !query &&
          navView === 'albums' &&
          currentArtistKey == null &&
          albumTotalCount > 0;
        if (showLibraryTotalOnly) {
          parts.push(albumTotalCount + ' album' + (albumTotalCount === 1 ? '' : 's'));
        } else if (albumGrid) {
          parts.push(albumMatches + ' album' + (albumMatches === 1 ? '' : 's'));
        }
      }
      feedback.textContent = parts.join(' · ');
    }

    clearTimeout(hubSearchFallbackTimer);
    if (navView === 'albums' && query && query.length > 2) {
      hubSearchFallbackTimer = setTimeout(() => {
        try {
          const inp = $('inSearch');
          if (!inp || normalizeSearchText(inp.value) !== query) return;
          const albumsPane = $('viewAlbums');
          const tracksPane = $('viewTracks');
          if (!albumsPane || albumsPane.style.display === 'none') return;
          if (tracksPane && tracksPane.style.display === 'block') return;
          const grid = $('albumGrid');
          if (!grid || !grid.querySelectorAll('.album').length) return;
          if (countVisibleAlbumCards(grid) > 0) return;
          const raw = inp.value;
          plexSearchHubTracks(raw)
            .then((tracks) => {
              if (!tracks.length) return;
              const t = tracks[0];
              const ak = t && t.parentRatingKey;
              if (!ak) return;
              const iq = $('inSearch');
              if (!iq || normalizeSearchText(iq.value) !== query) return;
              return openAlbumByKey(String(ak)).then(() => {
                const hint = normalizeSearchText(String(t.title || '').split('(')[0]).trim();
                if (iq && hint.length > 2) {
                  iq.value = hint;
                  iq.dispatchEvent(new Event('input', { bubbles: true }));
                }
                const fb = $('searchFeedback');
                if (fb) fb.textContent = (fb.textContent || '') + ' · opened from server search';
              });
            })
            .catch(() => {});
        } catch (_) {}
      }, 950);
    }
  }

  function syncSearchUi() {
    const inp = $('inSearch');
    if (!inp) return;
    const wrap = inp.closest('.search-wrap');
    if (!wrap) return;
    wrap.classList.toggle('has-value', !!String(inp.value || '').trim());
  }

  function clearSearchInput(opts) {
    const inp = $('inSearch');
    if (!inp) return;
    if (inp.value) inp.value = '';
    if (filterDebounceTimer) {
      clearTimeout(filterDebounceTimer);
      filterDebounceTimer = 0;
    }
    updateFilterState();
    if (opts && opts.focus) inp.focus();
  }

  async function ensureAlbumTrackSearch(albumKey) {
    if (!albumKey) return { search: '', titles: [] };
    if (albumTrackSearchCache.has(albumKey)) return albumTrackSearchCache.get(albumKey);
    if (albumTrackFetchCache.has(albumKey)) return albumTrackFetchCache.get(albumKey);
    const section = activeSectionKey();
    if (section) {
      const cached = await getSearchRecord(section, String(albumKey));
      if (cached && cached.trackSearch) {
        const payload = { search: String(cached.trackSearch || ''), titles: Array.isArray(cached.titles) ? cached.titles : [] };
        albumTrackSearchCache.set(String(albumKey), payload);
        albumSearchIndexCache.set(String(albumKey), {
          albumSearch: String(cached.albumSearch || ''),
          trackSearch: payload.search,
          titles: payload.titles
        });
        return payload;
      }
    }
    const promise = plexFetch('/library/metadata/' + albumKey + '/children')
      .then((data) => {
        const tracks = (data.MediaContainer.Metadata || []).filter(isPlexAudioLeafForPlayback);
        const titles = tracks.map((t) => String(t.title || '').trim()).filter(Boolean);
        const search = tracks.map((t) => makeSearchBlob(t)).join(' ');
        const payload = { search, titles };
        albumTrackSearchCache.set(albumKey, payload);
        const existing = albumSearchIndexCache.get(String(albumKey)) || {};
        albumSearchIndexCache.set(String(albumKey), {
          albumSearch: String(existing.albumSearch || ''),
          trackSearch: search,
          titles
        });
        if (section) {
          const record = {
            id: searchRecordId(section, albumKey),
            sectionKey: section,
            albumKey: String(albumKey),
            albumSearch: String(existing.albumSearch || ''),
            trackSearch: search,
            titles,
            updatedAt: Date.now()
          };
          putSearchRecords([record]).catch(() => {});
        }
        albumTrackFetchCache.delete(albumKey);
        return payload;
      })
      .catch((err) => {
        albumTrackFetchCache.delete(albumKey);
        throw err;
      });
    albumTrackFetchCache.set(albumKey, promise);
    return promise;
  }

  function renderAlbumSongMatches(card, query, trackData, albumHit) {
    const box = card.querySelector('.song-matches');
    if (!box || !query || !trackData || !trackData.titles.length || albumHit) {
      if (box) {
        box.textContent = '';
        box.style.display = 'none';
      }
      return;
    }
    const matches = trackData.titles.filter((title) => searchMatchesBlob(title, query));
    if (!matches.length) {
      box.textContent = '';
      box.style.display = 'none';
      return;
    }
    const top = matches.slice(0, 3).map(escapeHtml).join(' • ');
    const extra = matches.length > 3 ? ' +' + (matches.length - 3) + ' more' : '';
    box.innerHTML = 'Song match: ' + top + extra;
    box.style.display = 'block';
  }

  function syncSidebarNavActive() {
    document.querySelectorAll('.sidebar-nav-btn[data-nav]').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.nav === navView);
    });
    updatePlexLibraryLinkActive();
  }

  function setNavView(next, opts) {
    const o = opts || {};
    navView = next;
    if (o.updateLocation !== false) {
      const nextHash = '#' + (next || 'albums');
      if (location.hash !== nextHash) location.hash = nextHash;
    }
    syncSidebarNavActive();
    if (next === 'radio') {
      $('viewLibrary').style.display = 'none';
      $('viewPlaylists').style.display = 'none';
      $('viewTracks').style.display = 'none';
      $('viewAlbums').style.display = 'none';
      $('viewRadio').style.display = 'block';
      $('viewYouTube').style.display = 'none';
      $('albumViewToggle').style.display = 'none';
      renderRadioView();
      updateFilterState();
      return;
    }
    if (next === 'youtube') {
      $('viewLibrary').style.display = 'none';
      $('viewPlaylists').style.display = 'none';
      $('viewTracks').style.display = 'none';
      $('viewAlbums').style.display = 'none';
      $('viewRadio').style.display = 'none';
      $('viewYouTube').style.display = 'block';
      $('albumViewToggle').style.display = 'none';
      renderYouTubeView();
      updateFilterState();
      return;
    }
    if (next === 'playlists') {
      $('viewLibrary').style.display = 'none';
      $('viewPlaylists').style.display = 'block';
      $('viewTracks').style.display = 'none';
      $('viewAlbums').style.display = 'none';
      $('viewRadio').style.display = 'none';
      $('viewYouTube').style.display = 'none';
      $('albumViewToggle').style.display = 'none';
      renderPlaylistsView();
      return;
    }
    const isLibrary = next === 'favorites' || next === 'recent';
    $('viewLibrary').style.display = isLibrary ? 'block' : 'none';
    $('viewPlaylists').style.display = 'none';
    if (isLibrary) {
      $('viewAlbums').style.display = 'none';
      $('viewTracks').style.display = 'none';
      $('viewRadio').style.display = 'none';
      $('viewYouTube').style.display = 'none';
      $('albumViewToggle').style.display = 'none';
      renderLibraryView();
      return;
    }
    $('viewLibrary').style.display = 'none';
    $('viewPlaylists').style.display = 'none';
    $('viewTracks').style.display = 'none';
    $('viewAlbums').style.display = 'block';
    $('viewRadio').style.display = 'none';
    $('viewYouTube').style.display = 'none';
    $('albumViewToggle').style.display = '';
    updateFilterState();
  }

  function trackDisplayText(item) {
    const title = String(item.title || item.trackTitle || '').trim();
    const artist = String(item.artist || item.grandparentTitle || item.albumParentTitle || '');
    const name = title || effectiveTrackListName(item);
    if (!artist) return name;
    return name + ' - ' + artist;
  }

  function renderLibraryView() {
    const wrap = $('viewLibrary');
    if (!wrap) return;
    const starredTrackItems = getStarredTrackItems();
    const starredAlbumItems = getStarredAlbumItems();
    const starredTracks = [...getStarredTracks()]
      .map((key) => trackLookup.get(String(key)) || starredTrackItems[String(key)])
      .filter(Boolean);
    const starredAlbums = [...getStarredAlbums()]
      .map((key) => albumLookup.get(String(key)) || starredAlbumItems[String(key)])
      .filter(Boolean);
    const starredStations = [...getStarredStations()]
      .map((id) => radioStations.find((station) => station.id === id))
      .filter(Boolean);
    const recent = getRecentTracks();
    const showFavorites = navView === 'favorites';
    const showRecent = navView === 'recent';

    const favoriteTrackHtml = starredTracks.length
      ? starredTracks
          .map((t, idx) => '<li><button type="button" data-track-index="' + idx + '" data-set="fav">' + escapeHtml(trackDisplayText(t)) + '</button></li>')
          .join('')
      : '<li class="empty-state">Star a track to pin it here.</li>';
    const favoriteAlbumHtml = starredAlbums.length
      ? starredAlbums
          .map((a) => '<li><button type="button" data-open-album="' + escapeHtml(String(a.ratingKey || '')) + '">' + escapeHtml(a.title || '[Album]') + '</button></li>')
          .join('')
      : '<li class="empty-state">Star an album to save it here.</li>';
    const favoriteStationHtml = starredStations.length
      ? starredStations
          .map((station) => '<li><button type="button" data-play-station="' + escapeHtml(station.id) + '">' + escapeHtml(station.title) + '</button></li>')
          .join('')
      : '<li class="empty-state">Star a station in Radio to pin it here.</li>';
    const recentHtml = recent.length
      ? recent
          .map((t, idx) => '<li><button type="button" data-track-index="' + idx + '" data-set="recent">' + escapeHtml(trackDisplayText(t)) + '</button></li>')
          .join('')
      : '<li class="empty-state">Play songs to build your history.</li>';

    wrap.innerHTML =
      '<div class="library-grid">' +
      (showFavorites
        ? '<section class="library-card"><h3>Favorite Tracks</h3><ul class="library-list">' + favoriteTrackHtml + '</ul></section>' +
          '<section class="library-card"><h3>Favorite Albums</h3><ul class="library-list">' + favoriteAlbumHtml + '</ul></section>' +
          '<section class="library-card"><h3>Favorite Stations</h3><ul class="library-list">' + favoriteStationHtml + '</ul></section>'
        : '') +
      (showRecent ? '<section class="library-card"><h3>Recently Played</h3><ul class="library-list">' + recentHtml + '</ul></section>' : '') +
      '</div>';

    wrap.querySelectorAll('button[data-open-album]').forEach((btn) => {
      btn.onclick = () => openAlbumByKey(btn.dataset.openAlbum);
    });
    wrap.querySelectorAll('button[data-track-index]').forEach((btn) => {
      btn.onclick = () => {
        const idx = Number(btn.dataset.trackIndex || -1);
        if (idx < 0) return;
        const source = btn.dataset.set === 'recent' ? recent[idx] : starredTracks[idx];
        if (!source || !getFirstPlayablePartKey(source)) return;
        currentAlbum = {
          ratingKey: source.parentRatingKey || source.albumRatingKey || '',
          title: source.parentTitle || source.albumTitle || '',
          parentTitle: source.grandparentTitle || source.artist || ''
        };
        currentTracks = [source];
        playIndex(0, { crossfade: false });
      };
    });
    wrap.querySelectorAll('button[data-play-station]').forEach((btn) => {
      btn.onclick = () => {
        const id = btn.dataset.playStation || '';
        const idx = radioStations.findIndex((station) => station.id === id);
        if (idx >= 0) playRadioIndex(idx);
      };
    });
  }

  function playlistIdFromFilename(filename) {
    return String(filename || '').replace(/\.csv$/i, '');
  }

  function playlistNameFromFilename(filename) {
    return playlistIdFromFilename(filename)
      .replace(/_/g, ' ')
      .trim();
  }

  function parseCsvRows(text) {
    const src = String(text || '');
    const rows = [];
    let row = [];
    let cell = '';
    let inQuotes = false;
    for (let i = 0; i < src.length; i++) {
      const ch = src[i];
      if (ch === '"') {
        if (inQuotes && src[i + 1] === '"') {
          cell += '"';
          i += 1;
        } else {
          inQuotes = !inQuotes;
        }
      } else if (ch === ',' && !inQuotes) {
        row.push(cell);
        cell = '';
      } else if ((ch === '\n' || ch === '\r') && !inQuotes) {
        if (ch === '\r' && src[i + 1] === '\n') i += 1;
        row.push(cell);
        cell = '';
        if (row.some((v) => String(v).trim() !== '')) rows.push(row);
        row = [];
      } else {
        cell += ch;
      }
    }
    if (cell.length || row.length) {
      row.push(cell);
      if (row.some((v) => String(v).trim() !== '')) rows.push(row);
    }
    return rows;
  }

  function csvHeaderIndexMap(header) {
    const map = {};
    (header || []).forEach((name, idx) => {
      map[String(name || '').trim().toLowerCase()] = idx;
    });
    return map;
  }

  function csvVal(row, indexMap, headerName) {
    const idx = indexMap[String(headerName || '').toLowerCase()];
    if (typeof idx !== 'number' || idx < 0 || idx >= row.length) return '';
    return String(row[idx] || '').trim();
  }

  function parseSpotifyCsvTrack(row, indexMap) {
    const trackName = csvVal(row, indexMap, 'Track Name');
    const uri = csvVal(row, indexMap, 'Track URI');
    const artistsRaw = csvVal(row, indexMap, 'Artist Name(s)');
    if (!trackName && !uri) return null;
    const artists = artistsRaw
      .split(/[;,]/g)
      .map((s) => String(s || '').trim())
      .filter(Boolean);
    const durationMs = Number(csvVal(row, indexMap, 'Duration (ms)'));
    return {
      uri: uri,
      trackName: trackName,
      artistsRaw: artistsRaw,
      artists: artists,
      albumName: csvVal(row, indexMap, 'Album Name'),
      durationMs: Number.isFinite(durationMs) ? durationMs : null,
      addedAt: csvVal(row, indexMap, 'Added At')
    };
  }

  async function loadSpotifyCsvPlaylists(files) {
    for (const file of Array.from(files || [])) {
      const rows = parseCsvRows(await file.text());
      if (!rows.length) continue;
      const indexMap = csvHeaderIndexMap(rows[0]);
      if (indexMap['track name'] == null && indexMap['track uri'] == null) {
        throw new Error('CSV needs Track Name or Track URI columns.');
      }
      const tracks = rows.slice(1).map((row) => parseSpotifyCsvTrack(row, indexMap)).filter(Boolean);
      const id = playlistIdFromFilename(file.name);
      const playlist = { id, filename: file.name, name: playlistNameFromFilename(file.name), tracks, trackCount: tracks.length };
      playlistCsvState.playlists = playlistCsvState.playlists.filter((item) => item.id !== id).concat(playlist);
      delete playlistCsvState.matchesById[id];
      playlistCsvState.selectedId = id;
    }
    playlistCsvState.playlists.sort((a, b) => a.name.localeCompare(b.name));
    playlistCsvState.loaded = true;
  }

  function normalizeTrackToken(s) {
    return String(s || '')
      .toLowerCase()
      .normalize('NFKD')
      .replace(/[\u0300-\u036f]/g, '')
      .replace(/\([^)]*\)|\[[^\]]*\]/g, ' ')
      .replace(/\b(feat|ft|remaster(?:ed)?|version|edit|mix)\b.*$/g, ' ')
      .replace(/[^a-z0-9]+/g, ' ')
      .trim();
  }

  function scorePlexCandidate(csvTrack, plexTrack) {
    const wantTitle = normalizeTrackToken(csvTrack.trackName);
    const gotTitle = normalizeTrackToken(plexTrack && plexTrack.title ? plexTrack.title : '');
    const wantArtist = normalizeTrackToken(csvTrack.artists && csvTrack.artists[0] ? csvTrack.artists[0] : csvTrack.artistsRaw);
    const gotArtist = normalizeTrackToken(plexTrack && plexTrack.artist ? plexTrack.artist : '');
    let score = 0;
    if (wantTitle && gotTitle && wantTitle === gotTitle) score += 70;
    else if (wantTitle && gotTitle && (gotTitle.indexOf(wantTitle) >= 0 || wantTitle.indexOf(gotTitle) >= 0)) score += 45;
    if (wantArtist && gotArtist && wantArtist === gotArtist) score += 30;
    else if (wantArtist && gotArtist && (gotArtist.indexOf(wantArtist) >= 0 || wantArtist.indexOf(gotArtist) >= 0)) score += 20;
    if (csvTrack.uri && String(csvTrack.uri).trim()) score += 1;
    return score;
  }

  async function fetchPlexTrackCandidates(libraryId, query) {
    const q = String(query || '').trim();
    const lib = String(libraryId || '').trim();
    if (!lib || !q) return [];
    const cacheKey = lib + '::' + q.toLowerCase();
    if (playlistCsvState.searchCache.has(cacheKey)) return playlistCsvState.searchCache.get(cacheKey);
    // Use direct Plex token-based search (same path as main UI) so playlists work
    // in standalone mode and do not require friend/session auth used by /tracks.
    const data = await plexFetch(
      '/library/sections/' + encodeURIComponent(lib) + '/all?type=10&query=' + encodeURIComponent(q)
    );
    const meta = data && data.MediaContainer && Array.isArray(data.MediaContainer.Metadata)
      ? data.MediaContainer.Metadata
      : [];
    const tracks = meta
      .filter((t) => t && t.ratingKey && t.title)
      .map((t) => ({
        ratingKey: String(t.ratingKey),
        title: String(t.title || ''),
        artist: String(t.grandparentTitle || t.originalTitle || '')
      }));
    playlistCsvState.searchCache.set(cacheKey, tracks);
    return tracks;
  }

  async function matchPlaylistToPlex(playlist) {
    const sectionKey = String($('inSection').value || cfg().sectionKey || '').trim();
    if (!sectionKey) throw new Error('Choose a Plex library first.');
    const rows = Array.isArray(playlist && playlist.tracks) ? playlist.tracks : [];
    const matched = [];
    const ambiguous = [];
    const missing = [];
    for (let i = 0; i < rows.length; i++) {
      const row = rows[i];
      const artistLead = row.artists && row.artists.length ? row.artists[0] : row.artistsRaw;
      const q1 = [row.trackName, artistLead].filter(Boolean).join(' ');
      const q2 = row.trackName || '';
      const candsPrimary = await fetchPlexTrackCandidates(sectionKey, q1);
      const candsSecondary = q2 && q2 !== q1 ? await fetchPlexTrackCandidates(sectionKey, q2) : [];
      const byKey = new Map();
      candsPrimary.concat(candsSecondary).forEach((t) => {
        if (!t || !t.ratingKey) return;
        byKey.set(String(t.ratingKey), t);
      });
      const scored = Array.from(byKey.values())
        .map((t) => ({ track: t, score: scorePlexCandidate(row, t) }))
        .sort((a, b) => b.score - a.score);
      if (!scored.length || scored[0].score < 60) {
        missing.push({ index: i, source: row });
        continue;
      }
      const best = scored[0];
      const second = scored[1];
      if (best.score >= 85 && (!second || best.score - second.score >= 8)) {
        matched.push({ index: i, source: row, track: best.track, score: best.score });
      } else {
        ambiguous.push({
          index: i,
          source: row,
          candidates: scored.slice(0, 4).map((s) => ({ track: s.track, score: s.score }))
        });
      }
    }
    return {
      playlistId: playlist.id,
      playlistName: playlist.name,
      sectionKey: sectionKey,
      generatedAt: new Date().toISOString(),
      stats: {
        total: rows.length,
        matched: matched.length,
        ambiguous: ambiguous.length,
        missing: missing.length
      },
      matched: matched,
      ambiguous: ambiguous,
      missing: missing
    };
  }

  function downloadBlob(name, blob) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 250);
  }

  function downloadReportJson(filename, data) {
    downloadBlob(filename, new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
  }

  function csvEscape(v) {
    const s = String(v == null ? '' : v);
    if (/[",\n\r]/.test(s)) return '"' + s.replace(/"/g, '""') + '"';
    return s;
  }

  function downloadMissingCsv(filename, report) {
    const rows = [['status', 'track_uri', 'track_name', 'artist', 'album', 'added_at', 'top_candidate', 'top_score']];
    (report.missing || []).forEach((r) => {
      rows.push(['missing', r.source.uri, r.source.trackName, r.source.artistsRaw, r.source.albumName, r.source.addedAt, '', '']);
    });
    (report.ambiguous || []).forEach((r) => {
      const top = r.candidates && r.candidates[0];
      rows.push([
        'ambiguous',
        r.source.uri,
        r.source.trackName,
        r.source.artistsRaw,
        r.source.albumName,
        r.source.addedAt,
        top && top.track ? (top.track.title + ' - ' + top.track.artist) : '',
        top ? top.score : ''
      ]);
    });
    const text = rows.map((r) => r.map(csvEscape).join(',')).join('\n');
    downloadBlob(filename, new Blob([text], { type: 'text/csv;charset=utf-8' }));
  }

  async function hydrateMatchedTracksForPlayback(report, maxItems) {
    const cap = Math.max(1, Math.min(200, Number(maxItems || 80)));
    const items = (report.matched || []).slice(0, cap);
    const out = [];
    for (let i = 0; i < items.length; i++) {
      const m = items[i];
      const rk = m && m.track && m.track.ratingKey ? String(m.track.ratingKey) : '';
      if (!rk) continue;
      const metaUrl = metadataUrlForRatingKey(rk);
      if (!metaUrl) continue;
      try {
        const res = await fetch(metaUrl, {
          cache: 'no-store',
          credentials: 'same-origin',
          headers: {
            Accept: 'application/json'
          }
        });
        if (!res.ok) continue;
        const payload = await res.json().catch(() => null);
        const mc = payload && payload.MediaContainer ? payload.MediaContainer : {};
        const list = plexMetadataArray(mc).filter(isPlexAudioLeafForPlayback);
        if (list.length) out.push(list[0]);
      } catch (_) {}
    }
    return out;
  }

  async function playMatchedPlaylistSubset(report) {
    const hydrated = await hydrateMatchedTracksForPlayback(report, 120);
    if (!hydrated.length) throw new Error('No playable matches found in Plex.');
    currentAlbum = {
      ratingKey: '',
      title: (report && report.playlistName) ? report.playlistName : 'Playlist',
      parentTitle: 'Spotify CSV'
    };
    currentTracks = hydrated;
    playIndex(0, { crossfade: false });
  }

  function selectedPlaylist() {
    return playlistCsvState.playlists.find((p) => p.id === playlistCsvState.selectedId) || null;
  }

  function renderPlaylistTrackRows(items) {
    const list = Array.isArray(items) ? items : [];
    if (!list.length) return '<li class="empty-state">No tracks.</li>';
    return list
      .map((row, idx) => {
        const durSec = row && Number.isFinite(Number(row.durationMs)) ? Math.round(Number(row.durationMs) / 1000) : 0;
        const mm = Math.floor(durSec / 60);
        const ss = durSec % 60;
        const durStr = durSec ? mm + ':' + (ss < 10 ? '0' : '') + ss : '';
        const name = trackDisplayText({
          title: row && row.trackName ? row.trackName : '',
          artist: row && row.artistsRaw ? row.artistsRaw : ''
        });
        return (
          '<li><span class="idx">' +
          (idx + 1) +
          '</span><span class="name">' +
          escapeHtml(name) +
          '</span><span class="dur">' +
          escapeHtml(durStr) +
          '</span></li>'
        );
      })
      .join('');
  }

  function playlistCoveragePct(report, totalFallback) {
    const total = report && report.stats ? Number(report.stats.total) : Number(totalFallback || 0);
    const matched = report && report.stats ? Number(report.stats.matched) : 0;
    if (!Number.isFinite(total) || total <= 0) return 0;
    return Math.round((matched / total) * 100);
  }

  function playlistCoverageClass(pct) {
    const n = Number(pct || 0);
    if (n >= 70) return 'ok';
    if (n >= 35) return 'warn';
    return 'bad';
  }

  function renderPlaylistsView() {
    const wrap = $('viewPlaylists');
    if (!wrap) return;
    const doRender = () => {
      const selected = selectedPlaylist();
      const playlistItemsHtml = playlistCsvState.playlists.length
        ? playlistCsvState.playlists
            .map((p) => {
              const report = playlistCsvState.matchesById[p.id];
              const matched = report ? report.stats.matched : '-';
              const ambiguous = report ? report.stats.ambiguous : '-';
              const missing = report ? report.stats.missing : '-';
              const coverage = report ? playlistCoveragePct(report, p.trackCount) + '%' : '—';
              const active = p.id === playlistCsvState.selectedId ? ' active' : '';
              const covClass = report ? playlistCoverageClass(playlistCoveragePct(report, p.trackCount)) : '';
              return (
                '<tr class="' + active + '" data-playlist-id="' + escapeHtml(p.id) + '">' +
                '<td>' + escapeHtml(p.name) + '</td>' +
                '<td>' + escapeHtml(String(p.trackCount)) + '</td>' +
                '<td>' + escapeHtml(String(matched)) + '</td>' +
                '<td>' + escapeHtml(String(ambiguous)) + '</td>' +
                '<td>' + escapeHtml(String(missing)) + '</td>' +
                '<td><span class="playlist-stat ' + covClass + '">' + escapeHtml(String(coverage)) + '</span></td>' +
                '</tr>'
              );
            })
            .join('')
        : '<tr><td colspan="6" class="empty-state">No CSV playlists found.</td></tr>';
      let detailHtml = '<p class="empty-state">Select a playlist.</p>';
      if (selected) {
        const report = playlistCsvState.matchesById[selected.id];
        const statusLine = report
          ? ('Matched ' + report.stats.matched + ' / ' + report.stats.total + ', ambiguous ' + report.stats.ambiguous + ', missing ' + report.stats.missing + '.')
          : 'No Plex matching run yet.';
        const sampleRows = report
          ? report.missing.map((m) => m.source)
          : selected.tracks;
        const sample = renderPlaylistTrackRows(sampleRows);
        const coveragePct = report ? playlistCoveragePct(report, selected.trackCount) : 0;
        const coverageClass = report ? playlistCoverageClass(coveragePct) : '';
        detailHtml =
          '<h3 style="margin:0 0 .55rem">' + escapeHtml(selected.name) + '</h3>' +
          '<div class="playlist-summary-row">' +
          '<span class="playlist-chip">tracks: ' + escapeHtml(String(selected.trackCount)) + '</span>' +
          (report ? '<span class="playlist-chip">matched: ' + escapeHtml(String(report.stats.matched)) + '</span>' : '') +
          (report ? '<span class="playlist-chip">ambiguous: ' + escapeHtml(String(report.stats.ambiguous)) + '</span>' : '') +
          (report ? '<span class="playlist-chip">missing: ' + escapeHtml(String(report.stats.missing)) + '</span>' : '') +
          (report ? '<span class="playlist-chip">coverage: <span class="playlist-stat ' + coverageClass + '">' + escapeHtml(String(coveragePct)) + '%</span></span>' : '') +
          '</div>' +
          '<p class="inline-note" style="margin:0 0 .7rem">' + escapeHtml(statusLine) + '</p>' +
          '<div class="inline-actions" style="margin:0 0 .75rem">' +
          '<button type="button" class="btn" data-action="match">Match in Plex</button> ' +
          '<button type="button" class="btn" data-action="play">Play matched subset</button> ' +
          '<button type="button" class="btn" data-action="json">Download report JSON</button> ' +
          '<button type="button" class="btn" data-action="csv">Download missing CSV</button>' +
          '</div>' +
          '<ul class="tracks">' + sample + '</ul>';
      }
      wrap.innerHTML =
        '<div class="library-grid">' +
        '<section class="library-card"><h3>Playlists (CSV)</h3><label>Import Spotify CSV files <input id="playlistCsvFiles" type="file" accept=".csv,text/csv" multiple></label><p class="msg">Files stay in this browser tab. Matching sends track searches to your configured Plex server.</p><div class="playlist-table-wrap"><table class="playlist-table"><thead><tr><th>Playlist</th><th>Tracks</th><th>Matched</th><th>Ambig</th><th>Missing</th><th>Coverage</th></tr></thead><tbody>' + playlistItemsHtml + '</tbody></table></div></section>' +
        '<section class="library-card" id="playlistDetailCard">' + detailHtml + '</section>' +
        '</div>';

      $('playlistCsvFiles').onchange = async (event) => {
        try {
          await loadSpotifyCsvPlaylists(event.target.files);
          renderPlaylistsView();
        } catch (err) {
          const message = document.createElement('p');
          message.className = 'msg error';
          message.textContent = err.message || String(err);
          wrap.prepend(message);
        }
      };

      wrap.querySelectorAll('tr[data-playlist-id]').forEach((row) => {
        row.onclick = () => {
          playlistCsvState.selectedId = String(row.dataset.playlistId || '');
          renderPlaylistsView();
        };
      });
      const detailCard = $('playlistDetailCard');
      if (!detailCard) return;
      const selectedNow = selectedPlaylist();
      if (!selectedNow) return;
      const runAction = async (action) => {
        try {
          if (action === 'match') {
            setSettingsStatus('Matching playlist in Plex…', '');
            const report = await matchPlaylistToPlex(selectedNow);
            playlistCsvState.matchesById[selectedNow.id] = report;
            setSettingsStatus('Playlist matching complete.', 'ok');
            renderPlaylistsView();
          } else if (action === 'play') {
            const report = playlistCsvState.matchesById[selectedNow.id];
            if (!report) throw new Error('Run Match in Plex first.');
            await playMatchedPlaylistSubset(report);
          } else if (action === 'json') {
            const report = playlistCsvState.matchesById[selectedNow.id];
            if (!report) throw new Error('Run Match in Plex first.');
            downloadReportJson('playlist-' + selectedNow.id + '-report.json', report);
          } else if (action === 'csv') {
            const report = playlistCsvState.matchesById[selectedNow.id];
            if (!report) throw new Error('Run Match in Plex first.');
            downloadMissingCsv('playlist-' + selectedNow.id + '-missing.csv', report);
          }
        } catch (err) {
          setSettingsStatus(err && err.message ? err.message : String(err), 'error');
        }
      };
      detailCard.querySelectorAll('button[data-action]').forEach((btn) => {
        btn.onclick = () => runAction(String(btn.dataset.action || ''));
      });
    };

    if (!playlistCsvState.loaded && !playlistCsvState.loading) {
      wrap.innerHTML = '<p class="msg"><span class="spinner"></span> Loading playlists…</p>';
      loadSpotifyCsvPlaylists().then(() => {
        if (playlistCsvState.error) {
          wrap.innerHTML = '<p class="msg error">' + escapeHtml(playlistCsvState.error) + '</p>';
          return;
        }
        doRender();
      });
      return;
    }
    if (playlistCsvState.loading) {
      wrap.innerHTML = '<p class="msg"><span class="spinner"></span> Loading playlists…</p>';
      return;
    }
    if (playlistCsvState.error) {
      wrap.innerHTML = '<p class="msg error">' + escapeHtml(playlistCsvState.error) + '</p>';
      return;
    }
    doRender();
  }

  function titleFromStationId(id) {
    return String(id || '')
      .replace(/_/g, ' ')
      .replace(/\b\w/g, (m) => m.toUpperCase());
  }

  function sanitizeYouTubeStatusMessage(msg) {
    return String(msg || '').replace(/\s+/g, ' ').trim();
  }

  function formatYouTubeAttempts(attempts) {
    if (!Array.isArray(attempts) || !attempts.length) return '';
    return attempts
      .map((att) => {
        const host = String(att && att.instance ? att.instance : '').replace(/^https?:\/\//, '');
        if (att && att.ok) return host + ': ok';
        const err = String(att && att.error ? att.error : 'failed');
        return host + ': ' + err;
      })
      .join(' | ');
  }

  function setYouTubeStatus(msg, kind) {
    const el = $('youtubeStatus');
    if (!el) return;
    el.textContent = sanitizeYouTubeStatusMessage(msg);
    el.classList.remove('error', 'ok');
    if (kind === 'error') el.classList.add('error');
    if (kind === 'ok') el.classList.add('ok');
  }

  async function updateYouTubeExternalLinksPanel(videoId, linksFromResolve) {
    const box = $('youtubeExternalLinks');
    if (!box) return;
    const id = String(videoId || '').trim();
    if (!id || !/^[A-Za-z0-9_-]{11}$/.test(id)) {
      box.innerHTML = '';
      box.hidden = true;
      return;
    }
    box.hidden = false;
    let links = Array.isArray(linksFromResolve) ? linksFromResolve : null;
    if (!links || !links.length) {
      try {
        const res = await fetch('/api/youtube/external-links?videoId=' + encodeURIComponent(id), {
          cache: 'no-store',
          credentials: 'same-origin'
        });
        const data = await res.json().catch(() => null);
        links = data && Array.isArray(data.externalLinks) ? data.externalLinks : [];
      } catch (_) {
        links = [];
      }
    }
    if (!links.length) {
      box.innerHTML = '<p class="youtube-note">No external link hints.</p>';
      return;
    }
    box.innerHTML =
      '<h4>Check mirrors (open in new tab)</h4><ul>' +
      links
        .map(function (row) {
          const label = escapeHtml(String(row && row.label ? row.label : 'Link'));
          const href = String(row && row.url ? row.url : '');
          return (
            '<li><span class="link-label">' +
            label +
            '</span><a href="' +
            escapeHtml(href) +
            '" target="_blank" rel="noopener">' +
            escapeHtml(href) +
            '</a></li>'
          );
        })
        .join('') +
      '</ul>';
  }

  function extractYouTubeVideoId(rawInput) {
    const raw = String(rawInput || '').trim();
    if (!raw) return '';
    if (/^[A-Za-z0-9_-]{11}$/.test(raw)) return raw;
    let parsed = null;
    try {
      parsed = new URL(raw);
    } catch (_) {
      return '';
    }
    const host = String(parsed.hostname || '').toLowerCase();
    if (host === 'youtu.be' || host.endsWith('.youtu.be')) {
      const seg = String(parsed.pathname || '').replace(/^\/+/, '').split('/')[0] || '';
      return /^[A-Za-z0-9_-]{11}$/.test(seg) ? seg : '';
    }
    if (host !== 'youtube.com' && !host.endsWith('.youtube.com') && host !== 'youtube-nocookie.com' && !host.endsWith('.youtube-nocookie.com')) return '';
    const v = parsed.searchParams.get('v');
    if (v && /^[A-Za-z0-9_-]{11}$/.test(v)) return v;
    const pieces = String(parsed.pathname || '').split('/').filter(Boolean);
    const keyed = ['embed', 'shorts', 'live', 'v'];
    for (let i = 0; i < pieces.length - 1; i++) {
      if (keyed.includes(pieces[i]) && /^[A-Za-z0-9_-]{11}$/.test(pieces[i + 1])) {
        return pieces[i + 1];
      }
    }
    return '';
  }

  async function resolveYouTubeViaPiped(videoId) {
    ytDebug('resolve-start', { videoId });
    const resolveUrl = '/api/youtube/resolve?videoId=' + encodeURIComponent(videoId);
    let res = null;
    try {
      ytDebug('resolve-fetch-start', {
        videoId,
        resolveUrl,
        origin: location.origin,
        href: location.href,
        online: typeof navigator !== 'undefined' ? navigator.onLine : null
      });
      res = await fetch(resolveUrl, {
        method: 'GET',
        cache: 'no-store',
        credentials: 'same-origin'
      });
    } catch (fetchErr) {
      let healthProbe = null;
      try {
        const healthRes = await fetch('/api/health', {
          method: 'GET',
          cache: 'no-store',
          credentials: 'same-origin'
        });
        const txt = await healthRes.text().catch(() => '');
        healthProbe = {
          status: healthRes.status,
          ok: healthRes.ok,
          bodyPreview: String(txt || '').slice(0, 240)
        };
      } catch (healthErr) {
        healthProbe = {
          error: healthErr && healthErr.message ? healthErr.message : String(healthErr || 'unknown')
        };
      }
      ytDebug('resolve-fetch-threw', {
        videoId,
        resolveUrl,
        fetchError: fetchErr && fetchErr.message ? fetchErr.message : String(fetchErr || 'unknown'),
        online: typeof navigator !== 'undefined' ? navigator.onLine : null,
        healthProbe
      });
      throw fetchErr;
    }
    let payload = null;
    try {
      payload = await res.json();
    } catch (_) {
      payload = null;
    }
    ytDebug('resolve-response', {
      videoId,
      status: res.status,
      ok: res.ok,
      payloadPreview: payload
        ? {
            ok: payload.ok,
            sourceApi: payload.sourceApi,
            videoId: payload.videoId,
            hasStreamUrl: !!payload.streamUrl,
            hasProxyUrl: !!payload.proxyUrl,
            attemptsCount: Array.isArray(payload.attempts) ? payload.attempts.length : 0,
            externalLinksCount: Array.isArray(payload.externalLinks) ? payload.externalLinks.length : 0
          }
        : null
    });
    if (!res.ok) {
      const attemptsText = formatYouTubeAttempts(payload && payload.attempts);
      const detail = String(payload && payload.error ? payload.error : '').trim();
      const msg = detail || ('HTTP ' + res.status);
      const withAttempts = attemptsText ? (msg + ' | attempts: ' + attemptsText) : msg;
      console.error('[YouTube resolve error]', { videoId, status: res.status, payload });
      throw new Error(withAttempts);
    }
    if (!payload || typeof payload !== 'object') {
      throw new Error('Resolve returned invalid JSON payload');
    }
    const streamUrl = String(payload.streamUrl || '').trim();
    if (!streamUrl) {
      const attemptsText = formatYouTubeAttempts(payload.attempts);
      const msg = attemptsText ? ('Resolve returned no stream URL | attempts: ' + attemptsText) : 'Resolve returned no stream URL';
      console.error('[YouTube resolve no stream]', { videoId, payload });
      throw new Error(msg);
    }
    const attemptsText = formatYouTubeAttempts(payload.attempts);
    if (attemptsText) console.debug('[YouTube resolve attempts]', attemptsText);
    ytDebug('resolve-success', {
      videoId,
      sourceApi: String(payload.sourceApi || ''),
      proxyUrl: String(payload.proxyUrl || '').trim(),
      streamUrlHost: (function () {
        try { return new URL(streamUrl, location.href).host; } catch (_) { return ''; }
      })(),
      attemptsText
    });
    return {
      streamUrl,
      proxyUrl: String(payload.proxyUrl || '').trim(),
      title: String(payload.title ? payload.title : 'YouTube audio'),
      artist: String(payload.artist ? payload.artist : 'YouTube'),
      artwork: String(payload.artwork ? payload.artwork : ''),
      sourceApi: String(payload.sourceApi ? payload.sourceApi : 'backend'),
      attemptsText,
      externalLinks: Array.isArray(payload.externalLinks) ? payload.externalLinks : []
    };
  }

  function setYouTubeNowPlaying(meta) {
    const item = meta || {};
    const title = String(item.title || 'YouTube audio');
    const artist = String(item.artist || 'YouTube');
    const videoId = String(item.videoId || '');
    const link = videoId ? 'https://www.youtube.com/watch?v=' + encodeURIComponent(videoId) : '#';
    const sourceLabel = item.sourceApi ? String(item.sourceApi).replace(/^https?:\/\//, '') : 'Piped';
    $('audio').closest('.player-bar').classList.remove('radio-live');
    $('npTitle').textContent = title;
    $('npTitle').href = link;
    $('npTitle').style.pointerEvents = videoId ? '' : 'none';
    $('npAlbum').textContent = 'YouTube via ' + sourceLabel;
    $('npAlbum').href = '#';
    $('npAlbum').style.pointerEvents = 'none';
    $('npSub').textContent = artist;
    $('npSub').href = '#';
    $('npSub').style.pointerEvents = 'none';
    clearNowPlayingWikiLinks();
    $('npNext').textContent = 'Single YouTube item';
    const npArt = $('npArt');
    const fallback = fallbackThumbDataUrl();
    npArt.src = item.artwork || fallback;
    npArt.onerror = () => {
      if (npArt.src !== fallback) npArt.src = fallback;
    };
    const favBtn = $('btnFavNow');
    if (favBtn) {
      favBtn.disabled = true;
      favBtn.classList.remove('starred');
      favBtn.innerHTML = starIconHtml(false);
      favBtn.setAttribute('aria-label', 'Favorite current track');
      favBtn.setAttribute('title', 'Favorite current track');
    }
    document.title = title + ' | pleXtr';
    updateMediaSessionNowPlaying({
      title,
      artist,
      album: 'YouTube',
      artwork: item.artwork || fallback
    });
  }

  async function playYouTubeFromUrl(rawInput) {
    ytDebug('play-requested', { rawInput: String(rawInput || ''), hash: location.hash });
    const videoId = extractYouTubeVideoId(rawInput);
    if (!videoId) {
      ytDebug('play-invalid-input', { rawInput: String(rawInput || '') });
      setYouTubeStatus('Enter a valid YouTube video URL or video ID.', 'error');
      showError('Invalid YouTube link. Use a single video URL or video ID.');
      return;
    }
    const nextHash = youtubeHash(videoId);
    ytDebug('play-video-id', { videoId, nextHash });
    if (location.hash !== nextHash) location.hash = nextHash;
    setYouTubeStatus('Resolving stream…', '');
    showError('');
    const audio = $('audio');
    let stage = 'resolve';
    try {
      const resolved = await resolveYouTubeViaPiped(videoId);
      const playbackUrl = resolved.proxyUrl || resolved.streamUrl;
      ytDebug('playback-url-selected', {
        videoId,
        useProxy: !!resolved.proxyUrl,
        playbackUrl,
        snapshotBeforeSet: audioDebugSnapshot(audio)
      });
      setAudioElementSource(audio, playbackUrl);
      ytDebug('audio-src-set', { videoId, snapshotAfterSet: audioDebugSnapshot(audio) });
      stage = 'audio-play';
      await audio.play();
      ytDebug('audio-play-resolved', { videoId, snapshotAfterPlay: audioDebugSnapshot(audio) });
      isRadioMode = false;
      currentRadioIdx = -1;
      currentTracks = [];
      playingIdx = -1;
      youtubeSelection = {
        videoId,
        input: String(rawInput || '').trim(),
        title: resolved.title,
        artist: resolved.artist,
        artwork: resolved.artwork,
        sourceApi: resolved.sourceApi,
        attemptsText: resolved.attemptsText || ''
      };
      setYouTubeNowPlaying(youtubeSelection);
      const playMsg = resolved.attemptsText
        ? ('Playing: ' + resolved.title + ' | attempts: ' + resolved.attemptsText)
        : ('Playing: ' + resolved.title);
      setYouTubeStatus(playMsg, 'ok');
      void updateYouTubeExternalLinksPanel(videoId, resolved.externalLinks);
      updatePlayPauseButton();
      updateNextAlbumButtonVisibility();
    } catch (err) {
      const detail = err && err.message ? err.message : String(err || 'Unknown error');
      ytDebug('play-failed', {
        videoId,
        stage,
        error: detail,
        snapshotOnError: audioDebugSnapshot(audio)
      });
      setYouTubeStatus('Could not play this video (' + detail + ').', 'error');
      showError('Could not resolve/play YouTube audio: ' + detail);
      void updateYouTubeExternalLinksPanel(videoId, null);
    }
  }

  function renderYouTubeView() {
    const wrap = $('viewYouTube');
    if (!wrap) return;
    const hashPref = youtubeRouteSegment(location.hash);
    const pref = hashPref || (youtubeSelection && youtubeSelection.input ? youtubeSelection.input : '');
    ytDebug('render-youtube-view', { hash: location.hash, hashPref, pref });
    wrap.innerHTML =
      '<section class="youtube-card">' +
      '<h3>YouTube (via Piped)</h3>' +
      '<div class="youtube-input-row">' +
      '<input id="youtubeUrlInput" type="text" placeholder="Paste YouTube video URL or ID" value="' + escapeHtml(pref) + '" />' +
      '<button type="button" class="btn primary" id="btnPlayYouTube">Play</button>' +
      '</div>' +
      '<div id="youtubeStatus" class="youtube-status"></div>' +
      '<div id="youtubeExternalLinks" class="youtube-external-links" hidden></div>' +
      '<p class="youtube-note">v1 supports single videos only. Playlists/channels and favorites/sharing are intentionally out of scope.</p>' +
      '</section>';
    bindClick('btnPlayYouTube', () => {
      const input = $('youtubeUrlInput');
      playYouTubeFromUrl(input ? input.value : '');
    });
    bindEvent('youtubeUrlInput', 'keydown', (ev) => {
      if (ev.key !== 'Enter') return;
      ev.preventDefault();
      const input = $('youtubeUrlInput');
      playYouTubeFromUrl(input ? input.value : '');
    });
    if (youtubeSelection && youtubeSelection.title) {
      setYouTubeStatus('Last played: ' + youtubeSelection.title, '');
    }
    const vidForLinks = extractYouTubeVideoId(pref) || (/^[A-Za-z0-9_-]{11}$/.test(String(hashPref || '').trim()) ? String(hashPref).trim() : '');
    void updateYouTubeExternalLinksPanel(vidForLinks, null);
  }

  function somaStreamUrl(id) {
    return 'https://ice1.somafm.com/' + encodeURIComponent(String(id)) + '-128-mp3';
  }

  function stationArtDataUrl(id, station) {
    const seed = String(id || '');
    const tags = Array.isArray(station && station.tags) ? station.tags : [];
    const title = String((station && station.title) || titleFromStationId(id) || 'Radio');
    const initials = title
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0].toUpperCase())
      .join('') || 'RG';

    function svgBadge(opts) {
      const top = opts.top;
      const bottom = opts.bottom;
      const label = opts.label || initials;
      const ring = opts.ring || 'rgba(255,255,255,.22)';
      const accent = opts.accent || 'rgba(255,255,255,.3)';
      const svg =
        '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="240" viewBox="0 0 240 240">' +
        '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">' +
        '<stop offset="0%" stop-color="' + top + '"/>' +
        '<stop offset="100%" stop-color="' + bottom + '"/>' +
        '</linearGradient></defs>' +
        '<rect width="240" height="240" rx="36" fill="url(#g)"/>' +
        '<rect x="20" y="20" width="200" height="200" rx="28" fill="rgba(0,0,0,.2)" stroke="' + ring + '" stroke-width="2"/>' +
        '<circle cx="192" cy="48" r="12" fill="' + accent + '"/>' +
        '<text x="120" y="138" text-anchor="middle" fill="#fff" font-family="system-ui,sans-serif" font-size="68" font-weight="800" letter-spacing="1.5">' + label + '</text>' +
        '</svg>';
      return 'data:image/svg+xml,' + encodeURIComponent(svg);
    }

    if (tags.includes('fip')) {
      return svgBadge({ top: '#f5168c', bottom: '#4f2cff', label: 'FIP' });
    }
    if (tags.includes('flux')) {
      return svgBadge({ top: '#ff5e00', bottom: '#f0007a', label: 'FLX' });
    }
    if (tags.includes('paradise')) {
      return svgBadge({ top: '#00a0ff', bottom: '#0040c7', label: 'RP' });
    }
    if (tags.includes('concertzender')) {
      return svgBadge({ top: '#3e63ff', bottom: '#1f2a6b', label: 'CZ' });
    }
    if (tags.includes('soma')) {
      return svgBadge({ top: '#ef7d00', bottom: '#ff2b8a', label: 'SOMA' });
    }
    if (tags.includes('traxx')) {
      return svgBadge({ top: '#00d1ff', bottom: '#0066d9', label: 'TRX' });
    }
    if (tags.includes('portugal') || tags.includes('pt')) {
      return svgBadge({ top: '#0f8f3a', bottom: '#d31d2f', label: 'PT' });
    }
    if (tags.includes('latin')) {
      return svgBadge({ top: '#ff8a00', bottom: '#e53935', label: 'LAT' });
    }
    if (tags.includes('country') || tags.includes('bluegrass')) {
      return svgBadge({ top: '#8d6e63', bottom: '#4e342e', label: 'C&W' });
    }
    if (tags.includes('ambient')) {
      return svgBadge({ top: '#00c9a7', bottom: '#2b7cff', label: 'AMB' });
    }
    if (tags.includes('other')) {
      return svgBadge({ top: '#6c5ce7', bottom: '#2d3436', label: initials });
    }
    let hash = 0;
    for (let i = 0; i < seed.length; i++) hash = ((hash << 5) - hash + seed.charCodeAt(i)) | 0;
    const hue = Math.abs(hash % 360);
    const hue2 = (hue + 44) % 360;
    return svgBadge({
      top: 'hsl(' + hue + ' 78% 42%)',
      bottom: 'hsl(' + hue2 + ' 78% 30%)',
      label: initials
    });
  }

  function buildRadioData() {
    const rawChannels = typeof channels === 'object' && channels ? channels : {};
    const rawSections = Array.isArray(sections) ? sections : [];
    const stationsInChannelOrder = [];
    Object.entries(rawChannels).forEach(([id, info]) => {
      const tags = Array.isArray(info.tags) ? info.tags.map((t) => String(t)) : [];
      const title = titleFromStationId(id);
      const fallbackImg = stationArtDataUrl(id, { title, tags });
      const s = {
        id,
        title,
        url: info.url || (tags.includes('soma') ? somaStreamUrl(id) : ''),
        tags,
        site: info.site || '',
        img: info.img || fallbackImg,
        fallbackImg
      };
      if (s.url) stationsInChannelOrder.push(s);
    });
    const sec = rawSections
      .slice()
      .sort((a, b) => (Number(a.order) || 999) - (Number(b.order) || 999))
      .map((section) => {
        const tags = Array.isArray(section.tags) ? section.tags : [];
        const items = stationsInChannelOrder.filter((station) =>
          tags.some((tag) => station.tags.includes(tag))
        );
        return {
          name: section.name || 'Stations',
          items
        };
      })
      .filter((s) => s.items.length > 0);
    const seen = new Set();
    const radioOrder = [];
    sec.forEach((section) => {
      section.items.forEach((station) => {
        if (seen.has(station.id)) return;
        seen.add(station.id);
        radioOrder.push(station);
      });
    });
    stationsInChannelOrder.forEach((station) => {
      if (seen.has(station.id)) return;
      seen.add(station.id);
      radioOrder.push(station);
    });
    radioStations = radioOrder;
    radioSections = sec;
  }

  window.addEventListener('radioguaka-somafm-artwork', () => {
    buildRadioData();
    if (navView === 'radio') renderRadioView();
    if (isRadioMode && currentRadioIdx >= 0 && radioStations[currentRadioIdx]) {
      setRadioNowPlaying(radioStations[currentRadioIdx]);
    }
  });

  function radioShuffleOn() {
    return isRadioMode && playbackMode !== PLAYBACK_MODE_OFF;
  }

  function randomRadioStationIndex(excludeIdx) {
    const n = radioStations.length;
    if (n <= 0) return -1;
    if (n === 1) return 0;
    if (excludeIdx < 0 || excludeIdx >= n) return Math.floor(Math.random() * n);
    let j = Math.floor(Math.random() * (n - 1));
    if (j >= excludeIdx) j += 1;
    return j;
  }

  function renderRadioView() {
    const wrap = $('viewRadio');
    if (!wrap) return;
    if (!radioStations.length) buildRadioData();
    const query = (searchQuery || '').trim();
    const q = query.toLowerCase();
    const filteredSections = radioSections
      .map((section) => {
        const items = section.items.filter((station) => {
          if (!q) return true;
          return (
            station.title.toLowerCase().includes(q) ||
            station.id.toLowerCase().includes(q) ||
            station.tags.join(' ').toLowerCase().includes(q)
          );
        });
        return { name: section.name, items };
      })
      .filter((section) => section.items.length > 0);
    const hasItems = filteredSections.some((section) => section.items.length > 0);
    if (!hasItems) {
      wrap.innerHTML = '<p class="empty-state">No radio stations match this search.</p>';
      return;
    }
    wrap.innerHTML =
      '<div class="radio-view">' +
      filteredSections
        .map((section) => {
          return (
            '<section class="radio-section-card">' +
            '<div class="radio-section-head"><h3>' + escapeHtml(section.name) + '</h3><span class="count">' + section.items.length + ' station' + (section.items.length === 1 ? '' : 's') + '</span></div>' +
            '<ul class="radio-list">' +
            section.items
              .map((station) => {
                const sub = station.tags.length ? station.tags.join(' · ') : 'Live stream';
                const isPlaying = isRadioMode && currentRadioIdx >= 0 && radioStations[currentRadioIdx] && radioStations[currentRadioIdx].id === station.id;
                const starred = getStarredStations().has(station.id);
                return (
                  '<li class="radio-item' + (isPlaying ? ' playing' : '') + '" data-radio-id="' + escapeHtml(station.id) + '">' +
                  '<button type="button" class="radio-play-btn" data-radio-play="' + escapeHtml(station.id) + '" aria-label="Play ' + escapeHtml(station.title) + '" title="Play station">&#9654;</button>' +
                  '<img class="radio-thumb" src="' + escapeHtml(station.img || station.fallbackImg || fallbackThumbDataUrl()) + '" data-fallback="' + escapeHtml(station.fallbackImg || fallbackThumbDataUrl()) + '" alt="" loading="lazy" />' +
                  '<div class="radio-meta">' +
                  '<div class="radio-title">' + (station.site ? '<a class="meta-link" href="' + escapeHtml(station.site) + '" target="_blank" rel="noopener">' + escapeHtml(station.title) + '</a>' : escapeHtml(station.title)) + '</div>' +
                  '<div class="radio-sub">' + escapeHtml(sub) + '</div>' +
                  '</div>' +
                  '<button type="button" class="star-btn' + (starred ? ' starred' : '') + '" data-radio-star="' + escapeHtml(station.id) + '" aria-label="' + (starred ? 'Unfavorite station' : 'Favorite station') + '" title="' + (starred ? 'Unfavorite station' : 'Favorite station') + '">' + starIconHtml(starred) + '</button>' +
                  '<span class="live-pill">Live</span>' +
                  '</li>'
                );
              })
              .join('') +
            '</ul>' +
            '</section>'
          );
        })
        .join('') +
      '</div>';
    wrap.querySelectorAll('button[data-radio-play]').forEach((btn) => {
      btn.onclick = () => {
        const id = btn.getAttribute('data-radio-play');
        const idx = radioStations.findIndex((station) => station.id === id);
        if (idx >= 0) playRadioIndex(idx);
      };
    });
    wrap.querySelectorAll('button[data-radio-star]').forEach((btn) => {
      btn.onclick = () => {
        const id = btn.getAttribute('data-radio-star');
        const nowStarred = toggleStationStar(id);
        btn.classList.toggle('starred', nowStarred);
        btn.setAttribute('aria-label', nowStarred ? 'Unfavorite station' : 'Favorite station');
        btn.setAttribute('title', nowStarred ? 'Unfavorite station' : 'Favorite station');
        btn.innerHTML = starIconHtml(nowStarred);
        if (isRadioMode && currentRadioIdx >= 0 && radioStations[currentRadioIdx] && radioStations[currentRadioIdx].id === id) {
          updateNowPlayingStationFavButton(id);
        }
      };
    });
    wrap.querySelectorAll('img.radio-thumb').forEach((img) => {
      img.onerror = () => {
        const fallback = img.getAttribute('data-fallback') || fallbackThumbDataUrl();
        if (img.src !== fallback) img.src = fallback;
      };
    });
  }

  function updateNowPlayingStationFavButton(stationId) {
    const btn = $('btnFavNow');
    const key = String(stationId || '');
    if (!key) {
      btn.disabled = true;
      btn.classList.remove('starred');
      btn.innerHTML = starIconHtml(false);
      btn.setAttribute('aria-label', 'Favorite current station');
      btn.setAttribute('title', 'Favorite current station');
      return;
    }
    const starred = getStarredStations().has(key);
    btn.disabled = false;
    btn.classList.toggle('starred', starred);
    btn.innerHTML = starIconHtml(starred);
    btn.setAttribute('aria-label', starred ? 'Unfavorite current station' : 'Favorite current station');
    btn.setAttribute('title', starred ? 'Unfavorite current station' : 'Favorite current station');
  }

  function setRadioNowPlaying(station) {
    if (!station) return;
    $('audio').closest('.player-bar').classList.add('radio-live');
    $('npTitle').textContent = station.title;
    $('npTitle').href = station.site || '#';
    $('npTitle').style.pointerEvents = station.site ? '' : 'none';
    $('npAlbum').textContent = '';
    $('npAlbum').href = '#';
    $('npAlbum').style.pointerEvents = 'none';
    $('npSub').textContent = station.tags.length ? station.tags.join(' · ') : 'Live stream';
    $('npSub').href = '#';
    $('npSub').style.pointerEvents = 'none';
    clearNowPlayingWikiLinks();
    $('npNext').textContent = 'Live radio stream';
    const npArt = $('npArt');
    const fallback = station.fallbackImg || fallbackThumbDataUrl();
    npArt.src = station.img || fallback;
    npArt.onerror = () => {
      if (npArt.src !== fallback) npArt.src = fallback;
    };
    $('npTime').textContent = 'LIVE';
    $('npDur').textContent = '--:--';
    $('npSeek').value = '0';
    updateNowPlayingStationFavButton(station.id);
    document.title = station.title + ' | pleXtr';
  }

  function playRadioIndex(i, options) {
    const opts = options || {};
    const station = radioStations[i];
    if (!station || !station.url) return;
    clearNowPlayingNoteSchedule();
    currentRadioIdx = i;
    isRadioMode = true;
    const audio = $('audio');
    audio._pendingRestoreTime = null;
    setAudioElementSource(audio, station.url);
    if (opts.autoplay !== false) {
      audio.play().catch((err) => {
        showError('Could not start this stream: ' + (err && err.message ? err.message : 'Playback blocked'));
      });
    }
    savePlaybackState({ type: 'radio', stationId: station.id });
    setRadioNowPlaying(station);
    updateMediaSessionNowPlaying({
      title: station.title || 'Radio',
      artist: '',
      album: 'Live stream',
      artwork: station.img || station.fallbackImg || fallbackThumbDataUrl()
    });
    renderRadioView();
    updatePlayPauseButton();
    updateNextAlbumButtonVisibility();
    applyPlaybackModeUI();
    sendChatContext();
    scheduleNotifyNowPlaying(null, station);
    const rh = radioHash(station.id);
    if (location.hash !== rh) location.hash = rh;
  }

  function renderAlbumPageIntoView(meta, total, append) {
    const sectionKey = String(cfg().sectionKey || $('inSection').value || '');
    if (!sectionKey) return;
    albumTotalCount = Number(total) || 0;
    const indexBatch = [];
    if (!append) {
      $('viewAlbums').innerHTML = '<div class="albums" id="albumGrid"></div>';
      albumStart = 0;
    }
    const grid = $('albumGrid');
    if (!grid) return;
    const starredAlbums = getStarredAlbums();
    const filterActive = append && ($('inSearch').value || '').trim().toLowerCase().length > 0;
    const frag = document.createDocumentFragment();
    meta.forEach((a) => {
      albumLookup.set(String(a.ratingKey || ''), a);
      const albumKey = String(a.ratingKey || '');
      const albumSearchBlob = makeSearchBlob(a);
      const indexed = albumSearchIndexCache.get(albumKey);
      albumSearchIndexCache.set(albumKey, {
        albumSearch: albumSearchBlob,
        trackSearch: String((indexed && indexed.trackSearch) || ''),
        titles: Array.isArray(indexed && indexed.titles) ? indexed.titles : []
      });
      indexBatch.push({
        id: searchRecordId(sectionKey, albumKey),
        sectionKey,
        albumKey,
        albumSearch: albumSearchBlob,
        trackSearch: String((indexed && indexed.trackSearch) || ''),
        titles: Array.isArray(indexed && indexed.titles) ? indexed.titles : [],
        updatedAt: Date.now()
      });
      const div = document.createElement('div');
      div.className = 'album';
      div.dataset.key = a.ratingKey;
      div.dataset.search = albumSearchBlob;
      if (filterActive) div.style.display = 'none';
      const img = thumbUrl(a.thumb || a.parentThumb) || fallbackThumbDataUrl();
      const initialAlbumTitle = isUnknownAlbumTitle(a.title) ? '[Unknown Album]' : String(a.title || '');
      const albumHref = albumHash(a.ratingKey);
      const artistHref = artistHash(a.parentRatingKey);
      const albumStarred = starredAlbums.has(String(a.ratingKey));
      div.innerHTML =
        '<div class="star-wrap"><button type="button" class="star-btn' + (albumStarred ? ' starred' : '') + '" data-rating-key="' + escapeHtml(String(a.ratingKey)) + '" aria-label="' + (albumStarred ? 'Unstar album' : 'Star album') + '" title="' + (albumStarred ? 'Unstar' : 'Star') + '">' + starIconHtml(albumStarred) + '</button></div>' +
        '<div class="album-art">' +
        '<button type="button" class="album-play-btn" aria-label="Play album" title="Play album">' +
        albumCardPlayIconHtml() +
        '</button>' +
        '<img src="' + img + '" alt="" loading="lazy" /></div>' +
        '<div class="meta"><div class="title">' + makeMetaLinkHtml(initialAlbumTitle, albumHref, 'album') + '</div>' +
        '<div class="artist">' + makeMetaLinkHtml(String(a.parentTitle || ''), artistHref, 'artist') + '</div>' +
        '<div class="song-matches"></div></div>';
      div.onclick = (ev) => {
        if (ev.target.closest('a[data-meta-link]')) return;
        if (ev.target.closest('.star-btn')) return;
        if (ev.target.closest('.album-play-btn')) return;
        openAlbum(a);
      };
      const playBtn = div.querySelector('.album-play-btn');
      if (playBtn) {
        playBtn.onclick = (ev) => playAlbumFromCard(a, ev);
      }
      const starBtn = div.querySelector('.star-btn');
      if (starBtn) {
        starBtn.onclick = (ev) => {
          ev.preventDefault();
          ev.stopPropagation();
          const nowStarred = toggleAlbumStar(starBtn.dataset.ratingKey, a);
          starBtn.classList.toggle('starred', nowStarred);
          starBtn.setAttribute('aria-label', nowStarred ? 'Unstar album' : 'Star album');
          starBtn.setAttribute('title', nowStarred ? 'Unstar' : 'Star');
          starBtn.innerHTML = starIconHtml(nowStarred);
          notifyFavoriteAlbum(a, nowStarred);
          syncAlbumDetailStarButton(a.ratingKey, nowStarred);
          updateFilterState();
        };
      }
      frag.appendChild(div);
      if (isUnknownAlbumTitle(a.title)) {
        resolveAlbumFolderName(a.ratingKey)
          .then((folderName) => {
            if (!folderName || !document.body.contains(div)) return;
            const titleLink = div.querySelector('.title a[data-meta-link="album"]');
            if (titleLink) titleLink.textContent = folderName;
            else {
              const titleEl = div.querySelector('.title');
              if (!titleEl) return;
              titleEl.textContent = folderName;
            }
            div.dataset.search = (div.dataset.search || '') + ' ' + folderName.toLowerCase();
            if (searchQuery) updateFilterState();
          })
          .catch(() => {});
      }
    });
    grid.appendChild(frag);
    putSearchRecords(indexBatch).catch(() => {});
    saveSearchIndexMeta({
      schemaVersion: SEARCH_INDEX_SCHEMA_VERSION,
      sectionKey,
      totalCount: albumTotalCount,
      updatedAt: Date.now()
    });
    updateFilterState();
    albumStart += meta.length;
    const more = $('loadMoreAlb');
    if (more) more.remove();
    hasMoreAlbums = albumStart < total;
    if (hasMoreAlbums) {
      const wrap = document.createElement('div');
      wrap.className = 'load-more';
      wrap.id = 'loadMoreAlb';
      const b = document.createElement('button');
      b.className = 'btn';
      b.textContent = 'Load more albums';
      b.onclick = () => loadAlbums(true);
      wrap.appendChild(b);
      $('viewAlbums').appendChild(wrap);
    }
    if (!append) {
      $('albumViewToggle').style.display = '';
      applyAlbumViewMode();
    }
    scheduleAlbumAutoLoad(20);
  }

  function plexMetadataArray(mc) {
    const raw = mc && mc.Metadata != null ? mc.Metadata : null;
    if (raw == null) return [];
    return Array.isArray(raw) ? raw : [raw];
  }

  async function loadAlbums(append) {
    const section = cfg().sectionKey || $('inSection').value;
    if (!section) return 0;
    if (isLoadingAlbums) return 0;
    isLoadingAlbums = true;
    let shownFromIdb = false;
    let result = 0;
    try {
      const ns = plexAlbumCacheNs();
      const sec = String(section);
      if (!append) {
        activeSearchIndexSection = String(section);
        albumTrackSearchCache.clear();
        libraryTrackTotalCache = { sectionKey: '', total: 0 };
        songShuffleRecentKeys = [];
        navView = 'albums';
        syncSidebarNavActive();
        $('viewLibrary').style.display = 'none';
        $('viewPlaylists').style.display = 'none';
        albumStart = 0;
        sectionAlbumListMode = { sectionKey: sec, mode: 'albums' };
        $('viewAlbums').innerHTML = '<p class="msg"><span class="spinner"></span> Loading albums…</p>';
        const libMeta = await readAlbumLibraryMetaWithTimeout(ns, sec, 450);
        const idxEarly = loadSearchIndexMeta();
        let countHint = 0;
        if (libMeta && Number(libMeta.totalAlbums) > 0) {
          countHint = Math.max(countHint, Number(libMeta.totalAlbums));
        }
        if (String(idxEarly.sectionKey || '') === sec && Number(idxEarly.totalCount) > 0) {
          countHint = Math.max(countHint, Number(idxEarly.totalCount));
        }
        if (countHint > 0) {
          albumTotalCount = countHint;
          updateFilterState();
        }
        const cachedAlbums = await readAlbumListPageWithTimeout(ns, sec, 'albums', 0, 800);
        if (cachedAlbums && cachedAlbums.items && cachedAlbums.items.length) {
          showError('');
          showCors(false);
          const mergedTotal = mergeAlbumListTotalHint(
            cachedAlbums.totalSize,
            cachedAlbums.items.length,
            sec
          );
          renderAlbumPageIntoView(cachedAlbums.items, mergedTotal, false);
          shownFromIdb = true;
        }
      }
      showError('');
      showCors(false);
      const useAllAlbums =
        sectionAlbumListMode.sectionKey === sec && sectionAlbumListMode.mode === 'allType9';
      const pageFetchStart = append ? albumStart : 0;
      let path = useAllAlbums
        ? '/library/sections/' +
          sec +
          '/all?type=9&X-Plex-Container-Start=' +
          pageFetchStart +
          '&X-Plex-Container-Size=' +
          PAGE
        : '/library/sections/' +
          sec +
          '/albums?X-Plex-Container-Start=' +
          pageFetchStart +
          '&X-Plex-Container-Size=' +
          PAGE;
      let data = await plexFetch(path);
      let meta = plexMetadataArray(data.MediaContainer);
      let total = resolveAlbumListTotal(data.MediaContainer, meta.length, append);
      if (!append && pageFetchStart === 0 && !meta.length && !useAllAlbums) {
        if (shownFromIdb) {
          await deleteAlbumListPage(ns, sec, 'albums', 0);
          $('viewAlbums').innerHTML = '<p class="msg"><span class="spinner"></span> Loading albums…</p>';
          shownFromIdb = false;
        }
        sectionAlbumListMode = { sectionKey: sec, mode: 'allType9' };
        const cachedAll9 = await readAlbumListPageWithTimeout(ns, sec, 'all9', 0, 800);
        if (cachedAll9 && cachedAll9.items && cachedAll9.items.length) {
          showError('');
          showCors(false);
          const mergedAll9 = mergeAlbumListTotalHint(
            cachedAll9.totalSize,
            cachedAll9.items.length,
            sec
          );
          renderAlbumPageIntoView(cachedAll9.items, mergedAll9, false);
          shownFromIdb = true;
        }
        path =
          '/library/sections/' +
          sec +
          '/all?type=9&X-Plex-Container-Start=0&X-Plex-Container-Size=' +
          PAGE;
        data = await plexFetch(path);
        meta = plexMetadataArray(data.MediaContainer);
        total = resolveAlbumListTotal(data.MediaContainer, meta.length, append);
      }
      const listMode =
        sectionAlbumListMode.sectionKey === sec && sectionAlbumListMode.mode === 'allType9' ? 'all9' : 'albums';
      renderAlbumPageIntoView(meta, total, append);
      if (meta.length) {
        putAlbumListPage(ns, sec, listMode, pageFetchStart, total, meta);
        if (!append && pageFetchStart === 0 && total > 0) {
          putAlbumLibraryMeta(ns, sec, total, listMode);
        }
      }
      result = meta.length;
    } catch (e) {
      console.error(e);
      if (!append && !shownFromIdb) $('viewAlbums').innerHTML = '';
      showError(e.message || String(e));
      if (String(e.message).includes('Failed to fetch') || e.name === 'TypeError') showCors(true);
      result = 0;
    } finally {
      isLoadingAlbums = false;
    }
    return result;
  }

  async function fetchAlbumPlayableTracks(album) {
    if (!album || album.ratingKey == null) return [];
    const data = await plexFetch('/library/metadata/' + album.ratingKey + '/children');
    const meta = data.MediaContainer.Metadata || [];
    let tracks = meta.filter(isPlexAudioLeafForPlayback);
    if (!tracks.length) {
      try {
        const fullData = await plexFetch(
          '/library/metadata/' + encodeURIComponent(String(album.ratingKey || ''))
        );
        const detail =
          fullData.MediaContainer &&
          fullData.MediaContainer.Metadata &&
          fullData.MediaContainer.Metadata[0];
        if (detail && isPlexAudioLeafForPlayback(detail)) {
          tracks = [detail];
        }
      } catch (_) {}
    }
    return tracks;
  }

  async function refreshCurrentAlbumTracksForPlayback() {
    if (!currentAlbum || currentAlbum.ratingKey == null) return false;
    const tracks = await fetchAlbumPlayableTracks(currentAlbum);
    if (!Array.isArray(tracks) || !tracks.length) return false;
    currentTracks = tracks;
    currentTracks.forEach((track) => {
      if (track && track.ratingKey) trackLookup.set(String(track.ratingKey), track);
    });
    return true;
  }

  function findRetryTrackAfterRefresh(oldTrack, tracks, preferredIdx) {
    const list = Array.isArray(tracks) ? tracks : [];
    if (!list.length) return { track: null, index: -1 };
    const oldKey = String((oldTrack && oldTrack.ratingKey) || '');
    if (oldKey) {
      const byKey = list.findIndex((t) => String((t && t.ratingKey) || '') === oldKey);
      if (byKey >= 0) return { track: list[byKey], index: byKey };
    }
    const oldTitle = String((oldTrack && oldTrack.title) || '').trim().toLowerCase();
    if (oldTitle) {
      const byTitle = list.findIndex((t) => String((t && t.title) || '').trim().toLowerCase() === oldTitle);
      if (byTitle >= 0) return { track: list[byTitle], index: byTitle };
    }
    const idx = Number.isInteger(preferredIdx) ? preferredIdx : 0;
    if (idx >= 0 && idx < list.length) return { track: list[idx], index: idx };
    return { track: list[0], index: 0 };
  }

  async function playAlbumFromCard(album, ev) {
    if (ev) {
      ev.preventDefault();
      ev.stopPropagation();
    }
    showError('');
    try {
      const tracks = await fetchAlbumPlayableTracks(album);
      tracks.forEach((track) => {
        if (track && track.ratingKey) trackLookup.set(String(track.ratingKey), track);
      });
      currentAlbum = album;
      currentTracks = tracks;
      if (!currentTracks.length) {
        showError('No playable tracks in this album.');
        return;
      }
      playIndex(0);
    } catch (e) {
      console.error(e);
      showError(e.message || String(e));
    }
  }

  async function openAlbum(album) {
    navView = 'albums';
    syncSidebarNavActive();
    $('viewLibrary').style.display = 'none';
    $('viewPlaylists').style.display = 'none';
    currentAlbum = album;
    location.hash = albumHash(album.ratingKey);
    $('viewAlbums').style.display = 'none';
    $('viewTracks').style.display = 'block';
    $('albumViewToggle').style.display = 'none';
    $('viewTracks').innerHTML =
      '<div class="breadcrumb"><button type="button" id="backAlb">← Albums</button></div>' +
      '<h2 style="margin:0 0 1rem;font-size:1.15rem">' + escapeHtml(album.title) + '</h2>' +
      '<p class="msg"><span class="spinner"></span> Loading tracks…</p>';
    $('backAlb').onclick = backToAlbums;
    showError('');
    try {
      const data = await plexFetch('/library/metadata/' + album.ratingKey + '/children');
      const meta = data.MediaContainer.Metadata || [];
      currentTracks = meta.filter(isPlexAudioLeafForPlayback);
      if (!currentTracks.length) {
        try {
          const fullData = await plexFetch(
            '/library/metadata/' + encodeURIComponent(String(album.ratingKey || ''))
          );
          const detail =
            fullData.MediaContainer &&
            fullData.MediaContainer.Metadata &&
            fullData.MediaContainer.Metadata[0];
          if (detail && isPlexAudioLeafForPlayback(detail)) {
            currentTracks = [detail];
          }
        } catch (_) {}
      }
      currentTracks.forEach((track) => {
        if (track && track.ratingKey) trackLookup.set(String(track.ratingKey), track);
      });
      const displayTitle = albumDisplayTitleFromTracks(album, currentTracks);
      if (displayTitle && displayTitle !== album.title) albumFolderNameCache.set(album.ratingKey, displayTitle);
      const albumHref = albumHash(album.ratingKey);
      const artistHref = artistHash(album.parentRatingKey);
      const albumArt = albumDetailArtUrl(album, currentTracks);
      const starredTracks = getStarredTracks();
      const albumStarred = getStarredAlbums().has(String(album.ratingKey));
      const list = currentTracks
        .map((t, i) => {
          const dur = t.duration ? Math.round(t.duration / 1000) : 0;
          const mm = Math.floor(dur / 60);
          const ss = dur % 60;
          const durStr = dur ? mm + ':' + (ss < 10 ? '0' : '') + ss : '';
          const trackStarred = starredTracks.has(String(t.ratingKey));
          const filePath = getTrackFilePath(t);
          const base = filePath ? pathBasename(filePath) : '';
          const fileLine = base ? '<span class="track-file">' + escapeHtml(base) + '</span>' : '';
          return (
            '<li data-i="' +
            i +
            '"><span class="idx">' +
            (i + 1) +
            '</span><button type="button" class="star-btn' + (trackStarred ? ' starred' : '') + '" data-rating-key="' + escapeHtml(String(t.ratingKey)) + '" aria-label="' + (trackStarred ? 'Unstar track' : 'Star track') + '" title="' + (trackStarred ? 'Unstar' : 'Star') + '">' + starIconHtml(trackStarred) + '</button><span class="name">' +
            escapeHtml(effectiveTrackListName(t)) +
            fileLine +
            '</span><span class="dur">' +
            durStr +
            '</span></li>'
          );
        })
        .join('');
      const fsHeader = formatAlbumFilesystemHeader(currentTracks);
      $('viewTracks').innerHTML =
        '<div class="breadcrumb"><button type="button" id="backAlb2">← Albums</button></div>' +
        '<div class="album-detail-head">' +
        '<img class="album-detail-art" src="' + albumArt + '" alt="' + escapeHtml(displayTitle || 'Album cover') + '" loading="lazy" />' +
        '<div class="album-detail-meta">' +
        '<div class="album-detail-title-row">' +
        '<button type="button" class="star-btn album-detail-star' +
        (albumStarred ? ' starred' : '') +
        '" id="btnAlbumDetailStar" data-rating-key="' +
        escapeHtml(String(album.ratingKey)) +
        '" aria-label="' +
        (albumStarred ? 'Unstar album' : 'Star album') +
        '" title="' +
        (albumStarred ? 'Unstar album' : 'Star album') +
        '">' +
        starIconHtml(albumStarred) +
        '</button>' +
        '<h2>' +
        makeMetaLinkHtml(displayTitle, albumHref, 'album') +
        '</h2>' +
        '</div>' +
        '<p>' + makeMetaLinkHtml(String(album.parentTitle || ''), artistHref, 'artist') + '</p>' +
        fsHeader +
        '</div>' +
        '</div>' +
        '<ul class="tracks">' +
        list +
        '</ul>';
      const detailArtEl = $('viewTracks').querySelector('.album-detail-art');
      if (detailArtEl) {
        detailArtEl.addEventListener('click', () => {
          openArtworkModalFromSrc(detailArtEl.src, detailArtEl.alt || 'Expanded album artwork');
        });
      }
      $('backAlb2').onclick = backToAlbums;
      $('viewTracks').querySelectorAll('.tracks li').forEach((li) => {
        li.onclick = (ev) => {
          if (ev.target.closest('.star-btn')) return;
          playIndex(parseInt(li.dataset.i, 10));
        };
      });
      $('viewTracks').querySelectorAll('.tracks li .star-btn').forEach((btn) => {
        btn.onclick = (ev) => {
          ev.preventDefault();
          ev.stopPropagation();
          const key = String(btn.dataset.ratingKey || '');
          const track = currentTracks.find((item) => String(item && item.ratingKey) === key);
          const nowStarred = toggleTrackStar(key, track);
          btn.classList.toggle('starred', nowStarred);
          btn.setAttribute('aria-label', nowStarred ? 'Unstar track' : 'Star track');
          btn.setAttribute('title', nowStarred ? 'Unstar' : 'Star');
          btn.innerHTML = starIconHtml(nowStarred);
          if (nowPlayingTrackKey && String(btn.dataset.ratingKey || '') === String(nowPlayingTrackKey)) {
            updateNowPlayingFavButton(nowPlayingTrackKey);
          }
          notifyFavorite(track, null, nowStarred);
        };
      });
      const starAlbumBtn = $('btnAlbumDetailStar');
      if (starAlbumBtn) {
        starAlbumBtn.onclick = (ev) => {
          ev.preventDefault();
          ev.stopPropagation();
          const rk = String(album.ratingKey || '');
          const albumPayload = Object.assign({}, album, { title: displayTitle || album.title });
          const nowStarred = toggleAlbumStar(rk, albumPayload);
          syncAlbumDetailStarButton(rk, nowStarred);
          syncVisibleAlbumGridStars(rk, nowStarred);
          notifyFavoriteAlbum(albumPayload, nowStarred);
          updateFilterState();
        };
      }
      $('viewTracks').querySelectorAll('.tracks li').forEach((li, i) => {
        li.dataset.search = makeSearchBlob(currentTracks[i]);
      });
      updateFilterState();
    } catch (e) {
      $('viewTracks').innerHTML =
        '<div class="breadcrumb"><button type="button" id="backAlb3">← Albums</button></div><p class="msg error">' +
        escapeHtml(e.message) +
        '</p>';
      $('backAlb3').onclick = backToAlbums;
    }
  }

  /** Load album grid if we're on the albums list and it's empty (e.g. deep link to #albums). */
  function ensureAlbumsGridLoaded() {
    const section = cfg().sectionKey || ($('inSection') && $('inSection').value);
    if (!section) return;
    if (navView !== 'albums') return;
    if ($('viewTracks') && $('viewTracks').style.display !== 'none') return;
    if ($('viewAlbums') && $('viewAlbums').style.display === 'none') return;
    const grid = $('albumGrid');
    if (!grid || !grid.querySelector('.album')) {
      if (!isLoadingAlbums) loadAlbums(false).catch(() => {});
    }
  }

  function backToAlbums(opts) {
    const o = opts || {};
    const wantFullLibrary = o.fullLibrary === true;
    const hadArtistChrome = wantFullLibrary && !!$('backToAllAlbums');
    const hadArtistScope = wantFullLibrary && currentArtistKey != null;
    navView = 'albums';
    syncSidebarNavActive();
    currentArtistKey = null;
    if (o.updateLocation !== false && location.hash !== '#albums') location.hash = '#albums';
    $('viewLibrary').style.display = 'none';
    $('viewPlaylists').style.display = 'none';
    $('viewTracks').style.display = 'none';
    $('viewAlbums').style.display = 'block';
    $('viewRadio').style.display = 'none';
    $('albumViewToggle').style.display = '';
    currentAlbum = null;
    currentTracks = [];
    document.title = 'pleXtr';
    updateFilterState();
    if (o.skipLoad) return;
    if (hadArtistChrome || hadArtistScope) loadAlbums(false).catch(() => {});
    else scheduleAlbumAutoLoad(50);
  }

  async function openAlbumByKey(ratingKey) {
    if (!ratingKey) return;
    try {
      const data = await plexFetch('/library/metadata/' + encodeURIComponent(String(ratingKey)));
      const meta = data.MediaContainer.Metadata;
      const album = meta && meta[0];
      if (album) await openAlbum(album);
    } catch (e) {
      console.error(e);
      showError(e.message || String(e));
    }
  }

  async function showArtistView(artistKey) {
    if (!artistKey) return;
    currentArtistKey = String(artistKey);
    navView = 'albums';
    syncSidebarNavActive();
    $('viewLibrary').style.display = 'none';
    $('viewPlaylists').style.display = 'none';
    $('viewTracks').style.display = 'none';
    $('viewAlbums').style.display = 'block';
    $('viewAlbums').innerHTML = '<p class="msg"><span class="spinner"></span> Loading artist…</p>';
    showError('');
    try {
      const [artistData, childrenData] = await Promise.all([
        plexFetch('/library/metadata/' + encodeURIComponent(String(artistKey))),
        plexFetch('/library/metadata/' + encodeURIComponent(String(artistKey)) + '/children')
      ]);
      const artistMeta = artistData.MediaContainer.Metadata && artistData.MediaContainer.Metadata[0];
      const artistTitle = (artistMeta && artistMeta.title) || 'Artist';
      const albums = (childrenData.MediaContainer.Metadata || []).filter((m) => m.type === 'album');
      const gridHtml = '<div class="albums" id="albumGrid"></div>';
      $('viewAlbums').innerHTML =
        '<div class="breadcrumb">' +
        '<button type="button" id="backToAllAlbums">← Albums</button>' +
        '<span style="color:var(--muted)"> / ' + escapeHtml(artistTitle) + '</span></div>' +
        gridHtml;
      $('backToAllAlbums').onclick = () => {
        if (location.hash !== '#albums') location.hash = '#albums';
        else backToAlbums({ updateLocation: false, fullLibrary: true });
        ensureAlbumsGridLoaded();
      };
      const grid = $('albumGrid');
      const starredAlbums = getStarredAlbums();
      albums.forEach((a) => {
        albumLookup.set(String(a.ratingKey || ''), a);
        const div = document.createElement('div');
        div.className = 'album';
        div.dataset.key = a.ratingKey;
        div.dataset.search = makeSearchBlob(a);
        const img = thumbUrl(a.thumb || a.parentThumb) || fallbackThumbDataUrl();
        const initialAlbumTitle = isUnknownAlbumTitle(a.title) ? '[Unknown Album]' : String(a.title || '');
        const albumHref = albumHash(a.ratingKey);
        const artistHref = artistHash(a.parentRatingKey);
        const albumStarred = starredAlbums.has(String(a.ratingKey));
        div.innerHTML =
          '<div class="star-wrap"><button type="button" class="star-btn' + (albumStarred ? ' starred' : '') + '" data-rating-key="' + escapeHtml(String(a.ratingKey)) + '" aria-label="' + (albumStarred ? 'Unstar album' : 'Star album') + '" title="' + (albumStarred ? 'Unstar' : 'Star') + '">' + starIconHtml(albumStarred) + '</button></div>' +
          '<div class="album-art">' +
          '<button type="button" class="album-play-btn" aria-label="Play album" title="Play album">' +
          albumCardPlayIconHtml() +
          '</button>' +
          '<img src="' + img + '" alt="" loading="lazy" /></div>' +
          '<div class="meta"><div class="title">' + makeMetaLinkHtml(initialAlbumTitle, albumHref, 'album') + '</div>' +
          '<div class="artist">' + makeMetaLinkHtml(String(a.parentTitle || ''), artistHref, 'artist') + '</div>' +
          '<div class="song-matches"></div></div>';
        div.onclick = (ev) => {
          if (ev.target.closest('a[data-meta-link]')) return;
          if (ev.target.closest('.star-btn')) return;
          if (ev.target.closest('.album-play-btn')) return;
          openAlbum(a);
        };
        const playBtn = div.querySelector('.album-play-btn');
        if (playBtn) {
          playBtn.onclick = (ev) => playAlbumFromCard(a, ev);
        }
        const starBtn = div.querySelector('.star-btn');
        if (starBtn) {
          starBtn.onclick = (ev) => {
            ev.preventDefault();
            ev.stopPropagation();
            const nowStarred = toggleAlbumStar(starBtn.dataset.ratingKey, a);
            starBtn.classList.toggle('starred', nowStarred);
            starBtn.setAttribute('aria-label', nowStarred ? 'Unstar album' : 'Star album');
            starBtn.setAttribute('title', nowStarred ? 'Unstar' : 'Star');
            starBtn.innerHTML = starIconHtml(nowStarred);
            notifyFavoriteAlbum(a, nowStarred);
            syncAlbumDetailStarButton(a.ratingKey, nowStarred);
            updateFilterState();
          };
        }
        grid.appendChild(div);
      });
      applyAlbumViewMode();
      location.hash = artistHash(artistKey);
    } catch (e) {
      console.error(e);
      $('viewAlbums').innerHTML = '';
      showError(e.message || String(e));
    }
  }

  function applyHashRoute() {
    const hash = (location.hash || '').trim();
    ytDebug('apply-hash-route', { hash });
    const viewMatch = hash.match(/^#(albums|youtube|playlists|favorites|recent)$/);
    const youtubeMatch = hash.match(/^#youtube\/([^/]+)$/);
    const radioMatch = hash.match(/^#radio(?:\/([^/]+))?$/);
    const albumMatch = hash.match(/^#album\/([^/]+)$/);
    const artistMatch = hash.match(/^#artist\/([^/]+)$/);
    const directoryMatch = hash.match(/^#directory\/(.+)$/);
    if (!hash || hash === '#') {
      const connected = !!(cfg().server && cfg().token);
      const want = connected ? '#albums' : '#radio';
      if (location.hash !== want) location.hash = want;
      return;
    }
    if (viewMatch) {
      const view = viewMatch[1];
      if (view === 'albums') {
        backToAlbums({ updateLocation: false, fullLibrary: true });
        ensureAlbumsGridLoaded();
      } else {
        setNavView(view, { updateLocation: false });
      }
      return;
    }
    if (radioMatch) {
      setNavView('radio', { updateLocation: false });
      const idSeg = radioMatch[1];
      if (idSeg) {
        const id = decodeURIComponent(idSeg);
        if (!radioStations.length) buildRadioData();
        const idx = radioStations.findIndex((station) => station.id === id);
        if (idx >= 0) {
          const playingThis =
            isRadioMode &&
            currentRadioIdx >= 0 &&
            radioStations[currentRadioIdx] &&
            radioStations[currentRadioIdx].id === id;
          if (!playingThis) playRadioIndex(idx);
        }
      }
      return;
    }
    if (youtubeMatch) {
      setNavView('youtube', { updateLocation: false });
      const seg = youtubeRouteSegment(hash);
      if (seg) {
        ytDebug('hash-youtube-route', { hash, segment: seg });
        youtubeSelection = youtubeSelection || {};
        youtubeSelection.input = seg;
        const inputEl = $('youtubeUrlInput');
        if (inputEl) inputEl.value = seg;
      }
      return;
    }
    if (albumMatch) {
      const key = decodeURIComponent(albumMatch[1]);
      navView = 'albums';
      syncSidebarNavActive();
      if (currentAlbum && String(currentAlbum.ratingKey) === String(key)) return;
      openAlbumByKey(key);
      return;
    }
    if (artistMatch) {
      const key = decodeURIComponent(artistMatch[1]);
      navView = 'albums';
      syncSidebarNavActive();
      if (currentArtistKey != null && String(currentArtistKey) === String(key)) return;
      showArtistView(key);
      return;
    }
    if (directoryMatch) {
      const normalized = normalizePathSeparators(directoryPathFromRouteSegment(directoryMatch[1]));
      if (!normalized) return;
      openDirectoryView(normalized, { updateLocation: false }).catch((err) => {
        console.error(err);
        showError(err.message || String(err));
      });
      return;
    }
  }

  let playingIdx = -1;
  let nowPlayingTrackKey = '';
  let lastPlaybackPersistMs = 0;
  let lastAudiobookSyncAt = 0;
  let lastAudiobookSyncPosition = -1;
  let lastAudiobookSyncBookId = '';

  function resolveDirectoryPathFromEntry(entry, currentPath, options) {
    const o = options || {};
    if (!entry || typeof entry !== 'object') return '';
    if (entry.path) return normalizePathSeparators(entry.path);
    const key = String(entry.key || '');
    if (key && key.includes('?')) {
      const query = key.split('?')[1];
      const params = new URLSearchParams(query || '');
      const p = params.get('path');
      if (p) return normalizePathSeparators(p);
    }
    const name = String(entry.title || entry.name || '').trim();
    if (!name) return '';
    const base = normalizePathSeparators(currentPath || '');
    if (!base) return '';
    if (o.allowNameFallback === false) return '';
    return (base.endsWith('/') ? base : base + '/') + name;
  }

  function isFolderLikeMetadataEntry(entry) {
    if (!entry || typeof entry !== 'object') return false;
    const t = String(entry.type || '').toLowerCase();
    if (t === 'folder' || t === 'directory') return true;
    if (entry.directory === true || entry.Directory === true || entry.isDirectory === true) return true;
    const key = String(entry.key || '');
    if (/\/folder(?:$|\?)/i.test(key)) return true;
    if (t === 'album' || isPlexAudioLeafForPlayback(entry)) return false;
    if (entry.ratingKey && (entry.thumb || entry.parentThumb || entry.grandparentThumb)) return false;
    return !!(entry.title || entry.name) && !entry.ratingKey;
  }

  function isDirectChildPath(parentPath, childPath) {
    const parent = normalizePathSeparators(parentPath);
    const child = normalizePathSeparators(childPath);
    if (!parent || !child) return false;
    const base = parent.endsWith('/') ? parent : parent + '/';
    if (!child.startsWith(base)) return false;
    const rest = child.slice(base.length);
    if (!rest) return false;
    return !rest.includes('/');
  }

  function directChildDirFromFilePath(parentPath, filePath) {
    const parent = normalizePathSeparators(parentPath);
    const full = normalizePathSeparators(filePath);
    if (!parent || !full) return '';
    const base = parent.endsWith('/') ? parent : parent + '/';
    if (!full.startsWith(base)) return '';
    const rest = full.slice(base.length);
    if (!rest || !rest.includes('/')) return '';
    const first = rest.split('/')[0];
    if (!first) return '';
    return base + first;
  }

  async function plexFetchDirectoryCandidates(sectionKey, directoryPath) {
    const qs = new URLSearchParams({ path: directoryPath }).toString();
    const out = { directories: [], metadata: [] };
    const folderUrl = '/library/sections/' + encodeURIComponent(sectionKey) + '/folder?' + qs;
    try {
      const data = await plexFetch(folderUrl);
      const mc = data && data.MediaContainer ? data.MediaContainer : {};
      if (Array.isArray(mc.Directory)) out.directories = out.directories.concat(mc.Directory);
      if (Array.isArray(mc.Metadata)) out.metadata = out.metadata.concat(mc.Metadata);
    } catch (_) {}
    // Fallback only when folder endpoint is empty/sparse.
    if (!out.directories.length && !out.metadata.length) {
      const fallbackUrl = '/library/sections/' + encodeURIComponent(sectionKey) + '/all?type=8&' + qs;
      try {
        const data = await plexFetch(fallbackUrl);
        const mc = data && data.MediaContainer ? data.MediaContainer : {};
        if (Array.isArray(mc.Directory)) out.directories = out.directories.concat(mc.Directory);
        if (Array.isArray(mc.Metadata)) out.metadata = out.metadata.concat(mc.Metadata);
      } catch (_) {}
    }
    return out;
  }

  function openDirectoryByPath(pathValue) {
    const nextPath = normalizePathSeparators(pathValue);
    if (!nextPath) return;
    const nextHash = directoryHash(nextPath);
    if (!nextHash) return;
    if (location.hash !== nextHash) {
      location.hash = nextHash;
      return;
    }
    openDirectoryView(nextPath, { updateLocation: false }).catch((err) => {
      console.error(err);
      showError(err.message || String(err));
    });
  }

  async function openDirectoryView(pathValue, options) {
    const o = options || {};
    const sectionKey = String($('inSection').value || cfg().sectionKey || '').trim();
    if (!sectionKey) {
      showError('Select a Plex library first.');
      return;
    }
    const directoryPath = normalizePathSeparators(pathValue);
    if (!directoryPath) return;
    if (o.updateLocation !== false) {
      const h = directoryHash(directoryPath);
      if (h && location.hash !== h) {
        location.hash = h;
        return;
      }
    }
    navView = 'albums';
    syncSidebarNavActive();
    $('viewLibrary').style.display = 'none';
    $('viewPlaylists').style.display = 'none';
    $('viewAlbums').style.display = 'none';
    $('viewTracks').style.display = 'block';
    $('albumViewToggle').style.display = 'none';
    $('viewTracks').innerHTML =
      '<div class="breadcrumb"><button type="button" id="backAlbDir">← Albums</button></div>' +
      '<h2 style="margin:0 0 .55rem;font-size:1.05rem">' +
      breadcrumbLinkHtmlFromPath(directoryPath, true) +
      '</h2>' +
      '<p class="msg"><span class="spinner"></span> Loading directory…</p>';
    $('backAlbDir').onclick = backToAlbums;
    showError('');
    try {
      const candidates = await plexFetchDirectoryCandidates(sectionKey, directoryPath);
      const directoriesRaw = Array.isArray(candidates.directories) ? candidates.directories : [];
      const metadata = Array.isArray(candidates.metadata) ? candidates.metadata : [];
      const metadataDirs = metadata.filter(isFolderLikeMetadataEntry);
      const dirMap = new Map();
      const currentBaseName = pathBasename(directoryPath).toLowerCase();

      const addDirectoryCandidate = (dir, target) => {
        const resolved = normalizePathSeparators(target);
        if (!resolved) return;
        if (resolved === directoryPath) return;
        if (!isDirectChildPath(directoryPath, resolved)) return;
        const key = resolved;
        if (!dirMap.has(key)) dirMap.set(key, { entry: dir, targetPath: resolved });
      };

      directoriesRaw.forEach((dir) => {
        const explicit = resolveDirectoryPathFromEntry(dir, directoryPath, { allowNameFallback: false });
        addDirectoryCandidate(dir, explicit);
      });
      metadataDirs.forEach((dir) => {
        // Metadata entries are noisy; only accept ones that expose an explicit path.
        const explicit = resolveDirectoryPathFromEntry(dir, directoryPath, { allowNameFallback: false });
        addDirectoryCandidate(dir, explicit);
      });
      // Some Plex servers omit Directory rows; infer direct child folders from item file paths.
      metadata.forEach((item) => {
        if (!isPlexAudioLeafForPlayback(item)) return;
        const fp = getTrackFilePath(item);
        const inferred = directChildDirFromFilePath(directoryPath, fp);
        if (!inferred) return;
        const inferredTitle = pathBasename(inferred);
        addDirectoryCandidate({ title: inferredTitle, key: '/library/sections/' + sectionKey + '/folder?path=' + encodeURIComponent(inferred) }, inferred);
      });
      // Last resort only: if Plex omits explicit paths, derive from title for Directory rows.
      if (!dirMap.size) {
        directoriesRaw.forEach((dir) => {
          const title = String((dir && (dir.title || dir.name)) || '').trim();
          if (!title) return;
          if (title.toLowerCase() === currentBaseName) return;
          const target = (directoryPath.endsWith('/') ? directoryPath : directoryPath + '/') + title;
          addDirectoryCandidate(dir, target);
        });
      }
      // Final fallback: if Plex exposes only artist metadata for this path, map titles to direct child folders.
      if (!dirMap.size) {
        const seenArtistTitle = new Set();
        metadata.forEach((item) => {
          if (String((item && item.type) || '').toLowerCase() !== 'artist') return;
          const title = String((item && item.title) || '').trim();
          if (!title) return;
          const keyTitle = title.toLowerCase();
          if (keyTitle === 'artist' || keyTitle === currentBaseName) return;
          if (seenArtistTitle.has(keyTitle)) return;
          seenArtistTitle.add(keyTitle);
          const target = (directoryPath.endsWith('/') ? directoryPath : directoryPath + '/') + title;
          addDirectoryCandidate({ title: title }, target);
        });
      }
      const currentPlayableCount = metadata.filter((item) => isPlexAudioLeafForPlayback(item)).length;
      if (!dirMap.size && currentPlayableCount <= 1) {
        // Deep folder fallback: some Plex libraries only expose artist folders via /all?path.
        try {
          const broad = await plexFetch(
            '/library/sections/' + encodeURIComponent(sectionKey) + '/all?' + new URLSearchParams({ path: directoryPath }).toString()
          );
          const broadMeta = broad && broad.MediaContainer && Array.isArray(broad.MediaContainer.Metadata)
            ? broad.MediaContainer.Metadata
            : [];
          const seenArtistTitle = new Set();
          broadMeta.forEach((item) => {
            if (String((item && item.type) || '').toLowerCase() !== 'artist') return;
            const title = String((item && item.title) || '').trim();
            if (!title) return;
            const keyTitle = title.toLowerCase();
            if (keyTitle === 'artist' || keyTitle === currentBaseName) return;
            if (seenArtistTitle.has(keyTitle)) return;
            seenArtistTitle.add(keyTitle);
            const target = (directoryPath.endsWith('/') ? directoryPath : directoryPath + '/') + title;
            addDirectoryCandidate({ title: title }, target);
          });
        } catch (_) {}
      }
      const directories = (() => {
        const sorted = Array.from(dirMap.values()).sort((a, b) => {
          const at = String((a && a.entry && (a.entry.title || a.entry.name)) || '').toLowerCase();
          const bt = String((b && b.entry && (b.entry.title || b.entry.name)) || '').toLowerCase();
          return at.localeCompare(bt);
        });
        const seenByTitle = new Set();
        const unique = [];
        sorted.forEach((dirInfo) => {
          const target = normalizePathSeparators((dirInfo && dirInfo.targetPath) || '');
          const dir = dirInfo && dirInfo.entry ? dirInfo.entry : {};
          const rawTitle = String((dir && (dir.title || dir.name)) || pathBasename(target) || '').trim();
          if (!rawTitle) return;
          const key = rawTitle.toLowerCase();
          if (seenByTitle.has(key)) return;
          seenByTitle.add(key);
          unique.push(dirInfo);
        });
        return unique;
      })();
      const albums = metadata.filter((item) => String((item && item.type) || '').toLowerCase() === 'album');
      const playable = metadata.filter((item) => isPlexAudioLeafForPlayback(item) && String((item && item.type) || '').toLowerCase() !== 'album');
      const folderHtml = directories.length
        ? '<div class="directory-group"><h3>Folders</h3><ul class="directory-list">' +
          directories.map((dirInfo, idx) => {
            const target = normalizePathSeparators(dirInfo && dirInfo.targetPath || '');
            const dir = dirInfo && dirInfo.entry ? dirInfo.entry : {};
            const title = String((dir && (dir.title || dir.name)) || pathBasename(target) || 'Folder');
            return (
              '<li><button type="button" data-open-directory="' +
              escapeHtml(target || '') +
              '" data-dir-index="' +
              idx +
              '">📁 ' +
              escapeHtml(title) +
              '</button></li>'
            );
          }).join('') +
          '</ul></div>'
        : '';
      const albumsHtml = albums.length
        ? '<div class="directory-group"><h3>Albums</h3><ul class="directory-list">' +
          albums.map((album) => (
            '<li><button type="button" data-open-album="' +
            escapeHtml(String(album.ratingKey || '')) +
            '">💿 ' +
            escapeHtml(String(album.title || '[Album]')) +
            '</button></li>'
          )).join('') +
          '</ul></div>'
        : '';
      const trackHtml = playable.length
        ? '<div class="directory-group"><h3>Tracks</h3><span class="directory-leaf-text">' +
          escapeHtml(String(playable.length) + ' playable file' + (playable.length === 1 ? '' : 's')) +
          '</span></div>'
        : '';
      if (!folderHtml && !albumsHtml && !trackHtml) {
        $('viewTracks').innerHTML =
          '<div class="breadcrumb"><button type="button" id="backAlbDir2">← Albums</button></div>' +
          '<h2 style="margin:0 0 .55rem;font-size:1.05rem">' +
          breadcrumbLinkHtmlFromPath(directoryPath, true) +
          '</h2>' +
          '<p class="msg">This folder is empty.</p>';
        $('backAlbDir2').onclick = backToAlbums;
        return;
      }
      $('viewTracks').innerHTML =
        '<div class="breadcrumb"><button type="button" id="backAlbDir3">← Albums</button></div>' +
        '<h2 style="margin:0 0 .55rem;font-size:1.05rem">' +
        breadcrumbLinkHtmlFromPath(directoryPath, true) +
        '</h2>' +
        '<div class="directory-browser">' +
        folderHtml +
        albumsHtml +
        trackHtml +
        '</div>';
      $('backAlbDir3').onclick = backToAlbums;
      $('viewTracks').querySelectorAll('[data-open-directory]').forEach((btn) => {
        btn.onclick = () => {
          const target = String(btn.getAttribute('data-open-directory') || '');
          if (!target) return;
          openDirectoryByPath(target);
        };
      });
      $('viewTracks').querySelectorAll('[data-open-album]').forEach((btn) => {
        btn.onclick = () => {
          const key = String(btn.getAttribute('data-open-album') || '');
          if (!key) return;
          openAlbumByKey(key);
        };
      });
    } catch (e) {
      $('viewTracks').innerHTML =
        '<div class="breadcrumb"><button type="button" id="backAlbDir4">← Albums</button></div>' +
        '<p class="msg error">' +
        escapeHtml(e.message || String(e)) +
        '</p>';
      $('backAlbDir4').onclick = backToAlbums;
    }
  }

  async function pickLatestNostrAudiobookProgress(bookId, sectionKey) {
    const events = await queryNostrAudiobookProgressEvents(bookId || '', 40);
    for (let i = 0; i < events.length; i++) {
      const parsed = await parseNostrAudiobookProgressEvent(events[i]);
      if (!parsed) continue;
      if (sectionKey && String(parsed.sectionKey) !== String(sectionKey)) continue;
      if (bookId && String(parsed.audiobookBookId) !== String(bookId)) continue;
      return parsed;
    }
    return null;
  }

  async function maybeDeleteSupersededAudiobookProgress(bookId, keepEventId) {
    if (!bookId || !keepEventId) return;
    const prior = await queryNostrAudiobookProgressEvents(bookId, 20);
    if (!Array.isArray(prior) || !prior.length) return;
    const stale = [];
    for (let i = 0; i < prior.length; i++) {
      const ev = prior[i];
      if (!ev || !ev.id || ev.id === keepEventId) continue;
      stale.push(String(ev.id));
      if (stale.length >= 6) break;
    }
    if (stale.length) {
      publishNostrDeleteEvents(stale, 'Superseded audiobook progress');
    }
  }

  async function publishNostrAudiobookProgress(state, forceSync) {
    if (!state || !state.audiobookBookId) return;
    if (!nostrState.ready || !nostrState.secretKey || !nostrState.pool || !nostrState.pubkey) return;
    const now = Date.now();
    const bookId = String(state.audiobookBookId || '');
    const position = Number(state.currentTime) || 0;
    if (!forceSync) {
      if (bookId === lastAudiobookSyncBookId && now - lastAudiobookSyncAt < NOSTR_AUDIOBOOK_PROGRESS_SYNC_MS) return;
      if (
        bookId === lastAudiobookSyncBookId &&
        Math.abs(position - lastAudiobookSyncPosition) < NOSTR_AUDIOBOOK_PROGRESS_MIN_DELTA_SECONDS &&
        now - lastAudiobookSyncAt < NOSTR_AUDIOBOOK_PROGRESS_SYNC_MS * 2
      ) {
        return;
      }
    }
    try {
      const tools = await getNostrTools();
      const payload = {
        kind: 'audiobook_progress',
        spec: PLEXTR_AUDIOBOOK_PROGRESS_SPEC_VER,
        state: Object.assign({}, state, {
          updatedAt: Number(state.updatedAt) || now,
          savedAt: Number(state.savedAt) || now,
          audiobookBookId: bookId
        })
      };
      const encrypted = await encryptNostrDmContent(tools, JSON.stringify(payload), nostrState.pubkey);
      const evKind = encrypted.algorithm === 'nip44' ? 14 : 4;
      const tags = [
        ['p', nostrState.pubkey],
        ['t', NOSTR_TAG],
        ['t', 'radioguaka'],
        ['t', 'audiobook_progress'],
        ['client', 'plextr'],
        ['plextr_item', 'audiobook_progress'],
        ['plextr_ver', PLEXTR_AUDIOBOOK_PROGRESS_SPEC_VER],
        ['encryption', encrypted.algorithm],
        ['d', bookId],
        ['plextr_book_id', bookId],
        ['plextr_section_key', String(state.sectionKey || '')],
        ['plextr_album_rating_key', String(state.albumRatingKey || '')]
      ];
      if (state.trackRatingKey) tags.push(['plextr_rating_key', String(state.trackRatingKey)]);
      if (state.partKey) tags.push(['plextr_part_key', String(state.partKey)]);
      const eventTemplate = {
        kind: evKind,
        created_at: Math.floor(now / 1000),
        tags: tags,
        content: encrypted.content
      };
      const signed = tools.finalizeEvent(eventTemplate, nostrState.secretKey);
      safeNostrPoolPublish(nostrState.pool, NOSTR_RELAYS, signed);
      lastAudiobookSyncAt = now;
      lastAudiobookSyncPosition = position;
      lastAudiobookSyncBookId = bookId;
      setNostrStatus('Synced audiobook progress.', 'ok');
      maybeDeleteSupersededAudiobookProgress(bookId, signed.id).catch(() => {});
    } catch (err) {
      setNostrStatus(
        'Audiobook sync skipped: ' + (err && err.message ? err.message : String(err)),
        'error'
      );
    }
  }

  function persistCurrentPlaybackState(overrides) {
    if (isRadioMode) {
      const station = radioStations[currentRadioIdx];
      if (station) savePlaybackState({ type: 'radio', stationId: station.id });
      return;
    }
    const track = currentTracks[playingIdx];
    const partKeyPersist = getFirstPlayablePartKey(track);
    if (!track || !partKeyPersist) return;
    const audio = $('audio');
    const sectionKey = $('inSection').value || cfg().sectionKey || '';
    const now = Date.now();
    const opts = overrides && typeof overrides === 'object' ? Object.assign({}, overrides) : {};
    const forceNostrSync = !!opts.forceNostrSync;
    if (Object.prototype.hasOwnProperty.call(opts, 'forceNostrSync')) delete opts.forceNostrSync;
    const state = {
      sectionKey: sectionKey || '',
      albumRatingKey: track.parentRatingKey
        ? String(track.parentRatingKey)
        : currentAlbum && currentAlbum.ratingKey
          ? String(currentAlbum.ratingKey)
          : '',
      albumTitle: track.parentTitle
        ? String(track.parentTitle)
        : currentAlbum && currentAlbum.title
          ? String(currentAlbum.title)
          : '',
      albumParentTitle: track.grandparentTitle
        ? String(track.grandparentTitle)
        : currentAlbum && currentAlbum.parentTitle
          ? String(currentAlbum.parentTitle)
          : '',
      trackIndex: playingIdx,
      trackRatingKey: track.ratingKey ? String(track.ratingKey) : '',
      trackTitle: track.title ? String(track.title) : '',
      partKey: partKeyPersist,
      currentTime: audio._pendingRestoreTime != null ? audio._pendingRestoreTime : (Number(audio.currentTime) || 0),
      wasPlaying: !audio.paused && !audio.ended,
      savedAt: now,
      updatedAt: now
    };
    if (opts && typeof opts === 'object') Object.assign(state, opts);
    const asAudiobook = trackLooksLikeAudiobook(track);
    state.audiobookBookId = asAudiobook ? computeAudiobookBookId(state) : '';
    savePlaybackState(state);
    lastPlaybackPersistMs = now;
    if (state.audiobookBookId) {
      publishNostrAudiobookProgress(state, forceNostrSync).catch(() => {});
    }
  }

  function maybePersistPlaybackState() {
    const now = Date.now();
    if (now - lastPlaybackPersistMs < 2000) return;
    persistCurrentPlaybackState();
  }

  function syncVisibleTrackStarButtons(trackRatingKey, starred) {
    const key = String(trackRatingKey || '');
    if (!key) return;
    $('viewTracks').querySelectorAll('.tracks li .star-btn').forEach((btn) => {
      if (String(btn.dataset.ratingKey || '') !== key) return;
      btn.classList.toggle('starred', starred);
      btn.setAttribute('aria-label', starred ? 'Unstar track' : 'Star track');
      btn.setAttribute('title', starred ? 'Unstar' : 'Star');
      btn.innerHTML = starIconHtml(starred);
    });
  }

  function syncAlbumDetailStarButton(albumRatingKey, starred) {
    const btn = $('btnAlbumDetailStar');
    if (!btn || String(btn.dataset.ratingKey || '') !== String(albumRatingKey || '')) return;
    btn.classList.toggle('starred', starred);
    btn.setAttribute('aria-label', starred ? 'Unstar album' : 'Star album');
    btn.setAttribute('title', starred ? 'Unstar' : 'Star');
    btn.innerHTML = starIconHtml(starred);
  }

  function syncVisibleAlbumGridStars(albumRatingKey, starred) {
    const key = String(albumRatingKey || '');
    if (!key) return;
    const grid = $('albumGrid');
    if (!grid) return;
    grid.querySelectorAll('.album .star-btn').forEach((btn) => {
      if (String(btn.dataset.ratingKey || '') !== key) return;
      btn.classList.toggle('starred', starred);
      btn.setAttribute('aria-label', starred ? 'Unstar album' : 'Star album');
      btn.setAttribute('title', starred ? 'Unstar' : 'Star');
      btn.innerHTML = starIconHtml(starred);
    });
  }

  function updateNowPlayingFavButton(trackRatingKey) {
    const btn = $('btnFavNow');
    const key = String(trackRatingKey || '');
    nowPlayingTrackKey = key;
    if (!key) {
      btn.disabled = true;
      btn.classList.remove('starred');
      btn.innerHTML = starIconHtml(false);
      btn.setAttribute('aria-label', 'Favorite current track');
      btn.setAttribute('title', 'Favorite current track');
      return;
    }
    const starred = getStarredTracks().has(key);
    btn.disabled = false;
    btn.classList.toggle('starred', starred);
    btn.innerHTML = starIconHtml(starred);
    btn.setAttribute('aria-label', starred ? 'Unfavorite current track' : 'Favorite current track');
    btn.setAttribute('title', starred ? 'Unfavorite current track' : 'Favorite current track');
  }

  function updatePlayPauseButton() {
    const audio = $('audio');
    const btn = $('btnPlayPause');
    if (!audio || !btn) return;
    const isPlaying = !audio.paused && !audio.ended;
    btn.setAttribute('aria-label', isPlaying ? 'Pause' : 'Play');
    btn.setAttribute('title', isPlaying ? 'Pause' : 'Play');
    btn.innerHTML = isPlaying
      ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5h3v14H8z" fill="currentColor" stroke="none"></path><path d="M13 5h3v14h-3z" fill="currentColor" stroke="none"></path></svg>'
      : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z" fill="currentColor" stroke="none"></path></svg>';
  }

  function setupMediaSession() {
    if (!('mediaSession' in navigator)) return;
    try {
      navigator.mediaSession.setActionHandler('play', () => {
        const audio = $('audio');
        if (!audio.currentSrc) return;
        audio.play().catch(() => {});
      });
      navigator.mediaSession.setActionHandler('pause', () => {
        const audio = $('audio');
        if (!audio.currentSrc) return;
        audio.pause();
      });
      navigator.mediaSession.setActionHandler('previoustrack', () => {
        handlePreviousTrackAction();
      });
      navigator.mediaSession.setActionHandler('nexttrack', () => {
        handleNextTrackAction();
      });
      navigator.mediaSession.setActionHandler('seekforward', () => {
        if (!isRadioMode && playbackMode === PLAYBACK_MODE_ALBUM_SHUFFLE) {
          skipToNextRandomAlbum().catch((err) => {
            console.error(err);
            showError(err && err.message ? err.message : String(err));
          });
          return;
        }
        handleNextTrackAction();
      });
      navigator.mediaSession.setActionHandler('stop', () => {
        const audio = $('audio');
        if (!audio.currentSrc) return;
        audio.pause();
        try {
          audio.currentTime = 0;
        } catch (_) {}
      });
    } catch (_) {
      // Ignore unsupported action handlers on older browsers/head units.
    }
  }

  function updateMediaSessionNowPlaying(payload) {
    if (!('mediaSession' in navigator) || typeof MediaMetadata !== 'function') return;
    const data = payload || {};
    const title = String(data.title || '').trim() || 'Radio Guaka';
    const artist = String(data.artist || '').trim();
    const album = String(data.album || '').trim();
    const artworkSrc = String(data.artwork || '').trim();
    const artwork = artworkSrc
      ? [{ src: artworkSrc, sizes: '512x512', type: 'image/jpeg' }]
      : [{ src: fallbackThumbDataUrl(), sizes: '512x512', type: 'image/png' }];
    try {
      navigator.mediaSession.metadata = new MediaMetadata({
        title,
        artist,
        album,
        artwork
      });
    } catch (_) {}
  }

  function handlePreviousTrackAction() {
    if (isRadioMode && radioStations.length) {
      if (radioShuffleOn()) {
        playRadioIndex(randomRadioStationIndex(currentRadioIdx));
      } else {
        const prev = currentRadioIdx > 0 ? currentRadioIdx - 1 : radioStations.length - 1;
        playRadioIndex(prev);
      }
      return;
    }
    const audio = $('audio');
    if (playingIdx < 0 || !currentTracks.length) return;
    if (playingIdx > 0) {
      playIndex(playingIdx - 1);
    } else if (audio.currentTime > 4) {
      audio.currentTime = 0;
      audio.play().catch(() => {});
    }
  }

  function handleNextTrackAction() {
    if (isRadioMode && radioStations.length) {
      if (radioShuffleOn()) {
        playRadioIndex(randomRadioStationIndex(currentRadioIdx));
      } else {
        const next = currentRadioIdx + 1 < radioStations.length ? currentRadioIdx + 1 : 0;
        playRadioIndex(next);
      }
      return;
    }
    if (playingIdx < 0 || !currentTracks.length) return;
    if (playingIdx + 1 < currentTracks.length) {
      playIndex(playingIdx + 1);
    } else {
      $('audio').pause();
      persistCurrentPlaybackState({ wasPlaying: false, currentTime: 0 });
    }
  }

  function updateNowPlayingUI(t, i) {
    const fileHints = filenameDisplayHints(getTrackFilePath(t));
    const trackAlbumTitle = String(t.parentTitle || '').trim();
    const trackArtistTitle = String(t.grandparentTitle || '').trim();
    const albumTitle =
      trackAlbumTitle ||
      albumDisplayTitleFromTracks(currentAlbum, currentTracks) ||
      String(t.title || '').trim() ||
      fileHints.label ||
      '—';
    const artistTitle =
      trackArtistTitle ||
      (currentAlbum && String(currentAlbum.parentTitle || '').trim()) ||
      fileHints.artist ||
      '';
    const trackTitle =
      String(t.title || '').trim() || fileHints.title || fileHints.label || '';
    const albumKey = t.parentRatingKey || (currentAlbum && currentAlbum.ratingKey);
    const artistKey = t.grandparentRatingKey || (currentAlbum && currentAlbum.parentRatingKey);
    const albumHref = albumHash(albumKey);
    const artistHref = artistHash(artistKey);
    const npTitle = $('npTitle');
    const npAlbum = $('npAlbum');
    const npSub = $('npSub');
    const npArt = $('npArt');
    const npNext = $('npNext');
    npTitle.textContent = trackTitle || pathBasename(getTrackFilePath(t)) || '—';
    npTitle.href = '#';
    npTitle.style.pointerEvents = 'none';
    npAlbum.textContent = albumTitle || '';
    npAlbum.href = albumHref || '#';
    npAlbum.style.pointerEvents = albumHref ? '' : 'none';
    npSub.textContent = artistTitle;
    npSub.href = artistHref || '#';
    npSub.style.pointerEvents = artistHref ? '' : 'none';
    refreshNowPlayingWikipediaLinks({
      song: trackTitle || pathBasename(getTrackFilePath(t)) || '',
      album: albumTitle || '',
      artist: artistTitle || ''
    });
    npArt.src = nowPlayingArtUrl(t, currentAlbum, currentTracks);
    const next = currentTracks[i + 1];
    if (playbackMode === PLAYBACK_MODE_SONG_SHUFFLE && currentTracks.length > 1) {
      npNext.textContent = 'Up next: Random track';
    } else if (playbackMode === PLAYBACK_MODE_ALBUM_SHUFFLE && i + 1 >= currentTracks.length) {
      npNext.textContent = 'Up next: Random album';
    } else {
      npNext.textContent = next ? 'Up next: ' + trackDisplayText(next) : 'Up next: —';
    }
    updateNowPlayingFavButton(t && t.ratingKey ? String(t.ratingKey) : '');
    $('viewTracks').querySelectorAll('.tracks li').forEach((li, j) => {
      li.classList.toggle('playing', j === i);
    });
    document.title = artistTitle && trackTitle
      ? `${artistTitle} - ${trackTitle} | pleXtr`
      : (trackTitle ? `${trackTitle} | pleXtr` : 'pleXtr');
    updateMediaSessionNowPlaying({
      title: trackTitle || pathBasename(getTrackFilePath(t)) || '—',
      artist: artistTitle || '',
      album: albumTitle || '',
      artwork: nowPlayingArtUrl(t, currentAlbum, currentTracks)
    });
  }

  function playIndex(i, options) {
    const opts = options || {};
    const t = currentTracks[i];
    const partKey = getFirstPlayablePartKey(t);
    if (!t || !partKey) {
      showError('No playable file for this track.');
      return;
    }
    clearNowPlayingNoteSchedule();
    isRadioMode = false;
    $('audio').closest('.player-bar').classList.remove('radio-live');
    updateNextAlbumButtonVisibility();
    applyPlaybackModeUI();
    playingIdx = i;
    if (t && t.ratingKey) trackLookup.set(String(t.ratingKey), t);
    const audio = $('audio');
    audio._plexTrackForSource = t || null;
    const newUrl = streamUrl(partKey);
    plexDebug('play-index', {
      index: i,
      trackRatingKey: t && t.ratingKey ? String(t.ratingKey) : '',
      trackTitle: t && t.title ? String(t.title) : '',
      partKey: partKey,
      streamUrl: maskPlexToken(newUrl),
      pageOrigin: location.origin
    });
    if (!newUrl) {
      showError('No playable file for this track.');
      return;
    }
    const seekTime = Number(opts.seekTime);
    const targetVolume = Math.max(0, Math.min(1, typeof audio._targetVolume === 'number' ? audio._targetVolume : (audio.volume || 1)));
    audio._targetVolume = targetVolume;

    const applyPlay = () => {
      updateNowPlayingUI(t, i);
      const recent = getRecentTracks().filter((entry) => String(entry.ratingKey || '') !== String(t.ratingKey || ''));
      const snapshot = Object.assign({}, t, {
        title: effectiveTrackListName(t),
        parentTitle: String(t.parentTitle || (currentAlbum && currentAlbum.title) || ''),
        grandparentTitle: String(t.grandparentTitle || (currentAlbum && currentAlbum.parentTitle) || ''),
        albumRatingKey: (currentAlbum && currentAlbum.ratingKey) || t.parentRatingKey || ''
      });
      recent.unshift(snapshot);
      saveRecentTracks(recent);
      scheduleNotifyNowPlaying(t, null);
      setAudioElementSource(audio, newUrl);
      audio._pendingRestoreTime = Number.isFinite(seekTime) && seekTime > 0 ? seekTime : null;
      if (Number.isFinite(seekTime) && seekTime > 0) {
        const applySeek = () => {
          if (audio._pendingRestoreTime !== seekTime) return;
          try {
            const d = Number(audio.duration);
            if (Number.isFinite(d) && d > 0) {
              audio.currentTime = Math.min(seekTime, Math.max(0, d - 0.25));
              audio._pendingRestoreTime = null;
              return;
            }
            if (audio.seekable && audio.seekable.length > 0) {
              let end = 0;
              for (let s = 0; s < audio.seekable.length; s++) {
                const e = audio.seekable.end(s);
                if (e > end) end = e;
              }
              if (Number.isFinite(end) && end > 0 && seekTime <= end - 0.25) {
                audio.currentTime = seekTime;
                audio._pendingRestoreTime = null;
              }
            }
          } catch (_) {}
        };
        if (audio.readyState >= 1) applySeek();
        else audio.addEventListener('loadedmetadata', applySeek, { once: true });
      }
      if (opts.autoplay === false) {
        audio.pause();
        audio.volume = targetVolume;
        persistCurrentPlaybackState({ currentTime: Number.isFinite(seekTime) ? seekTime : 0 });
      } else {
        audio.volume = 0;
        audio.play().catch(() => {});
        runFadeIn(audio, targetVolume, () => {
          persistCurrentPlaybackState();
        });
      }
    };

    const useCrossfade = opts.crossfade !== false && audio.currentSrc && audio.currentSrc !== newUrl && (!audio.paused || audio.ended);
    if (useCrossfade) {
      runCrossfade(audio, targetVolume, () => {
        audio.pause();
        applyPlay();
      });
    } else {
      applyPlay();
    }
  }

  async function getLibraryTrackTotalCount(forceRefresh) {
    const section = String($('inSection').value || cfg().sectionKey || '');
    if (!section) return 0;
    if (
      !forceRefresh &&
      libraryTrackTotalCache.sectionKey === section &&
      libraryTrackTotalCache.total > 0
    ) {
      return libraryTrackTotalCache.total;
    }
    const data = await plexFetch(
      '/library/sections/' +
        encodeURIComponent(section) +
        '/all?type=10&X-Plex-Container-Start=0&X-Plex-Container-Size=1'
    );
    const mc = data.MediaContainer || {};
    const total = Number(mc.totalSize);
    const n = Number.isFinite(total) && total > 0 ? total : 0;
    libraryTrackTotalCache = { sectionKey: section, total: n };
    return n;
  }

  async function fetchRandomTrackMetaFromLibrary(excludedTrackKey) {
    const section = String($('inSection').value || cfg().sectionKey || '');
    if (!section) return null;
    let total = await getLibraryTrackTotalCount(false);
    if (total < 1) {
      total = await getLibraryTrackTotalCount(true);
    }
    if (total < 1) return null;
    const excluded = String(excludedTrackKey || '');
    const windowSize = Math.min(12, total);
    const maxAttempts = 40;
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      const startMax = Math.max(0, total - windowSize);
      const start = randomUniformIntBelow(startMax + 1);
      const data = await plexFetch(
        '/library/sections/' +
          encodeURIComponent(section) +
          '/all?type=10&X-Plex-Container-Start=' +
          start +
          '&X-Plex-Container-Size=' +
          windowSize
      );
      const meta = data.MediaContainer.Metadata || [];
      const pool = meta.filter(
        (t) => t && isPlexAudioLeafForPlayback(t) && t.ratingKey && t.parentRatingKey
      );
      if (!pool.length) continue;
      const avoidRecent = attempt < 28 && total > SONG_SHUFFLE_RECENT_CAP;
      const filtered = pool.filter((t) => {
        const key = String(t.ratingKey);
        if (excluded && key === excluded) return false;
        if (avoidRecent && isSongShuffleRecentlyPlayed(key)) return false;
        return true;
      });
      const pickFrom = filtered.length ? filtered : pool.filter((t) => {
        const key = String(t.ratingKey);
        if (excluded && key === excluded) return false;
        return true;
      });
      if (!pickFrom.length) continue;
      const choice = pickFrom[randomUniformIntBelow(pickFrom.length)];
      return choice;
    }
    return null;
  }

  async function pickRandomAlbum(excludedAlbumKey) {
    const excludedKey = String(excludedAlbumKey || '');
    const knownAlbums = Array.from(albumLookup.values()).filter(
      (album) => album && album.ratingKey && String(album.ratingKey) !== excludedKey
    );
    if (knownAlbums.length) {
      return knownAlbums[randomUniformIntBelow(knownAlbums.length)];
    }
    const section = $('inSection').value || cfg().sectionKey || '';
    if (!section) return null;
    const startMax = Math.max(0, albumTotalCount - PAGE);
    const start = startMax > 0 ? randomUniformIntBelow(startMax + 1) : 0;
    const data = await plexFetch(
      '/library/sections/' +
        encodeURIComponent(section) +
        '/all?type=9&X-Plex-Container-Start=' +
        start +
        '&X-Plex-Container-Size=' +
        PAGE
    );
    const candidates = (data.MediaContainer.Metadata || []).filter(
      (m) => m && m.type === 'album' && m.ratingKey && String(m.ratingKey) !== excludedKey
    );
    if (!candidates.length) return null;
    candidates.forEach((album) => {
      albumLookup.set(String(album.ratingKey || ''), album);
    });
    return candidates[randomUniformIntBelow(candidates.length)];
  }

  async function playRandomTrackViaRandomAlbum(excludedTrackKey) {
    const excludedKey = String(excludedTrackKey || '');
    for (let tries = 0; tries < 10; tries += 1) {
      const album = await pickRandomAlbum('');
      if (!album) return false;
      await openAlbum(album);
      if (!currentTracks.length) continue;
      const eligibleIndexes = [];
      currentTracks.forEach((track, idx) => {
        const trackKey = String((track && track.ratingKey) || '');
        if (!excludedKey || trackKey !== excludedKey) eligibleIndexes.push(idx);
      });
      if (!eligibleIndexes.length) continue;
      const pickIdx = eligibleIndexes[randomUniformIntBelow(eligibleIndexes.length)];
      playIndex(pickIdx);
      const played = currentTracks[pickIdx];
      if (played && played.ratingKey) rememberSongShufflePick(played.ratingKey);
      return true;
    }
    return false;
  }

  async function playRandomTrackFromLibrary(excludedTrackKey) {
    const trackMeta = await fetchRandomTrackMetaFromLibrary(excludedTrackKey);
    if (!trackMeta) return playRandomTrackViaRandomAlbum(excludedTrackKey);
    try {
      const albumData = await plexFetch(
        '/library/metadata/' + encodeURIComponent(String(trackMeta.parentRatingKey))
      );
      const album = albumData.MediaContainer.Metadata && albumData.MediaContainer.Metadata[0];
      if (!album || album.type !== 'album') return playRandomTrackViaRandomAlbum(excludedTrackKey);
      await openAlbum(album);
      const idx = currentTracks.findIndex(
        (tr) => String(tr && tr.ratingKey) === String(trackMeta.ratingKey)
      );
      if (idx < 0) return playRandomTrackViaRandomAlbum(excludedTrackKey);
      playIndex(idx);
      rememberSongShufflePick(trackMeta.ratingKey);
      return true;
    } catch (e) {
      console.error(e);
      return playRandomTrackViaRandomAlbum(excludedTrackKey);
    }
  }

  async function startPlaybackForShuffleMode() {
    if (playbackMode === PLAYBACK_MODE_OFF) return false;
    if (playbackMode === PLAYBACK_MODE_SONG_SHUFFLE) {
      return playRandomTrackFromLibrary('');
    }
    const album = await pickRandomAlbum('');
    if (!album) return false;
    await openAlbum(album);
    if (!currentTracks.length) return false;
    playIndex(0);
    return true;
  }

  async function skipToNextRandomAlbum() {
    if (playbackMode !== PLAYBACK_MODE_ALBUM_SHUFFLE || isRadioMode) return;
    try {
      const album = await pickRandomAlbum(currentAlbum && currentAlbum.ratingKey);
      if (!album) {
        showError('No other album found.');
        return;
      }
      await openAlbum(album);
      if (!currentTracks.length) return;
      playIndex(0);
    } catch (e) {
      console.error(e);
      showError(e.message || String(e));
    }
  }

  async function advanceByPlaybackMode() {
    if (playingIdx < 0 || !currentTracks.length) return false;
    if (playbackMode === PLAYBACK_MODE_SONG_SHUFFLE) {
      const currentTrack = currentTracks[playingIdx];
      const excludedTrackKey = currentTrack && currentTrack.ratingKey ? String(currentTrack.ratingKey) : '';
      return playRandomTrackFromLibrary(excludedTrackKey);
    }
    if (playbackMode === PLAYBACK_MODE_ALBUM_SHUFFLE) {
      if (playingIdx + 1 < currentTracks.length) {
        playIndex(playingIdx + 1);
        return true;
      }
      const album = await pickRandomAlbum(currentAlbum && currentAlbum.ratingKey);
      if (!album) return false;
      await openAlbum(album);
      if (!currentTracks.length) return false;
      playIndex(0);
      return true;
    }
    if (playingIdx + 1 < currentTracks.length) {
      playIndex(playingIdx + 1);
      return true;
    }
    return false;
  }

  bindClick('btnPrev', () => {
    handlePreviousTrackAction();
  });

  bindClick('btnFavNow', () => {
    if (isRadioMode && currentRadioIdx >= 0 && radioStations[currentRadioIdx]) {
      const id = radioStations[currentRadioIdx].id;
      const nowStarred = toggleStationStar(id);
      updateNowPlayingStationFavButton(id);
      renderRadioView();
      notifyFavorite(null, radioStations[currentRadioIdx], nowStarred);
      return;
    }
    if (!nowPlayingTrackKey) return;
    const track = currentTracks.find((item) => String(item && item.ratingKey) === String(nowPlayingTrackKey));
    const nowStarred = toggleTrackStar(nowPlayingTrackKey, track);
    syncVisibleTrackStarButtons(nowPlayingTrackKey, nowStarred);
    updateNowPlayingFavButton(nowPlayingTrackKey);
    notifyFavorite(track, null, nowStarred);
  });

  bindClick('btnNext', () => {
    handleNextTrackAction();
  });

  bindClick('btnNextAlbum', () => {
    skipToNextRandomAlbum().catch((err) => {
      console.error(err);
      showError(err.message || String(err));
    });
  });
  bindClick('btnPlayPause', async () => {
    const audio = $('audio');
    if (!audio.currentSrc) {
      if (playingIdx >= 0 && currentTracks[playingIdx]) {
        playIndex(playingIdx);
      } else if (currentTracks.length) {
        playIndex(0);
      } else {
        const started = await startPlaybackForShuffleMode().catch((err) => {
          console.error(err);
          return false;
        });
        if (!started) showError('Nothing to play yet. Open an album or enable and use shuffle.');
      }
      return;
    }
    if (audio.paused || audio.ended) audio.play().catch(() => {});
    else audio.pause();
  });

  $('audio').addEventListener('timeupdate', maybePersistPlaybackState);
  $('audio').addEventListener('timeupdate', () => {
    const a = $('audio');
    const seek = $('npSeek');
    const rawDur = Number(a.duration);
    const max = Number.isFinite(rawDur) && rawDur > 0 ? rawDur : 0;
    if (isRadioMode) {
      if (seek) {
        seek.max = '1000';
        seek.value = '0';
      }
      $('npTime').textContent = 'LIVE';
      $('npDur').textContent = '--:--';
      return;
    }
    if (seek && !seek.matches(':active')) {
      seek.max = String(Math.max(1, Math.floor(max * 10)));
      seek.value = String(Math.floor((Number(a.currentTime) || 0) * 10));
    }
    $('npTime').textContent = formatClock(a.currentTime);
    $('npDur').textContent = max > 0 ? formatClock(rawDur) : '--:--';
  });
  $('audio').addEventListener('volumechange', () => {
    const a = $('audio');
    a._targetVolume = a.volume;
  });
  $('audio').addEventListener('pause', () => {
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';
    persistCurrentPlaybackState({ wasPlaying: false, forceNostrSync: true });
    updatePlayPauseButton();
  });
  $('audio').addEventListener('play', () => {
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing';
    persistCurrentPlaybackState({ wasPlaying: true });
    updatePlayPauseButton();
  });
  $('audio').addEventListener('ended', async () => {
    const advanced = await advanceByPlaybackMode().catch((err) => {
      console.error(err);
      return false;
    });
    if (!advanced) persistCurrentPlaybackState({ wasPlaying: false, currentTime: 0 });
    updatePlayPauseButton();
  });
  // Extra diagnostics for YouTube playback/network failures.
  ['loadstart', 'loadedmetadata', 'canplay', 'playing', 'waiting', 'stalled', 'suspend', 'error'].forEach((evt) => {
    $('audio').addEventListener(evt, () => {
      if (navView !== 'youtube' && !isYouTubeRouteHash(location.hash)) return;
      ytDebug('audio-event:' + evt, audioDebugSnapshot($('audio')));
    });
  });
  $('npSeek').addEventListener('input', () => {
    if (isRadioMode) return;
    const a = $('audio');
    const seek = $('npSeek');
    const value = Number(seek.value) / 10;
    if (!Number.isFinite(value) || !Number.isFinite(a.duration)) return;
    a.currentTime = Math.max(0, Math.min(a.duration, value));
  });

  (function initAirPlay() {
    const audio = $('audio');
    const btn = $('btnAirPlay');
    if (!audio || !btn || typeof audio.webkitShowPlaybackTargetPicker !== 'function') return;
    btn.style.display = '';
    btn.onclick = () => {
      try {
        audio.webkitShowPlaybackTargetPicker();
      } catch (_) {}
    };
    function updateAirPlayLabel(isWireless) {
      btn.setAttribute('aria-label', isWireless ? 'Playing to AirPlay' : 'AirPlay');
      btn.setAttribute('title', isWireless ? 'Playing to AirPlay' : 'AirPlay');
    }
    audio.addEventListener('webkitplaybacktargetavailabilitychanged', function (e) {
      btn.disabled = e.availability === 'not_available';
    });
    audio.addEventListener('webkitcurrentplaybacktargetiswirelesschanged', function (e) {
      updateAirPlayLabel(!!e.isWireless);
    });
  })();

  window.addEventListener('beforeunload', () => persistCurrentPlaybackState({ forceNostrSync: true }));
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') persistCurrentPlaybackState({ forceNostrSync: true });
  });

  async function restorePlaybackFromState() {
    const rawSaved = loadPlaybackState();
    if (rawSaved && rawSaved.type === 'radio') {
      const idx = radioStations.findIndex((station) => station.id === rawSaved.stationId);
      if (idx >= 0) playRadioIndex(idx, { autoplay: false });
      return;
    }
    const localSaved = normalizeProgressState(rawSaved, 'local', null);
    const activeSection = $('inSection').value || cfg().sectionKey || '';
    const localAllowed = localSaved && (!localSaved.sectionKey || !activeSection || String(localSaved.sectionKey) === String(activeSection))
      ? localSaved
      : null;
    const localBookId = localAllowed ? String(localAllowed.audiobookBookId || computeAudiobookBookId(localAllowed)) : '';
    let remoteSaved = null;
    if (nostrState.ready && nostrState.pubkey && nostrState.secretKey) {
      remoteSaved = await pickLatestNostrAudiobookProgress(localBookId, activeSection).catch(() => null);
      if (!remoteSaved && !localAllowed) {
        remoteSaved = await pickLatestNostrAudiobookProgress('', activeSection).catch(() => null);
      }
    }
    const saved = (() => {
      if (!localAllowed) return remoteSaved;
      if (!remoteSaved) return localAllowed;
      const localUpdated = Number(localAllowed.updatedAt || localAllowed.savedAt || 0);
      const remoteUpdated = Number(remoteSaved.updatedAt || remoteSaved.savedAt || 0);
      return remoteUpdated > localUpdated ? remoteSaved : localAllowed;
    })();
    if (!saved || !saved.albumRatingKey) return;
    const album = {
      ratingKey: saved.albumRatingKey,
      title: saved.albumTitle || '[Album]',
      parentTitle: saved.albumParentTitle || ''
    };
    await openAlbum(album);
    let index = saved.trackRatingKey
      ? currentTracks.findIndex((t) => String(t.ratingKey || '') === String(saved.trackRatingKey))
      : Number(saved.trackIndex);
    if (index < 0 || index >= currentTracks.length) return;
    playIndex(index, {
      autoplay: false,
      seekTime: Number(saved.currentTime) || 0
    });
  }

  bindClick('btnSettings', () => {
    openSettingsModal();
    const c = cfg();
    if (c.server && c.token) {
      loadSections().catch(() => {
        $('inSection').value = '';
        const plNav = $('plexLibraryLinks');
        if (plNav) plNav.innerHTML = '<p class="sidebar-plex-note error">Could not load libraries.</p>';
        setSettingsStatus('Could not load libraries. Check token and CORS.', 'error');
      });
    }
  });
  bindClick('btnCloseSettings', closeSettingsModal);
  bindEvent('npArt', 'click', openArtworkModal);
  const artworkModalEl = $('artworkModal');
  if (artworkModalEl) {
    artworkModalEl.onclick = (e) => {
      if (e.target.id === 'artworkModal') closeArtworkModal();
    };
  }
  const settingsModalEl = $('settingsModal');
  if (settingsModalEl) {
    settingsModalEl.onclick = (e) => {
      if (e.target.id === 'settingsModal') closeSettingsModal();
    };
  }
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      if ($('artworkModal').classList.contains('open')) {
        closeArtworkModal();
        e.preventDefault();
        return;
      }
      if ($('settingsModal').classList.contains('open')) {
        closeSettingsModal();
        e.preventDefault();
        return;
      }
    }
    if ((e.metaKey || e.ctrlKey) && e.key === '/') {
      e.preventDefault();
      $('inSearch').focus();
      $('inSearch').select();
      return;
    }
    if ($('settingsModal').classList.contains('open') || $('artworkModal').classList.contains('open')) return;

    const activeElement = document.activeElement;
    const isInputField = activeElement && (
      activeElement.tagName === 'INPUT' ||
      activeElement.tagName === 'TEXTAREA' ||
      activeElement.tagName === 'SELECT' ||
      activeElement.isContentEditable
    );
    if (isInputField) return;

    if ((e.metaKey || e.ctrlKey) && e.key === '.') {
      e.preventDefault();
      openSettingsModal();
      return;
    }

    const audio = $('audio');
    if (e.code === 'Space') {
      e.preventDefault();
      $('btnPlayPause').click();
      return;
    }
    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      $('btnPrev').click();
      return;
    }
    if (e.key === 'ArrowRight') {
      e.preventDefault();
      if (playbackMode === PLAYBACK_MODE_ALBUM_SHUFFLE && !isRadioMode) {
        $('btnNextAlbum').click();
      } else {
        $('btnNext').click();
      }
      return;
    }
    if (e.key === 'ArrowUp' && audio) {
      e.preventDefault();
      let v = typeof audio._targetVolume === 'number' ? audio._targetVolume : (Number(audio.volume) || 1);
      v = Math.min(1, v + 0.1);
      audio.volume = v;
      audio._targetVolume = v;
      return;
    }
    if (e.key === 'ArrowDown' && audio) {
      e.preventDefault();
      let v = typeof audio._targetVolume === 'number' ? audio._targetVolume : (Number(audio.volume) || 1);
      v = Math.max(0, v - 0.1);
      audio.volume = v;
      audio._targetVolume = v;
    }
  });

  bindClick('btnSave', async () => {
    const token = $('inToken').value.trim();
    if (!token) {
      setSettingsStatus('Enter token before saving.', 'error');
      return;
    }
    localStorage.setItem(LS.token, token);
    try {
      await autoDetectServersFromToken();
    } catch (e) {
      setSettingsStatus(
        'Could not detect Plex servers from token: ' + ((e && e.message) || String(e)),
        'error'
      );
      return;
    }
    if (!cfg().server) {
      setSettingsStatus('No Plex servers found for this token.', 'error');
      return;
    }
    updatePlexSettingsButtonsVisibility();
    if (!$('inSection').value) {
      try {
        await loadSections();
      } catch (_) {
        setSettingsStatus('Could not load libraries. Check token and CORS.', 'error');
      }
    }
    setSettingsStatus('Settings saved. Choose a Plex library in the sidebar.', 'ok');
    closeSettingsModal();
    if ($('inSection').value) {
      localStorage.setItem(LS.section, $('inSection').value);
      await loadAlbums(false);
    }
  });

  bindClick('viewModeToggleBtn', () => {
    setAlbumViewMode(getAlbumViewMode() === 'list' ? 'grid' : 'list');
  });
  bindClick('logoHome', (e) => {
    e.preventDefault();
    const connected = !!(cfg().server && cfg().token);
    if (!connected) {
      if (location.hash !== '#radio') location.hash = '#radio';
      return;
    }
    if (location.hash !== '#albums') location.hash = '#albums';
    else backToAlbums({ fullLibrary: true });
  });
  bindClick('btnPlexLogin', async () => {
    showError('');
    try {
      await plexSignInGetToken();
      const token = $('inToken').value.trim();
      if (token) {
        localStorage.setItem(LS.token, token);
        await autoDetectServersFromToken();
        if (cfg().server) await loadSections();
      }
    } catch (e) {
      $('plexLoginStatus').textContent = '';
      showError(e.message || String(e));
    }
  });
  bindClick('btnDeletePlexSettings', () => {
    const ok = window.confirm('Remove saved Plex server, token, and library settings?');
    if (!ok) return;
    disconnectPlexSession();
  });

  // Resume Plex login when returning from same-tab auth (e.g. Safari iPhone)
  try {
    const pendingPinId = sessionStorage.getItem('plexLoginPinId');
    const expiry = sessionStorage.getItem('plexLoginPinExpiry');
    if (pendingPinId && expiry && Date.now() < parseInt(expiry, 10)) {
      openSettingsModal();
      resumePlexPinPoll(pendingPinId).then(() => {
        const token = $('inToken').value.trim();
        if (token) {
          localStorage.setItem(LS.token, token);
          autoDetectServersFromToken().then(() => {
            if (cfg().server) loadSections().catch(() => {});
          }).catch(() => {});
        }
      });
    }
  } catch (_) {}

  bindEvent('inToken', 'input', () => {
    updatePlexLoginButtonVisibility();
    const token = $('inToken').value.trim();
    if (!token) {
      renderServerCandidates([]);
      setServerDiscoveryStatus('');
    }
  });
  bindEvent('inToken', 'change', async () => {
    showError('');
    try {
      await autoDetectServersFromToken();
    } catch (e) {
      renderServerCandidates([]);
      setServerDiscoveryStatus(e.message || String(e), 'error');
    }
  });
  bindEvent('inSearch', 'input', () => {
    syncSearchUi();
    if (filterDebounceTimer) clearTimeout(filterDebounceTimer);
    filterDebounceTimer = setTimeout(updateFilterState, 200);
  });
  bindClick('btnClearSearch', () => {
    clearSearchInput({ focus: true });
  });
  document.querySelectorAll('.sidebar-nav-btn[data-nav]').forEach((btn) => {
    btn.addEventListener('click', () => {
      clearSearchInput();
      const next = btn.dataset.nav;
      const nextHash = '#' + next;
      if (location.hash !== nextHash) location.hash = nextHash;
      else setNavView(next, { updateLocation: false });
    });
  });
  bindEvent('mobileLibrarySelect', 'change', async (e) => {
    const key = String((e.target && e.target.value) || '');
    if (!key) return;
    clearSearchInput();
    await handlePlexSectionPick(key);
  });
  window.addEventListener('scroll', () => {
    if (albumsViewVisible()) scheduleAlbumAutoLoad(30);
  }, { passive: true });
  window.addEventListener('resize', () => {
    if (albumsViewVisible()) scheduleAlbumAutoLoad(50);
    if (window.innerWidth > 720) setChatVisibility(true);
    else setChatVisibility(isChatVisibleMobile);
  });
  window.addEventListener('hashchange', applyHashRoute);
  window.addEventListener('message', (event) => {
    try {
      if (!event || !event.data || typeof event.data !== 'object') return;
      if (event.data.type === 'chatReady') {
        sendChatContext();
        syncNostrNsecToChatIframe();
      } else if (event.data.type === 'openSettingsModal') {
        openSettingsModal({ focusNostr: event.data.tab === 'nostr' });
      } else if (event.data.type === 'chatSearchQuery') {
        const q = normalizeChatSearchQuery(String(event.data.query || '').trim());
        if (!q) return;
        setNavView('albums', { updateLocation: true });
        const inp = $('inSearch');
        if (inp) {
          inp.value = q;
          inp.dispatchEvent(new Event('input', { bubbles: true }));
        } else {
          updateFilterState();
        }
        if (inp) {
          inp.focus();
          try {
            inp.select();
          } catch (_) {}
        }
      } else if (event.data.type === 'tuneToChannel' && event.data.channel) {
        const channelId = String(event.data.channel || '').replace(/_/g, ' ').trim();
        if (!channelId) return;
        setNavView('radio', { updateLocation: true });
        const inp = $('inSearch');
        if (inp) {
          inp.value = channelId;
          inp.dispatchEvent(new Event('input', { bubbles: true }));
        } else {
          updateFilterState();
        }
      }
    } catch (_) {}
  });
  bindEvent('btnShuffleMode', 'click', cyclePlaybackMode);
  bindEvent('btnChatToggle', 'click', () => {
    setChatVisibility(!isChatVisibleMobile);
  });
  bindClick('btnToggleNsecMask', () => {
    const nsecInput = $('inNsec');
    if (!nsecInput) return;
    const isMasked = nsecInput.type === 'password';
    nsecInput.type = isMasked ? 'text' : 'password';
    const btn = $('btnToggleNsecMask');
    if (btn) {
      btn.textContent = isMasked ? '**' : 'Aa';
      btn.title = isMasked ? 'Hide nsec' : 'Show nsec';
      btn.setAttribute('aria-label', isMasked ? 'Hide nsec' : 'Show nsec');
    }
  });
  bindClick('btnSaveNostr', async () => {
    const ok = await initNostrFromNsec($('inNsec').value.trim());
    if (ok) {
      setSettingsStatus('Nostr key saved.', 'ok');
      closeSettingsModal();
    }
  });
  bindClick('btnGenerateNostr', async () => {
    const existing = String(localStorage.getItem(LS.nostrNsec) || '').trim();
    const typed = String(($('inNsec') && $('inNsec').value) || '').trim();
    if (existing || typed) {
      setNostrStatus('Remove existing nsec before generating a new one.', 'error');
      updateNostrSaveButtonVisibility();
      return;
    }
    try {
      const tools = await getNostrTools();
      let secretKey = null;
      if (typeof tools.generateSecretKey === 'function') {
        secretKey = tools.generateSecretKey();
      } else if (typeof tools.generatePrivateKey === 'function') {
        secretKey = tools.generatePrivateKey();
      } else {
        const bytes = new Uint8Array(32);
        crypto.getRandomValues(bytes);
        secretKey = bytes;
      }
      if (!tools.nip19 || typeof tools.nip19.nsecEncode !== 'function') {
        throw new Error('nostr-tools nsec encoder unavailable');
      }
      const nsec = tools.nip19.nsecEncode(secretKey);
      $('inNsec').value = nsec;
      updateNostrSaveButtonVisibility();
      const ok = await initNostrFromNsec(nsec);
      if (ok) setSettingsStatus('Generated and saved new Nostr key.', 'ok');
    } catch (err) {
      setNostrStatus('Could not generate Nostr key: ' + (err && err.message ? err.message : String(err)), 'error');
    }
  });
  bindEvent('inNsec', 'input', () => {
    updateNostrSaveButtonVisibility();
  });
  bindEvent('inNsec', 'keydown', (e) => {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    const btn = $('btnSaveNostr');
    if (btn && btn.style.display !== 'none' && !btn.disabled) btn.click();
  });
  bindClick('btnClearNostr', () => {
    const confirmed = window.confirm('Remove saved nsec from this browser?');
    if (!confirmed) return;
    localStorage.removeItem(LS.nostrNsec);
    localStorage.removeItem(LS.nostrNpub);
    localStorage.removeItem(LS.nostrActivity);
    nostrState = { ready: false, nsec: '', npub: '', pubkey: '', secretKey: null, pool: null, lastPostKey: '', lastPostAt: 0 };
    $('inNsec').value = '';
    updateNostrIdentityUi();
    setNostrStatus('Nostr key removed.', '');
    syncNostrNsecToChatIframe();
  });

  (function init() {
    updateShortcutModifierLabels();
    buildRadioData();
    wireUnifiedSearchPanelOnce();
    setChatVisibility(window.innerWidth > 720);
    $('inNsec').value = localStorage.getItem(LS.nostrNsec) || '';
    updateNostrIdentityUi();
    if ($('inNsec').value) initNostrFromNsec($('inNsec').value).catch(() => {});
    playbackMode = getPlaybackMode();
    applyPlaybackModeUI();
    updateNowPlayingFavButton('');
    updatePlayPauseButton();
    setupMediaSession();
    const c = cfg();
    $('inToken').value = c.token;
    probeSameOriginBackend().catch(() => {});
    const initialHash = (location.hash || '').trim();
    if (initialHash === '#favorites' || initialHash === '#recent') applyHashRoute();
    if (c.server && c.token) {
      loadSections().then(() => {
        const hash = (location.hash || '').trim();
        const isViewRoute =
          /^#(albums|youtube|playlists|favorites|recent)$/.test(hash) || isRadioRouteHash(hash) || isYouTubeRouteHash(hash);
        const isAlbumRoute = /^#album\/[^/]+$/.test(hash);
        const isArtistRoute = /^#artist\/[^/]+$/.test(hash);
        const isDirectoryRoute = isDirectoryRouteHash(hash);
        if (isViewRoute || isAlbumRoute || isArtistRoute || isDirectoryRoute) {
          const saved = loadPlaybackState();
          if (isRadioRouteHash(hash) && saved?.type === 'radio' && hash === radioHash(saved.stationId)) {
            restorePlaybackFromState();
          } else if (isAlbumRoute && saved?.albumRatingKey && hash === albumHash(saved.albumRatingKey)) {
            restorePlaybackFromState().catch(() => applyHashRoute());
          } else {
            applyHashRoute();
          }
        } else if (!$('inSection').value) {
          if (!isRadioRouteHash(location.hash)) location.hash = '#albums';
          applyHashRoute();
        } else {
          loadAlbums(false)
            .then(() => restorePlaybackFromState())
            .catch(() => {});
        }
      }).catch(() => {
        setSettingsStatus('Could not load libraries. Open Settings to check connection.', 'error');
        if (location.hash === '#favorites' || location.hash === '#recent') return;
        if (!isRadioRouteHash(location.hash)) location.hash = '#radio';
        applyHashRoute();
      });
    } else {
      const h = (location.hash || '').trim();
      if (h === '#albums') location.hash = '#radio';
      const saved = loadPlaybackState();
      if (saved?.type === 'radio' && location.hash === radioHash(saved.stationId)) {
        restorePlaybackFromState();
      } else {
        applyHashRoute();
      }
    }
    updateFilterState();
  })();
})();
  
