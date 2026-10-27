import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Bell,
  ArrowDownLeft,
  ArrowRight,
  ArrowUpRight,
  Check,
  ChevronRight,
  CircleHelp,
  CreditCard,
  Fingerprint,
  Globe2,
  LayoutDashboard,
  LockKeyhole,
  LogOut,
  Menu,
  Moon,
  Plus,
  ShieldCheck,
  Sun,
  Wallet,
  Users,
  UserRound,
  X,
} from "lucide-react";
import { useSession } from "./auth";
import { getCurrentSubject } from "./api";
import { SignOut } from "./banking/SignOut";
import { Banking, bankPages } from "./banking/Banking";
import { FraudWorkspace } from "./fraud/FraudWorkspace";
import { Failure, Loading } from "./banking/Common";

export function Portal({ mode }: { mode: "customer" | "admin" }) {
  const session = useSession();
  const cache = useQueryClient();
  const [dark, setDark] = useState(false);
  const [signOut, setSignOut] = useState(false);
  const [menu, setMenu] = useState(false);
  const [page, setPage] = useState("Overview");
  const isCustomer = mode === "customer";
  const isAnalyst = !isCustomer && session.roles.includes("FRAUD_ANALYST");
  const permitted = isCustomer
    ? session.roles.includes("CUSTOMER")
    : session.roles.some((role) =>
        [
          "ADMIN",
          "SUPPORT_AGENT",
          "FRAUD_ANALYST",
          "FINANCE_OPERATOR",
        ].includes(role),
      );
  const authenticated = session.status === "authenticated";
  useEffect(() => {
    if (!authenticated) cache.clear();
  }, [authenticated, cache]);
  const profile = useQuery({
    queryKey: ["current-subject", session.username],
    queryFn: ({ signal }) => getCurrentSubject(session.token, signal),
    enabled: authenticated && permitted && isCustomer,
    retry: false,
    staleTime: 30_000,
  });
  const name = session.username ?? "there";
  function navigate(label: string) {
    setPage(label); setMenu(false); window.scrollTo({ top: 0, left: 0, behavior: "auto" });
  }

  return (
    <div className={`app ${dark ? "dark" : ""}`}>
      <aside className={`sidebar ${menu ? "mobile-open" : ""}`}>
        <a className="brand" href="/" aria-label="FinCore home">
          <span className="brand-mark">
            f<span>c</span>
          </span>
          fincore<span className="brand-dot">.</span>
        </a>
        <button
          className="mobile-close icon-button"
          onClick={() => setMenu(false)}
          aria-label="Close navigation"
        >
          <X size={20} />
        </button>
        <div className="workspace-label">
          {isCustomer ? "PERSONAL WORKSPACE" : "OPERATIONS WORKSPACE"}
        </div>
        <nav aria-label="Main navigation">
          {(isCustomer && authenticated && permitted ? [...bankPages, "Security"] : isAnalyst && authenticated ? ["Overview", "Fraud cases", "Fraud rules", "Security"] : ["Overview", "Security"]).map((label) => (
            <button
              key={label}
              className={`nav-item ${page === label ? "active" : ""}`}
              onClick={() => navigate(label)}
              aria-current={page === label ? "page" : undefined}
            >
              {label === "Overview" ? (
                <LayoutDashboard size={19} />
              ) : label === "Accounts" ? <Wallet size={19} /> : label === "Transfers" ? <ArrowUpRight size={19} /> :
                label === "History" ? <ArrowDownLeft size={19} /> : label === "Beneficiaries" ? <Users size={19} /> :
                label === "Notifications" ? <Bell size={19} /> : label === "Profile" ? <UserRound size={19} /> : <ShieldCheck size={19} /> }
              <span>{label}</span>
              {page === label && <span className="active-dot" />}
            </button>
          ))}
        </nav>
        <div className="workspace-label upcoming-label">{isCustomer && !authenticated ? "YOUR BANKING WORKSPACE" : "COMING TO FINCORE"}</div>
        {!(isCustomer && authenticated && permitted) && <>
        <div className="nav-item unavailable">
          <Wallet size={19} />
          <span>Accounts</span>
          <span className="soon">{isCustomer ? "Sign in" : "Soon"}</span>
        </div>
        <div className="nav-item unavailable">
          <ArrowUpRight size={19} />
          <span>Transfers</span>
          <span className="soon">{isCustomer ? "Sign in" : "Soon"}</span>
        </div>
        </>}
        <div className="nav-item unavailable">
          <CreditCard size={19} />
          <span>Virtual cards</span>
          <span className="soon">Soon</span>
        </div>
        <div className="sidebar-bottom">
          <div className="simulation-card">
            <span className="simulation-icon">
              <Globe2 size={18} />
            </span>
            <strong>A safe space to explore</strong>
            <p>FinCore uses simulated money. Your real finances stay yours.</p>
            <span className="simulation-label">SIMULATION ENVIRONMENT</span>
          </div>
          <a
            className="help-link"
            href="https://github.com/ABDOUUisHERE/FinCore-Bank#readme"
            target="_blank"
            rel="noreferrer"
          >
            <CircleHelp size={17} /> Project guide <ArrowUpRight size={15} />
          </a>
        </div>
      </aside>
      {menu && (
        <button
          className="menu-backdrop"
          aria-label="Dismiss navigation"
          onClick={() => setMenu(false)}
        />
      )}
      <div className="main-wrap">
        <header className="topbar">
          <div className="breadcrumb">
            <button
              className="mobile-toggle icon-button"
              aria-label="Open navigation"
              onClick={() => setMenu(true)}
            >
              <Menu size={21} />
            </button>
            <span>Workspace</span>
            <ChevronRight size={14} />
            <strong>{page}</strong>
          </div>
          <div className="topbar-right">
            <span className="environment">
              <span /> Sandbox
            </span>
            <button
              className="icon-button theme-toggle"
              onClick={() => setDark(!dark)}
              aria-label={dark ? "Use light theme" : "Use dark theme"}
            >
              {dark ? <Sun size={18} /> : <Moon size={18} />}
            </button>
            <span
              className="avatar"
              aria-label={authenticated ? name : "Guest workspace"}
            >
              {authenticated ? name.slice(0, 2).toUpperCase() : "FC"}
            </span>
          </div>
        </header>
        <main>
          <div className="page-title">
            <div>
              <div className="eyebrow">YOUR FINANCIAL SPACE, REIMAGINED</div>
              <h1>
                {page === "Security"
                  ? "Peace of mind, built in."
                  : authenticated
                    ? `Welcome back, ${name}.`
                    : "A fresh start for your finances."}
              </h1>
              <p>
                {isCustomer
                  ? "A little clarity. A lot of possibility. Welcome to your FinCore workspace."
                  : "A dedicated workspace for responsible platform operations."}
              </p>
            </div>
            <span className="phase-tag">
              <span /> {isCustomer && authenticated && permitted ? "Customer workspace" : isAnalyst && authenticated ? "Fraud analyst workspace" : "Foundation release"}
            </span>
          </div>
          {session.status === "loading" ? (
            <div className="status-panel" role="status">
              <span className="loading-dot" /> Opening your secure workspace…
            </div>
          ) : session.status === "error" ? (
            <div className="status-panel error" role="alert">
              <h2>We couldn’t reach sign-in</h2>
              <p>
                Check that the local identity service is running, then reload
                this page.
              </p>
              <button
                className="button"
                onClick={() => window.location.reload()}
              >
                Try again
              </button>
            </div>
          ) : session.status === "expired" ? (
            <div className="status-panel" role="alert">
              <h2>Your session has expired</h2>
              <p>Sign in again to reconnect to your workspace.</p>
              <button className="button" onClick={() => void session.login()}>
                Sign in again <ArrowRight size={17} />
              </button>
            </div>
          ) : authenticated && !permitted ? (
            <div className="status-panel" role="alert">
              <ShieldCheck size={28} />
              <h2>Access denied</h2>
              <p>Your identity is not assigned a role for this workspace.</p>
              <button className="button" onClick={() => setSignOut(true)}>
                Sign out
              </button>
            </div>
          ) : page === "Security" ? (
            <section className="security-view">
              <div className="section-heading">
                <h2>Security at every step</h2>
                <span className="small-label">Built on OpenID Connect</span>
              </div>
              <div className="security-grid">
                <article className="surface security-item">
                  <Fingerprint />
                  <h3>Your identity, protected</h3>
                  <p>
                    Sign-in is handled by Keycloak. Passwords never pass through
                    the FinCore portals.
                  </p>
                </article>
                <article className="surface security-item">
                  <LockKeyhole />
                  <h3>A session that stays private</h3>
                  <p>
                    Session tokens stay in memory and are cleared when you sign
                    out.
                  </p>
                </article>
                <article className="surface security-item">
                  <ShieldCheck />
                  <h3>Access with a purpose</h3>
                  <p>
                    Each service checks your identity and permissions
                    independently.
                  </p>
                </article>
              </div>
              {authenticated && (
                <button
                  className="button secondary"
                  onClick={() => setSignOut(true)}
                >
                  <LogOut size={17} /> Sign out of this session
                </button>
              )}
            </section>
          ) : isAnalyst && authenticated ? <FraudWorkspace section={page} /> : isCustomer && authenticated && permitted ? (
            profile.isPending ? <Loading text="Verifying your customer identity..." /> : profile.error ?
              <Failure error={profile.error} retry={() => void profile.refetch()} /> : profile.data ?
              <Banking key={profile.data.subject} subject={profile.data.subject} section={page} navigate={navigate} /> : null
          ) : (
            <>
              <section className="hero">
                <div className="hero-copy">
                  <span className="hero-kicker">
                    <span /> A MORE THOUGHTFUL WAY TO BANK
                  </span>
                  <h2>
                    Your money.
                    <br />
                    Your pace.
                  </h2>
                  <p>
                    A clear view of what matters, with security at the heart of
                    every step. Your journey starts here.
                  </p>
                  {authenticated ? (
                    <a className="button hero-button" href="#workspace">
                      Explore your workspace <ArrowRight size={18} />
                    </a>
                  ) : (
                    <button
                      className="button hero-button"
                      onClick={() => void session.login()}
                    >
                      Sign in securely <ArrowRight size={18} />
                    </button>
                  )}
                  <div className="hero-assurance">
                    <ShieldCheck size={15} />{" "}
                    {authenticated
                      ? "You’re signed in with a protected session"
                      : "Secure sign-in · No real money involved"}
                  </div>
                </div>
                <div className="hero-art" aria-hidden="true">
                  <div className="orbit orbit-one" />
                  <div className="orbit orbit-two" />
                  <div className="orbit orbit-three" />
                  <div className="art-spark spark-one">+</div>
                  <div className="art-spark spark-two">+</div>
                  <div className="visual-card back-card" />
                  <div className="visual-card front-card">
                    <span className="card-wordmark">fincore.</span>
                    <span className="card-chip">
                      <span />
                      <span />
                    </span>
                    <span className="card-wave">)))</span>
                    <div className="card-bottom">
                      <span>MADE FOR POSSIBILITY</span>
                      <Globe2 size={26} />
                    </div>
                  </div>
                  <div className="floating-badge">
                    <span>
                      <Check size={16} />
                    </span>{" "}
                    Designed around you
                  </div>
                </div>
              </section>
              <div className="trust-strip">
                <span>
                  <LockKeyhole size={15} /> Secure by design
                </span>
                <span>
                  <Globe2 size={15} /> Simulated money only
                </span>
                <span>
                  <ShieldCheck size={15} /> Your access, your control
                </span>
              </div>
              <section className="workspace-section" id="workspace">
                <div className="section-heading">
                  <h2>{isCustomer ? "Your workspace" : "Operations access"}</h2>
                  <span className="small-label">One step at a time</span>
                </div>
                <div className="workspace-grid">
                  <article className="surface identity-card">
                    <div className="card-heading">
                      <span className="tile-icon mint">
                        <Fingerprint size={22} />
                      </span>
                      <span
                        className={`connection-pill ${authenticated ? "connected" : ""}`}
                      >
                        <span />
                        {authenticated ? "Signed in" : "Not connected"}
                      </span>
                    </div>
                    <h3>
                      {authenticated
                        ? "Your secure identity"
                        : "Make yourself at home"}
                    </h3>
                    <p>
                      {authenticated
                        ? "Your session connects you to the workspace you’re authorized to use."
                        : "Sign in to connect your identity and see your personal workspace."}
                    </p>
                    {authenticated && isCustomer ? (
                      profile.isPending ? (
                        <div className="connection-detail" role="status">
                          Connecting to your workspace…
                        </div>
                      ) : profile.isError ? (
                        <div className="connection-detail" role="alert">
                          <p>{profile.error.message}</p>
                          <button
                            className="text-button"
                            onClick={() => void profile.refetch()}
                          >
                            Reconnect <ArrowRight size={15} />
                          </button>
                        </div>
                      ) : (
                        <div className="connection-detail">
                          <span className="verified-line">
                            <Check size={15} /> Identity verified by the
                            customer service
                          </span>
                          <dl>
                            <dt>Subject</dt>
                            <dd className="subject-id">
                              {profile.data.subject}
                            </dd>
                            <dt>Roles</dt>
                            <dd>{profile.data.roles.join(", ")}</dd>
                          </dl>
                        </div>
                      )
                    ) : authenticated ? (
                      <div className="connection-detail">
                        <span className="verified-line">
                          <Check size={15} /> Authorized staff session
                        </span>
                        <dl>
                          <dt>Assigned roles</dt>
                          <dd>
                            {session.roles
                              .filter((role) =>
                                [
                                  "ADMIN",
                                  "SUPPORT_AGENT",
                                  "FRAUD_ANALYST",
                                  "FINANCE_OPERATOR",
                                ].includes(role),
                              )
                              .join(", ")}
                          </dd>
                        </dl>
                        <p>
                          Operational data will appear as its services are
                          introduced.
                        </p>
                      </div>
                    ) : (
                      <button
                        className="text-button"
                        onClick={() => void session.login()}
                      >
                        Connect your workspace <ArrowRight size={16} />
                      </button>
                    )}
                  </article>
                  <article className="surface next-card">
                    <div className="card-heading">
                      <span className="tile-icon lavender">
                        <Wallet size={22} />
                      </span>
                      <span className="coming-pill">Coming next</span>
                    </div>
                    <h3>{isCustomer ? "Your next chapter starts here" : "A foundation for the next phase"}</h3>
                    <p>{isCustomer ? "Open accounts, send simulated money and follow every confirmed transaction in your personal workspace." :
                      "Operations tools will be added gradually, with verified permissions and audit trails."}</p>
                    <div className="feature-row">
                      <span>
                        <Wallet size={16} /> Accounts
                      </span>
                      <span>
                        <ArrowUpRight size={16} /> Transfers
                      </span>
                      <span>
                        <CreditCard size={16} /> Cards
                      </span>
                    </div>
                    {isCustomer && <small>Virtual cards arrive in a future release.</small>}
                    <div className="empty-account">
                      <span className="dashed-icon">
                        <Plus size={20} />
                      </span>
                      <div>
                        <strong>Your first account starts here</strong>
                        <span>{isCustomer ? "Sign in to open a simulated account" : "Available in a future release"}</span>
                      </div>
                    </div>
                  </article>
                </div>
              </section>
              <section className="note-panel">
                <span className="note-icon">
                  <ArrowDownLeft size={22} />
                </span>
                <div>
                  <h3>Built with care. Growing with purpose.</h3>
                  <p>
                    {isCustomer ? "Your personal workspace connects accounts, transfers and confirmed ledger activity, using simulated funds." :
                      "This foundation release connects identity and access. Operations features will arrive gradually."}
                  </p>
                </div>
                <span className="note-version">FINCORE / 0.1</span>
              </section>
            </>
          )}
          <footer>
            <span>
              © {new Date().getFullYear()} FinCore. A banking simulation.
            </span>
            <span>
              Made for learning. Built for clarity.
              <span className="footer-dot" />
            </span>
            {authenticated && (
              <button
                className="text-button"
                onClick={() => setSignOut(true)}
              >
                Sign out <LogOut size={14} />
              </button>
            )}
          </footer>
        </main>
      </div>
      {signOut && <SignOut close={() => setSignOut(false)} />}
    </div>
  );
}
