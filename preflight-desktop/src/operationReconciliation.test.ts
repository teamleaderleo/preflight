import { startOperationReconciliation } from "./operationReconciliation";
import type { OperationSnapshot } from "./types";

const idle: OperationSnapshot = {
  format: "preflight-operation-state-v1",
  gamePid: null,
  gameRecovered: false,
  desktopSmokePid: null,
  desktopSmokeRunDirectory: null,
  preparationPid: null,
  reportUploadId: null,
  reportUploadTotalBytes: null,
  updateInstalling: false,
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((finish) => {
    resolve = finish;
  });
  return { promise, resolve };
}

afterEach(() => {
  vi.useRealTimers();
});

test("reads immediately and polls serially while the reconciled state stays active", async () => {
  vi.useFakeTimers();
  let active = true;
  const read = vi.fn()
    .mockResolvedValueOnce({ ...idle, preparationPid: 42 })
    .mockResolvedValueOnce(idle);
  const apply = vi.fn((snapshot: OperationSnapshot) => {
    active = snapshot.preparationPid !== null;
  });

  const cancel = startOperationReconciliation({
    apply,
    isActive: () => active,
    onError: vi.fn(),
    read,
  });

  expect(read).toHaveBeenCalledOnce();
  await vi.advanceTimersByTimeAsync(0);
  expect(apply).toHaveBeenCalledWith(expect.objectContaining({ preparationPid: 42 }));

  await vi.advanceTimersByTimeAsync(999);
  expect(read).toHaveBeenCalledOnce();
  await vi.advanceTimersByTimeAsync(1);
  expect(read).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(0);
  expect(apply).toHaveBeenLastCalledWith(idle);

  await vi.advanceTimersByTimeAsync(2_000);
  expect(read).toHaveBeenCalledTimes(2);
  cancel();
});

test("cancellation fences an in-flight result and prevents another poll", async () => {
  vi.useFakeTimers();
  const pending = deferred<OperationSnapshot>();
  const apply = vi.fn();
  const read = vi.fn(() => pending.promise);
  const cancel = startOperationReconciliation({
    apply,
    isActive: () => true,
    onError: vi.fn(),
    read,
  });

  expect(read).toHaveBeenCalledOnce();
  cancel();
  pending.resolve({ ...idle, gamePid: 84 });
  await vi.advanceTimersByTimeAsync(2_000);

  expect(apply).not.toHaveBeenCalled();
  expect(read).toHaveBeenCalledOnce();
});

test("reports read and callback failures without leaking rejected work", async () => {
  vi.useFakeTimers();
  let active = true;
  const failure = new Error("coordinator unavailable");
  const onError = vi.fn(() => {
    active = false;
    throw new Error("broken error renderer");
  });
  const read = vi.fn().mockRejectedValue(failure);

  const cancel = startOperationReconciliation({
    apply: vi.fn(),
    isActive: () => active,
    onError,
    read,
  });
  await vi.advanceTimersByTimeAsync(0);

  expect(onError).toHaveBeenCalledWith(failure);
  await vi.advanceTimersByTimeAsync(2_000);
  expect(read).toHaveBeenCalledOnce();
  cancel();
});

test("reports one error per failure streak while continuing to reconcile", async () => {
  vi.useFakeTimers();
  const onError = vi.fn();
  const read = vi.fn()
    .mockRejectedValueOnce(new Error("offline one"))
    .mockRejectedValueOnce(new Error("offline two"))
    .mockResolvedValueOnce(idle)
    .mockRejectedValueOnce(new Error("offline again"));
  const cancel = startOperationReconciliation({
    apply: vi.fn(),
    isActive: () => true,
    onError,
    read,
  });

  await vi.advanceTimersByTimeAsync(0);
  expect(onError).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(1_000);
  expect(onError).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(1_000);
  await vi.advanceTimersByTimeAsync(1_000);
  expect(onError).toHaveBeenCalledTimes(2);
  cancel();
});
