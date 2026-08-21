const state = {
  snapshot: null,
  edits: new Map(),
  editPages: new Map(),
  errors: new Map(),
  csrf: null,
  capabilities: null,
  currentPageEditable: null,
  page: 'overview',
  advanced: false,
  permissionView: 'groups',
  permissionPage: 1,
  permissionData: { summary: {}, groups: [], users: [], nodes: [] },
  selectedPermissionTarget: null,
  selectedCommand: null,
  commandDraft: null,
  commandIsNew: false,
  commandDirty: false,
  auditPage: 1,
  auditRows: [],
  auditTotal: 0,
  moderationPage: 1,
  moderationIdentity: null,
  pageSize: 25,
  selectedListRows: new Map(),
  motdSelectedLine: 0,
  motdCompact: true,
  openPreviews: new Set(),
  pendingConfirm: null,
  tablistActiveEditor: 'tablist-player-format',
  chatNameSample: { player_name: 'Alex', player_group: 'admin', player_world: 'minecraft:overworld', player_ping: '42' },
  hoverLineFocus: null,
  hologramData: null,
  selectedHologram: null,
  hologramDraft: null,
  discordStatus: null,
  confirmReturnFocus: null,
  confirmMode: 'confirm',
  servers: [],
  networkActive: false,
  remote: {
    serverId: null,
    snapshot: null,
    section: null,
    scopes: new Map(),
    edits: new Map(),
    editPages: new Map(),
    errors: new Map()
  }
};

const $ = id => document.getElementById(id);
const themeStorageKey = 'paradigm-dashboard-theme';
const sidebarStorageKey = 'paradigm-dashboard-sidebar-collapsed';
const advancedStorageKey = 'paradigm-dashboard-advanced-details';
let mountedPageAction = null;
let mountedPageToolbar = null;

function restorePageAction() {
  if (mountedPageAction && mountedPageToolbar) mountedPageToolbar.appendChild(mountedPageAction);
  mountedPageAction = null;
  mountedPageToolbar = null;
  const mount = $('page-actions');
  if (mount) mount.replaceChildren();
}

function syncThemeLayout() {
  restorePageAction();
  if (document.documentElement.dataset.theme !== 'dark') return;
  const toolbar = document.querySelector('.page.active > .page-toolbar');
  const action = toolbar?.children[1];
  const mount = $('page-actions');
  if (!toolbar || !action || !mount) return;
  mountedPageAction = action;
  mountedPageToolbar = toolbar;
  mount.appendChild(action);
}

function setNavigationOpen(open) {
  const mobile = window.matchMedia('(max-width: 980px)').matches;
  const effectiveOpen = mobile && open;
  document.body.classList.toggle('nav-open', effectiveOpen);
  const toggle = $('nav-toggle');
  const sidebar = document.querySelector('.sidebar');
  const scrim = $('nav-scrim');
  if (toggle) {
    toggle.setAttribute('aria-expanded', String(effectiveOpen));
    toggle.setAttribute('aria-label', effectiveOpen ? 'Close navigation' : 'Open navigation');
  }
  if (sidebar) sidebar.inert = mobile && !effectiveOpen;
  if (scrim) {
    scrim.setAttribute('aria-hidden', String(!effectiveOpen));
    scrim.tabIndex = effectiveOpen ? 0 : -1;
  }
}

function setSidebarCollapsed(collapsed, persist = false) {
  document.body.classList.toggle('sidebar-collapsed', collapsed);
  const toggle = $('nav-toggle');
  const collapse = $('sidebar-collapse');
  if (toggle && !window.matchMedia('(max-width: 980px)').matches) {
    toggle.setAttribute('aria-label', collapsed ? 'Show navigation' : 'Navigation is visible');
    toggle.setAttribute('aria-expanded', String(!collapsed));
  }
  if (collapse) collapse.setAttribute('aria-label', collapsed ? 'Show navigation' : 'Hide navigation');
  if (persist) localStorage.setItem(sidebarStorageKey, String(collapsed));
}

function toggleNavigation() {
  if (window.matchMedia('(max-width: 980px)').matches) {
    setNavigationOpen(!document.body.classList.contains('nav-open'));
    return;
  }
  setSidebarCollapsed(!document.body.classList.contains('sidebar-collapsed'), true);
}

function filterNavigation(query) {
  const needle = query.trim().toLocaleLowerCase();
  let totalVisible = 0;
  document.querySelectorAll('.nav-group').forEach(group => {
    let visible = 0;
    group.querySelectorAll('[data-page-target]').forEach(button => {
      const matches = !needle || button.textContent.toLocaleLowerCase().includes(needle);
      button.classList.toggle('nav-filtered', !matches);
      if (matches) {
        visible += 1;
        totalVisible += 1;
      }
    });
    group.classList.toggle('nav-filtered', visible === 0);
  });
  $('nav-empty')?.classList.toggle('hidden', totalVisible !== 0);
}

function setTheme(theme, persist = false) {
  const next = theme === 'dark' ? 'dark' : 'classic';
  document.documentElement.dataset.theme = next;
  const toggle = $('theme-toggle');
  if (toggle) {
    toggle.textContent = next === 'dark' ? 'Classic' : 'Dark';
    toggle.setAttribute('aria-label', `Switch to ${next === 'dark' ? 'classic' : 'dark'} theme`);
    toggle.setAttribute('aria-pressed', String(next === 'dark'));
  }
  if (persist) localStorage.setItem(themeStorageKey, next);
  syncThemeLayout();
}

function setAdvancedShown(show, persist = false) {
  state.advanced = Boolean(show);
  document.body.classList.toggle('show-advanced', state.advanced);
  document.querySelectorAll('.advanced-toggle').forEach(button => {
    button.textContent = state.advanced ? 'Hide advanced details' : 'Advanced details';
    button.setAttribute('aria-pressed', String(state.advanced));
  });
  if (persist) localStorage.setItem(advancedStorageKey, String(state.advanced));
}

setTheme(localStorage.getItem(themeStorageKey) || 'classic');
setSidebarCollapsed(localStorage.getItem(sidebarStorageKey) === 'true');
setNavigationOpen(false);
setAdvancedShown(localStorage.getItem(advancedStorageKey) === 'true');

const pageInfo = {
  overview: ['Overview', 'Server administration at a glance.'],
  servers: ['Servers', 'Local identity and observed network heartbeats.'],
  storage: ['Storage', 'Runtime provider health and migration planning.'],
  audit: ['Audit', 'Searchable administrative history.'],
  general: ['General', 'Modules and common server behavior.'],
  teleports: ['Teleports', 'Homes, warps, spawn, and teleport requests.'],
  chat: ['Chat Editor', 'Formatting, messages, and chat behavior.'],
  announcements: ['Announcements', 'Scheduled messages across supported channels.'],
  restart: ['Restart', 'Schedules, warnings, and restart presentation.'],
  motd: ['MOTD Editor', 'Join and server-list presentation.'],
  tablist: ['Tablist', 'Player-list formatting, sorting, and world overrides.'],
  holograms: ['Holograms', 'Server-side text displays, locations, and refresh behavior.'],
  menus: ['Menus', 'Server-side inventory GUI definitions, slots, conditions, and actions.'],
  customCommands: ['Custom Commands', 'Structured custom command definitions.'],
  commands: ['Command Settings', 'Built-in command availability.'],
  cooldowns: ['Cooldowns', 'Cooldown and warmup timing.'],
  dashboard: ['Dashboard', 'Local dashboard security and runtime settings.'],
  discord: ['Discord', 'Chat relay, event notifications, and connection state.'],
  permissions: ['Permission Editor', 'Groups, users, assignments, and nodes.'],
  moderation: ['Moderation', 'Player history and moderation actions.'],
  storageConfig: ['Storage Configuration', 'Provider settings and masked connection state.']
};

async function api(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase();
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (state.csrf && method !== 'GET' && method !== 'HEAD' && path !== '/api/auth/login') headers['X-Paradigm-CSRF'] = state.csrf;
  const response = await fetch(path, { credentials: 'same-origin', ...options, method, headers });
  const body = await response.json().catch(() => ({ ok: false, error: { code: 'invalid_response', message: 'Invalid server response.' } }));
  if (!body.ok) {
    const error = new Error(body.error?.message || 'Request failed.');
    error.code = body.error?.code || 'request_failed';
    error.data = body.data;
    error.warnings = body.warnings || [];
    throw error;
  }
  return body.data;
}

async function checkAuth() {
  const params = new URLSearchParams(location.search);
  const urlToken = params.get('token');
  if (urlToken) {
    $('login-token').value = urlToken;
    await login(true);
    return;
  }
  try {
    const status = await api('/api/auth/status');
    if (status.authenticated || !status.requireLogin) {
      state.csrf = status.csrfToken || null;
      state.capabilities = status.capabilities || null;
      showApp(status.principal);
    } else showLogin();
  } catch (error) {
    showLogin();
    $('login-message').textContent = error.message;
  }
}

function showLogin() {
  document.body.classList.remove('is-authenticated');
  restorePageAction();
  setNavigationOpen(false);
  $('login-panel').classList.remove('hidden');
  $('app-panel').classList.add('hidden');
  $('session-state').textContent = 'Not logged in';
  $('page-title').textContent = 'Dashboard Login';
  $('page-subtitle').textContent = 'Authenticate with a one-time in-game link.';
  document.title = 'Login · Paradigm Dashboard';
}

function showApp(principal) {
  document.body.classList.add('is-authenticated');
  $('login-panel').classList.add('hidden');
  $('app-panel').classList.remove('hidden');
  $('session-state').textContent = principal?.name ? principal.name : 'Local Admin';
  document.dispatchEvent(new CustomEvent('paradigm:capabilities-changed', { detail: state.capabilities }));
  const requested = validPage(location.hash.slice(1)) ? location.hash.slice(1) : 'overview';
  navigate(accessiblePage(requested), false);
  loadConfigSnapshot();
}

async function login(fromUrl = false) {
  const token = $('login-token').value.trim();
  if (!token) return setMessage('login-message', 'Enter a one-time login code.', true);
  try {
    const data = await api('/api/auth/login', { method: 'POST', body: JSON.stringify({ token }) });
    state.csrf = data.csrfToken || null;
    state.capabilities = data.capabilities || null;
    if (fromUrl) history.replaceState(null, '', `${location.pathname}${location.hash || '#overview'}`);
    setMessage('login-message', '');
    showApp(data.principal);
  } catch (error) {
    setMessage('login-message', error.message, true);
  }
}

async function logout() {
  try { await api('/api/auth/logout', { method: 'POST', body: '{}' }); } catch (_) {}
  state.csrf = null;
  showLogin();
}

function validPage(page) { return Object.prototype.hasOwnProperty.call(pageInfo, page); }

function pageAccessible(page) {
  if (!state.capabilities || !state.capabilities.pages) return true;
  return state.capabilities.pages[page] !== false;
}

function pageEditable(page) {
  if (!state.capabilities || !state.capabilities.config) return true;
  const section = state.capabilities.config[page];
  return !section || section.edit !== false;
}

function firstAccessiblePage() {
  return Object.keys(pageInfo).find(pageAccessible) || 'overview';
}

function accessiblePage(page) {
  return (validPage(page) && pageAccessible(page)) ? page : firstAccessiblePage();
}

