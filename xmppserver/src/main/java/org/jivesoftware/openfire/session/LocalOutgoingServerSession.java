/*
 * Copyright (C) 2005-2008 Jive Software, 2022-2023 Ignite Realtime Foundation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.openfire.session;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.XMPPPacketReader;
import org.jivesoftware.openfire.*;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.event.ServerSessionEventDispatcher;
import org.jivesoftware.openfire.net.MXParser;
import org.jivesoftware.openfire.net.SASLAuthentication;
import org.jivesoftware.openfire.net.SocketConnection;
import org.jivesoftware.openfire.net.SocketUtil;
import org.jivesoftware.openfire.server.OutgoingServerSocketReader;
import org.jivesoftware.openfire.server.RemoteServerManager;
import org.jivesoftware.openfire.server.ServerDialback;
import org.jivesoftware.openfire.spi.BasicStreamIDFactory;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.StringUtils;
import org.jivesoftware.util.TaskEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmpp.packet.*;

import javax.annotation.Nonnull;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Server-to-server communication is done using two TCP connections between the servers. One
 * connection is used for sending packets while the other connection is used for receiving packets.
 * The {@code OutgoingServerSession} represents the connection to a remote server that will only
 * be used for sending packets.<p>
 *
 * Currently only the Server Dialback method is being used for authenticating with the remote
 * server. Use {@link #authenticateDomain(DomainPair)} to create a new connection to a remote
 * server that will be used for sending packets to the remote server from the specified domain.
 * Only the authenticated domains with the remote server will be able to effectively send packets
 * to the remote server. The remote server will reject and close the connection if a
 * non-authenticated domain tries to send a packet through this connection.<p>
 *
 * Once the connection has been established with the remote server and at least a domain has been
 * authenticated then a new route will be added to the routing table for this connection. For
 * optimization reasons the same outgoing connection will be used even if the remote server has
 * several hostnames. However, different routes will be created in the routing table for each
 * hostname of the remote server.
 *
 * @author Gaston Dombiak
 */
public class LocalOutgoingServerSession extends LocalServerSession implements OutgoingServerSession {

    private static final Logger Log = LoggerFactory.getLogger(LocalOutgoingServerSession.class);

    private static final Interner<JID> remoteAuthMutex = Interners.newWeakInterner();

    private final OutgoingServerSocketReader socketReader;
    private final Collection<DomainPair> outgoingDomainPairs = new HashSet<>();

