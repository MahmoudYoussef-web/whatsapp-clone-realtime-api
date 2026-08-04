package com.alibou.whatsappclone.storage;

public class StorageFailureException extends RuntimeException {

    public StorageFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
