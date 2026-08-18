(() => {
  'use strict';

  const COPY = {
    en: { network: 'Network', current: 'This server', defaults: 'Network defaults', target: 'Server context', offline: 'Offline' },
    cs: { network: 'Síť', current: 'Tento server', defaults: 'Výchozí pro síť', target: 'Kontext serveru', offline: 'Offline' },
    ru: { network: 'Сеть', current: 'Этот сервер', defaults: 'По умолчанию для сети', target: 'Контекст сервера', offline: 'Оффлайн' }
  };

  let servers = [];
  let currentServer = null;
  let networkActive = false;
  let loading = null;
  let authEpoch = 0;
  let wasAuthenticated = document.body.classList.contains('is-authenticated');

  function locale() {
    const code = window.ParadigmI18n?.locale || document.documentElement.lang || 'en';
    return COPY[code] ? code : 'en';
  }

  function tr(key) {
    return COPY[locale()]?.[key] || COPY.en[key] || key;
  }

  function esc(value) {
    return String(value ?? '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  function authenticated() {
    return document.body.classList.contains('is-authenticated');
  }

  function page() {
    return document.querySelector('.page.active')?.dataset.page || location.hash.slice(1) || 'overview';
  }

  function applyServers(rows, active) {
    servers = Array.isArray(rows) ? rows : [];
    networkActive = Boolean(active);
    currentServer = servers.find(server => server.current) || servers[0] || null;
  }

  function clearServers() {
    servers = [];
    currentServer = null;
    networkActive = false;
  }

  async function refreshServers(force = false) {
    if (!authenticated()) return;
    if (loading) return loading;
    if (!force && servers.length) {
      render();
      return;
    }

    const epoch = authEpoch;
    let request;
    request = (async () => {
      try {
        const data = await api('/api/servers');
        if (epoch !== authEpoch || !authenticated()) return;
        applyServers(data?.servers, data?.networkActive);
      } catch (_) {
        if (epoch !== authEpoch || !authenticated()) return;
        clearServers();
      } finally {
        if (loading === request) loading = null;
        if (epoch === authEpoch) render();
      }
    })();
    loading = request;
    return request;
  }

  function render() {
    let root = document.getElementById('network-context-switcher');
    if (!authenticated() || !networkActive || servers.length < 2) {
      root?.remove();
      return;
    }
    if (!root) {
      root = document.createElement('label');
      root.id = 'network-context-switcher';
      root.className = 'network-context-switcher';
      root.innerHTML = '<span></span><select aria-label="Server context"></select>';
      const heading = document.querySelector('.topbar .page-heading');
      if (heading) heading.after(root);
      else document.querySelector('.topbar-leading')?.appendChild(root);
      root.querySelector('select')?.addEventListener('change', onSelect);
    }
    root.querySelector('span').textContent = tr('network');
    const select = root.querySelector('select');
    select.setAttribute('aria-label', tr('target'));
    const currentId = currentServer?.serverId || '';
    const options = [
      `<option value="local">${esc(tr('current'))}${currentServer?.serverName ? ` · ${esc(currentServer.serverName)}` : ''}</option>`,
      `<option value="network">${esc(tr('defaults'))}</option>`,
      ...servers.filter(server => !server.current).map(server => `<option value="server:${esc(server.serverId)}">${esc(server.serverName || server.serverId)}${server.online ? '' : ` · ${esc(tr('offline'))}`}</option>`)
    ];
    const wanted = selectedContext();
    select.innerHTML = options.join('');
    select.value = [...select.options].some(option => option.value === wanted) ? wanted : 'local';
    root.dataset.currentServer = currentId;
  }

  function selectedContext() {
    if (page() !== 'servers') return 'local';
    const remoteServer = document.getElementById('remote-server-select')?.value || '';
    const scope = document.getElementById('remote-scope-select')?.value || 'SERVER';
    if (scope === 'NETWORK') return 'network';
    if (remoteServer && remoteServer !== currentServer?.serverId) return `server:${remoteServer}`;
    return 'local';
  }

  async function onSelect(event) {
    const value = event.currentTarget.value;
    if (value === 'local') {
      await requestNavigate('overview');
      render();
      return;
    }

    await requestNavigate('servers');
    if (page() !== 'servers') {
      render();
      return;
    }

    const serverSelect = await waitFor('#remote-server-select');
    const scopeSelect = await waitFor('#remote-scope-select');
    if (value === 'network') {
      if (scopeSelect) {
        scopeSelect.value = 'NETWORK';
        scopeSelect.dispatchEvent(new Event('change', { bubbles: true }));
      }
      window.setTimeout(render, 80);
      return;
    }

    const serverId = value.startsWith('server:') ? value.slice('server:'.length) : '';
    if (serverSelect && serverId && [...serverSelect.options].some(option => option.value === serverId)) {
      serverSelect.value = serverId;
      serverSelect.dispatchEvent(new Event('change', { bubbles: true }));
    }
    if (scopeSelect) {
      scopeSelect.value = 'SERVER';
      scopeSelect.dispatchEvent(new Event('change', { bubbles: true }));
    }
    window.setTimeout(render, 80);
  }

  async function waitFor(selector, attempts = 30) {
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      const node = document.querySelector(selector);
      if (node) return node;
      await new Promise(resolve => window.setTimeout(resolve, 50));
    }
    return null;
  }

  window.ParadigmDashboardRuntime?.afterServersLoad(rows => {
    if (!authenticated()) return;
    applyServers(rows, state?.networkActive);
    render();
  });

  document.addEventListener('paradigm:language-changed', render);
  window.addEventListener('hashchange', () => window.setTimeout(() => {
    if (page() === 'servers' && !servers.length) refreshServers();
    else render();
  }, 0));
  document.addEventListener('change', event => {
    if (event.target?.matches?.('#remote-server-select, #remote-scope-select')) window.setTimeout(render, 0);
  });

  const authObserver = new MutationObserver(() => {
    const next = authenticated();
    if (next === wasAuthenticated) return;
    wasAuthenticated = next;
    if (next) {
      refreshServers();
      return;
    }
    authEpoch += 1;
    loading = null;
    clearServers();
    document.getElementById('network-context-switcher')?.remove();
  });
  authObserver.observe(document.body, { attributes: true, attributeFilter: ['class'] });

  if (authenticated()) refreshServers();
})();