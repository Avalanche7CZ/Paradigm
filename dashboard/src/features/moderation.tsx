import { readPlayerSelection } from '../core/preferences';
import { useState } from 'preact/hooks';
import { query } from '../core/api';
import { AsyncBoundary, useDirty, useResource, useWorkspace } from '../core/context';
import { array, formatTime, object, string, type Json, type Obj } from '../core/types';
import { Card, DataView, Pager, Rows, Tabs } from '../components/ui';
export function Moderation() {
  const w = useWorkspace();
  const capabilities = useResource<{ actions: Record<string, boolean> }>(
    (signal) => w.api.request('/api/moderation/capabilities', undefined, signal),
    w.hostId,
  );
  const actions = Object.keys(capabilities.data?.actions || {});
  const [page, setPage] = useState(1);
  const [view, setView] = useState('recent');
  const [search, setSearch] = useState(readPlayerSelection());
  const [player, setPlayer] = useState('');
  const [revision, setRevision] = useState(0);
  const [draft, setDraft] = useState<Obj>({ action: 'warn', scope: 'server' });
  const [edited, setEdited] = useState(false);
  const [detail, setDetail] = useState('');
  const [filter, setFilter] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const resource = useResource<Json>(
    (signal) =>
      w.api.request(
        query('/api/moderation/' + (player ? 'player' : view), { uuidOrName: player }),
        undefined,
        signal,
      ),
    `${view}:${player}:${revision}`,
  );
  const punishment = useResource<Json>(
    (signal) =>
      detail
        ? w.api.request(query('/api/moderation/punishment', { id: detail }), undefined, signal)
        : Promise.resolve(null),
    `${detail}:${revision}`,
  );
  useDirty('moderation', edited);
  const change = (key: string, v: Json) => {
    setDraft({ ...draft, [key]: v });
    setEdited(true);
  };
  async function apply(body: Obj = draft) {
    if (capabilities.data?.actions[string(body.action)] !== true) {
      w.notify('You do not have permission for this moderation action.', true);
      return;
    }
    if (['mute', 'ban', 'ipban'].includes(string(body.action)) && string(body.duration).trim())
      body = { ...body, action: 'temp' + string(body.action) };
    if (
      ['tempmute', 'tempban', 'tempipban'].includes(string(body.action)) &&
      !string(body.duration).trim()
    ) {
      w.notify('A duration is required for temporary punishments.', true);
      return;
    }
    if (
      !(await w.confirm(
        `Apply ${string(body.action)} on ${w.hostId}?\n\n${JSON.stringify(body, null, 2)}\n\nNetwork scope affects shared punishment records.`,
        'Apply moderation',
      ))
    )
      return;
    try {
      const result = object(
        await w.mutate(() =>
          w.api.request<Json>(
            '/api/moderation/' + string(body.action),
            { ...body, confirmed: true },
            undefined,
            w.hostId,
          ),
        ),
      );
      if (result.applied !== true) {
        w.notify(string(result.message) || 'Operation was not applied.', true);
        return;
      }
      setEdited(false);
      setRevision((v) => v + 1);
      w.notify(string(result.message) || 'Moderation action applied.');
    } catch (error) {
      w.notify(String(error), true);
    }
  }
  return (
    <>
      <div class="scope-note">
        Live moderation runs on {w.hostId}. Punishment scope is explicit; shared records can affect
        the network.
      </div>
      <Tabs
        values={['recent', 'active'].map((id) => ({
          id,
          label: id === 'recent' ? 'Recent history' : 'Active punishments',
        }))}
        selected={view}
        onChange={(id) => {
          setView(id);
          setPlayer('');
        }}
      />
      <div class="toolbar">
        <input
          type="search"
          aria-label="Player name or UUID"
          placeholder="Find a player by name or UUID…"
          value={search}
          onInput={(e) => setSearch(e.currentTarget.value)}
        />
        <button
          onClick={() => {
            setPlayer(search);
            setPage(1);
            setDraft((prev) => ({ ...prev, player: search }));
          }}
        >
          Find player
        </button>
        <button
          onClick={() => {
            setPlayer('');
            setDetail('');
          }}
        >
          Show all
        </button>
      </div>
      <Card>
        <div class="filter-grid">
          <label class="field">
            <span>Filter history</span>
            <input
              type="search"
              value={filter}
              onInput={(e) => {
                setFilter(e.currentTarget.value);
                setPage(1);
              }}
            />
          </label>
          <label class="field">
            <span>From</span>
            <input type="date" value={from} onInput={(e) => setFrom(e.currentTarget.value)} />
          </label>
          <label class="field">
            <span>To</span>
            <input type="date" value={to} onInput={(e) => setTo(e.currentTarget.value)} />
          </label>
        </div>
      </Card>
      <AsyncBoundary resource={resource}>
        {(raw) => {
          const data = object(raw);
          const history = array(data.punishments || data.history || data.entries).filter((v) => {
            const r = object(v);
            return (
              JSON.stringify(r).toLowerCase().includes(filter.toLowerCase()) &&
              (!from || Number(r.createdAtMs) >= new Date(from).getTime()) &&
              (!to || Number(r.createdAtMs) < new Date(to).getTime() + 86400000)
            );
          });
          return (
            <>
              {string(object(data.player).nickname) && (
                <p>
                  Account: {string(object(data.player).name)} · Nickname:{' '}
                  {string(object(data.player).nickname)} · UUID: {string(object(data.player).uuid)}
                </p>
              )}
              <Card>
                <Rows
                  rows={history.slice((page - 1) * 25, page * 25)}
                  columns={[
                    { key: 'name', label: 'Player' },
                    { key: 'type', label: 'Type' },
                    { key: 'status', label: 'Status' },
                    { key: 'reason', label: 'Reason' },
                    { key: 'scope', label: 'Scope' },
                    {
                      key: 'createdAtMs',
                      label: 'Created',
                      render: (r) => formatTime(r.createdAtMs),
                    },
                  ]}
                  onSelect={(r) => setDetail(string(r.punishmentId))}
                />
                <Pager page={page} total={history.length} change={setPage} />
              </Card>
              {player && (
                <Card title="Resolved player identity">
                  <DataView data={data.identity || data.player || data} />
                </Card>
              )}
            </>
          );
        }}
      </AsyncBoundary>
      {detail && (
        <Card title="Punishment details">
          <AsyncBoundary resource={punishment}>
            {(data) => (
              <>
                <DataView data={data} />
                <button
                  class="danger"
                  disabled={w.busy || capabilities.data?.actions.revoke !== true}
                  onClick={() =>
                    apply({ action: 'revoke', punishmentId: detail, reason: string(draft.reason) })
                  }
                >
                  Revoke this punishment
                </button>
              </>
            )}
          </AsyncBoundary>
        </Card>
      )}
      <Card title="Moderation action">
        <div class="field-grid">
          <label class="field">
            <span>Action</span>
            <select
              value={string(draft.action)}
              disabled={w.busy}
              onChange={(e) => change('action', e.currentTarget.value)}
            >
              {actions.map((v) => (
                <option disabled={!capabilities.data?.actions[v]}>{v}</option>
              ))}
            </select>
          </label>
          <label class="field">
            <span>Punishment scope</span>
            <select
              value={string(draft.scope)}
              disabled={w.busy}
              onChange={(e) => change('scope', e.currentTarget.value)}
            >
              <option value="server">This server</option>
              <option value="network">Entire network</option>
            </select>
          </label>
          {['player', 'uuid', 'ipAddress', 'punishmentId', 'duration', 'reason'].map((key) => (
            <label class="field">
              <span>{key}</span>
              <input
                value={string(draft[key])}
                disabled={w.busy}
                placeholder={key === 'duration' ? '30m, 2h, 7d or perm' : undefined}
                onInput={(e) => change(key, e.currentTarget.value)}
              />
            </label>
          ))}
        </div>
        <div class="actions">
          <button
            class="primary"
            disabled={w.busy || capabilities.data?.actions[string(draft.action)] !== true}
            onClick={() => apply()}
          >
            Review action
          </button>
          <button
            disabled={w.busy}
            onClick={() => {
              setDraft({ action: 'warn', scope: 'server' });
              setEdited(false);
            }}
          >
            Discard draft
          </button>
        </div>
      </Card>
    </>
  );
}
