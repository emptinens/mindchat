//! Concrete Tokio/XMPP transport implementation.
//!
//! This module is deliberately kept behind [`XmppTransport`](crate::XmppTransport).
//! All `tokio_xmpp` and `xmpp_parsers` values stay in this worker, while the
//! rest of the crate receives normalized, account-scoped transport events.

use crate::{
    AccountId, ConnectionRequest, ContactPresence, ConversationKind, DisconnectKind,
    OutgoingMessage, ProtocolCapability, RosterSubscription, SecretString, TransportError,
    TransportEvent, XmppTransport,
    proxy::{ConnectStrategy, ProxyConfig, ProxyTarget},
};
use futures::{SinkExt, StreamExt};
use hickory_resolver::{TokioResolver, config::LookupIpStrategy, proto::rr::RData};
use std::collections::{BTreeSet, HashMap, HashSet};
use std::fmt;
use std::net::{IpAddr, SocketAddr};
use std::str::FromStr;
use std::sync::mpsc::{self, Receiver, Sender, TryRecvError};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::runtime::Builder as RuntimeBuilder;
use tokio::sync::mpsc::{UnboundedReceiver, UnboundedSender};
use tokio_xmpp::{
    Client, Event as TokioXmppEvent, Stanza,
    connect::{
        AsyncReadAndWrite, ChannelBinding, DirectTlsServerConnector, DnsConfig,
        PreconnectedServerConnector, ServerConnector, StartTlsServerConnector,
    },
    parsers::{
        disco::{DiscoInfoQuery, DiscoInfoResult},
        ibr::{FieldsQuery, FormQuery, LegacyQuery},
        iq::Iq,
        jid::{BareJid, Jid},
        message::{Id as XmppMessageId, Lang, Message, MessageType},
        ns,
        ping::Ping,
        presence::{Presence, Show, Type as PresenceType},
        receipts::{Received as ReceiptReceived, Request as ReceiptRequest},
        roster::{Ask, Item as RosterItem, Roster, Subscription},
        stanza_error::{DefinedCondition, StanzaError},
    },
    xmlstream::{
        FallibleStreamElement, PendingFeaturesRecv, Timeouts, XmppStream, XmppStreamElement,
    },
};

const SEND_TIMEOUT: Duration = Duration::from_secs(15);
const DISCONNECT_TIMEOUT: Duration = Duration::from_secs(5);
/// Upper bound for one stanza send inside the worker loop. A dead socket or
/// a full transmit queue must never stall the single worker task (and with
/// it the whole account) for longer than this.
const WORKER_SEND_TIMEOUT: Duration = Duration::from_secs(10);
/// Total wall-clock budget for the DNS resolution phase. getaddrinfo is the
/// primary path; hickory SRV is only consulted within a shorter inner bound.
const DNS_TOTAL_TIMEOUT: Duration = Duration::from_secs(8);
/// Upper bound for the best-effort hickory SRV lookup, which is unreliable on
/// Android and must never sit on the hot path.
const SRV_TIMEOUT: Duration = Duration::from_secs(3);
/// Upper bound for one connection attempt. tokio-xmpp reconnects silently on
/// its own, so without this bound a stalled handshake would leave the UI in
/// "connecting" forever.
const CONNECT_ATTEMPT_TIMEOUT: Duration = Duration::from_secs(10);
/// Hard deadline for the whole connect phase (DNS plus all candidate
/// handshakes). A terminal `Disconnected` is always emitted when it elapses.
const CONNECT_PHASE_TIMEOUT: Duration = Duration::from_secs(30);
/// Hard deadline for one XEP-0077 in-band registration session (DNS, stream
/// setup, fields query, and submission). Registration is a one-shot exchange,
/// so a single timeout guarantees the FFI call returns a terminal result.
const REGISTRATION_PHASE_TIMEOUT: Duration = Duration::from_secs(30);
/// Upper bound for reading one element during the registration exchange. A
/// silent server must not stall the session past the phase deadline.
const REGISTRATION_READ_TIMEOUT: Duration = Duration::from_secs(10);
/// Backstop for the registration worker channel: slightly longer than the
/// phase deadline so the worker's own timeout wins with a precise reason.
const REGISTRATION_TIMEOUT: Duration = Duration::from_secs(35);
/// Tuned XMPP stream timeouts (0.1.8 P0-2), applied at both connector sites.
/// A soft timeout after `read_timeout` of silence makes the stanzastream
/// worker emit a liveness ping; if the peer stays silent for another
/// `response_timeout`, the stream hard-fails and reconnects. The previous
/// 300s/300s defaults let a dead link sit as stale "Online" for ~10 minutes.
const STREAM_READ_TIMEOUT: Duration = Duration::from_secs(90);
const STREAM_RESPONSE_TIMEOUT: Duration = Duration::from_secs(60);
const STREAM_TIMEOUTS: Timeouts =
    Timeouts { read_timeout: STREAM_READ_TIMEOUT, response_timeout: STREAM_RESPONSE_TIMEOUT };
/// XEP-0199 ping cadence in the worker loop while a session is live. The
/// server's answer counts as inbound data, keeping the stale watchdog from
/// tripping on otherwise idle-but-healthy sessions.
const PING_INTERVAL: Duration = Duration::from_secs(45);
/// How long a live session may go without any inbound data before the
/// watchdog forces a reconnect (bounds stale "Online" to ~3 minutes even
/// against a half-dead server that only echoes keep-alives).
const INBOUND_STALE_TIMEOUT: Duration = Duration::from_secs(180);
/// Granularity of the stale-session check; the actual decision uses
/// [`INBOUND_STALE_TIMEOUT`], not this interval.
const WATCHDOG_CHECK_INTERVAL: Duration = Duration::from_secs(30);
/// Happy Eyeballs-lite head start for the preferred address family before
/// racing the remaining candidates of the same endpoint.
const HAPPY_EYEBALLS_HEAD_START: Duration = Duration::from_millis(250);
/// Upper bound on addresses collected per port during resolution.
const MAX_ADDRESSES_PER_PORT: usize = 4;

/// One-shot XEP-0077 in-band registration request.
///
/// The password is wrapped in [`SecretString`] and consumed at the single
/// hand-off point into the registration worker, mirroring the connect path.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RegisterRequest {
    /// Requested account localpart, without the `@domain` suffix.
    pub username: String,
    /// XMPP server domain that offers in-band registration.
    pub server: String,
    /// Password to register; never persisted and never logged.
    pub password: SecretString,
}

/// Tokio-backed XMPP client implementation used by the Android native core.
///
/// Every account receives one dedicated current-thread Tokio runtime. The
/// synchronous domain trait communicates with that worker through bounded
/// hand-off acknowledgements and polls normalized events without exposing
/// network, XML, or TLS types to callers.
pub struct TokioXmppTransport {
    workers: HashMap<AccountId, WorkerHandle>,
    event_sender: Sender<TransportEvent>,
    event_receiver: Receiver<TransportEvent>,
}

impl Default for TokioXmppTransport {
    fn default() -> Self {
        Self::new()
    }
}

impl fmt::Debug for TokioXmppTransport {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("TokioXmppTransport")
            .field("connected_accounts", &self.workers.keys().collect::<Vec<_>>())
            .finish_non_exhaustive()
    }
}

impl TokioXmppTransport {
    /// Creates an idle concrete transport. No network operation starts until
    /// [`XmppTransport::connect`] is called for an account.
    #[must_use]
    pub fn new() -> Self {
        let (event_sender, event_receiver) = mpsc::channel();
        Self { workers: HashMap::new(), event_sender, event_receiver }
    }

    /// Returns account IDs with a currently-owned worker.
    #[must_use]
    pub fn connected_accounts(&self) -> Vec<AccountId> {
        let mut account_ids = self.workers.keys().copied().collect::<Vec<_>>();
        account_ids.sort_unstable();
        account_ids
    }

    fn retire_worker(&mut self, account_id: AccountId) {
        // The worker emits its terminal event before returning from its Tokio
        // loop. Dropping an unfinished JoinHandle detaches only that exiting
        // thread and, crucially, frees the account slot for an explicit retry.
        let _ = self.workers.remove(&account_id);
    }

    /// Runs a one-shot XEP-0077 in-band registration session.
    ///
    /// This is a synchronous, bounded call: the whole session (DNS, stream
    /// setup, fields query, and submission) runs on a dedicated current-thread
    /// Tokio runtime under [`REGISTRATION_PHASE_TIMEOUT`], so the caller always
    /// gets a terminal result and never hangs. The password is consumed by the
    /// registration worker and never retained by this transport.
    ///
    /// Registration is only attempted when the server advertises
    /// `jabber:iq:register` in its stream features. Servers that require a
    /// data form or captcha are refused with a UI-safe detail, because MindChat
    /// deliberately implements only username/password registration.
    pub fn register(&mut self, request: RegisterRequest) -> Result<(), TransportError> {
        let (sender, receiver) = mpsc::sync_channel(1);
        thread::Builder::new()
            .name("mindchat-xmpp-register".to_owned())
            .spawn(move || {
                let runtime = RuntimeBuilder::new_current_thread().enable_all().build();
                let result = match runtime {
                    Ok(runtime) => {
                        // A panic inside the registration worker would
                        // otherwise kill the thread without any result,
                        // leaving the caller blocked until the channel
                        // backstop. Catch it and report a terminal failure.
                        let outcome =
                            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                                runtime.block_on(run_registration_bounded(request))
                            }));
                        match outcome {
                            Ok(result) => result,
                            Err(_) => Err(TransportError::ConnectionFailed(
                                "XMPP registration worker crashed".to_owned(),
                            )),
                        }
                    }
                    Err(error) => Err(TransportError::ConnectionFailed(error.to_string())),
                };
                let _ = sender.send(result);
            })
            .map_err(|error| TransportError::ConnectionFailed(error.to_string()))?;

        match receiver.recv_timeout(REGISTRATION_TIMEOUT) {
            Ok(result) => result,
            Err(mpsc::RecvTimeoutError::Timeout) => Err(TransportError::ConnectionFailed(
                "registration did not finish within its time bound".to_owned(),
            )),
            Err(mpsc::RecvTimeoutError::Disconnected) => Err(TransportError::ConnectionFailed(
                "XMPP registration worker stopped unexpectedly".to_owned(),
            )),
        }
    }
}

impl XmppTransport for TokioXmppTransport {
    fn connect(&mut self, request: ConnectionRequest) -> Result<(), TransportError> {
        let account_id = request.account_id;
        if self.workers.contains_key(&account_id) {
            return Err(TransportError::Unsupported("account is already connecting".to_owned()));
        }

        let connection = WorkerConnection::from_request(request)?;
        let (command_sender, command_receiver) = tokio::sync::mpsc::unbounded_channel();
        let (ready_sender, ready_receiver) = mpsc::sync_channel(1);
        let event_sender = self.event_sender.clone();
        let thread_name = format!("mindchat-xmpp-{account_id}");
        let join = thread::Builder::new()
            .name(thread_name)
            .spawn(move || {
                let runtime = RuntimeBuilder::new_current_thread().enable_all().build();
                match runtime {
                    Ok(runtime) => {
                        let _ = ready_sender.send(Ok(()));
                        // A panic inside the worker (for example in
                        // tokio-xmpp) would otherwise kill the thread without
                        // any terminal event, leaving the account stuck on
                        // "Connecting" forever. Catch it and emit a
                        // recoverable Disconnected before the thread dies.
                        let worker_sender = event_sender.clone();
                        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                            runtime.block_on(run_worker(
                                connection,
                                command_receiver,
                                worker_sender,
                            ));
                        }));
                        if result.is_err() {
                            let _ = event_sender.send(TransportEvent::Disconnected {
                                account_id,
                                recoverable: true,
                                detail: Some("XMPP worker crashed".to_owned()),
                                kind: DisconnectKind::Unknown,
                            });
                        }
                    }
                    Err(error) => {
                        let _ = ready_sender
                            .send(Err(TransportError::ConnectionFailed(error.to_string())));
                    }
                }
            })
            .map_err(|error| TransportError::ConnectionFailed(error.to_string()))?;

        match ready_receiver.recv() {
            Ok(Ok(())) => {
                self.workers.insert(account_id, WorkerHandle { command_sender, join });
                Ok(())
            }
            Ok(Err(error)) => {
                let _ = join.join();
                Err(error)
            }
            Err(_) => {
                let _ = join.join();
                Err(TransportError::ConnectionFailed(
                    "XMPP worker stopped before initialization".to_owned(),
                ))
            }
        }
    }

    fn disconnect(&mut self, account_id: AccountId) -> Result<(), TransportError> {
        let worker = self.workers.remove(&account_id).ok_or_else(|| {
            TransportError::ConnectionFailed("account has no active XMPP worker".to_owned())
        })?;
        let (response_sender, response_receiver) = mpsc::sync_channel(1);
        worker.command_sender.send(WorkerCommand::Disconnect { response_sender }).map_err(
            |_| TransportError::ConnectionFailed("XMPP worker is unavailable".to_owned()),
        )?;
        let result = match response_receiver.recv_timeout(DISCONNECT_TIMEOUT) {
            Ok(result) => result,
            Err(mpsc::RecvTimeoutError::Timeout) => Err(TransportError::ConnectionFailed(
                "timed out while closing XMPP connection".to_owned(),
            )),
            Err(mpsc::RecvTimeoutError::Disconnected) => Err(TransportError::ConnectionFailed(
                "XMPP worker stopped while closing connection".to_owned(),
            )),
        };
        if result.is_ok() || worker.join.is_finished() {
            let _ = worker.join.join();
        }
        result
    }

    fn send(&mut self, message: OutgoingMessage) -> Result<(), TransportError> {
        let worker = self.workers.get(&message.account_id).ok_or_else(|| {
            TransportError::ConnectionFailed("account has no active XMPP worker".to_owned())
        })?;
        let (response_sender, response_receiver) = mpsc::sync_channel(1);
        worker.command_sender.send(WorkerCommand::Send { message, response_sender }).map_err(
            |_| TransportError::ConnectionFailed("XMPP worker is unavailable".to_owned()),
        )?;
        match response_receiver.recv_timeout(SEND_TIMEOUT) {
            Ok(result) => result,
            Err(mpsc::RecvTimeoutError::Timeout) => Err(TransportError::ConnectionFailed(
                "timed out while sending XMPP stanza".to_owned(),
            )),
            Err(mpsc::RecvTimeoutError::Disconnected) => Err(TransportError::ConnectionFailed(
                "XMPP worker stopped while sending stanza".to_owned(),
            )),
        }
    }

    fn next_event(&mut self) -> Result<Option<TransportEvent>, TransportError> {
        match self.event_receiver.try_recv() {
            Ok(event) => {
                if let TransportEvent::Disconnected { account_id, .. } = &event {
                    self.retire_worker(*account_id);
                }
                Ok(Some(event))
            }
            Err(TryRecvError::Empty) => Ok(None),
            Err(TryRecvError::Disconnected) => {
                // Every worker shares this single event channel, so a
                // disconnected receiver means every worker is gone (crashed,
                // or exited without emitting a terminal event). Retire the
                // tracked workers one at a time, synthesizing a terminal
                // event for each account so no account is ever left stuck on
                // "Connecting" forever.
                if let Some((&account_id, _)) = self.workers.iter().next() {
                    self.retire_worker(account_id);
                    Ok(Some(TransportEvent::Disconnected {
                        account_id,
                        recoverable: true,
                        detail: Some("XMPP worker stopped unexpectedly".to_owned()),
                        kind: DisconnectKind::Unknown,
                    }))
                } else {
                    Ok(None)
                }
            }
        }
    }
}