    /**
     * Authenticates the local domain to the remote domain. Once authenticated the remote domain can be expected to
     * start accepting data from the local domain.
     *
     * This implementation will attempt to re-use an existing connection. An connection is deemed re-usable when it is either:
     * <ul>
     *     <li>authenticated to the remote domain itself, or:</li>
     *     <li>authenticated to a sub- or superdomain of the remote domain AND offers dialback.</li>
     * </ul>
     *
     * When no re-usable connection exists, a new connection will be created.
     *
     * DNS will be used to find hosts for the remote domain. When DNS records do not specify a port, port 5269 will be
     * used unless this default is overridden by the <b>xmpp.server.socket.remotePort</b> property.
     *
     * @param domainPair the local and remote domain for which authentication is to be established.
     * @return True if the domain was authenticated by the remote server.
     */
    public static boolean authenticateDomain(final DomainPair domainPair) {
        final String localDomain = domainPair.getLocal();
        final String remoteDomain = domainPair.getRemote();
        final Logger log = LoggerFactory.getLogger( Log.getName() + "[Authenticate local domain: '" + localDomain + "' to remote domain: '" + remoteDomain + "']" );

        log.debug( "Start domain authentication ..." );
        if (remoteDomain == null || remoteDomain.length() == 0 || remoteDomain.trim().indexOf(' ') > -1) {
            // Do nothing if the target domain is empty, null or contains whitespaces
            log.warn( "Unable to authenticate: remote domain is invalid." );
            return false;
        }
        try {
            // Check if the remote domain is in the blacklist
            if (!RemoteServerManager.canAccess(remoteDomain)) {
                log.info( "Unable to authenticate: Remote domain is not accessible according to our configuration (typical causes: server federation is disabled, or domain is blacklisted)." );
                return false;
            }

            log.debug( "Searching for pre-existing outgoing sessions to the remote domain (if one exists, it will be re-used) ..." );
            OutgoingServerSession session;
            SessionManager sessionManager = SessionManager.getInstance();
            if (sessionManager == null) {
                // Server is shutting down while we are trying to create a new s2s connection
                log.warn( "Unable to authenticate: a SessionManager instance is not available. This should not occur unless Openfire is starting up or shutting down." );
                return false;
            }
            session = sessionManager.getOutgoingServerSession(domainPair);
            if (session != null && session.checkOutgoingDomainPair(domainPair))
            {
                // Do nothing since the domain has already been authenticated.
                log.debug( "Authentication successful (domain was already authenticated in the pre-existing session)." );
                //inform all listeners as well.
                ServerSessionEventDispatcher.dispatchEvent(session, ServerSessionEventDispatcher.EventType.session_created);
                return true;
            }
            if (session != null && !session.isUsingServerDialback() )
            {
                log.debug( "Dialback was not used for '{}'. This session cannot be re-used.", domainPair );
                session = null;
            }

            if (session == null)
            {
                log.debug( "There are no pre-existing outgoing sessions to the remote domain itself. Searching for pre-existing outgoing sessions to super- or subdomains of the remote domain (if one exists, it might be re-usable) ..." );

                for ( IncomingServerSession incomingSession : sessionManager.getIncomingServerSessions( remoteDomain ) )
                {
                    // These are the remote domains that are allowed to send data to the local domain - expected to be sub- or superdomains of remoteDomain
                    for ( String otherRemoteDomain : incomingSession.getValidatedDomains() )
                    {
                        // See if there's an outgoing session to any of the (other) domains hosted by the remote domain.
                        session = sessionManager.getOutgoingServerSession( new DomainPair(localDomain, otherRemoteDomain) );
                        if (session != null)
                        {
                            log.debug( "An outgoing session to a different domain ('{}') hosted on the remote domain was found.", otherRemoteDomain );

                            // As this sub/superdomain is different from the original remote domain, we need to check if it supports dialback.
                            if ( session.isUsingServerDialback() )
                            {
                                log.debug( "Dialback was used for '{}'. This session can be re-used.", otherRemoteDomain );
                                break;
                            }
                            else
                            {
                                log.debug( "Dialback was not used for '{}'. This session cannot be re-used.", otherRemoteDomain );
                                session = null;
                            }
                        }
                    }
                }

                if (session == null) {
                    log.debug( "There are no pre-existing session to other domains hosted on the remote domain." );
                }
            }

            if ( session != null )
            {
                log.debug( "A pre-existing session can be re-used. The session was established using server dialback so it is possible to do piggybacking to authenticate more domains." );
                if ( session.checkOutgoingDomainPair(domainPair) )
                {
                    // Do nothing since the domain has already been authenticated.
                    log.debug( "Authentication successful (domain was already authenticated in the pre-existing session)." );
                    return true;
                }

                // A session already exists so authenticate the domain using that session.
                if ( session.authenticateSubdomain(domainPair) )
                {
                    log.debug( "Authentication successful (domain authentication was added using a pre-existing session)." );
                    return true;
                }
                else
                {
                    log.warn( "Unable to authenticate: Unable to add authentication to pre-exising session." );
                    return false;
                }
            }
            else
            {
                try {
                    log.debug("Unable to re-use an existing session. Creating a new session ...");
                    int port = RemoteServerManager.getPortForServer(remoteDomain);
                    session = createOutgoingSession(domainPair, port);
                    if (session != null) {
                        log.debug("Created a new session.");

                        session.addOutgoingDomainPair(domainPair);
                        sessionManager.outgoingServerSessionCreated((LocalOutgoingServerSession) session);
                        log.debug("Authentication successful.");
                        //inform all listeners as well.
                        ServerSessionEventDispatcher.dispatchEvent(session, ServerSessionEventDispatcher.EventType.session_created);
                        return true;
                    } else {
                        log.warn("Unable to authenticate: Fail to create new session.");
                        return false;
                    }
                } catch (Exception e) {
                    if (session != null) {
                        session.close();
                    }
                    throw e;
                }
            }
        }
        catch (Exception e)
        {
            log.error( "An exception occurred while authenticating to remote domain '{}'!", remoteDomain, e );
            return false;
        }
    }

