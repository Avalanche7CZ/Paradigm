(() => {
  'use strict';

  const COPY = {
    en: {
      current: 'Current', online: 'Online', offline: 'Offline', players: 'Players', modules: 'Modules', lastSeen: 'Last seen',
      minecraft: 'Minecraft', paradigm: 'Paradigm', loader: 'Loader', storage: 'Storage', dashboard: 'Dashboard', network: 'Network', serverId: 'Server ID',
      schema: 'Config schema', schemaMatch: 'Matches dashboard host', schemaMismatch: 'Different from dashboard host', schemaUnknown: 'Unknown',
      enabled: 'Enabled', disabled: 'Disabled', manage: 'Manage configuration', localConfig: 'Open local configuration', select: 'Select a server',
      noServers: 'No server heartbeats are available yet.', unavailable: 'Remote configuration is unavailable for this server.'
    },
    cs: {
      current: 'Aktuální', online: 'Online', offline: 'Offline', players: 'Hráči', modules: 'Moduly', lastSeen: 'Naposledy',
      minecraft: 'Minecraft', paradigm: 'Paradigm', loader: 'Loader', storage: 'Storage', dashboard: 'Dashboard', network: 'Síť', serverId: 'ID serveru',
      schema: 'Config schema', schemaMatch: 'Shodné s hostitelem dashboardu', schemaMismatch: 'Liší se od hostitele dashboardu', schemaUnknown: 'Neznámé',
      enabled: 'Zapnutý', disabled: 'Vypnutý', manage: 'Spravovat konfiguraci', localConfig: 'Otevřít lokální konfiguraci', select: 'Vyber server',
      noServers: 'Zatím nejsou dostupné žádné heartbeat záznamy serverů.', unavailable: 'Vzdálená konfigurace pro tento server není dostupná.'
    },
    ru: {
      current: 'Текущий', online: 'Онлайн', offline: 'Оффлайн', players: 'Игроки', modules: 'Модули', lastSeen: 'Последний сигнал',
      minecraft: 'Minecraft', paradigm: 'Paradigm', loader: 'Загрузчик', storage: 'Хранилище', dashboard: 'Dashboard', network: 'Сеть', serverId: 'ID сервера',
      schema: 'Схема конфигурации', schemaMatch: 'Совпадает с хостом dashboard', schemaMismatch: 'Отличается от хоста dashboard', schemaUnknown: 'Неизвестно',
      enabled: 'Включён', disabled: 'Выключен', manage: 'Управлять конфигурацией', localConfig: 'Открыть локальную конфигурацию', select: 'Выберите сервер',
      noServers: 'Данные heartbeat серверов пока недоступны.', unavailable: 'Удалённая конфигурация для этого сервера недоступна.'
    }
  };

  let selectedServerId = null;
  let installed = false;

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

  function servers() {
    return Array.isArray(state?.servers) ? state.servers : [];
  }

  function serverKey(server) {
    return String(server?.serverId || '').trim();
  }

  function hasRuntimeDetails(server) {
    if (server?.runtimeDetailsAvailable != null) return server.runtimeDetailsAvailable === true;
    return Boolean(server?.current || server?.onlinePlayers != null || server?.moduleCount != null || server?.dashboardEnabled != null);
  }

  function playerCount(server) {
    return hasRuntimeDetails(server) && server?.onlinePlayers != null ? String(Number(server.onlinePlayers)) : '—';
  }

  function selectedServer() {
    const rows = servers();
    let selected = rows.find(server => serverKey(server) === selectedServerId);
    if (!selected) selected = rows.find(server => server.current) || rows[0] || null;
    if (selected) selectedServerId = serverKey(selected);
    return selected;
  }

  function relative(value) {
    if (!value) return '—';
    if (typeof relativeTime === 'function') {
      try { return relativeTime(value); } catch (_) {}
    }
    try { return new Date(Number(value)).toLocaleString(); } catch (_) { return '—'; }
  }

  function stateLabel(server) {
    return server.current ? tr('current') : server.online ? tr('online') : tr('offline');
  }

  function currentFingerprint() {
    return servers().find(server => server.current)?.schemaFingerprint || '';
  }

  function schemaState(server) {
    const fingerprint = String(server?.schemaFingerprint || '');
    const current = String(currentFingerprint() || '');
    if (!fingerprint || !current) return { label: tr('schemaUnknown'), className: 'is-unknown' };
    if (fingerprint === current) return { label: tr('schemaMatch'), className: 'is-match' };
    return { label: tr('schemaMismatch'), className: 'is-mismatch' };
  }

  function ensureWorkspace() {
    const root = document.getElementById('servers-table');
    if (!root) return null;
    root.classList.add('servers-workspace-host');
    return root;
  }

  function renderServersWorkspace() {
    const root = ensureWorkspace();
    if (!root || root.querySelector(':scope > .notice-inline')) return;
    const rows = servers();
    if (!rows.length) {
      root.innerHTML = `<div class="servers-workspace-empty">${esc(tr('noServers'))}</div>`;
      return;
    }

    const selected = selectedServer();
    root.innerHTML = `
      <div class="servers-workspace">
        <div class="servers-directory" role="listbox" aria-label="Servers">
          ${rows.map(server => serverRow(server, selected)).join('')}
        </div>
        <div class="servers-detail">${selected ? serverDetail(selected) : `<div class="servers-workspace-empty">${esc(tr('select'))}</div>`}</div>
      </div>`;

    root.querySelectorAll('[data-server-workspace-id]').forEach(button => button.addEventListener('click', () => {
      selectedServerId = button.dataset.serverWorkspaceId;
      renderServersWorkspace();
    }));
    root.querySelector('[data-server-manage]')?.addEventListener('click', () => manageSelected(selected));
  }

  function serverRow(server, selected) {
    const active = selected && serverKey(server) === serverKey(selected);
    const status = stateLabel(server);
    return `<button type="button" role="option" aria-selected="${active}" class="servers-directory-row${active ? ' is-active' : ''}${server.online || server.current ? '' : ' is-offline'}" data-server-workspace-id="${esc(serverKey(server))}">
      <span class="servers-directory-state"><span class="shell-online-dot${server.online || server.current ? '' : ' is-offline'}"></span></span>
      <span class="servers-directory-copy"><strong>${esc(server.serverName || server.serverId || '-')}</strong><small>${esc(server.loader || '-')} · ${esc(server.minecraftVersion || '-')}</small></span>
      <span class="servers-directory-meta"><strong>${esc(status)}</strong><small>${esc(playerCount(server))} ${esc(tr('players'))}</small></span>
    </button>`;
  }

  function fact(label, value, className = '') {
    return `<div class="servers-detail-fact ${className}"><span>${esc(label)}</span><strong>${esc(value ?? '—')}</strong></div>`;
  }

  function serverDetail(server) {
    const schema = schemaState(server);
    const runtime = hasRuntimeDetails(server);
    const modules = runtime && server.moduleCount != null ? `${Number(server.enabledModuleCount || 0)} / ${Number(server.moduleCount || 0)}` : '—';
    const storage = [server.activeProvider, server.storageHealth].filter(value => value != null && value !== '' && value !== 'unknown').join(' · ') || '—';
    const dashboard = runtime && server.dashboardEnabled != null ? (server.dashboardEnabled ? tr('enabled') : tr('disabled')) : '—';
    const manageLabel = server.current ? tr('localConfig') : tr('manage');
    const remoteAvailable = server.current || canManageRemote(server);

    return `
      <div class="servers-detail-head${server.online || server.current ? '' : ' is-offline'}">
        <div><span class="servers-detail-status">${esc(stateLabel(server))}</span><h2>${esc(server.serverName || server.serverId || '-')}</h2><code>${esc(server.serverId || '-')}</code></div>
        <div class="servers-detail-player-count"><strong>${esc(playerCount(server))}</strong><span>${esc(tr('players'))}</span></div>
      </div>
      <div class="servers-detail-grid">
        ${fact(tr('network'), server.networkId || '—')}
        ${fact(tr('minecraft'), server.minecraftVersion || '—')}
        ${fact(tr('loader'), server.loader || '—')}
        ${fact(tr('paradigm'), server.version || '—')}
        ${fact(tr('modules'), modules)}
        ${fact(tr('storage'), storage)}
        ${fact(tr('dashboard'), dashboard)}
        ${fact(tr('lastSeen'), relative(server.lastSeenMs))}
        ${fact(tr('schema'), schema.label, `servers-schema ${schema.className}`)}
      </div>
      <div class="servers-schema-fingerprint"><span>${esc(tr('schema'))}</span><code>${esc(server.schemaFingerprint || '—')}</code></div>
      <div class="servers-detail-actions">
        <button type="button" data-server-manage ${remoteAvailable ? '' : 'disabled'}>${esc(manageLabel)}</button>
        ${!remoteAvailable ? `<small>${esc(tr('unavailable'))}</small>` : ''}
      </div>`;
  }

  function canManageRemote(server) {
    if (!state?.networkActive || !server || server.current) return false;
    const select = document.getElementById('remote-server-select');
    return Boolean(select && [...select.options].some(option => option.value === server.serverId));
  }

  async function manageSelected(server) {
    if (!server) return;
    if (server.current) {
      await requestNavigate('general');
      return;
    }

    const serverSelect = document.getElementById('remote-server-select');
    const option = serverSelect && [...serverSelect.options].find(entry => entry.value === server.serverId);
    if (!serverSelect || !option) return;

    serverSelect.value = server.serverId;
    serverSelect.dispatchEvent(new Event('change', { bubbles: true }));
    const scope = document.getElementById('remote-scope-select');
    if (scope) {
      scope.value = 'SERVER';
      scope.dispatchEvent(new Event('change', { bubbles: true }));
    }
    window.setTimeout(() => document.getElementById('remote-config-panel')?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 60);
  }

  function install() {
    if (installed) return;
    installed = true;
    window.ParadigmDashboardRuntime?.afterServersLoad(renderServersWorkspace);
    if (state?.page === 'servers' || location.hash === '#servers') renderServersWorkspace();
  }

  document.addEventListener('paradigm:language-changed', () => {
    if (state?.page === 'servers' || location.hash === '#servers') renderServersWorkspace();
  });
  document.addEventListener('change', event => {
    if (event.target?.matches?.('#remote-server-select')) {
      const next = String(event.target.value || '').trim();
      if (next) selectedServerId = next;
      window.setTimeout(renderServersWorkspace, 0);
      return;
    }
    if (event.target?.matches?.('#remote-scope-select')) {
      window.setTimeout(renderServersWorkspace, 0);
    }
  });

  install();
})();