function navigate(page, updateHash = true) {
  if (!validPage(page)) page = 'overview';
  if (!pageAccessible(page)) page = firstAccessiblePage();
  state.page = page;
  document.querySelectorAll('[data-page-target]').forEach(button => {
    const active = button.dataset.pageTarget === page;
    button.classList.toggle('active', active);
    if (active) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
  document.querySelectorAll('.page').forEach(section => section.classList.toggle('active', section.dataset.page === page));
  $('page-title').textContent = pageInfo[page][0];
  $('page-subtitle').textContent = pageInfo[page][1];
  document.title = `${pageInfo[page][0]} · Paradigm Dashboard`;
  if (updateHash && location.hash !== `#${page}`) history.pushState({ page }, '', `#${page}`);
  setNavigationOpen(false);
  syncThemeLayout();
  loadPage(page);
  updateSaveBar();
}

async function requestNavigate(page) {
  const hasPageEdits = [...state.editPages.values()].some(value => value === state.page);
  if ((hasPageEdits || state.commandDirty) && !await confirmAction('Leave this page and discard its unsaved changes?', true)) return;
  if (hasPageEdits) discardCurrentPage();
  state.commandDirty = false;
  navigate(page);
}

async function loadPage(page) {
  if (!pageAccessible(page)) return;
  if (page === 'overview') await loadOverview();
  if (page === 'servers') await loadServers();
  if (page === 'storage') await loadStorage();
  if (page === 'storageConfig') await loadStorageConfiguration();
  if (page === 'discord') await loadDiscord();
  if (page === 'permissions') await loadPermissions();
  if (page === 'customCommands') await loadCustomCommands();
  if (page === 'holograms') await loadHolograms();
  if (page === 'menus' && window.ParadigmMenus) await window.ParadigmMenus.load();
  if (page === 'moderation') await loadModeration();
  if (page === 'audit') await loadAudit();
  if (state.snapshot) renderConfiguration();
}

async function loadConfigSnapshot(force = false) {
  if (state.edits.size && force && !await confirmAction('Discard unsaved dashboard changes and reload from disk?', true)) return;
  try {
    state.snapshot = await api('/api/config/snapshot');
    if (force) clearEdits();
    renderConfiguration();
  } catch (error) { notice(error.message, true); }
}

function fieldsFor(categories) {
  const wanted = new Set(categories);
  return (state.snapshot?.fields || []).filter(field => wanted.has(field.category));
}

function pageCategories(page = state.page) {
  const section = document.querySelector(`[data-page="${page}"]`);
  if (section?.dataset.categories) return section.dataset.categories.split(',');
  if (page === 'chat') return ['chat'];
  if (page === 'announcements') return ['announcements'];
  if (page === 'restart') return ['restart'];
  if (page === 'motd') return ['motd'];
  if (page === 'tablist') return ['tablist'];
  return [];
}

function renderConfiguration() {
  if (!state.snapshot) return;
  renderConfigContainer('general-fields', fieldsFor(['modules', 'command_groups', 'admin_utilities']), 'general', { readOnly: !pageEditable('general') });
  renderConfigContainer('teleport-fields', fieldsFor(['teleports']), 'teleports', { readOnly: !pageEditable('teleports') });
  renderChat();
  renderAnnouncements();
  renderRestart();
  renderMotd();
  renderTablist();
  if (state.page === 'moderation' && $('moderation-ban-screen')) renderModerationBanScreen();
  renderConfigContainer('command-fields', filterByInput(fieldsFor(['commands']), 'command-search'), 'commands', { readOnly: !pageEditable('commands') });
  renderConfigContainer('cooldown-fields', filterByInput(fieldsFor(['cooldowns']), 'cooldown-search'), 'cooldowns', { readOnly: !pageEditable('cooldowns') });
  renderConfigContainer('dashboard-fields', fieldsFor(['dashboard']), 'dashboard', { readOnly: !pageEditable('dashboard') });
  renderDiscord();
  updateSaveBar();
}

function renderConfigContainer(id, fields, page, options = {}) {
  const root = $(id);
  if (!root) return;
  const groups = new Map();
  fields.forEach(field => {
    const group = options.groupBy ? options.groupBy(field) : readableGroup(field);
    if (!groups.has(group)) groups.set(group, []);
    groups.get(group).push(field);
  });
  root.innerHTML = groups.size ? [...groups].map(([name, rows]) => `<section class="config-section"><h2>${esc(name)}</h2>${rows.map(field => configRow(field, page, options)).join('')}</section>`).join('') : empty('No settings found.');
  wireConfigControls(root, page, options.store || state);
}

function readableGroup(field) {
  const key = field.key || '';
  if (key.startsWith('cooldowns.cooldown.')) return 'Cooldowns';
  if (key.startsWith('cooldowns.warmup.')) return 'Warmups';
  if (key.startsWith('main.')) return 'Module Settings';
  return categoryTitle(field.category);
}

function categoryTitle(value) {
  return String(value || 'Settings').replaceAll('_', ' ').replace(/\b\w/g, c => c.toUpperCase());
}

function configRow(field, page, options = {}) {
  const store = options.store || state;
  const value = store.edits.has(field.key) ? store.edits.get(field.key) : clone(field.value?.value);
  const dirty = store.edits.has(field.key);
  const error = store.errors.get(field.key);
  const control = configControl(field, value, options);
  const reload = field.reloadBehavior === 'RESTART_REQUIRED' ? 'Server restart required' : field.reloadBehavior === 'RELOAD_REQUIRED' ? 'Apply reload after saving' : '';
  const origin = field.origin ? `<span class="origin-badge origin-${attr(field.origin)}">${esc(field.origin)}</span>` : '';
  return `<div class="config-row ${dirty ? 'is-dirty' : ''} ${error ? 'has-error' : ''}" data-field-row="${attr(field.key)}">
    <div class="config-label"><strong>${esc(humanLabel(field))}</strong>${origin}<small>${esc(field.help || '')}</small>${reload ? `<span class="reload-note">${esc(reload)}</span>` : ''}<span class="advanced-detail">${esc(field.key)} · ${esc(field.owner || '')} · ${esc(field.type)} · default ${esc(display(field.defaultValue?.value))}</span>${error ? `<div class="field-error">${esc(error)}</div>` : ''}</div>
    <div class="config-control"><div class="config-control-line">${control}${field.editable && !options.readOnly ? `<button type="button" data-reset-field="${attr(field.key)}" title="Reset to default">Reset</button>` : ''}</div></div>
  </div>`;
}

function humanLabel(field) {
  if (field.label && !/^[a-z][A-Za-z0-9]+$/.test(field.label)) return field.label;
  const raw = field.label || field.key.split('.').pop();
  return raw.replace(/([a-z])([A-Z])/g, '$1 $2').replaceAll('_', ' ').replace(/\b\w/g, c => c.toUpperCase());
}

function configControl(field, value, options = {}) {
  const label = attr(humanLabel(field));
  if (!field.editable || field.type === 'READ_ONLY_TEXT' || options.readOnly) return `<div class="readonly-value">${esc(display(value))}</div>`;
  if (field.type === 'SECRET_MASKED') return `<div class="readonly-value">${field.value?.set ? 'Configured' : 'Not configured'}</div>`;
  if (field.type === 'BOOLEAN') return `<label class="switch"><input data-config-key="${attr(field.key)}" data-config-type="BOOLEAN" type="checkbox" aria-label="${label}" ${value ? 'checked' : ''}><span aria-hidden="true"></span></label>`;
  if (field.type === 'ENUM') return `<select data-config-key="${attr(field.key)}" data-config-type="ENUM" aria-label="${label}">${(field.options || []).map(option => `<option ${option === value ? 'selected' : ''}>${esc(option)}</option>`).join('')}</select>`;
  if (field.type === 'INTEGER' || field.type === 'DOUBLE' || field.type === 'DURATION') return `<input data-config-key="${attr(field.key)}" data-config-type="${attr(field.type)}" type="number" aria-label="${label}" min="${attr(field.min ?? '')}" max="${attr(field.max ?? '')}" step="${attr(field.step ?? (field.type === 'DOUBLE' ? 0.1 : 1))}" value="${attr(value ?? '')}">`;
  if (field.type === 'STRING_LIST') return listControl(field, Array.isArray(value) ? value : []);
  const large = options.largeStrings || field.multiline || String(value || '').length > 70;
  return large ? `<textarea data-config-key="${attr(field.key)}" data-config-type="STRING" aria-label="${label}">${esc(value ?? '')}</textarea>` : `<input data-config-key="${attr(field.key)}" data-config-type="STRING" aria-label="${label}" value="${attr(value ?? '')}">`;
}

function listControl(field, values) {
  const rows = values.map((value, index) => `<div class="reorder-row" draggable="true" data-drag-key="${attr(field.key)}" data-drag-index="${index}"><span class="reorder-handle" title="Drag to reorder" aria-hidden="true">::</span><textarea class="reorder-editor" rows="1" data-list-key="${attr(field.key)}" data-list-index="${index}" aria-label="${attr(humanLabel(field))} item ${index + 1}">${esc(value)}</textarea><div class="reorder-actions"><button data-list-move="up" data-key="${attr(field.key)}" data-index="${index}" title="Move up" aria-label="Move up">&#8593;</button><button data-list-move="down" data-key="${attr(field.key)}" data-index="${index}" title="Move down" aria-label="Move down">&#8595;</button><button data-list-duplicate data-key="${attr(field.key)}" data-index="${index}" title="Duplicate" aria-label="Duplicate">+</button><button data-list-remove data-key="${attr(field.key)}" data-index="${index}" title="Delete" aria-label="Delete">&#215;</button></div></div>`).join('');
  return `<div class="reorder-list" data-list-control="${attr(field.key)}">${rows || '<div class="reorder-empty">No messages configured.</div>'}<button class="reorder-add" data-list-add data-key="${attr(field.key)}">Add Item</button></div>`;
}

function wireConfigControls(root, page, store = state) {
  root.querySelectorAll('[data-config-key]').forEach(input => input.addEventListener('input', () => {
    setEdit(input.dataset.configKey, readInput(input, input.dataset.configType), page, false, store);
    input.closest('.config-row')?.classList.add('is-dirty');
  }));
  root.querySelectorAll('[data-reset-field]').forEach(button => button.addEventListener('click', () => {
    if (store === state.remote) clearRemoteEdit(button.dataset.resetField);
    else resetField(button.dataset.resetField, page);
  }));
  root.querySelectorAll('[data-list-key]').forEach(input => input.addEventListener('input', () => {
    const values = listValue(input.dataset.listKey, store);
    values[Number(input.dataset.listIndex)] = input.value;
    setEdit(input.dataset.listKey, values, page, false, store);
  }));
  root.querySelectorAll('[data-list-add]').forEach(button => button.addEventListener('click', () => mutateList(button.dataset.key, page, values => values.push(''), store)));
  root.querySelectorAll('[data-list-remove]').forEach(button => button.addEventListener('click', () => mutateList(button.dataset.key, page, values => values.splice(Number(button.dataset.index), 1), store)));
  root.querySelectorAll('[data-list-duplicate]').forEach(button => button.addEventListener('click', () => mutateList(button.dataset.key, page, values => values.splice(Number(button.dataset.index) + 1, 0, values[Number(button.dataset.index)]), store)));
  root.querySelectorAll('[data-list-move]').forEach(button => button.addEventListener('click', () => mutateList(button.dataset.key, page, values => move(values, Number(button.dataset.index), button.dataset.listMove === 'up' ? -1 : 1), store)));
  wireDragRows(root, '[data-drag-key]', row => row.dataset.dragKey, row => Number(row.dataset.dragIndex), (key, from, to) => mutateList(key, page, values => moveTo(values, from, to), store));
  wireAutoGrow(root);
}

function wireAutoGrow(root) {
  root.querySelectorAll('textarea.reorder-editor, textarea.auto-grow').forEach(input => {
    const resize = () => { input.style.height = 'auto'; input.style.height = `${Math.min(input.scrollHeight, 168)}px`; input.style.overflowY = input.scrollHeight > 168 ? 'auto' : 'hidden'; };
    input.addEventListener('input', resize);
    resize();
  });
}

function collapsiblePreview(key, classes = '') {
  const open = state.openPreviews.has(key);
  return `<div class="preview-disclosure ${open ? 'is-open' : ''}" data-preview-disclosure="${attr(key)}"><button type="button" class="preview-toggle" data-preview-toggle="${attr(key)}" aria-expanded="${open}">${open ? '&#9660;' : '&#9654;'} Preview</button><div class="minecraft-preview ${classes} ${open ? '' : 'hidden'}" data-preview-panel="${attr(key)}" role="region" aria-label="Rendered Minecraft preview" aria-hidden="${!open}"></div></div>`;
}

function wirePreviewDisclosures(root, renderer) {
  root.querySelectorAll('[data-preview-toggle]').forEach(button => button.addEventListener('click', () => {
    const key = button.dataset.previewToggle;
    const disclosure = button.closest('[data-preview-disclosure]');
    const open = !state.openPreviews.has(key);
    if (open) state.openPreviews.add(key); else state.openPreviews.delete(key);
    disclosure.classList.toggle('is-open', open);
    button.setAttribute('aria-expanded', String(open));
    button.innerHTML = `${open ? '&#9660;' : '&#9654;'} Preview`;
    const panel = disclosure.querySelector('[data-preview-panel]');
    panel.classList.toggle('hidden', !open);
    panel.setAttribute('aria-hidden', String(!open));
    if (open) renderer(panel, key);
  }));
  root.querySelectorAll('.preview-disclosure.is-open [data-preview-panel]').forEach(panel => renderer(panel, panel.dataset.previewPanel));
}

function setEdit(key, value, page, rerender = true, store = state) {
  store.edits.set(key, clone(value));
  store.editPages.set(key, page);
  store.errors.delete(key);
  if (store === state.remote) {
    if (rerender) renderRemoteConfig(); else updateRemoteSaveBar();
  } else if (rerender) renderConfiguration(); else {
    updateSaveBar();
  }
}

function resetField(key, page) {
  const field = findField(key);
  if (!field) return;
  setEdit(key, clone(field.defaultValue?.value), page);
}

function clearRemoteEdit(key) {
  state.remote.edits.delete(key);
  state.remote.editPages.delete(key);
  state.remote.errors.delete(key);
  renderRemoteConfig();
}

function mutateList(key, page, mutation, store = state) {
  const values = listValue(key, store);
  mutation(values);
  setEdit(key, values, page, true, store);
}

function listValue(key, store = state) {
  let field = store === state.remote ? findField(key, state.remote.snapshot) : findField(key);
  if (store === state.remote && field) {
    const section = (state.remote.snapshot?.sections || []).find(item => item.section === field.category);
    field = remoteFieldForScope(field, remoteSectionScope(section));
  }
  return clone(store.edits.has(key) ? store.edits.get(key) : (field?.value?.value || []));
}

function findField(key, snapshot = state.snapshot) { return snapshot?.fields?.find(field => field.key === key); }
function readInput(input, type) {
  if (type === 'BOOLEAN') return input.checked;
  if (type === 'INTEGER' || type === 'DURATION') return input.value === '' ? null : Number.parseInt(input.value, 10);
  if (type === 'DOUBLE') return input.value === '' ? null : Number(input.value);
  return input.value;
}

async function saveCurrentPage() {
  const operations = [...state.edits].filter(([key]) => state.editPages.get(key) === state.page).map(([key, value]) => ({ key, value }));
  if (!operations.length) return;
  try {
    const result = await api('/api/config/patch', { method: 'POST', body: JSON.stringify({ revision: state.snapshot.revision, operations }) });
    operations.forEach(({ key }) => { state.edits.delete(key); state.editPages.delete(key); state.errors.delete(key); });
    await loadConfigSnapshot();
    const needsReload = operations.some(({ key }) => findField(key)?.reloadBehavior === 'RELOAD_REQUIRED');
    const needsRestart = operations.some(({ key }) => findField(key)?.reloadBehavior === 'RESTART_REQUIRED');
    notice(`Saved ${operations.length} change${operations.length === 1 ? '' : 's'}.${needsRestart ? ' A server restart is required.' : needsReload ? ' Apply a module reload to activate every change.' : ''}`, false, needsReload ? () => applyReload(state.page) : null, needsRestart);
    if (result?.rejected?.length) result.rejected.forEach(error => state.errors.set(error.key, error.reason));
  } catch (error) {
    if (error.code === 'stale_revision') notice('Configuration changed on disk. Reload the page before saving again.', true);
    (error.data?.rejected || []).forEach(item => state.errors.set(item.key, item.reason));
    renderConfiguration();
    if (!error.data?.rejected?.length) notice(error.message, true);
  }
}

async function applyReload(page) {
  try {
    const result = await api('/api/config/apply', { method: 'POST', body: JSON.stringify({ page }) });
    notice(result.message || 'Reload applied.');
  } catch (error) { notice(error.message, true); }
}

function discardCurrentPage() {
  [...state.editPages].filter(([, page]) => page === state.page).forEach(([key]) => { state.edits.delete(key); state.editPages.delete(key); state.errors.delete(key); });
  renderConfiguration();
}

function clearEdits() { state.edits.clear(); state.editPages.clear(); state.errors.clear(); updateSaveBar(); }

function updateSaveBar() {
  const count = [...state.editPages.values()].filter(page => page === state.page).length;
  state.currentPageEditable = pageEditable(state.page);
  $('unsaved-count').textContent = count;
  $('save-bar').classList.toggle('hidden', count === 0);
  $('save-changes').disabled = count === 0 || state.currentPageEditable === false;
  const restart = [...state.edits.keys()].some(key => findField(key)?.reloadBehavior === 'RESTART_REQUIRED');
  const reload = [...state.edits.keys()].some(key => findField(key)?.reloadBehavior === 'RELOAD_REQUIRED');
  $('apply-state').textContent = restart ? 'Restart required' : reload ? 'Reload required' : 'Applies live';
}

function filterByInput(fields, id) {
  const query = ($(id)?.value || '').trim().toLowerCase();
  return !query ? fields : fields.filter(field => `${field.label} ${field.help} ${field.key}`.toLowerCase().includes(query));
}

function move(values, index, delta) {
  const target = index + delta;
  if (target < 0 || target >= values.length) return;
  [values[index], values[target]] = [values[target], values[index]];
}

function moveTo(values, from, to) {
  if (from === to || from < 0 || to < 0 || from >= values.length || to >= values.length) return;
  const [item] = values.splice(from, 1);
  values.splice(to, 0, item);
}

function wireDragRows(root, selector, keyOf, indexOf, onDrop) {
  let dragging = null;
  root.querySelectorAll(selector).forEach(row => {
    row.addEventListener('dragstart', event => { dragging = { key: keyOf(row), index: indexOf(row) }; event.dataTransfer.effectAllowed = 'move'; });
    row.addEventListener('dragover', event => { event.preventDefault(); event.dataTransfer.dropEffect = 'move'; });
    row.addEventListener('drop', event => { event.preventDefault(); if (dragging && dragging.key === keyOf(row)) onDrop(dragging.key, dragging.index, indexOf(row)); dragging = null; });
  });
}

function clone(value) { return value == null ? value : JSON.parse(JSON.stringify(value)); }
function display(value) { return Array.isArray(value) ? `${value.length} items` : value == null || value === '' ? '-' : String(value); }

const PLAYER_NAME_KEYS = ['chat.enablePlayerNameHover', 'chat.playerNameFormat', 'chat.playerNameHover', 'chat.playerNameHoverVariants', 'chat.playerNameClickAction', 'chat.playerNameClickValue'];
const NAME_MARK = '\u0001';

function renderChat() {
  const fields = fieldsFor(['chat']);
  const root = $('chat-fields');
  const readOnly = !pageEditable('chat');
  const rest = fields.filter(field => !PLAYER_NAME_KEYS.includes(field.key));
  const featureFields = rest.filter(field => !isFormattedChatField(field));
  const formattedFields = rest.filter(isFormattedChatField);
  root.innerHTML = `${featureFields.length ? `<section class="config-section"><h2>Features</h2>${featureFields.map(field => configRow(field, 'chat', { readOnly })).join('')}</section>` : ''}<section class="config-section formatted-fields-section"><h2>Formatting and Messages</h2>${formattedFields.map(chatFormatRow).join('')}</section>${playerNameEditor(fields)}`;
  wireConfigControls(root, 'chat');
  wireFormattingEditors(root, 'chat', renderChatFieldPreview);
  wirePreviewDisclosures(root, renderChatFieldPreview);
  wirePlayerNameEditor(root);
}

function playerNameEditor(fields) {
  const field = key => fields.find(item => item.key === key);
  const enable = field('chat.enablePlayerNameHover');
  const nameFormat = field('chat.playerNameFormat');
  const hover = field('chat.playerNameHover');
  const action = field('chat.playerNameClickAction');
  const command = field('chat.playerNameClickValue');
  const variants = field('chat.playerNameHoverVariants');
  if (!enable || !nameFormat || !hover) return '';
  return `<section class="config-section player-name-editor" id="player-name-editor">
    <h2>Player Name</h2>
    <p>The player name inside the chat format is rendered as its own component, so it can carry hover text and one click action. It needs custom chat formatting to be enabled.</p>
    ${configRow(enable, 'chat')}
    ${chatFormatRow(nameFormat)}
    <div class="config-row" data-field-row="${attr(hover.key)}">
      <div class="config-label"><strong>Hover Lines</strong><small>${esc(hover.help || '')}</small></div>
      <div class="config-control formatted-control">${formattingToolbar(hover.key, chatPlaceholders(hover))}${listControl(hover, listValue(hover.key))}</div>
    </div>
    ${clickActionRow(action, command)}
    ${hoverVariantEditor(variants)}
    <div class="config-row player-name-sample">
      <div class="config-label"><strong>Sample Player</strong><small>Only affects this preview. Nothing is saved.</small></div>
      <div class="config-control"><div class="compact-form">
        <label>Name<input data-name-sample="player_name" value="${attr(nameSample().player_name)}"></label>
        <label>Group<input data-name-sample="player_group" value="${attr(nameSample().player_group)}"></label>
        <label>World<input data-name-sample="player_world" value="${attr(nameSample().player_world)}"></label>
        <label>Ping<input data-name-sample="player_ping" value="${attr(nameSample().player_ping)}"></label>
      </div></div>
    </div>
    <div class="player-name-preview" id="player-name-preview">
      <div class="player-name-preview-block"><span class="preview-caption">Chat line</span><div class="minecraft-preview" id="player-name-line-preview"></div></div>
      <div class="player-name-preview-block"><span class="preview-caption">Hover card (default lines)</span><div class="minecraft-preview preview-hover-card" id="player-name-hover-preview"></div></div>
    </div>
    <div class="player-name-issues" id="player-name-issues"></div>
  </section>`;
}

function clickActionRow(action, command) {
  if (!action || !command) return '';
  const selected = String(valueOf(action.key) ?? 'none');
  const disabled = selected === 'none';
  return `<div class="config-row player-name-click-row" data-field-row="${attr(action.key)}">
    <div class="config-label"><strong>Click Action</strong><small>${esc(action.help || '')}</small></div>
    <div class="config-control"><div class="compact-form">
      <label>Type<select data-config-key="${attr(action.key)}" data-config-type="ENUM">${(action.options || []).map(option => `<option ${option === selected ? 'selected' : ''}>${esc(option)}</option>`).join('')}</select></label>
      <label>Command<input data-config-key="${attr(command.key)}" data-config-type="STRING" ${disabled ? 'disabled' : ''} value="${attr(valueOf(command.key) ?? '')}"></label>
    </div></div>
  </div>`;
}

function hoverVariantEditor(field) {
  if (!field) return '';
  const variants = parseHoverVariants(listValue(field.key));
  const rows = variants.map((variant, index) => `<div class="hover-variant-row" data-variant-index="${index}">
    <div class="compact-form"><label>Permission<input data-variant-permission="${index}" value="${attr(variant.permission || '')}"></label><button type="button" data-variant-remove="${index}" title="Delete variant">&#215;</button></div>
    <textarea class="format-editor auto-grow" rows="2" data-variant-hover="${index}">${esc((variant.hover || []).join('\n'))}</textarea>
  </div>`).join('');
  return `<div class="config-row" data-field-row="${attr(field.key)}">
    <div class="config-label"><strong>Hover Variants</strong><small>${esc(field.help || '')}</small></div>
    <div class="config-control"><div class="hover-variant-list">${rows || '<div class="reorder-empty">No variants. Everyone sees the hover lines above.</div>'}<button type="button" class="reorder-add" id="hover-variant-add">Add Variant</button></div></div>
  </div>`;
}

function parseHoverVariants(rows) {
  return (rows || []).map(row => { try { return JSON.parse(row); } catch (_) { return null; } }).filter(Boolean);
}

function mutateHoverVariants(mutation, rerender = true) {
  const variants = parseHoverVariants(listValue('chat.playerNameHoverVariants'));
  mutation(variants);
  setEdit('chat.playerNameHoverVariants', variants.map(variant => JSON.stringify(variant)), 'chat', rerender);
}

function nameSample() {
  return state.chatNameSample;
}

function chatSampleValues() {
  const sample = nameSample();
  return {
    player: sample.player_name, player_name: sample.player_name, player_uuid: '3f8a1c22-0000-4000-8000-1d2e3f4a5b6c',
    player_group: sample.player_group, player_groups: sample.player_group, player_primary_group: sample.player_group,
    group: sample.player_group, prefix: `[${sample.player_group}] `, suffix: '',
    player_prefix: `[${sample.player_group}] `, player_suffix: '',
    player_world: sample.player_world, player_dimension: String(sample.player_world).split(':').pop(),
    player_ping: sample.player_ping, player_level: '12', player_health: '20.0', max_player_health: '20.0',
    message: 'Hello'
  };
}

function wireHoverLineToolbar(root) {
  const toolbar = root.querySelector('[data-format-for="chat.playerNameHover"]');
  if (!toolbar) return;
  const lines = [...root.querySelectorAll('[data-list-key="chat.playerNameHover"]')];
  if (!lines.length) return;
  lines.forEach(input => input.addEventListener('focus', () => { state.hoverLineFocus = input; }));
  const target = () => (state.hoverLineFocus && root.contains(state.hoverLineFocus) ? state.hoverLineFocus : lines[lines.length - 1]);
  toolbar.querySelectorAll('[data-format-tag]').forEach(button => button.addEventListener('click', () => applyFormatInput(target(), button.dataset.formatTag)));
  toolbar.querySelectorAll('[data-placeholder-for]').forEach(select => select.addEventListener('change', () => {
    if (!select.value) return;
    const input = target();
    insertAtCursor(input, select.value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.focus();
    select.value = '';
  }));
}

function wirePlayerNameEditor(root) {
  wireHoverLineToolbar(root);
  root.querySelectorAll('[data-name-sample]').forEach(input => input.addEventListener('input', () => {
    state.chatNameSample[input.dataset.nameSample] = input.value;
    refreshPlayerNamePreview();
  }));
  root.querySelectorAll('[data-variant-permission]').forEach(input => input.addEventListener('input', () => {
    mutateHoverVariants(variants => { variants[Number(input.dataset.variantPermission)].permission = input.value; }, false);
    refreshPlayerNamePreview();
  }));
  root.querySelectorAll('[data-variant-hover]').forEach(input => input.addEventListener('input', () => {
    mutateHoverVariants(variants => { variants[Number(input.dataset.variantHover)].hover = input.value.split('\n'); }, false);
    refreshPlayerNamePreview();
  }));
  root.querySelectorAll('[data-variant-remove]').forEach(button => button.addEventListener('click', () =>
    mutateHoverVariants(variants => variants.splice(Number(button.dataset.variantRemove), 1))));
  $('hover-variant-add')?.addEventListener('click', () =>
    mutateHoverVariants(variants => variants.push({ permission: 'paradigm.chat.staff-hover', hover: ['<red>Staff</red>', '{player_name}'] })));
  root.querySelectorAll('#player-name-editor [data-config-key], #player-name-editor [data-list-key]')
    .forEach(input => input.addEventListener('input', refreshPlayerNamePreview));
  refreshPlayerNamePreview();
}

function refreshPlayerNamePreview() {
  renderPlayerNameLinePreview($('player-name-line-preview'));
  renderPlayerNameHoverPreview($('player-name-hover-preview'));
  renderPlayerNameIssues($('player-name-issues'));
}

function activeHoverLines() {
  return listValue('chat.playerNameHover');
}

function playerNameHoverEnabled() {
  return valueOf('chat.enablePlayerNameHover') === true && valueOf('chat.enableCustomChatFormat') !== false;
}

function renderPlayerNameLinePreview(panel) {
  if (!panel) return;
  const samples = chatSampleValues();
  const format = String(valueOf('chat.customChatFormat') || '');
  const line = format.replaceAll('{player_name}', NAME_MARK).replaceAll('{player}', NAME_MARK);
  const fragment = buildMinecraftPreview(line, samples);
  const interactive = playerNameHoverEnabled() || String(valueOf('chat.playerNameClickAction') || 'none') !== 'none';
  spliceNameMark(fragment, buildPlayerNameChip(samples, interactive));
  panel.replaceChildren(fragment);
}

function buildPlayerNameChip(samples, interactive) {
  const chip = document.createElement('span');
  chip.className = interactive ? 'preview-interactive' : '';
  chip.append(buildMinecraftPreview(String(valueOf('chat.playerNameFormat') || '{player_name}'), samples));
  if (interactive) chip.title = 'Interactive: this segment carries the hover and click action.';
  return chip;
}

function spliceNameMark(fragment, replacement) {
  const walker = document.createTreeWalker(fragment, NodeFilter.SHOW_TEXT);
  const hits = [];
  while (walker.nextNode()) if (walker.currentNode.nodeValue.includes(NAME_MARK)) hits.push(walker.currentNode);
  hits.forEach(node => {
    const parts = node.nodeValue.split(NAME_MARK);
    const replaced = document.createDocumentFragment();
    parts.forEach((part, index) => {
      if (index) replaced.append(replacement.cloneNode(true));
      replaced.append(document.createTextNode(part));
    });
    node.replaceWith(replaced);
  });
}

function renderPlayerNameHoverPreview(panel) {
  if (!panel) return;
  if (!playerNameHoverEnabled()) {
    panel.replaceChildren(document.createTextNode('Hover is disabled. Chat renders exactly as it did before.'));
    return;
  }
  renderMinecraftPreview(panel, activeHoverLines(), chatSampleValues());
}

function renderPlayerNameIssues(panel) {
  if (!panel) return;
  const issues = playerNameIssues();
  panel.replaceChildren();
  issues.forEach(issue => {
    const row = document.createElement('div');
    row.className = 'field-error';
    row.textContent = issue;
    panel.append(row);
  });
  panel.classList.toggle('hidden', !issues.length);
}

function playerNameIssues() {
  const issues = [];
  const format = String(valueOf('chat.customChatFormat') || '');
  if (!format.includes('{player_name}') && !format.includes('{player}')) {
    issues.push('The chat format has no {player_name}, so there is no name to attach hover or click behavior to.');
  }
  if (valueOf('chat.enableCustomChatFormat') === false) {
    issues.push('Custom chat formatting is disabled, so the player name settings have no effect.');
  }
  const action = String(valueOf('chat.playerNameClickAction') || 'none');
  if (action !== 'none') {
    const command = String(valueOf('chat.playerNameClickValue') ?? '');
    if (!command.trim()) issues.push('The click action needs a command value.');
    else if (/[<>§\n\r]/.test(command)) issues.push('The click command cannot contain formatting markup or line breaks.');
    else if (command.length > 256) issues.push('The click command must be at most 256 characters.');
    else if (command.trim().replace(/^\/+/, '') === '') issues.push('The click command needs a command name after the slash.');
    else if (!command.trimStart().startsWith('/')) issues.push('The click command will be saved with a leading slash so clients run it as a command.');
  }
  const hover = listValue('chat.playerNameHover');
  if (hover.length > 16) issues.push('A hover template supports at most 16 lines.');
  if (hover.some(line => String(line).length > 256)) issues.push('Hover lines must be at most 256 characters.');
  const permissions = new Set();
  parseHoverVariants(listValue('chat.playerNameHoverVariants')).forEach(variant => {
    const permission = String(variant.permission || '').trim();
    if (!permission) issues.push('Every hover variant needs a permission node.');
    else if (!/^[A-Za-z0-9_.*-]+$/.test(permission)) issues.push(`Variant permission "${permission}" may only use letters, digits, dot, dash, underscore, and *.`);
    else if (permissions.has(permission.toLowerCase())) issues.push(`Variant permission "${permission}" is used more than once.`);
    else permissions.add(permission.toLowerCase());
    if (!(variant.hover || []).some(line => String(line).trim())) issues.push('Every hover variant needs at least one hover line.');
  });
  return issues;
}

function isFormattedChatField(field) {
  return field.type === 'STRING' && /format|message/i.test(field.key) && !/symbol/i.test(field.key);
}

function chatFormatRow(field) {
  const value = state.edits.has(field.key) ? state.edits.get(field.key) : field.value?.value;
  const placeholders = chatPlaceholders(field);
  return `<div class="config-row formatted-config-row" data-field-row="${attr(field.key)}"><div class="config-label"><strong>${esc(humanLabel(field))}</strong><small>${esc(field.help || '')}</small></div><div class="config-control formatted-control">${formattingToolbar(field.key, placeholders)}<textarea class="format-editor auto-grow" rows="2" data-config-key="${attr(field.key)}" data-config-type="STRING">${esc(value || '')}</textarea>${collapsiblePreview(`chat:${field.key}`, 'compact-preview')}</div></div>`;
}

function chatPlaceholders(field) {
  const found = [...String(field.help || '').matchAll(/\{[a-z0-9_]+}/gi)].map(match => match[0]);
  if (/customChatFormat$/i.test(field.key)) found.push('{message}', '{prefix}', '{suffix}', '{group}');
  return [...new Set(found)];
}

function renderChatFieldPreview(panel, previewKey) {
  const key = previewKey.replace(/^chat:/, '');
  const field = findField(key);
  let value = String(valueOf(key) || '');
  const positional = /privateMessageTo/i.test(key) ? ['Morgan', 'Hello world'] : /privateMessageFrom|staffChat/i.test(key) ? ['Alex', 'Hello world'] : [];
  positional.forEach(sample => { value = value.replace('%s', sample); });
  renderMinecraftPreview(panel, value || field?.label || '', chatSampleValues());
}

function renderDiscord() {
  const root = $('discord-fields');
  if (!root) return;
  const fields = fieldsFor(['discord']);
  const readOnly = !pageEditable('discord');
  root.innerHTML = fields.length
    ? `<section class="config-section"><h2>Discord</h2>${fields.map(field => isDiscordMessageField(field) ? discordMessageRow(field) : configRow(field, 'discord', { readOnly })).join('')}</section>`
    : empty('No Discord settings found.');
  root.querySelectorAll('.discord-template-preview').forEach(panel => panel.setAttribute('aria-label', 'Rendered Discord message preview'));
  wireConfigControls(root, 'discord');
  wirePreviewDisclosures(root, renderDiscordFieldPreview);
  root.querySelectorAll('.discord-format-editor[data-config-key]').forEach(input => input.addEventListener('input', () => {
    const previewKey = `discord:${input.dataset.configKey}`;
    const panel = root.querySelector(`[data-preview-panel="${CSS.escape(previewKey)}"]`);
    if (panel && state.openPreviews.has(previewKey)) renderDiscordFieldPreview(panel, previewKey);
  }));
}

function isDiscordMessageField(field) {
  return field.type === 'STRING' && /Format$/.test(field.key) && !/\.presenceFormat$/.test(field.key);
}

function discordMessageRow(field) {
  const value = state.edits.has(field.key) ? state.edits.get(field.key) : field.value?.value;
  const dirty = state.edits.has(field.key);
  const error = state.errors.get(field.key);
  const reload = field.reloadBehavior === 'RESTART_REQUIRED' ? 'Server restart required' : field.reloadBehavior === 'RELOAD_REQUIRED' ? 'Apply reload after saving' : '';
  const previewClass = isMinecraftDiscordFormat(field.key) ? 'compact-preview' : 'compact-preview discord-template-preview';
  return `<div class="config-row formatted-config-row ${dirty ? 'is-dirty' : ''} ${error ? 'has-error' : ''}" data-field-row="${attr(field.key)}">
    <div class="config-label"><strong>${esc(humanLabel(field))}</strong><small>${esc(field.help || '')}</small>${reload ? `<span class="reload-note">${esc(reload)}</span>` : ''}<span class="advanced-detail">${esc(field.key)} · ${esc(field.owner || '')} · ${esc(field.type)} · default ${esc(display(field.defaultValue?.value))}</span>${error ? `<div class="field-error">${esc(error)}</div>` : ''}</div>
    <div class="config-control formatted-control"><div class="config-control-line"><textarea class="discord-format-editor auto-grow" rows="3" data-config-key="${attr(field.key)}" data-config-type="STRING" aria-label="${attr(humanLabel(field))}">${esc(value ?? '')}</textarea><button type="button" data-reset-field="${attr(field.key)}" title="Reset to default">Reset</button></div>${collapsiblePreview(`discord:${field.key}`, previewClass)}</div>
  </div>`;
}

function isMinecraftDiscordFormat(key) {
  return /^discord\.minecraft(?:Chat|Reply|Edit|Delete)Format$/.test(key);
}

function renderDiscordFieldPreview(panel, previewKey) {
  if (!panel) return;
  const key = previewKey.replace(/^discord:/, '');
  const raw = String(valueOf(key) ?? '');
  const samples = discordPreviewSamples(key);
  if (isMinecraftDiscordFormat(key)) {
    panel.classList.remove('discord-message-preview');
    renderMinecraftPreview(panel, raw, samples);
    return;
  }
  renderDiscordMessagePreview(panel, raw, samples);
}

function discordPreviewSamples(key) {
  const samples = {
    player: 'Alex', message: 'Hello from Minecraft!', name: 'Alex', reply_name: 'Morgan',
    reply_message: 'Are you joining us?', online: '12', max: '100', prefix: '[Admin] ', suffix: '', group: 'admin',
    advancement: 'Stone Age', description: 'Mine stone with your new pickaxe', sender: 'Alex', command: 'spawn',
    command_root: 'spawn', icon: '🔨', action: 'Ban', target: 'Morgan', actor: 'Moderator', reason: 'Griefing',
    duration: '7 days', duration_suffix: ' for 7 days', punishment_id: 'P-0123456789ABCDEF',
    expiry: '22 August 2026', time: '30 seconds', seconds: '30'
  };
  if (/deathFormat$/.test(key)) samples.message = 'Alex fell from a high place';
  return samples;
}

function renderDiscordMessagePreview(panel, raw, samples) {
  panel.classList.add('discord-message-preview');
  panel.replaceChildren();

  const avatar = document.createElement('div');
  avatar.className = 'discord-preview-avatar';
  avatar.textContent = 'P';

  const body = document.createElement('div');
  body.className = 'discord-preview-body';
  const header = document.createElement('div');
  header.className = 'discord-preview-header';
  const author = document.createElement('strong');
  author.textContent = 'Paradigm';
  const bot = document.createElement('span');
  bot.className = 'discord-preview-bot';
  bot.textContent = 'APP';
  const time = document.createElement('span');
  time.className = 'discord-preview-time';
  time.textContent = 'Today at 12:00';
  header.append(author, bot, time);

  const content = document.createElement('div');
  content.className = 'discord-preview-content';
  const rendered = replacePreviewSamples(String(raw || ''), samples);
  rendered.split('\n').forEach((line, index) => {
    if (index) content.append(document.createElement('br'));
    appendDiscordMarkdown(content, line);
  });
  if (!rendered) content.textContent = 'Empty messages are not sent.';

  body.append(header, content);
  panel.append(avatar, body);
}

function appendDiscordMarkdown(parent, text) {
  const pattern = /\*\*([^*\n]+)\*\*|__([^_\n]+)__|~~([^~\n]+)~~|`([^`\n]+)`|\*([^*\n]+)\*|_([^_\n]+)_/g;
  let offset = 0;
  for (const match of text.matchAll(pattern)) {
    parent.append(document.createTextNode(text.slice(offset, match.index)));
    const element = document.createElement(match[1] ? 'strong' : match[2] ? 'u' : match[3] ? 's' : match[4] ? 'code' : 'em');
    element.textContent = match.slice(1).find(value => value !== undefined) || '';
    parent.append(element);
    offset = match.index + match[0].length;
  }
  parent.append(document.createTextNode(text.slice(offset)));
}

function formattingToolbar(key, placeholders = []) {
  return `<div class="format-toolbar compact-format-toolbar" data-format-for="${attr(key)}" aria-label="Formatting controls"><button type="button" data-format-tag="bold" title="Bold" aria-label="Bold"><strong>B</strong></button><button type="button" data-format-tag="italic" title="Italic" aria-label="Italic"><em>I</em></button><button type="button" data-format-tag="underline" title="Underline" aria-label="Underline"><u>U</u></button><button type="button" data-format-tag="strikethrough" title="Strikethrough" aria-label="Strikethrough"><s>S</s></button><button type="button" data-format-tag="color:#55FFFF">Color</button><button type="button" data-format-tag="gradient:#22D3EE:#A78BFA">Gradient</button><button type="button" data-format-tag="rainbow">Rainbow</button>${placeholders.length ? `<select data-placeholder-for="${attr(key)}" aria-label="Insert placeholder"><option value="">Insert placeholder</option>${placeholders.map(value => `<option value="${attr(value)}">${esc(value)}</option>`).join('')}</select>` : ''}</div>`;
}

function wireFormattingEditors(root, page, previewRenderer) {
  root.querySelectorAll('[data-format-tag]').forEach(button => button.addEventListener('click', () => {
    const input = root.querySelector(`[data-config-key="${CSS.escape(button.closest('[data-format-for]').dataset.formatFor)}"]`);
    if (input) applyFormatInput(input, button.dataset.formatTag);
  }));
  root.querySelectorAll('[data-placeholder-for]').forEach(select => select.addEventListener('change', () => {
    if (!select.value) return;
    const input = root.querySelector(`[data-config-key="${CSS.escape(select.dataset.placeholderFor)}"]`);
    if (input) { insertAtCursor(input, select.value); input.dispatchEvent(new Event('input', { bubbles: true })); input.focus(); }
    select.value = '';
  }));
  root.querySelectorAll('.format-editor[data-config-key]').forEach(input => input.addEventListener('input', () => {
    const preview = root.querySelector(`[data-preview-panel="${CSS.escape(`${page}:${input.dataset.configKey}`)}"]`);
    if (preview && state.openPreviews.has(`${page}:${input.dataset.configKey}`)) previewRenderer(preview, `${page}:${input.dataset.configKey}`);
  }));
}

function applyFormatInput(input, tag) {
  const start = input.selectionStart ?? input.value.length;
  const end = input.selectionEnd ?? start;
  const selected = input.value.slice(start, end) || 'text';
  input.setRangeText(`<${tag}>${selected}</${tag.split(':')[0]}>`, start, end, 'end');
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.focus();
}

function renderAnnouncements() {
  const fields = fieldsFor(['announcements']);
  const messageFields = fields.filter(field => field.type === 'STRING_LIST' && /Messages$/.test(field.key));
  const bossbarColor = fields.find(field => /bossbarColor$/.test(field.key));
  renderConfigContainer('announcement-settings', fields.filter(field => !messageFields.includes(field) && field !== bossbarColor), 'announcements', { readOnly: !pageEditable('announcements'), groupBy: field => /Interval|Time/.test(field.key) ? 'Timing' : /Enable/.test(field.key) ? 'Channels' : 'Presentation' });
  const root = $('announcement-editor');
  if (!root) return;
  root.innerHTML = `<h2>Announcement Messages</h2><p>Edit, duplicate, delete, and reorder messages for each channel.</p>${messageFields.map(field => announcementChannel(field, bossbarColor)).join('')}`;
  wireConfigControls(root, 'announcements');
  root.querySelectorAll('[data-announcement-select]').forEach(input => input.addEventListener('input', () => updateAnnouncementRowPreview(input.dataset.announcementKey, Number(input.dataset.announcementSelect))));
  root.querySelectorAll('[data-title-part]').forEach(input => input.addEventListener('input', () => updateTitleMessage(input)));
  wirePreviewDisclosures(root, (panel, key) => { const [, fieldKey, index] = key.split('::'); renderAnnouncementPreview(panel, fieldKey, Number(index)); });
}

function announcementChannel(field, bossbarColor) {
  const values = listValue(field.key);
  const channel = field.key.match(/announcements\.([^.]+)Messages$/)?.[1] || 'global';
  const settings = channel === 'bossbar' && bossbarColor ? `<label class="inline-control">Color<select data-config-key="${attr(bossbarColor.key)}" data-config-type="STRING">${['BLUE','GREEN','PINK','PURPLE','RED','WHITE','YELLOW'].map(value => `<option ${String(value).toLowerCase() === String(valueOf(bossbarColor.key)).toLowerCase() ? 'selected' : ''}>${value}</option>`).join('')}</select></label>` : '';
  const rows = values.map((value, index) => {
    const title = channel === 'title' ? splitTitleMessage(value) : null;
    const editor = title
      ? `<div class="reorder-editor-stack"><input data-title-part="title" data-announcement-key="${attr(field.key)}" data-announcement-select="${index}" value="${attr(title.title)}" placeholder="Title"><input data-title-part="subtitle" data-announcement-key="${attr(field.key)}" data-announcement-select="${index}" value="${attr(title.subtitle)}" placeholder="Subtitle"></div>`
      : `<textarea class="reorder-editor" rows="1" data-list-key="${attr(field.key)}" data-list-index="${index}" data-announcement-key="${attr(field.key)}" data-announcement-select="${index}">${esc(value)}</textarea>`;
    return `<div class="reorder-row announcement-message-row" draggable="true" data-drag-key="${attr(field.key)}" data-drag-index="${index}"><span class="reorder-handle">::</span>${editor}<div class="reorder-actions"><button data-list-move="up" data-key="${attr(field.key)}" data-index="${index}" title="Move up">&#8593;</button><button data-list-move="down" data-key="${attr(field.key)}" data-index="${index}" title="Move down">&#8595;</button><button data-list-duplicate data-key="${attr(field.key)}" data-index="${index}" title="Duplicate">+</button><button data-list-remove data-key="${attr(field.key)}" data-index="${index}" title="Delete">&#215;</button></div><div class="row-preview">${collapsiblePreview(`announcement::${field.key}::${index}`, 'compact-preview')}</div></div>`;
  }).join('');
  return `<section class="announcement-channel"><div class="channel-heading"><h3>${esc(categoryTitle(channel))}</h3>${settings}</div><div class="reorder-list">${rows || '<div class="reorder-empty">No messages in this channel.</div>'}<button class="reorder-add" data-list-add data-key="${attr(field.key)}">Add Message</button></div></section>`;
}

