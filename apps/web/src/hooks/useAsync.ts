"use client";

import { useCallback, useEffect, useRef, useState, type DependencyList } from "react";
import { ApiError, toApiError } from "@/lib/api";

export type AsyncState<T> =
  | { status: "loading"; data?: undefined; error?: undefined }
  | { status: "success"; data: T; error?: undefined }
  | { status: "error"; data?: undefined; error: ApiError };

/**
 * Loads data and tracks loading / error / success, ignoring responses that arrive after the inputs
 * changed or the component unmounted. `reload` refetches in the background without flashing a spinner.
 */
export function useAsync<T>(load: () => Promise<T>, deps: DependencyList) {
  const [state, setState] = useState<AsyncState<T>>({ status: "loading" });
  const generation = useRef(0);
  const loadRef = useRef(load);
  loadRef.current = load;

  const run = useCallback(async (background: boolean) => {
    const current = ++generation.current;
    if (!background) setState({ status: "loading" });
    try {
      const data = await loadRef.current();
      if (current === generation.current) setState({ status: "success", data });
    } catch (e) {
      if (current === generation.current) setState({ status: "error", error: toApiError(e) });
    }
  }, []);

  useEffect(() => {
    void run(false);
    return () => {
      generation.current++;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  const reload = useCallback(() => run(true), [run]);
  const setData = useCallback((data: T) => setState({ status: "success", data }), []);
  return { ...state, reload, setData };
}
