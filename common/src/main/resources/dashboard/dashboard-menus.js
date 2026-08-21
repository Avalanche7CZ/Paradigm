(() => {
  'use strict';

  const state = {
    snapshot: null,
    selectedId: null,
    draft: null,
    selectedSlot: null,
    dirty: false,
    rawMode: false
  };

  function esc(value) {
    return String(value ?? '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  function $(id) {
    return document.getElementById(id);
  }

  async function call(path, options) {
    return window.api(path, options || {});
  }

  function emptySlot(index) {
    return {
      slot: index,
      item: { itemId: 'minecraft:stone', amount: 1, name: '', lore: [], glint: false, customModelData: null, hideTooltip: false },
      visibleIf: [],
      actions: [],
      leftActions: [],
      rightActions: [],
      refresh: false
    };
  }

  function newDraft() {
    return {
      id: '',
      title: 'New Menu',
      rows: 3,
      permission: '',
      openConditions: [],
      slots: [],
      filler: null,
      fillEmpty: false,
      refreshSeconds: 0,
      onClose: []
    };
  }

  function markDirty() {
    state.dirty = true;
    const save = $('menu-save');
    if (save) save.disabled = false;
  }

  async function load() {
    try {
      state.snapshot = await call('/api/menus');
    } catch (error) {
      $('menu-list').innerHTML = `<div class="empty-detail">${esc(error.message)}</div>`;
      return;
    }
    renderProblems();
    renderList();
    if (state.selectedId) renderEditor();
  }

  function renderProblems() {
    const box = $('menu-problems');
    if (!box) return;
    const problems = (state.snapshot && state.snapshot.errors) || [];
    if (!problems.length) {
      box.classList.add('hidden');
      box.innerHTML = '';
      return;
    }
    box.classList.remove('hidden');
    box.innerHTML = `<h2>Definition problems (${problems.length})</h2><ul class="menu-problem-list">${
      problems.map(problem => `<li>${esc(problem)}</li>`).join('')}</ul>`;
  }

  function renderList() {
    const list = $('menu-list');
    if (!list) return;
    const menus = (state.snapshot && state.snapshot.menus) || [];
    if (!menus.length) {
      list.innerHTML = '<div class="empty-detail">No menus defined.</div>';
      return;
    }
    list.innerHTML = menus.map(menu => `
      <button class="selection-item${menu.id === state.selectedId ? ' active' : ''}" data-menu-id="${esc(menu.id)}">
        <strong>${esc(menu.id)}</strong>
        <span>${esc(menu.title)} · ${menu.rows}r · ${menu.slots} slots${menu.editable ? '' : ' · module'}</span>
      </button>`).join('');
    list.querySelectorAll('[data-menu-id]').forEach(button => {
      button.addEventListener('click', () => select(button.dataset.menuId));
    });
  }

  async function select(id) {
    if (state.dirty && !confirm('Discard unsaved menu changes?')) return;
    state.selectedId = id;
    state.selectedSlot = null;
    state.dirty = false;
    try {
      const data = await call(`/api/menus/item?id=${encodeURIComponent(id)}`);
      state.draft = data.definition;
      normalizeDraft();
    } catch (error) {
      state.draft = null;
      $('menu-editor').innerHTML = `<div class="empty-detail">${esc(error.message)}</div>`;
      return;
    }
    renderList();
    renderEditor();
  }

  function normalizeDraft() {
    const draft = state.draft;
    if (!draft) return;
    draft.slots = draft.slots || [];
    draft.openConditions = draft.openConditions || [];
    draft.onClose = draft.onClose || [];
    draft.slots.forEach(slot => {
      slot.item = slot.item || { itemId: 'minecraft:stone', amount: 1, name: '', lore: [], glint: false };
      slot.item.lore = slot.item.lore || [];
      slot.visibleIf = slot.visibleIf || [];
      slot.actions = slot.actions || [];
      slot.leftActions = slot.leftActions || [];
      slot.rightActions = slot.rightActions || [];
    });
  }

  function createNew() {
    if (state.dirty && !confirm('Discard unsaved menu changes?')) return;
    state.selectedId = null;
    state.selectedSlot = null;
    state.draft = newDraft();
    state.dirty = true;
    renderList();
    renderEditor();
  }

  function slotAt(index) {
    const slots = state.draft && Array.isArray(state.draft.slots) ? state.draft.slots : [];
    return slots.find(slot => slot.slot === index) || null;
  }

  function renderEditor() {
    const host = $('menu-editor');
    if (!host) return;
    const draft = state.draft;
    if (!draft) {
      host.classList.add('empty-detail');
      host.textContent = 'Select a menu or create one.';
      return;
    }
    host.classList.remove('empty-detail');

    const size = draft.rows * 9;
    const cells = [];
    for (let index = 0; index < size; index++) {
      const slot = slotAt(index);
      const selected = state.selectedSlot === index;
      const label = slot ? esc(slot.item.itemId.replace('minecraft:', '')) : '';
      const hasActions = slot && (slot.actions.length || slot.leftActions.length || slot.rightActions.length);
      cells.push(`<button type="button" class="menu-cell${slot ? ' filled' : ''}${selected ? ' selected' : ''}"
        data-slot="${index}" title="Slot ${index}${slot ? ' · ' + esc(slot.item.itemId) : ''}">
        <span class="menu-cell-index">${index}</span>
        <span class="menu-cell-item">${label}</span>
        ${hasActions ? '<span class="menu-cell-flag">A</span>' : ''}
        ${slot && slot.visibleIf.length ? '<span class="menu-cell-flag cond">C</span>' : ''}
      </button>`);
    }

    host.innerHTML = `
      <div class="menu-meta editor-section">
        <div class="menu-meta-grid">
          <label>ID<input id="menu-f-id" value="${esc(draft.id)}" ${state.selectedId ? 'readonly' : ''}></label>
          <label>Title<input id="menu-f-title" value="${esc(draft.title)}"></label>
          <label>Rows<select id="menu-f-rows">${[1, 2, 3, 4, 5, 6]
            .map(rows => `<option value="${rows}"${rows === draft.rows ? ' selected' : ''}>${rows}</option>`).join('')}</select></label>
          <label>Permission<input id="menu-f-permission" value="${esc(draft.permission)}" placeholder="none"></label>
          <label>Refresh (s)<input id="menu-f-refresh" type="number" min="0" max="3600" value="${draft.refreshSeconds || 0}"></label>
          <label class="menu-check"><input id="menu-f-fill" type="checkbox" ${draft.fillEmpty ? 'checked' : ''}> Fill empty slots</label>
        </div>
        <div class="menu-actions-bar">
          <button id="menu-save" type="button" ${state.dirty ? '' : 'disabled'}>Save</button>
          <button id="menu-duplicate" type="button" ${state.selectedId ? '' : 'disabled'}>Duplicate</button>
          <button id="menu-delete" type="button" class="danger" ${state.selectedId ? '' : 'disabled'}>Delete</button>
          <button id="menu-raw-toggle" type="button">${state.rawMode ? 'Grid editor' : 'Raw JSON'}</button>
        </div>
      </div>
      ${state.rawMode ? renderRaw() : `
      <div class="editor-section">
        <h2>Slot grid</h2>
        <div class="menu-grid" style="--menu-rows:${draft.rows}">${cells.join('')}</div>
      </div>
      <div id="menu-slot-editor" class="editor-section">${renderSlotEditor()}</div>`}
    `;

    bindEditor();
  }

  function renderRaw() {
    return `<div class="editor-section"><h2>Raw definition</h2>
      <textarea id="menu-raw" class="menu-raw" spellcheck="false">${esc(JSON.stringify(state.draft, null, 2))}</textarea>
      <p class="menu-hint">Saving applies this JSON. Validation errors are reported by the server.</p></div>`;
  }

  function renderSlotEditor() {
    const index = state.selectedSlot;
    if (index === null || index === undefined) {
      return '<h2>Slot</h2><p class="menu-hint">Select a slot in the grid to edit it.</p>';
    }
    const slot = slotAt(index);
    if (!slot) {
      return `<h2>Slot ${index}</h2><p class="menu-hint">This slot is empty.</p>
        <button id="menu-slot-add" type="button">Define slot ${index}</button>`;
    }
    return `
      <h2>Slot ${index}</h2>
      <div class="menu-slot-grid">
        <label>Item ID<input id="menu-s-item" value="${esc(slot.item.itemId)}"></label>
        <label>Amount<input id="menu-s-amount" type="number" min="1" max="64" value="${slot.item.amount || 1}"></label>
        <label>Display name<input id="menu-s-name" value="${esc(slot.item.name)}" placeholder="&aExample {player_name}"></label>
        <label>Custom model data<input id="menu-s-cmd" type="number" min="0" placeholder="none"
          value="${slot.item.customModelData ?? ''}"></label>
        <label class="menu-check"><input id="menu-s-glint" type="checkbox" ${slot.item.glint ? 'checked' : ''}> Enchant glint</label>
        <label class="menu-check"><input id="menu-s-hidetip" type="checkbox" ${slot.item.hideTooltip ? 'checked' : ''}> Hide tooltip</label>
        <label class="menu-check"><input id="menu-s-refresh" type="checkbox" ${slot.refresh ? 'checked' : ''}> Live refresh</label>
      </div>
      <label class="menu-block">Lore (one line per row)
        <textarea id="menu-s-lore" rows="4" spellcheck="false">${esc((slot.item.lore || []).join('\n'))}</textarea></label>
      <label class="menu-block">Visibility conditions (type value, one per line; prefix ! to negate)
        <textarea id="menu-s-conditions" rows="3" spellcheck="false">${esc(conditionsToText(slot.visibleIf))}</textarea></label>
      <label class="menu-block">Click actions (any click)
        <textarea id="menu-s-actions" rows="3" spellcheck="false">${esc(actionsToText(slot.actions))}</textarea></label>
      <label class="menu-block">Left-click actions
        <textarea id="menu-s-left" rows="2" spellcheck="false">${esc(actionsToText(slot.leftActions))}</textarea></label>
      <label class="menu-block">Right-click actions
        <textarea id="menu-s-right" rows="2" spellcheck="false">${esc(actionsToText(slot.rightActions))}</textarea></label>
      <p class="menu-hint">Action syntax: <code>open_menu &lt;id&gt;</code>, <code>close_menu</code>, <code>menu_back</code>,
        <code>message &lt;text&gt;</code>, <code>run_command &lt;cmd&gt;</code>, <code>run_console &lt;cmd&gt;</code>.
        Condition syntax: <code>has_permission node</code>, <code>is_op</code>, <code>in_world id</code>.</p>
      <button id="menu-slot-remove" type="button" class="danger">Clear slot ${index}</button>
    `;
  }

  function conditionsToText(conditions) {
    return (conditions || []).map(condition =>
      `${condition.negate ? '!' : ''}${condition.type}${condition.value ? ' ' + condition.value : ''}`).join('\n');
  }

  function textToConditions(text) {
    return String(text || '').split('\n').map(line => line.trim()).filter(Boolean).map(line => {
      const negate = line.startsWith('!');
      const body = negate ? line.slice(1).trim() : line;
      const space = body.indexOf(' ');
      return {
        type: space === -1 ? body : body.slice(0, space),
        value: space === -1 ? '' : body.slice(space + 1).trim(),
        negate
      };
    });
  }

  function actionsToText(actions) {
    return (actions || []).map(action => {
      if (action.type === 'open_menu') return `open_menu ${action.menu || ''}`.trim();
      if (action.type === 'message') return `message ${(action.text || []).join(' | ')}`.trim();
      if (action.type === 'run_command' || action.type === 'run_console') {
        return `${action.type} ${(action.commands || []).join(' | ')}`.trim();
      }
      return action.type || '';
    }).join('\n');
  }

  function textToActions(text) {
    return String(text || '').split('\n').map(line => line.trim()).filter(Boolean).map(line => {
      const space = line.indexOf(' ');
      const type = space === -1 ? line : line.slice(0, space);
      const rest = space === -1 ? '' : line.slice(space + 1).trim();
      if (type === 'open_menu') return { type, menu: rest };
      if (type === 'message') return { type, text: rest ? rest.split('|').map(part => part.trim()) : [] };
      if (type === 'run_command' || type === 'run_console') {
        return { type, commands: rest ? rest.split('|').map(part => part.trim()) : [] };
      }
      return { type };
    });
  }

  function bindEditor() {
    const draft = state.draft;
    if (!draft) return;

    const bind = (id, handler, event) => {
      const node = $(id);
      if (node) node.addEventListener(event || 'input', handler);
    };

    bind('menu-f-id', event => { draft.id = event.target.value; markDirty(); });
    bind('menu-f-title', event => { draft.title = event.target.value; markDirty(); });
    bind('menu-f-permission', event => { draft.permission = event.target.value; markDirty(); });
    bind('menu-f-refresh', event => { draft.refreshSeconds = Number(event.target.value) || 0; markDirty(); });
    bind('menu-f-rows', event => {
      draft.rows = Number(event.target.value);
      const size = draft.rows * 9;
      draft.slots = draft.slots.filter(slot => slot.slot < size);
      if (state.selectedSlot !== null && state.selectedSlot >= size) state.selectedSlot = null;
      markDirty();
      renderEditor();
    }, 'change');
    bind('menu-f-fill', event => { draft.fillEmpty = event.target.checked; markDirty(); }, 'change');

    document.querySelectorAll('#menu-editor [data-slot]').forEach(cell => {
      cell.addEventListener('click', () => {
        state.selectedSlot = Number(cell.dataset.slot);
        renderEditor();
      });
    });

    const addButton = $('menu-slot-add');
    if (addButton) addButton.addEventListener('click', () => {
      draft.slots.push(emptySlot(state.selectedSlot));
      markDirty();
      renderEditor();
    });

    const removeButton = $('menu-slot-remove');
    if (removeButton) removeButton.addEventListener('click', () => {
      draft.slots = draft.slots.filter(slot => slot.slot !== state.selectedSlot);
      markDirty();
      renderEditor();
    });

    const slot = state.selectedSlot === null ? null : slotAt(state.selectedSlot);
    if (slot) {
      bind('menu-s-item', event => { slot.item.itemId = event.target.value; markDirty(); });
      bind('menu-s-amount', event => { slot.item.amount = Number(event.target.value) || 1; markDirty(); });
      bind('menu-s-name', event => { slot.item.name = event.target.value; markDirty(); });
      bind('menu-s-glint', event => { slot.item.glint = event.target.checked; markDirty(); }, 'change');
      bind('menu-s-hidetip', event => { slot.item.hideTooltip = event.target.checked; markDirty(); }, 'change');
      bind('menu-s-cmd', event => {
        const raw = event.target.value.trim();
        slot.item.customModelData = raw === '' ? null : Number(raw);
        markDirty();
      });
      bind('menu-s-refresh', event => { slot.refresh = event.target.checked; markDirty(); }, 'change');
      bind('menu-s-lore', event => {
        const lines = event.target.value.split('\n');
        while (lines.length && lines[lines.length - 1] === '') lines.pop();
        slot.item.lore = lines;
        markDirty();
      });
      bind('menu-s-conditions', event => { slot.visibleIf = textToConditions(event.target.value); markDirty(); });
      bind('menu-s-actions', event => { slot.actions = textToActions(event.target.value); markDirty(); });
      bind('menu-s-left', event => { slot.leftActions = textToActions(event.target.value); markDirty(); });
      bind('menu-s-right', event => { slot.rightActions = textToActions(event.target.value); markDirty(); });
    }

    bind('menu-raw', event => {
      try {
        const parsed = JSON.parse(event.target.value);
        if (parsed && typeof parsed === 'object') {
          state.draft = parsed;
          normalizeDraft();
        }
        markDirty();
      } catch (ignored) {
        markDirty();
      }
    });

    const rawToggle = $('menu-raw-toggle');
    if (rawToggle) rawToggle.addEventListener('click', () => {
      state.rawMode = !state.rawMode;
      renderEditor();
    });

    const save = $('menu-save');
    if (save) save.addEventListener('click', saveDraft);

    const duplicate = $('menu-duplicate');
    if (duplicate) duplicate.addEventListener('click', duplicateMenu);

    const remove = $('menu-delete');
    if (remove) remove.addEventListener('click', deleteMenu);
  }

  async function saveDraft() {
    const draft = state.draft;
    if (!draft) return;
    const creating = !state.selectedId;
    try {
      const result = await call(`/api/menus/${creating ? 'create' : 'update'}`, {
        method: 'POST',
        body: JSON.stringify({ id: draft.id, definition: draft })
      });
      state.dirty = false;
      state.selectedId = draft.id;
      notify(result.problems && result.problems.length
        ? `Saved with ${result.problems.length} problem(s).`
        : 'Menu saved.');
      await load();
      await select(draft.id);
    } catch (error) {
      notify(error.message, true);
    }
  }

  async function duplicateMenu() {
    const target = prompt('New menu id:', `${state.selectedId}_copy`);
    if (!target) return;
    try {
      await call('/api/menus/duplicate', {
        method: 'POST',
        body: JSON.stringify({ id: state.selectedId, targetId: target })
      });
      notify('Menu duplicated.');
      await load();
      await select(target);
    } catch (error) {
      notify(error.message, true);
    }
  }

  async function deleteMenu() {
    if (!confirm(`Delete menu '${state.selectedId}'?`)) return;
    try {
      await call('/api/menus/delete', { method: 'POST', body: JSON.stringify({ id: state.selectedId }) });
      state.selectedId = null;
      state.draft = null;
      state.dirty = false;
      notify('Menu deleted.');
      await load();
      renderEditor();
    } catch (error) {
      notify(error.message, true);
    }
  }

  async function reloadMenus() {
    try {
      const result = await call('/api/menus/reload', { method: 'POST', body: '{}' });
      notify(result.problems && result.problems.length
        ? `Reloaded with ${result.problems.length} problem(s).`
        : 'Menus reloaded.');
      await load();
    } catch (error) {
      notify(error.message, true);
    }
  }

  function notify(message, isError) {
    const box = $('menu-problems');
    if (!box) return;
    box.classList.remove('hidden');
    box.innerHTML = `<h2 class="${isError ? 'menu-message-bad' : 'menu-message-ok'}">${esc(message)}</h2>`;
  }

  function install() {
    const newButton = $('menu-new');
    if (newButton) newButton.addEventListener('click', createNew);
    const reloadButton = $('menu-reload');
    if (reloadButton) reloadButton.addEventListener('click', reloadMenus);
  }

  window.ParadigmMenus = { load };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', install);
  } else {
    install();
  }
})();
