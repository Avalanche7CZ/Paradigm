import { array, object, string, type Json, type Obj, title } from '../core/types';
import { ObjectEditor, ListEditor } from './settings';
import { FormatEditor } from './formatting';
export function actionProblems(value: Json[], depth = 0): string[] {
  if (depth > 16) return ['Action nesting is too deep.'];
  const problems: string[] = [];
  function transaction(steps: Json[], nested: number) {
    if (nested > 16) {
      problems.push('Action nesting is too deep.');
      return;
    }
    for (const raw of steps) {
      const step = object(raw);
      const type = string(step.type).trim().toLowerCase();
      if (array(step.conditions).length || array(step.on_failure).length)
        problems.push('Put transaction conditions and failure actions on the outer action.');
      if (['consume_items', 'add_group', 'remove_group'].includes(type))
        transaction(array(step.on_success), nested + 1);
      else if (!['message', 'broadcast', 'actionbar', 'title', 'sound', 'open_menu'].includes(type))
        problems.push(`${type || 'Unknown action'} cannot participate in an item transaction.`);
    }
  }
  value.forEach((raw, index) => {
    const action = object(raw);
    const type = string(action.type).trim().toLowerCase();
    if (type === 'consume_items')
      transaction([...array(action.on_success), ...value.slice(index + 1)], depth + 1);
    if (type === 'transaction')
      transaction([...array(action.actions), ...array(action.on_success)], depth + 1);
    if (['add_group', 'remove_group'].includes(type))
      transaction(array(action.on_success), depth + 1);
    if (type === 'await_input' && index !== value.length - 1)
      problems.push('await_input must be last; put subsequent actions in on_success.');
    for (const key of ['actions', 'on_success', 'on_failure'])
      problems.push(...actionProblems(array(action[key]), depth + 1));
  });
  return [...new Set(problems)];
}
export function actionTemplate(type: string): Obj {
  switch (type) {
    case 'message':
    case 'actionbar':
      return { type, text: [] };
    case 'run_command':
    case 'run_console':
      return { type, commands: [] };
    case 'play_sound':
      return { type, text: ['minecraft:entity.experience_orb.pickup 1 1 master'] };
    case 'runcmd':
    case 'command':
    case 'player_command':
    case 'console_command':
      return { type, commands: [] };
    case 'menu':
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
      return { type, x: 0, y: 64, z: 0 };
    case 'title':
      return { type, text: ['', ''] };
    case 'sound':
      return { type, text: ['minecraft:entity.experience_orb.pickup 1 1 master'] };
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
      {actionProblems(value).map((problem) => (
        <p role="alert" class="text-error">
          {problem}
        </p>
      ))}
      <p class="help">
        Runs from top to bottom. Use conditional for guarded actions. consume_items includes all
        following actions in its transaction; only item/group mutations and message, broadcast,
        actionbar, title, sound or open_menu notifications are supported there. Put conditions and
        failure actions on the outer transaction. Teleport uses the current world. Title text
        contains title then subtitle; sound text uses ID, volume, pitch and category.
      </p>
      {value.map((raw, i) => {
        const action = object(raw);
        const type = string(action.type).trim().toLowerCase();
        const unsupported =
          type === 'teleport'
            ? ['world']
            : type === 'title'
              ? ['title', 'subtitle', 'fadeIn', 'stay', 'fadeOut']
              : ['sound', 'play_sound'].includes(type)
                ? ['sound', 'volume', 'pitch', 'category']
                : [];
        const ignored = unsupported.filter((key) => key in action);
        return (
          <div class="card" key={i}>
            {ignored.length > 0 && (
              <p role="alert" class="text-warning">
                The server does not use these fields: {ignored.join(', ')}. They remain in the
                complete definition JSON. Title and sound use text; teleport uses coordinates in the
                current world.
              </p>
            )}
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
              .filter(([key]) => key !== 'type' && !unsupported.includes(key))
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