function splitTitleMessage(value) {
  const separator = String(value).indexOf('||');
  return separator < 0 ? { title: String(value), subtitle: '' } : { title: String(value).slice(0, separator).trim(), subtitle: String(value).slice(separator + 2).trim() };
}

function renderAnnouncementPreview(panel, key, index) {
  renderMinecraftPreview(panel, listValue(key)[index] || '', {});
}

function updateAnnouncementRowPreview(key, index) {
  const previewKey = `announcement::${key}::${index}`;
  if (!state.openPreviews.has(previewKey)) return;
  renderAnnouncementPreview(document.querySelector(`[data-preview-panel="${CSS.escape(previewKey)}"]`), key, index);
}

function updateTitleMessage(input) {
  const key = input.dataset.announcementKey;
  const index = Number(input.dataset.announcementSelect);
  const values = listValue(key);
  const parts = splitTitleMessage(values[index] || '');
  parts[input.dataset.titlePart] = input.value;
  values[index] = parts.subtitle ? `${parts.title} || ${parts.subtitle}` : parts.title;
  setEdit(key, values, 'announcements', false);
  updateAnnouncementRowPreview(key, index);
}

function renderRestart() {
  const fields = fieldsFor(['restart']);
  const scheduleKeys = new Set(['restart.restartType', 'restart.restartInterval', 'restart.realTimeInterval']);
  const warningFields = fields.filter(field => /timerBroadcast|preRestartCommands/.test(field.key));
  const otherFields = fields.filter(field => !scheduleKeys.has(field.key) && !warningFields.includes(field));
  const root = $('restart-settings');
  root.innerHTML = `${restartScheduleEditor()}<div id="restart-other-settings" class="config-sections"></div>`;
  wireRestartSchedule(root);
  renderConfigContainer('restart-other-settings', otherFields, 'restart', { readOnly: !pageEditable('restart'), groupBy: field => /Message|Reason/.test(field.key) ? 'Messages' : /Enabled|UseChat/.test(field.key) ? 'Warning Channels' : 'Presentation' });
  renderRestartActions(warningFields);
  updateRestartSummary();
}

function restartScheduleEditor() {
  const mode = String(valueOf('restart.restartType') || 'Fixed');
  const fixed = mode.toLowerCase() === 'fixed';
  const realtime = mode.toLowerCase() === 'realtime';
  const duration = hoursToDuration(valueOf('restart.restartInterval'));
  const times = listValue('restart.realTimeInterval');
  return `<section class="config-section restart-schedule-section"><h2>Schedule</h2><div class="restart-mode-row"><label>Restart mode<select id="restart-mode"><option value="Fixed" ${fixed ? 'selected' : ''}>Fixed interval</option><option value="Realtime" ${realtime ? 'selected' : ''}>Real time</option><option value="None" ${!fixed && !realtime ? 'selected' : ''}>Disabled</option></select></label></div><div id="restart-fixed-schedule" class="mode-schedule ${fixed ? '' : 'hidden'}"><h3>Fixed interval</h3><div class="duration-editor"><span>Restart every</span><input id="restart-fixed-value" type="number" min="0.01" step="0.25" value="${attr(duration.value)}"><select id="restart-fixed-unit"><option value="seconds" ${duration.unit === 'seconds' ? 'selected' : ''}>seconds</option><option value="minutes" ${duration.unit === 'minutes' ? 'selected' : ''}>minutes</option><option value="hours" ${duration.unit === 'hours' ? 'selected' : ''}>hours</option><option value="days" ${duration.unit === 'days' ? 'selected' : ''}>days</option></select></div><p class="schedule-summary" id="restart-fixed-summary"></p></div><div id="restart-realtime-schedule" class="mode-schedule ${realtime ? '' : 'hidden'}"><h3>Real-time restart times</h3><div class="reorder-list">${times.map((time, index) => `<div class="reorder-row realtime-row" draggable="true" data-realtime-drag="${index}"><span class="reorder-handle" aria-hidden="true">::</span><label>Restart time<input type="time" data-realtime-index="${index}" value="${attr(time)}"></label><div class="reorder-actions"><button data-realtime-move="up" data-index="${index}" title="Move up" aria-label="Move restart time up">&#8593;</button><button data-realtime-move="down" data-index="${index}" title="Move down" aria-label="Move restart time down">&#8595;</button><button data-realtime-remove data-index="${index}" title="Delete" aria-label="Delete restart time">&#215;</button></div></div>`).join('') || '<div class="reorder-empty">No real-time restart times configured.</div>'}<button id="restart-realtime-add" class="reorder-add">Add Time</button></div></div><div id="restart-disabled-schedule" class="mode-schedule ${!fixed && !realtime ? '' : 'hidden'}"><p>Automatic restart scheduling is disabled. Fixed and real-time settings remain stored.</p></div></section>`;
}

function wireRestartSchedule(root) {
  $('restart-mode').addEventListener('change', event => setEdit('restart.restartType', event.target.value, 'restart'));
  const updateFixed = () => {
    const hours = durationToHours(Number($('restart-fixed-value').value), $('restart-fixed-unit').value);
    if (Number.isFinite(hours) && hours > 0) setEdit('restart.restartInterval', hours, 'restart', false);
    updateRestartSummary();
  };
  $('restart-fixed-value').addEventListener('input', updateFixed);
  $('restart-fixed-unit').addEventListener('change', updateFixed);
  root.querySelectorAll('[data-realtime-index]').forEach(input => input.addEventListener('input', () => updateRealtimeValue(Number(input.dataset.realtimeIndex), input.value)));
  root.querySelectorAll('[data-realtime-move]').forEach(button => button.addEventListener('click', () => mutateRestartTimes(values => move(values, Number(button.dataset.index), button.dataset.realtimeMove === 'up' ? -1 : 1))));
  root.querySelectorAll('[data-realtime-remove]').forEach(button => button.addEventListener('click', () => mutateRestartTimes(values => values.splice(Number(button.dataset.index), 1))));
  $('restart-realtime-add').addEventListener('click', () => mutateRestartTimes(values => values.push(nextRestartTime(values))));
  wireDragRows(root, '[data-realtime-drag]', () => 'restart.realTimeInterval', row => Number(row.dataset.realtimeDrag), (_key, from, to) => mutateRestartTimes(values => moveTo(values, from, to)));
}

function hoursToDuration(hoursValue) {
  const hours = Number(hoursValue) || 0;
  if (hours >= 24 && Number.isInteger(hours / 24)) return { value: hours / 24, unit: 'days' };
  if (hours >= 1) return { value: Number(hours.toFixed(3)), unit: 'hours' };
  if (hours * 60 >= 1) return { value: Number((hours * 60).toFixed(3)), unit: 'minutes' };
  return { value: Number((hours * 3600).toFixed(3)), unit: 'seconds' };
}

function durationToHours(value, unit) { return value * ({ seconds: 1 / 3600, minutes: 1 / 60, hours: 1, days: 24 }[unit] || 1); }
function describeThreshold(seconds) { const part = secondsToDuration(seconds); const unit = part.value === 1 ? part.unit.replace(/s$/, '') : part.unit; return `${part.value} ${unit} before`; }
function secondsToDuration(value) { const seconds = Number(value) || 0; if (seconds % 3600 === 0) return { value: seconds / 3600, unit: 'hours' }; if (seconds % 60 === 0) return { value: seconds / 60, unit: 'minutes' }; return { value: seconds, unit: 'seconds' }; }
function durationToSeconds(value, unit) { return Math.round(value * ({ seconds: 1, minutes: 60, hours: 3600 }[unit] || 1)); }
function mutateRestartTimes(mutation) { const values = listValue('restart.realTimeInterval'); mutation(values); setEdit('restart.realTimeInterval', values, 'restart'); }
function updateRealtimeValue(index, value) { const values = listValue('restart.realTimeInterval'); if (values.some((item, other) => other !== index && item === value)) return notice('Realtime restart times must be unique.', true); values[index] = value; setEdit('restart.realTimeInterval', values, 'restart', false); updateRestartSummary(); }
function nextRestartTime(values) { for (let hour = 0; hour < 24; hour++) { const value = `${String(hour).padStart(2, '0')}:00`; if (!values.includes(value)) return value; } return '00:30'; }

function updateRestartSummary() {
  const mode = String(valueOf('restart.restartType') || 'Fixed').toLowerCase();
  const hours = Number(valueOf('restart.restartInterval')) || 0;
  const normalized = hoursToDuration(hours);
  if ($('restart-fixed-summary')) $('restart-fixed-summary').textContent = `Next cycle interval: ${normalized.value} ${normalized.unit}`;
  $('restart-preview').textContent = mode === 'realtime' ? `Configured restart times: ${(valueOf('restart.realTimeInterval') || []).join(', ') || 'none'}` : mode === 'fixed' ? `Restart cycle: ${normalized.value} ${normalized.unit}` : 'Automatic restart scheduling is disabled.';
}

function renderRestartActions(fields) {
  const root = $('restart-warnings');
  if (!root) return;
  root.innerHTML = `<h2>Warnings and pre-restart actions</h2>${fields.map(field => /preRestartCommands$/.test(field.key) ? restartCommandEditor(field) : restartThresholdEditor(field)).join('')}`;
  root.querySelectorAll('[data-threshold-key]').forEach(input => input.addEventListener('input', () => updateRestartThreshold(input)));
  root.querySelectorAll('[data-threshold-add]').forEach(button => button.addEventListener('click', () => mutateList(button.dataset.thresholdAdd, 'restart', values => values.push('60'))));
  root.querySelectorAll('[data-restart-action-key]').forEach(input => input.addEventListener('input', () => updateRestartAction(input)));
  root.querySelectorAll('[data-restart-raw-key]').forEach(input => input.addEventListener('input', () => updateRestartRaw(input)));
  root.querySelectorAll('[data-restart-add]').forEach(button => button.addEventListener('click', () => mutateList(button.dataset.restartAdd, 'restart', values => values.push('30 | broadcast '))));
  wireConfigControls(root, 'restart');
  wirePreviewDisclosures(root, (panel, key) => { const [, fieldKey, index] = key.split('::'); renderRestartActionPreview(panel, fieldKey, Number(index)); });
}

function restartThresholdEditor(field) {
  const values = listValue(field.key);
  const rows = values.map((value, index) => { const part = secondsToDuration(value); return `<div class="structured-action-row threshold-row"><span class="reorder-handle">::</span><label>Warning threshold<div class="duration-editor"><input type="number" min="1" data-threshold-key="${attr(field.key)}" data-index="${index}" data-threshold-part="value" value="${part.value}"><select data-threshold-key="${attr(field.key)}" data-index="${index}" data-threshold-part="unit"><option value="seconds" ${part.unit === 'seconds' ? 'selected' : ''}>seconds</option><option value="minutes" ${part.unit === 'minutes' ? 'selected' : ''}>minutes</option><option value="hours" ${part.unit === 'hours' ? 'selected' : ''}>hours</option></select></div><small>${esc(describeThreshold(Number(value)))} <span class="advanced-detail">(${esc(value)} normalized seconds)</span></small></label><div class="reorder-actions"><button data-list-move="up" data-key="${attr(field.key)}" data-index="${index}">&#8593;</button><button data-list-move="down" data-key="${attr(field.key)}" data-index="${index}">&#8595;</button><button data-list-remove data-key="${attr(field.key)}" data-index="${index}">&#215;</button></div></div>`; }).join('');
  return `<section class="structured-action-list"><h3>Timer broadcast thresholds</h3><p>Warnings are scheduled this long before restart. Runtime applies them from longest to shortest.</p>${rows || '<div class="reorder-empty">No warning thresholds configured.</div>'}<button data-threshold-add="${attr(field.key)}">Add Threshold</button></section>`;
}

function restartCommandEditor(field) {
  const rows = listValue(field.key).map((value, index) => restartActionRow(field.key, index, parseRestartAction(value))).join('');
  return `<section class="structured-action-list"><h3>Pre-restart actions</h3>${rows || '<div class="reorder-empty">No pre-restart actions configured.</div>'}<button data-restart-add="${attr(field.key)}">Add Action</button></section>`;
}

function parseRestartAction(value) {
  const match = String(value).match(/^\s*(\d+)\s*\|\s*(.*)$/s);
  if (!match) return { legacy: true, raw: String(value) };
  let content = match[2];
  let runAs = 'console';
  if (/^(\[asPlayer\]|asplayer:|each:)\s*/i.test(content)) { runAs = 'player'; content = content.replace(/^(\[asPlayer\]|asplayer:|each:)\s*/i, ''); }
  const broadcast = /^broadcast\s+/i.test(content);
  return { legacy: false, seconds: Number(match[1]), type: broadcast ? 'broadcast' : 'command', runAs, content: broadcast ? content.replace(/^broadcast\s+/i, '') : content };
}

function serializeRestartAction(action) {
  if (action.legacy) return action.raw;
  const prefix = action.type === 'broadcast' ? 'broadcast ' : action.runAs === 'player' ? '[asPlayer] ' : '';
  return `${action.seconds} | ${prefix}${action.content}`;
}

function restartActionRow(key, index, action) {
  if (action.legacy) return `<div class="structured-action-row legacy-action"><span class="reorder-handle">::</span><label>Legacy value<textarea class="reorder-editor" data-restart-raw-key="${attr(key)}" data-index="${index}">${esc(action.raw)}</textarea><small>Could not parse this value. It is preserved exactly.</small></label><div class="reorder-actions"><button data-list-remove data-key="${attr(key)}" data-index="${index}">&#215;</button></div></div>`;
  return `<div class="structured-action-row" data-restart-action-row="${index}"><span class="reorder-handle">::</span><div class="structured-action-fields ${action.type === 'broadcast' ? 'is-broadcast' : ''}"><label>Time before (seconds)<input type="number" min="0" data-restart-action-key="${attr(key)}" data-part="seconds" data-index="${index}" value="${action.seconds}"></label><label>Type<select data-restart-action-key="${attr(key)}" data-part="type" data-index="${index}"><option value="broadcast" ${action.type === 'broadcast' ? 'selected' : ''}>Broadcast</option><option value="command" ${action.type === 'command' ? 'selected' : ''}>Command</option></select></label>${action.type === 'command' ? `<label>Run as<select data-restart-action-key="${attr(key)}" data-part="runAs" data-index="${index}"><option value="console" ${action.runAs === 'console' ? 'selected' : ''}>Console</option><option value="player" ${action.runAs === 'player' ? 'selected' : ''}>Player</option></select></label>` : ''}<label class="action-content">${action.type === 'broadcast' ? 'Message' : 'Command'}<textarea class="auto-grow" data-restart-action-key="${attr(key)}" data-part="content" data-index="${index}">${esc(action.content)}</textarea>${action.type === 'broadcast' ? collapsiblePreview(`restart::${key}::${index}`, 'compact-preview') : ''}</label></div><div class="reorder-actions"><button data-list-move="up" data-key="${attr(key)}" data-index="${index}">&#8593;</button><button data-list-move="down" data-key="${attr(key)}" data-index="${index}">&#8595;</button><button data-list-duplicate data-key="${attr(key)}" data-index="${index}">+</button><button data-list-remove data-key="${attr(key)}" data-index="${index}">&#215;</button></div></div>`;
}

function updateRestartThreshold(input) {
  const key = input.dataset.thresholdKey;
  const index = Number(input.dataset.index);
  const row = input.closest('.threshold-row');
  const value = Number(row.querySelector('[data-threshold-part="value"]').value);
  const unit = row.querySelector('[data-threshold-part="unit"]').value;
  const seconds = durationToSeconds(value, unit);
  const values = listValue(key).map(String);
  if (!Number.isInteger(seconds) || seconds <= 0 || seconds > 86400) return notice('Warning threshold must be between 1 second and 24 hours.', true);
  if (values.some((item, other) => other !== index && Number(item) === seconds)) return notice('Warning thresholds must be unique.', true);
  values[index] = String(seconds);
  setEdit(key, values, 'restart', false);
  row.querySelector('small').firstChild.textContent = `${describeThreshold(seconds)} `;
}
function updateRestartRaw(input) { const values = listValue(input.dataset.restartRawKey); values[Number(input.dataset.index)] = input.value; setEdit(input.dataset.restartRawKey, values, 'restart', false); }
function updateRestartAction(input) {
  const key = input.dataset.restartActionKey;
  const values = listValue(key);
  const index = Number(input.dataset.index);
  const action = parseRestartAction(values[index]);
  action[input.dataset.part] = input.dataset.part === 'seconds' ? Number(input.value) : input.value;
  values[index] = serializeRestartAction(action);
  setEdit(key, values, 'restart', false);
  if (input.dataset.part === 'type') renderRestart();
  else if (input.dataset.part === 'content') refreshRestartPreview(key, index);
}

function refreshRestartPreview(key, index) {
  const previewKey = `restart::${key}::${index}`;
  const preview = document.querySelector(`[data-preview-panel="${CSS.escape(previewKey)}"]`);
  const action = parseRestartAction(listValue(key)[index] || '');
  if (preview && state.openPreviews.has(previewKey) && !action.legacy && action.type === 'broadcast') renderMinecraftPreview(preview, action.content, { player_name: 'Alex' });
}

function renderRestartActionPreview(panel, key, index) { const action = parseRestartAction(listValue(key)[index] || ''); if (!action.legacy) renderMinecraftPreview(panel, action.content, { player_name: 'Alex' }); }

const MOTD_TEMPLATES = {
  minimal: ['<color:aqua>Welcome, {player_name}!</color>'],
  welcome: ['<center><bold><gradient:#22D3EE:#A78BFA>Welcome to {server_name}</gradient></bold></center>', '<center><color:gray>{online_players}/{max_players} players online</color></center>'],
  info: ['<bold><color:yellow>{server_name}</color></bold>', '<color:gray>Welcome {player_name} · Group: {player_group}</color>', '<color:aqua>Use /paradigm for commands.</color>'],
  maintenance: ['<center><bold><color:red>Maintenance Mode</color></bold></center>', '<center><color:gray>Please check back soon.</color></center>'],
  blank: ['']
};

function renderMotd() {
  const fields = fieldsFor(['motd']);
  const linesField = fields.find(field => field.key === 'motd.motdLines');
  const lines = linesField ? listValue(linesField.key) : [];
  state.motdSelectedLine = Math.min(state.motdSelectedLine, Math.max(0, lines.length - 1));
  $('motd-lines').innerHTML = `<div class="motd-overview-heading"><strong>${lines.length} line${lines.length === 1 ? '' : 's'}</strong><button id="motd-density-toggle">${state.motdCompact ? 'Expand overview' : 'Compact overview'}</button></div>${lines.map((line, index) => `<div class="motd-summary-row ${index === state.motdSelectedLine ? 'active' : ''}" draggable="true" data-motd-drag="${index}"><span class="reorder-handle">::</span><button class="motd-summary" data-motd-select="${index}"><span>Line ${index + 1}</span><small>${esc(line || '(blank line)')}</small></button>${state.motdCompact ? '' : `<textarea class="reorder-editor auto-grow" data-motd-inline="${index}">${esc(line)}</textarea>`}<div class="reorder-actions"><button data-motd-move="up" data-index="${index}" title="Move up">&#8593;</button><button data-motd-move="down" data-index="${index}" title="Move down">&#8595;</button><button data-motd-duplicate data-index="${index}" title="Duplicate">+</button><button data-motd-remove data-index="${index}" title="Delete">&#215;</button></div></div>`).join('') || '<div class="reorder-empty">No join MOTD lines configured.</div>'}<div class="motd-selected-editor"><label>Selected line<textarea id="motd-selected-editor" rows="3">${esc(lines[state.motdSelectedLine] || '')}</textarea></label></div>`;
  $('motd-density-toggle').addEventListener('click', () => { state.motdCompact = !state.motdCompact; renderMotd(); });
  $('motd-lines').querySelectorAll('[data-motd-select]').forEach(button => button.addEventListener('click', () => { state.motdSelectedLine = Number(button.dataset.motdSelect); renderMotd(); }));
  $('motd-selected-editor').addEventListener('input', event => updateMotdLine(state.motdSelectedLine, event.target.value, false));
  $('motd-lines').querySelectorAll('[data-motd-inline]').forEach(input => input.addEventListener('input', () => updateMotdLine(Number(input.dataset.motdInline), input.value, false)));
  $('motd-lines').querySelectorAll('[data-motd-move]').forEach(button => button.addEventListener('click', () => mutateMotd(values => move(values, Number(button.dataset.index), button.dataset.motdMove === 'up' ? -1 : 1))));
  $('motd-lines').querySelectorAll('[data-motd-duplicate]').forEach(button => button.addEventListener('click', () => mutateMotd(values => values.splice(Number(button.dataset.index) + 1, 0, values[Number(button.dataset.index)]))));
  $('motd-lines').querySelectorAll('[data-motd-remove]').forEach(button => button.addEventListener('click', () => mutateMotd(values => values.splice(Number(button.dataset.index), 1))));
  wireDragRows($('motd-lines'), '[data-motd-drag]', () => 'motd.motdLines', row => Number(row.dataset.motdDrag), (_key, from, to) => mutateMotd(values => moveTo(values, from, to)));
  renderMotdSettings(fields.filter(field => field !== linesField));
  renderTokens('motd-placeholders', ['{player_name}', '{player_uuid}', '{player_group}', '{online_players}', '{max_players}', '{server_name}'], token => insertTokenIntoActive('motd-lines', token));
  $('motd-join-preview').innerHTML = collapsiblePreview('motd:join', 'multiline');
  wirePreviewDisclosures($('motd-join-preview'), panel => renderMotdPreview(listValue('motd.motdLines'), panel));
  renderMotdPreview(lines);
  wireAutoGrow($('motd-lines'));
}

function mutateMotd(mutation) { const values = listValue('motd.motdLines'); mutation(values); setEdit('motd.motdLines', values, 'motd'); }
function updateMotdLine(index, value, rerender = true) { const values = listValue('motd.motdLines'); values[index] = value; setEdit('motd.motdLines', values, 'motd', false); renderMotdPreview(values); if (rerender) renderMotd(); }
function renderMotdPreview(lines, panel = document.querySelector('[data-preview-panel="motd:join"]')) { if (state.openPreviews.has('motd:join')) renderMinecraftPreview(panel, lines, { player_name: 'Alex', player_uuid: '0000-0000', player_group: 'member', online_players: '12', max_players: '100', server_name: 'Paradigm Server' }); }

function renderMotdSettings(fields) {
  const root = $('motd-settings');
  if (!root) return;
  const readOnly = !pageEditable('motd');
  const rows = fields.map(field => {
    const value = valueOf(field.key);
    const focused = /\.line[12]$|hoverText$/.test(field.key);
    const control = focused && field.editable && !readOnly ? `<div class="motd-format-layout"><div class="motd-format-editor">${formattingToolbar(field.key)}<textarea class="format-editor auto-grow" rows="${/hoverText$/.test(field.key) ? 4 : 2}" data-config-key="${attr(field.key)}" data-config-type="STRING">${esc(value || '')}</textarea></div>${collapsiblePreview(`motd:${field.key}`, 'compact-preview')}</div>` : configControl(field, value, { readOnly });
    return `<div class="config-row ${focused ? 'wide-editor-row' : ''}"><div class="config-label"><strong>${esc(humanLabel(field))}</strong><small>${esc(field.help || '')}</small></div><div class="config-control"><div class="config-control-line">${control}</div></div></div>`;
  }).join('');
  root.innerHTML = `<section class="config-section"><h2>Server List</h2>${rows}</section>`;
  wireConfigControls(root, 'motd');
  wireFormattingEditors(root, 'motd', renderMotdFieldPreview);
  wirePreviewDisclosures(root, renderMotdFieldPreview);
}

function renderMotdFieldPreview(panel, previewKey) {
  const key = previewKey.replace(/^motd:/, '');
  renderMinecraftPreview(panel, valueOf(key) || '', { online_players: '12', max_players: '100', server_name: 'Paradigm Server' });
}

const TABLIST_SORT_RULES = ['GROUP_WEIGHT_DESC','GROUP_WEIGHT_ASC','PLAYER_NAME_ASC','PLAYER_NAME_DESC','PING_ASC','PING_DESC'];
const TABLIST_SAMPLE_PLAYERS = [
  { name: 'Alex', group: 'admin', weight: 100, prefix: '<color:red>[Admin] </color>', suffix: '', ping: 38 },
  { name: 'Steve', group: 'moderator', weight: 50, prefix: '<color:aqua>[Mod] </color>', suffix: '', ping: 74 },
  { name: 'Maya', group: 'member', weight: 0, prefix: '<color:gray>[Member] </color>', suffix: '', ping: 21 },
  { name: 'Robin', group: 'member', weight: 0, prefix: '', suffix: '<color:dark_gray> ★</color>', ping: 112 }
];

