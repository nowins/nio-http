package com.nowin.webdav;

final class WebDavStatus {
    private WebDavStatus() {
    }

    static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 207 -> "Multi-Status";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 412 -> "Precondition Failed";
            case 415 -> "Unsupported Media Type";
            case 423 -> "Locked";
            case 424 -> "Failed Dependency";
            case 500 -> "Internal Server Error";
            case 507 -> "Insufficient Storage";
            default -> "";
        };
    }

    static String statusLine(int status) {
        return "HTTP/1.1 " + status + " " + reason(status);
    }
}
