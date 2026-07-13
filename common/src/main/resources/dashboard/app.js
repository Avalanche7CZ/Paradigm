const state = {
  snapshot: null,
  edits: new Map(),
  editPages: new Map(),
  errors: new Map(),
  csrf: null,
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
  pendingConfirm: null
};

const $ = id => document.getElementById(id);
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
  customCommands: ['Custom Commands', 'Structured custom command definitions.'],
  commands: ['Command Settings', 'Built-in command availability.'],
  cooldowns: ['Cooldowns', 'Cooldown and warmup timing.'],
  dashboard: ['Dashboard', 'Local dashboard security and runtime settings.'],
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
      showApp(status.principal);
    } else showLogin();
  } catch (error) {
    showLogin();
    $('login-message').textContent = error.message;
  }
}

function showLogin() {
  $('login-panel').classList.remove('hidden');
  $('app-panel').classList.add('hidden');
  $('session-state').textContent = 'Not logged in';
}

function showApp(principal) {
  $('login-panel').classList.add('hidden');
  $('app-panel').classList.remove('hidden');
  $('session-state').textContent = principal?.name ? principal.name : 'Local Admin';
  const initial = validPage(location.hash.slice(1)) ? location.hash.slice(1) : 'overview';
  navigate(initial, false);
  loadConfigSnapshot();
}

