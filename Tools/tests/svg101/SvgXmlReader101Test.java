package org.telegram.messenger;

import com.sun.net.httpserver.HttpServer;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.InputSource;
import org.xml.sax.Parser;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLFilterImpl;
import org.xml.sax.helpers.XMLReaderAdapter;

public final class SvgXmlReader101Test {
    private static int checks;
    private static boolean rejectLexicalHandler;

    public static void main(String[] args) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/external.dtd", exchange -> {
            requests.incrementAndGet();
            byte[] body = "<!ENTITY injected 'NETWORK_SECRET'>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        Path secret = Files.createTempFile("lunagram-svg-test-", ".txt");
        Files.writeString(secret, "LOCAL_SECRET", StandardCharsets.UTF_8);
        String original = System.getProperty("javax.xml.parsers.SAXParserFactory");
        try {
            String external = "http://127.0.0.1:" + server.getAddress().getPort() + "/external.dtd";
            runCases(secret.toUri().toString(), external);
            // Exercise the real JDK parser with the Apache-specific switch
            // unavailable, as on Android: only the required SAX guards remain.
            System.setProperty("javax.xml.parsers.SAXParserFactory", AndroidLikeFactory.class.getName());
            runCases(secret.toUri().toString(), external);
            check(requests.get() == 0, "no external HTTP request from either parser configuration");
            rejectLexicalHandler = true;
            try {
                LumaSvgXmlReader.create();
                throw new AssertionError("a parser without the mandatory DTD guard must not be returned");
            } catch (SAXNotRecognizedException expected) { checks++; }
        } finally {
            if (original == null) System.clearProperty("javax.xml.parsers.SAXParserFactory");
            else System.setProperty("javax.xml.parsers.SAXParserFactory", original);
            server.stop(0);
            Files.deleteIfExists(secret);
        }
        System.out.println("PASS: " + checks + " SVG XML cases, including Android-style feature fallback, DTD/entity rejection and zero external HTTP requests.");
    }

    private static void runCases(String localFile, String external) throws Exception {
        check(parse("<?xml version='1.0'?><svg xmlns='http://www.w3.org/2000/svg'><text>Луна &amp; &lt; &#9733;</text><path d='M0 0 L1 1'/></svg>")
                .equals("Луна & < ★"), "ordinary SVG, predefined entities and Unicode still parse");
        reject("<!DOCTYPE svg><svg/>");
        reject("<!DOCTYPE svg SYSTEM '" + external + "'><svg/>");
        reject("<!DOCTYPE svg [<!ENTITY injected SYSTEM '" + localFile + "'>]><svg>&injected;</svg>");
        reject("<!DOCTYPE svg [<!ENTITY injected SYSTEM '" + external + "'>]><svg>&injected;</svg>");
        reject("<!DOCTYPE svg [<!ENTITY % injected SYSTEM '" + external + "'>%injected;]><svg/>");
        reject("<!DOCTYPE svg [<!ENTITY a 'expanded'><!ENTITY b '&a;&a;&a;&a;'><!ENTITY c '&b;&b;&b;&b;'>]><svg>&c;</svg>");
        XMLReader reader = LumaSvgXmlReader.create();
        check(!reader.getFeature("http://xml.org/sax/features/external-general-entities"), "external general entities disabled");
        check(!reader.getFeature("http://xml.org/sax/features/external-parameter-entities"), "external parameter entities disabled");
    }

    private static String parse(String xml) throws Exception {
        StringBuilder text = new StringBuilder();
        XMLReader reader = LumaSvgXmlReader.create();
        reader.setContentHandler(new DefaultHandler() {
            @Override public void characters(char[] chars, int start, int length) { text.append(chars, start, length); }
        });
        reader.setErrorHandler(new DefaultHandler());
        reader.parse(new InputSource(new StringReader(xml)));
        return text.toString();
    }

    private static void reject(String xml) throws Exception {
        try {
            parse(xml);
            throw new AssertionError("DTD-containing SVG accepted");
        } catch (SAXException expected) { checks++; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static final class AndroidLikeFactory extends SAXParserFactory {
        private final SAXParserFactory delegate = SAXParserFactory.newDefaultInstance();
        @Override public void setFeature(String name, boolean value) throws javax.xml.parsers.ParserConfigurationException, org.xml.sax.SAXNotRecognizedException, org.xml.sax.SAXNotSupportedException { delegate.setFeature(name, value); }
        @Override public boolean getFeature(String name) throws javax.xml.parsers.ParserConfigurationException, org.xml.sax.SAXNotRecognizedException, org.xml.sax.SAXNotSupportedException { return delegate.getFeature(name); }
        @Override public SAXParser newSAXParser() throws javax.xml.parsers.ParserConfigurationException, SAXException {
            XMLReader reader = new XMLFilterImpl(delegate.newSAXParser().getXMLReader()) {
                @Override public void setFeature(String name, boolean value) throws org.xml.sax.SAXNotRecognizedException, org.xml.sax.SAXNotSupportedException {
                    if (name.equals("http://apache.org/xml/features/disallow-doctype-decl")) throw new SAXNotRecognizedException(name);
                    super.setFeature(name, value);
                }
                @Override public void setProperty(String name, Object value) throws org.xml.sax.SAXNotRecognizedException, org.xml.sax.SAXNotSupportedException {
                    if (rejectLexicalHandler && name.equals("http://xml.org/sax/properties/lexical-handler")) throw new SAXNotRecognizedException(name);
                    super.setProperty(name, value);
                }
            };
            return new SAXParser() {
                @Override public Parser getParser() { return new XMLReaderAdapter(reader); }
                @Override public XMLReader getXMLReader() { return reader; }
                @Override public boolean isNamespaceAware() { return false; }
                @Override public boolean isValidating() { return false; }
                @Override public void setProperty(String name, Object value) throws org.xml.sax.SAXNotRecognizedException, org.xml.sax.SAXNotSupportedException { reader.setProperty(name, value); }
                @Override public Object getProperty(String name) throws org.xml.sax.SAXNotRecognizedException, org.xml.sax.SAXNotSupportedException { return reader.getProperty(name); }
            };
        }
    }
}
