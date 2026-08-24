package dev.yuemeng.marthub.common;

/**
 * The request is not malformed and the caller is not going too fast; it collides with state that
 * already exists. Separate from {@link BadRequestException} because the status has to differ: 400
 * tells a client to fix the request, and there is nothing here for it to fix.
 */
public class ConflictException extends RuntimeException {
    private final String code;
    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String code() { return code; }
}
