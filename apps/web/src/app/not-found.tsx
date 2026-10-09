import Link from "next/link";

export default function NotFound() {
  return (
    <div className="py-24 text-center">
      <p className="text-sm font-medium text-zinc-500">404</p>
      <h1 className="mt-2 text-2xl font-semibold">Page not found</h1>
      <Link href="/projects" className="mt-6 inline-block text-sm underline underline-offset-4">
        Back to projects
      </Link>
    </div>
  );
}
