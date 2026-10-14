import { writePlayerSelection } from '../core/preferences';
import { useState } from 'preact/hooks';
import { query } from '../core/api';
import { AsyncBoundary, useResource, useWorkspace } from '../core/context';
import { ALL, array, formatTime, number, object, string, type Json, type Obj } from '../core/types';
import { Badge, Card, DataView, Pager, Rows } from '../components/ui';
export function Players() {
  const w = useWorkspace();
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [filter, setFilter] = useState('');
  const [detail, setDetail] = useState<Obj>();
  const result = useResource<Json>(
    (signal) =>
      w.api.request(
        query('/api/players', {
          query: search,
          page,
          pageSize: 25,
          serverId: w.selected === ALL ? '' : w.selected,
          status: filter,
        }),
        undefined,
        signal,
      ),
    `${w.selected}:${search}:${page}:${filter}`,
  );
  return (
    <>
      <div class="scope-note">
        <strong>{w.networkActive ? 'Network profile directory' : 'Local profile directory'}</strong>
        <p>
          Online and AFK states are established only for the dashboard host. Remote presence is
          unavailable; shared profiles are not treated as proof of a player's current server.
        </p>
      </div>
      <div class="toolbar">
        <input
          type="search"
          aria-label="Search players"
          placeholder="Search name or UUID…"
          value={search}
          onInput={(e) => {
            setSearch(e.currentTarget.value);
            setPage(1);
            setDetail(undefined);
          }}
        />
        <select
          aria-label="Player presence"
          value={filter}
          onChange={(e) => {
            setFilter(e.currentTarget.value);
            setPage(1);
          }}
        >
          <option value="">All known profiles</option>
          <option value="online">Online on selected server</option>
          <option value="offline">Not online on selected server</option>
        </select>
      </div>
      <AsyncBoundary resource={result}>
        {(raw) => {
          const data = object(raw);
          return (
            <>
              {array(data.warnings).map((warning) => (
                <p class="notice warning" role="status">
                  {string(warning)}
                </p>
              ))}
              <Card>
                <Rows
                  rows={array(data.players)}
                  columns={[
                    { key: 'name', label: 'Player' },
                    { key: 'uuid', label: 'UUID' },
                    {
                      key: 'online',
                      label: 'Presence',
                      render: (r) => (
                        <Badge tone={r.online === true ? 'success' : 'neutral'}>
                          {r.online === true
                            ? 'Online on host'
                            : r.online === false
                              ? 'Not online on host'
                              : 'Unavailable'}
                        </Badge>
                      ),
                    },
                    {
                      key: 'lastSeenMs',
                      label: 'Last seen',
                      render: (r) => formatTime(r.lastSeenMs),
                    },
                    {
                      key: 'playtimeMs',
                      label: 'Recorded playtime',
                      render: (r) => `${Math.floor(number(r.playtimeMs) / 3600000)} hours`,
                    },
                  ]}
                  onSelect={setDetail}
                />
                <Pager page={page} total={number(data.matchTotal)} change={setPage} />
              </Card>
              {detail && (
                <Card title={string(detail.name) || string(detail.uuid)}>
                  <DataView data={detail} />
                  <div class="actions">
                    <button
                      onClick={() => {
                        writePlayerSelection(string(detail.uuid));
                        void w.navigate('permissions');
                      }}
                    >
                      View permissions
                    </button>
                    <button
                      onClick={() => {
                        writePlayerSelection(string(detail.uuid));
                        void w.navigate('moderation');
                      }}
                    >
                      Moderation history
                    </button>
                  </div>
                </Card>
              )}
            </>
          );
        }}
      </AsyncBoundary>
    </>
  );
}
export function Audit() {
  const w = useWorkspace();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [page, setPage] = useState(1);
  const result = useResource<Json>(
    (signal) =>
      w.api.request(
        query('/api/audit/recent', { ...filters, page, pageSize: 25 }),
        undefined,
        signal,
      ),
    JSON.stringify([filters, page, w.selected]),
  );
  return (
    <>
      <div class="scope-note">
        Audit records are supplied by the dashboard host's audit service. The selected server does
        not imply a complete remote audit stream.
      </div>
      <Card>
        <div class="filter-grid">
          {['actor', 'type', 'target', 'result', 'source', 'fromMs', 'toMs'].map((key) => (
            <label class="field">
              <span>{key === 'fromMs' ? 'From' : key === 'toMs' ? 'To' : key}</span>
              {['result', 'source'].includes(key) ? (
                <select
                  value={filters[key] || ''}
                  onChange={(e) => {
                    setFilters({ ...filters, [key]: e.currentTarget.value });
                    setPage(1);
                  }}
                >
                  <option value="">All</option>
                  {(key === 'result'
                    ? ['SUCCESS', 'FAILED', 'DENIED']
                    : ['COMMAND', 'DASHBOARD']
                  ).map((v) => (
                    <option>{v}</option>
                  ))}
                </select>
              ) : (
                <input
                  type={key.endsWith('Ms') ? 'datetime-local' : 'search'}
                  onInput={(e) => {
                    setFilters({
                      ...filters,
                      [key]:
                        key.endsWith('Ms') && e.currentTarget.value
                          ? String(new Date(e.currentTarget.value).getTime())
                          : e.currentTarget.value,
                    });
                    setPage(1);
                  }}
                />
              )}
            </label>
          ))}
        </div>
      </Card>
      <AsyncBoundary resource={result}>
        {(raw) => {
          const data = object(raw);
          return (
            <Card>
              <Rows
                rows={array(data.entries)}
                columns={[
                  {
                    key: 'createdAtMs',
                    label: 'Time',
                    render: (r) => formatTime(r.createdAtMs || r.timestampMs),
                  },
                  { key: 'actorName', label: 'Actor' },
                  { key: 'actionType', label: 'Action' },
                  {
                    key: 'summary',
                    label: 'Description',
                    render: (r) => string(r.summary || r.message),
                  },
                  {
                    key: 'result',
                    label: 'Result',
                    render: (r) => (
                      <Badge tone={r.result === 'SUCCESS' ? 'success' : 'danger'}>
                        {string(r.result)}
                      </Badge>
                    ),
                  },
                ]}
              />
              <Pager page={page} total={number(data.total)} change={setPage} />
            </Card>
          );
        }}
      </AsyncBoundary>
    </>
  );
}
