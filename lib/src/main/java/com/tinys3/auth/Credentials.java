package com.tinys3.auth;

public class Credentials {
    private final String accessKey;
    private final String secretKey;
    private final String region;

    public Credentials(String accessKey, String secretKey, String region) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getRegion() {
        return region;
    }
}