impl Drop for TokioXmppTransport {
    fn drop(&mut self) {
        for (_, worker) in self.workers.drain() {
            let (response_sender, _) = mpsc::sync_channel(1);
            let _ = worker.command_sender.send(WorkerCommand::Disconnect { response_sender });
        }
    }
}

struct WorkerHandle {
    command_sender: UnboundedSender<WorkerCommand>,
    join: JoinHandle<()>,
}

enum WorkerCommand {
    Send { message: OutgoingMessage, response_sender: mpsc::SyncSender<Result<(), TransportError>> },
    Disconnect { response_sender: mpsc::SyncSender<Result<(), TransportError>> },
}

struct WorkerConnection {
    account_id: AccountId,
    jid: BareJid,
    password: String,
    server: String,
    auto_reconnect: bool,
    /// Connect strategy for this session (ROADMAP 6.2 P2-2). Direct keeps the
    /// resolution-and-dial path; the proxy variants tunnel through
    /// [`Self::proxy`] with TLS against the real hostname (6.3).
    connect_strategy: ConnectStrategy,
    /// Non-secret tunnel configuration; `None` for a direct connection.
    proxy: Option<ProxyConfig>,
    /// Runtime-only proxy credentials (single secret, username empty),
    /// consumed by the proxy handshake and never persisted.
    proxy_password: Option<String>,
}

impl WorkerConnection {
    fn from_request(request: ConnectionRequest) -> Result<Self, TransportError> {
        let jid = BareJid::from_str(&request.jid).map_err(|_| {
            TransportError::ProtocolViolation(
                "invalid account JID supplied to XMPP transport".to_owned(),
            )
        })?;
        let server = request.server.trim().to_owned();
        if server.is_empty() {
            return Err(TransportError::ProtocolViolation("XMPP server is empty".to_owned()));
        }
        validate_endpoint_syntax(&server)?;
        let proxy = request.proxy;
        let connect_strategy = match &proxy {
            None => ConnectStrategy::Direct,
            Some(config) => ConnectStrategy::from(config.kind),
        };
        Ok(Self {
            account_id: request.account_id,
            jid,
            password: request.password.into_inner(),
            server,
            auto_reconnect: request.auto_reconnect,
            connect_strategy,
            proxy,
            proxy_password: request.proxy_password.map(SecretString::into_inner),
        })
    }
}

#[allow(clippy::too_many_lines)]
async fn run_worker(
    connection: WorkerConnection,
    mut command_receiver: UnboundedReceiver<WorkerCommand>,
    event_sender: Sender<TransportEvent>,
) {
    install_rustls_provider();
    let account_id = connection.account_id;
    let auto_reconnect = connection.auto_reconnect;
    // P0-3: one TokioResolver reused for every SRV lookup of this worker,
    // instead of rebuilding the system configuration per resolution.
    let mut srv_resolver: Option<TokioResolver> = None;
    // The entire connect phase (DNS plus every candidate handshake) runs under
    // one hard deadline so a terminal event is guaranteed within
    // CONNECT_PHASE_TIMEOUT, and it is selectable against the command channel
    // so a user disconnect during "connecting" is honored immediately.
    let phase = async {
        // Proxy strategies skip local resolution entirely (DNS-leak
        // protection): the XMPP hostname travels verbatim inside the tunnel
        // and SRV is never consulted. Only the configured proxy hostname is
        // resolved, to open the TCP connection.
        match connection.connect_strategy {
            ConnectStrategy::Direct => {
                let attempts = resolve_endpoints_with(&connection.server, &mut srv_resolver)
                    .await
                    .map_err(|detail| ConnectFailure {
                        detail,
                        recoverable: true,
                        kind: DisconnectKind::NetworkLost,
                    })?;
                connect_attempt(&connection, attempts).await
            }
            ConnectStrategy::HttpConnect | ConnectStrategy::Socks5 => {
                connect_attempt_proxy(&connection).await
            }
        }
    };
    let outcome = tokio::select! {
        command = command_receiver.recv() => {
            match command {
                Some(WorkerCommand::Disconnect { response_sender }) => {
                    let _ = response_sender.send(Ok(()));
                }
                Some(WorkerCommand::Send { response_sender, .. }) => {
                    let _ = response_sender.send(Err(TransportError::ConnectionFailed(
                        "account is still connecting".to_owned(),
                    )));
                }
                None => {}
            }
            return;
        }
        outcome = tokio::time::timeout(CONNECT_PHASE_TIMEOUT, phase) => outcome,
    };
    let (mut client, pending_event) = match outcome {
        Ok(Ok(ReadyClient { client, pending_event })) => (client, pending_event),
        Ok(Err(failure)) => {
            send_event(
                &event_sender,
                TransportEvent::Disconnected {
                    account_id,
                    recoverable: failure.recoverable,
                    detail: Some(failure.detail),
                    kind: failure.kind,
                },
            );
            return;
        }
        Err(_) => {
            send_event(
                &event_sender,
                TransportEvent::Disconnected {
                    account_id,
                    recoverable: true,
                    detail: Some("connection attempt exceeded 30 seconds".to_owned()),
                    kind: DisconnectKind::NetworkLost,
                },
            );
            return;
        }
    };
    let mut stream_capabilities = BTreeSet::new();
    // P0-1/P0-2: whether the account currently has a live session. The ping
    // and the stale-inbound watchdog only run while live; a recoverable
    // Disconnected drops it to false but keeps the worker polling so the
    // vendored stanzastream can resume (XEP-0198).
    let mut session_live = false;
    if let Some(event) = pending_event {
        let action = handle_client_event(
            account_id,
            &mut client,
            event,
            &mut stream_capabilities,
            &event_sender,
            auto_reconnect,
            &mut session_live,
        )
        .await;
        if action == LoopAction::Stop {
            return;
        }
    }

    let mut ping_interval = tokio::time::interval(PING_INTERVAL);
    ping_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut watchdog_interval = tokio::time::interval(WATCHDOG_CHECK_INTERVAL);
    watchdog_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut last_inbound = Instant::now();
    let mut ping_counter = 0u64;

    loop {
        tokio::select! {
            command = command_receiver.recv() => match command {
                Some(WorkerCommand::Send { message, response_sender }) => {
                    // Bound the send so a dead socket cannot stall the
                    // command channel longer than WORKER_SEND_TIMEOUT.
                    let result =
                        match tokio::time::timeout(WORKER_SEND_TIMEOUT, send_message(&mut client, message)).await {
                            Ok(result) => result,
                            Err(_) => Err(TransportError::ConnectionFailed(
                                "timed out while sending XMPP stanza".to_owned(),
                            )),
                        };
                    let _ = response_sender.send(result);
                }
                Some(WorkerCommand::Disconnect { response_sender }) => {
                    // A user-requested disconnect stays immediate: the worker
                    // closes the stream and exits regardless of auto_reconnect.
                    let result =
                        match tokio::time::timeout(WORKER_SEND_TIMEOUT, client.send_end()).await {
                            Ok(result) => result.map_err(map_xmpp_error),
                            Err(_) => Err(TransportError::ConnectionFailed(
                                "timed out while closing XMPP connection".to_owned(),
                            )),
                        };
                    let _ = response_sender.send(result);
                    break;
                }
                None => {
                    let _ = tokio::time::timeout(WORKER_SEND_TIMEOUT, client.send_end()).await;
                    break;
                }
            },
            _ = ping_interval.tick(), if session_live => {
                // XEP-0199 keep-alive. Bounded like every send; the response
                // counts as inbound data for the watchdog.
                ping_counter = ping_counter.wrapping_add(1);
                let ping: Stanza =
                    Iq::from_get(format!("mindchat-ping-{ping_counter}"), Ping).into();
                send_stanza_bounded(&mut client, ping).await;
            }
            _ = watchdog_interval.tick(), if session_live => {
                if is_inbound_stale(
                    Instant::now().duration_since(last_inbound),
                    INBOUND_STALE_TIMEOUT,
                ) {
                    session_live = false;
                    send_event(
                        &event_sender,
                        TransportEvent::Disconnected {
                            account_id,
                            recoverable: true,
                            detail: Some(
                                "no inbound data from the server for 3 minutes".to_owned(),
                            ),
                            kind: DisconnectKind::NetworkLost,
                        },
                    );
                    if auto_reconnect {
                        // Force the stale session down; the vendored
                        // stanzastream reconnects with jittered backoff and
                        // XEP-0198 resume.
                        client.request_reconnect().await;
                    } else {
                        // Without auto-reconnect the worker exits exactly
                        // like any other disconnect; a manual connect can
                        // bring the account back.
                        break;
                    }
                }
            }
            event = client.next() => if let Some(event) = event {
                    // Any inbound activity (stanzas, resets, resumes) proves
                    // the link is alive; reset the stale watchdog.
                    last_inbound = Instant::now();
                    let action = handle_client_event(
                        account_id,
                        &mut client,
                        event,
                        &mut stream_capabilities,
                        &event_sender,
                        auto_reconnect,
                        &mut session_live,
                    ).await;
                    if action == LoopAction::Stop {
                        break;
                    }
            } else {
                send_event(
                    &event_sender,
                    // An EOF has no server-supplied terminal cause. Treat it
                    // as retryable so an ordinary network loss never becomes
                    // a permanent credential failure in the UI.
                    TransportEvent::Disconnected {
                        account_id,
                        recoverable: true,
                        detail: None,
                        kind: DisconnectKind::NetworkLost,
                    },
                );
                break;
            },
        }
    }
}

/// A connected client plus the first event received during negotiation.
struct ReadyClient {
    client: Client,
    pending_event: Option<TokioXmppEvent>,
}

/// A terminal connect failure with a UI-safe reason.
#[derive(Clone, Debug, Eq, PartialEq)]
struct ConnectFailure {
    detail: String,
    /// False when the server rejected the credentials, so the account is
    /// projected as `Failed` instead of silently returning to `Offline`.
    recoverable: bool,
    /// Diagnostics classification (ROADMAP 6.5).
    kind: DisconnectKind,
}

/// Candidates for one connection attempt grouped by `(port, use_direct_tls)`.
///
/// Addresses of the same endpoint (for example one A and one AAAA record for
/// `host:5222`) form one race group so a dead address family cannot block the
/// other for a full attempt budget.
#[derive(Debug, Clone, Eq, PartialEq)]
struct AttemptGroup {
    port: u16,
    direct_tls: bool,
    addresses: Vec<SocketAddr>,
}

/// Splits the ordered candidate list into consecutive groups that share a
/// port and TLS mode, preserving the plain-endpoints-before-SRV ordering.
#[must_use]
fn group_attempts(attempts: Vec<(SocketAddr, bool)>) -> Vec<AttemptGroup> {
    let mut groups: Vec<AttemptGroup> = Vec::new();
    for (address, direct_tls) in attempts {
        match groups.last_mut() {
            Some(group) if group.port == address.port() && group.direct_tls == direct_tls => {
                group.addresses.push(address);
            }
            _ => groups.push(AttemptGroup {
                port: address.port(),
                direct_tls,
                addresses: vec![address],
            }),
        }
    }
    groups
}

