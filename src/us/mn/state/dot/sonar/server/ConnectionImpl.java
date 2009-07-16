/*
 * SONAR -- Simple Object Notification And Replication
 * Copyright (C) 2006-2009  Minnesota Department of Transportation
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
package us.mn.state.dot.sonar.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.BufferOverflowException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import javax.net.ssl.SSLException;
import us.mn.state.dot.sonar.Conduit;
import us.mn.state.dot.sonar.Connection;
import us.mn.state.dot.sonar.FlushError;
import us.mn.state.dot.sonar.Message;
import us.mn.state.dot.sonar.Name;
import us.mn.state.dot.sonar.Namespace;
import us.mn.state.dot.sonar.NamespaceError;
import us.mn.state.dot.sonar.ProtocolError;
import us.mn.state.dot.sonar.SonarException;
import us.mn.state.dot.sonar.SonarObject;
import us.mn.state.dot.sonar.SSLState;
import us.mn.state.dot.sonar.User;

/**
 * A connection encapsulates the state of one client connection on the server.
 *
 * @author Douglas Lau
 */
public class ConnectionImpl extends Conduit implements Connection {

	/** Check if a name is watch positive. This means that the name can be
	 * used as a positive entry in the watching set. */
	static protected boolean isWatchPositive(Name name) {
		return name.isType() || name.isObject();
	}

	/** Check if a name is watch negative. This means that the name can
	 * be used as a negative entry in the watching set. */
	static protected boolean isWatchNegative(Name name) {
		return name.isAttribute() && name.getObjectPart().equals("");
	}

	/** Define the set of valid messages from a client connection */
	static protected final EnumSet<Message> MESSAGES = EnumSet.of(
		Message.LOGIN, Message.QUIT, Message.ENUMERATE, Message.IGNORE,
		Message.OBJECT, Message.REMOVE, Message.ATTRIBUTE);

	/** Lookup a message from the specified message code */
	static protected Message lookupMessage(char code) throws ProtocolError {
		for(Message m: MESSAGES)
			if(code == m.code)
				return m;
		throw ProtocolError.INVALID_MESSAGE_CODE;
	}

	/** Random number generator for session IDs */
	static protected final Random RAND = new Random();

	/** Create a new session ID */
	static protected long createSessionId() {
		return RAND.nextLong();
	}

	/** Get the SONAR type name */
	public String getTypeName() {
		return SONAR_TYPE;
	}

	/** Client host name and port */
	protected final String hostport;

	/** Get the SONAR object name */
	public String getName() {
		return hostport;
	}

	/** User logged in on the connection.
	 * May be null (before a successful login). */
	protected UserImpl user;

	/** Get the user logged in on the connection.
	 * May be null (before a successful login). */
	public User getUser() {
		return user;
	}

	/** Session ID (random cookie) */
	protected final long sessionId;

	/** Get the SONAR session ID */
	public long getSessionId() {
		return sessionId;
	}

	/** Server for the connection */
	protected final Server server;

	/** SONAR namepsace */
	protected final ServerNamespace namespace;

	/** Selection key for the socket channel */
	protected final SelectionKey key;

	/** Channel to client */
	protected final SocketChannel channel;

	/** SSL state for encrypting network data */
	protected final SSLState state;

	/** Set of names the connection is watching */
	protected final Set<String> watching = new HashSet<String>();

	/** Phantom object for setting attributes before storing a new object
	 * in the database. */
	protected SonarObject phantom;

	/** Create a new connection */
	public ConnectionImpl(Server s, SelectionKey k, SocketChannel c)
		throws SSLException
	{
		server = s;
		namespace = server.getNamespace();
		key = k;
		channel = c;
		state = new SSLState(this, server.createSSLEngine(), true);
		StringBuilder h = new StringBuilder();
		h.append(c.socket().getInetAddress().getHostAddress());
		h.append(':');
		h.append(c.socket().getPort());
		hostport = h.toString();
		user = null;
		connected = true;
		sessionId = createSessionId();
	}

	/** Start watching the specified name */
	protected void startWatching(Name name) {
		synchronized(watching) {
			watching.remove(name.toString());
			if(isWatchPositive(name))
				watching.add(name.toString());
		}
	}

	/** Stop watching the specified name */
	protected void stopWatching(Name name) {
		synchronized(watching) {
			watching.remove(name.toString());
			if(isWatchNegative(name))
				watching.add(name.toString());
		}
	}

	/** Check if the connection is watching a name */
	protected boolean isWatching(Name name) {
		synchronized(watching) {
			// Object watch is highest priority (positive)
			if(watching.contains(name.getObjectName()))
				return true;
			// Attribute watch is middle priority (negative)
			if(watching.contains(name.getAttributeName()))
				return false;
			// Type watch is lowest priority (positive)
			return watching.contains(name.getTypePart());
		}
	}

