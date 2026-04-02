package com.dumcaptions.captions;

import net.dv8tion.jda.api.audio.OpusPacket;

final class BufferedOpusPacket implements Comparable<BufferedOpusPacket> {
    private final int sequence;
    private final int timestamp;
    private final byte[] opusAudio;

    private BufferedOpusPacket(int sequence, int timestamp, byte[] opusAudio) {
        this.sequence = sequence;
        this.timestamp = timestamp;
        this.opusAudio = opusAudio;
    }

    static BufferedOpusPacket from(OpusPacket packet) {
        return new BufferedOpusPacket(
                packet.getSequence() & 0xFFFF,
                packet.getTimestamp(),
                packet.getOpusAudio());
    }

    int getSequence() {
        return sequence;
    }

    int getTimestamp() {
        return timestamp;
    }

    byte[] getOpusAudio() {
        return opusAudio;
    }

    @Override
    public int compareTo(BufferedOpusPacket other) {
        return Integer.compare(sequence, other.sequence);
    }
}
