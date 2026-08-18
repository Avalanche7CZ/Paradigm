(() => {
  'use strict';

  const COPY = {
    en: {
      overview: 'Overview', permissions: 'Permissions', moderation: 'Moderation', loading: 'Loading...', noPermissionData: 'No Paradigm permission data for this player.',
      primaryGroup: 'Primary group', groups: 'Groups', directPermissions: 'Direct permissions', groupAssignments: 'Group assignments', noDirectPermissions: 'No direct permission assignments.',
      allow: 'Allow', deny: 'Deny', groupAssignment: 'Group', temporary: 'Temporary', expires: 'Expires', context: 'Context', openPermissionEditor: 'Open permission editor',
      punishmentHistory: 'Punishment history', activePunishments: 'Active', noPunishments: 'No moderation history for this player.', reason: 'Reason', moderator: 'Moderator', status: 'Status',
      created: 'Created', openModeration: 'Open moderation', permissionUnavailable: 'Permission data is unavailable.', moderationUnavailable: 'Moderation data is unavailable.',
      inheritedGroups: 'Resolved groups', permanent: 'Permanent', expired: 'Expired'
    },
    cs: {
      overview: 'Přehled', permissions: 'Oprávnění', moderation: 'Moderace', loading: 'Načítám...', noPermissionData: 'Pro tohoto hráče nejsou v Paradigmu permission data.',
      primaryGroup: 'Primární skupina', groups: 'Skupiny', directPermissions: 'Přímá oprávnění', groupAssignments: 'Přiřazení skupin', noDirectPermissions: 'Žádná přímá permission přiřazení.',
      allow: 'Povolit', deny: 'Zakázat', groupAssignment: 'Skupina', temporary: 'Dočasné', expires: 'Vyprší', context: 'Kontext', openPermissionEditor: 'Otevřít editor oprávnění',
      punishmentHistory: 'Historie trestů', activePunishments: 'Aktivní', noPunishments: 'Tento hráč nemá historii moderace.', reason: 'Důvod', moderator: 'Moderátor', status: 'Stav',
      created: 'Vytvořeno', openModeration: 'Otevřít moderaci', permissionUnavailable: 'Permission data nejsou dostupná.', moderationUnavailable: 'Data moderace nejsou dostupná.',
      inheritedGroups: 'Výsledné skupiny', permanent: 'Trvalé', expired: 'Vypršelo'
    },
    ru: {
      overview: 'Обзор', permissions: 'Права', moderation: 'Модерация', loading: 'Загрузка...', noPermissionData: 'Для этого игрока нет данных прав Paradigm.',
      primaryGroup: 'Основная группа', groups: 'Группы', directPermissions: 'Прямые права', groupAssignments: 'Назначения групп', noDirectPermissions: 'Нет прямых назначений прав.',
      allow: 'Разрешить', deny: 'Запретить', groupAssignment: 'Группа', temporary: 'Временно', expires: 'Истекает', context: 'Контекст', openPermissionEditor: 'Открыть редактор прав',
      punishmentHistory: 'История наказаний', activePunishments: 'Активные', noPunishments: 'У игрока нет истории модерации.', reason: 'Причина', moderator: 'Модератор', status: 'Статус',
      created: 'Создано', openModeration: 'Открыть модерацию', permissionUnavailable: 'Данные прав недоступны.', moderationUnavailable: 'Данные модерации недоступны.',
      inheritedGroups: 'Итоговые группы', permanent: 'Постоянные', expired: 'Истекло'
    }
  };

  const detailState = new WeakMap();

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

  function selectedIdentity(root) {
    const name = root.querySelector('.shell-player-detail-head h2')?.textContent?.trim() || '';
    const uuid = root.querySelector('.shell-player-uuid strong')?.textContent?.trim() || '';
    if (!name && !uuid) return null;
    return { name, uuid, key: uuid || name };
  }

  function contextInstalled(root) {
    return Boolean(root?.querySelector(':scope > .player-context-tabs') && root?.querySelector(':scope > .player-context-panels'));
  }

  function uninstallContext(root) {
    if (!root) return;
    const tabs = root.querySelector(':scope > .player-context-tabs');
    const panels = root.querySelector(':scope > .player-context-panels');
    const head = root.querySelector('.shell-player-detail-head');
    const overview = panels?.querySelector('[data-player-panel="overview"]');
    const facts = overview?.querySelector('.shell-player-facts');
    const actions = overview?.querySelector('.shell-player-actions');

    if (head && facts) {
      head.after(facts);
      if (actions) facts.after(actions);
    } else if (head && actions) {
      head.after(actions);
    }

    tabs?.remove();
    panels?.remove();
    delete root.dataset.playerContext;
    detailState.delete(root);
  }

  function installContext(root) {
    if (!root || root.querySelector('.shell-player-empty')) return;
    const identity = selectedIdentity(root);
    if (!identity) return;
    if (root.dataset.playerContext === identity.key && contextInstalled(root)) return;

    const head = root.querySelector('.shell-player-detail-head');
    const facts = root.querySelector('.shell-player-facts');
    const actions = root.querySelector('.shell-player-actions');
    if (!head || !facts) return;
    root.dataset.playerContext = identity.key;

    const tabs = document.createElement('div');
    tabs.className = 'player-context-tabs segmented';
    tabs.setAttribute('role', 'tablist');
    tabs.innerHTML = `
      <button type="button" role="tab" data-player-tab="overview" aria-selected="true">${esc(tr('overview'))}</button>
      <button type="button" role="tab" data-player-tab="permissions" aria-selected="false">${esc(tr('permissions'))}</button>
      <button type="button" role="tab" data-player-tab="moderation" aria-selected="false">${esc(tr('moderation'))}</button>`;

    const panels = document.createElement('div');
    panels.className = 'player-context-panels';
    const overview = document.createElement('section');
    overview.className = 'player-context-panel is-active';
    overview.dataset.playerPanel = 'overview';
    overview.appendChild(facts);
    if (actions) overview.appendChild(actions);

    const permissions = document.createElement('section');
    permissions.className = 'player-context-panel';
    permissions.dataset.playerPanel = 'permissions';
    permissions.innerHTML = `<div class="player-context-loading">${esc(tr('loading'))}</div>`;

    const moderation = document.createElement('section');
    moderation.className = 'player-context-panel';
    moderation.dataset.playerPanel = 'moderation';
    moderation.innerHTML = `<div class="player-context-loading">${esc(tr('loading'))}</div>`;

    panels.append(overview, permissions, moderation);
    head.after(tabs, panels);
    detailState.set(root, { identity, loaded: new Set(), active: 'overview' });

    tabs.querySelectorAll('[data-player-tab]').forEach(button => button.addEventListener('click', () => activateTab(root, button.dataset.playerTab)));
    convertOverviewActionToTab(root, 'permissions');
    convertOverviewActionToTab(root, 'moderation');
  }

  function convertOverviewActionToTab(root, tab) {
    const button = root.querySelector(`[data-player-action="${tab}"]`);
    if (!button) return;
    const replacement = button.cloneNode(true);
    button.replaceWith(replacement);
    replacement.addEventListener('click', () => activateTab(root, tab));
  }

  async function activateTab(root, tab) {
    const state = detailState.get(root);
    if (!state) return;
    state.active = tab;
    root.querySelectorAll('[data-player-tab]').forEach(button => {
      const active = button.dataset.playerTab === tab;
      button.classList.toggle('active', active);
      button.setAttribute('aria-selected', String(active));
    });
    root.querySelectorAll('[data-player-panel]').forEach(panel => panel.classList.toggle('is-active', panel.dataset.playerPanel === tab));
    if (tab === 'permissions' && !state.loaded.has(tab)) await loadPermissions(root, state);
    if (tab === 'moderation' && !state.loaded.has(tab)) await loadModeration(root, state);
  }

  async function loadPermissions(root, state) {
    const panel = root.querySelector('[data-player-panel="permissions"]');
    if (!panel) return;
    panel.innerHTML = `<div class="player-context-loading">${esc(tr('loading'))}</div>`;
    try {
      const data = await api(`/api/permissions/user?uuidOrName=${encodeURIComponent(state.identity.uuid || state.identity.name)}`);
      if (!sameIdentity(root, state.identity)) return;
      state.loaded.add('permissions');
      const user = data?.user;
      const info = user?.info;
      if (!user || !info) {
        panel.innerHTML = `<div class="player-context-empty">${esc(tr('noPermissionData'))}</div>${contextAction('permissions', tr('openPermissionEditor'))}`;
        wireContextActions(panel, state.identity);
        return;
      }
      const meta = info.meta || {};
      const permanent = Array.isArray(info.permanentGroups) ? info.permanentGroups : [];
      const temporary = Array.isArray(info.temporaryGroups) ? info.temporaryGroups : [];
      const resolved = Array.isArray(meta.groups) ? meta.groups : [...permanent, ...temporary.map(group => group.group)].filter(Boolean);
      const assignments = Array.isArray(info.assignments) ? info.assignments : [];
      const groupAssignments = Array.isArray(info.groupAssignments) ? info.groupAssignments : [];
      panel.innerHTML = `
        <div class="player-context-summary-grid">
          ${summaryCard(tr('primaryGroup'), meta.primaryGroup || resolved[0] || '—')}
          ${summaryCard(tr('inheritedGroups'), resolved.length ? resolved.join(', ') : '—')}
        </div>
        <section class="player-context-section">
          <header><h3>${esc(tr('groups'))}</h3><span>${permanent.length + temporary.length}</span></header>
          <div class="player-context-chips">
            ${permanent.map(group => `<span>${esc(group)}<small>${esc(tr('permanent'))}</small></span>`).join('')}
            ${temporary.map(group => `<span>${esc(group.group || '-')}<small>${esc(tr('temporary'))}${group.expiresAtMs ? ` · ${esc(formatDate(group.expiresAtMs))}` : ''}</small></span>`).join('')}
            ${!permanent.length && !temporary.length ? '<em>—</em>' : ''}
          </div>
        </section>
        <section class="player-context-section">
          <header><h3>${esc(tr('directPermissions'))}</h3><span>${assignments.length}</span></header>
          <div class="player-context-rules">${assignments.length ? assignments.map(permissionRule).join('') : `<div class="player-context-empty compact">${esc(tr('noDirectPermissions'))}</div>`}</div>
        </section>
        ${groupAssignments.length ? `<section class="player-context-section"><header><h3>${esc(tr('groupAssignments'))}</h3><span>${groupAssignments.length}</span></header><div class="player-context-rules">${groupAssignments.map(permissionRule).join('')}</div></section>` : ''}
        ${contextAction('permissions', tr('openPermissionEditor'))}`;
      wireContextActions(panel, state.identity);
    } catch (error) {
      if (!sameIdentity(root, state.identity)) return;
      panel.innerHTML = `<div class="player-context-empty">${esc(error?.message || tr('permissionUnavailable'))}</div>${contextAction('permissions', tr('openPermissionEditor'))}`;
      wireContextActions(panel, state.identity);
    }
  }

  async function loadModeration(root, state) {
    const panel = root.querySelector('[data-player-panel="moderation"]');
    if (!panel) return;
    panel.innerHTML = `<div class="player-context-loading">${esc(tr('loading'))}</div>`;
    try {
      const data = await api(`/api/moderation/player?uuidOrName=${encodeURIComponent(state.identity.uuid || state.identity.name)}`);
      if (!sameIdentity(root, state.identity)) return;
      state.loaded.add('moderation');
      const punishments = Array.isArray(data?.punishments) ? data.punishments : [];
      const active = punishments.filter(item => item.active);
      panel.innerHTML = `
        <div class="player-context-summary-grid">
          ${summaryCard(tr('activePunishments'), String(active.length), active.length ? 'is-warning' : '')}
          ${summaryCard(tr('punishmentHistory'), String(punishments.length))}
        </div>
        <section class="player-context-section">
          <header><h3>${esc(tr('punishmentHistory'))}</h3><span>${punishments.length}</span></header>
          <div class="player-context-punishments">${punishments.length ? punishments.map(punishmentCard).join('') : `<div class="player-context-empty compact">${esc(tr('noPunishments'))}</div>`}</div>
        </section>
        ${contextAction('moderation', tr('openModeration'))}`;
      wireContextActions(panel, state.identity);
    } catch (error) {
      if (!sameIdentity(root, state.identity)) return;
      panel.innerHTML = `<div class="player-context-empty">${esc(error?.message || tr('moderationUnavailable'))}</div>${contextAction('moderation', tr('openModeration'))}`;
      wireContextActions(panel, state.identity);
    }
  }

  function summaryCard(label, value, className = '') {
    return `<div class="player-context-summary ${className}"><span>${esc(label)}</span><strong>${esc(value)}</strong></div>`;
  }

  function permissionRule(assignment) {
    const denied = Boolean(assignment?.denied);
    const expired = Boolean(assignment?.expired);
    const groupAssignment = String(assignment?.kind || '') === 'USER_GROUP';
    const context = contextLabel(assignment?.contexts);
    const expires = assignment?.expiresAtMs ? formatDate(assignment.expiresAtMs) : '';
    const effect = expired ? tr('expired') : groupAssignment ? tr('groupAssignment') : denied ? tr('deny') : tr('allow');
    return `<div class="player-context-rule${groupAssignment ? ' is-group' : denied ? ' is-deny' : ' is-allow'}${expired ? ' is-expired' : ''}">
      <span class="player-context-rule-effect">${esc(effect)}</span>
      <div><strong>${esc(assignment?.value || assignment?.node || '-')}</strong><small>${context ? `${esc(tr('context'))}: ${esc(context)}` : ''}${context && expires ? ' · ' : ''}${expires ? `${esc(tr('expires'))}: ${esc(expires)}` : ''}</small></div>
    </div>`;
  }

  function contextLabel(raw) {
    if (!raw || typeof raw !== 'object') return '';
    const values = raw.contexts && typeof raw.contexts === 'object' ? raw.contexts : raw;
    const pairs = Object.entries(values).filter(([, value]) => typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean');
    return pairs.map(([key, value]) => `${key}=${value}`).join(', ');
  }

  function punishmentCard(item) {
    const active = Boolean(item?.active);
    const type = String(item?.type || '-').replaceAll('_', ' ');
    const created = item?.createdAtMs ? formatDate(item.createdAtMs) : '—';
    const expires = item?.expiresAtMs ? formatDate(item.expiresAtMs) : '';
    return `<article class="player-context-punishment${active ? ' is-active' : ''}">
      <header><strong>${esc(type)}</strong><span>${esc(item?.status || (active ? tr('activePunishments') : '-'))}</span></header>
      <p>${esc(item?.reason || '—')}</p>
      <div class="player-context-punishment-meta">
        <span>${esc(tr('moderator'))}: <strong>${esc(item?.actorName || '—')}</strong></span>
        <span>${esc(tr('created'))}: <strong>${esc(created)}</strong></span>
        ${expires ? `<span>${esc(tr('expires'))}: <strong>${esc(expires)}</strong></span>` : ''}
      </div>
    </article>`;
  }

  function contextAction(action, label) {
    return `<div class="player-context-footer"><button type="button" data-context-open="${esc(action)}">${esc(label)}</button></div>`;
  }

  function wireContextActions(panel, identity) {
    panel.querySelector('[data-context-open="permissions"]')?.addEventListener('click', () => openPermissionEditor(identity));
    panel.querySelector('[data-context-open="moderation"]')?.addEventListener('click', () => openModerationEditor(identity));
  }

  async function openPermissionEditor(identity) {
    await requestNavigate('permissions');
    const users = await waitFor('[data-permission-view="users"]');
    users?.click();
    const search = await waitFor('#permissions-search');
    if (search) {
      search.value = identity.name || identity.uuid || '';
      search.dispatchEvent(new Event('input', { bubbles: true }));
      search.focus();
    }
  }

  async function openModerationEditor(identity) {
    await requestNavigate('moderation');
    const search = await waitFor('#moderation-search');
    if (search) search.value = identity.name || identity.uuid || '';
    const find = await waitFor('#moderation-find');
    find?.click();
  }

  function sameIdentity(root, identity) {
    return root?.isConnected && root.dataset.playerContext === identity.key;
  }

  function formatDate(value) {
    const date = new Date(Number(value));
    if (!Number.isFinite(date.getTime())) return '—';
    try {
      return new Intl.DateTimeFormat(locale(), { dateStyle: 'medium', timeStyle: 'short' }).format(date);
    } catch (_) {
      return date.toLocaleString();
    }
  }

  async function waitFor(selector, attempts = 24) {
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      const node = document.querySelector(selector);
      if (node) return node;
      await new Promise(resolve => window.setTimeout(resolve, 50));
    }
    return null;
  }

  function scan() {
    const root = document.getElementById('shell-player-detail');
    if (root) installContext(root);
  }

  const observer = new MutationObserver(records => {
    if (records.some(record => record.target?.closest?.('#shell-player-detail') || [...record.addedNodes].some(node => node instanceof Element && (node.id === 'shell-player-detail' || node.querySelector?.('#shell-player-detail'))))) {
      window.queueMicrotask(scan);
    }
  });
  observer.observe(document.body, { subtree: true, childList: true });

  document.addEventListener('paradigm:language-changed', () => {
    const root = document.getElementById('shell-player-detail');
    if (root && !root.querySelector('.shell-player-empty')) {
      uninstallContext(root);
      window.setTimeout(scan, 0);
    }
  });

  scan();
})();
