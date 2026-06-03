package com.nowin.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FileChannelBodyTest {

    @TempDir
    Path tempDir;

    @Test
    void writeToHonorsMaxBytesPerCall() throws Exception {
        Path file = tempDir.resolve("large.bin");
        Files.write(file, new byte[8192]);

        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            FileChannelBody body = new FileChannelBody(fileChannel, 0, Files.size(file));
            CountingWritableChannel target = new CountingWritableChannel();

            long first = body.writeTo(target, 1024);
            long second = body.writeTo(target, 2048);

            assertEquals(1024, first);
            assertEquals(2048, second);
            assertEquals(3072, target.bytesWritten);
            assertEquals(8192 - 3072, body.remaining());
            assertFalse(body.isComplete());
        }
    }

    private static final class CountingWritableChannel implements WritableByteChannel {
        private long bytesWritten;
        private boolean open = true;

        @Override
        public int write(ByteBuffer src) {
            int remaining = src.remaining();
            src.position(src.limit());
            bytesWritten += remaining;
            return remaining;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            open = false;
        }
    }
}
