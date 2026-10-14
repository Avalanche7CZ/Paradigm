import { useState } from 'preact/hooks';
import { AsyncBoundary, useDirty, useResource, useWorkspace } from '../core/context';
import { object, string, title, type Json, type Obj } from '../core/types';
import type { Page } from '../core/navigation';
import { Card, DataView, SaveBar } from '../components/ui';
import { Configuration } from './configuration';
export function Storage() {
  const w = useWorkspace();
  const resource = useResource<Json>(
    (signal) => w.api.request('/api/storage/status', undefined, signal),
    'storage',
  );
  const [result, setResult] = useState<Json>();
  const [source, setSource] = useState('json');
  const [target, setTarget] = useState('sql');
  const [policy, setPolicy] = useState('skip');
  async function run(path: string, body?: Obj) {
    try {
      setResult(await w.mutate(() => w.api.request(path, body, undefined, w.hostId)));
    } catch (error) {
      w.notify(String(error), true);
    }
  }
  return (
    <>
      <Card title="Storage runtime">
        <AsyncBoundary resource={resource}>{(data) => <DataView data={data} />}</AsyncBoundary>
        <div class="actions">
          <button
            disabled={w.busy || w.session.capabilities?.mutations?.storage === false}
            onClick={() => run('/api/storage/test', {})}
          >
            Test connection
          </button>
          <button disabled={w.busy} onClick={() => run('/api/storage/health')}>
            Health report
          </button>
          <button disabled={w.busy} onClick={() => run('/api/storage/repair/check')}>
            Check repair requirements
          </button>
        </div>
      </Card>
      <Card title="Migration dry run">
        <p class="help">
          Preview conflicts and counts. No player data is changed by this operation. Saving a new
          provider never migrates data automatically.
        </p>
        <div class="field-grid">
          <label class="field">
            <span>Source</span>
            <select value={source} onChange={(e) => setSource(e.currentTarget.value)}>
              <option>json</option>
              <option>sql</option>
            </select>
          </label>
          <label class="field">
            <span>Target</span>
            <select value={target} onChange={(e) => setTarget(e.currentTarget.value)}>
              <option>sql</option>
              <option>json</option>
            </select>
          </label>
          <label class="field">
            <span>Conflict policy</span>
            <select value={policy} onChange={(e) => setPolicy(e.currentTarget.value)}>
              <option>skip</option>
              <option>overwrite</option>
              <option>fail</option>
            </select>
          </label>
        </div>
        <button
          disabled={
            w.busy || source === target || w.session.capabilities?.mutations?.storage === false
          }
          onClick={() => run('/api/storage/migration/dry-run', { source, target, policy })}
        >
          Preview migration
        </button>
      </Card>
      {result && (
        <Card title="Diagnostic result">
          <DataView data={result} />
        </Card>
      )}
    </>
  );
}
export function StorageConfiguration() {
  const w = useWorkspace();
  const resource = useResource<Json>(
    (signal) => w.api.request('/api/storage/configuration', undefined, signal),
    'storage-config',
  );
  return (
    <AsyncBoundary resource={resource}>
      {(data) => <StorageForm initial={object(data)} />}
    </AsyncBoundary>
  );
}
function StorageForm({ initial }: { initial: Obj }) {
  const w = useWorkspace();
  const [draft, setDraft] = useState<Obj>({ ...initial, sqlPassword: '' });
  const [result, setResult] = useState<Json>();
  const [saved, setSaved] = useState<Obj>({ ...initial, sqlPassword: '' });
  const dirty = JSON.stringify(saved) !== JSON.stringify(draft);
  useDirty('storage-configuration', dirty);
  const writable = w.session.capabilities?.config.storage?.edit !== false;
  const change = (key: string, v: Json) => setDraft({ ...draft, [key]: v });
  async function run(test = false) {
    if (!writable) {
      w.notify('You do not have permission to edit storage configuration.', true);
      return;
    }
    if (
      !test &&
      !(await w.confirm(
        `Save storage configuration on ${w.hostId}? A restart is required. This changes the data source and identity but does not migrate data.\n\nProvider: ${string(draft.provider)}\nDatabase: ${string(draft.sqlDatabase)}\nServer: ${string(draft.serverId)}`,
        'Save configuration',
      ))
    )
      return;
    try {
      const body = Object.fromEntries(
        Object.entries(draft).filter(([k]) => k !== 'sqlPasswordSet'),
      );
      const data = await w.mutate(() =>
        w.api.request<Json>(
          '/api/storage/configuration' + (test ? '/test' : ''),
          body,
          undefined,
          w.hostId,
        ),
      );
      setResult(data);
      if (!test) {
        const next = { ...object(object(data).configuration), sqlPassword: '' };
        setDraft(next);
        setSaved(next);
        w.notify('Storage settings saved. Restart required.');
      }
    } catch (error) {
      w.notify(String(error), true);
    }
  }
  const field = (key: string, type = 'text') => (
    <label class="field">
      <span>{title(key)}</span>
      {type === 'checkbox' ? (
        <input
          type="checkbox"
          checked={draft[key] === true}
          disabled={w.busy || !writable}
          onChange={(e) => change(key, e.currentTarget.checked)}
        />
      ) : (
        <input
          type={type}
          value={string(draft[key])}
          disabled={w.busy || !writable}
          placeholder={
            key === 'sqlPassword' ? 'Leave blank to keep the existing password' : undefined
          }
          autoComplete={key === 'sqlPassword' ? 'new-password' : undefined}
          onInput={(e) =>
            change(key, type === 'number' ? Number(e.currentTarget.value) : e.currentTarget.value)
          }
        />
      )}
    </label>
  );
  return (
    <>
      <div class="scope-note">
        Storage settings belong to the dashboard host. Connection secrets are write-only. Provider
        and identity changes require a restart.
      </div>
      <Card title="Provider and identity">
        <div class="field-grid">
          <label class="field">
            <span>Provider</span>
            <select
              value={string(draft.provider)}
              disabled={w.busy || !writable}
              onChange={(e) => change('provider', e.currentTarget.value)}
            >
              {['json', 'sqlite', 'mysql'].map((v) => (
                <option>{v}</option>
              ))}
            </select>
          </label>
          {field('fallbackToJsonOnSqlFailure', 'checkbox')}
          {['networkId', 'serverId', 'serverName'].map((k) => field(k))}
        </div>
      </Card>
      <Card title="SQLite">
        <div class="field-grid">{field('sqlitePath')}</div>
      </Card>
      <Card title="MySQL / MariaDB connection">
        <div class="field-grid">
          {['sqlHost', 'sqlDatabase', 'sqlUsername', 'sqlPasswordEnv'].map((k) => field(k))}
          {field('sqlPort', 'number')}
          {field('sqlPoolSize', 'number')}
          {field('sqlPassword', 'password')}
          {field('sqlSsl', 'checkbox')}
        </div>
        <button disabled={w.busy || !writable} onClick={() => run(true)}>
          Test proposed connection
        </button>
      </Card>
      {result && (
        <Card title="Result">
          <DataView data={result} />
        </Card>
      )}
      <SaveBar
        dirty={dirty}
        busy={w.busy || !writable}
        save={() => run()}
        discard={() => setDraft({ ...saved })}
      />
    </>
  );
}
export function Discord({ page }: { page: Page }) {
  const w = useWorkspace();
  const host = w.selected === w.hostId;
  return (
    <>
      {host && <DiscordConnection />}
      <Configuration page={page} />
    </>
  );
}
function DiscordConnection() {
  const w = useWorkspace();
  const status = useResource<Json>(
    (signal) => w.api.request('/api/discord/status', undefined, signal),
    'discord',
  );
  const [token, setToken] = useState('');
  const [destination, setDestination] = useState('chat');
  useDirty('discord-token', !!token);
  async function run(action: string, body: Obj = {}) {
    if (
      action === 'token' &&
      !(await w.confirm(
        body.clear === true
          ? 'Clear the Discord bot token on the dashboard host?'
          : 'Replace the Discord bot token on the dashboard host?',
        'Update token',
      ))
    )
      return;
    try {
      const data = await w.mutate(() =>
        w.api.request<Json>('/api/discord/' + action, body, undefined, w.hostId),
      );
      if (action === 'token') setToken('');
      status.refresh();
      w.notify(
        action === 'test' && object(data).queued === true
          ? 'Test message queued. Delivery is not yet confirmed.'
          : 'Operation completed.',
      );
    } catch (error) {
      w.notify(String(error), true);
    }
  }
  return (
    <Card title="Dashboard host connection">
      <AsyncBoundary resource={status}>{(data) => <DataView data={data} />}</AsyncBoundary>
      <div class="field-grid">
        <label class="field">
          <span>Replace bot token</span>
          <input
            type="password"
            autoComplete="new-password"
            placeholder="Leave blank to keep the existing token"
            value={token}
            disabled={w.busy}
            onInput={(e) => setToken(e.currentTarget.value)}
          />
        </label>
        <label class="field">
          <span>Test destination</span>
          <select
            value={destination}
            disabled={w.busy}
            onChange={(e) => setDestination(e.currentTarget.value)}
          >
            {['chat', 'moderation', 'notifications'].map((v) => (
              <option>{v}</option>
            ))}
          </select>
        </label>
      </div>
      <div class="actions">
        <button
          disabled={w.busy || !token || w.session.capabilities?.config.discord?.edit === false}
          onClick={() => run('token', { botToken: token })}
        >
          Replace token
        </button>
        <button
          class="danger"
          disabled={w.busy || w.session.capabilities?.config.discord?.edit === false}
          onClick={() => run('token', { clear: true })}
        >
          Clear token
        </button>
        <button
          disabled={w.busy || w.session.capabilities?.mutations?.discord === false}
          onClick={() => run('test', { destination })}
        >
          Send test
        </button>
        <button
          disabled={w.busy || w.session.capabilities?.mutations?.discord === false}
          onClick={() => run('reconnect')}
        >
          Reconnect
        </button>
      </div>
    </Card>
  );
}
