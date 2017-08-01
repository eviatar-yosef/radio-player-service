package co.mobiwise.library.media;

@SuppressWarnings("WeakerAccess")
public interface MediaListener {

    void onMediaLoading();

    void onMediaStarted(int totalDuration, int currentDuration);

    void onMediaStopped();

}