/// Attempts each candidate group in order and returns the first client whose
/// handshake produces a usable event within [`CONNECT_ATTEMPT_TIMEOUT`].
///
/// tokio-xmpp retries failed connections internally without surfacing them,
/// so each attempt is bounded here and the next candidate (for example
/// direct TLS on port 5223) is tried instead of leaving the UI stuck in
/// "connecting". Only one handshake runs per candidate: authentication
/// failures are delivered by the vendored tokio-xmpp as the first event
/// (`Event::Disconnected(Error::Auth(_))`), so a rejected password fails fast
/// with a precise, non-recoverable reason instead of a second handshake or a
/// generic timeout.
async fn connect_attempt(
    connection: &WorkerConnection,
    attempts: Vec<(SocketAddr, bool)>,
) -> Result<ReadyClient, ConnectFailure> {
    // ROADMAP 6.2 P2-2: a proxy strategy without a tunnel configuration fails
    // recoverably before any candidate handshake instead of silently
    // connecting directly. Direct always passes. The failure runs under the
    // same connect budget as every other candidate failure.
    if let Some(failure) =
        strategy_connect_failure(connection.connect_strategy, connection.proxy.is_some())
    {
        return Err(failure);
    }
    let mut last_failure = ConnectFailure {
        detail: String::from("no connection candidates"),
        recoverable: true,
        kind: DisconnectKind::NetworkLost,
    };
    for group in group_attempts(attempts) {
        match try_group(connection, group).await {
            Ok(ready) => return Ok(ready),
            Err(failure) => {
                if let Some(terminal) = terminal_if_auth(failure.clone()) {
                    // Rejected credentials are terminal: the server decided,
                    // so no other candidate can succeed. Fail fast without a
                    // second handshake, exactly like 0.1.7.
                    return Err(terminal);
                }
                last_failure = failure;
            }
        }
    }
    Err(last_failure)
}

/// Returns the failure when it is non-recoverable (the server rejected the
/// credentials and no other candidate can help), so `connect_attempt` can
/// fail fast instead of trying another handshake. Recoverable failures are
/// filtered out and only remembered as the last detail.
#[must_use]
fn terminal_if_auth(failure: ConnectFailure) -> Option<ConnectFailure> {
    (!failure.recoverable).then_some(failure)
}

/// Maps a connect strategy to the failure it produces for a worker without
/// usable proxy configuration.
///
/// [`ConnectStrategy::Direct`] connects normally. A proxy strategy whose
/// worker carries no tunnel configuration produces a recoverable connection
/// failure so a misconfigured account can never silently fall back to a
/// direct connection; the failure is billed to the same connect budget as any
/// other candidate failure.
#[must_use]
fn strategy_connect_failure(
    strategy: ConnectStrategy,
    proxy_configured: bool,
) -> Option<ConnectFailure> {
    match (strategy, proxy_configured) {
        (ConnectStrategy::Direct, _)
        | (ConnectStrategy::HttpConnect | ConnectStrategy::Socks5, true) => None,
        (strategy @ (ConnectStrategy::HttpConnect | ConnectStrategy::Socks5), false) => {
            Some(ConnectFailure {
                detail: format!("{strategy} connect strategy has no proxy configuration"),
                recoverable: true,
                // Local configuration problem: no dedicated bucket yet.
                kind: DisconnectKind::Unknown,
            })
        }
    }
}

/// Establishes an XMPP session through the worker's proxy tunnel.
///
/// The proxy handshake (SOCKS5 or HTTP CONNECT) runs first; DNS for the XMPP
/// domain happens only at the proxy. The tunneled stream is then wrapped in a
/// fresh [`PreconnectedServerConnector`] so direct TLS runs against the real
/// hostname, and the resulting client is polled for its first event exactly
/// like the direct path. The whole attempt is bounded by
/// [`CONNECT_ATTEMPT_TIMEOUT`].
async fn connect_attempt_proxy(
    connection: &WorkerConnection,
) -> Result<ReadyClient, ConnectFailure> {
    if let Some(failure) =
        strategy_connect_failure(connection.connect_strategy, connection.proxy.is_some())
    {
        return Err(failure);
    }
    let proxy = connection.proxy.as_ref().expect("strategy_connect_failure guarantees a proxy");
    let target = ProxyTarget::from_server(&connection.server).map_err(|error| ConnectFailure {
        detail: error.to_string(),
        recoverable: true,
        // Invalid proxy target is a local configuration problem.
        kind: DisconnectKind::Unknown,
    })?;
    let connector =
        ProxyServerConnector::new(proxy.clone(), connection.proxy_password.clone(), target);
    let mut client = Client::new_with_connector(
        connection.jid.clone(),
        connection.password.clone(),
        connector,
        STREAM_TIMEOUTS,
    );
    match tokio::time::timeout(CONNECT_ATTEMPT_TIMEOUT, client.next()).await {
        Ok(Some(event)) => first_event_to_ready(client, event),
        Ok(None) => Err(ConnectFailure {
            detail: "the proxy tunnel closed during the XMPP handshake".to_owned(),
            recoverable: true,
            kind: DisconnectKind::NetworkLost,
        }),
        Err(_) => Err(ConnectFailure {
            detail: format!(
                "the proxy connection timed out after {} seconds",
                CONNECT_ATTEMPT_TIMEOUT.as_secs()
            ),
            recoverable: true,
            kind: DisconnectKind::NetworkLost,
        }),
    }
}

/// ServerConnector that opens a proxy tunnel and hands the stream to the
/// vendored `PreconnectedServerConnector` for TLS + stream initiation.
///
/// Each `connect` call (including the vendored reconnect path) runs a fresh
/// proxy handshake and builds a fresh preconnected connector, so mid-session
/// reconnects re-establish the tunnel instead of reusing a consumed stream.
#[derive(Clone, Debug)]
struct ProxyServerConnector {
    proxy: ProxyConfig,
    proxy_password: Option<String>,
    target: ProxyTarget,
}

impl ProxyServerConnector {
    fn new(proxy: ProxyConfig, proxy_password: Option<String>, target: ProxyTarget) -> Self {
        Self { proxy, proxy_password, target }
    }
}

impl ServerConnector for ProxyServerConnector {
    type Stream = <PreconnectedServerConnector<
        tokio::io::BufReader<tokio::net::TcpStream>,
    > as ServerConnector>::Stream;

    async fn connect(
        &self,
        jid: &Jid,
        ns: &'static str,
        timeouts: Timeouts,
    ) -> Result<(PendingFeaturesRecv<Self::Stream>, ChannelBinding), tokio_xmpp::Error> {
        let stream = crate::proxy::connect_via_proxy(
            &self.proxy,
            self.proxy_password.as_deref(),
            &self.target,
        )
        .await
        .map_err(|error| tokio_xmpp::Error::Io(std::io::Error::other(error.to_string())))?;
        PreconnectedServerConnector::new(stream, self.target.host.clone())
            .connect(jid, ns, timeouts)
            .await
    }
}

/// Races every address of one `(port, TLS mode)` group with a Happy
/// Eyeballs-lite head start: the first address is polled for
/// [`HAPPY_EYEBALLS_HEAD_START`], then all remaining addresses are raced
/// concurrently. The whole group is bounded by [`CONNECT_ATTEMPT_TIMEOUT`].
async fn try_group(
    connection: &WorkerConnection,
    group: AttemptGroup,
) -> Result<ReadyClient, ConnectFailure> {
    let mut addresses = group.addresses.into_iter();
    let first_address =
        addresses.next().expect("an attempt group always contains at least one address");
    let mut first_client = new_connect_client(connection, first_address, group.direct_tls);

    // Head start for the preferred address family.
    let head_start = tokio::time::timeout(HAPPY_EYEBALLS_HEAD_START, first_client.next()).await;
    match head_start {
        Ok(Some(event)) => return first_event_to_ready(first_client, event),
        Ok(None) => {
            return Err(ConnectFailure {
                detail: format!("connection to {first_address} closed during handshake"),
                recoverable: true,
                kind: DisconnectKind::NetworkLost,
            });
        }
        // Head start elapsed (or the first client errored) without a usable
        // event: race the first client against the remaining addresses.
        Err(_) => {}
    }

    let mut racing: Vec<Client> = Vec::with_capacity(addresses.len() + 1);
    racing.push(first_client);
    for address in addresses {
        racing.push(new_connect_client(connection, address, group.direct_tls));
    }
    let outcome = tokio::time::timeout(CONNECT_ATTEMPT_TIMEOUT, async {
        // Each racer owns its client and yields it back with the first event,
        // so the winner's client can be returned to the session loop.
        let mut pending = racing
            .into_iter()
            .map(|mut client| {
                Box::pin(async move { (client.next().await, client) })
                    as std::pin::Pin<
                        Box<
                            dyn std::future::Future<Output = (Option<TokioXmppEvent>, Client)>
                                + Send,
                        >,
                    >
            })
            .collect::<Vec<_>>();
        loop {
            let (result, _, remaining) = futures::future::select_all(pending).await;
            pending = remaining;
            let (event, client) = result;
            match event {
                Some(event) => return Ok((client, event)),
                None if pending.is_empty() => {
                    return Err(());
                }
                None => {
                    // This racer's handshake closed without an event; keep
                    // racing the remaining addresses.
                }
            }
        }
    })
    .await;
    match outcome {
        Ok(Ok((client, event))) => first_event_to_ready(client, event),
        Ok(Err(())) => Err(ConnectFailure {
            detail: format!("connection to port {} closed during handshake", group.port),
            recoverable: true,
            kind: DisconnectKind::NetworkLost,
        }),
        Err(_) => Err(ConnectFailure {
            detail: format!(
                "connection to port {} timed out after {} seconds",
                group.port,
                CONNECT_ATTEMPT_TIMEOUT.as_secs()
            ),
            recoverable: true,
            kind: DisconnectKind::NetworkLost,
        }),
    }
}

/// Builds a client for one concrete endpoint with the tuned stream timeouts.
fn new_connect_client(
    connection: &WorkerConnection,
    endpoint: SocketAddr,
    direct_tls: bool,
) -> Client {
    if direct_tls {
        Client::new_direct_tls_with_config(
            connection.jid.clone(),
            connection.password.clone(),
            DnsConfig::addr(&endpoint.to_string()),
            STREAM_TIMEOUTS,
        )
    } else {
        Client::new_starttls(
            connection.jid.clone(),
            connection.password.clone(),
            DnsConfig::addr(&endpoint.to_string()),
            STREAM_TIMEOUTS,
        )
    }
}

/// Converts the winning handshake event into a ready client, or surfaces a
/// terminal failure (rejected credentials) with its precise reason.
fn first_event_to_ready(
    client: Client,
    event: TokioXmppEvent,
) -> Result<ReadyClient, ConnectFailure> {
    if let Some(failure) = terminal_failure_for_event(&event) {
        return Err(failure);
    }
    Ok(ReadyClient { client, pending_event: Some(event) })
}

/// Bounded wrapper around the full registration session so a stalled server
/// can never hang the synchronous [`TokioXmppTransport::register`] call.
async fn run_registration_bounded(request: RegisterRequest) -> Result<(), TransportError> {
    match tokio::time::timeout(REGISTRATION_PHASE_TIMEOUT, run_registration(request)).await {
        Ok(result) => result,
        Err(_) => Err(TransportError::ConnectionFailed(format!(
            "registration exceeded {} seconds",
            REGISTRATION_PHASE_TIMEOUT.as_secs()
        ))),
    }
}

/// Runs the XEP-0077 exchange against the first reachable endpoint.
///
/// Registration cannot reuse the [`Client`]-based `connect_attempt` path:
/// tokio-xmpp's `Client` always performs SASL authentication and resource
/// binding before exposing stanzas, while XEP-0077 must run on the unbound,
/// pre-authentication stream. This session therefore reuses the same
/// `resolve_endpoints` machinery and tokio-xmpp connectors directly.
async fn run_registration(request: RegisterRequest) -> Result<(), TransportError> {
    install_rustls_provider();
    // The password leaves its SecretString wrapper here, the single hand-off
    // point into the registration worker, mirroring WorkerConnection.
    let RegisterRequest { username, server, password } = request;
    let password = password.into_inner();
    let jid = Jid::from_str(&format!("{username}@{server}")).map_err(|_| {
        TransportError::ProtocolViolation(
            "invalid username or server supplied for XMPP registration".to_owned(),
        )
    })?;
    let attempts = resolve_endpoints(&server).await.map_err(TransportError::ConnectionFailed)?;
    let mut last_connection_error = String::from("no connection candidates");
    for (endpoint, direct_tls) in attempts {
        match registration_attempt_for_endpoint(&username, &password, &jid, endpoint, direct_tls)
            .await
        {
            Ok(()) => return Ok(()),
            Err(RegistrationAttemptError::TlsVerification(detail)) => {
                // TLS verification failed: fail fast without trying remaining candidates.
                return Err(TransportError::TlsVerification(detail));
            }
            Err(RegistrationAttemptError::Protocol(detail)) => {
                // The server decided (refused, requires a form, or the
                // username is taken); retrying another candidate cannot help.
                return Err(TransportError::ConnectionFailed(detail));
            }
            Err(RegistrationAttemptError::Connection(detail)) => {
                last_connection_error = detail;
            }
        }
    }
    Err(TransportError::ConnectionFailed(last_connection_error))
}

/// A failure within one registration candidate attempt.
enum RegistrationAttemptError {
    /// TLS certificate verification failure (fail fast).
    TlsVerification(String),
    /// Transport-level failure (DNS, TCP, TLS, stream setup). Another
    /// candidate may still succeed.
    Connection(String),
    /// The server decided on the registration; further candidates cannot help.
    Protocol(String),
}

async fn registration_attempt_for_endpoint(
    username: &str,
    password: &str,
    jid: &Jid,
    endpoint: SocketAddr,
    direct_tls: bool,
) -> Result<(), RegistrationAttemptError> {
    if direct_tls {
        registration_attempt(
            username,
            password,
            jid,
            DirectTlsServerConnector::from(DnsConfig::addr(&endpoint.to_string())),
        )
        .await
    } else {
        registration_attempt(
            username,
            password,
            jid,
            StartTlsServerConnector::from(DnsConfig::addr(&endpoint.to_string())),
        )
        .await
    }
}

