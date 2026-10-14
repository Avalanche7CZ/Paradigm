import { createContext } from 'preact';
import { useContext } from 'preact/hooks';
export interface Dictionary {
  strings?: Record<string, string>;
  fields?: Record<string, { label?: string; help?: string }>;
}
export const DictionaryContext = createContext<Dictionary>({});
export function useTranslation() {
  const dictionary = useContext(DictionaryContext);
  return {
    t: (text: string) => dictionary.strings?.[text] || text,
    field: (key: string) => dictionary.fields?.[key],
  };
}