async function login(fromUrl = false) {
  const token = $('login-token').value.trim();
  if (!token) return setMessage('login-message', 'Enter a one-time login code.', true);
  try {
    const data = await api('/api/auth/login', { method: 'POST', body: JSON.stringify({ token }) });
    state.csrf = data.csrfToken || null;
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

function navigate(page, updateHash = true) {
  if (!validPage(page)) page = 'overview';
  state.page = page;
  document.querySelectorAll('[data-page-target]').forEach(button => button.classList.toggle('active', button.dataset.pageTarget === page));
  document.querySelectorAll('.page').forEach(section => section.classList.toggle('active', section.dataset.page === page));
  $('page-title').textContent = pageInfo[page][0];
  $('page-subtitle').textContent = pageInfo[page][1];
  if (updateHash && location.hash !== `#${page}`) history.pushState({ page }, '', `#${page}`);
  document.body.classList.remove('nav-open');
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
  if (page === 'overview') await loadOverview();
  if (page === 'servers') await loadServers();
  if (page === 'storage') await loadStorage();
  if (page === 'storageConfig') await loadStorageConfiguration();
  if (page === 'permissions') await loadPermissions();
  if (page === 'customCommands') await loadCustomCommands();
  if (page === 'moderation') await loadModeration();
  if (page === 'audit') await loadAudit();
  if (state.snapshot) renderConfiguration();
}

async function loadConfigSnapshot(force = false) {
  if (state.edits.size && force && !window.confirm('Discard unsaved dashboard changes and reload from disk?')) return;
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
  return [];
}

function renderConfiguration() {
  if (!state.snapshot) return;
  renderConfigContainer('general-fields', fieldsFor(['modules', 'command_groups', 'admin_utilities']), 'general');
  renderConfigContainer('teleport-fields', fieldsFor(['teleports']), 'teleports');
  renderChat();
  renderAnnouncements();
  renderRestart();
  renderMotd();
  if (state.page === 'moderation' && $('moderation-ban-screen')) renderModerationBanScreen();
  renderConfigContainer('command-fields', filterByInput(fieldsFor(['commands']), 'command-search'), 'commands');
  renderConfigContainer('cooldown-fields', filterByInput(fieldsFor(['cooldowns']), 'cooldown-search'), 'cooldowns');
  renderConfigContainer('dashboard-fields', fieldsFor(['dashboard']), 'dashboard');
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
  wireConfigControls(root, page);
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
  const value = state.edits.has(field.key) ? state.edits.get(field.key) : clone(field.value?.value);
  const dirty = state.edits.has(field.key);
  const error = state.errors.get(field.key);
  const control = configControl(field, value, options);
  const reload = field.reloadBehavior === 'RESTART_REQUIRED' ? 'Server restart required' : field.reloadBehavior === 'RELOAD_REQUIRED' ? 'Apply reload after saving' : '';
  return `<div class="config-row ${dirty ? 'is-dirty' : ''} ${error ? 'has-error' : ''}" data-field-row="${attr(field.key)}">
    <div class="config-label"><strong>${esc(humanLabel(field))}</strong><small>${esc(field.help || '')}</small>${reload ? `<span class="reload-note">${esc(reload)}</span>` : ''}<span class="advanced-detail">${esc(field.key)} · ${esc(field.owner || '')} · ${esc(field.type)} · default ${esc(display(field.defaultValue?.value))}</span>${error ? `<div class="field-error">${esc(error)}</div>` : ''}</div>
    <div class="config-control"><div class="config-control-line">${control}${field.editable ? `<button type="button" data-reset-field="${attr(field.key)}" title="Reset to default">Reset</button>` : ''}</div></div>
  </div>`;
}

function humanLabel(field) {
  if (field.label && !/^[a-z][A-Za-z0-9]+$/.test(field.label)) return field.label;
  const raw = field.label || field.key.split('.').pop();
  return raw.replace(/([a-z])([A-Z])/g, '$1 $2').replaceAll('_', ' ').replace(/\b\w/g, c => c.toUpperCase());
}

function configControl(field, value, options = {}) {
  if (!field.editable || field.type === 'READ_ONLY_TEXT') return `<div class="readonly-value">${esc(display(value))}</div>`;
  if (field.type === 'SECRET_MASKED') return `<div class="readonly-value">${field.value?.set ? 'Configured' : 'Not configured'}</div>`;
  if (field.type === 'BOOLEAN') return `<label class="switch"><input data-config-key="${attr(field.key)}" data-config-type="BOOLEAN" type="checkbox" ${value ? 'checked' : ''}><span></span></label>`;
  if (field.type === 'ENUM') return `<select data-config-key="${attr(field.key)}" data-config-type="ENUM">${(field.options || []).map(option => `<option ${option === value ? 'selected' : ''}>${esc(option)}</option>`).join('')}</select>`;
  if (field.type === 'INTEGER' || field.type === 'DOUBLE' || field.type === 'DURATION') return `<input data-config-key="${attr(field.key)}" data-config-type="${attr(field.type)}" type="number" min="${attr(field.min ?? '')}" max="${attr(field.max ?? '')}" step="${attr(field.step ?? (field.type === 'DOUBLE' ? 0.1 : 1))}" value="${attr(value ?? '')}">`;
  if (field.type === 'STRING_LIST') return listControl(field, Array.isArray(value) ? value : []);
  const large = options.largeStrings || field.multiline || String(value || '').length > 70;
  return large ? `<textarea data-config-key="${attr(field.key)}" data-config-type="STRING">${esc(value ?? '')}</textarea>` : `<input data-config-key="${attr(field.key)}" data-config-type="STRING" value="${attr(value ?? '')}">`;
}

function listControl(field, values) {
  const rows = values.map((value, index) => `<div class="reorder-row" draggable="true" data-drag-key="${attr(field.key)}" data-drag-index="${index}"><span class="reorder-handle" title="Drag to reorder">::</span><textarea class="reorder-editor" rows="1" data-list-key="${attr(field.key)}" data-list-index="${index}">${esc(value)}</textarea><div class="reorder-actions"><button data-list-move="up" data-key="${attr(field.key)}" data-index="${index}" title="Move up">&#8593;</button><button data-list-move="down" data-key="${attr(field.key)}" data-index="${index}" title="Move down">&#8595;</button><button data-list-duplicate data-key="${attr(field.key)}" data-index="${index}" title="Duplicate">+</button><button data-list-remove data-key="${attr(field.key)}" data-index="${index}" title="Delete">&#215;</button></div></div>`).join('');
  return `<div class="reorder-list" data-list-control="${attr(field.key)}">${rows || '<div class="reorder-empty">No messages configured.</div>'}<button class="reorder-add" data-list-add data-key="${attr(field.key)}">Add Item</button></div>`;
}

function wireConfigControls(root, page) {
  root.querySelectorAll('[data-config-key]').forEach(input => input.addEventListener('input', () => {
    setEdit(input.dataset.configKey, readInput(input, input.dataset.configType), page, false);
    input.closest('.config-row')?.classList.add('is-dirty');
  }));
  root.querySelectorAll('[data-reset-field]').forEach(button => button.addEventListener('click', () => resetField(button.dataset.resetField, page)));
  root.querySelectorAll('[data-list-key]').forEach(input => input.addEventListener('input', () => {
    const values = listValue(input.dataset.listKey);
    values[Number(input.dataset.listIndex)] = input.value;
    setEdit(input.dataset.listKey, values, page, false);
  }));
  root.querySelectorAll('[data-list-add]').forEach(button => button.addEventListener('click', () => mutateList(button.dataset.key, page, values => values.push(''))));
  root.querySelectorAll('[data-list-remove]').forEach(button => button.addEventListener('click', () => mutateList(button.dataset.key, page, values => values.splice(Number(button.dataset.index), 1))));
  root.querySelectorAll('[data-list-duplicate]').forEach(button => button.addEventListener('click', () => mutateList(button.dataset.key, page, values => values.splice(Number(button.dataset.index) + 1, 0, values[Number(button.dataset.index)]))));
  root.querySelectorAll('[data-list-move]').forEach(button => button.addEventListener('click', () => mutateList(button.dataset.key, page, values => move(values, Number(button.dataset.index), button.dataset.listMove === 'up' ? -1 : 1))));
  wireDragRows(root, '[data-drag-key]', row => row.dataset.dragKey, row => Number(row.dataset.dragIndex), (key, from, to) => mutateList(key, page, values => moveTo(values, from, to)));
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
  return `<div class="preview-disclosure ${open ? 'is-open' : ''}" data-preview-disclosure="${attr(key)}"><button type="button" class="preview-toggle" data-preview-toggle="${attr(key)}" aria-expanded="${open}">${open ? '&#9660;' : '&#9654;'} Preview</button><div class="minecraft-preview ${classes} ${open ? '' : 'hidden'}" data-preview-panel="${attr(key)}"></div></div>`;
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
    disclosure.querySelector('[data-preview-panel]').classList.toggle('hidden', !open);
    if (open) renderer(disclosure.querySelector('[data-preview-panel]'), key);
  }));
  root.querySelectorAll('.preview-disclosure.is-open [data-preview-panel]').forEach(panel => renderer(panel, panel.dataset.previewPanel));
}

