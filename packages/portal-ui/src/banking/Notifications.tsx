import { useEffect, useState } from "react";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSession } from "../auth";
import { query, request } from "./api";
import { Confirm, Empty, Failure, Loading } from "./Common";
import { readEventStream } from "./eventStream";

type Notice = { notificationId: string; sequence: string; aggregateId: string; category: string; title: string; body: string; readAt: string | null; createdAt: string };
type Inbox = { items: Notice[]; nextCursor: string | null; unreadCount: number };
type Preferences = { inApp: boolean; email: boolean; payments: boolean; security: boolean; version: number };
type Email = { deliveryId: string; title: string; body: string; createdAt: string };
export function LiveNotifications({ subject }: { subject: string }) {
  const session = useSession(); const cache = useQueryClient();
  const [state, setState] = useState("Connecting live updates"); const [retry, setRetry] = useState(0);
  useEffect(() => {
    const abort = new AbortController(); let active = true; let attempt = 0; let timer: ReturnType<typeof setTimeout> | undefined;
    async function connect() {
      if (!active) return;
      try {
        const token = await session.token();
        const response = await fetch((import.meta.env.VITE_API_URL ?? "http://localhost:8280") + "/api/v1/notifications/stream", {
          headers: { Authorization: "Bearer " + token }, credentials: "omit", cache: "no-store", signal: abort.signal,
        });
        if (response.status === 401 || response.status === 403) { setState("Sign in again for live updates"); return; }
        if (!response.ok || !response.body || !response.headers.get("content-type")?.includes("text/event-stream")) throw new Error("Stream unavailable");
        if (!active) return;
        attempt = 0; setState("Live updates connected");
        await readEventStream(response.body.getReader(), () => { if (active) void cache.invalidateQueries({ queryKey: ["bank", subject] }); });
      } catch { if (active) setState("Live updates reconnecting. Recorded data remains available."); }
      if (active) timer = setTimeout(() => void connect(), Math.min(15000, 1000 * 2 ** Math.min(attempt++, 4)));
    }
    void connect();
    return () => { active = false; abort.abort(); if (timer) clearTimeout(timer); };
  }, [subject, session, cache, retry]);
  return <div className="bank-live" role="status"><span>{state}</span>{state.startsWith("Sign in") ?
    <button className="text-button" onClick={() => void session.login()}>Sign in again</button> :
    state.includes("reconnecting") && <button className="text-button" onClick={() => setRetry(value => value + 1)}>Reconnect live updates</button>}</div>;
}
export function Notifications({ subject }: { subject: string }) {
  const session = useSession(); const cache = useQueryClient(); const [unread, setUnread] = useState(false);
  const key = ["bank", subject, "notifications"];
  const inbox = useInfiniteQuery({ queryKey: [...key, unread], initialPageParam: undefined as string | undefined,
    queryFn: ({ signal, pageParam }) => request<Inbox>(session.token, "/notifications" + query({ before: pageParam, unread: String(unread) }), { signal }),
    getNextPageParam: last => last.nextCursor ?? undefined, retry: false });
  const preferences = useQuery({ queryKey: [...key, "preferences"], queryFn: ({ signal }) => request<Preferences>(session.token, "/notifications/preferences", { signal }), retry: false });
  const emails = useQuery({ queryKey: [...key, "emails"], queryFn: ({ signal }) => request<{ items: Email[] }>(session.token, "/notifications/emails", { signal }), retry: false });
  const mark = useMutation({ mutationFn: (id: string) => request(session.token, "/notifications/" + id + "/read", { method: "PUT", body: {} }),
    onSuccess: () => { void cache.invalidateQueries({ queryKey: key }); } });
  const items = inbox.data?.pages.flatMap(p => p.items) ?? [];
  return <><section className="surface bank-panel"><div className="section-heading"><div><h1>Notifications</h1><p>{inbox.data?.pages[0]?.unreadCount ?? 0} unread</p></div>
    <label className="bank-toggle"><input type="checkbox" checked={unread} onChange={e => setUnread(e.target.checked)} /> Unread only</label></div>
    <p>Payment messages follow recorded outcomes. Live updates refresh your account data from its source.</p>
    <Failure error={mark.error} />
    {inbox.isPending ? <Loading /> : inbox.error ? <Failure error={inbox.error} retry={() => void inbox.refetch()} /> : !items.length ?
      <Empty title="No notifications to show"><p>Confirmed payment and account security updates appear here when enabled.</p></Empty> :
      <ul className="bank-notifications">{items.map(item => <li key={item.notificationId} className={item.readAt ? "" : "unread"}>
        <div><small>{item.category.toLowerCase()} ? {new Date(item.createdAt).toLocaleString()}</small><h3>{item.title}</h3><p>{item.body}</p>
        <small className="bank-reference">Reference: {item.aggregateId}</small></div>
        {!item.readAt && <button className="text-button" disabled={mark.isPending} onClick={() => mark.mutate(item.notificationId)}>Mark as read</button>}</li>)}</ul>}
    {inbox.hasNextPage && <button className="button secondary" disabled={inbox.isFetchingNextPage} onClick={() => void inbox.fetchNextPage()}>Load more notifications</button>}
  </section>
  <section className="surface bank-panel"><h2>Notification preferences</h2>
    {preferences.isPending ? <Loading /> : preferences.error ? <Failure error={preferences.error} retry={() => void preferences.refetch()} /> :
      <PreferenceForm key={preferences.data.version} subject={subject} current={preferences.data} />}</section>
  <section className="surface bank-panel"><h2>Simulated email inbox</h2><p>Local simulation only. These messages are stored here; no external email is sent.</p>
    {emails.isPending ? <Loading /> : emails.error ? <Failure error={emails.error} retry={() => void emails.refetch()} /> : !emails.data.items.length ? <Empty title="No simulated emails" /> :
      <ul className="bank-notifications">{emails.data.items.map(email => <li key={email.deliveryId}><div><h3>{email.title}</h3><p>{email.body}</p><small>{new Date(email.createdAt).toLocaleString()}</small></div></li>)}</ul>}
  </section></>;
}
function PreferenceForm({ subject, current }: { subject: string; current: Preferences }) {
  const session = useSession(); const cache = useQueryClient(); const [draft, setDraft] = useState(current); const [review, setReview] = useState(false);
  const save = useMutation({ mutationFn: () => request<Preferences>(session.token, "/notifications/preferences", { method: "PUT", body: {
    inApp: draft.inApp, email: draft.email, payments: draft.payments, security: draft.security, expectedVersion: current.version,
  } }), onSuccess: () => { void cache.invalidateQueries({ queryKey: ["bank", subject, "notifications"] }); setReview(false); } });
  const fields = { inApp: "In-app notifications", email: "Simulated emails", payments: "Payment updates", security: "Account security updates" } as const;
  return <><Failure error={save.error} retry={() => { void cache.invalidateQueries({ queryKey: ["bank", subject, "notifications"] }); }} />
    {Object.entries(fields).map(([name, label]) => <label className="bank-toggle" key={name}><input type="checkbox" checked={draft[name as keyof typeof fields]}
      onChange={e => setDraft({ ...draft, [name]: e.target.checked })} />{label}</label>)}
    {!review ? <button className="button secondary" onClick={() => setReview(true)}>Review notification preferences</button> :
      <Confirm title="Confirm notification preferences" busy={save.isPending} confirm={() => save.mutate()} cancel={() => setReview(false)}>
        <p>Apply these channels and categories to future notifications. Existing messages remain available.</p></Confirm>}
  </>;
}
