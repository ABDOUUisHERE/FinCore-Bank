import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import Keycloak from "keycloak-js";

export type Session = {
  status: "loading" | "anonymous" | "authenticated" | "expired" | "error";
  roles: string[];
  username?: string;
  login: () => Promise<void>;
  logout: () => Promise<void>;
  token: () => Promise<string>;
};
const SessionContext = createContext<Session | null>(null);
let keycloak: Keycloak | undefined;
let initialization: Promise<boolean> | undefined;

function client(clientId: string) {
  keycloak ??= new Keycloak({
    url: import.meta.env.VITE_OIDC_URL ?? "http://localhost:8180",
    realm: "fincore",
    clientId,
  });
  return keycloak;
}

export function AuthProvider({
  clientId,
  children,
}: {
  clientId: string;
  children: ReactNode;
}) {
  const [status, setStatus] = useState<Session["status"]>("loading");
  const [roles, setRoles] = useState<string[]>([]);
  const [username, setUsername] = useState<string>();
  const kc = client(clientId);
  useEffect(() => {
    let mounted = true;
    function update() {
      if (!mounted) return;
      setStatus(kc.authenticated ? "authenticated" : "anonymous");
      setRoles(kc.realmAccess?.roles ?? []);
      setUsername(kc.tokenParsed?.preferred_username);
    }
    kc.onAuthLogout = () => {
      if (mounted) setStatus("expired");
    };
    kc.onAuthRefreshError = () => {
      kc.clearToken();
      if (mounted) setStatus("expired");
    };
    kc.onTokenExpired = () => {
      void kc
        .updateToken(30)
        .then(update)
        .catch(() => {
          if (mounted) setStatus("expired");
        });
    };
    initialization ??= kc.init({
      onLoad: "check-sso",
      pkceMethod: "S256",
      checkLoginIframe: false,
      responseMode: "query",
    });
    void initialization.then(update).catch(() => {
      if (mounted) setStatus("error");
    });
    return () => {
      mounted = false;
    };
  }, [kc]);

  const value: Session = {
    status,
    roles,
    username,
    login: () => kc.login({ redirectUri: `${window.location.origin}/` }),
    logout: () => kc.logout({ redirectUri: `${window.location.origin}/` }),
    token: async () => {
      try {
        await kc.updateToken(30);
        if (!kc.token) throw new Error("Missing session");
        return kc.token;
      } catch {
        kc.clearToken();
        setStatus("expired");
        throw new Error("Your session has expired. Please sign in again.");
      }
    },
  };
  return (
    <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
  );
}

export function useSession() {
  const value = useContext(SessionContext);
  if (!value) throw new Error("AuthProvider is required");
  return value;
}
