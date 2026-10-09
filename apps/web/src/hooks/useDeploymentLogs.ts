"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api, toApiError } from "@/lib/api";
import type { DeploymentLog } from "@/types";

/**
 * Deployment log lines, kept in `seq` order without duplicates.
 *
 * While the deployment is in progress the hook streams `/logs/stream` (Server-Sent Events). The server
 * sends missed lines first; event ids are line `seq`s, so the browser's automatic reconnect resumes
 * exactly where it stopped. Once the deployment settles the stream is closed (otherwise EventSource
 * would reconnect forever) and one REST read picks up anything still in flight.
 */
export function useDeploymentLogs(deploymentId: string, settled: boolean) {
  const [lines, setLines] = useState<DeploymentLog[]>([]);
  const [live, setLive] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const lastSeq = useRef(0);

  const append = useCallback((incoming: DeploymentLog[]) => {
    const fresh = incoming.filter((line) => line.seq > lastSeq.current).sort((a, b) => a.seq - b.seq);
    if (fresh.length === 0) return;
    lastSeq.current = fresh[fresh.length - 1]!.seq;
    setLines((current) => [...current, ...fresh]);
  }, []);

  const fetchRemaining = useCallback(async () => {
    try {
      append(await api.deployments.logs(deploymentId, lastSeq.current));
      setError(null);
    } catch (e) {
      setError(toApiError(e).message);
    }
  }, [append, deploymentId]);

  // New deployment: start from scratch.
  useEffect(() => {
    lastSeq.current = 0;
    setLines([]);
  }, [deploymentId]);

  useEffect(() => {
    if (settled) {
      void fetchRemaining();
      return;
    }
    const source = new EventSource(`${api.deployments.logStreamUrl(deploymentId)}?after=${lastSeq.current}`, {
      withCredentials: true,
    });
    source.onopen = () => setLive(true);
    source.onerror = () => setLive(false); // EventSource retries by itself, sending Last-Event-ID
    source.addEventListener("log", (event) => {
      append([JSON.parse((event as MessageEvent<string>).data) as DeploymentLog]);
    });
    return () => {
      source.close();
      setLive(false);
    };
  }, [append, deploymentId, fetchRemaining, settled]);

  return { lines, live, error };
}
