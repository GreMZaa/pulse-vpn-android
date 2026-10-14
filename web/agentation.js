// Agentation Visual Feedback Integration for AI Agents
// Activates via ?agentation=1 (or ?agentation=true) in the URL
// Runs in standalone web mode without requiring a local server.
(async function initAgentation() {
  try {
    const params = new URLSearchParams(window.location.search);
    const agentationParam = params.get('agentation');

    // Handle explicit disable via ?agentation=0
    if (agentationParam === '0' || agentationParam === 'false' || agentationParam === 'off') {
      try {
        localStorage.removeItem('agentation_enabled');
      } catch (_) {}
      console.log('🎯 Agentation отключен (?agentation=0)');
      return;
    }

    // Check if enabled via query param or saved preference
    const isParamEnabled = agentationParam !== null && agentationParam !== '0' && agentationParam !== 'false';
    const isStoredEnabled = (function() {
      try {
        return localStorage.getItem('agentation_enabled') === '1';
      } catch (_) {
        return false;
      }
    })();

    if (!isParamEnabled && !isStoredEnabled) {
      // Not requested - do not load React or Agentation to keep production page fast and clean
      return;
    }

    // Persist preference so user doesn't have to keep adding ?agentation=1 on page refreshes
    try {
      localStorage.setItem('agentation_enabled', '1');
    } catch (_) {}

    // Avoid double mounting
    if (document.getElementById('agentation-root-mount')) return;

    // Determine endpoint (runs in standalone web mode unless a custom remote endpoint is specified)
    let endpoint = null;
    if (agentationParam && (agentationParam.startsWith('http://') || agentationParam.startsWith('https://'))) {
      endpoint = agentationParam;
    } else if (params.get('endpoint')) {
      endpoint = params.get('endpoint');
    } else if (params.get('agentation_endpoint')) {
      endpoint = params.get('agentation_endpoint');
    } else if (agentationParam === 'local' && (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1')) {
      endpoint = 'http://localhost:4747';
    }

    // Dynamically load React and Agentation from ESM CDN
    const [{ default: React }, { createRoot }, { Agentation }] = await Promise.all([
      import('https://esm.sh/react@18.3.1'),
      import('https://esm.sh/react-dom@18.3.1/client'),
      import('https://esm.sh/agentation@3.1.2?deps=react@18.3.1,react-dom@18.3.1')
    ]);

    const mount = document.createElement('div');
    mount.id = 'agentation-root-mount';
    document.body.appendChild(mount);

    const props = {};
    if (endpoint) {
      props.endpoint = endpoint;
    }

    createRoot(mount).render(
      React.createElement(Agentation, props)
    );

    console.log(
      '🎯 Agentation toolbar успешно запущен' +
      (endpoint ? ` (подключен к ${endpoint})` : ' в автономном веб-режиме (без локального сервера).') +
      ' Для отключения используйте ?agentation=0'
    );
  } catch (err) {
    console.warn('Agentation failed to load:', err);
  }
})();
