import { readFileSync } from 'node:fs';
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/preact';
import { useState } from 'preact/hooks';
import { WorkspaceContext, type Workspace } from '../src/core/context';
import { Definitions } from '../src/features/definitions';
import { TrackEditor } from '../src/features/permissions/tracks';
import { WorkflowContext } from '../src/features/permissions/workflow';
import { type Change, type Group } from '../src/features/permissions/model';
import { ActionList, actionProblems, actionTemplate } from '../src/components/actions';
import type { Obj } from '../src/core/types';
afterEach(cleanup);
it('all canonical templates use fields accepted by the shared Java fixture', () => {
  for (const sample of fixtures.actions as Obj[]) {
    const template = actionTemplate(String(sample.type));
    expect(
      Object.keys(template).filter((key) => !(key in sample)),
      String(sample.type),
    ).toEqual([]);
  }
});
function workspace(request = vi.fn()): Workspace {
  return {
    api: { request } as unknown as Workspace['api'],
    session: {
      authenticated: true,
      requireLogin: true,
      capabilities: { pages: {}, mutations: { menus: true, customCommands: true }, config: {} },
    },
    servers: [],
    networkActive: false,
    selected: 'host',
    hostId: 'host',
    select: vi.fn(),
    navigate: vi.fn(),
    confirm: vi.fn(async () => true),
    notify: vi.fn(),
    mutate: async (task) => task(),
    setDirty: vi.fn(),
    busy: false,
  };
}
const fixtures = JSON.parse(readFileSync('tests/fixtures/definitions.json', 'utf8'));
const menu: Obj = fixtures.menuResponse.definition;
const command: Obj = fixtures.commandResponse.command.command;
function definitions(kind: 'menus' | 'customCommands') {
  const request = vi.fn(async (path: string, body?: unknown) => {
    if (body) return {};
    if (path.includes('/item'))
      return kind === 'menus'
        ? { definition: structuredClone(menu) }
        : { command: { name: 'sample', command: structuredClone(command), actionCount: 1 } };
    return kind === 'menus'
      ? {
          menus: [{ id: 'shop', title: 'Shop' }],
          actionTypes: ['message'],
          conditionTypes: ['has_permission'],
        }
      : {
          commands: [{ name: 'sample', description: 'Original' }],
          actionTypes: ['message', 'conditional'],
          conditionTypes: ['has_permission'],
        };
  });
  render(
    <WorkspaceContext.Provider value={workspace(request)}>
      <Definitions kind={kind} />
    </WorkspaceContext.Provider>,
  );
  return request;
}
it('displays persisted slots and edits amount without losing attributes', async () => {
  const request = definitions('menus');
  fireEvent.click(await screen.findByRole('button', { name: /shop Shop/ }));
  const slot = await screen.findByRole('button', { name: 'Slot 17', exact: true });
  expect(slot.textContent).toContain('diamond');
  fireEvent.click(slot);
  fireEvent.input(screen.getByLabelText('amount'), { target: { value: '8' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save definition' }));
  await waitFor(() =>
    expect(request.mock.calls.some(([path]) => path.endsWith('/update'))).toBe(true),
  );
  const body = request.mock.calls.find(([path]) => path.endsWith('/update'))![1] as {
    definition: Obj;
  };
  expect(body.definition).toEqual({
    ...menu,
    slots: [
      {
        ...(menu.slots as Obj[])[0],
        item: { ...((menu.slots as Obj[])[0].item as Obj), amount: 8 },
      },
    ],
  });
  expect(screen.queryByText('Clickable If')).toBeNull();
});
it('creates distinct nonzero slots with backend field names', async () => {
  const request = definitions('menus');
  fireEvent.click(await screen.findByRole('button', { name: /shop Shop/ }));
  fireEvent.click(await screen.findByRole('button', { name: 'Slot 5', exact: true }));
  fireEvent.input(screen.getByLabelText('itemId'), { target: { value: 'minecraft:gold_ingot' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save definition' }));
  await waitFor(() =>
    expect(request.mock.calls.some(([path]) => path.endsWith('/update'))).toBe(true),
  );
  const body = request.mock.calls.find(([path]) => path.endsWith('/update'))![1] as {
    definition: Obj;
  };
  expect((body.definition.slots as Obj[]).map((s) => s.slot)).toEqual([17, 5]);
  expect((body.definition.slots as Obj[])[1].item).toMatchObject({ amount: 1 });
});
it('unwraps CommandView and preserves nested and unknown command fields', async () => {
  const request = definitions('customCommands');
  fireEvent.click(await screen.findByRole('button', { name: /sample Original/ }));
  const field = await screen.findByLabelText('Description');
  expect((field as HTMLInputElement).value).toBe('Original');
  fireEvent.input(field, { target: { value: 'Edited' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save definition' }));
  await waitFor(() =>
    expect(request.mock.calls.some(([path]) => path.endsWith('/update'))).toBe(true),
  );
  expect(
    (request.mock.calls.find(([path]) => path.endsWith('/update'))![1] as { command: Obj }).command,
  ).toEqual({ ...command, description: 'Edited' });
});
it('creates new commands from the proper document without dropping defaults', async () => {
  const request = definitions('customCommands');
  fireEvent.click(await screen.findByRole('button', { name: 'New command' }));
  expect(((await screen.findByLabelText('Name')) as HTMLInputElement).value).toBe('');
  expect(screen.getByText('Command arguments')).toBeTruthy();
  expect(screen.getByText('Add action')).toBeTruthy();
  expect(screen.getByLabelText('permissionErrorMessage')).toBeTruthy();
  fireEvent.input(screen.getByLabelText('Name'), { target: { value: 'fresh' } });
  fireEvent.click(screen.getByRole('button', { name: 'Add action' }));
  fireEvent.click(document.querySelector('.action-list .list-editor > button')!);
  fireEvent.input(screen.getByLabelText('Text 1'), { target: { value: 'Hello' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create definition' }));
  await waitFor(() => expect(request.mock.calls.some(([path]) => path.endsWith('/create'))).toBe(true));
  expect((request.mock.calls.find(([path]) => path.endsWith('/create'))![1] as { command: Obj }).command).toMatchObject({
    name: 'fresh', requirePermission: false, permissionErrorMessage: '', arguments: [],
    actions: [{ type: 'message', text: ['Hello'] }],
  });
});
it('uses runtime title, sound and teleport template fields', () => {
  expect(actionTemplate('title')).toEqual({ type: 'title', text: ['', ''] });
  expect(actionTemplate('sound')).toEqual({
    type: 'sound',
    text: ['minecraft:entity.experience_orb.pickup 1 1 master'],
  });
  expect(actionTemplate('teleport')).toEqual({ type: 'teleport', x: 0, y: 64, z: 0 });
});
it('recomputes track order after unstaging', () => {
  const groups = ['a', 'b', 'c'].map((name): Group => ({
    name,
    description: '',
    prefix: '',
    suffix: '',
    weight: 0,
    parents: [],
    assignments: [],
  }));
  function Harness() {
    const [changes, setChanges] = useState<Change[]>([]);
    return (
      <WorkflowContext.Provider
        value={{
          editable: true,
          changes,
          stage: (next) => {
            setChanges([
              ...changes,
              ...next.map((c, i) => ({ ...c, key: String(changes.length + i) })),
            ]);
            return true;
          },
          open: vi.fn(),
          draft: vi.fn(),
          draftDirty: false,
          refresh: vi.fn(),
        }}
      >
        <TrackEditor track={{ name: 'staff', groups: ['a', 'b', 'c'] }} groups={groups} />
        <button onClick={() => setChanges(changes.slice(1))}>Unstage first</button>
      </WorkflowContext.Provider>
    );
  }
  const rendered = render(<Harness />);
  fireEvent.click(screen.getByRole('button', { name: 'Move b up' }));
  expect([...rendered.container.querySelectorAll('li strong')].map((el) => el.textContent)).toEqual(
    ['b', 'a', 'c'],
  );
  fireEvent.click(screen.getByRole('button', { name: 'Unstage first' }));
  expect([...rendered.container.querySelectorAll('li strong')].map((el) => el.textContent)).toEqual(
    ['a', 'b', 'c'],
  );
});

it('metadata unstaging restores persisted fields while retaining independent unstaged edits', async () => {
  const { TargetEditor } = await import('../src/features/permissions/targets');
  const initial = {
    name: 'member',
    description: 'Saved',
    prefix: 'Old',
    suffix: '',
    weight: 10,
    assignments: [],
    parents: [],
  };
  function Harness() {
    const [changes, setChanges] = useState<Change[]>([]);
    return (
      <WorkspaceContext.Provider value={workspace(vi.fn(async () => ({ group: initial })))}>
        <WorkflowContext.Provider
          value={{
            editable: true,
            changes,
            stage: (values) => {
              setChanges([...changes, ...values.map((v, i) => ({ ...v, key: String(i) }))]);
              return true;
            },
            open: vi.fn(),
            draft: vi.fn(),
            draftDirty: false,
            refresh: vi.fn(),
          }}
        >
          <TargetEditor
            kind="group"
            target="member"
            label=""
            groups={[]}
            tracks={[]}
            keys={[]}
            revision={0}
            selectUser={vi.fn()}
          />
          <button onClick={() => setChanges([])}>Unstage metadata</button>
        </WorkflowContext.Provider>
      </WorkspaceContext.Provider>
    );
  }
  render(<Harness />);
  fireEvent.click(await screen.findByRole('button', { name: 'Metadata' }));
  const field = screen.getByLabelText('Description') as HTMLInputElement;
  fireEvent.input(field, { target: { value: 'Staged' } });
  fireEvent.click(screen.getByRole('button', { name: 'Stage metadata changes' }));
  expect(field.value).toBe('Staged');
  fireEvent.input(screen.getByLabelText('Group weight'), { target: { value: '20' } });
  fireEvent.click(screen.getByRole('button', { name: 'Unstage metadata' }));
  await waitFor(() => expect(field.value).toBe('Saved'));
  expect((screen.getByLabelText('Group weight') as HTMLInputElement).value).toBe('20');
});

it('warns about unsafe transaction tails and accepts supported item/group notifications', () => {
  expect(
    actionProblems([
      { type: 'consume_items', items: ['minecraft:diamond 1'] },
      { type: 'run_console', commands: ['op Alice'] },
    ])[0],
  ).toMatch(/cannot participate/);
  expect(
    actionProblems([
      { type: 'consume_items', items: ['minecraft:diamond 1'] },
      { type: 'add_group', group: 'member' },
      { type: 'message', text: ['Done'] },
    ]),
  ).toEqual([]);
});

it('keeps unsupported legacy action fields in JSON while withholding misleading controls', () => {
  const change = vi.fn();
  render(<ActionList value={[{ type: 'teleport', world: 'legacy-world', x: 1, y: 64, z: 3 }]} change={change} types={['teleport']} conditions={[]} disabled={false} />);
  expect(screen.queryByLabelText('World')).toBeNull();
  expect(screen.getByRole('alert').textContent).toContain('current world');
  fireEvent.input(screen.getByLabelText('X'), { target: { value: '2' } });
  expect(change.mock.calls[0][0][0]).toEqual({ type: 'teleport', world: 'legacy-world', x: 2, y: 64, z: 3 });
});