    /**
     * Establishes a new outgoing session to a remote domain. If the remote domain supports TLS and SASL then the new
     * outgoing connection will be encrypted with TLS and authenticated using SASL. However, if TLS or SASL is not
     * supported by the remote domain or if an error occurred while securing or authenticating the connection using SASL
     * then server dialback will be used.
     *
     * @param domainPair the local and remote domain for which a session is to be established.
     * @param port default port to use to establish the connection.
     * @return new outgoing session to a remote domain, or null.
     */
    // package-protected to facilitate unit testing..
    static LocalOutgoingServerSession createOutgoingSession(@Nonnull final DomainPair domainPair, int port) {
        final Logger log = LoggerFactory.getLogger( Log.getName() + "[Create outgoing session for: " + domainPair + "]" );

        log.debug( "Creating new session..." );

        // Connect to remote server using XMPP 1.0 (TLS + SASL EXTERNAL or TLS + server dialback or server dialback)
        log.debug( "Creating plain socket connection to a host that belongs to the remote XMPP domain." );
        final Map.Entry<Socket, Boolean> socketToXmppDomain = SocketUtil.createSocketToXmppDomain(domainPair.getRemote(), port );

        if ( socketToXmppDomain == null ) {
            log.info( "Unable to create new session: Cannot create a plain socket connection with any applicable remote host." );
            return null;
        }
        Socket socket = socketToXmppDomain.getKey();
        boolean directTLS = socketToXmppDomain.getValue();

        SocketConnection connection = null;
        try {
            final SocketAddress socketAddress = socket.getRemoteSocketAddress();
            log.debug( "Opening a new connection to {} {}.", socketAddress, directTLS ? "using directTLS" : "that is initially not encrypted" );
            connection = new SocketConnection(XMPPServer.getInstance().getPacketDeliverer(), socket, false);
            if (directTLS) {
                try {
                    connection.startTLS( true, true );
                } catch ( SSLException ex ) {
                    if ( JiveGlobals.getBooleanProperty(ConnectionSettings.Server.TLS_ON_PLAIN_DETECTION_ALLOW_NONDIRECTTLS_FALLBACK, true) && ex.getMessage().contains( "plaintext connection?" ) ) {
                        Log.warn( "Plaintext detected on a new connection that is was started in DirectTLS mode (socket address: {}). Attempting to restart the connection in non-DirectTLS mode.", socketAddress );
                        try {
                            // Close old socket
                            socket.close();
                        } catch ( Exception e ) {
                            Log.debug( "An exception occurred (and is ignored) while trying to close a socket that was already in an error state.", e );
                        }
                        socket = new Socket();
                        socket.connect( socketAddress, RemoteServerManager.getSocketTimeout() );
                        connection = new SocketConnection(XMPPServer.getInstance().getPacketDeliverer(), socket, false);
                        directTLS = false;
                        Log.info( "Re-established connection to {}. Proceeding without directTLS.", socketAddress );
                    } else {
                        // Do not retry as non-DirectTLS, rethrow the exception.
                        throw ex;
                    }
                }
            }

            log.debug( "Send the stream header and wait for response..." );
            StringBuilder openingStream = new StringBuilder();
            openingStream.append("<stream:stream");
            if (ServerDialback.isEnabled() || ServerDialback.isEnabledForSelfSigned()) {
                openingStream.append(" xmlns:db=\"jabber:server:dialback\"");
            }
            openingStream.append(" xmlns:stream=\"http://etherx.jabber.org/streams\"");
            openingStream.append(" xmlns=\"jabber:server\"");
            openingStream.append(" from=\"").append(domainPair.getLocal()).append("\""); // OF-673
            openingStream.append(" to=\"").append(domainPair.getRemote()).append("\"");
            openingStream.append(" version=\"1.0\">");
            connection.deliverRawText(openingStream.toString());

            // Set a read timeout (of 5 seconds) so we don't keep waiting forever
            int soTimeout = socket.getSoTimeout();
            socket.setSoTimeout(5000);

            XMPPPacketReader reader = new XMPPPacketReader();

            final InputStream inputStream;
            if (directTLS) {
                inputStream = connection.getTLSStreamHandler().getInputStream();
            } else {
                inputStream = socket.getInputStream();
            }
            reader.getXPPParser().setInput(new InputStreamReader( inputStream, StandardCharsets.UTF_8 ));

            // Get the answer from the Receiving Server
            XmlPullParser xpp = reader.getXPPParser();
            for (int eventType = xpp.getEventType(); eventType != XmlPullParser.START_TAG;) {
                eventType = xpp.next();
            }

            String serverVersion = xpp.getAttributeValue("", "version");
            String id = xpp.getAttributeValue("", "id");
            log.debug( "Got a response (stream ID: {}, version: {}). Check if the remote server is XMPP 1.0 compliant...", id, serverVersion );

            if (serverVersion != null && Session.decodeVersion(serverVersion)[0] >= 1) {
                log.debug( "The remote server is XMPP 1.0 compliant (or at least reports to be)." );

                // Restore default timeout
                socket.setSoTimeout(soTimeout);

                Element features = reader.parseDocument().getRootElement();
                if (features != null) {
                    log.debug( "Processing stream features of the remote domain: {}", features.asXML() );
                    if (directTLS) {
                        log.debug( "We connected to the remote server using direct TLS. Authenticate the connection with SASL..." );
                        LocalOutgoingServerSession answer = authenticate(domainPair, connection, reader, openingStream, features, id);
                        if (answer != null) {
                            log.debug( "Successfully authenticated the connection with SASL)!" );
                            // Everything went fine so return the encrypted and authenticated connection.
                            log.debug( "Successfully created new session!" );
                            return answer;
                        }
                        log.debug( "Unable to authenticate the connection with SASL." );
                    } else {
                        log.debug( "Check if both us as well as the remote server have enabled STARTTLS and/or dialback ..." );
                        final boolean useTLS = connection.getTlsPolicy() == Connection.TLSPolicy.optional || connection.getTlsPolicy() == Connection.TLSPolicy.required;
                        if (useTLS && features.element("starttls") != null) {
                            log.debug( "Both us and the remote server support the STARTTLS feature. Encrypt and authenticate the connection with TLS & SASL..." );
                            LocalOutgoingServerSession answer = encryptAndAuthenticate(domainPair, connection, reader, openingStream);
                            if (answer != null) {
                                log.debug( "Successfully encrypted/authenticated the connection with TLS/SASL)!" );
                                // Everything went fine so return the secured and
                                // authenticated connection
                                log.debug( "Successfully created new session!" );
                                return answer;
                            }
                            log.debug( "Unable to encrypt and authenticate the connection with TLS & SASL." );
                        }
                        else if (connection.getTlsPolicy() == Connection.TLSPolicy.required) {
                            log.debug("I have no StartTLS yet I must TLS");
                            connection.close(new StreamError(StreamError.Condition.not_authorized, "TLS is mandatory, but was not established."));
                            return null;
                        }
                        // Check if we are going to try server dialback (XMPP 1.0)
                        else if (ServerDialback.isEnabled() && features.element("dialback") != null) {
                            log.debug( "Both us and the remote server support the 'dialback' feature. Authenticate the connection with dialback..." );
                            ServerDialback method = new ServerDialback(connection, domainPair);
                            OutgoingServerSocketReader newSocketReader = new OutgoingServerSocketReader(reader);
                            if (method.authenticateDomain(newSocketReader, id)) {
                                log.debug( "Successfully authenticated the connection with dialback!" );
                                StreamID streamID = BasicStreamIDFactory.createStreamID(id);
                                LocalOutgoingServerSession session = new LocalOutgoingServerSession(domainPair.getLocal(), connection, newSocketReader, streamID);
                                connection.init(session);
                                session.setAuthenticationMethod(AuthenticationMethod.DIALBACK);
                                // Set the remote domain name as the address of the session.
                                session.setAddress(new JID(null, domainPair.getRemote(), null));
                                log.debug( "Successfully created new session!" );
                                return session;
                            }
                            else {
                                log.debug( "Unable to authenticate the connection with dialback." );
                            }
                        }
                    }
                }
                else {
                    log.debug( "Error! No data from the remote server (expected a 'feature' element).");
                }
            } else {
                log.debug( "The remote server is not XMPP 1.0 compliant." );
            }

            log.debug( "Something went wrong so close the connection and try server dialback over a plain connection" );
            if (connection.getTlsPolicy() == Connection.TLSPolicy.required) {
                log.debug("I have no StartTLS yet I must TLS");
                connection.close(new StreamError(StreamError.Condition.not_authorized, "TLS is mandatory, but was not established."));
                return null;
            }
            connection.close();
        }
        catch (SSLHandshakeException e)
        {
            // When not doing direct TLS but startTLS, this a failure as described in RFC6120, section 5.4.3.2 "STARTTLS Failure".
            log.info( "{} negotiation failed. Closing connection (without sending any data such as <failure/> or </stream>).", (directTLS ? "Direct TLS" : "StartTLS" ), e );

            // The receiving entity is expected to close the socket *without* sending any more data (<failure/> nor </stream>).
            // It is probably (see OF-794) best if we, as the initiating entity, therefor don't send any data either.
            if (connection != null) {
                connection.forceClose();

                if (connection.getTlsPolicy() == Connection.TLSPolicy.required) {
                    return null;
                }
            }

            if (e.getCause() instanceof CertificateException && JiveGlobals.getBooleanProperty(ConnectionSettings.Server.STRICT_CERTIFICATE_VALIDATION, true)) {
                log.warn("Aborting attempt to create outgoing session as TLS handshake failed, and strictCertificateValidation is enabled.", e);
                return null;
            }
        }
        catch (Exception e)
        {
            // This might be RFC6120, section 5.4.2.2 "Failure Case" or even an unrelated problem. Handle 'normally'.
            log.warn( "An exception occurred while creating an encrypted session. Closing connection.", e );

            if (connection != null) {
                connection.close();
                if (connection.getTlsPolicy() == Connection.TLSPolicy.required) {
                    return null;
                }
            }
        }

        if (ServerDialback.isEnabled())
        {
            log.debug( "Unable to create a new session. Going to try connecting using server dialback as a fallback." );

            // Use server dialback (pre XMPP 1.0) over a plain connection
            final LocalOutgoingServerSession outgoingSession = new ServerDialback(domainPair).createOutgoingSession(port);
            if ( outgoingSession != null) { // TODO this success handler behaves differently from a similar success handler above. Shouldn't those be the same?
                log.debug( "Successfully created new session (using dialback as a fallback)!" );
                return outgoingSession;
            } else {
                log.warn( "Unable to create a new session: Dialback (as a fallback) failed." );
                return null;
            }
        }
        else
        {
            log.warn( "Unable to create a new session: exhausted all options (not trying dialback as a fallback, as server dialback is disabled by configuration." );
            return null;
        }
    }

