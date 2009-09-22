/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.parser.mbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.XHTMLContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Mbox (mailbox) parser. This version returns the headers via metadata, which means it
 * assumes that it's getting called with a single email for the mbox - it will skip
 * any after the first.
 */
public class MboxParser implements Parser {
	private static final Logger LOGGER = Logger.getLogger(MboxParser.class);
	
	public static final String MBOX_MIME_TYPE = "application/mbox";
	public static final String MBOX_RECORD_DIVIDER = "From ";

	private static final String EMAIL_HEADER_METADATA_PREFIX = MboxParser.class.getSimpleName() + "-";

	private static final Pattern EMAIL_HEADER_PATTERN = Pattern.compile("([^ ]+):[ \t]*(.*)");
	
	private enum ParseStates {
		START,
		IN_HEADER,
		IN_CONTENT
	}
	
	public void parse(InputStream stream, ContentHandler handler,
			Metadata metadata, Map<String, Object> context) throws IOException,
			SAXException, TikaException {
		
		InputStreamReader isr;
		try {
			isr = new InputStreamReader(stream, "us-ascii");
		} catch (UnsupportedEncodingException e) {
			LOGGER.error("Unexpected exception setting up MboxParser", e);
			isr = new InputStreamReader(stream);
		}
		
		BufferedReader reader = new BufferedReader(isr);
		
        metadata.set(Metadata.CONTENT_TYPE, MBOX_MIME_TYPE);
        metadata.set(Metadata.CONTENT_ENCODING, "us-ascii");
        // TODO KKr - set other static/default metadata values
        
        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        xhtml.startDocument();
        
        ParseStates parseState = ParseStates.START;
        String multiLine = null;
        boolean inQuote = false;
        
        // We're going to scan, line-by-line, for a line that starts with "From "
        for (String curLine = reader.readLine(); curLine != null; curLine = reader.readLine()) {
        	boolean newMessage = curLine.startsWith(MBOX_RECORD_DIVIDER);

        	switch (parseState) {
        	case START:
        		if (newMessage) {
        			parseState = ParseStates.IN_HEADER;
        			newMessage = false;
        			// Fall through to IN_HEADER
        		} else {
        			break;
        		}

        	case IN_HEADER:
        		if (newMessage) {
        			setMetadataFromEmailHeader(metadata, multiLine);
        			xhtml.endDocument();
        			return;
        		} else if (curLine.length() == 0) {
        			// Blank line is signal that we're transitioning to the content.
        			setMetadataFromEmailHeader(metadata, multiLine);
        			parseState = ParseStates.IN_CONTENT;
        			xhtml.startElement("p");
        		} else if (curLine.startsWith(" ") || curLine.startsWith("\t")) {
        			multiLine += " " + curLine.trim();
        		} else {
        			setMetadataFromEmailHeader(metadata, multiLine);
        			multiLine = curLine;
        		}

        		break;

        		// TODO KKr - use real email parsing support so we can correctly handle
        		// things like multipart messages and quoted-printable encoding
        	case IN_CONTENT:
        		if (newMessage) {
                	if (inQuote) {
                		xhtml.endElement("q");
                	}

                	xhtml.endElement("p");
        			xhtml.endDocument();
        			return;
        		} else {
        			boolean quoted = curLine.startsWith(">");
        			if (inQuote) {
        				if (!quoted) {
        					xhtml.endElement("q");
        					inQuote = false;
        				}
        			} else if (quoted) {
        				xhtml.startElement("q");
        				inQuote = true;
        			}
        			
        			xhtml.characters(curLine);
        			xhtml.element("br", "");
        		}
        	}
        }
        
        if (parseState == ParseStates.IN_HEADER) {
			setMetadataFromEmailHeader(metadata, multiLine);
        } else if (parseState == ParseStates.IN_CONTENT) {
        	if (inQuote) {
        		xhtml.endElement("q");
        	}
        	
        	xhtml.endElement("p");
        }

        xhtml.endDocument();
	}
	
    private void setMetadataFromEmailHeader(Metadata metadata, String curLine) {
    	if (curLine == null) {
    		return;
    	} else if (curLine.startsWith(MBOX_RECORD_DIVIDER)) {
    		metadata.add(EMAIL_HEADER_METADATA_PREFIX + "from", curLine.substring(MBOX_RECORD_DIVIDER.length()));
    		return;
    	}
    	
    	Matcher headerMatcher = EMAIL_HEADER_PATTERN.matcher(curLine);
    	if (!headerMatcher.matches()) {
    		LOGGER.warn("Malformed email header in mbox file: " + curLine);
    		return;
    	}
    	
    	String headerTag = headerMatcher.group(1).toLowerCase();
    	String headerContent = headerMatcher.group(2);

    	if (headerTag.equalsIgnoreCase("From")) {
    		metadata.add(Metadata.AUTHOR, headerContent);
    		metadata.add(Metadata.CREATOR, headerContent);
    	} else if (headerTag.equalsIgnoreCase("Subject")) {
    		metadata.add(Metadata.SUBJECT, headerContent);
    		metadata.add(Metadata.TITLE, headerContent);
    	} else if (headerTag.equalsIgnoreCase("Date")) {
    		// TODO KKr - parse and convert to ISO format YYYY-MM-DD
    		metadata.add(Metadata.DATE, headerContent);
    	} else if (headerTag.equalsIgnoreCase("Message-Id")) {
    		metadata.add(Metadata.IDENTIFIER, headerContent);
    	} else if (headerTag.equalsIgnoreCase("In-Reply-To")) {
    		metadata.add(Metadata.RELATION, headerContent);
    	} else if (headerTag.equalsIgnoreCase("Content-Type")) {
        	// TODO KKr - key off content-type in headers to
        	// set mapping to use for content and convert if necessary.		

    		metadata.add(Metadata.CONTENT_TYPE, headerContent);
    		metadata.add(Metadata.FORMAT, headerContent);
    	} else {
    		metadata.add(EMAIL_HEADER_METADATA_PREFIX + headerTag, headerContent);
    	}
	}

	@SuppressWarnings("unchecked")
	public void parse(
            InputStream stream, ContentHandler handler, Metadata metadata)
            throws IOException, SAXException, TikaException {
    	parse(stream, handler, metadata, Collections.EMPTY_MAP);
    }

}
