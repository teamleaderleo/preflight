package dev.starsector.preflight.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Decodes Ogg/Vorbis through the same jogg/jorbis call sequence the installed game uses.
 *
 * <p>The shipped {@code sound/void} is the only class in {@code fs.sound_obf.jar} that touches the
 * decoder, and its disassembly references exactly this surface: {@code SyncState.init/buffer/wrote/
 * pageout/data/clear}, {@code StreamState.init/pagein/packetout/clear}, {@code Page.serialno/eos},
 * {@code Info.init/synthesis_headerin/channels/rate/clear}, {@code DspState.synthesis_init/
 * synthesis_blockin/synthesis_pcmout/synthesis_read/clear}, {@code Block.init/synthesis/clear} and
 * {@code Comment.init}.
 *
 * <p>It deliberately does <strong>not</strong> use {@code com.jcraft.jorbis.VorbisFile}. That class is
 * present in {@code jorbis-0.0.15.jar} but no JAR in the reviewed installation references it, and on
 * identical input the two paths do not produce the same audio — the earlier harness decoded a
 * clipping-stress fixture to complete digital silence through {@code VorbisFile} while this path
 * produces full-scale audio. Choosing the API decides the answer before any comparison happens, so the
 * choice belongs here, documented, rather than in whichever call site is convenient.
 *
 * <p>Everything is reflective because the decoder archives are not build dependencies; they are
 * supplied by the child JVM's classpath so that the exact installed bytes are what runs.
 */
final class LowLevelVorbisDecoder {

    /** Matches the read granularity the installed decoder requests from its source stream. */
    private static final int READ_BUFFER_BYTES = 4_096;

    private final Constructor<?> syncStateCtor;
    private final Constructor<?> streamStateCtor;
    private final Constructor<?> pageCtor;
    private final Constructor<?> packetCtor;
    private final Constructor<?> infoCtor;
    private final Constructor<?> commentCtor;
    private final Constructor<?> dspStateCtor;
    private final Constructor<?> blockCtor;

    private final Method syncInit;
    private final Method syncBuffer;
    private final Method syncWrote;
    private final Method syncPageout;
    private final Method syncClear;
    private final Field syncData;

    private final Method streamInit;
    private final Method streamPagein;
    private final Method streamPacketout;
    private final Method streamClear;

    private final Method pageSerialno;
    private final Method pageEos;

    private final Method infoInit;
    private final Method infoHeaderin;
    private final Method infoClear;
    private final Field infoChannels;
    private final Field infoRate;

    private final Method commentInit;

    private final Method dspSynthesisInit;
    private final Method dspBlockin;
    private final Method dspPcmout;
    private final Method dspRead;
    private final Method dspClear;

    private final Method blockInit;
    private final Method blockSynthesis;
    private final Method blockClear;

    LowLevelVorbisDecoder() throws Exception {
        Class<?> syncState = Class.forName("com.jcraft.jogg.SyncState");
        Class<?> streamState = Class.forName("com.jcraft.jogg.StreamState");
        Class<?> page = Class.forName("com.jcraft.jogg.Page");
        Class<?> packet = Class.forName("com.jcraft.jogg.Packet");
        Class<?> info = Class.forName("com.jcraft.jorbis.Info");
        Class<?> comment = Class.forName("com.jcraft.jorbis.Comment");
        Class<?> dspState = Class.forName("com.jcraft.jorbis.DspState");
        Class<?> block = Class.forName("com.jcraft.jorbis.Block");

        syncStateCtor = syncState.getConstructor();
        streamStateCtor = streamState.getConstructor();
        pageCtor = page.getConstructor();
        packetCtor = packet.getConstructor();
        infoCtor = info.getConstructor();
        commentCtor = comment.getConstructor();
        dspStateCtor = dspState.getConstructor();
        blockCtor = block.getConstructor(dspState);

        syncInit = syncState.getMethod("init");
        syncBuffer = syncState.getMethod("buffer", int.class);
        syncWrote = syncState.getMethod("wrote", int.class);
        syncPageout = syncState.getMethod("pageout", page);
        syncClear = syncState.getMethod("clear");
        syncData = syncState.getField("data");

        streamInit = streamState.getMethod("init", int.class);
        streamPagein = streamState.getMethod("pagein", page);
        streamPacketout = streamState.getMethod("packetout", packet);
        streamClear = streamState.getMethod("clear");

        pageSerialno = page.getMethod("serialno");
        pageEos = page.getMethod("eos");

        infoInit = info.getMethod("init");
        infoHeaderin = info.getMethod("synthesis_headerin", comment, packet);
        infoClear = info.getMethod("clear");
        infoChannels = info.getField("channels");
        infoRate = info.getField("rate");

        commentInit = comment.getMethod("init");

        dspSynthesisInit = dspState.getMethod("synthesis_init", info);
        dspBlockin = dspState.getMethod("synthesis_blockin", block);
        dspPcmout = dspState.getMethod("synthesis_pcmout", float[][][].class, int[].class);
        dspRead = dspState.getMethod("synthesis_read", int.class);
        dspClear = dspState.getMethod("clear");

        blockInit = block.getMethod("init", dspState);
        blockSynthesis = block.getMethod("synthesis", packet);
        blockClear = block.getMethod("clear");
    }

