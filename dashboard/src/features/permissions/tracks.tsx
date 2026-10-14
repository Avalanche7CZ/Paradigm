import { useLayoutEffect, useState } from 'preact/hooks';
import { array, string, type Obj } from '../../core/types';
import { Empty } from '../../components/ui';
import { Popup } from './controls';
import { subjectValid, type Group } from './model';
import { useWorkflow } from './workflow';
export function TrackEditor({ track, groups }: { track: Obj; groups: Group[] }) {
  const flow = useWorkflow();
  const name = string(track.name);
  const [ranks, setRanks] = useState(array(track.groups).map(string));
  const [next, setNext] = useState('');
  const [position, setPosition] = useState(1);
  const [rename, setRename] = useState<'rename' | 'clone'>();
  const [target, setTarget] = useState('');
  const [error, setError] = useState('');
  useLayoutEffect(() => {
    flow.draft(!!next);
    return () => flow.draft(false);
  }, [next]);
  function move(rank: string, index: number) {
    flow.stage([
      {
        label: `Move ${rank} to position ${index + 1} in ${name}`,
        route: 'track/move',
        body: { track: name, group: rank, position: index + 1 },
      },
    ]);
    const changed = ranks.filter((r) => r !== rank);
    changed.splice(index, 0, rank);
    setRanks(changed);
  }
  const modal = (value: typeof rename) => {
    setRename(value);
    flow.open(!!value);
    setTarget('');
  };
  return (
    <section class="permission-detail">
      <header class="permission-detail-header">
        <div>
          <span class="eyebrow">Ordered track</span>
          <h2>{name}</h2>
          <p class="muted">{ranks.length} ranks · lowest to highest</p>
        </div>
        <div class="actions">
          <button disabled={!flow.editable} onClick={() => modal('rename')}>
            Rename
          </button>
          <button disabled={!flow.editable} onClick={() => modal('clone')}>
            Clone
          </button>
          <button
            class="danger"
            disabled={!flow.editable}
            onClick={() =>
              flow.stage([
                { label: `Delete track ${name}`, route: 'track/delete', body: { track: name } },
              ])
            }
          >
            Delete
          </button>
        </div>
      </header>
      <p class="help">
        Promotion follows this order. Changes are staged individually; positions start at 1.
      </p>
      <ol class="permission-ranks">
        {ranks.map((rank, index) => (
          <li>
            <span class="permission-rank-number">{index + 1}</span>
            <strong>{rank}</strong>
            <span class="actions">
              <button
                aria-label={`Move ${rank} up`}
                disabled={!flow.editable || index === 0}
                onClick={() => move(rank, index - 1)}
              >
                ↑
              </button>
              <button
                aria-label={`Move ${rank} down`}
                disabled={!flow.editable || index === ranks.length - 1}
                onClick={() => move(rank, index + 1)}
              >
                ↓
              </button>
              <button
                class="danger"
                disabled={!flow.editable}
                onClick={() => {
                  flow.stage([
                    {
                      label: `Remove ${rank} from ${name}`,
                      route: 'track/remove',
                      body: { track: name, group: rank },
                    },
                  ]);
                  setRanks(ranks.filter((r) => r !== rank));
                }}
              >
                Remove
              </button>
            </span>
          </li>
        ))}
      </ol>
      {!ranks.length && (
        <Empty title="No ranks yet">Add existing groups to define a promotion path.</Empty>
      )}
      <form
        class="toolbar"
        onSubmit={(e) => {
          e.preventDefault();
          if (
            !groups.some((g) => g.name === next) ||
            ranks.includes(next) ||
            position < 1 ||
            position > ranks.length + 1
          )
            return;
          flow.stage([
            {
              label: `Insert ${next} at position ${position} in ${name}`,
              route: 'track/insert',
              body: { track: name, group: next, position },
            },
          ]);
          const changed = [...ranks];
          changed.splice(position - 1, 0, next);
          setRanks(changed);
          setNext('');
        }}
      >
        <label class="field">
          <span>Existing group</span>
          <select
            value={next}
            disabled={!flow.editable}
            onChange={(e) => setNext(e.currentTarget.value)}
          >
            <option value="">Choose rank…</option>
            {groups
              .filter((g) => !ranks.includes(g.name))
              .map((g) => (
                <option>{g.name}</option>
              ))}
          </select>
        </label>
        <label class="field">
          <span>Position</span>
          <input
            type="number"
            min={1}
            max={ranks.length + 1}
            value={position}
            disabled={!flow.editable}
            onInput={(e) => setPosition(Number(e.currentTarget.value))}
          />
        </label>
        <button class="primary" disabled={!flow.editable || !next}>
          Stage rank
        </button>
        <button
          type="button"
          class="danger"
          disabled={!flow.editable || !ranks.length}
          onClick={() => {
            flow.stage([
              {
                label: `Clear all ranks from ${name}`,
                route: 'track/clear',
                body: { track: name },
              },
            ]);
            setRanks([]);
          }}
        >
          Clear track
        </button>
      </form>
      {rename && (
        <Popup
          title={rename === 'clone' ? 'Clone track' : 'Rename track'}
          close={() => modal(undefined)}
        >
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (!subjectValid(target)) {
                setError('Use a valid track name, at most 64 characters.');
                return;
              }
              flow.stage([
                {
                  label: `${rename} ${name} → ${target}`,
                  route: `track/${rename}`,
                  body: { track: name, target },
                },
              ]);
              modal(undefined);
            }}
          >
            <label class="field">
              <span>New track name</span>
              <input
                autoFocus
                required
                value={target}
                onInput={(e) => setTarget(e.currentTarget.value)}
              />
            </label>
            {error && (
              <p role="alert" class="text-error">
                {error}
              </p>
            )}
            <button class="primary" disabled={!flow.editable}>
              Stage {rename}
            </button>
          </form>
        </Popup>
      )}
    </section>
  );
}
