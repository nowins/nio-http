package com.nowin.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Zero-copy HTTP body backed by a {@link FileChannel}.
 * Uses {@link FileChannel#transferTo(long, long, WritableByteChannel)} for
 * kernel-bypass transmission when possible.
 */
public class FileChannelBody implements HttpBody {

    private static final Logger logger = LoggerFactory.getLogger(FileChannelBody.class);

    private final FileChannel fileChannel;
    private final long position;
    private final long count;
    private long transferred = 0;
    private volatile boolean closed = false;

    public FileChannelBody(FileChannel fileChannel, long position, long count) {
        this.fileChannel = fileChannel;
        this.position = position;
        this.count = count;
    }

    @Override
    public long contentLength() {
        return count;
    }

    @Override
    public boolean isBuffered() {
        return false;
    }

    @Override
    public ByteBuffer toByteBuffer() {
        throw new UnsupportedOperationException("FileChannelBody cannot be materialized to ByteBuffer");
    }

    /**
     * Writes the next portion of this body to the target channel.
     * Each call resumes from where the previous call left off.
     *
     * @param target the destination channel
     * @return the number of bytes written in this call
     */
    public long writeTo(WritableByteChannel target) throws IOException {
        if (transferred >= count) {
            return 0;
        }
        long remaining = count - transferred;
        long written = fileChannel.transferTo(position + transferred, remaining, target);
        if (written > 0) {
            transferred += written;
        } else if (written == 0) {
            logger.debug("FileChannel.transferTo returned 0, socket buffer may be full");
        }
        return written;
    }

    /**
     * Returns true if all bytes have been transferred.
     */
    public boolean isComplete() {
        return transferred >= count;
    }

    /**
     * Returns how many bytes remain to be transferred.
     */
    public long remaining() {
        return count - transferred;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            fileChannel.close();
            logger.debug("FileChannelBody closed");
        }
    }

    public FileChannel fileChannel() {
        return fileChannel;
    }

    public long position() {
        return position;
    }

    public long count() {
        return count;
    }
}