    private static LocalOutgoingServerSession encryptAndAuthenticate(DomainPair domainPair, SocketConnection connection, XMPPPacketReader reader, StringBuilder openingStream) throws Exception {
        final Logger log = LoggerFactory.getLogger(Log.getName() + "[Encrypt connection for: " + domainPair + "]" );
        Element features;

        log.debug( "Encrypting and authenticating connection ...");

        log.debug( "Indicating we want TLS and wait for response." );
        connection.deliverRawText( "<starttls xmlns='urn:ietf:params:xml:ns:xmpp-tls'/>" );

        MXParser xpp = reader.getXPPParser();
        // Wait for the <proceed> response
        Element proceed = reader.parseDocument().getRootElement();
        if (proceed != null && proceed.getName().equals("proceed")) {
            log.debug( "Received 'proceed' from remote server. Negotiating TLS..." );
            try {
//                boolean needed = JiveGlobals.getBooleanProperty(ConnectionSettings.Server.TLS_CERTIFICATE_VERIFY, true) &&
//                        		 JiveGlobals.getBooleanProperty(ConnectionSettings.Server.TLS_CERTIFICATE_CHAIN_VERIFY, true) &&
//                        		 !JiveGlobals.getBooleanProperty(ConnectionSettings.Server.TLS_ACCEPT_SELFSIGNED_CERTS, false);
                connection.startTLS(true, false);
            } catch(Exception e) {
                log.debug("TLS negotiation failed: " + e.getMessage());
                throw e;
            }
            log.debug( "TLS negotiation was successful. Connection encrypted. Proceeding with authentication..." );

            // If TLS cannot be used for authentication, it is permissible to use another authentication mechanism
            // such as dialback. RFC 6120 does not explicitly allow this, as it does not take into account any other
            // authentication mechanism other than TLS (it does mention dialback in an interoperability note. However,
            // RFC 7590 Section 3.4 writes: "In particular for XMPP server-to-server interactions, it can be reasonable
            // for XMPP server implementations to accept encrypted but unauthenticated connections when Server Dialback
            // keys [XEP-0220] are used." In short: if Dialback is allowed, unauthenticated TLS is better than no TLS.
            if (!SASLAuthentication.verifyCertificates(connection.getPeerCertificates(), domainPair.getRemote(), true)) {
                if (JiveGlobals.getBooleanProperty(ConnectionSettings.Server.STRICT_CERTIFICATE_VALIDATION, true)) {
                    log.warn("Aborting attempt to create outgoing session as TLS handshake failed, and strictCertificateValidation is enabled.");
                    return null;
                }
                if (ServerDialback.isEnabled() || ServerDialback.isEnabledForSelfSigned()) {
                    log.debug( "SASL authentication failed. Will continue with dialback." );
                } else {
                    log.warn( "Unable to authenticate the connection: SASL authentication failed (and dialback is not available)." );
                    return null;
                }
            }

            log.debug( "TLS negotiation was successful so initiate a new stream." );
            connection.deliverRawText( openingStream.toString() );

            // Reset the parser to use the new secured reader
            xpp.setInput(new InputStreamReader(connection.getTLSStreamHandler().getInputStream(), StandardCharsets.UTF_8));
            // Skip new stream element
            for (int eventType = xpp.getEventType(); eventType != XmlPullParser.START_TAG;) {
                eventType = xpp.next();
            }
            // Get the stream ID
            String id = xpp.getAttributeValue("", "id");
            // Get new stream features
            features = reader.parseDocument().getRootElement();
            if (features != null) {
                return authenticate( domainPair, connection, reader, openingStream, features, id );
            }
            else {
                log.debug( "Failed to encrypt and authenticate connection: neither SASL mechanisms nor SERVER DIALBACK were offered by the remote host." );
                return null;
            }
        }
        else {
            log.debug( "Failed to encrypt and authenticate connection: <proceed> was not received!" );
            return null;
        }
    }

