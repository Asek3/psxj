package nanolive.psxj.retroachievements;

import nanolive.psxj.util.Log;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AchievementSoundPlayer {

    private static final String RESOURCE =
        "/nanolive/psxj/audio/achievement-unlocked.wav.b64";
    private static final ExecutorService PLAYER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "psxj-notification-audio");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile byte[] sound;

    private AchievementSoundPlayer() {
    }

    static void play() {
        PLAYER.execute(AchievementSoundPlayer::playNow);
    }

    private static void playNow() {
        try {
            byte[] data = soundData();
            if (data.length == 0) return;
            try (AudioInputStream stream = AudioSystem.getAudioInputStream(
                new ByteArrayInputStream(data))) {
                Clip clip = AudioSystem.getClip();
                clip.addLineListener(event -> {
                    if (event.getType() == LineEvent.Type.STOP) clip.close();
                });
                clip.open(stream);
                clip.start();
            }
        } catch (Exception failure) {
            Log.debug("Could not play achievement sound: " + failure.getMessage());
        }
    }

    private static byte[] soundData() throws Exception {
        byte[] current = sound;
        if (current != null) return current;
        synchronized (AchievementSoundPlayer.class) {
            if (sound != null) return sound;
            try (InputStream encoded = AchievementSoundPlayer.class.getResourceAsStream(RESOURCE)) {
                sound = encoded == null
                    ? new byte[0]
                    : Base64.getMimeDecoder().wrap(encoded).readAllBytes();
            }
            return sound;
        }
    }
}
