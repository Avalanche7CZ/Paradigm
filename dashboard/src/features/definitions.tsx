import { useEffect, useLayoutEffect, useState } from 'preact/hooks';
import { query } from '../core/api';
import { AsyncBoundary, useDirty, useResource, useWorkspace } from '../core/context';
import { array, object, string, title, type Json, type Obj } from '../core/types';
import { Card, DataView, Empty, JsonEditor, SaveBar } from '../components/ui';
import { ObjectEditor, ListEditor } from '../components/settings';
import { ActionList, ConditionList, actionProblems } from '../components/actions';
import { FormatEditor, FormatPreview } from '../components/formatting';
type Kind = 'menus' | 'holograms' | 'customCommands';
const endpoint: Record<Kind, string> = {
  menus: '/api/menus',
  holograms: '/api/holograms',
  customCommands: '/api/custom-commands',
};
const defaults: Record<Kind, Obj> = {
  menus: {
    id: '',
    title: 'New menu',
    rows: 3,
    permission: '',
    refreshSeconds: 0,
    slots: [],
    openConditions: [],
    onClose: [],
    fillEmpty: false,
    filler: null,
  },
  holograms: {
    enabled: true,
    dimension: 'minecraft:overworld',
    x: 0,
    y: 64,
    z: 0,
    lineSpacing: 0.28,
    lines: ['<white>New hologram</white>'],
    display: {
      billboard: 'center',
      alignment: 'center',
      scale: 1,
      backgroundColor: '#000000',
      backgroundOpacity: 0,
      textOpacity: 1,
      maxLineWidth: 200,
      textShadow: true,
      seeThrough: false,
    },
    visibility: { mode: 'all', negate: false, conditions: [] },
    interaction: {
      enabled: false,
      width: 1,
      height: 1,
      cooldownSeconds: 0,
      conditions: { mode: 'all', negate: false, conditions: [] },
      onInteract: [],
      onAttack: [],
    },
  },
  customCommands: {
    name: '',
    description: '',
    permission: '',
    requirePermission: false,
    permissionErrorMessage: '',
    cooldown_seconds: 0,
    cooldown_message: '',
    actions: [],
    arguments: [],
  },
};
export function Definitions({ kind }: { kind: Kind }) {
  const w = useWorkspace();
  const base = endpoint[kind];
  const [selected, setSelected] = useState('');
  const [creating, setCreating] = useState(false);
  const [search, setSearch] = useState('');
  const [serial, setSerial] = useState(0);
  const resource = useResource<Json>(
    (signal) => w.api.request(base, undefined, signal),
    `${kind}:${serial}`,
  );
  const [dirty, setDirty] = useState(false);
  const guard = async (task: () => void) => {
    if (w.busy) return;
    if (!dirty || (await w.confirm('Discard unsaved definition changes?', 'Discard changes')))
      task();
  };
  async function reload() {
    if (
      !(await w.confirm(
        `Reload ${title(kind)} definitions on ${w.hostId}? Active runtime objects may be refreshed.`,
        'Reload definitions',
      ))
    )
      return;
    try {
      await w.mutate(() =>
        w.api.request(
          base + (kind === 'holograms' ? '/refresh' : '/reload'),
          {},
          undefined,
          w.hostId,
        ),
      );
      resource.refresh();
      w.notify('Definitions reloaded.');
    } catch (error) {
      w.notify(String(error), true);
    }
  }
  return (
    <>
      <div class="toolbar">
        <input
          type="search"
          aria-label="Search definitions"
          value={search}
          placeholder="Search definitions…"
          onInput={(e) => setSearch(e.currentTarget.value)}
        />
        <div class="actions">
          <button
            disabled={w.busy || w.session.capabilities?.mutations?.[kind] === false}
            onClick={() =>
              guard(() => {
                setSelected('');
                setCreating(true);
                setSerial((v) => v + 1);
              })
            }
          >
            New {kind === 'customCommands' ? 'command' : kind === 'holograms' ? 'hologram' : 'menu'}
          </button>
          <button
            disabled={w.busy || w.session.capabilities?.mutations?.[kind] === false}
            onClick={reload}
          >
            {kind === 'holograms' ? 'Refresh all' : 'Reload definitions'}
          </button>
        </div>
      </div>
      <AsyncBoundary resource={resource}>
        {(raw) => {
          const data = object(raw);
          const definitions =
            kind === 'holograms'
              ? Object.entries(object(object(data.config).holograms)).map(([id, v]) => ({
                  id,
                  definition: v,
                }))
              : array(data[kind === 'customCommands' ? 'commands' : 'menus']);
          return (
            <>
              {data.supported === false && (
                <div class="notice warning">
                  This platform does not support the feature's native rendering. Definitions can be
                  inspected; check the capabilities below.
                </div>
              )}
              {array(data.errors).length > 0 && (
                <Card title="Definition errors">
                  <DataView data={data.errors} />
                </Card>
              )}
              <div class="master-detail">
                <Card>
                  <div class="selection-list">
                    {definitions
                      .filter((raw) =>
                        string(object(raw).id || object(raw).name)
                          .toLowerCase()
                          .includes(search.toLowerCase()),
                      )
                      .map((raw) => {
                        const v = object(raw);
                        const id = string(v.id || v.name);
                        return (
                          <button
                            aria-current={selected === id && !creating ? 'page' : undefined}
                            disabled={w.busy}
                            onClick={() =>
                              guard(() => {
                                setSelected(id);
                                setCreating(false);
                              })
                            }
                          >
                            <strong>{id}</strong>
                            <small>{string(v.title || v.description) || 'Definition'}</small>
                            {v.editable === false && (
                              <small>Provided by a module · read only</small>
                            )}
                          </button>
                        );
                      })}
                    {!definitions.length && <p class="muted">No definitions yet.</p>}
                  </div>
                </Card>
                {selected || creating ? (
                  <DefinitionLoader
                    key={`${kind}:${selected}:${creating}:${serial}`}
                    kind={kind}
                    id={selected}
                    creating={creating}
                    metadata={data}
                    onDirty={setDirty}
                    saved={() => {
                      setCreating(false);
                      setSelected('');
                      setSerial((v) => v + 1);
                    }}
                  />
                ) : (
                  <Empty title="Choose a definition">
                    Select an entry to edit or create a new one.
                  </Empty>
                )}
              </div>
              {kind === 'holograms' ? (
                <HologramRuntime metadata={data} />
              ) : (
                <Card title={kind === 'menus' ? 'Open menu sessions' : 'Runtime capabilities'}>
                  <DataView
                    data={
                      kind === 'menus'
                        ? data.sessions
                        : { actionTypes: data.actionTypes, conditionTypes: data.conditionTypes }
                    }
                  />
                </Card>
              )}
            </>
          );
        }}
      </AsyncBoundary>
    </>
  );
}
function DefinitionLoader({
  kind,
  id,
  creating,
  metadata,
  onDirty,
  saved,
}: {
  kind: Kind;
  id: string;
  creating: boolean;
  metadata: Obj;
  onDirty: (v: boolean) => void;
  saved: () => void;
}) {
  const w = useWorkspace();
  const resource = useResource<Json>(
    (signal) =>
      creating
        ? Promise.resolve<Json>(
            kind === 'customCommands'
              ? { command: { command: defaults[kind] } }
              : { definition: defaults[kind] },
          )
        : w.api.request(
            query(endpoint[kind] + '/item', { [kind === 'customCommands' ? 'name' : 'id']: id }),
            undefined,
            signal,
          ),
    `${kind}:${id}:${creating}`,
  );
  return (
    <AsyncBoundary resource={resource}>
      {(data) => (
        <DefinitionEditor
          kind={kind}
          id={id}
          creating={creating}
          initial={
            kind === 'customCommands'
              ? object(object(object(data).command).command)
              : object(object(data).definition)
          }
          metadata={metadata}
          onDirty={onDirty}
          saved={saved}
        />
      )}
    </AsyncBoundary>
  );
}
function DefinitionEditor({
  kind,
  id,
  creating,
  initial,
  metadata,
  onDirty,
  saved,
}: {
  kind: Kind;
  id: string;
  creating: boolean;
  initial: Obj;
  metadata: Obj;
  onDirty: (v: boolean) => void;
  saved: () => void;
}) {
  const w = useWorkspace();
  const [draft, setDraft] = useState<Obj>(structuredClone(initial));
  const [newId, setNewId] = useState(id);
  const [raw, setRaw] = useState<string | null>(null);
  const [slot, setSlot] = useState(0);
  const [duplicate, setDuplicate] = useState('');
  const dirty =
    creating || newId !== id || JSON.stringify(draft) !== JSON.stringify(initial) || raw !== null;
  useDirty('definition', dirty);
  // Parent guards selection; application guards routes and server changes.
  useLayoutEffect(() => {
    onDirty(dirty);
    return () => onDirty(false);
  }, [dirty]);
  const types = array(metadata.actionTypes).map(string);
  const conditions = array(metadata.conditionTypes).map(string);
  const tokens = array(metadata.placeholderTokens).map(string);
  const editable =
    kind !== 'menus' ||
    !array(metadata.menus).some((v) => object(v).id === id && object(v).editable === false);
  const disabled =
    w.busy || !editable || raw !== null || w.session.capabilities?.mutations?.[kind] === false;
  const set = (key: string, value: Json) => {
    setRaw(null);
    setDraft((prev) => ({ ...prev, [key]: value }));
  };
  async function operation(action: string, body: Obj, needsConfirmation = false) {
    if (
      needsConfirmation &&
      !(await w.confirm(`${title(action)} ${id || newId} on ${w.hostId}?`, 'Confirm operation'))
    )
      return;
    try {
      await w.mutate(() => w.api.request(endpoint[kind] + '/' + action, body, undefined, w.hostId));
      w.notify('Operation completed.');
      saved();
    } catch (error) {
      w.notify(String(error), true);
    }
  }
  async function save() {
    let value = draft;
    if (raw !== null) {
      try {
        value = object(JSON.parse(raw) as Json);
        if (!Object.keys(value).length) throw new Error();
      } catch {
        w.notify('Correct the definition JSON before saving.', true);
        return;
      }
    }
    const name =
      kind === 'customCommands' ? string(value.name) : kind === 'menus' ? string(value.id) : newId;
    const actionLists =
      kind === 'customCommands'
        ? [array(value.actions)]
        : kind === 'menus'
          ? [
              array(value.onClose),
              ...array(value.slots).flatMap((slot) =>
                ['actions', 'leftActions', 'rightActions'].map((key) => array(object(slot)[key])),
              ),
            ]
          : [];
    const problems = actionLists.flatMap((list) => actionProblems(list));
    if (problems.length) {
      w.notify(problems[0], true);
      return;
    }
    if (!name.trim()) {
      w.notify('A stable identifier is required.', true);
      return;
    }
    await operation(
      creating ? 'create' : 'update',
      kind === 'customCommands'
        ? { originalName: id, name, command: value }
        : { id: name, definition: value },
    );
  }
  const textField = (key: string, readOnly = false) => (
    <label class="field">
      <span>{title(key)}</span>
      <input
        value={string(draft[key])}
        disabled={disabled || readOnly}
        onInput={(e) => set(key, e.currentTarget.value)}
      />
    </label>
  );
  return (
    <Card>
      <div class="definition-editor">
        <div class="toolbar">
          <h2>{creating ? 'Create definition' : id}</h2>
          {!creating && (
            <button
              class="danger"
              disabled={disabled}
              onClick={() =>
                operation('delete', kind === 'customCommands' ? { name: id } : { id }, true)
              }
            >
              Delete
            </button>
          )}
        </div>
        {kind === 'holograms' ? (
          <label class="field">
            <span>Hologram ID</span>
            <input
              value={newId}
              disabled={disabled || !creating}
              onInput={(e) => setNewId(e.currentTarget.value)}
            />
          </label>
        ) : (
          textField(kind === 'menus' ? 'id' : 'name', kind === 'menus' && !creating)
        )}
        {kind === 'menus' ? (
          <>
            <FormatEditor
              label="Menu title"
              value={string(draft.title)}
              change={(v) => set('title', v)}
              disabled={disabled}
              placeholders={tokens}
            />
            <div class="field-grid">
              <label class="field">
                <span>Rows</span>
                <select
                  value={Number(draft.rows) || 3}
                  disabled={disabled}
                  onChange={(e) => set('rows', Number(e.currentTarget.value))}
                >
                  {[1, 2, 3, 4, 5, 6].map((v) => (
                    <option>{v}</option>
                  ))}
                </select>
              </label>
              {textField('permission')}
              <label class="field">
                <span>Refresh seconds</span>
                <input
                  type="number"
                  min={0}
                  disabled={disabled}
                  value={Number(draft.refreshSeconds) || 0}
                  onInput={(e) => set('refreshSeconds', Number(e.currentTarget.value))}
                />
              </label>
            </div>
            <div class="inventory-grid" aria-label="Inventory layout">
              {Array.from(
                { length: Math.max(1, Math.min(6, Number(draft.rows) || 1)) * 9 },
                (_, index) => {
                  const item = array(draft.slots)
                    .map(object)
                    .find((s) => s.slot === index);
                  return (
                    <button
                      class={`inventory-slot ${item ? 'filled' : ''}`}
                      aria-label={`Slot ${index}`}
                      aria-pressed={index === slot}
                      onClick={() => setSlot(index)}
                    >
                      <span>{index}</span>
                      <small>{string(object(item?.item).itemId).replace('minecraft:', '')}</small>
                    </button>
                  );
                },
              )}
            </div>
            <MenuSlot
              index={slot}
              draft={draft}
              set={set}
              types={types}
              conditions={conditions}
              disabled={disabled}
            />
            <details>
              <summary>Menu access and closing actions</summary>
              <p class="help">
                Open conditions and slot visibility are checked again on the server during clicks.
                Use a conditional action to restrict clicks independently.
              </p>
              <ConditionList
                value={array(draft.openConditions)}
                change={(v) => set('openConditions', v)}
                types={conditions}
                disabled={disabled}
              />
              <ActionList
                label="On close"
                value={array(draft.onClose)}
                change={(v) => set('onClose', v)}
                types={types}
                conditions={conditions}
                disabled={disabled}
              />
            </details>
            <label class="field">
              <span>Fill empty slots</span>
              <input
                type="checkbox"
                checked={draft.fillEmpty === true}
                disabled={disabled}
                onChange={(e) => set('fillEmpty', e.currentTarget.checked)}
              />
            </label>
            {draft.fillEmpty === true && (
              <MenuItemEditor
                value={object(
                  draft.filler || { itemId: 'minecraft:gray_stained_glass_pane', amount: 1 },
                )}
                change={(v) => set('filler', v)}
                disabled={disabled}
              />
            )}
          </>
        ) : kind === 'holograms' ? (
          <>
            <div class="field-grid">
              {[
                'dimension',
                'x',
                'y',
                'z',
                'viewDistance',
                'refreshIntervalSeconds',
                'lineSpacing',
              ].map((key) => (
                <label class="field">
                  <span>{title(key)}</span>
                  <input
                    type={key === 'dimension' ? 'text' : 'number'}
                    step="any"
                    disabled={disabled}
                    value={string(draft[key])}
                    onInput={(e) =>
                      set(
                        key,
                        key === 'dimension'
                          ? e.currentTarget.value
                          : e.currentTarget.value === ''
                            ? null
                            : Number(e.currentTarget.value),
                      )
                    }
                  />
                </label>
              ))}
            </div>
            <h3>Text lines</h3>
            <ListEditor
              label="Line"
              value={array(draft.lines)}
              change={(v) => set('lines', v)}
              formatted
              disabled={disabled}
              placeholders={tokens}
            />
            {['display', 'visibility', 'interaction'].map((key) => (
              <ObjectEditor
                label={title(key)}
                value={draft[key] || {}}
                change={(v) => set(key, v)}
                disabled={disabled}
              />
            ))}
            <PlayerPlacement
              metadata={metadata}
              id={id}
              disabled={disabled || creating}
              refresh={saved}
            />
          </>
        ) : (
          <>
            {textField('description')}
            {textField('permission')}
            <label class="field">
              <span>Require permission</span>
              <input
                type="checkbox"
                disabled={disabled}
                checked={draft.requirePermission === true}
                onChange={(e) => set('requirePermission', e.currentTarget.checked)}
              />
            </label>
            <ActionList
              value={array(draft.actions)}
              change={(v) => set('actions', v)}
              types={types}
              conditions={conditions}
              disabled={disabled}
            />
            <ObjectEditor
              label="Command arguments"
              value={draft.arguments || []}
              change={(v) => set('arguments', v)}
              disabled={disabled}
            />
            <ObjectEditor
              label="Cooldown, permission messages and area restriction"
              value={Object.fromEntries(
                Object.entries(draft).filter(
                  ([k]) =>
                    ![
                      'name',
                      'description',
                      'permission',
                      'requirePermission',
                      'actions',
                      'arguments',
                    ].includes(k),
                ),
              )}
              change={(v) => setDraft((prev) => ({ ...prev, ...object(v) }))}
              disabled={disabled}
            />
          </>
        )}
        {kind === 'holograms' && (
          <ObjectEditor
            label="Other properties"
            value={Object.fromEntries(
              Object.entries(draft).filter(
                ([k]) =>
                  ![
                    'dimension',
                    'x',
                    'y',
                    'z',
                    'viewDistance',
                    'refreshIntervalSeconds',
                    'lineSpacing',
                    'lines',
                    'display',
                    'visibility',
                    'interaction',
                  ].includes(k),
              ),
            )}
            change={(v) => setDraft((prev) => ({ ...prev, ...object(v) }))}
            disabled={disabled}
          />
        )}
        <details>
          <summary>Advanced definition JSON</summary>
          <JsonEditor
            label="Complete definition"
            value={raw ?? JSON.stringify(draft, null, 2)}
            onChange={setRaw}
            disabled={w.busy || !editable}
          />
          <div class="actions">
            <button
              disabled={w.busy || raw === null}
              onClick={() => {
                try {
                  const next = JSON.parse(raw || '{}');
                  if (!next || Array.isArray(next) || typeof next !== 'object') throw new Error();
                  setDraft(next);
                  setRaw(null);
                } catch {
                  w.notify('Enter a valid definition object.', true);
                }
              }}
            >
              Apply JSON to editor
            </button>
            <button disabled={w.busy || raw === null} onClick={() => setRaw(null)}>
              Discard JSON changes
            </button>
          </div>
          <p class="muted">
            All existing properties are preserved. Structured controls and JSON edit the same
            definition.
          </p>
        </details>
        {!creating && (
          <div class="field-grid">
            <label class="field">
              <span>Duplicate as</span>
              <input
                value={duplicate}
                disabled={disabled}
                onInput={(e) => setDuplicate(e.currentTarget.value)}
              />
            </label>
            <div class="actions">
              <button
                disabled={disabled || !duplicate.trim()}
                onClick={() =>
                  operation(
                    'duplicate',
                    kind === 'customCommands'
                      ? { originalName: id, name: duplicate }
                      : kind === 'holograms'
                        ? { originalId: id, id: duplicate }
                        : { id, targetId: duplicate },
                    true,
                  )
                }
              >
                Duplicate
              </button>
              {kind === 'holograms' && (
                <button
                  disabled={disabled || !duplicate.trim()}
                  onClick={() => operation('rename', { originalId: id, id: duplicate }, true)}
                >
                  Rename
                </button>
              )}
            </div>
          </div>
        )}
        <SaveBar
          dirty={dirty}
          busy={w.busy}
          save={save}
          discard={() => {
            if (creating) {
              saved();
              return;
            }
            setDraft(structuredClone(initial));
            setNewId(id);
            setRaw(null);
          }}
          label={creating ? 'Create definition' : 'Save definition'}
        />
      </div>
    </Card>
  );
}
function MenuSlot({
  index,
  draft,
  set,
  types,
  conditions,
  disabled,
}: {
  index: number;
  draft: Obj;
  set: (key: string, value: Json) => void;
  types: string[];
  conditions: string[];
  disabled: boolean;
}) {
  const slots = array(draft.slots);
  const selected = slots.map(object).find((s) => s.slot === index);
  const value: Obj = selected || {
    slot: index,
    item: {
      itemId: 'minecraft:stone',
      amount: 1,
      name: '',
      lore: [],
      glint: false,
      customModelData: null,
      legacyMetadata: null,
      hideTooltip: false,
    },
    visibleIf: [],
    actions: [],
    leftActions: [],
    rightActions: [],
  };
  const update = (key: string, next: Json) => {
    const entry = { ...value, [key]: next };
    set(
      'slots',
      selected ? slots.map((raw) => (object(raw).slot === index ? entry : raw)) : [...slots, entry],
    );
  };
  return (
    <Card title={`Slot ${index}`}>
      <MenuItemEditor
        value={object(value.item)}
        change={(v) => update('item', v)}
        disabled={disabled}
      />
      <label class="field">
        <span>Refresh slot</span>
        <input
          type="checkbox"
          checked={value.refresh === true}
          disabled={disabled}
          onChange={(e) => update('refresh', e.currentTarget.checked)}
        />
      </label>
      {['visibleIf'].map((key) => (
        <details>
          <summary>{title(key)}</summary>
          <ConditionList
            value={array(value[key])}
            change={(v) => update(key, v)}
            types={conditions}
            disabled={disabled}
          />
        </details>
      ))}
      {['actions', 'leftActions', 'rightActions'].map((key) => (
        <details>
          <summary>{title(key)}</summary>
          <ActionList
            label={title(key)}
            value={array(value[key])}
            change={(v) => update(key, v)}
            types={types}
            conditions={conditions}
            disabled={disabled}
          />
        </details>
      ))}
      <button
        class="danger"
        disabled={disabled || !selected}
        onClick={() =>
          set(
            'slots',
            slots.filter((raw) => object(raw).slot !== index),
          )
        }
      >
        Clear slot
      </button>
    </Card>
  );
}
function MenuItemEditor({
  value,
  change,
  disabled,
}: {
  value: Obj;
  change: (v: Obj) => void;
  disabled: boolean;
}) {
  const set = (key: string, next: Json) => change({ ...value, [key]: next });
  return (
    <fieldset disabled={disabled}>
      <legend>Item, amount, name and lore</legend>
      <label class="field">
        <span>itemId</span>
        <input value={string(value.itemId)} onInput={(e) => set('itemId', e.currentTarget.value)} />
      </label>
      {['amount', 'customModelData', 'legacyMetadata'].map((key) => (
        <label class="field">
          <span>{key}</span>
          <input
            type="number"
            min={key === 'amount' ? 1 : 0}
            max={key === 'amount' ? 64 : key === 'legacyMetadata' ? 32767 : undefined}
            step={1}
            value={string(value[key])}
            onInput={(e) =>
              set(key, e.currentTarget.value === '' ? null : Number(e.currentTarget.value))
            }
          />
        </label>
      ))}
      <FormatEditor
        label="Item name"
        value={string(value.name)}
        change={(v) => set('name', v)}
        disabled={disabled}
      />
      <ListEditor
        label="Lore"
        value={array(value.lore)}
        change={(v) => set('lore', v)}
        formatted
        disabled={disabled}
      />
      {['glint', 'hideTooltip'].map((key) => (
        <label class="field">
          <span>{title(key)}</span>
          <input
            type="checkbox"
            checked={value[key] === true}
            onChange={(e) => set(key, e.currentTarget.checked)}
          />
        </label>
      ))}
    </fieldset>
  );
}
function PlayerPlacement({
  metadata,
  id,
  disabled,
  refresh,
}: {
  metadata: Obj;
  id: string;
  disabled: boolean;
  refresh: () => void;
}) {
  const w = useWorkspace();
  const [player, setPlayer] = useState('');
  async function move() {
    if (
      !(await w.confirm(
        `Move ${id} to ${player}'s current location on ${w.hostId}? Unsaved definition edits will be discarded.`,
        'Move hologram',
      ))
    )
      return;
    try {
      await w.mutate(() =>
        w.api.request('/api/holograms/player-location', { id, player }, undefined, w.hostId),
      );
      refresh();
    } catch (error) {
      w.notify(String(error), true);
    }
  }
  return (
    <div class="actions">
      <select
        aria-label="Online player location"
        value={player}
        disabled={disabled}
        onChange={(e) => setPlayer(e.currentTarget.value)}
      >
        <option value="">Select online player…</option>
        {array(metadata.onlinePlayers).map((v) => (
          <option value={string(object(v).name)}>
            {string(object(v).name)} · {string(object(v).dimension)}
          </option>
        ))}
      </select>
      <button disabled={disabled || !player} onClick={move}>
        Use player location
      </button>
    </div>
  );
}
function HologramRuntime({ metadata }: { metadata: Obj }) {
  const w = useWorkspace();
  const [settings, setSettings] = useState(object(metadata.config));
  const [savedSettings, setSavedSettings] = useState(object(metadata.config));
  const [temporary, setTemporary] = useState('');
  const [raw, setRaw] = useState('');
  const [expires, setExpires] = useState('');
  useDirty(
    'hologram-settings',
    JSON.stringify(settings) !== JSON.stringify(savedSettings) || !!raw,
  );
  async function mutate(action: string, body: Obj) {
    if (!(await w.confirm(`Apply ${action} on ${w.hostId}?`, 'Apply operation'))) return;
    try {
      const result = object(
        await w.mutate(() =>
          w.api.request<Json>('/api/holograms/' + action, body, undefined, w.hostId),
        ),
      );
      if (action === 'settings') {
        const next = object(result.config);
        setSettings(next);
        setSavedSettings(next);
      }
      setRaw('');
      w.notify('Operation completed. Refresh definitions for the latest state.');
    } catch (error) {
      w.notify(String(error), true);
    }
  }
  return (
    <>
      <Card title="Runtime capabilities">
        <DataView
          data={{
            capabilities: metadata.capabilities,
            runtimeStatus: metadata.runtimeStatus,
            loadedDimensions: metadata.loadedDimensions,
          }}
        />
      </Card>
      <Card title="Hologram defaults">
        <fieldset disabled={w.busy || w.session.capabilities?.mutations?.holograms === false}>
          <ObjectEditor
            label="Settings"
            value={{
              enabled: settings.enabled,
              defaultViewDistance: settings.defaultViewDistance,
              defaultRefreshIntervalSeconds: settings.defaultRefreshIntervalSeconds,
            }}
            change={(v) => setSettings({ ...settings, ...object(v) })}
            disabled={w.busy}
          />
          <button
            disabled={w.busy}
            onClick={() =>
              mutate('settings', {
                enabled: settings.enabled,
                defaultViewDistance: settings.defaultViewDistance,
                defaultRefreshIntervalSeconds: settings.defaultRefreshIntervalSeconds,
              })
            }
          >
            Save defaults
          </button>
        </fieldset>
      </Card>
      <Card title="Temporary holograms">
        <fieldset disabled={w.busy || w.session.capabilities?.mutations?.holograms === false}>
          <DataView data={metadata.temporary} />
          <div class="field-grid">
            <label class="field">
              <span>Temporary ID</span>
              <input value={temporary} onInput={(e) => setTemporary(e.currentTarget.value)} />
            </label>
            <label class="field">
              <span>Expires at (epoch milliseconds; empty keeps lifetime)</span>
              <input
                type="number"
                value={expires}
                onInput={(e) => setExpires(e.currentTarget.value)}
              />
            </label>
          </div>
          <JsonEditor label="Temporary definition" value={raw || '{}'} onChange={setRaw} />
          <div class="actions">
            <button
              disabled={w.busy || !temporary || !raw}
              onClick={() => {
                try {
                  void mutate('temporary-update', {
                    id: temporary,
                    definition: JSON.parse(raw) as Json,
                    expiresAt: expires ? Number(expires) : null,
                  });
                } catch {
                  w.notify('Correct the JSON before updating.', true);
                }
              }}
            >
              Update temporary
            </button>
            <button
              class="danger"
              disabled={w.busy || !temporary}
              onClick={() => mutate('temporary-remove', { id: temporary })}
            >
              Remove temporary
            </button>
          </div>
        </fieldset>
      </Card>
    </>
  );
}