	/** Notify the client of a new object being added */
	protected void notifyObject(SonarObject o) {
		try {
			namespace.enumerateObject(state.encoder, o);
			flush();
		}
		catch(SonarException e) {
			disconnect("Notify error: " + e.getMessage());
		}
	}

	/** Notify the client of a new object being added */
	void notifyObject(Name name, SonarObject o) {
		if(isWatching(name))
			notifyObject(o);
	}

	/** Notify the client of an attribute change */
	void notifyAttribute(Name name, String[] params) {
		if(isWatching(name))
			notifyAttribute(name.toString(), params);
	}

	/** Notify the client of an attribute change */
	protected void notifyAttribute(String name, String[] params) {
		try {
			state.encoder.encode(Message.ATTRIBUTE, name, params);
			flush();
		}
		catch(FlushError e) {
			disconnect("Flush error: notifyAttribute " + name);
		}
	}

	/** Notify the client of a name being removed */
	void notifyRemove(Name name) {
		if(isWatching(name)) {
			notifyRemove(name.toString());
			stopWatching(name);
		}
	}

	/** Notify the client of a name being removed */
	protected void notifyRemove(String name) {
		try {
			state.encoder.encode(Message.REMOVE, name);
			flush();
		}
		catch(FlushError e) {
			disconnect("Flush error: notifyRemove " + name);
		}
	}

	/** Read messages from the socket channel */
	void doRead() throws IOException {
		int nbytes;
		ByteBuffer net_in = state.getNetInBuffer();
		synchronized(net_in) {
			nbytes = channel.read(net_in);
		}
		if(nbytes > 0)
			server.processMessages(this);
		else if(nbytes < 0)
			throw new IOException("EOF");
	}

	/** Write pending data to the socket channel */
	void doWrite() throws IOException {
		ByteBuffer net_out = state.getNetOutBuffer();
		synchronized(net_out) {
			net_out.flip();
			channel.write(net_out);
			if(!net_out.hasRemaining())
				disableWrite();
			net_out.compact();
		}
	}

	/** Disconnect the client connection */
	protected void disconnect(String msg) {
		super.disconnect(msg);
		System.err.println("SONAR: " + msg + " on " + getName() + 
			", " + (user == null ? "user=null" : user.getName()) +
			", " + new Date() + ".");
		synchronized(watching) {
			watching.clear();
		}
		server.disconnect(key);
		try {
			channel.close();
		}
		catch(IOException e) {
			System.err.println("SONAR: Close error: " +
				e.getMessage() + " on " + getName());
		}
	}

	/** Destroy the connection */
	public void destroy() {
		if(isConnected())
			disconnect("Connection destroyed");
	}

	/** Process any incoming messages */
	void processMessages() {
		if(!isConnected())
			return;
		try {
			_processMessages();
		}
		catch(SSLException e) {
			disconnect("SSL error " + e.getMessage());
		}
		catch(FlushError e) {
			disconnect("Flush error: processMessages");
		}
	}

	/** Process any incoming messages */
	protected void _processMessages() throws SSLException, FlushError {
		while(state.doRead()) {
			List<String> params = state.decoder.decode();
			while(params != null) {
				processMessage(params);
				params = state.decoder.decode();
			}
		}
		flush();
	}

	/** Process one message from the client */
	protected void processMessage(List<String> params)
		throws FlushError
	{
		try {
			if(params.size() > 0)
				_processMessage(params);
		}
		catch(SonarException e) {
			state.encoder.encode(Message.SHOW, e.getMessage());
		}
	}

	/** Process one message from the client */
	protected void _processMessage(List<String> params)
		throws SonarException
	{
		String c = params.get(0);
		if(c.length() != 1)
			throw ProtocolError.INVALID_MESSAGE_CODE;
		Message m = lookupMessage(c.charAt(0));
		m.handle(this, params);
	}

	/** Check that the client is logged in */
	protected void checkLoggedIn() throws SonarException {
		if(user == null)
			throw ProtocolError.AUTHENTICATION_REQUIRED;
	}

	/** Lookup a user by name */
	protected UserImpl lookupUser(String n) throws PermissionDenied {
		UserImpl u = (UserImpl)namespace.lookupObject(User.SONAR_TYPE,
			n);
		if(u != null)
			return u;
		else
			throw PermissionDenied.AUTHENTICATION_FAILED;
	}

	/** Check if the specified name refers to the phantom object */
	protected boolean isPhantom(Name name) {
		return phantom != null &&
		       phantom.getTypeName().equals(name.getTypePart()) &&
		       phantom.getName().equals(name.getObjectPart());
	}

	/** Get the specified object (either phantom or new object) */
	protected SonarObject getObject(Name name) throws SonarException {
		if(isPhantom(name))
			return phantom;
		else
			return namespace.createObject(name);
	}

