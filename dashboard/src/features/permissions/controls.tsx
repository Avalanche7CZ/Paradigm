import type { ComponentChildren } from 'preact';
import { useEffect, useRef } from 'preact/hooks';
import { useWorkspace } from '../../core/context';
import { ALL } from '../../core/types';
import { contextLabel } from './model';
export function Popup({
  title,
  close,
  children,
}: {
  title: string;
  close: () => void;
  children: ComponentChildren;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const previous = document.activeElement;
    dialog.current?.showModal();
    return () => {
      dialog.current?.close();
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
    };
  }, []);
  return (
    <dialog
      ref={dialog}
      class="permission-dialog"
      aria-label={title}
      onCancel={(e) => {
        e.preventDefault();
        close();
      }}
    >
      <div class="toolbar">
        <h2>{title}</h2>
        <button aria-label="Close editor" onClick={close}>
          ×
        </button>
      </div>
      {children}
    </dialog>
  );
}
export function ContextChips({ value }: { value: Record<string, string> }) {
  return (
    <span class="permission-contexts">
      {Object.keys(value).length ? (
        Object.entries(value).map(([key, v]) => (
          <span class="badge" title={contextLabel(value)}>
            {key}: {v}
          </span>
        ))
      ) : (
        <span class="muted">Global</span>
      )}
    </span>
  );
}
export function ContextEditor({
  value,
  change,
  keys,
}: {
  value: Record<string, string>;
  change: (v: Record<string, string>) => void;
  keys: string[];
}) {
  const w = useWorkspace();
  const preset = (type: string) =>
    change(
      type === 'global'
        ? {}
        : type === 'server' && w.selected !== ALL
          ? { server: w.selected }
          : { network: w.servers.find((s) => s.current)?.networkId || '' },
    );
  return (
    <fieldset class="permission-context-editor">
      <legend>Contexts</legend>
      <p class="help">
        All specified contexts must match. Empty means global. These constrain assignments, not
        storage ownership.
      </p>
      <div class="actions">
        <button type="button" onClick={() => preset('global')}>
          Global
        </button>
        {w.selected !== ALL && keys.includes('server') && (
          <button type="button" onClick={() => preset('server')}>
            Selected server
          </button>
        )}
      </div>
      <div class="field-grid">
        {keys.map((key) => (
          <label class="field">
            <span>{key[0].toUpperCase() + key.slice(1)}</span>
            <input
              value={value[key] || ''}
              list={
                key === 'server'
                  ? 'permission-server-options'
                  : key === 'network'
                    ? 'permission-network-options'
                    : undefined
              }
              placeholder="Any"
              onInput={(e) => {
                const next = { ...value };
                if (e.currentTarget.value.trim()) next[key] = e.currentTarget.value;
                else delete next[key];
                change(next);
              }}
            />
          </label>
        ))}
      </div>
      <datalist id="permission-server-options">
        {w.servers.map((s) => (
          <option value={s.serverId}>{s.serverName}</option>
        ))}
      </datalist>
      <datalist id="permission-network-options">
        {[...new Set(w.servers.map((s) => s.networkId).filter(Boolean))].map((id) => (
          <option value={id} />
        ))}
      </datalist>
      <p class="help">
        World and dimension IDs must match the server's actual permission contexts. Values are case
        sensitive.
      </p>
    </fieldset>
  );
}
export function ExpiryEditor({
  mode,
  setMode,
  value,
  setValue,
}: {
  mode: string;
  setMode: (v: string) => void;
  value: string;
  setValue: (v: string) => void;
}) {
  return (
    <div class="field-grid">
      <label class="field">
        <span>Expiration</span>
        <select value={mode} onChange={(e) => setMode(e.currentTarget.value)}>
          <option value="permanent">Permanent</option>
          <option value="duration">Temporary duration</option>
          <option value="date">Exact date and time</option>
        </select>
      </label>
      {mode !== 'permanent' && (
        <label class="field">
          <span>{mode === 'date' ? 'Expires at (your time zone)' : 'Duration'}</span>
          <input
            type={mode === 'date' ? 'datetime-local' : 'text'}
            value={value}
            placeholder="30m, 2h, 7d"
            required
            onInput={(e) => setValue(e.currentTarget.value)}
          />
        </label>
      )}
    </div>
  );
}
export function expiryBody(mode: string, value: string): Record<string, string | number | boolean> {
  if (mode === 'permanent') return { permanent: true };
  if (mode === 'date') {
    const expiresAtMs = new Date(value).getTime();
    if (!Number.isFinite(expiresAtMs) || expiresAtMs <= Date.now())
      throw new Error('Choose a future expiration.');
    return { expiresAtMs };
  }
  if (!value.trim()) throw new Error('Enter a duration.');
  return { duration: value.trim() };
}
