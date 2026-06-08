package com.nowin.webdav;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

final class WebDavPropertyStore {
    private final Path root;
    private final Path storeDirectory;

    WebDavPropertyStore(Path root, Path storeDirectory) {
        this.root = root.toAbsolutePath().normalize();
        this.storeDirectory = storeDirectory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storeDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize WebDAV property store", e);
        }
    }

    synchronized Map<QName, String> load(Path path) throws IOException {
        Path file = fileFor(path);
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        XMLInputFactory factory = secureInputFactory();
        Map<QName, String> properties = new LinkedHashMap<>();
        try (InputStream input = Files.newInputStream(file)) {
            var reader = factory.createXMLStreamReader(input, StandardCharsets.UTF_8.name());
            QName current = null;
            StringBuilder value = new StringBuilder();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && "property".equals(reader.getLocalName())) {
                    String namespace = reader.getAttributeValue(null, "namespace");
                    String name = reader.getAttributeValue(null, "name");
                    current = new QName(namespace != null ? namespace : "", name);
                    value.setLength(0);
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && current != null) {
                    value.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT && "property".equals(reader.getLocalName()) && current != null) {
                    properties.put(current, value.toString());
                    current = null;
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("Failed to read WebDAV properties for " + path, e);
        }
        return properties;
    }

    synchronized void set(Path path, Map<QName, String> updates) throws IOException {
        Map<QName, String> properties = load(path);
        properties.putAll(updates);
        save(path, properties);
    }

    synchronized void remove(Path path, Iterable<QName> names) throws IOException {
        Map<QName, String> properties = load(path);
        for (QName name : names) {
            properties.remove(name);
        }
        save(path, properties);
    }

    synchronized void copy(Path source, Path destination, boolean recursive) throws IOException {
        if (recursive && Files.isDirectory(source)) {
            try (var stream = Files.walk(source)) {
                for (Path current : stream.toList()) {
                    Path target = destination.resolve(source.relativize(current)).normalize();
                    copyOne(current, target);
                }
            }
            return;
        }
        copyOne(source, destination);
    }

    synchronized void move(Path source, Path destination, boolean recursive) throws IOException {
        copy(source, destination, recursive);
        delete(source, recursive);
    }

    synchronized void delete(Path path, boolean recursive) throws IOException {
        if (recursive && Files.exists(path) && Files.isDirectory(path)) {
            try (var stream = Files.walk(path)) {
                for (Path current : stream.toList()) {
                    Files.deleteIfExists(fileFor(current));
                }
            }
            return;
        }
        Files.deleteIfExists(fileFor(path));
    }

    private void copyOne(Path source, Path destination) throws IOException {
        Path sourceFile = fileFor(source);
        if (!Files.exists(sourceFile)) {
            return;
        }
        Files.createDirectories(storeDirectory);
        Files.copy(sourceFile, fileFor(destination), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private void save(Path path, Map<QName, String> properties) throws IOException {
        Path file = fileFor(path);
        if (properties.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(storeDirectory);
        XMLOutputFactory factory = XMLOutputFactory.newFactory();
        try (OutputStream output = Files.newOutputStream(file)) {
            var writer = factory.createXMLStreamWriter(output, StandardCharsets.UTF_8.name());
            writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            writer.writeStartElement("properties");
            for (Map.Entry<QName, String> entry : properties.entrySet()) {
                writer.writeStartElement("property");
                writer.writeAttribute("namespace", entry.getKey().getNamespaceURI());
                writer.writeAttribute("name", entry.getKey().getLocalPart());
                writer.writeCharacters(entry.getValue());
                writer.writeEndElement();
            }
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IOException("Failed to write WebDAV properties for " + path, e);
        }
    }

    private Path fileFor(Path path) {
        return storeDirectory.resolve(hash(relativeName(path)) + ".xml");
    }

    private String relativeName(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(root)) {
            return "/";
        }
        return root.relativize(normalized).toString().replace('\\', '/');
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

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                result.append(Character.forDigit((b >> 4) & 0xf, 16));
                result.append(Character.forDigit(b & 0xf, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
