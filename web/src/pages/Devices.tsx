import { useCallback, useEffect, useRef, useState } from "react";
import {
  BatteryCharging, BatteryFull, BatteryLow, BatteryMedium, BellOff, BellRing, ClipboardPaste, Laptop, Link2, MonitorSmartphone, MoreHorizontal,
  Pencil, Smartphone, Trash2,
} from "lucide-react";
import { api } from "../lib/api";
import { ago, percent, webAddress } from "../lib/format";
import { canSeal, seal } from "../lib/seal";
import type { Battery, Device, DeviceKind, Payload } from "../lib/types";
import { Button, Empty, ErrorNotice, errorToast, Field, Menu, Modal, PageHeader, Spinner, toast } from "../components/ui";

/** How often the list is fetched again while the tab is in view: the web page has no live line. */
const REFRESH_MS = 10_000;

/** How long the server keeps each kind for a device that's away, in seconds (docs/PROTOCOL.md). */
const KEEP = { link: 7 * 24 * 3600, clipboard: 600, ring: 300 } as const;

const KIND_NAMES: Record<DeviceKind, string> = { LINUX: "Computer", ANDROID: "Phone", OTHER: "Device" };

function KindIcon({ kind, size = 24 }: { kind: DeviceKind; size?: number }) {
  if (kind === "LINUX") return <Laptop size={size} aria-hidden="true" />;
  if (kind === "ANDROID") return <Smartphone size={size} aria-hidden="true" />;
  return <MonitorSmartphone size={size} aria-hidden="true" />;
}

/** The person's devices, fetched again every few seconds while the tab is in view. */
function useDevices() {
  const [devices, setDevices] = useState<Device[]>();
  const [error, setError] = useState<string | null>(null);
  const latest = useRef(0);
  const load = useCallback(async () => {
    const mine = ++latest.current;
    try {
      const list = await api.devices();
      // An older answer never replaces a newer one (a rename, then a slow refresh).
      if (mine === latest.current) { setDevices(list); setError(null); }
    } catch (e) {
      if (mine === latest.current) setError(e instanceof Error ? e.message : String(e));
    }
  }, []);
  useEffect(() => {
    void load();
    const timer = window.setInterval(() => { if (document.visibilityState === "visible") void load(); }, REFRESH_MS);
    const onVisible = () => { if (document.visibilityState === "visible") void load(); };
    document.addEventListener("visibilitychange", onVisible);
    return () => { window.clearInterval(timer); document.removeEventListener("visibilitychange", onVisible); };
  }, [load]);
  return { devices, error, reload: load, setDevices };
}

/** Seals [payload] for [device] here in the browser and sends it; says where it got to. */
async function send(device: Device, payload: Payload, keepSeconds: number, collapseKey?: string): Promise<boolean> {
  const sealed = await seal(JSON.stringify(payload), device.publicKey, device.id);
  const sent = await api.send({ to: { [device.id]: sealed }, ttlSeconds: keepSeconds, ...(collapseKey ? { collapseKey } : {}) });
  return sent.delivered.includes(device.id);
}

