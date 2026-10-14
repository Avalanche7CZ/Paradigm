import { readPlayerSelection } from '../core/preferences';
import { useState } from 'preact/hooks';
import { query } from '../core/api';
import { AsyncBoundary, useDirty, useResource, useWorkspace } from '../core/context';
import { ALL, array, number, object, string, title, type Json, type Obj } from '../core/types';
import { Card, DataView, JsonEditor, Pager, Rows, Tabs } from '../components/ui';
const operations: Record<string, { route: string; fields: string[] }> = {
  'Create group': { route: 'group/create', fields: ['group'] },
  'Delete group': { route: 'group/delete', fields: ['group'] },
  'Update group metadata': { route: 'group/update', fields: ['group', 'metadata'] },
  'Add group permission': {
    route: 'group/permission/add',
    fields: ['group', 'permission', 'denied', 'duration', 'contexts'],
  },
  'Remove group permission': {
    route: 'group/permission/remove',
    fields: ['group', 'permission', 'assignmentId', 'contexts'],
  },
  'Add parent group': { route: 'group/parent/add', fields: ['group', 'parent'] },
  'Remove parent group': { route: 'group/parent/remove', fields: ['group', 'parent'] },
  'Add user permission': {
    route: 'user/permission/add',
    fields: ['user', 'permission', 'denied', 'duration', 'contexts'],
  },
  'Remove user permission': {
    route: 'user/permission/remove',
    fields: ['user', 'permission', 'assignmentId', 'contexts'],
  },
  'Assign group': { route: 'user/group/add', fields: ['user', 'group', 'duration', 'contexts'] },
  'Remove group membership': {
    route: 'user/group/remove',
    fields: ['user', 'group', 'assignmentId', 'contexts'],
  },
  'Create track': { route: 'track/create', fields: ['track'] },
  'Delete track': { route: 'track/delete', fields: ['track'] },
  'Rename track': { route: 'track/rename', fields: ['track', 'target'] },
  'Clone track': { route: 'track/clone', fields: ['track', 'target'] },
  'Clear track': { route: 'track/clear', fields: ['track'] },
  'Append rank': { route: 'track/append', fields: ['track', 'group'] },
  'Insert rank': { route: 'track/insert', fields: ['track', 'group', 'position'] },
  'Remove rank': { route: 'track/remove', fields: ['track', 'group'] },
  'Move rank': { route: 'track/move', fields: ['track', 'group', 'position'] },
  'Promote user': {
    route: 'user/track/promote',
    fields: ['user', 'track', 'duration', 'contexts', 'dontAddToFirst'],
  },
  'Demote user': {
    route: 'user/track/demote',
    fields: ['user', 'track', 'duration', 'contexts', 'dontRemoveFromFirst'],
  },
  'Set user rank': {
    route: 'user/track/settrack',
    fields: ['user', 'track', 'group', 'duration', 'contexts'],
  },
  'Clear user track': { route: 'user/track/cleartrack', fields: ['user', 'track', 'contexts'] },
};
export function Permissions() {
  const w = useWorkspace();
  const [tab, setTab] = useState('groups');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState('');
  const [revision, setRevision] = useState(0);
  const [operation, setOperation] = useState('Add user permission');
  const [draft, setDraft] = useState<Obj>({
    user: readPlayerSelection(),
  });
  const [contexts, setContexts] = useState(
    JSON.stringify(w.selected === ALL ? {} : { server: w.selected }, null, 2),
  );
  const [metadata, setMetadata] = useState('{}');
  const [edited, setEdited] = useState(false);
  const [direction, setDirection] = useState('import');
  const [mode, setMode] = useState('dry-run');
  const [report, setReport] = useState<Json>();
  const summary = useResource<Json>(
    (signal) => w.api.request('/api/permissions/summary', undefined, signal),
    String(revision),
  );
  const resource = useResource<Json>(
    (signal) =>
      w.api.request(
        query('/api/permissions/' + tab, { query: search, page, pageSize: 25 }),
        undefined,
        signal,
      ),
    `${tab}:${search}:${page}:${revision}`,
  );
  const detail = useResource<Json>(
    (signal) =>
      selected
        ? w.api.request(
            query(
              '/api/permissions/' +
                (tab === 'groups' ? 'group' : tab === 'users' ? 'user' : 'effective'),
              {
                id: tab === 'groups' ? selected : undefined,
                uuidOrName: tab === 'users' || tab === 'effective' ? selected : undefined,
                query: search,
                page,
                pageSize: 25,
              },
            ),
            undefined,
            signal,
          )
        : Promise.resolve(null),
    `${tab}:${selected}:${revision}:${search}:${page}`,
  );
  useDirty('permission-draft', edited);
  const spec = operations[operation];
  const change = (key: string, v: Json) => {
    setDraft((prev) => ({ ...prev, [key]: v }));
    setEdited(true);
  };
  async function apply() {
    let body: Obj;
    try {
      body = {
        ...Object.fromEntries(Object.entries(draft).filter(([key]) => spec.fields.includes(key))),
        scope: 'custom',
        contexts: JSON.parse(contexts) as Json,
        metadata: JSON.parse(metadata) as Json,
        confirmed: true,
      };
    } catch {
      w.notify('Correct contexts and metadata JSON before applying.', true);
      return;
    }
    if (
      !(await w.confirm(
        `${operation}\n\n${JSON.stringify({ ...body, confirmed: undefined }, null, 2)}\n\nGroup metadata, inheritance and track definitions are shared. Contexts constrain assignments; they do not create separate group definitions.`,
        'Apply reviewed change',
      ))
    )
      return;
    try {
      const result = await w.mutate(() =>
        w.api.request<Json>('/api/permissions/' + spec.route, body),
      );
      const data = object(result);
      if (data.applied !== true) {
        w.notify(string(data.message) || 'The operation was not applied.', true);
        return;
      }
      setEdited(false);
      setDraft({});
      setRevision((v) => v + 1);
      w.notify(string(data.message) || 'Permission change applied.');
    } catch (error) {
      w.notify(String(error), true);
    }
  }
  async function migrate() {
    if (
      mode !== 'dry-run' &&
      !(await w.confirm(
        `${direction} LuckPerms data using ${mode} mode? This affects shared permission state.`,
        'Run migration',
      ))
    )
      return;
    try {
      setReport(
        await w.mutate(() =>
          w.api.request('/api/permissions/migrate/luckperms', {
            direction,
            mode,
            confirmed: mode !== 'dry-run',
          }),
        ),
      );
      setRevision((v) => v + 1);
    } catch (error) {
      w.notify(String(error), true);
    }
  }
  return (
    <>
      <div class="scope-note">
        <strong>Shared permission service</strong>
        <p>
          Definitions and membership come from the host's configured permission backend. New
          assignments use {w.selected === ALL ? 'global context' : `server context ${w.selected}`};
          review contexts before applying. Effective checks use the host's runtime context.
        </p>
      </div>
      <Card>
        <AsyncBoundary resource={summary}>{(data) => <DataView data={data} />}</AsyncBoundary>
      </Card>
      <Tabs
        values={['groups', 'users', 'tracks', 'nodes', 'effective'].map((id) => ({
          id,
          label: title(id),
        }))}
        selected={tab}
        onChange={(id) => {
          setTab(id);
          setPage(1);
          setSelected('');
        }}
      />
      <div class="toolbar">
        <input
          type="search"
          aria-label="Search permission targets"
          placeholder={
            tab === 'effective'
              ? 'Filter effective permission nodes…'
              : 'Search groups, users or nodes…'
          }
          value={search}
          onInput={(e) => {
            setSearch(e.currentTarget.value);
            setPage(1);
          }}
        />
        {tab === 'effective' && (
          <input
            aria-label="User for effective permissions"
            placeholder="User name or UUID"
            value={selected}
            onInput={(e) => setSelected(e.currentTarget.value)}
          />
        )}
      </div>
      {tab === 'effective' ? (
        <AsyncBoundary resource={detail}>
          {(raw) => {
            const data = object(raw);
            return (
              <Card>
                <Rows
                  rows={array(data.entries)}
                  columns={[
                    { key: 'node', label: 'Node' },
                    { key: 'allowed', label: 'Allowed' },
                    { key: 'sourceType', label: 'Source type' },
                    { key: 'sourceName', label: 'Source' },
                    { key: 'rule', label: 'Matching rule' },
                  ]}
                />
                <Pager page={page} total={number(data.total)} change={setPage} />
              </Card>
            );
          }}
        </AsyncBoundary>
      ) : (
        <AsyncBoundary resource={resource}>
          {(raw) => {
            const data = object(raw);
            const rows = array(data[tab]).filter((v) =>
              ['groups', 'tracks'].includes(tab)
                ? JSON.stringify(v).toLowerCase().includes(search.toLowerCase())
                : true,
            );
            return (
              <Card>
                <Rows
                  rows={rows}
                  columns={
                    tab === 'nodes'
                      ? [
                          { key: 'node', label: 'Node' },
                          { key: 'description', label: 'Description' },
                          { key: 'source', label: 'Source' },
                        ]
                      : tab === 'tracks'
                        ? [
                            { key: 'name', label: 'Track' },
                            { key: 'groups', label: 'Ranks, lowest to highest' },
                          ]
                        : [
                            { key: 'name', label: 'Name' },
                            { key: 'description', label: 'Description' },
                            { key: 'permissions', label: 'Permissions' },
                            { key: 'groups', label: 'Groups' },
                          ]
                  }
                  onSelect={
                    tab === 'nodes'
                      ? undefined
                      : (row) => {
                          const id = string(row.name || row.uuid);
                          setSelected(id);
                          setDraft((prev) => ({
                            ...prev,
                            [tab === 'groups' ? 'group' : tab === 'users' ? 'user' : 'track']: id,
                          }));
                        }
                  }
                />
                {typeof data.total === 'number' && (
                  <Pager page={page} total={number(data.total)} change={setPage} />
                )}
              </Card>
            );
          }}
        </AsyncBoundary>
      )}
      {selected && ['groups', 'users'].includes(tab) && (
        <Card title="Assignments and inheritance">
          <AsyncBoundary resource={detail}>{(data) => <DataView data={data} />}</AsyncBoundary>
        </Card>
      )}
      <Card title="Edit permissions">
        <fieldset disabled={w.busy || w.session.capabilities?.mutations?.permissions === false}>
          <label class="field">
            <span>Operation</span>
            <select
              value={operation}
              disabled={w.busy}
              onChange={(e) => setOperation(e.currentTarget.value)}
            >
              {Object.keys(operations).map((name) => (
                <option>{name}</option>
              ))}
            </select>
          </label>
          <div class="field-grid">
            {spec.fields
              .filter((key) => !['contexts', 'metadata'].includes(key))
              .map((key) => (
                <label class="field">
                  <span>
                    {title(key)}
                    {key === 'position' ? ' (zero-based)' : ''}
                  </span>
                  {['denied', 'dontAddToFirst', 'dontRemoveFromFirst'].includes(key) ? (
                    <input
                      type="checkbox"
                      checked={draft[key] === true}
                      disabled={w.busy}
                      onChange={(e) => change(key, e.currentTarget.checked)}
                    />
                  ) : (
                    <input
                      disabled={w.busy}
                      type={key === 'position' ? 'number' : 'text'}
                      value={string(draft[key])}
                      placeholder={
                        key === 'duration' ? 'e.g. 30m, 2h; empty is permanent' : undefined
                      }
                      onInput={(e) =>
                        change(
                          key,
                          key === 'position'
                            ? Number(e.currentTarget.value)
                            : e.currentTarget.value,
                        )
                      }
                    />
                  )}
                </label>
              ))}
          </div>
          {spec.fields.includes('contexts') && (
            <JsonEditor
              label="Assignment contexts"
              value={contexts}
              onChange={(v) => {
                setContexts(v);
                setEdited(true);
              }}
              disabled={w.busy}
            />
          )}{' '}
          {spec.fields.includes('metadata') && (
            <JsonEditor
              label="Group metadata (prefix, suffix, weight, description)"
              value={metadata}
              onChange={(v) => {
                setMetadata(v);
                setEdited(true);
              }}
              disabled={w.busy}
            />
          )}
          <p class="help">
            Remove assignments by stable assignment ID when multiple rules match. Explicit denies,
            expiry and context rules are preserved by the backend.
          </p>
          <div class="actions">
            <button class="primary" disabled={w.busy} onClick={apply}>
              Review and apply
            </button>
            <button
              disabled={w.busy}
              onClick={() => {
                setDraft({});
                setEdited(false);
                setContexts(
                  JSON.stringify(w.selected === ALL ? {} : { server: w.selected }, null, 2),
                );
                setMetadata('{}');
              }}
            >
              Discard draft
            </button>
          </div>
        </fieldset>
      </Card>
      <Card title="LuckPerms migration">
        <fieldset disabled={w.busy || w.session.capabilities?.mutations?.permissions === false}>
          <div class="actions">
            <select
              aria-label="Migration direction"
              value={direction}
              onChange={(e) => setDirection(e.currentTarget.value)}
            >
              <option value="import">LuckPerms → Paradigm</option>
              <option value="export">Paradigm → LuckPerms</option>
            </select>
            <select
              aria-label="Migration mode"
              value={mode}
              onChange={(e) => setMode(e.currentTarget.value)}
            >
              <option value="dry-run">Dry run</option>
              <option value="merge">Merge</option>
              <option value="replace">Replace</option>
            </select>
            <button disabled={w.busy} onClick={migrate}>
              Run migration
            </button>
          </div>
          {report && <DataView data={report} />}
        </fieldset>
      </Card>
    </>
  );
}
