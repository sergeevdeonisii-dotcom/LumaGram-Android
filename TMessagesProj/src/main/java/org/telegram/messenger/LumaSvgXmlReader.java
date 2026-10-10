package org.telegram.messenger;

import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/** SVG needs neither a DTD nor any external entity or document. */
public final class LumaSvgXmlReader {
    private LumaSvgXmlReader() {}

    public static XMLReader create() throws ParserConfigurationException, SAXException {
        XMLReader reader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        DefaultHandler2 guard = new DefaultHandler2() {
            @Override
            public void startDTD(String name, String publicId, String systemId) throws SAXException {
                throw new SAXException("SVG document types are not allowed");
            }

            @Override
            public org.xml.sax.InputSource resolveEntity(String publicId, String systemId) throws SAXException {
                throw new SAXException("SVG external entities are not allowed");
            }

            @Override
            public org.xml.sax.InputSource resolveEntity(String name, String publicId, String baseURI, String systemId) throws SAXException {
                throw new SAXException("SVG external entities are not allowed");
            }
        };
        // Android's Expat reader supports this property, but not the Apache
        // disallow-doctype feature. Installation must succeed or parsing fails
        // closed; startDTD rejects internal entity expansion as well as XXE.
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", guard);
        reader.setEntityResolver(guard);
        try {
            reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (SAXNotRecognizedException | SAXNotSupportedException ignored) {
            // The required lexical handler above is the Android-safe guard.
        }
        return reader;
    }
}
