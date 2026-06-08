package com.nowin.webdav;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WebDavXml {
    static final String DAV_NAMESPACE = "DAV:";
    static final QName DISPLAY_NAME = new QName(DAV_NAMESPACE, "displayname");
    static final QName RESOURCE_TYPE = new QName(DAV_NAMESPACE, "resourcetype");
    static final QName CONTENT_LENGTH = new QName(DAV_NAMESPACE, "getcontentlength");
    static final QName CONTENT_TYPE = new QName(DAV_NAMESPACE, "getcontenttype");
    static final QName LAST_MODIFIED = new QName(DAV_NAMESPACE, "getlastmodified");
    static final QName CREATION_DATE = new QName(DAV_NAMESPACE, "creationdate");
    static final QName ETAG = new QName(DAV_NAMESPACE, "getetag");
    static final QName SUPPORTED_LOCK = new QName(DAV_NAMESPACE, "supportedlock");
    static final QName LOCK_DISCOVERY = new QName(DAV_NAMESPACE, "lockdiscovery");

    private WebDavXml() {
    }

    static PropfindRequest parsePropfind(InputStream input) throws IOException {
        XMLStreamReader reader = reader(input);
        boolean rootSeen = false;
        PropfindMode mode = PropfindMode.ALL;
        List<QName> properties = List.of();
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                if (!rootSeen) {
                    rootSeen = true;
                    continue;
                }
                String local = reader.getLocalName();
                if ("allprop".equals(local)) {
                    mode = PropfindMode.ALL;
                    skipSubtree(reader);
                    break;
                }
                if ("propname".equals(local)) {
                    mode = PropfindMode.PROPNAME;
                    skipSubtree(reader);
                    break;
                }
                if ("prop".equals(local)) {
                    mode = PropfindMode.PROP;
                    properties = readPropChildren(reader, false);
                    break;
                }
            }
            return new PropfindRequest(mode, properties);
        } catch (XMLStreamException e) {
            throw new IOException("Invalid PROPFIND XML", e);
        }
    }

    static ProppatchRequest parseProppatch(InputStream input) throws IOException {
        XMLStreamReader reader = reader(input);
        List<PropertyUpdate> updates = new ArrayList<>();
        boolean rootSeen = false;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                if (!rootSeen) {
                    rootSeen = true;
                    continue;
                }
                String local = reader.getLocalName();
                if ("set".equals(local)) {
                    readPatchContainer(reader, true, updates);
                } else if ("remove".equals(local)) {
                    readPatchContainer(reader, false, updates);
                }
            }
            return new ProppatchRequest(updates);
        } catch (XMLStreamException e) {
            throw new IOException("Invalid PROPPATCH XML", e);
        }
    }

    static LockInfo parseLockInfo(InputStream input) throws IOException {
        XMLStreamReader reader = reader(input);
        boolean exclusive = true;
        String owner = "";
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                String local = reader.getLocalName();
                if ("exclusive".equals(local)) {
                    exclusive = true;
                    skipSubtree(reader);
                } else if ("shared".equals(local)) {
                    exclusive = false;
                    skipSubtree(reader);
                } else if ("owner".equals(local)) {
                    owner = readTextLenient(reader);
                }
            }
            return new LockInfo(exclusive, owner);
        } catch (XMLStreamException e) {
            throw new IOException("Invalid LOCK XML", e);
        }
    }

    static byte[] writeMultiStatus(List<MultiStatusResponse> responses) throws IOException {
        return writeXml(writer -> {
            writer.writeStartElement("D", "multistatus", DAV_NAMESPACE);
            writer.writeNamespace("D", DAV_NAMESPACE);
            for (MultiStatusResponse response : responses) {
                writer.writeStartElement("D", "response", DAV_NAMESPACE);
                writer.writeStartElement("D", "href", DAV_NAMESPACE);
                writer.writeCharacters(response.href());
                writer.writeEndElement();
                writePropstats(writer, response.properties());
                writer.writeEndElement();
            }
            writer.writeEndElement();
        });
    }

    static byte[] writeLockDiscoveryProperty(List<WebDavLockManager.LockRecord> locks) throws IOException {
        return writeXml(writer -> {
            writer.writeStartElement("D", "prop", DAV_NAMESPACE);
            writer.writeNamespace("D", DAV_NAMESPACE);
            writer.writeStartElement("D", "lockdiscovery", DAV_NAMESPACE);
            writeActiveLocks(writer, locks);
            writer.writeEndElement();
            writer.writeEndElement();
        });
    }

    private static void writePropstats(XMLStreamWriter writer, List<PropertyResult> properties) throws XMLStreamException {
        Map<Integer, List<PropertyResult>> grouped = new LinkedHashMap<>();
        for (PropertyResult property : properties) {
            grouped.computeIfAbsent(property.status(), ignored -> new ArrayList<>()).add(property);
        }
        for (Map.Entry<Integer, List<PropertyResult>> entry : grouped.entrySet()) {
            writer.writeStartElement("D", "propstat", DAV_NAMESPACE);
            writer.writeStartElement("D", "prop", DAV_NAMESPACE);
            for (PropertyResult property : entry.getValue()) {
                if (property.status() == 200 && property.value() != null) {
                    writePropertyValue(writer, property.value());
                } else {
                    writeEmptyProperty(writer, property.name());
                }
            }
            writer.writeEndElement();
            writer.writeStartElement("D", "status", DAV_NAMESPACE);
            writer.writeCharacters(WebDavStatus.statusLine(entry.getKey()));
            writer.writeEndElement();
            writer.writeEndElement();
        }
    }

    private static void writePropertyValue(XMLStreamWriter writer, PropertyValue value) throws XMLStreamException {
        writePropertyStart(writer, value.name());
        switch (value.kind()) {
            case TEXT -> writer.writeCharacters(value.text() != null ? value.text() : "");
            case RESOURCE_TYPE -> {
                if (value.collection()) {
                    writer.writeEmptyElement("D", "collection", DAV_NAMESPACE);
                }
            }
            case SUPPORTED_LOCK -> writeSupportedLock(writer);
            case LOCK_DISCOVERY -> writeActiveLocks(writer, value.locks());
            case EMPTY -> {
                // property name only
            }
        }
        writer.writeEndElement();
    }

    private static void writeSupportedLock(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement("D", "lockentry", DAV_NAMESPACE);
        writer.writeStartElement("D", "lockscope", DAV_NAMESPACE);
        writer.writeEmptyElement("D", "exclusive", DAV_NAMESPACE);
        writer.writeEndElement();
        writer.writeStartElement("D", "locktype", DAV_NAMESPACE);
        writer.writeEmptyElement("D", "write", DAV_NAMESPACE);
        writer.writeEndElement();
        writer.writeEndElement();

        writer.writeStartElement("D", "lockentry", DAV_NAMESPACE);
        writer.writeStartElement("D", "lockscope", DAV_NAMESPACE);
        writer.writeEmptyElement("D", "shared", DAV_NAMESPACE);
        writer.writeEndElement();
        writer.writeStartElement("D", "locktype", DAV_NAMESPACE);
        writer.writeEmptyElement("D", "write", DAV_NAMESPACE);
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private static void writeActiveLocks(XMLStreamWriter writer, List<WebDavLockManager.LockRecord> locks) throws XMLStreamException {
        for (WebDavLockManager.LockRecord lock : locks) {
            writer.writeStartElement("D", "activelock", DAV_NAMESPACE);
            writer.writeStartElement("D", "locktype", DAV_NAMESPACE);
            writer.writeEmptyElement("D", "write", DAV_NAMESPACE);
            writer.writeEndElement();
            writer.writeStartElement("D", "lockscope", DAV_NAMESPACE);
            writer.writeEmptyElement("D", lock.exclusive() ? "exclusive" : "shared", DAV_NAMESPACE);
            writer.writeEndElement();
            writer.writeStartElement("D", "depth", DAV_NAMESPACE);
            writer.writeCharacters(lock.deep() ? "Infinity" : "0");
            writer.writeEndElement();
            if (lock.owner() != null && !lock.owner().isBlank()) {
                writer.writeStartElement("D", "owner", DAV_NAMESPACE);
                writer.writeCharacters(lock.owner());
                writer.writeEndElement();
            }
            writer.writeStartElement("D", "timeout", DAV_NAMESPACE);
            writer.writeCharacters("Second-" + lock.timeoutSecondsRemaining());
            writer.writeEndElement();
            writer.writeStartElement("D", "locktoken", DAV_NAMESPACE);
            writer.writeStartElement("D", "href", DAV_NAMESPACE);
            writer.writeCharacters(lock.token());
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
        }
    }

    private static void writeEmptyProperty(XMLStreamWriter writer, QName name) throws XMLStreamException {
        writePropertyStart(writer, name);
        writer.writeEndElement();
    }

    private static void writePropertyStart(XMLStreamWriter writer, QName name) throws XMLStreamException {
        String namespace = name.getNamespaceURI();
        if (namespace == null || namespace.isEmpty()) {
            writer.writeStartElement(name.getLocalPart());
        } else if (DAV_NAMESPACE.equals(namespace)) {
            writer.writeStartElement("D", name.getLocalPart(), DAV_NAMESPACE);
        } else {
            writer.writeStartElement("Z", name.getLocalPart(), namespace);
            writer.writeNamespace("Z", namespace);
        }
    }

    private static byte[] writeXml(XmlWriteAction action) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            XMLStreamWriter writer = XMLOutputFactory.newFactory()
                    .createXMLStreamWriter(output, StandardCharsets.UTF_8.name());
            writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            action.write(writer);
            writer.writeEndDocument();
            writer.close();
            return output.toByteArray();
        } catch (XMLStreamException e) {
            throw new IOException("Failed to write WebDAV XML", e);
        }
    }

    private static void readPatchContainer(XMLStreamReader reader, boolean set, List<PropertyUpdate> updates)
            throws XMLStreamException {
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (depth == 1 && "prop".equals(reader.getLocalName())) {
                    readPatchPropChildren(reader, set, updates);
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    private static List<QName> readPropChildren(XMLStreamReader reader, boolean skipValues) throws XMLStreamException {
        List<QName> names = new ArrayList<>();
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (depth == 1) {
                    names.add(reader.getName());
                    skipSubtree(reader);
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return names;
    }

    private static void readPatchPropChildren(XMLStreamReader reader, boolean set, List<PropertyUpdate> updates)
            throws XMLStreamException {
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (depth == 1) {
                    QName name = reader.getName();
                    String value = set ? readTextLenient(reader) : "";
                    if (!set) {
                        skipSubtree(reader);
                    }
                    updates.add(new PropertyUpdate(set, name, value));
                } else {
                    depth++;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    private static String readTextLenient(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                text.append(reader.getText());
            }
        }
        return text.toString();
    }

    private static void skipSubtree(XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    private static XMLStreamReader reader(InputStream input) throws IOException {
        try {
            return secureInputFactory().createXMLStreamReader(input, StandardCharsets.UTF_8.name());
        } catch (XMLStreamException e) {
            throw new IOException("Invalid XML", e);
        }
    }

    private static XMLInputFactory secureInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setPropertyIfSupported(factory, XMLInputFactory.SUPPORT_DTD, false);
        setPropertyIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setPropertyIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        setPropertyIfSupported(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        return factory;
    }

    private static void setPropertyIfSupported(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // Some StAX implementations do not expose every hardening flag.
        }
    }

    @FunctionalInterface
    private interface XmlWriteAction {
        void write(XMLStreamWriter writer) throws XMLStreamException;
    }

    enum PropfindMode {
        ALL,
        PROP,
        PROPNAME
    }

    enum PropertyKind {
        TEXT,
        RESOURCE_TYPE,
        SUPPORTED_LOCK,
        LOCK_DISCOVERY,
        EMPTY
    }

    record PropfindRequest(PropfindMode mode, List<QName> properties) {
    }

    record ProppatchRequest(List<PropertyUpdate> updates) {
    }

    record PropertyUpdate(boolean set, QName name, String value) {
    }

    record LockInfo(boolean exclusive, String owner) {
    }

    record PropertyValue(QName name, PropertyKind kind, String text, boolean collection,
                         List<WebDavLockManager.LockRecord> locks) {
        static PropertyValue text(QName name, String text) {
            return new PropertyValue(name, PropertyKind.TEXT, text, false, List.of());
        }

        static PropertyValue resourceType(boolean collection) {
            return new PropertyValue(RESOURCE_TYPE, PropertyKind.RESOURCE_TYPE, "", collection, List.of());
        }

        static PropertyValue supportedLock() {
            return new PropertyValue(SUPPORTED_LOCK, PropertyKind.SUPPORTED_LOCK, "", false, List.of());
        }

        static PropertyValue lockDiscovery(List<WebDavLockManager.LockRecord> locks) {
            return new PropertyValue(LOCK_DISCOVERY, PropertyKind.LOCK_DISCOVERY, "", false, locks);
        }

        static PropertyValue empty(QName name) {
            return new PropertyValue(name, PropertyKind.EMPTY, "", false, List.of());
        }
    }

    record PropertyResult(QName name, int status, PropertyValue value) {
    }

    record MultiStatusResponse(String href, List<PropertyResult> properties) {
    }
}
