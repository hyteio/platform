package io.hyte.platform.sample;

import java.io.Serializable;

/** Diagnostic payload of the {@code /sample/db} endpoint: the H2 version as loaded IN-CONTAINER. */
public class DbInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String h2Version;
    private Long rows;

    public DbInfo() {
    }

    public DbInfo(String h2Version) {
        this.h2Version = h2Version;
    }

    public DbInfo(String h2Version, long rows) {
        this.h2Version = h2Version;
        this.rows = rows;
    }

    public String getH2Version() {
        return h2Version;
    }

    public void setH2Version(String h2Version) {
        this.h2Version = h2Version;
    }

    public Long getRows() {
        return rows;
    }

    public void setRows(Long rows) {
        this.rows = rows;
    }
}
