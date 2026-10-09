import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "EdgeDeploy",
  description: "Lightweight cloud deployments from GitHub to AWS ECS",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-screen">{children}</body>
    </html>
  );
}