function renderTablist() {
  const root = $('tablist-editor');
  if (!root || !state.snapshot) return;
  const enabled = Boolean(valueOf('tablist.enabled'));
  const header = valueOf('tablist.header') || [];
  const footer = valueOf('tablist.footer') || [];
  const playerFormat = valueOf('tablist.playerFormat') || '{player_name}';
  const sorting = valueOf('tablist.sorting') || [];
  const showPing = Boolean(valueOf('tablist.showPing'));
  const refresh = Number(valueOf('tablist.refreshInterval') || 5);
  const worlds = parseTablistWorlds(valueOf('tablist.perWorldOverrides'));
  const issues = tablistValidationIssues(worlds, refresh);
  root.innerHTML = `<section class="editor-section tablist-basics"><div class="tablist-switch-row"><div><h2>Tablist presentation</h2><p>Changes apply to online players after saving.</p></div><label class="switch-label"><input id="tablist-enabled" type="checkbox" ${enabled ? 'checked' : ''}> Enabled</label></div><div class="tablist-format-grid"><label>Header<textarea id="tablist-header" class="auto-grow" rows="3">${esc(header.join('\n'))}</textarea>${tablistToolbar('header')}${collapsiblePreview('tablist:header', 'compact-preview')}</label><label>Footer<textarea id="tablist-footer" class="auto-grow" rows="3">${esc(footer.join('\n'))}</textarea>${tablistToolbar('footer')}${collapsiblePreview('tablist:footer', 'compact-preview')}</label></div><label class="tablist-player-format">Player name format<textarea id="tablist-player-format" class="auto-grow" rows="2">${esc(playerFormat)}</textarea>${tablistToolbar('player')}${collapsiblePreview('tablist:player', 'compact-preview')}</label><div class="token-list" id="tablist-placeholders"></div><div class="compact-form"><label>Numeric ping<input id="tablist-ping" type="checkbox" ${showPing ? 'checked' : ''}></label><label>Refresh interval (seconds)<input id="tablist-refresh" type="number" min="1" max="3600" value="${attr(refresh)}"></label></div>${collapsiblePreview('tablist:full', 'tablist-preview')}</section><section class="editor-section"><h2>Sorting</h2><p>Rules are evaluated from top to bottom. Player name and UUID remain deterministic final fallbacks.</p><div id="tablist-sorting" class="reorder-list">${sorting.map((rule, index) => tablistSortRow(rule, index)).join('') || '<div class="reorder-empty">No rules configured; Paradigm defaults are used.</div>'}</div><button id="tablist-sort-add">Add Rule</button></section><section class="editor-section"><div class="tablist-world-heading"><div><h2>Per-world overrides</h2><p>Unset values inherit the global tablist settings.</p></div><button id="tablist-world-add">Add World</button></div>${issues.length ? `<div class="field-error">${issues.map(esc).join('<br>')}</div>` : ''}<div id="tablist-worlds" class="tablist-worlds">${worlds.map((world, index) => tablistWorldCard(world, index, issues)).join('') || '<div class="reorder-empty">No world overrides configured.</div>'}</div></section>`;

  $('tablist-enabled').addEventListener('change', event => setEdit('tablist.enabled', event.target.checked, 'tablist', false));
  $('tablist-header').addEventListener('input', event => { setEdit('tablist.header', event.target.value.split('\n'), 'tablist', false); updateTablistPreviews(); });
  $('tablist-footer').addEventListener('input', event => { setEdit('tablist.footer', event.target.value.split('\n'), 'tablist', false); updateTablistPreviews(); });
  $('tablist-player-format').addEventListener('input', event => { setEdit('tablist.playerFormat', event.target.value, 'tablist', false); updateTablistPreviews(); });
  $('tablist-ping').addEventListener('change', event => { setEdit('tablist.showPing', event.target.checked, 'tablist', false); updateTablistPreviews(); });
  $('tablist-refresh').addEventListener('input', event => setEdit('tablist.refreshInterval', Number(event.target.value), 'tablist', false));
  root.querySelectorAll('#tablist-header,#tablist-footer,#tablist-player-format').forEach(input => input.addEventListener('focus', () => { state.tablistActiveEditor = input.id; }));
  root.querySelectorAll('[data-tab-format]').forEach(button => button.addEventListener('click', () => applyFormatToInput($(tablistTargetId(button.closest('[data-tab-target]').dataset.tabTarget)), button.dataset.tabFormat)));
  renderTokens('tablist-placeholders', ['{player_name}','{prefix}','{suffix}','{group}','{world}','{ping}','{afk}','{playtime}','{playtime_short}','{online_players}','{max_players}','{server_name}','{server_id}','{network_id}'], token => { const input = $(state.tablistActiveEditor); if (input) { insertAtCursor(input, token); input.dispatchEvent(new Event('input', { bubbles: true })); input.focus(); } });
  wirePreviewDisclosures(root, renderTablistPreviewPanel);
  wireAutoGrow(root);

  root.querySelectorAll('[data-tab-sort-index]').forEach(select => select.addEventListener('change', () => mutateTablistSort(values => values[Number(select.dataset.tabSortIndex)] = select.value)));
  root.querySelectorAll('[data-tab-sort-move]').forEach(button => button.addEventListener('click', () => mutateTablistSort(values => move(values, Number(button.dataset.index), button.dataset.tabSortMove === 'up' ? -1 : 1))));
  root.querySelectorAll('[data-tab-sort-remove]').forEach(button => button.addEventListener('click', () => mutateTablistSort(values => values.splice(Number(button.dataset.index), 1))));
  $('tablist-sort-add').addEventListener('click', () => mutateTablistSort(values => values.push('PLAYER_NAME_ASC')));
  $('tablist-world-add').addEventListener('click', () => mutateTablistWorlds(values => values.push({ world: '', header: null, footer: null, playerFormat: null, showPing: null })));
  root.querySelectorAll('[data-tab-world-index]').forEach(input => input.addEventListener('input', () => updateTablistWorldInput(input)));
  root.querySelectorAll('[data-tab-world-remove]').forEach(button => button.addEventListener('click', () => mutateTablistWorlds(values => values.splice(Number(button.dataset.index), 1))));
}

function tablistToolbar(target) {
  return `<div class="format-toolbar compact-format-toolbar" data-tab-target="${target}" aria-label="Formatting controls"><button type="button" data-tab-format="bold" title="Bold" aria-label="Bold">B</button><button type="button" data-tab-format="italic" title="Italic" aria-label="Italic">I</button><button type="button" data-tab-format="underline" title="Underline" aria-label="Underline">U</button><button type="button" data-tab-format="strikethrough" title="Strikethrough" aria-label="Strikethrough">S</button><button type="button" data-tab-format="color:#55FFFF">Color</button><button type="button" data-tab-format="gradient:#22D3EE:#A78BFA">Gradient</button><button type="button" data-tab-format="rainbow">Rainbow</button></div>`;
}

function tablistSortRow(rule, index) {
  return `<div class="reorder-row"><span class="reorder-handle" aria-hidden="true">::</span><select data-tab-sort-index="${index}" aria-label="Sort rule ${index + 1}">${TABLIST_SORT_RULES.map(option => `<option ${option === rule ? 'selected' : ''}>${option}</option>`).join('')}</select><div class="reorder-actions"><button data-tab-sort-move="up" data-index="${index}" title="Move up" aria-label="Move sort rule up">&#8593;</button><button data-tab-sort-move="down" data-index="${index}" title="Move down" aria-label="Move sort rule down">&#8595;</button><button data-tab-sort-remove data-index="${index}" title="Delete" aria-label="Delete sort rule">&#215;</button></div></div>`;
}

function tablistValidationIssues(worlds, refreshInterval) {
  const issues = [];
  const seen = new Map();
  worlds.forEach((world, index) => {
    const id = (world.world || '').trim();
    if (!id) { issues.push(`World override #${index + 1} is missing a world/dimension ID.`); return; }
    const normalized = id.toLowerCase();
    if (seen.has(normalized)) issues.push(`World ID "${id}" is configured more than once; only the first match applies.`);
    seen.set(normalized, index);
  });
  if (!Number.isFinite(refreshInterval) || refreshInterval < 1 || refreshInterval > 3600) {
    issues.push('Refresh interval must be between 1 and 3600 seconds.');
  }
  return issues;
}

function tablistWorldCard(world, index) {
  const nullable = value => value == null ? '' : value;
  const invalid = !(world.world || '').trim();
  return `<article class="tablist-world-card"><div class="tablist-world-heading"><label>World ID<input data-tab-world-index="${index}" data-part="world" value="${attr(world.world || '')}" placeholder="minecraft:overworld"${invalid ? ' aria-invalid="true" class="input-invalid"' : ''}></label><button data-tab-world-remove data-index="${index}">Delete</button></div><div class="tablist-format-grid"><label>Header override<textarea data-tab-world-index="${index}" data-part="header" rows="2" placeholder="Inherit global">${esc(Array.isArray(world.header) ? world.header.join('\n') : '')}</textarea></label><label>Footer override<textarea data-tab-world-index="${index}" data-part="footer" rows="2" placeholder="Inherit global">${esc(Array.isArray(world.footer) ? world.footer.join('\n') : '')}</textarea></label></div><label>Player format override<textarea data-tab-world-index="${index}" data-part="playerFormat" rows="2" placeholder="Inherit global">${esc(nullable(world.playerFormat))}</textarea></label><div class="compact-form"><label>Ping override<select data-tab-world-index="${index}" data-part="showPing"><option value="" ${world.showPing == null ? 'selected' : ''}>Inherit</option><option value="true" ${world.showPing === true ? 'selected' : ''}>Show</option><option value="false" ${world.showPing === false ? 'selected' : ''}>Hide</option></select></label></div></article>`;
}

function mutateTablistSort(mutation) { const values = clone(valueOf('tablist.sorting') || []); mutation(values); setEdit('tablist.sorting', values, 'tablist'); }
function parseTablistWorlds(rows) { return (rows || []).map(row => { try { return JSON.parse(row); } catch (_) { return null; } }).filter(Boolean); }
function mutateTablistWorlds(mutation, rerender = true) { const values = parseTablistWorlds(valueOf('tablist.perWorldOverrides')); mutation(values); setEdit('tablist.perWorldOverrides', values.map(value => JSON.stringify(value)), 'tablist', rerender); }
function updateTablistWorldInput(input) {
  mutateTablistWorlds(values => {
    const row = values[Number(input.dataset.tabWorldIndex)];
    if (!row) return;
    const part = input.dataset.part;
    if (part === 'header' || part === 'footer') row[part] = input.value === '' ? null : input.value.split('\n');
    else if (part === 'showPing') row[part] = input.value === '' ? null : input.value === 'true';
    else row[part] = input.value === '' && part !== 'world' ? null : input.value;
  }, false);
}

function tablistTargetId(target) { return target === 'header' ? 'tablist-header' : target === 'footer' ? 'tablist-footer' : 'tablist-player-format'; }
function applyFormatToInput(input, tag) {
  if (!input) return;
  const start = input.selectionStart ?? input.value.length; const end = input.selectionEnd ?? start;
  const selected = input.value.slice(start, end) || 'text'; const base = tag.split(':')[0];
  input.setRangeText(`<${tag}>${selected}</${base}>`, start, end, 'end');
  input.dispatchEvent(new Event('input', { bubbles: true })); input.focus();
}

function renderTablistPreviewPanel(panel, key) {
  const samples = { online_players: '4', max_players: '100', server_name: 'Paradigm Server', server_id: 'survival', network_id: 'main', world: 'minecraft:overworld' };
  if (key === 'tablist:header') return renderMinecraftPreview(panel, valueOf('tablist.header') || [], samples);
  if (key === 'tablist:footer') return renderMinecraftPreview(panel, valueOf('tablist.footer') || [], samples);
  if (key === 'tablist:player') return renderMinecraftPreview(panel, replaceTablistSample(valueOf('tablist.playerFormat') || '', TABLIST_SAMPLE_PLAYERS[0]), samples);
  const sorted = sortTablistSamples(TABLIST_SAMPLE_PLAYERS, valueOf('tablist.sorting') || []);
  panel.replaceChildren();
  const header = document.createElement('div'); header.className = 'tablist-preview-header'; header.append(buildMinecraftPreview(valueOf('tablist.header') || [], samples)); panel.append(header);
  const list = document.createElement('div'); list.className = 'tablist-preview-players';
  sorted.forEach(player => { const row = document.createElement('div'); const text = replaceTablistSample(valueOf('tablist.playerFormat') || '{player_name}', player) + (valueOf('tablist.showPing') && !(valueOf('tablist.playerFormat') || '').includes('{ping}') ? ` <color:gray>${player.ping}ms</color>` : ''); row.append(buildMinecraftPreview(text, samples)); list.append(row); });
  panel.append(list);
  const footer = document.createElement('div'); footer.className = 'tablist-preview-footer'; footer.append(buildMinecraftPreview(valueOf('tablist.footer') || [], samples)); panel.append(footer);
}

function updateTablistPreviews() {
  document.querySelectorAll('#tablist-editor .preview-disclosure.is-open [data-preview-panel]').forEach(panel => renderTablistPreviewPanel(panel, panel.dataset.previewPanel));
}
function replaceTablistSample(value, player) { return String(value).replaceAll('{player_name}', player.name).replaceAll('{prefix}', player.prefix).replaceAll('{suffix}', player.suffix).replaceAll('{group}', player.group).replaceAll('{ping}', String(player.ping)); }
function sortTablistSamples(values, rules) {
  const result = clone(values); const active = rules.length ? rules : ['GROUP_WEIGHT_DESC','PLAYER_NAME_ASC'];
  result.sort((a, b) => { for (const rule of active) { let difference = 0; if (rule === 'GROUP_WEIGHT_DESC') difference = b.weight - a.weight; if (rule === 'GROUP_WEIGHT_ASC') difference = a.weight - b.weight; if (rule === 'PLAYER_NAME_ASC') difference = a.name.localeCompare(b.name); if (rule === 'PLAYER_NAME_DESC') difference = b.name.localeCompare(a.name); if (rule === 'PING_ASC') difference = a.ping - b.ping; if (rule === 'PING_DESC') difference = b.ping - a.ping; if (difference) return difference; } return a.name.localeCompare(b.name); }); return result;
}

function applyMotdTemplate() {
  const template = MOTD_TEMPLATES[$('motd-template').value];
  if (!template) return;
  confirmAction('Replace the current join MOTD lines with this template?', false).then(ok => { if (ok) setEdit('motd.motdLines', clone(template), 'motd'); });
}

function valueOf(key) {
  const field = findField(key);
  return state.edits.has(key) ? state.edits.get(key) : field?.value?.value;
}

function renderTokens(id, tokens, onClick) {
  const root = $(id);
  if (!root) return;
  root.innerHTML = tokens.map(token => `<button type="button" data-token="${attr(token)}">${esc(token)}</button>`).join('');
  root.querySelectorAll('[data-token]').forEach(button => button.addEventListener('click', () => onClick(button.dataset.token)));
}

function insertTokenIntoActive(rootId, token) {
  const root = $(rootId);
  const input = root?.querySelector('textarea:focus, input:focus') || root?.querySelector('textarea, input');
  if (!input) return;
  insertAtCursor(input, token);
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.focus();
}

function applyFormat(rootId, tag) {
  const root = $(rootId);
  const input = root?.querySelector('textarea:focus, input:focus') || root?.querySelector('textarea, input');
  if (!input) return;
  const start = input.selectionStart ?? input.value.length;
  const end = input.selectionEnd ?? start;
  const selected = input.value.slice(start, end) || 'text';
  const replacement = `<${tag}>${selected}</${tag.split(':')[0]}>`;
  input.setRangeText(replacement, start, end, 'end');
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.focus();
}

function insertAtCursor(input, text) {
  const start = input.selectionStart ?? input.value.length;
  const end = input.selectionEnd ?? start;
  input.setRangeText(text, start, end, 'end');
}

const PREVIEW_COLORS = { black: '#000000', dark_blue: '#0000aa', dark_green: '#00aa00', dark_aqua: '#00aaaa', dark_red: '#aa0000', dark_purple: '#aa00aa', gold: '#ffaa00', gray: '#aaaaaa', dark_gray: '#555555', blue: '#5555ff', green: '#55ff55', aqua: '#55ffff', red: '#ff5555', light_purple: '#ff55ff', yellow: '#ffff55', white: '#ffffff' };
const LEGACY_COLORS = ['000000','0000aa','00aa00','00aaaa','aa0000','aa00aa','ffaa00','aaaaaa','555555','5555ff','55ff55','55ffff','ff5555','ff55ff','ffff55','ffffff'];

function renderMinecraftPreview(root, raw, samples = {}) {
  if (!root) return;
  root.replaceChildren(buildMinecraftPreview(raw, samples));
}

function buildMinecraftPreview(raw, samples = {}) {
  const fragment = document.createDocumentFragment();
  const lines = Array.isArray(raw) ? raw : String(raw ?? '').split('\n');
  lines.forEach((line, index) => {
    if (index) fragment.append(document.createElement('br'));
    fragment.append(parsePreviewLine(replacePreviewSamples(String(line), samples)));
  });
  return fragment;
}

function replacePreviewSamples(text, samples) {
  return Object.entries(samples).reduce((value, [key, sample]) => value.replaceAll(`{${key}}`, String(sample)), text);
}

function parsePreviewLine(text) {
  const root = document.createDocumentFragment();
  const stack = [{ name: null, node: root }];
  const tokenPattern = /<[^>\n]*>/g;
  let offset = 0;
  for (const match of text.matchAll(tokenPattern)) {
    appendLegacyPreviewText(stack.at(-1).node, text.slice(offset, match.index));
    const token = previewTag(match[0]);
    if (!token) appendLegacyPreviewText(stack.at(-1).node, match[0]);
    else if (token.close) {
      const matching = stack.findLastIndex(entry => entry.name === token.name);
      if (matching > 0) stack.splice(matching);
      else appendLegacyPreviewText(stack.at(-1).node, match[0]);
    } else if (token.standalone) stack.at(-1).node.append(token.node);
    else { stack.at(-1).node.append(token.node); stack.push({ name: token.name, node: token.node }); }
    offset = match.index + match[0].length;
  }
  appendLegacyPreviewText(stack.at(-1).node, text.slice(offset));
  return root;
}

function previewTag(raw) {
  const body = raw.slice(1, -1);
  const close = body.startsWith('/');
  const value = close ? body.slice(1) : body;
  const name = value.split(':', 1)[0].toLowerCase();
  if (close) return ['color','bold','italic','underline','strikethrough','gradient','rainbow','center','click','hover'].includes(name) || PREVIEW_COLORS[name] || /^#[0-9a-f]{6}$/i.test(name) ? { name, close: true } : null;
  if (name === 'emoji' && /^[a-z0-9_+-]+$/i.test(value.slice(6))) return { standalone: true, node: document.createTextNode(`:${value.slice(6)}:`) };
  const element = document.createElement(name === 'bold' ? 'strong' : name === 'italic' ? 'em' : name === 'underline' ? 'u' : name === 'strikethrough' ? 's' : 'span');
  if (name === 'color') {
    const color = value.slice(6).toLowerCase();
    const resolved = resolvePreviewColor(color);
    if (!resolved) return null;
    element.style.color = resolved;
  } else if (PREVIEW_COLORS[name] || /^#[0-9a-f]{6}$/i.test(name)) {
    element.style.color = PREVIEW_COLORS[name] || name;
  } else if (name === 'gradient') {
    const colors = value.split(':').slice(1).map(resolvePreviewColor).filter(Boolean);
    if (colors.length < 2) return null;
    element.className = 'preview-gradient';
    element.style.backgroundImage = `linear-gradient(90deg, ${colors.join(', ')})`;
  } else if (name === 'rainbow') element.className = 'preview-rainbow';
  else if (name === 'center') element.className = 'preview-center';
  else if (name === 'click') { element.className = 'preview-click'; element.dataset.action = value.split(':')[1] || ''; }
  else if (name === 'hover') { element.className = 'preview-hover'; element.title = value.split(':').slice(2).join(':') || value.split(':').slice(1).join(':'); }
  else if (!['bold','italic','underline','strikethrough'].includes(name)) return null;
  return { name, close: false, standalone: false, node: element };
}

