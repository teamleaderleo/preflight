import { act, render, screen } from "@testing-library/react";
import { createElement } from "react";
import { afterEach, expect, test, vi } from "vitest";
import { ResolutionSelect } from "./GameSettingSelects";

const matchMediaDescriptor = Object.getOwnPropertyDescriptor(window, "matchMedia");
const screenDescriptor = Object.getOwnPropertyDescriptor(window, "screen");
const devicePixelRatioDescriptor = Object.getOwnPropertyDescriptor(window, "devicePixelRatio");

afterEach(() => {
  vi.restoreAllMocks();
  if (matchMediaDescriptor) {
    Object.defineProperty(window, "matchMedia", matchMediaDescriptor);
  } else {
    Reflect.deleteProperty(window, "matchMedia");
  }
  if (screenDescriptor) Object.defineProperty(window, "screen", screenDescriptor);
  if (devicePixelRatioDescriptor) Object.defineProperty(window, "devicePixelRatio", devicePixelRatioDescriptor);
});

function setDisplay(width: number, height: number, pixelRatio: number) {
  Object.defineProperty(window, "screen", {
    configurable: true,
    value: { width, height },
  });
  Object.defineProperty(window, "devicePixelRatio", {
    configurable: true,
    value: pixelRatio,
  });
}

function installMatchMediaMock() {
  const queries: Array<{
    media: string;
    addEventListener: ReturnType<typeof vi.fn>;
    removeEventListener: ReturnType<typeof vi.fn>;
    fireChange: () => void;
  }> = [];
  const matchMedia = vi.fn((media: string) => {
    let changeListener: (() => void) | null = null;
    const query = {
      matches: false,
      media,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn((type: string, listener: () => void) => {
        if (type === "change") changeListener = listener;
      }),
      removeEventListener: vi.fn((type: string, listener: () => void) => {
        if (type === "change" && changeListener === listener) changeListener = null;
      }),
      dispatchEvent: vi.fn(() => true),
      fireChange: () => changeListener?.(),
    };
    queries.push(query);
    return query as unknown as MediaQueryList;
  });
  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    value: matchMedia,
  });
  return queries;
}

function optionValues(select: HTMLSelectElement): string[] {
  return Array.from(select.options, (option) => option.value);
}

test("resolution choices refresh and re-arm when display DPR changes", () => {
  setDisplay(1920, 1080, 1);
  const queries = installMatchMediaMock();
  const rendered = render(createElement(ResolutionSelect, {
    id: "resolution",
    label: "Resolution",
    value: "1440x900",
    onChange: vi.fn(),
  }));
  const select = screen.getByLabelText("Resolution") as HTMLSelectElement;

  expect(optionValues(select)).toContain("1920x1080");
  expect(optionValues(select)).not.toContain("2560x1440");
  expect(queries).toHaveLength(1);
  expect(queries[0].media).toBe("(resolution: 1dppx)");
  expect(queries[0].addEventListener)
    .toHaveBeenCalledWith("change", expect.any(Function), { once: true });

  setDisplay(1440, 900, 2);
  act(() => queries[0].fireChange());

  expect(optionValues(select)).toContain("2560x1440");
  expect(optionValues(select)).toContain("2880x1800");
  expect(queries[0].removeEventListener)
    .toHaveBeenCalledWith("change", expect.any(Function));
  expect(queries).toHaveLength(2);
  expect(queries[1].media).toBe("(resolution: 2dppx)");
  expect(queries[1].addEventListener)
    .toHaveBeenCalledWith("change", expect.any(Function), { once: true });

  rendered.unmount();
  expect(queries[1].removeEventListener)
    .toHaveBeenCalledWith("change", expect.any(Function));
});
