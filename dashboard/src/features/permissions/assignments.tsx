import { useState } from 'preact/hooks';
import { query } from '../../core/api';
import { useResource, useWorkspace } from '../../core/context';
import { array, object, string, type Json, type Obj } from '../../core/types';
import { Badge, Empty, Pager } from '../../components/ui';
import { ContextChips, ContextEditor, ExpiryEditor, Popup, expiryBody } from './controls';
import {
  assignmentEqual,
  expiryLabel,
  nodeValid,
  removal,
  subjectValid,
  type Assignment,
  type Group,
} from './model';
import { useWorkflow } from './workflow';
export function AssignmentTable({
  rows,
  kind,
  target,
  membership = false,
  inherited = false,
  edit,
}: {
  rows: Assignment[];
  kind: 'group' | 'user';
  target: string;
  membership?: boolean;
  inherited?: boolean;
  edit: (row: Assignment) => void;
}) {
  const flow = useWorkflow();
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('all');
  const [sort, setSort] = useState('node');
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<string[]>([]);
  const pending = new Set(flow.changes.map((c) => c.assignmentId).filter(Boolean));
  const filtered = rows
    .filter(
      (row) =>
        (row.node + ' ' + JSON.stringify(row.contexts) + ' ' + (row.source || ''))
          .toLowerCase()
          .includes(search.toLowerCase()) &&
        (filter === 'all' ||
          (filter === 'denied' && row.denied) ||
          (filter === 'allowed' && !row.denied) ||
          (filter === 'temporary' && row.expiresAtMs !== null) ||
          (filter === 'global' && !Object.keys(row.contexts).length)),
    )
    .sort((a, b) =>
      sort === 'expiry'
        ? (a.expiresAtMs ?? Infinity) - (b.expiresAtMs ?? Infinity)
        : sort === 'context'
          ? JSON.stringify(a.contexts).localeCompare(JSON.stringify(b.contexts))
          : a.node.localeCompare(b.node),
    );
  const visible = filtered.slice((page - 1) * 50, page * 50);
  function removeRows(values: Assignment[]) {
    try {
      flow.stage(
        values
          .filter((row) => !pending.has(row.id))
          .map((row) => removal(kind, target, row, membership)),
      );
      setSelected([]);
    } catch (error) {
      w.notify(String(error), true);
    }
  }
  const w = useWorkspace();
  return (
    <>
      <div class="toolbar permission-table-toolbar">
        <input
          type="search"
          aria-label="Search assignments"
          placeholder={membership ? 'Search group memberships…' : 'Search permission assignments…'}
          value={search}
          onInput={(e) => {
            setSearch(e.currentTarget.value);
            setPage(1);
          }}
        />
        <select
          aria-label="Filter assignments"
          value={filter}
          onChange={(e) => {
            setFilter(e.currentTarget.value);
            setPage(1);
          }}
        >
          <option value="all">All assignments</option>
          {!membership && (
            <>
              <option value="allowed">Allowed</option>
              <option value="denied">Denied</option>
            </>
          )}
          <option value="temporary">Temporary</option>
          <option value="global">Global</option>
        </select>
        <select
          aria-label="Sort assignments"
          value={sort}
          onChange={(e) => setSort(e.currentTarget.value)}
        >
          <option value="node">Sort by node</option>
          <option value="context">Sort by context</option>
          <option value="expiry">Sort by expiration</option>
        </select>
        {!inherited && selected.length > 0 && (
          <button
            class="danger"
            disabled={!flow.editable}
            onClick={() => removeRows(rows.filter((r) => selected.includes(r.id)))}
          >
            Stage removal ({selected.length})
          </button>
        )}
      </div>
      <div
        class="table-wrap permission-table"
        tabIndex={0}
        role="region"
        aria-label="Permission assignment table"
      >
        <table class="permission-assignment-grid">
          <colgroup>
            {!inherited && <col class="permission-selection-column" />}
            <col />
            <col class={inherited ? 'permission-source-column' : 'permission-value-column'} />
            <col class="permission-context-column" />
            <col class="permission-expiry-column" />
            {!inherited && <col class="permission-actions-column" />}
          </colgroup>
          <thead>
            <tr>
              {!inherited && (
                <th scope="col">
                  <input
                    type="checkbox"
                    aria-label="Select visible assignments"
                    disabled={!flow.editable || !visible.some((r) => r.id && !pending.has(r.id))}
                    checked={
                      visible.filter((r) => r.id && !pending.has(r.id)).length > 0 &&
                      visible
                        .filter((r) => r.id && !pending.has(r.id))
                        .every((r) => selected.includes(r.id))
                    }
                    onChange={(e) =>
                      setSelected(
                        e.currentTarget.checked
                          ? [
                              ...new Set([
                                ...selected,
                                ...visible
                                  .filter((r) => r.id && !pending.has(r.id))
                                  .map((r) => r.id),
                              ]),
                            ]
                          : selected.filter((id) => !visible.some((r) => r.id === id)),
                      )
                    }
                  />
                </th>
              )}
              <th scope="col">{membership ? 'Group' : 'Permission'}</th>
              <th scope="col">{inherited ? 'Assignment / source' : 'Value'}</th>
              <th scope="col">Context</th>
              <th scope="col">Expiration</th>
              {!inherited && <th scope="col">Actions</th>}
            </tr>
          </thead>
          <tbody>
            {visible.map((row) => (
              <tr key={row.id || row.node} class={pending.has(row.id) ? 'permission-pending' : ''}>
                {!inherited && (
                  <td>
                    <input
                      type="checkbox"
                      aria-label={`Select ${row.node} ${row.id}`}
                      checked={selected.includes(row.id)}
                      disabled={!flow.editable || !row.id || pending.has(row.id)}
                      onChange={(e) =>
                        setSelected(
                          e.currentTarget.checked
                            ? [...selected, row.id]
                            : selected.filter((id) => id !== row.id),
                        )
                      }
                    />
                  </td>
                )}
                <td>
                  <code class="permission-node">{row.node}</code>
                  {pending.has(row.id) && <small class="text-warning">Change staged</small>}
                </td>
                <td>
                  <Badge
                    tone={
                      inherited || (row.expiresAtMs !== null && row.expiresAtMs <= Date.now())
                        ? 'neutral'
                        : row.denied
                          ? 'danger'
                          : 'success'
                    }
                  >
                    {membership ? 'Member' : row.denied ? 'Denied' : 'Allowed'}
                  </Badge>
                  {inherited && <small class="muted">From {row.source}</small>}
                </td>
                <td>
                  <ContextChips value={row.contexts} />
                </td>
                <td class="permission-expiry">{expiryLabel(row.expiresAtMs)}</td>
                {!inherited && (
                  <td class="permission-actions">
                    <div class="actions">
                      <button
                        disabled={!flow.editable || pending.has(row.id) || !row.id}
                        onClick={() => edit(row)}
                      >
                        Edit
                      </button>
                      <button
                        class="danger"
                        aria-label={`Remove ${row.node} ${row.id}`}
                        disabled={!flow.editable || pending.has(row.id) || !row.id}
                        onClick={() => removeRows([row])}
                      >
                        Remove
                      </button>
                    </div>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!filtered.length && (
        <Empty title="No matching assignments">Adjust the filters or add an assignment.</Empty>
      )}
      <Pager page={page} total={filtered.length} size={50} change={setPage} />
    </>
  );
}
export function AssignmentEditor({
  kind,
  target,
  keys,
  groups,
  membership = false,
  initial,
  existing,
  close,
}: {
  kind: 'group' | 'user';
  target: string;
  keys: string[];
  groups: Group[];
  membership?: boolean;
  initial?: Assignment;
  existing: Assignment[];
  close: () => void;
}) {
  const w = useWorkspace();
  const flow = useWorkflow();
  const [input, setInput] = useState('');
  const [nodes, setNodes] = useState<string[]>(initial ? [initial.node] : []);
  const [denied, setDenied] = useState(initial?.denied || false);
  const [contexts, setContexts] = useState<Record<string, string>>(
    initial?.contexts || (w.selected ? { server: w.selected } : {}),
  );
  const [mode, setMode] = useState(initial?.expiresAtMs != null ? 'date' : 'permanent');
  const initialExpiration =
    initial?.expiresAtMs != null
      ? new Date(initial.expiresAtMs - new Date(initial.expiresAtMs).getTimezoneOffset() * 60000)
          .toISOString()
          .slice(0, 16)
      : '';
  const [expiration, setExpiration] = useState(initialExpiration);
  const [error, setError] = useState('');
  const [active, setActive] = useState(-1);
  const [suggestionsOpen, setSuggestionsOpen] = useState(false);
  const suggestions = useResource<Json>(
    (signal) =>
      !membership && input.trim()
        ? w.api.request(
            query('/api/permissions/nodes', { query: input.trim(), pageSize: 10 }),
            undefined,
            signal,
          )
        : Promise.resolve({ nodes: [] }),
    input,
  );
  const choices = membership
    ? groups
        .filter((g) => g.name.includes(input.toLowerCase()))
        .slice(0, 10)
        .map((g) => ({ node: g.name, description: g.description }))
    : array(object(suggestions.data).nodes).map(object);
  function add(value = input) {
    const candidates = value
      .trim()
      .toLowerCase()
      .split(/[\s,]+/)
      .filter(Boolean);
    if (!candidates.length) return;
    if (candidates.some((n) => (membership ? !subjectValid(n) : !nodeValid(n)))) {
      setError(
        'Use valid node names (letters, numbers, dots, underscores, colons, hyphens and wildcards).',
      );
      return;
    }
    if (initial && candidates.length !== 1) {
      setError('Edit one assignment at a time.');
      return;
    }
    setNodes(initial ? candidates : [...new Set([...nodes, ...candidates])]);
    setInput('');
    setError('');
    setActive(-1);
    setSuggestionsOpen(false);
  }
  function stage(e: Event) {
    e.preventDefault();
    try {
      if (!flow.editable) throw new Error('Permission editing is unavailable.');
      const typed = input
        .trim()
        .toLowerCase()
        .split(/[\s,]+/)
        .filter(Boolean);
      const candidates = typed.length
        ? initial
          ? typed
          : [...new Set([...nodes, ...typed])]
        : nodes;
      if (
        !candidates.length ||
        candidates.some((n) => (membership ? !subjectValid(n) : !nodeValid(n)))
      )
        throw new Error('Choose at least one valid node.');
      if (initial && candidates.length !== 1)
        throw new Error('Choose exactly one node for this replacement.');
      const expiry =
        initial &&
        initial.expiresAtMs !== null &&
        mode === 'date' &&
        expiration === initialExpiration
          ? { expiresAtMs: initial.expiresAtMs }
          : expiryBody(mode, expiration);
      if ('expiresAtMs' in expiry && Number(expiry.expiresAtMs) <= Date.now())
        throw new Error('Choose a future expiration.');
      const changes = candidates.flatMap((node) => {
        const value = {
          node,
          denied,
          contexts: Object.fromEntries(Object.entries(contexts).map(([k, v]) => [k, v.trim()])),
          expiresAtMs: 'expiresAtMs' in expiry ? (expiry.expiresAtMs as number) : null,
        };
        if (
          initial &&
          mode !== 'duration' &&
          assignmentEqual(initial, { ...value, id: initial.id })
        )
          throw new Error('This assignment has no changes.');
        const duplicate =
          mode !== 'duration' &&
          existing.some((a) => a.id !== initial?.id && assignmentEqual(a, { ...value, id: '' }));
        if (duplicate && !initial)
          throw new Error(`${node} already has this value, context and expiration.`);
        const body: Obj = {
          [kind]: target,
          [membership ? 'group' : 'permission']: node,
          scope: 'custom',
          contexts: value.contexts,
          ...expiry,
          ...(!membership ? { denied } : {}),
        };
        return [
          ...(!duplicate
            ? [
                {
                  label: `${initial ? 'Replacement' : 'Add'} ${node} · ${membership ? 'member' : denied ? 'deny' : 'allow'}`,
                  route: `${kind}/${membership ? 'group' : 'permission'}/add`,
                  body,
                },
              ]
            : []),
          ...(initial ? [removal(kind, target, initial, membership)] : []),
        ].map((change) => (initial ? { ...change, bundle: initial.id } : change));
      });
      if (!flow.stage(changes)) return;
      close();
    } catch (error) {
      setError(String(error instanceof Error ? error.message : error));
    }
  }
  return (
    <Popup
      title={initial ? 'Edit assignment' : membership ? 'Assign groups' : 'Add permissions'}
      close={close}
    >
      <form
        onSubmit={stage}
        onKeyDown={(e) => {
          if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
            e.preventDefault();
            stage(e);
          }
        }}
      >
        <p class="help">
          Target: <strong>{target}</strong>.{' '}
          {initial
            ? 'Edits add the replacement, then remove the original by ID. These are separate operations; the replacement receives a new ID.'
            : 'Choose multiple nodes or paste a list. Suggestions come from the actual registry; custom nodes are allowed.'}
        </p>
        {initial && (
          <details>
            <summary>Assignment identity</summary>
            <code>{initial.id}</code>
            <p class="help">Original expiration: {initial.expiresAtMs ?? 'permanent'}</p>
          </details>
        )}
        <div class="permission-node-picker">
          <label class="field">
            <span>{membership ? 'Group names' : 'Permission nodes'}</span>
            <input
              autoFocus
              value={input}
              role="combobox"
              aria-autocomplete="list"
              aria-expanded={suggestionsOpen && choices.length > 0}
              aria-controls={
                suggestionsOpen && choices.length ? 'permission-suggestions' : undefined
              }
              aria-activedescendant={
                suggestionsOpen && active >= 0 && active < choices.length
                  ? `permission-suggestion-${active}`
                  : undefined
              }
              onFocus={() => setSuggestionsOpen(true)}
              placeholder={initial ? 'Choose replacement node' : 'Type a node or paste several…'}
              onInput={(e) => {
                setInput(e.currentTarget.value);
                setActive(-1);
                setSuggestionsOpen(true);
              }}
              onKeyDown={(e) => {
                if (e.key === 'ArrowDown') {
                  e.preventDefault();
                  setSuggestionsOpen(true);
                  setActive(Math.min(active + 1, choices.length - 1));
                }
                if (e.key === 'ArrowUp') {
                  e.preventDefault();
                  setActive(Math.max(0, active - 1));
                }
                if (e.key === 'Escape') {
                  e.preventDefault();
                  e.stopPropagation();
                  setSuggestionsOpen(false);
                }
                if (e.key === 'Enter') {
                  e.preventDefault();
                  add(active >= 0 ? string(choices[active]?.node) : input);
                }
              }}
            />
          </label>
          {suggestionsOpen && choices.length > 0 && (
            <div
              id="permission-suggestions"
              class="permission-suggestions"
              role="listbox"
              aria-label="Node suggestions"
            >
              {choices.map((choice, index) => (
                <button
                  type="button"
                  id={`permission-suggestion-${index}`}
                  role="option"
                  aria-selected={index === active}
                  onClick={() => add(string(choice.node))}
                >
                  <code>{string(choice.node)}</code>
                  <small>{string(choice.description)}</small>
                </button>
              ))}
            </div>
          )}
          {!membership && input && suggestions.loading && (
            <p class="help" role="status">
              Searching registered nodes…
            </p>
          )}
          {suggestions.error && (
            <p class="text-warning">Suggestions unavailable; custom nodes can still be entered.</p>
          )}
        </div>
        <div class="actions">
          <button type="button" onClick={() => add()}>
            Add to selection
          </button>
          {nodes.map((node) => (
            <button
              type="button"
              class="permission-node-chip"
              aria-label={`Unselect ${node}`}
              onClick={() => setNodes(nodes.filter((n) => n !== node))}
            >
              <code>{node}</code> ×
            </button>
          ))}
        </div>
        {!membership && (
          <label class="field">
            <span>Value</span>
            <select
              value={denied ? 'deny' : 'allow'}
              onChange={(e) => setDenied(e.currentTarget.value === 'deny')}
            >
              <option value="allow">Allow</option>
              <option value="deny">Deny</option>
            </select>
          </label>
        )}
        <ContextEditor value={contexts} change={setContexts} keys={keys} />
        <ExpiryEditor mode={mode} setMode={setMode} value={expiration} setValue={setExpiration} />
        {error && (
          <p class="text-error" role="alert">
            {error}
          </p>
        )}
        <div class="actions">
          <button type="button" onClick={close}>
            Cancel
          </button>
          <button class="primary" disabled={!flow.editable}>
            Stage {initial ? 'replacement' : 'assignments'}
          </button>
        </div>
      </form>
    </Popup>
  );
}
