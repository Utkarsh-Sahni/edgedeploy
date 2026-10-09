package com.edgedeploy.dto;

import java.util.List;

/** One page of results; {@code hasNext} says whether requesting {@code page + 1} is worthwhile. */
public record PageResponse<T>(List<T> items, int page, int perPage, boolean hasNext) {
}
