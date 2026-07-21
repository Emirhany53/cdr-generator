package com.turkcell.cdrgenerator1.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class OpenApiConfig {

    private static final String API_TITLE = "EMM CDR Üretici API";
    private static final String API_VERSION = "1.0.0";
    private static final String API_DESCRIPTION =
            "ASN.1 yapı tanımlarını ayrıştırarak Ericsson Mediation Manager (EMM) "
                    + "platformu için Token-Separated-ASCII (.dat) ve BER kodlu (.ber) "
                    + "CDR test dosyaları üreten REST API.";

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