function setEdit(key, value, page, rerender = true) {
  state.edits.set(key, clone(value));
  state.editPages.set(key, page);
  state.errors.delete(key);
  if (rerender) renderConfiguration(); else {
    updateSaveBar();
  }
}

function resetField(key, page) {
  const field = findField(key);
  if (!field) return;
  setEdit(key, clone(field.defaultValue?.value), page);
}

function mutateList(key, page, mutation) {
  const values = listValue(key);
  mutation(values);
  setEdit(key, values, page);
}

function listValue(key) {
  const field = findField(key);
  return clone(state.edits.has(key) ? state.edits.get(key) : (field?.value?.value || []));
}

function findField(key) { return state.snapshot?.fields?.find(field => field.key === key); }
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
  $('unsaved-count').textContent = count;
  $('save-bar').classList.toggle('hidden', count === 0);
  $('save-changes').disabled = count === 0;
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

function renderChat() {
  const fields = fieldsFor(['chat']);
  const root = $('chat-fields');
  const featureFields = fields.filter(field => !isFormattedChatField(field));
  const formattedFields = fields.filter(isFormattedChatField);
  root.innerHTML = `${featureFields.length ? `<section class="config-section"><h2>Features</h2>${featureFields.map(field => configRow(field, 'chat')).join('')}</section>` : ''}<section class="config-section formatted-fields-section"><h2>Formatting and Messages</h2>${formattedFields.map(chatFormatRow).join('')}</section>`;
  wireConfigControls(root, 'chat');
  wireFormattingEditors(root, 'chat', renderChatFieldPreview);
  wirePreviewDisclosures(root, renderChatFieldPreview);
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
  const samples = { player: 'Alex', player_name: 'Alex', player_uuid: '0000-0000', player_level: '12', player_health: '20', max_player_health: '20', player_prefix: '[Member] ', player_suffix: '', player_group: 'member', player_groups: 'member', prefix: '[Member] ', suffix: '', group: 'member', message: 'Hello world' };
  renderMinecraftPreview(panel, value || field?.label || '', samples);
}