export function Devices() {
  const { devices, error, reload, setDevices } = useDevices();
  const [sendingLink, setSendingLink] = useState<Device | null>(null);
  const [sendingText, setSendingText] = useState<Device | null>(null);
  const [renaming, setRenaming] = useState<Device | null>(null);
  const [removing, setRemoving] = useState<Device | null>(null);
  /** Devices this page asked to ring, until it asks them to stop. */
  const [ringing, setRinging] = useState<Set<string>>(new Set());
  const sealing = canSeal();

  const ring = async (device: Device, on: boolean) => {
    try {
      const there = await send(device, { type: "ring", on }, KEEP.ring, "ring");
      setRinging((before) => { const next = new Set(before); if (on) next.add(device.id); else next.delete(device.id); return next; });
      if (on) toast(there ? `${device.name} is ringing` : `${device.name} is away. It'll ring if it's back in the next few minutes.`);
      else toast(`Asked ${device.name} to stop`);
    } catch (e) { errorToast(e); }
  };

  const sorted = [...(devices ?? [])].sort((a, b) => Number(b.online) - Number(a.online) || b.lastSeenAt - a.lastSeenAt);

  return (
    <div className="page devices-page">
      <PageHeader title="Devices" subtitle={devices && devices.length > 0 ? "Everything you're signed in on" : undefined} />
      {error && <ErrorNotice message={error} onRetry={() => void reload()} />}
      {!devices && !error && <div className="center-pad"><Spinner size={28} /></div>}
      {devices && devices.length === 0 && (
        <section className="panel-card">
          <Empty icon={<MonitorSmartphone />} title="No devices yet">
            <p>Sign in once on a device, and every app on it is signed in. Your devices then share notifications, the clipboard and links.</p>
            <ul className="steps">
              <li><Smartphone size={18} aria-hidden="true" /><span>On a phone, install the SuperAwesomeMagicalOrchestrator app and sign in.</span></li>
              <li><Laptop size={18} aria-hidden="true" /><span>On SuperAwesomeMagicalLinux, open Settings › Account.</span></li>
            </ul>
          </Empty>
        </section>
      )}
      {devices && devices.length > 0 && !sealing && (
        <div className="notice notice-quiet">
          Sending from this page needs a secure address (https), because what you send is sealed here in your browser. Your devices can still send to each other.
        </div>
      )}
      <div className="device-grid">
        {sorted.map((device) => {
          const can = (what: string) => sealing && device.capabilities.includes(what);
          const isRinging = ringing.has(device.id);
          return (
            <section key={device.id} className="panel-card device-card" aria-label={device.name}>
              <div className="device-head">
                <span className={`device-icon ${device.online ? "is-online" : ""}`}><KindIcon kind={device.kind} /></span>
                <div className="device-titles">
                  <h2 className="device-name">{device.name}</h2>
                  <p className="device-model">{device.model ? `${KIND_NAMES[device.kind]} · ${device.model}` : KIND_NAMES[device.kind]}</p>
                </div>
                <Menu label={`More for ${device.name}`} trigger={<MoreHorizontal size={20} />} items={[
                  { label: "Rename", icon: <Pencil size={18} />, onSelect: () => setRenaming(device) },
                  { label: "Remove", icon: <Trash2 size={18} />, danger: true, onSelect: () => setRemoving(device) },
                ]} />
              </div>
              <div className="device-status">
                <span className={`presence ${device.online ? "is-online" : ""}`}>
                  <span className="presence-dot" aria-hidden="true" />
                  {device.online ? "Connected now" : `Last seen ${ago(device.lastSeenAt)}`}
                </span>
                {device.battery && <BatteryLine battery={device.battery} />}
              </div>
              {(can("links") || can("clipboard") || can("ring")) && (
                <div className="device-actions">
                  {can("links") && <Button onClick={() => setSendingLink(device)}><Link2 size={18} /> Send a link</Button>}
                  {can("clipboard") && <Button onClick={() => setSendingText(device)}><ClipboardPaste size={18} /> Send text</Button>}
                  {can("ring") && (isRinging
                    ? <Button variant="primary" onClick={() => void ring(device, false)}><BellOff size={18} /> Stop ringing</Button>
                    : <Button onClick={() => void ring(device, true)}><BellRing size={18} /> Ring</Button>)}
                </div>
              )}
            </section>
          );
        })}
      </div>
      {sendingLink && <SendLink device={sendingLink} onClose={() => setSendingLink(null)} />}
      {sendingText && <SendText device={sendingText} onClose={() => setSendingText(null)} />}
      {renaming && (
        <Rename device={renaming} onClose={() => setRenaming(null)}
          onRenamed={(d) => { setDevices((devices ?? []).map((x) => (x.id === d.id ? { ...x, name: d.name } : x))); setRenaming(null); void reload(); }} />
      )}
      {removing && (
        <Modal title={`Remove ${removing.name}?`} onClose={() => setRemoving(null)} footer={<>
          <Button variant="ghost" onClick={() => setRemoving(null)}>Cancel</Button>
          <Button variant="danger" onClick={async () => {
            try {
              await api.removeDevice(removing.id);
              setDevices((devices ?? []).filter((x) => x.id !== removing.id));
              toast(`Removed ${removing.name}`);
              setRemoving(null);
              void reload();
            } catch (e) { errorToast(e); }
          }}>Remove</Button>
        </>}>
          <p>This signs {removing.name} out. Its apps stay signed in until you sign out of them there, and anything waiting to be sent to it is dropped. You can sign it in again any time.</p>
        </Modal>
      )}
    </div>
  );
}

