package com.wt.pg.bo;

import java.io.Serializable;

public class Message implements Serializable {
    private final String message;

    public Message(final String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}

