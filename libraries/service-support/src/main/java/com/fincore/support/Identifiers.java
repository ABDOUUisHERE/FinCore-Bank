package com.fincore.support;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

public final class Identifiers {
    private Identifiers() {}
    public static UUID stable(String... identity){
        try{
            var bytes=MessageDigest.getInstance("SHA-256").digest(Json.text(identity).getBytes(StandardCharsets.UTF_8));
            bytes[6]=(byte)((bytes[6]&0x0f)|0x50);bytes[8]=(byte)((bytes[8]&0x3f)|0x80);
            var buffer=ByteBuffer.wrap(bytes);return new UUID(buffer.getLong(),buffer.getLong());
        }catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    public static void key(String value){if(value==null || !value.matches("[A-Za-z0-9._:-]{1,120}"))throw new ApiFailure(400,"INVALID_IDEMPOTENCY_KEY");}
}
