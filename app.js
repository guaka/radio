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

// Player class - converted from mtrPlayer.coffee
class RadioPlayer {
  constructor() {
    this.readyState = null;
    this.networkState = null;
    
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
  
  setChannel(channel, appState) {
    this.readyState = null;
    this.networkState = null;
    
    if (appState) {
      appState.currentChannel = channel;
    }
    
    const audio = this.audioTag();
    if (audio) {
      audio.pause();
    }
    
    setTimeout(() => {
      if (audio) {
        audio.load();
        this.play();
      }
    }, 10);
  }
  
  checkStatus() {
    const audio = this.audioTag();
    if (!audio) return;
    
    const newNetworkState = audio.networkState;
    const newReadyState = audio.readyState;
    this.play();
    
    // Check if we need to restart (stuck state)
    if (this.readyState !== null &&
        newReadyState === this.readyState &&
        [0, 1].indexOf(newReadyState) > -1 &&
        newNetworkState !== 2) {
      console.log('Restart channel');
      this.readyState = null;
      this.networkState = null;
      const currentChannel = window.appState?.currentChannel || '';
      this.setChannel(currentChannel, window.appState);
      if (audio) {
        audio.load();
        this.play();
      }
    } else {
      // Update status display
      const readyStateEl = document.getElementById('readyState');
      const networkStateEl = document.getElementById('networkState');
      
      if (newReadyState !== null && readyStateEl) {
        readyStateEl.textContent = readyStates[newReadyState] || '';
      }
      if (newNetworkState !== null && networkStateEl) {
        networkStateEl.textContent = networkStates[newNetworkState] || '';
      }
      
      this.readyState = newReadyState;
      this.networkState = newNetworkState;
    }
  }
}

// Router - converted from router.coffee
class Router {
  constructor(player, appState) {
    this.player = player;
    this.appState = appState;
    this.init();
  }
  
  init() {
    // Handle browser back/forward
    window.addEventListener('popstate', (e) => {
      this.handleRoute();
    });
    
    // Check for redirect from 404.html
    const redirectPath = sessionStorage.getItem('redirectPath');
    if (redirectPath) {
      sessionStorage.removeItem('redirectPath');
      window.history.replaceState({}, '', redirectPath);
    }
    
    // Handle initial load
    this.handleRoute();
  }
  
  navigate(path) {
    window.history.pushState({}, '', path);
    this.handleRoute();
  }
  
  handleRoute() {
    const path = window.location.pathname;
    const channel = path === '/' ? '' : path.slice(1);
    
    if (this.appState) {
      this.appState.currentChannel = channel;
    }
    
    if (channel) {
      document.title = channel + ' | Radio Guaka';
      this.player.setChannel(channel, this.appState);
    } else {
      document.title = 'Radio Guaka';
      this.player.setChannel('', this.appState);
    }
  }
}

// Helper functions
function volChange(delta) {
  const audio = document.getElementById('player');
  if (audio) {
    audio.volume = Math.max(0, Math.min(audio.volume + delta, 1));
  }
}

function nextChannel(offset, appState, router) {
  const channelKeys = Object.keys(channels).sort();
  channelKeys.unshift(''); // Silence at beginning
  
  const currentIndex = channelKeys.indexOf(appState.currentChannel || '');
  let newIndex = currentIndex + offset;
  
  if (newIndex >= channelKeys.length) {
    newIndex = 0;
  }
  if (newIndex < 0) {
    newIndex = channelKeys.length - 1;
  }
  
  const newChannel = channelKeys[newIndex];
  if (newChannel === '') {
    router.navigate('/');
  } else {
    router.navigate('/' + newChannel);
  }
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
      switch (e.keyCode) {
        case 39: // Right arrow
          nextChannel(1, window.appState, router);
          break;
        case 37: // Left arrow
          nextChannel(-1, window.appState, router);
          break;
        case 38: // Up arrow
          volChange(0.1);
          break;
        case 40: // Down arrow
          volChange(-0.1);
          break;
        case 32: // Space
          e.preventDefault();
          router.navigate('/');
          break;
        default:
          // Alt + letter to jump to channel
          if (e.keyCode >= 65 && e.keyCode <= 90 && e.altKey) {
            const char = String.fromCharCode(e.keyCode + 32);
            const channelKeys = Object.keys(channels).sort();
            for (const channel of channelKeys) {
              if (channel[0] === char) {
                router.navigate('/' + channel);
                break;
              }
            }
          }
      }
    });
  };
  
  initApp();
});

