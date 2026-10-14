import { array, object, string, type Json, type Obj, title } from '../core/types';
import { ObjectEditor, ListEditor } from './settings';
import { FormatEditor } from './formatting';
export function actionTemplate(type: string): Obj {
  switch (type) {
    case 'message':
    case 'actionbar':
      return { type, text: [] };
    case 'run_command':
    case 'run_console':
      return { type, commands: [] };
    case 'open_menu':
      return { type, menu: '' };
    case 'check_items':
    case 'consume_items':
      return { type, items: [], on_success: [], on_failure: [] };
    case 'add_group':
    case 'remove_group':
      return { type, group: '', on_success: [], on_failure: [] };
    case 'transaction':
      return { type, actions: [], on_success: [], on_failure: [] };
    case 'broadcast':
      return { type, text: [] };
    case 'conditional':
      return { type, conditions: [], on_success: [], on_failure: [] };
    case 'await_input':
      return { type, key: 'input', timeout: 60, text: [], on_success: [], on_failure: [] };
    case 'teleport':
      return { type, world: 'minecraft:overworld', x: 0, y: 64, z: 0 };
    case 'title':
      return { type, title: '', subtitle: '', fadeIn: 10, stay: 70, fadeOut: 20 };
    case 'sound':
      return { type, sound: 'minecraft:entity.experience_orb.pickup', volume: 1, pitch: 1 };
    default:
      return { type };
  }
}
export function ActionList({
  value,
  change,
  types,
  conditions,
  disabled,
  label = 'Actions',
}: {
  value: Json[];
  change: (v: Json[]) => void;
  types: string[];
  conditions: string[];
  disabled: boolean;
  label?: string;
}) {
  const update = (i: number, v: Json) => change(value.map((a, n) => (n === i ? v : a)));
  return (
    <section class="action-list">
      <h3>{label}</h3>
      <p class="help">
        Runs from top to bottom. Available types come from the server's live registry.
      </p>
      {value.map((raw, i) => {
        const action = object(raw);
        return (
          <div class="card" key={i}>
            <div class="toolbar">
              <label class="field compact">
                <span>Action type</span>
                <select
                  disabled={disabled}
                  value={string(action.type)}
                  onChange={(e) => update(i, actionTemplate(e.currentTarget.value))}
                >
                  {[...new Set([string(action.type), ...types])].filter(Boolean).map((type) => (
                    <option>{type}</option>
                  ))}
                </select>
              </label>
              <div class="actions">
                <button
                  disabled={disabled || i === 0}
                  aria-label={`Move action ${i + 1} up`}
                  onClick={() => {
                    const next = [...value];
                    [next[i - 1], next[i]] = [next[i], next[i - 1]];
                    change(next);
                  }}
                >
                  ↑
                </button>
                <button
                  disabled={disabled || i === value.length - 1}
                  aria-label={`Move action ${i + 1} down`}
                  onClick={() => {
                    const next = [...value];
                    [next[i + 1], next[i]] = [next[i], next[i + 1]];
                    change(next);
                  }}
                >
                  ↓
                </button>
                <button
                  disabled={disabled}
                  onClick={() =>
                    change([
                      ...value.slice(0, i + 1),
                      structuredClone(action),
                      ...value.slice(i + 1),
                    ])
                  }
                >
                  Duplicate
                </button>
                <button
                  class="danger"
                  disabled={disabled}
                  onClick={() => change(value.filter((_, n) => n !== i))}
                >
                  Remove
                </button>
              </div>
            </div>
            {Object.entries(action)
              .filter(([key]) => key !== 'type')
              .map(([key, v]) =>
                ['on_success', 'on_failure', 'actions'].includes(key) ? (
                  <ActionList
                    label={title(key)}
                    value={array(v)}
                    change={(next) => update(i, { ...action, [key]: next })}
                    types={types}
                    conditions={conditions}
                    disabled={disabled}
                  />
                ) : key === 'conditions' ? (
                  <ConditionList
                    value={array(v)}
                    change={(next) => update(i, { ...action, [key]: next })}
                    types={conditions}
                    disabled={disabled}
                  />
                ) : Array.isArray(v) ? (
                  <div>
                    <h3>{title(key)}</h3>
                    <ListEditor
                      label={title(key)}
                      value={v}
                      change={(next) => update(i, { ...action, [key]: next })}
                      disabled={disabled}
                      formatted={key === 'text'}
                    />
                  </div>
                ) : typeof v === 'object' && v !== null ? (
                  <ObjectEditor
                    label={title(key)}
                    value={v}
                    change={(next) => update(i, { ...action, [key]: next })}
                    disabled={disabled}
                  />
                ) : ['text', 'title', 'subtitle'].includes(key) ? (
                  <FormatEditor
                    label={title(key)}
                    value={string(v)}
                    change={(next) => update(i, { ...action, [key]: next })}
                    disabled={disabled}
                  />
                ) : (
                  <label class="field">
                    <span>{title(key)}</span>
                    <input
                      disabled={disabled}
                      type={typeof v === 'number' ? 'number' : 'text'}
                      value={string(v)}
                      onInput={(e) =>
                        update(i, {
                          ...action,
                          [key]:
                            typeof v === 'number'
                              ? Number(e.currentTarget.value)
                              : e.currentTarget.value,
                        })
                      }
                    />
                  </label>
                ),
              )}
          </div>
        );
      })}
      <button
        disabled={disabled}
        onClick={() =>
          change([
            ...value,
            actionTemplate(types.includes('message') ? 'message' : types[0] || 'message'),
          ])
        }
      >
        Add action
      </button>
    </section>
  );
}
export function ConditionList({
  value,
  change,
  types,
  disabled,
}: {
  value: Json[];
  change: (v: Json[]) => void;
  types: string[];
  disabled: boolean;
}) {
  return (
    <section>
      <h3>Conditions</h3>
      {value.map((raw, i) => {
        const condition = object(raw);
        const update = (key: string, next: Json) =>
          change(value.map((v, n) => (n === i ? { ...condition, [key]: next } : v)));
        return (
          <div class="card">
            {string(condition.type) === 'has_items' && (
              <ListEditor
                label="Items"
                value={array(condition.items)}
                change={(next) => update('items', next)}
                disabled={disabled}
              />
            )}
            <div class="field-grid">
              <label class="field">
                <span>Condition type</span>
                <select
                  disabled={disabled}
                  value={string(condition.type)}
                  onChange={(e) => update('type', e.currentTarget.value)}
                >
                  {[...new Set([string(condition.type), ...types])].filter(Boolean).map((v) => (
                    <option>{v}</option>
                  ))}
                </select>
              </label>
              <label class="field">
                <span>Value</span>
                <input
                  disabled={disabled}
                  value={string(condition.value)}
                  onInput={(e) => update('value', e.currentTarget.value)}
                />
              </label>
              <label class="field">
                <span>Negate</span>
                <input
                  disabled={disabled}
                  type="checkbox"
                  role="switch"
                  checked={condition.negate === true}
                  onChange={(e) => update('negate', e.currentTarget.checked)}
                />
              </label>
            </div>
            <ObjectEditor
              label="Additional condition parameters"
              value={Object.fromEntries(
                Object.entries(condition).filter(([k]) => !['type', 'value', 'negate'].includes(k)),
              )}
              change={(next) =>
                change(
                  value.map((v, n) =>
                    n === i
                      ? {
                          type: condition.type,
                          value: condition.value,
                          negate: condition.negate,
                          ...object(next),
                        }
                      : v,
                  ),
                )
              }
              disabled={disabled}
            />
            <button
              class="danger"
              disabled={disabled}
              onClick={() => change(value.filter((_, n) => n !== i))}
            >
              Remove condition
            </button>
          </div>
        );
      })}
      <button
        disabled={disabled}
        onClick={() =>
          change([...value, { type: types[0] || 'has_permission', value: '', negate: false }])
        }
      >
        Add condition
      </button>
    </section>
  );
}
