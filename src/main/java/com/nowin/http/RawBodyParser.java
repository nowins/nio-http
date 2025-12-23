package com.nowin.http;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class RawBodyParser implements BodyParser {

    private enum State {
        INITIAL,
        COMPLETE,
        ERROR,
    }

    private final long contentLength;
    private final long sizeThreshold;
    private long bytesRead = 0;
    private State state = State.INITIAL;

    private OutputStream dataStream;
    private byte[] inMemoryData;
    private File tempFile;
    private IOException error;

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
                this.error = e;
                this.state = State.ERROR;
                throw new RuntimeException("Failed to create temp file for raw body.", e);
            }
        }
    }

    @Override
    public void parse(ByteBuffer buffer, Map<String, String> headers) throws IOException {
        if (state == State.ERROR) {
            throw new IOException("Parser is in error state", error);
        }

        // If we've already read the expected amount of data, and there's still data in the buffer, that's an error
        if (state == State.COMPLETE && buffer.hasRemaining()) {
            state = State.ERROR;
            error = new IOException("Received more data than expected content length");
            throw error;
        }

        // Process data from buffer up to the expected content length
        while (buffer.hasRemaining() && bytesRead < contentLength) {
            int bytesToWrite = (int) Math.min(buffer.remaining(), contentLength - bytesRead);
            // if the buffer has an underlying array, use it
            if (buffer.hasArray()) {
                dataStream.write(buffer.array(), buffer.position(), bytesToWrite);
                buffer.position(buffer.position() + bytesToWrite);
            } else {
                // otherwise, use a temporary array
                byte[] data = new byte[Math.min(bytesToWrite, 8192)];
                while (bytesToWrite > 0) {
                    int chunkSize = Math.min(data.length, bytesToWrite);
                    buffer.get(data, 0, chunkSize);
                    dataStream.write(data, 0, chunkSize);
                    bytesToWrite -= chunkSize;
                }
            }
            bytesRead += bytesToWrite;
        }

        // After processing, check if we've read the expected amount and if there's still more data in the buffer
        if (bytesRead > contentLength) {
            // This shouldn't happen with the current logic, but keeping for safety
            state = State.ERROR;
            error = new IOException("Received more data than expected content length");
            throw error;
        } else if (bytesRead == contentLength) {
            // Check if there are still remaining bytes in the original buffer after reading expected amount
            // This happens when the buffer contains more data than expected content length
            if (buffer.hasRemaining()) {
                state = State.ERROR;
                error = new IOException("Received more data than expected content length");
                throw error;
            }
            
            finish();
            state = State.COMPLETE;
        }
        // If bytesRead < contentLength, we expect more data later, so do nothing here
    }

    @Override
    public void populate(HttpRequest request) {
        if (inMemoryData != null) {
            request.setBody(inMemoryData);
        } else if (tempFile != null && tempFile.exists()) {
            // For file-backed parser, the file is already available via getTempFile()
            request.setTempBodyFile(tempFile);
        }
    }

    @Override
    public boolean isComplete() {
        return state == State.COMPLETE;
    }

    @Override
    public boolean hasError() {
        return state == State.ERROR || (error != null);
    }

    private void finish() throws IOException {
        try {
            dataStream.close();
            if (dataStream instanceof ByteArrayOutputStream) {
                this.inMemoryData = ((ByteArrayOutputStream) dataStream).toByteArray();
            }
        } catch (IOException e) {
            this.error = e;
            this.state = State.ERROR;
            throw e;
        }
    }

    public byte[] getInMemoryData() {
        return inMemoryData;
    }

    public File getTempFile() {
        return tempFile;
    }
    
    public IOException getError() {
        return error;
    }
}