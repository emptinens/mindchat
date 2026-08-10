//! Concrete Tokio/XMPP transport implementation.
//!
//! This module is deliberately kept behind [`XmppTransport`](crate::XmppTransport).
//! All `tokio_xmpp` and `xmpp_parsers` values stay in this worker, while the
//! rest of the crate receives normalized, account-scoped transport events.

use crate::{
    AccountId, ConnectionRequest, ContactPresence, ConversationKind, OutgoingMessage,
    ProtocolCapability, RosterSubscription, TransportError, TransportEvent, XmppTransport,
};
use futures::StreamExt;
use hickory_resolver::{TokioResolver, config::LookupIpStrategy, proto::rr::RData};
use std::collections::{BTreeSet, HashMap, HashSet};
use std::fmt;
use std::net::{IpAddr, SocketAddr};
use std::str::FromStr;
use std::sync::mpsc::{self, Receiver, Sender, TryRecvError};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::runtime::Builder as RuntimeBuilder;
use tokio::sync::mpsc::{UnboundedReceiver, UnboundedSender};
use tokio_xmpp::{
    Client, Event as TokioXmppEvent, Stanza,
    connect::DnsConfig,
    parsers::{
        disco::{DiscoInfoQuery, DiscoInfoResult},
        iq::Iq,
        jid::{BareJid, Jid},
        message::{Id as XmppMessageId, Lang, Message, MessageType},
        ns,
        presence::{Presence, Show, Type as PresenceType},
        roster::{Ask, Item as RosterItem, Roster, Subscription},
    },
    xmlstream::Timeouts,
};

