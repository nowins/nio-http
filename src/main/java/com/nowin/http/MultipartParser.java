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
        PREAMBLE,      // 寻找第一个 boundary
        PART_HEADERS,
        PART_DATA,
        DONE
    }

    private static final byte CR = (byte) '\r';
    private static final byte LF = (byte) '\n';

    private MultipartParseState currentState = MultipartParseState.PREAMBLE;
    private final byte[] boundary;
    private final byte[] fullBoundary; // --boundary
    private final byte[] endBoundary;  // --boundary--

    private final long sizeThreshold; // 超过此大小则写入临时文件

    private List<HttpPart> parts = new ArrayList<>();
    private HttpPart currentPart;
    private ByteArrayOutputStream currentPartHeaderBuffer = new ByteArrayOutputStream(1024);
    private OutputStream currentPartDataStream;
    private long currentPartSize = 0;
    private boolean lastBoundaryWasEnd = false;

    // 用于处理跨 ByteBuffer 的边界。存储上一个 buffer 未能完全匹配边界的尾部数据。
    private ByteBuffer tailBuffer;

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
    public boolean parse(ByteBuffer buffer) throws IOException {
        // 将上一次的尾部数据和本次的新数据组合成一个虚拟的 buffer 来处理
        ByteBuffer effectiveBuffer = combineWithTail(buffer);

        while (effectiveBuffer.hasRemaining() && currentState != MultipartParseState.DONE) {
            switch (currentState) {
                case PREAMBLE:
                    // 忽略第一个 boundary 之前的所有内容
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
                        // 找到了 boundary，意味着当前 part 结束
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

        // 保存当前 buffer 未处理的尾部，以备下次拼接
        updateTail(effectiveBuffer);

        return currentState == MultipartParseState.DONE;
    }

    @Override
    public void populate(HttpRequest request) {
        request.setParts(parts);
    }

    /**
     * 在 PREAMBLE 状态下查找并跳过第一个 boundary。
     * @param buffer 数据
     * @param isEndBoundaryAllowed 是否允许是结束 boundary (在 PREAMBLE 中为 false)
     * @return true 如果找到并跳过了 boundary
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
    private boolean readPartHeaders(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            currentPartHeaderBuffer.write(b);
            int size = currentPartHeaderBuffer.size();
            // 检查是否以 \r\n\r\n 结尾
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
     * 解析已缓存的 Part Headers
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
        // 默认先写入内存
        currentPartDataStream = new ByteArrayOutputStream();
    }

    private void writeToCurrentPartStream(ByteBuffer buffer) throws IOException {
        int bytesToWrite = buffer.remaining();
        if (currentPartDataStream instanceof ByteArrayOutputStream &&
                currentPartSize + bytesToWrite > sizeThreshold && currentPart.isFile()) {
            // 超过阈值且是个文件，从内存切换到临时文件
            switchToTempFile();
        }

        byte[] data = new byte[bytesToWrite];
        buffer.get(data);
        currentPartDataStream.write(data);
        currentPartSize += bytesToWrite;
    }

    private void switchToTempFile() throws IOException {
        ByteArrayOutputStream baos = (ByteArrayOutputStream) currentPartDataStream;
        Path tempFilePath = Files.createTempFile("http-upload-", ".tmp");
        FileOutputStream fos = new FileOutputStream(tempFilePath.toFile());

        baos.writeTo(fos);

        currentPart.setTempFile(tempFilePath.toFile());
        currentPartDataStream = fos;
        //System.out.println("Switched to temp file: " + tempFilePath);
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
            for(int i = 0; i < tailSize; i++) {
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
