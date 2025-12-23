package com.nowin.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * BodyParser implementation for handling chunked transfer encoding requests
 * according to RFC 7230 section 4.1
 */
public class ChunkedBodyParser implements BodyParser {

    private static final Logger logger = LoggerFactory.getLogger(ChunkedBodyParser.class);
    private static final byte CR = '\r';
    private static final byte LF = '\n';

    public enum State {
        CHUNK_SIZE, CHUNK_DATA, CHUNK_END, LAST_CHUNK_END, CHUNK_TRAILERS, COMPLETE, ERROR
    }

    private final long sizeThreshold;
    private State state = State.CHUNK_SIZE;
    private long currentChunkSize = 0;
    private long bytesReadInChunk = 0;
    private long totalBytesRead = 0;

    private Map<String, String> headers;
    private OutputStream dataStream;
    private byte[] inMemoryData;
    private File tempFile;
    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
    private static final int MAX_LINE_LENGTH = 8192; // 8KB max for trailer lines

    public ChunkedBodyParser(long sizeThreshold) {
        this.sizeThreshold = sizeThreshold;
        // Initialize with in-memory storage, will switch to file if needed
        this.dataStream = new ByteArrayOutputStream();
    }

    @Override
    public void parse(ByteBuffer buffer, Map<String, String> headers) throws IOException {
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer cannot be null");
        }
        if (headers == null) {
            throw new IllegalArgumentException("Headers cannot be null");
        }
        
        this.headers = headers;
        while (state != State.COMPLETE && state != State.ERROR) {
            // Record the initial buffer position
            int initialPosition = buffer.position();

            switch (state) {
                case CHUNK_SIZE -> {
                    parseChunkSize(buffer);
                }
                case CHUNK_DATA -> {
                    parseChunkData(buffer);
                }
                case CHUNK_END -> {
                    parseChunkEnd(buffer);
                }
                case LAST_CHUNK_END -> {
                    parseLastChunkEnd(buffer);
                }
                case CHUNK_TRAILERS -> {
                    parseTrailers(buffer);
                }
            }

            // If we didn't read any data and buffer has no remaining data,
            // break out of the loop to avoid infinite loop
            if (buffer.position() == initialPosition && !buffer.hasRemaining()) {
                break;
            }
        }

        // If we've processed all available data and haven't completed,
        // check if we're at the final state after last chunk
        if (state == State.LAST_CHUNK_END && !buffer.hasRemaining()) {
            // If we're waiting for trailers and no more data is available,
            // consider the parsing complete
            if (currentChunkSize == 0) {
                state = State.COMPLETE;
                finish();
            }
        }
        
