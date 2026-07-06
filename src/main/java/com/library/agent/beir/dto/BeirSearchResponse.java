package com.library.agent.beir.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class BeirSearchResponse {

    private List<BeirSearchHit> hits;
}
