import { useEffect, useRef, useState } from "react";
import { useSession } from "../auth";

export function SignOut({ close }: { close: () => void }) {
  const session = useSession();
  const dialog = useRef<HTMLDialogElement>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => { dialog.current?.showModal(); }, []);
  return <dialog ref={dialog} className="bank-signout surface" aria-labelledby="signout-title" onCancel={event => { event.preventDefault(); if (!busy) close(); }}>
    <h2 id="signout-title">Sign out of FinCore?</h2>
    <p>Accepted transfers continue after you sign out. Check their latest status when you return.</p>
    <div className="bank-actions"><button className="button" disabled={busy} onClick={() => {
      setBusy(true); setFailed(false);
      void session.logout().catch(() => { setBusy(false); setFailed(true); });
    }}>{busy ? "Signing out..." : "Confirm sign out"}</button>
      <button className="button secondary" disabled={busy} onClick={close}>Stay signed in</button></div>
    {failed && <p role="alert">We could not complete sign-out. Try again.</p>}
  </dialog>;
}
