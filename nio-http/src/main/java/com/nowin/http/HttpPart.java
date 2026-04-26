package com.nowin.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.util.Map;

public class HttpPart {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpPart.class);

    private Map<String, String> headers;
    private String name;
    private String filename;

    // 二者只有一个会非 null
    private byte[] inMemoryData;
    private File tempFile;

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public byte[] getInMemoryData() {
        return inMemoryData;
    }

    public void setInMemoryData(byte[] inMemoryData) {
        this.inMemoryData = inMemoryData;
    }

    public File getTempFile() {
        return tempFile;
    }

    public void setTempFile(File tempFile) {
        this.tempFile = tempFile;
    }

    public boolean isFile() {
        return filename != null;
    }

    /**
     * 统一的输入流，业务逻辑通过此方法读取数据，
     * 无需关心数据是在内存还是在文件。
     */
    public InputStream getInputStream() throws IOException {
        if (inMemoryData != null) {
            return new ByteArrayInputStream(inMemoryData);
        }
        if (tempFile != null) {
            return new FileInputStream(tempFile);
        }
        return null;
    }

    /**
     * 清理资源，删除临时文件。
     */
    public void cleanup() {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile.toPath());
            } catch (IOException e) {
                LOGGER.error("Failed to delete temp file:{}", tempFile);
            }
        }
    }


}
