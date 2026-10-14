import { useEffect, useState } from 'preact/hooks';
import { ApiError, query } from '../core/api';
import { AsyncBoundary, useDirty, useResource, useWorkspace } from '../core/context';
import {
  applicationState,
  configurationTarget,
  remainingEdits,
  validateField,
} from '../core/model';
import {
  ALL,
  type Field,
  type Json,
  type RemoteSnapshot,
  type Scope,
  type Snapshot,
  type Validation,
} from '../core/types';
import type { Page } from '../core/navigation';
import { Badge, Card, Empty, SaveBar, Pager } from '../components/ui';
import { SettingControl } from '../components/settings';
export function Configuration({ page }: { page: Page }) {
  const w = useWorkspace();
  const [scope, setScope] = useState<Scope>(
    w.selected === ALL ? 'NETWORK' : w.selected === w.hostId ? 'LOCAL' : 'SERVER',
  );
  const [section, setSection] = useState(page.categories?.[0] || '');
  const remote = scope !== 'LOCAL';
  const target = configurationTarget(w.selected, w.hostId, scope);
  const resource = useResource<Snapshot | RemoteSnapshot>(async (signal) => {
    if (remote) {
      const response = await w.api.request<RemoteSnapshot>(
        query('/api/remote-config/snapshot', { serverId: target.serverId, categories: section }),
        undefined,
        signal,
      );
      if (response.serverId !== target.serverId)
        throw new ApiError(
          409,
          'wrong_server_response',
          'The response did not match the selected server. Refresh its context before editing.',
        );
      return response;
    }
    const local = await w.api.request<Snapshot>('/api/config/snapshot', undefined, signal);
    if (w.networkActive) {
      const managed = await w.api.request<RemoteSnapshot>(
        query('/api/remote-config/snapshot', { serverId: w.hostId, categories: section }),
        undefined,
        signal,
      );
      local.managedSections = managed.sections.filter((s) => s.adopted).map((s) => s.section);
    }
    return local;
  }, `${w.selected}:${scope}:${section}`);
  const placeholders = useResource<{ tokens: string[] }>(
    (signal) => w.api.request('/api/config/formatting', undefined, signal),
    'formatting',
  );
  const [dirty, setDirty] = useState(false);
  async function transition(task: () => void) {
    if (w.busy) return;
    if (
      !dirty ||
      (await w.confirm(
        'Discard unsaved configuration changes before changing scope or section?',
        'Discard changes',
      ))
    )
      task();
  }
  return (
    <>
      <div class="toolbar">
        <label class="compact field">
          <span>Configuration scope</span>
          <select
            aria-label="Configuration scope"
            value={scope}
            disabled={w.busy}
            onChange={(e) => {
              const next = e.currentTarget.value as Scope;
              void transition(() => setScope(next));
            }}
          >
            {w.selected === w.hostId && <option value="LOCAL">Local server configuration</option>}
            {w.selected !== ALL && (w.networkActive || scope === 'SERVER') && (
              <option value="SERVER" disabled={!w.networkActive}>
                Server-specific override
              </option>
            )}
            {(w.networkActive || scope === 'NETWORK') && (
              <option value="NETWORK" disabled={!w.networkActive}>
                Network-wide default
              </option>
            )}
          </select>
        </label>
        <label class="compact field">
          <span>Section</span>
          <select
            aria-label="Configuration section"
            value={section}
            disabled={w.busy}
            onChange={(e) => {
              const next = e.currentTarget.value;
              void transition(() => setSection(next));
            }}
          >
            {page.categories?.map((id) => (
              <option value={id}>{id.replace(/_/g, ' ')}</option>
            ))}
          </select>
        </label>
      </div>
      {!w.networkActive && remote && (
        <div class="notice error">
          Shared storage is unavailable. Existing drafts are retained; saving is disabled.
        </div>
      )}
      <AsyncBoundary resource={resource}>
        {(snapshot) => (
          <ConfigEditor
            key={`${w.selected}:${scope}:${section}`}
            initial={snapshot}
            scope={scope}
            section={section}
            page={page}
            refresh={resource.refresh}
            onDirty={setDirty}
            tokens={placeholders.data?.tokens || []}
          />
        )}
      </AsyncBoundary>
    </>
  );
}
function ConfigEditor({
  initial,
  scope,
  section,
  page,
  refresh,
  onDirty,
  tokens,
}: {
  initial: Snapshot | RemoteSnapshot;
  scope: Scope;
  section: string;
  page: Page;
  refresh: () => void;
  onDirty: (dirty: boolean) => void;
  tokens: string[];
}) {
  const w = useWorkspace();
  const [snapshot, setSnapshot] = useState(initial);
  const [edits, setEdits] = useState<Record<string, Json>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [search, setSearch] = useState('');
  const [message, setMessage] = useState('');
  const [observation, setObservation] = useState<RemoteSnapshot>();
  const [ackError, setAckError] = useState('');
  const [destination, setDestination] = useState('');
  const [settingsPage, setSettingsPage] = useState(1);
  const dirty = Object.keys(edits).length > 0;
  useDirty('configuration', dirty);
  useEffect(() => onDirty(dirty), [dirty]);
  const remote = scope !== 'LOCAL';
  const remoteSnapshot = remote ? (snapshot as RemoteSnapshot) : undefined;
  const status = remoteSnapshot?.sections.find((s) => s.section === section);
  const observed = observation || remoteSnapshot;
  const observedStatus = observed?.sections.find((s) => s.section === section);
  useEffect(() => {
    if (!remoteSnapshot) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        if (document.visibilityState !== 'hidden') {
          const next = await w.api.request<RemoteSnapshot>(
            query('/api/remote-config/snapshot', {
              serverId: remoteSnapshot.serverId,
              categories: section,
            }),
            undefined,
            controller.signal,
          );
          if (!controller.signal.aborted) {
            setObservation(next);
            setAckError('');
          }
        }
      } catch (error) {
        if (!controller.signal.aborted)
          setAckError('Application acknowledgment is unavailable. ' + String(error));
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(poll, 5000);
      }
    };
    timer = setTimeout(poll, 5000);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [scope, section, w.selected]);
  const writable =
    (!remote || w.networkActive) &&
    !(scope === 'LOCAL' && snapshot.managedSections?.includes(section)) &&
    w.session.capabilities?.config[page.id]?.edit !== false &&
    (!(scope === 'NETWORK' || w.selected !== w.hostId) ||
      w.session.capabilities?.mutations?.network !== false) &&
    (!observed || observed.schemaCompatible);
  const fields = snapshot.fields.filter((f) => f.category === section);
  const filtered = fields.filter((f) =>
    `${f.label} ${f.key} ${f.help}`.toLowerCase().includes(search.toLowerCase()),
  );
  const valueFor = (f: Field): Json =>
    Object.hasOwn(edits, f.key)
      ? edits[f.key]
      : scope === 'NETWORK'
        ? (f.networkValue?.value ?? null)
        : f.value.value;
  async function save() {
    const invalid = Object.fromEntries(
      fields
        .filter((f) => Object.hasOwn(edits, f.key))
        .flatMap((f) => {
          const error = validateField(f, edits[f.key]);
          return error ? [[f.key, error]] : [];
        }),
    );
    setErrors(invalid);
    if (Object.keys(invalid).length) {
      w.notify('Correct the highlighted fields before saving.', true);
      return;
    }
    const target = configurationTarget(w.selected, w.hostId, scope);
    const operations = Object.entries(edits).map(([key, value]) => ({ key, value }));
    const warnings = fields
      .filter((f) => Object.hasOwn(edits, f.key) && /HIGH|DANGEROUS/.test(f.riskLevel))
      .map((f) => f.label);
    if (scope === 'NETWORK' || warnings.length)
      if (
        !(await w.confirm(
          `${scope === 'NETWORK' ? 'Change the network-wide default. All compatible servers inheriting these fields may be affected.' : 'Change sensitive settings.'}\n\nTarget: ${scope === 'NETWORK' ? 'Entire network' : target.serverId}\nSection: ${section}\nFields: ${operations.map((o) => o.key).join(', ')}`,
          'Save reviewed changes',
        ))
      )
        return;
    try {
      await w.mutate(async () => {
        if (remote) {
          const outcome = await w.api.request<{
            saved: boolean;
            revision: number;
            result: Validation;
          }>('/api/remote-config/patch', {
            ...target,
            section,
            expectedRevision:
              scope === 'NETWORK' ? status?.networkRevision || 0 : status?.serverRevision || 0,
            operations,
          });
          if (!outcome.saved) throw new Error('The configuration was not stored.');
          setEdits(remainingEdits(edits, outcome.result.accepted));
          setMessage('Saved to shared storage. Application acknowledgment is checked separately.');
          const next = await w.api.request<RemoteSnapshot>(
            query('/api/remote-config/snapshot', {
              serverId: target.serverId,
              categories: section,
            }),
          );
          setSnapshot(next);
        } else {
          const result = await w.api.request<Validation>(
            '/api/config/patch',
            { revision: snapshot.revision, operations },
            undefined,
            target.serverId,
          );
          setEdits(remainingEdits(edits, result.accepted));
          setMessage(`Saved ${result.accepted.length} fields. ${result.warnings.join(' ')}`);
          setSnapshot(await w.api.request<Snapshot>('/api/config/snapshot'));
        }
      });
      w.notify('Configuration saved.');
    } catch (error) {
      if (error instanceof ApiError) {
        const result = error.data as Validation | null;
        if (result?.accepted) {
          setEdits(remainingEdits(edits, result.accepted));
          setMessage(
            `${result.accepted.length} fields accepted; remaining changes need review. Reload the snapshot before retrying.`,
          );
        }
        if (result?.rejected)
          setErrors(Object.fromEntries(result.rejected.map((e) => [e.key, e.reason])));
      }
      w.notify(error instanceof Error ? error.message : String(error), true);
    }
  }
  async function adopt() {
    const target = configurationTarget(w.selected, w.hostId, scope);
    if (
      !(await w.confirm(
        scope === 'NETWORK'
          ? `Publish ${section} using the dashboard host's local values as network defaults? Server overrides remain higher priority.`
          : `Request central management of ${section} on ${target.serverId}? The target captures its own baseline when it synchronizes.`,
        'Adopt section',
      ))
    )
      return;
    try {
      const result = await w.mutate(() =>
        w.api.request<{ ok: boolean; pending?: boolean; reason?: string }>(
          '/api/remote-config/adopt',
          { ...target, section },
        ),
      );
      if (!result.ok) throw new Error(result.reason || 'Adoption conflicted with another change.');
      w.notify(
        result.pending
          ? 'Adoption requested. Waiting for the target server.'
          : 'Central management enabled.',
      );
      refresh();
    } catch (error) {
      w.notify(String(error), true);
    }
  }
  async function reloadSnapshot() {
    if (
      !dirty ||
      (await w.confirm(
        'Discard unsaved changes and reload the latest configuration?',
        'Discard and reload',
      ))
    )
      refresh();
  }
  async function apply() {
    if (
      !(await w.confirm(
        `Reload ${page.label} on ${w.hostId}? Dashboard listener changes may interrupt this session.`,
        'Apply reload',
      ))
    )
      return;
    try {
      await w.mutate(() =>
        w.api.request('/api/config/apply', { page: page.id }, undefined, w.hostId),
      );
      w.notify('Reload request completed.');
    } catch (error) {
      w.notify(String(error), true);
    }
  }
  return (
    <>
      <div class={`scope-note ${scope === 'NETWORK' ? 'network-scope' : ''}`}>
        <div>
          <strong>
            {scope === 'NETWORK'
              ? 'Network-wide default'
              : scope === 'SERVER'
                ? `Override for ${w.selected}`
                : `Local configuration · ${w.hostId}`}
          </strong>
          <p>
            {scope === 'NETWORK'
              ? 'Server-specific overrides take priority. Effective values below refer to the selected server; edits change only network defaults.'
              : 'The target is captured when you save. Changing servers requires reviewing unsaved changes.'}
          </p>
        </div>
        {status && (
          <Badge tone={status.lastError ? 'danger' : 'neutral'}>
            {applicationState(observed!, observedStatus || status)}
          </Badge>
        )}
      </div>
      {status && (
        <Card>
          <p class="muted">
            Reviewed revisions: network {status.networkRevision}, server {status.serverRevision}.
            Latest acknowledgment: network{' '}
            {observedStatus?.appliedGlobalRevision ?? status.appliedGlobalRevision}, server{' '}
            {observedStatus?.appliedServerRevision ?? status.appliedServerRevision}.
          </p>
          {observedStatus?.lastError && <p class="text-error">{observedStatus.lastError}</p>}
          {ackError && <p class="text-warning">{ackError}</p>}
          <p class="muted">
            An acknowledgment covers configuration processing. Reloads, restarts and in-game
            appearance may still require verification.
          </p>
          {(scope === 'NETWORK' ? status.networkRevision === 0 : !status.adopted) && (
            <button disabled={!writable || w.busy} onClick={adopt}>
              Adopt for central management
            </button>
          )}
        </Card>
      )}
      {remoteSnapshot && !remoteSnapshot.schemaCompatible && (
        <div class="notice error" role="alert">
          The target schema is unknown or incompatible. Editing is disabled until compatible server
          heartbeats are available.
        </div>
      )}
      {scope === 'LOCAL' && snapshot.managedSections?.includes(section) && (
        <div class="notice warning">
          This section is centrally managed. Choose Server-specific override to save with its shared
          revision.
        </div>
      )}
      {message && (
        <div class="notice" role="status">
          {message}
        </div>
      )}
      {Object.entries(errors)
        .filter(([key]) => key.startsWith('<'))
        .map(([key, reason]) => (
          <div class="notice error" role="alert" key={key}>
            {reason}
          </div>
        ))}
      <div class="toolbar">
        <input
          type="search"
          aria-label="Search settings"
          placeholder="Search settings or descriptions…"
          value={search}
          onInput={(e) => {
            setSearch(e.currentTarget.value);
            setSettingsPage(1);
          }}
        />
        <button disabled={w.busy} onClick={reloadSnapshot}>
          Refresh configuration
        </button>
        {scope === 'LOCAL' && (
          <button disabled={!writable || w.busy || dirty} onClick={apply}>
            Apply reload
          </button>
        )}
      </div>
      {!fields.length ? (
        <Empty title="This section is unavailable">
          The backend did not expose settings for this category or context.
        </Empty>
      ) : (
        <div class="settings-list">
          {filtered.slice((settingsPage - 1) * 12, settingsPage * 12).map((f) => (
            <Card key={f.key}>
              <SettingControl
                field={f}
                value={valueFor(f)}
                change={(value) => {
                  const base =
                    scope === 'NETWORK' ? (f.networkValue?.value ?? null) : f.value.value;
                  setEdits((prev) => {
                    const next = { ...prev };
                    if (JSON.stringify(value) === JSON.stringify(base)) delete next[f.key];
                    else next[f.key] = value;
                    return next;
                  });
                  setErrors((prev) => ({ ...prev, [f.key]: '' }));
                }}
                disabled={
                  w.busy ||
                  !writable ||
                  !f.editable ||
                  (scope === 'LOCAL' && f.origin !== undefined)
                }
                placeholders={tokens}
              />
              {errors[f.key] && <p class="text-error">{errors[f.key]}</p>}
              {remote && (
                <details class="value-origin">
                  <summary>Effective value and origin</summary>
                  <p>
                    Effective: <code>{JSON.stringify(f.value.value)}</code>
                  </p>
                  <p>
                    Network default:{' '}
                    <code>
                      {f.networkValue?.set
                        ? JSON.stringify(f.networkValue.value)
                        : 'Not configured'}
                    </code>
                  </p>
                  <p>
                    Server override:{' '}
                    <code>
                      {f.serverValue?.set ? JSON.stringify(f.serverValue.value) : 'Not configured'}
                    </code>
                  </p>
                </details>
              )}
            </Card>
          ))}
        </div>
      )}
      {filtered.length > 12 && (
        <Pager page={settingsPage} total={filtered.length} size={12} change={setSettingsPage} />
      )}
      {remote && scope === 'SERVER' && status && status.serverRevision > 0 && (
        <Card title="Copy this server override">
          <p class="help">
            Copies the reviewed section to another compatible server. Both source and destination
            revisions are checked. Application is acknowledged separately.
          </p>
          <div class="actions">
            <select
              aria-label="Copy destination server"
              value={destination}
              disabled={w.busy || dirty}
              onChange={(e) => setDestination(e.currentTarget.value)}
            >
              <option value="">Choose destination…</option>
              {w.servers
                .filter((s) => s.serverId !== w.selected)
                .map((s) => (
                  <option value={s.serverId}>{s.serverName || s.serverId}</option>
                ))}
            </select>
            <button
              disabled={w.busy || dirty || !destination}
              onClick={async () => {
                const from = w.selected,
                  to = destination;
                try {
                  await w.mutate(async () => {
                    const destinationSnapshot = await w.api.request<RemoteSnapshot>(
                      query('/api/remote-config/snapshot', { serverId: to, categories: section }),
                    );
                    const dest = destinationSnapshot.sections.find((s) => s.section === section);
                    if (!destinationSnapshot.schemaCompatible)
                      throw new Error('Destination schema is incompatible.');
                    if (
                      !(await w.confirm(
                        `Replace the ${section} server override on ${to} with the reviewed override on ${from}?\nSource revision: ${status.serverRevision}\nDestination revision: ${dest?.serverRevision || 0}`,
                        'Copy reviewed override',
                      ))
                    )
                      return;
                    const result = await w.api.request<{ ok: boolean; reason?: string }>(
                      '/api/remote-config/copy',
                      {
                        fromServerId: from,
                        toServerId: to,
                        section,
                        expectedRevision: dest?.serverRevision || 0,
                        expectedSourceRevision: status.serverRevision,
                      },
                    );
                    if (!result.ok)
                      throw new Error(result.reason || 'Configuration copy conflicted.');
                    w.notify('Override stored on the destination. Synchronization is pending.');
                  });
                } catch (error) {
                  w.notify(String(error), true);
                }
              }}
            >
              Review copy
            </button>
          </div>
        </Card>
      )}
      <SaveBar
        dirty={dirty}
        busy={w.busy}
        save={save}
        discard={() => {
          setEdits({});
          setErrors({});
        }}
      />
    </>
  );
}