/// Performs one full XEP-0077 exchange over a freshly established stream.
async fn registration_attempt<C: ServerConnector>(
    username: &str,
    password: &str,
    jid: &Jid,
    connector: C,
) -> Result<(), RegistrationAttemptError> {
    let (pending, _channel_binding) =
        connector.connect(jid, ns::JABBER_CLIENT, STREAM_TIMEOUTS).await.map_err(|error| {
            if is_tls_verification_error(&error) {
                RegistrationAttemptError::TlsVerification(format!(
                    "TLS certificate verification failed for {}: {error}",
                    jid.domain()
                ))
            } else {
                RegistrationAttemptError::Connection(error.to_string())
            }
        })?;
    let (features, mut stream) = pending
        .recv_features::<FallibleStreamElement>()
        .await
        .map_err(|error| RegistrationAttemptError::Connection(error.to_string()))?;
    if let Some(detail) = registration_refusal(&features) {
        return Err(RegistrationAttemptError::Protocol(detail));
    }

    let fields_id = format!("mindchat-register-get-{}", now_epoch_ms());
    let fields_query: Stanza = Iq::from_get(fields_id.clone(), FieldsQuery).into();
    stream
        .send(&fields_query)
        .await
        .map_err(|error| RegistrationAttemptError::Connection(error.to_string()))?;

    let fields_response = loop {
        let element = read_registration_element(&mut stream).await?;
        if let XmppStreamElement::Stanza(Stanza::Iq(iq)) = element
            && iq.id() == fields_id
        {
            break iq;
        }
    };

    // Parse the fields response. A data form (XEP-0077 form or captcha) is
    // deliberately unsupported; only the legacy username/password flow is.
    let submit = match fields_response {
        Iq::Result { payload: Some(payload), .. } => {
            if FormQuery::try_from(payload.clone()).is_ok() {
                return Err(RegistrationAttemptError::Protocol(
                    "server requires additional registration fields".to_owned(),
                ));
            }
            let mut query = LegacyQuery::try_from(payload).map_err(|_| {
                RegistrationAttemptError::Protocol(
                    "unexpected response to registration fields query".to_owned(),
                )
            })?;
            if query.registered {
                return Err(RegistrationAttemptError::Protocol(
                    "this username is already registered".to_owned(),
                ));
            }
            query.username = Some(username.to_owned());
            query.password = Some(password.to_owned());
            query
        }
        Iq::Error { error, .. } => {
            return Err(RegistrationAttemptError::Protocol(registration_error_detail(&error)));
        }
        _ => {
            return Err(RegistrationAttemptError::Protocol(
                "unexpected response to registration fields query".to_owned(),
            ));
        }
    };

    let submit_id = format!("mindchat-register-set-{}", now_epoch_ms());
    let submit_query: Stanza = Iq::from_set(submit_id.clone(), submit).into();
    stream
        .send(&submit_query)
        .await
        .map_err(|error| RegistrationAttemptError::Connection(error.to_string()))?;

    let submit_response = loop {
        let element = read_registration_element(&mut stream).await?;
        if let XmppStreamElement::Stanza(Stanza::Iq(iq)) = element
            && iq.id() == submit_id
        {
            break iq;
        }
    };
    let result = match submit_response {
        Iq::Result { .. } => Ok(()),
        Iq::Error { error, .. } => {
            Err(RegistrationAttemptError::Protocol(registration_error_detail(&error)))
        }
        _ => Err(RegistrationAttemptError::Protocol(
            "unexpected response to registration submission".to_owned(),
        )),
    };
    // Best-effort polite stream close; the transport is dropped either way.
    let _ = tokio::time::timeout(Duration::from_secs(5), stream.shutdown()).await;
    result
}

/// UI-safe refusal when the server does not advertise in-band registration.
///
/// XEP-0077 mandates that servers offering registration include the
/// `http://jabber.org/features/iq-register` feature in their stream features,
/// so the stream-features check alone gates registration. A disco#info
/// fallback is deliberately not implemented: a server that hides registration
/// from stream features is not offering the legacy flow this client supports.
#[must_use]
fn registration_refusal(
    features: &xmpp_parsers::stream_features::StreamFeatures,
) -> Option<String> {
    (!features.in_band_registration)
        .then(|| "this server does not support in-band registration".to_owned())
}

/// Maps a stanza error condition from the registration exchange to a UI-safe
/// detail string.
#[must_use]
fn registration_error_detail(error: &StanzaError) -> String {
    match error.defined_condition {
        DefinedCondition::Conflict => "this username is already registered".to_owned(),
        DefinedCondition::NotAcceptable => {
            "the server did not accept the registration details".to_owned()
        }
        DefinedCondition::NotAllowed | DefinedCondition::Forbidden => {
            "this server does not allow registration".to_owned()
        }
        DefinedCondition::ServiceUnavailable => {
            "registration is temporarily unavailable".to_owned()
        }
        DefinedCondition::FeatureNotImplemented => {
            "this server does not support in-band registration".to_owned()
        }
        _ => "the server refused the registration".to_owned(),
    }
}

/// Reads one stream element during the registration exchange, bounded so a
/// silent server cannot stall the session. A stream error is a terminal
/// protocol-level failure for the registration.
async fn read_registration_element<S: AsyncReadAndWrite>(
    stream: &mut XmppStream<S>,
) -> Result<XmppStreamElement, RegistrationAttemptError> {
    let item = tokio::time::timeout(REGISTRATION_READ_TIMEOUT, stream.next())
        .await
        .map_err(|_| {
            RegistrationAttemptError::Connection(
                "timed out waiting for the server during registration".to_owned(),
            )
        })?
        .ok_or_else(|| {
            RegistrationAttemptError::Connection(
                "the server closed the stream during registration".to_owned(),
            )
        })?;
    let element = item.and_then(FallibleStreamElement::into_read_error).map_err(|error| {
        RegistrationAttemptError::Connection(format!(
            "invalid data from the server during registration: {error}"
        ))
    })?;
    if let XmppStreamElement::StreamError(error) = &element {
        return Err(RegistrationAttemptError::Protocol(format!(
            "the server closed the stream during registration: {error}"
        )));
    }
    Ok(element)
}

/// Removes duplicate `(address, use_direct_tls)` candidates while preserving
/// order. The SRV target often equals the plain `host:5222` candidate.
#[must_use]
fn dedupe_candidates(attempts: Vec<(SocketAddr, bool)>) -> Vec<(SocketAddr, bool)> {
    let mut seen = HashSet::new();
    attempts.into_iter().filter(|candidate| seen.insert(*candidate)).collect()
}

/// True when the error is a terminal authentication failure.
///
/// A server-side `TemporaryAuthFailure` is deliberately recoverable: it is a
/// transient condition (for example rate limiting) and a later retry can
/// succeed, unlike a hard credential rejection.
#[must_use]
fn is_auth_error(error: &tokio_xmpp::Error) -> bool {
    let tokio_xmpp::Error::Auth(auth_error) = error else {
        return false;
    };
    !matches!(
        auth_error,
        tokio_xmpp::error::AuthError::Fail(
            tokio_xmpp::parsers::sasl::DefinedCondition::TemporaryAuthFailure,
        )
    )
}

/// True when the error represents a TLS certificate verification failure.
///
/// TLS certificate validation failures (invalid, expired, untrusted CA, domain mismatch)
/// are terminal like authentication failures: retrying the same server endpoint cannot
/// succeed and retrying indefinitely would leave the UI in connecting loops.
#[must_use]
fn is_tls_verification_error(error: &tokio_xmpp::Error) -> bool {
    error.is_tls_verification_error()
}

/// Classifies a typed transport failure into the diagnostics disconnect kind
/// (ROADMAP 6.5).
///
/// This is the single place that derives control-flow meaning from a
/// disconnect cause, and it reads typed error variants, never prose. Display
/// strings (for example the connect failure detail) stay display-only.
/// Mapping:
///
/// - TLS certificate failure → [`DisconnectKind::TlsVerificationFailed`];
/// - SASL failure → [`DisconnectKind::AuthenticationFailed`];
/// - server stream error (the server ends the stream with `<stream:error>`)
///   → [`DisconnectKind::ServerRefused`];
/// - I/O error, closed stream, connector/tunnel failure, wrong address, or
///   reconnect-budget exhaustion → [`DisconnectKind::NetworkLost`];
/// - anything else (JID/protocol/format/state) → [`DisconnectKind::Unknown`].
#[must_use]
fn classify_disconnect(error: &tokio_xmpp::Error) -> DisconnectKind {
    if is_tls_verification_error(error) {
        return DisconnectKind::TlsVerificationFailed;
    }
    match error {
        tokio_xmpp::Error::Auth(_) => DisconnectKind::AuthenticationFailed,
        tokio_xmpp::Error::StreamError(_) => DisconnectKind::ServerRefused,
        tokio_xmpp::Error::Io(_)
        | tokio_xmpp::Error::Disconnected
        | tokio_xmpp::Error::Connection(_)
        | tokio_xmpp::Error::Addr(_)
        | tokio_xmpp::Error::DnsProto(_)
        | tokio_xmpp::Error::DnsNet(_)
        | tokio_xmpp::Error::Idna
        | tokio_xmpp::Error::ReconnectBudgetExhausted
        | tokio_xmpp::Error::Tls(_) => DisconnectKind::NetworkLost,
        tokio_xmpp::Error::JidParse(_)
        | tokio_xmpp::Error::Protocol(_)
        | tokio_xmpp::Error::InvalidState
        | tokio_xmpp::Error::Fmt(_)
        | tokio_xmpp::Error::Utf8(_) => DisconnectKind::Unknown,
    }
}

/// Maps the first client event to a terminal connect failure, if it is one.
///
/// The only terminal first event is `Disconnected`; it carries the precise
/// reason (for example a rejected SASL exchange or TLS certificate failure) and
/// its recoverability. This is the single place that replaces the old
/// authentication preflight's detection of rejected credentials.
#[must_use]
fn terminal_failure_for_event(event: &TokioXmppEvent) -> Option<ConnectFailure> {
    match event {
        TokioXmppEvent::Disconnected(error) => Some(ConnectFailure {
            detail: error.to_string(),
            recoverable: disconnected_is_recoverable(error),
            kind: classify_disconnect(error),
        }),
        _ => None,
    }
}

/// Sends a stanza with a bounded timeout so a dead socket or a full transmit
/// queue can never stall the single worker task indefinitely. Sending is best
/// effort: a transient failure is deliberately ignored so the worker keeps
/// processing events (and can still surface the underlying disconnect).
async fn send_stanza_bounded(client: &mut Client, stanza: Stanza) {
    let _ = tokio::time::timeout(WORKER_SEND_TIMEOUT, client.send_stanza(stanza)).await;
}

/// Recoverability of a mid-session disconnect, mirroring the connect path:
/// only a hard authentication failure or TLS certificate validation failure
/// is non-recoverable; a transient `TemporaryAuthFailure` or any network/IO
/// failure keeps the account retryable.
#[must_use]
fn disconnected_is_recoverable(error: &tokio_xmpp::Error) -> bool {
    !is_auth_error(error) && !is_tls_verification_error(error)
}

/// What the worker loop should do after handling one client event.
#[derive(Debug, Clone, Copy, Eq, PartialEq)]
enum LoopAction {
    /// Keep polling the same client. For a recoverable mid-session
    /// `Disconnected` with auto-reconnect enabled this means the vendored
    /// stanzastream keeps reconnecting (XEP-0198 resume).
    Keep,
    /// Drop the client and end the worker (non-recoverable failure, disabled
    /// auto-reconnect, reconnect budget exhausted, or stream end).
    Stop,
}

/// True when the vendored reconnect loop gave up after its total retry
/// budget. The account is still recoverable, but this worker will not retry
/// on its own, so the loop must end.
#[must_use]
fn reconnect_budget_exhausted(error: &tokio_xmpp::Error) -> bool {
    matches!(error, tokio_xmpp::Error::ReconnectBudgetExhausted)
}

/// Pure reconnect decision for a client event. This is the single place that
/// maps a `Disconnected` to keep-polling (resume) or worker exit, so tests
/// can drive it without a network.
#[must_use]
fn reconnect_decision(event: &TokioXmppEvent, auto_reconnect: bool) -> LoopAction {
    let TokioXmppEvent::Disconnected(error) = event else {
        return LoopAction::Keep;
    };
    if reconnect_budget_exhausted(error) {
        return LoopAction::Stop;
    }
    if disconnected_is_recoverable(error) && auto_reconnect {
        return LoopAction::Keep;
    }
    LoopAction::Stop
}

/// Pure stale-session decision for the inbound watchdog. `idle` is the time
/// since the last inbound event; `threshold` is [`INBOUND_STALE_TIMEOUT`].
/// Kept free of time sources so unit tests can drive it deterministically.
#[must_use]
fn is_inbound_stale(idle: Duration, threshold: Duration) -> bool {
    idle > threshold
}

