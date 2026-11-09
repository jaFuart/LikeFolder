package com.likefolder.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds a bounded, ordered queue without adding any IDs the user did not request. */
final class DownloadSelection {
    static List<String> pending(List<String> candidates, Set<String> downloaded,
            Set<String> seen, boolean newOnly) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String id : candidates) {
            if (id == null || !id.matches("[0-9]+(:[0-9]+)?")) continue;
            if (downloaded.contains(id) || (newOnly && seen.contains(id))) continue;
            result.add(id);
        }
        return new ArrayList<>(result);
    }

    private DownloadSelection() { }
}