    private static LocalOutgoingServerSession authenticate( final DomainPair domainPair,
                                                            final SocketConnection connection,
                                                            final XMPPPacketReader reader,
                                                            final StringBuilder openingStream,
                                                            final Element features,
                                                            final String id ) throws DocumentException, IOException, XmlPullParserException
    {
        final Logger log = LoggerFactory.getLogger(Log.getName() + "[Authenticate connection for: " + domainPair + "]" );

        MXParser xpp = reader.getXPPParser();

        // Bookkeeping: determine what functionality the remote server offers.
        boolean saslEXTERNALoffered = false;
        if (features.element("mechanisms") != null) {
            Iterator<Element> it = features.element( "mechanisms").elementIterator();
            while (it.hasNext()) {
                Element mechanism = it.next();
                if ("EXTERNAL".equals(mechanism.getTextTrim())) {
                    saslEXTERNALoffered = true;
                    break;
                }
            }
        }
        final boolean dialbackOffered = features.element("dialback") != null;

        log.debug("Remote server is offering dialback: {}, EXTERNAL SASL: {}", dialbackOffered, saslEXTERNALoffered );

        LocalOutgoingServerSession result = null;

        // first, try SASL
        if (saslEXTERNALoffered) {
            log.debug( "Trying to authenticate with EXTERNAL SASL." );
            result = attemptSASLexternal(connection, xpp, reader, domainPair, id, openingStream);
            if (result == null) {
                log.debug( "Failed to authenticate with EXTERNAL SASL." );
            } else {
                log.debug( "Successfully authenticated with EXTERNAL SASL." );
            }
        }

        // SASL unavailable or failed, try dialback.
        if (result == null) {
            log.debug( "Trying to authenticate with dialback." );
            result = attemptDialbackOverTLS(connection, reader, domainPair, id);
            if (result == null) {
                log.debug( "Failed to authenticate with dialback." );
            } else {
                log.debug( "Successfully authenticated with dialback." );
            }
        }

        if ( result != null ) {
            log.debug( "Successfully encrypted and authenticated connection!" );
            return result;
        } else {
            log.warn( "Unable to encrypt and authenticate connection: Exhausted all options." );
            return null;
        }
    }

