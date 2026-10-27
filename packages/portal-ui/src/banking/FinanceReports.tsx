import { useQuery } from "@tanstack/react-query";
import { useSession } from "../auth";
import { ApiError, request } from "./api";
import { Empty, Failure, Loading } from "./Common";
import "./banking.css";

export function FinanceReports() {
  const session = useSession(); const allowed = session.roles.includes("FINANCE_OPERATOR"); const report = useQuery({ queryKey: ["finance", session.username, "reconciliation"], enabled: allowed, retry: false, queryFn: async ({ signal }) => { const result = await request<{ balanceDiscrepancies: unknown[]; invalidJournals: unknown[]; reconciled: boolean; observedAt: string; source: string }>(session.token, "/admin/reports/reconciliation", { signal }); if (!Array.isArray(result.balanceDiscrepancies) || !Array.isArray(result.invalidJournals)) throw new ApiError(502); return result; } });
  if (!allowed) return <Empty title="Finance operator access required" />;
  return <section className="bank-workspace"><section className="surface bank-panel"><div className="section-heading"><h2>Ledger reconciliation</h2><button className="button secondary" onClick={() => void report.refetch()}>Refresh reconciliation</button></div><p>Authoritative booked balances against signed postings and balanced journals, read in one repeatable database snapshot.</p><Failure error={report.error} retry={() => void report.refetch()} />{report.isPending ? <Loading /> : report.data && !report.error && <><strong role="status">{report.data.reconciled ? "Reconciliation confirmed" : "Discrepancies require investigation"}</strong><dl className="bank-facts"><dt>Balance discrepancies</dt><dd>{report.data.balanceDiscrepancies.length}</dd><dt>Invalid journals</dt><dd>{report.data.invalidJournals.length}</dd><dt>Observed at</dt><dd>{new Date(report.data.observedAt).toLocaleString()}</dd><dt>Source</dt><dd>{report.data.source}</dd></dl>{!report.data.reconciled && <p>Recorded discrepancies remain available through the scoped reconciliation API for investigation.</p>}</>}</section></section>;
}
