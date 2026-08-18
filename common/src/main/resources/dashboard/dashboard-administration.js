(() => {
  'use strict';

  const COPY = {
    en: {
      administration: 'Administration', help: 'Players, permissions, moderation and administrative history in one place.', refresh: 'Refresh', back: 'Administration',
      permissions: 'Permissions', permissionsHelp: 'Groups, users, inheritance and permission assignments.', moderation: 'Moderation', moderationHelp: 'Review player history and apply moderation actions.', audit: 'Audit', auditHelp: 'See who changed what and where it came from.',
      open: 'Open', groups: 'Groups', users: 'Users', nodes: 'Nodes', recentActions: 'Recent actions', recentActivity: 'Recent activity', unavailable: 'Unavailable',
      playerWorkflow: 'Player workflow', playerWorkflowHelp: 'For a specific player, start in Players. Permissions and moderation history are available directly in the player workspace.', openPlayers: 'Open Players'
    },
    cs: {
      administration: 'Administrace', help: 'Hráči, oprávnění, moderace a administrační historie na jednom místě.', refresh: 'Obnovit', back: 'Administrace',
      permissions: 'Oprávnění', permissionsHelp: 'Skupiny, uživatelé, dědičnost a permission přiřazení.', moderation: 'Moderace', moderationHelp: 'Historie hráčů a moderační akce.', audit: 'Audit', auditHelp: 'Kdo co změnil a odkud změna přišla.',
      open: 'Otevřít', groups: 'Skupiny', users: 'Uživatelé', nodes: 'Nody', recentActions: 'Nedávné akce', recentActivity: 'Nedávná aktivita', unavailable: 'Nedostupné',
      playerWorkflow: 'Práce s hráčem', playerWorkflowHelp: 'Pro konkrétního hráče začni v Hráčích. Oprávnění i historii moderace uvidíš přímo v jeho workspace.', openPlayers: 'Otevřít Hráče'
    },
    ru: {
      administration: 'Администрирование', help: 'Игроки, права, модерация и история административных действий в одном месте.', refresh: 'Обновить', back: 'Администрирование',
      permissions: 'Права', permissionsHelp: 'Группы, пользователи, наследование и назначения прав.', moderation: 'Модерация', moderationHelp: 'История игроков и действия модерации.', audit: 'Аудит', auditHelp: 'Кто, что и откуда изменил.',
      open: 'Открыть', groups: 'Группы', users: 'Пользователи', nodes: 'Узлы', recentActions: 'Недавние действия', recentActivity: 'Недавняя активность', unavailable: 'Недоступно',
      playerWorkflow: 'Работа с игроком', playerWorkflowHelp: 'Для конкретного игрока начните со страницы Игроки. Права и история модерации доступны прямо в рабочей области игрока.', openPlayers: 'Открыть Игроков'
    }
  };

  const CHILD_PAGES = new Set(['permissions', 'moderation', 'audit']);
  let installed = false;
  let loadGeneration = 0;

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

  function setText(node, value) {
    if (node && node.textContent !== value) node.textContent = value;
  }

  function currentPage() {
    return document.querySelector('.page.active')?.dataset.page || location.hash.slice(1) || 'overview';
  }

  function administrationGroup() {
    return [...document.querySelectorAll('#navigation .nav-group')].find(group =>
      group.querySelector('[data-page-target="permissions"]') && group.querySelector('[data-page-target="moderation"]')) || null;
  }

  function ensureNavigation(page = currentPage()) {
    const group = administrationGroup();
    if (!group) return;
    let button = group.querySelector('[data-page-target="administration"]');
    if (!button) {
      button = document.createElement('button');
      button.type = 'button';
      button.dataset.pageTarget = 'administration';
      button.addEventListener('click', () => requestNavigate('administration'));
      const first = group.querySelector('[data-page-target]');
      if (first) group.insertBefore(button, first);
      else group.appendChild(button);
    }
    setText(button, tr('administration'));
    const parentActive = page === 'administration' || CHILD_PAGES.has(page);
    button.classList.toggle('active', parentActive);
    if (page === 'administration') button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');

    document.querySelectorAll('#navigation [data-page-target="permissions"], #navigation [data-page-target="moderation"], #navigation [data-page-target="audit"]').forEach(child => {
      child.classList.add('administration-child-nav');
      if (child.dataset.pageTarget === page) child.setAttribute('aria-current', 'page');
      else child.removeAttribute('aria-current');
    });
  }

  function ensurePage() {
    let page = document.querySelector('[data-page="administration"]');
    if (page) return page;
    const app = document.getElementById('app-panel');
    const saveBar = document.getElementById('save-bar');
    if (!app) return null;
    page = document.createElement('section');
    page.dataset.page = 'administration';
    page.className = 'page administration-hub';
    page.innerHTML = `
      <div class="page-toolbar">
        <div><strong data-admin-copy="title"></strong><span data-admin-copy="help"></span></div>
        <button type="button" id="administration-refresh"></button>
      </div>
      <section class="administration-player-workflow">
        <div><span class="shell-kicker">Paradigm</span><h2 data-admin-copy="playerWorkflow"></h2><p data-admin-copy="playerWorkflowHelp"></p></div>
        <button type="button" data-admin-go="players" data-admin-copy="openPlayers"></button>
      </section>
      <div id="administration-cards" class="administration-cards"></div>`;
    if (saveBar) app.insertBefore(page, saveBar);
    else app.appendChild(page);
    page.querySelector('#administration-refresh')?.addEventListener('click', loadAdministration);
    page.querySelectorAll('[data-admin-go]').forEach(button => button.addEventListener('click', () => requestNavigate(button.dataset.adminGo)));
    translatePage(page);
    return page;
  }

  function childTitle(page) {
    if (page === 'permissions') return tr('permissions');
    if (page === 'moderation') return tr('moderation');
    if (page === 'audit') return tr('audit');
    return '';
  }

  function ensureChildBreadcrumb(page) {
    document.querySelectorAll('.administration-child-breadcrumb').forEach(node => {
      if (node.closest('.page')?.dataset.page !== page) node.remove();
    });
    if (!CHILD_PAGES.has(page)) return;
    const section = document.querySelector(`[data-page="${page}"]`);
    if (!section) return;
    let breadcrumb = section.querySelector(':scope > .administration-child-breadcrumb');
    if (!breadcrumb) {
      breadcrumb = document.createElement('div');
      breadcrumb.className = 'administration-child-breadcrumb';
      breadcrumb.innerHTML = '<button type="button" data-admin-back></button><span aria-hidden="true">/</span><strong></strong>';
      breadcrumb.querySelector('[data-admin-back]')?.addEventListener('click', () => requestNavigate('administration'));
      section.prepend(breadcrumb);
    }
    setText(breadcrumb.querySelector('[data-admin-back]'), `← ${tr('back')}`);
    setText(breadcrumb.querySelector('strong'), childTitle(page));
  }

  function translatePage(page = document.querySelector('[data-page="administration"]')) {
    if (!page) return;
    const map = {
      title: 'administration', help: 'help', playerWorkflow: 'playerWorkflow', playerWorkflowHelp: 'playerWorkflowHelp', openPlayers: 'openPlayers'
    };
    page.querySelectorAll('[data-admin-copy]').forEach(node => {
      const key = map[node.dataset.adminCopy] || node.dataset.adminCopy;
      setText(node, tr(key));
    });
    setText(page.querySelector('#administration-refresh'), tr('refresh'));
  }

  function syncChrome(page = currentPage()) {
    ensureNavigation(page);
    ensureChildBreadcrumb(page);
  }

  function install() {
    if (installed) return;
    installed = true;
    pageInfo.administration = [tr('administration'), tr('help')];
    ensurePage();
    syncChrome();
    window.ParadigmDashboardRuntime?.afterPageLoad(async page => {
      syncChrome(page);
      if (page === 'administration') await loadAdministration();
    });
  }

  async function loadAdministration() {
    const root = document.getElementById('administration-cards');
    if (!root) return;
    const generation = ++loadGeneration;
    root.classList.add('is-loading');
    try {
      const [permissionResult, moderationResult, auditResult] = await Promise.allSettled([
        api('/api/permissions/summary'),
        api('/api/moderation/recent'),
        api('/api/audit/recent?page=1&pageSize=5')
      ]);
      if (generation !== loadGeneration) return;
      root.innerHTML = [
        permissionCard(permissionResult),
        moderationCard(moderationResult),
        auditCard(auditResult)
      ].join('');
      root.querySelectorAll('[data-admin-go]').forEach(button => button.addEventListener('click', () => requestNavigate(button.dataset.adminGo)));
    } finally {
      if (generation === loadGeneration) root.classList.remove('is-loading');
    }
  }

  function numeric(data, ...keys) {
    for (const key of keys) {
      const value = data?.[key];
      if (typeof value === 'number' && Number.isFinite(value)) return value;
    }
    return null;
  }

  function permissionCard(result) {
    if (result.status !== 'fulfilled') return hubCard('permissions', tr('permissions'), tr('permissionsHelp'), `<span class="administration-unavailable">${esc(tr('unavailable'))}</span>`);
    const data = result.value || {};
    const stats = [
      [tr('groups'), numeric(data, 'groups', 'groupCount')],
      [tr('users'), numeric(data, 'users', 'userCount')],
      [tr('nodes'), numeric(data, 'nodes', 'nodeCount')]
    ].filter(([, value]) => value != null);
    return hubCard('permissions', tr('permissions'), tr('permissionsHelp'), stats.length ? statGrid(stats) : '');
  }

  function moderationCard(result) {
    if (result.status !== 'fulfilled') return hubCard('moderation', tr('moderation'), tr('moderationHelp'), `<span class="administration-unavailable">${esc(tr('unavailable'))}</span>`);
    const data = result.value || {};
    const rows = Array.isArray(data) ? data : (data.punishments || data.rows || data.items || data.entries || []);
    const total = numeric(data, 'total', 'count') ?? (Array.isArray(rows) ? rows.length : null);
    return hubCard('moderation', tr('moderation'), tr('moderationHelp'), total == null ? '' : statGrid([[tr('recentActions'), total]]));
  }

  function auditCard(result) {
    if (result.status !== 'fulfilled') return hubCard('audit', tr('audit'), tr('auditHelp'), `<span class="administration-unavailable">${esc(tr('unavailable'))}</span>`);
    const data = result.value || {};
    const rows = Array.isArray(data) ? data : (data.entries || data.rows || data.items || []);
    const total = numeric(data, 'total', 'count') ?? (Array.isArray(rows) ? rows.length : null);
    return hubCard('audit', tr('audit'), tr('auditHelp'), total == null ? '' : statGrid([[tr('recentActivity'), total]]));
  }

  function hubCard(page, title, help, body) {
    return `<article class="administration-card">
      <div><span class="shell-kicker">${esc(title)}</span><h2>${esc(title)}</h2><p>${esc(help)}</p>${body || ''}</div>
      <button type="button" data-admin-go="${esc(page)}">${esc(tr('open'))}</button>
    </article>`;
  }

  function statGrid(rows) {
    return `<div class="administration-stats">${rows.map(([label, value]) => `<div><span>${esc(label)}</span><strong>${esc(value)}</strong></div>`).join('')}</div>`;
  }

  document.addEventListener('paradigm:language-changed', () => {
    pageInfo.administration = [tr('administration'), tr('help')];
    translatePage();
    syncChrome();
    if (currentPage() === 'administration') {
      setText(document.getElementById('page-title'), tr('administration'));
      setText(document.getElementById('page-subtitle'), tr('help'));
      document.title = `${tr('administration')} · Paradigm Dashboard`;
      loadAdministration();
    }
  });

  window.addEventListener('hashchange', () => window.setTimeout(syncChrome, 0));
  install();
})();
