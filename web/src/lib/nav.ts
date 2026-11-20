/*
 * Getting around: the three tabs, and which one each other page belongs to (it stays lit while
 * that page is open, and its back arrow leads there when the page was opened straight from a link).
 */

export type Tab = "devices" | "apps" | "you";

export const TABS: { id: Tab; label: string; to: string }[] = [
  { id: "devices", label: "Devices", to: "/" },
  { id: "apps", label: "Apps", to: "/apps" },
  { id: "you", label: "You", to: "/you" },
];

/** The tab [pathname] belongs to. */
export function tabOf(pathname: string): Tab | null {
  if (pathname === "/") return "devices";
  if (pathname.startsWith("/apps")) return "apps";
  if (pathname.startsWith("/you") || pathname.startsWith("/settings") || pathname.startsWith("/admin")) return "you";
  return null;
}

/** Where a page's back arrow leads when there's nowhere to go back to; null on the tabs themselves. */
export function backFor(pathname: string): { to: string; label: string } | null {
  if (TABS.some((t) => t.to === pathname)) return null;
  const tab = TABS.find((t) => t.id === tabOf(pathname));
  return tab ? { to: tab.to, label: tab.label } : { to: "/", label: "Devices" };
}
