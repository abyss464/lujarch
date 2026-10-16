package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session whose bytes come from and go to a {@link HostTransport} (the Lujarch Linux
 * host's attach connection) instead of a process spawned through JNI: the host owns the PTYs.
 * <p>
 * Output from the host is handed to {@link #onHostOutput(byte[], int)} from any thread and fed to
 * the emulator on the main thread, where all emulation and callback methods run. The emulator is
 * created by the first {@link #updateSize(int, int, int, int)}.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    /** Where the session's input and size changes go. Calls come from the main thread. */
    public interface HostTransport {
        /** Send keyboard input (or the emulator's replies) to the session's shell; the caller reuses the array, so copy it. */
        void sendInput(byte[] data, int offset, int count);

        /** Tell the host the window size; it sends SIGWINCH to the session's foreground job. */
        void sendResize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);
    }

    private static final int MSG_OUTPUT = 1;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** Buffer to write translate code points into utf8 before writing to the transport */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    private volatile HostTransport mTransport;

    /** Set once the host reported the shell's exit. */
    private boolean mFinished;
    private int mShellExitStatus;

    /**
     * The emulator answers queries it finds in the output (cursor position, device attributes). The
     * host replays up to 2 MiB of old output at every attach, and answers to old queries would be
     * typed into the shell; replies are dropped until this uptime (milliseconds).
     */
    private volatile long mReplyHoldUntil;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final Integer mTranscriptRows;

    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    public void setTransport(HostTransport transport) {
        mTransport = transport;
    }

    /** Drop emulator replies for the next {@code millis} milliseconds. */
    public void holdReplies(long millis) {
        mReplyHoldUntil = SystemClock.uptimeMillis() + millis;
    }

    /**
     * Forget the screen so that the next {@link #updateSize(int, int, int, int)} starts an empty
     * emulator: the host replays its buffer at every attach, a kept emulator would show it twice.
     */
    public void dropEmulator() {
        mEmulator = null;
        mFinished = false;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the host of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            HostTransport transport = mTransport;
            if (transport != null) transport.sendResize(columns, rows, cellWidthPixels, cellHeightPixels);
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
    }

    /**
     * Output of the session's shell, from any thread. The bytes are copied and fed to the emulator
     * on the main thread; output that arrives before the emulator exists is dropped (the host
     * replays its buffer at the next attach).
     */
    public void onHostOutput(byte[] data, int length) {
        if (length <= 0) return;
        byte[] copy = new byte[length];
        System.arraycopy(data, 0, copy, 0, length);
        mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_OUTPUT, copy));
    }

    /** The host reported the shell's exit (status as {@code waitpid} gave it, negated signal). */
    public void onHostExit(final int status) {
        mMainThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (TerminalSession.this) {
                    mFinished = true;
                    mShellExitStatus = status;
                }
                if (mEmulator != null) {
                    String exitDescription = "\r\n[Process completed";
                    if (status > 0) exitDescription += " (code " + status + ")";
                    else if (status < 0) exitDescription += " (signal " + (-status) + ")";
                    exitDescription += "]";
                    byte[] bytes = exitDescription.getBytes(StandardCharsets.UTF_8);
                    mEmulator.append(bytes, bytes.length);
                    notifyScreenUpdate();
                }
                mClient.onSessionFinished(TerminalSession.this);
            }
        });
    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        HostTransport transport = mTransport;
        if (transport == null || count <= 0) return;
        if (SystemClock.uptimeMillis() < mReplyHoldUntil && mInEmulatorAppend) return;
        transport.sendInput(data, offset, count);
    }

    /** True while the emulator is processing host output: writes then are its replies. */
    private boolean mInEmulatorAppend;

    /**
     * True while a callback runs that the host's output caused (an OSC 52 clipboard write), false
     * for the owner's own actions (copying a selection).
     */
    public boolean isHostOutput() {
        return mInEmulatorAppend;
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return !mFinished;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    class MainThreadHandler extends Handler {

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what != MSG_OUTPUT || mEmulator == null) return;
            byte[] data = (byte[]) msg.obj;
            mInEmulatorAppend = true;
            try {
                mEmulator.append(data, data.length);
            } finally {
                mInEmulatorAppend = false;
            }
            notifyScreenUpdate();
        }

    }

}
