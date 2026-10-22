import type { ComponentChildren } from 'preact';
import { useLayoutEffect, useRef, useState } from 'preact/hooks';
import { query } from '../core/api';
import { AsyncBoundary, useDirty, useResource, useWorkspace } from '../core/context';
import { ALL, array, formatTime, number, object, string, title, type Json } from '../core/types';
import { Badge, Card, DataView, Pager } from '../components/ui';

const label = (value: Json | undefined) => title(string(value).toLowerCase()) || 'Unknown';
const statusTone = (value: Json | undefined) =>
  value === 'RESOLVED' ? 'success' : value === 'CLOSED' ? 'neutral' : 'warning';

function TicketDrawer({
  id,
  close,
  children,
}: {
  id: string;
  close: () => void;
  children: ComponentChildren;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useLayoutEffect(() => {
    const previous = document.activeElement;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const dialog = ref.current;
    dialog?.showModal();
    return () => {
      dialog?.close();
      document.body.style.overflow = overflow;
      if (previous instanceof HTMLElement && previous.isConnected)
        previous.focus({ preventScroll: true });
      else {
        const opener = Array.from(
          document.querySelectorAll<HTMLButtonElement>('.ticket-subject'),
        ).find((button) => button.dataset.ticketKey === id);
        (opener || document.getElementById('ticket-filter-search'))?.focus({ preventScroll: true });
      }
    };
  }, []);
  return (
    <dialog
      ref={ref}
      class="ticket-drawer"
      aria-labelledby="ticket-heading"
      onKeyDown={(event) => {
        if (event.key !== 'Tab') return;
        const controls = Array.from(
          event.currentTarget.querySelectorAll<HTMLElement>(
            'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex="0"]',
          ),
        ).filter((element) => element.getClientRects().length > 0);
        const target = event.shiftKey ? controls.at(-1) : controls[0];
        const boundary = event.shiftKey ? controls[0] : controls.at(-1);
        if (target && document.activeElement === boundary) {
          event.preventDefault();
          target.focus();
        }
      }}
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
    >
      {children}
    </dialog>
  );
}

export function Tickets() {
  const w = useWorkspace();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [page, setPage] = useState(1);
  const [id, setId] = useState('');
  const [revision, setRevision] = useState(0);
  const [reply, setReply] = useState('');
  const [action, setAction] = useState('claim');
  const [value, setValue] = useState('');
  const [error, setError] = useState('');
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
  const data = object(detail.data),
    ticket = object(data.ticket);
  const filterData = object(list.data);
  const needsValue = ['assign', 'priority', 'status', 'category'].includes(action);
  const options = array(
    filterData[
      action === 'status' ? 'statuses' : action === 'priority' ? 'priorities' : 'categories'
    ],
  );
  const canMutate = !w.busy && !detail.loading && !detail.error && !!ticket.revision;
  useDirty('ticket', !!reply || !!value);
  const origin = (raw: Json | undefined) => {
    const server = string(raw);
    return server === 'web'
      ? 'Website'
      : w.servers.find((s) => s.serverId === server)?.serverName || server || 'Unknown origin';
  };
  function filter(key: string, value: string) {
    setFilters((current) => ({ ...current, [key]: value }));
    setPage(1);
  }
  async function choose(next: string) {
    if (w.busy || next === id) return;
    if (
      (!reply && !value) ||
      (await w.confirm('Discard the unsent reply and ticket changes?', 'Discard draft'))
    ) {
      setId(next);
      setReply('');
      setValue('');
      setError('');
      setAction('claim');
    }
  }
  async function mutate(operation: string) {
    if (!id || !canMutate) return;
    if (
      operation !== 'reply' &&
      !(await w.confirm(`${title(operation)} ${id}?\n${value}`, 'Apply ticket change'))
    )
      return;
    setError('');
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
      if (operation === 'reply') setReply('');
      else setValue('');
      setRevision((v) => v + 1);
      w.notify('Ticket updated.');
    } catch (error) {
      setError(error instanceof Error ? error.message : String(error));
    }
  }
  function filterField(key: string) {
    return (
      <label class="field" key={key} for={`ticket-filter-${key}`}>
        <span id={`ticket-filter-label-${key}`}>{title(key)}</span>
        {['status', 'priority', 'category'].includes(key) ? (
          <select
            id={`ticket-filter-${key}`}
            aria-labelledby={`ticket-filter-label-${key}`}
            value={filters[key] || ''}
            onChange={(e) => filter(key, e.currentTarget.value)}
          >
            <option value="">All</option>
            {array(
              filterData[
                key === 'status' ? 'statuses' : key === 'priority' ? 'priorities' : 'categories'
              ],
            ).map((raw) => (
              <option value={typeof raw === 'object' ? string(object(raw).id) : string(raw)}>
                {typeof raw === 'object' ? string(object(raw).displayName) : label(raw)}
              </option>
            ))}
          </select>
        ) : (
          <input
            id={`ticket-filter-${key}`}
            aria-labelledby={`ticket-filter-label-${key}`}
            value={filters[key] || ''}
            placeholder={
              key === 'assignee'
                ? 'UUID or unassigned'
                : key === 'creator'
                  ? 'Creator UUID'
                  : 'Search tickets'
            }
            onInput={(e) => filter(key, e.currentTarget.value)}
          />
        )}
      </label>
    );
  }
  return (
    <>
      <p class="muted">Network tickets · Server context filters the origin.</p>
      <Card class="ticket-filters">
        <div class="ticket-filter-main">{['search', 'status', 'priority'].map(filterField)}</div>
        <details>
          <summary>
            More filters
            {['category', 'assignee', 'creator'].some((key) => filters[key]) ? ' · Active' : ''}
          </summary>
          <div class="ticket-filter-main">
            {['category', 'assignee', 'creator'].map(filterField)}
          </div>
        </details>
      </Card>
      <AsyncBoundary resource={list}>
        {(raw) => {
          const data = object(raw),
            summary = object(data.summary);
          return (
            <>
              <div class="ticket-summary" aria-label="Ticket status overview">
                {['open', 'in_progress', 'resolved', 'closed'].map((status) => (
                  <button
                    aria-pressed={filters.status === status.toUpperCase()}
                    onClick={() =>
                      filter(
                        'status',
                        filters.status === status.toUpperCase() ? '' : status.toUpperCase(),
                      )
                    }
                  >
                    <span>{label(status)}</span>
                    <strong>{summary[status] == null ? '—' : number(summary[status])}</strong>
                  </button>
                ))}
              </div>
              <Card class="ticket-list">
                {array(data.entries).length ? (
                  <table aria-label="Support tickets">
                    <thead>
                      <tr>
                        <th>Subject / Creator</th>
                        <th>Status / Priority</th>
                        <th class="ticket-origin">Origin</th>
                      </tr>
                    </thead>
                    <tbody>
                      {array(data.entries).map((raw) => {
                        const row = object(raw),
                          key = string(row.ticketKey);
                        return (
                          <tr
                            key={key}
                            class={id === key ? 'selected' : ''}
                            onClick={(event) => {
                              event.currentTarget
                                .querySelector<HTMLButtonElement>('button')
                                ?.focus({ preventScroll: true });
                              void choose(key);
                            }}
                          >
                            <td>
                              <button
                                class="ticket-subject"
                                data-ticket-key={key}
                                aria-label={`Open ${key}: ${string(row.subject)}`}
                                aria-current={id === key ? 'true' : undefined}
                                onClick={(event) => {
                                  event.stopPropagation();
                                  void choose(key);
                                }}
                              >
                                <strong title={string(row.subject)}>
                                  {string(row.subject) || 'Untitled ticket'}
                                </strong>
                                <small>
                                  {key} · {string(row.creatorName) || 'Unknown creator'}
                                  <span class="ticket-origin-mobile">
                                    {' '}
                                    · {origin(row.originServerId)}
                                  </span>
                                </small>
                              </button>
                            </td>
                            <td>
                              <Badge tone={statusTone(row.status)}>{label(row.status)}</Badge>
                              <small class="ticket-priority">{label(row.priority)} priority</small>
                            </td>
                            <td class="ticket-origin">{origin(row.originServerId)}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                ) : (
                  <p class="state">No matching tickets. Try changing the filters.</p>
                )}
                <Pager page={page} total={number(data.total)} change={setPage} />
              </Card>
            </>
          );
        }}
      </AsyncBoundary>
      {id && (
        <TicketDrawer id={id} close={() => void choose('')}>
          <header class="ticket-header">
            <div>
              <small>{id}</small>
              <h2 id="ticket-heading" tabIndex={0}>
                {string(ticket.subject) || 'Ticket details'}
              </h2>
              {!!ticket.status && (
                <Badge tone={statusTone(ticket.status)}>{label(ticket.status)}</Badge>
              )}
            </div>
            <button
              autoFocus
              disabled={w.busy}
              aria-label="Close ticket"
              onClick={() => void choose('')}
            >
              Close
            </button>
          </header>
          <div
            class="ticket-conversation"
            key={id}
            tabIndex={0}
            role="region"
            aria-label="Ticket conversation and details"
          >
            <AsyncBoundary resource={detail}>
              {(raw) => {
                const data = object(raw),
                  ticket = object(data.ticket);
                return (
                  <>
                    <dl class="ticket-facts">
                      <div>
                        <dt>Priority</dt>
                        <dd>{label(ticket.priority)}</dd>
                      </div>
                      <div>
                        <dt>Creator</dt>
                        <dd>{string(ticket.creatorName) || 'Unknown creator'}</dd>
                      </div>
                      <div>
                        <dt>Origin</dt>
                        <dd>{origin(ticket.originServerId)}</dd>
                      </div>
                      <div>
                        <dt>Assignee</dt>
                        <dd>
                          {string(ticket.assigneeName) ||
                            (ticket.assigneeUuid ? 'Assigned staff' : 'Unassigned')}
                        </dd>
                      </div>
                    </dl>
                    <h3>Conversation</h3>
                    <div class="message-thread">
                      {array(data.messages).length ? (
                        [...array(data.messages)]
                          .sort(
                            (a, b) => number(object(a).createdAtMs) - number(object(b).createdAtMs),
                          )
                          .map((raw, index) => {
                            const message = object(raw);
                            return (
                              <article key={string(message.messageId) || index}>
                                <strong>
                                  {string(message.authorName) || label(message.authorType)}
                                </strong>
                                <small>{formatTime(message.createdAtMs)}</small>
                                <p class="preserve-lines">{string(message.text)}</p>
                              </article>
                            );
                          })
                      ) : (
                        <p class="muted">No messages yet.</p>
                      )}
                    </div>
                    <details>
                      <summary>Ticket history</summary>
                      {array(data.events).length ? (
                        array(data.events).map((raw, index) => {
                          const event = object(raw);
                          return (
                            <article class="ticket-event" key={string(event.eventId) || index}>
                              <strong>{label(event.eventType)}</strong> ·{' '}
                              {string(event.actorName) || 'System'}
                              <small>{formatTime(event.createdAtMs)}</small>
                              {(event.oldValue || event.newValue) && (
                                <p>
                                  {string(event.oldValue) || '—'} → {string(event.newValue) || '—'}
                                </p>
                              )}
                            </article>
                          );
                        })
                      ) : (
                        <p>No history yet.</p>
                      )}
                    </details>
                    <details>
                      <summary>Technical details</summary>
                      <DataView
                        data={Object.fromEntries(
                          Object.entries(ticket).map(([key, value]) => [
                            key,
                            key.endsWith('AtMs') ? formatTime(value) : value,
                          ]),
                        )}
                      />
                    </details>
                  </>
                );
              }}
            </AsyncBoundary>
          </div>
          <footer class="ticket-composer">
            {error && (
              <div class="ticket-error" role="alert">
                {error}{' '}
                <span>
                  Your draft is preserved. Refresh the ticket before retrying if its state may have
                  changed.
                </span>
              </div>
            )}
            <div class="ticket-reply">
              <label class="field">
                <span>Reply</span>
                <textarea
                  rows={2}
                  value={reply}
                  disabled={w.busy}
                  onInput={(e) => setReply(e.currentTarget.value)}
                />
              </label>
              <button
                class="primary"
                disabled={!canMutate || !reply.trim()}
                onClick={() => void mutate('reply')}
              >
                Send reply
              </button>
            </div>
            <div class="ticket-controls">
              <label class="field">
                <span id="ticket-operation-label">Ticket operation</span>
                <select
                  aria-labelledby="ticket-operation-label"
                  value={action}
                  disabled={w.busy}
                  onChange={(e) => {
                    setAction(e.currentTarget.value);
                    setValue('');
                  }}
                >
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
                    <option value={v}>{label(v)}</option>
                  ))}
                </select>
              </label>
              {needsValue && (
                <label class="field">
                  <span id="ticket-value-label">
                    {action === 'assign' ? 'Assignee UUID' : 'New ' + action}
                  </span>
                  {action === 'assign' ? (
                    <input
                      aria-labelledby="ticket-value-label"
                      value={value}
                      disabled={w.busy}
                      onInput={(e) => setValue(e.currentTarget.value)}
                    />
                  ) : (
                    <select
                      aria-labelledby="ticket-value-label"
                      value={value}
                      disabled={w.busy}
                      onChange={(e) => setValue(e.currentTarget.value)}
                    >
                      <option value="">Choose…</option>
                      {options.map((raw) => (
                        <option
                          value={typeof raw === 'object' ? string(object(raw).id) : string(raw)}
                        >
                          {typeof raw === 'object' ? string(object(raw).displayName) : label(raw)}
                        </option>
                      ))}
                    </select>
                  )}
                </label>
              )}
              <button
                disabled={!canMutate || (needsValue && !value.trim())}
                onClick={() => void mutate(action)}
              >
                Review change
              </button>
              <button disabled={w.busy || detail.loading} onClick={detail.refresh}>
                Refresh ticket
              </button>
            </div>
          </footer>
        </TicketDrawer>
      )}
    </>
  );
}
