    window.BUILD_TIME = '';
    window.BUILD_INFO = { go: '', html: '' };

    function formatBuildDateFallback() {
      const now = new Date();
      const year = now.getFullYear().toString().slice(-2);
      const month = String(now.getMonth() + 1).padStart(2, '0');
      const day = String(now.getDate()).padStart(2, '0');
      const hours = String(now.getHours()).padStart(2, '0');
      const minutes = String(now.getMinutes()).padStart(2, '0');
      return `${year}${month}${day} ${hours}:${minutes}`;
    }

    function getBuildDateText() {
      const goDate = (window.BUILD_INFO && typeof window.BUILD_INFO.go === 'string') ? window.BUILD_INFO.go.trim() : '';
      const htmlDate = (window.BUILD_INFO && typeof window.BUILD_INFO.html === 'string') ? window.BUILD_INFO.html.trim() : '';
      if (goDate && htmlDate) return `go ${goDate} · html ${htmlDate}`;
      if (htmlDate) return `html ${htmlDate}`;
      if (goDate) return `go ${goDate}`;
      if (typeof window.BUILD_TIME === 'string' && /^\d/.test(window.BUILD_TIME)) {
        return `html ${window.BUILD_TIME}`;
      }
      return `html ${formatBuildDateFallback()}`;
    }

    function updateFooterBuildDate() {
      const el = document.getElementById('footerBuildDate');
      if (!el) return;
      el.textContent = getBuildDateText();
    }

    updateFooterBuildDate();
    fetch('/api/build-info')
      .then((r) => {
        if (!r.ok) throw new Error('build info unavailable');
        return r.json();
      })
      .then((data) => {
        window.BUILD_INFO = {
          go: (data && typeof data.go === 'string') ? data.go : '',
          html: (data && typeof data.html === 'string') ? data.html : '',
        };
        updateFooterBuildDate();
      })
      .catch(() => {});
    fetch('/js/buildtime.js')
      .then((r) => r.text())
      .then((text) => {
        const match = text.match(/BUILD_TIME\s*=\s*'([^']+)'/);
        if (match && /^\d/.test(match[1])) {
          window.BUILD_TIME = match[1];
          updateFooterBuildDate();
        }
      })
      .catch(() => {});
  
