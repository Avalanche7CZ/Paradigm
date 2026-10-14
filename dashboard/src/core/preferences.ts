export function readPreference(key: string, fallback: string): string {
  try {
    return localStorage.getItem(key) || fallback;
  } catch {
    return fallback;
  }
}
export function writePreference(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {}
}

export function readPlayerSelection(): string {
  try {
    return sessionStorage.getItem('paradigm-player') || '';
  } catch {
    return '';
  }
}
export function writePlayerSelection(uuid: string) {
  try {
    sessionStorage.setItem('paradigm-player', uuid);
  } catch {}
}
