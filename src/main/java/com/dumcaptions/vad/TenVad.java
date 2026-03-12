package com.dumcaptions.vad;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.FloatByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.dumcaptions.captions.CaptionsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ShortBuffer;

public class TenVad implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(TenVad.class);

    public interface TenVadLibrary extends Library {
        TenVadLibrary INSTANCE = (TenVadLibrary) Native.load("ten_vad", TenVadLibrary.class);

        int ten_vad_create(PointerByReference handle, long hop_size, float threshold);
        int ten_vad_process(Pointer handle, short[] audio_data, long audio_data_length, FloatByReference out_probability, IntByReference out_flag);
        int ten_vad_destroy(PointerByReference handle);
    }

    static {
        // Look for native library in the "libs" directory relative to the current working directory
        String libDir = new File("libs").getAbsolutePath();
        System.setProperty("jna.library.path", libDir);
    }

    private Pointer handle;
    private final int hopSize;

    public TenVad(int hopSize, float threshold) throws Exception {
        this.hopSize = hopSize;
        PointerByReference ph = new PointerByReference();
        int ret = TenVadLibrary.INSTANCE.ten_vad_create(ph, hopSize, threshold);
        if (ret != 0) {
            throw new Exception("Failed to create TEN-VAD instance: " + ret);
        }
        this.handle = ph.getValue();
    }

    public static class VadResult {
        public final float probability;
        public final boolean isSpeech;

        public VadResult(float probability, boolean isSpeech) {
            this.probability = probability;
            this.isSpeech = isSpeech;
        }
    }

    public VadResult process(short[] audioData) throws Exception {
        if (handle == null) {
            throw new Exception("VAD instance is closed");
        }
        if (audioData.length != hopSize) {
            throw new Exception("Data length " + audioData.length + " does not match hop size " + hopSize);
        }

        FloatByReference prob = new FloatByReference();
        IntByReference flag = new IntByReference();
        
        int ret = TenVadLibrary.INSTANCE.ten_vad_process(handle, audioData, hopSize, prob, flag);
        if (ret != 0) {
            throw new Exception("Failed to process TEN-VAD: " + ret);
        }

        return new VadResult(prob.getValue(), flag.getValue() == 1);
    }

    @Override
    public void close() {
        if (handle != null) {
            PointerByReference ph = new PointerByReference(handle);
            TenVadLibrary.INSTANCE.ten_vad_destroy(ph);
            handle = null;
        }
    }
}
