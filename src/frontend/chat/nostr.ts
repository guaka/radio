    function buildNostrErrorBlob(value, depth = 0) {
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
      if (window.__rgChatNostrConsoleFilterInstalled) return;
      window.__rgChatNostrConsoleFilterInstalled = true;
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

    (function installNostrErrorGuard() {
      installNostrConsoleNoiseFilter();
      if (window.__rgChatNostrRejectionGuard) return;
      window.__rgChatNostrRejectionGuard = true;
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

    async function loadNDK() {
      try {
        // Import NDK from esm.sh - use latest version for bug fixes
        const NDKModule = await import('https://esm.sh/@nostr-dev-kit/ndk@latest');
        
        // NDK is the default export
        const NDK = NDKModule.default;
        
        // Get named exports - try multiple ways
        let NDKEvent = NDKModule.NDKEvent || NDK?.NDKEvent;
        let NDKUser = NDKModule.NDKUser || NDK?.NDKUser;
        let NDKPrivateKeySigner = NDKModule.NDKPrivateKeySigner || NDK?.NDKPrivateKeySigner;
        
        // Try importing from specific paths if not found
        if (!NDKEvent) {
          try {
            const eventModule = await import('https://esm.sh/@nostr-dev-kit/ndk@2.0.0/events');
            NDKEvent = eventModule.NDKEvent || eventModule.default;
          } catch (e) {
            // Fallback import failed
          }
        }
        
        if (!NDKUser) {
          try {
            const userModule = await import('https://esm.sh/@nostr-dev-kit/ndk@2.0.0/user');
            NDKUser = userModule.NDKUser || userModule.default;
          } catch (e) {
            // Fallback import failed
          }
        }
        
        if (!NDKPrivateKeySigner) {
          try {
            const signerModule = await import('https://esm.sh/@nostr-dev-kit/ndk@2.0.0/signers/private-key');
            NDKPrivateKeySigner = signerModule.NDKPrivateKeySigner || signerModule.default;
          } catch (e) {
            // Fallback import failed
          }
        }
        
        if (!NDK || typeof NDK !== 'function') {
          throw new Error('NDK class not found or not a constructor');
        }
        
        // Import crypto utilities for generatePrivateKey
        let generatePrivateKey = null;
        try {
          const cryptoUtils = await import('https://esm.sh/@noble/secp256k1@1.7.1');
          if (cryptoUtils && cryptoUtils.utils && cryptoUtils.utils.randomPrivateKey) {
            generatePrivateKey = () => {
              const bytes = cryptoUtils.utils.randomPrivateKey();
              return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
            };
          }
        } catch (e) {
          // Crypto utils import failed, will use fallback
        }
        
        // Fallback generatePrivateKey using Web Crypto API
        if (!generatePrivateKey) {
          generatePrivateKey = () => {
            const bytes = new Uint8Array(32);
            crypto.getRandomValues(bytes);
            return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
          };
        }
        
        // Expose NDK classes globally for compatibility with existing code
        window.NDK = NDK;
        window.NDKModule = {
          NDK: NDK,
          NDKEvent: NDKEvent,
          NDKUser: NDKUser,
          NDKPrivateKeySigner: NDKPrivateKeySigner,
          generatePrivateKey: generatePrivateKey
        };
        
        window.dispatchEvent(new CustomEvent('ndkReady', { 
          detail: { NDK: NDK, source: 'CDN (esm.sh)' } 
        }));
        
      } catch (error) {
        console.error('Failed to load NDK:', error);
        window.dispatchEvent(new CustomEvent('ndkError', { 
          detail: { error: error.message } 
        }));
      }
    }
    
    loadNDK();
  
