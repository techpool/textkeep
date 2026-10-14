import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  BrowserRouter,
  useLocation,
  Link as RouterLink,
} from "react-router-dom";
import {
  AppBar,
  Toolbar,
  Box,
  Container,
  Typography,
  Button,
  Chip,
  Card,
  CardContent,
  Stack,
  Grid,
  Divider,
  TextField,
  Alert,
  Checkbox,
  FormControlLabel,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  CssBaseline,
  ThemeProvider,
  createTheme,
  Tabs,
  Tab,
  MenuItem,
  LinearProgress,
  Link,
  IconButton,
} from "@mui/material";
import {
  SmsOutlined,
  ContentCopyRounded,
  ArrowForwardRounded,
  Android,
  GitHub,
  ShieldOutlined,
  CloudSyncOutlined,
  CheckCircleOutlined,
  Logout,
  Search,
  DevicesOutlined,
  LockOutlined,
  MoreHoriz,
} from "@mui/icons-material";
import { initializeApp, getApps } from "@firebase/app";
import {
  getAuth,
  setPersistence,
  inMemoryPersistence,
  GoogleAuthProvider,
  signInWithPopup,
  signOut,
} from "@firebase/auth";
import { ApiError, api, type Message, type PublicConfig } from "./api";
import "./styles.css";
const theme = createTheme({
  palette: {
    primary: { main: "#185e50", light: "#e4f2e8" },
    secondary: { main: "#9d5d32" },
    background: { default: "#fafbf7", paper: "#ffffff" },
    text: { primary: "#16352f", secondary: "#62736a" },
  },
  typography: {
    fontFamily: "Inter, ui-sans-serif, system-ui, sans-serif",
    h1: { fontWeight: 650, letterSpacing: "-.055em", lineHeight: 1.06 },
    h2: { fontWeight: 650, letterSpacing: "-.04em" },
    h3: { fontWeight: 600, letterSpacing: "-.025em" },
    button: { textTransform: "none", fontWeight: 600 },
  },
  shape: { borderRadius: 18 },
  components: {
    MuiButton: {
      styleOverrides: {
        root: { borderRadius: 12, padding: "12px 22px", boxShadow: "none" },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: { boxShadow: "none", border: "1px solid #dee7dd" },
      },
    },
    MuiTextField: { defaultProps: { size: "small" } },
    MuiChip: { styleOverrides: { root: { fontWeight: 550 } } },
  },
});
const repo = "https://github.com/techpool/textkeep";
const boxes: Record<number, string> = {
  1: "Inbox",
  2: "Sent",
  3: "Draft",
  4: "Outbox",
  5: "Failed",
  6: "Queued",
};
const time = (n: number) => new Date(n).toLocaleString();
function Header() {
  return (
    <AppBar position="static" color="transparent" elevation={0}>
      <Container sx={{ maxWidth: "lg" }}>
        <Toolbar disableGutters sx={{ gap: 2, minHeight: 88 }}>
          <Link
            component={RouterLink}
            to="/"
            underline="none"
            color="text.primary"
            sx={{
              display: "flex",
              gap: 1.2,
              alignItems: "center",
              fontSize: 24,
              fontWeight: 750,
            }}
          >
            <Box className="logo">
              <SmsOutlined />
            </Box>
            TextKeep<span className="wordmark-dot">.</span>
          </Link>
          <Box sx={{ flex: 1 }} />
          <Link
            component={RouterLink}
            to="/self-host"
            color="text.primary"
            underline="none"
            sx={{ display: { xs: "none", sm: "block" } }}
          >
            Self-host
          </Link>
          <Button
            href={repo}
            color="inherit"
            startIcon={<GitHub />}
            sx={{ display: { xs: "none", md: "flex" } }}
          >
            Source
          </Button>
          <Button component={RouterLink} to="/app" variant="outlined">
            Open inbox <ArrowForwardRounded sx={{ ml: 1, fontSize: 18 }} />
          </Button>
        </Toolbar>
      </Container>
    </AppBar>
  );
}
function Footer() {
  return (
    <Container sx={{ maxWidth: "lg" }}>
      <Divider sx={{ mt: 8, mb: 4 }} />
      <Stack
        direction={{ xs: "column", sm: "row" }}
        sx={{ ...{ pb: 5 }, justifyContent: "space-between", gap: 2 }}
      >
        <Typography variant="body2">
          TextKeep · Open source. Made for Android.
        </Typography>
        <Stack direction="row" sx={{ gap: 3 }}>
          <Link component={RouterLink} to="/privacy" color="text.secondary">
            Privacy
          </Link>
          <Link component={RouterLink} to="/self-host" color="text.secondary">
            Self-hosting
          </Link>
          <Link href={repo} color="text.secondary">
            GitHub
          </Link>
        </Stack>
      </Stack>
    </Container>
  );
}
function Landing({ config }: { config: PublicConfig | null }) {
  return (
    <>
      <Header />
      <Container sx={{ maxWidth: "lg" }}>
        <Box className="hero">
          <Box>
            <Chip
              icon={<Android />}
              label="ANDROID, WITH A LITTLE MORE PEACE"
              variant="outlined"
              sx={{
                mb: 3,
                fontSize: 11,
                letterSpacing: ".09em",
                borderColor: "#c8d8c8",
              }}
            />
            <Typography
              component="h1"
              variant="h1"
              sx={{ fontSize: { xs: 48, sm: 64, md: 76 }, maxWidth: 630 }}
            >
              Your messages.
              <br />A clearer <span className="accent">head.</span>
            </Typography>
            <Typography
              sx={{
                mt: 3,
                fontSize: 19,
                lineHeight: 1.7,
                maxWidth: 470,
                color: "text.secondary",
              }}
            >
              Copy verification codes in a tap. Keep the rest of your SMS easy
              to find — on your phone and on a server you control.
            </Typography>
            <Stack direction="row" sx={{ ...{ mt: 4 }, gap: 1.5 }}>
              {config?.release ? (
                <Button
                  variant="contained"
                  href="/downloads/textkeep.apk"
                  startIcon={<Android />}
                >
                  Download for Android
                </Button>
              ) : (
                <Button variant="contained" href="#how-it-works">
                  Explore TextKeep <ArrowForwardRounded sx={{ ml: 1 }} />
                </Button>
              )}
              <Button href={repo} variant="outlined" startIcon={<GitHub />}>
                View source
              </Button>
            </Stack>
            <Typography variant="body2" sx={{ mt: 2, color: "text.secondary" }}>
              Android 10+ ·{" "}
              {config?.release
                ? "Signed experimental APK"
                : "Experimental release in development"}{" "}
              · No Play Store required
            </Typography>
            <Box className="small-proof">
              <ShieldOutlined sx={{ fontSize: "small" }} /> Local OTP
              recognition. No internet needed.
            </Box>
          </Box>
          <Box className="phone-scene">
            <Box className="scene-label">LESS HUNTING. MORE LIVING.</Box>
            <Box className="phone">
              <Box className="phone-status">
                <span>9:41</span>
                <span>••• ▰</span>
              </Box>
              <Box className="phone-top">
                <SmsOutlined />
                <strong>TextKeep</strong>
                <MoreHoriz />
              </Box>
              <Box className="phone-search">
                <Search sx={{ fontSize: "small" }} /> Search your messages
              </Box>
              <Stack direction="row" sx={{ ...{ my: 2 }, gap: 1 }}>
                <Chip
                  label="All messages"
                  size="small"
                  sx={{ background: "#d8ebde" }}
                />
                <Chip label="OTP" size="small" variant="outlined" />
              </Stack>
              <div className="mock-row">
                <div className="sender-icon">T</div>
                <div>
                  <strong>Travel updates</strong>
                  <p>Your ticket is confirmed. Have a good trip!</p>
                </div>
                <small>9:35</small>
              </div>
              <div className="mock-row">
                <div className="sender-icon muted">D</div>
                <div>
                  <strong>Delivery</strong>
                  <p>Your parcel is on its way.</p>
                </div>
                <small>8:20</small>
              </div>
              <div className="mock-date">TODAY</div>
              <Box className="otp-card">
                <Stack direction="row" sx={{ justifyContent: "space-between" }}>
                  <Typography sx={{ fontSize: 13, fontWeight: 650 }}>
                    VM-DEMO
                  </Typography>
                  <Typography color="text.secondary" sx={{ fontSize: 11 }}>
                    just now
                  </Typography>
                </Stack>
                <Typography sx={{ ...{ my: 1 }, fontSize: 13 }}>
                  Your verification code
                </Typography>
                <Typography
                  sx={{ fontSize: 33, fontWeight: 650, letterSpacing: ".17em" }}
                >
                  482 913
                </Typography>
                <Button
                  fullWidth
                  startIcon={<ContentCopyRounded />}
                  sx={{
                    mt: 1.5,
                    background: "#d9ebdf",
                    color: "#185e50",
                    pointerEvents: "none",
                  }}
                >
                  Copy code
                </Button>
              </Box>
              <Typography
                sx={{
                  ...{ mt: 2, color: "text.secondary", textAlign: "center" },
                  fontSize: 10,
                }}
              >
                Synthetic preview · no real messages
              </Typography>
            </Box>
            <Box className="offline-pill">
              <CheckCircleOutlined sx={{ color: "#185e50" }} />
              <span>
                <strong>Works offline</strong>
                <br />
                <small>Codes stay useful when the network isn’t.</small>
              </span>
            </Box>
          </Box>
        </Box>
        <Divider />
        <Stack
          direction={{ xs: "column", sm: "row" }}
          spacing={3}
          sx={{ ...{ py: 4 }, justifyContent: "space-between" }}
        >
          {[
            "Native Android messaging",
            "Google sign-in for sync",
            "Your own PostgreSQL server",
          ].map((s) => (
            <Typography
              key={s}
              variant="body2"
              sx={{ display: "flex", gap: 1, alignItems: "center" }}
            >
              <CheckCircleOutlined color="primary" sx={{ fontSize: "small" }} />
              {s}
            </Typography>
          ))}
        </Stack>
        <Box id="how-it-works" sx={{ pt: 7, pb: 3 }}>
          <Typography variant="overline" color="secondary">
            SMALL DETAILS. A BETTER INBOX.
          </Typography>
          <Typography
            variant="h2"
            sx={{ fontSize: { xs: 34, sm: 44 }, mt: 1, mb: 4 }}
          >
            Useful in the moment.
            <br />
            Findable when you need it.
          </Typography>
          <Grid container spacing={2}>
            {[
              [
                ContentCopyRounded,
                "Copy, then carry on.",
                "Recognizes verification codes locally and offers an explicit copy action. Historical imports never trigger new-code alerts.",
              ],
              [
                CloudSyncOutlined,
                "An inbox beyond your phone.",
                "With your consent, full SMS syncs to your self-hosted server. Search sender, date, device and message type from the web.",
              ],
              [
                LockOutlined,
                "AI access, on your terms.",
                "Read-only MCP tools have separate consent and revocable access. Sensitive messages are excluded by default.",
              ],
            ].map(([Icon, title, text]) => {
              const I = Icon as typeof SmsOutlined;
              return (
                <Grid size={{ xs: 12, md: 4 }} key={String(title)}>
                  <Card sx={{ height: "100%", background: "#f0f5ee" }}>
                    <CardContent sx={{ p: 3.5 }}>
                      <I sx={{ fontSize: 32, mb: 3, color: "primary.main" }} />
                      <Typography variant="h6" sx={{ mb: 1 }}>
                        {String(title)}
                      </Typography>
                      <Typography
                        color="text.secondary"
                        sx={{ lineHeight: 1.7 }}
                      >
                        {String(text)}
                      </Typography>
                    </CardContent>
                  </Card>
                </Grid>
              );
            })}
          </Grid>
        </Box>
        <Box className="architecture">
          <Box>
            <Typography variant="overline" color="secondary">
              OPEN SOURCE, SELF-HOSTED
            </Typography>
            <Typography
              variant="h3"
              sx={{ ...{ my: 2 }, fontSize: { xs: 30, sm: 40 } }}
            >
              Your server.
              <br />
              Your rules.
            </Typography>
            <Typography
              color="text.secondary"
              sx={{ lineHeight: 1.75, maxWidth: 420 }}
            >
              Firebase handles Google identity. Your selected server stores SMS
              in PostgreSQL with application-layer encryption. You can inspect
              and run every part.
            </Typography>
            <Button
              component={RouterLink}
              to="/self-host"
              sx={{ pl: 0, mt: 2 }}
            >
              Read the self-hosting guide <ArrowForwardRounded sx={{ ml: 1 }} />
            </Button>
          </Box>
          <Box className="architecture-flow">
            <div>
              <Android />
              <strong>Android</strong>
              <small>Local messages + OTPs</small>
            </div>
            <span>↓ consented sync</span>
            <div>
              <CloudSyncOutlined />
              <strong>Your server</strong>
              <small>PostgreSQL · dashboard · API</small>
            </div>
            <span>↓ separate authorization</span>
            <div>
              <LockOutlined />
              <strong>Read-only MCP</strong>
              <small>Scoped and revocable</small>
            </div>
          </Box>
        </Box>
        <Alert severity="info" sx={{ mt: 3 }}>
          TextKeep is a provisional name and an experimental default SMS/MMS
          app. Carrier and OEM reliability require device testing. SMS sync
          excludes MMS attachments, RCS, WhatsApp and other private messengers.
        </Alert>
        {config?.release && (
          <Card sx={{ mt: 3 }}>
            <CardContent>
              <Typography variant="h6">
                Try the experimental Android release
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Version {config.release.version}. Local messaging is available
                to installers.{" "}
                {config.registration === "google"
                  ? "Verified Google users can enable sync to their own private archive."
                  : "Hosted sync requires an invited Google account."}{" "}
                Test SMS and MMS before changing your daily phone’s default app.
              </Typography>
              <Typography className="digest" variant="caption">
                APK SHA-256: {config.release.sha256}
                <br />
                Signing certificate SHA-256: {config.release.certificateSha256}
              </Typography>
            </CardContent>
          </Card>
        )}
      </Container>
      <Footer />
    </>
  );
}
function Info({ kind }: { kind: string }) {
  return (
    <>
      <Header />
      <Container sx={{ ...{ py: 7 }, maxWidth: "md" }}>
        <Typography variant="h2" sx={{ mb: 4 }}>
          {kind === "privacy"
            ? "A clear view of your data."
            : "Make it your own."}
        </Typography>
        {(kind === "privacy"
          ? [
              [
                "Local first",
                "Receiving SMS, sending replies and identifying OTPs do not require Google sign-in, Firebase or an internet connection. Clipboard content is copied only when you tap. OTP notifications hide their public lock-screen content by default, subject to OEM behavior.",
              ],
              [
                "When you enable sync",
                "Full SMS bodies and senders, including OTPs, banking messages and every exposed SMS box type, are sent over HTTPS to the server you select. Google/Firebase supplies identity and does not store your SMS archive. MMS attachments and RCS are outside cloud sync.",
              ],
              [
                "Storage protection",
                "The backend encrypts message content and senders at the application layer. It can decrypt authorized results. This is not end-to-end encryption or zero knowledge; metadata remains queryable and a compromised application server can expose content.",
              ],
              [
                "Connecting an AI client",
                "MCP access is separately authorized and read-only. OTP, recovery and security messages are excluded by default and other plausible sensitive values are conservatively filtered. Detection is imperfect. An authorized client may disclose returned text to its AI provider.",
              ],
              [
                "Deletion and limitations",
                "Deleting locally does not purge the server archive. Dashboard deletion requires confirmation and recent login, and adds a suppression record to stop ordinary re-upload. Suppression identity may not survive reinstall or a provider reset. No separate backup or recovery system is supplied. Force-stop, revoked permissions and OEM restrictions can delay work.",
              ],
            ]
          : [
              [
                "One app deployment. Your existing database.",
                "Build the monorepo with the pinned Node and Gradle toolchains. The production Compose file deploys the API, web dashboard and OAuth/MCP service together, using an existing PostgreSQL instance. It creates no production database server.",
              ],
              [
                "Set up your own Firebase identity",
                "Create a Firebase project, enable Google authentication, register web and Android applications, and add the actual debug/release signing fingerprints. Build your APK with that project’s public client configuration. Configure a nonempty verified-Google account allowlist on your server.",
              ],
              [
                "Deploy on Docker or Coolify",
                "Create isolated textkeep schema and limited runtime credentials. Run the forward migration using a separate migration connection. Supply encryption keys, Firebase service credentials and OAuth signing material through your secret store. Attach the private database network and set your HTTPS origin.",
              ],
              [
                "Connect a read-only client",
                "Remote clients connect to /mcp using authorization-code flow with PKCE and explicit web consent. A local stdio adapter can use a revocable personal token from access settings. The README covers scopes, build steps, permissions and diagnostics.",
              ],
            ]
        ).map(([title, text]) => (
          <Box key={title} sx={{ mb: 4 }}>
            <Typography variant="h5" sx={{ mb: 1 }}>
              {title}
            </Typography>
            <Typography color="text.secondary" sx={{ lineHeight: 1.8 }}>
              {text}
            </Typography>
          </Box>
        ))}
        <Button href={repo} variant="contained" startIcon={<GitHub />}>
          Full documentation and source
        </Button>
      </Container>
      <Footer />
    </>
  );
}
async function googleLogin(config: PublicConfig) {
  const auth = getAuth(getApps()[0] || initializeApp(config.firebase));
  await setPersistence(auth, inMemoryPersistence);
  try {
    const result = await signInWithPopup(auth, new GoogleAuthProvider());
    await api("/api/v1/auth/session", {
      method: "POST",
      body: JSON.stringify({ idToken: await result.user.getIdToken(true) }),
    });
  } finally {
    await signOut(auth);
  }
}
function Dashboard({ config }: { config: PublicConfig | null }) {
  const location = useLocation();
  const section = location.pathname.split("/")[2] || "messages";
  const [user, setUser] = useState<any>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(true),
    [data, setData] = useState<{
      messages: Message[];
      nextCursor: string | null;
      incompleteScan: boolean;
    } | null>(null),
    [devices, setDevices] = useState<any[]>([]),
    [status, setStatus] = useState<any>(null),
    [history, setHistory] = useState<any>(null),
    [conversations, setConversations] = useState<any>(null),
    [conversation, setConversation] = useState<any>(null),
    [access, setAccess] = useState<any>(null),
    [query, setQuery] = useState(""),
    [sender, setSender] = useState(""),
    [type, setType] = useState(""),
    [device, setDevice] = useState(""),
    [from, setFrom] = useState(""),
    [to, setTo] = useState(""),
    [detail, setDetail] = useState<Message | null>(null),
    [purge, setPurge] = useState<Message | null>(null),
    [confirm, setConfirm] = useState(""),
    [token, setToken] = useState(""),
    [sensitive, setSensitive] = useState(false),
    [tokenSensitive, setTokenSensitive] = useState(false),
    [interaction, setInteraction] = useState<any>(null);
  const interactionId = new URLSearchParams(location.search).get("interaction");
  const clearPrivateState = () => {
    setUser(null);
    setData(null);
    setDevices([]);
    setStatus(null);
    setHistory(null);
    setConversations(null);
    setConversation(null);
    setAccess(null);
    setDetail(null);
    setPurge(null);
    setToken("");
    setSensitive(false);
    setTokenSensitive(false);
    setInteraction(null);
  };
  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    try {
      await fn();
      setError("");
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) clearPrivateState();
      setError(e instanceof Error ? e.message : "Request failed");
    } finally {
      setBusy(false);
    }
  };
  const load = async () => {
    let me;
    try {
      me = await api("/api/v1/me");
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        clearPrivateState();
        return;
      }
      throw e;
    }
    setUser(me);
    setDevices(await api("/api/v1/devices"));
    setStatus(await api("/api/v1/sync/status"));
    if (section === "access") {
      setAccess(await api("/api/v1/access"));
      if (interactionId) setInteraction(await api("/app/access/interaction"));
    } else if (section === "devices") {
      setHistory(await api("/api/v1/sync/history"));
    } else if (section === "conversations") {
      const threads = await api("/api/v1/threads");
      setConversations(threads);
      const id = location.pathname.split("/")[3];
      setConversation(
        id
          ? {
              id,
              address:
                threads.threads.find((t: any) => t.id === id)?.address ||
                "Conversation",
              ...(await api(
                `/api/v1/threads/${encodeURIComponent(id)}/messages`,
              )),
            }
          : null,
      );
    } else {
      await search();
      const messageId = location.pathname.split("/")[3];
      if (messageId)
        setDetail(
          await api<Message>(
            "/api/v1/messages/" + encodeURIComponent(messageId),
          ),
        );
    }
  };
  const search = async (cursor?: string) => {
    const q = new URLSearchParams();
    for (const [key, value] of Object.entries({
      query,
      sender,
      type,
      device,
      from: from ? String(new Date(from).getTime()) : "",
      to: to ? String(new Date(to).getTime() + 86400000 - 1) : "",
      cursor: cursor || "",
    }))
      if (value) q.set(key, value);
    setData(await api("/api/v1/messages?" + q));
  };
  useEffect(() => {
    void run(load);
  }, [location.pathname, interactionId]);
  if (!user)
    return (
      <>
        <Header />
        <Container sx={{ ...{ py: 9 }, maxWidth: "sm" }}>
          <Card>
            <CardContent sx={{ p: 4 }}>
              <LockOutlined color="primary" sx={{ fontSize: 40 }} />
              <Typography variant="h4" sx={{ mt: 2 }}>
                Your private inbox.
              </Typography>
              <Typography color="text.secondary" sx={{ my: 3 }}>
                {config?.registration === "google"
                  ? "Sign in with Google to open your own private archive."
                  : "Sign in with an invited Google account."}{" "}
                Installing the APK does not grant access to another person’s
                archive.
              </Typography>
              {error && (
                <Alert severity="error" sx={{ mb: 2 }}>
                  {error}
                </Alert>
              )}
              <Button
                fullWidth
                variant="contained"
                disabled={busy || !config}
                onClick={() =>
                  void run(async () => {
                    await googleLogin(config!);
                    await load();
                  })
                }
              >
                Continue with Google
              </Button>
              {busy && <LinearProgress sx={{ mt: 2 }} />}
              <Typography variant="caption" sx={{ display: "block", mt: 2 }}>
                Google handles identity. Your selected server stores the
                messages.
              </Typography>
            </CardContent>
          </Card>
        </Container>
        <Footer />
      </>
    );
  return (
    <>
      <Header />
      <Container sx={{ ...{ pb: 6 }, maxWidth: "lg" }}>
        <Stack
          direction="row"
          sx={{
            ...{ mt: 4, mb: 3 },
            justifyContent: "space-between",
            alignItems: "center",
          }}
        >
          <Box>
            <Typography variant="h4">Your inbox, in focus.</Typography>
            <Typography color="text.secondary">
              {status?.archivedMessages ?? 0} synchronized messages
            </Typography>
          </Box>
          <Button
            startIcon={<Logout />}
            onClick={() =>
              void run(async () => {
                await api("/api/v1/auth/session", { method: "DELETE" });
                clearPrivateState();
              })
            }
          >
            Sign out
          </Button>
        </Stack>
        <Tabs
          value={
            section === "conversations"
              ? 1
              : section === "devices"
                ? 2
                : section === "access"
                  ? 3
                  : 0
          }
          variant="scrollable"
          scrollButtons="auto"
          sx={{ mb: 3, borderBottom: "1px solid #dee7dd" }}
        >
          {[
            ["Messages", "/app"],
            ["Conversations", "/app/conversations"],
            ["Devices & sync", "/app/devices"],
            ["Access & privacy", "/app/access"],
          ].map(([name, to]) => (
            <Tab key={name} label={name} component={RouterLink} to={to!} />
          ))}
        </Tabs>
        {busy && <LinearProgress />}
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        {section === "devices" ? (
          <Stack sx={{ gap: 2 }}>
            <Alert severity="info">{status?.freshness}</Alert>
            {devices.map((d) => (
              <Card key={d.id}>
                <CardContent>
                  <Stack direction="row" sx={{ alignItems: "center", gap: 2 }}>
                    <DevicesOutlined />
                    <Box sx={{ flex: 1 }}>
                      <Typography variant="h6">{d.name}</Typography>
                      <Typography color="text.secondary">
                        Last upload:{" "}
                        {d.last_sync
                          ? new Date(d.last_sync).toLocaleString()
                          : "Never"}
                      </Typography>
                      <Typography variant="caption">
                        {d.revoked_at ? "Revoked" : "Authorized"}
                      </Typography>
                    </Box>
                    <Button
                      color="error"
                      disabled={!!d.revoked_at}
                      onClick={() => {
                        if (
                          window.confirm(
                            "Revoke this phone’s upload authorization? Local messaging continues.",
                          )
                        )
                          void run(async () => {
                            await api(`/api/v1/devices/${d.id}/revoke`, {
                              method: "POST",
                            });
                            await load();
                          });
                      }}
                    >
                      Revoke device
                    </Button>
                  </Stack>
                </CardContent>
              </Card>
            ))}
            <Card>
              <CardContent>
                <Typography variant="h6">Upload history</Typography>
                <Typography color="text.secondary" sx={{ mb: 2 }}>
                  Recent batches accepted by this server. Pending phone work
                  appears in the Android sync screen.
                </Typography>
                {history?.uploads.length === 0 && (
                  <Typography>No uploads yet.</Typography>
                )}
                {history?.uploads.map((h: any, index: number) => (
                  <Box
                    key={index}
                    sx={{ py: 1, borderTop: "1px solid #dee7dd" }}
                  >
                    <Typography>
                      {h.name} · {h.processed} processed records
                    </Typography>
                    <Typography variant="caption">
                      {time(new Date(h.created_at).getTime())}
                    </Typography>
                  </Box>
                ))}
                {history?.truncated && (
                  <Alert severity="info">
                    Showing the latest 100 upload batches.
                  </Alert>
                )}
              </CardContent>
            </Card>
          </Stack>
        ) : section === "conversations" ? (
          <Stack sx={{ gap: 2 }}>
            {!conversation ? (
              <>
                <Typography color="text.secondary">
                  Conversations are grouped by phone and inbox generation.
                </Typography>
                {conversations?.truncated && (
                  <Alert severity="info">
                    Showing up to 100 conversations. Use Messages to search the
                    full archive.
                  </Alert>
                )}
                {conversations?.threads.length === 0 && (
                  <Typography>No synchronized conversations yet.</Typography>
                )}
                {conversations?.threads.map((thread: any) => (
                  <Card key={thread.id}>
                    <CardContent>
                      <Typography variant="h6">{thread.address}</Typography>
                      <Typography
                        sx={{
                          whiteSpace: "pre-wrap",
                          overflowWrap: "anywhere",
                          my: 1,
                        }}
                      >
                        {thread.latest.body}
                      </Typography>
                      <Typography variant="caption">
                        {time(thread.date)} ·{" "}
                        {devices.find((d) => d.id === thread.latest.device)
                          ?.name || "Phone"}
                      </Typography>
                      <Box sx={{ mt: 1 }}>
                        <Button
                          component={RouterLink}
                          to={`/app/conversations/${encodeURIComponent(thread.id)}`}
                        >
                          Open conversation
                        </Button>
                      </Box>
                    </CardContent>
                  </Card>
                ))}
              </>
            ) : (
              <>
                <Button
                  sx={{ alignSelf: "start" }}
                  component={RouterLink}
                  to="/app/conversations"
                >
                  Back to conversations
                </Button>
                <Typography variant="h5">{conversation.address}</Typography>
                {conversation.messages.map((m: Message) => (
                  <Card key={m.id}>
                    <CardContent>
                      <Typography variant="caption">
                        {time(m.date)} · {boxes[m.type]}
                      </Typography>
                      <Typography
                        sx={{
                          whiteSpace: "pre-wrap",
                          overflowWrap: "anywhere",
                          my: 2,
                        }}
                      >
                        {m.body}
                      </Typography>
                      <Button onClick={() => setDetail(m)}>
                        Message details
                      </Button>
                    </CardContent>
                  </Card>
                ))}
                {conversation.nextCursor && (
                  <Button
                    onClick={() =>
                      void run(async () => {
                        const next = await api(
                          `/api/v1/threads/${encodeURIComponent(conversation.id)}/messages?cursor=${encodeURIComponent(conversation.nextCursor)}`,
                        );
                        setConversation({
                          ...conversation,
                          ...next,
                          messages: [
                            ...conversation.messages,
                            ...next.messages,
                          ],
                        });
                      })
                    }
                  >
                    Load older messages
                  </Button>
                )}
              </>
            )}
          </Stack>
        ) : section === "access" ? (
          <Stack sx={{ gap: 3 }}>
            <Alert severity="warning">
              AI clients may share returned message text with their provider.
              Sensitive-message access is separate, and detection/filtering is
              imperfect.
            </Alert>
            {interaction && (
              <Card>
                <CardContent>
                  <Typography variant="h6">
                    Authorize {interaction.client}
                  </Typography>
                  <Typography color="text.secondary" sx={{ my: 2 }}>
                    This client requests read-only SMS access. Default consent
                    excludes OTP, security and recovery messages. No SMS
                    sending, deletion or account administration is available
                    through MCP.
                  </Typography>
                  {!user.recent && (
                    <Alert severity="info" sx={{ mb: 2 }}>
                      Sign in with Google again before confirming this client's
                      access. Authorization requests expire after ten minutes;
                      restart the connection from your MCP client if this page
                      has expired.
                    </Alert>
                  )}
                  <FormControlLabel
                    control={
                      <Checkbox
                        checked={sensitive}
                        onChange={(e) => setSensitive(e.target.checked)}
                      />
                    }
                    label="Explicitly allow sensitive messages, including verification and recovery codes"
                  />
                  <Button
                    variant="contained"
                    disabled={busy || !user.recent}
                    onClick={() =>
                      void run(async () => {
                        const { redirectTo } = await api<{
                          redirectTo: string;
                        }>("/app/access/consent", {
                          method: "POST",
                          body: JSON.stringify({ sensitive, approve: true }),
                        });
                        const destination = new URL(
                          redirectTo,
                          window.location.origin,
                        );
                        if (
                          destination.origin !== window.location.origin ||
                          !destination.pathname.startsWith("/oauth/")
                        )
                          throw Error("Invalid authorization continuation");
                        window.location.assign(destination.href);
                      })
                    }
                  >
                    Confirm read-only access
                  </Button>
                  <Button
                    disabled={busy || !config}
                    sx={{ ml: 1 }}
                    onClick={() =>
                      void run(async () => {
                        await googleLogin(config!);
                        await load();
                      })
                    }
                  >
                    Sign in again to continue
                  </Button>
                </CardContent>
              </Card>
            )}
            <Card>
              <CardContent>
                <Typography variant="h6">
                  Personal token for a local MCP adapter
                </Typography>
                <Typography color="text.secondary" sx={{ my: 2 }}>
                  Tokens expire after seven days. Save the token privately; it
                  is shown only once.
                </Typography>
                <FormControlLabel
                  control={
                    <Checkbox
                      checked={tokenSensitive}
                      onChange={(e) => setTokenSensitive(e.target.checked)}
                    />
                  }
                  label="Allow sensitive messages for this token"
                />
                <Button
                  variant="outlined"
                  onClick={() =>
                    void run(async () => {
                      const r = await api("/api/v1/personal-access-tokens", {
                        method: "POST",
                        body: JSON.stringify({
                          name: "Local MCP client",
                          sensitive: tokenSensitive,
                          days: 7,
                        }),
                      });
                      setToken(r.token);
                      await load();
                    })
                  }
                >
                  Create scoped token
                </Button>
                {token && (
                  <TextField
                    fullWidth
                    multiline
                    value={token}
                    label="Copy once, then store privately"
                    sx={{ mt: 3 }}
                  />
                )}
              </CardContent>
            </Card>
            <Typography variant="h6">Authorized clients</Typography>
            {access?.grants?.map((g: any) => (
              <Card key={g.id}>
                <CardContent>
                  <Typography>{g.client}</Typography>
                  <Button
                    color="error"
                    onClick={() =>
                      void run(async () => {
                        await api(`/api/v1/access/${g.id}/revoke`, {
                          method: "POST",
                        });
                        await load();
                      })
                    }
                  >
                    Revoke client
                  </Button>
                </CardContent>
              </Card>
            ))}
            {access?.tokens?.map((t: any) => (
              <Card key={t.id}>
                <CardContent>
                  <Typography>
                    {t.name} ·{" "}
                    {t.sensitive ? "Sensitive access" : "Filtered access"} ·{" "}
                    {t.revoked_at
                      ? "Revoked"
                      : `Expires ${new Date(t.expires_at).toLocaleDateString()}`}
                  </Typography>
                  <Button
                    color="error"
                    disabled={!!t.revoked_at}
                    onClick={() =>
                      void run(async () => {
                        await api(`/api/v1/personal-access-tokens/${t.id}`, {
                          method: "DELETE",
                        });
                        await load();
                      })
                    }
                  >
                    Revoke token
                  </Button>
                </CardContent>
              </Card>
            ))}
            <Typography color="text.secondary">
              Purging server copies requires recent Google authentication.
              Suppression records prevent normal reconciliation from
              re-uploading the same source identity. Reinstall and provider
              resets can change that identity.
            </Typography>
            <Button
              onClick={() =>
                void run(async () => {
                  await googleLogin(config!);
                  await load();
                })
              }
            >
              Sign in again for sensitive actions
            </Button>
          </Stack>
        ) : (
          <>
            <Card sx={{ mb: 3 }}>
              <CardContent>
                <Grid container spacing={2}>
                  <Grid size={{ xs: 12, md: 6 }}>
                    <TextField
                      fullWidth
                      label="Search messages"
                      value={query}
                      onChange={(e) => setQuery(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") void run(() => search());
                      }}
                    />
                  </Grid>
                  <Grid size={{ xs: 12, md: 3 }}>
                    <TextField
                      fullWidth
                      label="Exact sender"
                      value={sender}
                      onChange={(e) => setSender(e.target.value)}
                    />
                  </Grid>
                  <Grid size={{ xs: 12, md: 3 }}>
                    <TextField
                      select
                      fullWidth
                      label="Message type"
                      value={type}
                      onChange={(e) => setType(e.target.value)}
                    >
                      <MenuItem value="">All types</MenuItem>
                      {Object.entries(boxes).map(([id, name]) => (
                        <MenuItem key={id} value={id}>
                          {name}
                        </MenuItem>
                      ))}
                    </TextField>
                  </Grid>
                  <Grid size={{ xs: 12, md: 4 }}>
                    <TextField
                      select
                      fullWidth
                      label="Device"
                      value={device}
                      onChange={(e) => setDevice(e.target.value)}
                    >
                      <MenuItem value="">All devices</MenuItem>
                      {devices.map((d) => (
                        <MenuItem key={d.id} value={d.id}>
                          {d.name}
                        </MenuItem>
                      ))}
                    </TextField>
                  </Grid>
                  <Grid size={{ xs: 6, md: 3 }}>
                    <TextField
                      type="date"
                      fullWidth
                      label="From"
                      slotProps={{ inputLabel: { shrink: true } }}
                      value={from}
                      onChange={(e) => setFrom(e.target.value)}
                    />
                  </Grid>
                  <Grid size={{ xs: 6, md: 3 }}>
                    <TextField
                      type="date"
                      fullWidth
                      label="To"
                      slotProps={{ inputLabel: { shrink: true } }}
                      value={to}
                      onChange={(e) => setTo(e.target.value)}
                    />
                  </Grid>
                  <Grid size={{ xs: 12, md: 2 }}>
                    <Button
                      fullWidth
                      variant="contained"
                      startIcon={<Search />}
                      onClick={() => void run(() => search())}
                    >
                      Search
                    </Button>
                  </Grid>
                </Grid>
              </CardContent>
            </Card>
            {data?.incompleteScan && (
              <Alert severity="info" sx={{ mb: 2 }}>
                This scan found fewer matches than the page limit. Continue
                scanning before treating the result as complete.
              </Alert>
            )}
            <Stack sx={{ gap: 1.5 }}>
              {data?.messages.map((m) => (
                <Card
                  key={m.id}
                  onClick={() => setDetail(m)}
                  sx={{
                    cursor: "pointer",
                    "&:hover": { borderColor: "primary.main" },
                  }}
                >
                  <CardContent>
                    <Stack direction="row" sx={{ gap: 2, alignItems: "start" }}>
                      <Box className="sender-icon">{m.address.slice(0, 1)}</Box>
                      <Box sx={{ flex: 1, minWidth: 0 }}>
                        <Typography sx={{ fontWeight: 650 }}>
                          {m.address}
                        </Typography>
                        <Typography
                          color="text.secondary"
                          sx={{
                            overflow: "hidden",
                            textOverflow: "ellipsis",
                            whiteSpace: "nowrap",
                            my: 0.5,
                          }}
                        >
                          {m.body}
                        </Typography>
                        <Typography variant="caption">
                          {time(m.date)} · {boxes[m.type]}
                        </Typography>
                      </Box>
                      {m.sensitive && <Chip label="Sensitive" size="small" />}
                    </Stack>
                  </CardContent>
                </Card>
              ))}
              {!busy && data?.messages.length === 0 && (
                <Box sx={{ py: 7, textAlign: "center" }}>
                  <SmsOutlined sx={{ fontSize: 50, color: "text.secondary" }} />
                  <Typography variant="h6" sx={{ mt: 2 }}>
                    {data.nextCursor
                      ? "No matches in this scan"
                      : "No synchronized messages here yet"}
                  </Typography>
                  <Typography color="text.secondary">
                    Check your phone’s consent, device authorization and upload
                    status.
                  </Typography>
                </Box>
              )}
            </Stack>
            {data?.nextCursor && (
              <Button
                sx={{ mt: 3 }}
                variant="outlined"
                onClick={() => void run(() => search(data.nextCursor!))}
              >
                Continue {data.incompleteScan ? "scanning" : "to next page"}
              </Button>
            )}
          </>
        )}
      </Container>
      <Dialog
        open={!!detail}
        onClose={() => setDetail(null)}
        fullWidth
        sx={{ maxWidth: "sm" }}
      >
        <DialogTitle>{detail?.address}</DialogTitle>
        <DialogContent>
          <Typography className="message-body">{detail?.body}</Typography>
          <Divider sx={{ my: 3 }} />
          <Typography variant="body2" color="text.secondary">
            {detail && time(detail.date)} · {detail && boxes[detail.type]}
            <br />
            Server ingestion: {detail?.ingestedAt}
            <br />
            Device: {detail?.device}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button
            color="error"
            onClick={() => {
              setPurge(detail);
              setConfirm("");
              setDetail(null);
            }}
          >
            Delete server copy
          </Button>
          <Button onClick={() => setDetail(null)}>Close</Button>
        </DialogActions>
      </Dialog>
      <Dialog open={!!purge} onClose={() => setPurge(null)}>
        <DialogTitle>Delete this server copy?</DialogTitle>
        <DialogContent>
          <Typography sx={{ mb: 2 }}>
            This removes the archive record and suppresses ordinary re-upload.
            It does not delete the phone message.
          </Typography>
          <TextField
            fullWidth
            label="Type DELETE SERVER COPIES"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPurge(null)}>Cancel</Button>
          <Button
            color="error"
            disabled={confirm !== "DELETE SERVER COPIES"}
            onClick={() =>
              void run(async () => {
                await api("/api/v1/messages/purge", {
                  method: "POST",
                  body: JSON.stringify({
                    ids: [purge!.id],
                    confirmation: confirm,
                  }),
                });
                setPurge(null);
                await search();
              })
            }
          >
            Purge copy
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
function App() {
  const location = useLocation();
  const [config, setConfig] = useState<PublicConfig | null>(null);
  useEffect(() => {
    void api<PublicConfig>("/api/v1/public/config")
      .then(setConfig)
      .catch(() => {});
  }, []);
  if (location.pathname.startsWith("/app") || location.pathname === "/login")
    return <Dashboard config={config} />;
  if (location.pathname === "/privacy" || location.pathname === "/self-host")
    return <Info kind={location.pathname.slice(1)} />;
  return <Landing config={config} />;
}
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </ThemeProvider>
  </React.StrictMode>,
);