#[allow(clippy::too_many_lines)]
async fn handle_client_event(
    account_id: AccountId,
    client: &mut Client,
    event: TokioXmppEvent,
    stream_capabilities: &mut BTreeSet<ProtocolCapability>,
    event_sender: &Sender<TransportEvent>,
    auto_reconnect: bool,
    session_live: &mut bool,
) -> LoopAction {
    match event {
        TokioXmppEvent::Online { bound_jid, features, .. } => {
            *session_live = true;
            *stream_capabilities = stream_capabilities_from_features(&features);
            send_event(
                event_sender,
                TransportEvent::Connected { account_id, capabilities: stream_capabilities.clone() },
            );
            send_stanza_bounded(client, Presence::available().into()).await;
            send_stanza_bounded(
                client,
                Iq::from_get(roster_request_id(account_id), Roster { ver: None, items: vec![] })
                    .into(),
            )
            .await;
            if let Ok(server_jid) = Jid::from_str(bound_jid.domain().as_str()) {
                send_stanza_bounded(
                    client,
                    Iq::from_get(disco_request_id(account_id), DiscoInfoQuery { node: None })
                        .with_to(server_jid)
                        .into(),
                )
                .await;
            }
            LoopAction::Keep
        }
        TokioXmppEvent::Disconnected(error) => {
            *session_live = false;
            send_event(
                event_sender,
                TransportEvent::Disconnected {
                    account_id,
                    recoverable: disconnected_is_recoverable(&error),
                    detail: Some(error.to_string()),
                    kind: classify_disconnect(&error),
                },
            );
            reconnect_decision(&TokioXmppEvent::Disconnected(error), auto_reconnect)
        }
        TokioXmppEvent::Stanza(Stanza::Message(message)) => {
            // Receipt payloads are independent of message bodies: a stanza may
            // carry only <received/>, only <request/>, or both a body and
            // receipt metadata. Parse each payload defensively so malformed
            // extensions cannot terminate the worker.
            for event in translate_incoming_receipts(account_id, &message) {
                send_event(event_sender, event);
            }
            if let Some(acknowledgement) = receipt_acknowledgement(account_id, &message) {
                // An acknowledgement is best effort. A transient send error
                // must not break polling of later stanzas.
                send_stanza_bounded(client, acknowledgement.into()).await;
            }
            if let Some(event) = translate_incoming_message(account_id, message) {
                send_event(event_sender, event);
            }
            LoopAction::Keep
        }
        TokioXmppEvent::Stanza(Stanza::Presence(presence)) => {
            if let Some(event) = translate_presence(account_id, presence) {
                send_event(event_sender, event);
            }
            LoopAction::Keep
        }
        TokioXmppEvent::Stanza(Stanza::Iq(iq)) => {
            handle_iq(account_id, client, iq, stream_capabilities, event_sender).await;
            LoopAction::Keep
        }
    }
}

async fn handle_iq(
    account_id: AccountId,
    client: &mut Client,
    iq: Iq,
    stream_capabilities: &BTreeSet<ProtocolCapability>,
    event_sender: &Sender<TransportEvent>,
) {
    match iq {
        Iq::Result { id, payload: Some(payload), .. } if id == roster_request_id(account_id) => {
            if payload.is("query", ns::ROSTER)
                && let Ok(roster) = Roster::try_from(payload)
            {
                emit_roster(account_id, roster, event_sender);
            }
        }
        Iq::Result { id, payload: Some(payload), .. } if id == disco_request_id(account_id) => {
            if payload.is("query", ns::DISCO_INFO)
                && let Ok(disco) = DiscoInfoResult::try_from(payload)
            {
                let mut capabilities = stream_capabilities.clone();
                capabilities.extend(capabilities_from_disco(&disco));
                send_event(
                    event_sender,
                    TransportEvent::CapabilitiesDiscovered { account_id, capabilities },
                );
            }
        }
        Iq::Set { from, id, payload, .. } if payload.is("query", ns::ROSTER) => {
            if let Ok(roster) = Roster::try_from(payload) {
                emit_roster(account_id, roster, event_sender);
            }
            let acknowledgement = Iq::Result { from: None, to: from, id, payload: None };
            send_stanza_bounded(client, acknowledgement.into()).await;
        }
        _ => {}
    }
}

async fn send_message(client: &mut Client, message: OutgoingMessage) -> Result<(), TransportError> {
    let stanza = build_message_stanza(message)?;
    client.send_stanza(stanza.into()).await.map(|_| ()).map_err(map_io_error)
}

fn build_message_stanza(message: OutgoingMessage) -> Result<Message, TransportError> {
    let recipient = Jid::from_str(&message.recipient).map_err(|_| {
        TransportError::ProtocolViolation(
            "invalid conversation JID supplied to XMPP transport".to_owned(),
        )
    })?;
    let mut stanza = match message.kind {
        ConversationKind::Direct => Message::chat(Some(recipient)),
        ConversationKind::MultiUserChat => Message::groupchat(Some(recipient)),
    };
    stanza.id = Some(XmppMessageId(format!("mindchat-{}", message.message_id)));
    stanza.bodies.insert(Lang::default(), message.body);
    if message.kind == ConversationKind::Direct {
        // XEP-0184 requests are meaningful for one-to-one messages only. MUC
        // messages deliberately remain unchanged.
        stanza.payloads.push(ReceiptRequest.into());
    }
    Ok(stanza)
}

fn translate_incoming_message(account_id: AccountId, message: Message) -> Option<TransportEvent> {
    if message.type_ != MessageType::Chat {
        return None;
    }
    let sender = message.from.as_ref()?.to_bare().to_string();
    let (_, body) = message.get_best_body_cloned(vec!["ru", "en"])?;
    (!body.trim().is_empty()).then_some(TransportEvent::IncomingText {
        account_id,
        kind: ConversationKind::Direct,
        address: sender.clone(),
        sender,
        body,
        received_at_epoch_ms: now_epoch_ms(),
    })
}

fn is_direct_receipt_message(message: &Message) -> bool {
    matches!(message.type_, MessageType::Chat | MessageType::Normal)
}

/// Converts valid XEP-0184 receipts in a direct message into normalized
/// account/sender-scoped delivery events. Unknown and malformed IDs are
/// intentionally ignored here; the domain applies the final ownership check
/// against its account and conversation projections.
fn translate_incoming_receipts(account_id: AccountId, message: &Message) -> Vec<TransportEvent> {
    if !is_direct_receipt_message(message) {
        return Vec::new();
    }
    let Some(sender) = message.from.as_ref().map(|jid| jid.to_bare().to_string()) else {
        return Vec::new();
    };
    message
        .payloads
        .iter()
        .filter(|payload| payload.is("received", ns::RECEIPTS))
        .filter_map(|payload| ReceiptReceived::try_from(payload.clone()).ok())
        .filter_map(|receipt| parse_receipt_message_id(&receipt.id))
        .map(|message_id| TransportEvent::DeliveryUpdated {
            account_id,
            sender: sender.clone(),
            message_id,
            state: crate::DeliveryState::Delivered,
        })
        .collect()
}

/// Builds the best-effort XEP-0184 acknowledgement for a valid direct message
/// carrying a `<request/>` payload. A message without a usable stanza ID
/// cannot be acknowledged and is ignored.
fn receipt_acknowledgement(account_id: AccountId, message: &Message) -> Option<Message> {
    if !is_direct_receipt_message(message)
        || !message.payloads.iter().any(|payload| {
            payload.is("request", ns::RECEIPTS) && ReceiptRequest::try_from(payload.clone()).is_ok()
        })
    {
        return None;
    }
    let sender = message.from.as_ref()?.to_bare();
    let stanza_id = message.id.as_ref()?.0.as_str();
    if stanza_id.is_empty() || stanza_id.chars().any(char::is_whitespace) {
        return None;
    }
    let mut acknowledgement = Message::chat(Some(Jid::from(sender.clone())));
    acknowledgement.id =
        Some(XmppMessageId(format!("mindchat-receipt-{account_id}-{}", now_epoch_ms())));
    acknowledgement.payloads.push(ReceiptReceived { id: stanza_id.to_owned() }.into());
    Some(acknowledgement)
}

fn parse_receipt_message_id(id: &str) -> Option<u64> {
    let suffix = id.strip_prefix("mindchat-")?;
    if suffix.is_empty() || !suffix.chars().all(|character| character.is_ascii_digit()) {
        return None;
    }
    suffix.parse().ok().filter(|message_id| *message_id != 0)
}

fn translate_presence(account_id: AccountId, presence: Presence) -> Option<TransportEvent> {
    let jid = presence.from?.to_bare().to_string();
    let contact_presence = match presence.type_ {
        PresenceType::None => match presence.show {
            Some(Show::Away | Show::Xa) => ContactPresence::Away,
            Some(Show::Dnd) => ContactPresence::DoNotDisturb,
            Some(Show::Chat) | None => ContactPresence::Online,
        },
        PresenceType::Unavailable => ContactPresence::Offline,
        _ => return None,
    };
    let status =
        presence.statuses.get("").cloned().or_else(|| presence.statuses.values().next().cloned());
    Some(TransportEvent::ContactPresenceUpdated {
        account_id,
        jid,
        presence: contact_presence,
        status,
    })
}

fn emit_roster(account_id: AccountId, roster: Roster, event_sender: &Sender<TransportEvent>) {
    for item in roster.items {
        let jid = item.jid.to_string();
        if item.subscription == Subscription::Remove {
            send_event(event_sender, TransportEvent::RosterContactRemoved { account_id, jid });
            continue;
        }
        let subscription = roster_subscription(&item);
        let display_name = item.name.unwrap_or_default();
        send_event(
            event_sender,
            TransportEvent::RosterContactUpsert { account_id, jid, display_name, subscription },
        );
    }
}

fn roster_subscription(item: &RosterItem) -> RosterSubscription {
    match (&item.subscription, &item.ask) {
        (Subscription::To, _) => RosterSubscription::Inbound,
        (Subscription::From, _) => RosterSubscription::Outbound,
        (Subscription::Both, _) => RosterSubscription::Mutual,
        (Subscription::None, Ask::Subscribe) => RosterSubscription::PendingOutbound,
        (Subscription::None | Subscription::Remove, _) => RosterSubscription::None,
    }
}

fn stream_capabilities_from_features(
    features: &xmpp_parsers::stream_features::StreamFeatures,
) -> BTreeSet<ProtocolCapability> {
    features
        .stream_management
        .is_some()
        .then_some(ProtocolCapability::StreamManagement)
        .into_iter()
        .collect()
}

fn capabilities_from_disco(disco: &DiscoInfoResult) -> BTreeSet<ProtocolCapability> {
    let mut capabilities = BTreeSet::new();
    for feature in &disco.features {
        let capability = match feature.as_str() {
            ns::MUC => Some(ProtocolCapability::MultiUserChat),
            ns::MAM => Some(ProtocolCapability::MessageArchiveManagement),
            ns::SM => Some(ProtocolCapability::StreamManagement),
            ns::HTTP_UPLOAD => Some(ProtocolCapability::HttpFileUpload),
            ns::PUSH => Some(ProtocolCapability::PushNotifications),
            ns::RECEIPTS => Some(ProtocolCapability::Receipts),
            ns::DISPLAYED_MARKERS => Some(ProtocolCapability::ChatMarkers),
            ns::CHATSTATES => Some(ProtocolCapability::ChatStates),
            ns::REACTIONS => Some(ProtocolCapability::MessageReactions),
            ns::MESSAGE_CORRECT => Some(ProtocolCapability::MessageCorrections),
            "urn:xmpp:reply:0" => Some(ProtocolCapability::MessageReplies),
            "urn:xmpp:omemo:1" | "urn:xmpp:omemo:2" | ns::LEGACY_OMEMO => {
                Some(ProtocolCapability::Omemo)
            }
            _ => None,
        };
        if let Some(capability) = capability {
            capabilities.insert(capability);
        }
    }
    capabilities
}

fn roster_request_id(account_id: AccountId) -> String {
    format!("mindchat-roster-{account_id}")
}

fn disco_request_id(account_id: AccountId) -> String {
    format!("mindchat-disco-{account_id}")
}

fn validate_endpoint_syntax(server: &str) -> Result<(), TransportError> {
    let server = server.trim();
    if server.is_empty() {
        return Err(TransportError::ProtocolViolation("XMPP server is empty".to_owned()));
    }
    if split_host_and_port(server)?.is_some() {
        // An explicit host:port or IP endpoint was validated by the parser.
    }
    Ok(())
}

/// Resolves a configured server to one concrete socket address.
///
/// Returns the first candidate of [`resolve_endpoints`]. For SRV-dependent
/// domains this may be the plain `host:5222` candidate rather than the SRV
/// target; this is a diagnostic helper, not the connect path.
#[cfg_attr(docsrs, doc(cfg(feature = "xmpp-transport")))]
pub async fn resolve_endpoint(server: &str) -> Result<SocketAddr, String> {
    resolve_endpoints(server)
        .await?
        .into_iter()
        .next()
        .map(|(address, _)| address)
        .ok_or_else(|| format!("cannot resolve XMPP server {server}"))
}

/// One SRV answer used by the pure RFC 2782 ordering decision.
#[derive(Clone, Debug, Eq, PartialEq)]
struct SrvCandidate {
    priority: u16,
    weight: u16,
    target: String,
    port: u16,
}

/// Orders SRV answers per RFC 2782: priority ascending, then weight
/// descending (higher weight is tried first). Deterministic and pure so the
/// connect path is testable without DNS.
#[must_use]
fn order_srv_candidates(mut records: Vec<SrvCandidate>) -> Vec<SrvCandidate> {
    records.sort_by(|a, b| a.priority.cmp(&b.priority).then(b.weight.cmp(&a.weight)));
    records
}

/// Resolves the ordered connection candidates for a server.
///
/// Each candidate is a `(socket address, use_direct_tls)` pair. Explicit
/// endpoints resolve to their addresses (StartTLS unless the port is the
/// direct-TLS port 5223); bare domains get StartTLS addresses on the default
/// port 5222, a direct-TLS fallback on port 5223, and finally the bounded
/// best-effort hickory SRV candidates appended after the plain ones (0.1.4
/// rationale). All addresses of a host are collected, capped at
/// [`MAX_ADDRESSES_PER_PORT`], so the connect path can race address families.
/// The whole resolution is capped by [`DNS_TOTAL_TIMEOUT`] so a wedged system
/// resolver cannot stall the connect phase forever.
async fn resolve_endpoints(server: &str) -> Result<Vec<(SocketAddr, bool)>, String> {
    let mut resolver_cache = None;
    resolve_endpoints_with(server, &mut resolver_cache).await
}

