import { useCallback, useId, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";

interface InfoTipProps {
  label: string;
  children: ReactNode;
}

const WIDTH = 290;
const MARGIN = 12;

/**
 * A tooltip rendered into the document body rather than beside its trigger.
 *
 * <p>Absolutely positioning it inside the card left it at the mercy of whatever stacking context an
 * ancestor happened to create -- the optimizations tip ended at "keeps the original" with "code."
 * painted over by the next card, and a z-index cannot lift an element out of a stacking context it
 * is trapped in. Raising the card itself fixed it in Chromium and not in the WebKit the packaged
 * app runs on. A portal sidesteps the question: the tooltip is a child of body, so no card is above
 * it and none can clip it.
 *
 * <p>It stays mounted and hidden rather than unmounting, so the id in aria-describedby always
 * resolves to a real element.
 */
export function InfoTip({ label, children }: InfoTipProps) {
  const tooltipId = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const tooltip = useRef<HTMLSpanElement>(null);
  const [open, setOpen] = useState(false);
  const [placement, setPlacement] = useState({ top: -9999, left: -9999 });

  const place = useCallback(() => {
    if (!trigger.current) return;
    const anchor = trigger.current.getBoundingClientRect();
    const width = Math.min(WIDTH, window.innerWidth - 4 * MARGIN);
    const height = tooltip.current?.offsetHeight ?? 0;
    const below = anchor.bottom - 3;
    // Flip above the trigger rather than run off the bottom of a short window.
    const top = height > 0 && below + height + MARGIN > window.innerHeight
      ? Math.max(MARGIN, anchor.top - height + 3)
      : below;
    setPlacement({
      top,
      left: Math.max(MARGIN, Math.min(anchor.left + 8, window.innerWidth - width - MARGIN)),
    });
  }, []);

  // The tooltip is already in the DOM, so it has a height to measure. Placing before paint means it
  // is never briefly visible in the wrong spot.
  useLayoutEffect(() => {
    if (open) place();
  }, [open, place]);

  return (
    <span
      className="info-tip"
      onPointerEnter={() => setOpen(true)}
      onPointerLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
    >
      <button
        ref={trigger}
        className="info-tip__trigger"
        type="button"
        aria-label={label}
        aria-describedby={tooltipId}
      >
        <span aria-hidden="true">i</span>
      </button>
      {createPortal(
        <span
          ref={tooltip}
          className={`info-tip__content ${open ? "info-tip__content--open" : ""}`}
          id={tooltipId}
          role="tooltip"
          style={{ top: placement.top, left: placement.left }}
        >
          {children}
        </span>,
        document.body,
      )}
    </span>
  );
}
