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
package us.mn.state.dot.sonar;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * PropertyLoader is a utility class to load property files.
 * FIXME: this class should be moved to some utility module.
 *
 * @author Douglas Lau
 */
public class PropertyLoader {

	/** Get an input stream for the specified property location */
	static protected InputStream getInputStream(String loc)
		throws IOException
	{
		try {
			return new FileInputStream(loc);
		}
		catch(FileNotFoundException e) {
			return PropertyLoader.class.getResourceAsStream(loc);
		}
	}

	/** Load properties from the specified location */
	static protected Properties _load(String loc) throws IOException {
		InputStream s = getInputStream(loc);
		Properties props = new Properties();
		if(s != null) {
			try {
				props.load(s);
				return props;
			}
			finally {
				s.close();
			}
		} else
			return props;
	}

	/** Load properties from the specified location */
	static public Properties load(String loc) throws ConfigurationError {
		try {
			return _load(loc);
		}
		catch(IOException e) {
			throw new ConfigurationError("Cannot read " + loc);
		}
	}
}