/// [`resolve_endpoints`] against a worker-cached [`TokioResolver`] (P0-3):
/// the resolver is built once from the system configuration and reused for
/// every SRV lookup of the worker instead of being rebuilt per resolution.
async fn resolve_endpoints_with(
    server: &str,
    resolver_cache: &mut Option<TokioResolver>,
) -> Result<Vec<(SocketAddr, bool)>, String> {
    let server = server.trim();
    tokio::time::timeout(DNS_TOTAL_TIMEOUT, async {
        let mut attempts = Vec::new();
        if let Some((host, port)) =
            split_host_and_port(server).map_err(|error| error.to_string())?
        {
            attempts.extend(
                resolve_host(&host, port).await?.into_iter().map(|address| (address, port == 5223)),
            );
            return Ok(attempts);
        }
        if let Ok(ip) = server.parse::<IpAddr>() {
            attempts.push((SocketAddr::new(ip, 5222), false));
            attempts.push((SocketAddr::new(ip, 5223), true));
            return Ok(attempts);
        }
        // Plain getaddrinfo candidates come first: they are reliable on
        // Android and usually succeed or fail fast. A failed single lookup is
        // skipped, not fatal, so SRV can still rescue SRV-dependent domains.
        if let Ok(addresses) = resolve_host(server, 5222).await {
            attempts.extend(addresses.into_iter().map(|address| (address, false)));
        }
        if let Ok(addresses) = resolve_host(server, 5223).await {
            attempts.extend(addresses.into_iter().map(|address| (address, true)));
        }
        // Bounded best-effort SRV fallback, never on the hot path.
        if let Ok(srv_attempts) =
            tokio::time::timeout(SRV_TIMEOUT, srv_endpoints(server, resolver_cache)).await
        {
            attempts.extend(srv_attempts);
        }
        if attempts.is_empty() {
            return Err(format!("cannot resolve XMPP server {server}"));
        }
        Ok(dedupe_candidates(attempts))
    })
    .await
    .map_err(|_| "DNS resolution timed out".to_owned())?
}

/// Lazily builds the worker's [`TokioResolver`] from the system
/// configuration. The resolver is kept for the worker's lifetime so repeated
/// SRV lookups do not re-read the system configuration.
fn cached_srv_resolver(cache: &mut Option<TokioResolver>) -> Option<&TokioResolver> {
    if cache.is_none() {
        let (_, mut options) = hickory_resolver::system_conf::read_system_conf().ok()?;
        options.ip_strategy = LookupIpStrategy::Ipv4AndIpv6;
        *cache = TokioResolver::builder_tokio().ok()?.with_options(options).build().ok();
    }
    cache.as_ref()
}

/// Attempts a `_xmpp-client._tcp` SRV lookup, orders all answers by priority
/// then weight (RFC 2782), and resolves every target to its addresses
/// (capped at [`MAX_ADDRESSES_PER_PORT`] each). Returns the ordered
/// `(address, use_direct_tls)` candidates; SRV targets are StartTLS unless
/// the record explicitly points at port 5223.
async fn srv_endpoints(
    domain: &str,
    resolver_cache: &mut Option<TokioResolver>,
) -> Vec<(SocketAddr, bool)> {
    let Some(resolver) = cached_srv_resolver(resolver_cache) else {
        return Vec::new();
    };
    let Ok(lookup) = resolver.srv_lookup(format!("_xmpp-client._tcp.{domain}.")).await else {
        return Vec::new();
    };
    let records = lookup
        .answers()
        .iter()
        .filter_map(|record| {
            let RData::SRV(ref srv) = record.data else { return None };
            let target = srv.target.to_ascii();
            (target != ".").then_some(SrvCandidate {
                priority: srv.priority,
                weight: srv.weight,
                target,
                port: srv.port,
            })
        })
        .collect::<Vec<_>>();
    let mut attempts = Vec::new();
    for record in order_srv_candidates(records) {
        if let Ok(addresses) = resolve_host(&record.target, record.port).await {
            attempts.extend(addresses.into_iter().map(|address| (address, record.port == 5223)));
        }
    }
    attempts
}

/// Resolves a hostname through the operating system resolver (getaddrinfo),
/// collecting up to [`MAX_ADDRESSES_PER_PORT`] addresses per port so the
/// connect path can race address families (Happy Eyeballs-lite) instead of
/// pinning to whatever address the system resolver listed first.
async fn resolve_host(host: &str, port: u16) -> Result<Vec<SocketAddr>, String> {
    let host = host.trim();
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(vec![SocketAddr::new(ip, port)]);
    }
    let addresses = tokio::net::lookup_host((host, port))
        .await
        .map_err(|error| format!("cannot resolve XMPP server {host}:{port}: {error}"))?
        .take(MAX_ADDRESSES_PER_PORT)
        .collect::<Vec<_>>();
    if addresses.is_empty() {
        return Err(format!("XMPP server {host}:{port} resolved to no addresses"));
    }
    Ok(addresses)
}

fn split_host_and_port(server: &str) -> Result<Option<(String, u16)>, TransportError> {
    if let Some(rest) = server.strip_prefix('[') {
        let Some((host, suffix)) = rest.split_once(']') else {
            return Err(TransportError::ProtocolViolation(
                "invalid bracketed XMPP server".to_owned(),
            ));
        };
        if suffix.is_empty() {
            return Ok(None);
        }
        let Some(port) = suffix.strip_prefix(':') else {
            return Err(TransportError::ProtocolViolation("invalid XMPP server port".to_owned()));
        };
        return parse_endpoint(host, port).map(Some);
    }
    if server.matches(':').count() == 1
        && let Some((host, port)) = server.rsplit_once(':')
    {
        return parse_endpoint(host, port).map(Some);
    }
    Ok(None)
}

fn parse_endpoint(host: &str, port: &str) -> Result<(String, u16), TransportError> {
    let host = host.trim();
    let port = port
        .parse::<u16>()
        .map_err(|_| TransportError::ProtocolViolation("invalid XMPP server port".to_owned()))?;
    if host.is_empty() || port == 0 {
        return Err(TransportError::ProtocolViolation("invalid XMPP server endpoint".to_owned()));
    }
    Ok((host.to_owned(), port))
}

fn install_rustls_provider() {
    let _ = tokio_xmpp::rustls::crypto::ring::default_provider().install_default();
}

fn send_event(event_sender: &Sender<TransportEvent>, event: TransportEvent) {
    let _ = event_sender.send(event);
}

fn now_epoch_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}

fn map_io_error(error: std::io::Error) -> TransportError {
    TransportError::ConnectionFailed(error.to_string())
}