    private static LocalOutgoingServerSession attemptDialbackOverTLS(Connection connection, XMPPPacketReader reader, DomainPair domainPair, String id) {
        final Logger log = LoggerFactory.getLogger( Log.getName() + "[Dialback over TLS for: " + domainPair + " (Stream ID: " + id + ")]" );

        if (ServerDialback.isEnabled() || ServerDialback.isEnabledForSelfSigned()) {
            log.debug("Trying to connecting using dialback over TLS.");
            ServerDialback method = new ServerDialback(connection, domainPair);
            OutgoingServerSocketReader newSocketReader = new OutgoingServerSocketReader(reader);
            if (method.authenticateDomain(newSocketReader, id)) {
                log.debug("Dialback over TLS was successful.");
                StreamID streamID = BasicStreamIDFactory.createStreamID(id);
                LocalOutgoingServerSession session = new LocalOutgoingServerSession(domainPair.getLocal(), connection, newSocketReader, streamID);
                connection.init(session);
                // Set the remote domain name as the address of the session.
                session.setAddress(new JID(null, domainPair.getRemote(), null));
                session.setAuthenticationMethod(AuthenticationMethod.DIALBACK);
                return session;
            }
            else {
                log.debug("Dialback over TLS failed");
                return null;
            }
        }
        else {
            log.debug("Skipping server dialback attempt as it has been disabled by local configuration.");
            return null;
        }    	
    }
    
