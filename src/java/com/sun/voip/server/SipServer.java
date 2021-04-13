/*
 * Copyright 2007 Sun Microsystems, Inc.
 *
 * This file is part of jVoiceBridge.
 *
 * jVoiceBridge is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation and distributed hereunder
 * to you.
 *
 * jVoiceBridge is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Sun designates this particular file as subject to the "Classpath"
 * exception as provided by Sun in the License file that accompanied this
 * code.
 */

package com.sun.voip.server;

import com.sun.voip.*;

import java.text.ParseException;
import java.util.logging.Level;


import javax.sip.*;
import javax.sip.address.*;
import javax.sip.address.SipURI;
import javax.sip.address.URI;
import javax.sip.header.*;
import javax.sip.message.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.text.*;
import java.security.*;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.voicebridge.*;

import org.jivesoftware.util.*;

import gov.nist.javax.sip.header.Require;


/**
 * The SIP Server sets up a SIP Stack and handles SIP requests and responses.
 *
 * It is the first point of contact for all incoming SIP messages.
 *
 * It listens for SIP messages on a default port of 5060
 * (override by the NIST-SIP property gov.nist.jainsip.stack.enableUDP),
 * and forwards the request to the appropriate SipListener according to
 * the SIP CallId of the SIP message.
 *
 * Properties:
 * gov.nist.jainsip.stack.enableUDP = 5060;
 */
public class SipServer implements SipListener {
    /*
     * default port for SIP communication
     */
    private static final int SIP_PORT = 5060;

    /*
     * ip addresses of the SIP Proxy (NIST Proxy Server by default)
     */
    private static String defaultSipProxy;

    /*
     * IP address of the Cisco SIP/VoIP gateways
     */
    private static ArrayList<String> voIPGateways = new ArrayList<String>();
    private static ArrayList<ProxyCredentials> voIPGatewayLoginInfo = new ArrayList<ProxyCredentials>();
	private static ArrayList<RegisterProcessing> registrations = new ArrayList<RegisterProcessing>();
	public static ConcurrentHashMap<String, String> clientRegistrations = new ConcurrentHashMap<String, String>();

    /*
     * flag to indicate whether to send SIP Uri's to a proxy or directly
     * to the target endpoint.
     */
    private static boolean sendSipUriToProxy = false;

    /*
     * hashtable of SipListeners
     */
    private static Hashtable sipListenersTable;

    /*
     * sip stack variables
     */
    private static SipFactory sipFactory;
    private static AddressFactory addressFactory;
    private static HeaderFactory headerFactory;
    private static MessageFactory messageFactory;
    private static SipStack sipStack;
    private static SipProvider sipProvider;
    private static Iterator listeningPoints;
    private static SipServerCallback sipServerCallback;
    private static InetSocketAddress sipAddress;

     public SipServer(Config config, Properties properties) {
        sipListenersTable = new Hashtable();
        sipServerCallback = new SipServerCallback();
        setup(config, properties);
    }

