import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, expect, it, vi } from 'vitest';
import { ApiClient } from '../src/core/api';
import { WorkspaceContext, type Workspace } from '../src/core/context';
import { AssignmentEditor } from '../src/features/permissions/assignments';
import { WorkflowContext } from '../src/features/permissions/workflow';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it('selects a discovered anonymous class node and stages its literal value', async () => {
  Object.assign(HTMLDialogElement.prototype, {
    showModal(this: HTMLDialogElement) {
      this.open = true;
    },
    close(this: HTMLDialogElement) {
      this.open = false;
    },
  });
  try {
    const node = 'network.arcturus.tokens.forge17.arcturustokensmod$1';
    const api = new ApiClient();
    const request = vi.spyOn(api, 'request').mockResolvedValue({
      nodes: [{ node, description: 'Crucible wrapper permission for /tokens' }],
    });
    const stage = vi.fn(() => true);
    const workspace: Workspace = {
      api,
      selected: '',
      servers: [],
      hostId: '',
      networkActive: false,
      busy: false,
      session: { authenticated: true, requireLogin: false },
      select: vi.fn(),
      navigate: vi.fn(),
      confirm: vi.fn(),
      notify: vi.fn(),
      setDirty: vi.fn(),
      mutate: (task) => task(),
    };
    render(
      <WorkspaceContext.Provider value={workspace}>
        <WorkflowContext.Provider
          value={{
            editable: true,
            changes: [],
            stage,
            open: vi.fn(),
            draft: vi.fn(),
            draftDirty: false,
            refresh: vi.fn(),
          }}
        >
          <AssignmentEditor
            kind="user"
            target="00000000-0000-0000-0000-000000000001"
            keys={[]}
            groups={[]}
            existing={[]}
            close={vi.fn()}
          />
        </WorkflowContext.Provider>
      </WorkspaceContext.Provider>,
    );

    fireEvent.input(screen.getByRole('combobox', { name: 'Permission nodes' }), {
      target: { value: 'arcturustokensmod' },
    });
    fireEvent.click(await screen.findByRole('option', { name: /arcturustokensmod\$1/ }));
    expect(request.mock.calls[0][0]).toContain('/api/permissions/nodes?');
    expect(screen.getByRole('button', { name: `Unselect ${node}` })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Stage assignments' }));
    expect(stage).toHaveBeenCalledWith([
      expect.objectContaining({
        route: 'user/permission/add',
        body: expect.objectContaining({
          user: '00000000-0000-0000-0000-000000000001',
          permission: node,
          denied: false,
        }),
      }),
    ]);
  } finally {
    cleanup();
    Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal');
    Reflect.deleteProperty(HTMLDialogElement.prototype, 'close');
  }
});
