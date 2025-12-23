package com.nowin.http;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MultipartParser implements BodyParser {

    private enum MultipartParseState {
        PREAMBLE,      // find first boundary
        PART_HEADERS,
        PART_DATA,
        DONE,
        ERROR
    }

    private static final byte CR = (byte) '\r';
    private static final byte LF = (byte) '\n';

    private MultipartParseState currentState = MultipartParseState.PREAMBLE;
    private final byte[] boundary;
    private final byte[] fullBoundary; // --boundary
    private final byte[] endBoundary;  // --boundary--

    private final long sizeThreshold; // write to temp file if size exceeds this threshold

    private final List<HttpPart> parts = new ArrayList<>();
    private HttpPart currentPart;
    private final ByteArrayOutputStream currentPartHeaderBuffer = new ByteArrayOutputStream(1024);
    private static final int MAX_HEADER_LINE_LENGTH = 8192; // 8KB max per header line
    private static final int MAX_HEADERS_SIZE = 65536; // 64KB max for all headers
    private OutputStream currentPartDataStream;
    private long currentPartSize = 0;
    private boolean lastBoundaryWasEnd = false;

    // 用于处理跨 ByteBuffer 的边界。存储上一个 buffer 未能完全匹配边界的尾部数据。
    private final ByteBuffer tailBuffer;

    public MultipartParser(String boundaryStr, long sizeThreshold) {
        this.boundary = boundaryStr.getBytes(StandardCharsets.US_ASCII);
        this.fullBoundary = ("--" + boundaryStr).getBytes(StandardCharsets.US_ASCII);
        this.endBoundary = ("--" + boundaryStr + "--").getBytes(StandardCharsets.US_ASCII);
        this.sizeThreshold = sizeThreshold;
        // 尾部缓冲区的大小为 边界长度-1，确保任何跨 buffer 的边界都能被检测到
        this.tailBuffer = ByteBuffer.allocate(fullBoundary.length - 1);
        this.tailBuffer.flip(); // Initially empty
    }

    public List<HttpPart> getParts() {
        return parts;
    }

    @Override
    public void parse(ByteBuffer buffer, Map<String, String> headers) throws IOException {
        // combine tail buffer with current buffer
        ByteBuffer effectiveBuffer = combineWithTail(buffer);

        while (effectiveBuffer.hasRemaining() && currentState != MultipartParseState.DONE) {
            switch (currentState) {
                case PREAMBLE:
                    // ignore everything before first boundary
                    if (findAndSkipBoundary(effectiveBuffer, false)) {
                        startNewPart();
                        currentState = MultipartParseState.PART_HEADERS;
                    }
                    break;
                case PART_HEADERS:
                    if (readPartHeaders(effectiveBuffer)) {
                        parseCurrentPartHeaders();
                        preparePartDataStream();
                        currentState = MultipartParseState.PART_DATA;
                    }
                    break;
                case PART_DATA:
                    if (readPartData(effectiveBuffer)) {
                        // finding the boundary means the end of the part
                        finishCurrentPart();

                        if (isEndBoundaryFound()) {
                            currentState = MultipartParseState.DONE;
                        } else {
                            startNewPart();
                            currentState = MultipartParseState.PART_HEADERS;
                        }
                    }
                    break;
            }
        }

        // save tail for next parse() call
        updateTail(effectiveBuffer);
    }

    @Override
    public void populate(HttpRequest request) {
        request.setParts(parts);
    }

    @Override
    public boolean isComplete() {
        return currentState == MultipartParseState.DONE;
    }

    @Override
    public boolean hasError() {
        return currentState == MultipartParseState.DONE;
    }

    /**
     * Find and skip the first boundary in the PREAMBLE state.
     * @param buffer data
     * @param isEndBoundaryAllowed Whether the end boundary is allowed (false in PREAMBLE)
     * @return true if the boundary is found and skipped
     */
    private boolean findAndSkipBoundary(ByteBuffer buffer, boolean isEndBoundaryAllowed) {
        int pos = indexOf(buffer, fullBoundary);
        if (pos != -1) {
            // 跳过找到的 boundary 之前的所有数据 (preamble)
            buffer.position(pos + fullBoundary.length);

            // 检查是结束 boundary 还是普通 boundary
            if (buffer.remaining() >= 2 && buffer.get(buffer.position()) == '-' && buffer.get(buffer.position() + 1) == '-') {
                if (isEndBoundaryAllowed) {
                    lastBoundaryWasEnd = true;
                    buffer.position(buffer.position() + 2); // Skip "--"
                    // 跳过末尾的 CR LF
                    skipCRLF(buffer);
                    return true;
                } else {
                    // Preamble 不应该直接遇到结束符
                    throw new IllegalStateException("Unexpected end boundary in preamble.");
                }
            }

            // 普通 boundary 后面必须跟 CR LF
            if (buffer.remaining() >= 2 && buffer.get(buffer.position()) == CR && buffer.get(buffer.position() + 1) == LF) {
                buffer.position(buffer.position() + 2); // Skip CR LF
                lastBoundaryWasEnd = false;
                return true;
            }
        }
        // 没找到完整的 boundary，消耗掉大部分 buffer，只留下可能的尾部
        return false;
    }


    /**
     * 从 buffer 中读取 Part 的 Headers，直到遇到空行 (\r\n\r\n)。
     * @param buffer 数据
     * @return true 如果 Headers 读取完毕
     */
    private boolean readPartHeaders(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            // Check if we've already exceeded max header size
            if (currentPartHeaderBuffer.size() > MAX_HEADERS_SIZE) {
                throw new IOException("Total headers size exceeds maximum limit of " + MAX_HEADERS_SIZE);
            }

            byte b = buffer.get();
            currentPartHeaderBuffer.write(b);
            int size = currentPartHeaderBuffer.size();

            // Check if we've exceeded max line length
            // (simple check: if we've written a lot without seeing CRLF)
            if (size > MAX_HEADER_LINE_LENGTH) {
                byte[] data = currentPartHeaderBuffer.toByteArray();
                // Find last CRLF
                boolean hasRecentCRLF = false;
                for (int i = Math.max(0, size - 20); i < size; i++) {
                    if (data[i] == CR || data[i] == LF) {
                        hasRecentCRLF = true;
                        break;
                    }
                }
                if (!hasRecentCRLF) {
                    throw new IOException("Header line exceeds maximum length of " + MAX_HEADER_LINE_LENGTH);
                }
            }

            // check if end with CRLF
            if (size >= 4) {
                byte[] lastFour = currentPartHeaderBuffer.toByteArray();
                if (lastFour[size - 4] == CR && lastFour[size - 3] == LF &&
                        lastFour[size - 2] == CR && lastFour[size - 1] == LF) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Parse the cached Part Headers
     */
    private void parseCurrentPartHeaders() {
        String headersStr = currentPartHeaderBuffer.toString(StandardCharsets.UTF_8);
        currentPartHeaderBuffer.reset();

        Map<String, String> headers = new HashMap<>();
        String[] lines = headersStr.split("\r\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            int colonPos = line.indexOf(':');
            if (colonPos != -1) {
                String key = line.substring(0, colonPos).trim();
                String value = line.substring(colonPos + 1).trim();
                headers.put(key, value);

                if ("Content-Disposition".equalsIgnoreCase(key)) {
                    parseContentDisposition(value);
                }
            }
        }
        currentPart.setHeaders(headers);
    }

    /**
     * 解析 Content-Disposition 头，提取 name 和 filename
     */
    private void parseContentDisposition(String value) {
        String[] params = value.split(";");
        for (String param : params) {
            param = param.trim();
            if (param.startsWith("name=")) {
                currentPart.setName(param.substring("name=".length()).replace("\"", ""));
            } else if (param.startsWith("filename=")) {
                currentPart.setFilename(param.substring("filename=".length()).replace("\"", ""));
            }
        }
    }


    /**
     * 读取 Part Data，直到下一个 boundary。
     * 这是性能关键点，通过 indexOf 和 tailBuffer 机制来处理。
     * @param buffer 数据
     * @return true 如果找到了下一个 boundary
     */
    private boolean readPartData(ByteBuffer buffer) throws IOException {
        // 在 buffer 中寻找下一个 boundary 的起始位置
        // 注意：这里的 boundary 是 \r\n--boundary...
        byte[] boundaryWithPrefix = new byte[fullBoundary.length + 2];
        boundaryWithPrefix[0] = CR;
        boundaryWithPrefix[1] = LF;
        System.arraycopy(fullBoundary, 0, boundaryWithPrefix, 2, fullBoundary.length);

        int pos = indexOf(buffer, boundaryWithPrefix);

        if (pos != -1) {
            // 找到了 boundary
            int dataLimit = pos;
            int oldLimit = buffer.limit();
            buffer.limit(dataLimit);

            // 将 boundary 之前的数据写入流
            writeToCurrentPartStream(buffer);

            // 恢复 limit 并将 position 移动到 boundary 之后
            buffer.limit(oldLimit);
            buffer.position(pos + boundaryWithPrefix.length);

            // 检查是 "--" 还是 "\r\n"
            if (buffer.remaining() >= 2 && buffer.get(buffer.position()) == '-' && buffer.get(buffer.position() + 1) == '-') {
                lastBoundaryWasEnd = true;
                buffer.position(buffer.position() + 2); // Skip "--"
                skipCRLF(buffer);
            } else if (buffer.remaining() >= 2 && buffer.get(buffer.position()) == CR && buffer.get(buffer.position() + 1) == LF) {
                lastBoundaryWasEnd = false;
                buffer.position(buffer.position() + 2); // Skip CR LF
            } else {
                // 格式错误，但我们尽量容错
                lastBoundaryWasEnd = false;
            }
            return true;
        } else {
            // 没找到 boundary，写入所有数据
            writeToCurrentPartStream(buffer);
            return false;
        }
    }

    /**
     * 检查上一次找到的 boundary 是否是结束 boundary。
     * @return true 如果是结束标志
     */
    public boolean isEndBoundaryFound() {
        return lastBoundaryWasEnd;
    }

    private void startNewPart() {
        currentPart = new HttpPart();
        currentPartSize = 0;
        currentPartHeaderBuffer.reset();
    }

    private void preparePartDataStream() throws IOException {
        // 默认先写入内存，文件类型在写入数据时会根据大小决定是否切换到临时文件
        currentPartDataStream = new ByteArrayOutputStream();
    }

    private void writeToCurrentPartStream(ByteBuffer buffer) throws IOException {
        int bytesToWrite = buffer.remaining();
        if (currentPartDataStream instanceof ByteArrayOutputStream &&
                currentPartSize + bytesToWrite > sizeThreshold && currentPart.isFile()) {
            // 超过阈值且是个文件，从内存切换到临时文件
            switchToTempFile();
        }

        // 直接写入ByteBuffer内容，避免创建临时byte[]数组
        if (buffer.hasArray()) {
            // 如果ByteBuffer有底层数组，直接使用它
            currentPartDataStream.write(buffer.array(), buffer.position(), bytesToWrite);
            buffer.position(buffer.position() + bytesToWrite);
        } else {
            // 否则使用临时数组，但减少创建频率
            byte[] data = new byte[Math.min(bytesToWrite, 8192)];
            while (bytesToWrite > 0) {
                int chunkSize = Math.min(data.length, bytesToWrite);
                buffer.get(data, 0, chunkSize);
                currentPartDataStream.write(data, 0, chunkSize);
                bytesToWrite -= chunkSize;
            }
        }
        currentPartSize += bytesToWrite;
    }

    private void switchToTempFile() throws IOException {
        Path tempFilePath = Files.createTempFile("http-upload-", ".tmp");
        FileOutputStream fos = new FileOutputStream(tempFilePath.toFile());

        if (currentPartDataStream instanceof ByteArrayOutputStream) {
            // 如果当前是ByteArrayOutputStream，将已有数据写入临时文件
            ByteArrayOutputStream baos = (ByteArrayOutputStream) currentPartDataStream;
            baos.writeTo(fos);
            baos.close();
        } else if (currentPartDataStream != null) {
            // 如果是其他类型的流，关闭它
            currentPartDataStream.close();
        }

        currentPart.setTempFile(tempFilePath.toFile());
        currentPartDataStream = fos;
    }

    private void finishCurrentPart() throws IOException {
        currentPartDataStream.close();
        if (currentPartDataStream instanceof ByteArrayOutputStream) {
            currentPart.setInMemoryData(((ByteArrayOutputStream) currentPartDataStream).toByteArray());
        }
        parts.add(currentPart);
    }

    private void skipCRLF(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            byte b = buffer.get(buffer.position());
            if (b == CR || b == LF) {
                buffer.get();
            } else {
                break;
            }
        }
    }

    private ByteBuffer combineWithTail(ByteBuffer newBuffer) {
        if (!tailBuffer.hasRemaining()) {
            return newBuffer;
        }

        ByteBuffer combined = ByteBuffer.allocate(tailBuffer.remaining() + newBuffer.remaining());
        combined.put(tailBuffer);
        combined.put(newBuffer);
        combined.flip();
        tailBuffer.clear().flip(); // clear and flip to reset
        return combined;
    }

    private void updateTail(ByteBuffer buffer) {
        // 将 buffer 中剩余的、可能是不完整 boundary 的数据保存到 tailBuffer
        int tailSize = Math.min(buffer.remaining(), tailBuffer.capacity());
        if (tailSize > 0) {
            // 从 buffer 的 limit - tailSize 处开始复制
            int startPos = buffer.limit() - tailSize;
            tailBuffer.clear();
            for (int i = 0; i < tailSize; i++) {
                tailBuffer.put(buffer.get(startPos + i));
            }
            tailBuffer.flip();
        }

        // 调整 buffer 的 limit，使其不包含已被复制到 tail 的部分
        buffer.limit(buffer.limit() - tailBuffer.remaining());
    }

    /**
     * 在 haystack ByteBuffer 中查找 needle 字节数组的首次出现位置。
     * @return 首次出现的索引，如果未找到则返回 -1。
     */
    private static int indexOf(ByteBuffer haystack, byte[] needle) {
        if (needle.length == 0) return 0;
        int haystackLimit = haystack.limit();
        int needleLen = needle.length;

        for (int i = haystack.position(); i <= haystackLimit - needleLen; i++) {
            boolean found = true;
            for (int j = 0; j < needleLen; j++) {
                if (haystack.get(i + j) != needle[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return i;
            }
        }
        return -1;
    }
}
