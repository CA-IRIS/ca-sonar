/*
 * IRIS -- Intelligent Roadway Information System
 * Copyright (C) 2006-2012  Minnesota Department of Transportation
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

import java.util.Hashtable;
import java.util.LinkedList;
import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;

/**
 * Simple class to authenticate a user with an LDAP server.
 *
 * @author Douglas Lau
 */
public class Authenticator {

	/** Check that a DN is sane */
	static protected boolean isDnSane(String dn) {
		return dn != null && dn.length() > 0;
	}

	/** Check that a password is sane */
	static protected boolean isPasswordSane(char[] pwd) {
		return pwd != null && pwd.length > 0;
	}

	/** Authentication provider */
	public interface Provider {
		void authenticate(String dn, char[] pwd)
			throws AuthenticationException, NamingException;
	}

	/** Bypass provider (for debugging) */
	static protected class BypassProvider implements Provider {
		public void authenticate(String dn, char[] pwd) { }
	}

	/** LDAP provider */
	static protected class LDAPProvider implements Provider {

		/** Environment for creating a directory context */
		protected final Hashtable<String, Object> env =
			new Hashtable<String, Object>();

		/** Create a new LDAP provider */
		protected LDAPProvider(String url) {
			env.put(Context.INITIAL_CONTEXT_FACTORY,
				"com.sun.jndi.ldap.LdapCtxFactory");
			env.put(Context.PROVIDER_URL, url);
			env.put("com.sun.jndi.ldap.connect.timeout", "5000");
			if(url.startsWith("ldaps")) {
				env.put(Context.SECURITY_PROTOCOL, "ssl");
				env.put("java.naming.ldap.factory.socket",
					LDAPSocketFactory.class.getName());
			}
		}

		/** Get a string representation of the provider (URL) */
		public String toString() {
			Object url = env.get(Context.PROVIDER_URL);
			if(url != null)
				return url.toString();
			else
				return "";
		}

		/** Authenticate a user's credentials */
		public void authenticate(String dn, char[] pwd)
			throws AuthenticationException, NamingException
		{
			env.put(Context.SECURITY_PRINCIPAL, dn);
			env.put(Context.SECURITY_CREDENTIALS, pwd);
			try {
				InitialDirContext ctx =
					new InitialDirContext(env);
				ctx.close();
			}
			finally {
				// We shouldn't keep these around
				env.remove(Context.SECURITY_PRINCIPAL);
				env.remove(Context.SECURITY_CREDENTIALS);
			}
		}
	}

	/** Create an authentication provider */
	static private Provider createProvider(String url) {
		if(url.equals("bypass_authentication"))
			return new BypassProvider();
		else
			return new LDAPProvider(url);
	}

	/** List of LDAP providers */
	protected final LinkedList<Provider> providers =
		new LinkedList<Provider>();

	/** Create a new user authenticator */
	public Authenticator(String urls) {
		for(String url: urls.split("[ \t]+"))
			providers.add(createProvider(url));
	}

	/** Authenticate a user's credentials */
	public void authenticate(String dn, char[] pwd) throws PermissionDenied
	{
		if(isDnSane(dn) && isPasswordSane(pwd)) {
			for(Provider p: providers) {
				try {
					p.authenticate(dn, pwd);
					return;
				}
				catch(AuthenticationException e) {
					// Try next provider
				}
				catch(NamingException e) {
					System.err.println("SONAR: " +
						namingMessage(e) + " on " + p);
					// Try next provider
				}
			}
		}
		throw PermissionDenied.AUTHENTICATION_FAILED;
	}

	/** Get a useful message string from a naming exception */
	static protected String namingMessage(NamingException e) {
		Throwable c = e.getCause();
		return (c != null)
			? c.getMessage()
			: e.getClass().getSimpleName();
	}
}