package com.dumcaptions.captions;

import java.util.ArrayList;
import java.util.List;

final class PreparedPacketBatch {
    final List<BufferedOpusPacket> validPackets;
    final List<short[]> decodedFrames;
    final int totalPackets;
    final int decodeErrorPackets;
    final int suspiciousPackets;
    final int duplicatePackets;
    final int outOfOrderPackets;
    final int missingPackets;
    final int maxAmplitude;
    final String firstDropDetail;

    PreparedPacketBatch(
            List<BufferedOpusPacket> validPackets,
            List<short[]> decodedFrames,
            int totalPackets,
            int decodeErrorPackets,
            int suspiciousPackets,
            int duplicatePackets,
            int outOfOrderPackets,
            int missingPackets,
            int maxAmplitude,
            String firstDropDetail) {
        this.validPackets = validPackets;
        this.decodedFrames = decodedFrames;
        this.totalPackets = totalPackets;
        this.decodeErrorPackets = decodeErrorPackets;
        this.suspiciousPackets = suspiciousPackets;
        this.duplicatePackets = duplicatePackets;
        this.outOfOrderPackets = outOfOrderPackets;
        this.missingPackets = missingPackets;
        this.maxAmplitude = maxAmplitude;
        this.firstDropDetail = firstDropDetail;
    }

    int getDroppedPackets() {
        return totalPackets - validPackets.size();
    }

    double getDroppedRate() {
        if (totalPackets == 0) {
            return 0;
        }
        return (double) getDroppedPackets() / totalPackets;
    }

    boolean hasIssues() {
        return getDroppedPackets() > 0 || outOfOrderPackets > 0 || missingPackets > 0;
    }

    boolean shouldWarn() {
        return getDroppedRate() > CaptionsConfig.MAX_OPUS_ERROR_PERCENTAGE
                || suspiciousPackets > 0
                || outOfOrderPackets > 0;
    }

    String summary() {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("opus=%d/%d valid", validPackets.size(), totalPackets));

        int dropped = getDroppedPackets();
        if (dropped > 0) {
            summary.append(String.format(", dropped=%d", dropped));

            List<String> reasons = new ArrayList<>();
            if (decodeErrorPackets > 0) {
                reasons.add("decode=" + decodeErrorPackets);
            }
            if (suspiciousPackets > 0) {
                reasons.add("suspicious=" + suspiciousPackets);
            }
            if (duplicatePackets > 0) {
                reasons.add("duplicate=" + duplicatePackets);
            }
            if (!reasons.isEmpty()) {
                summary.append(" (").append(String.join(", ", reasons)).append(")");
            }
        }

        if (outOfOrderPackets > 0) {
            summary.append(", reordered=").append(outOfOrderPackets);
        }
        if (missingPackets > 0) {
            summary.append(", gaps=").append(missingPackets);
        }
        if (firstDropDetail != null) {
            summary.append(", ").append(firstDropDetail);
        }

        return summary.toString();
    }
}
