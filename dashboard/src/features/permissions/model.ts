import { array, object, string, type Json, type Obj } from '../../core/types';
export type View = 'groups' | 'users' | 'tracks' | 'nodes';
export interface Assignment {
  id: string;
  node: string;
  denied: boolean;
  contexts: Record<string, string>;
  expiresAtMs: number | null;
  source?: string;
}
export interface Change {
  key: string;
  label: string;
  route: string;
  body: Obj;
  assignmentId?: string;
  bundle?: string;
}
export interface Group {
  name: string;
  description: string;
  prefix: string;
  suffix: string;
  weight: number;
  parents: string[];
  assignments: Assignment[];
}
export function contextsOf(raw: Json | undefined): Record<string, string> {
  const value = object(raw);
  const result = object(value.contexts || value);
  return Object.fromEntries(
    Object.entries(result).filter(([, v]) => typeof v === 'string'),
  ) as Record<string, string>;
}
export function assignment(raw: Json): Assignment {
  const v = object(raw);
  return {
    id: string(v.id || v.assignmentId),
    node: string(v.node || v.value || v.permission),
    denied: v.denied === true,
    contexts: contextsOf(v.contexts || v.contextSet),
    expiresAtMs: typeof v.expiresAtMs === 'number' ? v.expiresAtMs : null,
    source: string(v.source),
  };
}
export function group(raw: Json): Group {
  const v = object(raw);
  return {
    name: string(v.name),
    description: string(v.description),
    prefix: string(v.prefix),
    suffix: string(v.suffix),
    weight: Number(v.weight) || 0,
    parents: array(v.parents).map(string),
    assignments: array(v.assignments).map(assignment),
  };
}
export function contextLabel(contexts: Record<string, string>) {
  return (
    Object.entries(contexts)
      .map(([k, v]) => `${k}: ${v}`)
      .join(' · ') || 'Global'
  );
}
export function nodeValid(node: string) {
  return node.length > 0 && node.length <= 255 && /^[a-z0-9_.*:-]+$/.test(node);
}
export function subjectValid(name: string) {
  return name.length > 0 && name.length <= 64 && /^[A-Za-z0-9_.-]+$/.test(name);
}
export function assignmentEqual(a: Assignment, b: Assignment) {
  return (
    a.node === b.node &&
    a.denied === b.denied &&
    a.expiresAtMs === b.expiresAtMs &&
    JSON.stringify(Object.entries(a.contexts).sort()) ===
      JSON.stringify(Object.entries(b.contexts).sort())
  );
}
export function wouldCycle(groups: Group[], child: string, parent: string) {
  const visited = new Set<string>();
  const visit = (id: string): boolean => {
    if (id === child) return true;
    if (visited.has(id)) return false;
    visited.add(id);
    return (groups.find((g) => g.name === id)?.parents || []).some(visit);
  };
  return visit(parent);
}
export function inheritedAssignments(groups: Group[], selected: Group) {
  const visited = new Set<string>([selected.name]);
  const rows: Assignment[] = [];
  function visit(id: string) {
    if (visited.has(id)) return;
    visited.add(id);
    const parent = groups.find((g) => g.name === id);
    if (!parent) return;
    rows.push(...parent.assignments.map((a) => ({ ...a, id: `${id}:${a.id}`, source: id })));
    parent.parents.forEach(visit);
  }
  selected.parents.forEach(visit);
  return rows;
}
export function removal(
  kind: 'group' | 'user',
  target: string,
  row: Assignment,
  membership = false,
): Omit<Change, 'key'> {
  if (!row.id) throw new Error('This assignment has no stable ID. Reload before removing it.');
  return {
    label: `Remove ${membership ? 'membership' : 'permission'} ${row.node} · ${contextLabel(row.contexts)}`,
    route: `${kind}/${membership ? 'group' : 'permission'}/remove`,
    body: {
      [kind]: target,
      [membership ? 'group' : 'permission']: row.node,
      assignmentId: row.id,
      scope: 'custom',
      contexts: row.contexts,
    },
    assignmentId: row.id,
  };
}
export const expiryLabel = (ms: number | null) =>
  ms === null
    ? 'Permanent'
    : (ms <= Date.now() ? 'Expired · ' : '') + new Date(ms).toLocaleString();

