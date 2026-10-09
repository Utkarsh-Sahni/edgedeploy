const relative = new Intl.RelativeTimeFormat("en", { numeric: "auto" });

export function timeAgo(iso: string, now: number = Date.now()): string {
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000);
  const abs = Math.abs(seconds);
  if (abs < 60) return relative.format(seconds, "second");
  if (abs < 3600) return relative.format(Math.round(seconds / 60), "minute");
  if (abs < 86400) return relative.format(Math.round(seconds / 3600), "hour");
  return relative.format(Math.round(seconds / 86400), "day");
}

export function duration(startIso: string | null, endIso: string | null, now: number = Date.now()): string {
  if (!startIso) return "—";
  const end = endIso ? new Date(endIso).getTime() : now;
  const total = Math.max(0, Math.round((end - new Date(startIso).getTime()) / 1000));
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return minutes > 0 ? `${minutes}m ${seconds}s` : `${seconds}s`;
}

export function shortId(id: string): string {
  return id.replace(/-/g, "").slice(0, 8);
}

export function shortSha(sha: string | null): string {
  return sha ? sha.slice(0, 7) : "branch tip";
}

export function clockTime(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}
