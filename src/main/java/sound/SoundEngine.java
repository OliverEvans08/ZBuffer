package sound;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Simple pooled-Clip sound engine.
 * Fixes "sometimes doesn't play" by allowing retriggers and overlap (polyphony)
 * and always rewinding clips before starting.
 */
public final class SoundEngine {

    private static final int DEFAULT_VOICES_PER_SOUND = 6; // per wav
    private static final int MAX_PLAYS_PER_TICK = 64;      // safety

    private final Path baseDir;
    private volatile double masterVolume = 1.0;

    private final ConcurrentLinkedQueue<PlayRequest> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, SoundResource> cache = new ConcurrentHashMap<>();

    private volatile boolean closed = false;

    public SoundEngine(String baseDirectory) {
        this.baseDir = Paths.get(baseDirectory == null ? "" : baseDirectory);
    }

    public void setMasterVolume(double v01) {
        if (Double.isNaN(v01)) v01 = 1.0;
        masterVolume = clamp01(v01);
    }

    public double getMasterVolume() {
        return masterVolume;
    }

    /**
     * Queue a sound to be played on the next tick.
     * @param fileName e.g. "jump.wav"
     * @param volume01 0..1
     */
    public void fireSound(String fileName, double volume01) {
        if (closed) return;
        if (fileName == null || fileName.isBlank()) return;
        queue.add(new PlayRequest(fileName.trim(), clamp01(volume01)));
    }

    /**
     * Must be called regularly (you already call this once per fixedUpdate).
     * Drains the play queue and starts clips.
     */
    public void tick() {
        if (closed) return;

        int played = 0;
        while (played < MAX_PLAYS_PER_TICK) {
            PlayRequest req = queue.poll();
            if (req == null) break;

            try {
                playInternal(req.fileName, req.volume01);
            } catch (Throwable ignored) {
                // keep audio failures non-fatal
            }

            played++;
        }
    }

    public void shutdown() {
        closed = true;
        queue.clear();

        for (SoundResource res : cache.values()) {
            if (res == null) continue;
            res.closeQuiet();
        }
        cache.clear();
    }

    // ---------------- internal ----------------

    private void playInternal(String fileName, double volume01) {
        SoundResource res = cache.computeIfAbsent(fileName, fn -> {
            try {
                return SoundResource.load(baseDir, fn, DEFAULT_VOICES_PER_SOUND);
            } catch (Throwable t) {
                return null;
            }
        });

        if (res == null) return;

        double finalVol = clamp01(masterVolume * volume01);
        res.play(finalVol);
    }

    private static final class PlayRequest {
        final String fileName;
        final double volume01;

        PlayRequest(String fileName, double volume01) {
            this.fileName = fileName;
            this.volume01 = volume01;
        }
    }

    private static final class SoundResource {
        final AudioFormat format;
        final byte[] pcmData;
        final Voice[] voices;

        private SoundResource(AudioFormat format, byte[] pcmData, Voice[] voices) {
            this.format = format;
            this.pcmData = pcmData;
            this.voices = voices;
        }

        static SoundResource load(Path baseDir, String fileName, int voicesPerSound) throws Exception {
            Path p = (baseDir == null ? Paths.get(fileName) : baseDir.resolve(fileName));

            if (!Files.exists(p)) {
                // Try also as-is (in case caller already passed full/relative path)
                Path alt = Paths.get(fileName);
                if (Files.exists(alt)) p = alt;
            }

            try (InputStream rawIn = Files.newInputStream(p);
                 BufferedInputStream bin = new BufferedInputStream(rawIn);
                 AudioInputStream in = AudioSystem.getAudioInputStream(bin)) {

                // Convert to a common PCM format for Clips
                AudioFormat src = in.getFormat();
                AudioFormat dst = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        src.getSampleRate(),
                        16,
                        src.getChannels(),
                        src.getChannels() * 2,
                        src.getSampleRate(),
                        false
                );

                try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(dst, in)) {
                    byte[] data = readAllBytes(pcmStream);

                    Voice[] voices = new Voice[Math.max(1, voicesPerSound)];
                    for (int i = 0; i < voices.length; i++) {
                        Clip clip = AudioSystem.getClip();
                        clip.open(dst, data, 0, data.length);
                        voices[i] = new Voice(clip);
                    }

                    return new SoundResource(dst, data, voices);
                }
            }
        }

        void play(double finalVol01) {
            long now = System.nanoTime();

            Voice chosen = null;
            Voice oldest = null;

            for (Voice v : voices) {
                if (v == null || v.clip == null) continue;

                // Prefer a free clip for overlap
                if (!v.clip.isRunning()) {
                    chosen = v;
                    break;
                }

                if (oldest == null || v.lastUseNanos < oldest.lastUseNanos) {
                    oldest = v;
                }
            }

            if (chosen == null) chosen = oldest;
            if (chosen == null || chosen.clip == null) return;

            Clip c = chosen.clip;

            // Always retrigger reliably:
            try { c.stop(); } catch (Throwable ignored) {}
            try { c.flush(); } catch (Throwable ignored) {}
            try { c.setFramePosition(0); } catch (Throwable ignored) {}

            applyVolume(c, finalVol01);

            try { c.start(); } catch (Throwable ignored) {}

            chosen.lastUseNanos = now;
        }

        void closeQuiet() {
            for (Voice v : voices) {
                if (v == null || v.clip == null) continue;
                try { v.clip.stop(); } catch (Throwable ignored) {}
                try { v.clip.close(); } catch (Throwable ignored) {}
            }
        }

        private static byte[] readAllBytes(AudioInputStream ais) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = ais.read(buf)) != -1) out.write(buf, 0, r);
            return out.toByteArray();
        }
    }

    private static final class Voice {
        final Clip clip;
        volatile long lastUseNanos = 0L;

        Voice(Clip clip) {
            this.clip = clip;
        }
    }

    private static void applyVolume(Clip clip, double vol01) {
        if (clip == null) return;

        // If a Clip exposes MASTER_GAIN, set it. Otherwise do nothing.
        try {
            Control ctl = clip.getControl(FloatControl.Type.MASTER_GAIN);
            if (!(ctl instanceof FloatControl fc)) return;

            // Map 0..1 -> dB range (log scale)
            if (vol01 <= 0.0001) {
                fc.setValue(fc.getMinimum());
                return;
            }

            float dB = (float) (20.0 * Math.log10(vol01));
            if (dB < fc.getMinimum()) dB = fc.getMinimum();
            if (dB > fc.getMaximum()) dB = fc.getMaximum();
            fc.setValue(dB);
        } catch (Throwable ignored) {
            // Some mixers don't support MASTER_GAIN.
        }
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
