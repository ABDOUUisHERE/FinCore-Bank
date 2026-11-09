import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useSession } from "../auth";
import { request } from "../banking/api";
import type { Grant, SupportKind } from "./SupportGrant";

export function useSupportObservation<T>(subject: string, grant: Grant, kind: SupportKind) {
  const session = useSession(), cache = useQueryClient(); const pending = useRef<AbortController | null>(null);
  const [result, setResult] = useState<T | null>(null), [error, setError] = useState<Error | null>(null), [busy, setBusy] = useState(false);
  const [expired, setExpired] = useState(() => !grant.expiresAt || Date.parse(grant.expiresAt) <= Date.now());
  useEffect(() => {
    const timer = grant.expiresAt ? setTimeout(() => { setExpired(true); setResult(null); pending.current?.abort(); }, Math.max(0, Math.min(2_147_483_647, Date.parse(grant.expiresAt) - Date.now()))) : undefined;
    return () => { clearTimeout(timer); pending.current?.abort(); };
  }, [grant.expiresAt]);
  async function inspect() {
    setResult(null); setError(null); setBusy(true); const controller = new AbortController(); pending.current = controller;
    try { setResult(await request<T>(session.token, "/admin/incidents/" + grant.incidentId + "/" + kind + "-status", { method: "POST", body: {}, signal: controller.signal })); }
    catch (failure) { setError(failure instanceof Error ? failure : new Error("Status is unavailable.")); }
    finally { setBusy(false); void cache.invalidateQueries({ queryKey: ["operations", subject, "history", grant.incidentId] }); }
  }
  return { result: expired ? null : result, error, busy, expired, inspect };
}
