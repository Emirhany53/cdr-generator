package com.turkcell.cdrgenerator1.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CdrStructureDto {

    private Long id;
    private String name;
    private Integer serverid;
    private String description;
    private String contents;
    private Boolean islocked;
    private String servertype;

}