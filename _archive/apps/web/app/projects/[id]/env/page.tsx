"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { api, type EnvVar } from "@/lib/api";

export default function EnvPage() {
  const params = useParams<{ id: string }>();
  const [vars, setVars] = useState<EnvVar[]>([]);
  const [key, setKey] = useState("");
  const [value, setValue] = useState("");

  async function load() {
    setVars(await api.env(params.id));
  }

  useEffect(() => {
    load().catch(() => undefined);
  }, [params.id]);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    await api.upsertEnv(params.id, key, value);
    setKey("");
    setValue("");
    await load();
  }

  return (
    <Shell>
      <Link href={`/projects/${params.id}`} className="text-sm text-zinc-400 hover:text-white">
        ← Back to project
      </Link>
      <h1 className="mt-4 text-3xl font-semibold">Environment variables</h1>
      <p className="mt-1 text-zinc-400">Values are encrypted at rest and never returned by the API.</p>

      <form onSubmit={submit} className="panel mt-6 grid gap-3 p-5 md:grid-cols-[1fr_1fr_auto]">
        <input
          placeholder="NODE_ENV"
          className="rounded-lg border border-white/10 bg-ink-800 px-3 py-2"
          value={key}
          onChange={(e) => setKey(e.target.value.toUpperCase())}
          required
        />
        <input
          placeholder="value"
          type="password"
          className="rounded-lg border border-white/10 bg-ink-800 px-3 py-2"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          required
        />
        <button className="rounded-full bg-accent px-4 py-2 text-sm font-semibold text-ink-950">Save</button>
      </form>

      <div className="mt-6 grid gap-2">
        {vars.map((variable) => (
          <div key={variable.id} className="panel flex items-center justify-between px-4 py-3">
            <span className="font-mono">{variable.key}</span>
            <button
              className="text-sm text-rose-300"
              onClick={async () => {
                await api.deleteEnv(params.id, variable.id);
                await load();
              }}
            >
              Delete
            </button>
          </div>
        ))}
      </div>
    </Shell>
  );
}