    /**
     * Sets up and initializes the sip server, including the sipstack.
     */
    private void setup(Config config, Properties properties)
    {
		String localHostAddress = config.getPrivateHost();
        properties.setProperty("javax.sip.IP_ADDRESS", localHostAddress);

        /*
         * get IPs of the voip gateways
         */

        setVoIPGateways(config);

		if (voIPGateways.size() == 0) {
			Logger.println("There are no VoIP gateways.  You cannot make calls to the phone system.");
		}

        /*
	 * Obtain an instance of the singleton SipFactory
	 */
        sipFactory = SipFactory.getInstance();

        /*
         * Set path name of SipFactory to implementation.
         * used to setup the classpath
         */
        sipFactory.setPathName("gov.nist");

        try {
            /*
	     * Create SipStack object
	     */
            sipStack = (SipStack)sipFactory.createSipStack(properties);
            /*
	     * Create AddressFactory
	     */
            addressFactory = sipFactory.createAddressFactory();
            /*
             * Create HeaderFactory
	     */
            headerFactory = sipFactory.createHeaderFactory();
            /*
	     * Create MessageFactory
	     */
            messageFactory = sipFactory.createMessageFactory();
        } catch(Exception e) {
            Logger.exception("could not stsart sip stack.", e);
            return;
        }

		ListeningPoint udpListenPort = null;
		ListeningPoint tcpListenPort = null;

        try {
            /*
             * Create SipProvider based on the first ListeningPoint
             * Note that this call will block until somebody sends us
             * a message because we dont know what IP address and
             * port to assign to outgoing messages from this provider
             * at this point.
             */
            String s = System.getProperty("com.sun.voip.server.SIP_PORT", String.valueOf(SIP_PORT));
	    	int sipPort = Integer.parseInt(s);

	    	Logger.println("");
            Logger.println("Bridge private address:   " + properties.getProperty("javax.sip.IP_ADDRESS"));

			tcpListenPort = sipStack.createListeningPoint("0.0.0.0", sipPort, "tcp");
			udpListenPort = sipStack.createListeningPoint("0.0.0.0", sipPort, "udp");


			if ("tcp".equals(JiveGlobals.getProperty("voicebridge.default.protocol", "tcp")))
			{
            	sipProvider = sipStack.createSipProvider(tcpListenPort);
            	sipProvider.addListeningPoint(udpListenPort);
			} else {
            	sipProvider = sipStack.createSipProvider(udpListenPort);
            	sipProvider.addListeningPoint(tcpListenPort);
			}

            sipProvider.addSipListener(this);
	   		sipAddress = new InetSocketAddress(sipStack.getIPAddress(), sipPort);

            /*
	     * get IPs of the SIP Proxy server
	     */
            defaultSipProxy = config.getDefaultProxy();

            /*
	     * Initialize SipUtil class.  Do this last so that
             * the other sip stack variables are initialized
             */
            SipUtil.initialize();

			for (int i = 0; i < voIPGatewayLoginInfo.size(); i++)
			{
				try {
					InetAddress inetAddress = InetAddress.getByName(voIPGateways.get(i));

					ProxyCredentials proxyCredentials = voIPGatewayLoginInfo.get(i);

					if (proxyCredentials.getAuthUserName() != null && !proxyCredentials.getAuthUserName().trim().equals(""))
					{
						registrations.add(new RegisterProcessing(inetAddress.getHostAddress(), voIPGateways.get(i), proxyCredentials));
					}

				} catch (Exception e) {
					System.out.println(String.format("Bad Address  %s ", voIPGateways.get(i)));
					continue;
				}
			}


        } catch(TransportAlreadySupportedException e) {
            Logger.exception("Stack has transport already supported", e);
            return;
        } catch(NullPointerException e) {
            Logger.exception("Stack has no ListeningPoints", e);
            return;
        } catch(ObjectInUseException e) {
            Logger.exception("Stack has no ListeningPoints", e);
            return;
        } catch(TransportNotSupportedException e) {
	    Logger.exception("TransportNotSupportedException", e);
            return;
	} catch(TooManyListenersException e) {
	    Logger.exception("TooManyListenersException", e);
            return;
	} catch(InvalidArgumentException e) {
	    Logger.exception("InvalidArgumentException", e);
            return;
	}

        Logger.println("Default SIP Proxy:        " + defaultSipProxy);
	Logger.println("");
    }

    public static ArrayList<String> getVoIPGateways() {
        return voIPGateways;
    }

    public static ArrayList<ProxyCredentials> getProxyCredentials() {
        return voIPGatewayLoginInfo;
    }

    public static void setVoIPGateways(Config config)
    {
		voIPGateways = config.getRegistrars();
		voIPGatewayLoginInfo = config.getRegistrations();
        return;
    }


    /**
     * set flag to indicate whether to send SIP Uri's to a proxy or directly
     * to the target endpoint.
     */
    public static void setSendSipUriToProxy(boolean sendSipUriToProxy) {
	SipServer.sendSipUriToProxy = sendSipUriToProxy;
    }

    public static boolean getSendSipUriToProxy() {
	return sendSipUriToProxy;
    }

    /**
     * Get the IP Address of the SIP Proxy.
     * @return SipProxy String with dotted IP address
     */
    public static String getDefaultSipProxy() {
	return defaultSipProxy;
    }

    /**
     * Set the IP address of the SIP Proxy.
     * @param ip String with dotted IP address
     */
    public static void setDefaultSipProxy(String defaultSipProxy) {
	SipServer.defaultSipProxy = defaultSipProxy;
    }

