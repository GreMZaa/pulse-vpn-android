// Agentation Visual Feedback Integration for AI Agents
// Automatically connects to local agentation-mcp server at http://localhost:4747
(async function initAgentation() {
  try {
    // Avoid double mounting
    if (document.getElementById('agentation-root-mount')) return;

    // Dynamically load React and Agentation from ESM CDN
    const [{ default: React }, { createRoot }, { Agentation }] = await Promise.all([
      import('https://esm.sh/react@18.3.1'),
      import('https://esm.sh/react-dom@18.3.1/client'),
      import('https://esm.sh/agentation@3.1.2?deps=react@18.3.1,react-dom@18.3.1')
    ]);

    const mount = document.createElement('div');
    mount.id = 'agentation-root-mount';
    document.body.appendChild(mount);

    // Endpoint points to local MCP server
    const endpoint = 'http://localhost:4747';

    createRoot(mount).render(
      React.createElement(Agentation, {
        endpoint: endpoint
      })
    );

    console.log('🎯 Agentation visual feedback toolbar active. Endpoint:', endpoint);
  } catch (err) {
    console.warn('Agentation failed to load:', err);
  }
})();
