(() => {
  'use strict';

  const COPY = {
    en: {
      review: 'Review changes', reviewHelp: 'Check what will change before Paradigm writes the configuration.', cancel: 'Cancel', save: 'Save changes',
      before: 'Before', after: 'After', restartRequired: 'Server restart required', reloadRequired: 'Module reload required', sensitive: 'Sensitive value',
      localScope: 'This server', networkScope: 'Network default', serverScope: 'Server override', inherited: 'Inherited',
      player: 'Player', server: 'Server', group: 'Permission group', online: 'Online', offline: 'Offline', permissions: 'Permissions', players: 'Players', servers: 'Servers', entities: 'Entities',
      highRisk: 'High risk', mediumRisk: 'Medium risk', changeOne: 'change', changeMany: 'changes', knownServerOne: 'known server', knownServerMany: 'known servers'
    },
    cs: {
      review: 'Kontrola změn', reviewHelp: 'Zkontroluj změny před tím, než je Paradigm zapíše do konfigurace.', cancel: 'Zrušit', save: 'Uložit změny',
      before: 'Předtím', after: 'Potom', restartRequired: 'Vyžaduje restart serveru', reloadRequired: 'Vyžaduje reload modulu', sensitive: 'Citlivá hodnota',
      localScope: 'Tento server', networkScope: 'Výchozí pro síť', serverScope: 'Override serveru', inherited: 'Zděděno',
      player: 'Hráč', server: 'Server', group: 'Permission skupina', online: 'Online', offline: 'Offline', permissions: 'Oprávnění', players: 'Hráči', servers: 'Servery', entities: 'Entity',
      highRisk: 'Vysoké riziko', mediumRisk: 'Střední riziko', changeOne: 'změna', changeMany: 'změn', knownServerOne: 'známý server', knownServerMany: 'známých serverů'
    },
    ru: {
      review: 'Проверка изменений', reviewHelp: 'Проверьте изменения перед записью конфигурации Paradigm.', cancel: 'Отмена', save: 'Сохранить изменения',
      before: 'До', after: 'После', restartRequired: 'Требуется перезапуск сервера', reloadRequired: 'Требуется перезагрузка модуля', sensitive: 'Скрытое значение',
      localScope: 'Этот сервер', networkScope: 'По умолчанию для сети', serverScope: 'Переопределение сервера', inherited: 'Унаследовано',
      player: 'Игрок', server: 'Сервер', group: 'Группа прав', online: 'Онлайн', offline: 'Оффлайн', permissions: 'Права', players: 'Игроки', servers: 'Серверы', entities: 'Объекты',
      highRisk: 'Высокий риск', mediumRisk: 'Средний риск', changeOne: 'изменение', changeMany: 'изменений', knownServerOne: 'известный сервер', knownServerMany: 'известных серверов'
    }
  };

  let localBypass = false;
  let remoteBypass = false;
  let reviewBackdrop = null;
  let reviewResolve = null;
  let reviewPending = null;
  let reviewReturnFocus = null;
  let entityCache = emptyEntityCache();
  let paletteGeneration = 0;
  let paletteTimer = null;
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

  function normalized(value) {
    return String(value || '').toLocaleLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
  }

  function playerKey(player) {
    return String(player?.uuid || player?.name || '').toLowerCase();
  }

  function emptyEntityCache() {
    return { loadedAt: 0, loading: null, players: [], servers: [], groups: [] };
  }

  function resetEntityCache() {
    entityCache = emptyEntityCache();
    paletteGeneration += 1;
    if (paletteTimer) {
      window.clearTimeout(paletteTimer);
      paletteTimer = null;
    }
  }

  function fieldByKey(snapshot, key) {
    return (snapshot?.fields || []).find(field => field?.key === key) || null;
  }

  function labelFor(field, key) {
    if (typeof humanLabel === 'function' && field) {
      try { return humanLabel(field); } catch (_) {}
    }
    return field?.label || key;
  }

  function maskedField(field, key) {
    return Boolean(field?.masked) || /(token|password|secret|private.?key|webhook)/i.test(String(key || ''));
  }

  function formatValue(value, masked = false) {
    if (masked) return '••••••••';
    if (value === undefined) return '—';
    if (value === null) return 'null';
    if (typeof value === 'string') return value === '' ? '""' : value;
    if (typeof value === 'boolean' || typeof value === 'number') return String(value);
    try { return JSON.stringify(value, null, 2); } catch (_) { return String(value); }
  }

  function impactBadges(field) {
    const badges = [];
    if (field?.reloadBehavior === 'RESTART_REQUIRED') badges.push({ text: tr('restartRequired'), className: 'is-warning' });
    else if (field?.reloadBehavior === 'RELOAD_REQUIRED') badges.push({ text: tr('reloadRequired'), className: 'is-reload' });
    const risk = String(field?.riskLevel || '').toUpperCase();
    if (risk === 'HIGH') badges.push({ text: tr('highRisk'), className: 'is-danger' });
    else if (risk === 'MEDIUM') badges.push({ text: tr('mediumRisk'), className: 'is-warning' });
    return badges;
  }

  function localChanges() {
    if (typeof state === 'undefined' || !state?.edits || !state?.snapshot) return [];
    return [...state.edits]
      .filter(([key]) => state.editPages.get(key) === state.page)
      .map(([key, value]) => {
        const field = fieldByKey(state.snapshot, key);
        return { key, label: labelFor(field, key), before: field?.value?.value, after: value, masked: maskedField(field, key), field, scope: tr('localScope') };
      });
  }

  function networkScopeLabel() {
    const count = Array.isArray(state?.servers) ? state.servers.length : 0;
    if (!count) return tr('networkScope');
    return `${tr('networkScope')} · ${count} ${tr(count === 1 ? 'knownServerOne' : 'knownServerMany')}`;
  }

  function remoteBefore(field, scope) {
    if (scope === 'NETWORK') {
      if (field?.networkValue?.set) return { value: field.networkValue.value, explicit: true };
      if (field?.baselineValue?.set) return { value: field.baselineValue.value, explicit: false };
      return { value: field?.value?.value, explicit: false };
    }
    if (field?.serverValue?.set) return { value: field.serverValue.value, explicit: true };
    if (field?.networkValue?.set) return { value: field.networkValue.value, explicit: false };
    if (field?.baselineValue?.set) return { value: field.baselineValue.value, explicit: false };
    return { value: field?.value?.value, explicit: false };
  }

  function remoteChanges() {
    if (typeof state === 'undefined' || !state?.remote?.edits || !state.remote.snapshot || !state.remote.section) return [];
    const section = state.remote.section;
    const sectionStatus = (state.remote.snapshot.sections || []).find(item => item.section === section);
    let scope = document.getElementById('remote-scope-select')?.value || 'SERVER';
    if (typeof remoteSectionScope === 'function') {
      try { scope = remoteSectionScope(sectionStatus) || scope; } catch (_) {}
    }
    return [...state.remote.edits]
      .filter(([key]) => state.remote.editPages.get(key) === 'remoteConfig' && fieldByKey(state.remote.snapshot, key)?.category === section)
      .map(([key, value]) => {
        const field = fieldByKey(state.remote.snapshot, key);
        const before = remoteBefore(field, scope);
        return {
          key,
          label: labelFor(field, key),
          before: before.value,
          beforePrefix: before.explicit ? '' : `${tr('inherited')}: `,
          after: value,
          masked: maskedField(field, key),
          field,
          scope: scope === 'NETWORK' ? networkScopeLabel() : `${tr('serverScope')} · ${state.remote.serverId || ''}`
        };
      });
  }

  function ensureReviewModal() {
    if (reviewBackdrop) return reviewBackdrop;
    reviewBackdrop = document.createElement('div');
    reviewBackdrop.className = 'ux2-review-backdrop hidden';
    reviewBackdrop.innerHTML = `
      <section class="ux2-review" role="dialog" aria-modal="true" aria-labelledby="ux2-review-title">
        <header class="ux2-review-header">
          <div><span class="shell-kicker">Paradigm</span><h2 id="ux2-review-title"></h2><p id="ux2-review-help"></p></div>
          <button type="button" class="ux2-review-close" aria-label="Close">×</button>
        </header>
        <div id="ux2-review-summary" class="ux2-review-summary"></div>
        <div id="ux2-review-list" class="ux2-review-list"></div>
        <footer class="ux2-review-actions">
          <button type="button" data-review-action="cancel"></button>
          <button type="button" data-review-action="save" class="ux2-review-save"></button>
        </footer>
      </section>`;
    document.body.appendChild(reviewBackdrop);
    reviewBackdrop.addEventListener('mousedown', event => { if (event.target === reviewBackdrop) resolveReview(false); });
    reviewBackdrop.querySelector('.ux2-review-close')?.addEventListener('click', () => resolveReview(false));
    reviewBackdrop.querySelector('[data-review-action="cancel"]')?.addEventListener('click', () => resolveReview(false));
    reviewBackdrop.querySelector('[data-review-action="save"]')?.addEventListener('click', () => resolveReview(true));
    reviewBackdrop.addEventListener('keydown', event => {
      if (event.key === 'Escape') {
        event.preventDefault();
        resolveReview(false);
      } else if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
        event.preventDefault();
        resolveReview(true);
      }
    });
    return reviewBackdrop;
  }

  function reviewChanges(changes) {
    if (!changes.length) return Promise.resolve(true);
    if (reviewPending) return Promise.resolve(false);
    const modal = ensureReviewModal();
    reviewReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    modal.querySelector('#ux2-review-title').textContent = tr('review');
    modal.querySelector('#ux2-review-help').textContent = tr('reviewHelp');
    modal.querySelector('[data-review-action="cancel"]').textContent = tr('cancel');
    modal.querySelector('[data-review-action="save"]').textContent = `${tr('save')} (${changes.length})`;

    const restartCount = changes.filter(change => change.field?.reloadBehavior === 'RESTART_REQUIRED').length;
    const reloadCount = changes.filter(change => change.field?.reloadBehavior === 'RELOAD_REQUIRED').length;
    const riskCount = changes.filter(change => String(change.field?.riskLevel || '').toUpperCase() === 'HIGH').length;
    const scopes = [...new Set(changes.map(change => change.scope).filter(Boolean))];
    const summary = modal.querySelector('#ux2-review-summary');
    summary.innerHTML = `
      <strong>${changes.length} ${esc(tr(changes.length === 1 ? 'changeOne' : 'changeMany'))}</strong>
      ${scopes.map(scope => `<span>${esc(scope)}</span>`).join('')}
      ${restartCount ? `<span class="is-warning">${restartCount} · ${esc(tr('restartRequired'))}</span>` : ''}
      ${reloadCount ? `<span class="is-reload">${reloadCount} · ${esc(tr('reloadRequired'))}</span>` : ''}
      ${riskCount ? `<span class="is-danger">${riskCount} · ${esc(tr('highRisk'))}</span>` : ''}`;

    modal.querySelector('#ux2-review-list').innerHTML = changes.map(change => {
      const badges = impactBadges(change.field);
      const before = `${change.beforePrefix || ''}${formatValue(change.before, change.masked)}`;
      const after = formatValue(change.after, change.masked);
      return `<article class="ux2-change-card">
        <header><div><strong>${esc(change.label)}</strong><code>${esc(change.key)}</code></div><div class="ux2-change-badges">${change.masked ? `<span>${esc(tr('sensitive'))}</span>` : ''}${badges.map(badge => `<span class="${badge.className}">${esc(badge.text)}</span>`).join('')}</div></header>
        <div class="ux2-change-values">
          <div><span>${esc(tr('before'))}</span><pre>${esc(before)}</pre></div>
          <div class="ux2-change-arrow" aria-hidden="true">→</div>
          <div><span>${esc(tr('after'))}</span><pre>${esc(after)}</pre></div>
        </div>
      </article>`;
    }).join('');

    modal.classList.remove('hidden');
    document.body.classList.add('ux2-review-open');
    window.setTimeout(() => modal.querySelector('[data-review-action="save"]')?.focus(), 0);
    reviewPending = new Promise(resolve => { reviewResolve = resolve; });
    return reviewPending;
  }

  function resolveReview(accepted) {
    if (!reviewBackdrop || reviewBackdrop.classList.contains('hidden')) return;
    reviewBackdrop.classList.add('hidden');
    document.body.classList.remove('ux2-review-open');
    const resolve = reviewResolve;
    reviewResolve = null;
    reviewPending = null;
    const focus = reviewReturnFocus;
    reviewReturnFocus = null;
    if (focus?.isConnected) window.setTimeout(() => focus.focus(), 0);
    resolve?.(Boolean(accepted));
  }

  function installSaveReview() {
    const local = document.getElementById('save-changes');
    if (local && !local.dataset.ux2Review) {
      local.dataset.ux2Review = 'true';
      local.addEventListener('click', async event => {
        if (localBypass) {
          localBypass = false;
          return;
        }
        const changes = localChanges();
        if (!changes.length) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        if (await reviewChanges(changes)) {
          localBypass = true;
          local.click();
        }
      }, true);
    }

    const remote = document.getElementById('remote-save-changes');
    if (remote && !remote.dataset.ux2Review) {
      remote.dataset.ux2Review = 'true';
      remote.addEventListener('click', async event => {
        if (remoteBypass) {
          remoteBypass = false;
          return;
        }
        const changes = remoteChanges();
        if (!changes.length) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        if (await reviewChanges(changes)) {
          remoteBypass = true;
          remote.click();
        }
      }, true);
    }
  }

  async function loadEntityCache(force = false) {
    const now = Date.now();
    if (!force && entityCache.loadedAt && now - entityCache.loadedAt < 15000) return entityCache;
    if (entityCache.loading) return entityCache.loading;
    const epoch = authEpoch;
    let request;
    request = (async () => {
      try {
        const [overviewResult, serversResult, groupsResult] = await Promise.allSettled([
          api('/api/overview'),
          api('/api/servers'),
          api('/api/permissions/groups')
        ]);
        if (epoch !== authEpoch || !document.body.classList.contains('is-authenticated')) return entityCache;
        entityCache.players = overviewResult.status === 'fulfilled' && Array.isArray(overviewResult.value?.players) ? overviewResult.value.players.map(player => ({ ...player, online: true })) : [];
        entityCache.servers = serversResult.status === 'fulfilled' && Array.isArray(serversResult.value?.servers) ? serversResult.value.servers : [];
        entityCache.groups = groupsResult.status === 'fulfilled' && Array.isArray(groupsResult.value?.groups) ? groupsResult.value.groups : [];
        entityCache.loadedAt = Date.now();
        return entityCache;
      } finally {
        if (entityCache.loading === request) entityCache.loading = null;
      }
    })();
    entityCache.loading = request;
    return request;
  }

  async function knownPlayerMatches(query) {
    try {
      const data = await api(`/api/players?query=${encodeURIComponent(query)}&page=1&pageSize=20`);
      return Array.isArray(data?.players) ? data.players : [];
    } catch (_) {
      return [];
    }
  }

  function entityScore(label, detail, query) {
    const name = normalized(label);
    const extra = normalized(detail);
    if (name === query) return 200;
    if (name.startsWith(query)) return 160;
    if (name.includes(query)) return 120;
    if (extra.includes(query)) return 60;
    return 0;
  }

  function mergedPlayerEntries(knownPlayers) {
    const merged = new Map();
    for (const player of knownPlayers || []) {
      const key = playerKey(player);
      if (key) merged.set(key, { ...player, online: Boolean(player.online) });
    }
    for (const player of entityCache.players) {
      const key = playerKey(player);
      if (!key) continue;
      merged.set(key, { ...(merged.get(key) || {}), ...player, online: true });
    }
    return [...merged.values()];
  }

  function entityEntries(query, knownPlayers = []) {
    const entries = [];
    for (const player of mergedPlayerEntries(knownPlayers)) {
      const label = player.name || player.uuid || '-';
      const detail = [player.online ? player.world : tr('offline'), player.uuid, player.online ? `${Number(player.ping || 0)} ms` : ''].filter(Boolean).join(' · ');
      const score = entityScore(label, detail, query);
      if (score) entries.push({ type: 'player', kind: tr('player'), label, detail, page: tr('players'), score, data: player });
    }
    for (const server of entityCache.servers) {
      const label = server.serverName || server.serverId || '-';
      const detail = [server.serverId, server.loader, server.current ? tr('online') : (server.online ? tr('online') : tr('offline')), server.onlinePlayers != null ? `${server.onlinePlayers} ${tr('players')}` : ''].filter(Boolean).join(' · ');
      const score = entityScore(label, detail, query);
      if (score) entries.push({ type: 'server', kind: tr('server'), label, detail, page: tr('servers'), score, data: server });
    }
    for (const group of entityCache.groups) {
      const label = group.name || '-';
      const detail = [group.description, group.prefix, ...(group.parents || [])].filter(Boolean).join(' · ');
      const score = entityScore(label, detail, query);
      if (score) entries.push({ type: 'group', kind: tr('group'), label, detail, page: tr('permissions'), score, data: group });
    }
    return entries.sort((a, b) => b.score - a.score || a.label.localeCompare(b.label)).slice(0, 8);
  }

  function closeShellPalette(backdrop) {
    backdrop?.classList.add('hidden');
    document.body.classList.remove('shell-palette-open');
  }

  async function openEntity(entry, backdrop) {
    closeShellPalette(backdrop);
    if (entry.type === 'player') {
      await requestNavigate('players');
      const search = await waitForNode('#shell-player-search');
      if (search) {
        search.value = entry.data.name || entry.data.uuid || '';
        search.dispatchEvent(new Event('input', { bubbles: true }));
      }
      const key = playerKey(entry.data);
      const row = await waitForNode(`[data-shell-player="${cssEscape(key)}"]`);
      row?.click();
      return;
    }
    if (entry.type === 'server') {
      await requestNavigate('servers');
      const select = await waitForNode('#remote-server-select');
      if (select && [...select.options].some(option => option.value === entry.data.serverId)) {
        select.value = entry.data.serverId;
        select.dispatchEvent(new Event('change', { bubbles: true }));
      }
      return;
    }
    if (entry.type === 'group') {
      await requestNavigate('permissions');
      const groups = await waitForNode('[data-permission-view="groups"]');
      groups?.click();
      const search = await waitForNode('#permissions-search');
      if (search) {
        search.value = entry.data.name || '';
        search.dispatchEvent(new Event('input', { bubbles: true }));
      }
      const target = await waitForPermissionTarget(entry.data.name);
      target?.click();
    }
  }

  function cssEscape(value) {
    if (window.CSS?.escape) return CSS.escape(String(value));
    return String(value).replace(/["\\]/g, '\\$&');
  }

  async function waitForNode(selector, attempts = 30) {
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      const node = document.querySelector(selector);
      if (node) return node;
      await new Promise(resolve => window.setTimeout(resolve, 50));
    }
    return null;
  }

  async function waitForPermissionTarget(groupName, attempts = 40) {
    const wanted = normalized(groupName);
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      const target = [...document.querySelectorAll('#permission-target-list [data-permission-kind="group"]')]
        .find(button => normalized(button.dataset.permissionId) === wanted);
      if (target) return target;
      await new Promise(resolve => window.setTimeout(resolve, 50));
    }
    return null;
  }

  async function augmentPalette(backdrop) {
    const input = backdrop.querySelector('#shell-palette-input');
    const root = backdrop.querySelector('#shell-palette-results');
    if (!input || !root) return;
    root.querySelectorAll('.ux2-entity-result, .ux2-entity-divider').forEach(node => node.remove());
    const query = normalized(input.value.trim());
    if (query.length < 2 || !document.body.classList.contains('is-authenticated')) return;
    const generation = ++paletteGeneration;
    const [cacheResult, knownResult] = await Promise.allSettled([loadEntityCache(), knownPlayerMatches(query)]);
    if (generation !== paletteGeneration || query !== normalized(input.value.trim()) || !document.body.classList.contains('is-authenticated')) return;
    if (cacheResult.status !== 'fulfilled') return;
    const knownPlayers = knownResult.status === 'fulfilled' ? knownResult.value : [];
    const entries = entityEntries(query, knownPlayers);
    if (!entries.length) return;
    const divider = document.createElement('div');
    divider.className = 'ux2-entity-divider';
    divider.textContent = tr('entities');
    root.prepend(divider);
    entries.reverse().forEach(entry => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'shell-palette-result ux2-entity-result';
      button.innerHTML = '<span class="shell-result-kind"></span><span class="shell-result-copy"><strong></strong><small></small></span><span class="shell-result-page"></span>';
      button.querySelector('.shell-result-kind').textContent = entry.kind;
      button.querySelector('strong').textContent = entry.label;
      button.querySelector('small').textContent = entry.detail;
      button.querySelector('.shell-result-page').textContent = entry.page;
      button.addEventListener('click', () => openEntity(entry, backdrop));
      divider.after(button);
    });
    const buttons = [...root.querySelectorAll('.shell-palette-result')];
    buttons.forEach(button => button.classList.remove('is-active'));
    buttons[0]?.classList.add('is-active');
  }

  function schedulePaletteAugment(backdrop) {
    if (paletteTimer) window.clearTimeout(paletteTimer);
    paletteTimer = window.setTimeout(() => {
      paletteTimer = null;
      augmentPalette(backdrop);
    }, 140);
  }

  function wirePalette(backdrop) {
    if (!backdrop || backdrop.dataset.ux2Entities) return;
    backdrop.dataset.ux2Entities = 'true';
    const input = backdrop.querySelector('#shell-palette-input');
    const root = backdrop.querySelector('#shell-palette-results');
    if (!input || !root) return;
    input.addEventListener('input', () => schedulePaletteAugment(backdrop));
    input.addEventListener('keydown', event => {
      if (!root.querySelector('.ux2-entity-result')) return;
      const buttons = [...root.querySelectorAll('.shell-palette-result')];
      if (!buttons.length || !['ArrowDown', 'ArrowUp', 'Enter'].includes(event.key)) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      let index = Math.max(0, buttons.findIndex(button => button.classList.contains('is-active')));
      if (event.key === 'ArrowDown') index = (index + 1) % buttons.length;
      else if (event.key === 'ArrowUp') index = (index - 1 + buttons.length) % buttons.length;
      else {
        buttons[index]?.click();
        return;
      }
      buttons.forEach((button, buttonIndex) => {
        button.classList.toggle('is-active', buttonIndex === index);
        button.setAttribute('aria-selected', String(buttonIndex === index));
      });
      buttons[index]?.scrollIntoView({ block: 'nearest' });
    }, true);
    root.addEventListener('mouseover', event => {
      const button = event.target.closest('.shell-palette-result');
      if (!button) return;
      root.querySelectorAll('.shell-palette-result').forEach(result => result.classList.toggle('is-active', result === button));
    });
  }

  function discoverPalette() {
    const palette = document.querySelector('.shell-palette-backdrop');
    if (palette) wirePalette(palette);
  }

  function resetSessionUi() {
    resetEntityCache();
    const palette = document.querySelector('.shell-palette-backdrop');
    closeShellPalette(palette);
    palette?.querySelectorAll('.ux2-entity-result, .ux2-entity-divider').forEach(node => node.remove());
    if (reviewBackdrop && !reviewBackdrop.classList.contains('hidden')) resolveReview(false);
  }

  function install() {
    installSaveReview();
    discoverPalette();
  }

  const observer = new MutationObserver(() => install());
  observer.observe(document.body, { subtree: true, childList: true });

  const authObserver = new MutationObserver(() => {
    const authenticated = document.body.classList.contains('is-authenticated');
    if (authenticated === wasAuthenticated) return;
    wasAuthenticated = authenticated;
    authEpoch += 1;
    resetSessionUi();
  });
  authObserver.observe(document.body, { attributes: true, attributeFilter: ['class'] });

  document.addEventListener('paradigm:language-changed', () => {
    if (reviewBackdrop && !reviewBackdrop.classList.contains('hidden')) resolveReview(false);
    resetEntityCache();
    install();
  });
  window.addEventListener('hashchange', () => window.setTimeout(install, 0));

  install();
})();