    public static InetSocketAddress getSipAddress() {
	return sipAddress;
    }

    /**
     * Process transaction timeout.  Forwards the event to
     * the appropriate sipListener
     * @param transactionTimeOutEvent The timeout event
     */
    public void processTimeout(javax.sip.TimeoutEvent timeoutEvent) {
        try {
            /*
             * FIXME: Possible BUG - getMessage() for a transaction
             * timed out event returns null, so we're unable to
             * determine the SIP callId of the event.
             *
             * Workaround: enumerate through the agents table and
             * call processTimeOut() of all agents that are in
             * the ONE_PARTY_INVITED state.
             */
            //Enumeration e = sipListenersTable.elements();
            //while (e.hasMoreElements()) {
                //CallSetupAgent callSetupAgent = (CallSetupAgent)e.nextElement();
                //int state = callSetupAgent.getState();
                //if (state == CallSetupAgent.CALL_PARTICIPANT_INVITED) {
                //    // callSetupAgent.processTimeOut(timeoutEvent);
		//    Logger.error("timeout:  " + callSetupAgent);
		//}
            //}
        } catch (Exception e) {
            /*
	     * FIXME: if any exception happens at this stage,
             * we should send back a 500 Internal Server Error
             */
	    Logger.exception("processTimeout", e);
        }
    }

    /**
     * Process requests received.  Forwards the request to
     * the appropriate SipListener.
     * @param requestEvent the request event
     */
    public void processRequest(RequestEvent requestEvent) {
        try {
            Request request = requestEvent.getRequest();

	    CallIdHeader callIdHeader = (CallIdHeader)
		request.getHeader(CallIdHeader.NAME);

	    String sipCallId = callIdHeader.getCallId();

            SipListener sipListener = findSipListener(requestEvent);

	    /*
	     * If there's an existing listener pass the request there.
	     */
            if (sipListener != null) {

	        if (request.getMethod().equals(Request.INVITE)) {
		    duplicateInvite(request);
		    return;
			}


             sipListener.processRequest(requestEvent);
		     return;

            } else {
		if (request.getMethod().equals(Request.REGISTER)) {
		    handleRegister(request, requestEvent);

		} else if (request.getMethod().equals(Request.OPTIONS)) {

			Response res = messageFactory.createResponse(Response.OK, request);
			sipProvider.sendResponse(res);
			return;


		} else if (!request.getMethod().equals(Request.INVITE)) {
                    Logger.writeFile("sipListener could not be found for " + sipCallId + " " + request.getMethod() + ".  Ignoring");
		    return;
                }
	    }

	    /*
	     * An INVITE for an incoming call goes to the IncomingCallHandler.
	     */
	    if (request.getMethod().equals(Request.INVITE)) {

		if (SipIncomingCallAgent.addSipCallId(sipCallId) == false) {
		    FromHeader fromHeader = (FromHeader)
			request.getHeader(FromHeader.NAME);

        	    ToHeader toHeader = (ToHeader)
			request.getHeader(ToHeader.NAME);

        	    String from = fromHeader.getAddress().toString();
        	    String to = toHeader.getAddress().toString();

		    Logger.writeFile("SipServer:  duplicate INVITE from " + from + " to " + to);

		    return;
		}

		CallParticipant cp = new CallParticipant();

		String s = SipUtil.getCallIdFromSdp(request);

		if (s != null) {
		    if (Logger.logLevel >= Logger.LOG_MOREINFO) {
			Logger.println("Using callId from SDP in INVITE: "
			    + s);
		    }
		    cp.setCallId(s);
		}

	  	s = SipUtil.getConferenceIdFromSdp(request);

		if (s != null) {
		    String[] tokens = s.split(":");

		    cp.setConferenceId(tokens[0].trim());

		    if (tokens.length > 1) {
                        cp.setMediaPreference(tokens[1]);
                    }

		    if (tokens.length > 2) {
			cp.setConferenceDisplayName(tokens[2]);
		    }
		}

		if (SipUtil.getUserNameFromSdp(request) != null) {
		    cp.setName(SipUtil.getUserNameFromSdp(request));
		} else {
		    cp.setName(SipUtil.getFromName(requestEvent));
		}

		cp.setDistributedBridge(SipUtil.getDistributedBridgeFromSdp(request));
		cp.setPhoneNumber(SipUtil.getFromPhoneNumber(requestEvent));
		cp.setToPhoneNumber(SipUtil.getToPhoneNumber(requestEvent));


		new IncomingCallHandler(cp, requestEvent);
		return;
	    }
        } catch (Exception e) {
            /*
	     * FIXME: if any exception happens at this stage,
             * we should send back a 500 Internal Server Error
             */
	    Logger.exception("processRequest", e);
	    e.printStackTrace();
        }
    }


