package com.edgedeploy.dto;

/** Input patterns shared by request DTOs and path-variable validation. */
public final class ValidationPatterns {

    /** GitHub account names: alphanumerics and single hyphens, max 39 chars. */
    public static final String GITHUB_OWNER = "^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$";
    public static final String GITHUB_REPO_NAME = "^[A-Za-z0-9._-]{1,100}$";
    public static final String GITHUB_REPOSITORY = "^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}$";
    /** A conservative subset of valid git ref names: no "..", "//", leading "-" or "/", trailing "/" or ".". */
    public static final String GIT_BRANCH = "^(?!.*\\.\\.)(?!.*//)[A-Za-z0-9._][A-Za-z0-9._/-]*(?<![/.])$";
    /** Single-line shell command; control characters and newlines are rejected. */
    public static final String COMMAND = "^[^\\p{Cntrl}]*$";

    private ValidationPatterns() {
    }
}
