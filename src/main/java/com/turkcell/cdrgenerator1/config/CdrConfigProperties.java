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

    /**
     * Tipi CHOICE olup IMPLICIT tag tasiyan OPTIONAL alanlari uretime hic
     * katmaz. X.680 30.6 geregi bu alanlarin tag'i her zaman EXPLICIT'e doner
     * ve encoder onlari dogru (EXPLICIT sarmalanmis) uretir; ne var ki EMM'in
     * cozucusu bu bicimi yanlis "hoisting" ile okuyup "Duplicate Tag" verir
     * (Ericsson TR). Hepsi OPTIONAL oldugu icin atlanmalari kaydi gecerli
     * birakir. EMM tarafi duzelince false yapilip yeniden acilabilir.
     */
    private boolean skipImplicitChoiceFields = true;
}