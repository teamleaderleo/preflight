import { listen, type EventCallback } from "@tauri-apps/api/event";

export function listenWhileMounted<T>(
  event: string,
  handler: EventCallback<T>,
  onError?: (error: unknown) => void,
): () => void {
  let disposed = false;
  let stopListening: (() => void) | undefined;
  void listen<T>(event, handler).then((unlisten) => {
    if (disposed) {
      unlisten();
    } else {
      stopListening = unlisten;
    }
  }).catch((error: unknown) => {
    if (!disposed) onError?.(error);
  });
  return () => {
    disposed = true;
    stopListening?.();
  };
}
