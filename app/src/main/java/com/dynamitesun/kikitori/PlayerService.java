package com.dynamitesun.kikitori;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

/** Plays a playlist of bundled clips in the foreground so it keeps going with the screen off. */
public class PlayerService extends Service {
    public interface Listener { void onEvent(String json); }

    public static volatile PlayerService instance;
    public static volatile Listener listener;

    static final String CH = "playback";
    static final int NID = 7;
    public static final String A_PLAY = "kikitori.PLAY", A_PAUSE = "kikitori.PAUSE", A_RESUME = "kikitori.RESUME",
            A_TOGGLE = "kikitori.TOGGLE", A_NEXT = "kikitori.NEXT", A_PREV = "kikitori.PREV", A_STOP = "kikitori.STOP";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable advance = this::playCurrent;
    private MediaPlayer mp;
    private JSONArray items = new JSONArray();
    private float rate = 1f;
    private int gap = 1200, repeat = 1, idx = 0, rep = 0;
    private boolean loop = true, playing = false, active = false, midClip = false;
    private MediaSession session;
    private PowerManager.WakeLock wl;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
    };
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { pause(); }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        session = new MediaSession(this, "Kikitori");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onStop() { stopAll(); }
        });
        session.setActive(true);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kikitori:listen");
        wl.setReferenceCounted(false);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CH, "Listen mode", NotificationManager.IMPORTANCE_LOW));
        }
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String a = intent == null ? null : intent.getAction();
        if (A_PLAY.equals(a)) startPlaylist(intent.getStringExtra("json"));
        else if (A_PAUSE.equals(a)) pause();
        else if (A_RESUME.equals(a)) resume();
        else if (A_TOGGLE.equals(a)) { if (playing) pause(); else resume(); }
        else if (A_NEXT.equals(a)) next();
        else if (A_PREV.equals(a)) prev();
        else if (A_STOP.equals(a)) stopAll();
        if (!active) stopAll();
        return START_NOT_STICKY;
    }

    private void startPlaylist(String json) {
        try {
            JSONObject o = new JSONObject(json);
            items = o.getJSONArray("items");
            rate = (float) o.optDouble("rate", 1.0);
            gap = o.optInt("gap", 1200);
            repeat = Math.max(1, o.optInt("repeat", 1));
            loop = o.optBoolean("loop", true);
            idx = Math.max(0, o.optInt("start", 0));
            rep = 0;
            active = items.length() > 0;
            playing = active;
            if (active) { requestFocus(); playCurrent(); }
        } catch (Exception e) {
            active = false;
        }
    }

    private void requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusListener).build();
            am.requestAudioFocus(focusReq);
        } else {
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void playCurrent() {
        h.removeCallbacks(advance);
        release();
        if (!active || idx < 0 || idx >= items.length()) { stopAll(); return; }
        try {
            JSONObject it = items.getJSONObject(idx);
            AssetFileDescriptor afd = getAssets().openFd(it.getString("src"));
            mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setOnCompletionListener(m -> onClipDone());
            mp.setOnErrorListener((m, what, extra) -> { onClipDone(); return true; });
            mp.prepare();
            mp.start();
            setSpeed();
            midClip = true;
            playing = true;
            wl.acquire(60 * 60 * 1000L);
        } catch (Exception e) {
            midClip = false;
            h.postDelayed(this::onClipDone, 200);
        }
        updateUi();
    }

    private void setSpeed() {
        try { if (mp != null) mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(rate)); } catch (Exception ignored) { }
    }

    private void onClipDone() {
        midClip = false;
        rep++;
        if (rep >= repeat) {
            rep = 0;
            idx++;
            if (idx >= items.length()) {
                if (loop) idx = 0; else { stopAll(); return; }
            }
        }
        if (playing) h.postDelayed(advance, gap);
        updateUi();
    }

    void pause() {
        if (!active) return;
        playing = false;
        h.removeCallbacks(advance);
        try { if (mp != null && mp.isPlaying()) mp.pause(); } catch (Exception ignored) { }
        if (wl.isHeld()) wl.release();
        updateUi();
    }

    void resume() {
        if (!active) return;
        playing = true;
        requestFocus();
        if (mp != null && midClip) {
            try { mp.start(); setSpeed(); wl.acquire(60 * 60 * 1000L); } catch (Exception e) { playCurrent(); return; }
            updateUi();
        } else {
            playCurrent();
        }
    }

    void next() {
        if (!active) return;
        rep = 0;
        idx = idx + 1 >= items.length() ? (loop ? 0 : items.length() - 1) : idx + 1;
        jump();
    }

    void prev() {
        if (!active) return;
        rep = 0;
        idx = Math.max(0, idx - 1);
        jump();
    }

    private void jump() {
        midClip = false;
        if (playing) playCurrent(); else { release(); updateUi(); }
    }

    void stopAll() {
        active = false;
        playing = false;
        midClip = false;
        h.removeCallbacks(advance);
        release();
        if (wl != null && wl.isHeld()) wl.release();
        if (am != null) {
            if (Build.VERSION.SDK_INT >= 26 && focusReq != null) am.abandonAudioFocusRequest(focusReq);
            else am.abandonAudioFocus(focusListener);
        }
        emit();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void release() {
        if (mp != null) { try { mp.release(); } catch (Exception ignored) { } mp = null; }
    }

    private void goForeground() {
        Notification n = buildNotif();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NID, n);
    }

    private PendingIntent pi(String action, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action act(int icon, String label, String action, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi(action, code)).build();
    }

    private String[] current() {
        String title = "Kikitori", text = "Listen mode";
        JSONObject it = (idx >= 0 && idx < items.length()) ? items.optJSONObject(idx) : null;
        if (it != null) { title = it.optString("title", title); text = it.optString("text", text); }
        return new String[]{title, text};
    }

    private Notification buildNotif() {
        String[] c = current();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(c[1])
                .setContentText(c[0] + "  ·  " + (idx + 1) + " / " + items.length())
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(act(R.drawable.ic_prev, "Previous", A_PREV, 1))
                .addAction(playing ? act(R.drawable.ic_pause, "Pause", A_TOGGLE, 2) : act(R.drawable.ic_play, "Play", A_TOGGLE, 2))
                .addAction(act(R.drawable.ic_next, "Next", A_NEXT, 3))
                .addAction(act(R.drawable.ic_stop, "Stop", A_STOP, 4))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private void updateUi() {
        if (active) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            try { nm.notify(NID, buildNotif()); } catch (Exception ignored) { }
            String[] c = current();
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? rate : 0f)
                    .build());
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, c[1])
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, c[0])
                    .build());
        }
        emit();
    }

    public String state() {
        try {
            String[] c = current();
            JSONObject o = new JSONObject();
            o.put("active", active);
            o.put("playing", playing);
            o.put("index", idx);
            o.put("count", items.length());
            o.put("title", c[0]);
            o.put("text", c[1]);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void emit() {
        Listener l = listener;
        if (l != null) l.onEvent(state());
    }

    @Override
    public void onDestroy() {
        instance = null;
        h.removeCallbacks(advance);
        release();
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        if (session != null) session.release();
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }
}