    private void duplicateInvite(Request request)
    {
      try
      {
	FromHeader fromHeader = (FromHeader) request.getHeader(FromHeader.NAME);
        ToHeader toHeader = (ToHeader) request.getHeader(ToHeader.NAME);

	String from = fromHeader.getAddress().toString();
        String to = toHeader.getAddress().toString();

        Response response = messageFactory.createResponse(Response.OK, request);
        Logger.writeFile("SipServer:  duplicate INVITE from " + from + " to " + to);
        Logger.println("RESPONSE " + response);

        sipProvider.sendResponse(response);
    }
      catch (SipException ex)
      {
        java.util.logging.Logger.getLogger(SipServer.class.getName()).log(Level.SEVERE, null, ex);
      }
      catch (ParseException ex)
      {
        java.util.logging.Logger.getLogger(SipServer.class.getName()).log(Level.SEVERE, null, ex);
      }
    }



    private void handleRegister(Request request, RequestEvent requestEvent) throws Exception
    {
		Response response = processRegister(request);
		ServerTransaction serverTransaction = requestEvent.getServerTransaction();

		if (Logger.logLevel >= Logger.LOG_INFO)
		{
			Logger.println("Response " + response);
		}

		if (serverTransaction != null)
		{
			serverTransaction.sendResponse(response);

		} else {
			sipProvider.sendResponse(response);
		}
	}

    private Response processRegister(Request request) throws ParseException
    {
		if (Logger.logLevel >= Logger.LOG_INFO)
		{
			Logger.println(request.toString());
		}

		DigestServerAuthenticationMethod dsam = null;

        try
        {
            dsam = new DigestServerAuthenticationMethod(JiveGlobals.getProperty("xmpp.domain", "localhost"), new String[] { "MD5" });
        }
        catch (NoSuchAlgorithmException ex)
        {
          	Logger.error("Cannot create authentication method. Some algorithm is not implemented: "+ ex);
		 	return messageFactory.createResponse(Response.SERVER_INTERNAL_ERROR, request);
        }

        try
        {
            if (!checkAuthorization(request, dsam) )
            {
                Logger.println("Request rejected ( Unauthorized )");

                Response response = messageFactory.createResponse(Response.UNAUTHORIZED,request);

                WWWAuthenticateHeader wwwAuthenticateHeader = headerFactory.createWWWAuthenticateHeader("Digest");
                wwwAuthenticateHeader.setParameter("realm",dsam.getDefaultRealm());
                wwwAuthenticateHeader.setParameter("nonce",dsam.generateNonce(dsam.getPreferredAlgorithm()));
                wwwAuthenticateHeader.setParameter("opaque","");
                wwwAuthenticateHeader.setParameter("stale","FALSE");
                wwwAuthenticateHeader.setParameter("algorithm", dsam.getPreferredAlgorithm());

                response.setHeader(wwwAuthenticateHeader);
                return response;
            }
        }
        catch (Exception e)
        {
            Logger.error("processRegister failed " + e);
            return messageFactory.createResponse(Response.NOT_FOUND, request);
        }

		ContactHeader cont = (ContactHeader) request.getHeader(ContactHeader.NAME);
		String from = ((SipURI) cont.getAddress().getURI()).toString();

		ToHeader th = (ToHeader) request.getHeader("To");
		String to = ((SipURI) th.getAddress().getURI()).getUser();

        Logger.println("Request accepted ( Authorized ) " + from + " " + to);
        clientRegistrations.put(to, from);
		return messageFactory.createResponse(Response.OK, request);
    }

