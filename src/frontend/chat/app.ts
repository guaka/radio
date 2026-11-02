    /**
     * Radio Guaka Chat - Nostr-based chat component
     * 
     * AUTHENTICATION FLOW:
     * 1. Chat loads immediately in read-only mode (anyone can view messages)
     * 2. When user tries to post (focuses input or presses Enter):
     *    - If no local key is authenticated yet, show modal to generate/import key
     *    - Import key (paste nsec) or generate anonymous keypair
     * 3. Keys are saved to localStorage for future sessions
     * 
     * This flow prioritizes:
     * - Immediate access to read chat
     * - Simple local-key auth for posting
     */
    function redactNsecForLog(value) {
      if (value == null || value === '') return '(none)';
      const s = String(value).trim();
      if (!s.startsWith('nsec')) return '(non-nsec, ' + s.length + ' chars)';
      return '(nsec, ' + s.length + ' chars)';
    }

    function chatApp() {
      return {
        // State
        ndk: null,
        messages: [],
        messageInput: '',
        isAuthenticated: false,
        currentUserPubkey: null,
        currentUserNpub: null,
        currentUserPrivateKey: null,
        currentUserNsec: null,
        currentUserNip05: null, // NIP-05 identifier (e.g. user@domain.com)
        currentChannel: '',
        relays: [],
        relayStatus: {},
        userProfiles: {},
        reactions: {},
        isLoading: true,
        isSending: false,
        showAuthModal: false,
        showManualAuth: false,
        showPrivateKey: false,
        manualKey: '',
        errorMessage: '',
        isInIframe: window.parent !== window,
        /** pleXtr embed: no chat header / no in-chat Nostr UI; parent supplies nsec */
        minimalEmbed: false,
        _pendingPlextrNsec: null,
        /** Subscribe / display notes with any of these #t values (Radio Guaka legacy + pleXtr) */
        chatRoomTags: Object.freeze(['plextr', 'radioguaka']),
        
        // Ensure messages is always an array
        ensureMessagesArray() {
          if (!Array.isArray(this.messages)) {
            this.messages = [];
          }
        },

        scrollMessagesToBottom() {
          this.$nextTick(() => {
            const container = this.$refs.messagesContainer;
            if (container) container.scrollTop = container.scrollHeight;
          });
        },

        normalizeRelayUrl(url) {
          return String(url || '').replace(/\/$/, '');
        },

        relayUrlFromInput(relayUrlOrObject) {
          if (typeof relayUrlOrObject === 'string') return relayUrlOrObject;
          if (relayUrlOrObject && relayUrlOrObject.url) return relayUrlOrObject.url;
          if (relayUrlOrObject && typeof relayUrlOrObject.toString === 'function') return relayUrlOrObject.toString();
          return '';
        },

        isConfiguredRelay(relayUrlOrObject) {
          const relayUrl = this.relayUrlFromInput(relayUrlOrObject);
          if (!relayUrl) return false;
          const normalizedUrl = this.normalizeRelayUrl(relayUrl);
          const normalizedRelays = this.relays.map((r) => this.normalizeRelayUrl(r));
          return normalizedRelays.includes(normalizedUrl);
        },

        relayStatusFromValue(status) {
          if (status === 'connected' || status === 1 || status === WebSocket.OPEN) return 'connected';
          if (status === 'connecting' || status === 0 || status === WebSocket.CONNECTING) return 'connecting';
          return 'disconnected';
        },

        setRelayStatusFor(relayUrl, status) {
          if (!relayUrl || !this.relays.includes(relayUrl)) return;
          this.relayStatus[relayUrl] = { status };
        },

        findRelayInPool(relayUrl) {
          if (!this.ndk || !this.ndk.pool || !this.ndk.pool.relays) return null;
          if (this.ndk.pool.relays.get) {
            const direct = this.ndk.pool.relays.get(relayUrl);
            if (direct) return direct;
          }
          for (const [url, relay] of this.ndk.pool.relays) {
            if (url === relayUrl || relay?.url === relayUrl) return relay;
          }
          return null;
        },
        
        hasRoomTag(tags) {
          const set = new Set(this.chatRoomTags);
          return (tags || []).some((tag) => tag && tag[0] === 't' && set.has(tag[1]));
        },
        
        /** Other tabs wrote radio_guaka_chat_messages — merge into this tab */
        mergeChatFromStorageJson(jsonStr) {
          try {
            const incoming = JSON.parse(jsonStr);
            if (!Array.isArray(incoming)) return;
            this.ensureMessagesArray();
            const byId = new Map();
            this.messages.forEach((m) => {
              if (m && m.id) byId.set(m.id, m);
            });
            incoming.forEach((m) => {
              if (m && m.id) byId.set(m.id, m);
            });
            this.messages = Array.from(byId.values()).sort(
              (a, b) => (Number(a.created_at) || 0) - (Number(b.created_at) || 0)
            );
            this.deduplicateMessages();
            this.messages = [...this.messages];
            this.scrollMessagesToBottom();
          } catch (e) {
            console.warn('mergeChatFromStorageJson:', e);
          }
        },
        
        // Deduplicate messages array in place
        deduplicateMessages() {
          this.ensureMessagesArray();
          const seen = new Set();
          const deduplicated = [];
          for (const msg of this.messages) {
            if (msg && msg.id && !seen.has(msg.id)) {
              seen.add(msg.id);
              deduplicated.push(msg);
            } else if (msg && !msg.id) {
              // Keep messages without IDs (shouldn't happen, but be safe)
              deduplicated.push(msg);
            }
          }
          this.messages = deduplicated;
        },
        
        // Get deduplicated messages array (computed property for template)
        get safeMessages() {
          try {
            this.ensureMessagesArray();
            // Deduplicate by ID, keeping the first occurrence
            const seen = new Set();
            const deduplicated = [];
            for (const msg of this.messages) {
              if (msg && msg.id && !seen.has(msg.id)) {
                seen.add(msg.id);
                deduplicated.push(msg);
              }
            }
            return deduplicated;
          } catch (e) {
            console.error('Error in safeMessages getter:', e);
            return [];
          }
        },
        
        // Get unique key for message (for Alpine.js x-for)
        getMessageKey(message, index) {
          if (message && message.id) {
            return String(message.id);
          }
          return 'msg-' + String(index);
        },
        
        // Initialize
        async init() {
          try {
            // Ensure messages is initialized as array
            this.ensureMessagesArray();
            
            // Load cached messages immediately for instant UI
            this.loadCachedMessages();
            
            const cacheKey = 'radio_guaka_chat_messages';
            window.addEventListener('storage', (e) => {
              if (e.key !== cacheKey || e.newValue == null) return;
              this.mergeChatFromStorageJson(e.newValue);
            });
            document.addEventListener('visibilitychange', () => {
              if (document.visibilityState === 'visible' && this.ndk) {
                this.fetchPastMessages();
              }
            });
            window.addEventListener('pageshow', (e) => {
              if (e.persisted && this.ndk) {
                this.fetchPastMessages();
              }
            });
            
            // Parse URL parameters
            const params = new URLSearchParams(window.location.search);
            const hashtagParam = params.get('hashtag') || 'plextr';
            this.minimalEmbed = params.get('minimal') === '1' || params.get('embed') === 'plextr';
            if (this.minimalEmbed) this.showAuthModal = false;
            
            // Set up relays - using nomadwiki and trustroots community relays
            // IMPORTANT: We only use these 2 relays, never use relays from extension or user profile
            this.relays = [
              'wss://relay.nomadwiki.org',
              'wss://relay.trustroots.org'
            ];
            
            // Initialize relay status (only for the 2 relays)
            this.relayStatus = {};
            this.relays.forEach(relay => {
              this.relayStatus[relay] = { status: 'connecting' };
            });
            
            // Set up postMessage listener for parent communication
            if (this.isInIframe) {
              window.addEventListener('message', (event) => {
                try {
                  if (event.data.type === 'channelChange') {
                    this.currentChannel = event.data.channel || '';
                  } else if (event.data.type === 'addSongToMessage' && event.data.song) {
                    // Add song to message input (only if not already present)
                    const songText = event.data.song;
                    // Check if the song text is already in the input
                    if (!this.messageInput.includes(songText)) {
                      const separator = this.messageInput.trim() ? ' ' : '';
                      this.messageInput = this.messageInput + separator + songText;
                    }
                    // Focus the input
                    this.$nextTick(() => {
                      const input = this.$refs.chatInput;
                      if (input) {
                        input.focus();
                      }
                    });
                  } else if (event.data.type === 'likeSong' && event.data.song) {
                    // Immediately post the song with a like icon
                    const songText = event.data.song;
                    if (songText) {
                      // Set the message input with song + like icon
                      this.messageInput = `👍 ${songText}`;
                      // Immediately send the message (will handle auth if needed)
                      this.sendMessage();
                    }
                  } else if (event.data.type === 'setChatNsec' && typeof event.data.nsec === 'string') {
                    const ns = event.data.nsec.trim();
                    if (ns.startsWith('nsec')) {
                      this.manualKey = ns;
                      this.authenticateWithKey().catch(() => {});
                    }
                  } else if (event.data.type === 'clearChatNsec') {
                    this.deleteKeys();
                  }
                } catch (e) {
                  console.error('Error handling postMessage:', e);
                }
              });
              
              // Notify parent that chat is ready
              window.parent.postMessage({ type: 'chatReady' }, '*');
            }
            
            /*
             * AUTH FLOW:
             * 1. Chat is shown immediately in read-only mode
             * 2. When user tries to post (focus input or send):
             *    - If no local nsec key is authenticated, show modal to generate/import key
             * 3. Keys are saved to localStorage for future sessions
             */
            
            // Only load saved keys from storage (don't prompt or auto-auth)
            // User will be prompted when they try to post
            this.loadKeysFromStorage();
            
            // Wait for NDK to load
            if (window.NDKModule) {
              await this.initializeNDK();
            } else {
              window.addEventListener('ndkReady', () => {
                this.initializeNDK();
              });
              
              // Timeout if NDK doesn't load
              setTimeout(() => {
                if (!this.ndk) {
                  this.errorMessage = 'Failed to load NDK library. Chat unavailable.';
                  this.isLoading = false;
                }
              }, 5000);
            }
            
            // Focus the input field after initialization
            this.$nextTick(() => {
              setTimeout(() => {
                const input = this.$refs.chatInput;
                if (input) {
                  input.focus();
                }
              }, 500);
            });
          } catch (error) {
            console.error('Init error:', error);
            this.errorMessage = 'Failed to initialize chat: ' + error.message;
            this.isLoading = false;
          }
        },
        
        // Initialize NDK
        async initializeNDK() {
          try {
            if (!window.NDKModule) {
              throw new Error('NDK not available');
            }
            
            const NDKClass = window.NDKModule.NDK;
            
            if (typeof NDKClass !== 'function') {
              throw new Error('NDK class is not a constructor');
            }
            
            // Ensure relays are set
            if (!this.relays || this.relays.length === 0) {
              throw new Error('No relays configured');
            }
            
            // Try different initialization approaches for NDK v2.0.0
            // IMPORTANT: Only use our 2 configured relays, ignore any from extension/user
            try {
              // First try with explicitRelayUrls (this ensures only these relays are used)
              this.ndk = new NDKClass({
                explicitRelayUrls: this.relays
              });
            } catch (e) {
              // Try with 'relays' option name
              this.ndk = new NDKClass({
                relays: this.relays
              });
            }
            
            // Manually add relays if needed (for v2.0.0 compatibility)
            // Only add our configured relays, never add relays from extension
            if (this.ndk && this.ndk.addExplicitRelay) {
              this.relays.forEach(relayUrl => {
                try {
                  this.ndk.addExplicitRelay(relayUrl);
                } catch (e) {
                  // Could not add relay
                }
              });
            }
            
            await this.ndk.connect();
            
            // Intercept pool.addRelay to prevent unwanted relays from being added
            if (this.ndk.pool && this.ndk.pool.addRelay) {
              const originalAddRelay = this.ndk.pool.addRelay.bind(this.ndk.pool);
              this.ndk.pool.addRelay = async (relayUrlOrObject) => {
                if (this.isConfiguredRelay(relayUrlOrObject)) {
                  return await originalAddRelay(relayUrlOrObject);
                } else {
                  // Block any relay not in our configured list
                  // Silently return undefined - don't throw or reject
                  return undefined;
                }
              };
            }
            
            // Also intercept addExplicitRelay if it exists
            if (this.ndk.addExplicitRelay) {
              const originalAddExplicitRelay = this.ndk.addExplicitRelay.bind(this.ndk);
              this.ndk.addExplicitRelay = (relayUrlOrObject) => {
                if (this.isConfiguredRelay(relayUrlOrObject)) {
                  return originalAddExplicitRelay(relayUrlOrObject);
                } else {
                  // Silently ignore
                  return undefined;
                }
              };
            }
            
            // Ensure relays are properly set in the pool
            // Only use our 2 configured relays, ignore any others
            if (this.ndk.pool && (!this.ndk.pool.relays || this.ndk.pool.size === 0)) {
              // Manually add relays to pool if they're not there
              // Only add our configured relays
              for (const relayUrl of this.relays) {
                try {
                  if (this.ndk.pool.addRelay) {
                    await this.ndk.pool.addRelay(relayUrl);
                  } else if (this.ndk.addExplicitRelay) {
                    this.ndk.addExplicitRelay(relayUrl);
                  }
                } catch (e) {
                  // Could not add relay to pool
                }
              }
            }
            
            // Ensure we're not using any relays from extension or user profile
            // Remove any relays that aren't in our configured list
            this.cleanupRelays();
            
            // Set up a periodic cleanup to ensure no extension relays are added
            setInterval(() => {
              this.cleanupRelays();
            }, 2000); // Check every 2 seconds (more aggressive)
            
            // Set up relay status tracking (only for the 2 configured relays)
            if (this.ndk.pool) {
              this.ndk.pool.on('relay:connect', (relay) => {
                // Only track status for our configured relays
                const relayUrl = relay.url || relay;
                this.setRelayStatusFor(relayUrl, 'connected');
              });
              
              this.ndk.pool.on('relay:disconnect', (relay) => {
                // Only track status for our configured relays
                const relayUrl = relay.url || relay;
                this.setRelayStatusFor(relayUrl, 'disconnected');
              });
              
              // Also listen for 'connect' and 'disconnect' without 'relay:' prefix (NDK v2.x)
              this.ndk.pool.on('connect', (relay) => {
                const relayUrl = relay?.url || relay;
                this.setRelayStatusFor(relayUrl, 'connected');
              });
              
              this.ndk.pool.on('disconnect', (relay) => {
                const relayUrl = relay?.url || relay;
                this.setRelayStatusFor(relayUrl, 'disconnected');
              });
            }
            
            // Wait a bit for relays to be fully connected before subscribing
            await new Promise(resolve => setTimeout(resolve, 1500));
            
            // Check actual relay connection status after waiting
            this.updateRelayStatus();
            
            // Start subscriptions
            this.startSubscriptions();
            
            // Periodically update relay status (every 30 seconds as fallback)
            setInterval(() => this.updateRelayStatus(), 30000);
            
            // Also try a direct fetch as fallback after a delay
            setTimeout(() => this.fetchPastMessages(), 3000);
            
            if (this.minimalEmbed && this._pendingPlextrNsec) {
              this.manualKey = this._pendingPlextrNsec;
              this._pendingPlextrNsec = null;
              await this.authenticateWithKey();
            } else if (this.currentUserPrivateKey) {
              await this.setupSigner();
            }
            
            // Recompute npub if we have a pubkey but npub wasn't set (NDK might not have been ready before)
            if (this.currentUserPubkey && !this.currentUserNpub) {
              this.setUserPubkey(this.currentUserPubkey);
            }
            
          } catch (error) {
            console.error('NDK init error:', error);
            this.errorMessage = 'Failed to connect to relays: ' + error.message;
            this.isLoading = false;
          }
        },
        
        // Start subscriptions
        startSubscriptions() {
          try {
            if (!this.ndk) return;
            
            // One subscription per room tag (OR semantics vary by relay; this is reliable)
            this.chatRoomTags.forEach((roomTag) => {
              const messageFilter = {
                kinds: [1],
                '#t': [roomTag],
                limit: 100
              };
              
              const subscription = this.ndk.subscribe(messageFilter, { closeOnEose: false });
              
              subscription.on('event', (event) => {
                try {
                  this.handleMessageEvent(event);
                  this.markRelaysConnected();
                } catch (e) {
                  console.error('Error handling message event:', e);
                }
              });
              
              subscription.on('eose', () => {
                this.isLoading = false;
                this.markRelaysConnected();
              });
              
              (async () => {
                try {
                  if (subscription[Symbol.asyncIterator]) {
                    for await (const event of subscription) {
                      try {
                        this.handleMessageEvent(event);
                      } catch (e) {
                        console.error('Error handling async message event:', e);
                      }
                    }
                  }
                } catch (e) {
                  // no async iterator
                }
              })();
            });
            
            // Subscribe to profiles (kind 0) for usernames of message authors
            const profileSubscription = this.ndk.subscribe(
              { kinds: [0] },
              { closeOnEose: true }
            );
            
            profileSubscription.on('event', (event) => {
              try {
                this.handleProfileEvent(event);
              } catch (e) {
                console.error('Error handling profile event:', e);
              }
            });
            
            // Subscribe to reactions (kind 7)
            const reactionSubscription = this.ndk.subscribe(
              { kinds: [7] },
              { closeOnEose: false }
            );
            
            reactionSubscription.on('event', (event) => {
              try {
                this.handleReactionEvent(event);
              } catch (e) {
                console.error('Error handling reaction event:', e);
              }
            });
            
            // Subscribe to deletions (kind 5)
            const deletionSubscription = this.ndk.subscribe(
              { kinds: [5] },
              { closeOnEose: false }
            );
            
            deletionSubscription.on('event', (event) => {
              try {
                this.handleDeletionEvent(event);
              } catch (e) {
                console.error('Error handling deletion event:', e);
              }
            });
            
          } catch (error) {
            console.error('Subscription error:', error);
            this.errorMessage = 'Failed to subscribe to messages: ' + error.message;
            this.isLoading = false;
          }
        },
        
        // Handle message events
        handleMessageEvent(event) {
          // Ensure messages is an array
          this.ensureMessagesArray();
          
          // Validate event has required fields
          if (!event || !event.id) {
            console.warn('Invalid message event: missing id', event);
            return;
          }
          
          // Check if message already exists (including optimistic ones)
          const existingIndex = this.messages.findIndex(m => m && m.id === event.id);
          if (existingIndex !== -1) {
            // Update existing optimistic message with real data from relay
            if (this.messages[existingIndex].optimistic) {
              this.messages[existingIndex] = {
                id: event.id,
                pubkey: event.pubkey,
                content: event.content,
                created_at: event.created_at,
                tags: event.tags
              };
            }
            return;
          }
          
          // #plextr (pleXtr) or #radioguaka (legacy Radio Guaka)
          const tags = event.tags || [];
          if (!this.hasRoomTag(tags)) return;
          
          const message = {
            id: event.id,
            pubkey: event.pubkey,
            content: event.content,
            created_at: event.created_at,
            tags: event.tags
          };
          
          // Insert sorted by timestamp
          const insertIndex = this.messages.findIndex(m => m && m.created_at > event.created_at);
          if (insertIndex === -1) {
            this.messages.push(message);
          } else {
            this.messages.splice(insertIndex, 0, message);
          }
          
          // Keep only last 200 messages in memory
          if (this.messages.length > 200) {
            this.messages = this.messages.slice(-200);
          }
          
          // Deduplicate before saving
          this.deduplicateMessages();
          
          // Save to localStorage cache
          this.saveCachedMessages();
          
          // Auto-scroll to bottom
          this.scrollMessagesToBottom();
        },
        
        // Handle profile events
        handleProfileEvent(event) {
          try {
            const profile = JSON.parse(event.content);
            this.userProfiles[event.pubkey] = {
              name: profile.name || profile.display_name || 'Anonymous',
              picture: profile.picture || '',
              nip05: profile.nip05 || null
            };
            
            // If this is the current user's profile, update their NIP-05
            if (this.currentUserPubkey && event.pubkey === this.currentUserPubkey) {
              this.currentUserNip05 = profile.nip05 || null;
            }
          } catch (e) {
            console.error('Error parsing profile:', e);
          }
        },
        
        // Handle reaction events
        handleReactionEvent(event) {
          try {
            const tags = event.tags || [];
            const eventTag = tags.find(tag => tag[0] === 'e');
            if (!eventTag) return;
            
            const eventId = eventTag[1];
            const content = event.content;
            
            if (!this.reactions[eventId]) {
              this.reactions[eventId] = {};
            }
            
            if (!this.reactions[eventId][content]) {
              this.reactions[eventId][content] = [];
            }
            
            if (!this.reactions[eventId][content].includes(event.pubkey)) {
              this.reactions[eventId][content].push(event.pubkey);
            }
          } catch (e) {
            console.error('Error handling reaction:', e);
          }
        },
        
        // Handle deletion events (kind 5)
        handleDeletionEvent(event) {
          try {
            // Ensure messages is an array
            this.ensureMessagesArray();
            
            const tags = event.tags || [];
            // Get all 'e' tags (deleted event IDs)
            const deletedIds = tags
              .filter(tag => tag[0] === 'e')
              .map(tag => tag[1]);
            
            if (deletedIds.length === 0) return;
            
            // Only delete messages that were authored by the deletion event's author
            const originalCount = this.messages.length;
            this.messages = this.messages.filter(m => {
              if (m && deletedIds.includes(m.id)) {
                // Verify the deletion is from the message author
                return m.pubkey !== event.pubkey;
              }
              return true;
            });
            
            // Update cache if messages were removed
            if (this.messages.length < originalCount) {
              this.saveCachedMessages();
            }
          } catch (e) {
            console.error('Error handling deletion:', e);
          }
        },
        
        // Get username
        getUsername(pubkey) {
          if (this.userProfiles[pubkey]) {
            return this.userProfiles[pubkey].name;
          }
          // Convert hex pubkey to npub format and abbreviate
          const npub = this.hexToNpub(pubkey);
          return npub.substring(0, 12) + '...';
        },
        
        // Check if message is from the same user as previous message
        isSameUserAsPrevious(index) {
          this.ensureMessagesArray();
          if (index === 0 || !Array.isArray(this.messages)) return false;
          if (index >= this.messages.length || index - 1 < 0) return false;
          const current = this.messages[index];
          const previous = this.messages[index - 1];
          if (!current || !previous) return false;
          return current.pubkey === previous.pubkey;
        },
        
        // Convert hex pubkey to npub (bech32 encoding)
        hexToNpub(hex) {
          try {
            const ALPHABET = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l';
            const CHARSET_MAP = {};
            for (let i = 0; i < ALPHABET.length; i++) {
              CHARSET_MAP[ALPHABET[i]] = i;
            }
            
            // Convert hex to bytes
            const bytes = [];
            for (let i = 0; i < hex.length; i += 2) {
              bytes.push(parseInt(hex.substr(i, 2), 16));
            }
            
            // Convert 8-bit bytes to 5-bit groups
            const data = [];
            let value = 0;
            let bits = 0;
            for (const byte of bytes) {
              value = (value << 8) | byte;
              bits += 8;
              while (bits >= 5) {
                bits -= 5;
                data.push((value >> bits) & 31);
              }
            }
            if (bits > 0) {
              data.push((value << (5 - bits)) & 31);
            }
            
            // Create checksum
            const hrp = 'npub';
            const values = data;
            
            function polymod(values) {
              const GENERATOR = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3];
              let chk = 1;
              for (const v of values) {
                const top = chk >> 25;
                chk = ((chk & 0x1ffffff) << 5) ^ v;
                for (let i = 0; i < 5; i++) {
                  if ((top >> i) & 1) {
                    chk ^= GENERATOR[i];
                  }
                }
              }
              return chk;
            }
            
            function hrpExpand(hrp) {
              const ret = [];
              for (let i = 0; i < hrp.length; i++) {
                ret.push(hrp.charCodeAt(i) >> 5);
              }
              ret.push(0);
              for (let i = 0; i < hrp.length; i++) {
                ret.push(hrp.charCodeAt(i) & 31);
              }
              return ret;
            }
            
            const checksumInput = hrpExpand(hrp).concat(values).concat([0, 0, 0, 0, 0, 0]);
            const mod = polymod(checksumInput) ^ 0x2bc830a3; // bech32m constant
            const checksum = [];
            for (let i = 0; i < 6; i++) {
              checksum.push((mod >> (5 * (5 - i))) & 31);
            }
            
            // Encode
            let result = hrp + '1';
            for (const d of values.concat(checksum)) {
              result += ALPHABET[d];
            }
            
            return result;
          } catch (e) {
            // Fallback to hex if encoding fails
            return hex.substring(0, 8) + '...';
          }
        },
        
        // Get reaction count
        getReactionCount(eventId, emoji) {
          if (!this.reactions[eventId] || !this.reactions[eventId][emoji]) {
            return '';
          }
          const count = this.reactions[eventId][emoji].length;
          return count > 0 ? count : '';
        },
        
        // Get channel from message tags
        getMessageChannel(message) {
          if (!message.tags) return '';
          const channelTag = message.tags.find(tag => tag[0] === 'channel');
          return channelTag ? channelTag[1] : '';
        },
        
        // Tune to a channel (tell parent window to switch)
        tuneToChannel(channel) {
          if (!channel) return;
          try {
            // Convert spaces back to underscores (channel names use underscores)
            const channelId = channel.replace(/ /g, '_');
            // Send message to parent to change channel
            window.parent.postMessage({
              type: 'tuneToChannel',
              channel: channelId
            }, '*');
          } catch (e) {
            // Ignore tune request errors
          }
        },
        
        // Format time
        formatTime(timestamp) {
          const date = new Date(timestamp * 1000);
          const hours = date.getHours().toString().padStart(2, '0');
          const minutes = date.getMinutes().toString().padStart(2, '0');
          return `${hours}:${minutes}`;
        },
        
        // Escape HTML
        escapeHtml(text) {
          const div = document.createElement('div');
          div.textContent = text;
          return div.innerHTML;
        },
        
        escapeHtmlAttr(text) {
          return String(text)
            .replace(/&/g, '&amp;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
        },
        
        /** Song / station text before first ` #` — make it a link that searches in parent pleXtr */
        formatMessageContentHtml(content) {
          const raw = String(content || '');
          const idx = raw.indexOf(' #');
          const head = idx >= 0 ? raw.slice(0, idx).trim() : '';
          const tail = idx >= 0 ? raw.slice(idx) : '';
          if (!head) {
            return this.escapeHtml(raw);
          }
          const displayHead = head
            .replace(/^\s*now playing:\s*/i, '')
            .replace(/^\s*favorited:\s*/i, '⭐ ')
            .replace(/^\s*unfavorited:\s*/i, '☆ ');
          const searchQ = displayHead
            .replace(/^\s*👍\s+/, '')
            .replace(/^\s*⭐\s+/, '')
            .replace(/^\s*☆\s+/, '')
            .replace(/^\s*🌟\s+/, '')
            .trim();
          if (!searchQ) {
            return this.escapeHtml(raw);
          }
          return (
            '<button type="button" class="message-song-link" data-query="' +
            this.escapeHtmlAttr(searchQ) +
            '">' +
            this.escapeHtml(displayHead) +
            '</button>' +
            this.escapeHtml(tail)
          );
        },
        
        onMessageContentClick(event) {
          const btn = event.target.closest('.message-song-link');
          if (!btn) return;
          event.preventDefault();
          event.stopPropagation();
          const q = (btn.getAttribute('data-query') || '').trim();
          if (!q) return;
          this.postToParent({ type: 'chatSearchQuery', query: q }, 'chatSearchQuery');
        },

        setTransientError(message, timeoutMs = 5000) {
          this.errorMessage = String(message || '');
          if (!this.errorMessage) return;
          setTimeout(() => { this.errorMessage = ''; }, timeoutMs);
        },

        postToParent(payload, label = 'postMessage') {
          try {
            window.parent.postMessage(payload, '*');
          } catch (e) {
            console.warn('postMessage ' + label + ' failed:', e);
          }
        },

        getStoredPlextrNsec() {
          const raw = localStorage.getItem('guakaNostrNsec');
          if (!raw || typeof raw !== 'string') return null;
          const ns = raw.trim();
          return ns.startsWith('nsec') ? ns : null;
        },
        
        // Check auth when user focuses input
        // Flow: in minimal embed, try key from parent storage; otherwise wait for send/open modal
        async checkAuthOnFocus() {
          if (this.isAuthenticated) return;
          if (this.minimalEmbed) {
            const px = this.getStoredPlextrNsec();
            if (px) {
              this.manualKey = px;
              await this.authenticateWithKey();
            }
            return;
          }
        },
        
        // Handle keyboard input
        handleKeyDown(event) {
          // Shift+Enter = new line (allow default behavior)
          if (event.key === 'Enter' && event.shiftKey) {
            return; // Allow default behavior (new line)
          }
          
          // Enter (without Shift) = send message
          if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            this.sendMessage();
          }
        },
        
        // Clean up relays - remove any that aren't in our configured list
        cleanupRelays() {
          if (!this.ndk || !this.ndk.pool || !this.ndk.pool.relays) return;
          
          // Normalize configured relays (remove trailing slashes)
          const normalizedConfiguredRelays = new Set(
            this.relays.map((r) => this.normalizeRelayUrl(r))
          );
          const relaysToRemove = [];
          
          // Check all relays in the pool
          if (this.ndk.pool.relays instanceof Map) {
            for (const [url, relay] of this.ndk.pool.relays) {
              const normalizedUrl = this.normalizeRelayUrl(url);
              if (!normalizedConfiguredRelays.has(normalizedUrl)) {
                relaysToRemove.push(url);
              }
            }
          } else if (Array.isArray(this.ndk.pool.relays)) {
            for (const relay of this.ndk.pool.relays) {
              const url = relay.url || relay;
              const normalizedUrl = this.normalizeRelayUrl(url);
              if (!normalizedConfiguredRelays.has(normalizedUrl)) {
                relaysToRemove.push(url);
              }
            }
          }
          
          // Remove any relays not in our configured list
          for (const url of relaysToRemove) {
            try {
              if (this.ndk.pool.removeRelay) {
                this.ndk.pool.removeRelay(url);
              }
            } catch (e) {
              // Ignore removal errors
            }
          }
          
          // Ensure our configured relays are in the pool
          for (const relayUrl of this.relays) {
            try {
              let relayExists = false;
              if (this.ndk.pool.relays instanceof Map) {
                relayExists = this.ndk.pool.relays.has(relayUrl);
              } else if (Array.isArray(this.ndk.pool.relays)) {
                relayExists = this.ndk.pool.relays.some(r => (r.url || r) === relayUrl);
              }
              
              if (!relayExists) {
                if (this.ndk.pool.addRelay) {
                  this.ndk.pool.addRelay(relayUrl).catch(() => {});
                } else if (this.ndk.addExplicitRelay) {
                  this.ndk.addExplicitRelay(relayUrl);
                }
              }
            } catch (e) {
              // Could not add relay
            }
          }
        },
        
        // Store WebSocket connections to our relays
        relayWebSockets: {},
        
        // Get or create WebSocket connection to a relay
        async getRelayWebSocket(relayUrl) {
          // Check if we already have a connected WebSocket
          if (this.relayWebSockets[relayUrl] && 
              this.relayWebSockets[relayUrl].readyState === WebSocket.OPEN) {
            return this.relayWebSockets[relayUrl];
          }
          
          // Create a new WebSocket connection
          return new Promise((resolve, reject) => {
            try {
              const ws = new WebSocket(relayUrl);
              
              ws.onopen = () => {
                this.relayWebSockets[relayUrl] = ws;
                resolve(ws);
              };
              
              ws.onerror = (error) => {
                reject(new Error(`WebSocket error for ${relayUrl}`));
              };
              
              // Timeout after 5 seconds
              setTimeout(() => {
                if (ws.readyState !== WebSocket.OPEN) {
                  ws.close();
                  reject(new Error(`WebSocket connection timeout for ${relayUrl}`));
                }
              }, 5000);
              
            } catch (e) {
              reject(e);
            }
          });
        },
        
        // Publish event directly via WebSocket to a relay
        async publishToRelayViaWebSocket(relayUrl, plainEvent) {
          return new Promise(async (resolve, reject) => {
            try {
              const ws = await this.getRelayWebSocket(relayUrl);
              
              // Listen for OK response
              const messageHandler = (msg) => {
                try {
                  const data = JSON.parse(msg.data);
                  // ["OK", <event_id>, <true|false>, <message>]
                  if (data[0] === 'OK' && data[1] === plainEvent.id) {
                    ws.removeEventListener('message', messageHandler);
                    if (data[2]) {
                      resolve({ success: true, relay: relayUrl });
                    } else {
                      reject(new Error(`Relay rejected event: ${data[3] || 'unknown reason'}`));
                    }
                  }
                } catch (e) {
                  // Not JSON or not relevant message, ignore
                }
              };
              
              ws.addEventListener('message', messageHandler);
              
              // Send the event
              const eventMessage = JSON.stringify(['EVENT', plainEvent]);
              ws.send(eventMessage);
              
              // Timeout after 10 seconds
              setTimeout(() => {
                ws.removeEventListener('message', messageHandler);
                reject(new Error(`Publish timeout for ${relayUrl}`));
              }, 10000);
              
            } catch (e) {
              reject(e);
            }
          });
        },
        
        // Publish event to only our configured relays (ignore extension/user relays)
        async publishToOurRelays(event) {
          // Convert NDKEvent to plain event object for publishing
          const plainEvent = {
            id: event.id,
            kind: event.kind,
            created_at: event.created_at,
            tags: event.tags || [],
            content: event.content || '',
            sig: event.sig,
            pubkey: event.pubkey
          };
          
          // Publish directly to each of our configured relays via WebSocket
          // This completely bypasses NDK's relay management
          const publishPromises = this.relays.map(relayUrl => 
            this.publishToRelayViaWebSocket(relayUrl, plainEvent)
              .catch(e => ({ error: e.message, relay: relayUrl }))
          );
          
          // Wait for all publish attempts
          const results = await Promise.allSettled(publishPromises);
          
          // Count successes
          const successes = results.filter(r => 
            r.status === 'fulfilled' && 
            r.value && 
            r.value.success
          );
          
          if (successes.length === 0) {
            const errors = results
              .map(r => r.status === 'fulfilled' ? r.value?.error : r.reason?.message)
              .filter(Boolean);
            throw new Error(`Failed to publish to any relay: ${errors.join(', ')}`);
          }
          
          return { success: true, publishedTo: successes.length };
        },
        
        // Send message
        // Flow: require local key auth; show modal if missing
        async sendMessage() {
          if (!this.isAuthenticated) {
            if (this.minimalEmbed) {
              const px = this.getStoredPlextrNsec();
              if (px) {
                this.manualKey = px;
                await this.authenticateWithKey();
              }
              if (!this.isAuthenticated) {
                this.postToParent({ type: 'openSettingsModal', tab: 'nostr' }, 'openSettingsModal');
                this.setTransientError('Add your nsec in Nostr (header).', 5000);
                return;
              }
            } else {
              this.showAuthModal = true;
              return;
            }
          }
          
          if (!this.messageInput.trim() || !this.ndk || !this.ndk.signer) {
            if (!this.ndk || !this.ndk.signer) {
              this.setTransientError('Not connected. Please wait or refresh.', 3000);
              return;
            }
            return;
          }
          
          try {
            this.isSending = true;
            
            const messageContent = this.messageInput.trim();
            const NDKEvent = window.NDKModule?.NDKEvent;
            if (!NDKEvent) {
              throw new Error('NDKEvent not available');
            }
            
            // Calculate expiration: 30 days from now (Unix timestamp in seconds)
            const expirationTimestamp = Math.floor((Date.now() + (30 * 24 * 60 * 60 * 1000)) / 1000);
            
            const event = new NDKEvent(this.ndk);
            event.kind = 1;
            event.content = messageContent;
            event.tags = [['t', 'plextr'], ['t', 'radioguaka']];
            
            // Add expiration tag (NIP-40: 30 days from now)
            event.tags.push(['expiration', expirationTimestamp.toString()]);
            
            if (this.currentChannel) {
              event.tags.push(['channel', this.currentChannel]);
            }
            
            // Sign event with local key signer
            await event.sign();
            
            // Get event ID after signing (required for optimistic update)
            const eventId = event.id;
            
            // Ensure we have a valid event ID
            if (!eventId) {
              throw new Error('Failed to generate event ID');
            }
            
            // Ensure messages is an array
            this.ensureMessagesArray();
            
            // Optimistically add message to UI immediately (before publish)
            const optimisticMessage = {
              id: eventId,
              pubkey: this.currentUserPubkey,
              content: messageContent,
              created_at: Math.floor(Date.now() / 1000),
              tags: event.tags,
              optimistic: true // Mark as optimistic
            };
            
            // Insert sorted by timestamp
            const insertIndex = this.messages.findIndex(m => m && m.created_at > optimisticMessage.created_at);
            if (insertIndex === -1) {
              this.messages.push(optimisticMessage);
            } else {
              this.messages.splice(insertIndex, 0, optimisticMessage);
            }
            
            // Force Alpine.js to update
            this.messages = [...this.messages];
            
            // Scroll to bottom to show new message
            this.scrollMessagesToBottom();
            
            // Clear input immediately for better UX
            this.messageInput = '';
            
            // Publish to our configured relays only (ignore extension/user relays)
            await this.publishToOurRelays(event);
            
            // Remove optimistic flag once published (message will come back through subscription)
            if (eventId) {
              const messageIndex = this.messages.findIndex(m => m.id === eventId);
              if (messageIndex !== -1 && this.messages[messageIndex].optimistic) {
                this.messages[messageIndex].optimistic = false;
                // Force update
                this.messages = [...this.messages];
              }
            }
            
          } catch (error) {
            console.error('Send error:', error.message);
            this.setTransientError('Failed to send message: ' + error.message, 5000);
            
            // Remove optimistic message if send failed
            try {
              this.ensureMessagesArray();
              if (event && event.id) {
                this.messages = this.messages.filter(m => m && m.id !== event.id && !m.optimistic);
              }
            } catch (e) {
              // Ignore cleanup errors
            }
            
          } finally {
            this.isSending = false;
          }
        },
        
        // React to message
        async reactToMessage(eventId, emoji) {
          if (!this.isAuthenticated || !this.ndk || !this.ndk.signer) return;
          
          try {
            const NDKEvent = window.NDKModule?.NDKEvent;
            if (!NDKEvent) return;
            
            const event = new NDKEvent(this.ndk);
            event.kind = 7;
            event.content = emoji;
            event.tags = [['e', eventId]];
            
            await event.sign();
            await this.publishToOurRelays(event);
          } catch (error) {
            console.error('Reaction error:', error);
          }
        },
        
        // Delete message
        async deleteMessage(eventId) {
          if (!this.isAuthenticated || !this.ndk || !this.ndk.signer) return;
          
          try {
            const NDKEvent = window.NDKModule?.NDKEvent;
            if (!NDKEvent) return;
            
            const event = new NDKEvent(this.ndk);
            event.kind = 5;
            event.tags = [['e', eventId]];
            
            await event.sign();
            await this.publishToOurRelays(event);
            
            // Remove from local messages
            this.ensureMessagesArray();
            this.messages = this.messages.filter(m => m && m.id !== eventId);
            
            // Update cache
            this.saveCachedMessages();
          } catch (error) {
            console.error('Delete error:', error);
          }
        },
        
        // Set pubkey and compute npub
        setUserPubkey(pubkey) {
          // Idempotent guard: avoid recomputing/logging on duplicate calls
          if (pubkey && this.currentUserPubkey === pubkey && this.currentUserNpub) {
            return;
          }
          this.currentUserPubkey = pubkey;
          // Convert to npub using NDK
          if (pubkey && !pubkey.includes('...') && !pubkey.startsWith('npub')) {
            try {
              const NDKUser = window.NDKModule?.NDKUser;
              if (NDKUser) {
                const user = new NDKUser({ pubkey: pubkey });
                this.currentUserNpub = user.npub;
                console.log('Computed npub:', this.currentUserNpub);
              } else {
                console.log('NDKUser not available yet, will retry when NDK loads');
              }
            } catch (e) {
              console.log('Could not convert to npub:', e);
            }
          } else if (pubkey && pubkey.startsWith('npub')) {
            // Already an npub
            this.currentUserNpub = pubkey;
          }
        },
        
        // Set private key and compute nsec
        setUserPrivateKey(privateKey) {
          // Idempotent guard: repeated auth paths can call this many times
          if (privateKey && this.currentUserPrivateKey === privateKey && this.currentUserNsec) {
            return;
          }
          this.currentUserPrivateKey = privateKey;
          // If already nsec format, use it directly
          if (privateKey && privateKey.startsWith('nsec')) {
            this.currentUserNsec = privateKey;
            console.log('Using nsec directly', redactNsecForLog(this.currentUserNsec));
          } else if (privateKey && !privateKey.includes('...')) {
            // Convert hex to nsec using NDKPrivateKeySigner
            try {
              const NDKPrivateKeySigner = window.NDKModule?.NDKPrivateKeySigner;
              if (NDKPrivateKeySigner) {
                // Create signer to get user, then get nsec
                const signer = new NDKPrivateKeySigner(privateKey);
                signer.user().then(user => {
                  if (user && user.nsec) {
                    this.currentUserNsec = user.nsec;
                    console.log('Computed nsec', redactNsecForLog(this.currentUserNsec));
                  }
                }).catch(e => {
                  console.log('Could not get nsec from signer:', e);
                });
              } else {
                console.log('NDKPrivateKeySigner not available yet, will retry when NDK loads');
              }
            } catch (e) {
              console.log('Could not convert to nsec:', e);
            }
          }
        },

        // Keep key assignment in one place for auth flows
        setUserCredentials(privateKey, pubkey) {
          this.setUserPrivateKey(privateKey);
          if (pubkey) {
            this.setUserPubkey(pubkey);
          }
        },
        
        // Authenticate with key (nsec only)
        async authenticateWithKey() {
          try {
            if (!this.manualKey.trim()) {
              this.setTransientError('Please enter an nsec key', 3000);
              return;
            }
            
            const key = this.manualKey.trim();
            this.errorMessage = ''; // Clear previous errors
            
            // Only accept nsec
            if (!key.startsWith('nsec')) {
              this.setTransientError('Only nsec private keys are accepted. Please enter an nsec1... key.', 6000);
              return;
            }
            
            // Private key — NDK 3+ uses NDKPrivateKeySigner for nsec (NDKUser.fromNsec was removed)
            try {
              const NDKPrivateKeySigner = window.NDKModule?.NDKPrivateKeySigner;
              if (NDKPrivateKeySigner) {
                const signer = new NDKPrivateKeySigner(key);
                this.setUserCredentials(key, signer.pubkey);
              } else {
                this.setUserPrivateKey(key);
                this.currentUserPubkey = 'loading...';
              }
              
              this.isAuthenticated = true;
              this.showAuthModal = false;
              this.showManualAuth = false;
              localStorage.setItem('radio_guaka_chat_private_key', key);
              if (this.currentUserPubkey && this.currentUserPubkey !== 'loading...') {
                localStorage.setItem('radio_guaka_chat_pubkey', this.currentUserPubkey);
              }
              
              await this.setupSigner();
              
              // Update pubkey after signer is set up if needed
              if (this.currentUserPubkey === 'loading...' && this.ndk && this.ndk.signer) {
                try {
                  const user = await this.ndk.signer.user();
                  if (user && user.pubkey) {
                    this.setUserPubkey(user.pubkey);
                    localStorage.setItem('radio_guaka_chat_pubkey', user.pubkey);
                  }
                } catch (e) {
                  console.error('Error getting pubkey from signer:', e);
                }
              }
              
              // Fetch user's profile to get NIP-05
              this.fetchCurrentUserProfile();
            } catch (e) {
              // Minimal embed: nsec comes from pleXtr header — no in-chat error toast
              if (this.minimalEmbed) {
                console.warn('Chat could not apply nsec (minimal embed):', e);
              } else {
                this.setTransientError('Invalid nsec format. Please check your key and try again.', 6000);
              }
            }
            
          } catch (error) {
            console.error('Key auth error:', error);
            this.setTransientError('Authentication failed: ' + error.message, 6000);
          } finally {
            // Clear the manual key input after processing
            if (this.isAuthenticated) {
              this.manualKey = '';
              this.showManualAuth = false;
            }
          }
        },
        
        // Generate new key
        async generateNewKey() {
          try {
            this.errorMessage = ''; // Clear any previous errors
            
            // Use NDK to generate a keypair
            if (window.NDKModule && window.NDKModule.generatePrivateKey) {
              const privateKey = window.NDKModule.generatePrivateKey();
              const NDKPrivateKeySigner = window.NDKModule.NDKPrivateKeySigner;
              
              if (NDKPrivateKeySigner) {
                try {
                  // Use signer to get user and pubkey
                  const signer = new NDKPrivateKeySigner(privateKey);
                  const user = await signer.user();
                  if (user && user.pubkey) {
                    this.setUserCredentials(privateKey, user.pubkey);
                    // Try to get nsec if available
                    if (user.nsec) {
                      this.currentUserNsec = user.nsec;
                    }
                  } else {
                    this.setUserPrivateKey(privateKey);
                    this.currentUserPubkey = 'generating...';
                  }
                } catch (e) {
                  // If signer fails, just store the key
                  this.setUserPrivateKey(privateKey);
                  this.currentUserPubkey = 'generating...';
                }
              } else {
                // Fallback: generate hex key and convert
                this.setUserPrivateKey(privateKey);
                this.currentUserPubkey = 'generating...';
              }
            } else {
              // Fallback: generate random hex using Web Crypto API
              const bytes = new Uint8Array(32);
              crypto.getRandomValues(bytes);
              const hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
              this.setUserPrivateKey(hex);
              this.currentUserPubkey = 'generating...';
            }
            
            this.isAuthenticated = true;
            this.showAuthModal = false;
            
            // Save to localStorage
            localStorage.setItem('radio_guaka_chat_private_key', this.currentUserPrivateKey);
            if (this.currentUserPubkey && this.currentUserPubkey !== 'generating...') {
              localStorage.setItem('radio_guaka_chat_pubkey', this.currentUserPubkey);
            }
            
            await this.setupSigner();
            
            // Update pubkey after signer is set up
            if (this.currentUserPubkey === 'generating...' && this.ndk && this.ndk.signer) {
              try {
                const user = await this.ndk.signer.user();
                if (user && user.pubkey) {
                  this.currentUserPubkey = user.pubkey;
                  localStorage.setItem('radio_guaka_chat_pubkey', user.pubkey);
                }
              } catch (e) {
                console.error('Error getting pubkey from signer:', e);
                this.setTransientError('Key generated but failed to get public key. You may need to reconnect.', 5000);
              }
            }
            
          } catch (error) {
            console.error('Key generation error:', error.message);
            this.setTransientError('Failed to generate key: ' + error.message, 6000);
          }
        },
        
        // Delete all keys
        deleteKeys() {
          this.currentUserPrivateKey = null;
          this.currentUserNsec = null;
          this.currentUserPubkey = null;
          this.currentUserNpub = null;
          this.currentUserNip05 = null;
          this.isAuthenticated = false;
          this.manualKey = '';
          this.showPrivateKey = false;

          // Clear from localStorage
          localStorage.removeItem('radio_guaka_chat_private_key');
          localStorage.removeItem('radio_guaka_chat_pubkey');

          // Clear signer
          if (this.ndk) {
            this.ndk.signer = null;
          }
        },
        
        // Setup signer
        async setupSigner() {
          try {
            if (!this.ndk) return;
            if (this.currentUserPrivateKey) {
              // Use private key signer
              try {
                const NDKPrivateKeySigner = window.NDKModule.NDKPrivateKeySigner;
                if (NDKPrivateKeySigner) {
                  this.ndk.signer = new NDKPrivateKeySigner(this.currentUserPrivateKey);
                } else {
                  console.warn('NDKPrivateKeySigner missing; cannot attach nsec signer to NDK');
                }
              } catch (e) {
                console.error('Error setting up private key signer:', e);
              }
            }
          } catch (error) {
            console.error('Signer setup error:', error);
          }
        },
        
        // Fetch current user's profile to get NIP-05 and other info
        async fetchCurrentUserProfile() {
          try {
            if (!this.ndk || !this.currentUserPubkey) return;
            
            // Check if we already have their profile cached
            if (this.userProfiles[this.currentUserPubkey]?.nip05) {
              this.currentUserNip05 = this.userProfiles[this.currentUserPubkey].nip05;
              return;
            }
            
            const filter = {
              kinds: [0], // Profile metadata
              authors: [this.currentUserPubkey],
              limit: 1
            };
            
            const events = await this.ndk.fetchEvents(filter);
            
            if (events && events.size > 0) {
              for (const event of events) {
                this.handleProfileEvent(event);
              }
            }
          } catch (error) {
            console.error('Error fetching user profile:', error);
          }
        },
        
        // Fetch past messages directly (fallback method)
        async fetchPastMessages() {
          try {
            if (!this.ndk) return;
            
            const seen = new Set();
            for (let ti = 0; ti < this.chatRoomTags.length; ti++) {
              const filter = {
                kinds: [1],
                '#t': [this.chatRoomTags[ti]],
                limit: 100
              };
              const events = await this.ndk.fetchEvents(filter);
              if (!events) continue;
              const visit = (ev) => {
                if (ev && ev.id && !seen.has(ev.id)) {
                  seen.add(ev.id);
                  this.handleMessageEvent(ev);
                }
              };
              if (events instanceof Set || Array.isArray(events)) {
                events.forEach(visit);
              } else if (typeof events.forEach === 'function') {
                events.forEach(visit);
              } else if (events.values && typeof events.values === 'function') {
                for (const ev of events.values()) visit(ev);
              }
            }
            
            this.isLoading = false;
          } catch (error) {
            console.error('Error fetching past messages:', error);
            this.isLoading = false;
          }
        },
        
        // Load cached messages from localStorage
        loadCachedMessages() {
          try {
            // Ensure messages is initialized as array
            this.ensureMessagesArray();
            
            const cached = localStorage.getItem('radio_guaka_chat_messages');
            if (cached) {
              const messages = JSON.parse(cached);
              if (Array.isArray(messages) && messages.length > 0) {
                // Filter out any messages without valid IDs
                const validMessages = messages.filter(m => m && m.id);
                this.messages = validMessages;
                // Deduplicate after loading
                this.deduplicateMessages();
                
                // Scroll to bottom after loading cached messages
                this.scrollMessagesToBottom();
              }
            }
          } catch (e) {
            console.error('Error loading cached messages:', e);
            // Clear corrupted cache and ensure messages is an array
            localStorage.removeItem('radio_guaka_chat_messages');
            this.ensureMessagesArray();
          }
        },
        
        // Save messages to localStorage cache
        saveCachedMessages() {
          try {
            // Ensure messages is an array
            this.ensureMessagesArray();
            
            // Only save the last 200 messages with valid IDs to avoid storage limits
            const toSave = this.messages
              .filter(m => m && m.id) // Only messages with valid IDs
              .slice(-200)
              .map(m => ({
                id: m.id,
                pubkey: m.pubkey,
                content: m.content,
                created_at: m.created_at,
                tags: m.tags
              }));
            localStorage.setItem('radio_guaka_chat_messages', JSON.stringify(toSave));
          } catch (e) {
            console.error('Error saving cached messages:', e);
          }
        },
        
        // Mark all relays as connected (called when we receive events)
        markRelaysConnected() {
          for (const relayUrl of this.relays) {
            if (this.relayStatus[relayUrl]?.status !== 'connected') {
              this.setRelayStatusFor(relayUrl, 'connected');
            }
          }
        },
        
        // Update relay status by checking actual connection state
        updateRelayStatus() {
          try {
            if (!this.ndk || !this.ndk.pool) return;
            
            // Check each configured relay's actual status
            for (const relayUrl of this.relays) {
              const relay = this.findRelayInPool(relayUrl);
              
              if (relay) {
                // Check relay's connection status
                // NDK relay status can be: 'connecting', 'connected', 'disconnected', etc.
                const status = relay.status ?? relay.connectionStatus ?? relay.readyState;
                this.relayStatus[relayUrl] = { status: this.relayStatusFromValue(status) };
              }
            }
            
          } catch (e) {
            // Ignore relay status errors
          }
        },
        
        // Load keys from storage
        async loadKeysFromStorage() {
          try {
            if (this.minimalEmbed) {
              const px = this.getStoredPlextrNsec();
              if (px) {
                this._pendingPlextrNsec = px;
                return;
              }
              return;
            }
            const privateKey = localStorage.getItem('radio_guaka_chat_private_key');
            const pubkey = localStorage.getItem('radio_guaka_chat_pubkey');
            
            if (privateKey) {
              this.setUserCredentials(privateKey, pubkey);
              this.isAuthenticated = true;
              
              // Set up signer if NDK is ready
              if (this.ndk) {
                await this.setupSigner();
                // Update pubkey from signer if available
                if (this.ndk && this.ndk.signer) {
                  try {
                    const user = await this.ndk.signer.user();
                    if (user && user.pubkey) {
                      this.setUserPubkey(user.pubkey);
                      localStorage.setItem('radio_guaka_chat_pubkey', user.pubkey);
                    }
                  } catch (e) {
                    // Could not get pubkey from signer
                  }
                }
                // Fetch user's profile to get NIP-05
                this.fetchCurrentUserProfile();
              }
            } else if (pubkey) {
              this.setUserPubkey(pubkey);
              this.isAuthenticated = false; // Read-only
            }
          } catch (e) {
            console.error('Error loading keys:', e);
          }
        },
        
      };
    }
  
