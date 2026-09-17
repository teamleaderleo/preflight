import { useEffect, useRef, useState } from "react";

interface BattleSizeInputProps {
  id: string;
  label: string;
  value: number;
  min: number;
  max: number;
  disabled?: boolean;
  onCommit: (value: number) => void;
}

export function BattleSizeInput({ id, label, value, min, max, disabled = false, onCommit }: BattleSizeInputProps) {
  const [text, setText] = useState(String(value));
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (document.activeElement !== inputRef.current) setText(String(value));
  }, [value]);

  const commit = () => {
    const candidate = Number(text);
    if (!text.trim() || !Number.isFinite(candidate)) {
      setText(String(value));
      return;
    }
    const next = Math.min(max, Math.max(min, candidate));
    setText(String(next));
    if (next !== value) onCommit(next);
  };

  return <input
    ref={inputRef}
    id={id}
    aria-label={label}
    type="number"
    min={min}
    max={max}
    step="10"
    value={text}
    disabled={disabled}
    onChange={(event) => setText(event.target.value)}
    onBlur={commit}
    onKeyDown={(event) => {
      if (event.key === "Enter") event.currentTarget.blur();
      if (event.key === "Escape") {
        setText(String(value));
        event.currentTarget.blur();
      }
    }}
  />;
}
