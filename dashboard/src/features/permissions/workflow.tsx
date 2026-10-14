import { createContext } from 'preact';
import { useContext } from 'preact/hooks';
import type { Change } from './model';
export interface Workflow {
  editable: boolean;
  changes: Change[];
  stage: (changes: Omit<Change, 'key'>[]) => void;
  open: (modal: boolean) => void;
  draft: (dirty: boolean) => void;
  draftDirty: boolean;
  refresh: () => void;
}
export const WorkflowContext = createContext<Workflow | null>(null);
export function useWorkflow() {
  const value = useContext(WorkflowContext);
  if (!value) throw new Error('Permissions workflow missing');
  return value;
}
