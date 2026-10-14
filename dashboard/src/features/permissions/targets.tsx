import { useLayoutEffect, useState } from 'preact/hooks';
import { query } from '../../core/api';
import { AsyncBoundary, useResource, useWorkspace } from '../../core/context';
import { array, number, object, string, type Json, type Obj } from '../../core/types';
import { Badge, Empty, Pager, Tabs } from '../../components/ui';
import { FormatEditor, FormatPreview } from '../../components/formatting';
import { AssignmentEditor, AssignmentTable } from './assignments';
import { ContextChips, ContextEditor, ExpiryEditor, expiryBody } from './controls';
import {
  assignment,
  expiryLabel,
  inheritedAssignments,
  subjectValid,
  wouldCycle,
  type Assignment,
  type Group,
} from './model';
import { useWorkflow } from './workflow';
export function TargetEditor({
  kind,
  target,
  label,
  groups,
  tracks,
  keys,
  revision,
  selectUser,
}: {
  kind: 'group' | 'user';
  target: string;
  label: string;
  groups: Group[];
  tracks: Obj[];
  keys: string[];
  revision: number;
  selectUser: (id: string, label: string) => void;
}) {
  const w = useWorkspace();
  const flow = useWorkflow();
  const [section, setSection] = useState('permissions');
  const [editing, setEditing] = useState<{ membership: boolean; row?: Assignment }>();
  const detail = useResource<Json>(
    (signal) =>
      w.api.request(
        query(
          '/api/permissions/' + kind,
          kind === 'group' ? { id: target } : { uuidOrName: target },
        ),
        undefined,
        signal,
      ),
    `${kind}:${target}:${revision}`,
  );
  const popup = (value: typeof editing) => {
    setEditing(value);
    flow.open(!!value);
  };
  const nav = async (id: string) => {
    if (flow.draftDirty && !(await w.confirm('Discard unstaged editor fields?', 'Discard fields')))
      return;
    setSection(id);
  };
  return (
    <AsyncBoundary resource={detail}>
      {(raw) => {
        const data = object(raw);
        const row = object(data[kind]);
        if (!row || !Object.keys(row).length)
          return (
            <Empty title="Target not found">
              Reload the directory; this group or user may have been removed.
            </Empty>
          );
        const info = kind === 'user' ? object(row.info) : row;
        const assignments = array(info.assignments).map(assignment);
        const memberships = array(info.groupAssignments).map(assignment);
        const parents = array(row.parents).map(string);
        const id = kind === 'user' ? string(row.uuid) : string(row.name);
        const inherited =
          kind === 'group'
            ? inheritedAssignments(groups, {
                ...groups.find((g) => g.name === id)!,
                name: id,
                parents,
              })
            : [];
        const tabs =
          kind === 'group'
            ? ['permissions', 'inheritance', 'members', 'metadata']
            : ['permissions', 'groups', 'effective', 'tracks'];
        return (
          <section class="permission-detail">
            <header class="permission-detail-header">
              <div>
                <span class="eyebrow">{kind === 'group' ? 'Group' : 'User'}</span>
                <h2>{kind === 'group' ? id : label || id}</h2>
                <p class="muted">
                  {kind === 'group' ? string(row.description) || 'No description' : id}
                </p>
              </div>
              <div class="permission-stats">
                <span>
                  <strong>{assignments.length}</strong> direct permissions
                </span>
                <span>
                  <strong>{kind === 'group' ? parents.length : memberships.length}</strong>{' '}
                  {kind === 'group' ? 'parents' : 'memberships'}
                </span>
                {kind === 'group' && (
                  <span>
                    <strong>{number(row.weight)}</strong> weight
                  </span>
                )}
              </div>
              {kind === 'group' && (
                <button
                  class="danger"
                  disabled={!flow.editable}
                  onClick={() =>
                    flow.stage([
                      {
                        label: `Delete group ${id} (also removes memberships, inheritance links and track entries)`,
                        route: 'group/delete',
                        body: { group: id },
                      },
                    ])
                  }
                >
                  Delete group
                </button>
              )}
            </header>
            <Tabs
              label={`${kind} editor sections`}
              values={tabs.map((value) => ({
                id: value,
                label:
                  value === 'permissions'
                    ? 'Permissions'
                    : value === 'groups'
                      ? 'Groups'
                      : value === 'effective'
                        ? 'Effective permissions'
                        : value[0].toUpperCase() + value.slice(1),
              }))}
              selected={section}
              onChange={(value) => void nav(value)}
            />
            {section === 'permissions' && (
              <>
                <div class="permission-section-heading">
                  <h3>Direct permission assignments</h3>
                  <button
                    class="primary"
                    disabled={!flow.editable}
                    onClick={() => popup({ membership: false })}
                  >
                    Add permission
                  </button>
                </div>
                <AssignmentTable
                  key={`direct:${id}:${revision}`}
                  rows={assignments}
                  kind={kind}
                  target={id}
                  edit={(value) => popup({ membership: false, row: value })}
                />
              </>
            )}
            {section === 'inheritance' && (
              <Inheritance id={id} parents={parents} groups={groups} rows={inherited} />
            )}
            {section === 'members' && (
              <>
                <p class="help">
                  Direct memberships reported by the storage backend. Implicit default membership
                  and inherited membership are not counted. Showing {array(row.members).length} of{' '}
                  {number(row.membershipCount)} assignments.
                </p>
                <Members rows={array(row.members).map(object)} select={selectUser} />
              </>
            )}
            {section === 'metadata' && <Metadata key={id} initial={row} id={id} />}
            {section === 'groups' && (
              <>
                <div class="permission-section-heading">
                  <h3>Direct group memberships</h3>
                  <button
                    class="primary"
                    disabled={!flow.editable}
                    onClick={() => popup({ membership: true })}
                  >
                    Assign group
                  </button>
                </div>
                <AssignmentTable
                  key={`membership:${id}:${revision}`}
                  rows={memberships}
                  kind="user"
                  target={id}
                  membership
                  edit={(value) => popup({ membership: true, row: value })}
                />
                <h3>Implicit / inherited groups</h3>
                <div class="permission-contexts">
                  {array(info.implicitGroups).map((value) => (
                    <Badge>{string(value)}</Badge>
                  ))}
                </div>
                <p class="help">
                  These are read-only resolver metadata, not direct assignments. Effective
                  inspection explains matching rules where available.
                </p>
              </>
            )}
            {section === 'effective' && <Effective user={id} revision={revision} />}
            {section === 'tracks' && <UserTracks user={id} tracks={tracks} keys={keys} />}
            {editing && (
              <AssignmentEditor
                kind={kind}
                target={id}
                keys={keys}
                groups={groups}
                membership={editing.membership}
                initial={editing.row}
                existing={editing.membership ? memberships : assignments}
                close={() => popup(undefined)}
              />
            )}
          </section>
        );
      }}
    </AsyncBoundary>
  );
}
function Inheritance({
  id,
  parents,
  groups,
  rows,
}: {
  id: string;
  parents: string[];
  groups: Group[];
  rows: Assignment[];
}) {
  const flow = useWorkflow();
  const [parent, setParent] = useState('');
  useLayoutEffect(() => {
    flow.draft(!!parent);
    return () => flow.draft(false);
  }, [parent]);
  const eligible = groups.filter(
    (g) => !parents.includes(g.name) && !wouldCycle(groups, id, g.name),
  );
  return (
    <>
      <div class="permission-section-heading">
        <h3>Parent groups</h3>
        <div class="actions">
          <select
            aria-label="Parent group"
            value={parent}
            disabled={!flow.editable}
            onChange={(e) => setParent(e.currentTarget.value)}
          >
            <option value="">Choose parent…</option>
            {eligible.map((g) => (
              <option>{g.name}</option>
            ))}
          </select>
          <button
            disabled={!flow.editable || !parent || !eligible.some((g) => g.name === parent)}
            onClick={() => {
              flow.stage([
                {
                  label: `Add parent ${parent} to ${id}`,
                  route: 'group/parent/add',
                  body: { group: id, parent },
                },
              ]);
              setParent('');
            }}
          >
            Add parent
          </button>
        </div>
      </div>
      <div class="permission-parent-list">
        {parents.map((value) => (
          <div>
            <span>
              <strong>{id}</strong> inherits <strong>{value}</strong>
            </span>
            <button
              class="danger"
              disabled={!flow.editable}
              onClick={() =>
                flow.stage([
                  {
                    label: `Remove parent ${value} from ${id}`,
                    route: 'group/parent/remove',
                    body: { group: id, parent: value },
                  },
                ])
              }
            >
              Remove parent
            </button>
          </div>
        ))}
      </div>
      {!parents.length && <p class="muted">No parent groups.</p>}
      <h3>Assignments from parent groups</h3>
      <p class="help">
        These are inheritance candidates, not a resolved allow/deny result. Contexts, expiration,
        precedence and wildcards still apply. Cycles are excluded here and validated by the backend.
      </p>
      <AssignmentTable rows={rows} kind="group" target={id} inherited edit={() => {}} />
    </>
  );
}
function Members({ rows, select }: { rows: Obj[]; select: (id: string, label: string) => void }) {
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const matches = rows.filter((r) =>
    (string(r.name) + ' ' + string(r.uuid)).toLowerCase().includes(search.toLowerCase()),
  );
  return (
    <>
      <input
        type="search"
        aria-label="Search group members"
        placeholder="Search members…"
        value={search}
        onInput={(e) => {
          setSearch(e.currentTarget.value);
          setPage(1);
        }}
      />
      <div
        class="table-wrap permission-table"
        tabIndex={0}
        role="region"
        aria-label="Permission assignment table"
      >
        <table>
          <thead>
            <tr>
              <th scope="col">User</th>
              <th scope="col">Context</th>
              <th scope="col">Expiration</th>
            </tr>
          </thead>
          <tbody>
            {matches.slice((page - 1) * 50, page * 50).map((r) => (
              <tr>
                <td>
                  <button
                    class="text-button"
                    onClick={() => select(string(r.uuid), string(r.name) || string(r.uuid))}
                  >
                    {string(r.name) || string(r.uuid)}
                  </button>
                  <small class="code muted">{string(r.uuid)}</small>
                </td>
                <td>
                  <ContextChips
                    value={Object.fromEntries(
                      Object.entries(object(r.contexts)).map(([k, v]) => [k, string(v)]),
                    )}
                  />
                </td>
                <td>{expiryLabel(typeof r.expiresAtMs === 'number' ? r.expiresAtMs : null)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Pager page={page} total={matches.length} size={50} change={setPage} />
    </>
  );
}
function Metadata({ id, initial }: { id: string; initial: Obj }) {
  const flow = useWorkflow();
  const initialValues = {
    description: string(initial.description),
    prefix: string(initial.prefix),
    suffix: string(initial.suffix),
    weight: String(number(initial.weight)),
  };
  const [baseline, setBaseline] = useState(initialValues);
  const [values, setValues] = useState(initialValues);
  const [error, setError] = useState('');
  const dirty = JSON.stringify(values) !== JSON.stringify(baseline);
  useLayoutEffect(() => {
    flow.draft(dirty);
    return () => flow.draft(false);
  }, [dirty]);
  const change = (key: keyof typeof values, value: string) =>
    setValues({ ...values, [key]: value });
  function stage(e: Event) {
    e.preventDefault();
    if (!flow.editable) return;
    const weight = Number(values.weight);
    if (
      !values.weight.trim() ||
      !Number.isInteger(weight) ||
      weight < -2147483648 ||
      weight > 2147483647
    ) {
      setError('Weight must be a 32-bit whole number.');
      return;
    }
    if (Object.values(values).some((v) => v.length > 512)) {
      setError('Metadata values are limited to 512 characters.');
      return;
    }
    flow.stage(
      Object.entries(values)
        .filter(([k, v]) => baseline[k as keyof typeof baseline] !== v)
        .map(([key, value]) => ({
          label: `Set ${id} ${key}`,
          route: 'group/update',
          body: { group: id, metadata: { [key]: value } },
        })),
    );
    setBaseline(values);
    setError('');
  }
  return (
    <form
      class="permission-metadata-form"
      onSubmit={stage}
      onKeyDown={(e) => {
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
          e.preventDefault();
          e.stopPropagation();
          stage(e);
        }
      }}
    >
      <fieldset disabled={!flow.editable}>
        <div class="field-grid">
          <label class="field">
            <span>Description</span>
            <input
              maxLength={512}
              value={values.description}
              onInput={(e) => change('description', e.currentTarget.value)}
            />
          </label>
          <label class="field">
            <span>Group weight</span>
            <input
              type="number"
              step={1}
              min={-2147483648}
              max={2147483647}
              value={values.weight}
              onInput={(e) => change('weight', e.currentTarget.value)}
            />
          </label>
        </div>
        <div class="permission-format-fields">
          <FormatEditor
            label="Prefix (raw formatting)"
            value={values.prefix}
            change={(v) => change('prefix', v)}
          />
          <FormatEditor
            label="Suffix (raw formatting)"
            value={values.suffix}
            change={(v) => change('suffix', v)}
          />
        </div>
        <h3>Combined formatting preview</h3>
        <FormatPreview text={`${values.prefix}Player${values.suffix}`} />
        <p class="help">
          Preview does not resolve metadata precedence or render every Minecraft format. Values
          retain their native storage format.
        </p>
        {error && (
          <p role="alert" class="text-error">
            {error}
          </p>
        )}
        <div class="actions">
          <button type="button" disabled={!dirty} onClick={() => setValues(baseline)}>
            Discard fields
          </button>
          <button class="primary" disabled={!dirty}>
            Stage metadata changes
          </button>
        </div>
      </fieldset>
    </form>
  );
}
function Effective({ user, revision }: { user: string; revision: number }) {
  const w = useWorkspace();
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const result = useResource<Json>(
    (signal) =>
      w.api.request(
        query('/api/permissions/effective', {
          uuidOrName: user,
          query: search,
          page,
          pageSize: 50,
        }),
        undefined,
        signal,
      ),
    `${user}:${search}:${page}:${revision}`,
  );
  return (
    <>
      <p class="scope-note">
        Evaluated by the host's permission resolver in its runtime context ({w.hostId}). Selecting
        another server does not simulate its world, dimension or external providers. Only registry
        nodes and explanation fields supplied by the resolver are shown.
      </p>
      <input
        type="search"
        aria-label="Search effective permissions"
        placeholder="Search effective nodes…"
        value={search}
        onInput={(e) => {
          setSearch(e.currentTarget.value);
          setPage(1);
        }}
      />
      <AsyncBoundary resource={result}>
        {(raw) => {
          const data = object(raw);
          return (
            <>
              <div
                class="table-wrap permission-table"
                tabIndex={0}
                role="region"
                aria-label="Permission assignment table"
              >
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Permission</th>
                      <th scope="col">Result</th>
                      <th scope="col">Source</th>
                      <th scope="col">Matching rule</th>
                    </tr>
                  </thead>
                  <tbody>
                    {array(data.entries)
                      .map(object)
                      .map((r) => (
                        <tr>
                          <td>
                            <code>{string(r.node)}</code>
                          </td>
                          <td>
                            <Badge
                              tone={
                                r.allowed === true
                                  ? 'success'
                                  : r.allowed === false
                                    ? 'danger'
                                    : 'neutral'
                              }
                            >
                              {r.allowed === true
                                ? 'Allowed'
                                : r.allowed === false
                                  ? 'Denied'
                                  : 'Unset / unresolved'}
                            </Badge>
                          </td>
                          <td>
                            {string(r.sourceType) || 'Unavailable'}
                            {r.sourceName && <small>{string(r.sourceName)}</small>}
                            <small class="muted">
                              {array(r.groups).length
                                ? `Groups checked: ${array(r.groups).map(string).join(', ')}`
                                : ''}
                            </small>
                          </td>
                          <td>
                            <code>{string(r.rule) || 'No matching rule reported'}</code>
                          </td>
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
    </>
  );
}
function UserTracks({ user, tracks, keys }: { user: string; tracks: Obj[]; keys: string[] }) {
  const w = useWorkspace();
  const flow = useWorkflow();
  const [track, setTrack] = useState('');
  const [action, setAction] = useState('promote');
  const [rank, setRank] = useState('');
  const [flag, setFlag] = useState(false);
  const [expiry, setExpiry] = useState(false);
  const [mode, setMode] = useState('permanent');
  const [value, setValue] = useState('');
  const [contexts, setContexts] = useState<Record<string, string>>(
    w.selected ? { server: w.selected } : {},
  );
  const [error, setError] = useState('');
  const ranks = array(tracks.find((t) => t.name === track)?.groups).map(string);
  const dirty =
    !!track ||
    action !== 'promote' ||
    !!rank ||
    flag ||
    expiry ||
    !!value ||
    JSON.stringify(contexts) !== JSON.stringify(w.selected ? { server: w.selected } : {});
  useLayoutEffect(() => {
    flow.draft(dirty);
    return () => flow.draft(false);
  }, [dirty]);
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        try {
          if (
            !track ||
            !tracks.some((t) => t.name === track) ||
            (action === 'settrack' && !ranks.includes(rank))
          )
            throw new Error('Choose an existing track and a valid rank.');
          flow.stage([
            {
              label: `${action} ${user} on ${track}`,
              route: `user/track/${action}`,
              body: {
                user,
                track,
                scope: 'custom',
                contexts,
                ...(action === 'settrack' ? { group: rank } : {}),
                ...(flag && action === 'promote'
                  ? { dontAddToFirst: true }
                  : flag && action === 'demote'
                    ? { dontRemoveFromFirst: true }
                    : {}),
                ...(expiry && ['promote', 'settrack'].includes(action)
                  ? expiryBody(mode, value)
                  : {}),
              },
            },
          ]);
          setError('');
          setTrack('');
          setAction('promote');
          setRank('');
          setFlag(false);
          setExpiry(false);
          setValue('');
          setContexts(w.selected ? { server: w.selected } : {});
        } catch (error) {
          setError(String(error));
        }
      }}
    >
      <fieldset disabled={!flow.editable}>
        <div class="field-grid">
          <label class="field">
            <span>Track</span>
            <select
              value={track}
              onChange={(e) => {
                setTrack(e.currentTarget.value);
                setRank('');
              }}
            >
              <option value="">Choose track…</option>
              {tracks.map((t) => (
                <option>{string(t.name)}</option>
              ))}
            </select>
          </label>
          <label class="field">
            <span>Rank action</span>
            <select
              value={action}
              onChange={(e) => {
                setAction(e.currentTarget.value);
                setFlag(false);
              }}
            >
              <option value="promote">Promote</option>
              <option value="demote">Demote</option>
              <option value="settrack">Set rank</option>
              <option value="cleartrack">Clear track memberships</option>
            </select>
          </label>
          {action === 'settrack' && (
            <label class="field">
              <span>Rank</span>
              <select value={rank} onChange={(e) => setRank(e.currentTarget.value)}>
                <option value="">Choose rank…</option>
                {ranks.map((r) => (
                  <option>{r}</option>
                ))}
              </select>
            </label>
          )}
        </div>
        <p class="permission-track-path">{ranks.join(' → ') || 'No ranks in selected track'}</p>
        {['promote', 'demote'].includes(action) && (
          <label class="field">
            <span>
              {action === 'promote'
                ? 'Do not add an unranked user to the first rank'
                : 'Keep the first rank when demoting'}
            </span>
            <input
              type="checkbox"
              checked={flag}
              onChange={(e) => setFlag(e.currentTarget.checked)}
            />
          </label>
        )}
        <ContextEditor value={contexts} change={setContexts} keys={keys} />
        {['promote', 'settrack'].includes(action) && (
          <>
            <label class="field">
              <span>Change rank expiration (otherwise preserve existing expiry)</span>
              <input
                type="checkbox"
                checked={expiry}
                onChange={(e) => setExpiry(e.currentTarget.checked)}
              />
            </label>
            {expiry && (
              <ExpiryEditor mode={mode} setMode={setMode} value={value} setValue={setValue} />
            )}
          </>
        )}
        {error && (
          <p role="alert" class="text-error">
            {error}
          </p>
        )}
        <button class="primary">Stage rank change</button>
      </fieldset>
    </form>
  );
}
