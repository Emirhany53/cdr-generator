package com.turkcell.cdrgenerator1.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class OpenApiConfig {

    private static final String API_TITLE = "EMM CDR Generator API";
    private static final String API_VERSION = "1.0.0";
    private static final String API_DESCRIPTION =
            "REST API that parses ASN.1 structure definitions and generates "
                    + "Token-Separated-ASCII and BER encoded CDR test files for the "
                    + "Ericsson Mediation Manager (EMM) platform.";

    @Bean
    public OpenAPI cdrGeneratorOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title(API_TITLE)
                        .version(API_VERSION)
                        .description(API_DESCRIPTION)
                        .contact(new Contact()
                                .name("Turkcell CDR Generator")
                                .url("https://github.com/Emirhany53/cdr-generator"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}