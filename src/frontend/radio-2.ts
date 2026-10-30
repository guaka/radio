    // Load build time (Jekyll-processed on GH Pages, may fail locally)
    window.BUILD_TIME = '';
    fetch('/js/buildtime.js')
      .then(r => r.text())
      .then(text => {
        // Extract the BUILD_TIME value from the processed JS
        const match = text.match(/BUILD_TIME\s*=\s*'([^']+)'/);
        if (match && /^\d/.test(match[1])) {
          window.BUILD_TIME = match[1];
        }
      })
      .catch(() => {});
  
