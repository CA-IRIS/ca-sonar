/*
 * SONAR -- Simple Object Notification And Replication
 * Copyright (C) 2006-2008  Minnesota Department of Transportation
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
package us.mn.state.dot.sonar.client;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Properties;
import java.util.Map;
import java.util.Set;
import javax.naming.AuthenticationException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import us.mn.state.dot.sonar.Conduit;
import us.mn.state.dot.sonar.ConfigurationError;
import us.mn.state.dot.sonar.Security;
import us.mn.state.dot.sonar.SonarObject;

/**
 * The SONAR client processes all data transfers with the server.
 *
 * @author Douglas Lau
 */
public class Client extends Thread {

	/** Selector for non-blocking I/O */
	protected final Selector selector;

	/** SSL context */
	protected final SSLContext context;

	/** Client conduit */
	protected final ClientConduit conduit;

	/** Get the connection name */
	public String getConnection() {
		return conduit.getConnection();
	}

	/** Create a new SONAR client */
	public Client(Properties props, ShowHandler handler) throws IOException,
		ConfigurationError
	{
		selector = Selector.open();
		context = Security.createContext(props);
		conduit = new ClientConduit(props, selector, createSSLEngine(),
			handler);
		setDaemon(true);
		start();
	}

	/** Create an SSL engine in the client context */
	protected SSLEngine createSSLEngine() {
		SSLEngine engine = context.createSSLEngine();
		engine.setUseClientMode(true);
		return engine;
	}

	/** Client loop to perfrom socket I/O */
	public void run() {
		while(conduit.isConnected()) {
			try {
				doSelect();
			}
			catch(Exception e) {
				e.printStackTrace();
				break;
			}
		}
	}

	/** Quit the client connection */
	public void quit() {
		conduit.quit();
	}

	/** Select and perform I/O on ready channels */
	protected void doSelect() throws IOException {
		selector.select();
		Set<SelectionKey> ready = selector.selectedKeys();
		for(SelectionKey key: ready) {
			if(key.isConnectable())
				conduit.doConnect();
			if(key.isWritable())
				conduit.doWrite();
			if(key.isReadable())
				conduit.doRead();
		}
		ready.clear();
	}

	/** Populate the specified type cache */
	public void populate(TypeCache tc) {
		tc.setConduit(conduit);
		conduit.queryAll(tc);
	}

	/** Login to the SONAR server */
	public void login(String user, String password)
		throws AuthenticationException
	{
		conduit.login(user, password);
		for(int i = 0; i < 100; i++) {
			if(conduit.isLoggedIn())
				return;
			try {
				Thread.sleep(50);
			}
			catch(InterruptedException e) {
				e.printStackTrace();
			}
		}
		throw new AuthenticationException("Login failed");
	}
}
