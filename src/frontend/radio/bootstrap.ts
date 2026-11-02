    // Block rendering until fonts are loaded - must run immediately
    (function() {
      function showPage() {
        document.documentElement.style.visibility = 'visible';
        document.documentElement.style.opacity = '1';
      }
      
      if ('fonts' in document) {
        // Wait for fonts to be ready
        if (document.fonts.ready) {
          document.fonts.ready.then(function() {
            // Ensure fonts are actually loaded
            Promise.all([
              document.fonts.load('400 1em "Share Tech Mono"'),
              document.fonts.load('700 1em "Orbitron"'),
              document.fonts.load('400 1em "Orbitron"'),
              document.fonts.load('900 1em "Orbitron"')
            ]).then(showPage).catch(function() {
              setTimeout(showPage, 300);
            });
          }).catch(function() {
            setTimeout(showPage, 300);
          });
        } else {
          Promise.all([
            document.fonts.load('400 1em "Share Tech Mono"'),
            document.fonts.load('700 1em "Orbitron"'),
            document.fonts.load('400 1em "Orbitron"'),
            document.fonts.load('900 1em "Orbitron"')
          ]).then(showPage).catch(function() {
            setTimeout(showPage, 300);
          });
        }
      } else {
        setTimeout(showPage, 100);
      }
    })();
  