    /** Signed 16-bit little-endian PCM plus the metadata the wrapper contract needs. */
    record Decoded(byte[] pcm, int channels, int sampleRate, int packets, boolean sawEndOfStream) {
    }

    Decoded decode(InputStream input) throws Exception {
        Object sync = syncStateCtor.newInstance();
        Object stream = streamStateCtor.newInstance();
        Object page = pageCtor.newInstance();
        Object packet = packetCtor.newInstance();
        Object info = infoCtor.newInstance();
        Object comment = commentCtor.newInstance();
        Object dsp = dspStateCtor.newInstance();
        Object block = blockCtor.newInstance(dsp);

        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        syncInit.invoke(sync);
        boolean streamStarted = false;
        boolean sawEndOfStream = false;
        int headerPackets = 0;
        int audioPackets = 0;
        int channels = 0;
        int sampleRate = 0;
        byte[] convert = null;
        int convertFrames = 0;

        try {
            while (!sawEndOfStream) {
                int index = (Integer) syncBuffer.invoke(sync, READ_BUFFER_BYTES);
                byte[] data = (byte[]) syncData.get(sync);
                int read = input.read(data, index, READ_BUFFER_BYTES);
                if (read < 0) {
                    read = 0;
                }
                syncWrote.invoke(sync, read);

                while (!sawEndOfStream) {
                    int pageStatus = (Integer) syncPageout.invoke(sync, page);
                    if (pageStatus == 0) {
                        break;
                    }
                    if (pageStatus < 0) {
                        // A hole in the data. The installed loop keeps going rather than failing.
                        continue;
                    }
                    if (!streamStarted) {
                        streamInit.invoke(stream, pageSerialno.invoke(page));
                        infoInit.invoke(info);
                        commentInit.invoke(comment);
                        streamStarted = true;
                    }
                    if ((Integer) streamPagein.invoke(stream, page) < 0) {
                        throw new IOException("jogg rejected a page belonging to the stream");
                    }
                    while (true) {
                        int packetStatus = (Integer) streamPacketout.invoke(stream, packet);
                        if (packetStatus == 0) {
                            break;
                        }
                        if (packetStatus < 0) {
                            continue;
                        }
                        if (headerPackets < 3) {
                            if ((Integer) infoHeaderin.invoke(info, comment, packet) < 0) {
                                throw new IOException("jorbis rejected the Vorbis headers");
                            }
                            if (++headerPackets == 3) {
                                channels = infoChannels.getInt(info);
                                sampleRate = infoRate.getInt(info);
                                if (channels < 1 || sampleRate < 1) {
                                    throw new IOException("jorbis reported unusable stream metadata");
                                }
                                dspSynthesisInit.invoke(dsp, info);
                                blockInit.invoke(block, dsp);
                                convertFrames = READ_BUFFER_BYTES / channels;
                                convert = new byte[convertFrames * 2 * channels];
                            }
                            continue;
                        }
                        audioPackets++;
                        if ((Integer) blockSynthesis.invoke(block, packet) == 0) {
                            dspBlockin.invoke(dsp, block);
                        }
                        drain(dsp, channels, convert, convertFrames, pcmOut);
                    }
                    if ((Integer) pageEos.invoke(page) != 0) {
                        sawEndOfStream = true;
                    }
                }
                if (read == 0) {
                    break;
                }
            }
        } finally {
            if (streamStarted) {
                streamClear.invoke(stream);
            }
            blockClear.invoke(block);
            dspClear.invoke(dsp);
            infoClear.invoke(info);
            syncClear.invoke(sync);
        }

        if (headerPackets < 3) {
            throw new IOException("stream ended before the three Vorbis headers were read");
        }
        byte[] pcm = pcmOut.toByteArray();
        int frameBytes = Math.multiplyExact(channels, 2);
        if (pcm.length % frameBytes != 0) {
            throw new IOException("decoded PCM is not frame aligned");
        }
        return new Decoded(pcm, channels, sampleRate, audioPackets, sawEndOfStream);
    }

    /**
     * Converts every ready block to signed 16-bit little-endian and hands it back to the decoder.
     *
     * <p>The scale and clamp mirror the installed converter, which multiplies by {@code 32767.0} and
     * saturates to {@code [-32768, 32767]} rather than wrapping.
     */
    private void drain(Object dsp, int channels, byte[] convert, int convertFrames,
            ByteArrayOutputStream pcmOut) throws Exception {
        float[][][] pcm = new float[1][][];
        int[] offsets = new int[channels];
        while (true) {
            int available = (Integer) dspPcmout.invoke(dsp, pcm, offsets);
            if (available <= 0) {
                return;
            }
            int frames = Math.min(available, convertFrames);
            for (int channel = 0; channel < channels; channel++) {
                int out = channel * 2;
                float[] samples = pcm[0][channel];
                int base = offsets[channel];
                for (int frame = 0; frame < frames; frame++) {
                    int value = (int) (samples[base + frame] * 32767.0);
                    if (value > 32767) {
                        value = 32767;
                    } else if (value < -32768) {
                        value = -32768;
                    }
                    convert[out] = (byte) value;
                    convert[out + 1] = (byte) (value >>> 8);
                    out += 2 * channels;
                }
            }
            pcmOut.write(convert, 0, 2 * channels * frames);
            dspRead.invoke(dsp, frames);
        }
    }
}
