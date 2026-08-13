import { waitFor } from "@testing-library/react";
import { listen } from "@tauri-apps/api/event";
import { listenWhileMounted } from "./tauriEvents";

vi.mock("@tauri-apps/api/event", () => ({ listen: vi.fn() }));

const mockedListen = vi.mocked(listen);

beforeEach(() => {
  mockedListen.mockReset();
});

test("unsubscribes an active native event stream", async () => {
  const unlisten = vi.fn();
  mockedListen.mockResolvedValue(unlisten);

  const stop = listenWhileMounted("state", vi.fn());
  await waitFor(() => expect(mockedListen).toHaveBeenCalledOnce());
  stop();

  await waitFor(() => expect(unlisten).toHaveBeenCalledOnce());
});

test("keeps one native stream while an effect resubscribes in the same turn", async () => {
  const unlisten = vi.fn();
  let nativeHandler: ((event: { event: string; id: number; payload: unknown }) => void) | undefined;
  mockedListen.mockImplementation(async (_event, handler) => {
    nativeHandler = handler as typeof nativeHandler;
    return unlisten;
  });
  const firstHandler = vi.fn();
  const secondHandler = vi.fn();

  const stopFirst = listenWhileMounted("run-state", firstHandler);
  await waitFor(() => expect(mockedListen).toHaveBeenCalledOnce());

  stopFirst();
  const stopSecond = listenWhileMounted("run-state", secondHandler);
  await Promise.resolve();

  expect(mockedListen).toHaveBeenCalledOnce();
  expect(unlisten).not.toHaveBeenCalled();

  nativeHandler?.({ event: "run-state", id: 1, payload: { state: "finished" } });
  expect(firstHandler).not.toHaveBeenCalled();
  expect(secondHandler).toHaveBeenCalledOnce();

  stopSecond();
  await waitFor(() => expect(unlisten).toHaveBeenCalledOnce());
});

test("unsubscribes when registration completes after disposal", async () => {
  const unlisten = vi.fn();
  let finishRegistration: ((unlisten: () => void) => void) | undefined;
  mockedListen.mockReturnValue(new Promise((resolve) => {
    finishRegistration = resolve;
  }));

  const stop = listenWhileMounted("state", vi.fn());
  stop();
  finishRegistration?.(unlisten);

  await waitFor(() => expect(unlisten).toHaveBeenCalledOnce());
});

test("reports registration failures only while mounted", async () => {
  const onError = vi.fn();
  mockedListen.mockRejectedValue(new Error("permission denied"));

  listenWhileMounted("state", vi.fn(), onError);

  await waitFor(() => expect(onError).toHaveBeenCalledWith(expect.objectContaining({ message: "permission denied" })));

  let rejectRegistration: ((error: unknown) => void) | undefined;
  mockedListen.mockReturnValue(new Promise((_, reject) => {
    rejectRegistration = reject;
  }));
  const stop = listenWhileMounted("state", vi.fn(), onError);
  stop();
  rejectRegistration?.(new Error("late failure"));
  await Promise.resolve();

  expect(onError).toHaveBeenCalledOnce();
});
