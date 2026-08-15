import type { LaunchSettings } from "../types";
import { formatMemory } from "../uiFormat";

const COMMON_MEMORY_MIB = [1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288, 16384];

interface GameMemorySelectProps {
  id: string;
  label: string;
  memory: LaunchSettings["memory"];
  value: number | null;
  disabled?: boolean;
  onChange: (memoryMiB: number) => void;
}

export function GameMemorySelect({ id, label, memory, value, disabled = false, onChange }: GameMemorySelectProps) {
  if (!memory.editable || value === null) {
    return <strong title={memory.reason ?? undefined}>{memory.maxHeapMiB ? formatMemory(memory.maxHeapMiB) : "External"}</strong>;
  }

  const options = Array.from(new Set([...COMMON_MEMORY_MIB, value])).sort((left, right) => left - right);
  return (
    <select id={id} aria-label={label} value={value} disabled={disabled} onChange={(event) => onChange(Number(event.target.value))}>
      {options.map((memoryMiB) => <option value={memoryMiB} key={memoryMiB}>{formatMemory(memoryMiB)}</option>)}
    </select>
  );
}
