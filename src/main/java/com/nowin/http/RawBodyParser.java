package com.nowin.http;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

public class RawBodyParser implements BodyParser {

    private final long contentLength;
    private final long sizeThreshold;
    private long bytesRead = 0;

    private OutputStream dataStream;
    private byte[] inMemoryData;
    private File tempFile;

    public RawBodyParser(long contentLength, long sizeThreshold) {
        this.contentLength = contentLength;
        this.sizeThreshold = sizeThreshold;

        if (contentLength <= sizeThreshold) {
            this.dataStream = new ByteArrayOutputStream((int) contentLength);
        } else {
            try {
                Path tempFilePath = Files.createTempFile("http-raw-body-", ".tmp");
                this.tempFile = tempFilePath.toFile();
                this.dataStream = new FileOutputStream(this.tempFile);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create temp file for raw body.", e);
            }
        }
    }

    @Override
    public boolean parse(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining() && bytesRead < contentLength) {
            int bytesToWrite = (int) Math.min(buffer.remaining(), contentLength - bytesRead);
            byte[] data = new byte[bytesToWrite];
            buffer.get(data);
            dataStream.write(data);
            bytesRead += bytesToWrite;
        }

        if (bytesRead >= contentLength) {
            finish();
            return true;
        }
        return false;
    }

    @Override
    public void populate(HttpRequest request) {
        request.setBody(inMemoryData);
        request.setTempBodyFile(tempFile);
    }

    private void finish() throws IOException {
        dataStream.close();
        if (dataStream instanceof ByteArrayOutputStream) {
            this.inMemoryData = ((ByteArrayOutputStream) dataStream).toByteArray();
        }
    }

    public byte[] getInMemoryData() {
        return inMemoryData;
    }

    public File getTempFile() {
        return tempFile;
    }
}