    public boolean checkAuthorization(Request request, DigestServerAuthenticationMethod dsam)
    {
		Logger.println("checkAuthorization ");

        AuthorizationHeader authorizationHeader = (AuthorizationHeader) request.getHeader(AuthorizationHeader.NAME);

        if (authorizationHeader == null)
        {
            Logger.error("Authentication failed: Authorization header missing.");
            return false;
        }
        else
        {
            String username = JiveGlobals.getProperty("audiobridge.sip.username", "audiobridge");
            String password = JiveGlobals.getProperty("audiobridge.sip.password", "audiobridge");

            String username_h = authorizationHeader.getUsername();

            Logger.println("checkAuthorization " + username_h + " " + username + " " + password);

            if (username_h == null) return false;
            if (username_h.indexOf('@') != -1) username_h = username_h.substring(0, username_h.indexOf('@'));

            // If user names are not equal, authorization failed
            //if (!username.equals(username_h)) return false;

			// BAO TODO Proper authentication
            // hack accept password = username
            password = username_h;

            return dsam.doAuthenticate(request, authorizationHeader, username_h, password);
        }
    }

    /**
     * Process responses received.  Forward the responses to
     * the appropriate SipListener.
     * @param responseReceivedEvent the response event
     */
    public void processResponse(ResponseEvent responseReceivedEvent)
    {
        Response response = responseReceivedEvent.getResponse();

		if (responseReceivedEvent.getClientTransaction() == null)
		{
            Logger.error("SipServer processResponse:  clientTransaction is null! " + responseReceivedEvent.getResponse());
	    	return;
        }

        try {
            SipListener sipListener = findSipListener(responseReceivedEvent);

            if (sipListener != null) {
                sipListener.processResponse(responseReceivedEvent);
            } else {
                /*
		 * a BYE message could come from a party that already
                 * has its entry removed from the SipListenersTable.  Ignoring.
                 * This is the desired behaviour if we wished to send BYE
                 * requests to a party that just got hung up on (e.g.
                 * if party A hangs up, we send a BYE to party B).
                 */

                if (response.getStatusCode() != Response.OK && response.getStatusCode() != 201)
                {
		    		CallIdHeader callIdHeader = (CallIdHeader) response.getHeader(CallIdHeader.NAME);
                    //Logger.writeFile("sipListener could not be found for " + callIdHeader.getCallId() + " " + response.getStatusCode() + ".  Ignoring");
				}
            }
        } catch (Exception e) {
            /* FIXME: if any exception happens at this stage,
             * we should send back a 500 Internal Server Error
             */
	    Logger.exception("processResponse", e);
        }
    }

    /**
     * Finds the SipListener responsible for handling the SIP transaction
     * associated with the SIP callId
     * @param event the EventObject
     * @throws Exception general exception when an error occurs
     * @return the SipListener for this sipEvent
     */
    private SipListener findSipListener(EventObject event) {
	String sipCallId = null;

        try {
	    CallIdHeader callIdHeader;

	    if (event instanceof RequestEvent) {
                Request request = ((RequestEvent)event).getRequest();

		callIdHeader = (CallIdHeader)
                    request.getHeader(CallIdHeader.NAME);
	    } else if (event instanceof ResponseEvent) {
		Response response = ((ResponseEvent)event).getResponse();

		callIdHeader = (CallIdHeader)
                    response.getHeader(CallIdHeader.NAME);
	    } else {
		Logger.error("Invalid event object " + event);
	       	return null;
	    }

	    sipCallId = callIdHeader.getCallId();

            synchronized (sipListenersTable) {
                return (SipListener)sipListenersTable.get(sipCallId);
            }
        } catch (NullPointerException e) {
            /*
	     * most likely due to a null sipCallId
	     */
            if (sipCallId == null || "".equals(sipCallId)) {
                Logger.exception("could not get SIP CallId from incoming"
                        + " message.  Dropping message", e);
            }
            throw e;
        }
    }

    /**
     * returns the sipStack
     * @return the sipStack
     */
    public static SipStack getSipStack() {
	return sipStack;
    }

    /**
     * returns the headerFactory
     * @return the headerFactory
     */
    public static HeaderFactory getHeaderFactory() {
        return headerFactory;
    }

    /**
     * returns the addressFactory
     * @return addressFactory the addressFactory
     */
    public static AddressFactory getAddressFactory() {
        return addressFactory;
    }