fn map_xmpp_error(error: tokio_xmpp::Error) -> TransportError {
    match error {
        tokio_xmpp::Error::Auth(_) => TransportError::AuthenticationFailed,
        error => TransportError::ConnectionFailed(error.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use xmpp_parsers::minidom::Element;

    #[test]
    fn maps_discovered_features_without_claiming_unknown_capabilities() {
        let element: Element = "<query xmlns='http://jabber.org/protocol/disco#info'><feature var='http://jabber.org/protocol/muc'/><feature var='urn:xmpp:mam:2'/><feature var='urn:xmpp:receipts'/><feature var='urn:xmpp:omemo:2'/><feature var='urn:unknown:extension'/></query>"
            .parse()
            .expect("valid disco XML");
        let disco = DiscoInfoResult::try_from(element).expect("valid disco payload");
        assert_eq!(
            capabilities_from_disco(&disco),
            BTreeSet::from([
                ProtocolCapability::MultiUserChat,
                ProtocolCapability::MessageArchiveManagement,
                ProtocolCapability::Receipts,
                ProtocolCapability::Omemo,
            ])
        );
    }

    #[test]
    fn direct_strategy_has_no_connect_failure() {
        // ROADMAP 6.2 P2-2: Direct is the shipped behavior and must not be
        // rejected anywhere in the connect path, with or without a proxy
        // configured for the worker.
        assert_eq!(strategy_connect_failure(ConnectStrategy::Direct, false), None);
        assert_eq!(strategy_connect_failure(ConnectStrategy::Direct, true), None);
    }

    #[test]
    fn proxy_strategies_require_tunnel_configuration() {
        // A proxy strategy with a tunnel configuration passes the guard (the
        // handshake is implemented in 6.3). Without one it must fail
        // recoverably (never silently connect directly, never surface as a
        // credential failure), with a UI-safe detail naming the strategy.
        for strategy in [ConnectStrategy::HttpConnect, ConnectStrategy::Socks5] {
            assert_eq!(
                strategy_connect_failure(strategy, true),
                None,
                "a configured {strategy:?} tunnel must pass the guard"
            );
            let failure = strategy_connect_failure(strategy, false)
                .expect("a proxy strategy without configuration fails in the connect path");
            assert!(failure.recoverable, "{strategy:?} must be recoverable");
            assert!(
                failure.detail.contains("proxy configuration"),
                "detail should name the missing configuration: {}",
                failure.detail
            );
            assert!(failure.detail.contains(strategy.as_str()));
        }
    }

    #[test]
    fn connect_attempt_proxy_without_configuration_fails_recoverably_offline() {
        // The seam guard must reject a proxy strategy without a tunnel before
        // any network I/O, so the proxy connect path is testable fully
        // offline (no TCP, no DNS, no proxy).
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("current-thread runtime");
        let connection = WorkerConnection {
            account_id: 7,
            jid: "alice@example.org".parse().expect("valid jid"),
            password: "s3cret".to_owned(),
            server: "example.org".to_owned(),
            auto_reconnect: true,
            connect_strategy: ConnectStrategy::Socks5,
            proxy: None,
            proxy_password: None,
        };
        let Err(failure) = runtime.block_on(connect_attempt_proxy(&connection)) else {
            panic!("a proxy strategy without configuration must fail");
        };
        assert!(failure.recoverable, "the missing-configuration failure must stay recoverable");
        assert!(failure.detail.contains("socks5"));
    }

    #[test]
    fn maps_roster_subscription_and_removal_events() {
        let element: Element = "<query xmlns='jabber:iq:roster'><item jid='bob@example.org' name='Bob' subscription='to'/><item jid='old@example.org' subscription='remove'/><item jid='mila@example.org' ask='subscribe' subscription='none'/></query>"
            .parse()
            .expect("valid roster XML");
        let roster = Roster::try_from(element).expect("valid roster payload");
        let (sender, receiver) = mpsc::channel();
        emit_roster(7, roster, &sender);
        assert_eq!(
            receiver.try_iter().collect::<Vec<_>>(),
            vec![
                TransportEvent::RosterContactUpsert {
                    account_id: 7,
                    jid: "bob@example.org".to_owned(),
                    display_name: "Bob".to_owned(),
                    subscription: RosterSubscription::Inbound,
                },
                TransportEvent::RosterContactRemoved {
                    account_id: 7,
                    jid: "old@example.org".to_owned(),
                },
                TransportEvent::RosterContactUpsert {
                    account_id: 7,
                    jid: "mila@example.org".to_owned(),
                    display_name: String::new(),
                    subscription: RosterSubscription::PendingOutbound,
                },
            ]
        );
    }

    #[test]
    fn maps_available_and_unavailable_presence_to_bare_jid() {
        let available: Element = "<presence xmlns='jabber:client' from='bob@example.org/mobile'><show>dnd</show><status>Busy</status></presence>"
            .parse()
            .expect("valid presence XML");
        let unavailable: Element =
            "<presence xmlns='jabber:client' from='bob@example.org/mobile' type='unavailable'/>"
                .parse()
                .expect("valid presence XML");
        assert_eq!(
            translate_presence(3, Presence::try_from(available).expect("presence")),
            Some(TransportEvent::ContactPresenceUpdated {
                account_id: 3,
                jid: "bob@example.org".to_owned(),
                presence: ContactPresence::DoNotDisturb,
                status: Some("Busy".to_owned()),
            })
        );
        assert_eq!(
            translate_presence(3, Presence::try_from(unavailable).expect("presence")),
            Some(TransportEvent::ContactPresenceUpdated {
                account_id: 3,
                jid: "bob@example.org".to_owned(),
                presence: ContactPresence::Offline,
                status: None,
            })
        );
    }

    #[test]
    fn outgoing_direct_messages_request_receipts_but_groupchat_does_not() {
        let direct = build_message_stanza(OutgoingMessage {
            account_id: 7,
            conversation_id: 1,
            message_id: 42,
            kind: ConversationKind::Direct,
            recipient: "bob@example.org".to_owned(),
            body: "Hello".to_owned(),
            in_reply_to: None,
        })
        .expect("direct stanza");
        assert_eq!(direct.type_, MessageType::Chat);
        assert_eq!(direct.id.as_ref().map(|id| id.0.as_str()), Some("mindchat-42"));
        assert!(direct.payloads.iter().any(|payload| payload.is("request", ns::RECEIPTS)));

        let groupchat = build_message_stanza(OutgoingMessage {
            account_id: 7,
            conversation_id: 2,
            message_id: 43,
            kind: ConversationKind::MultiUserChat,
            recipient: "room@example.org".to_owned(),
            body: "Hello room".to_owned(),
            in_reply_to: None,
        })
        .expect("groupchat stanza");
        assert_eq!(groupchat.type_, MessageType::Groupchat);
        assert!(!groupchat.payloads.iter().any(|payload| payload.is("request", ns::RECEIPTS)));
    }

    #[test]
    fn parses_valid_receipts_and_ignores_malformed_or_non_chat_payloads() {
        let element: Element = "<message xmlns='jabber:client' from='bob@example.org/phone' type='chat'><received xmlns='urn:xmpp:receipts' id='mindchat-42'/><received xmlns='urn:xmpp:receipts' id='foreign-9'/><received xmlns='urn:xmpp:receipts'/></message>"
            .parse()
            .expect("valid message XML");
        assert_eq!(
            translate_incoming_receipts(7, &Message::try_from(element).expect("message")),
            vec![TransportEvent::DeliveryUpdated {
                account_id: 7,
                sender: "bob@example.org".to_owned(),
                message_id: 42,
                state: crate::DeliveryState::Delivered,
            }]
        );

        let groupchat: Element = "<message xmlns='jabber:client' from='room@example.org/nick' type='groupchat'><received xmlns='urn:xmpp:receipts' id='mindchat-42'/></message>"
            .parse()
            .expect("valid groupchat XML");
        assert!(
            translate_incoming_receipts(7, &Message::try_from(groupchat).expect("message"))
                .is_empty()
        );
    }

    #[test]
    fn acknowledges_valid_receipt_requests_only_when_stanza_id_is_usable() {
        let element: Element = "<message xmlns='jabber:client' from='bob@example.org/phone' id='peer-7' type='chat'><body>Hello</body><request xmlns='urn:xmpp:receipts'/></message>"
            .parse()
            .expect("valid message XML");
        let acknowledgement =
            receipt_acknowledgement(7, &Message::try_from(element).expect("message"))
                .expect("acknowledgement");
        assert_eq!(
            acknowledgement.to.as_ref().map(ToString::to_string),
            Some("bob@example.org".to_owned())
        );
        assert!(acknowledgement.payloads.iter().any(|payload| {
            payload.is("received", ns::RECEIPTS)
                && ReceiptReceived::try_from(payload.clone())
                    .is_ok_and(|receipt| receipt.id == "peer-7")
        }));

        let missing_id: Element = "<message xmlns='jabber:client' from='bob@example.org' type='chat'><request xmlns='urn:xmpp:receipts'/></message>"
            .parse()
            .expect("valid message XML");
        assert!(
            receipt_acknowledgement(7, &Message::try_from(missing_id).expect("message")).is_none()
        );
    }

    #[test]
    fn parse_receipt_message_id_requires_mindchat_numeric_id() {
        assert_eq!(parse_receipt_message_id("mindchat-42"), Some(42));
        assert_eq!(parse_receipt_message_id("mindchat-0"), None);
        assert_eq!(parse_receipt_message_id("foreign-42"), None);
        assert_eq!(parse_receipt_message_id("mindchat-4 2"), None);
    }

    #[test]
    fn maps_direct_message_stanzas_without_leaking_resource_addresses() {
        let element: Element = "<message xmlns='jabber:client' from='bob@example.org/phone' type='chat'><body>Hello</body></message>"
            .parse()
            .expect("valid message XML");
        let event = translate_incoming_message(9, Message::try_from(element).expect("message"))
            .expect("direct text event");
        assert!(matches!(
            event,
            TransportEvent::IncomingText {
                account_id: 9,
                kind: ConversationKind::Direct,
                ref address,
                ref sender,
                ref body,
                received_at_epoch_ms,
            } if address == "bob@example.org"
                && sender == "bob@example.org"
                && body == "Hello"
                && received_at_epoch_ms > 0
        ));
    }

    #[test]
    fn validates_endpoint_syntax() {
        assert!(validate_endpoint_syntax("example.org").is_ok());
        assert!(validate_endpoint_syntax("example.org:5223").is_ok());
        assert!(validate_endpoint_syntax("[::1]:5222").is_ok());
        assert!(validate_endpoint_syntax("example.org:not-a-port").is_err());
        assert!(validate_endpoint_syntax("").is_err());
    }

    #[test]
    fn dedupe_candidates_removes_duplicate_pairs_preserving_order() {
        let address = |port: u16| SocketAddr::new(IpAddr::from([127, 0, 0, 1]), port);
        let attempts = vec![
            (address(5222), false),
            (address(5223), true),
            (address(5222), false),
            (address(5222), true),
            (address(5222), false),
        ];
        assert_eq!(
            dedupe_candidates(attempts),
            vec![(address(5222), false), (address(5223), true), (address(5222), true)]
        );
    }

    #[test]
    fn candidate_ordering_places_plain_endpoints_before_srv() {
        // An explicit-IP server never touches DNS: exactly two candidates with
        // StartTLS on 5222 first and direct TLS on 5223 second.
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("current-thread runtime");
        let candidates = runtime
            .block_on(resolve_endpoints("127.0.0.1"))
            .expect("explicit IP resolves without DNS");
        assert_eq!(
            candidates,
            vec![
                (SocketAddr::new(IpAddr::from([127, 0, 0, 1]), 5222), false),
                (SocketAddr::new(IpAddr::from([127, 0, 0, 1]), 5223), true),
            ]
        );
    }

    #[test]
    fn is_auth_error_maps_only_auth_variants() {
        let auth = tokio_xmpp::Error::Auth(tokio_xmpp::error::AuthError::Fail(
            tokio_xmpp::parsers::sasl::DefinedCondition::NotAuthorized,
        ));
        assert!(is_auth_error(&auth));
        assert!(!is_auth_error(&tokio_xmpp::Error::Disconnected));
        assert!(!is_auth_error(&tokio_xmpp::Error::Protocol(
            tokio_xmpp::error::ProtocolError::NoTls
        )));
    }

    #[test]
    fn temporary_auth_failure_is_recoverable() {
        let temporary = tokio_xmpp::Error::Auth(tokio_xmpp::error::AuthError::Fail(
            tokio_xmpp::parsers::sasl::DefinedCondition::TemporaryAuthFailure,
        ));
        let failure = terminal_failure_for_event(&TokioXmppEvent::Disconnected(temporary))
            .expect("a disconnected first event is a terminal failure");
        assert!(failure.recoverable, "a transient server auth failure must be retryable");
    }

    #[test]
    fn terminal_failure_for_event_marks_auth_non_recoverable() {
        let auth = tokio_xmpp::Error::Auth(tokio_xmpp::error::AuthError::Fail(
            tokio_xmpp::parsers::sasl::DefinedCondition::NotAuthorized,
        ));
        let failure = terminal_failure_for_event(&TokioXmppEvent::Disconnected(auth))
            .expect("a disconnected first event is a terminal failure");
        assert!(!failure.recoverable, "rejected credentials must be non-recoverable");
        assert!(!failure.detail.is_empty(), "auth failures must carry precise detail");
        assert!(failure.detail.contains("authentication"));
        assert_eq!(
            failure.kind,
            DisconnectKind::AuthenticationFailed,
            "a rejected SASL exchange must classify as AuthenticationFailed"
        );
    }

    /// Marker error type for exercising the connector-error classification
    /// without a network.
    #[derive(Debug)]
    struct TestConnectorError;

    impl fmt::Display for TestConnectorError {
        fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
            formatter.write_str("test connector error")
        }
    }

    impl std::error::Error for TestConnectorError {}

    impl tokio_xmpp::connect::ServerConnectorError for TestConnectorError {}

    /// One typed failure per [`DisconnectKind`] variant, proving the
    /// classifier covers every bucket with a real error value.
    #[test]
    fn classify_disconnect_maps_typed_failures_to_every_kind() {
        use xmpp_parsers::stream_error::{
            DefinedCondition as StreamCondition, ReceivedStreamError, StreamError,
        };

        let utf8_error = String::from_utf8(vec![0xFF])
            .expect_err("an invalid UTF-8 sequence is an error")
            .utf8_error();
        let addr_error =
            "not-an-address".parse::<std::net::SocketAddr>().expect_err("invalid addr");

        let cases: Vec<(tokio_xmpp::Error, DisconnectKind)> = vec![
            // SASL rejection → AuthenticationFailed.
            (
                tokio_xmpp::Error::Auth(tokio_xmpp::error::AuthError::Fail(
                    tokio_xmpp::parsers::sasl::DefinedCondition::NotAuthorized,
                )),
                DisconnectKind::AuthenticationFailed,
            ),
            // TLS certificate validation failure → TlsVerificationFailed.
            (
                tokio_xmpp::Error::Tls(tokio_xmpp::connect::tls_common::TlsConnectorError::Tls(
                    tokio_xmpp::connect::tls_common::rustls::Error::InvalidCertificate(
                        tokio_xmpp::connect::tls_common::rustls::CertificateError::Expired,
                    ),
                )),
                DisconnectKind::TlsVerificationFailed,
            ),
            // Non-cert TLS failure (e.g. transient alert/general) → NetworkLost.
            (
                tokio_xmpp::Error::Tls(tokio_xmpp::connect::tls_common::TlsConnectorError::Tls(
                    tokio_xmpp::connect::tls_common::rustls::Error::General(
                        "handshake alert".to_owned(),
                    ),
                )),
                DisconnectKind::NetworkLost,
            ),
            // Server stream error (the server ends the stream with a
            // <stream:error>) → ServerRefused.
            (
                tokio_xmpp::Error::StreamError(ReceivedStreamError(StreamError::new(
                    StreamCondition::PolicyViolation,
                    "en",
                    "policy violation",
                ))),
                DisconnectKind::ServerRefused,
            ),
            // Timeout/EOF/suspended/connector/budget → NetworkLost.
            (
                tokio_xmpp::Error::Io(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    "read timed out",
                )),
                DisconnectKind::NetworkLost,
            ),
            (tokio_xmpp::Error::Disconnected, DisconnectKind::NetworkLost),
            (
                tokio_xmpp::Error::Connection(Box::new(TestConnectorError)),
                DisconnectKind::NetworkLost,
            ),
            (tokio_xmpp::Error::Addr(addr_error), DisconnectKind::NetworkLost),
            (tokio_xmpp::Error::ReconnectBudgetExhausted, DisconnectKind::NetworkLost),
            // Everything else → Unknown.
            (tokio_xmpp::Error::InvalidState, DisconnectKind::Unknown),
            (tokio_xmpp::Error::Fmt(fmt::Error), DisconnectKind::Unknown),
            (tokio_xmpp::Error::Utf8(utf8_error), DisconnectKind::Unknown),
            (
                tokio_xmpp::Error::Protocol(tokio_xmpp::error::ProtocolError::NoTls),
                DisconnectKind::Unknown,
            ),
        ];

        let covered: std::collections::BTreeSet<DisconnectKind> =
            cases.iter().map(|(_, kind)| *kind).collect();
        assert_eq!(
            covered,
            std::collections::BTreeSet::from([
                DisconnectKind::AuthenticationFailed,
                DisconnectKind::TlsVerificationFailed,
                DisconnectKind::ServerRefused,
                DisconnectKind::NetworkLost,
                DisconnectKind::Unknown,
            ]),
            "the classifier must cover every transport-derived bucket"
        );

        for (error, expected) in cases {
            assert_eq!(classify_disconnect(&error), expected, "classify_disconnect({error:?})");
        }
    }

    #[test]
    fn terminal_failure_for_event_marks_tls_verification_non_recoverable() {
        let tls_err =
            tokio_xmpp::Error::Tls(tokio_xmpp::connect::tls_common::TlsConnectorError::Tls(
                tokio_xmpp::connect::tls_common::rustls::Error::InvalidCertificate(
                    tokio_xmpp::connect::tls_common::rustls::CertificateError::UnknownIssuer,
                ),
            ));
        let failure = terminal_failure_for_event(&TokioXmppEvent::Disconnected(tls_err))
            .expect("a disconnected first event is a terminal failure");
        assert!(!failure.recoverable, "TLS certificate failure must be non-recoverable");
        assert_eq!(
            failure.kind,
            DisconnectKind::TlsVerificationFailed,
            "invalid cert must classify as TlsVerificationFailed"
        );
    }

    /// Cancelled is a coordinator-side classification (explicit user
    /// disconnect) and is not produced from a transport failure; the FFI
    /// mapping test in `ffi.rs` covers its variant. This test pins the
    /// domain-side enum completeness instead.
    #[test]
    fn disconnect_kind_variant_set_is_stable() {
        let all = [
            DisconnectKind::AuthenticationFailed,
            DisconnectKind::TlsVerificationFailed,
            DisconnectKind::ServerRefused,
            DisconnectKind::NetworkLost,
            DisconnectKind::Cancelled,
            DisconnectKind::Unknown,
        ];
        for (index, kind) in all.iter().enumerate() {
            assert!(
                all.iter()
                    .enumerate()
                    .all(|(other_index, other)| { index == other_index || kind != other }),
                "every disconnect kind must be distinct"
            );
        }
    }

    #[test]
    fn terminal_failure_for_event_ignores_non_terminal_first_events() {
        assert!(
            terminal_failure_for_event(&TokioXmppEvent::Online {
                bound_jid: "bob@example.org/resource".parse().expect("valid jid"),
                features: xmpp_parsers::stream_features::StreamFeatures::default(),
                resumed: false,
            })
            .is_none()
        );
    }

    #[test]
    fn disconnected_events_release_the_account_worker_slot() {
        let mut transport = TokioXmppTransport::new();
        let (command_sender, _command_receiver) = tokio::sync::mpsc::unbounded_channel();
        transport
            .workers
            .insert(42, WorkerHandle { command_sender, join: std::thread::spawn(|| {}) });
        transport
            .event_sender
            .send(TransportEvent::Disconnected {
                account_id: 42,
                recoverable: true,
                detail: None,
                kind: DisconnectKind::Unknown,
            })
            .expect("event receiver is alive");

        assert!(matches!(
            transport.next_event(),
            Ok(Some(TransportEvent::Disconnected {
                account_id: 42,
                recoverable: true,
                detail: None,
                kind: DisconnectKind::Unknown,
            }))
        ));
        assert!(transport.connected_accounts().is_empty());
    }

    #[test]
    fn disconnected_event_channel_synthesizes_terminal_event_for_tracked_worker() {
        let mut transport = TokioXmppTransport::new();
        let (command_sender, _command_receiver) = tokio::sync::mpsc::unbounded_channel();
        transport
            .workers
            .insert(42, WorkerHandle { command_sender, join: std::thread::spawn(|| {}) });
        // Drop the transport's own sender (swap in a dangling one whose
        // receiver is already gone) so the event receiver observes a
        // disconnected channel, exactly as if every worker thread had died
        // without emitting a terminal event.
        let (dangling_sender, _dangling_receiver) = mpsc::channel::<TransportEvent>();
        let _ = std::mem::replace(&mut transport.event_sender, dangling_sender);

        assert!(matches!(
            transport.next_event(),
            Ok(Some(TransportEvent::Disconnected {
                account_id: 42,
                recoverable: true,
                detail: Some(_),
                kind: DisconnectKind::Unknown,
            }))
        ));
        assert!(transport.connected_accounts().is_empty(), "the dead worker must be retired");
        assert!(matches!(transport.next_event(), Ok(None)), "no more workers to retire");
    }

    #[test]
    fn disconnected_event_channel_yields_one_terminal_event_per_tracked_worker() {
        let mut transport = TokioXmppTransport::new();
        for account_id in [42u64, 43u64] {
            let (command_sender, _command_receiver) = tokio::sync::mpsc::unbounded_channel();
            transport.workers.insert(
                account_id,
                WorkerHandle { command_sender, join: std::thread::spawn(|| {}) },
            );
        }
        let (dangling_sender, _dangling_receiver) = mpsc::channel::<TransportEvent>();
        let _ = std::mem::replace(&mut transport.event_sender, dangling_sender);

        // Each poll retires one tracked worker, so every account gets its own
        // synthesized terminal event instead of a single shared one. The
        // retirement order is a HashMap artifact and intentionally unspecified.
        let mut synthesized = Vec::new();
        while let Ok(Some(TransportEvent::Disconnected { account_id, .. })) = transport.next_event()
        {
            synthesized.push(account_id);
        }
        synthesized.sort_unstable();
        assert_eq!(synthesized, vec![42, 43]);
        assert!(transport.connected_accounts().is_empty());
    }

    #[test]
    fn mid_session_disconnect_recoverability_matches_connect_path() {
        let temporary = tokio_xmpp::Error::Auth(tokio_xmpp::error::AuthError::Fail(
            tokio_xmpp::parsers::sasl::DefinedCondition::TemporaryAuthFailure,
        ));
        assert!(
            disconnected_is_recoverable(&temporary),
            "a transient server auth failure mid-session must keep the account retryable"
        );

        let rejected = tokio_xmpp::Error::Auth(tokio_xmpp::error::AuthError::Fail(
            tokio_xmpp::parsers::sasl::DefinedCondition::NotAuthorized,
        ));
        assert!(
            !disconnected_is_recoverable(&rejected),
            "rejected credentials mid-session remain non-recoverable"
        );

        let network_loss = tokio_xmpp::Error::Io(std::io::Error::new(
            std::io::ErrorKind::ConnectionReset,
            "XMPP connection suspended",
        ));
        assert!(
            disconnected_is_recoverable(&network_loss),
            "a network loss mid-session must be recoverable"
        );
    }

    #[test]
    fn registration_queries_build_and_parse_with_xmpp_parsers() {
        // The fields request serializes to <iq type='get'><query
        // xmlns='jabber:iq:register'/></iq> and round-trips.
        let get_iq = Iq::from_get("mindchat-register-get-1".to_owned(), FieldsQuery);
        let element: Element = get_iq.into();
        let parsed = Iq::try_from(element).expect("fields request parses");
        assert_eq!(parsed.id(), "mindchat-register-get-1");
        assert!(matches!(parsed, Iq::Get { payload, .. } if payload.is("query", ns::REGISTER)));

        // A legacy fields response (username + password) parses into
        // LegacyQuery and can be resubmitted as a set with credentials.
        let response: Element = "<iq xmlns='jabber:client' type='result' id='mindchat-register-get-1'><query xmlns='jabber:iq:register'><username/><password/></query></iq>"
            .parse()
            .expect("valid fields response XML");
        let Iq::Result { payload: Some(payload), .. } =
            Iq::try_from(response).expect("fields response parses")
        else {
            panic!("expected a result IQ");
        };
        let mut query = LegacyQuery::try_from(payload).expect("legacy fields");
        assert!(query.username.is_some());
        assert!(query.password.is_some());
        query.username = Some("alice".to_owned());
        query.password = Some("s3cret".to_owned());
        let set_iq = Iq::from_set("mindchat-register-set-1".to_owned(), query);
        let element: Element = set_iq.into();
        let parsed = Iq::try_from(element).expect("submission parses");
        assert_eq!(parsed.id(), "mindchat-register-set-1");
        assert!(matches!(parsed, Iq::Set { payload, .. } if payload.is("query", ns::REGISTER)));

        // A data-form response (xdata/captcha) parses into FormQuery, the
        // signal for "server requires additional registration fields".
        let form: Element = "<iq xmlns='jabber:client' type='result' id='form'><query xmlns='jabber:iq:register'><x xmlns='jabber:x:data' type='form'/></query></iq>"
            .parse()
            .expect("valid form response XML");
        let Iq::Result { payload: Some(payload), .. } =
            Iq::try_from(form).expect("form response parses")
        else {
            panic!("expected a result IQ");
        };
        assert!(FormQuery::try_from(payload).is_ok());
    }

    #[test]
    fn registration_is_gated_on_advertised_stream_features() {
        // A server that does not advertise jabber:iq:register in its stream
        // features must be refused with a UI-safe detail before any exchange.
        let features = xmpp_parsers::stream_features::StreamFeatures::default();
        assert_eq!(
            registration_refusal(&features).as_deref(),
            Some("this server does not support in-band registration")
        );

        let features = xmpp_parsers::stream_features::StreamFeatures {
            in_band_registration: true,
            ..Default::default()
        };
        assert_eq!(registration_refusal(&features), None);
    }

    #[test]
    fn registration_error_conditions_map_to_ui_safe_details() {
        let error = |condition: DefinedCondition| {
            StanzaError::new(xmpp_parsers::stanza_error::ErrorType::Cancel, condition, "en", "")
        };
        assert_eq!(
            registration_error_detail(&error(DefinedCondition::Conflict)),
            "this username is already registered"
        );
        assert_eq!(
            registration_error_detail(&error(DefinedCondition::NotAcceptable)),
            "the server did not accept the registration details"
        );
        assert_eq!(
            registration_error_detail(&error(DefinedCondition::NotAllowed)),
            "this server does not allow registration"
        );
        assert_eq!(
            registration_error_detail(&error(DefinedCondition::ServiceUnavailable)),
            "registration is temporarily unavailable"
        );
        assert_eq!(
            registration_error_detail(&error(DefinedCondition::FeatureNotImplemented)),
            "this server does not support in-band registration"
        );
        assert_eq!(
            registration_error_detail(&error(DefinedCondition::InternalServerError)),
            "the server refused the registration"
        );
    }

    #[test]
    fn backoff_sequence_is_jittered_deterministic_and_budgeted() {
        // Pure generator from the vendored stanzastream: same seed reproduces
        // the same sequence, every sleep lies in [base/2, base], the base
        // doubles 1s -> 30s cap, and a small budget terminates the cycle.
        use tokio_xmpp::stanzastream::backoff::{Backoff, MAX_BACKOFF_BASE, RECONNECT_BUDGET};
        let mut a = Backoff::new(42, RECONNECT_BUDGET);
        let mut b = Backoff::new(42, RECONNECT_BUDGET);
        let mut expected_base = Duration::from_secs(1);
        for _ in 0..10 {
            let sleep_a = a.next_sleep().expect("budget must not run out");
            let sleep_b = b.next_sleep().expect("budget must not run out");
            assert_eq!(sleep_a, sleep_b, "same seed must reproduce the sequence");
            let half = expected_base / 2;
            assert!(sleep_a >= half, "sleep {sleep_a:?} below base/2 {half:?}");
            assert!(sleep_a <= expected_base, "sleep {sleep_a:?} above base {expected_base:?}");
            let doubled = expected_base * 2;
            expected_base = if doubled > MAX_BACKOFF_BASE { MAX_BACKOFF_BASE } else { doubled };
        }
        assert_eq!(expected_base, MAX_BACKOFF_BASE, "base must cap at 30s");

        // A tiny budget must terminate instead of overflowing.
        let mut tiny = Backoff::new(7, Duration::from_secs(2));
        let mut emitted = 0;
        while tiny.next_sleep().is_some() {
            emitted += 1;
            assert!(emitted < 100, "budgeted backoff must terminate");
        }
        assert!(emitted >= 1, "a 2s budget covers at least one sleep");
    }

    #[test]
    fn reconnect_decision_keeps_polling_only_for_recoverable_loss_with_auto_reconnect() {
        let io_loss = |kind: std::io::ErrorKind| {
            TokioXmppEvent::Disconnected(tokio_xmpp::Error::Io(std::io::Error::new(
                kind,
                "network loss",
            )))
        };
        let auth_failure = TokioXmppEvent::Disconnected(tokio_xmpp::Error::Auth(
            tokio_xmpp::error::AuthError::Fail(
                tokio_xmpp::parsers::sasl::DefinedCondition::NotAuthorized,
            ),
        ));
        let budget_exhausted =
            TokioXmppEvent::Disconnected(tokio_xmpp::Error::ReconnectBudgetExhausted);

        // A recoverable loss keeps the worker polling only when auto-reconnect
        // is enabled; otherwise the worker exits exactly like 0.1.7.
        assert_eq!(
            reconnect_decision(&io_loss(std::io::ErrorKind::ConnectionReset), true),
            LoopAction::Keep
        );
        assert_eq!(
            reconnect_decision(&io_loss(std::io::ErrorKind::ConnectionReset), false),
            LoopAction::Stop
        );

        // Rejected credentials are terminal regardless of the toggle.
        assert_eq!(reconnect_decision(&auth_failure, true), LoopAction::Stop);

        // Budget exhaustion ends this worker even though the account stays
        // recoverable for a later manual connect.
        assert_eq!(reconnect_decision(&budget_exhausted, true), LoopAction::Stop);

        // Non-disconnect events always keep polling.
        let online = TokioXmppEvent::Online {
            bound_jid: "bob@example.org/resource".parse().expect("valid jid"),
            features: xmpp_parsers::stream_features::StreamFeatures::default(),
            resumed: false,
        };
        assert_eq!(reconnect_decision(&online, false), LoopAction::Keep);
    }

    #[test]
    fn inbound_watchdog_is_stale_only_past_the_threshold() {
        let threshold = Duration::from_secs(180);
        assert!(!is_inbound_stale(Duration::ZERO, threshold));
        assert!(!is_inbound_stale(threshold, threshold), "exactly at the threshold is not stale");
        assert!(is_inbound_stale(threshold + Duration::from_millis(1), threshold));
        assert!(is_inbound_stale(Duration::from_secs(300), threshold));
    }

    #[test]
    fn srv_candidates_order_by_priority_then_weight() {
        let candidate = |priority: u16, weight: u16, target: &str, port: u16| SrvCandidate {
            priority,
            weight,
            target: target.to_owned(),
            port,
        };
        let ordered = order_srv_candidates(vec![
            candidate(10, 1, "b.example.org", 5222),
            candidate(5, 50, "a.example.org", 5222),
            candidate(10, 100, "c.example.org", 5222),
            candidate(5, 90, "d.example.org", 5222),
        ]);
        // Priority ascending first: all priority-5 targets beat priority-10.
        // Within the same priority, higher weight is tried first (RFC 2782).
        assert_eq!(
            ordered,
            vec![
                candidate(5, 90, "d.example.org", 5222),
                candidate(5, 50, "a.example.org", 5222),
                candidate(10, 100, "c.example.org", 5222),
                candidate(10, 1, "b.example.org", 5222),
            ]
        );
    }

    #[test]
    fn multi_address_candidates_group_for_happy_eyeballs() {
        let v4 = |port: u16| SocketAddr::new(IpAddr::from([192, 0, 2, 1]), port);
        let v6 = |port: u16| SocketAddr::new(IpAddr::from([0x2001, 0xdb8, 0, 0, 0, 0, 0, 1]), port);
        // Two families on 5222 and one on 5223: consecutive same-(port, TLS)
        // addresses must merge into race groups, preserving order.
        let attempts =
            vec![(v4(5222), false), (v6(5222), false), (v4(5222), false), (v4(5223), true)];
        assert_eq!(
            group_attempts(attempts),
            vec![
                AttemptGroup {
                    port: 5222,
                    direct_tls: false,
                    addresses: vec![v4(5222), v6(5222), v4(5222)],
                },
                AttemptGroup { port: 5223, direct_tls: true, addresses: vec![v4(5223)] },
            ]
        );
    }

    #[test]
    fn connect_attempt_keeps_auth_failures_non_recoverable() {
        // Regression guard: the connect phase must surface rejected
        // credentials as non-recoverable (Failed in the UI) instead of
        // degrading them to a generic recoverable connection failure.
        let auth = ConnectFailure {
            detail: "authentication error".to_owned(),
            recoverable: false,
            kind: DisconnectKind::AuthenticationFailed,
        };
        let network = ConnectFailure {
            detail: "connection to x timed out".to_owned(),
            recoverable: true,
            kind: DisconnectKind::NetworkLost,
        };
        assert!(
            matches!(terminal_if_auth(auth.clone()), Some(failure) if !failure.recoverable),
            "an auth failure must be returned as terminal"
        );
        assert!(
            terminal_if_auth(network).is_none(),
            "a recoverable failure must not short-circuit the candidate list"
        );
    }

    #[test]
    fn ipv6_endpoint_resolves_without_dns() {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("current-thread runtime");
        // resolve_host on an IPv6 literal returns exactly that address.
        assert_eq!(
            runtime.block_on(resolve_host("::1", 5222)).expect("literal resolves"),
            vec![SocketAddr::new(IpAddr::from([0, 0, 0, 0, 0, 0, 0, 1]), 5222)]
        );
        // A bracketed endpoint resolves to StartTLS on the requested port.
        let candidates = runtime
            .block_on(resolve_endpoints("[::1]:5222"))
            .expect("bracketed IPv6 resolves without DNS");
        assert_eq!(
            candidates,
            vec![(SocketAddr::new(IpAddr::from([0, 0, 0, 0, 0, 0, 0, 1]), 5222), false)]
        );
    }
}
