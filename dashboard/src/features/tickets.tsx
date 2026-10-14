import { useState } from 'preact/hooks';
import { query } from '../core/api';
import { AsyncBoundary, useDirty, useResource, useWorkspace } from '../core/context';
import { ALL, array, formatTime, number, object, string, title, type Json } from '../core/types';
import { Badge, Card, DataView, Pager, Rows } from '../components/ui';
export function Tickets() {
  const w = useWorkspace();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [page, setPage] = useState(1);
  const [id, setId] = useState('');
  const [revision, setRevision] = useState(0);
  const [reply, setReply] = useState('');
  const [action, setAction] = useState('claim');
  const [value, setValue] = useState('');
  const list = useResource<Json>(
    (signal) =>
      w.api.request(
        query('/api/tickets', {
          ...filters,
          server: w.selected === ALL ? '' : w.selected,
          page,
          pageSize: 25,
        }),
        undefined,
        signal,
      ),
    JSON.stringify([filters, w.selected, page, revision]),
  );
  const detail = useResource<Json>(
    (signal) =>
      id
        ? w.api.request(query('/api/tickets/item', { id }), undefined, signal)
        : Promise.resolve(null),
    `${id}:${revision}`,
  );
  useDirty('ticket', !!reply || !!value);
  async function choose(next: string) {
    if (w.busy) return;
    if (
      (!reply && !value) ||
      (await w.confirm('Discard the unsent reply and ticket changes?', 'Discard draft'))
    ) {
      setId(next);
      setReply('');
      setValue('');
    }
  }
  async function mutate(operation: string) {
    const ticket = object(object(detail.data).ticket);
    if (!id || !ticket.revision) {
      w.notify('Load the current ticket before making changes.', true);
      return;
    }
    if (
      operation !== 'reply' &&
      !(await w.confirm(`${title(operation)} ${id}?\n${value}`, 'Apply ticket change'))
    )
      return;
    try {
      await w.mutate(() =>
        w.api.request('/api/tickets/' + operation, {
          id,
          revision: ticket.revision,
          message: reply,
          value,
          assignee: value,
        }),
      );
      setReply('');
      setValue('');
      setRevision((v) => v + 1);
      w.notify('Ticket updated.');
    } catch (error) {
      w.notify(String(error), true);
    }
  }
  return (
    <>
      <div class="scope-note">
        Tickets are network records. The global selector filters their origin server; mutations use
        the ticket's stable ID and reviewed revision.
      </div>
      <Card>
        <div class="filter-grid">
          {['search', 'status', 'priority', 'category', 'assignee', 'creator'].map((key) => (
            <label class="field">
              <span>{title(key)}</span>
              {['status', 'priority', 'category'].includes(key) ? (
                <select
                  value={filters[key] || ''}
                  onChange={(e) => {
                    setFilters({ ...filters, [key]: e.currentTarget.value });
                    setPage(1);
                  }}
                >
                  <option value="">All</option>
                  {array(
                    object(list.data)[
                      key === 'status'
                        ? 'statuses'
                        : key === 'priority'
                          ? 'priorities'
                          : 'categories'
                    ],
                  ).map((v) =>
                    typeof v === 'object' ? (
                      <option value={string(object(v).id)}>{string(object(v).displayName)}</option>
                    ) : (
                      <option>{string(v)}</option>
                    ),
                  )}
                </select>
              ) : (
                <input
                  value={filters[key] || ''}
                  placeholder={key === 'assignee' ? 'UUID or unassigned' : undefined}
                  onInput={(e) => {
                    setFilters({ ...filters, [key]: e.currentTarget.value });
                    setPage(1);
                  }}
                />
              )}
            </label>
          ))}
        </div>
      </Card>
      <AsyncBoundary resource={list}>
        {(raw) => {
          const data = object(raw);
          return (
            <>
              <Card title="Ticket summary">
                <DataView data={data.summary} />
              </Card>
              <Card>
                <Rows
                  rows={array(data.entries)}
                  columns={[
                    { key: 'ticketKey', label: 'Ticket' },
                    { key: 'subject', label: 'Subject' },
                    { key: 'creatorName', label: 'Creator' },
                    { key: 'originServerId', label: 'Origin' },
                    {
                      key: 'status',
                      label: 'Status',
                      render: (r) => <Badge>{string(r.status)}</Badge>,
                    },
                    { key: 'priority', label: 'Priority' },
                  ]}
                  onSelect={(r) => void choose(string(r.ticketKey))}
                />
                <Pager page={page} total={number(data.total)} change={setPage} />
              </Card>
            </>
          );
        }}
      </AsyncBoundary>
      {id && (
        <AsyncBoundary resource={detail}>
          {(raw) => {
            const data = object(raw),
              ticket = object(data.ticket);
            return (
              <Card title={`${id} · ${string(ticket.subject)}`}>
                <button disabled={w.busy} onClick={detail.refresh}>
                  Refresh ticket revision
                </button>
                <DataView data={ticket} />
                <div class="message-thread">
                  {array(data.messages).map((raw) => {
                    const message = object(raw);
                    return (
                      <article>
                        <strong>{string(message.authorName)}</strong>
                        <small> · {formatTime(message.createdAtMs)}</small>
                        <p class="preserve-lines">{string(message.text)}</p>
                      </article>
                    );
                  })}
                </div>
                <label class="field">
                  <span>Reply</span>
                  <textarea
                    value={reply}
                    disabled={w.busy}
                    onInput={(e) => setReply(e.currentTarget.value)}
                  />
                </label>
                <button
                  class="primary"
                  disabled={w.busy || !reply.trim()}
                  onClick={() => mutate('reply')}
                >
                  Send reply
                </button>
                <div class="toolbar">
                  <label class="field compact">
                    <span>Ticket operation</span>
                    <select value={action} onChange={(e) => setAction(e.currentTarget.value)}>
                      {[
                        'claim',
                        'unclaim',
                        'assign',
                        'priority',
                        'status',
                        'category',
                        'resolve',
                        'close',
                        'reopen',
                      ].map((v) => (
                        <option>{v}</option>
                      ))}
                    </select>
                  </label>
                  <label class="field compact">
                    <span>New value / assignee</span>
                    <input value={value} onInput={(e) => setValue(e.currentTarget.value)} />
                  </label>
                  <button disabled={w.busy} onClick={() => mutate(action)}>
                    Review change
                  </button>
                </div>
                <details>
                  <summary>Ticket history</summary>
                  <DataView data={data.events} />
                </details>
              </Card>
            );
          }}
        </AsyncBoundary>
      )}
    </>
  );
}
