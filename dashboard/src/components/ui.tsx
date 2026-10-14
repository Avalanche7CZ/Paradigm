import { useTranslation } from '../core/i18n';
import type { ComponentChildren } from 'preact';
import { useEffect, useRef } from 'preact/hooks';
import { array, object, string, title, type Json } from '../core/types';
export function Card({
  title: heading,
  children,
  class: cls = '',
}: {
  title?: string;
  children: ComponentChildren;
  class?: string;
}) {
  const { t } = useTranslation();
  return (
    <section class={`card ${cls}`}>
      {heading && <h2>{t(heading)}</h2>}
      {children}
    </section>
  );
}
export function Badge({
  children,
  tone = 'neutral',
}: {
  children: ComponentChildren;
  tone?: string;
}) {
  return <span class={`badge ${tone}`}>{children}</span>;
}
export function Empty({
  title: heading,
  children,
}: {
  title: string;
  children?: ComponentChildren;
}) {
  return (
    <div class="state">
      <span class="empty-icon">◇</span>
      <h2>{heading}</h2>
      <p>{children}</p>
    </div>
  );
}
export function Dialog({
  message,
  label,
  resolve,
}: {
  message: string;
  label: string;
  resolve: (ok: boolean) => void;
}) {
  const { t } = useTranslation();
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const previous = document.activeElement;
    ref.current?.showModal();
    return () => {
      ref.current?.close();
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      aria-labelledby="confirm-title"
      onCancel={(e) => {
        e.preventDefault();
        resolve(false);
      }}
    >
      <h2 id="confirm-title">{t('Review operation')}</h2>
      <p class="preserve-lines">{message}</p>
      <div class="actions">
        <button autoFocus onClick={() => resolve(false)}>
          {t('Cancel')}
        </button>
        <button class="primary" onClick={() => resolve(true)}>
          {t(label)}
        </button>
      </div>
    </dialog>
  );
}
export function Tabs({
  values,
  selected,
  onChange,
}: {
  values: { id: string; label: string }[];
  selected: string;
  onChange: (id: string) => void;
}) {
  return (
    <nav class="tabs" aria-label="View options">
      {values.map((v) => (
        <button
          key={v.id}
          aria-current={selected === v.id ? 'page' : undefined}
          onClick={() => onChange(v.id)}
        >
          {v.label}
        </button>
      ))}
    </nav>
  );
}
export function Pager({
  page,
  total,
  size = 25,
  change,
}: {
  page: number;
  total: number;
  size?: number;
  change: (page: number) => void;
}) {
  return (
    <div class="pager">
      <span>
        {total} results · Page {page} of {Math.max(1, Math.ceil(total / size))}
      </span>
      <div class="actions">
        <button disabled={page <= 1} onClick={() => change(page - 1)}>
          Previous
        </button>
        <button disabled={page * size >= total} onClick={() => change(page + 1)}>
          Next
        </button>
      </div>
    </div>
  );
}
export function DataView({ data }: { data: Json | undefined }) {
  if (data == null) return <span class="muted">Unavailable</span>;
  if (typeof data !== 'object') return <span class="preserve-lines">{string(data)}</span>;
  if (Array.isArray(data))
    return data.length ? (
      <div class="data-list">
        {data.map((v, i) => (
          <div key={i}>
            <DataView data={v} />
          </div>
        ))}
      </div>
    ) : (
      <span class="muted">None</span>
    );
  return (
    <dl class="data-details">
      {Object.entries(data).map(([k, v]) => (
        <div key={k}>
          <dt>{title(k)}</dt>
          <dd>
            <DataView data={v} />
          </dd>
        </div>
      ))}
    </dl>
  );
}
export function Rows({
  rows,
  columns,
  onSelect,
}: {
  rows: Json[];
  columns: {
    key: string;
    label: string;
    render?: (row: ReturnType<typeof object>) => ComponentChildren;
  }[];
  onSelect?: (row: ReturnType<typeof object>) => void;
}) {
  if (!rows.length)
    return (
      <Empty title="No matching results">Try changing the filters or create a new entry.</Empty>
    );
  return (
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            {columns.map((c) => (
              <th scope="col" key={c.key}>
                {c.label}
              </th>
            ))}
            {onSelect && <th scope="col">Details</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((raw, i) => {
            const row = object(raw);
            return (
              <tr key={string(row.id || row.uuid || row.ticketKey) || i}>
                {columns.map((c) => (
                  <td key={c.key}>{c.render ? c.render(row) : string(row[c.key]) || '—'}</td>
                ))}
                {onSelect && (
                  <td>
                    <button onClick={() => onSelect(row)}>Open</button>
                  </td>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
export function JsonEditor({
  label,
  value,
  onChange,
  disabled,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}) {
  let error = '';
  try {
    JSON.parse(value);
  } catch {
    error = 'Enter valid JSON. Your input is preserved.';
  }
  return (
    <label class="field">
      <span>{label}</span>
      <textarea
        class="code json-editor"
        value={value}
        disabled={disabled}
        spellcheck={false}
        onInput={(e) => onChange(e.currentTarget.value)}
        aria-invalid={!!error}
      />
      {error && <small class="text-error">{error}</small>}
    </label>
  );
}
export function SaveBar({
  dirty,
  busy,
  save,
  discard,
  label = 'Save changes',
}: {
  dirty: boolean;
  busy: boolean;
  save: () => void;
  discard: () => void;
  label?: string;
}) {
  const { t } = useTranslation();
  return dirty ? (
    <div class="savebar" role="status">
      <span>
        <span class="dirty-dot" />
        {t('Unsaved changes')}
      </span>
      <div class="actions">
        <button disabled={busy} onClick={discard}>
          {t('Discard')}
        </button>
        <button class="primary" disabled={busy} onClick={save}>
          {t(busy ? 'Saving…' : label)}
        </button>
      </div>
    </div>
  ) : null;
}
export function HostOnly({ children }: { children: ComponentChildren }) {
  return <div class="scope-note">{children}</div>;
}
