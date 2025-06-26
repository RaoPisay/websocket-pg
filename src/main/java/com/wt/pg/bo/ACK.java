package com.wt.pg.bo;

import java.io.Serializable;

public class ACK implements Serializable {
    private boolean status;

    public ACK(boolean status) {
        this.status = status;
    }

    public boolean isStatus() {
        return status;
    }

    public void setStatus(boolean status) {
        this.status = status;
    }
}
