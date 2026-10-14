import { createContext, type ComponentChildren } from 'preact';
import { useContext, useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { ApiClient } from './api';
import type { Server, Session } from './types';
export interface Workspace {
  api: ApiClient;
  session: Session;
  servers: Server[];
  networkActive: boolean;
  selected: string;
  hostId: string;
  select: (server: string) => Promise<void>;
  navigate: (page: string) => Promise<void>;
  confirm: (message: string, label?: string) => Promise<boolean>;
  notify: (message: string, error?: boolean) => void;
  mutate: <T>(task: () => Promise<T>) => Promise<T>;
  setDirty: (key: string, dirty: boolean) => void;
  busy: boolean;
}
export const WorkspaceContext = createContext<Workspace | null>(null);
export const useWorkspace = () => {
  const value = useContext(WorkspaceContext);
  if (!value) throw new Error('Workspace missing');
  return value;
};
export function useDirty(key: string, dirty: boolean) {
  const { setDirty } = useWorkspace();
  useLayoutEffect(() => {
    setDirty(key, dirty);
    return () => setDirty(key, false);
  }, [key, dirty, setDirty]);
}
export interface Resource<T> {
  data?: T;
  error?: Error;
  loading: boolean;
  refresh: () => void;
}
export function useResource<T>(
  load: (signal: AbortSignal) => Promise<T>,
  key: string,
): Resource<T> {
  const [state, setState] = useState<Omit<Resource<T>, 'refresh'> & { identity?: string }>({
    loading: true,
  });
  const [revision, setRevision] = useState(0);
  const identity = key + '\0' + revision;
  const activeIdentity = useRef(identity);
  activeIdentity.current = identity;
  const loader = useRef(load);
  loader.current = load;
  useEffect(() => {
    const controller = new AbortController();
    setState({ loading: true, identity });
    loader
      .current(controller.signal)
      .then((data) => {
        if (!controller.signal.aborted && activeIdentity.current === identity)
          setState({ data, loading: false, identity });
      })
      .catch((error) => {
        if (!controller.signal.aborted && activeIdentity.current === identity)
          setState({
            identity,
            error: error instanceof Error ? error : new Error(String(error)),
            loading: false,
          });
      });
    return () => controller.abort();
  }, [key, revision]);
  return {
    ...(state.identity === identity ? state : { loading: true }),
    refresh: () => setRevision((v) => v + 1),
  };
}
export function AsyncBoundary<T>({
  resource,
  children,
}: {
  resource: Resource<T>;
  children: (data: T) => ComponentChildren;
}) {
  if (resource.loading)
    return (
      <div class="state loading" role="status">
        <span class="spinner" />
        Loading workspace…
      </div>
    );
  if (resource.error)
    return (
      <div class="state error" role="alert">
        <strong>Could not load this view</strong>
        <p>{resource.error.message}</p>
        <button onClick={resource.refresh}>Try again</button>
      </div>
    );
  if (resource.data === undefined) return <div class="state">No data available.</div>;
  return <>{children(resource.data)}</>;
}
