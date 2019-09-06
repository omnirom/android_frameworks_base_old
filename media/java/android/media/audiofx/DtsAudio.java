package android.media.audiofx;

import android.util.Log;
import java.util.UUID;

public class DtsAudio extends AudioEffect {
    private static final String TAG = DtsAudio.class.getSimpleName();

    public DtsAudio(UUID type, UUID uuid, int priority, int audioSession) throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException, RuntimeException {
        super(type, uuid, priority, audioSession);
        Log.d(TAG, "constructor");
    }

    public int setEnabled(boolean enabled) throws IllegalStateException {
        String str = TAG;
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("setEnabled ");
        stringBuilder.append(enabled);
        Log.d(str, stringBuilder.toString());
        return super.setEnabled(enabled);
    }

    public boolean getEnabled() throws IllegalStateException {
        Log.d(TAG, "getEnabled");
        return super.getEnabled();
    }

    public int setParameter(byte[] param, byte[] value) throws IllegalStateException {
        Log.d(TAG, "setParameter");
        return super.setParameter(param, value);
    }

    public int getParameter(byte[] param, byte[] value) throws IllegalStateException {
        Log.d(TAG, "getParameter");
        return super.getParameter(param, value);
    }
}
