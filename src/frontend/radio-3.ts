    // Network and ready state mappings
    const networkStates = {
      0: 'empty',
      1: 'idle',
      2: 'loading',
      3: 'no source'
    };

    const readyStates = {
      0: 'nothing',
      1: 'metadata',
      2: 'current',
      3: 'future',
      4: 'enough'
    };

    // Radio Tuning Noise Generator - Enhanced FM Radio Static
    class RadioTuningNoise {
      constructor() {
        this.audioContext = null;
        this.noiseSources = [];
        this.gainNode = null;
        this.biquadFilter = null;
        this.oscillators = [];
        this.intervals = [];
        this.isPlaying = false;
        this.volume = 0.5; // Volume for tuning noise (0-1)
        this.nodes = []; // Track all nodes for cleanup
      }
      
      init() {
        try {
          // Create audio context if not already created
          if (!this.audioContext) {
            const AudioContext = window.AudioContext || window.webkitAudioContext;
            this.audioContext = new AudioContext();
          }
          
          // Resume context if suspended (required for autoplay policies)
          if (this.audioContext.state === 'suspended') {
            this.audioContext.resume();
          }
        } catch (e) {
          console.log('Web Audio API not supported:', e);
          return false;
        }
        return true;
      }
      
      play() {
        if (this.isPlaying) return;
        
        if (!this.init()) return;
        
        try {
          const now = this.audioContext.currentTime;
          const sampleRate = this.audioContext.sampleRate;
          
          // Create multiple noise buffers with different characteristics
          const bufferSize = sampleRate * 3; // 3 seconds
          
          // Main pink noise buffer (base static)
          const pinkBuffer = this.audioContext.createBuffer(1, bufferSize, sampleRate);
          const pinkData = pinkBuffer.getChannelData(0);
          let b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
          for (let i = 0; i < bufferSize; i++) {
            const white = Math.random() * 2 - 1;
            b0 = 0.99886 * b0 + white * 0.0555179;
            b1 = 0.99332 * b1 + white * 0.0750759;
            b2 = 0.96900 * b2 + white * 0.1538520;
            b3 = 0.86650 * b3 + white * 0.3104856;
            b4 = 0.55000 * b4 + white * 0.5329522;
            b5 = -0.7616 * b5 - white * 0.0168980;
            pinkData[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.12;
            b6 = white * 0.115926;
          }
          
          // High-frequency white noise buffer (for FM hiss)
          const whiteBuffer = this.audioContext.createBuffer(1, bufferSize, sampleRate);
          const whiteData = whiteBuffer.getChannelData(0);
          for (let i = 0; i < bufferSize; i++) {
            whiteData[i] = (Math.random() * 2 - 1) * 0.08;
          }
          
          // Create noise sources
          const pinkSource = this.audioContext.createBufferSource();
          pinkSource.buffer = pinkBuffer;
          pinkSource.loop = true;
          
          const whiteSource = this.audioContext.createBufferSource();
          whiteSource.buffer = whiteBuffer;
          whiteSource.loop = true;
          
          this.noiseSources = [pinkSource, whiteSource];
          
          // Create main bandpass filter for tuning effect
          this.biquadFilter = this.audioContext.createBiquadFilter();
          this.biquadFilter.type = 'bandpass';
          this.biquadFilter.frequency.value = 2000; // Lower center frequency
          this.biquadFilter.Q.value = 10;
          
          // Create secondary bandpass for texture
          const bandpass2 = this.audioContext.createBiquadFilter();
          bandpass2.type = 'bandpass';
          bandpass2.frequency.value = 3500; // Lower frequency
          bandpass2.Q.value = 5;
          
          // High-pass filter (remove rumble)
          const highPass = this.audioContext.createBiquadFilter();
          highPass.type = 'highpass';
          highPass.frequency.value = 300; // Lower cutoff
          highPass.Q.value = 0.7;
          
          // Low-pass filter (FM bandwidth limit)
          const lowPass = this.audioContext.createBiquadFilter();
          lowPass.type = 'lowpass';
          lowPass.frequency.value = 18000;
          lowPass.Q.value = 0.7;
          
          // High-frequency emphasis (FM has good highs)
          const highShelf = this.audioContext.createBiquadFilter();
          highShelf.type = 'highshelf';
          highShelf.frequency.value = 6000; // Lower frequency
          highShelf.gain.value = 3; // Boost highs slightly
          
          // Create gain nodes - slightly louder
          const pinkGain = this.audioContext.createGain();
          pinkGain.gain.value = 0.8;
          
          const whiteGain = this.audioContext.createGain();
          whiteGain.gain.value = 0.6;
          
          this.gainNode = this.audioContext.createGain();
          this.gainNode.gain.value = 0;
          
          // Create master merger
          const merger = this.audioContext.createChannelMerger(1);
          
          // Connect pink noise chain
          pinkSource.connect(pinkGain);
          pinkGain.connect(highPass);
          highPass.connect(this.biquadFilter);
          this.biquadFilter.connect(bandpass2);
          bandpass2.connect(lowPass);
          lowPass.connect(highShelf);
          highShelf.connect(merger, 0, 0);
          
          // Connect white noise (high-frequency hiss)
          whiteSource.connect(whiteGain);
          whiteGain.connect(bandpass2);
          
          // Connect to output
          merger.connect(this.gainNode);
          this.gainNode.connect(this.audioContext.destination);
          
          // Create frequency modulation (less variation)
          // Main slow sweep - more consistent
          const mainSweep = this.audioContext.createOscillator();
          mainSweep.type = 'sine';
          mainSweep.frequency.value = 0.3; // Fixed, less variation
          
          // Fast sweep - more consistent
          const fastSweep = this.audioContext.createOscillator();
          fastSweep.type = 'triangle';
          fastSweep.frequency.value = 2.0; // Fixed, less variation
          
          // Slow random walk oscillator (reduced variation)
          const randomWalk = this.audioContext.createOscillator();
          randomWalk.type = 'sawtooth';
          randomWalk.frequency.value = 0.12; // Fixed, less variation
          
          // Modulation gains - more consistent
          const modGain1 = this.audioContext.createGain();
          modGain1.gain.value = 1800; // Fixed, less variation
          
          const modGain2 = this.audioContext.createGain();
          modGain2.gain.value = 1000; // Fixed, less variation
          
          const modGain3 = this.audioContext.createGain();
          modGain3.gain.value = 600; // Fixed, less variation
          
          // Combine modulations
          const modMerger = this.audioContext.createChannelMerger(1);
          mainSweep.connect(modGain1);
          fastSweep.connect(modGain2);
          randomWalk.connect(modGain3);
          modGain1.connect(modMerger, 0, 0);
          modGain2.connect(modMerger, 0, 0);
          modGain3.connect(modMerger, 0, 0);
          
          // Connect to filter frequency
          modMerger.connect(this.biquadFilter.frequency);
          
          // Dynamic Q variation (signal strength simulation) - reduced variation
          const qVariation = this.audioContext.createOscillator();
          qVariation.type = 'sine';
          qVariation.frequency.value = 0.7; // Fixed, less variation
          const qGain = this.audioContext.createGain();
          qGain.gain.value = 3.5; // Fixed, less variation
          qVariation.connect(qGain);
          qGain.connect(this.biquadFilter.Q);
          
          // Amplitude modulation for texture - reduced variation
          const ampMod = this.audioContext.createOscillator();
          ampMod.type = 'sine';
          ampMod.frequency.value = 3.5; // Fixed, less variation
          const ampGain = this.audioContext.createGain();
          ampGain.gain.value = 0.12; // Slightly reduced
          ampMod.connect(ampGain);
          ampGain.connect(pinkGain.gain);
          
          // Store nodes
          this.nodes = [
            mainSweep, fastSweep, randomWalk, modGain1, modGain2, modGain3, modMerger,
            highPass, bandpass2, lowPass, highShelf, pinkGain, whiteGain, merger,
            qVariation, qGain, ampMod, ampGain
          ];
          this.oscillators = [mainSweep, fastSweep, randomWalk, qVariation, ampMod];
          
          // Start sources
          pinkSource.start(0);
          whiteSource.start(0);
          mainSweep.start(0);
          fastSweep.start(0);
          randomWalk.start(0);
          qVariation.start(0);
          ampMod.start(0);
          
          // Add crackling/pops
          const crackleGain = this.audioContext.createGain();
          crackleGain.gain.value = 0;
          pinkSource.connect(crackleGain);
          crackleGain.connect(this.gainNode);
          
          const crackleInterval = setInterval(() => {
            if (this.isPlaying && crackleGain && this.audioContext) {
              const crackleTime = this.audioContext.currentTime;
              const intensity = 0.3 + Math.random() * 0.4;
              crackleGain.gain.setValueAtTime(0, crackleTime);
              crackleGain.gain.setValueAtTime(intensity, crackleTime);
              crackleGain.gain.exponentialRampToValueAtTime(0.001, crackleTime + 0.03 + Math.random() * 0.04);
            }
          }, 80 + Math.random() * 180);
          
          this.intervals.push(crackleInterval);
          this.nodes.push(crackleGain);
          
          // Add brief "station-like" peaks (moments where you almost hear something)
          const stationPeakGain = this.audioContext.createGain();
          stationPeakGain.gain.value = 0;
          pinkSource.connect(stationPeakGain);
          
          // Filter for station peaks (voice-like frequencies) - lower frequencies
          const stationFilter = this.audioContext.createBiquadFilter();
          stationFilter.type = 'bandpass';
          stationFilter.frequency.value = 1500; // Lower frequency
          stationFilter.Q.value = 15;
          stationPeakGain.connect(stationFilter);
          stationFilter.connect(this.gainNode);
          
          const stationInterval = setInterval(() => {
            if (this.isPlaying && stationPeakGain && this.audioContext && Math.random() > 0.7) {
              const peakTime = this.audioContext.currentTime;
              const duration = 0.1 + Math.random() * 0.2;
              const freq = 1200 + Math.random() * 1500; // Lower frequency range
              
              stationFilter.frequency.setValueAtTime(freq, peakTime);
              stationPeakGain.gain.setValueAtTime(0, peakTime);
              stationPeakGain.gain.linearRampToValueAtTime(0.25 + Math.random() * 0.15, peakTime + 0.05);
              stationPeakGain.gain.linearRampToValueAtTime(0, peakTime + duration);
            }
          }, 300 + Math.random() * 500);
          
          this.intervals.push(stationInterval);
          this.nodes.push(stationPeakGain, stationFilter);
          
          // Random frequency jumps (like tuning knob movement) - reduced frequency
          const freqJumpInterval = setInterval(() => {
            if (this.isPlaying && this.biquadFilter && this.audioContext && Math.random() > 0.92) {
              const jumpTime = this.audioContext.currentTime;
              const currentFreq = this.biquadFilter.frequency.value;
              const newFreq = currentFreq + (Math.random() - 0.5) * 1500; // Smaller jumps
              this.biquadFilter.frequency.setValueAtTime(currentFreq, jumpTime);
              this.biquadFilter.frequency.linearRampToValueAtTime(newFreq, jumpTime + 0.15);
            }
          }, 400 + Math.random() * 400); // Less frequent jumps
          
          this.intervals.push(freqJumpInterval);
          
          // Fade in
          this.gainNode.gain.setValueAtTime(0, now);
          this.gainNode.gain.linearRampToValueAtTime(this.volume, now + 0.2);
          
          this.isPlaying = true;
        } catch (e) {
          console.log('Error playing tuning noise:', e);
        }
      }
      
      stop() {
        if (!this.isPlaying) return;
        
        try {
          // Clear all intervals
          this.intervals.forEach(interval => {
            clearInterval(interval);
          });
          this.intervals = [];
          
          // Fade out smoothly
          if (this.gainNode && this.audioContext) {
            const now = this.audioContext.currentTime;
            this.gainNode.gain.setValueAtTime(this.volume, now);
            this.gainNode.gain.linearRampToValueAtTime(0, now + 0.25);
            
            // Stop sources after fade out
            setTimeout(() => {
              // Stop all oscillators
              this.oscillators.forEach(osc => {
                try {
                  osc.stop();
                } catch (e) {
                  // Already stopped
                }
              });
              
              // Stop all noise sources
              this.noiseSources.forEach(source => {
                try {
                  source.stop();
                } catch (e) {
                  // Already stopped
                }
              });
              
              // Clean up all nodes
              this.nodes = [];
              this.oscillators = [];
              this.noiseSources = [];
              this.biquadFilter = null;
              this.gainNode = null;
              this.isPlaying = false;
            }, 300);
          } else {
            this.isPlaying = false;
          }
        } catch (e) {
          console.log('Error stopping tuning noise:', e);
          this.isPlaying = false;
        }
      }
    }

    // Player class
    class RadioPlayer {
      constructor() {
        this.readyState = null;
        this.networkState = null;
        this.tuningNoise = new RadioTuningNoise();
        
        // Check status every 7 seconds
        setInterval(() => {
          if (this.audioTag()) {
            this.checkStatus();
          }
        }, 7000);
      }
      
      audioTag() {
        return document.getElementById('player');
      }
      
      play() {
        const audio = this.audioTag();
        if (audio) {
          audio.play();
        }
      }
      
      setChannel(channel, appState, shouldAutoplay = true) {
        this.readyState = null;
        this.networkState = null;
        
        if (appState) {
          appState.currentChannel = channel;
        }
        
        // Get the expected source URL
        const expectedSrc = this.getExpectedSrc(channel);
        
        // Wait for audio element to be available (it's conditionally rendered by Alpine)
        const waitForAudioAndPlay = () => {
          const audio = this.audioTag();
          
          if (!audio) {
            // Audio element not rendered yet, wait a bit and try again
            if (expectedSrc) {
              setTimeout(waitForAudioAndPlay, 50);
            }
            return;
          }
          
          // Remove any existing event listeners to prevent duplicates
          const playWhenReady = () => {
            // Don't stop tuning noise here - only stop when playback actually starts
            // Only autoplay if shouldAutoplay is true and user hasn't manually paused
            if (shouldAutoplay && (!appState || !appState.userPaused)) {
              const playPromise = audio.play();
              if (playPromise !== undefined) {
                playPromise.then(() => {
                  // Stop tuning noise only when playback actually starts
                  if (this.tuningNoise) {
                    this.tuningNoise.stop();
                  }
                }).catch(error => {
                  // Autoplay was prevented - keep noise playing
                  // User can manually click play if needed
                  console.log('Autoplay prevented:', error);
                });
              }
            }
          };
          
          // Remove old listeners if they exist
          if (this._canPlayListener) {
            audio.removeEventListener('canplay', this._canPlayListener);
          }
          if (this._loadedDataListener) {
            audio.removeEventListener('loadeddata', this._loadedDataListener);
          }
          
          // Audio element exists, now set src and play
          audio.pause();
          
          // Set volume from app state if available
          if (appState && appState.volume !== undefined) {
            audio.volume = appState.volume;
          }
          
          if (expectedSrc) {
            // Set src directly - Alpine binding will also update but we need immediate control
            // Convert to absolute URL if needed for comparison
            const currentSrc = audio.src;
            const expectedSrcAbsolute = new URL(expectedSrc, window.location.href).href;
            
            // Only change src if it's different to avoid unnecessary reloads
            if (currentSrc !== expectedSrcAbsolute && currentSrc !== expectedSrc) {
              audio.src = expectedSrc;
              audio.load();
            } else if (audio.readyState === 0) {
              // If src is already correct but not loaded, load it
              audio.load();
            }
            
            // Create event listeners for when audio is ready
            this._canPlayListener = playWhenReady;
            this._loadedDataListener = playWhenReady;
            
            // Add listeners for when audio is ready to play
            audio.addEventListener('canplay', this._canPlayListener, { once: true });
            audio.addEventListener('loadeddata', this._loadedDataListener, { once: true });
            
            // Stop tuning noise only when playback actually starts
            const playingHandler = () => {
              if (this.tuningNoise) {
                this.tuningNoise.stop();
              }
            };
            audio.addEventListener('playing', playingHandler, { once: true });
            
            // Don't stop noise on error - keep it playing if stream fails
            const errorHandler = () => {
              // Keep tuning noise playing if stream fails
              console.log('Stream error - keeping tuning noise');
            };
            audio.addEventListener('error', errorHandler, { once: true });
            
            // Try to play immediately if shouldAutoplay and user hasn't paused
            // If it fails, the event listeners will retry when ready
            // Note: tuning noise will stop on 'playing' event, not here
            if (shouldAutoplay && (!appState || !appState.userPaused)) {
              setTimeout(() => {
                const playPromise = audio.play();
                if (playPromise !== undefined) {
                  playPromise.catch(error => {
                    // Autoplay was prevented - event listeners will retry when ready
                    // Keep tuning noise playing
                    console.log('Immediate autoplay prevented, will retry when ready:', error);
                  });
                }
              }, 50);
            }
          } else {
            audio.src = '';
          }
        };
        
        // Start waiting for audio element
        waitForAudioAndPlay();
      }
      
      getExpectedSrc(channel) {
        if (!channel) return null;
        
        const channelData = channels[channel];
        if (!channelData) {
          console.warn('Channel not found:', channel);
          return null;
        }
        
        // SomaFM streams use a pattern
        if (channelData.tags && channelData.tags.indexOf('soma') > -1) {
          return 'https://ice.somafm.com/' + channel;
        }
        
        const url = channelData.url || null;
        if (!url) {
          console.warn('Channel has no URL:', channel);
        }
        return url;
      }
      
      checkStatus() {
        const audio = this.audioTag();
        if (!audio) return;
        
        const newNetworkState = audio.networkState;
        const newReadyState = audio.readyState;
        
        // Only auto-play if audio is paused, user hasn't manually paused, and we have a channel
        if (audio.paused && 
            (!window.appState || !window.appState.userPaused) &&
            window.appState?.currentChannel) {
          this.play();
        }
        
        // Check if we need to restart (stuck state)
        if (this.readyState !== null &&
            newReadyState === this.readyState &&
            [0, 1].indexOf(newReadyState) > -1 &&
            newNetworkState !== 2) {
          console.log('Restart channel');
          this.readyState = null;
          this.networkState = null;
          const currentChannel = window.appState?.currentChannel || '';
          const shouldAutoplay = !window.appState || !window.appState.userPaused;
          this.setChannel(currentChannel, window.appState, shouldAutoplay);
          if (audio && shouldAutoplay) {
            audio.load();
            if (!window.appState || !window.appState.userPaused) {
              this.play();
            }
          }
        } else {
          // Update internal state (used for restart logic)
          this.readyState = newReadyState;
          this.networkState = newNetworkState;
        }
      }
    }

    // Router - using hash-based routing for file:// compatibility
    class Router {
      constructor(player, appState) {
        this.player = player;
        this.appState = appState;
        this.init();
      }
      
      init() {
        // Handle hash changes (browser back/forward)
        window.addEventListener('hashchange', () => {
          this.handleRoute();
        });
        
        // Handle initial load
        this.handleRoute();
      }
      
      navigate(path) {
        // Convert path format (/channel or /) to hash format (#channel or #)
        const hash = path === '/' ? '#' : '#' + path.slice(1);
        // Use replace to avoid adding to history if same hash
        if (window.location.hash !== hash) {
          window.location.hash = hash;
        } else {
          // If hash is already set, just handle the route
          this.handleRoute();
        }
      }
      
      handleRoute() {
        // Get channel from hash (remove #)
        const hash = window.location.hash.slice(1); // Remove #
        const channel = hash || '';
        
        if (this.appState) {
          const previousChannel = this.appState.currentChannel;
          
          // If navigating to empty hash, pause audio
          if (!channel) {
            const audio = document.getElementById('player');
            if (audio) {
              this.appState._isProgrammaticPause = true;
              audio.pause();
              this.appState.userPaused = true;
            }
            this.appState.currentChannel = '';
            // Stop tuning noise if playing
            if (this.player && this.player.tuningNoise) {
              this.player.tuningNoise.stop();
            }
          } else if (channel !== previousChannel) {
            // Changing to a different channel - always autoplay
            this.appState.userPaused = false;
            this.appState.currentChannel = channel;
            // Start tuning noise when switching channels
            if (this.player && this.player.tuningNoise && previousChannel) {
              this.player.tuningNoise.play();
            }
            // Trigger metadata fetch (watcher should also handle this, but ensure it happens)
            if (this.appState.fetchCurrentSong) {
              setTimeout(() => this.appState.fetchCurrentSong(), 100);
            }
          } else {
            // Same channel - keep current state
            this.appState.currentChannel = channel;
          }
        }
        
        if (channel) {
          document.title = channel + ' | Radio Guaka';
          // Always autoplay when changing to a different channel (userPaused was reset above)
          const shouldAutoplay = !this.appState || !this.appState.userPaused;
          this.player.setChannel(channel, this.appState, shouldAutoplay);
        } else {
          document.title = 'Radio Guaka';
          this.player.setChannel('', this.appState, false);
        }
      }
    }

    // Helper functions
    function volChange(delta) {
      const audio = document.getElementById('player');
      if (audio) {
        const newVolume = Math.max(0, Math.min(audio.volume + delta, 1));
        audio.volume = newVolume;
        // Update Alpine.js state to keep slider in sync
        if (window.appState) {
          window.appState.volume = newVolume;
        }
      }
    }

    function nextChannel(offset, appState, router) {
      // Get channels in visual order (as they appear on screen)
      const visualOrder = [];
      
      // First, add channels that don't belong to any section
      const sectionTags = typeof sections !== 'undefined' ? sections.flatMap(s => s.tags) : [];
      const otherChannels = Object.keys(channels)
        .filter(name => {
          const channel = channels[name];
          if (!channel.tags) return true;
          return !sectionTags.some(tag => channel.tags.indexOf(tag) > -1);
        })
        .sort();
      visualOrder.push(...otherChannels);
      
      // Then add channels from each section in order
      if (typeof sections !== 'undefined') {
        const sortedSections = [...sections].sort((a, b) => a.order - b.order);
        sortedSections.forEach(section => {
          const sectionChannels = Object.keys(channels)
            .filter(name => {
              const channel = channels[name];
              if (!channel.tags) return false;
              return section.tags.some(tag => channel.tags.indexOf(tag) > -1);
            })
            .sort();
          visualOrder.push(...sectionChannels);
        });
      }
      
      // Add empty string at beginning for "silence"
      visualOrder.unshift('');
      
      const currentChannel = appState.currentChannel || '';
      const currentIndex = visualOrder.indexOf(currentChannel);
      
      let newIndex = currentIndex + offset;
      
      if (newIndex >= visualOrder.length) {
        newIndex = 0;
      }
      if (newIndex < 0) {
        newIndex = visualOrder.length - 1;
      }
      
      const newChannel = visualOrder[newIndex];
      if (newChannel === '') {
        router.navigate('/');
      } else {
        router.navigate('/' + newChannel);
      }
    }

    // Alpine.js app
    function radioApp() {
      return {
        currentChannel: '',
        /** Bumped when channels.js finishes merging SomaFM artwork (Alpine refresh). */
        somaArtEpoch: 0,
        isPlaying: false,
        currentTime: 0,
        duration: 0,
        volume: 1,
        userPaused: false,
        _isProgrammaticPause: false,
        currentSong: null,
        currentSongError: null,
        isChatVisible: false, // Hidden by default on mobile, visible on desktop
        chatIframeReady: false,
        chatIframeCacheBuster: Date.now(), // Cache-busting timestamp set once per page load
        
        get chatIframeSrc() {
          // Add cache-busting parameter to ensure iframe always loads latest version on page refresh
          return `chatiframe1.html?t=${this.chatIframeCacheBuster}`;
        },
        
        // Build timestamp from buildtime.js (injected by Jekyll)
        // Falls back to current time locally (when Jekyll tag isn't processed)
        get buildDate() {
          if (typeof BUILD_TIME !== 'undefined' && /^\d/.test(BUILD_TIME)) {
            return BUILD_TIME;
          }
          const now = new Date();
          const year = now.getFullYear().toString().slice(-2);
          const month = (now.getMonth() + 1).toString().padStart(2, '0');
          const day = now.getDate().toString().padStart(2, '0');
          const hours = now.getHours().toString().padStart(2, '0');
          const minutes = now.getMinutes().toString().padStart(2, '0');
          return `${year}${month}${day} ${hours}:${minutes}`;
        },
        
        init() {
          // Make appState available globally for app.js
          window.appState = this;

          window.addEventListener('radioguaka-somafm-artwork', () => {
            this.somaArtEpoch++;
          });
          
          // Set chat visibility based on screen size
          if (window.innerWidth >= 769) {
            this.isChatVisible = true; // Show on desktop
          } else {
            this.isChatVisible = false; // Hide on mobile
          }
          
          // Update on resize
          window.addEventListener('resize', () => {
            if (window.innerWidth >= 769) {
              this.isChatVisible = true;
            }
          });
          
          console.log('Loaded channels:', Object.keys(channels).length);
          
          // Set up audio event listeners
          const setupAudioListeners = () => {
            const audio = document.getElementById('player');
            if (!audio) {
              setTimeout(setupAudioListeners, 50);
              return;
            }
            
            // Remove old listeners if they exist
            if (this._timeUpdateListener) {
              audio.removeEventListener('timeupdate', this._timeUpdateListener);
            }
            if (this._playListener) {
              audio.removeEventListener('play', this._playListener);
            }
            if (this._pauseListener) {
              audio.removeEventListener('pause', this._pauseListener);
            }
            
            // Update time display
            this._timeUpdateListener = () => {
              this.currentTime = audio.currentTime || 0;
            };
            audio.addEventListener('timeupdate', this._timeUpdateListener);
            
            // Update duration when metadata loads
            audio.addEventListener('loadedmetadata', () => {
              this.duration = audio.duration;
            });
            
            // Update duration when duration changes
            audio.addEventListener('durationchange', () => {
              this.duration = audio.duration;
            });
            
            // Try to listen for ICY metadata updates (non-standard, browser-dependent)
            // Some browsers may fire metadataupdate events for Icecast streams
            if ('onmetadataupdate' in audio || 'addEventListener' in audio) {
              const metadataHandler = (e) => {
                // Try to extract metadata from event or audio element
                if (e && e.metadata) {
                  const metadata = e.metadata;
                  if (metadata.StreamTitle) {
                    const parts = metadata.StreamTitle.split(' - ');
                    if (parts.length >= 2) {
                      this.currentSong = {
                        artist: parts[0].trim(),
                        title: parts.slice(1).join(' - ').trim(),
                        album: ''
                      };
                    } else if (parts.length === 1) {
                      this.currentSong = {
                        artist: '',
                        title: parts[0].trim(),
                        album: ''
                      };
                    }
                  }
                }
              };
              // Try both standard and non-standard event names
              audio.addEventListener('metadataupdate', metadataHandler);
              audio.addEventListener('icy-metadata', metadataHandler);
            }
            
            // Update playing state
            this._playListener = () => {
              this.isPlaying = true;
              // Fetch metadata when playback starts
              if (this.currentChannel) {
                this.fetchCurrentSong();
              }
            };
            this._pauseListener = () => {
              this.isPlaying = false;
              // If pause was not programmatic (e.g., user paused via AirPods), set userPaused
              if (!this._isProgrammaticPause) {
                this.userPaused = true;
              }
              // Reset the flag after handling the pause event
              this._isProgrammaticPause = false;
            };
            audio.addEventListener('play', this._playListener);
            audio.addEventListener('pause', this._pauseListener);
            
            // Set initial volume
            audio.volume = this.volume;
            
            // Initial time update
            this.currentTime = audio.currentTime || 0;
          };
          
          setupAudioListeners();
          
          // Re-setup listeners when srcUrl changes (audio element might be recreated)
          this.$watch('srcUrl', () => {
            setTimeout(setupAudioListeners, 100);
          });
          
          // Wait for router to be initialized by app.js
          const checkRouter = () => {
            if (window.router) {
              window.router.handleRoute();
            } else {
              setTimeout(checkRouter, 50);
            }
          };
          checkRouter();
          
          // Set up metadata polling
          this.startMetadataPolling();
          
          // Set up Media Session API for lock screen/Bluetooth/car stereo controls
          this.setupMediaSession();
          this.updateMediaSessionMetadata();
          
          // Watch for channel changes to restart polling
          this.$watch('currentChannel', () => {
            this.currentSong = null;
            this.currentSongError = null;
            this.startMetadataPolling();
          });
          
          // Watch for play/pause to start/stop polling
          this.$watch('isPlaying', () => {
            if (this.isPlaying) {
              this.startMetadataPolling();
            } else {
              this.stopMetadataPolling();
            }
          });
          
          // Watch for channel changes and notify chat iframe
          this.$watch('currentChannel', () => {
            this.sendChannelToChat();
            this.updateMediaSessionMetadata();
          });
          
          // Watch for song changes to update media session metadata (lock screen, car stereo)
          this.$watch('currentSong', () => {
            this.updateMediaSessionMetadata();
          });
          
          // Set up postMessage listener for chat
          window.addEventListener('message', (event) => {
            try {
              if (event.data.type === 'chatReady') {
                this.chatIframeReady = true;
                this.sendChannelToChat();
              } else if (event.data.type === 'tuneToChannel' && event.data.channel) {
                // User clicked a channel tag in chat - tune to that channel
                this.navigateTo('#' + event.data.channel);
              }
            } catch (e) {
              // Silently ignore chat errors - don't affect radio
              console.log('Chat message error (non-critical):', e);
            }
          });
        },
        
        toggleChat() {
          this.isChatVisible = !this.isChatVisible;
        },
        
        onChatIframeLoad() {
          // Iframe loaded, wait a bit then send channel
          setTimeout(() => {
            this.sendChannelToChat();
          }, 500);
        },
        
        sendChannelToChat() {
          try {
            const iframe = this.$refs.chatIframe;
            if (iframe && iframe.contentWindow) {
              iframe.contentWindow.postMessage({
                type: 'channelChange',
                channel: this.currentChannel ? this.currentChannel.replace(/_/g, ' ') : ''
              }, '*');
            }
          } catch (e) {
            // Silently ignore - chat errors shouldn't affect radio
            console.log('Chat communication error (non-critical):', e);
          }
        },
        
        sendSongToChat() {
          try {
            if (!this.currentSong) return;
            
            const iframe = this.$refs.chatIframe;
            if (iframe && iframe.contentWindow) {
              const songText = this.currentSong.artist && this.currentSong.title 
                ? `${this.currentSong.artist} - ${this.currentSong.title}`
                : (this.currentSong.title || '');
              
              if (songText) {
                iframe.contentWindow.postMessage({
                  type: 'addSongToMessage',
                  song: songText
                }, '*');
              }
            }
          } catch (e) {
            console.log('Chat communication error (non-critical):', e);
          }
        },
        
        likeSong() {
          try {
            if (!this.currentSong) return;
            
            const iframe = this.$refs.chatIframe;
            if (iframe && iframe.contentWindow) {
              const songText = this.currentSong.artist && this.currentSong.title 
                ? `${this.currentSong.artist} - ${this.currentSong.title}`
                : (this.currentSong.title || '');
              
              if (songText) {
                iframe.contentWindow.postMessage({
                  type: 'likeSong',
                  song: songText
                }, '*');
              }
            }
          } catch (e) {
            console.log('Chat communication error (non-critical):', e);
          }
        },
        
        setupMediaSession() {
          if (!('mediaSession' in navigator)) return;
          
          // Set action handlers for media controls (lock screen, Bluetooth, car stereo)
          navigator.mediaSession.setActionHandler('play', () => {
            this.togglePlay();
          });
          navigator.mediaSession.setActionHandler('pause', () => {
            this.togglePlay();
          });
          navigator.mediaSession.setActionHandler('previoustrack', () => {
            nextChannel(-1, this, window.router);
          });
          navigator.mediaSession.setActionHandler('nexttrack', () => {
            nextChannel(1, this, window.router);
          });
          navigator.mediaSession.setActionHandler('stop', () => {
            const audio = document.getElementById('player');
            if (audio) {
              audio.pause();
              audio.currentTime = 0;
            }
          });
        },
        
        updateMediaSessionMetadata() {
          if (!('mediaSession' in navigator)) return;
          
          const channelName = this.currentChannel 
            ? this.currentChannel.replace(/_/g, ' ') 
            : 'Radio Guaka';
          
          const artist = this.currentSong?.artist || '';
          const title = this.currentSong?.title || channelName;
          const album = this.currentSong?.album || 'Radio Guaka';
          
          const channel = this.currentChannel && typeof channels !== 'undefined'
            ? channels[this.currentChannel]
            : null;
          let artwork = channel ? this.channelArtworkForMediaSession(channel) : null;
          if (!artwork || !artwork.length) {
            artwork = [{
              src: this.resolveMediaImageUrl('og-image.png'),
              sizes: '512x512',
              type: 'image/png'
            }];
          }
          
          navigator.mediaSession.metadata = new MediaMetadata({
            title: title,
            artist: artist,
            album: album,
            artwork: artwork
          });
        },
        
        startMetadataPolling() {
          // Clear any existing interval
          this.stopMetadataPolling();
          
          // Only poll if we have a channel
          if (!this.currentChannel) {
            return;
          }
          
          // Fetch immediately (even if not playing yet)
          this.fetchCurrentSong();
          
          // Then poll every 12 seconds (only if playing)
          this._metadataInterval = setInterval(() => {
            if (this.currentChannel && this.isPlaying) {
              this.fetchCurrentSong();
            } else if (!this.currentChannel) {
              this.stopMetadataPolling();
            }
          }, 12000);
        },
        
        stopMetadataPolling() {
          if (this._metadataInterval) {
            clearInterval(this._metadataInterval);
            this._metadataInterval = null;
          }
        },
        
        togglePlay() {
          const audio = document.getElementById('player');
          if (!audio) return;
          
          if (audio.paused) {
            this.userPaused = false;
            audio.play();
          } else {
            this._isProgrammaticPause = true;
            this.userPaused = true;
            audio.pause();
          }
        },
        
        formatTime(seconds) {
          if (!isFinite(seconds) || isNaN(seconds)) return '0:00';
          const mins = Math.floor(seconds / 60);
          const secs = Math.floor(seconds % 60);
          return `${mins}:${secs.toString().padStart(2, '0')}`;
        },
        
        get hasValidDuration() {
          return isFinite(this.duration) && this.duration > 0;
        },
        
        setVolume(value) {
          const audio = document.getElementById('player');
          if (audio) {
            this.volume = parseFloat(value);
            audio.volume = this.volume;
          }
        },
        
        navigateTo(hash) {
          // Extract channel name from hash
          const channelName = hash === '#' ? '' : hash.slice(1);
          
          // If clicking the same channel, toggle play/pause instead of navigating
          if (channelName === this.currentChannel) {
            this.togglePlay();
            return;
          }
          
          // If changing to a different channel, reset userPaused and navigate
          this.userPaused = false;
          
          // Directly set hash - works with both file:// and http://
          // This will trigger hashchange event which calls router.handleRoute()
          if (window.location.hash !== hash) {
            window.location.hash = hash;
          } else {
            // If hash is already set, manually trigger route handling
            // Wait a bit to ensure router is ready, then handle route
            const handleRouteNow = () => {
              if (window.router) {
                window.router.handleRoute();
              } else {
                // Router not ready yet, try again
                setTimeout(handleRouteNow, 50);
              }
            };
            handleRouteNow();
          }
        },
        
        get sortedChannels() {
          if (typeof channels === 'undefined') {
            console.error('channels is not defined');
            return [];
          }
          return Object.keys(channels)
            .sort()
            .map(name => ({
              name: name,
              playing: name === this.currentChannel
            }));
        },
        
        get somaChannels() {
          if (typeof channels === 'undefined') {
            console.error('channels is not defined');
            return [];
          }
          return Object.keys(channels)
            .filter(name => {
              const channel = channels[name];
              return channel.tags && channel.tags.indexOf('soma') > -1;
            })
            .sort()
            .map(name => ({
              name: name,
              playing: name === this.currentChannel
            }));
        },
        
        get fipChannels() {
          if (typeof channels === 'undefined') {
            console.error('channels is not defined');
            return [];
          }
          return Object.keys(channels)
            .filter(name => {
              const channel = channels[name];
              return channel.tags && channel.tags.indexOf('fip') > -1;
            })
            .sort()
            .map(name => ({
              name: name,
              playing: name === this.currentChannel
            }));
        },
        
        get fluxFmChannels() {
          if (typeof channels === 'undefined') {
            console.error('channels is not defined');
            return [];
          }
          return Object.keys(channels)
            .filter(name => {
              const channel = channels[name];
              return channel.tags && channel.tags.indexOf('flux') > -1;
            })
            .sort()
            .map(name => ({
              name: name,
              playing: name === this.currentChannel
            }));
        },
        
        get otherStationsChannels() {
          if (typeof channels === 'undefined') {
            console.error('channels is not defined');
            return [];
          }
          return Object.keys(channels)
            .filter(name => {
              const channel = channels[name];
              return channel.tags && channel.tags.indexOf('other') > -1;
            })
            .sort()
            .map(name => ({
              name: name,
              playing: name === this.currentChannel
            }));
        },
        
        get paradiseChannels() {
          if (typeof channels === 'undefined') {
            console.error('channels is not defined');
            return [];
          }
          return Object.keys(channels)
            .filter(name => {
              const channel = channels[name];
              return channel.tags && channel.tags.indexOf('paradise') > -1;
            })
            .sort()
            .map(name => ({
              name: name,
              playing: name === this.currentChannel
            }));
        },
        
        get otherChannels() {
          if (typeof channels === 'undefined') {
            console.error('channels is not defined');
            return [];
          }
          // Channels that don't belong to any defined section
          const sectionTags = typeof sections !== 'undefined' ? sections.flatMap(s => s.tags) : [];
          return Object.keys(channels)
            .filter(name => {
              const channel = channels[name];
              if (!channel.tags) return true;
              // Check if channel belongs to any section
              return !sectionTags.some(tag => channel.tags.indexOf(tag) > -1);
            })
            .sort()
            .map(name => ({
              name: name,
              playing: name === this.currentChannel
            }));
        },
        
        get sortedSections() {
          if (typeof sections === 'undefined') {
            return [];
          }
          return [...sections].sort((a, b) => a.order - b.order);
        },
        
        getSectionChannels(section) {
          if (typeof channels === 'undefined' || typeof section === 'undefined') {
            return [];
          }
          return Object.keys(channels)
            .filter(name => {
              const channel = channels[name];
              if (!channel.tags) return false;
              // Channel must have at least one tag matching the section
              return section.tags.some(tag => channel.tags.indexOf(tag) > -1);
            })
            .sort()
            .map(name => ({
              name: name,
              playing: name === this.currentChannel
            }));
        },
        
        formatChannelName(channelName, sectionName) {
          // Special formatting for different sections
          if (sectionName === 'Radio Paradise') {
            return channelName === 'paradise' ? 'paradise' : channelName.replace(/^paradise_?/, '').replace(/_/g, ' ');
          }
          if (sectionName === 'Flux FM') {
            return channelName.replace(/^flux_fm_/, '').replace(/_/g, ' ');
          }
          // Default: replace underscores with spaces
          return channelName.replace(/_/g, ' ');
        },
        
        get currentChannelData() {
          if (!this.currentChannel) return null;
          
          const channel = channels[this.currentChannel];
          if (!channel) return null;
          
          const data = { ...channel, name: this.currentChannel };
          
          // Set SomaFM metadata if needed
          if (channel.tags && channel.tags.indexOf('soma') > -1) {
            data.site = 'https://somafm.com/';
          }
          
          // FIP channels already have site URLs in their channel data
          
          return data;
        },

        resolveMediaImageUrl(src) {
          if (!src || typeof src !== 'string') return '';
          const s = src.trim();
          if (!s) return '';
          if (/^(https?:|data:)/i.test(s)) return s;
          try {
            return new URL(s, window.location.href).href;
          } catch (_) {
            return s;
          }
        },

        get currentChannelArtUrl() {
          void this.somaArtEpoch;
          if (!this.currentChannel || typeof channels === 'undefined') return '';
          const ch = channels[this.currentChannel];
          if (!ch) return '';
          let raw = '';
          if (typeof ch.artwork === 'string' && ch.artwork.trim()) raw = ch.artwork.trim();
          else if (Array.isArray(ch.artwork) && ch.artwork[0] && typeof ch.artwork[0].src === 'string') {
            raw = ch.artwork[0].src.trim();
          } else if (ch.img) raw = ch.img;
          return raw ? this.resolveMediaImageUrl(raw) : '';
        },

        channelArtworkForMediaSession(channel) {
          if (!channel) return null;
          if (Array.isArray(channel.artwork) && channel.artwork.length) {
            const list = channel.artwork
              .map((item) => {
                if (typeof item === 'string') {
                  const src = this.resolveMediaImageUrl(item);
                  return src ? { src } : null;
                }
                if (item && typeof item.src === 'string') {
                  const src = this.resolveMediaImageUrl(item.src);
                  if (!src) return null;
                  const out = { src };
                  if (item.sizes) out.sizes = item.sizes;
                  if (item.type) out.type = item.type;
                  return out;
                }
                return null;
              })
              .filter(Boolean);
            return list.length ? list : null;
          }
          if (typeof channel.artwork === 'string' && channel.artwork.trim()) {
            const src = this.resolveMediaImageUrl(channel.artwork);
            return src ? [{ src, sizes: '512x512' }] : null;
          }
          if (channel.img) {
            const src = this.resolveMediaImageUrl(channel.img);
            return src ? [{ src, sizes: '512x512' }] : null;
          }
          return null;
        },
        
        get srcUrl() {
          if (!this.currentChannel) return null;
          
          if (typeof channels === 'undefined') {
            console.error('channels is not defined');
            return null;
          }
          
          const channel = channels[this.currentChannel];
          if (!channel) return null;
          
          // SomaFM streams use a pattern
          if (channel.tags && channel.tags.indexOf('soma') > -1) {
            return 'https://ice.somafm.com/' + this.currentChannel;
          }
          
          return channel.url || null;
        },
        
        async fetchRadioParadiseMetadata() {
          if (!this.currentChannel) return null;
          
          const channel = channels[this.currentChannel];
          if (!channel) return null;
          
          // Map channel names to API chan parameter
          // Based on https://api.radioparadise.com/api/list_chan
          const chanMap = {
            'paradise': 0,        // Main Mix
            'paradise_mellow': 1, // Mellow Mix
            'paradise_rock': 2,   // RockIt!
            'paradise_global': 3, // Globalized (World)
            'paradise_beyond': 5, // Beyond...
            'paradise_serenity': 4, // Serenity (experimental)
            'paradise_2050': 0    // Radio 2050 (fallback to main)
          };
          
          const chan = chanMap[this.currentChannel];
          if (chan === undefined) return null;
          
          // Use the now_playing endpoint which returns a simple object
          const apiUrl = `https://api.radioparadise.com/api/now_playing?chan=${chan}`;
          
          // Radio Paradise API doesn't have CORS headers, so we need to use a proxy
          const proxyUrl = `https://api.allorigins.win/raw?url=${encodeURIComponent(apiUrl)}`;
          const proxyUrls = [proxyUrl];
          
          for (const proxyUrl of proxyUrls) {
            try {
              const response = await fetch(proxyUrl);
              if (response.ok) {
                const data = await response.json();
                if (data && data.artist && data.title) {
                  return {
                    artist: data.artist,
                    title: data.title,
                    album: data.album || ''
                  };
                }
              }
            } catch (error) {
              // Try next proxy
              continue;
            }
          }
          
          console.log('Radio Paradise metadata unavailable (all proxies failed)');
          return null;
        },
        
        async fetchSomaFMMetadata() {
          if (!this.currentChannel) return null;
          
          try {
            const response = await fetch('https://api.somafm.com/channels.json');
            if (!response.ok) {
              return null;
            }
            
            const data = await response.json();
            if (data && data.channels) {
              const channel = data.channels.find(ch => ch.id === this.currentChannel);
              if (channel) {
                // Try current_track first
                if (channel.current_track) {
                  const track = channel.current_track;
                  // If current_track is a string like "Artist - Title"
                  if (typeof track === 'string') {
                    const parts = track.split(' - ');
                    if (parts.length >= 2) {
                      return {
                        artist: parts[0].trim(),
                        title: parts.slice(1).join(' - ').trim(),
                        album: ''
                      };
                    } else if (parts.length === 1) {
                      return {
                        artist: '',
                        title: parts[0].trim(),
                        album: ''
                      };
                    }
                  }
                  // If current_track is an object
                  else if (track.title) {
                    return {
                      artist: track.artist || '',
                      title: track.title,
                      album: track.album || ''
                    };
                  }
                }
                // Try lastPlaying as fallback (can be string or object)
                if (channel.lastPlaying) {
                  const track = channel.lastPlaying;
                  
                  // If lastPlaying is a string like "Artist - Title"
                  if (typeof track === 'string') {
                    const parts = track.split(' - ');
                    if (parts.length >= 2) {
                      return {
                        artist: parts[0].trim(),
                        title: parts.slice(1).join(' - ').trim(),
                        album: ''
                      };
                    } else if (parts.length === 1) {
                      return {
                        artist: '',
                        title: parts[0].trim(),
                        album: ''
                      };
                    }
                  }
                  // If lastPlaying is an object
                  else if (track.title) {
                    return {
                      artist: track.artist || '',
                      title: track.title,
                      album: track.album || ''
                    };
                  }
                }
              }
            }
          } catch (error) {
            console.log('SomaFM metadata fetch error:', error);
          }
          return null;
        },
        
        async fetchFIPMetadata() {
          if (!this.currentChannel) return null;
          
          // Map channel names to station IDs
          // These IDs are used by the Radio France livemeta API
          const stationIdMap = {
            'fip': 7,
            'fip_jazz': 65,           // Jazz (Wayne Shorter, etc.)
            'fip_rock': 64,           // Rock (AC/DC, etc.)
            'fip_groove': 66,         // Groove/Funk/Soul
            'fip_reggae': 71,         // Reggae
            'fip_pop': 7,             // Fallback to main (no dedicated API)
            'fip_electro': 74,        // Electro
            'fip_monde': 69,          // World music
            'fip_nouveautes': 70,     // New releases
            'fip_metal': 77,          // Metal
            'fip_hiphop': 7,          // Fallback to main (no dedicated API)
            'fip_sacre_francais': 7   // Fallback to main (no dedicated API)
          };
          
          const stationId = stationIdMap[this.currentChannel];
          if (!stationId) return null;
          
          try {
            const response = await fetch(`https://api.radiofrance.fr/livemeta/pull/${stationId}`);
            if (!response.ok) return null;
            
            const data = await response.json();
            // The API returns steps as an object with UUIDs as keys, not an array
            if (data && data.steps && typeof data.steps === 'object') {
              const now = Math.floor(Date.now() / 1000); // Current time in Unix seconds
              const stepsArray = Object.values(data.steps);
              
              // Find the current track where now is between start and end (Unix timestamps)
              const currentTrack = stepsArray.find(step => {
                return step.start && step.end && now >= step.start && now < step.end;
              });
              
              if (currentTrack && currentTrack.title) {
                return {
                  artist: currentTrack.authors || currentTrack.performers || '',
                  title: currentTrack.title,
                  album: currentTrack.titreAlbum || ''
                };
              }
              
              // Fallback: if no current track found, return the most recent one
              if (stepsArray.length > 0) {
                // Sort by start time descending and get the most recent
                const sorted = stepsArray.sort((a, b) => (b.start || 0) - (a.start || 0));
                const mostRecent = sorted[0];
                if (mostRecent && mostRecent.title) {
                  return {
                    artist: mostRecent.authors || mostRecent.performers || '',
                    title: mostRecent.title,
                    album: mostRecent.titreAlbum || ''
                  };
                }
              }
            }
          } catch (error) {
            console.log('FIP metadata fetch error:', error);
          }
          return null;
        },
        
        async fetchFluxFMMetadata() {
          if (!this.currentChannel) return null;
          
          // Flux FM streams are Icecast-based
          // Try to extract ICY metadata from the audio element if available
          // HTML5 audio element may expose metadata via metadataupdate event in some browsers
          const audio = document.getElementById('player');
          if (audio && audio.icyMetadata) {
            // Some browsers expose icyMetadata property
            const metadata = audio.icyMetadata;
            if (metadata && metadata.StreamTitle) {
              const parts = metadata.StreamTitle.split(' - ');
              if (parts.length >= 2) {
                return {
                  artist: parts[0].trim(),
                  title: parts.slice(1).join(' - ').trim(),
                  album: ''
                };
              } else if (parts.length === 1) {
                return {
                  artist: '',
                  title: parts[0].trim(),
                  album: ''
                };
              }
            }
          }
          
          // Fallback: Try to extract from stream URL via proxy (complex, may not work due to CORS)
          // For now, return null - ICY metadata extraction requires server-side support
          return null;
        },
        
        async fetchConcertzenderMetadata() {
          if (!this.currentChannel) return null;
          
          // Concertzender streams are Icecast-based
          // Try to extract ICY metadata from the audio element if available
          const audio = document.getElementById('player');
          if (audio && audio.icyMetadata) {
            const metadata = audio.icyMetadata;
            if (metadata && metadata.StreamTitle) {
              const parts = metadata.StreamTitle.split(' - ');
              if (parts.length >= 2) {
                return {
                  artist: parts[0].trim(),
                  title: parts.slice(1).join(' - ').trim(),
                  album: ''
                };
              } else if (parts.length === 1) {
                return {
                  artist: '',
                  title: parts[0].trim(),
                  album: ''
                };
              }
            }
          }
          
          // Fallback: Try to extract from stream URL via proxy (complex, may not work due to CORS)
          // For now, return null - ICY metadata extraction requires server-side support
          return null;
        },
        
        async fetchSteroscenicMetadata() {
          if (!this.currentChannel) return null;
          
          // Map channel names to stream paths in the statistics API
          const streamPathMap = {
            'ambientsleepingpill': '/asp-s',
            'amambient': '/ama-s',
            'ambientmodern': '/mod-s'
          };
          
          const streamPath = streamPathMap[this.currentChannel];
          if (!streamPath) return null;
          
          // Stereoscenic API doesn't have CORS headers, so we use proxies
          const apiUrl = 'https://radio.stereoscenic.com/statistics?json=1';
          const proxyUrls = [
            `https://api.allorigins.win/raw?url=${encodeURIComponent(apiUrl)}`,
            `https://corsproxy.io/?${encodeURIComponent(apiUrl)}`
          ];
          
          for (const proxyUrl of proxyUrls) {
            try {
              const response = await fetch(proxyUrl, { 
                signal: AbortSignal.timeout(5000) // 5 second timeout
              });
              if (response.ok) {
                const data = await response.json();
                if (data && data.streams) {
                  // Find the stream matching our channel
                  const stream = data.streams.find(s => s.streampath === streamPath);
                  if (stream && stream.songtitle) {
                    // songtitle format is "Artist - Title"
                    const parts = stream.songtitle.split(' - ');
                    if (parts.length >= 2) {
                      return {
                        artist: parts[0].trim(),
                        title: parts.slice(1).join(' - ').trim(),
                        album: ''
                      };
                    } else if (parts.length === 1) {
                      return {
                        artist: '',
                        title: parts[0].trim(),
                        album: ''
                      };
                    }
                  }
                }
              }
            } catch (error) {
              // Try next proxy
              continue;
            }
          }
          return null;
        },
        
        async fetchCurrentSong() {
          if (!this.currentChannel) {
            this.currentSong = null;
            return;
          }
          
          const channel = channels[this.currentChannel];
          if (!channel || !channel.tags) {
            this.currentSong = null;
            return;
          }
          
          let songData = null;
          
          // Check which API to use based on tags or channel name
          if (channel.tags.indexOf('paradise') > -1) {
            songData = await this.fetchRadioParadiseMetadata();
          } else if (channel.tags.indexOf('soma') > -1) {
            songData = await this.fetchSomaFMMetadata();
          } else if (channel.tags.indexOf('fip') > -1) {
            songData = await this.fetchFIPMetadata();
          } else if (channel.tags.indexOf('flux') > -1) {
            songData = await this.fetchFluxFMMetadata();
          } else if (channel.tags.indexOf('concertzender') > -1) {
            songData = await this.fetchConcertzenderMetadata();
          } else if (['ambientsleepingpill', 'amambient', 'ambientmodern'].indexOf(this.currentChannel) > -1) {
            songData = await this.fetchSteroscenicMetadata();
          }
          
          this.currentSong = songData;
          this.currentSongError = songData ? null : 'No metadata available';
        }
      };
    }

    // Initialize app when DOM is ready
    document.addEventListener('DOMContentLoaded', () => {
      // Wait for Alpine.js to initialize appState
      const initApp = () => {
        if (!window.appState) {
          setTimeout(initApp, 50);
          return;
        }
        
        // Initialize player
        const player = new RadioPlayer();
        window.radioPlayer = player;
        
        // Initialize router
        const router = new Router(player, window.appState);
        window.router = router;
        
        // Keyboard shortcuts
        document.body.addEventListener('keydown', (e) => {
          // Letter key to jump to channel (only if not typing in an input field)
          const activeElement = document.activeElement;
          const isInputField = activeElement && (
            activeElement.tagName === 'INPUT' ||
            activeElement.tagName === 'TEXTAREA' ||
            activeElement.isContentEditable
          );
          
          if (!isInputField && e.key && e.key.length === 1 && /[a-z]/i.test(e.key) && !e.ctrlKey && !e.altKey && !e.metaKey) {
            e.preventDefault();
            const char = e.key.toLowerCase();
            const channelKeys = Object.keys(channels).sort();
            const matchingChannels = channelKeys.filter(channel => channel[0] === char);
            
            if (matchingChannels.length === 0) {
              return;
            }
            
            const currentChannel = window.appState?.currentChannel || '';
            
            // If current channel starts with this letter, find the next one
            if (currentChannel && currentChannel[0] === char) {
              const currentIndex = matchingChannels.indexOf(currentChannel);
              if (currentIndex !== -1) {
                // Go to next channel, wrapping around if at the end
                const nextIndex = (currentIndex + 1) % matchingChannels.length;
                router.navigate('/' + matchingChannels[nextIndex]);
              } else {
                // Current channel not in list (shouldn't happen), go to first
                router.navigate('/' + matchingChannels[0]);
              }
            } else {
              // Current channel doesn't start with this letter, go to first match
              router.navigate('/' + matchingChannels[0]);
            }
            return;
          }
          
          switch (e.keyCode) {
            case 39: // Right arrow
              e.preventDefault();
              nextChannel(1, window.appState, router);
              break;
            case 37: // Left arrow
              e.preventDefault();
              nextChannel(-1, window.appState, router);
              break;
            case 38: // Up arrow
              e.preventDefault();
              volChange(0.1);
              break;
            case 40: // Down arrow
              e.preventDefault();
              volChange(-0.1);
              break;
            case 32: // Space - toggle play/pause
              e.preventDefault();
              if (window.appState) {
                window.appState.togglePlay();
              }
              break;
          }
        });
      };
      
      initApp();
    });
  
