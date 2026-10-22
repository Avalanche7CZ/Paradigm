export function dashboardUrl(path: string): string {
  const url = new URL(path.replace(/^\/+/, ''), document.baseURI);
  return url.pathname + url.search + url.hash;
}

import type { Envelope } from './types';
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public data: unknown = null,
  ) {
    super(message);
  }
}
export class ApiClient {
  csrf = '';
  locale = 'en';
  constructor(
    private expired: () => void = () => {},
    private timeoutMs = 30000,
  ) {}
  async request<T>(
    path: string,
    body?: unknown,
    signal?: AbortSignal,
    target?: string,
  ): Promise<T> {
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (signal?.aborted) abort();
    else signal?.addEventListener('abort', abort, { once: true });
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, this.timeoutMs);
    try {
      const headers: Record<string, string> = {
        Accept: 'application/json',
        'X-Paradigm-Locale': this.locale,
      };
      if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
        if (this.csrf) headers['X-Paradigm-CSRF'] = this.csrf;
      }
      if (target) headers['X-Paradigm-Server'] = target;
      let response: Response;
      try {
        response = await fetch(dashboardUrl(path), {
          method: body === undefined ? 'GET' : 'POST',
          headers,
          credentials: 'same-origin',
          cache: 'no-store',
          body: body === undefined ? undefined : JSON.stringify(body),
          signal: controller.signal,
        });
      } catch (error) {
        if (signal?.aborted) throw error;
        throw new ApiError(
          0,
          'connection_failed',
          'Could not reach the dashboard. Your changes are still in this browser.',
        );
      }
      let envelope: Envelope<T>;
      try {
        envelope = await response.json();
      } catch {
        throw new ApiError(
          response.status,
          'invalid_response',
          'The server returned an unreadable response. Check the connection before retrying.',
        );
      }
      if (!envelope || typeof envelope.ok !== 'boolean')
        throw new ApiError(
          response.status,
          'invalid_response',
          'The server returned an invalid API response. Review the latest state before retrying.',
        );
      if (!response.ok || !envelope.ok) {
        if (response.status === 401 && !path.startsWith('/api/auth/')) this.expired();
        throw new ApiError(
          response.status,
          envelope.error?.code || 'request_failed',
          envelope.error?.message || 'The operation failed.',
          envelope.data,
        );
      }
      return envelope.data;
    } catch (error) {
      if (timedOut && !signal?.aborted)
        throw new ApiError(
          0,
          'request_timeout',
          body === undefined
            ? 'The dashboard did not respond in time. Retry to refresh the latest state.'
            : 'The dashboard did not confirm this operation in time. It may have been stored. Review the latest state before trying again; your draft is preserved.',
        );
      throw error;
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', abort);
    }
  }
}
export const query = (path: string, values: Record<string, string | number | undefined>) => {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value));
  });
  return `${path}?${params}`;
};