function formattingToolbar(key, placeholders = []) {
  return `<div class="format-toolbar compact-format-toolbar" data-format-for="${attr(key)}"><button type="button" data-format-tag="bold" title="Bold"><strong>B</strong></button><button type="button" data-format-tag="italic" title="Italic"><em>I</em></button><button type="button" data-format-tag="underline" title="Underline"><u>U</u></button><button type="button" data-format-tag="strikethrough" title="Strikethrough"><s>S</s></button><button type="button" data-format-tag="color:#55FFFF">Color</button><button type="button" data-format-tag="gradient:#22D3EE:#A78BFA">Gradient</button><button type="button" data-format-tag="rainbow">Rainbow</button>${placeholders.length ? `<select data-placeholder-for="${attr(key)}"><option value="">Insert placeholder</option>${placeholders.map(value => `<option value="${attr(value)}">${esc(value)}</option>`).join('')}</select>` : ''}</div>`;
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
  renderConfigContainer('announcement-settings', fields.filter(field => !messageFields.includes(field) && field !== bossbarColor), 'announcements', { groupBy: field => /Interval|Time/.test(field.key) ? 'Timing' : /Enable/.test(field.key) ? 'Channels' : 'Presentation' });
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
  renderConfigContainer('restart-other-settings', otherFields, 'restart', { groupBy: field => /Message|Reason/.test(field.key) ? 'Messages' : /Enabled|UseChat/.test(field.key) ? 'Warning Channels' : 'Presentation' });
  renderRestartActions(warningFields);
  updateRestartSummary();
}