const SEND_TIMEOUT: Duration = Duration::from_secs(15);
const DISCONNECT_TIMEOUT: Duration = Duration::from_secs(5);
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
                        runtime.block_on(run_worker(connection, command_receiver, event_sender));
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
            Err(TryRecvError::Empty | TryRecvError::Disconnected) => Ok(None),
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
        Ok(Self {
            account_id: request.account_id,
            jid,
            password: request.password.into_inner(),
            server,
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
    // The entire connect phase (DNS plus every candidate handshake) runs under
    // one hard deadline so a terminal event is guaranteed within
    // CONNECT_PHASE_TIMEOUT, and it is selectable against the command channel
    // so a user disconnect during "connecting" is honored immediately.
    let phase = async {
        let attempts = resolve_endpoints(&connection.server)
            .await
            .map_err(|detail| ConnectFailure { detail, recoverable: true })?;
        connect_attempt(&connection, attempts).await
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
                },
            );
            return;
        }
    };
    let mut stream_capabilities = BTreeSet::new();
    if let Some(event) = pending_event {
        let keep_running = handle_client_event(
            account_id,
            &mut client,
            event,
            &mut stream_capabilities,
            &event_sender,
        )
        .await;
        if !keep_running {
            return;
        }
    }

    loop {
        tokio::select! {
            command = command_receiver.recv() => match command {
                Some(WorkerCommand::Send { message, response_sender }) => {
                    let _ = response_sender.send(send_message(&mut client, message).await);
                }
                Some(WorkerCommand::Disconnect { response_sender }) => {
                    let result = client.send_end().await.map_err(map_xmpp_error);
                    let _ = response_sender.send(result);
                    break;
                }
                None => {
                    let _ = client.send_end().await;
                    break;
                }
            },
            event = client.next() => if let Some(event) = event {
                    let keep_running = handle_client_event(
                        account_id,
                        &mut client,
                        event,
                        &mut stream_capabilities,
                        &event_sender,
                    ).await;
                    if !keep_running {
                        break;
                    }
            } else {
                send_event(
                    &event_sender,
                    TransportEvent::Disconnected { account_id, recoverable: false, detail: None },
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
struct ConnectFailure {
    detail: String,
    /// False when the server rejected the credentials, so the account is
    /// projected as `Failed` instead of silently returning to `Offline`.
    recoverable: bool,
}

/// Attempts each candidate endpoint in order and returns the first client
/// whose handshake produces a usable event within
/// [`CONNECT_ATTEMPT_TIMEOUT`].
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
    let mut last_error = String::from("no connection candidates");
    for (endpoint, direct_tls) in attempts {
        let mut client = if direct_tls {
            Client::new_direct_tls_with_config(
                connection.jid.clone(),
                connection.password.clone(),
                DnsConfig::addr(&endpoint.to_string()),
                Timeouts::default(),
            )
        } else {
            Client::new_starttls(
                connection.jid.clone(),
                connection.password.clone(),
                DnsConfig::addr(&endpoint.to_string()),
                Timeouts::default(),
            )
        };
        match tokio::time::timeout(CONNECT_ATTEMPT_TIMEOUT, client.next()).await {
            Ok(Some(event)) => {
                if let Some(failure) = terminal_failure_for_event(&event) {
                    return Err(failure);
                }
                return Ok(ReadyClient { client, pending_event: Some(event) });
            }
            Ok(None) => {
                last_error = format!("connection to {endpoint} closed during handshake");
            }
            Err(_) => {
                last_error = format!(
                    "connection to {endpoint} timed out after {} seconds",
                    CONNECT_ATTEMPT_TIMEOUT.as_secs()
                );
            }
        }
    }
    Err(ConnectFailure { detail: last_error, recoverable: true })
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

/// Maps the first client event to a terminal connect failure, if it is one.
///
/// The only terminal first event is `Disconnected`; it carries the precise
/// reason (for example a rejected SASL exchange) and its recoverability. This
/// is the single place that replaces the old authentication preflight's
/// detection of rejected credentials.
#[must_use]
fn terminal_failure_for_event(event: &TokioXmppEvent) -> Option<ConnectFailure> {
    match event {
        TokioXmppEvent::Disconnected(error) => {
            Some(ConnectFailure { detail: error.to_string(), recoverable: !is_auth_error(error) })
        }
        _ => None,
    }
}

#[allow(clippy::too_many_lines)]
async fn handle_client_event(
    account_id: AccountId,
    client: &mut Client,
    event: TokioXmppEvent,
    stream_capabilities: &mut BTreeSet<ProtocolCapability>,
    event_sender: &Sender<TransportEvent>,
) -> bool {
    match event {
        TokioXmppEvent::Online { bound_jid, features, .. } => {
            *stream_capabilities = stream_capabilities_from_features(&features);
            send_event(
                event_sender,
                TransportEvent::Connected { account_id, capabilities: stream_capabilities.clone() },
            );
            let _ = client.send_stanza(Presence::available().into()).await;
            let _ = client
                .send_stanza(
                    Iq::from_get(
                        roster_request_id(account_id),
                        Roster { ver: None, items: vec![] },
                    )
                    .into(),
                )
                .await;
            if let Ok(server_jid) = Jid::from_str(bound_jid.domain().as_str()) {
                let _ = client
                    .send_stanza(
                        Iq::from_get(disco_request_id(account_id), DiscoInfoQuery { node: None })
                            .with_to(server_jid)
                            .into(),
                    )
                    .await;
            }
            true
        }
        TokioXmppEvent::Disconnected(error) => {
            let recoverable = !matches!(error, tokio_xmpp::Error::Auth(_));
            send_event(
                event_sender,
                TransportEvent::Disconnected {
                    account_id,
                    recoverable,
                    detail: Some(error.to_string()),
                },
            );
            false
        }
        TokioXmppEvent::Stanza(Stanza::Message(message)) => {
            if let Some(event) = translate_incoming_message(account_id, message) {
                send_event(event_sender, event);
            }
            true
        }
        TokioXmppEvent::Stanza(Stanza::Presence(presence)) => {
            if let Some(event) = translate_presence(account_id, presence) {
                send_event(event_sender, event);
            }
            true
        }
        TokioXmppEvent::Stanza(Stanza::Iq(iq)) => {
            handle_iq(account_id, client, iq, stream_capabilities, event_sender).await;
            true
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
            let _ = client.send_stanza(acknowledgement.into()).await;
        }
        _ => {}
    }
}

async fn send_message(client: &mut Client, message: OutgoingMessage) -> Result<(), TransportError> {
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
    client.send_stanza(stanza.into()).await.map(|_| ()).map_err(map_io_error)
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

/// Resolves the ordered connection candidates for a server.
///
/// Each candidate is a `(socket address, use_direct_tls)` pair. Explicit
/// endpoints resolve to a single candidate (StartTLS unless the port is the
/// direct-TLS port 5223); bare domains get a StartTLS candidate on the default
/// port 5222, a direct-TLS fallback on port 5223, and finally a bounded
/// best-effort hickory SRV candidate appended after the plain ones. The whole
/// resolution is capped by [`DNS_TOTAL_TIMEOUT`] so a wedged system resolver
/// cannot stall the connect phase forever.
async fn resolve_endpoints(server: &str) -> Result<Vec<(SocketAddr, bool)>, String> {
    let server = server.trim();
    tokio::time::timeout(DNS_TOTAL_TIMEOUT, async {
        let mut attempts = Vec::new();
        if let Some((host, port)) =
            split_host_and_port(server).map_err(|error| error.to_string())?
        {
            attempts.push((resolve_host(&host, port).await?, port == 5223));
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
        if let Ok(endpoint) = resolve_host(server, 5222).await {
            attempts.push((endpoint, false));
        }
        if let Ok(endpoint) = resolve_host(server, 5223).await {
            attempts.push((endpoint, true));
        }
        // Bounded best-effort SRV fallback, never on the hot path.
        if let Ok(Some(endpoint)) = tokio::time::timeout(SRV_TIMEOUT, srv_endpoint(server)).await {
            attempts.push((endpoint, false));
        }
        if attempts.is_empty() {
            return Err(format!("cannot resolve XMPP server {server}"));
        }
        Ok(dedupe_candidates(attempts))
    })
    .await
    .map_err(|_| "DNS resolution timed out".to_owned())?
}

/// Attempts a `_xmpp-client._tcp` SRV lookup, then resolves the best target.
async fn srv_endpoint(domain: &str) -> Option<SocketAddr> {
    let Ok((_, mut options)) = hickory_resolver::system_conf::read_system_conf() else {
        return None;
    };
    options.ip_strategy = LookupIpStrategy::Ipv4AndIpv6;
    let Ok(resolver) = TokioResolver::builder_tokio().ok()?.with_options(options).build() else {
        return None;
    };
    let Ok(lookup) = resolver.srv_lookup(format!("_xmpp-client._tcp.{domain}.")).await else {
        return None;
    };
    for record in lookup.answers() {
        let RData::SRV(ref srv) = record.data else { continue };
        let target = srv.target.to_ascii();
        if target != "."
            && let Ok(endpoint) = resolve_host(&target, srv.port).await
        {
            return Some(endpoint);
        }
    }
    None
}

/// Resolves a hostname through the operating system resolver (getaddrinfo).
async fn resolve_host(host: &str, port: u16) -> Result<SocketAddr, String> {
    let host = host.trim();
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(SocketAddr::new(ip, port));
    }
    tokio::net::lookup_host((host, port))
        .await
        .map_err(|error| format!("cannot resolve XMPP server {host}:{port}: {error}"))?
        .next()
        .ok_or_else(|| format!("XMPP server {host}:{port} resolved to no addresses"))
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
            .send(TransportEvent::Disconnected { account_id: 42, recoverable: true, detail: None })
            .expect("event receiver is alive");

        assert!(matches!(
            transport.next_event(),
            Ok(Some(TransportEvent::Disconnected {
                account_id: 42,
                recoverable: true,
                detail: None,
            }))
        ));
        assert!(transport.connected_accounts().is_empty());
    }
}
