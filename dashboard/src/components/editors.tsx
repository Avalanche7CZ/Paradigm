import { array, object, string, type Json } from '../core/types';
import { FormatEditor } from './formatting';
export function ListEditor({
  value,
  change,
  disabled,
  label,
  formatted = false,
  placeholders = [],
  itemTemplate,
}: {
  itemTemplate?: Json;
  value: Json[];
  change: (v: Json[]) => void;
  disabled?: boolean;
  label: string;
  formatted?: boolean;
  placeholders?: string[];
}) {
  return (
    <div class="list-editor">
      {value.map((v, i) => (
        <div class="list-item" key={i}>
          <span class="list-index">{i + 1}</span>
          <div class="grow">
            {formatted ? (
              <FormatEditor
                label={`${label} ${i + 1}`}
                value={string(v)}
                change={(next) => change(value.map((item, n) => (n === i ? next : item)))}
                disabled={disabled}
                placeholders={placeholders}
              />
            ) : typeof v === 'object' && v !== null ? (
              <ObjectEditor
                label={`${label} ${i + 1}`}
                value={v}
                change={(next) => change(value.map((item, n) => (n === i ? next : item)))}
                disabled={disabled}
              />
            ) : (
              <textarea
                aria-label={`${label} ${i + 1}`}
                value={string(v)}
                rows={2}
                disabled={disabled}
                onInput={(e) =>
                  change(value.map((item, n) => (n === i ? e.currentTarget.value : item)))
                }
              />
            )}
          </div>
          <div class="actions vertical">
            <button
              aria-label={`Move ${label} ${i + 1} up`}
              disabled={disabled || i === 0}
              onClick={() => {
                const next = [...value];
                [next[i - 1], next[i]] = [next[i], next[i - 1]];
                change(next);
              }}
            >
              ↑
            </button>
            <button
              aria-label={`Move ${label} ${i + 1} down`}
              disabled={disabled || i === value.length - 1}
              onClick={() => {
                const next = [...value];
                [next[i], next[i + 1]] = [next[i + 1], next[i]];
                change(next);
              }}
            >
              ↓
            </button>
            <button
              aria-label={`Remove ${label} ${i + 1}`}
              disabled={disabled}
              onClick={() => change(value.filter((_, n) => n !== i))}
            >
              ×
            </button>
          </div>
        </div>
      ))}
      <button
        disabled={disabled}
        onClick={() =>
          change([
            ...value,
            structuredClone(
              itemTemplate ?? (typeof value[0] === 'object' && value[0] !== null ? value[0] : ''),
            ),
          ])
        }
      >
        Add item
      </button>
    </div>
  );
}
export function ObjectEditor({
  value,
  change,
  disabled,
  label,
}: {
  value: Json;
  change: (v: Json) => void;
  disabled?: boolean;
  label: string;
}) {
  return (
    <details class="structured">
      <summary>{label}</summary>
      {Array.isArray(value) ? (
        <ListEditor
          value={value}
          change={change}
          label={label}
          disabled={disabled}
          itemTemplate={
            /arguments/i.test(label)
              ? { name: 'argument', type: 'string', required: true }
              : /conditions/i.test(label)
                ? { type: 'permission', value: '', negate: false }
                : /onInteract|onAttack/i.test(label)
                  ? { type: 'message', text: '' }
                  : undefined
          }
        />
      ) : (
        <div class="field-grid">
          {Object.entries(object(value)).map(([key, v]) => (
            <div key={key}>
              {typeof v === 'object' && v !== null ? (
                <ObjectEditor
                  value={v}
                  change={(next) => change({ ...object(value), [key]: next })}
                  label={key}
                  disabled={disabled}
                />
              ) : (
                <label class="field">
                  <span>{key}</span>
                  {typeof v === 'boolean' ? (
                    <input
                      type="checkbox"
                      role="switch"
                      checked={v}
                      disabled={disabled}
                      onChange={(e) => change({ ...object(value), [key]: e.currentTarget.checked })}
                    />
                  ) : (
                    <input
                      type={typeof v === 'number' ? 'number' : 'text'}
                      value={string(v)}
                      disabled={disabled}
                      onInput={(e) =>
                        change({
                          ...object(value),
                          [key]:
                            typeof v === 'number'
                              ? Number(e.currentTarget.value)
                              : e.currentTarget.value,
                        })
                      }
                    />
                  )}
                </label>
              )}
            </div>
          ))}
        </div>
      )}
    </details>
  );
}