function restartScheduleEditor() {
  const mode = String(valueOf('restart.restartType') || 'Fixed');
  const fixed = mode.toLowerCase() === 'fixed';
  const realtime = mode.toLowerCase() === 'realtime';
  const duration = hoursToDuration(valueOf('restart.restartInterval'));
  const times = listValue('restart.realTimeInterval');
  return `<section class="config-section restart-schedule-section"><h2>Schedule</h2><div class="restart-mode-row"><label>Restart mode<select id="restart-mode"><option value="Fixed" ${fixed ? 'selected' : ''}>Fixed interval</option><option value="Realtime" ${realtime ? 'selected' : ''}>Real time</option><option value="None" ${!fixed && !realtime ? 'selected' : ''}>Disabled</option></select></label></div><div id="restart-fixed-schedule" class="mode-schedule ${fixed ? '' : 'hidden'}"><h3>Fixed interval</h3><div class="duration-editor"><span>Restart every</span><input id="restart-fixed-value" type="number" min="0.01" step="0.25" value="${attr(duration.value)}"><select id="restart-fixed-unit"><option value="seconds" ${duration.unit === 'seconds' ? 'selected' : ''}>seconds</option><option value="minutes" ${duration.unit === 'minutes' ? 'selected' : ''}>minutes</option><option value="hours" ${duration.unit === 'hours' ? 'selected' : ''}>hours</option><option value="days" ${duration.unit === 'days' ? 'selected' : ''}>days</option></select></div><p class="schedule-summary" id="restart-fixed-summary"></p></div><div id="restart-realtime-schedule" class="mode-schedule ${realtime ? '' : 'hidden'}"><h3>Real-time restart times</h3><div class="reorder-list">${times.map((time, index) => `<div class="reorder-row realtime-row" draggable="true" data-realtime-drag="${index}"><span class="reorder-handle">::</span><label>Restart time<input type="time" data-realtime-index="${index}" value="${attr(time)}"></label><div class="reorder-actions"><button data-realtime-move="up" data-index="${index}">&#8593;</button><button data-realtime-move="down" data-index="${index}">&#8595;</button><button data-realtime-remove data-index="${index}">&#215;</button></div></div>`).join('') || '<div class="reorder-empty">No real-time restart times configured.</div>'}<button id="restart-realtime-add" class="reorder-add">Add Time</button></div></div><div id="restart-disabled-schedule" class="mode-schedule ${!fixed && !realtime ? '' : 'hidden'}"><p>Automatic restart scheduling is disabled. Fixed and real-time settings remain stored.</p></div></section>`;
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
  const rows = fields.map(field => {
    const value = valueOf(field.key);
    const focused = /\.line[12]$|hoverText$/.test(field.key);
    const control = focused && field.editable ? `<div class="motd-format-layout"><div class="motd-format-editor">${formattingToolbar(field.key)}<textarea class="format-editor auto-grow" rows="${/hoverText$/.test(field.key) ? 4 : 2}" data-config-key="${attr(field.key)}" data-config-type="STRING">${esc(value || '')}</textarea></div>${collapsiblePreview(`motd:${field.key}`, 'compact-preview')}</div>` : configControl(field, value);
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
    let token = previewTag(match[0]);
    if (token && !token.close && !token.standalone && !text.slice(match.index + match[0].length).toLowerCase().includes(`</${token.name}>`)) token = null;
    if (!token) appendLegacyPreviewText(stack.at(-1).node, match[0]);
    else if (token.close) {
      if (stack.length > 1 && stack.at(-1).name === token.name) stack.pop();
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
    const resolved = PREVIEW_COLORS[color] || (/^#[0-9a-f]{6}$/i.test(color) ? color : null);
    if (!resolved) return null;
    element.style.color = resolved;
  } else if (PREVIEW_COLORS[name] || /^#[0-9a-f]{6}$/i.test(name)) {
    element.style.color = PREVIEW_COLORS[name] || name;
  } else if (name === 'gradient') {
    const colors = value.split(':').slice(1).filter(color => /^#[0-9a-f]{6}$/i.test(color));
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
    const rows = (data.servers || []).map(server => [server.serverName || server.serverId, server.networkId, server.loader || '-', server.local ? 'Local editable' : server.state || 'Observed', server.onlinePlayers ?? '-', relativeTime(server.lastHeartbeatMs)]);
    $('servers-table').innerHTML = dataTable(['Server', 'Network', 'Loader', 'State', 'Players', 'Last heartbeat'], rows);
  } catch (error) { renderError('servers-table', error.message); }
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
  root.className = 'detail-editor';
  root.innerHTML = `<div class="detail-header"><div><h2>${state.commandIsNew ? 'Create Command' : `/${esc(command.name)}`}</h2><span>${state.commandIsNew ? 'New definition' : 'Loaded from a restricted command file'}</span></div><div class="detail-header-actions">${state.commandIsNew ? '' : '<button id="command-duplicate">Duplicate</button><button id="command-delete" class="danger">Delete</button>'}<button id="command-save">${state.commandIsNew ? 'Create' : 'Save'}</button></div></div>
    <section class="config-section"><h2>Command</h2>
      ${commandField('Name', 'name', command.name, 'text', 'Lowercase command root without /')}
      ${commandField('Description', 'description', command.description || '', 'textarea', 'Shown in command help.')}
      ${commandField('Permission', 'permission', command.permission || '', 'text', 'Existing Paradigm permission syntax.')}
      ${commandField('Require Permission', 'requirePermission', !!command.requirePermission, 'checkbox', 'Require the permission node above.')}
      ${commandField('Permission Denied Message', 'permissionErrorMessage', command.permissionErrorMessage || '', 'textarea', 'Formatting tags are supported.')}
      ${commandField('Cooldown Seconds', 'cooldown_seconds', command.cooldown_seconds ?? 0, 'number', 'Zero disables the cooldown.')}
      ${commandField('Cooldown Message', 'cooldown_message', command.cooldown_message || '', 'textarea', 'Use {remaining_time}.')}
    </section>
    <section class="config-section"><h2>Arguments</h2><div id="command-arguments">${renderCommandArguments(command.arguments || [])}</div><button id="command-add-argument">Add Argument</button></section>
    <section class="config-section"><h2>Actions</h2><div id="command-actions">${renderActions(command.actions || [], 'actions')}</div><button data-add-action-path="actions">Add Action</button></section>
    <section class="config-section"><h2>Area Restriction</h2><label class="checkbox-line"><input id="command-area-enabled" type="checkbox" ${command.area_restriction ? 'checked' : ''}> Restrict this command to an area</label><div id="command-area">${renderArea(command.area_restriction)}</div></section>
    <details><summary>Advanced JSON preview</summary><textarea class="command-json" readonly>${esc(JSON.stringify(command, null, 2))}</textarea></details>`;
  wireCustomCommandEditor(root);
}

function commandField(label, key, value, type, help) {
  let control;
  if (type === 'checkbox') control = `<label class="switch"><input data-command-field="${attr(key)}" type="checkbox" ${value ? 'checked' : ''}><span></span></label>`;
  else if (type === 'textarea') control = `<textarea data-command-field="${attr(key)}">${esc(value)}</textarea>`;
  else control = `<input data-command-field="${attr(key)}" type="${type}" value="${attr(value)}">`;
  return `<div class="config-row"><div class="config-label"><strong>${esc(label)}</strong><small>${esc(help)}</small></div><div class="config-control">${control}</div></div>`;
}

function renderCommandArguments(argumentsList) {
  return argumentsList.map((argument, index) => `<div class="command-action" data-argument-index="${index}"><div class="command-action-grid"><label>Name<input data-argument-field="name" value="${attr(argument.name || '')}"></label><label>Type<select data-argument-field="type">${['string','integer','boolean','player','world','gamemode','custom'].map(type => `<option ${type === argument.type ? 'selected' : ''}>${type}</option>`).join('')}</select></label><label>Required<input data-argument-field="required" type="checkbox" ${argument.required ? 'checked' : ''}></label><label>Error message<input data-argument-field="errorMessage" value="${attr(argument.errorMessage || '')}"></label></div><div class="button-row"><button data-argument-move="up">Move Up</button><button data-argument-move="down">Move Down</button><button data-argument-duplicate>Duplicate</button><button data-argument-remove class="danger">Delete</button></div></div>`).join('');
}

function renderActions(actions, path) {
  return actions.map((action, index) => {
    const actionPath = `${path}.${index}`;
    const type = action.type || 'message';
    let fields = '';
    if (type === 'message') fields = `<label>Message lines<textarea data-action-field="text">${esc((action.text || []).join('\n'))}</textarea></label>`;
    else if (type === 'teleport') fields = `<div class="compact-form"><label>X<input data-action-field="x" type="number" value="${attr(action.x ?? 0)}"></label><label>Y<input data-action-field="y" type="number" value="${attr(action.y ?? 64)}"></label><label>Z<input data-action-field="z" type="number" value="${attr(action.z ?? 0)}"></label></div>`;
    else if (type === 'conditional') fields = `<h3>Conditions</h3><div data-condition-list>${renderConditions(action.conditions || [])}</div><button data-add-condition>Add Condition</button><h3>On Success</h3><div>${renderActions(action.on_success || [], `${actionPath}.on_success`)}</div><button data-add-action-path="${actionPath}.on_success">Add Success Action</button><h3>On Failure</h3><div>${renderActions(action.on_failure || [], `${actionPath}.on_failure`)}</div><button data-add-action-path="${actionPath}.on_failure">Add Failure Action</button>`;
    else fields = `<label>Commands, one per line<textarea data-action-field="commands">${esc((action.commands || []).join('\n'))}</textarea></label>`;
    return `<div class="command-action" data-action-path="${attr(actionPath)}"><div class="detail-header"><label>Action type<select data-action-field="type">${['message','teleport','run_command','run_console','conditional'].map(option => `<option ${option === type ? 'selected' : ''}>${option}</option>`).join('')}</select></label><div class="detail-header-actions"><button data-action-move="up">&#8593;</button><button data-action-move="down">&#8595;</button><button data-action-duplicate>Duplicate</button><button data-action-remove class="danger">Delete</button></div></div>${fields}</div>`;
  }).join('');
}

function renderConditions(conditions) {
  return conditions.map((condition, index) => `<div class="compact-form" data-condition-index="${index}"><label>Type<select data-condition-field="type">${['has_permission','has_item','health_above','health_below','is_op'].map(type => `<option ${type === condition.type ? 'selected' : ''}>${type}</option>`).join('')}</select></label><label>Value<input data-condition-field="value" value="${attr(condition.value || '')}"></label><label>Item amount<input data-condition-field="item_amount" type="number" min="1" value="${attr(condition.item_amount || 1)}"></label><label>Negate<input data-condition-field="negate" type="checkbox" ${condition.negate ? 'checked' : ''}></label><button data-condition-remove>Delete</button></div>`).join('');
}

function renderArea(area) {
  if (!area) return '';
  return `<div class="compact-form"><label>World<input data-area-field="world" value="${attr(area.world || '')}"></label><label>Corner 1<input data-area-field="corner1" value="${attr((area.corner1 || []).join(', '))}"></label><label>Corner 2<input data-area-field="corner2" value="${attr((area.corner2 || []).join(', '))}"></label></div><label>Restriction message<textarea data-area-field="restriction_message">${esc(area.restriction_message || '')}</textarea></label>`;
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
    if (key === 'type') normalizeAction(action);
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
  const requested = window.prompt('Name for the duplicate command:', `${state.selectedCommand}_copy`);
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
        : api(`/api/permissions/nodes?query=${query}&page=${page}&pageSize=${state.pageSize}`);
    const [summary, data] = await Promise.all([summaryPromise, dataPromise]);
    state.permissionData.summary = summary;
    state.permissionData[state.permissionView] = data[state.permissionView] || [];
    if (state.permissionView === 'nodes') $('known-permission-nodes').innerHTML = state.permissionData.nodes.map(node => `<option value="${attr(node.node)}"></option>`).join('');
    state.permissionData.total = data.total ?? state.permissionData[state.permissionView].length;
    $('permissions-summary').textContent = `${summary.groups} groups · ${summary.users} permission subjects · ${summary.nodes} nodes`;
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
  return renderNodeEditor(state.permissionData.nodes.find(node => node.node === selected.id));
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
  $('user-group-add').addEventListener('click', () => permissionMutation('user_group_add', { user: user.uuid, group: $('user-group-select').value, ...readContextExpiry('user-group') }));
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
  return `<div class="compact-form">${fixedNode ? '' : `<label>Permission node<input id="${prefix}-permission-node" list="known-permission-nodes" placeholder="paradigm.fly"></label>`}<label>Value<select id="${prefix}-permission-value"><option value="false">Allow</option><option value="true">Deny</option></select></label>${contextExpiryForm(prefix)}<button id="${prefix}-permission-add">Add Permission</button></div>`;
}

function contextExpiryForm(prefix) {
  return `<label>Scope<select id="${prefix}-scope"><option value="global">Global</option><option value="current_server">Current server</option><option value="current_network">Current network</option><option value="custom">Custom context</option></select></label><label>Context key<input id="${prefix}-context-key" placeholder="world"></label><label>Context value<input id="${prefix}-context-value" placeholder="minecraft:overworld"></label><label>Expiry<select id="${prefix}-expiry-mode"><option value="permanent">Permanent</option><option value="duration">Duration</option><option value="exact">Exact date/time</option></select></label><label>Duration<input id="${prefix}-duration" placeholder="7d"></label><label>Exact expiry<input id="${prefix}-exact" type="datetime-local"></label>`;
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
  $(`${prefix}-permission-add`)?.addEventListener('click', async () => {
    const node = fixedNode || $(`${prefix}-permission-node`).value.trim();
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
      user_group_add: 'user/group/add', user_group_remove: 'user/group/remove'
    })[action];
    const result = await api(`/api/permissions/${path}`, { method: 'POST', body: JSON.stringify(body) });
    notice(result.message || 'Permission change applied.');
    await loadPermissions();
  } catch (error) { notice(error.message, true); }
}

async function createPermissionGroup() {
  const name = window.prompt('New group name:');
  if (!name) return;
  await permissionMutation('group_create', { group: name, confirmed: true });
  state.selectedPermissionTarget = { kind: 'group', id: name };
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
      <section class="permission-section"><h2>Create Punishment</h2><div class="compact-form moderation-action-form"><label>Type<select id="moderation-action"><option value="warn">Warning</option><option value="mute">Mute</option><option value="tempmute">Temporary mute</option><option value="ban">Ban</option><option value="tempban">Temporary ban</option><option value="ipban">IP ban</option><option value="tempipban">Temporary IP ban</option><option value="jail">Jail</option></select></label><label>Scope<select id="moderation-scope"><option value="network">Network</option><option value="server">Current server</option></select></label><label>Duration<input id="moderation-duration" placeholder="Permanent or 7d"></label><label>Reason<input id="moderation-reason" placeholder="Reason"></label><label>Explicit IP<input id="moderation-ip" placeholder="Online player or literal IP"></label><button id="moderation-apply">Apply</button></div></section>
      <section class="permission-section"><h2>Active Punishments</h2><div id="moderation-active">${punishmentTable(active, true)}</div></section>
      <section class="permission-section"><div class="detail-header"><h2>Punishment History</h2><div class="compact-form"><label>Type<select id="moderation-filter-type"><option value="">All</option><option>BAN</option><option>IP_BAN</option><option>MUTE</option><option>WARN</option><option>JAIL</option></select></label><label>Status<select id="moderation-filter-status"><option value="">All</option><option>ACTIVE</option><option>EXPIRED</option><option>REVOKED</option></select></label><label>Scope<select id="moderation-filter-scope"><option value="">All</option><option>GLOBAL</option><option>SERVER</option></select></label><label>From<input id="moderation-filter-from" type="date"></label><label>To<input id="moderation-filter-to" type="date"></label></div></div><div id="moderation-history"></div><div id="moderation-pagination"></div></section>
      <section id="moderation-ban-screen" class="permission-section"></section>
      <section id="moderation-detail" class="permission-section hidden"></section>`;
    $('moderation-apply').addEventListener('click', () => applyModeration(target));
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
  const action = forcedAction || $('moderation-action').value;
  const destructive = ['ban','tempban','ipban','tempipban','jail','revoke'].includes(action);
  if (destructive && !await confirmAction(`Apply ${action} to ${target}?`, true)) return;
  try {
    const uuid = state.moderationIdentity?.uuid || (/^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(target) ? target : '');
    const player = state.moderationIdentity?.name || target;
    const body = { player, uuid, punishmentId, reason: $('moderation-reason')?.value || '', duration: $('moderation-duration')?.value || '', ipAddress: $('moderation-ip')?.value || '', scope: $('moderation-scope')?.value || 'network', confirmed: true };
    const result = await api(`/api/moderation/${action}`, { method: 'POST', body: JSON.stringify(body) });
    notice(result.message || `Moderation action ${action} applied.`);
    await loadModerationPlayer(target);
  } catch (error) { notice(error.message, true); }
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

function confirmAction(message, danger = false) {
  return new Promise(resolve => {
    state.pendingConfirm = resolve;
    $('confirm-title').textContent = danger ? 'Confirm Important Action' : 'Confirm Action';
    $('confirm-message').textContent = message;
    $('confirm-accept').classList.toggle('danger', danger);
    $('confirm-modal').classList.remove('hidden');
  });
}

function resolveConfirm(value) {
  $('confirm-modal').classList.add('hidden');
  const resolve = state.pendingConfirm;
  state.pendingConfirm = null;
  if (resolve) resolve(value);
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
function duration(value) { const seconds = Math.max(0, Math.floor(Number(value || 0) / 1000)); const days = Math.floor(seconds / 86400); const hours = Math.floor((seconds % 86400) / 3600); const minutes = Math.floor((seconds % 3600) / 60); return days ? `${days}d ${hours}h` : hours ? `${hours}h ${minutes}m` : `${minutes}m`; }
function debounce(fn, wait) { let timer; return (...args) => { clearTimeout(timer); timer = setTimeout(() => fn(...args), wait); }; }
function esc(value) { return String(value ?? '').replace(/[&<>'"]/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' })[character]); }
function attr(value) { return esc(value); }

function bindEvents() {
  $('login-btn').addEventListener('click', () => login(false));
  $('login-token').addEventListener('keydown', event => { if (event.key === 'Enter') login(false); });
  $('logout-btn').addEventListener('click', logout);
  $('reload-config').addEventListener('click', () => loadConfigSnapshot(true));
  $('nav-toggle').addEventListener('click', () => document.body.classList.toggle('nav-open'));
  document.querySelectorAll('[data-page-target]').forEach(button => button.addEventListener('click', () => requestNavigate(button.dataset.pageTarget)));
  document.querySelectorAll('[data-refresh]').forEach(button => button.addEventListener('click', () => loadPage(button.dataset.refresh)));
  document.querySelectorAll('.advanced-toggle').forEach(button => button.addEventListener('click', () => { state.advanced = !state.advanced; document.body.classList.toggle('show-advanced', state.advanced); button.textContent = state.advanced ? 'Hide advanced details' : 'Advanced details'; }));
  document.querySelectorAll('.format-toolbar').forEach(toolbar => toolbar.querySelectorAll('[data-wrap]').forEach(button => button.addEventListener('click', () => applyFormat(toolbar.dataset.editorTarget === 'motd' ? 'motd-lines' : 'chat-fields', button.dataset.wrap))));
  $('save-changes').addEventListener('click', saveCurrentPage);
  $('discard-changes').addEventListener('click', discardCurrentPage);
  $('storage-test-btn').addEventListener('click', testStorage);
  $('migration-dry-run-btn').addEventListener('click', migrationDryRun);
  $('motd-add-line').addEventListener('click', () => mutateMotd(values => values.push('')));
  $('motd-template-apply').addEventListener('click', applyMotdTemplate);
  $('custom-command-new').addEventListener('click', newCustomCommand);
  $('custom-command-reload').addEventListener('click', async () => { try { await api('/api/custom-commands/reload', { method: 'POST', body: '{}' }); notice('Custom command definitions reloaded.'); await loadCustomCommands(); } catch (error) { notice(error.message, true); } });
  $('custom-command-search').addEventListener('input', debounce(loadCustomCommands, 250));
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
  window.addEventListener('beforeunload', event => { if (state.edits.size || state.commandDirty) { event.preventDefault(); event.returnValue = ''; } });
}

bindEvents();
checkAuth();
