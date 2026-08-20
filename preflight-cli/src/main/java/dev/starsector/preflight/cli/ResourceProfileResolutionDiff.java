package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.ResourceIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only comparison of ordinary ResourceIndex resolution between two profile snapshots. */
final class ResourceProfileResolutionDiff {
    private static final String EVIDENCE_KIND = "resource-index-profile-resolution-diff-v1";
    private static final String DERIVATION = "resource-index-ordered-provider-snapshot-comparison";
    private static final int MAX_CHANGE_DETAILS = 512;

    private ResourceProfileResolutionDiff() {
    }

    static Result compare(ResourceIndex before, ResourceIndex after) {
        return compare(before, after, MAX_CHANGE_DETAILS);
    }

    static Result compare(ResourceIndex before, ResourceIndex after, int maxChangeDetails) {
        if (before == null || after == null) {
            throw new IllegalArgumentException("Both ResourceIndex snapshots are required");
        }
        if (maxChangeDetails < 1) {
            throw new IllegalArgumentException("Change detail limit must be positive");
        }

        Iterator<Map.Entry<String, List<ResourceIndex.Provider>>> beforeIterator =
                before.entries().entrySet().iterator();
        Iterator<Map.Entry<String, List<ResourceIndex.Provider>>> afterIterator =
                after.entries().entrySet().iterator();
        Map.Entry<String, List<ResourceIndex.Provider>> beforeEntry = next(beforeIterator);
        Map.Entry<String, List<ResourceIndex.Provider>> afterEntry = next(afterIterator);

        long pathsCompared = 0;
        long unchangedPaths = 0;
        long changedPaths = 0;
        long addedPaths = 0;
        long removedPaths = 0;
        long providerSourceSequenceChangedPaths = 0;
        long providerSnapshotMetadataOnlyChangedPaths = 0;
        long winnerSourceChangedPaths = 0;
        long winnerSnapshotMetadataOnlyChangedPaths = 0;
        boolean detailsTruncated = false;
        List<Map<String, Object>> changes = new ArrayList<>();

        while (beforeEntry != null || afterEntry != null) {
            if (beforeEntry == null) {
                String path = afterEntry.getKey();
                pathsCompared++;
                changedPaths++;
                addedPaths++;
                if (changes.size() < maxChangeDetails) {
                    changes.add(detail(before, after, path, "added", List.of("resource-added"), null));
                } else {
                    detailsTruncated = true;
                }
                afterEntry = next(afterIterator);
                continue;
            }
            if (afterEntry == null) {
                String path = beforeEntry.getKey();
                pathsCompared++;
                changedPaths++;
                removedPaths++;
                if (changes.size() < maxChangeDetails) {
                    changes.add(detail(before, after, path, "removed", List.of("resource-removed"), null));
                } else {
                    detailsTruncated = true;
                }
                beforeEntry = next(beforeIterator);
                continue;
            }

            int keyOrder = beforeEntry.getKey().compareTo(afterEntry.getKey());
            if (keyOrder < 0) {
                String path = beforeEntry.getKey();
                pathsCompared++;
                changedPaths++;
                removedPaths++;
                if (changes.size() < maxChangeDetails) {
                    changes.add(detail(before, after, path, "removed", List.of("resource-removed"), null));
                } else {
                    detailsTruncated = true;
                }
                beforeEntry = next(beforeIterator);
                continue;
            }
            if (keyOrder > 0) {
                String path = afterEntry.getKey();
                pathsCompared++;
                changedPaths++;
                addedPaths++;
                if (changes.size() < maxChangeDetails) {
                    changes.add(detail(before, after, path, "added", List.of("resource-added"), null));
                } else {
                    detailsTruncated = true;
                }
                afterEntry = next(afterIterator);
                continue;
            }

            pathsCompared++;
            ChangeFlags flags = compareProviders(
                    before,
                    beforeEntry.getValue(),
                    after,
                    afterEntry.getValue());
            if (!flags.changed()) {
                unchangedPaths++;
            } else {
                changedPaths++;
                if (flags.providerSourceSequenceChanged()) {
                    providerSourceSequenceChangedPaths++;
                }
                if (flags.providerSnapshotMetadataOnlyChanged()) {
                    providerSnapshotMetadataOnlyChangedPaths++;
                }
                if (flags.winnerSourceChanged()) {
                    winnerSourceChangedPaths++;
                }
                if (flags.winnerSnapshotMetadataOnlyChanged()) {
                    winnerSnapshotMetadataOnlyChangedPaths++;
                }
                if (changes.size() < maxChangeDetails) {
                    changes.add(detail(
                            before,
                            after,
                            beforeEntry.getKey(),
                            "changed",
                            flags.reasons(),
                            flags));
                } else {
                    detailsTruncated = true;
                }
            }
            beforeEntry = next(beforeIterator);
            afterEntry = next(afterIterator);
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("pathsBefore", before.entryCount());
        totals.put("pathsAfter", after.entryCount());
        totals.put("pathsCompared", pathsCompared);
        totals.put("unchangedPaths", unchangedPaths);
        totals.put("changedPaths", changedPaths);
        totals.put("addedPaths", addedPaths);
        totals.put("removedPaths", removedPaths);
        totals.put("providerSourceSequenceChangedPaths", providerSourceSequenceChangedPaths);
        totals.put("providerSnapshotMetadataOnlyChangedPaths", providerSnapshotMetadataOnlyChangedPaths);
        totals.put("winnerSourceChangedPaths", winnerSourceChangedPaths);
        totals.put("winnerSnapshotMetadataOnlyChangedPaths", winnerSnapshotMetadataOnlyChangedPaths);
        totals.put("changeDetailsReturned", changes.size());
        totals.put("changeDetailsTruncated", detailsTruncated);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("evidenceKind", EVIDENCE_KIND);
        values.put("derivation", DERIVATION);
        values.put("beforeProfileFingerprint", before.profileFingerprint());
        values.put("afterProfileFingerprint", after.profileFingerprint());
        values.put("comparisonScope", "resource-index-resolution-and-provider-snapshot-metadata");
        values.put("filesystemRead", false);
        values.put("sourceGenerationBound", false);
        values.put("providerSourceIdentity", "core-root-id-relative-path");
        values.put("rootIndexScope", "profile-local-provider-occurrence");
        values.put("totals", totals);
        values.put("changes", List.copyOf(changes));
        values.put("notes", List.of(
                "This comparison uses only the two supplied immutable ResourceIndex snapshots and performs no filesystem reads.",
                "Provider source equality compares core/mod identity, root ID, and provider-relative path while preserving sequence and multiplicity.",
                "rootIndex remains visible inside each side's ownership explanation but is local to that profile snapshot and is not cross-profile identity.",
                "Metadata-only counters apply only when the corresponding provider or winner source identity stayed the same.",
                "Provider size and modifiedMillis differences are ResourceIndex snapshot metadata changes, not exact content-generation evidence.",
                "Added, removed, and changed resources are information; this comparison does not classify overrides as errors or intentional/unintentional."));
        return new Result(values);
    }

    private static ChangeFlags compareProviders(
            ResourceIndex beforeIndex,
            List<ResourceIndex.Provider> beforeProviders,
            ResourceIndex afterIndex,
            List<ResourceIndex.Provider> afterProviders) {
        boolean sourceSequenceChanged = !sameSourceSequence(
                beforeIndex, beforeProviders, afterIndex, afterProviders);
        boolean providerMetadataOnlyChanged = !sourceSequenceChanged
                && !sameSnapshotMetadata(beforeProviders, afterProviders);

        ResourceIndex.Provider beforeWinner = beforeProviders.get(beforeProviders.size() - 1);
        ResourceIndex.Provider afterWinner = afterProviders.get(afterProviders.size() - 1);
        boolean winnerSourceChanged = !sameSource(
                beforeIndex, beforeWinner, afterIndex, afterWinner);
        boolean winnerMetadataOnlyChanged = !winnerSourceChanged
                && !sameSnapshotMetadata(beforeWinner, afterWinner);

        List<String> reasons = new ArrayList<>(4);
        if (sourceSequenceChanged) {
            reasons.add("provider-source-sequence-changed");
        }
        if (providerMetadataOnlyChanged) {
            reasons.add("provider-snapshot-metadata-only-changed");
        }
        if (winnerSourceChanged) {
            reasons.add("winner-source-changed");
        }
        if (winnerMetadataOnlyChanged) {
            reasons.add("winner-snapshot-metadata-only-changed");
        }
        return new ChangeFlags(
                sourceSequenceChanged,
                providerMetadataOnlyChanged,
                winnerSourceChanged,
                winnerMetadataOnlyChanged,
                List.copyOf(reasons));
    }

    private static boolean sameSourceSequence(
            ResourceIndex beforeIndex,
            List<ResourceIndex.Provider> beforeProviders,
            ResourceIndex afterIndex,
            List<ResourceIndex.Provider> afterProviders) {
        if (beforeProviders.size() != afterProviders.size()) {
            return false;
        }
        for (int index = 0; index < beforeProviders.size(); index++) {
            if (!sameSource(
                    beforeIndex,
                    beforeProviders.get(index),
                    afterIndex,
                    afterProviders.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSource(
            ResourceIndex beforeIndex,
            ResourceIndex.Provider beforeProvider,
            ResourceIndex afterIndex,
            ResourceIndex.Provider afterProvider) {
        ResourceIndex.Root beforeRoot = beforeIndex.roots().get(beforeProvider.rootIndex());
        ResourceIndex.Root afterRoot = afterIndex.roots().get(afterProvider.rootIndex());
        return beforeRoot.core() == afterRoot.core()
                && beforeRoot.id().equals(afterRoot.id())
                && beforeProvider.relativePath().equals(afterProvider.relativePath());
    }

    private static boolean sameSnapshotMetadata(
            List<ResourceIndex.Provider> beforeProviders,
            List<ResourceIndex.Provider> afterProviders) {
        if (beforeProviders.size() != afterProviders.size()) {
            return false;
        }
        for (int index = 0; index < beforeProviders.size(); index++) {
            if (!sameSnapshotMetadata(beforeProviders.get(index), afterProviders.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSnapshotMetadata(
            ResourceIndex.Provider beforeProvider, ResourceIndex.Provider afterProvider) {
        return beforeProvider.size() == afterProvider.size()
                && beforeProvider.modifiedMillis() == afterProvider.modifiedMillis();
    }

    private static Map<String, Object> detail(
            ResourceIndex before,
            ResourceIndex after,
            String logicalPath,
            String status,
            List<String> reasons,
            ChangeFlags flags) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("logicalPath", logicalPath);
        value.put("status", status);
        value.put("changeReasons", reasons);
        if (flags != null) {
            value.put("providerSourceSequenceChanged", flags.providerSourceSequenceChanged());
            value.put("providerSnapshotMetadataOnlyChanged", flags.providerSnapshotMetadataOnlyChanged());
            value.put("winnerSourceChanged", flags.winnerSourceChanged());
            value.put("winnerSnapshotMetadataOnlyChanged", flags.winnerSnapshotMetadataOnlyChanged());
        }
        value.put("before", ResourceOwnershipSummary.explain(before, logicalPath));
        value.put("after", ResourceOwnershipSummary.explain(after, logicalPath));
        return value;
    }

    private static <T> T next(Iterator<T> iterator) {
        return iterator.hasNext() ? iterator.next() : null;
    }

    record Result(Map<String, Object> values) {
        Result {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        String toJson() {
            return Json.object(values);
        }
    }

    private record ChangeFlags(
            boolean providerSourceSequenceChanged,
            boolean providerSnapshotMetadataOnlyChanged,
            boolean winnerSourceChanged,
            boolean winnerSnapshotMetadataOnlyChanged,
            List<String> reasons) {
        boolean changed() {
            return providerSourceSequenceChanged || providerSnapshotMetadataOnlyChanged;
        }
    }
}
