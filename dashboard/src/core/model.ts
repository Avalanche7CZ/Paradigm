import {
  ALL,
  type Field,
  type Json,
  type RemoteSnapshot,
  type Scope,
  type Section,
  type Server,
} from './types';
export function applicationState(snapshot: RemoteSnapshot, section: Section): string {
  if (!snapshot.schemaCompatible) return 'Incompatible';
  if (section.lastError) return 'Rejected';
  if (!section.adopted) return snapshot.online ? 'Local configuration' : 'Offline';
  if (
    section.appliedAtMs > 0 &&
    section.networkRevision === section.appliedGlobalRevision &&
    section.serverRevision === section.appliedServerRevision
  )
    return snapshot.online ? 'Applied configuration' : 'Offline · last configuration applied';
  return snapshot.online ? 'Pending synchronization' : 'Offline · pending synchronization';
}
export const isHost = (selected: string, servers: Server[]) =>
  servers.some((server) => server.current && server.serverId === selected);
export function configurationTarget(selected: string, hostId: string, scope: Scope) {
  if (scope === 'LOCAL' && selected !== hostId)
    throw new Error('Local configuration requires the dashboard host.');
  if (scope === 'SERVER' && selected === ALL)
    throw new Error('Select a server before editing an override.');
  return { serverId: selected === ALL ? hostId : selected, scope };
}
export function validateField(field: Field, value: Json): string | undefined {
  if (value === null) return field.nullable ? undefined : 'A value is required.';
  if (['INTEGER', 'DOUBLE', 'DURATION'].includes(field.type)) {
    if (typeof value !== 'number' || !Number.isFinite(value)) return 'Enter a finite number.';
    if (field.type === 'INTEGER' && !Number.isInteger(value)) return 'Enter a whole number.';
    if (field.min != null && value < field.min) return `Minimum: ${field.min}.`;
    if (field.max != null && value > field.max) return `Maximum: ${field.max}.`;
  }
  if (typeof value === 'string' && field.required && !value.trim()) return 'A value is required.';
  if (field.type === 'ENUM' && !field.options.includes(String(value)))
    return 'Choose a supported option.';
  if (field.type === 'STRING_LIST' && !Array.isArray(value)) return 'Enter a list.';
  if (field.type === 'STRING_LIST' && Array.isArray(value)) {
    if (
      field.key === 'restart.realTimeInterval' &&
      (value.some((v) => typeof v !== 'string' || !/^([01]\d|2[0-3]):[0-5]\d$/.test(v)) ||
        new Set(value).size !== value.length)
    )
      return 'Use unique clock times in HH:mm format.';
    if (
      field.key === 'restart.timerBroadcast' &&
      (value.some((v) => !/^\d+$/.test(String(v)) || Number(v) < 1 || Number(v) > 86400) ||
        new Set(value.map(Number)).size !== value.length)
    )
      return 'Use unique warning thresholds between 1 and 86400 seconds.';
    if (field.key === 'tablist.perWorldOverrides') {
      try {
        const worlds = value.map((v) => JSON.parse(String(v)));
        if (
          worlds.some((v) => !v || !String(v.world || '').trim()) ||
          new Set(worlds.map((v) => v.world)).size !== worlds.length
        )
          return 'Each world override needs a unique world or dimension ID.';
      } catch {
        return 'World overrides must contain valid JSON objects.';
      }
    }
  }
  if (Array.isArray(value) && !field.allowEmptyItems && value.some((v) => v === null || v === ''))
    return 'Remove empty items.';
  return undefined;
}
export function remainingEdits(
  edits: Record<string, Json>,
  accepted: string[],
): Record<string, Json> {
  const next = { ...edits };
  accepted.forEach((key) => delete next[key]);
  return next;
}
