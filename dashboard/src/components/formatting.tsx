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
const tagName = (tag: string) => (tag === 'gr' ? 'gradient' : tag);
const gradientColor = (value: string) => {
  const name = value.trim().toLowerCase().replace(/[- ]/g, '_');
  const aliases: Record<string, string> = {
    darkblue: 'dark_blue',
    darkgreen: 'dark_green',
    darkaqua: 'dark_aqua',
    darkred: 'dark_red',
    darkpurple: 'dark_purple',
    lightpurple: 'light_purple',
    darkgray: 'dark_gray',
    lightgray: 'gray',
    grey: 'gray',
    dark_grey: 'dark_gray',
    orange: 'gold',
    pink: 'light_purple',
    magenta: 'light_purple',
    cyan: 'aqua',
    dark_cyan: 'dark_aqua',
  };
  return (
    colors[aliases[name] || name] ||
    (/^#?[0-9a-f]{6}$/.test(name) ? `#${name.replace('#', '')}` : '#ffffff')
  );
};
export function previewSegments(raw: string): { segments: Segment[]; unsupported: string[] } {
  const segments: Segment[] = [];
  const unsupported = new Set<string>();
  let style: JSX.CSSProperties = {};
  const stack: { tag: string; style: JSX.CSSProperties; from: number; gradient?: string[] }[] = [];
  const close = () => {
    const frame = stack.pop()!;
    const gradient = frame.gradient;
    if (gradient) {
      const content = segments
        .splice(frame.from)
        .flatMap((segment) =>
          Array.from(segment.text, (text) => ({ text, style: { ...segment.style } })),
        );
      if (gradient.length >= 2) {
        content.forEach((segment, index) => {
          const position = (index / Math.max(1, content.length - 1)) * (gradient.length - 1);
          const before = Math.floor(position),
            after = Math.min(before + 1, gradient.length - 1);
          const start = parseInt(gradient[before].slice(1), 16),
            end = parseInt(gradient[after].slice(1), 16);
          const channels = [16, 8, 0].map((shift) => {
            const a = (start >> shift) & 255,
              b = (end >> shift) & 255;
            return Math.floor(a + (b - a) * (position - before))
              .toString(16)
              .padStart(2, '0');
          });
          segment.style.color = `#${channels.join('')}`;
          segments.push(segment);
        });
      }
    }
    style = frame.style;
  };
  const reset = () => {
    style = {};
    for (const frame of stack) frame.style = {};
  };
  const append = (text: string) => {
    if (text) segments.push({ text, style: { ...style } });
  };
  for (const token of raw.split(/(<[^>]+>|[&§][0-9a-fk-or])/gi)) {
    if (/^[&§][0-9a-fk-or]$/i.test(token)) {
      const code = token[1].toLowerCase();
      const color = Object.values(colors)['0123456789abcdef'.indexOf(code)];
      if (color) style = { color };
      else if (code === 'r') reset();
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
      const idx = stack.map((v) => v.tag).lastIndexOf(tagName(tag.slice(1)));
      if (idx >= 0) {
        while (stack.length > idx) close();
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
      reset();
      continue;
    }
    if (tag === 'newline' || tag === 'br') {
      append('\n');
      continue;
    }
    const [name, ...arguments_] = tag.split(':');
    if (tagName(name) === 'gradient') {
      stack.push({
        tag: 'gradient',
        style: { ...style },
        from: segments.length,
        gradient:
          arguments_.length && arguments_.join(':')
            ? arguments_.map(gradientColor)
            : ['#ff0000', '#00ff00', '#0000ff'],
      });
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
    stack.push({
      tag: tag.startsWith('color:') ? 'color' : tag,
      style: { ...style },
      from: segments.length,
    });
    style = next;
  }
  while (stack.length) close();
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
        Approximation of colors, gradients, basic styles and line breaks. Placeholders remain
        literal. Minecraft fonts, click/hover events and platform rendering require an in-game
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
