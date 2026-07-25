package com.turkcell.cdrgenerator1.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.cdr")
public class CdrConfigProperties {

    /** datastructure.json konumu. */
    private String dataStructurePath;

    private int defaultRecordCount;
    private int maxRecordCount;
}