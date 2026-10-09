"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { BranchSelect } from "@/components/BranchSelect";
import { EnvironmentVariables } from "@/components/EnvironmentVariables";
import { Banner, Button, Card, Field, inputClass } from "@/components/ui";
import { api, toApiError } from "@/lib/api";
import { FRAMEWORKS, FRAMEWORK_LABELS, type Framework, type Project } from "@/types";

/** PATCH /api/projects/{id} and DELETE /api/projects/{id}. */
export function ProjectSettings({ project, onSaved }: { project: Project; onSaved: (project: Project) => void }) {
  const router = useRouter();
  const [name, setName] = useState(project.name);
  const [branch, setBranch] = useState(project.branch);
  const [framework, setFramework] = useState<Framework>(project.framework);
  const [buildCommand, setBuildCommand] = useState(project.defaultBuildCommand ?? "");
  const [startCommand, setStartCommand] = useState(project.defaultStartCommand ?? "");
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [message, setMessage] = useState<{ tone: "error" | "success"; text: string } | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  async function save(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setMessage(null);
    setFieldErrors({});
    try {
      const updated = await api.projects.update(project.id, {
        name: name.trim(),
        branch,
        framework,
        buildCommand: buildCommand.trim(),
        startCommand: startCommand.trim(),
      });
      onSaved(updated);
      setMessage({ tone: "success", text: "Settings saved" });
    } catch (e) {
      const error = toApiError(e);
      setFieldErrors(error.fieldErrors);
      setMessage({ tone: "error", text: error.message });
    } finally {
      setSaving(false);
    }
  }

  async function remove() {
    if (!window.confirm(`Delete ${project.name}? Its deployment history is deleted too. This cannot be undone.`)) return;
    setDeleting(true);
    try {
      await api.projects.remove(project.id);
      router.replace("/projects");
    } catch (e) {
      setMessage({ tone: "error", text: toApiError(e).message });
      setDeleting(false);
    }
  }

  return (
    <div className="space-y-6">
      <Card className="p-6">
        <h2 className="mb-4 font-medium">Settings</h2>
        <form onSubmit={save} className="space-y-5" noValidate>
          {message && <Banner tone={message.tone}>{message.text}</Banner>}
          <div className="grid gap-5 sm:grid-cols-2">
            <Field label="Project name" htmlFor="settings-name" error={fieldErrors.name}>
              <input id="settings-name" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} className={inputClass} />
            </Field>
            <Field label="Branch" htmlFor="settings-branch" error={fieldErrors.branch}>
              <BranchSelect id="settings-branch" repository={project.repository} value={branch} onChange={setBranch} />
            </Field>
            <Field label="Framework" htmlFor="settings-framework">
              <select id="settings-framework" value={framework} onChange={(e) => setFramework(e.target.value as Framework)} className={inputClass}>
                {FRAMEWORKS.map((f) => (
                  <option key={f} value={f}>
                    {FRAMEWORK_LABELS[f]}
                  </option>
                ))}
              </select>
            </Field>
            <div />
            <Field label="Build command" htmlFor="settings-build" error={fieldErrors.buildCommand}>
              <input id="settings-build" value={buildCommand} onChange={(e) => setBuildCommand(e.target.value)} placeholder="npm run build" className={`${inputClass} font-mono`} />
            </Field>
            <Field label="Start command" htmlFor="settings-start" error={fieldErrors.startCommand}>
              <input id="settings-start" value={startCommand} onChange={(e) => setStartCommand(e.target.value)} placeholder="npm start" className={`${inputClass} font-mono`} />
            </Field>
          </div>
          <div className="flex justify-end">
            <Button type="submit" disabled={saving || !name.trim()}>
              {saving ? "Saving…" : "Save settings"}
            </Button>
          </div>
        </form>
      </Card>

      <EnvironmentVariables projectId={project.id} />

      <Card className="border-red-200 p-6 dark:border-red-900">
        <h2 className="font-medium text-red-700 dark:text-red-400">Delete project</h2>
        <p className="mt-1 text-sm text-zinc-500">Removes the project and all of its deployments from EdgeDeploy. Your GitHub repository is not touched. Its AWS resources (ECS service, ECR repository, listener) are not deleted automatically: see the cleanup section of docs/aws-setup.md.</p>
        <Button variant="danger" className="mt-4" onClick={remove} disabled={deleting}>
          {deleting ? "Deleting…" : "Delete project"}
        </Button>
      </Card>
    </div>
  );
}
