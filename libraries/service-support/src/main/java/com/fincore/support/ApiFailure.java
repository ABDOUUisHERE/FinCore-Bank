package com.fincore.support;

public final class ApiFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int status;
    public final String code;
    public ApiFailure(int status, String code) { super(code); this.status=status; this.code=code; }
}
