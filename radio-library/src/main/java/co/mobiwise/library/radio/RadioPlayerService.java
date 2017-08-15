package co.mobiwise.library.radio;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioTrack;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.RemoteViews;

import com.spoledge.aacdecoder.MultiPlayer;
import com.spoledge.aacdecoder.PlayerCallback;

import java.util.ArrayList;
import java.util.List;

import co.mobiwise.library.R;

import static co.mobiwise.library.R.layout.notification;


@SuppressWarnings({"FieldCanBeLocal", "JavaDoc", "WeakerAccess"})
public class RadioPlayerService extends Service implements PlayerCallback {

    public static final String NOTIFICATION_INTENT_PLAY_PAUSE = "co.mobiwise.library.notification.radio.INTENT_PLAYPAUSE";
    public static final String NOTIFICATION_INTENT_CANCEL = "co.mobiwise.library.notification.radio.INTENT_CANCEL";
    public static final String NOTIFICATION_INTENT_OPEN_PLAYER = "co.mobiwise.library.notification.radio.INTENT_OPENPLAYER";

    private String singerName = "";
    private String songName = "";
    private int smallImage = R.drawable.default_art;
    private Bitmap artImage;

    private static final int NOTIFICATION_ID = 1;
    private static boolean isLogging = false;

    private final int AUDIO_BUFFER_CAPACITY_MS = 800;
    private final int AUDIO_DECODE_CAPACITY_MS = 400;

    private final String SUFFIX_PLS = ".pls";
    private final String SUFFIX_RAM = ".ram";
    private final String SUFFIX_WAX = ".wax";

    public enum State {
        IDLE,
        PLAYING,
        STOPPED,
    }

