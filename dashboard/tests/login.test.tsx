import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/preact';
import { App } from '../src/App';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  history.replaceState(null, '', '/');
});

it.each([200, 401, 503, 0])('removes the login token before handling HTTP %s', async (code) => {
  history.replaceState(null, '', '/?token=SECRET&keep=value#overview');
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addEventListener() {},
    removeEventListener() {},
  }));
  const seen: string[] = [];
  const fetch = vi.fn(async (url: string) => {
    seen.push(location.href);
    if (String(url).includes('/lang/')) return new Response('{}');
    if (String(url).includes('/api/auth/login') && code === 0) throw new TypeError('offline');
    if (String(url).includes('/api/auth/login'))
      return new Response(
        JSON.stringify(
          code === 200
            ? { ok: true, data: { authenticated: true, requireLogin: true } }
            : { ok: false, error: { message: 'Login failed' } },
        ),
        { status: code, headers: { 'Content-Type': 'application/json' } },
      );
    return new Response(
      JSON.stringify({ ok: true, data: { authenticated: false, requireLogin: true } }),
      { headers: { 'Content-Type': 'application/json' } },
    );
  });
  vi.stubGlobal('fetch', fetch);
  render(<App />);
  await waitFor(() =>
    expect(fetch.mock.calls.some(([url]) => String(url).includes('/api/auth/login'))).toBe(true),
  );
  expect(location.search).toBe('?keep=value');
  expect(location.hash).toBe('#overview');
  expect(seen.every((url) => !url.includes('SECRET'))).toBe(true);
});

it.each(['/gtnh/', '/tfg-modern/'])('exchanges a token only with its issuing dashboard at %s', async (base) => {
  history.replaceState(null, '', `${base}?token=LOCAL-ONLY`);
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
  const fetch = vi.fn(async (path: string) => {
    if (String(path).includes('/lang/')) return new Response('{}');
    return new Response(JSON.stringify({ ok: true, data: { authenticated: false, requireLogin: true } }));
  });
  vi.stubGlobal('fetch', fetch);
  render(<App />);
  await waitFor(() => expect(fetch.mock.calls.some(([path]) => path === base + 'api/auth/login')).toBe(true));
  expect(fetch.mock.calls.some(([path]) => path === '/api/auth/login')).toBe(false);
});