    private static LocalOutgoingServerSession attemptSASLexternal(SocketConnection connection, MXParser xpp, XMPPPacketReader reader, DomainPair domainPair, String id, StringBuilder openingStream) throws DocumentException, IOException, XmlPullParserException {
        final Logger log = LoggerFactory.getLogger( Log.getName() + "[EXTERNAL SASL for: " + domainPair + " (Stream ID: " + id + ")]" );

        log.debug("Starting EXTERNAL SASL.");
        if (doExternalAuthentication(domainPair.getLocal(), connection, reader)) {
            log.debug("EXTERNAL SASL was successful.");
            // SASL was successful so initiate a new stream
            connection.deliverRawText(openingStream.toString());
            
            // Reset the parser
            //xpp.resetInput();
            //             // Reset the parser to use the new secured reader
            xpp.setInput(new InputStreamReader(connection.getTLSStreamHandler().getInputStream(), StandardCharsets.UTF_8));
            // Skip the opening stream sent by the server
            for (int eventType = xpp.getEventType(); eventType != XmlPullParser.START_TAG;) {
                eventType = xpp.next();
            }

            // SASL authentication was successful so create new OutgoingServerSession
            id = xpp.getAttributeValue("", "id");
            StreamID streamID = BasicStreamIDFactory.createStreamID(id);
            LocalOutgoingServerSession session = new LocalOutgoingServerSession(domainPair.getLocal(), connection, new OutgoingServerSocketReader(reader), streamID);
            connection.init(session);
            // Set the remote domain name as the address of the session
            session.setAddress(new JID(null, domainPair.getRemote(), null));
            // Set that the session was created using TLS+SASL (no server dialback)
            session.setAuthenticationMethod(AuthenticationMethod.SASL_EXTERNAL);
            return session;
        }
        else {
            log.debug("EXTERNAL SASL failed.");
            return null;
        }  	
    }
    
    private static boolean doExternalAuthentication(String localDomain, SocketConnection connection,
            XMPPPacketReader reader) throws DocumentException, IOException, XmlPullParserException {

        StringBuilder sb = new StringBuilder();
        sb.append("<auth xmlns=\"urn:ietf:params:xml:ns:xmpp-sasl\" mechanism=\"EXTERNAL\">");
        sb.append(StringUtils.encodeBase64(localDomain));
        sb.append("</auth>");
        connection.deliverRawText(sb.toString());

        Element response = reader.parseDocument().getRootElement();
        return response != null && "success".equals(response.getName());
    }

    public LocalOutgoingServerSession(String localDomain, Connection connection, OutgoingServerSocketReader socketReader, StreamID streamID) {
        super(localDomain, connection, streamID);
        this.socketReader = socketReader;
        socketReader.setSession(this);
    }

    @Override
    boolean canProcess(Packet packet) {
        final DomainPair domainPair = new DomainPair(packet.getFrom().getDomain(), packet.getTo().getDomain());
        boolean processed = true;
        synchronized (remoteAuthMutex.intern(new JID(null, domainPair.getRemote(), null))) {
            if (!checkOutgoingDomainPair(domainPair) && !authenticateSubdomain(domainPair)) {
                // Return error since sender domain was not validated by remote server
                processed = false;
            }
        }
        if (!processed) {
            returnErrorToSenderAsync(packet);
        }
        return processed;
    }

    @Override
    void deliver(Packet packet) throws UnauthorizedException {
        if (!conn.isClosed()) {
            conn.deliver(packet);
        }
    }

