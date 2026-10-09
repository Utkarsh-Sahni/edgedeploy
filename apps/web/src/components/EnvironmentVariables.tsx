"use client";

import { useState, type FormEvent } from "react";
import { Banner, Button, Card, Field, inputClass } from "@/components/ui";
import { useAsync } from "@/hooks/useAsync";
import { api, toApiError } from "@/lib/api";

const KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/;

/**
 * GET/PUT/DELETE /api/projects/{id}/env. Values are write-only: the API never returns them, so this UI
 * only lists names and lets the user replace or remove a value.
 */
export function EnvironmentVariables({ projectId }: { projectId: string }) {
  const variables = useAsync(() => api.environment.list(projectId), [projectId]);
  const [key, setKey] = useState("");
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<{ tone: "error" | "success"; text: string } | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const trimmedKey = key.trim();
  const keyError = trimmedKey && !KEY_PATTERN.test(trimmedKey) ? "Letters, digits and underscores; must not start with a digit" : undefined;

  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy("save");
    setMessage(null);
    setFieldErrors({});
    try {
      await api.environment.set(projectId, trimmedKey, value);
      setKey("");
      setValue("");
      setMessage({ tone: "success", text: `${trimmedKey} saved. It applies to the next deployment.` });
      await variables.reload();
    } catch (e) {
      const error = toApiError(e);
      setFieldErrors(error.fieldErrors);
      setMessage({ tone: "error", text: error.message });
    } finally {
      setBusy(null);
    }
  }

  async function remove(name: string) {
    if (!window.confirm(`Delete ${name}? Running deployments keep it until the next deployment.`)) return;
    setBusy(name);
    setMessage(null);
    try {
      await api.environment.remove(projectId, name);
      await variables.reload();
    } catch (e) {
      setMessage({ tone: "error", text: toApiError(e).message });
    } finally {
      setBusy(null);
    }
  }

  return (
    <Card className="p-6">
      <h2 className="font-medium">Environment variables</h2>
      <p className="mt-1 text-sm text-zinc-500">
        Encrypted at rest and injected into the container at deploy time. Values are write-only and never shown again.
        Changes apply to the next deployment.
      </p>

      <div className="mt-5 space-y-5">
        {message && <Banner tone={message.tone}>{message.text}</Banner>}

        {variables.status === "error" && <Banner>{variables.error.message}</Banner>}
        {variables.status === "success" && variables.data.length > 0 && (
          <ul className="divide-y divide-zinc-200 rounded-lg border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
            {variables.data.map((variable) => (
              <li key={variable.key} className="flex items-center gap-3 px-4 py-2.5">
                <span className="font-mono text-sm">{variable.key}</span>
                <span className="font-mono text-sm text-zinc-400" aria-label="hidden value">••••••••</span>
                <span className="ml-auto text-xs text-zinc-500">
                  Updated {new Date(variable.updatedAt).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" })}
                </span>
                <Button variant="secondary" onClick={() => remove(variable.key)} disabled={busy !== null}>
                  {busy === variable.key ? "Deleting…" : "Delete"}
                </Button>
              </li>
            ))}
          </ul>
        )}
        {variables.status === "success" && variables.data.length === 0 && (
          <p className="text-sm text-zinc-500">No environment variables yet.</p>
        )}

        <form onSubmit={save} className="grid gap-4 sm:grid-cols-[minmax(0,1fr)_minmax(0,2fr)_auto] sm:items-end" noValidate>
          <Field label="Key" htmlFor="env-key" error={keyError ?? fieldErrors.key}>
            <input id="env-key" value={key} onChange={(e) => setKey(e.target.value)} placeholder="DATABASE_URL" maxLength={128}
              autoComplete="off" spellCheck={false} className={`${inputClass} font-mono`} />
          </Field>
          <Field label="Value" htmlFor="env-value" error={fieldErrors.value}>
            <input id="env-value" type="password" value={value} onChange={(e) => setValue(e.target.value)} maxLength={4096}
              autoComplete="new-password" spellCheck={false} className={`${inputClass} font-mono`} />
          </Field>
          <Button type="submit" disabled={busy !== null || !trimmedKey || Boolean(keyError)}>
            {busy === "save" ? "Saving…" : "Save"}
          </Button>
        </form>
        <p className="text-xs text-zinc-500">Saving an existing key replaces its value. PORT and EDGEDEPLOY_* are reserved.</p>
      </div>
    </Card>
  );
}
