(() => {
  const status = document.getElementById('signer-result');

  function deliverReply() {
    try {
      status.textContent = RadioAndroidSigner.deliverCallback()
        ? 'Signer reply sent to Radio Guaka.'
        : 'No valid signer reply was received.';
    } catch (_) {
      status.textContent = 'This browser could not return the signer reply.';
    }
  }

  window.addEventListener('hashchange', deliverReply);
  deliverReply();
})();