    @Override
    public boolean authenticateSubdomain(@Nonnull final DomainPair domainPair) {
        if (!isUsingServerDialback()) {
            /*
             * We cannot do this reliably; but this code should be unreachable.
             */
            return false;
        }
        ServerDialback method = new ServerDialback(getConnection(), domainPair);
        if (method.authenticateDomain(socketReader, getStreamID().getID())) {
            // Add the validated domain as an authenticated domain
            addOutgoingDomainPair(domainPair);
            return true;
        }
        return false;
    }

    private void returnErrorToSenderAsync(Packet packet) {
        TaskEngine.getInstance().submit(() -> {
            final PacketRouter packetRouter = XMPPServer.getInstance().getPacketRouter();
            if (packet.getError() != null) {
                Log.debug("Possible double bounce: {}", packet.toXML());
            }
            try {
                if (packet instanceof IQ) {
                    if (((IQ) packet).isResponse()) {
                        Log.debug("XMPP specs forbid us to respond with an IQ error to: {}", packet.toXML());
                        return;
                    }
                    IQ reply = new IQ();
                    reply.setID(packet.getID());
                    reply.setTo(packet.getFrom());
                    reply.setFrom(packet.getTo());
                    reply.setChildElement(((IQ) packet).getChildElement().createCopy());
                    reply.setType(IQ.Type.error);
                    reply.setError(PacketError.Condition.remote_server_not_found);
                    packetRouter.route(reply);
                }
                else if (packet instanceof Presence) {
                    if (((Presence)packet).getType() == Presence.Type.error) {
                        Log.debug("Avoid generating an error in response to a stanza that itself is an error (to avoid the chance of entering an endless back-and-forth of exchanging errors). Suppress sending an {} error in response to: {}", PacketError.Condition.remote_server_not_found, packet);
                        return;
                    }
                    Presence reply = new Presence();
                    reply.setID(packet.getID());
                    reply.setTo(packet.getFrom());
                    reply.setFrom(packet.getTo());
                    reply.setType(Presence.Type.error);
                    reply.setError(PacketError.Condition.remote_server_not_found);
                    packetRouter.route(reply);
                }
                else if (packet instanceof Message) {
                    if (((Message)packet).getType() == Message.Type.error){
                        Log.debug("Avoid generating an error in response to a stanza that itself is an error (to avoid the chance of entering an endless back-and-forth of exchanging errors). Suppress sending an {} error in response to: {}", PacketError.Condition.remote_server_not_found, packet);
                        return;
                    }
                    Message reply = new Message();
                    reply.setID(packet.getID());
                    reply.setTo(packet.getFrom());
                    reply.setFrom(packet.getTo());
                    reply.setType(Message.Type.error);
                    reply.setThread(((Message)packet).getThread());
                    reply.setError(PacketError.Condition.remote_server_not_found);
                    packetRouter.route(reply);
                }
            }
            catch (Exception e) {
                Log.error("Error returning error to sender. Original packet: {}", packet, e);
            }
        });
    }

    @Override
    public String getAvailableStreamFeatures() {
        // Nothing special to add
        return null;
    }

    @Override
    public void addOutgoingDomainPair(@Nonnull final DomainPair domainPair) {
        XMPPServer.getInstance().getRoutingTable().addServerRoute(domainPair, this);
        outgoingDomainPairs.add(domainPair);
    }

    @Override
    public boolean checkOutgoingDomainPair(@Nonnull final DomainPair domainPair) {
        final boolean result = outgoingDomainPairs.contains(domainPair);
        Log.trace( "Authentication exists for outgoing domain pair {}: {}", domainPair, result );
        return result;
    }

    @Override
    public Collection<DomainPair> getOutgoingDomainPairs() {
        return outgoingDomainPairs;
    }

    @Override
    public String toString()
    {
        return this.getClass().getSimpleName() +"{" +
            "address=" + getAddress() +
            ", streamID=" + getStreamID() +
            ", status=" + getStatus() +
            ", isEncrypted=" + isEncrypted() +
            ", isDetached=" + isDetached() +
            ", authenticationMethod=" + getAuthenticationMethod() +
            ", outgoingDomainPairs=" + getOutgoingDomainPairs().stream().map( DomainPair::toString ).collect(Collectors.joining(", ", "{", "}")) +
            '}';
    }
}
