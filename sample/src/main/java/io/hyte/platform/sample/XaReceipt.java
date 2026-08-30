package io.hyte.platform.sample;

import java.io.Serializable;

/** Acknowledgement for the XA execution path: the note was enqueued for the in-container consumer. */
public class XaReceipt implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean queued;
    private String content;

    public XaReceipt() {
    }

    public XaReceipt(boolean queued, String content) {
        this.queued = queued;
        this.content = content;
    }

    public boolean isQueued() {
        return queued;
    }

    public void setQueued(boolean queued) {
        this.queued = queued;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
