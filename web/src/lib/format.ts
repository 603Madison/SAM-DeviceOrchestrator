/* Dates and times, said the way people say them. No imports: npm test runs it as it is. */

/** How long ago [ms] was: "just now", "5 minutes ago", "3 days ago", "2 years ago". */
export function ago(ms: number | null | undefined, now: number = Date.now()): string {
  if (ms == null) return "";
  const s = Math.max(0, Math.round((now - ms) / 1000));
  const steps: [number, string][] = [[60, "second"], [60, "minute"], [24, "hour"], [7, "day"], [4.345, "week"], [12, "month"], [Infinity, "year"]];
  let value = s;
  for (const [size, unit] of steps) {
    if (value < size) {
      if (unit === "second") return "just now";
      const n = Math.floor(value);
      return `${n} ${unit}${n === 1 ? "" : "s"} ago`;
    }
    value /= size;
  }
  return "";
}

/** A date and time, for lists of sign-ins and the like. */
export function when(ms: number): string {
  return new Date(ms).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

/** A battery's level as people read it: a whole number from 0 to 100. */
export function percent(level: number): string {
  return `${Math.max(0, Math.min(100, Math.round(level)))}%`;
}

/**
 * What someone typed as a link to send, as an address a device can open: "example.com" gets
 * https://. Null when it isn't a web address (devices open http and https in the browser).
 */
export function webAddress(typed: string): string | null {
  const text = typed.trim();
  if (text === "" || /\s/.test(text)) return null;
  const withScheme = /^[a-z][a-z0-9+.-]*:/i.test(text) ? text : `https://${text}`;
  try {
    const url = new URL(withScheme);
    if (url.protocol !== "https:" && url.protocol !== "http:") return null;
    if (!url.hostname.includes(".") && url.hostname !== "localhost") return null;
    return url.href;
  } catch {
    return null;
  }
}