export function describeChange(change: Change) {
  const labels: Record<string, string> = {
    user: 'User UUID',
    group: 'Group',
    track: 'Track',
    target: 'New name',
    position: 'Position',
    permission: 'Permission',
    parent: 'Parent group',
    assignmentId: 'Assignment ID',
    duration: 'Duration',
    dontAddToFirst: 'Keep unranked users unassigned',
    dontRemoveFromFirst: 'Keep first rank on demotion',
  };
  const lines = Object.entries(change.body).flatMap(([key, value]) => {
    if (key === 'scope') return [];
    if (key === 'contexts') return [`Contexts: ${contextLabel(contextsOf(value))}`];
    if (key === 'expiresAtMs')
      return [`Expiration: ${expiryLabel(typeof value === 'number' ? value : null)}`];
    if (key === 'permanent') return ['Expiration: permanent'];
    if (key === 'denied') return [`Value: ${value === true ? 'Denied' : 'Allowed'}`];
    if (key === 'metadata')
      return Object.entries(object(value)).map(
        ([field, text]) => `${field}: ${JSON.stringify(string(text))}`,
      );
    return [`${labels[key] || key}: ${String(value)}`];
  });
  return `${change.label}\n${lines.join('\n')}`;
}

export function trackPreview(track: Obj, changes: Change[]) {
  const ranks = array(track.groups).map(string);
  let unavailable = false;
  let error = '';
  for (const change of changes) {
    if (change.body.track !== track.name || !change.route.startsWith('track/')) continue;
    const rank = string(change.body.group);
    const position = Number(change.body.position);
    if (unavailable) {
      error = 'A staged operation targets a renamed or deleted track.';
      break;
    }
    switch (change.route) {
      case 'track/rename':
      case 'track/delete':
        unavailable = true;
        break;
      case 'track/clear':
        ranks.splice(0);
        break;
      case 'track/remove':
        if (!ranks.includes(rank)) error = `${rank} is no longer in the staged track.`;
        else ranks.splice(ranks.indexOf(rank), 1);
        break;
      case 'track/move':
      case 'track/insert':
        if (
          !Number.isInteger(position) ||
          position < 1 ||
          position > ranks.length + (change.route === 'track/insert' ? 1 : 0) ||
          (change.route === 'track/move' ? !ranks.includes(rank) : ranks.includes(rank))
        ) {
          error = `Review the staged position for ${rank}; earlier changes may have been unstaged.`;
          break;
        }
        if (change.route === 'track/move') ranks.splice(ranks.indexOf(rank), 1);
        ranks.splice(position - 1, 0, rank);
        break;
    }
    if (error) break;
  }
  return { ranks, unavailable, error };
}

export function metadataPreview(initial: Obj, id: string, changes: Change[]) {
  const values = {
    description: string(initial.description),
    prefix: string(initial.prefix),
    suffix: string(initial.suffix),
    weight: String(Number(initial.weight) || 0),
  };
  for (const change of changes) {
    if (change.route !== 'group/update' || change.body.group !== id) continue;
    for (const [key, value] of Object.entries(object(change.body.metadata)))
      if (key in values) values[key as keyof typeof values] = string(value);
  }
  return values;
}

function canonical(value: Json): string {
  return JSON.stringify(
    value && typeof value === 'object' && !Array.isArray(value)
      ? Object.fromEntries(
          Object.entries(value)
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([key, v]) => [key, canonical(v)]),
        )
      : value,
  );
}

export function appendChanges(
  previous: Change[],
  values: Omit<Change, 'key'>[],
  key: () => string,
): Change[] {
  const pending = [...previous];
  for (const value of values) {
    if (
      pending.some(
        (c) =>
          (value.assignmentId && c.assignmentId === value.assignmentId) ||
          (c.route === value.route && canonical(c.body) === canonical(value.body)),
      )
    )
      throw new Error('This operation is already staged. Unstage it before replacing it.');
    pending.push({ ...value, key: key() });
  }
  return pending;
}
