import { useTranslation } from '../core/i18n';
import { array, object, string, type Field, type Json } from '../core/types';
import { validateField } from '../core/model';
import { FormatEditor } from './formatting';
import { ListEditor, ObjectEditor } from './editors';
import { StructuredList, hasStructuredList } from './structured-settings';
export { ListEditor, ObjectEditor } from './editors';
export function SettingControl({
  field,
  value,
  change,
  disabled,
  placeholders = [],
}: {
  field: Field;
  value: Json;
  change: (v: Json) => void;
  disabled: boolean;
  placeholders?: string[];
}) {
  const { t, field: translatedField } = useTranslation();
  const translation = translatedField(field.key);
  field = {
    ...field,
    label: translation?.label || field.label,
    help: translation?.help || field.help,
  };
  const formatted =
    /motd|message|format|header|footer|welcome|hover/i.test(field.key) &&
    !/command|clickAction/i.test(field.key);
  const error = validateField(field, value);
  const inputId = `setting-${field.key}`;
  let control;
  if (field.masked || field.value.secret)
    control = (
      <p class="muted">
        {field.value.set ? 'Secret configured' : 'No secret configured'} · values are never
        returned.
      </p>
    );
  else if (field.type === 'BOOLEAN')
    control = (
      <input
        id={inputId}
        type="checkbox"
        role="switch"
        disabled={disabled}
        checked={value === true}
        onChange={(e) => change(e.currentTarget.checked)}
      />
    );
  else if (field.type === 'ENUM')
    control = (
      <select
        id={inputId}
        value={string(value)}
        disabled={disabled}
        onChange={(e) => change(e.currentTarget.value)}
      >
        {field.options.map((option) => (
          <option value={option}>{option}</option>
        ))}
      </select>
    );
  else if (field.type === 'STRING_LIST' && hasStructuredList(field.key))
    control = (
      <StructuredList
        field={field}
        value={array(value)}
        change={change}
        disabled={disabled}
        placeholders={placeholders}
      />
    );
  else if (field.type === 'STRING_LIST')
    control = (
      <ListEditor
        label={field.label}
        value={array(value)}
        disabled={disabled}
        formatted={formatted}
        placeholders={placeholders}
        change={change}
      />
    );
  else if (typeof value === 'object' && value !== null)
    control = (
      <ObjectEditor label={field.label} value={value} change={change} disabled={disabled} />
    );
  else if (formatted && typeof value === 'string')
    control = (
      <FormatEditor
        id={inputId}
        label={field.label}
        value={value}
        change={change}
        disabled={disabled}
        placeholders={placeholders}
      />
    );
  else if (['INTEGER', 'DOUBLE', 'DURATION'].includes(field.type))
    control = (
      <input
        id={inputId}
        type="number"
        value={string(value)}
        min={field.min}
        max={field.max}
        step={field.step || (field.type === 'DOUBLE' ? 'any' : 1)}
        disabled={disabled}
        aria-invalid={!!error}
        onInput={(e) => change(e.currentTarget.value === '' ? null : Number(e.currentTarget.value))}
      />
    );
  else
    control = (
      <textarea
        id={inputId}
        rows={2}
        value={string(value)}
        disabled={disabled}
        aria-invalid={!!error}
        onInput={(e) => change(e.currentTarget.value)}
      />
    );
  return (
    <div class={`setting ${error ? 'invalid' : ''}`}>
      <div class="setting-heading">
        <label for={inputId}>{field.label || field.key}</label>
        {field.origin && (
          <span class="badge">
            {field.origin === 'server'
              ? 'Server override'
              : field.origin === 'network'
                ? 'Network default'
                : 'Local baseline'}
          </span>
        )}
      </div>
      {field.help && field.help.trim() !== field.label.trim() && <p class="help">{field.help}</p>}
      {control}
      {error && (
        <p class="text-error" role="alert">
          {error}
        </p>
      )}
      <div class="field-meta">
        <code>{field.key}</code>
        <span>
          {field.durationUnit || ''}{' '}
          {field.reloadBehavior !== 'IMMEDIATE'
            ? field.reloadBehavior.replace(/_/g, ' ').toLowerCase()
            : ''}
        </span>
        {!disabled && !field.masked && (
          <button class="text-button" onClick={() => change(field.defaultValue.value)}>
            {t('Use default')}
          </button>
        )}
      </div>
    </div>
  );
}