        // If we're in error state, clean up resources
        if (state == State.ERROR) {
            cleanupOnError();
        }
    }

    /**
     * Parse the chunk size line
     * Format: [chunk-size][chunk-extensions]CRLF
     */
    private void parseChunkSize(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            byte b = buffer.get();

            if (b == CR) {
                // Check if next byte is LF
                if (buffer.hasRemaining()) {
                    byte next = buffer.get();
                    if (next == LF) {
                        // Complete chunk size line
                        String chunkSizeLine = lineBuffer.toString(StandardCharsets.US_ASCII);
                        lineBuffer.reset();

                        // Parse chunk size (support for extensions after ;)
                        int semicolonIndex = chunkSizeLine.indexOf(';');
                        String chunkSizeStr = semicolonIndex > 0 ?
                                chunkSizeLine.substring(0, semicolonIndex).trim() :
                                chunkSizeLine.trim();

                        // Handle empty chunk size string - this is actually valid for the final chunk
                        // or might happen if there are multiple newlines
                        if (chunkSizeStr.isEmpty()) {
                            currentChunkSize = 0;
                        } else {
                            try {
                                currentChunkSize = Long.parseLong(chunkSizeStr, 16);
                            } catch (NumberFormatException e) {
                                logger.error("Invalid chunk size format: {}", chunkSizeStr);
                                state = State.ERROR;
                                cleanupOnError();
                                return;
                            }
                        }

                        if (currentChunkSize == 0) {
                            // Last chunk, need to read CRLF after it
                            if (headers.containsKey("trailer")) {
                                state = State.CHUNK_TRAILERS;
                            } else {
                                state = State.LAST_CHUNK_END;
                            }
                        } else {
                            // Prepare for reading chunk data
                            state = State.CHUNK_DATA;
                            bytesReadInChunk = 0;
                            // Check if we need to switch to file storage
                            if (totalBytesRead + currentChunkSize > sizeThreshold && !(dataStream instanceof FileOutputStream)) {
                                switchToFileStorage();
                            }
                            
                            // Check for potential overflow in total bytes read
                            if (totalBytesRead > Long.MAX_VALUE - currentChunkSize) {
                                logger.error("Total bytes read would overflow: {} + {}", totalBytesRead, currentChunkSize);
                                state = State.ERROR;
                                cleanupOnError();
                                return;
                            }
                            
                            // Check for potential integer overflow in chunk size
                            if (currentChunkSize > Integer.MAX_VALUE) {
                                logger.error("Chunk size too large: {}", currentChunkSize);
                                state = State.ERROR;
                                cleanupOnError();
                                return;
                            }
                        }
                        return;
                    } else {
                        // Not CRLF, put back the byte
                        buffer.position(buffer.position() - 1);
                        lineBuffer.write(b);
                    }
                } else {
                    // Need more data, put back the CR
                    buffer.position(buffer.position() - 1);
                    return;
                }
            } else {
                lineBuffer.write(b);
                
                // Check if line buffer exceeds maximum allowed length
                if (lineBuffer.size() > MAX_LINE_LENGTH) {
                    logger.error("Chunk size line exceeds maximum allowed length of {}", MAX_LINE_LENGTH);
                    state = State.ERROR;
                    cleanupOnError();
                    return;
                }
            }
        }
    }

    /**
     * Parse the chunk data
     */
    private void parseChunkData(ByteBuffer buffer) throws IOException {
        // Read exactly currentChunkSize bytes for this chunk
        while (bytesReadInChunk < currentChunkSize && buffer.hasRemaining()) {
            // Read one byte at a time to ensure we get exactly the right amount
            byte b = buffer.get();
            dataStream.write(b);
            bytesReadInChunk++;
            totalBytesRead++;
        }

        // If we've read all the bytes for this chunk, move to the next state
        if (bytesReadInChunk == currentChunkSize) {
            state = State.CHUNK_END;
        } else if (bytesReadInChunk > currentChunkSize) {
            // This shouldn't happen, but if it does, it's an error
            logger.error("Bytes read in chunk ({}) exceeds chunk size ({})", bytesReadInChunk, currentChunkSize);
            state = State.ERROR;
            cleanupOnError();
        }
    }

    /**
     * Parse the CRLF after chunk data
     */
    private void parseChunkEnd(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            // Mark the current position so we can put back unexpected data
            buffer.mark();

            byte b = buffer.get();

            if (b == CR) {
                // Check if next byte is LF
                if (buffer.hasRemaining()) {
                    byte next = buffer.get();
                    if (next == LF) {
                        // Found complete CRLF, move back to chunk size
                        state = State.CHUNK_SIZE;
                        bytesReadInChunk = 0; // Reset counter for next chunk
                        return;
                    } else {
                        // Not LF, put back the next byte
                        buffer.position(buffer.position() - 1);
                        return;
                    }
                } else {
                    // Need more data for LF, put back the CR
                    buffer.position(buffer.position() - 1);
                    return;
                }
            } else if (b == LF) {
                // Found LF without CR, treat as newline (lenient parsing)
                state = State.CHUNK_SIZE;
                bytesReadInChunk = 0; // Reset counter for next chunk
                return;
            } else {
                // unexpected data
                buffer.reset();
                state = State.ERROR;
                bytesReadInChunk = 0; // Reset counter for next chunk
                cleanupOnError();
                return;
            }
        }
    }

    /**
     * Parse the CRLF after the last chunk
     */
    private void parseLastChunkEnd(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            // Mark the current position so we can put back unexpected data
            buffer.mark();

            byte b = buffer.get();

            if (b == CR) {
                // Check if next byte is LF
                if (buffer.hasRemaining()) {
                    byte next = buffer.get();
                    if (next == LF) {
                        // Found complete CRLF after last chunk, move to trailers
                        state = State.CHUNK_TRAILERS;
                        return;
                    } else {
                        // Not LF, put back the next byte
                        buffer.position(buffer.position() - 1);
                        return;
                    }
                } else {
                    // Need more data, put back the CR
                    buffer.position(buffer.position() - 1);
                    return;
                }
            } else if (b == LF) {
                // Found LF without CR, treat as newline (lenient parsing)
                state = State.CHUNK_TRAILERS;
                return;
            } else {
                // Unexpected character, this is an error
                buffer.reset();
                state = State.ERROR;
                return;
            }
        }
    }

    /**
     * Parse the trailers after the last chunk
     */
    private void parseTrailers(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            lineBuffer.write(b);
            
            // Check if line buffer exceeds maximum allowed length
            if (lineBuffer.size() > MAX_LINE_LENGTH) {
                logger.error("Trailer line exceeds maximum allowed length of {}", MAX_LINE_LENGTH);
                state = State.ERROR;
                cleanupOnError();
                return;
            }

            // Check for CRLF indicating end of line
            if (lineBuffer.size() >= 2) {
                byte[] bufArray = lineBuffer.toByteArray();
                int lastIndex = lineBuffer.size() - 1;
                int secondLastIndex = lineBuffer.size() - 2;

                if (bufArray[secondLastIndex] == CR && bufArray[lastIndex] == LF) {
                    // Create a string from the buffer
                    String trailerLine = new String(bufArray, 0, lineBuffer.size(), StandardCharsets.US_ASCII);
                    lineBuffer.reset();

                    // Check if this is the end of trailers (empty line - just CRLF)
                    if (trailerLine.equals("\r\n")) {
                        // Found end of trailers, move to COMPLETE
                        state = State.COMPLETE;
                        finish();
                        return;
                    }

                    // Otherwise, parse trailer header (we'll ignore trailers for now)
                    logger.debug("Received trailer: {}", trailerLine.trim());
                }
            }
        }

        // If we reach here and there's no more data, and the buffer is empty,
        // it means we have finished reading all chunks and there are no trailers
        // so we should complete the parsing
        if (!buffer.hasRemaining() && lineBuffer.size() == 0) {
            state = State.COMPLETE;
            finish();
        }
    }

    /**
     * Switch from in-memory storage to file storage for large requests
     */
    private void switchToFileStorage() throws IOException {
        // Flush current data to a temp file
        byte[] currentData = ((ByteArrayOutputStream) dataStream).toByteArray();
        dataStream.close();

        try {
            Path tempFilePath = Files.createTempFile("http-chunked-body-", ".tmp");
            this.tempFile = tempFilePath.toFile();
            this.dataStream = new FileOutputStream(this.tempFile);

            // Write existing data to file
            dataStream.write(currentData);
        } catch (IOException e) {
            // If we can't create temp file, clean up and set error state
            logger.error("Failed to create temporary file for chunked body storage", e);
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
            throw e;
        }
    }

    private void readCRLF(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            if (b == CR) {
                // Check if next byte is LF
                if (buffer.hasRemaining()) {
                    byte next = buffer.get();
                    if (next == LF)
                        return;
                }
            }
        }
    }

    /**
     * Finish parsing and close resources
     */
    private void finish() throws IOException {
        if (dataStream instanceof ByteArrayOutputStream) {
            // Save before closing
            this.inMemoryData = ((ByteArrayOutputStream) dataStream).toByteArray();
        }
        dataStream.close();
    }
    
    /**
     * Clean up resources in case of error
     */
    private void cleanupOnError() {
        try {
            if (dataStream != null) {
                dataStream.close();
            }
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        } catch (IOException e) {
            logger.warn("Error during cleanup on error", e);
        }
    }

    @Override
    public void populate(HttpRequest request) {
        // Only populate if parsing completed successfully
        if (state == State.COMPLETE) {
            request.setBody(inMemoryData);
            request.setTempBodyFile(tempFile);
        }
    }

    @Override
    public boolean isComplete() {
        return state == State.COMPLETE;
    }

    @Override
    public boolean hasError() {
        return state == State.ERROR;
    }

    public byte[] getInMemoryData() {
        return inMemoryData;
    }

    public File getTempFile() {
        return tempFile;
    }

    public long getTotalBytesRead() {
        return totalBytesRead;
    }

    public State getState() {
        return state;
    }

    public long getCurrentChunkSize() {
        return currentChunkSize;
    }

    public long getBytesReadInChunk() {
        return bytesReadInChunk;
    }
}