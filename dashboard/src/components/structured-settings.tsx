import { array, object, string, type Field, type Json, type Obj } from '../core/types';
import { ListEditor, ObjectEditor } from './editors';
import { FormatEditor } from './formatting';
const structuredKeys = [
  'tablist.perWorldOverrides',
  'moderation.warnEscalationRules',
  'restart.realTimeInterval',
  'restart.timerBroadcast',
  'restart.preRestartCommands',
];
export const hasStructuredList = (key: string) => structuredKeys.includes(key);
export function parseRestartAction(
  value: string,
): { seconds: number; type: string; runAs: string; content: string } | undefined {
  const match = value.match(/^\s*(\d+)\s*\|\s*(.*)$/s);
  if (!match) return;
  let content = match[2];
  const asPlayer = /^(\[asPlayer\]|asplayer:|each:)\s*/i.test(content);
  if (asPlayer) content = content.replace(/^(\[asPlayer\]|asplayer:|each:)\s*/i, '');
  const broadcast = /^broadcast\s+/i.test(content);
  return {
    seconds: Number(match[1]),
    type: broadcast ? 'broadcast' : 'command',
    runAs: asPlayer ? 'player' : 'console',
    content: broadcast ? content.replace(/^broadcast\s+/i, '') : content,
  };
}
export function serializeRestartAction(value: NonNullable<ReturnType<typeof parseRestartAction>>) {
  return `${value.seconds} | ${value.type === 'broadcast' ? 'broadcast ' : value.runAs === 'player' ? '[asPlayer] ' : ''}${value.content}`;
}
function parseObject(value: Json): Obj | undefined {
  try {
    const parsed = JSON.parse(string(value)) as Json;
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}
export function StructuredList({
  field,
  value,
  change,
  disabled,
  placeholders,
}: {
  field: Field;
  value: Json[];
  change: (v: Json) => void;
  disabled: boolean;
  placeholders: string[];
}) {
  const update = (index: number, next: Json) =>
    change(value.map((v, i) => (i === index ? next : v)));
  function template(): Json {
    switch (field.key) {
      case 'tablist.perWorldOverrides':
        return JSON.stringify({
          world: '',
          header: null,
          footer: null,
          playerFormat: null,
          showPing: null,
        });
      case 'moderation.warnEscalationRules':
        return JSON.stringify({ warnings: 3, window: '7d', banDuration: '1h', reason: '' });
      case 'restart.preRestartCommands':
        return '30 | broadcast Restarting soon';
      case 'restart.realTimeInterval':
        return '06:00';
      default:
        return '60';
    }
  }
  return (
    <div class="list-editor">
      {value.map((v, i) => {
        const text = string(v);
        const parsed =
          field.key.startsWith('tablist') || field.key.startsWith('moderation')
            ? parseObject(v)
            : undefined;
        const restart =
          field.key === 'restart.preRestartCommands' ? parseRestartAction(text) : undefined;
        return (
          <section class="card" key={i}>
            <div class="toolbar">
              <h3>
                {field.key === 'tablist.perWorldOverrides'
                  ? `World override ${i + 1}`
                  : field.key === 'restart.preRestartCommands'
                    ? `Restart action ${i + 1}`
                    : `Entry ${i + 1}`}
              </h3>
              <div class="actions">
                <button
                  disabled={disabled || i === 0}
                  aria-label={`Move entry ${i + 1} up`}
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
                  aria-label={`Move entry ${i + 1} down`}
                  onClick={() => {
                    const next = [...value];
                    [next[i], next[i + 1]] = [next[i + 1], next[i]];
                    change(next);
                  }}
                >
                  ↓
                </button>
                <button
                  class="danger"
                  disabled={disabled}
                  onClick={() => change(value.filter((_, n) => n !== i))}
                >
                  Remove entry
                </button>
              </div>
            </div>
            {field.key === 'restart.realTimeInterval' ? (
              <label class="field">
                <span>Server clock time</span>
                <input
                  type="time"
                  disabled={disabled}
                  value={text}
                  onInput={(e) => update(i, e.currentTarget.value)}
                />
              </label>
            ) : field.key === 'restart.timerBroadcast' ? (
              <label class="field">
                <span>Seconds before restart</span>
                <input
                  type="number"
                  min={1}
                  max={86400}
                  disabled={disabled}
                  value={text}
                  onInput={(e) => update(i, e.currentTarget.value)}
                />
              </label>
            ) : restart ? (
              <>
                <div class="field-grid">
                  <label class="field">
                    <span>Seconds before restart</span>
                    <input
                      type="number"
                      min={0}
                      disabled={disabled}
                      value={restart.seconds}
                      onInput={(e) =>
                        update(
                          i,
                          serializeRestartAction({
                            ...restart,
                            seconds: Number(e.currentTarget.value),
                          }),
                        )
                      }
                    />
                  </label>
                  <label class="field">
                    <span>Action</span>
                    <select
                      disabled={disabled}
                      value={restart.type}
                      onChange={(e) =>
                        update(
                          i,
                          serializeRestartAction({ ...restart, type: e.currentTarget.value }),
                        )
                      }
                    >
                      <option value="broadcast">Broadcast</option>
                      <option value="command">Run command</option>
                    </select>
                  </label>
                  {restart.type === 'command' && (
                    <label class="field">
                      <span>Run as</span>
                      <select
                        disabled={disabled}
                        value={restart.runAs}
                        onChange={(e) =>
                          update(
                            i,
                            serializeRestartAction({ ...restart, runAs: e.currentTarget.value }),
                          )
                        }
                      >
                        <option value="console">Console</option>
                        <option value="player">Each player</option>
                      </select>
                    </label>
                  )}
                </div>
                {restart.type === 'broadcast' ? (
                  <FormatEditor
                    label="Broadcast text"
                    value={restart.content}
                    change={(next) =>
                      update(i, serializeRestartAction({ ...restart, content: next }))
                    }
                    disabled={disabled}
                    placeholders={placeholders}
                  />
                ) : (
                  <label class="field">
                    <span>Command</span>
                    <textarea
                      class="code"
                      value={restart.content}
                      disabled={disabled}
                      onInput={(e) =>
                        update(
                          i,
                          serializeRestartAction({ ...restart, content: e.currentTarget.value }),
                        )
                      }
                    />
                  </label>
                )}
              </>
            ) : parsed ? (
              field.key === 'tablist.perWorldOverrides' ? (
                <WorldOverride
                  value={parsed}
                  change={(next) => update(i, JSON.stringify(next))}
                  disabled={disabled}
                  placeholders={placeholders}
                />
              ) : (
                <ObjectEditor
                  label="Escalation rule"
                  value={parsed}
                  change={(next) => update(i, JSON.stringify(next))}
                  disabled={disabled}
                />
              )
            ) : (
              <>
                <p class="text-warning">
                  Could not parse this value. It is preserved exactly; edit the original text to
                  correct it.
                </p>
                <textarea
                  aria-label={`Original value ${i + 1}`}
                  class="code"
                  value={text}
                  disabled={disabled}
                  onInput={(e) => update(i, e.currentTarget.value)}
                />
              </>
            )}
          </section>
        );
      })}
      <button disabled={disabled} onClick={() => change([...value, template()])}>
        Add entry
      </button>
    </div>
  );
}
function WorldOverride({
  value,
  change,
  disabled,
  placeholders,
}: {
  value: Obj;
  change: (v: Obj) => void;
  disabled: boolean;
  placeholders: string[];
}) {
  const set = (key: string, v: Json) => change({ ...value, [key]: v });
  return (
    <>
      <div class="field-grid">
        <label class="field">
          <span>World / dimension ID</span>
          <input
            value={string(value.world)}
            disabled={disabled}
            aria-invalid={!string(value.world).trim()}
            onInput={(e) => set('world', e.currentTarget.value)}
          />
        </label>
        <label class="field">
          <span>Ping display</span>
          <select
            disabled={disabled}
            value={value.showPing == null ? '' : string(value.showPing)}
            onChange={(e) =>
              set(
                'showPing',
                e.currentTarget.value === '' ? null : e.currentTarget.value === 'true',
              )
            }
          >
            <option value="">Inherit</option>
            <option value="true">Show</option>
            <option value="false">Hide</option>
          </select>
        </label>
      </div>
      {['header', 'footer'].map((key) => (
        <section>
          <label class="field">
            <span>Override {key}</span>
            <input
              type="checkbox"
              checked={value[key] != null}
              disabled={disabled}
              onChange={(e) => set(key, e.currentTarget.checked ? [] : null)}
            />
          </label>
          {value[key] != null && (
            <ListEditor
              label={`${key} line`}
              value={array(value[key])}
              change={(v) => set(key, v)}
              disabled={disabled}
              formatted
              placeholders={placeholders}
            />
          )}
        </section>
      ))}
      <label class="field">
        <span>Override player format</span>
        <input
          type="checkbox"
          checked={value.playerFormat != null}
          disabled={disabled}
          onChange={(e) => set('playerFormat', e.currentTarget.checked ? '' : null)}
        />
      </label>
      {value.playerFormat != null && (
        <FormatEditor
          label="World player format"
          value={string(value.playerFormat)}
          change={(v) => set('playerFormat', v)}
          disabled={disabled}
          placeholders={placeholders}
        />
      )}
      <ObjectEditor
        label="Other world properties"
        value={Object.fromEntries(
          Object.entries(value).filter(
            ([k]) => !['world', 'showPing', 'header', 'footer', 'playerFormat'].includes(k),
          ),
        )}
        change={(v) => change({ ...value, ...object(v) })}
        disabled={disabled}
      />
    </>
  );
}
