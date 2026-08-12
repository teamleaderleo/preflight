import { listen, type Event, type EventCallback } from "@tauri-apps/api/event";

interface Subscriber {
  handler: EventCallback<unknown>;
  onError?: (error: unknown) => void;
}

interface SharedListener {
  subscribers: Set<Subscriber>;
  stopListening?: () => void;
  disposed: boolean;
  teardownScheduled: boolean;
}

const sharedListeners = new Map<string, SharedListener>();

export function listenWhileMounted<T>(
  event: string,
  handler: EventCallback<T>,
  onError?: (error: unknown) => void,
): () => void {
  const subscriber: Subscriber = {
    handler: handler as EventCallback<unknown>,
    onError,
  };
  let shared = sharedListeners.get(event);
  if (!shared || shared.disposed) {
    shared = createSharedListener(event);
    sharedListeners.set(event, shared);
  }

  shared.teardownScheduled = false;
  shared.subscribers.add(subscriber);
  let mounted = true;

  return () => {
    if (!mounted) return;
    mounted = false;
    shared?.subscribers.delete(subscriber);
    if (shared?.subscribers.size !== 0 || shared?.disposed) return;

    shared.teardownScheduled = true;
    queueMicrotask(() => {
      if (!shared || shared.disposed || !shared.teardownScheduled || shared.subscribers.size !== 0) {
        return;
      }
      shared.disposed = true;
      if (sharedListeners.get(event) === shared) {
        sharedListeners.delete(event);
      }
      shared.stopListening?.();
    });
  };
}

function createSharedListener(event: string): SharedListener {
  const shared: SharedListener = {
    subscribers: new Set(),
    disposed: false,
    teardownScheduled: false,
  };

  void listen<unknown>(event, (payload: Event<unknown>) => {
    for (const subscriber of [...shared.subscribers]) {
      subscriber.handler(payload);
    }
  }).then((unlisten) => {
    if (shared.disposed) {
      unlisten();
    } else {
      shared.stopListening = unlisten;
    }
  }).catch((error: unknown) => {
    if (shared.disposed) return;
    shared.disposed = true;
    if (sharedListeners.get(event) === shared) {
      sharedListeners.delete(event);
    }
    for (const subscriber of [...shared.subscribers]) {
      subscriber.onError?.(error);
    }
    shared.subscribers.clear();
  });

  return shared;
}
