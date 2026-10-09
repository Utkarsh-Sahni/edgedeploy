"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";

/**
 * Subscribes to a deployment's Server-Sent Events while `enabled`. Events are change notifications:
 * `onChange` should re-read the deployment over REST (the source of truth).
 */
export function useDeploymentEvents(deploymentId: string, enabled: boolean, onChange: () => void) {
  const [live, setLive] = useState(false);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  useEffect(() => {
    if (!enabled) {
      setLive(false);
      return;
    }
    const source = new EventSource(api.deployments.eventsUrl(deploymentId), { withCredentials: true });
    source.onopen = () => setLive(true);
    source.onerror = () => setLive(false); // EventSource reconnects by itself
    source.addEventListener("deployment", () => onChangeRef.current());
    return () => {
      // Closing explicitly also stops EventSource from auto-reconnecting after the server ends the stream.
      source.close();
      setLive(false);
    };
  }, [deploymentId, enabled]);

  return live;
}
