import { AsyncBoundary, useResource, useWorkspace } from '../core/context';
import { ALL, array, formatTime, object, string, type Json, type Server } from '../core/types';
import { Badge, Card, DataView, Empty } from '../components/ui';
export function ServersTable({ servers }: { servers: Server[] }) {
  const w = useWorkspace();
  return servers.length ? (
    <div class="table-wrap" tabIndex={0} role="region" aria-label="Server directory">
      <table>
        <thead>
          <tr>
            <th scope="col">Server</th>
            <th scope="col">Status</th>
            <th scope="col">Minecraft</th>
            <th scope="col">Players</th>
            <th scope="col">Last heartbeat</th>
            <th scope="col">Context</th>
          </tr>
        </thead>
        <tbody>
          {servers.map((server) => (
            <tr key={server.serverId}>
              <td>
                <strong>{server.serverName || server.serverId}</strong>
                <small class="muted code">
                  {server.serverId}
                  {server.current ? ' · dashboard host' : ''}
                </small>
              </td>
              <td>
                <Badge tone={server.online ? 'success' : 'neutral'}>
                  {server.online ? 'Online' : 'Offline'}
                </Badge>
              </td>
              <td>
                {server.minecraftVersion || 'Unknown'}
                <small class="muted">
                  {server.loader || 'Unknown loader'} · Paradigm {server.version || 'unknown'}
                </small>
              </td>
              <td>
                {server.online && server.runtimeDetailsAvailable
                  ? (server.onlinePlayers ?? 'Not reported')
                  : server.online
                    ? 'Not reported'
                    : '—'}
              </td>
              <td>{formatTime(server.lastSeenMs)}</td>
              <td>
                <button disabled={w.busy} onClick={() => w.select(server.serverId)}>
                  Manage
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  ) : (
    <Empty title="No server heartbeats">Check network storage and discovery.</Empty>
  );
}
export function Overview() {
  const w = useWorkspace();
  const selected = w.servers.find((s) => s.serverId === w.selected);
  const overview = useResource<Json>(
    (signal) => w.api.request('/api/overview', undefined, signal),
    w.selected,
  );
  if (w.selected === ALL) {
    const available = w.servers.filter((s) => s.onlinePlayers !== null && s.online);
    return (
      <>
        <div class="metrics">
          <Metric label="Connected servers" value={w.servers.filter((s) => s.online).length} />
          <Metric label="Offline servers" value={w.servers.filter((s) => !s.online).length} />
          <Metric
            label="Reported online players"
            value={
              available.length
                ? available.reduce((sum, s) => sum + (s.onlinePlayers || 0), 0)
                : 'Unavailable'
            }
            note={`${available.length} of ${w.servers.filter((s) => s.online).length} online servers report player counts`}
          />
        </div>
        <Card title="Network servers">
          <ServersTable servers={w.servers} />
        </Card>
        <Card title="Recent activity from the dashboard host">
          <AsyncBoundary resource={overview}>
            {(data) => <Activity entries={array(object(data).recentActivity)} />}
          </AsyncBoundary>
        </Card>
        <div class="scope-note">
          Status follows the latest server heartbeat. Player counts require fresh telemetry; an
          online server can be missing telemetry. Counts refresh with server discovery.
        </div>
      </>
    );
  }
  if (!selected)
    return (
      <Empty title="Server no longer discovered">
        The selected server remains your context. Select an available server before starting a new
        operation.
      </Empty>
    );
  if (!selected.current)
    return (
      <>
        <div class="metrics">
          <Metric label="Server status" value={selected.online ? 'Online' : 'Offline'} />
          <Metric
            label="Online players"
            value={
              selected.online && selected.runtimeDetailsAvailable
                ? (selected.onlinePlayers ?? 'Not reported')
                : 'Not reported'
            }
          />
          <Metric
            label="Enabled modules"
            value={
              selected.online && selected.runtimeDetailsAvailable
                ? (selected.enabledModuleCount ?? 'Not reported')
                : 'Not reported'
            }
          />
        </div>
        <div class="scope-note" role="status">
          <div>
            <strong>
              {!selected.online
                ? 'Server offline'
                : selected.runtimeDetailsAvailable
                  ? 'Runtime telemetry received'
                  : 'Online · telemetry not reported'}
            </strong>
            <p>
              {!selected.online
                ? 'No recent server heartbeat. Previous runtime counts are not shown as current.'
                : selected.runtimeDetailsAvailable
                  ? 'Player and module counts come from the latest runtime snapshot. Unreported fields remain blank.'
                  : 'The server is sending heartbeats without a fresh runtime snapshot. Runtime counts require a server version that publishes telemetry to the shared MySQL / MariaDB database.'}
            </p>
            {selected.runtimeUpdatedAtMs && (
              <p>Last runtime snapshot: {formatTime(selected.runtimeUpdatedAtMs)}</p>
            )}
          </div>
        </div>
        <Card title={selected.serverName}>
          <DataView
            data={{
              serverId: selected.serverId,
              minecraftVersion: selected.minecraftVersion,
              loader: selected.loader,
              version: selected.version,
              storageHealth:
                selected.online && selected.runtimeDetailsAvailable ? selected.storageHealth : null,
              lastHeartbeat: formatTime(selected.lastSeenMs),
            }}
          />
        </Card>
        <Card title="Manage this server">
          <p>
            Configuration pages use this server's desired configuration, origins and application
            acknowledgments.
          </p>
          <div class="actions">
            <button onClick={() => w.navigate('motd')}>Edit MOTD</button>
            <button onClick={() => w.navigate('general')}>Manage modules</button>
            <button onClick={() => w.navigate('tickets')}>Support tickets</button>
          </div>
        </Card>
      </>
    );
  return (
    <AsyncBoundary resource={overview}>
      {(raw) => {
        const data = object(raw);
        return (
          <>
            <div class="metrics">
              <Metric label="Online players" value={string(data.onlinePlayers)} />
              <Metric
                label="Uptime"
                value={`${Math.floor(Number(data.uptimeMs) / 3600000)}h ${Math.floor(Number(data.uptimeMs) / 60000) % 60}m`}
              />
              <Metric label="Active storage" value={string(data.activeProvider)} />
            </div>
            {array(data.warnings).map((warning) => (
              <div class="notice warning">{string(warning)}</div>
            ))}
            <div class="two-columns">
              <Card title="Server identity">
                <DataView
                  data={{
                    serverName: data.serverName,
                    serverId: data.serverId,
                    networkId: data.networkId,
                    minecraftVersion: data.minecraftVersion,
                    loader: data.loader,
                    version: data.version,
                    modules: data.modules,
                    runtime: data.runtime,
                    onlinePlayers: data.players,
                  }}
                />
              </Card>
              <Card title="Quick actions">
                <div class="quick-actions">
                  {[
                    ['general', 'Modules & configuration'],
                    ['motd', 'MOTD & welcome'],
                    ['players', 'Browse players'],
                    ['storage', 'Storage diagnostics'],
                  ].map(([id, label]) => (
                    <button onClick={() => w.navigate(id)}>
                      {label}
                      <span aria-hidden="true">↗</span>
                    </button>
                  ))}
                </div>
              </Card>
            </div>
            <Card title="Recent administrative activity">
              <Activity entries={array(data.recentActivity)} />
            </Card>
          </>
        );
      }}
    </AsyncBoundary>
  );
}
function Metric({ label, value, note }: { label: string; value: string | number; note?: string }) {
  return (
    <Card class="metric">
      <span class="muted">{label}</span>
      <strong>{value}</strong>
      {note && <small>{note}</small>}
    </Card>
  );
}
function Activity({ entries }: { entries: Json[] }) {
  return entries.length ? (
    <div class="activity">
      {entries.map((entry, i) => {
        const v = object(entry);
        return (
          <article key={i}>
            <span class="activity-mark" />
            <div>
              <strong>{string(v.summary || v.message || v.actionType)}</strong>
              <small>
                {string(v.actorName)} · {formatTime(v.createdAtMs || v.timestampMs)}
              </small>
            </div>
            <Badge>{string(v.result)}</Badge>
          </article>
        );
      })}
    </div>
  ) : (
    <Empty title="No recent activity">Administrative operations will appear here.</Empty>
  );
}
