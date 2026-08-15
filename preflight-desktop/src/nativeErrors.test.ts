import { describe, expect, test } from "vitest";
import { nativeCommandError } from "./nativeErrors";

describe("nativeCommandError", () => {
  test("accepts the exact stable error envelope", () => {
    expect(nativeCommandError({
      code: "report-upload-cancelled",
      message: "Run report upload was cancelled.",
      retryable: true,
    })).toEqual({
      code: "report-upload-cancelled",
      message: "Run report upload was cancelled.",
      retryable: true,
    });
  });

  test("accepts an optional string detail", () => {
    expect(nativeCommandError({
      code: "report-upload-failed",
      message: "Upload failed.",
      retryable: true,
      detail: "receipt mismatch",
    })?.detail).toBe("receipt mismatch");
  });

  test("rejects unknown fields and malformed envelopes", () => {
    expect(nativeCommandError({
      code: "report-upload-cancelled",
      message: "Run report upload was cancelled.",
      retryable: true,
      surprise: true,
    })).toBeNull();
    expect(nativeCommandError({ code: "report-upload-cancelled", message: "x" })).toBeNull();
    expect(nativeCommandError("Run report upload was cancelled.")).toBeNull();
  });
});
