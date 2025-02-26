package sound;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

public class SoundEngine {

    private Map<String, Clip> soundClips;
    private Queue<String> soundQueue;
    private String soundsDirectory;

    public SoundEngine(String soundsDirectory) {
        this.soundsDirectory = soundsDirectory;
        soundClips = new HashMap<>();
        soundQueue = new LinkedList<>();
        loadSounds();
    }

    private void loadSounds() {
        File dir = new File(soundsDirectory);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".wav"));
            if (files != null) {
                for (File file : files) {
                    try {
                        loadSound(file.getName(), file);
                        System.out.println("Loaded sound file " + file.getName());
                    } catch (Exception e) {
                        System.err.println("Error loading sound: " + file.getName());
                    }
                }
            }
        } else {
            System.err.println("Directory does not exist: " + soundsDirectory);
        }
    }

    private void loadSound(String soundName, File soundFile) throws UnsupportedAudioFileException, IOException, LineUnavailableException {
        AudioInputStream audioStream = AudioSystem.getAudioInputStream(soundFile);
        Clip clip = AudioSystem.getClip();
        clip.open(audioStream);
        soundClips.put(soundName, clip);
    }

    public void fireSound(String soundName) {
        if (soundClips.containsKey(soundName)) {
            soundQueue.add(soundName);
        } else {
            System.err.println("Sound not found: " + soundName);
        }
    }

    public void tick() {
        while (!soundQueue.isEmpty()) {
            String soundName = soundQueue.poll();
            playSound(soundName);
        }
    }

    private void playSound(String soundName) {
        Clip clip = soundClips.get(soundName);
        if (clip != null) {
            clip.stop();
            clip.setFramePosition(0);
            clip.start();
        } else {
            System.err.println("Sound not found: " + soundName);
        }
    }

    public void stopSound(String soundName) {
        Clip clip = soundClips.get(soundName);
        if (clip != null) {
            clip.stop();
        } else {
            System.err.println("Sound not found: " + soundName);
        }
    }

    public void close() {
        for (Clip clip : soundClips.values()) {
            clip.close();
        }
        soundClips.clear();
        soundQueue.clear();
    }
}