package com.miriyum.domain.auth.qrepoch;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "miriyum.auth.qr-epoch")
public class ConsumerQrEpochProperties {

    private static final Pattern STORAGE_GENERATION_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private String storageGeneration;

    public String requireStorageGeneration() {
        if (!isValidStorageGeneration(storageGeneration)) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        return storageGeneration;
    }

    static boolean isValidStorageGeneration(String value) {
        return value != null && STORAGE_GENERATION_PATTERN.matcher(value).matches();
    }

    public String getStorageGeneration() {
        return storageGeneration;
    }

    public void setStorageGeneration(String storageGeneration) {
        this.storageGeneration = storageGeneration;
    }
}