    /**
     * returns the messageFactory
     * @return the messageFactory
     */
    public static MessageFactory getMessageFactory() {
        return messageFactory;
    }

    /**
     * returns the sipProvider
     * @return the sipProvider
     */
    public static SipProvider getSipProvider() {
        return sipProvider;
    }

    /**
     * returns the sipServerCallback
     * @return the sipServerCallback
     */
    public static SipServerCallback getSipServerCallback() {
        return SipServer.sipServerCallback;
    }

    /**
     * Inner class responsible for handling SIP agent registrations and
     * unregistrations.  Used for callback purposes
     */
    class SipServerCallback {
        /**
         * registers a SIP agent with the given key.
         * @param key the key used to distinguish the SIP agent
         * @param agent the SIP agent
         */
        public void addSipListener(String key, SipListener sipListener) {
            synchronized (sipListenersTable) {
                if (!sipListenersTable.containsKey(key)) {
                    sipListenersTable.put(key, sipListener);
                } else {
                    Logger.error("key:  " + key + " already mapped!");
		}
            }
        }

        /**
         * unregisters a SIP agent with the given key.
         * @param key the key used to distinguish the SIP agent
         */
        public void removeSipListener(String key) {
            synchronized (sipListenersTable) {
                if (sipListenersTable.containsKey(key)) {
                    sipListenersTable.remove(key);
                } else {
                    Logger.println("could not find a SipListener "
                        + "entry to remove with the key:" + key);
	        }
            }
        }
    }

    public static ServerTransaction getServerTransaction(
	    RequestEvent requestEvent) throws TransactionDoesNotExistException,
	    TransactionUnavailableException {

	Request request = requestEvent.getRequest();

	ServerTransaction st = null;

	try {
	    getSipProvider().getNewServerTransaction(request);
	} catch (TransactionAlreadyExistsException e) {
	    Logger.println("Server transaction already exists for " + request);

	    st = requestEvent.getServerTransaction();

	    if (st == null) {
		Logger.println("st still null!");

		//st = sipStack.findTransaction((SIPRequest) request, true);
	    }
	}

	if (st == null) {
	    Logger.println("Server transaction not found for " + request);

	    throw new TransactionDoesNotExistException(
                        "Server transaction not found for " + request);
	}

	return st;
    }

    public void processDialogTerminated(DialogTerminatedEvent dte) {
        if (Logger.logLevel >= Logger.LOG_SIP) {
            Logger.println("processDialogTerminated called");
	}
    }

    public void  processTransactionTerminated(TransactionTerminatedEvent tte) {
        if (Logger.logLevel >= Logger.LOG_SIP) {
            Logger.println("processTransactionTerminated called");
	}
    }

    public void  processIOException(IOExceptionEvent ioee) {
        if (Logger.logLevel >= Logger.LOG_SIP) {
            Logger.println("processTransactionTerminated called");
	}
    }

