package com.dumcaptions.captions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Random;

/**
 * Minimalist OGG/Opus container writer.
 * Sufficient for Groq API to recognize the audio format.
 */
public class OggOpusWriter {
    
    // Ogg Header Types
    private static final byte HEADER_TYPE_BOS = 0x02; // Beginning of stream
    private static final byte HEADER_TYPE_EOS = 0x04; // End of stream

    public static byte[] write(List<BufferedOpusPacket> packets) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int serial = new Random().nextInt();
        
        // 1. OpusHead header (BOS)
        writePage(out, serial, 0, 0, createOpusHead(), HEADER_TYPE_BOS);
        
        // 2. OpusTags header (empty)
        writePage(out, serial, 1, 0, createOpusTags(), (byte) 0);
        
        // 3. Data pages
        long granulePos = 0;
        int pageSeq = 2;
        for (int i = 0; i < packets.size(); i++) {
            byte[] packet = packets.get(i).getOpusAudio();
            granulePos += 960; // 20ms at 48kHz
            
            byte headerType = (i == packets.size() - 1) ? HEADER_TYPE_EOS : (byte) 0;
            writePage(out, serial, pageSeq++, granulePos, packet, headerType);
        }
        
        return out.toByteArray();
    }
    
    private static void writePage(ByteArrayOutputStream out, int serial, int seq, long granule, byte[] payload, byte headerType) throws IOException {
        ByteArrayOutputStream page = new ByteArrayOutputStream();
        
        page.write("OggS".getBytes());
        page.write(0); // version
        page.write(headerType); 
        
        byte[] g = new byte[8];
        ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN).putLong(granule);
        page.write(g); // granule position
        
        byte[] s = new byte[4];
        ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN).putInt(serial);
        page.write(s); // serial number
        
        byte[] p = new byte[4];
        ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(seq);
        page.write(p); // page sequence
        
        // Checksum placeholder (4 bytes) - Offset 22
        page.write(new byte[4]); 
        
        // Segment Table
        int segmentCount = (payload.length / 255) + 1;
        page.write(segmentCount);
        
        int remaining = payload.length;
        for (int i = 0; i < segmentCount; i++) {
            int len = Math.min(remaining, 255);
            page.write(len);
            remaining -= len;
        }
        
        page.write(payload);
        
        byte[] pageBytes = page.toByteArray();
        
        // Calculate CRC and write it at offset 22
        int crc = calculateCRC(pageBytes);
        ByteBuffer.wrap(pageBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc);
        
        out.write(pageBytes);
    }
    
    private static byte[] createOpusHead() {
        ByteBuffer buf = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("OpusHead".getBytes());
        buf.put((byte) 1); // version
        buf.put((byte) 2); // channels (Discord is stereo)
        buf.putShort((short) 0); // pre-skip
        buf.putInt(48000); // sample rate
        buf.putShort((short) 0); // output gain
        buf.put((byte) 0); // mapping family
        return buf.array();
    }
    
    private static byte[] createOpusTags() {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("OpusTags".getBytes());
        buf.putInt(0); // vendor length
        buf.putInt(0); // user comment list length
        return buf.array();
    }

    // Ogg CRC-32: polynomial 0x04C11DB7
    private static int calculateCRC(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            int temp = (crc << 8) ^ TABLE[( (crc >>> 24) ^ (b & 0xFF) ) & 0xFF];
            crc = temp;
        }
        return crc;
    }

    private static final int[] TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                if ((r & 0x80000000) != 0) {
                    r = (r << 1) ^ 0x04C11DB7;
                } else {
                    r <<= 1;
                }
            }
            TABLE[i] = r;
        }
    }
}
