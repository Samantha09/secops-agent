package com.secops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CreateTargetRequest {

    @NotBlank(message = "域名不能为空")
    @Pattern(
        regexp = "^(?!-)[A-Za-z0-9-]{1,63}(?<!-)\\.(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\\.[A-Za-z0-9-]{1,63})*$",
        message = "域名格式不正确"
    )
    private String domain;
}
