export interface NativeCommandError {
  code: string;
  message: string;
  retryable: boolean;
  detail?: string;
}

const ALLOWED_KEYS = new Set(["code", "message", "retryable", "detail"]);

export function nativeCommandError(error: unknown): NativeCommandError | null {
  if (!error || typeof error !== "object" || Array.isArray(error)) return null;
  const value = error as Record<string, unknown>;
  const keys = Object.keys(value);
  if (keys.some((key) => !ALLOWED_KEYS.has(key))) return null;
  if (typeof value.code !== "string" || value.code.length === 0) return null;
  if (typeof value.message !== "string" || value.message.length === 0) return null;
  if (typeof value.retryable !== "boolean") return null;
  if (value.detail !== undefined && typeof value.detail !== "string") return null;
  return {
    code: value.code,
    message: value.message,
    retryable: value.retryable,
    ...(typeof value.detail === "string" ? { detail: value.detail } : {}),
  };
}
