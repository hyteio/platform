package io.hyte.platform.sample;

import java.io.Serializable;
import java.util.List;

/**
 * Inbound request. {@code tags} may be sent as a single JSON string to validate
 * {@code ACCEPT_SINGLE_VALUE_AS_ARRAY}; the JSON body is root-wrapped ({@code {"SampleRequest": ...}})
 * to validate {@code UNWRAP_ROOT_VALUE}.
 */
public class SampleRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String note;
    private List<String> tags;

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }
}
