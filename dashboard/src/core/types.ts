export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type Obj = { [key: string]: Json };
export const object = (v: Json | undefined): Obj =>
  v && typeof v === 'object' && !Array.isArray(v) ? v : {};
export const array = (v: Json | undefined): Json[] => (Array.isArray(v) ? v : []);
export const string = (v: Json | undefined): string =>
  v == null ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v);
export const number = (v: Json | undefined): number => (typeof v === 'number' ? v : Number(v || 0));
export interface Envelope<T> {
  ok: boolean;
  data: T;
  error?: { code: string; message: string };
  warnings: string[];
}
export interface Capabilities {
  mutations?: Record<string, boolean>;
  pages: Record<string, boolean>;
  config: Record<string, { view: boolean; edit: boolean }>;
}
export interface Session {
  serverId?: string;
  serverName?: string;
  networkActive?: boolean;
  authenticated: boolean;
  requireLogin: boolean;
  csrfToken?: string;
  principal?: { name: string; uuid: string; console: boolean };
  capabilities?: Capabilities;
}
export interface Server {
  serverId: string;
  serverName: string;
  networkId: string;
  current: boolean;
  online: boolean;
  version: string;
  minecraftVersion: string;
  loader: string;
  lastSeenMs: number;
  onlinePlayers: number | null;
  moduleCount: number | null;
  enabledModuleCount: number | null;
  activeProvider: string;
  storageHealth: string | null;
  runtimeDetailsAvailable: boolean;
}
export interface Servers {
  servers: Server[];
  networkActive: boolean;
}
export interface FieldValue {
  value: Json;
  secret: boolean;
  set: boolean;
}
export interface Field {
  key: string;
  category: string;
  label: string;
  help: string;
  type: string;
  value: FieldValue;
  defaultValue: FieldValue;
  editable: boolean;
  masked: boolean;
  required: boolean;
  nullable: boolean;
  trim: boolean;
  allowEmptyItems: boolean;
  min?: number;
  max?: number;
  step?: number;
  options: string[];
  listElementType?: string;
  durationUnit?: string;
  reloadBehavior: string;
  riskLevel: string;
  origin?: string;
  networkValue?: FieldValue;
  serverValue?: FieldValue;
  baselineValue?: FieldValue;
}
export interface Section {
  section: string;
  adopted: boolean;
  networkRevision: number;
  serverRevision: number;
  appliedGlobalRevision: number;
  appliedServerRevision: number;
  appliedAtMs: number;
  lastError: string;
}
export interface Snapshot {
  managedSections?: string[];
  revision: string;
  fields: Field[];
  categories: { id: string; label: string; help: string }[];
}
export interface RemoteSnapshot extends Snapshot {
  serverId: string;
  networkId: string;
  online: boolean;
  schemaCompatible: boolean;
  sections: Section[];
}
export interface Validation {
  accepted: string[];
  rejected: { key: string; reason: string }[];
  warnings: string[];
  newRevision?: string;
}
export type Scope = 'LOCAL' | 'SERVER' | 'NETWORK';
export const ALL = '';
export const formatTime = (ms: Json | undefined) =>
  number(ms) > 0 ? new Date(number(ms)).toLocaleString() : 'Unknown';
export const title = (v: string) =>
  v
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/[_.-]/g, ' ')
    .replace(/^./, (c) => c.toUpperCase());
