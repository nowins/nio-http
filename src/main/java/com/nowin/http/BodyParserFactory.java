package com.nowin.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class BodyParserFactory {

    private static final Logger logger = LoggerFactory.getLogger(BodyParserFactory.class);
    
    private static final long DEFAULT_SIZE_THRESHOLD = 1024 * 1024;
    
    public static BodyParser createUrlEncodedParser(long contentLength) {
        return new UrlEncodedParser(contentLength);
    }
    
    public static BodyParser createMultipartParser(String boundary, long sizeThreshold) {
        return new MultipartParser(boundary, sizeThreshold);
    }
    
    public static BodyParser createChunkedBodyParser(long sizeThreshold) {
        return new ChunkedBodyParser(sizeThreshold);
    }
    
    public static BodyParser createRawBodyParser(long contentLength, long sizeThreshold) {
        return new RawBodyParser(contentLength, sizeThreshold);
    }
    
    public static long getDefaultSizeThreshold() {
        return DEFAULT_SIZE_THRESHOLD;
    }
    
    public static String extractBoundary(String contentType) {
        if (contentType != null && contentType.startsWith("multipart/form-data")) {
            String[] parts = contentType.split(";\\s*");
            for (String part : parts) {
                if (part.startsWith("boundary=")) {
                    return part.substring(9).replace("\"", "");
                }
            }
            logger.error("Missing boundary for multipart/form-data request");
        }
        return null;
    }
}
