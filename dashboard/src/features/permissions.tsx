import { useEffect, useRef, useState } from 'preact/hooks';
import { readPlayerSelection } from '../core/preferences';
import { ApiError, query } from '../core/api';
import { AsyncBoundary, useDirty, useResource, useWorkspace } from '../core/context';
import { array, number, object, string, type Json, type Obj } from '../core/types';
import { Badge, Empty, Pager, Tabs } from '../components/ui';
import { Popup } from './permissions/controls';
import {
  appendChanges,
  describeChange,
  group,
  subjectValid,
  trackPreview,
  type Change,
  type View,
} from './permissions/model';
import { TargetEditor } from './permissions/targets';
import { TrackEditor } from './permissions/tracks';
import { WorkflowContext, useWorkflow } from './permissions/workflow';
export function Permissions() {
  const w = useWorkspace();
  const preset = readPlayerSelection();
  const [view, setView] = useState<View>(preset ? 'users' : 'groups');
  const [selected, setSelected] = useState(preset);
  const [label, setLabel] = useState('');
  const [search, setSearch] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [page, setPage] = useState(1);
  const [revision, setRevision] = useState(0);
  const [changes, setChanges] = useState<Change[]>([]);
  const changeSequence = useRef(0);
  const [modal, setModal] = useState(false);
  const [draftDirty, setDraftDirty] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [outcome, setOutcome] = useState('');
  const [create, setCreate] = useState(false);
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [migration, setMigration] = useState(false);
  useEffect(() => {
    const timer = setTimeout(() => setSearchQuery(search), 180);
    return () => clearTimeout(timer);
  }, [search]);
  useDirty('permissions-workspace', changes.length > 0 || modal || draftDirty);
  const summary = useResource<Json>(
    (signal) => w.api.request('/api/permissions/summary', undefined, signal),
    String(revision),
  );
  const groupsData = useResource<Json>(
    (signal) => w.api.request('/api/permissions/groups', undefined, signal),
    String(revision),
  );
  const tracksData = useResource<Json>(
    (signal) => w.api.request('/api/permissions/tracks', undefined, signal),
    String(revision),
  );
  const directory = useResource<Json>(
    (signal) =>
      ['users', 'nodes'].includes(view)
        ? w.api.request(
            query('/api/permissions/' + view, { query: searchQuery, page, pageSize: 50 }),
            undefined,
            signal,
          )
        : Promise.resolve(null),
    `${view}:${searchQuery}:${page}:${revision}`,
  );
  const groups = array(object(groupsData.data).groups).map(group);
  const tracks = array(object(tracksData.data).tracks).map(object);
  const meta = object(summary.data);
  const canManage = w.session.capabilities?.mutations?.permissions === true;
  const editable = !w.busy && !uncertain && canManage && meta.internalEnabled === true;
  const keys = array(meta.contextKeys).map(string);
  function stage(values: Omit<Change, 'key'>[]): boolean {
    if (!editable || uncertain) {
      w.notify(
        'Editing is unavailable until permissions and the current state are verified.',
        true,
      );
      return false;
    }
    try {
      setChanges(appendChanges(changes, values, () => String(++changeSequence.current)));
    } catch (error) {
      w.notify(String(error), true);
      return false;
    }
    setOutcome('');
    return true;
  }
  async function discard() {
    if (
      (changes.length || draftDirty) &&
      !(await w.confirm(
        'Discard remaining staged changes and unstaged fields? Applied changes are not undone.',
        'Discard changes',
      ))
    )
      return false;
    setChanges([]);
    setDraftDirty(false);
    setUncertain(false);
    setOutcome('');
    setRevision((v) => v + 1);
    return true;
  }
  async function select(nextView: View, id = '', display = '') {
    if (w.busy) return;
    if ((changes.length || draftDirty) && !(await discard())) return;
    setView(nextView);
    setSelected(id);
    setLabel(display);
    setSearch('');
    setPage(1);
  }
  async function apply() {
    if (w.busy || !changes.length || !editable || uncertain) return;
    if (draftDirty) {
      w.notify('Stage or discard the open editor fields before applying.', true);
      return;
    }
    const invalidTrack = tracks.map((track) => trackPreview(track, changes).error).find(Boolean);
    if (invalidTrack) {
      w.notify(invalidTrack, true);
      return;
    }
    const reviewed = [...changes];
    const review = reviewed.map((c, i) => `${i + 1}. ${describeChange(c)}`).join('\n\n');
    if (
      !(await w.confirm(
        `Apply ${reviewed.length} individual changes through ${w.hostId}'s permission service?\nThese are not an atomic transaction. Processing stops on the first failure.\n\n${review}\n\nWildcards and administrative grants can change access across the network. Definitions are shared; assignment contexts are shown above.`,
        'Apply reviewed changes',
      ))
    )
      return;
    let applied = 0;
    let alreadyAbsent = 0;
    try {
      await w.mutate(async () => {
        for (const change of reviewed) {
          let result: Obj;
          try {
            result = object(
              await w.api.request<Json>('/api/permissions/' + change.route, {
                ...change.body,
                confirmed: true,
              }),
            );
          } catch (error) {
            if (
              error instanceof ApiError &&
              error.code === 'assignment_not_found' &&
              change.bundle &&
              change.assignmentId &&
              change.route.endsWith('/remove')
            ) {
              alreadyAbsent++;
              setChanges((previous) => previous.filter((c) => c.key !== change.key));
              continue;
            }
            throw error;
          }
          if (typeof result.applied !== 'boolean')
            throw new ApiError(
              0,
              'invalid_response',
              'The backend did not provide a verifiable mutation outcome. Inspect current state before retrying.',
            );
          if (result.applied !== true)
            throw new Error(string(result.message) || 'The backend did not apply this operation.');
          applied++;
          if (change.route === 'track/rename')
            setSelected((current) =>
              current === change.body.track ? string(change.body.target) : current,
            );
          if (change.route === 'track/delete' || change.route === 'group/delete')
            setSelected((current) =>
              current === (change.body.track || change.body.group) ? '' : current,
            );
          setChanges((previous) => previous.filter((c) => c.key !== change.key));
        }
      });
      setOutcome(
        `${applied} changes applied by the host permission service. ${alreadyAbsent} original replacement assignments were already absent. Storage durability is not independently acknowledged by this API.`,
      );
      w.notify(
        `${applied} permission changes applied.${alreadyAbsent ? ` ${alreadyAbsent} original assignments were already absent.` : ''}`,
      );
    } catch (error) {
      const unknown =
        error instanceof ApiError &&
        (['connection_failed', 'request_timeout', 'invalid_response'].includes(error.code) ||
          error.status >= 500);
      const conflict = error instanceof ApiError && error.code === 'persistence_conflict';
      setUncertain(unknown || conflict);
      setOutcome(
        `${applied} of ${reviewed.length} changes acknowledged. ${conflict ? 'The last definition change was rejected because another server changed it. Reload server permissions and discard the remaining queue before reviewing again. No automatic retry.' : unknown ? 'The last outcome is unknown. Inspect current assignments and discard the remaining queue before starting a new change. No automatic retry.' : 'Remaining changes are still staged; remove or correct the failed operation before applying again.'} ${String(error)}`,
      );
      w.notify(`${applied} changes applied; processing stopped.`, true);
    } finally {
      setRevision((v) => v + 1);
    }
  }
  const applyRef = useRef(apply);
  applyRef.current = apply;
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        if (!document.querySelector('dialog[open]')) void applyRef.current();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  const flow = {
    editable,
    changes,
    stage,
    open: setModal,
    draft: setDraftDirty,
    draftDirty,
    refresh: () => setRevision((v) => v + 1),
  };
  const master = (
    <>
      {' '}
      <div class="toolbar">
        <input
          type="search"
          aria-label="Search permission targets"
          placeholder={view === 'users' ? 'Name or UUID…' : 'Find ' + view + '…'}
          value={search}
          onInput={(e) => {
            setSearch(e.currentTarget.value);
            setPage(1);
          }}
        />
        {view === 'users' && (
          <button
            aria-label="Open user by name or UUID"
            disabled={w.busy || !search.trim()}
            onClick={() => void select('users', search.trim(), search.trim())}
          >
            Open
          </button>
        )}
        {['groups', 'tracks'].includes(view) && (
          <button
            aria-label={view === 'groups' ? 'Create group' : 'Create track'}
            disabled={!editable}
            onClick={() => {
              setCreate(true);
              setModal(true);
              setName('');
              setError('');
            }}
          >
            +
          </button>
        )}
      </div>
      <AsyncBoundary
        resource={view === 'groups' ? groupsData : view === 'tracks' ? tracksData : directory}
      >
        {(raw) => {
          const data = object(raw);
          const rows = (
            view === 'groups'
              ? array(data.groups)
              : view === 'tracks'
                ? array(data.tracks)
                : array(data.users)
          )
            .map(object)
            .filter(
              (r) =>
                view === 'users' ||
                (string(r.name) + ' ' + string(r.description))
                  .toLowerCase()
                  .includes(search.toLowerCase()),
            );
          const current = view === 'users' ? rows : rows.slice((page - 1) * 50, page * 50);
          return (
            <>
              <div class="permission-target-list">
                {current.map((row) => {
                  const id = string(view === 'users' ? row.uuid : row.name);
                  return (
                    <button
                      key={id}
                      aria-current={selected === id ? 'page' : undefined}
                      onClick={() => void select(view, id, string(row.name))}
                    >
                      <span>{string(row.name) || id}</span>
                      <small>
                        {view === 'groups'
                          ? `${number(row.permissionCount)} permissions · ${array(row.parents).length} parents`
                          : view === 'tracks'
                            ? `${array(row.groups).length} ordered ranks`
                            : id}
                      </small>
                    </button>
                  );
                })}
                {!rows.length && <p class="muted">No matching {view}.</p>}
              </div>
              <Pager
                page={page}
                total={view === 'users' ? number(data.total) : rows.length}
                size={50}
                change={setPage}
              />
            </>
          );
        }}
      </AsyncBoundary>
    </>
  );
  return (
    <WorkflowContext.Provider value={flow}>
      <div class="permission-workspace">
        <div class="permission-workspace-heading">
          <div>
            <h2 class="sr-only">Permission workspace</h2>
            <span class="muted">
              {groups.length} groups · {tracks.length} tracks
            </span>
          </div>
          <div class="actions">
            <button
              disabled={w.busy || changes.length > 0 || draftDirty || modal}
              onClick={() => setRevision((v) => v + 1)}
            >
              Refresh
            </button>
            <button
              disabled={w.busy}
              onClick={() => {
                setMigration(true);
                setModal(true);
              }}
            >
              LuckPerms migration
            </button>
          </div>
        </div>
        <AsyncBoundary resource={summary}>
          {() => (
            <div class="permission-scope">
              <Badge>
                {meta.sharedStorage === true ? 'Shared network storage' : 'Host storage'}
              </Badge>
              <span>
                Definitions belong to <strong>{w.hostId}</strong>'s configured backend. New
                assignments default to{' '}
                <strong>{w.selected ? 'server: ' + w.selected : 'global'}</strong>.
              </span>
              {meta.internalEnabled !== true && (
                <span class="text-warning">
                  Internal permissions are disabled; management is read-only.
                </span>
              )}
              {w.session.capabilities?.mutations?.permissions !== true && (
                <span class="text-warning">You have read-only access.</span>
              )}
            </div>
          )}
        </AsyncBoundary>
        <Tabs
          label="Permission workspace views"
          values={[
            { id: 'groups', label: 'Groups' },
            { id: 'users', label: 'Users' },
            { id: 'tracks', label: 'Tracks' },
            { id: 'nodes', label: 'Permission node explorer' },
          ]}
          selected={view}
          onChange={(id) => void select(id as View)}
        />
        {view === 'nodes' ? (
          <NodeExplorer
            resource={directory}
            search={search}
            setSearch={(value) => {
              setSearch(value);
              setPage(1);
            }}
            page={page}
            setPage={setPage}
          />
        ) : (
          <div class="permission-master-detail">
            <aside class="permission-master" aria-label={`${view} directory`}>
              {master}
            </aside>
            <section class="permission-editor" aria-label="Selected permission target">
              {selected ? (
                view === 'tracks' ? (
                  <AsyncBoundary resource={tracksData}>
                    {() =>
                      tracks.some((track) => track.name === selected) ? (
                        <TrackEditor
                          key={`${selected}:${revision}`}
                          track={tracks.find((track) => track.name === selected)!}
                          groups={groups}
                        />
                      ) : (
                        <Empty title="Track no longer available">
                          Select another track from the directory.
                        </Empty>
                      )
                    }
                  </AsyncBoundary>
                ) : (
                  <TargetEditor
                    key={`${view}:${selected}`}
                    kind={view === 'groups' ? 'group' : 'user'}
                    target={selected}
                    label={label}
                    groups={groups}
                    tracks={tracks}
                    keys={keys}
                    revision={revision}
                    selectUser={(id, display) => void select('users', id, display)}
                  />
                )
              ) : (
                <Empty
                  title={`Choose a ${view === 'groups' ? 'group' : view === 'users' ? 'user' : 'track'}`}
                >
                  Select a target from the directory to open its editor.
                </Empty>
              )}
            </section>
          </div>
        )}
        {outcome && (
          <p class={`notice ${uncertain ? 'warning' : ''}`} role="status">
            {outcome}
          </p>
        )}
        {changes.length > 0 && (
          <section class="permission-review">
            <div class="permission-section-heading">
              <h3>
                Staged changes <Badge>{changes.length}</Badge>
              </h3>
              <div class="actions">
                <button disabled={w.busy} onClick={() => void discard()}>
                  Discard remaining changes
                </button>
                <button
                  class="primary"
                  disabled={!editable || uncertain || draftDirty}
                  onClick={() => void apply()}
                >
                  Review & apply ({changes.length})
                </button>
              </div>
            </div>
            <p class="help">
              Individual mutations, not an atomic save. Ctrl+S opens review. A replacement adds a
              new assignment before removing the original by ID.
            </p>
            <ol>
              {changes.map((change) => (
                <li key={change.key}>
                  <span>{change.label}</span>
                  <button
                    class="text-button danger"
                    aria-label={`Unstage ${change.label}`}
                    disabled={w.busy || uncertain}
                    onClick={() =>
                      setChanges(
                        changes.filter(
                          (c) =>
                            c.key !== change.key && (!change.bundle || c.bundle !== change.bundle),
                        ),
                      )
                    }
                  >
                    Unstage
                  </button>
                </li>
              ))}
            </ol>
          </section>
        )}
        {create && (
          <Popup
            title={view === 'groups' ? 'Create group' : 'Create track'}
            close={() => {
              setCreate(false);
              setModal(false);
            }}
          >
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const value = name.trim().toLowerCase();
                if (!subjectValid(value)) {
                  setError(
                    'Use letters, numbers, dots, underscores and hyphens; maximum 64 characters.',
                  );
                  return;
                }
                stage([
                  {
                    label: `Create ${view === 'groups' ? 'group' : 'track'} ${value}`,
                    route: view === 'groups' ? 'group/create' : 'track/create',
                    body: { [view === 'groups' ? 'group' : 'track']: value },
                  },
                ]);
                setCreate(false);
                setModal(false);
              }}
            >
              <label class="field">
                <span>Name</span>
                <input
                  autoFocus
                  required
                  value={name}
                  onInput={(e) => setName(e.currentTarget.value)}
                />
              </label>
              {error && (
                <p class="text-error" role="alert">
                  {error}
                </p>
              )}
              <button class="primary" disabled={!editable}>
                Stage creation
              </button>
            </form>
          </Popup>
        )}
        {migration && (
          <Migration
            close={() => {
              setMigration(false);
              setModal(false);
            }}
            editable={!w.busy && canManage}
          />
        )}
      </div>
    </WorkflowContext.Provider>
  );
}
function NodeExplorer({
  resource,
  search,
  setSearch,
  page,
  setPage,
}: {
  resource: ReturnType<typeof useResource<Json>>;
  search: string;
  setSearch: (v: string) => void;
  page: number;
  setPage: (v: number) => void;
}) {
  return (
    <section class="permission-detail">
      <div class="toolbar">
        <input
          type="search"
          aria-label="Search registered permission nodes"
          placeholder="Find registered / discovered nodes…"
          value={search}
          onInput={(e) => setSearch(e.currentTarget.value)}
        />
      </div>
      <p class="help">
        Nodes actually supplied by the registry. Being listed does not grant permission; custom
        nodes can be added from a group or user editor.
      </p>
      <AsyncBoundary resource={resource}>
        {(raw) => {
          const data = object(raw);
          return (
            <>
              <div
                class="table-wrap permission-table"
                tabIndex={0}
                role="region"
                aria-label="Permission node table"
              >
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Permission node</th>
                      <th scope="col">Description</th>
                      <th scope="col">Source</th>
                    </tr>
                  </thead>
                  <tbody>
                    {array(data.nodes)
                      .map(object)
                      .map((row) => (
                        <tr>
                          <td>
                            <code>{string(row.node)}</code>
                          </td>
                          <td>{string(row.description) || 'No description'}</td>
                          <td>{string(row.source)}</td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
              <Pager page={page} total={number(data.total)} size={50} change={setPage} />
            </>
          );
        }}
      </AsyncBoundary>
    </section>
  );
}
function Migration({ close, editable }: { close: () => void; editable: boolean }) {
  const w = useWorkspace();
  const flow = useWorkflow();
  const [direction, setDirection] = useState('import');
  const [mode, setMode] = useState('dry-run');
  const [report, setReport] = useState<Obj>();
  const [error, setError] = useState('');
  async function run() {
    if (!editable || w.busy) return;
    if (flow.changes.length || flow.draftDirty) {
      setError('Apply or discard staged changes and editor fields before migrating.');
      return;
    }
    if (
      mode !== 'dry-run' &&
      !(await w.confirm(
        `${direction} LuckPerms data using ${mode} mode? This changes the host backend's permission state.`,
        'Run migration',
      ))
    )
      return;
    try {
      const result = await w.mutate(() =>
        w.api.request<Json>('/api/permissions/migrate/luckperms', {
          direction,
          mode,
          confirmed: mode !== 'dry-run',
        }),
      );
      setReport(object(result));
      setError('');
      flow.refresh();
    } catch (error) {
      setError(String(error));
    }
  }
  return (
    <Popup title="LuckPerms migration" close={close}>
      <p class="help">
        Import/export through the existing migration service. Start with a dry run. Replace mode can
        remove existing definitions and assignments.
      </p>
      <fieldset disabled={!editable || w.busy}>
        <div class="field-grid">
          <label class="field">
            <span>Direction</span>
            <select value={direction} onChange={(e) => setDirection(e.currentTarget.value)}>
              <option value="import">LuckPerms → Paradigm</option>
              <option value="export">Paradigm → LuckPerms</option>
            </select>
          </label>
          <label class="field">
            <span>Mode</span>
            <select value={mode} onChange={(e) => setMode(e.currentTarget.value)}>
              <option value="dry-run">Dry run</option>
              <option value="merge">Merge</option>
              <option value="replace">Replace</option>
            </select>
          </label>
        </div>
        <button class="primary" onClick={() => void run()}>
          Run migration
        </button>
      </fieldset>
      {error && (
        <p class="text-error" role="alert">
          {error}
        </p>
      )}
      {report && (
        <div class="permission-migration-report">
          <h3>Migration report</h3>
          <div class="permission-stats">
            {Object.entries(report)
              .filter(([, v]) => typeof v === 'number' || typeof v === 'boolean')
              .map(([key, value]) => (
                <span>
                  {key}: <strong>{String(value)}</strong>
                </span>
              ))}
          </div>
          <ul>
            {array(report.details).map((value) => (
              <li>{string(value)}</li>
            ))}
          </ul>
        </div>
      )}
    </Popup>
  );
}