    List<RadioListener> mListenerList;
    private State mRadioState;
    private String mRadioUrl;
    public static final String ACTION_MEDIAPLAYER_STOP = "co.mobiwise.library.ACTION_STOP_MEDIAPLAYER";
    private MultiPlayer mRadioPlayer;
    private TelephonyManager mTelephonyManager;
    private boolean isSwitching;
    private boolean isClosedFromNotification = false;
    private boolean isInterrupted;
    private boolean mLock;
    private NotificationManager mNotificationManager;
    public final IBinder mLocalBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mLocalBinder;
    }

    public class LocalBinder extends Binder {
        public RadioPlayerService getService() {
            return RadioPlayerService.this;
        }
    }

    /**
     * Service called
     *
     * @param intent
     * @param flags
     * @param startId
     * @return
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String action = intent.getAction();

        /*
          If cancel clicked on notification, then set state to
          IDLE, stop player and cancel notification
         */
        if (action.equals(NOTIFICATION_INTENT_CANCEL)) {
            if (isPlaying()) {
                isClosedFromNotification = true;
                stop();
            }
            if (mNotificationManager != null)
                mNotificationManager.cancel(NOTIFICATION_ID);
        }
        /*
          If play/pause action clicked on notification,
          Check player state and stop/play streaming.
         */
        else if (action.equals(NOTIFICATION_INTENT_PLAY_PAUSE)) {
            if (isPlaying())
                stop();
            else if (mRadioUrl != null)
                play(mRadioUrl);

        }

        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            mListenerList = new ArrayList<>();

            mRadioState = State.IDLE;
            isSwitching = false;
            isInterrupted = false;
            mLock = false;
            getPlayer();

            mTelephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            if (mTelephonyManager != null)
                mTelephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        } catch (Exception ex) {
            Log.e("onCreate", ex.getMessage());
        }
    }

    /**
     * Play url if different from previous streaming url.
     *
     * @param mRadioUrl
     */
    public void play(String mRadioUrl) {

        try {
            sendBroadcast(new Intent(ACTION_MEDIAPLAYER_STOP));

            notifyRadioLoading();

            if (checkSuffix(mRadioUrl))
                decodeStremLink(mRadioUrl);
            else {
                this.mRadioUrl = mRadioUrl;
                isSwitching = false;

                if (isPlaying()) {
                    log("Switching Radio");
                    isSwitching = true;
                    stop();
                } else if (!mLock) {
                    log("Play requested.");
                    mLock = true;
                    getPlayer().playAsync(mRadioUrl);
                }
            }
        } catch (Exception ex) {
            Log.e("play", ex.getMessage());
        }
    }

    public void stop() {
        try {
            if (!mLock && mRadioState != State.STOPPED) {
                log("Stop requested.");
                mLock = true;
                getPlayer().stop();
            }
        } catch (Exception ex) {
            Log.e("stop", ex.getMessage());
        }
    }

    @Override
    public void playerStarted() {
        mRadioState = State.PLAYING;
//        buildNotification();
        mLock = false;
        notifyRadioStarted();

        log("Player started. tate : " + mRadioState);

        if (isInterrupted)
            isInterrupted = false;

    }

    public boolean isPlaying() {
        return State.PLAYING == mRadioState;
    }

    public void resume() {
        if (mRadioUrl != null)
            play(mRadioUrl);
    }

    public void stopFromNotification() {
        isClosedFromNotification = true;
        if (mNotificationManager != null) mNotificationManager.cancelAll();
        stop();
    }

    @Override
    public void playerPCMFeedBuffer(boolean b, int i, int i1) {
        //Empty
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public void playerStopped(int i) {

        mRadioState = State.STOPPED;

        /*
          If player stopped from notification then dont
          call buildNotification().
         */
        if (!isClosedFromNotification) {
//            buildNotification();
        } else {
            isClosedFromNotification = false;
        }

        mLock = false;
        notifyRadioStopped();
        log("Player stopped. State : " + mRadioState);

        if (isSwitching)
            play(mRadioUrl);


    }

    @Override
    public void playerException(Throwable throwable) {
        try {
            mLock = false;
            mRadioPlayer = null;
            getPlayer();
            notifyErrorOccured();
            log("ERROR OCCURED.");
        }
        catch (Exception ex)
        {
            Log.e("playerException", ex.getMessage());
        }
    }

    @Override
    public void playerMetadata(String s, String s2) {
        notifyMetaDataChanged(s, s2);
    }

    @Override
    public void playerAudioTrackCreated(AudioTrack audioTrack) {
        //Empty
    }

    public void registerListener(RadioListener mListener) {
        mListenerList.add(mListener);
    }

    public void unregisterListener(RadioListener mListener) {
        mListenerList.remove(mListener);
    }

    private void notifyRadioStarted() {
        for (RadioListener mRadioListener : mListenerList) {
            mRadioListener.onRadioStarted();
        }
    }

    private void notifyRadioStopped() {
        for (RadioListener mRadioListener : mListenerList)
            mRadioListener.onRadioStopped();
    }

    private void notifyMetaDataChanged(String s, String s2) {
        for (RadioListener mRadioListener : mListenerList)
            mRadioListener.onMetaDataReceived(s, s2);
    }

    private void notifyRadioLoading() {
        for (RadioListener mRadioListener : mListenerList) {
            mRadioListener.onRadioLoading();
        }
    }

    private void notifyErrorOccured() {
        for (RadioListener mRadioListener : mListenerList) {
            mRadioListener.onError();
        }
    }


    /**
     * Return AAC player. If it is not initialized, creates and returns.
     *
     * @return MultiPlayer
     */
    private MultiPlayer getPlayer() {
        try {

            java.net.URL.setURLStreamHandlerFactory(new java.net.URLStreamHandlerFactory() {

                public java.net.URLStreamHandler createURLStreamHandler(String protocol) {
                    Log.d("LOG", "Asking for stream handler for protocol: '" + protocol + "'");
                    if ("icy".equals(protocol))
                        return new com.spoledge.aacdecoder.IcyURLStreamHandler();
                    return null;
                }
            });
        } catch (Throwable t) {
            Log.w("LOG", "Cannot set the ICY URLStreamHandler - maybe already set ? - " + t);
        }

        if (mRadioPlayer == null) {
            mRadioPlayer = new MultiPlayer(this, AUDIO_BUFFER_CAPACITY_MS, AUDIO_DECODE_CAPACITY_MS);
            mRadioPlayer.setResponseCodeCheckEnabled(false);
            mRadioPlayer.setPlayerCallback(this);
        }
        return mRadioPlayer;
    }

    PhoneStateListener phoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if (state == TelephonyManager.CALL_STATE_RINGING) {

                /*
                  Stop radio and set interrupted if it is playing on incoming call.
                 */
                if (isPlaying()) {
                    isInterrupted = true;
                    stop();
                }

            } else if (state == TelephonyManager.CALL_STATE_IDLE) {

                /*
                  Keep playing if it is interrupted.
                 */
                if (isInterrupted)
                    play(mRadioUrl);

            } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {

                /*
                  Stop radio and set interrupted if it is playing on outgoing call.
                 */
                if (isPlaying()) {
                    isInterrupted = true;
                    stop();
                }

            }
            super.onCallStateChanged(state, incomingNumber);
        }
    };

    /**
     * Check supported suffix
     *
     * @param streamUrl
     * @return
     */
    public boolean checkSuffix(String streamUrl) {
        return streamUrl.contains(SUFFIX_PLS) ||
                streamUrl.contains(SUFFIX_RAM) ||
                streamUrl.contains(SUFFIX_WAX);
    }

    /**
     * Enable/Disable log
     *
     * @param logging
     */
    public void setLogging(boolean logging) {
        isLogging = logging;
    }

    /**
     * Logger
     *
     * @param log
     */
    private void log(String log) {
        if (isLogging)
            Log.v("RadioManager", "RadioPlayerService : " + log);
    }

    /**
     * If stream link is a file, then we
     * call stream decoder to get HTTP stream link
     * from that file.
     *
     * @param streamLink
     */
    private void decodeStremLink(String streamLink) {
        new StreamLinkDecoder(streamLink) {
            @Override
            protected void onPostExecute(String s) {
                super.onPostExecute(s);
                play(s);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Build notification
     */
    @SuppressWarnings("deprecation")
    private void buildNotification() {

        /*
          Intents
         */
        Intent intentPlayPause = new Intent(NOTIFICATION_INTENT_PLAY_PAUSE);
        Intent intentOpenPlayer = new Intent(NOTIFICATION_INTENT_OPEN_PLAYER);
        Intent intentCancel = new Intent(NOTIFICATION_INTENT_CANCEL);

        /*
          Pending intents
         */
        PendingIntent playPausePending = PendingIntent.getBroadcast(this, 23, intentPlayPause, 0);
        PendingIntent openPending = PendingIntent.getBroadcast(this, 31, intentOpenPlayer, 0);
        PendingIntent cancelPending = PendingIntent.getBroadcast(this, 12, intentCancel, 0);

        /*
          Remote view for normal view
         */

        RemoteViews mNotificationTemplate = new RemoteViews(this.getPackageName(), notification);
        Notification.Builder notificationBuilder = new Notification.Builder(this);

        /*
          set small notification texts and image
         */
        if (artImage == null)
            artImage = BitmapFactory.decodeResource(getResources(), R.drawable.default_art);

        mNotificationTemplate.setTextViewText(R.id.notification_line_one, singerName);
        mNotificationTemplate.setTextViewText(R.id.notification_line_two, songName);
        mNotificationTemplate.setImageViewResource(R.id.notification_play, isPlaying() ? R.drawable.btn_playback_pause : R.drawable.btn_playback_play);
        mNotificationTemplate.setImageViewBitmap(R.id.notification_image, artImage);

        /*
          OnClickPending intent for collapsed notification
         */
        mNotificationTemplate.setOnClickPendingIntent(R.id.notification_collapse, cancelPending);
        mNotificationTemplate.setOnClickPendingIntent(R.id.notification_play, playPausePending);

        /*
          Create notification instance
         */

        /*
          Expanded notification
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {

            Notification notification = notificationBuilder
                    .setSmallIcon(smallImage)
                    .setContentIntent(openPending)
                    .setPriority(Notification.PRIORITY_DEFAULT)
                    .setContent(mNotificationTemplate)
                    .setUsesChronometer(true)
                    .build();
            notification.flags = Notification.FLAG_ONGOING_EVENT;

            RemoteViews mExpandedView = new RemoteViews(this.getPackageName(), R.layout.notification_expanded);

            mExpandedView.setTextViewText(R.id.notification_line_one, singerName);
            mExpandedView.setTextViewText(R.id.notification_line_two, songName);
            mExpandedView.setImageViewResource(R.id.notification_expanded_play, isPlaying() ? R.drawable.btn_playback_pause : R.drawable.btn_playback_play);
            mExpandedView.setImageViewBitmap(R.id.notification_image, artImage);

            mExpandedView.setOnClickPendingIntent(R.id.notification_collapse, cancelPending);
            mExpandedView.setOnClickPendingIntent(R.id.notification_expanded_play, playPausePending);

            notification.bigContentView = mExpandedView;

            if (mNotificationManager != null)
                mNotificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    public void updateNotification(String singerName, String songName, int smallImage, int artImage) {
        this.singerName = singerName;
        this.songName = songName;
        this.smallImage = smallImage;
        this.artImage = BitmapFactory.decodeResource(getResources(), artImage);
//        buildNotification();
    }


    public void updateNotification(String singerName, String songName, int smallImage, Bitmap artImage) {
        this.singerName = singerName;
        this.songName = songName;
        this.smallImage = smallImage;
        this.artImage = artImage;
//        buildNotification();
    }


}
