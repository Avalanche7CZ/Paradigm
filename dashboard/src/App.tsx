import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import { DictionaryContext, type Dictionary } from './core/i18n';
import { ApiClient } from './core/api';
import { WorkspaceContext, type Workspace } from './core/context';
import { pages, route, type Page } from './core/navigation';
import { readPreference, writePreference } from './core/preferences';
import { ALL, type Server, type Servers, type Session } from './core/types';
import { Dialog, Empty } from './components/ui';
import { Overview, ServersTable } from './features/overview';
import { Configuration } from './features/configuration';
import { Audit, Players } from './features/directory';
import { Definitions } from './features/definitions';
import { Permissions } from './features/permissions';
import { Moderation } from './features/moderation';
import { Tickets } from './features/tickets';
import { Discord, Storage, StorageConfiguration } from './features/system';
interface Confirmation {
  message: string;
  label: string;
  resolve: (ok: boolean) => void;
}
export function App() {
  const [needsLogin, setNeedsLogin] = useState(false);
  const [session, setSession] = useState<Session>();
  const [authError, setAuthError] = useState('');
  const [token, setToken] = useState('');
  const [servers, setServers] = useState<Server[]>([]);
  const [networkActive, setNetworkActive] = useState(false);
  const [selected, setSelected] = useState(readPreference('paradigm-server', ALL));
  const [hostId, setHostId] = useState('');
  const [page, setPage] = useState(
    route(location.hash || '#' + location.pathname.split('/').pop()),
  );
  const [search, setSearch] = useState('');
  const [navOpen, setNavOpen] = useState(false);
  const [mobile, setMobile] = useState(matchMedia('(max-width:760px)').matches);
  const discovery = useRef<AbortController>();
  const initializedContext = useRef(false);
  const refreshDiscovery = useRef<() => Promise<void>>(() => Promise.resolve());
  useEffect(() => {
    const media = matchMedia('(max-width:760px)');
    const update = () => setMobile(media.matches);
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
  useEffect(() => {
    if (mobile && navOpen) document.querySelector<HTMLElement>('.sidebar input')?.focus();
  }, [mobile, navOpen]);
  const [theme, setTheme] = useState(readPreference('paradigm-theme', 'light'));
  const [locale, setLocale] = useState(readPreference('paradigm-locale', 'en'));
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const dirtyRef = useRef(new Set<string>());
  const [confirmation, setConfirmation] = useState<Confirmation>();
  const confirmationRef = useRef<Confirmation>();
  const [notice, setNotice] = useState<{ message: string; error: boolean }>();
  const apiRef = useRef<ApiClient>();
  if (!apiRef.current)
    apiRef.current = new ApiClient(() => {
      setAuthError('Your session expired. Sign in again to continue with your changes.');
      setNeedsLogin(true);
    });
  const api = apiRef.current;
  api.locale = locale;
  const [dictionary, setDictionary] = useState<Dictionary>({});
  useEffect(() => {
    const controller = new AbortController();
    fetch(`/lang/${['en', 'cs', 'ru'].includes(locale) ? locale : 'en'}.json`, {
      signal: controller.signal,
    })
      .then((r) => r.json())
      .then((data) => setDictionary(data))
      .catch(() => {});
    return () => controller.abort();
  }, [locale]);
  const t = (s: string) => dictionary.strings?.[s] || s;
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    writePreference('paradigm-theme', theme);
  }, [theme]);
  useEffect(() => {
    document.documentElement.lang = locale;
    writePreference('paradigm-locale', locale);
  }, [locale]);
  const notify = useCallback((message: string, error = false) => setNotice({ message, error }), []);
  const setDirty = useCallback((key: string, dirty: boolean) => {
    if (dirty) dirtyRef.current.add(key);
    else dirtyRef.current.delete(key);
  }, []);
  const confirm = useCallback(
    (message: string, label = 'Continue') =>
      new Promise<boolean>((resolve) => {
        if (confirmationRef.current) {
          resolve(false);
          return;
        }
        const value = { message, label, resolve };
        confirmationRef.current = value;
        setConfirmation(value);
      }),
    [],
  );
  const mutate = useCallback(async <T,>(task: () => Promise<T>): Promise<T> => {
    if (busyRef.current) throw new Error('Another operation is still in progress.');
    busyRef.current = true;
    setBusy(true);
    try {
      return await task();
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }, []);
  const canLeave = useCallback(
    async () =>
      !busyRef.current &&
      (!dirtyRef.current.size ||
        (await confirm(
          'You have unsaved changes. Discard them before leaving this workspace?',
          'Discard changes',
        ))),
    [confirm],
  );
  const navigate = useCallback(
    async (next: string) => {
      if (next === page) return;
      if (await canLeave()) {
        setPage(next);
        setNavOpen(false);
        history.pushState(null, '', `/${next}`);
      }
    },
    [page, canLeave],
  );
  const select = useCallback(
    async (next: string) => {
      if (next === selected) return;
      if (await canLeave()) {
        setSelected(next);
        writePreference('paradigm-server', next);
      }
    },
    [selected, canLeave],
  );
  useEffect(() => {
    const before = (e: BeforeUnloadEvent) => {
      if (dirtyRef.current.size || busyRef.current) {
        e.preventDefault();
        e.returnValue = '';
      }
    };
    const back = () => {
      const next = route(location.hash || '#' + location.pathname.split('/').pop());
      if (next === page) return;
      void canLeave().then((yes) => {
        if (yes) setPage(next);
        else history.replaceState(null, '', `/${page}`);
      });
    };
    window.addEventListener('beforeunload', before);
    window.addEventListener('popstate', back);
    window.addEventListener('hashchange', back);
    return () => {
      window.removeEventListener('beforeunload', before);
      window.removeEventListener('popstate', back);
      window.removeEventListener('hashchange', back);
    };
  }, [page, canLeave]);
  async function discover(current: Session) {
    api.csrf = current.csrfToken || '';
    setSession(current);
    setNeedsLogin(false);
    discovery.current?.abort();
    const controller = new AbortController();
    discovery.current = controller;
    try {
      const data = await api.request<Servers>('/api/servers', undefined, controller.signal);
      if (controller.signal.aborted) return;
      setServers(data.servers);
      setNetworkActive(data.networkActive);
      const host = data.servers.find((s) => s.current)?.serverId || current.serverId || hostId;
      setHostId(host);
      if (!initializedContext.current && selected === ALL && !data.networkActive) setSelected(host);
      initializedContext.current = true;
    } catch (error) {
      if (controller.signal.aborted) return;
      if (current.serverId) {
        setHostId(current.serverId);
        if (!initializedContext.current && selected === ALL) setSelected(current.serverId);
        initializedContext.current = true;
        setNetworkActive(false);
        setServers([
          {
            serverId: current.serverId,
            serverName: current.serverName || current.serverId,
            current: true,
            online: true,
            networkId: '',
            version: '',
            minecraftVersion: '',
            loader: '',
            lastSeenMs: 0,
            onlinePlayers: null,
            moduleCount: null,
            enabledModuleCount: null,
            activeProvider: '',
            storageHealth: null,
            runtimeDetailsAvailable: false,
          },
        ]);
      } else
        notify(
          `Server discovery unavailable: ${String(error)}. Server operations remain disabled until discovery succeeds.`,
          true,
        );
    }
  }
  refreshDiscovery.current = () => (session ? discover(session) : Promise.resolve());
  useEffect(() => {
    if (!session || (!session.authenticated && session.requireLogin) || needsLogin) return;
    const timer = setInterval(() => {
      if (document.visibilityState !== 'hidden' && !busyRef.current)
        void refreshDiscovery.current();
    }, 30000);
    return () => clearInterval(timer);
  }, [session?.authenticated, session?.requireLogin, needsLogin]);
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    void (async () => {
      try {
        const params = new URLSearchParams(location.search);
        const oneTime = params.get('token');
        let status: Session;
        if (oneTime) {
          const login = await api.request<Session>(
            '/api/auth/login',
            { token: oneTime },
            controller.signal,
          );
          params.delete('token');
          history.replaceState(
            null,
            '',
            `${location.pathname}${params.size ? '?' + params : ''}${location.hash}`,
          );
          status = {
            ...(await api.request<Session>('/api/auth/status', undefined, controller.signal)),
            ...login,
            authenticated: true,
            requireLogin: true,
          };
        } else
          status = await api.request<Session>('/api/auth/status', undefined, controller.signal);
        if (active) {
          if (status.authenticated || !status.requireLogin) await discover(status);
          else setSession(status);
        }
      } catch (error) {
        if (active) setAuthError(String(error));
      }
    })();
    return () => {
      active = false;
      controller.abort();
    };
  }, []);
  async function login(e: Event) {
    e.preventDefault();
    setAuthError('');
    try {
      const data = await mutate(() => api.request<Session>('/api/auth/login', { token }));
      setToken('');
      const status = await api.request<Session>('/api/auth/status');
      await discover({ ...status, ...data, authenticated: true, requireLogin: true });
    } catch (error) {
      setAuthError(error instanceof Error ? error.message : String(error));
    }
  }
  async function logout() {
    if (!(await canLeave())) return;
    try {
      await mutate(() => api.request('/api/auth/logout', {}));
      api.csrf = '';
      setSession({ authenticated: false, requireLogin: true });
      setServers([]);
      setHostId('');
      setNetworkActive(false);
    } catch (error) {
      notify(String(error), true);
    }
  }
  const accessible = (p: Page) =>
    p.id === 'players' || !session?.capabilities || session.capabilities.pages[p.id] === true;
  const currentPage = pages.find((p) => p.id === page) || pages[0];
  const workspace: Workspace = {
    api,
    session: session || { authenticated: false, requireLogin: true },
    servers,
    networkActive,
    selected,
    hostId,
    select,
    navigate,
    confirm,
    notify,
    mutate,
    setDirty,
    busy,
  };
  const authenticated = !!session && (session.authenticated || !session.requireLogin);
  function view() {
    if (!accessible(currentPage))
      return (
        <Empty title="Access restricted">
          Your current permissions do not grant access to this page.
        </Empty>
      );
    if (!hostId)
      return (
        <Empty title="Server context unavailable">
          Refresh server discovery before administering a server.
          <button onClick={() => session && discover(session)}>Retry discovery</button>
        </Empty>
      );
    if (currentPage.hostOnly && selected !== hostId)
      return (
        <Empty title="This operation requires the dashboard host">
          {currentPage.label} uses the live engine on{' '}
          {servers.find((s) => s.current)?.serverName || hostId}. Remote configuration remains
          available through configuration pages.
          <button class="primary" onClick={() => select(hostId)}>
            Select dashboard host
          </button>
        </Empty>
      );
    if (page === 'overview') return <Overview />;
    if (page === 'servers') return <ServersTable servers={servers} />;
    if (page === 'players') return <Players />;
    if (page === 'audit') return <Audit />;
    if (['menus', 'holograms', 'customCommands'].includes(page))
      return <Definitions kind={page as 'menus' | 'holograms' | 'customCommands'} />;
    if (page === 'permissions') return <Permissions />;
    if (page === 'moderation') return <Moderation />;
    if (page === 'tickets') return <Tickets />;
    if (page === 'storage') return <Storage />;
    if (page === 'storageConfig') return <StorageConfiguration />;
    if (page === 'discord') return <Discord page={currentPage} />;
    return <Configuration page={currentPage} />;
  }
  return (
    <WorkspaceContext.Provider value={workspace}>
      <DictionaryContext.Provider value={dictionary}>
        <a
          class="skip-link"
          href="#main-content"
          onClick={(e) => {
            e.preventDefault();
            document.getElementById('main-content')?.focus();
          }}
        >
          {t('Skip to dashboard content')}
        </a>
        {authenticated ? (
          <div class={`app-shell ${navOpen ? 'nav-open' : ''}`} inert={needsLogin}>
            <aside
              class="sidebar"
              inert={mobile && !navOpen}
              role={mobile && navOpen ? 'dialog' : undefined}
              aria-modal={mobile && navOpen ? true : undefined}
              aria-label="Workspace navigation"
              onKeyDown={(e) => {
                if (!mobile || !navOpen) return;
                if (e.key === 'Escape') {
                  setNavOpen(false);
                  document.querySelector<HTMLElement>('.nav-toggle')?.focus();
                }
                if (e.key === 'Tab') {
                  const elements = [
                    ...e.currentTarget.querySelectorAll<HTMLElement>('a,button,input,select'),
                  ];
                  const first = elements[0],
                    last = elements.at(-1);
                  if (e.shiftKey && document.activeElement === first) {
                    e.preventDefault();
                    last?.focus();
                  } else if (!e.shiftKey && document.activeElement === last) {
                    e.preventDefault();
                    first?.focus();
                  }
                }
              }}
            >
              <button
                class="drawer-close"
                aria-label="Close navigation"
                onClick={() => {
                  setNavOpen(false);
                  document.querySelector<HTMLElement>('.nav-toggle')?.focus();
                }}
              >
                ×
              </button>
              <a
                class="brand"
                href="/overview"
                onClick={(e) => {
                  e.preventDefault();
                  void navigate('overview');
                }}
              >
                <span class="brand-mark">P</span>
                <span>
                  Paradigm<small>Administration</small>
                </span>
              </a>
              <div class="workspace-label">
                <span class="workspace-avatar">P</span>
                <div>
                  Server workspace<small>Dashboard 2.0</small>
                </div>
              </div>
              <label class="nav-search">
                <span class="sr-only">{t('Find a page')}</span>
                <input
                  type="search"
                  placeholder={t('Find a page')}
                  value={search}
                  onInput={(e) => setSearch(e.currentTarget.value)}
                />
              </label>
              <nav aria-label="Dashboard sections">
                {(['Workspace', 'Management', 'System'] as const).map((group) => (
                  <section key={group}>
                    <h2>{t(group)}</h2>
                    {pages
                      .filter(
                        (p) =>
                          p.group === group &&
                          accessible(p) &&
                          `${p.label}`.toLowerCase().includes(search.toLowerCase()),
                      )
                      .map((p) => (
                        <a
                          href={`/${p.id}`}
                          aria-current={page === p.id ? 'page' : undefined}
                          onClick={(e) => {
                            e.preventDefault();
                            void navigate(p.id);
                          }}
                        >
                          <span class="nav-icon" aria-hidden="true">
                            {p.group === 'Workspace' ? '◫' : p.group === 'System' ? '⚙' : '◇'}
                          </span>
                          {t(p.label)}
                        </a>
                      ))}
                  </section>
                ))}
              </nav>
              <div class="sidebar-footer">
                <span class="status-dot" />
                Embedded server dashboard
              </div>
            </aside>
            <button
              class="nav-scrim"
              aria-label="Close navigation"
              onClick={() => setNavOpen(false)}
            />
            <div class="main-shell" inert={mobile && navOpen}>
              <header class="topbar">
                <button
                  class="nav-toggle"
                  aria-label="Open navigation"
                  aria-expanded={navOpen}
                  onClick={() => setNavOpen(!navOpen)}
                >
                  ☰
                </button>
                <label class="server-selector">
                  <span>{t('Server context')}</span>
                  <select
                    aria-label="Server context"
                    value={selected}
                    disabled={busy}
                    onChange={(e) => void select(e.currentTarget.value)}
                  >
                    <option value={ALL}>All servers · Network overview</option>
                    {!servers.some((s) => s.serverId === selected) && selected !== ALL && (
                      <option value={selected}>{selected} · no longer discovered</option>
                    )}
                    {servers.map((s) => (
                      <option key={s.serverId} value={s.serverId}>
                        {s.serverName || s.serverId} · {s.online ? 'Online' : 'Offline'} ·{' '}
                        {s.minecraftVersion || '?'} {s.loader || ''}
                        {s.current ? ' · Host' : ''}
                      </option>
                    ))}
                  </select>
                </label>
                <div class="topbar-actions">
                  <button
                    aria-label="Refresh server discovery"
                    disabled={busy}
                    onClick={() => session && discover(session)}
                  >
                    ↻
                  </button>
                  <button
                    aria-label={
                      theme === 'light' ? 'Switch to dark theme' : 'Switch to light theme'
                    }
                    onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}
                  >
                    {theme === 'light' ? 'Dark' : 'Light'}
                  </button>
                  <select
                    class="locale"
                    aria-label="Language"
                    value={locale}
                    onChange={(e) => setLocale(e.currentTarget.value)}
                  >
                    <option value="en">EN</option>
                    <option value="cs">CS</option>
                    <option value="ru">RU</option>
                  </select>
                  <span class="session-name">
                    {session.principal?.name || 'Local administrator'}
                  </span>
                  <button disabled={busy} onClick={logout}>
                    {t('Logout')}
                  </button>
                </div>
              </header>
              <main id="main-content" tabIndex={-1}>
                <div class="breadcrumb">
                  {t(currentPage.group)}
                  <span>/</span>
                  {selected === ALL
                    ? 'Network'
                    : servers.find((s) => s.serverId === selected)?.serverName || selected}
                </div>
                <div class="page-heading">
                  <div>
                    <h1>
                      {page === 'overview' && selected === ALL
                        ? 'Network overview'
                        : t(currentPage.label)}
                    </h1>
                    <p>{currentPage.description}</p>
                  </div>
                  <span class="page-context">
                    {selected === ALL
                      ? 'Network scope'
                      : selected === hostId
                        ? 'Dashboard host'
                        : 'Remote server'}
                  </span>
                </div>
                {notice && (
                  <div
                    class={`notice ${notice.error ? 'error' : ''}`}
                    role={notice.error ? 'alert' : 'status'}
                  >
                    <span>{notice.message}</span>
                    <button aria-label="Dismiss notification" onClick={() => setNotice(undefined)}>
                      ×
                    </button>
                  </div>
                )}
                <div class="page-content" key={`${page}:${selected}`}>
                  {view()}
                </div>
              </main>
              <footer class="main-footer">
                <span>Paradigm Essentials</span>
                <span>{busy ? 'Operation in progress…' : 'Server-side administration'}</span>
              </footer>
            </div>
          </div>
        ) : (
          <main class="login-page">
            <div class="login-brand brand">
              <span class="brand-mark">P</span>Paradigm
            </div>
            <form class="login-card" onSubmit={login}>
              <span class="badge">Dashboard 2.0</span>
              <h1>Welcome to your workspace</h1>
              <p>
                Open the dashboard from Minecraft with <code>/paradigm dashboard open</code>. Your
                link signs you in once.
              </p>
              <label class="field">
                <span>One-time code or login link</span>
                <input
                  autoFocus
                  autoComplete="one-time-code"
                  value={token}
                  onInput={(e) => setToken(e.currentTarget.value)}
                  required
                />
              </label>
              {authError && (
                <div class="notice error" role="alert">
                  {authError}
                </div>
              )}
              <button class="primary" disabled={busy}>
                {busy ? 'Signing in…' : 'Sign in'}
              </button>
            </form>
          </main>
        )}
        {needsLogin && (
          <SessionLogin
            token={token}
            setToken={setToken}
            error={authError}
            submit={login}
            busy={busy}
          />
        )}
        {confirmation && (
          <Dialog
            message={confirmation.message}
            label={confirmation.label}
            resolve={(ok) => {
              confirmation.resolve(ok);
              confirmationRef.current = undefined;
              setConfirmation(undefined);
            }}
          />
        )}
      </DictionaryContext.Provider>
    </WorkspaceContext.Provider>
  );
}

function SessionLogin({
  token,
  setToken,
  error,
  submit,
  busy,
}: {
  token: string;
  setToken: (v: string) => void;
  error: string;
  submit: (e: Event) => void;
  busy: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    ref.current?.showModal();
  }, []);
  return (
    <dialog ref={ref} aria-labelledby="session-login-title" onCancel={(e) => e.preventDefault()}>
      <form onSubmit={submit}>
        <h2 id="session-login-title">Session expired</h2>
        <p>{error} Your drafts remain in this workspace.</p>
        <label class="field">
          <span>One-time login code</span>
          <input
            autoFocus
            value={token}
            required
            onInput={(e) => setToken(e.currentTarget.value)}
          />
        </label>
        <button class="primary" disabled={busy}>
          Sign in again
        </button>
      </form>
    </dialog>
  );
}
