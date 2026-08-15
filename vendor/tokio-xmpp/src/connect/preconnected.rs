// Copyright (c) 2026 MindChat project
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

//! `PreconnectedServerConnector` wraps an already-established TCP stream so
//! TLS runs unchanged against the real hostname.
//!
//! MindChat 0.1.8 (ROADMAP 6.2 P2-2): a proxy connect strategy (HTTP CONNECT
//! or SOCKS5, implemented in 6.3) opens the TCP connection itself and runs
//! the proxy handshake, then hands the established stream to
//! [`PreconnectedServerConnector::new`]. The connector takes over from there:
//! direct TLS against the real XMPP hostname (never the proxy's), then the
//! usual stream initiation, exactly like [`DirectTlsServerConnector`] but
//! without resolving or dialing anything itself.
//!
//! The stream is single-use: `connect` consumes it. Reconnecting through a
//! proxy therefore re-runs the proxy handshake and builds a fresh connector,
//! which is why the ServerConnector trait's `&self` connect is backed by
//! interior mutability rather than requiring `S: Clone`.

use alloc::borrow::Cow;
use sasl::common::ChannelBinding;
use std::io;
use std::sync::{Arc, Mutex, PoisonError};
use tokio::io::{AsyncRead, AsyncWrite, BufStream};
use xmpp_parsers::jid::Jid;

use crate::{
    connect::{
        tls_common::{establish_tls_connection, TlsConnectorError, TlsStream},
        ServerConnector,
    },
    error::Error,
    xmlstream::{initiate_stream, PendingFeaturesRecv, StreamHeader, Timeouts},
};

/// Connector over a stream whose TCP connection is already established.
///
/// The hostname is the real XMPP server the TLS certificate must match;
/// proxy credentials and endpoints never reach this type.
#[derive(Debug)]
pub struct PreconnectedServerConnector<S> {
    stream: Arc<Mutex<Option<S>>>,
    hostname: String,
}

impl<S> Clone for PreconnectedServerConnector<S> {
    fn clone(&self) -> Self {
        Self { stream: Arc::clone(&self.stream), hostname: self.hostname.clone() }
    }
}

impl<S> PreconnectedServerConnector<S> {
    /// Wraps an established stream and the real hostname for TLS verification.
    pub fn new(stream: S, hostname: impl Into<String>) -> Self {
        Self { stream: Arc::new(Mutex::new(Some(stream))), hostname: hostname.into() }
    }
}

impl<S> ServerConnector for PreconnectedServerConnector<S>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static + std::fmt::Debug,
{
    type Stream = BufStream<TlsStream<S>>;

    async fn connect(
        &self,
        jid: &Jid,
        ns: &'static str,
        timeouts: Timeouts,
    ) -> Result<(PendingFeaturesRecv<Self::Stream>, ChannelBinding), Error> {
        // The mutex guard is released before any await: the stream is taken
        // out synchronously and this connector is single-use by contract.
        let stream = self
            .stream
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .take()
            .ok_or_else(|| {
                Error::Io(io::Error::new(
                    io::ErrorKind::NotConnected,
                    "preconnected stream was already consumed; re-run the proxy handshake",
                ))
            })?;

        // TLS runs against the real hostname, not the proxy.
        let (tls_stream, channel_binding) =
            establish_tls_connection(stream, &self.hostname).await?;

        // Establish the XMPP stream over TLS, addressed to the XMPP domain.
        Ok((
            initiate_stream(
                BufStream::new(tls_stream),
                ns,
                StreamHeader {
                    to: Some(Cow::Borrowed(jid.domain().as_str())),
                    from: Some(Cow::Borrowed(jid.to_bare().as_str())),
                    id: None,
                },
                timeouts,
            )
            .await?,
            channel_binding,
        ))
    }
}

/// Preconnected ServerConnector Error - alias to the common TLS error type.
pub type PreconnectedError = TlsConnectorError;
