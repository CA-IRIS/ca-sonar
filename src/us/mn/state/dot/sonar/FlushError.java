/*
 * SONAR -- Simple Object Notification And Replication
 * Copyright (C) 2008-2009  Minnesota Department of Transportation
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
package us.mn.state.dot.sonar;

/**
 * This exception indicates an error flushing data to a conduit.
 *
 * @author Douglas Lau
 */
public class FlushError extends SonarException {

	/** Create a new flush error */
	public FlushError(String m) {
		super("Flush error: " + m);
	}
}
