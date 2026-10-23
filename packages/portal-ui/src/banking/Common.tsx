import type { ReactNode } from "react";
import { useSession } from "../auth";
import { ApiError, type Account } from "./api";

export function Failure({ error, retry }: { error: Error | null; retry?: () => void }) {
  const session = useSession();
  if (!error) return null;
  return <div className="bank-message error" role="alert"><strong>{error.message}</strong>
    {error instanceof ApiError && error.correlation && <small>Support reference: {error.correlation}</small>}
    {error instanceof ApiError && error.status === 401 ? <button className="text-button" onClick={() => void session.login()}>Sign in again</button> :
      retry && <button className="text-button" onClick={retry}>Try again</button>}
  </div>;
}
export function Loading({ text = "Loading your latest data..." }: { text?: string }) { return <div className="bank-message" role="status"><span className="loading-dot" /> {text}</div>; }
export function Empty({ title, children }: { title: string; children?: ReactNode }) { return <div className="bank-empty"><h3>{title}</h3>{children}</div>; }
export function Badge({ status }: { status: string }) {
  const label: Record<string, string> = { GUARD_PENDING: "Restriction pending", PENDING_RISK_REVIEW: "Under review", PROVISIONING: "Opening", REVERSAL_PENDING: "Reversal pending" };
  return <span className={"bank-badge status-" + status.toLowerCase()}>{label[status] ?? status.replaceAll("_", " ").toLowerCase()}</span>;
}
export function Confirm({ title, children, confirm, cancel, busy = false }: { title: string; children: ReactNode; confirm: () => void; cancel: () => void; busy?: boolean }) {
  return <section className="bank-confirm" role="region" aria-label={title}><h3>{title}</h3>{children}
    <div className="bank-actions"><button className="button" disabled={busy} onClick={confirm}>{busy ? "Saving..." : "Confirm change"}</button>
      <button className="button secondary" disabled={busy} onClick={cancel}>Keep current details</button></div></section>;
}

export function AccountSelect({ accounts, selected, select, label = "Account" }: { accounts: Account[]; selected?: string; select: (id: string) => void; label?: string }) {
  return <label>{label}<select value={selected ?? ""} onChange={e => select(e.target.value)}>
    {!accounts.length && <option value="">No accounts available</option>}
    {accounts.map(account => <option key={account.accountId} value={account.accountId}>{account.currency} · {account.accountId.slice(-8)} · {account.status.toLowerCase()}</option>)}
  </select></label>;
}