    public synchronized static ClientTransaction handleChallenge(Response challenge, ClientTransaction challengedTransaction, ProxyCredentials proxyCredentials)
    {
        try {

            String branchID = challengedTransaction.getBranchId();
            Request challengedRequest = challengedTransaction.getRequest();
            Request reoriginatedRequest = (Request) challengedRequest.clone();

            ListIterator authHeaders = null;

            if (challenge == null || reoriginatedRequest == null)
                throw new NullPointerException("A null argument was passed to handle challenge.");

            // CallIdHeader callId =
            // (CallIdHeader)challenge.getHeader(CallIdHeader.NAME);

            if (challenge.getStatusCode() == Response.UNAUTHORIZED)
                authHeaders = challenge.getHeaders(WWWAuthenticateHeader.NAME);

            else if (challenge.getStatusCode() == Response.PROXY_AUTHENTICATION_REQUIRED)
                authHeaders = challenge.getHeaders(ProxyAuthenticateHeader.NAME);

            if (authHeaders == null)
                throw new SecurityException(
                        "Could not find WWWAuthenticate or ProxyAuthenticate headers");

            // Remove all authorization headers from the request (we'll re-add
            // them
            // from cache)
            reoriginatedRequest.removeHeader(AuthorizationHeader.NAME);
            reoriginatedRequest.removeHeader(ProxyAuthorizationHeader.NAME);
            reoriginatedRequest.removeHeader(ViaHeader.NAME);

            // rfc 3261 says that the cseq header should be augmented for the
            // new
            // request. do it here so that the new dialog (created together with
            // the new client transaction) takes it into account.
            // Bug report - Fredrik Wickstrom
            CSeqHeader cSeq = (CSeqHeader) reoriginatedRequest.getHeader((CSeqHeader.NAME));
            cSeq.setSequenceNumber(cSeq.getSequenceNumber() + 1);
            reoriginatedRequest.setHeader(cSeq);

            ClientTransaction retryTran = sipProvider.getNewClientTransaction(reoriginatedRequest);

            WWWAuthenticateHeader authHeader = null;

            while (authHeaders.hasNext()) {
                authHeader = (WWWAuthenticateHeader) authHeaders.next();
                String realm = authHeader.getRealm();

                FromHeader from = (FromHeader) reoriginatedRequest.getHeader(FromHeader.NAME);
                URI uri = from.getAddress().getURI();

                AuthorizationHeader authorization = getAuthorization(reoriginatedRequest.getMethod(),
                		reoriginatedRequest.getRequestURI().toString(),
                        reoriginatedRequest.getContent() == null ? "" : reoriginatedRequest.getContent().toString(),
                        authHeader, proxyCredentials);

                reoriginatedRequest.addHeader(authorization);

                // if there was trouble with the user - make sure we fix it
                if (uri.isSipURI()) {
                    ((SipURI) uri).setUser(proxyCredentials.getUserName());
                    Address add = from.getAddress();
                    add.setURI(uri);
                    from.setAddress(add);
                    reoriginatedRequest.setHeader(from);

                    if (challengedRequest.getMethod().equals(Request.REGISTER))
                    {
                        ToHeader to = (ToHeader) reoriginatedRequest.getHeader(ToHeader.NAME);
                        add.setURI(uri);
                        to.setAddress(add);
                        reoriginatedRequest.setHeader(to);

                    }

                    Logger.println("URI: " + uri.toString());
                }

                // if this is a register - fix to as well

            }

            return retryTran;
        }
        catch (Exception e) {
            Logger.println("ERRO REG: " + e.toString());
			e.printStackTrace();
            return null;
        }
    }

    private synchronized static AuthorizationHeader getAuthorization(String method, String uri,
                                                 String requestBody, WWWAuthenticateHeader authHeader,
                                                 ProxyCredentials proxyCredentials) throws SecurityException {
        String response = null;
        try {
            Logger.println("getAuthorization " + proxyCredentials.getAuthUserName());

            response = MessageDigestAlgorithm.calculateResponse(authHeader
                    .getAlgorithm(), proxyCredentials.getAuthUserName(),
                    authHeader.getRealm(), new String(proxyCredentials
                    .getPassword()), authHeader.getNonce(),
                    // TODO we should one day implement those two null-s
                    null,// nc-value
                    null,// cnonce
                    method, uri, requestBody, authHeader.getQop());
        }
        catch (NullPointerException exc) {
            throw new SecurityException(
                    "The authenticate header was malformatted");
        }

        AuthorizationHeader authorization = null;
        try {
            if (authHeader instanceof ProxyAuthenticateHeader) {
                authorization = headerFactory
                        .createProxyAuthorizationHeader(authHeader.getScheme());
            } else {
                authorization = headerFactory
                        .createAuthorizationHeader(authHeader.getScheme());
            }

            authorization.setUsername(proxyCredentials.getAuthUserName());
            authorization.setRealm(authHeader.getRealm());
            authorization.setNonce(authHeader.getNonce());
            authorization.setParameter("uri", uri);
            authorization.setResponse(response);

            if (authHeader.getAlgorithm() != null)
                authorization.setAlgorithm(authHeader.getAlgorithm());
            if (authHeader.getOpaque() != null)
                authorization.setOpaque(authHeader.getOpaque());

            authorization.setResponse(response);
        }
        catch (ParseException ex) {
			throw new SecurityException("Failed to create an authorization header!");
        }

        return authorization;
    }


	public class DigestServerAuthenticationMethod
	{
		private String defaultRealm;
		private final Random random;
		private final Hashtable<String, MessageDigest> algorithms;
		private final char[] toHex = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

