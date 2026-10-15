import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/preact';
import { ApiClient, ApiError, query } from '../src/core/api';
import { useResource } from '../src/core/context';
import {
  applicationState,
  configurationTarget,
  remainingEdits,
  validateField,
} from '../src/core/model';
import { ALL, type Field, type RemoteSnapshot, type Section } from '../src/core/types';
import { previewSegments } from '../src/components/formatting';
import { parseRestartAction, serializeRestartAction } from '../src/components/structured-settings';
import { actionTemplate } from '../src/components/actions';
import { pages, route } from '../src/core/navigation';
afterEach(cleanup);
const field: Field = {
  key: 'test',
  category: 'motd',
  label: 'Text',
  help: '',
  type: 'INTEGER',
  value: { value: 1, secret: false, set: true },
  defaultValue: { value: 1, secret: false, set: true },
  editable: true,
  masked: false,
  required: true,
  nullable: false,
  trim: true,
  allowEmptyItems: false,
  min: 1,
  max: 5,
  options: [],
  reloadBehavior: 'IMMEDIATE',
  riskLevel: 'LOW',
};
const section: Section = {
  section: 'motd',
  adopted: true,
  networkRevision: 2,
  serverRevision: 1,
  appliedGlobalRevision: 2,
  appliedServerRevision: 1,
  appliedAtMs: 1,
  lastError: '',
};
const snapshot = { schemaCompatible: true, online: true } as RemoteSnapshot;
describe('configuration state', () => {
  it('separates network default from a server override target', () => {
    expect(configurationTarget(ALL, 'host', 'NETWORK')).toEqual({
      serverId: 'host',
      scope: 'NETWORK',
    });
    expect(configurationTarget('remote', 'host', 'SERVER').serverId).toBe('remote');
    expect(() => configurationTarget(ALL, 'host', 'SERVER')).toThrow();
    expect(() => configurationTarget('remote', 'host', 'LOCAL')).toThrow();
  });
  it('requires an actual acknowledgment before showing applied', () => {
    expect(applicationState(snapshot, section)).toBe('Applied configuration');
    expect(applicationState(snapshot, { ...section, appliedAtMs: 0 })).toBe(
      'Pending synchronization',
    );
    expect(applicationState(snapshot, { ...section, serverRevision: 2 })).toBe(
      'Pending synchronization',
    );
  });
  it('distinguishes offline, incompatible and rejected states', () => {
    expect(applicationState({ ...snapshot, online: false }, section)).toMatch(/Offline/);
    expect(applicationState({ ...snapshot, schemaCompatible: false }, section)).toBe(
      'Incompatible',
    );
    expect(applicationState(snapshot, { ...section, lastError: 'failed' })).toBe('Rejected');
  });
  it('retains rejected edits after partial acceptance', () =>
    expect(remainingEdits({ a: 1, b: 2 }, ['a'])).toEqual({ b: 2 }));
  it('validates bounds, empty values and typed lists', () => {
    expect(validateField(field, 0)).toMatch(/Minimum/);
    expect(validateField(field, Infinity)).toMatch(/finite/);
    expect(validateField(field, 1.5)).toMatch(/whole/);
    expect(validateField(field, 3)).toBeUndefined();
    expect(validateField({ ...field, type: 'STRING_LIST' }, [''])).toMatch(/empty/);
    expect(validateField({ ...field, type: 'STRING_LIST' }, 'oops')).toMatch(/list/);
  });
});
describe('API boundary', () => {
  it('sends csrf and a captured target for a mutation', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ ok: true, data: { saved: true }, warnings: [] })),
      );
    vi.stubGlobal('fetch', fetch);
    const api = new ApiClient();
    api.csrf = 'csrf';
    await api.request('/api/config/patch', { revision: 'r1' }, undefined, 'host');
    expect(fetch.mock.calls[0][1]).toMatchObject({
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'X-Paradigm-CSRF': 'csrf', 'X-Paradigm-Server': 'host' },
      body: '{"revision":"r1"}',
    });
  });
  it('preserves conflict code and partial validation data', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            ok: false,
            error: { code: 'stale_revision', message: 'Changed' },
            data: { accepted: ['a'] },
          }),
          { status: 409 },
        ),
      ),
    );
    await expect(new ApiClient().request('/api/config/patch', {})).rejects.toMatchObject({
      status: 409,
      code: 'stale_revision',
      data: { accepted: ['a'] },
    });
  });
  it('expires the session on authenticated 401s, not failed login', async () => {
    const expired = vi.fn();
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation(() =>
          Promise.resolve(
            new Response(
              JSON.stringify({ ok: false, error: { code: 'unauthorized', message: 'Expired' } }),
              { status: 401 },
            ),
          ),
        ),
    );
    const api = new ApiClient(expired);
    await expect(api.request('/api/players')).rejects.toBeInstanceOf(ApiError);
    expect(expired).toHaveBeenCalledTimes(1);
    await expect(api.request('/api/auth/login', {})).rejects.toThrow();
    expect(expired).toHaveBeenCalledTimes(1);
  });
  it('url encodes filters and omits empty values', () =>
    expect(query('/api/players', { query: 'a&b', page: 2, status: '' })).toBe(
      '/api/players?query=a%26b&page=2',
    ));
  it('reports transport and unreadable response errors honestly', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    await expect(new ApiClient().request('/api/players')).rejects.toMatchObject({
      code: 'connection_failed',
    });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>error')));
    await expect(new ApiClient().request('/api/players')).rejects.toMatchObject({
      code: 'invalid_response',
    });
  });
});
describe('request lifetime', () => {
  it('cancels superseded reads and ignores a stale reply', async () => {
    let resolveOld: (v: string) => void = () => {};
    let signalOld: AbortSignal | undefined;
    function Probe({ target }: { target: string }) {
      const resource = useResource(
        (signal) =>
          target === 'old'
            ? new Promise<string>((resolve) => {
                resolveOld = resolve;
                signalOld = signal;
              })
            : Promise.resolve('new server'),
        target,
      );
      return <div>{resource.data || 'loading'}</div>;
    }
    const view = render(<Probe target="old" />);
    view.rerender(<Probe target="new" />);
    await screen.findByText('new server');
    expect(signalOld?.aborted).toBe(true);
    resolveOld('WRONG SERVER');
    await waitFor(() => expect(screen.queryByText('WRONG SERVER')).toBeNull());
  });
  it('renders errors and allows a fresh retry', async () => {
    let count = 0;
    function Probe() {
      const resource = useResource(
        () =>
          ++count === 1
            ? Promise.reject(new Error('Database unavailable'))
            : Promise.resolve('Recovered'),
        'same',
      );
      return (
        <div>
          {resource.error?.message || resource.data}
          <button onClick={resource.refresh}>Retry</button>
        </div>
      );
    }
    render(<Probe />);
    await screen.findByText('Database unavailable');
    screen.getByText('Retry').click();
    await screen.findByText('Recovered');
  });
});
describe('preview and feature inventory', () => {
  it('treats html as plain text and never claims unsupported formatting', () => {
    const result = previewSegments('<img src=x onerror=alert(1)>Hello <bold>world</bold>');
    expect(result.segments.map((s) => s.text).join('')).toContain('<img src=x onerror=alert(1)>');
    expect(result.unsupported).toContain('img src=x onerror=alert(1)');
    expect(result.segments.find((s) => s.text === 'world')?.style.fontWeight).toBe(700);
    expect(previewSegments('<gradient:red:blue>Hi</gradient>').unsupported).toContain('gradient');
  });
  it('has every existing management route including tickets', () => {
    for (const id of [
      'overview',
      'servers',
      'players',
      'general',
      'motd',
      'chat',
      'announcements',
      'restart',
      'teleports',
      'tablist',
      'menus',
      'holograms',
      'customCommands',
      'commands',
      'cooldowns',
      'permissions',
      'moderation',
      'tickets',
      'storage',
      'storageConfig',
      'audit',
      'discord',
      'dashboard',
    ])
      expect(pages.some((p) => p.id === id)).toBe(true);
    expect(route('#tickets')).toBe('tickets');
    expect(route('#invalid')).toBe('overview');
  });
  it('keeps registered menu and conditional action fields', () => {
    expect(actionTemplate('open_menu')).toEqual({ type: 'open_menu', menu: '' });
    expect(actionTemplate('conditional')).toMatchObject({
      conditions: [],
      on_success: [],
      on_failure: [],
    });
    expect(actionTemplate('await_input')).toMatchObject({
      on_success: [],
      on_failure: [],
      timeout: 60,
    });
  });
});

describe('structured configuration formats', () => {
  it('round trips native restart action encodings without changing commands', () => {
    for (const text of ['30 | broadcast Hello', '5 | [asPlayer] spawn', '0 | save-all']) {
      expect(serializeRestartAction(parseRestartAction(text)!)).toBe(text);
    }
    expect(parseRestartAction('broken original')).toBeUndefined();
  });
  it('rejects duplicate clock times and missing world identifiers', () => {
    expect(validateField({ ...field, type: 'STRING_LIST', key: 'restart.realTimeInterval' }, ['06:00', '06:00'])).toBeDefined();
    expect(validateField({ ...field, type: 'STRING_LIST', key: 'tablist.perWorldOverrides' }, ['{"world":""}'])).toBeDefined();
  });
});

it('releases a timed-out mutation without claiming it was not stored', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((_path, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
  })));
  await expect(new ApiClient(() => {}, 10).request('/api/config/patch', {})).rejects.toMatchObject({ code: 'request_timeout', message: expect.stringContaining('may have been stored') });
});
