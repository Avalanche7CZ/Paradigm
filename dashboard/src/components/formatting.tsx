import type { JSX } from 'preact';
import { useRef } from 'preact/hooks';
export interface Segment {
  text: string;
  style: JSX.CSSProperties;
}
const colors: Record<string, string> = {
  black: '#000000',
  dark_blue: '#0000aa',
  dark_green: '#00aa00',
  dark_aqua: '#00aaaa',
  dark_red: '#aa0000',
  dark_purple: '#aa00aa',
  gold: '#ffaa00',
  gray: '#aaaaaa',
  dark_gray: '#555555',
  blue: '#5555ff',
  green: '#55ff55',
  aqua: '#55ffff',
  red: '#ff5555',
  light_purple: '#ff55ff',
  yellow: '#ffff55',
  white: '#ffffff',
};
export function previewSegments(raw: string): { segments: Segment[]; unsupported: string[] } {
  const segments: Segment[] = [];
  const unsupported = new Set<string>();
  let style: JSX.CSSProperties = {};
  const stack: { tag: string; style: JSX.CSSProperties }[] = [];
  const append = (text: string) => {
    if (text) segments.push({ text, style: { ...style } });
  };
  for (const token of raw.split(/(<[^>]+>|[&§][0-9a-fk-or])/gi)) {
    if (/^[&§][0-9a-fk-or]$/i.test(token)) {
      const code = token[1].toLowerCase();
      const color = Object.values(colors)['0123456789abcdef'.indexOf(code)];
      if (color) style = { color };
      else if (code === 'r') style = {};
      else if (code === 'l') style.fontWeight = 700;
      else if (code === 'o') style.fontStyle = 'italic';
      else if (code === 'n') style.textDecoration = 'underline';
      else if (code === 'm') style.textDecoration = 'line-through';
      else unsupported.add('obfuscated');
      continue;
    }
    if (!token.startsWith('<')) {
      append(token);
      continue;
    }
    const tag = token.slice(1, -1).toLowerCase();
    if (tag.startsWith('/')) {
      const idx = stack.map((v) => v.tag).lastIndexOf(tag.slice(1));
      if (idx >= 0) {
        style = stack[idx].style;
        stack.splice(idx);
      } else {
        unsupported.add(tag);
        append(token);
      }
      continue;
    }
    const next = { ...style };
    const color =
      colors[tag] ||
      (tag.startsWith('color:')
        ? colors[tag.slice(6)] || (/^#[0-9a-f]{6}$/i.test(tag.slice(6)) ? tag.slice(6) : '')
        : /^#[0-9a-f]{6}$/i.test(tag)
          ? tag
          : '');
    if (tag === 'reset') {
      style = {};
      stack.length = 0;
      continue;
    }
    if (tag === 'newline' || tag === 'br') {
      append('\n');
      continue;
    }
    if (color) next.color = color;
    else if (['bold', 'b'].includes(tag)) next.fontWeight = 700;
    else if (['italic', 'i'].includes(tag)) next.fontStyle = 'italic';
    else if (['underlined', 'underline', 'u'].includes(tag)) next.textDecoration = 'underline';
    else if (['strikethrough', 'st'].includes(tag)) next.textDecoration = 'line-through';
    else {
      unsupported.add(tag.split(':')[0]);
      append(token);
      continue;
    }
    stack.push({ tag: tag.startsWith('color:') ? 'color' : tag, style: { ...style } });
    style = next;
  }
  return { segments, unsupported: [...unsupported] };
}
export function FormatPreview({ text }: { text: string }) {
  const { segments, unsupported } = previewSegments(text);
  return (
    <div class="preview">
      <div class="preview-label">Formatting preview</div>
      <div class="minecraft-preview">
        {segments.map((v, i) => (
          <span key={i} style={v.style}>
            {v.text}
          </span>
        ))}
      </div>
      <p class="muted">
        Approximation of colors, basic styles and line breaks. Placeholders remain literal.
        Minecraft fonts, click/hover events, gradients and platform rendering require an in-game
        check.
      </p>
      {unsupported.length > 0 && (
        <p class="text-warning">
          Not previewed: {unsupported.join(', ')}. The original text is preserved.
        </p>
      )}
    </div>
  );
}
export function FormatEditor({
  value,
  change,
  disabled,
  label,
  id,
  placeholders = [],
}: {
  value: string;
  change: (v: string) => void;
  disabled?: boolean;
  label: string;
  id?: string;
  placeholders?: string[];
}) {
  const Field = id ? 'div' : 'label';
  const ref = useRef<HTMLTextAreaElement>(null);
  const wrap = (start: string, end = '') => {
    const element = ref.current;
    if (!element) return;
    const from = element.selectionStart,
      to = element.selectionEnd;
    change(value.slice(0, from) + start + value.slice(from, to) + end + value.slice(to));
    requestAnimationFrame(() => {
      element.focus();
      element.setSelectionRange(from + start.length, to + start.length);
    });
  };
  return (
    <div class="format-editor">
      <div class="format-tools" aria-label="Formatting helpers">
        {[
          ['Bold', '<bold>', '</bold>'],
          ['Italic', '<italic>', '</italic>'],
          ['Underline', '<underlined>', '</underlined>'],
          ['Indigo', '<color:#818cf8>', '</color>'],
          ['Gradient', '<gradient:#60a5fa:#a78bfa>', '</gradient>'],
        ].map(([name, start, end]) => (
          <button type="button" disabled={disabled} onClick={() => wrap(start, end)}>
            {name}
          </button>
        ))}
        <select
          aria-label="Insert placeholder"
          disabled={disabled}
          value=""
          onChange={(e) => wrap(e.currentTarget.value)}
        >
          <option value="">Insert placeholder…</option>
          {placeholders.map((token) => (
            <option value={token}>{token}</option>
          ))}
        </select>
      </div>
      <Field class="field">
        {!id && <span>{label}</span>}
        <textarea
          id={id}
          ref={ref}
          class="code"
          value={value}
          disabled={disabled}
          onInput={(e) => change(e.currentTarget.value)}
          rows={4}
        />
      </Field>
      <FormatPreview text={value} />
    </div>
  );
}