		/**
		 * Default constructor.
		 * @param defaultRealm Realm to use when realm part is not specified in authentication headers.
		 * @param algorithms List of algorithms that can be used in authentication.
		 * @throws NoSuchAlgorithmException If one of algorithms specified in <i>algorithms</i> is not realized in current Java version.
		 */
		public DigestServerAuthenticationMethod(String defaultRealm, String[] algorithms) throws NoSuchAlgorithmException
		{
			this.defaultRealm = defaultRealm;

			this.algorithms = new Hashtable<String, MessageDigest>();
			random = new Random(System.currentTimeMillis());

			for (String algorithm : algorithms)
				this.algorithms.put(algorithm, MessageDigest.getInstance(algorithm));
		}

		/**
		 * @return The default realm that is to be used when no domain is specified in authentication headers.
		 */
		public String getDefaultRealm()
		{
			return defaultRealm;
		}

		/**
		 * @return The algorithm that is to be used when no algorithm is specified in authentication headers.
		 */
		public String getPreferredAlgorithm()
		{
			return algorithms.keys().nextElement();
		}

		/**
		 * Generate the challenge string.
		 * @param algorithm Encryption algorithm. "MD5", for example.
		 * @return a generated nonce. Empty string if specified <i>algorithm</i> is not recognized.
		 */
		public String generateNonce(String algorithm)
		{
			MessageDigest messageDigest = algorithms.get(algorithm);
			if (messageDigest == null) return "";

			// Get the time of day and run MD5 over it.
			long time = System.currentTimeMillis();
			long pad = random.nextLong();
			String nonceString = (new Long(time)).toString() + (new Long(pad)).toString();
			byte mdbytes[] = messageDigest.digest(nonceString.getBytes());
			// Convert the mdbytes array into a hex string.
			return toHexString(mdbytes);
		}

		/**
		 * Actually performs authentication of subscriber.
		 * @param authHeader Authroization header from the SIP request.
		 * @param request Request to authorize
		 * @param user Username to check with
		 * @param password to check with
		 * @return true if request is authorized, false in other case.
		 */
		public boolean doAuthenticate(Request request, AuthorizationHeader authHeader, String user, String password)
		{
			Log.info("doAuthenticate " + user + " " + password);

			String username = authHeader.getUsername();
			if (username == null || !username.equals(user))
				return false;

			String realm = authHeader.getRealm();
			if (realm == null)
				realm = defaultRealm;

			URI uri = authHeader.getURI();
			if (uri == null) return false;

			String algorithm = authHeader.getAlgorithm();
			if (algorithm == null)
				algorithm = getPreferredAlgorithm();

			MessageDigest messageDigest = algorithms.get(algorithm);
			if (messageDigest == null) return false;

			byte mdbytes[];

			String A1 = username + ":" + realm + ":" + password;
			String A2 = request.getMethod().toUpperCase() + ":" + uri.toString();
			mdbytes = messageDigest.digest(A1.getBytes());
			String HA1 = toHexString(mdbytes);
			mdbytes = messageDigest.digest(A2.getBytes());
			String HA2 = toHexString(mdbytes);

			String nonce = authHeader.getNonce();
			String cnonce = authHeader.getCNonce();
			String KD = HA1 + ":" + nonce;

			if (cnonce != null)
				KD += ":" + cnonce;

			KD += ":" + HA2;

			mdbytes = messageDigest.digest(KD.getBytes());
			String mdString = toHexString(mdbytes);
			String response = authHeader.getResponse();

			return mdString.compareTo(response) == 0;
		}

		public String toHexString(byte[] b)
		{
			int pos = 0;
			char[] c = new char[b.length * 2];

			for (int i = 0; i < b.length; i++)
			{
				c[pos++] = toHex[(b[i] >> 4) & 0x0F];
				c[pos++] = toHex[b[i] & 0x0f];
			}

			return new String(c);
		}
	}

/*
    private class CRLfKeepAliveTask extends TimerTask
    {
        @Override
        public void run()
        {
            try
            {
            	tcpListenPort.sendHeartbeat( sipAddress.getAddress().getHostAddress(), sipAddress.getPort() );
            }
            catch (IOException e)
            {
                Logger.println("Error while sending a heartbeat", e);
            }
        }
    }
*/
}
