import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./style.css";

export const metadata: Metadata = {
  title: "Agent Studio",
  description: "Manage agent definitions, executions and approvals.",
};

export default function Layout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
