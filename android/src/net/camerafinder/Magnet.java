package net.camerafinder;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.SystemClock;

/**
 * Magnetic-field sweep. Powered electronics (camera boards, batteries, wiring)
 * disturb the magnetic field right next to them, so a sharp jump while you pass
 * the phone over an object that shouldn't contain electronics is a red flag.
 * Works with no network at all, so it also finds offline / SD-card cameras.
 */
final class Magnet implements SensorEventListener {

    interface Listener { void reading(float microTesla, float baseline, boolean calibrating); }

    private static final int CALIBRATION_SAMPLES = 50;   // ~1 s at SENSOR_DELAY_GAME

    private final SensorManager sm;
    private final Sensor sensor;
    private Listener listener;
    private ToneGenerator tone;
    private volatile boolean sound = true;

    private float smooth = -1f, baseline = -1f, calibSum;
    private int calibCount;
    private long lastBeep, lastPush;

    Magnet(Context c) {
        sm = (SensorManager) c.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        sensor = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
    }

    boolean available() { return sensor != null; }

    /** Call on the main thread. */
    boolean start(Listener l) {
        if (sensor == null) return false;
        stop();
        listener = l;
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80); } catch (RuntimeException e) { tone = null; }
        calibrate();
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        return true;
    }

    void stop() {
        if (sm != null) sm.unregisterListener(this);
        listener = null;
        if (tone != null) { tone.release(); tone = null; }
    }

    /** Re-learn "normal" for the current spot (hold the phone away from objects). */
    void calibrate() {
        smooth = -1f; baseline = -1f; calibSum = 0f; calibCount = 0;
    }

    void setSound(boolean on) { sound = on; }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        float m = (float) Math.sqrt(x * x + y * y + z * z);
        smooth = smooth < 0 ? m : smooth * 0.7f + m * 0.3f;

        boolean calibrating = baseline < 0;
        if (calibrating) {
            calibSum += smooth;
            if (++calibCount >= CALIBRATION_SAMPLES) baseline = calibSum / calibCount;
        }

        long now = SystemClock.uptimeMillis();
        float delta = baseline < 0 ? 0f : Math.abs(smooth - baseline);
        if (sound && tone != null && delta >= 10f) {
            // Geiger-counter style: the stronger the disturbance, the faster the beeps.
            long interval = Math.max(70, Math.min(600, (long) (650 - delta * 12)));
            if (now - lastBeep >= interval) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 40);
                lastBeep = now;
            }
        }
        Listener l = listener;
        if (l != null && now - lastPush >= 100) {
            lastPush = now;
            l.reading(smooth, baseline, baseline < 0);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {}
}
