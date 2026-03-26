package com.travel.dto.recommendation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CachedRecItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long spotId;
    private String reason;
    private Double score;
}