	/** Create a new object in the server namespace */
	protected void createObject(Name name) throws SonarException {
		SonarObject o = getObject(name);
		server.doCreateObject(o);
		phantom = null;
	}

	/** Set the value of an attribute */
	protected void setAttribute(Name name, List<String> params)
		throws SonarException
	{
		String[] v = new String[params.size() - 2];
		for(int i = 0; i < v.length; i++)
			v[i] =  params.get(i + 2);
		if(isPhantom(name))
			namespace.setAttribute(name, v, phantom);
		else {
			phantom = namespace.setAttribute(name, v);
			if(phantom == null)
				server.notifyAttribute(name, v);
		}
	}

	/** Start writing data to client */
	protected void startWrite() throws IOException {
		if(state.doWrite())
			server.flush(this);
	}

	/** Tell the I/O thread to flush the output buffer */
	public void flush() {
		try {
			if(isConnected())
				startWrite();
		}
		catch(BufferOverflowException e) {
			disconnect("Buffer overflow error");
		}
		catch(IOException e) {
			disconnect("I/O error: " + e.getMessage());
		}
	}

	/** Enable writing data back to the client */
	public void enableWrite() {
		key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
		key.selector().wakeup();
	}

	/** Disable writing data back to the client */
	public void disableWrite() {
		key.interestOps(SelectionKey.OP_READ);
	}

	/** Respond to a LOGIN message */
	public void doLogin(List<String> params) throws SonarException {
		if(user != null)
			throw ProtocolError.ALREADY_LOGGED_IN;
		if(params.size() != 3)
			throw ProtocolError.WRONG_PARAMETER_COUNT;
		String name = params.get(1);
		String password = params.get(2);
		UserImpl u;
		try {
			u = lookupUser(name);
			server.getAuthenticator().authenticate(u.getDn(),
				password.toCharArray());
		} catch(SonarException ex) {
			System.err.println("SONAR: authentication failure for "
				+ name + ", from " + getName() + ", "
				+ new Date() + ".");
			throw ex;
		}
		System.err.println("SONAR: Login " + name + " from " +
			getName() + ", " + new Date() + ".");
		user = u;
		// The first TYPE message indicates a successful login
		state.encoder.encode(Message.TYPE);
		// Send the connection name to the client first
		state.encoder.encode(Message.SHOW, hostport);
		server.setAttribute(this, "user");
	}

	/** Respond to a QUIT message */
	public void doQuit(List<String> params) {
		disconnect("Client QUIT");
	}

	/** Respond to an ENUMERATE message */
	public void doEnumerate(List<String> params) throws SonarException {
		checkLoggedIn();
		if(params.size() > 2)
			throw ProtocolError.WRONG_PARAMETER_COUNT;
		Name name;
		if(params.size() > 1)
			name = new Name(params.get(1));
		else
			name = new Name("");
		if(!namespace.canRead(user, name))
			throw PermissionDenied.create(name);
		startWatching(name);
		namespace.enumerate(name, state.encoder);
	}

	/** Respond to an IGNORE message */
	public void doIgnore(List<String> params) throws SonarException {
		checkLoggedIn();
		if(params.size() != 2)
			throw ProtocolError.WRONG_PARAMETER_COUNT;
		Name name = new Name(params.get(1));
		stopWatching(name);
	}

	/** Respond to an OBJECT message */
	public void doObject(List<String> params) throws SonarException {
		checkLoggedIn();
		if(params.size() != 2)
			throw ProtocolError.WRONG_PARAMETER_COUNT;
		Name name = new Name(params.get(1));
		if(name.isObject()) {
			if(!namespace.canAdd(user, name))
				throw PermissionDenied.create(name);
			createObject(name);
		} else
			throw NamespaceError.NAME_INVALID;
	}

	/** Respond to a REMOVE message */
	public void doRemove(List<String> params) throws SonarException {
		checkLoggedIn();
		if(params.size() != 2)
			throw ProtocolError.WRONG_PARAMETER_COUNT;
		Name name = new Name(params.get(1));
		if(!namespace.canRemove(user, name))
			throw PermissionDenied.create(name);
		SonarObject obj = namespace.lookupObject(name);
		if(obj != null) {
			namespace.removeObject(obj);
			server.notifyRemove(name);
		} else
			throw NamespaceError.NAME_INVALID;
	}

	/** Respond to an ATTRIBUTE message */
	public void doAttribute(List<String> params) throws SonarException {
		checkLoggedIn();
		if(params.size() < 2)
			throw ProtocolError.WRONG_PARAMETER_COUNT;
		Name name = new Name(params.get(1));
		if(name.isAttribute()) {
			if(!namespace.canUpdate(user, name))
				throw PermissionDenied.create(name);
			setAttribute(name, params);
		} else
			throw NamespaceError.NAME_INVALID;
	}
}