function resolvePreviewColor(value) {
  const normalized = String(value || '').trim().toLowerCase();
  return PREVIEW_COLORS[normalized] || (/^#[0-9a-f]{6}$/i.test(normalized) ? normalized : null);
}

function appendLegacyPreviewText(parent, text) {
  let active = parent;
  let offset = 0;
  const pattern = /&(?!amp;|lt;|gt;|quot;|apos;)(?:#([0-9a-f]{6})|([0-9a-fklmnor]))/gi;
  for (const match of text.matchAll(pattern)) {
    active.append(document.createTextNode(text.slice(offset, match.index)));
    const code = match[2]?.toLowerCase();
    if (code === 'r') active = parent;
    else {
      const span = document.createElement(code === 'l' ? 'strong' : code === 'o' ? 'em' : code === 'n' ? 'u' : code === 'm' ? 's' : 'span');
      const color = match[1] ? `#${match[1]}` : code && /[0-9a-f]/.test(code) ? `#${LEGACY_COLORS[parseInt(code, 16)]}` : null;
      if (color) span.style.color = color;
      active.append(span);
      active = span;
    }
    offset = match.index + match[0].length;
  }
  active.append(document.createTextNode(text.slice(offset)));
}

async function loadOverview() {
  try {
    const data = await api('/api/overview');
    $('overview-grid').innerHTML = metrics([
      ['Server', data.serverName || data.serverId], ['Paradigm', data.version], ['Minecraft', `${data.minecraftVersion || '-'} · ${data.loader || '-'}`],
      ['Online Players', data.onlinePlayers ?? '-'], ['Uptime', duration(data.uptimeMs)], ['Storage', data.activeProvider],
      ['Modules', `${data.modules?.enabled ?? 0}/${data.modules?.total ?? 0}`], ['Dashboard', data.dashboardRunning ? 'Running' : 'Stopped']
    ]);
    $('warnings').innerHTML = (data.warnings || []).map(warning => `<div class="notice-inline">${esc(warning)}</div>`).join('');
    $('overview-activity').innerHTML = `<section class="editor-section"><h2>Quick Actions</h2><div class="button-row"><button data-go="permissions">Permissions</button><button data-go="motd">Edit MOTD</button><button data-go="customCommands">Custom Commands</button><button data-go="storage">Storage</button></div><h2>Identity</h2><p>${esc(data.networkId || '-')} / ${esc(data.serverId || '-')}</p><p>${esc(data.dashboardUrl || '')}</p></section><section class="editor-section"><h2>Recent Activity</h2>${dataTable(['Actor','Action','Result'], (data.recentActivity || []).map(entry => [entry.actorName || '-', entry.actionType, entry.result]))}</section>`;
    $('overview-activity').querySelectorAll('[data-go]').forEach(button => button.addEventListener('click', () => requestNavigate(button.dataset.go)));
  } catch (error) { renderError('overview-grid', error.message); }
}

async function loadServers() {
  try {
    const data = await api('/api/servers');
    state.servers = data.servers || [];
    state.networkActive = Boolean(data.networkActive);
    const rows = state.servers.map(server => [server.serverName || server.serverId, server.networkId, server.loader || '-', server.current ? 'Current' : (server.online ? 'Online' : 'Offline'), server.onlinePlayers ?? '-', relativeTime(server.lastSeenMs)]);
    $('servers-table').innerHTML = dataTable(['Server', 'Network', 'Loader', 'State', 'Players', 'Last seen'], rows);
    renderRemoteServerSelect();
    if (state.networkActive && state.remote.serverId) await loadRemoteConfigSnapshot();
  } catch (error) { renderError('servers-table', error.message); }
}

function renderRemoteServerSelect() {
  const wrap = $('remote-config-panel');
  if (!wrap) return;
  if (!state.networkActive) {
    wrap.classList.add('hidden');
    return;
  }
  wrap.classList.remove('hidden');
  const select = $('remote-server-select');
  if (!select) return;
  const current = state.remote.serverId;
  select.innerHTML = state.servers.map(server => `<option value="${attr(server.serverId)}" ${server.serverId === current ? 'selected' : ''}>${esc(server.serverName || server.serverId)}${server.current ? ' (this server)' : ''} ${server.online ? '' : '- offline'}</option>`).join('');
  if (!current && state.servers.length) {
    state.remote.serverId = state.servers[0].serverId;
    select.value = state.remote.serverId;
  }
  const server = state.servers.find(s => s.serverId === state.remote.serverId);
  const status = $('remote-server-status');
  if (status) status.textContent = server ? `${server.online ? 'Online' : 'Offline'} · last seen ${relativeTime(server.lastSeenMs)}` : '';
}

async function selectRemoteServer(serverId) {
  state.remote.serverId = serverId;
  state.remote.scopes.clear();
  state.remote.edits.clear();
  state.remote.editPages.clear();
  state.remote.errors.clear();
  renderRemoteServerSelect();
  await loadRemoteConfigSnapshot();
}

async function loadRemoteConfigSnapshot() {
  if (!state.remote.serverId) return;
  try {
    state.remote.snapshot = await api(`/api/remote-config/snapshot?serverId=${encodeURIComponent(state.remote.serverId)}`);
    if (!state.remote.section && state.remote.snapshot.categories?.length) {
      state.remote.section = state.remote.snapshot.categories[0].id;
    }
    renderRemoteConfig();
  } catch (error) {
    renderError('remote-config-fields', error.message);
  }
}

function renderRemoteConfig() {
  const snapshot = state.remote.snapshot;
  const tabs = $('remote-section-tabs');
  if (tabs && snapshot) {
    tabs.innerHTML = (snapshot.categories || []).map(category => `<button type="button" class="${category.id === state.remote.section ? 'active' : ''}" data-remote-section="${attr(category.id)}">${esc(category.label)}</button>`).join('');
    tabs.querySelectorAll('[data-remote-section]').forEach(button => button.addEventListener('click', () => {
      state.remote.section = button.dataset.remoteSection;
      renderRemoteConfig();
    }));
  }
  const banner = $('remote-schema-banner');
  if (banner) banner.classList.toggle('hidden', !snapshot || snapshot.schemaCompatible);

  const section = (snapshot?.sections || []).find(s => s.section === state.remote.section);
  const scope = remoteSectionScope(section);
  const scopeSelect = $('remote-scope-select');
  if (scopeSelect) scopeSelect.value = scope;
  const adopt = $('remote-adopt-banner');
  if (adopt) adopt.classList.toggle('hidden', !section || section.adopted);
  const status = $('remote-section-status');
  if (status && section) {
    status.textContent = section.lastError
      ? `Apply error: ${section.lastError}`
      : `Editing ${scope === 'NETWORK' ? 'network default' : 'server override'} · network rev ${section.networkRevision} · server rev ${section.serverRevision} · applied global ${section.appliedGlobalRevision} / server ${section.appliedServerRevision}`;
  } else if (status) status.textContent = '';

  if (!snapshot || !state.remote.section) return;
  const fields = snapshot.fields.filter(field => field.category === state.remote.section).map(field => remoteFieldForScope(field, scope));
  renderConfigContainer('remote-config-fields', fields, 'remoteConfig', { store: state.remote });
  updateRemoteSaveBar();
}

function remoteSectionScope(section) {
  if (!section) return 'SERVER';
  let scope = state.remote.scopes.get(section.section);
  if (!scope) {
    scope = section.serverRevision > 0 ? 'SERVER' : (section.networkRevision > 0 ? 'NETWORK' : 'SERVER');
    state.remote.scopes.set(section.section, scope);
  }
  return scope;
}

function remoteFieldForScope(field, scope) {
  if (scope === 'NETWORK') {
    const value = field.networkValue?.set ? field.networkValue : (field.baselineValue?.set ? field.baselineValue : field.value);
    return { ...field, value, origin: field.networkValue?.set ? 'network' : 'unmanaged' };
  }
  const value = field.serverValue?.set ? field.serverValue
    : (field.networkValue?.set ? field.networkValue : (field.baselineValue?.set ? field.baselineValue : field.value));
  const origin = field.serverValue?.set ? 'server' : (field.networkValue?.set ? 'network' : 'unmanaged');
  return { ...field, value, origin };
}

function selectRemoteScope(scope) {
  const section = state.remote.section;
  if (!section || (scope !== 'SERVER' && scope !== 'NETWORK')) return;
  state.remote.scopes.set(section, scope);
  for (const key of [...state.remote.edits.keys()]) {
    if (findField(key, state.remote.snapshot)?.category === section) {
      state.remote.edits.delete(key);
      state.remote.editPages.delete(key);
      state.remote.errors.delete(key);
    }
  }
  renderRemoteConfig();
}

function updateRemoteSaveBar() {
  const count = [...state.remote.editPages.values()].filter(page => page === 'remoteConfig').length;
  const bar = $('remote-save-bar');
  if (!bar) return;
  bar.classList.toggle('hidden', count === 0);
  const label = $('remote-unsaved-count');
  if (label) label.textContent = count;
  const button = $('remote-save-changes');
  if (button) button.disabled = count === 0;
}

async function saveRemoteSection() {
  const section = state.remote.section;
  const operations = [...state.remote.edits].filter(([key]) => state.remote.editPages.get(key) === 'remoteConfig' && findField(key, state.remote.snapshot)?.category === section).map(([key, value]) => ({ key, value }));
  if (!operations.length || !section) return;
  const sectionStatus = (state.remote.snapshot?.sections || []).find(s => s.section === section);
  const scope = remoteSectionScope(sectionStatus);
  try {
    await api('/api/remote-config/patch', {
      method: 'POST',
      body: JSON.stringify({
        serverId: state.remote.serverId,
        scope,
        section,
        expectedRevision: scope === 'NETWORK' ? (sectionStatus?.networkRevision || 0) : (sectionStatus?.serverRevision || 0),
        operations
      })
    });
    operations.forEach(({ key }) => { state.remote.edits.delete(key); state.remote.editPages.delete(key); state.remote.errors.delete(key); });
    notice(`Saved ${operations.length} change${operations.length === 1 ? '' : 's'} to the ${scope === 'NETWORK' ? 'network default' : 'server override'}.`);
    await loadRemoteConfigSnapshot();
  } catch (error) {
    if (error.code === 'stale_revision') notice('Managed configuration changed since this section loaded. Reload and try again.', true);
    else if (error.code === 'schema_incompatible') notice('Target server is running an incompatible Paradigm build; remote editing is disabled until versions match.', true);
    (error.data?.rejected || []).forEach(item => state.remote.errors.set(item.key, item.reason));
    renderRemoteConfig();
    if (!error.data?.rejected?.length) notice(error.message, true);
  }
}

async function adoptRemoteSection(scope) {
  const section = state.remote.section;
  if (!section) return;
  try {
    await api('/api/remote-config/adopt', { method: 'POST', body: JSON.stringify({ serverId: state.remote.serverId, scope, section }) });
    state.remote.scopes.set(section, scope);
    notice(scope === 'NETWORK' ? 'Section adopted as a network default.' : 'Section adopted for this server.');
    await loadRemoteConfigSnapshot();
  } catch (error) { notice(error.message, true); }
}

async function loadStorage() {
  try {
    const data = await api('/api/storage/status');
    $('storage-grid').innerHTML = metrics([
      ['Configured', data.configuredDataProvider], ['Active', data.activeDataProvider], ['Target', data.target || data.dataLocation],
      ['Migrations', data.migrationVersion], ['Repositories', data.repositoriesAvailable ? 'Available' : 'Unavailable'], ['Fallback', data.fallbackActive ? data.fallbackReason || 'Active' : 'Inactive']
    ]);
  } catch (error) { renderError('storage-grid', error.message); }
}

async function testStorage() {
  try { const result = await api('/api/storage/test', { method: 'POST', body: '{}' }); notice(result.message || 'Storage connection test completed.'); await loadStorage(); }
  catch (error) { notice(error.message, true); }
}

async function migrationDryRun() {
  try {
    const result = await api('/api/storage/migration/dry-run', { method: 'POST', body: JSON.stringify({ source: $('migration-source').value, target: $('migration-target').value, policy: $('migration-policy').value }) });
    $('migration-result').innerHTML = `<div class="metric-grid">${metrics([['Players', result.players], ['Homes', result.homes], ['Warps', result.warps], ['Moderation', result.moderationRecords], ['Permission Groups', result.permissionGroups], ['Permission Users', result.permissionUsers], ['Conflicts', result.conflicts], ['Failures', result.failures]])}</div><p>${esc(result.success ? 'Dry run completed successfully.' : 'Dry run reported failures.')}</p>${(result.messages || []).length ? `<details><summary>Messages</summary><ul>${result.messages.map(message => `<li>${esc(message)}</li>`).join('')}</ul></details>` : ''}`;
    notice('Migration dry run completed. No data was changed.');
  } catch (error) { $('migration-result').textContent = error.message; notice(error.message, true); }
}

async function loadStorageConfiguration() {
  try {
    const config = await api('/api/storage/configuration');
    state.storageConfiguration = config;
    $('storage-config-fields').innerHTML = `<section class="config-section"><h2>Provider and Identity</h2>
      ${storageField('Provider', 'storage-provider', `<select id="storage-provider"><option value="json" ${config.provider === 'json' ? 'selected' : ''}>JSON</option><option value="sqlite" ${config.provider === 'sqlite' ? 'selected' : ''}>SQLite</option><option value="mysql" ${config.provider === 'mysql' ? 'selected' : ''}>MySQL / MariaDB</option></select>`, 'Changing provider requires a restart and never migrates data automatically.')}
      ${storageField('Network ID', 'storage-network', `<input id="storage-network" value="${attr(config.networkId)}">`, 'Stable multi-server network identity.')}
      ${storageField('Server ID', 'storage-server-id', `<input id="storage-server-id" value="${attr(config.serverId)}">`, 'Stable server identity.')}
      ${storageField('Server Name', 'storage-server-name', `<input id="storage-server-name" value="${attr(config.serverName)}">`, 'Human-readable server name.')}
      ${storageField('Fallback to JSON', 'storage-fallback', `<label class="switch"><input id="storage-fallback" type="checkbox" ${config.fallbackToJsonOnSqlFailure ? 'checked' : ''}><span></span></label>`, 'Use JSON if configured SQL storage cannot start.')}
    </section><section class="config-section"><h2>SQLite</h2>${storageField('Database Path', 'storage-sqlite-path', `<input id="storage-sqlite-path" value="${attr(config.sqlitePath)}">`, 'Relative path only; parent traversal is rejected.')}</section>
    <section class="config-section"><h2>MySQL / MariaDB</h2>
      ${storageField('Host', 'storage-sql-host', `<input id="storage-sql-host" value="${attr(config.sqlHost)}">`, 'Database host name or address.')}
      ${storageField('Port', 'storage-sql-port', `<input id="storage-sql-port" type="number" value="${attr(config.sqlPort)}">`, 'Usually 3306.')}
      ${storageField('Database', 'storage-sql-database', `<input id="storage-sql-database" value="${attr(config.sqlDatabase)}">`, 'Database/schema name.')}
      ${storageField('Username', 'storage-sql-username', `<input id="storage-sql-username" value="${attr(config.sqlUsername)}">`, 'Database account.')}
      ${storageField('Replace Password', 'storage-sql-password', `<input id="storage-sql-password" type="password" autocomplete="new-password" placeholder="${config.sqlPasswordSet ? 'Password configured; leave blank to keep' : 'Enter password'}">`, 'The existing password is never returned to the browser.')}
      ${storageField('Password Environment Variable', 'storage-sql-password-env', `<input id="storage-sql-password-env" value="${attr(config.sqlPasswordEnv || '')}">`, 'Preferred alternative to storing a password.')}
      ${storageField('Pool Size', 'storage-sql-pool', `<input id="storage-sql-pool" type="number" min="1" max="50" value="${attr(config.sqlPoolSize)}">`, 'Connection pool size.')}
      ${storageField('TLS/SSL', 'storage-sql-ssl', `<label class="switch"><input id="storage-sql-ssl" type="checkbox" ${config.sqlSsl ? 'checked' : ''}><span></span></label>`, 'Use an encrypted database connection.')}
    </section><div class="button-row"><button id="storage-config-test">Test These Settings</button><button id="storage-config-save">Save Configuration</button></div>`;
    $('storage-config-test').addEventListener('click', testStorageConfiguration);
    $('storage-config-save').addEventListener('click', saveStorageConfiguration);
  } catch (error) { renderError('storage-config-fields', error.message); }
}

function storageField(label, id, control, help) { return `<div class="config-row"><div class="config-label"><strong>${esc(label)}</strong><small>${esc(help)}</small></div><div class="config-control">${control}</div></div>`; }

async function loadDiscord() {
  try {
    const status = await api('/api/discord/status');
    state.discordStatus = status;
    const warnings = Array.isArray(status.warnings) ? status.warnings : [];
    $('discord-connection').innerHTML = `<h2>Connection</h2>
      <div class="metric-grid discord-connection-grid">
        <div class="card"><span class="label">State</span><span class="value">${esc(status.summary || status.state || 'Unknown')}</span></div>
        <div class="card"><span class="label">Bot</span><span class="value">${esc(status.botUsername || 'Not connected')}</span></div>
        <div class="card"><span class="label">Queue</span><span class="value">${esc(String(status.queueDepth ?? 0))}</span></div>
        <div class="card"><span class="label">Sent</span><span class="value">${esc(String(status.sentCount ?? 0))}</span></div>
        <div class="card"><span class="label">Dropped</span><span class="value">${esc(String(status.droppedCount ?? 0))}</span></div>
        <div class="card"><span class="label">Heartbeat</span><span class="value">${esc(discordHeartbeatLabel(status))}</span></div>
      </div>
      ${warnings.map(warning => `<div class="notice-inline">${esc(warning)}</div>`).join('')}
      ${status.lastError ? `<div class="field-error">${esc(status.lastError)}</div>` : ''}
      ${storageField('Replace Bot Token', 'discord-token', `<input id="discord-token" type="password" autocomplete="new-password" placeholder="${status.botTokenSet ? 'Token configured; leave blank to keep' : 'Enter bot token'}">`, 'The stored token is never returned to the browser.')}
      ${storageField('Test Destination', 'discord-test-destination', `<select id="discord-test-destination"><option value="chat">Chat</option><option value="moderation">Moderation</option><option value="notifications">Notifications</option></select>`, 'Which configured channel the test message is sent to.')}
      <div class="button-row"><button id="discord-save-token">Save Token</button><button id="discord-clear-token">Clear Token</button><button id="discord-test">Send Test Message</button><button id="discord-reconnect">Reconnect</button></div>`;
    $('discord-save-token').addEventListener('click', () => saveDiscordToken(false));
    $('discord-clear-token').addEventListener('click', () => saveDiscordToken(true));
    $('discord-test').addEventListener('click', testDiscord);
    $('discord-reconnect').addEventListener('click', reconnectDiscord);
  } catch (error) { renderError('discord-connection', error.message); }
}

function discordHeartbeatLabel(status) {
  if (status.state !== 'CONNECTED') return 'Not connected';
  if (status.heartbeatOutstanding) return 'Awaiting ACK';
  if (!status.lastHeartbeatAckMs) return 'No ACK yet';
  return `${Math.max(0, Math.round((Date.now() - status.lastHeartbeatAckMs) / 1000))}s ago`;
}

async function saveDiscordToken(clear) {
  if (clear && !await confirmAction('Clear the stored Discord bot token? The integration will disconnect.', true)) return;
  const input = $('discord-token');
  const botToken = input ? input.value : '';
  if (!clear && !botToken.trim()) { notice('Enter a bot token, or use Clear Token to remove the stored one.', true); return; }
  try {
    const result = await api('/api/discord/token', { method: 'POST', body: JSON.stringify({ botToken, clear }) });
    if (input) input.value = '';
    notice(result.changed ? (clear ? 'Discord bot token cleared.' : 'Discord bot token saved.') : 'Existing Discord bot token kept.');
    await loadDiscord();
    await loadConfigSnapshot();
  } catch (error) { notice(error.message, true); }
}

async function testDiscord() {
  const select = $('discord-test-destination');
  const destination = select ? select.value : 'chat';
  try {
    await api('/api/discord/test', { method: 'POST', body: JSON.stringify({ destination }) });
    notice(`Test message queued for the ${destination} destination.`);
    await loadDiscord();
  } catch (error) { notice(error.message, true); }
}

async function reconnectDiscord() {
  try {
    await api('/api/discord/reconnect', { method: 'POST', body: '{}' });
    notice('Reconnecting to Discord.');
    await loadDiscord();
  } catch (error) { notice(error.message, true); }
}

function readStorageConfiguration() {
  return {
    provider: $('storage-provider').value, fallbackToJsonOnSqlFailure: $('storage-fallback').checked,
    networkId: $('storage-network').value, serverId: $('storage-server-id').value, serverName: $('storage-server-name').value,
    sqlitePath: $('storage-sqlite-path').value, sqlHost: $('storage-sql-host').value, sqlPort: Number($('storage-sql-port').value),
    sqlDatabase: $('storage-sql-database').value, sqlUsername: $('storage-sql-username').value, sqlPassword: $('storage-sql-password').value,
    sqlPasswordEnv: $('storage-sql-password-env').value, sqlPoolSize: Number($('storage-sql-pool').value), sqlSsl: $('storage-sql-ssl').checked
  };
}

async function testStorageConfiguration() {
  try { const result = await api('/api/storage/configuration/test', { method: 'POST', body: JSON.stringify(readStorageConfiguration()) }); notice(result.message || (result.success ? 'Connection succeeded.' : 'Connection failed.'), !result.success); }
  catch (error) { notice(error.message, true); }
}

async function saveStorageConfiguration() {
  if (!await confirmAction('Save storage configuration? The active provider will not change until restart, and no data migration will run.', true)) return;
  try { const result = await api('/api/storage/configuration', { method: 'POST', body: JSON.stringify(readStorageConfiguration()) }); notice(result.message || 'Storage configuration saved.'); await loadStorageConfiguration(); }
  catch (error) { notice(error.message, true); }
}

function metrics(items) { return items.map(([label, value]) => `<div class="card"><div class="label">${esc(label)}</div><div class="value">${esc(display(value))}</div></div>`).join(''); }

async function loadCustomCommands() {
  try {
    const query = encodeURIComponent($('custom-command-search')?.value || '');
    const data = await api(`/api/custom-commands?query=${query}`);
    const commands = data.commands || [];
    $('custom-command-list').innerHTML = commands.length ? commands.map(command => `<button class="selection-item ${state.selectedCommand === command.name ? 'active' : ''}" data-command-name="${attr(command.name)}"><strong>/${esc(command.name)}</strong><small>${esc(command.description || 'No description')} · ${command.actionCount} actions</small></button>`).join('') : empty('No custom commands found.');
    $('custom-command-list').querySelectorAll('[data-command-name]').forEach(button => button.addEventListener('click', () => selectCustomCommand(button.dataset.commandName)));
    if (state.selectedCommand && !state.commandDraft && commands.some(command => command.name === state.selectedCommand)) await selectCustomCommand(state.selectedCommand);
  } catch (error) { renderError('custom-command-list', error.message); }
}

async function selectCustomCommand(name) {
  try {
    const data = await api(`/api/custom-commands/item?name=${encodeURIComponent(name)}`);
    state.selectedCommand = name;
    state.commandDraft = clone(data.command.command);
    state.commandIsNew = false;
    state.commandDirty = false;
    renderCustomCommandEditor();
    loadCustomCommands();
  } catch (error) { notice(error.message, true); }
}

function newCustomCommand() {
  state.selectedCommand = null;
  state.commandIsNew = true;
  state.commandDirty = true;
  state.commandDraft = {
    name: 'new_command', description: '', permission: 'paradigm.custom.new_command', requirePermission: false,
    permissionErrorMessage: '&cYou do not have permission.', cooldown_seconds: 0, cooldown_message: '&cWait {remaining_time} seconds.',
    actions: [{ type: 'message', text: ['&aHello {player}!'] }], arguments: []
  };
  renderCustomCommandEditor();
}

function renderCustomCommandEditor() {
  const root = $('custom-command-editor');
  const command = state.commandDraft;
  if (!command) { root.className = 'detail-editor empty-detail'; root.textContent = 'Select a command or create one.'; return; }
  root.className = 'detail-editor command-editor';
  root.innerHTML = `<div class="detail-header"><div><h2>${state.commandIsNew ? 'Create Command' : `/${esc(command.name)}`}</h2><span>${state.commandIsNew ? 'New definition' : 'Loaded from a restricted command file'}</span></div><div class="detail-header-actions">${state.commandIsNew ? '' : '<button id="command-duplicate">Duplicate</button><button id="command-delete" class="danger">Delete</button>'}<button id="command-save">${state.commandIsNew ? 'Create' : 'Save'}</button></div></div>
    <section class="config-section command-section"><div class="section-heading"><div><h2>Command</h2><p>Identity, access, and cooldown behavior.</p></div></div>
      ${commandField('Name', 'name', command.name, 'text', 'Lowercase command root without /')}
      ${commandField('Description', 'description', command.description || '', 'textarea', 'Shown in command help.')}
      ${commandField('Permission', 'permission', command.permission || '', 'text', 'Existing Paradigm permission syntax.')}
      ${commandField('Require Permission', 'requirePermission', !!command.requirePermission, 'checkbox', 'Require the permission node above.')}
      ${commandField('Permission Denied Message', 'permissionErrorMessage', command.permissionErrorMessage || '', 'textarea', 'Formatting tags are supported.')}
      ${commandField('Cooldown Seconds', 'cooldown_seconds', command.cooldown_seconds ?? 0, 'number', 'Zero disables the cooldown.')}
      ${commandField('Cooldown Message', 'cooldown_message', command.cooldown_message || '', 'textarea', 'Use {remaining_time}.')}
    </section>
    <section class="config-section command-section"><div class="section-heading"><div><h2>Arguments</h2><p>Values parsed from the command input.</p></div><button id="command-add-argument">Add Argument</button></div><div id="command-arguments" class="command-section-body">${renderCommandArguments(command.arguments || [])}</div></section>
    <section class="config-section command-section"><div class="section-heading"><div><h2>Actions</h2><p>Operations run from top to bottom.</p></div><button data-add-action-path="actions">Add Action</button></div><div id="command-actions" class="command-section-body">${renderActions(command.actions || [], 'actions')}</div></section>
    <section class="config-section command-section command-area-section"><div class="section-heading"><div><h2>Area Restriction</h2><p>Optionally limit execution to one cuboid.</p></div><label class="command-section-toggle"><span>Enabled</span><span class="switch"><input id="command-area-enabled" type="checkbox" ${command.area_restriction ? 'checked' : ''}><span aria-hidden="true"></span></span></label></div><div id="command-area" class="command-section-body">${renderArea(command.area_restriction)}</div></section>
    <details class="editor-disclosure command-json-disclosure"><summary><span>Advanced JSON preview</span><small>Read-only representation of the command definition.</small></summary><div class="editor-disclosure-body"><textarea class="command-json" readonly aria-label="Command JSON preview">${esc(JSON.stringify(command, null, 2))}</textarea></div></details>`;
  wireCustomCommandEditor(root);
}

function commandField(label, key, value, type, help) {
  let control;
  if (type === 'checkbox') control = `<label class="switch"><input data-command-field="${attr(key)}" type="checkbox" aria-label="${attr(label)}" ${value ? 'checked' : ''}><span aria-hidden="true"></span></label>`;
  else if (type === 'textarea') control = `<textarea data-command-field="${attr(key)}" aria-label="${attr(label)}">${esc(value)}</textarea>`;
  else control = `<input data-command-field="${attr(key)}" type="${type}" aria-label="${attr(label)}" value="${attr(value)}">`;
  return `<div class="config-row"><div class="config-label"><strong>${esc(label)}</strong><small>${esc(help)}</small></div><div class="config-control">${control}</div></div>`;
}

function renderCommandArguments(argumentsList) {
  return argumentsList.map((argument, index) => `<div class="command-action" data-argument-index="${index}"><div class="command-action-grid"><label>Name<input data-argument-field="name" value="${attr(argument.name || '')}"></label><label>Type<select data-argument-field="type">${['string','integer','boolean','player','world','gamemode','custom'].map(type => `<option ${type === argument.type ? 'selected' : ''}>${type}</option>`).join('')}</select></label><label class="check-field"><input data-argument-field="required" type="checkbox" ${argument.required ? 'checked' : ''}><span>Required</span></label><label>Error message<input data-argument-field="errorMessage" value="${attr(argument.errorMessage || '')}"></label></div><div class="button-row"><button data-argument-move="up">Move Up</button><button data-argument-move="down">Move Down</button><button data-argument-duplicate>Duplicate</button><button data-argument-remove class="danger">Delete</button></div></div>`).join('') || '<div class="empty-state">No arguments. Players can run this command without parameters.</div>';
}

function renderActions(actions, path) {
  const rows = actions.map((action, index) => {
    const actionPath = `${path}.${index}`;
    const type = action.type || 'message';
    let fields = '';
    if (type === 'message') fields = `<label>Message lines<textarea data-action-field="text">${esc((action.text || []).join('\n'))}</textarea></label>`;
    else if (type === 'teleport') fields = `<div class="compact-form"><label>X<input data-action-field="x" type="number" value="${attr(action.x ?? 0)}"></label><label>Y<input data-action-field="y" type="number" value="${attr(action.y ?? 64)}"></label><label>Z<input data-action-field="z" type="number" value="${attr(action.z ?? 0)}"></label></div>`;
    else if (type === 'conditional') fields = `<h3>Conditions</h3><div data-condition-list>${renderConditions(action.conditions || [])}</div><button data-add-condition>Add Condition</button><h3>On Success</h3><div>${renderActions(action.on_success || [], `${actionPath}.on_success`)}</div><button data-add-action-path="${actionPath}.on_success">Add Success Action</button><h3>On Failure</h3><div>${renderActions(action.on_failure || [], `${actionPath}.on_failure`)}</div><button data-add-action-path="${actionPath}.on_failure">Add Failure Action</button>`;
    else fields = `<label>Commands, one per line<textarea data-action-field="commands">${esc((action.commands || []).join('\n'))}</textarea></label>`;
    return `<div class="command-action" data-action-path="${attr(actionPath)}"><div class="detail-header"><label>Action type<select data-action-field="type">${['message','teleport','run_command','run_console','conditional'].map(option => `<option ${option === type ? 'selected' : ''}>${option}</option>`).join('')}</select></label><div class="detail-header-actions"><button data-action-move="up" title="Move up" aria-label="Move action up">&#8593;</button><button data-action-move="down" title="Move down" aria-label="Move action down">&#8595;</button><button data-action-duplicate>Duplicate</button><button data-action-remove class="danger">Delete</button></div></div>${fields}</div>`;
  }).join('');
  return rows || '<div class="empty-state">No actions configured.</div>';
}

function renderConditions(conditions) {
  return conditions.map((condition, index) => `<div class="compact-form" data-condition-index="${index}"><label>Type<select data-condition-field="type">${['has_permission','has_item','health_above','health_below','is_op'].map(type => `<option ${type === condition.type ? 'selected' : ''}>${type}</option>`).join('')}</select></label><label>Value<input data-condition-field="value" value="${attr(condition.value || '')}"></label><label>Item amount<input data-condition-field="item_amount" type="number" min="1" value="${attr(condition.item_amount || 1)}"></label><label>Negate<input data-condition-field="negate" type="checkbox" ${condition.negate ? 'checked' : ''}></label><button data-condition-remove>Delete</button></div>`).join('');
}

function renderArea(area) {
  if (!area) return '<div class="empty-state">Area restriction is disabled.</div>';
  return `<div class="command-area-grid"><label>World<input data-area-field="world" value="${attr(area.world || '')}"></label><label>Corner 1<input data-area-field="corner1" value="${attr((area.corner1 || []).join(', '))}" placeholder="0, 64, 0"></label><label>Corner 2<input data-area-field="corner2" value="${attr((area.corner2 || []).join(', '))}" placeholder="10, 80, 10"></label></div><label class="command-area-message">Restriction message<textarea data-area-field="restriction_message" rows="3">${esc(area.restriction_message || '')}</textarea></label>`;
}

function wireCustomCommandEditor(root) {
  root.querySelectorAll('[data-command-field]').forEach(input => input.addEventListener('input', () => { state.commandDraft[input.dataset.commandField] = input.type === 'checkbox' ? input.checked : input.type === 'number' ? Number(input.value) : input.value; state.commandDirty = true; refreshCommandJson(); }));
  root.querySelectorAll('[data-argument-index]').forEach(card => {
    const index = Number(card.dataset.argumentIndex);
    card.querySelectorAll('[data-argument-field]').forEach(input => input.addEventListener('input', () => { state.commandDraft.arguments[index][input.dataset.argumentField] = input.type === 'checkbox' ? input.checked : input.value; state.commandDirty = true; refreshCommandJson(); }));
    bindListButtons(card, state.commandDraft.arguments, index, 'argument');
  });
  root.querySelectorAll('[data-action-path]').forEach(card => wireActionCard(card));
  root.querySelectorAll('[data-add-action-path]').forEach(button => button.addEventListener('click', () => { arrayAtPath(button.dataset.addActionPath).push({ type: 'message', text: [''] }); state.commandDirty = true; renderCustomCommandEditor(); }));
  $('command-add-argument').addEventListener('click', () => { state.commandDraft.arguments ||= []; state.commandDraft.arguments.push({ name: `arg${state.commandDraft.arguments.length + 1}`, type: 'string', required: true }); state.commandDirty = true; renderCustomCommandEditor(); });
  $('command-area-enabled').addEventListener('change', event => { state.commandDraft.area_restriction = event.target.checked ? { world: 'minecraft:overworld', corner1: [0,0,0], corner2: [0,0,0], restriction_message: '&cYou cannot use that command here.' } : null; state.commandDirty = true; renderCustomCommandEditor(); });
  root.querySelectorAll('[data-area-field]').forEach(input => input.addEventListener('input', () => { state.commandDraft.area_restriction[input.dataset.areaField] = input.dataset.areaField.startsWith('corner') ? input.value.split(',').map(value => Number(value.trim())) : input.value; state.commandDirty = true; refreshCommandJson(); }));
  $('command-save').addEventListener('click', saveCustomCommand);
  $('command-delete')?.addEventListener('click', deleteCustomCommand);
  $('command-duplicate')?.addEventListener('click', duplicateCustomCommand);
}

function wireActionCard(card) {
  const path = card.dataset.actionPath;
  const action = objectAtPath(path);
  card.querySelectorAll(':scope > [data-action-field], :scope > label [data-action-field], :scope > .detail-header [data-action-field], :scope > .compact-form [data-action-field]').forEach(input => input.addEventListener('input', () => {
    const key = input.dataset.actionField;
    action[key] = key === 'text' || key === 'commands' ? input.value.split('\n') : input.type === 'number' ? Number(input.value) : input.value;
    state.commandDirty = true;
    if (key !== 'type') { refreshCommandJson(); return; }
    normalizeAction(action);
    renderCustomCommandEditor();
  }));
  const parts = path.split('.');
  const index = Number(parts.pop());
  const list = arrayAtPath(parts.join('.'));
  bindListButtons(card, list, index, 'action');
  card.querySelectorAll('[data-condition-index]').forEach(row => {
    const conditionIndex = Number(row.dataset.conditionIndex);
    action.conditions ||= [];
    row.querySelectorAll('[data-condition-field]').forEach(input => input.addEventListener('input', () => { action.conditions[conditionIndex][input.dataset.conditionField] = input.type === 'checkbox' ? input.checked : input.type === 'number' ? Number(input.value) : input.value; state.commandDirty = true; refreshCommandJson(); }));
    row.querySelector('[data-condition-remove]')?.addEventListener('click', () => { action.conditions.splice(conditionIndex, 1); state.commandDirty = true; renderCustomCommandEditor(); });
  });
  card.querySelector(':scope > [data-add-condition]')?.addEventListener('click', () => { action.conditions ||= []; action.conditions.push({ type: 'has_permission', value: '', item_amount: 1, negate: false }); state.commandDirty = true; renderCustomCommandEditor(); });
}

function bindListButtons(card, list, index, prefix) {
  card.querySelector(`[data-${prefix}-move="up"]`)?.addEventListener('click', () => { move(list, index, -1); state.commandDirty = true; renderCustomCommandEditor(); });
  card.querySelector(`[data-${prefix}-move="down"]`)?.addEventListener('click', () => { move(list, index, 1); state.commandDirty = true; renderCustomCommandEditor(); });
  card.querySelector(`[data-${prefix}-duplicate]`)?.addEventListener('click', () => { list.splice(index + 1, 0, clone(list[index])); state.commandDirty = true; renderCustomCommandEditor(); });
  card.querySelector(`[data-${prefix}-remove]`)?.addEventListener('click', () => { list.splice(index, 1); state.commandDirty = true; renderCustomCommandEditor(); });
}

function normalizeAction(action) {
  const type = action.type;
  Object.keys(action).filter(key => key !== 'type').forEach(key => delete action[key]);
  if (type === 'message') action.text = [''];
  if (type === 'teleport') Object.assign(action, { x: 0, y: 64, z: 0 });
  if (['run_command','run_console'].includes(type)) action.commands = [''];
  if (type === 'conditional') Object.assign(action, { conditions: [], on_success: [], on_failure: [] });
}

function objectAtPath(path) {
  const parts = path.split('.');
  let value = state.commandDraft;
  parts.forEach(part => { value = /^\d+$/.test(part) ? value[Number(part)] : value[part]; });
  return value;
}

function arrayAtPath(path) {
  if (!path) return null;
  const value = objectAtPath(path);
  return Array.isArray(value) ? value : [];
}

function refreshCommandJson() { const preview = $('custom-command-editor').querySelector('.command-json'); if (preview) preview.value = JSON.stringify(state.commandDraft, null, 2); }

async function saveCustomCommand() {
  try {
    const action = state.commandIsNew ? 'create' : 'update';
    const result = await api(`/api/custom-commands/${action}`, { method: 'POST', body: JSON.stringify({ originalName: state.selectedCommand || '', command: state.commandDraft }) });
    state.selectedCommand = result.name || state.commandDraft.name;
    state.commandIsNew = false;
    state.commandDirty = false;
    notice(`Custom command /${state.selectedCommand} saved and reloaded.`);
    state.commandDraft = null;
    await loadCustomCommands();
    await selectCustomCommand(state.selectedCommand);
  } catch (error) { notice(error.message, true); }
}

async function deleteCustomCommand() {
  if (!await confirmAction(`Delete /${state.selectedCommand}? This cannot be undone.`, true)) return;
  try { await api('/api/custom-commands/delete', { method: 'POST', body: JSON.stringify({ originalName: state.selectedCommand }) }); state.selectedCommand = null; state.commandDraft = null; renderCustomCommandEditor(); await loadCustomCommands(); notice('Custom command deleted and definitions reloaded.'); }
  catch (error) { notice(error.message, true); }
}

async function duplicateCustomCommand() {
  const requested = await promptAction('Duplicate Command', 'Choose the root players will use for the duplicate.', `${state.selectedCommand}_copy`, 'Command name');
  if (!requested) return;
  try { const result = await api('/api/custom-commands/duplicate', { method: 'POST', body: JSON.stringify({ originalName: state.selectedCommand, name: requested }) }); state.selectedCommand = result.name; state.commandDraft = null; await loadCustomCommands(); await selectCustomCommand(result.name); notice('Custom command duplicated.'); }
  catch (error) { notice(error.message, true); }
}

async function loadPermissions() {
  try {
    const query = encodeURIComponent($('permissions-search')?.value || '');
    const page = state.permissionPage;
    const summaryPromise = api('/api/permissions/summary');
    const dataPromise = state.permissionView === 'groups'
      ? api('/api/permissions/groups')
      : state.permissionView === 'users'
        ? api(`/api/permissions/users?query=${query}&page=${page}&pageSize=${state.pageSize}`)
        : state.permissionView === 'tracks'
          ? api('/api/permissions/tracks')
        : api(`/api/permissions/nodes?query=${query}&page=${page}&pageSize=${state.pageSize}`);
    const [summary, data] = await Promise.all([summaryPromise, dataPromise]);
    state.permissionData.summary = summary;
    state.permissionData[state.permissionView] = data[state.permissionView] || [];
    state.permissionData.total = data.total ?? state.permissionData[state.permissionView].length;
    $('permissions-summary').textContent = `${summary.groups} groups · ${summary.users} configured permission subjects · ${summary.tracks ?? (state.permissionData.tracks || []).length} tracks · ${summary.nodes} nodes`;
    document.querySelectorAll('[data-permission-view]').forEach(button => button.classList.toggle('active', button.dataset.permissionView === state.permissionView));
    renderPermissionTargetList();
    if (state.selectedPermissionTarget) renderPermissionEditor();
  } catch (error) { renderError('permission-target-list', error.message); }
}

async function runLuckPermsMigration() {
  const direction = $('luckperms-direction').value;
  const mode = $('luckperms-mode').value;
  let confirmed = false;
  if (mode === 'replace') {
    confirmed = await confirmAction(`Replace ${direction === 'import' ? 'Paradigm' : 'LuckPerms'} permission data? This is destructive.`, true);
    if (!confirmed) return;
  }
  const report = $('luckperms-report');
  report.textContent = 'Migration running...';
  try {
    const result = await api('/api/permissions/migrate/luckperms', {
      method: 'POST',
      body: JSON.stringify({ direction, mode, confirmed })
    });
    report.textContent = `Groups: ${result.groups}\nUsers: ${result.users}\nPermissions: ${result.permissions}\nMemberships: ${result.memberships}\nParents: ${result.parents}\nMetadata: ${result.metadata}\nConflicts: ${result.conflicts}\nSkipped: ${result.skipped}${(result.details || []).length ? `\n\n${result.details.join('\n')}` : ''}`;
    notice(`LuckPerms ${direction} ${mode} completed.`);
    await loadPermissions();
  } catch (error) {
    report.textContent = error.message;
    notice(error.message, true);
  }
}

function renderPermissionTargetList() {
  const root = $('permission-target-list');
  const items = state.permissionData[state.permissionView] || [];
  if (state.permissionView === 'groups') {
    root.innerHTML = `<button id="permission-create-group">Create Group</button>${items.map(group => `<button class="selection-item ${selectedPermission('group', group.name) ? 'active' : ''}" data-permission-kind="group" data-permission-id="${attr(group.name)}"><strong>${esc(group.name)}</strong><small>${group.permissionCount} direct permissions · ${esc((group.parents || []).join(', ') || 'no parent')}</small></button>`).join('')}`;
    $('permission-create-group').addEventListener('click', createPermissionGroup);
  } else if (state.permissionView === 'users') {
    root.innerHTML = items.length ? items.map(user => `<button class="selection-item ${selectedPermission('user', user.uuid) ? 'active' : ''}" data-permission-kind="user" data-permission-id="${attr(user.uuid)}"><strong>${esc(user.name || user.uuid)}</strong><small>${user.online ? 'Online' : 'Offline'} · ${user.groups || 0} groups · ${user.permissions || 0} direct</small></button>`).join('') : empty('No players found.');
  } else if (state.permissionView === 'tracks') {
    root.innerHTML = `<button id="permission-create-track">Create Track</button>${items.map(track => `<button class="selection-item ${selectedPermission('track', track.name) ? 'active' : ''}" data-permission-kind="track" data-permission-id="${attr(track.name)}"><strong>${esc(track.name)}</strong><small>${(track.groups || []).length} ranks</small></button>`).join('')}`;
    $('permission-create-track').addEventListener('click', async () => {
      const name = await promptAction('Create Track', 'Choose a name for the ordered permission track.', '', 'Track name');
      if (name) permissionMutation('track_create', { track: name });
    });
  } else {
    root.innerHTML = items.length ? items.map(node => `<button class="selection-item ${selectedPermission('node', node.node) ? 'active' : ''}" data-permission-kind="node" data-permission-id="${attr(node.node)}"><strong>${esc(node.node)}</strong><small>${esc(node.source || 'Paradigm')} · ${esc(node.description || '')}</small></button>`).join('') : empty('No permission nodes found.');
  }
  root.querySelectorAll('[data-permission-kind]').forEach(button => button.addEventListener('click', () => {
    state.selectedPermissionTarget = { kind: button.dataset.permissionKind, id: button.dataset.permissionId };
    renderPermissionTargetList();
    renderPermissionEditor();
  }));
  renderPagination('permission-list-pagination', state.permissionPage, state.permissionData.total || items.length, state.pageSize, page => { state.permissionPage = page; loadPermissions(); });
}

function selectedPermission(kind, id) { return state.selectedPermissionTarget?.kind === kind && state.selectedPermissionTarget?.id === id; }

async function renderPermissionEditor() {
  const selected = state.selectedPermissionTarget;
  if (!selected) return;
  if (selected.kind === 'group') return renderGroupEditor(state.permissionData.groups.find(group => group.name === selected.id));
  if (selected.kind === 'user') return renderUserEditor(state.permissionData.users.find(user => user.uuid === selected.id));
  if (selected.kind === 'track') return renderTrackEditor(state.permissionData.tracks.find(track => track.name === selected.id));
  return renderNodeEditor(state.permissionData.nodes.find(node => node.node === selected.id));
}

function renderTrackEditor(track) {
  if (!track) return;
  const root = $('permission-editor');
  root.className = 'detail-editor permission-subject-editor';
  root.innerHTML = `<div class="detail-header"><div><h2>${esc(track.name)}</h2><span>Ordered ranks, lowest to highest.</span></div><div class="detail-header-actions"><button id="track-clear">Clear</button><button id="track-delete" class="danger">Delete</button></div></div><section class="permission-section"><h2>Ranks</h2><ol>${(track.groups || []).map((group, index) => `<li>${esc(group)} <button data-track-remove="${attr(group)}">Remove</button></li>`).join('') || '<li>No ranks.</li>'}</ol><div class="compact-form"><label>Group<select id="track-group-select">${(state.permissionData.groups || []).map(group => `<option>${esc(group.name)}</option>`).join('')}</select></label><button id="track-append">Append Rank</button></div></section>`;
  $('track-clear').addEventListener('click', () => permissionMutation('track_clear', { track: track.name }));
  $('track-delete').addEventListener('click', async () => { if (await confirmAction(`Delete track ${track.name}?`, true)) permissionMutation('track_delete', { track: track.name }); });
  $('track-append').addEventListener('click', () => permissionMutation('track_append', { track: track.name, group: $('track-group-select').value }));
  root.querySelectorAll('[data-track-remove]').forEach(button => button.addEventListener('click', () => permissionMutation('track_remove', { track: track.name, group: button.dataset.trackRemove })));
}

function renderGroupEditor(group) {
  if (!group) return;
  const root = $('permission-editor');
  root.className = 'detail-editor permission-subject-editor';
  root.innerHTML = `<div class="detail-header permission-subject-header"><div><h2>${esc(group.name)}</h2><span>${esc(group.description || 'Permission group')}</span></div><div class="detail-header-actions"><button id="group-save-meta">Save Metadata</button>${['default','admin'].includes(group.name.toLowerCase()) ? '' : '<button id="group-delete" class="danger">Delete Group</button>'}</div></div>
    <div class="compact-form"><label>Weight<input id="group-weight" type="number" value="${attr(group.weight ?? 0)}"></label><label>Prefix<input id="group-prefix" value="${attr(group.prefix || '')}"></label><label>Suffix<input id="group-suffix" value="${attr(group.suffix || '')}"></label><label>Description<input id="group-description" value="${attr(group.description || '')}"></label></div>
    <section class="permission-section"><h2>Parents</h2><div class="token-list">${(group.parents || []).map(parent => `<button data-remove-parent="${attr(parent)}">${esc(parent)} &#215;</button>`).join('') || '<span>No parent groups.</span>'}</div><div class="compact-form"><label>Parent<select id="group-parent-select">${state.permissionData.groups.filter(item => item.name !== group.name).map(item => `<option>${esc(item.name)}</option>`).join('')}</select></label><button id="group-parent-add">Add Parent</button></div><p class="advanced-detail">Parent relationships are global and permanent.</p></section>
    <section class="permission-section direct-permissions"><h2>Direct Permissions</h2>${assignmentTable(group.assignments || [])}</section>
    <section class="permission-section permission-add-section"><h2>Add Permission</h2>${permissionAddForm('group')}</section>`;
  wireAssignmentRemoval(root, group.name);
  root.querySelectorAll('[data-remove-parent]').forEach(button => button.addEventListener('click', () => permissionMutation('group_parent_remove', { group: group.name, parent: button.dataset.removeParent })));
  $('group-parent-add').addEventListener('click', () => permissionMutation('group_parent_add', { group: group.name, parent: $('group-parent-select').value }));
  $('group-save-meta').addEventListener('click', () => permissionMutation('group_update', { group: group.name, metadata: { weight: $('group-weight').value, prefix: $('group-prefix').value, suffix: $('group-suffix').value, description: $('group-description').value } }));
  $('group-delete')?.addEventListener('click', () => deletePermissionGroup(group));
  wirePermissionAddForm(root, 'group', group.name);
}

async function deletePermissionGroup(group) {
  let userCount = 0;
  try {
    const data = await api('/api/permissions/users?page=1&pageSize=100');
    userCount = (data.users || []).filter(user => (user.assignments || []).some(assignment => assignment.kind === 'user group' && assignment.node === group.name)).length;
  } catch (_) {}
  const childCount = state.permissionData.groups.filter(candidate => (candidate.parents || []).includes(group.name)).length;
  if (!await confirmAction(`Delete group ${group.name}? ${userCount} listed users and ${childCount} child groups currently reference it.`, true)) return;
  await permissionMutation('group_delete', { group: group.name, confirmed: true });
  state.selectedPermissionTarget = null;
  $('permission-editor').className = 'detail-editor empty-detail';
  $('permission-editor').textContent = 'Select a group or user.';
}

function renderUserEditor(user) {
  if (!user) return;
  const root = $('permission-editor');
  root.className = 'detail-editor permission-subject-editor';
  const assignments = user.assignments || [];
  const groupAssignments = assignments.filter(item => item.kind === 'user group');
  const permissions = assignments.filter(item => item.kind !== 'user group');
  root.innerHTML = `<div class="detail-header permission-subject-header"><div><h2>${esc(user.name || user.uuid)}</h2><span>${esc(user.uuid)} · ${user.online ? 'Online' : 'Offline'} · last seen ${relativeTime(user.lastSeenMs)}</span><span id="user-primary-group">Primary group: loading...</span></div></div>
    <section class="permission-section"><h2>Group Memberships</h2>${assignmentTable(groupAssignments)}<div class="compact-form"><label>Group<select id="user-group-select">${state.permissionData.groups.map(group => `<option>${esc(group.name)}</option>`).join('')}</select></label>${contextExpiryForm('user-group')}<button id="user-group-add">Add Group</button></div></section>
    <section class="permission-section direct-permissions"><h2>Direct Permissions</h2>${assignmentTable(permissions)}</section>
    <section class="permission-section permission-add-section"><h2>Add Direct Permission</h2>${permissionAddForm('user')}</section>
    <section class="permission-section effective-permissions-section"><div class="detail-header"><h2>Effective Permissions</h2><input id="effective-search" placeholder="Filter effective nodes"></div><div id="effective-permissions">Loading effective permissions...</div></section>`;
  wireAssignmentRemoval(root, user.uuid);
  wireContextExpiryForm('user-group');
  $('user-group-add').addEventListener('click', () => {
    const error = contextExpiryError('user-group');
    if (error) return notice(error, true);
    permissionMutation('user_group_add', { user: user.uuid, group: $('user-group-select').value, ...readContextExpiry('user-group') });
  });
  wirePermissionAddForm(root, 'user', user.uuid);
  loadEffectivePermissions(user.uuid);
  loadPermissionUserDetails(user.uuid);
  $('effective-search').addEventListener('input', debounce(() => loadEffectivePermissions(user.uuid), 250));
}

async function loadPermissionUserDetails(user) {
  try {
    const data = await api(`/api/permissions/user?uuidOrName=${encodeURIComponent(user)}`);
    const primary = data.user?.info?.meta?.primaryGroup || data.user?.info?.meta?.primary_group || '-';
    if ($('user-primary-group')) $('user-primary-group').textContent = `Primary group: ${primary}`;
  } catch (_) { if ($('user-primary-group')) $('user-primary-group').textContent = 'Primary group: -'; }
}

function renderNodeEditor(node) {
  if (!node) return;
  const root = $('permission-editor');
  root.className = 'detail-editor';
  const lastTarget = state.lastPermissionSubject;
  root.innerHTML = `<div class="detail-header"><div><h2>${esc(node.node)}</h2><span>${esc(node.source || 'Paradigm')} · ${esc(node.description || 'No description')}</span></div></div><p>Select a group or user target, then choose value, context, and expiry.</p><div class="compact-form"><label>Target type<select id="node-target-type"><option value="group">Group</option><option value="user">User UUID</option></select></label><label>Target<input id="node-target" value="${attr(lastTarget?.id || '')}" placeholder="Group or player UUID"></label></div>${permissionAddForm('node', node.node)}`;
  wirePermissionAddForm(root, 'node', null, node.node);
}

function permissionAddForm(prefix, fixedNode = '') {
  return `<div class="permission-add-form">${fixedNode ? '' : permissionNodePicker(prefix)}<label>Value<select id="${prefix}-permission-value"><option value="false">Allow</option><option value="true">Deny</option></select></label>${contextExpiryForm(prefix)}<button id="${prefix}-permission-add">Add Permission</button></div>`;
}

function permissionNodePicker(prefix) {
  return `<label class="permission-node-field">Permission node<div class="permission-node-picker"><input id="${prefix}-permission-node" placeholder="paradigm.fly" autocomplete="off" role="combobox" aria-autocomplete="list" aria-expanded="false" aria-controls="${prefix}-permission-suggestions"><div id="${prefix}-permission-suggestions" class="permission-suggestions hidden" role="listbox"></div></div></label>`;
}

function contextExpiryForm(prefix) {
  return `<label>Scope<select id="${prefix}-scope"><option value="global">Global</option><option value="current_server">Current server</option><option value="current_network">Current network</option><option value="custom">Custom context</option></select></label><label class="hidden" data-context-custom="${prefix}">Context key<input id="${prefix}-context-key" placeholder="world"></label><label class="hidden" data-context-custom="${prefix}">Context value<input id="${prefix}-context-value" placeholder="minecraft:overworld"></label><label>Expiry<select id="${prefix}-expiry-mode"><option value="permanent">Permanent</option><option value="duration">Duration</option><option value="exact">Exact date/time</option></select></label><label class="hidden" data-expiry-duration="${prefix}">Duration<input id="${prefix}-duration" placeholder="7d or 1d12h"></label><label class="hidden" data-expiry-exact="${prefix}">Exact expiry<input id="${prefix}-exact" type="datetime-local"></label>`;
}

function readContextExpiry(prefix) {
  const scope = $(`${prefix}-scope`)?.value || 'global';
  const expiryMode = $(`${prefix}-expiry-mode`)?.value || 'permanent';
  const contexts = {};
  if (scope === 'custom') {
    const key = $(`${prefix}-context-key`)?.value.trim();
    const value = $(`${prefix}-context-value`)?.value.trim();
    if (key && value) contexts[key] = value;
  }
  const result = { scope, contexts, permanent: expiryMode === 'permanent' };
  if (expiryMode === 'duration') result.duration = $(`${prefix}-duration`)?.value.trim();
  if (expiryMode === 'exact') result.expiresAtMs = new Date($(`${prefix}-exact`)?.value).getTime();
  return result;
}

function wirePermissionAddForm(root, prefix, target, fixedNode = '') {
  wireContextExpiryForm(prefix);
  if (!fixedNode) wirePermissionNodePicker(prefix);
  $(`${prefix}-permission-add`)?.addEventListener('click', async () => {
    const node = fixedNode || $(`${prefix}-permission-node`).value.trim();
    const expiryError = contextExpiryError(prefix);
    if (!node) return notice('Enter a permission node.', true);
    if (expiryError) return notice(expiryError, true);
    const denied = $(`${prefix}-permission-value`).value === 'true';
    let action;
    let body = { permission: node, denied, ...readContextExpiry(prefix), confirmed: false };
    if (prefix === 'group') { action = 'group_permission_add'; body.group = target; }
    else if (prefix === 'user') { action = 'user_permission_add'; body.user = target; }
    else {
      const type = $('node-target-type').value;
      const id = $('node-target').value.trim();
      action = type === 'group' ? 'group_permission_add' : 'user_permission_add';
      body[type === 'group' ? 'group' : 'user'] = id;
      state.lastPermissionSubject = { kind: type, id };
    }
    if (node === '*' || node.endsWith('.*')) {
      if (!await confirmAction(`Add broad permission ${node}? This can grant extensive access.`, true)) return;
      body.confirmed = true;
    }
    permissionMutation(action, body);
  });
}

function wireContextExpiryForm(prefix) {
  const scope = $(`${prefix}-scope`);
  const expiry = $(`${prefix}-expiry-mode`);
  const update = () => {
    document.querySelectorAll(`[data-context-custom="${CSS.escape(prefix)}"]`).forEach(label => label.classList.toggle('hidden', scope?.value !== 'custom'));
    document.querySelectorAll(`[data-expiry-duration="${CSS.escape(prefix)}"]`).forEach(label => label.classList.toggle('hidden', expiry?.value !== 'duration'));
    document.querySelectorAll(`[data-expiry-exact="${CSS.escape(prefix)}"]`).forEach(label => label.classList.toggle('hidden', expiry?.value !== 'exact'));
  };
  scope?.addEventListener('change', update);
  expiry?.addEventListener('change', update);
  update();
}

function contextExpiryError(prefix) {
  const scope = $(`${prefix}-scope`)?.value;
  if (scope === 'custom' && (!$(`${prefix}-context-key`)?.value.trim() || !$(`${prefix}-context-value`)?.value.trim())) return 'Custom context needs both a key and a value.';
  const mode = $(`${prefix}-expiry-mode`)?.value;
  if (mode === 'duration' && parseDurationInput($(`${prefix}-duration`)?.value) <= 0) return 'Enter a valid duration such as 30m, 7d, or 1d12h.';
  if (mode === 'exact') {
    const exact = new Date($(`${prefix}-exact`)?.value).getTime();
    if (!Number.isFinite(exact) || exact <= Date.now()) return 'Choose an exact expiry in the future.';
  }
  return '';
}

function wirePermissionNodePicker(prefix) {
  const input = $(`${prefix}-permission-node`);
  const menu = $(`${prefix}-permission-suggestions`);
  if (!input || !menu) return;
  let request = 0;
  let active = -1;
  const close = () => { menu.classList.add('hidden'); input.setAttribute('aria-expanded', 'false'); active = -1; };
  const activate = index => {
    const options = [...menu.querySelectorAll('[role="option"]')];
    active = Math.max(-1, Math.min(index, options.length - 1));
    options.forEach((option, optionIndex) => option.classList.toggle('active', optionIndex === active));
    options[active]?.scrollIntoView({ block: 'nearest' });
  };
  const choose = option => {
    input.value = option.dataset.permissionSuggestion;
    close();
    input.focus();
  };
  const render = nodes => {
    menu.innerHTML = nodes.slice(0, 10).map(node => `<button type="button" role="option" data-permission-suggestion="${attr(node.node)}"><strong>${esc(node.node)}</strong><small>${esc(node.description || node.source || '')}</small></button>`).join('');
    menu.classList.toggle('hidden', !nodes.length);
    input.setAttribute('aria-expanded', String(nodes.length > 0));
    active = -1;
  };
  const update = debounce(async () => {
    const query = input.value.trim();
    const current = ++request;
    if (!query) return close();
    try {
      const data = await api(`/api/permissions/nodes?query=${encodeURIComponent(query)}&page=1&pageSize=10`);
      if (current === request && document.activeElement === input) render(data.nodes || []);
    } catch (_) { if (current === request) close(); }
  }, 140);
  input.addEventListener('input', update);
  input.addEventListener('focus', update);
  input.addEventListener('blur', () => setTimeout(close, 100));
  input.addEventListener('keydown', event => {
    const options = [...menu.querySelectorAll('[role="option"]')];
    if (event.key === 'ArrowDown' && options.length) { event.preventDefault(); activate(active + 1); }
    else if (event.key === 'ArrowUp' && options.length) { event.preventDefault(); activate(active <= 0 ? options.length - 1 : active - 1); }
    else if (event.key === 'Enter' && active >= 0) { event.preventDefault(); choose(options[active]); }
    else if (event.key === 'Escape') close();
  });
  menu.addEventListener('mousedown', event => event.preventDefault());
  menu.addEventListener('click', event => { const option = event.target.closest('[data-permission-suggestion]'); if (option) choose(option); });
}

function assignmentTable(assignments) {
  if (!assignments.length) return empty('No direct assignments.');
  return `<div class="data-surface"><table class="assignment-table"><thead><tr><th>Value</th><th>Node / Group</th><th>Context</th><th>Expiry</th><th></th></tr></thead><tbody>${assignments.map(item => `<tr><td><span class="status-badge ${item.denied ? 'bad' : 'good'}">${item.denied ? 'Deny' : 'Allow'}</span></td><td>${esc(item.node)}</td><td>${contextBadge(item.contexts)}</td><td>${expiryBadge(item.expiresAtMs)}</td><td><button data-remove-assignment="${attr(item.id)}" data-assignment-kind="${attr(item.kind)}">Remove</button><span class="advanced-detail">${esc(item.id)}</span></td></tr>`).join('')}</tbody></table></div>`;
}

function wireAssignmentRemoval(root, target) {
  root.querySelectorAll('[data-remove-assignment]').forEach(button => button.addEventListener('click', async () => {
    if (!await confirmAction('Remove this exact permission assignment?', false)) return;
    const kind = button.dataset.assignmentKind;
    const action = kind === 'group permission' ? 'group_permission_remove' : kind === 'user group' ? 'user_group_remove' : 'user_permission_remove';
    const body = { assignmentId: button.dataset.removeAssignment };
    if (kind === 'group permission') body.group = target; else body.user = target;
    permissionMutation(action, body);
  }));
}

async function permissionMutation(action, body) {
  try {
    const path = ({
      group_create: 'group/create', group_delete: 'group/delete', group_update: 'group/update',
      group_parent_add: 'group/parent/add', group_parent_remove: 'group/parent/remove',
      group_permission_add: 'group/permission/add', group_permission_remove: 'group/permission/remove',
      user_permission_add: 'user/permission/add', user_permission_remove: 'user/permission/remove',
      user_group_add: 'user/group/add', user_group_remove: 'user/group/remove',
      track_create: 'track/create', track_delete: 'track/delete', track_clear: 'track/clear',
      track_append: 'track/append', track_remove: 'track/remove', track_insert: 'track/insert', track_move: 'track/move',
      track_clone: 'track/clone', track_rename: 'track/rename', promote: 'user/track/promote', demote: 'user/track/demote',
      settrack: 'user/track/settrack', cleartrack: 'user/track/cleartrack'
    })[action];
    const result = await api(`/api/permissions/${path}`, { method: 'POST', body: JSON.stringify(body) });
    if (!result.applied && result.confirmationRequired) {
      if (body.confirmed) {
        notice('The server did not accept the confirmed permission change. Reload the page and try again.', true);
        return null;
      }
      if (!await confirmAction(permissionConfirmationMessage(action, body), true)) return null;
      return permissionMutation(action, { ...body, confirmed: true });
    }
    notice(result.message || 'Permission change applied.');
    await loadPermissions();
    return result;
  } catch (error) {
    notice(error.message, true);
    return null;
  }
}

function permissionConfirmationMessage(action, body) {
  const group = body.group || 'the selected group';
  const permission = body.permission || 'the selected permission';
  return ({
    group_create: `Create the privileged group "${group}"? Members may receive administrative access.`,
    group_delete: `Delete the privileged group "${group}"?`,
    group_update: `Change metadata for the privileged group "${group}"?`,
    group_parent_add: `Add "${body.parent || 'this group'}" as a parent of "${group}"? Members may inherit administrative access.`,
    group_parent_remove: `Remove the privileged parent "${body.parent || 'this group'}" from "${group}"?`,
    group_permission_add: `Add the broad or administrative permission "${permission}" to "${group}"?`,
    group_permission_remove: `Remove this permission assignment from the privileged group "${group}"?`,
    user_permission_add: `Add the broad or administrative permission "${permission}" to this user?`,
    user_permission_remove: 'Remove this broad or administrative permission from the user?',
    user_group_add: `Add this user to the privileged group "${group}"?`,
    user_group_remove: `Remove this user from the privileged group "${group}"?`
  })[action] || 'Apply this broad or administrative permission change?';
}

async function createPermissionGroup() {
  const name = await promptAction('Create Permission Group', 'Choose a stable group name. You can configure inheritance and nodes after creation.', '', 'Group name');
  if (!name) return;
  const result = await permissionMutation('group_create', { group: name, confirmed: false });
  if (!result?.applied) return;
  state.selectedPermissionTarget = { kind: 'group', id: name };
  renderPermissionTargetList();
  renderPermissionEditor();
}

async function loadEffectivePermissions(user) {
  try {
    const query = encodeURIComponent($('effective-search')?.value || '');
    const data = await api(`/api/permissions/effective?uuidOrName=${encodeURIComponent(user)}&query=${query}&page=1&pageSize=50`);
    $('effective-permissions').innerHTML = data.entries.length ? dataTable(['Result','Node','Source','Matched rule'], data.entries.map(entry => [entry.allowed ? 'Allow' : 'Deny', entry.node, `${entry.sourceType || '-'} ${entry.sourceName || ''}`, entry.rule || '-'])) : empty('No defined effective permissions matched.');
  } catch (error) { renderError('effective-permissions', error.message); }
}

function contextBadge(contexts) {
  const entries = Object.entries(contexts || {});
  return `<span class="context-badge">${esc(entries.length ? entries.map(([key, value]) => `${key}=${value}`).join(', ') : 'global')}</span>`;
}

function expiryBadge(expiresAtMs) {
  if (!expiresAtMs) return '<span class="expiry-badge">permanent</span>';
  if (expiresAtMs <= Date.now()) return '<span class="expiry-badge">expired</span>';
  return `<span class="expiry-badge">${esc(relativeTime(expiresAtMs))}</span>`;
}

function hologramPlaceholders() {
  return state.hologramData?.placeholderTokens || [];
}

async function loadHolograms() {
  try {
    state.hologramData = await api('/api/holograms');
    const definitions = state.hologramData.config?.holograms || {};
    if (!state.selectedHologram || !definitions[state.selectedHologram]) {
      state.selectedHologram = Object.keys(definitions).sort()[0] || null;
    }
    state.hologramDraft = state.selectedHologram
      ? structuredClone(definitions[state.selectedHologram])
      : null;
    renderHologramSettings();
    renderHologramList();
    renderHologramEditor();
  } catch (error) {
    renderError('hologram-editor', error.message);
  }
}

function renderHologramSettings() {
  const root = $('hologram-settings');
  const config = state.hologramData?.config;
  if (!root || !config) return;
  root.innerHTML = `<div class="detail-header"><div><h2>Global Settings</h2><span>Rendering mode: ${esc(config.renderMode || 'auto')}</span></div><button id="hologram-settings-save">Save Settings</button></div>
    <div class="hologram-global-form">
      <label class="switch-label"><input id="hologram-global-enabled" type="checkbox" ${config.enabled ? 'checked' : ''}> Enabled</label>
      <label>Default view distance<input id="hologram-default-distance" type="number" min="1" max="512" step="1" value="${attr(config.defaultViewDistance)}"></label>
      <label>Default refresh interval<input id="hologram-default-refresh" type="number" min="1" max="3600" step="1" value="${attr(config.defaultRefreshIntervalSeconds)}"></label>
    </div>
    ${hologramCapabilityWarnings()}
    ${hologramRuntimeMonitor()}`;
  $('hologram-settings-save').addEventListener('click', saveHologramSettings);
  root.querySelectorAll('[data-temp-remove]').forEach(button => button.addEventListener('click', async () => {
    if (!await confirmAction(`Remove temporary hologram ${button.dataset.tempRemove}?`, true)) return;
    await runHologramOperation('temporary-remove', { id: button.dataset.tempRemove });
  }));
}

function hologramCapabilityWarnings() {
  if (!state.hologramData?.supported) return '<div class="notice-inline">This loader does not provide hologram rendering.</div>';
  const capabilities = state.hologramData.capabilities || {};
  const unavailable = [
    ['textDisplay', 'Text Display entities'], ['billboard', 'billboard'], ['alignment', 'alignment'], ['scale', 'scale'],
    ['textShadow', 'text shadow'], ['background', 'background'], ['textOpacity', 'text opacity'], ['seeThrough', 'see-through'],
    ['lineWidth', 'line wrapping'], ['viewerSpecificVisibility', 'viewer-specific visibility'], ['interaction', 'native interactions']
  ].filter(([key]) => !capabilities[key]).map(([, label]) => label);
  return unavailable.length ? `<div class="notice-inline">This target safely approximates or does not expose: ${esc(unavailable.join(', '))}.</div>` : '';
}

function hologramRuntimeMonitor() {
  const status = Object.values(state.hologramData?.runtimeStatus || {});
  const temporary = state.hologramData?.temporary || [];
  const rows = status.length ? status.map(value => `<li><strong>${esc(value.id)}</strong> · ${value.chunkLoaded ? 'chunk loaded' : 'chunk unloaded'} · ${Number(value.renderedEntities || 0)} entities${value.dirty ? ' · redraw queued' : ''}</li>`).join('') : '<li>No runtime holograms.</li>';
  const temps = temporary.length ? temporary.map(value => `<li><strong>${esc(value.id)}</strong> · ${esc(value.owner || 'unknown')} · ${value.expiresAt ? relativeTime(value.expiresAt) : 'no expiry'} <button data-temp-remove="${attr(value.id)}" class="danger">Remove</button></li>`).join('') : '<li>No temporary holograms.</li>';
  return `<div class="hologram-monitor"><h3>Runtime monitor</h3><div><strong>Persistent</strong><ul>${rows}</ul></div><div><strong>Temporary</strong><ul>${temps}</ul></div></div>`;
}

function renderHologramList() {
  const root = $('hologram-list');
  if (!root) return;
  const definitions = state.hologramData?.config?.holograms || {};
  const ids = Object.keys(definitions).sort();
  root.innerHTML = ids.length ? ids.map(id => {
    const definition = definitions[id];
    return `<button class="selection-item ${id === state.selectedHologram ? 'active' : ''}" data-hologram-id="${attr(id)}">
      <strong>${esc(id)}</strong><small>${esc(definition.dimension)} · ${(definition.lines || []).length} lines · ${definition.enabled ? 'enabled' : 'disabled'}</small>
    </button>`;
  }).join('') : empty('No holograms configured.');
  root.querySelectorAll('[data-hologram-id]').forEach(button => button.addEventListener('click', () => {
    state.selectedHologram = button.dataset.hologramId;
    state.hologramDraft = structuredClone(definitions[state.selectedHologram]);
    renderHologramList();
    renderHologramEditor();
  }));
}

function renderHologramEditor() {
  const root = $('hologram-editor');
  const definition = state.hologramDraft;
  if (!root || !definition || !state.selectedHologram) {
    if (root) {
      root.className = 'detail-editor empty-detail empty-detail-action';
      root.innerHTML = '<div><h2>Create your first hologram</h2><p>Choose a stable ID, then configure its location, lines, visibility, and interactions.</p><button id="hologram-empty-create">New Hologram</button></div>';
      $('hologram-empty-create').addEventListener('click', createHologram);
    }
    return;
  }

  root.className = 'detail-editor hologram-editor';
  root.innerHTML = `<div class="hologram-subject-header">
      <div><h2>${esc(state.selectedHologram)}</h2><span>${esc(definition.dimension)}</span></div>
      <div class="detail-header-actions"><button id="hologram-duplicate">Duplicate</button><button id="hologram-rename">Rename</button><button id="hologram-delete" class="danger">Delete</button><button id="hologram-save">Save</button></div>
    </div>
    ${state.hologramValidationError ? `<div class="field-error">${esc(state.hologramValidationError)}</div>` : ''}
    <section class="hologram-section"><h3>Location and Rendering</h3>
      <div class="hologram-field-grid">
        <label class="switch-label"><input data-hologram-field="enabled" type="checkbox" ${definition.enabled ? 'checked' : ''}> Enabled</label>
        <label>Dimension<input data-hologram-field="dimension" list="hologram-dimensions" value="${attr(definition.dimension)}"></label>
        <label>X<input data-hologram-field="x" type="number" step="0.01" value="${attr(definition.x)}"></label>
        <label>Y<input data-hologram-field="y" type="number" step="0.01" value="${attr(definition.y)}"></label>
        <label>Z<input data-hologram-field="z" type="number" step="0.01" value="${attr(definition.z)}"></label>
        <label>View distance<input data-hologram-field="viewDistance" type="number" min="1" max="512" step="1" value="${attr(definition.viewDistance)}"></label>
        <label>Refresh interval<input data-hologram-field="refreshIntervalSeconds" type="number" min="1" max="3600" step="1" value="${attr(definition.refreshIntervalSeconds)}"></label>
        <label>Line spacing<input data-hologram-field="lineSpacing" type="number" min="0.05" max="4" step="0.01" value="${attr(definition.lineSpacing)}"></label>
      </div>
      <datalist id="hologram-dimensions">${(state.hologramData?.loadedDimensions || []).map(value => `<option value="${attr(value)}">`).join('')}</datalist>
      ${playerLocationControl()}
    </section>
    <section class="hologram-section"><h3>Display</h3>
      <div class="hologram-field-grid">
        <label>Billboard<select data-hologram-path="display.billboard"><option value="center" ${definition.display?.billboard === 'center' ? 'selected' : ''}>Center</option><option value="fixed" ${definition.display?.billboard === 'fixed' ? 'selected' : ''}>Fixed</option><option value="vertical" ${definition.display?.billboard === 'vertical' ? 'selected' : ''}>Vertical</option><option value="horizontal" ${definition.display?.billboard === 'horizontal' ? 'selected' : ''}>Horizontal</option></select></label>
        <label>Alignment<select data-hologram-path="display.alignment"><option value="left" ${definition.display?.alignment === 'left' ? 'selected' : ''}>Left</option><option value="center" ${definition.display?.alignment !== 'left' && definition.display?.alignment !== 'right' ? 'selected' : ''}>Center</option><option value="right" ${definition.display?.alignment === 'right' ? 'selected' : ''}>Right</option></select></label>
        <label>Scale<input data-hologram-path="display.scale" type="number" min="0.05" max="16" step="0.05" value="${attr(definition.display?.scale ?? 1)}"></label>
        <label>Maximum line width<input data-hologram-path="display.maxLineWidth" type="number" min="0" max="1024" value="${attr(definition.display?.maxLineWidth ?? 0)}"></label>
        <label>Background color<input data-hologram-path="display.backgroundColor" type="color" value="${attr(definition.display?.backgroundColor || '#000000')}"></label>
        <label>Background opacity<input data-hologram-path="display.backgroundOpacity" type="number" min="0" max="1" step="0.01" value="${attr(definition.display?.backgroundOpacity ?? 0)}"></label>
        <label>Text opacity<input data-hologram-path="display.textOpacity" type="number" min="0" max="1" step="0.01" value="${attr(definition.display?.textOpacity ?? 1)}"></label>
        <label class="switch-label"><input data-hologram-path="display.textShadow" type="checkbox" ${definition.display?.textShadow ? 'checked' : ''}> Text shadow</label>
        <label class="switch-label"><input data-hologram-path="display.seeThrough" type="checkbox" ${definition.display?.seeThrough ? 'checked' : ''}> See-through</label>
      </div>
    </section>
    ${hologramVisibilityEditor(definition)}
    ${hologramInteractionEditor(definition)}
    <section class="hologram-section"><div class="detail-header"><div><h3>Lines</h3><span>One native entity per line.</span></div><button id="hologram-add-line">Add Line</button></div>
      <div id="hologram-lines" class="reorder-list">${(definition.lines || []).map(hologramLineEditor).join('') || '<div class="reorder-empty">No lines configured.</div>'}</div>
      <h3>Preview</h3><div id="hologram-preview" class="minecraft-preview multiline hologram-live-preview"></div>
    </section>`;

  wireHologramEditor(root);
  updateHologramPreview();
}

function hologramLineEditor(text, index) {
  return `<div class="reorder-row hologram-line-row" data-hologram-line-row="${index}">
    <span class="reorder-handle">${index + 1}</span>
    <div class="hologram-line-content">
      ${formattingToolbar(`hologram-line-${index}`, hologramPlaceholders())}
      <textarea class="reorder-editor format-editor" data-hologram-line="${index}" data-config-key="hologram-line-${index}">${esc(text)}</textarea>
    </div>
    <div class="reorder-actions"><button data-hologram-line-up="${index}" title="Move up" aria-label="Move hologram line up" ${index === 0 ? 'disabled' : ''}>↑</button><button data-hologram-line-down="${index}" title="Move down" aria-label="Move hologram line down" ${index === state.hologramDraft.lines.length - 1 ? 'disabled' : ''}>↓</button><button data-hologram-line-delete="${index}" title="Delete" aria-label="Delete hologram line">×</button></div>
  </div>`;
}

function hologramVisibilityEditor(definition) {
  const visibility = definition.visibility || { mode: 'all', negate: false, conditions: [] };
  const rows = (visibility.conditions || []).map((condition, index) => `<div class="hologram-condition-row" data-condition-row="${index}">
    <select data-condition-field="type" data-condition-index="${index}">${['permission','group','operator','world','distance','time','weather'].map(type => `<option value="${type}" ${condition.type === type ? 'selected' : ''}>${type}</option>`).join('')}</select>
    <input data-condition-field="value" data-condition-index="${index}" placeholder="Value / world / group / weather" value="${attr(condition.value || '')}">
    <label>Min<input data-condition-field="minDistance" data-condition-index="${index}" type="number" value="${attr(condition.minDistance ?? '')}"></label>
    <label>Max<input data-condition-field="maxDistance" data-condition-index="${index}" type="number" value="${attr(condition.maxDistance ?? '')}"></label>
    <label>Start<input data-condition-field="startTime" data-condition-index="${index}" type="number" min="0" max="23999" value="${attr(condition.startTime ?? '')}"></label>
    <label>End<input data-condition-field="endTime" data-condition-index="${index}" type="number" min="0" max="23999" value="${attr(condition.endTime ?? '')}"></label>
    <label class="switch-label"><input data-condition-field="negate" data-condition-index="${index}" type="checkbox" ${condition.negate ? 'checked' : ''}> Not</label>
    <button data-condition-remove="${index}" class="danger" title="Delete" aria-label="Delete visibility condition">×</button></div>`).join('') || '<div class="reorder-empty">Visible to every player.</div>';
  return `<section class="hologram-section"><div class="detail-header"><div><h3>Visibility conditions</h3><span>Rules are evaluated for each viewer.</span></div><button id="hologram-condition-add">Add condition</button></div>
    <div class="compact-form"><label>Match<select data-hologram-path="visibility.mode"><option value="all" ${visibility.mode !== 'any' ? 'selected' : ''}>All conditions</option><option value="any" ${visibility.mode === 'any' ? 'selected' : ''}>Any condition</option></select></label><label class="switch-label"><input data-hologram-path="visibility.negate" type="checkbox" ${visibility.negate ? 'checked' : ''}> Negate group</label></div>
    <div class="hologram-condition-list">${rows}</div></section>`;
}

function hologramInteractionEditor(definition) {
  const interaction = definition.interaction || { enabled: false, width: 1, height: 1, cooldownSeconds: 0, onInteract: [], onAttack: [] };
  return `<section class="hologram-section"><div class="detail-header"><div><h3>Interaction</h3><span>Owned invisible carrier with per-player cooldown.</span></div></div>
    <div class="hologram-field-grid"><label class="switch-label"><input data-hologram-path="interaction.enabled" type="checkbox" ${interaction.enabled ? 'checked' : ''}> Enabled</label><label>Hitbox width<input data-hologram-path="interaction.width" type="number" min="0.1" max="16" step="0.1" value="${attr(interaction.width ?? 1)}"></label><label>Hitbox height<input data-hologram-path="interaction.height" type="number" min="0.1" max="16" step="0.1" value="${attr(interaction.height ?? 1)}"></label><label>Cooldown seconds<input data-hologram-path="interaction.cooldownSeconds" type="number" min="0" max="86400" value="${attr(interaction.cooldownSeconds ?? 0)}"></label></div>
    ${hologramInteractionConditions(interaction.conditions || { mode: 'all', conditions: [] })}
    ${hologramActionList('interact', interaction.onInteract || [])}${hologramActionList('attack', interaction.onAttack || [])}</section>`;
}

function hologramInteractionConditions(group) {
  const rows = (group.conditions || []).map((condition, index) => `<div class="hologram-condition-row"><select data-interaction-condition-field="type" data-interaction-condition-index="${index}" aria-label="Condition type">${['permission','group','operator','world','distance','time','weather'].map(type => `<option value="${type}" ${condition.type === type ? 'selected' : ''}>${type}</option>`).join('')}</select><input data-interaction-condition-field="value" data-interaction-condition-index="${index}" aria-label="Condition value" placeholder="Value / world / group / weather" value="${attr(condition.value || '')}"><label>Min<input data-interaction-condition-field="minDistance" data-interaction-condition-index="${index}" type="number" value="${attr(condition.minDistance ?? '')}"></label><label>Max<input data-interaction-condition-field="maxDistance" data-interaction-condition-index="${index}" type="number" value="${attr(condition.maxDistance ?? '')}"></label><label>Start<input data-interaction-condition-field="startTime" data-interaction-condition-index="${index}" type="number" min="0" max="23999" value="${attr(condition.startTime ?? '')}"></label><label>End<input data-interaction-condition-field="endTime" data-interaction-condition-index="${index}" type="number" min="0" max="23999" value="${attr(condition.endTime ?? '')}"></label><label class="switch-label"><input data-interaction-condition-field="negate" data-interaction-condition-index="${index}" type="checkbox" ${condition.negate ? 'checked' : ''}> Not</label><button data-interaction-condition-remove="${index}" class="danger" title="Delete" aria-label="Delete interaction condition">×</button></div>`).join('') || '<div class="reorder-empty">No additional interaction restrictions.</div>';
  return `<div class="hologram-action-list"><div class="detail-header"><h4>Interaction conditions</h4><button id="hologram-interaction-condition-add">Add condition</button></div><div class="compact-form"><label>Match<select data-hologram-path="interaction.conditions.mode"><option value="all" ${group.mode !== 'any' ? 'selected' : ''}>All conditions</option><option value="any" ${group.mode === 'any' ? 'selected' : ''}>Any condition</option></select></label><label class="switch-label"><input data-hologram-path="interaction.conditions.negate" type="checkbox" ${group.negate ? 'checked' : ''}> Negate group</label></div>${rows}</div>`;
}

function hologramActionList(kind, actions) {
  return `<div class="hologram-action-list"><div class="detail-header"><h4>${kind === 'interact' ? 'Right-click actions' : 'Left-click actions'}</h4><button data-action-add="${kind}">Add action</button></div>${actions.map((action, index) => `<div class="hologram-action-row"><select data-action-field="type" data-action-kind="${kind}" data-action-index="${index}" aria-label="Action type">${['message','actionbar','title','sound','player_command','console_command'].map(type => `<option value="${type}" ${action.type === type ? 'selected' : ''}>${type}</option>`).join('')}</select><input data-action-field="text" data-action-kind="${kind}" data-action-index="${index}" aria-label="Action text or title" placeholder="Text / title" value="${attr(action.text || '')}"><input data-action-field="subtitle" data-action-kind="${kind}" data-action-index="${index}" aria-label="Action subtitle" placeholder="Subtitle" value="${attr(action.subtitle || '')}"><input data-action-field="sound" data-action-kind="${kind}" data-action-index="${index}" aria-label="Sound ID" placeholder="Sound id" value="${attr(action.sound || '')}"><select data-action-field="soundCategory" data-action-kind="${kind}" data-action-index="${index}" aria-label="Sound category">${['master','music','records','weather','blocks','hostile','neutral','players','ambient','voice'].map(category => `<option value="${category}" ${(action.soundCategory || 'master') === category ? 'selected' : ''}>${category}</option>`).join('')}</select><input data-action-field="volume" data-action-kind="${kind}" data-action-index="${index}" type="number" min="0" max="10" step="0.1" aria-label="Sound volume" placeholder="Volume" value="${attr(action.volume ?? 1)}"><input data-action-field="pitch" data-action-kind="${kind}" data-action-index="${index}" type="number" min="0" max="4" step="0.1" aria-label="Sound pitch" placeholder="Pitch" value="${attr(action.pitch ?? 1)}"><input data-action-field="command" data-action-kind="${kind}" data-action-index="${index}" aria-label="Command" placeholder="Command" value="${attr(action.command || '')}"><button data-action-remove="${kind}:${index}" class="danger" title="Delete" aria-label="Delete action">×</button></div>`).join('') || '<div class="reorder-empty">No actions.</div>'}</div>`;
}

function playerLocationControl() {
  const players = state.hologramData?.onlinePlayers || [];
  return `<div class="compact-form hologram-player-location"><label>Online player<select id="hologram-location-player"><option value="">Select player</option>${players.map(player => `<option value="${attr(player.name)}">${esc(player.name)} · ${esc(player.dimension)}</option>`).join('')}</select></label><button id="hologram-use-player-location" ${players.length ? '' : 'disabled'}>Use Player Location</button></div>`;
}

function wireHologramEditor(root) {
  ensureHologramDraftModels();
  root.querySelectorAll('[data-hologram-field]').forEach(input => bindHologramInput(input, () => {
    const field = input.dataset.hologramField;
    state.hologramDraft[field] = input.type === 'checkbox' ? input.checked : input.type === 'number' ? Number(input.value) : input.value;
  }));
  root.querySelectorAll('[data-hologram-path]').forEach(input => bindHologramInput(input, () => {
    setHologramPath(input.dataset.hologramPath, input.type === 'checkbox' ? input.checked : input.type === 'number' ? Number(input.value) : input.value);
    updateHologramPreview();
  }));
  root.querySelectorAll('[data-condition-field]').forEach(input => bindHologramInput(input, () => {
    const condition = state.hologramDraft.visibility.conditions[Number(input.dataset.conditionIndex)];
    condition[input.dataset.conditionField] = input.type === 'checkbox' ? input.checked : input.type === 'number' && input.value !== '' ? Number(input.value) : input.value;
  }));
  root.querySelectorAll('[data-condition-remove]').forEach(button => button.addEventListener('click', () => {
    state.hologramDraft.visibility.conditions.splice(Number(button.dataset.conditionRemove), 1);
    renderHologramEditor();
  }));
  const conditionAdd = $('hologram-condition-add');
  if (conditionAdd) conditionAdd.addEventListener('click', () => {
    state.hologramDraft.visibility.conditions.push({ type: 'permission', value: '', negate: false });
    renderHologramEditor();
  });
  root.querySelectorAll('[data-interaction-condition-field]').forEach(input => bindHologramInput(input, () => {
    const condition = state.hologramDraft.interaction.conditions.conditions[Number(input.dataset.interactionConditionIndex)];
    condition[input.dataset.interactionConditionField] = input.type === 'checkbox' ? input.checked : input.type === 'number' && input.value !== '' ? Number(input.value) : input.value;
  }));
  root.querySelectorAll('[data-interaction-condition-remove]').forEach(button => button.addEventListener('click', () => {
    state.hologramDraft.interaction.conditions.conditions.splice(Number(button.dataset.interactionConditionRemove), 1);
    renderHologramEditor();
  }));
  const interactionConditionAdd = $('hologram-interaction-condition-add');
  if (interactionConditionAdd) interactionConditionAdd.addEventListener('click', () => {
    state.hologramDraft.interaction.conditions.conditions.push({ type: 'permission', value: '', negate: false });
    renderHologramEditor();
  });
  root.querySelectorAll('[data-action-field]').forEach(input => bindHologramInput(input, () => {
    const actions = input.dataset.actionKind === 'attack' ? state.hologramDraft.interaction.onAttack : state.hologramDraft.interaction.onInteract;
    actions[Number(input.dataset.actionIndex)][input.dataset.actionField] = input.type === 'number' && input.value !== '' ? Number(input.value) : input.value;
  }));
  root.querySelectorAll('[data-action-remove]').forEach(button => button.addEventListener('click', () => {
    const [kind, index] = button.dataset.actionRemove.split(':');
    const actions = kind === 'attack' ? state.hologramDraft.interaction.onAttack : state.hologramDraft.interaction.onInteract;
    actions.splice(Number(index), 1);
    renderHologramEditor();
  }));
  root.querySelectorAll('[data-action-add]').forEach(button => button.addEventListener('click', () => {
    const actions = button.dataset.actionAdd === 'attack' ? state.hologramDraft.interaction.onAttack : state.hologramDraft.interaction.onInteract;
    actions.push({ type: 'message', text: '<color:white>Activated</color>' });
    renderHologramEditor();
  }));
  root.querySelectorAll('[data-hologram-line]').forEach(input => input.addEventListener('input', () => {
    state.hologramDraft.lines[Number(input.dataset.hologramLine)] = input.value;
    updateHologramPreview();
  }));
  root.querySelectorAll('[data-format-tag]').forEach(button => button.addEventListener('click', () => {
    const row = button.closest('[data-hologram-line-row]');
    applyFormatInput(row.querySelector('textarea'), button.dataset.formatTag);
  }));
  root.querySelectorAll('[data-placeholder-for]').forEach(select => select.addEventListener('change', () => {
    if (!select.value) return;
    const row = select.closest('[data-hologram-line-row]');
    const input = row.querySelector('textarea');
    insertAtCursor(input, select.value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    select.value = '';
  }));
  root.querySelectorAll('[data-hologram-line-up]').forEach(button => button.addEventListener('click', () => moveHologramLine(Number(button.dataset.hologramLineUp), -1)));
  root.querySelectorAll('[data-hologram-line-down]').forEach(button => button.addEventListener('click', () => moveHologramLine(Number(button.dataset.hologramLineDown), 1)));
  root.querySelectorAll('[data-hologram-line-delete]').forEach(button => button.addEventListener('click', () => {
    state.hologramDraft.lines.splice(Number(button.dataset.hologramLineDelete), 1);
    renderHologramEditor();
  }));
  $('hologram-add-line').addEventListener('click', () => {
    state.hologramDraft.lines.push('<color:white>New line</color>');
    renderHologramEditor();
  });
  $('hologram-use-player-location').addEventListener('click', useSelectedPlayerLocation);
  $('hologram-save').addEventListener('click', saveSelectedHologram);
  $('hologram-duplicate').addEventListener('click', duplicateSelectedHologram);
  $('hologram-rename').addEventListener('click', renameSelectedHologram);
  $('hologram-delete').addEventListener('click', deleteSelectedHologram);
}

function bindHologramInput(input, listener) {
  input.addEventListener('input', listener);
  if (input.tagName === 'SELECT' || input.type === 'checkbox' || input.type === 'color') input.addEventListener('change', listener);
}

function ensureHologramDraftModels() {
  const definition = state.hologramDraft;
  if (!definition) return;
  definition.display ||= { billboard: 'center', alignment: 'center', scale: 1, textShadow: false, backgroundColor: '#000000', backgroundOpacity: 0, textOpacity: 1, seeThrough: false, maxLineWidth: 0 };
  definition.visibility ||= { mode: 'all', negate: false, conditions: [] };
  definition.visibility.conditions ||= [];
  definition.interaction ||= { enabled: false, width: 1, height: 1, cooldownSeconds: 0, conditions: { mode: 'all', conditions: [] }, onInteract: [], onAttack: [] };
  definition.interaction.conditions ||= { mode: 'all', negate: false, conditions: [] };
  definition.interaction.conditions.conditions ||= [];
  definition.interaction.onInteract ||= [];
  definition.interaction.onAttack ||= [];
}

function setHologramPath(path, value) {
  const parts = path.split('.');
  let target = state.hologramDraft;
  parts.slice(0, -1).forEach(part => { target[part] ||= {}; target = target[part]; });
  target[parts[parts.length - 1]] = value;
}

function moveHologramLine(index, direction) {
  const target = index + direction;
  if (target < 0 || target >= state.hologramDraft.lines.length) return;
  const [line] = state.hologramDraft.lines.splice(index, 1);
  state.hologramDraft.lines.splice(target, 0, line);
  renderHologramEditor();
}

function useSelectedPlayerLocation() {
  const playerName = $('hologram-location-player').value;
  const player = (state.hologramData?.onlinePlayers || []).find(candidate => candidate.name === playerName);
  if (!player) return notice('Select an online player.', true);
  Object.assign(state.hologramDraft, {
    dimension: player.dimension,
    x: player.x,
    y: player.y,
    z: player.z
  });
  renderHologramEditor();
}

function updateHologramPreview() {
  const display = state.hologramDraft?.display || {};
  const preview = $('hologram-preview');
  if (preview) {
    preview.style.textAlign = display.alignment || 'center';
    preview.style.transform = `scale(${Math.max(0.05, Number(display.scale || 1))})`;
    preview.style.transformOrigin = display.alignment === 'left' ? 'left center' : display.alignment === 'right' ? 'right center' : 'center';
    preview.style.backgroundColor = hexWithOpacity(display.backgroundColor || '#000000', Number(display.backgroundOpacity || 0));
    preview.style.textShadow = display.textShadow ? '1px 1px 0 #000' : '';
    preview.style.opacity = String(display.textOpacity ?? 1);
    preview.style.maxWidth = Number(display.maxLineWidth || 0) > 0 ? `${display.maxLineWidth}px` : '';
    preview.style.lineHeight = String(Math.max(0.4, Number(state.hologramDraft?.lineSpacing || 0.28) * 3));
  }
  renderMinecraftPreview(preview, state.hologramDraft?.lines || [], {
    online_players: '24',
    max_players: '100',
    server_name: 'Cobbleverse',
    server_id: 'survival',
    network_id: 'main',
    world: state.hologramDraft?.dimension || 'minecraft:overworld'
  });
}

function hexWithOpacity(hex, opacity) {
  const normalized = /^#[0-9a-f]{6}$/i.test(hex || '') ? hex : '#000000';
  const alpha = Math.max(0, Math.min(1, opacity));
  const value = parseInt(normalized.slice(1), 16);
  return `rgba(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255}, ${alpha})`;
}

async function saveSelectedHologram() {
  try {
    await api('/api/holograms/update', {
      method: 'POST',
      body: JSON.stringify({ id: state.selectedHologram, definition: state.hologramDraft })
    });
    state.hologramValidationError = null;
    notice(`Hologram ${state.selectedHologram} saved and scheduled for redraw.`);
    await loadHolograms();
  } catch (error) {
    state.hologramValidationError = error.message;
    renderHologramEditor();
    notice(error.message, true);
  }
}

async function createHologram() {
  const id = await promptAction('Create Hologram', 'Use lowercase letters, numbers, underscores, or hyphens.', '', 'Hologram ID');
  if (!id) return;
  const config = state.hologramData?.config || {};
  const player = state.hologramData?.onlinePlayers?.[0];
  const definition = {
    enabled: true,
    dimension: player?.dimension || 'minecraft:overworld',
    x: player?.x ?? 0.5,
    y: player?.y ?? 64.0,
    z: player?.z ?? 0.5,
    viewDistance: config.defaultViewDistance ?? 48,
    refreshIntervalSeconds: config.defaultRefreshIntervalSeconds ?? 5,
    lineSpacing: 0.28,
    display: { billboard: 'center', alignment: 'center', scale: 1, textShadow: false, backgroundColor: '#000000', backgroundOpacity: 0, textOpacity: 1, seeThrough: false, maxLineWidth: 0 },
    visibility: { mode: 'all', negate: false, conditions: [] },
    interaction: { enabled: false, width: 1, height: 1, cooldownSeconds: 0, conditions: { mode: 'all', negate: false, conditions: [] }, onInteract: [], onAttack: [] },
    lines: ['<color:white><bold>New hologram</bold></color>']
  };
  try {
    await api('/api/holograms/create', { method: 'POST', body: JSON.stringify({ id, definition }) });
    state.selectedHologram = id.toLowerCase();
    await loadHolograms();
  } catch (error) {
    notice(error.message, true);
  }
}

async function duplicateSelectedHologram() {
  const id = await promptAction('Duplicate Hologram', 'Choose an ID for the new hologram.', `${state.selectedHologram}_copy`, 'Hologram ID');
  if (!id) return;
  await runHologramOperation('duplicate', { originalId: state.selectedHologram, id }, id.toLowerCase());
}

async function renameSelectedHologram() {
  const id = await promptAction('Rename Hologram', 'References to the old ID may need to be updated separately.', state.selectedHologram, 'New hologram ID');
  if (!id || id === state.selectedHologram) return;
  await runHologramOperation('rename', { originalId: state.selectedHologram, id }, id.toLowerCase());
}

async function deleteSelectedHologram() {
  if (!await confirmAction(`Delete hologram ${state.selectedHologram}?`, true)) return;
  await runHologramOperation('delete', { id: state.selectedHologram }, null);
}

async function runHologramOperation(action, body, nextSelection = state.selectedHologram) {
  try {
    await api(`/api/holograms/${action}`, { method: 'POST', body: JSON.stringify(body) });
    state.selectedHologram = nextSelection;
    notice(`Hologram ${action} completed.`);
    await loadHolograms();
  } catch (error) {
    notice(error.message, true);
  }
}

async function saveHologramSettings() {
  await runHologramOperation('settings', {
    enabled: $('hologram-global-enabled').checked,
    defaultViewDistance: Number($('hologram-default-distance').value),
    defaultRefreshIntervalSeconds: Number($('hologram-default-refresh').value)
  });
}

async function refreshAllHolograms() {
  await runHologramOperation('refresh', { id: '' });
}

async function loadModeration() {
  try {
    const [recent, active] = await Promise.all([api('/api/moderation/recent'), api('/api/moderation/active')]);
    const people = new Map();
    [...(active.punishments || []), ...(active.jails || []), ...(recent.punishments || []), ...(recent.warnings || [])].forEach(record => {
      const id = record.uuid || record.name;
      if (id) people.set(id, { uuid: record.uuid || '', name: record.name || record.uuid });
    });
    $('moderation-results').innerHTML = people.size ? [...people.values()].slice(0, 50).map(person => `<button class="selection-item" data-moderation-person="${attr(person.uuid || person.name)}"><strong>${esc(person.name || person.uuid)}</strong><small>${esc(person.uuid)}</small></button>`).join('') : empty('Search for any player by name or UUID.');
    $('moderation-results').querySelectorAll('[data-moderation-person]').forEach(button => button.addEventListener('click', () => loadModerationPlayer(button.dataset.moderationPerson)));
  } catch (error) { renderError('moderation-results', error.message); }
}

async function loadModerationPlayer(player) {
  const target = player || $('moderation-search').value.trim();
  if (!target) return;
  try {
    const data = await api(`/api/moderation/player?uuidOrName=${encodeURIComponent(target)}`);
    const root = $('moderation-editor');
    root.className = 'detail-editor moderation-subject-editor';
    const identity = data.player || { uuid: target, name: target };
    state.moderationIdentity = identity;
    state.moderationPage = 1;
    const punishments = data.punishments || [];
    const active = punishments.filter(item => item.status === 'ACTIVE');
    root.innerHTML = `<div class="detail-header moderation-subject-header"><div><h2>${esc(identity.name || target)}</h2><span>${esc(identity.uuid || '')}</span></div></div>
      <section class="permission-section"><h2>Create Punishment</h2><div class="moderation-action-form"><label>Type<select id="moderation-action"><option value="warn">Warning</option><option value="mute">Mute</option><option value="ban">Ban</option><option value="ipban">IP ban</option><option value="jail">Jail</option></select></label><label>Scope<select id="moderation-scope"><option value="network">Network</option><option value="server">Current server</option></select></label><label class="moderation-duration-field">Duration<input id="moderation-duration" placeholder="Permanent"><small id="moderation-duration-help">Leave empty for a permanent punishment.</small></label><label>Reason<input id="moderation-reason" placeholder="Reason"></label><label class="moderation-ip-field hidden">Explicit IP<input id="moderation-ip" placeholder="Online player or literal IP"></label><button id="moderation-apply">Apply Punishment</button></div></section>
      <section class="permission-section"><h2>Active Punishments</h2><div id="moderation-active">${punishmentTable(active, true)}</div></section>
      <section class="permission-section"><div class="detail-header"><h2>Punishment History</h2><div class="compact-form"><label>Type<select id="moderation-filter-type"><option value="">All</option><option>BAN</option><option>IP_BAN</option><option>MUTE</option><option>WARN</option><option>JAIL</option></select></label><label>Status<select id="moderation-filter-status"><option value="">All</option><option>ACTIVE</option><option>EXPIRED</option><option>REVOKED</option></select></label><label>Scope<select id="moderation-filter-scope"><option value="">All</option><option>GLOBAL</option><option>SERVER</option></select></label><label>From<input id="moderation-filter-from" type="date"></label><label>To<input id="moderation-filter-to" type="date"></label></div></div><div id="moderation-history"></div><div id="moderation-pagination"></div></section>
      <section id="moderation-ban-screen" class="permission-section"></section>
      <section id="moderation-detail" class="permission-section hidden"></section>`;
    $('moderation-apply').addEventListener('click', () => applyModeration(target));
    wireModerationActionForm();
    root.querySelectorAll('[data-revoke-id]').forEach(button => button.addEventListener('click', () => applyModeration(target, 'revoke', button.dataset.revokeId)));
    const renderHistory = () => {
      const type = $('moderation-filter-type').value, status = $('moderation-filter-status').value, scope = $('moderation-filter-scope').value;
      const from = $('moderation-filter-from').value ? new Date(`${$('moderation-filter-from').value}T00:00:00`).getTime() : null;
      const to = $('moderation-filter-to').value ? new Date(`${$('moderation-filter-to').value}T23:59:59.999`).getTime() : null;
      const filtered = punishments.filter(item => (!type || item.type === type) && (!status || item.status === status) && (!scope || item.scope === scope)
        && (from == null || item.createdAtMs >= from) && (to == null || item.createdAtMs <= to));
      const size = 10;
      const maxPage = Math.max(1, Math.ceil(filtered.length / size));
      state.moderationPage = Math.min(state.moderationPage, maxPage);
      $('moderation-history').innerHTML = punishmentTable(filtered.slice((state.moderationPage - 1) * size, state.moderationPage * size), false);
      $('moderation-history').querySelectorAll('[data-punishment-detail]').forEach(button => button.addEventListener('click', () => loadPunishmentDetail(button.dataset.punishmentDetail)));
      renderPagination('moderation-pagination', state.moderationPage, filtered.length, size, page => { state.moderationPage = page; renderHistory(); });
    };
    ['moderation-filter-type','moderation-filter-status','moderation-filter-scope','moderation-filter-from','moderation-filter-to'].forEach(id => $(id).addEventListener('change', () => { state.moderationPage = 1; renderHistory(); }));
    renderHistory();
    renderModerationBanScreen();
  } catch (error) { notice(error.message, true); }
}

function renderModerationBanScreen() {
  const root = $('moderation-ban-screen');
  if (!root) return;
  const fields = fieldsFor(['moderation']);
  const linesField = fields.find(field => /banScreenLines$/.test(field.key));
  const settings = fields.filter(field => field !== linesField);
  root.innerHTML = `<h2>Login Rejection Screen</h2><p>Formatted ban screen used for Paradigm bans. IP values are never available as placeholders.</p>${settings.map(field => configRow(field, 'moderation')).join('')}${linesField ? `<div class="ban-screen-lines"><h3>Lines</h3>${listControl(linesField, listValue(linesField.key))}</div>${collapsiblePreview('moderation:ban-screen', 'multiline')}` : ''}`;
  wireConfigControls(root, 'moderation');
  root.querySelectorAll('[data-list-key]').forEach(input => input.addEventListener('input', updateBanScreenPreview));
  wirePreviewDisclosures(root, panel => renderMinecraftPreview(panel, listValue('moderation.banScreenLines'), {
    punishment_id: 'P-0123456789ABCDEF', punishment_type: 'BAN', player_name: 'Alex', player_uuid: '0000-0000', reason: 'Griefing', actor: 'Moderator', actor_uuid: '1111-1111', created_at: '11 Jul 2026 12:00', expires_at: 'Permanent', expiry: 'Permanent', remaining: 'Permanent', scope: 'network', server_name: 'Paradigm', server_id: 'survival', network_id: 'main', appeal_url: 'https://example.invalid/appeal/P-0123456789ABCDEF'
  }));
}

function updateBanScreenPreview() {
  if (!state.openPreviews.has('moderation:ban-screen')) return;
  renderMinecraftPreview(document.querySelector('[data-preview-panel="moderation:ban-screen"]'), listValue('moderation.banScreenLines'), { punishment_id: 'P-0123456789ABCDEF', reason: 'Griefing', actor: 'Moderator', expiry: 'Permanent', appeal_url: 'https://example.invalid/appeal/P-0123456789ABCDEF' });
}

function punishmentTable(records, active) {
  return dataTable(['ID','Type','Scope','Reason','Actor','Created','Expiry','Status','Action'], records.map(item => [
    item.punishmentId, item.type, item.scope === 'GLOBAL' ? 'Network' : item.serverId || 'Server', item.reason || '-', item.actorName || '-',
    formatTime(item.createdAtMs), item.expiresAtMs ? formatTime(item.expiresAtMs) : 'Permanent', item.status,
    trusted(active ? `<button data-revoke-id="${attr(item.punishmentId)}">Revoke</button>` : `<button data-punishment-detail="${attr(item.punishmentId)}">Details</button>`)
  ]));
}

async function loadPunishmentDetail(id) {
  try {
    const item = await api(`/api/moderation/punishment?id=${encodeURIComponent(id)}`);
    const root = $('moderation-detail');
    root.classList.remove('hidden');
    root.innerHTML = `<div class="detail-header"><div><h2>${esc(item.punishmentId)}</h2><span>${esc(item.type)} · ${esc(item.status)} · ${esc(item.scope)}</span></div><button id="moderation-detail-close">Close</button></div><dl class="punishment-detail-grid"><dt>Subject</dt><dd>${esc(item.name || item.uuid || item.ipSubject || '-')}</dd><dt>Reason</dt><dd>${esc(item.reason || '-')}</dd><dt>Created by</dt><dd>${esc(item.actorName || '-')} · ${esc(formatTime(item.createdAtMs))}</dd><dt>Expires</dt><dd>${item.expiresAtMs ? esc(formatTime(item.expiresAtMs)) : 'Permanent'}</dd><dt>Revoked by</dt><dd>${esc(item.revokedByName || '-')}</dd><dt>Revoke reason</dt><dd>${esc(item.revokeReason || '-')}</dd></dl>`;
    $('moderation-detail-close').addEventListener('click', () => root.classList.add('hidden'));
  } catch (error) { notice(error.message, true); }
}

async function applyModeration(target, forcedAction = '', punishmentId = '') {
  const requestedAction = forcedAction || $('moderation-action').value;
  const durationValue = $('moderation-duration')?.value.trim() || '';
  const action = moderationActionForDuration(requestedAction, durationValue);
  const usesDuration = ['tempmute','tempban','tempipban','jail'].includes(action);
  if (usesDuration && durationValue && !['permanent','perm'].includes(durationValue.toLowerCase()) && parseDurationInput(durationValue) <= 0) {
    return notice('Enter a valid duration such as 30m, 1d, or 1d12h.', true);
  }
  const destructive = ['ban','tempban','ipban','tempipban','jail','revoke'].includes(action);
  const durationDescription = action.startsWith('temp') ? ` for ${durationValue}` : action === 'jail' && durationValue && !['permanent','perm'].includes(durationValue.toLowerCase()) ? ` for ${durationValue}` : '';
  if (destructive && !await confirmAction(`Apply ${readableModerationAction(action)} to ${target}${durationDescription}?`, true)) return;
  try {
    const uuid = state.moderationIdentity?.uuid || (/^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(target) ? target : '');
    const player = state.moderationIdentity?.name || target;
    const body = { player, uuid, punishmentId, reason: $('moderation-reason')?.value || '', duration: usesDuration ? durationValue : '', ipAddress: $('moderation-ip')?.value || '', scope: $('moderation-scope')?.value || 'network', confirmed: true };
    const result = await api(`/api/moderation/${action}`, { method: 'POST', body: JSON.stringify(body) });
    notice(result.message || `Moderation action ${action} applied.`);
    await loadModerationPlayer(target);
  } catch (error) { notice(error.message, true); }
}

function moderationActionForDuration(action, durationValue) {
  const temporary = durationValue && !['permanent','perm'].includes(durationValue.toLowerCase());
  if (!temporary) return action;
  return ({ mute: 'tempmute', ban: 'tempban', ipban: 'tempipban' })[action] || action;
}

function readableModerationAction(action) {
  return ({ tempmute: 'a temporary mute', tempban: 'a temporary ban', tempipban: 'a temporary IP ban', ipban: 'an IP ban', ban: 'a permanent ban', jail: 'jail', revoke: 'this revocation' })[action] || action;
}

function wireModerationActionForm() {
  const action = $('moderation-action');
  const duration = $('moderation-duration');
  const durationField = document.querySelector('.moderation-duration-field');
  const ipField = document.querySelector('.moderation-ip-field');
  const update = () => {
    const warning = action.value === 'warn';
    durationField?.classList.toggle('hidden', warning);
    ipField?.classList.toggle('hidden', action.value !== 'ipban');
    if (duration) {
      duration.placeholder = action.value === 'jail' ? 'Permanent or 7d' : 'Permanent, 7d, 1d12h…';
      if (warning) duration.value = '';
    }
  };
  action?.addEventListener('change', update);
  update();
}

async function loadAudit() {
  try {
    const actor = encodeURIComponent($('audit-actor')?.value || '');
    const type = encodeURIComponent($('audit-type')?.value || '');
    const target = encodeURIComponent($('audit-target')?.value || '');
    const result = encodeURIComponent($('audit-result')?.value || '');
    const source = encodeURIComponent($('audit-source')?.value || '');
    const fromMs = $('audit-from')?.value ? new Date(`${$('audit-from').value}T00:00:00`).getTime() : '';
    const toMs = $('audit-to')?.value ? new Date(`${$('audit-to').value}T23:59:59`).getTime() : '';
    const data = await api(`/api/audit/recent?actor=${actor}&type=${type}&target=${target}&result=${result}&source=${source}&fromMs=${fromMs}&toMs=${toMs}&page=${state.auditPage}&pageSize=${state.pageSize}`);
    state.auditRows = data.entries || [];
    state.auditTotal = data.total ?? state.auditRows.length;
    renderAudit();
  } catch (error) { renderError('audit-table', error.message); }
}

function renderAudit() {
  const rows = state.auditRows;
  $('audit-table').innerHTML = dataTable(['Time','Actor','Source','Action','Result','Target','Details'], rows.map(entry => [formatTime(entry.timestampMs), entry.actorName || entry.actorUuid || '-', entry.source, entry.actionType, entry.result, entry.targetName || entry.targetUuid || '-', trusted(`<details><summary>${esc(entry.message || 'Details')}</summary><pre>${esc(JSON.stringify(entry.details || {}, null, 2))}</pre></details>`)]));
  renderPagination('audit-pagination', state.auditPage, state.auditTotal, state.pageSize, page => { state.auditPage = page; loadAudit(); });
}

function dataTable(headers, rows) {
  if (!rows.length) return empty('No records found.');
  return `<div class="data-surface"><table><thead><tr>${headers.map(header => `<th>${esc(header)}</th>`).join('')}</tr></thead><tbody>${rows.map(row => `<tr>${row.map(cell => `<td>${cell && typeof cell === 'object' && '__html' in cell ? cell.__html : esc(display(cell))}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
}

function trusted(value) { return { __html: value }; }

function renderPagination(id, page, total, pageSize, onPage) {
  const root = $(id);
  if (!root) return;
  const pages = Math.max(1, Math.ceil(total / pageSize));
  root.innerHTML = `<span>Page ${page} of ${pages} · ${total} items</span><button data-page-prev ${page <= 1 ? 'disabled' : ''}>Previous</button><button data-page-next ${page >= pages ? 'disabled' : ''}>Next</button>`;
  root.querySelector('[data-page-prev]').addEventListener('click', () => onPage(Math.max(1, page - 1)));
  root.querySelector('[data-page-next]').addEventListener('click', () => onPage(Math.min(pages, page + 1)));
}

function openDialog({ title, message, danger = false, prompt = false, value = '', label = 'Value', acceptLabel = 'Confirm' }) {
  return new Promise(resolve => {
    state.pendingConfirm = resolve;
    state.confirmReturnFocus = document.activeElement;
    state.confirmMode = prompt ? 'prompt' : 'confirm';
    $('confirm-title').textContent = title;
    $('confirm-message').textContent = message;
    $('confirm-prompt-wrap').classList.toggle('hidden', !prompt);
    $('confirm-prompt-label').textContent = label;
    $('confirm-prompt-input').value = value;
    $('confirm-accept').textContent = acceptLabel;
    $('confirm-accept').classList.toggle('danger', danger);
    $('confirm-modal').querySelector('.pd-modal').setAttribute('role', danger ? 'alertdialog' : 'dialog');
    $('confirm-modal').classList.remove('hidden');
    if (prompt) {
      $('confirm-prompt-input').focus();
      $('confirm-prompt-input').select();
    } else $('confirm-accept').focus();
  });
}

function confirmAction(message, danger = false) {
  return openDialog({ title: danger ? 'Confirm Important Action' : 'Confirm Action', message, danger });
}

function promptAction(title, message, value = '', label = 'Value') {
  return openDialog({ title, message, prompt: true, value, label, acceptLabel: 'Continue' });
}

function resolveConfirm(value) {
  $('confirm-modal').classList.add('hidden');
  const resolve = state.pendingConfirm;
  const returnFocus = state.confirmReturnFocus;
  const result = state.confirmMode === 'prompt' ? (value ? $('confirm-prompt-input').value.trim() || null : null) : Boolean(value);
  state.pendingConfirm = null;
  state.confirmReturnFocus = null;
  state.confirmMode = 'confirm';
  $('confirm-prompt-wrap').classList.add('hidden');
  if (resolve) resolve(result);
  if (returnFocus instanceof HTMLElement && returnFocus.isConnected) returnFocus.focus();
}

function trapDialogFocus(event) {
  if (event.key !== 'Tab' || $('confirm-modal').classList.contains('hidden')) return;
  const focusable = [...$('confirm-modal').querySelectorAll('button:not([disabled]), input:not([disabled])')].filter(element => element.getClientRects().length);
  if (!focusable.length) return;
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

function notice(message, bad = false, action = null, persistent = false) {
  const root = $('notice');
  root.className = `notice ${bad ? 'bad' : 'good'}`;
  root.innerHTML = `<span>${esc(message)}</span>${action ? '<button id="notice-action">Apply Reload</button>' : ''}`;
  root.classList.remove('hidden');
  if (action) $('notice-action').addEventListener('click', action);
  clearTimeout(notice.timer);
  if (!persistent) notice.timer = setTimeout(() => root.classList.add('hidden'), action ? 10000 : 4500);
}

function setMessage(id, message, bad = false) { const element = $(id); element.textContent = message; element.className = `message ${bad ? 'bad' : ''}`; }
function renderError(id, message) { const root = $(id); if (root) root.innerHTML = `<div class="notice-inline">${esc(message)}</div>`; }
function empty(message) { return `<div class="empty-state">${esc(message)}</div>`; }
function formatTime(value) { if (!value) return '-'; const date = new Date(value); return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString(); }
function relativeTime(value) { if (!value) return '-'; const difference = Number(value) - Date.now(); const absolute = Math.abs(difference); const unit = absolute >= 86400000 ? [86400000, 'day'] : absolute >= 3600000 ? [3600000, 'hour'] : absolute >= 60000 ? [60000, 'minute'] : [1000, 'second']; const amount = Math.max(1, Math.round(absolute / unit[0])); return difference > 0 ? `in ${amount} ${unit[1]}${amount === 1 ? '' : 's'}` : `${amount} ${unit[1]}${amount === 1 ? '' : 's'} ago`; }
function parseDurationInput(value) {
  const normalized = String(value || '').trim().toLowerCase();
  if (!normalized) return -1;
  const pattern = /(\d+)([smhdw])/g;
  const units = { s: 1000, m: 60000, h: 3600000, d: 86400000, w: 604800000 };
  let consumed = 0;
  let total = 0;
  for (const match of normalized.matchAll(pattern)) {
    if (match.index !== consumed || Number(match[1]) <= 0) return -1;
    total += Number(match[1]) * units[match[2]];
    if (!Number.isSafeInteger(total)) return -1;
    consumed = match.index + match[0].length;
  }
  return consumed === normalized.length && total > 0 ? total : -1;
}
function duration(value) { const seconds = Math.max(0, Math.floor(Number(value || 0) / 1000)); const days = Math.floor(seconds / 86400); const hours = Math.floor((seconds % 86400) / 3600); const minutes = Math.floor((seconds % 3600) / 60); return days ? `${days}d ${hours}h` : hours ? `${hours}h ${minutes}m` : `${minutes}m`; }
function debounce(fn, wait) { let timer; return (...args) => { clearTimeout(timer); timer = setTimeout(() => fn(...args), wait); }; }
function esc(value) { return String(value ?? '').replace(/[&<>'"]/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' })[character]); }
function attr(value) { return esc(value); }

function bindEvents() {
  $('login-btn').addEventListener('click', () => login(false));
  $('login-token').addEventListener('keydown', event => { if (event.key === 'Enter') login(false); });
  $('logout-btn').addEventListener('click', logout);
  $('reload-config').addEventListener('click', () => loadConfigSnapshot(true));
  $('theme-toggle').addEventListener('click', () => setTheme(document.documentElement.dataset.theme === 'dark' ? 'classic' : 'dark', true));
  $('nav-toggle').addEventListener('click', toggleNavigation);
  $('sidebar-collapse').addEventListener('click', () => {
    if (window.matchMedia('(max-width: 980px)').matches) setNavigationOpen(false);
    else setSidebarCollapsed(true, true);
  });
  $('nav-scrim').addEventListener('click', () => setNavigationOpen(false));
  $('nav-search').addEventListener('input', event => filterNavigation(event.target.value));
  document.querySelectorAll('[data-page-target]').forEach(button => button.addEventListener('click', () => requestNavigate(button.dataset.pageTarget)));
  document.querySelectorAll('[data-refresh]').forEach(button => button.addEventListener('click', () => loadPage(button.dataset.refresh)));
  document.querySelectorAll('.advanced-toggle').forEach(button => button.addEventListener('click', () => setAdvancedShown(!state.advanced, true)));
  document.querySelectorAll('.format-toolbar').forEach(toolbar => toolbar.querySelectorAll('[data-wrap]').forEach(button => button.addEventListener('click', () => applyFormat(toolbar.dataset.editorTarget === 'motd' ? 'motd-lines' : 'chat-fields', button.dataset.wrap))));
  $('save-changes').addEventListener('click', saveCurrentPage);
  $('discard-changes').addEventListener('click', discardCurrentPage);
  $('remote-server-select')?.addEventListener('change', event => selectRemoteServer(event.target.value));
  $('remote-scope-select')?.addEventListener('change', event => selectRemoteScope(event.target.value));
  $('remote-save-changes')?.addEventListener('click', saveRemoteSection);
  $('remote-adopt-server')?.addEventListener('click', () => adoptRemoteSection('SERVER'));
  $('remote-adopt-network')?.addEventListener('click', () => adoptRemoteSection('NETWORK'));
  $('storage-test-btn').addEventListener('click', testStorage);
  $('migration-dry-run-btn').addEventListener('click', migrationDryRun);
  $('motd-add-line').addEventListener('click', () => mutateMotd(values => values.push('')));
  $('motd-template-apply').addEventListener('click', applyMotdTemplate);
  $('custom-command-new').addEventListener('click', newCustomCommand);
  $('custom-command-reload').addEventListener('click', async () => { try { await api('/api/custom-commands/reload', { method: 'POST', body: '{}' }); notice('Custom command definitions reloaded.'); await loadCustomCommands(); } catch (error) { notice(error.message, true); } });
  $('custom-command-search').addEventListener('input', debounce(loadCustomCommands, 250));
  $('hologram-new').addEventListener('click', createHologram);
  $('hologram-refresh-all').addEventListener('click', refreshAllHolograms);
  $('command-search').addEventListener('input', renderConfiguration);
  $('cooldown-search').addEventListener('input', renderConfiguration);
  document.querySelectorAll('[data-permission-view]').forEach(button => button.addEventListener('click', () => { state.permissionView = button.dataset.permissionView; state.permissionPage = 1; state.selectedPermissionTarget = null; loadPermissions(); $('permission-editor').className = 'detail-editor empty-detail'; $('permission-editor').textContent = state.permissionView === 'nodes' ? 'Select a permission node.' : `Select a ${state.permissionView === 'groups' ? 'group' : 'user'}.`; }));
  $('permissions-search').addEventListener('input', debounce(() => { state.permissionPage = 1; loadPermissions(); }, 250));
  $('luckperms-migrate').addEventListener('click', runLuckPermsMigration);
  $('moderation-find').addEventListener('click', () => loadModerationPlayer($('moderation-search').value.trim()));
  $('moderation-search').addEventListener('keydown', event => { if (event.key === 'Enter') loadModerationPlayer(event.target.value.trim()); });
  ['audit-actor','audit-type','audit-target','audit-result','audit-source','audit-from','audit-to'].forEach(id => $(id).addEventListener('input', debounce(() => { state.auditPage = 1; loadAudit(); }, 250)));
  $('confirm-cancel').addEventListener('click', () => resolveConfirm(false));
  $('confirm-accept').addEventListener('click', () => resolveConfirm(true));
  $('confirm-prompt-input').addEventListener('keydown', event => { if (event.key === 'Enter') resolveConfirm(true); });
  $('confirm-modal').addEventListener('click', event => { if (event.target === $('confirm-modal')) resolveConfirm(false); });
  window.addEventListener('hashchange', async () => {
    const next = validPage(location.hash.slice(1)) ? location.hash.slice(1) : 'overview';
    if (next === state.page) return;
    const dirty = [...state.editPages.values()].some(value => value === state.page) || state.commandDirty;
    if (dirty && !await confirmAction('Leave this page and discard its unsaved changes?', true)) {
      history.replaceState({ page: state.page }, '', `#${state.page}`);
      return;
    }
    if (dirty) discardCurrentPage();
    state.commandDirty = false;
    navigate(next, false);
  });
  window.addEventListener('keydown', event => {
    trapDialogFocus(event);
    if (event.key === 'Escape') {
      if (!$('confirm-modal').classList.contains('hidden')) {
        resolveConfirm(false);
        return;
      }
      if (document.body.classList.contains('nav-open')) {
        setNavigationOpen(false);
        $('nav-toggle').focus();
        return;
      }
      if (document.activeElement === $('nav-search') && $('nav-search').value) {
        $('nav-search').value = '';
        filterNavigation('');
        return;
      }
    }
    if (!$('confirm-modal').classList.contains('hidden') && (event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 'k') {
      event.preventDefault();
      return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 'k') {
      event.preventDefault();
      if (window.matchMedia('(max-width: 980px)').matches) setNavigationOpen(true);
      else setSidebarCollapsed(false, true);
      $('nav-search').focus();
      $('nav-search').select();
    }
  });
  window.addEventListener('resize', () => {
    setNavigationOpen(false);
    if (!window.matchMedia('(max-width: 980px)').matches) setSidebarCollapsed(document.body.classList.contains('sidebar-collapsed'));
  });
  window.addEventListener('beforeunload', event => { if (state.edits.size || state.commandDirty) { event.preventDefault(); event.returnValue = ''; } });
}

bindEvents();
checkAuth();
