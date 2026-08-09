import { useCallback, useRef, useState } from "react";
import type { NoticeTone } from "./types";

export type WorkflowNoticeScope =
  | "installation"
  | "game"
  | "game-settings"
  | "preparation"
  | "profiles"
  | "cache"
  | "benchmark"
  | "support"
  | "updates"
  | "removal";

export interface WorkflowNotice {
  message: string;
  tone: NoticeTone;
  sequence: number;
}

type NoticeMap = Partial<Record<WorkflowNoticeScope, WorkflowNotice>>;

export function useWorkflowNotices() {
  const [notices, setNotices] = useState<NoticeMap>({});
  const nextSequence = useRef(0);

  const announce = useCallback((scope: WorkflowNoticeScope, message: string, tone: NoticeTone = "info") => {
    setNotices((current) => ({
      ...current,
      [scope]: { message, tone, sequence: ++nextSequence.current },
    }));
  }, []);

  const clear = useCallback((scope: WorkflowNoticeScope) => {
    setNotices((current) => {
      if (!current[scope]) return current;
      const next = { ...current };
      delete next[scope];
      return next;
    });
  }, []);

  const latest = useCallback((scopes: readonly WorkflowNoticeScope[]): WorkflowNotice | null => {
    let result: WorkflowNotice | null = null;
    for (const scope of scopes) {
      const candidate = notices[scope];
      if (candidate && (!result || candidate.sequence > result.sequence)) result = candidate;
    }
    return result;
  }, [notices]);

  return { announce, clear, latest };
}