function BatteryLine({ battery }: { battery: Battery }) {
  const Icon = battery.charging ? BatteryCharging : battery.level >= 70 ? BatteryFull : battery.level >= 25 ? BatteryMedium : BatteryLow;
  return (
    <span className={`battery ${!battery.charging && battery.level < 15 ? "is-low" : ""}`}>
      <Icon size={20} aria-hidden="true" />
      <span>{percent(battery.level)}{battery.charging ? " · charging" : ""}</span>
    </span>
  );
}

function SendLink({ device, onClose }: { device: Device; onClose: () => void }) {
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const save = async () => {
    const url = webAddress(text);
    if (!url) { setError("That doesn't look like a web address. Try something like example.com/page."); return; }
    setBusy(true);
    try {
      const there = await send(device, { type: "link", url, title: "" }, KEEP.link);
      toast(there ? `Sent to ${device.name}` : `${device.name} is away. It'll open the link when it's back.`);
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title={`Send a link to ${device.name}`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={text.trim() === ""} onClick={save}>Send</Button></>}>
      <form className="form-stack" onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <Field label="Link" help={error ? undefined : "It opens there. Sealed in your browser, so only that device can read it."}>
          <input autoFocus inputMode="url" autoCapitalize="none" autoCorrect="off" spellCheck={false} placeholder="https://" value={text}
            onChange={(e) => { setText(e.target.value); setError(null); }} />
        </Field>
        {error && <div className="notice notice-error" role="alert">{error}</div>}
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}

function SendText({ device, onClose }: { device: Device; onClose: () => void }) {
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const save = async () => {
    if (text === "") return;
    setBusy(true);
    try {
      const there = await send(device, { type: "clipboard", text }, KEEP.clipboard, "clipboard");
      toast(there ? `On ${device.name}'s clipboard` : `${device.name} is away. It gets this if it's back within ten minutes.`);
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title={`Send text to ${device.name}`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={text === ""} onClick={save}>Send</Button></>}>
      <div className="form-stack">
        <Field label="Text" help="It lands on that device's clipboard, ready to paste. Sealed in your browser, so only that device can read it.">
          <textarea autoFocus rows={4} maxLength={20000} value={text} onChange={(e) => setText(e.target.value)} />
        </Field>
      </div>
    </Modal>
  );
}

function Rename({ device, onClose, onRenamed }: { device: Device; onClose: () => void; onRenamed: (d: Device) => void }) {
  const [name, setName] = useState(device.name);
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try { onRenamed(await api.renameDevice(device.id, name.trim())); toast("Renamed"); }
    catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title={`Rename ${device.name}`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={name.trim() === "" || name.trim() === device.name} onClick={save}>Save</Button></>}>
      <form className="form-stack" onSubmit={(e) => { e.preventDefault(); if (name.trim() !== "" && name.trim() !== device.name) void save(); }}>
        <Field label="Name" help="What your other devices call it."><input autoFocus value={name} maxLength={60} onChange={(e) => setName(e.target.value)} /></Field>
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}
