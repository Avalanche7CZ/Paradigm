(() => {
  'use strict';

  const COPY = {
    en: {
      tickets: 'Tickets', ticketsHelp: 'Player support tickets across this network, with the full thread and staff workflow.',
      refresh: 'Refresh', search: 'Search tickets...', allStatuses: 'All statuses', allPriorities: 'All priorities', allCategories: 'All categories',
      allServers: 'All servers', allAssignees: 'Anyone', unassignedFilter: 'Unassigned', creator: 'Creator UUID',
      open: 'Open', unassigned: 'Unassigned', waitingStaff: 'Waiting staff', waitingPlayer: 'Waiting player', elevated: 'High / urgent',
      noTickets: 'No tickets match these filters.', select: 'Select a ticket to open the thread.',
      thread: 'Thread', history: 'History', reply: 'Reply', replyPlaceholder: 'Write a reply...', send: 'Send reply',
      claim: 'Claim', unclaim: 'Unclaim', assign: 'Assign', assignPlaceholder: 'Player name or UUID', resolve: 'Resolve', close: 'Close', reopen: 'Reopen',
      priority: 'Priority', status: 'Status', category: 'Category', assignee: 'Assignee', nobody: 'Unassigned',
      createdBy: 'Created by', origin: 'Origin server', created: 'Created', updated: 'Last activity', revision: 'Revision',
      openPlayer: 'Open player', openModeration: 'Moderation history', staff: 'staff', system: 'system',
      applied: 'Ticket updated.', page: 'Page', prev: 'Previous', next: 'Next', of: 'of'
    },
    cs: {
      tickets: 'Tikety', ticketsHelp: 'Hráčské tikety podpory v této síti, včetně celého vlákna a workflow personálu.',
      refresh: 'Obnovit', search: 'Hledat tikety...', allStatuses: 'Všechny stavy', allPriorities: 'Všechny priority', allCategories: 'Všechny kategorie',
      allServers: 'Všechny servery', allAssignees: 'Kdokoliv', unassignedFilter: 'Nepřiřazené', creator: 'UUID zakladatele',
      open: 'Otevřené', unassigned: 'Nepřiřazené', waitingStaff: 'Čeká na personál', waitingPlayer: 'Čeká na hráče', elevated: 'Vysoká / urgentní',
      noTickets: 'Žádné tikety neodpovídají filtrům.', select: 'Vyber tiket pro zobrazení vlákna.',
      thread: 'Vlákno', history: 'Historie', reply: 'Odpovědět', replyPlaceholder: 'Napiš odpověď...', send: 'Odeslat odpověď',
      claim: 'Převzít', unclaim: 'Uvolnit', assign: 'Přiřadit', assignPlaceholder: 'Jméno hráče nebo UUID', resolve: 'Vyřešit', close: 'Uzavřít', reopen: 'Znovu otevřít',
      priority: 'Priorita', status: 'Stav', category: 'Kategorie', assignee: 'Přiřazeno', nobody: 'Nepřiřazeno',
      createdBy: 'Vytvořil', origin: 'Zdrojový server', created: 'Vytvořeno', updated: 'Poslední aktivita', revision: 'Revize',
      openPlayer: 'Otevřít hráče', openModeration: 'Historie moderace', staff: 'personál', system: 'systém',
      applied: 'Tiket byl aktualizován.', page: 'Stránka', prev: 'Předchozí', next: 'Další', of: 'z'
    },
    ru: {
      tickets: 'Тикеты', ticketsHelp: 'Тикеты поддержки игроков в этой сети с полной перепиской и рабочим процессом персонала.',
      refresh: 'Обновить', search: 'Поиск тикетов...', allStatuses: 'Все статусы', allPriorities: 'Все приоритеты', allCategories: 'Все категории',
      allServers: 'Все серверы', allAssignees: 'Любой', unassignedFilter: 'Без назначения', creator: 'UUID создателя',
      open: 'Открытые', unassigned: 'Без назначения', waitingStaff: 'Ждут персонал', waitingPlayer: 'Ждут игрока', elevated: 'Высокий / срочный',
      noTickets: 'Нет тикетов по этим фильтрам.', select: 'Выберите тикет, чтобы открыть переписку.',
      thread: 'Переписка', history: 'История', reply: 'Ответить', replyPlaceholder: 'Написать ответ...', send: 'Отправить ответ',
      claim: 'Взять', unclaim: 'Освободить', assign: 'Назначить', assignPlaceholder: 'Имя игрока или UUID', resolve: 'Решить', close: 'Закрыть', reopen: 'Открыть заново',
      priority: 'Приоритет', status: 'Статус', category: 'Категория', assignee: 'Назначен', nobody: 'Не назначен',
      createdBy: 'Создал', origin: 'Сервер-источник', created: 'Создан', updated: 'Активность', revision: 'Ревизия',
      openPlayer: 'Открыть игрока', openModeration: 'История модерации', staff: 'персонал', system: 'система',
      applied: 'Тикет обновлён.', page: 'Страница', prev: 'Назад', next: 'Вперёд', of: 'из'
    }
  };

  let routesInstalled = false;
  let entries = [];
  let summary = {};
  let categories = [];
  let statuses = [];
  let priorities = [];
  let selectedKey = null;
  let selectedDetail = null;
  let total = 0;
  let page = 1;
  let pageSize = 25;
  let searchTimer = null;
  let loadGeneration = 0;
  let detailGeneration = 0;

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

  function relative(ms) {
    const value = Number(ms || 0);
    if (!value) return '-';
    const delta = Math.max(0, Date.now() - value);
    const minutes = Math.floor(delta / 60000);
    if (minutes < 1) return 'just now';
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;
    return `${Math.floor(hours / 24)}d ago`;
  }

  function absolute(ms) {
    const value = Number(ms || 0);
    return value ? new Date(value).toLocaleString() : '-';
  }

  function statusClass(status) {
    return `ticket-chip ticket-status-${String(status || '').toLowerCase()}`;
  }

  function priorityClass(priority) {
    return `ticket-chip ticket-priority-${String(priority || '').toLowerCase()}`;
  }

  function filters() {
    return {
      status: document.getElementById('ticket-filter-status')?.value || '',
      priority: document.getElementById('ticket-filter-priority')?.value || '',
      category: document.getElementById('ticket-filter-category')?.value || '',
      server: document.getElementById('ticket-filter-server')?.value || '',
      assignee: document.getElementById('ticket-filter-assignee')?.value || '',
      creator: document.getElementById('ticket-filter-creator')?.value || '',
      search: document.getElementById('ticket-search')?.value || ''
    };
  }

  function buildQuery() {
    const current = filters();
    const parts = [`page=${encodeURIComponent(page)}`, `pageSize=${encodeURIComponent(pageSize)}`];
    Object.entries(current).forEach(([key, value]) => {
      const trimmed = String(value || '').trim();
      if (trimmed) parts.push(`${key}=${encodeURIComponent(trimmed)}`);
    });
    return parts.join('&');
  }

  async function loadTickets() {
    const generation = ++loadGeneration;
    try {
      const data = await window.api(`/api/tickets?${buildQuery()}`);
      if (generation !== loadGeneration) return;
      entries = Array.isArray(data.entries) ? data.entries : [];
      summary = data.summary || {};
      categories = Array.isArray(data.categories) ? data.categories : [];
      statuses = Array.isArray(data.statuses) ? data.statuses : [];
      priorities = Array.isArray(data.priorities) ? data.priorities : [];
      total = Number(data.total || 0);
      page = Number(data.page || 1);
      pageSize = Number(data.pageSize || 25);
      syncFilterOptions();
      renderSummary();
      renderList();
    } catch (error) {
      if (generation !== loadGeneration) return;
      const list = document.getElementById('ticket-list');
      if (list) list.innerHTML = `<div class="notice-inline">${esc(error.message)}</div>`;
    }
  }

  async function loadTicket(key) {
    selectedKey = key;
    const generation = ++detailGeneration;
    try {
      const data = await window.api(`/api/tickets/item?id=${encodeURIComponent(key)}`);
      if (generation !== detailGeneration) return;
      selectedDetail = data;
      renderDetail();
      renderList();
    } catch (error) {
      if (generation !== detailGeneration) return;
      selectedDetail = null;
      const detail = document.getElementById('ticket-detail');
      if (detail) detail.innerHTML = `<div class="notice-inline">${esc(error.message)}</div>`;
    }
  }

  async function mutate(action, body) {
    if (!selectedKey) return;
    const payload = Object.assign({ id: selectedKey, revision: selectedDetail?.ticket?.revision }, body || {});
    try {
      await window.api(`/api/tickets/${action}`, { method: 'POST', body: JSON.stringify(payload) });
      window.notice?.(tr('applied'));
      await loadTicket(selectedKey);
      await loadTickets();
    } catch (error) {
      window.notice?.(error.message, true);
      if (error.code === 'stale_ticket' || error.code === 'ticket_already_claimed') {
        if (error.data?.ticket) {
          selectedDetail = Object.assign({}, selectedDetail, { ticket: error.data.ticket });
          renderDetail();
        }
        await loadTickets();
      }
    }
  }

  function option(value, label) {
    return `<option value="${esc(value)}">${esc(label)}</option>`;
  }

  function fillFilterOnce(id, allLabelKey, values, toOption) {
    const select = document.getElementById(id);
    if (!select || select.dataset.filled === 'true' || !values.length) return;
    select.innerHTML = option('', tr(allLabelKey)) + values.map(toOption).join('');
    select.dataset.filled = 'true';
  }

  function syncFilterOptions() {
    fillFilterOnce('ticket-filter-status', 'allStatuses', statuses, value => option(value, value));
    fillFilterOnce('ticket-filter-priority', 'allPriorities', priorities, value => option(value, value));
    fillFilterOnce('ticket-filter-category', 'allCategories', categories,
      item => option(item.id, item.displayName || item.id));
    const serverSelect = document.getElementById('ticket-filter-server');
    if (serverSelect) {
      const servers = Array.from(new Set(entries.map(item => item.originServerId).filter(Boolean)));
      const current = serverSelect.value;
      serverSelect.innerHTML = option('', tr('allServers')) + servers.map(value => option(value, value)).join('');
      serverSelect.value = current;
    }
  }

  function renderSummary() {
    const grid = document.getElementById('ticket-summary');
    if (!grid) return;
    const tiles = [
      ['open', summary.open || 0],
      ['unassigned', summary.unassigned || 0],
      ['waitingStaff', summary.waiting_staff || 0],
      ['waitingPlayer', summary.waiting_player || 0],
      ['elevated', summary.elevated || 0]
    ];
    grid.innerHTML = tiles.map(([key, value]) => `
      <div class="ticket-metric"><span class="shell-kicker">${esc(tr(key))}</span><strong>${esc(value)}</strong></div>`).join('');
  }

  function renderList() {
    const list = document.getElementById('ticket-list');
    if (!list) return;
    if (!entries.length) {
      list.innerHTML = `<div class="empty-state">${esc(tr('noTickets'))}</div>`;
      renderPagination();
      return;
    }
    list.innerHTML = entries.map(ticket => `
      <button type="button" class="selection-item ticket-row${ticket.ticketKey === selectedKey ? ' active' : ''}" data-ticket="${esc(ticket.ticketKey)}">
        <span class="ticket-row-head">
          <strong>${esc(ticket.ticketKey)}</strong>
          <span class="${statusClass(ticket.status)}">${esc(ticket.status)}</span>
          <span class="${priorityClass(ticket.priority)}">${esc(ticket.priority)}</span>
        </span>
        <span class="ticket-row-subject">${esc(ticket.subject || '-')}</span>
        <small>${esc(ticket.creatorName || '-')} · ${esc(ticket.category || '-')} · ${esc(relative(ticket.lastActivityAtMs))}${ticket.assigneeName ? ' · ' + esc(ticket.assigneeName) : ''}</small>
      </button>`).join('');
    list.querySelectorAll('[data-ticket]').forEach(button => {
      button.addEventListener('click', () => loadTicket(button.dataset.ticket));
    });
    renderPagination();
  }

  function renderPagination() {
    const holder = document.getElementById('ticket-pagination');
    if (!holder) return;
    const pages = Math.max(1, Math.ceil(total / Math.max(1, pageSize)));
    if (pages <= 1) {
      holder.innerHTML = '';
      return;
    }
    holder.innerHTML = `<span>${esc(tr('page'))} ${page} ${esc(tr('of'))} ${pages}</span>
      <button type="button" data-ticket-page="prev"${page <= 1 ? ' disabled' : ''}>${esc(tr('prev'))}</button>
      <button type="button" data-ticket-page="next"${page >= pages ? ' disabled' : ''}>${esc(tr('next'))}</button>`;
    holder.querySelector('[data-ticket-page="prev"]')?.addEventListener('click', () => {
      page = Math.max(1, page - 1);
      loadTickets();
    });
    holder.querySelector('[data-ticket-page="next"]')?.addEventListener('click', () => {
      page = Math.min(pages, page + 1);
      loadTickets();
    });
  }

  function renderDetail() {
    const holder = document.getElementById('ticket-detail');
    if (!holder) return;
    if (!selectedDetail || !selectedDetail.ticket) {
      holder.className = 'detail-editor empty-detail';
      holder.innerHTML = esc(tr('select'));
      return;
    }
    const ticket = selectedDetail.ticket;
    const messages = Array.isArray(selectedDetail.messages) ? selectedDetail.messages : [];
    const events = Array.isArray(selectedDetail.events) ? selectedDetail.events : [];
    const closed = ticket.status === 'CLOSED';
    const terminal = ticket.status === 'CLOSED' || ticket.status === 'RESOLVED';

    holder.className = 'detail-editor';
    holder.innerHTML = `
      <div class="detail-header">
        <div>
          <h2>${esc(ticket.ticketKey)} · ${esc(ticket.subject || '-')}</h2>
          <p><span class="${statusClass(ticket.status)}">${esc(ticket.status)}</span>
             <span class="${priorityClass(ticket.priority)}">${esc(ticket.priority)}</span>
             <span class="ticket-chip">${esc(ticket.category || '-')}</span></p>
        </div>
        <div class="ticket-links">
          <button type="button" data-ticket-open-player="${esc(ticket.creatorName || ticket.creatorUuid || '')}">${esc(tr('openPlayer'))}</button>
          <button type="button" data-ticket-open-moderation="${esc(ticket.creatorName || ticket.creatorUuid || '')}">${esc(tr('openModeration'))}</button>
        </div>
      </div>
      <section class="permission-section">
        <dl class="ticket-meta">
          <div><dt>${esc(tr('createdBy'))}</dt><dd>${esc(ticket.creatorName || '-')}</dd></div>
          <div><dt>${esc(tr('assignee'))}</dt><dd>${esc(ticket.assigneeName || tr('nobody'))}</dd></div>
          <div><dt>${esc(tr('origin'))}</dt><dd>${esc(ticket.originServerId || '-')}</dd></div>
          <div><dt>${esc(tr('created'))}</dt><dd>${esc(absolute(ticket.createdAtMs))}</dd></div>
          <div><dt>${esc(tr('updated'))}</dt><dd>${esc(relative(ticket.lastActivityAtMs))}</dd></div>
          <div><dt>${esc(tr('revision'))}</dt><dd>${esc(ticket.revision)}</dd></div>
        </dl>
      </section>
      <section class="permission-section">
        <h3>${esc(tr('thread'))}</h3>
        <div class="ticket-thread">
          ${messages.map(message => `
            <article class="ticket-message ticket-author-${esc(String(message.authorType || '').toLowerCase())}">
              <header><strong>${esc(message.authorName || tr('system'))}</strong>
                <span>${esc(String(message.authorType || '').toLowerCase())}</span>
                <small>${esc(absolute(message.createdAtMs))}${message.serverId ? ' · ' + esc(message.serverId) : ''}</small></header>
              <p>${esc(message.text || '')}</p>
            </article>`).join('') || `<div class="empty-state">—</div>`}
        </div>
        <div class="ticket-reply">
          <textarea id="ticket-reply-text" rows="3" placeholder="${esc(tr('replyPlaceholder'))}"${closed ? ' disabled' : ''}></textarea>
          <button type="button" id="ticket-reply-send"${closed ? ' disabled' : ''}>${esc(tr('send'))}</button>
        </div>
      </section>
      <section class="permission-section">
        <h3>${esc(tr('status'))}</h3>
        <div class="compact-form ticket-actions">
          ${ticket.assigneeUuid
            ? `<button type="button" data-ticket-action="unclaim"${closed ? ' disabled' : ''}>${esc(tr('unclaim'))}</button>`
            : `<button type="button" data-ticket-action="claim"${closed ? ' disabled' : ''}>${esc(tr('claim'))}</button>`}
          <button type="button" data-ticket-action="resolve"${terminal ? ' disabled' : ''}>${esc(tr('resolve'))}</button>
          <button type="button" data-ticket-action="close"${closed ? ' disabled' : ''}>${esc(tr('close'))}</button>
          <button type="button" data-ticket-action="reopen"${terminal ? '' : ' disabled'}>${esc(tr('reopen'))}</button>
        </div>
        <div class="compact-form ticket-actions">
          <input id="ticket-assign-target" type="text" placeholder="${esc(tr('assignPlaceholder'))}"${closed ? ' disabled' : ''}>
          <button type="button" id="ticket-assign-send"${closed ? ' disabled' : ''}>${esc(tr('assign'))}</button>
          <select id="ticket-set-priority"${closed ? ' disabled' : ''}>
            ${priorities.map(value => `<option value="${esc(value)}"${value === ticket.priority ? ' selected' : ''}>${esc(value)}</option>`).join('')}
          </select>
          <select id="ticket-set-status"${closed ? ' disabled' : ''}>
            ${statuses.map(value => `<option value="${esc(value)}"${value === ticket.status ? ' selected' : ''}>${esc(value)}</option>`).join('')}
          </select>
          <select id="ticket-set-category"${closed ? ' disabled' : ''}>
            ${categories.map(item => `<option value="${esc(item.id)}"${item.id === ticket.category ? ' selected' : ''}>${esc(item.displayName || item.id)}</option>`).join('')}
          </select>
        </div>
      </section>
      <section class="permission-section">
        <h3>${esc(tr('history'))}</h3>
        <div class="ticket-history">
          ${events.map(event => `
            <div><strong>${esc(event.eventType)}</strong>
              <span>${esc(event.actorName || tr('system'))}</span>
              <small>${esc(event.oldValue || '-')} → ${esc(event.newValue || '-')} · ${esc(absolute(event.createdAtMs))}</small></div>`).join('')
            || `<div class="empty-state">—</div>`}
        </div>
      </section>`;

    bindDetailEvents(ticket);
  }

  function bindDetailEvents(ticket) {
    document.getElementById('ticket-reply-send')?.addEventListener('click', () => {
      const text = document.getElementById('ticket-reply-text')?.value || '';
      if (!text.trim()) return;
      mutate('reply', { message: text });
    });
    document.getElementById('ticket-assign-send')?.addEventListener('click', () => {
      const target = document.getElementById('ticket-assign-target')?.value || '';
      if (!target.trim()) return;
      mutate('assign', { assignee: target.trim() });
    });
    document.querySelectorAll('[data-ticket-action]').forEach(button => {
      button.addEventListener('click', () => mutate(button.dataset.ticketAction, {}));
    });
    document.getElementById('ticket-set-priority')?.addEventListener('change', event => {
      if (event.target.value !== ticket.priority) mutate('priority', { value: event.target.value });
    });
    document.getElementById('ticket-set-status')?.addEventListener('change', event => {
      if (event.target.value !== ticket.status) mutate('status', { value: event.target.value });
    });
    document.getElementById('ticket-set-category')?.addEventListener('change', event => {
      if (event.target.value !== ticket.category) mutate('category', { value: event.target.value });
    });
    document.querySelector('[data-ticket-open-player]')?.addEventListener('click', event => {
      openPlayer(event.currentTarget.dataset.ticketOpenPlayer);
    });
    document.querySelector('[data-ticket-open-moderation]')?.addEventListener('click', event => {
      openModeration(event.currentTarget.dataset.ticketOpenModeration);
    });
  }

  function waitFor(selector, attempts = 20) {
    return new Promise(resolve => {
      let tries = 0;
      const tick = () => {
        const element = document.querySelector(selector);
        if (element || tries >= attempts) return resolve(element);
        tries += 1;
        window.setTimeout(tick, 50);
      };
      tick();
    });
  }

  async function openModeration(target) {
    if (!target) return;
    await window.requestNavigate('moderation');
    const search = await waitFor('#moderation-search');
    if (search) search.value = target;
    const find = await waitFor('#moderation-find');
    find?.click();
  }

  async function openPlayer(target) {
    if (!target) return;
    await window.requestNavigate('players');
    const search = await waitFor('#shell-player-search');
    if (search) {
      search.value = target;
      search.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }

  function ensureTicketsPage() {
    if (document.querySelector('[data-page="tickets"]')) return;
    const app = document.getElementById('app-panel');
    const saveBar = document.getElementById('save-bar');
    if (!app) return;
    const section = document.createElement('section');
    section.dataset.page = 'tickets';
    section.className = 'page tickets-page';
    section.innerHTML = `
      <div class="page-toolbar">
        <div><strong>${esc(tr('tickets'))}</strong><span>${esc(tr('ticketsHelp'))}</span></div>
        <button id="ticket-refresh" type="button">${esc(tr('refresh'))}</button>
      </div>
      <div id="ticket-summary" class="metric-grid ticket-summary"></div>
      <div class="filter-bar ticket-filters">
        <input id="ticket-search" type="search" placeholder="${esc(tr('search'))}">
        <select id="ticket-filter-status"><option value="">${esc(tr('allStatuses'))}</option></select>
        <select id="ticket-filter-priority"><option value="">${esc(tr('allPriorities'))}</option></select>
        <select id="ticket-filter-category"><option value="">${esc(tr('allCategories'))}</option></select>
        <select id="ticket-filter-server"><option value="">${esc(tr('allServers'))}</option></select>
        <select id="ticket-filter-assignee">
          <option value="">${esc(tr('allAssignees'))}</option>
          <option value="unassigned">${esc(tr('unassignedFilter'))}</option>
        </select>
        <input id="ticket-filter-creator" type="text" placeholder="${esc(tr('creator'))}">
      </div>
      <div class="master-detail tickets-workspace">
        <aside>
          <div id="ticket-list" class="selection-list"></div>
          <div id="ticket-pagination" class="pagination"></div>
        </aside>
        <div id="ticket-detail" class="detail-editor empty-detail">${esc(tr('select'))}</div>
      </div>`;
    if (saveBar) app.insertBefore(section, saveBar);
    else app.appendChild(section);
    bindPageEvents();
  }

  function bindPageEvents() {
    document.getElementById('ticket-refresh')?.addEventListener('click', () => loadTickets());
    document.getElementById('ticket-search')?.addEventListener('input', scheduleSearch);
    document.getElementById('ticket-filter-creator')?.addEventListener('input', scheduleSearch);
    ['ticket-filter-status', 'ticket-filter-priority', 'ticket-filter-category',
      'ticket-filter-server', 'ticket-filter-assignee'].forEach(id => {
      document.getElementById(id)?.addEventListener('change', () => {
        page = 1;
        loadTickets();
      });
    });
  }

  function scheduleSearch() {
    if (searchTimer) window.clearTimeout(searchTimer);
    searchTimer = window.setTimeout(() => {
      searchTimer = null;
      page = 1;
      loadTickets();
    }, 250);
  }

  function installNav() {
    const groups = document.querySelectorAll('#navigation .nav-group');
    let target = null;
    groups.forEach(group => {
      if (group.querySelector('[data-page-target="moderation"]')) target = group;
    });
    if (!target || target.querySelector('[data-page-target="tickets"]')) return;
    const button = document.createElement('button');
    button.type = 'button';
    button.dataset.pageTarget = 'tickets';
    button.textContent = tr('tickets');
    button.addEventListener('click', () => window.requestNavigate('tickets'));
    const moderation = target.querySelector('[data-page-target="moderation"]');
    if (moderation) moderation.after(button);
    else target.appendChild(button);
  }

  function installRoutes() {
    if (routesInstalled) return;
    routesInstalled = true;
    pageInfo.tickets = [tr('tickets'), tr('ticketsHelp')];
    ensureTicketsPage();
    installNav();
    window.ParadigmDashboardRuntime?.afterPageLoad(async currentPage => {
      if (currentPage === 'tickets') {
        ensureTicketsPage();
        installNav();
        await loadTickets();
      }
    });
  }

  function resetSessionState() {
    loadGeneration += 1;
    detailGeneration += 1;
    entries = [];
    summary = {};
    selectedKey = null;
    selectedDetail = null;
    total = 0;
    page = 1;
    document.getElementById('ticket-list')?.replaceChildren();
    document.getElementById('ticket-detail')?.replaceChildren();
    document.getElementById('ticket-summary')?.replaceChildren();
  }

  function refreshLanguage() {
    const active = document.querySelector('.page.active')?.dataset.page === 'tickets';
    pageInfo.tickets = [tr('tickets'), tr('ticketsHelp')];
    document.querySelector('[data-page="tickets"]')?.remove();
    ensureTicketsPage();
    if (active) {
      document.querySelector('[data-page="tickets"]')?.classList.add('active');
      loadTickets();
    }
    const navButton = document.querySelector('#navigation [data-page-target="tickets"]');
    if (navButton) navButton.textContent = tr('tickets');
  }

  document.addEventListener('paradigm:session-changed', resetSessionState);
  document.addEventListener('paradigm:language-changed', refreshLanguage);
  window.addEventListener('hashchange', () => window.setTimeout(installNav, 0));

  installRoutes();
})();
