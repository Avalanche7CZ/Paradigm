import { describe, it, expect } from 'vitest';
import {
  assignment,
  assignmentEqual,
  contextsOf,
  removal,
  wouldCycle,
  inheritedAssignments,
  nodeValid,
  type Group,
} from '../src/features/permissions/model';
const group = (name: string, parents: string[] = []): Group => ({
  name,
  parents,
  assignments: [],
  prefix: '',
  suffix: '',
  description: '',
  weight: 0,
});
describe('permissions domain views', () => {
  it('normalizes actual stored and runtime context envelopes', () => {
    expect(contextsOf({ contexts: { server: 'host', world: 'DIM7' } })).toEqual({
      server: 'host',
      world: 'DIM7',
    });
    expect(
      assignment({
        value: 'paradigm.home',
        id: 'a',
        contexts: { contexts: { server: 'host' } },
        denied: true,
      }).denied,
    ).toBe(true);
  });
  it('removes an exact assignment without node-wide matching', () => {
    const row = assignment({ id: 'remote', node: 'paradigm.home', contexts: { server: 'remote' } });
    expect(removal('group', 'member', row).body).toEqual({
      group: 'member',
      permission: 'paradigm.home',
      assignmentId: 'remote',
      scope: 'custom',
      contexts: { server: 'remote' },
    });
    expect(() => removal('group', 'member', { ...row, id: '' })).toThrow(/stable ID/);
  });
  it('keeps duplicate names with distinct contexts separate', () => {
    const a = assignment({ id: 'a', node: 'paradigm.home', contexts: { server: 'a' } });
    const b = assignment({ id: 'b', node: 'paradigm.home', contexts: { server: 'b' } });
    expect(assignmentEqual(a, b)).toBe(false);
  });
  it('prevents self and transitive cycles without looping existing corrupt graphs', () => {
    const groups = [group('a', ['b']), group('b', ['c']), group('c', ['b'])];
    expect(wouldCycle(groups, 'c', 'a')).toBe(true);
    expect(wouldCycle(groups, 'a', 'a')).toBe(true);
    expect(wouldCycle(groups, 'a', 'c')).toBe(false);
  });
  it('deduplicates ancestor traversal while preserving assignment IDs and sources', () => {
    const parent = { ...group('parent'), assignments: [assignment({ id: 'one', node: 'x' })] };
    const child = group('child', ['parent', 'parent']);
    expect(inheritedAssignments([parent, child], child)).toEqual([
      { ...parent.assignments[0], id: 'parent:one', source: 'parent' },
    ]);
  });
  it('allows custom nodes and validates backend syntax', () => {
    expect(nodeValid('custom.node:*')).toBe(true);
    expect(nodeValid('invented node')).toBe(false);
    expect(nodeValid('a'.repeat(256))).toBe(false);
  });
});

import {
  appendChanges,
  metadataPreview,
  trackPreview,
  type Change,
} from '../src/features/permissions/model';
it('rejects a duplicate bundled removal without admitting half its replacement', () => {
  const old: Change = {
    key: 'old',
    route: 'user/permission/remove',
    label: 'Remove',
    assignmentId: 'id',
    body: { user: 'a', assignmentId: 'id' },
  };
  expect(() =>
    appendChanges(
      [old],
      [
        {
          label: 'New',
          route: 'user/permission/add',
          body: { user: 'a', permission: 'x' },
          bundle: 'id',
        },
        { ...old, bundle: 'id' },
      ],
      () => 'new',
    ),
  ).toThrow(/already staged/);
  expect(old.key).toBe('old');
});
it('detects invalid positions caused by unstaging an earlier insertion', () => {
  const changes: Change[] = [
    { key: 'm', label: 'Move', route: 'track/move', body: { track: 't', group: 'a', position: 3 } },
  ];
  expect(trackPreview({ name: 't', groups: ['a', 'b'] }, changes).error).toMatch(/position/);
  expect(trackPreview({ name: 't', groups: ['a', 'b'] }, changes).ranks).toEqual(['a', 'b']);
});
it('projects only remaining metadata operations over persisted fields', () => {
  expect(
    metadataPreview({ description: 'saved', prefix: 'old', weight: 10 }, 'member', [
      {
        key: 'p',
        label: 'Set prefix',
        route: 'group/update',
        body: { group: 'member', metadata: { prefix: 'new' } },
      },
    ]),
  ).toEqual({
    description: 'saved',
    prefix: 'new',
    suffix: '',
    weight: '10',
    chatNameFormat: '',
    chatMessageFormat: '',
  });
});

it('stages and unstages optional group chat formats over saved metadata', () => {
  const initial = { chatNameFormat: '<bold>{player_name}</bold>' };
  const changes = [
    {
      key: 'style',
      label: 'style',
      route: 'group/update',
      body: { group: 'vip', metadata: { chatMessageFormat: '<color:red>{message}</color>' } },
    },
  ];
  expect(metadataPreview(initial, 'vip', changes).chatMessageFormat).toBe(
    '<color:red>{message}</color>',
  );
  expect(metadataPreview(initial, 'vip', []).chatMessageFormat).toBe('');
  expect(metadataPreview(initial, 'vip', []).chatNameFormat).toBe('<bold>{player_name}</bold>');
});
