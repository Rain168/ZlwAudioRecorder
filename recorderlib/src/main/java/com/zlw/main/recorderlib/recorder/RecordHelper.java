package com.zlw.main.recorderlib.recorder;

import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.zlw.main.recorderlib.recorder.listener.RecordDataListener;
import com.zlw.main.recorderlib.recorder.listener.RecordSoundSizeListener;
import com.zlw.main.recorderlib.recorder.listener.RecordStateListener;
import com.zlw.main.recorderlib.recorder.mp3.Mp3EncodeThread;
import com.zlw.main.recorderlib.recorder.wav.WavUtils;
import com.zlw.main.recorderlib.utils.ByteUtils;
import com.zlw.main.recorderlib.utils.FileUtils;
import com.zlw.main.recorderlib.utils.Logger;
import com.zlw.main.recorderlib.utils.RecordUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @author zhaolewei on 2018/7/10.
 */
public class RecordHelper {
    private static final String TAG = RecordHelper.class.getSimpleName();
    private volatile static RecordHelper instance;
    private volatile RecordState state = RecordState.IDLE;
    private static final int RECORD_AUDIO_BUFFER_TIMES = 1;

    private RecordStateListener recordStateListener;
    private RecordDataListener recordDataListener;
    private RecordSoundSizeListener recordSoundSizeListener;
    private RecordConfig currentConfig;
    private AudioRecordThread audioRecordThread;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private File resultFile = null;
    private File tmpFile = null;
    private List<File> files = new ArrayList<>();
    private Mp3EncodeThread mp3EncodeThread;

    private RecordHelper() {
    }

    static RecordHelper getInstance() {
        if (instance == null) {
            synchronized (RecordHelper.class) {
                if (instance == null) {
                    instance = new RecordHelper();
                }
            }
        }
        return instance;
    }

    RecordState getState() {
        return state;
    }

    void setRecordStateListener(RecordStateListener recordStateListener) {
        this.recordStateListener = recordStateListener;
    }

    void setRecordDataListener(RecordDataListener recordDataListener) {
        this.recordDataListener = recordDataListener;
    }

    void setRecordSoundSizeListener(RecordSoundSizeListener recordSoundSizeListener) {
        this.recordSoundSizeListener = recordSoundSizeListener;
    }

    public void start(String filePath, RecordConfig config) {
        this.currentConfig = config;
        if (state != RecordState.IDLE) {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }
        resultFile = new File(filePath);
        String tempFilePath = getTempFilePath();

        Logger.d(TAG, "----------------开始录制 %s------------------------", currentConfig.getFormat().name());
        Logger.d(TAG, "参数： %s", currentConfig.toString());
        Logger.i(TAG, "pcm缓存 tmpFile: %s", tempFilePath);
        Logger.i(TAG, "录音文件 resultFile: %s", filePath);


        tmpFile = new File(tempFilePath);
        audioRecordThread = new AudioRecordThread();
        audioRecordThread.start();
    }

    public void stop() {
        if (state == RecordState.IDLE) {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }

        if (state == RecordState.PAUSE) {
            makeFile();
            state = RecordState.IDLE;
        } else {
            state = RecordState.STOP;
        }
        notifyState();
    }

    void pause() {
        if (state != RecordState.RECORDING) {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }
        state = RecordState.PAUSE;
        notifyState();
    }

    void resume() {
        if (state != RecordState.PAUSE) {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }
        String tempFilePath = getTempFilePath();
        Logger.i(TAG, "tmpPCM File: %s", tempFilePath);
        tmpFile = new File(tempFilePath);
        audioRecordThread = new AudioRecordThread();
        audioRecordThread.start();
    }

    private void notifyState() {
        if (recordStateListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                recordStateListener.onStateChange(state);
            }
        });
    }

    private void notifyFinish() {
        Logger.d(TAG, "录音结束 file: %s", resultFile.getAbsolutePath());
        if (recordStateListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                recordStateListener.onStateChange(RecordState.FINISH);
            }
        });
    }

    private void notifyError(final String error) {
        if (recordStateListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                recordStateListener.onError(error);
            }
        });
    }

    private void notifyData(final byte[] data) {
        if (recordDataListener == null && recordSoundSizeListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (recordDataListener != null) {
                    recordDataListener.onData(data);
                }
                if (recordSoundSizeListener != null) {
                    recordSoundSizeListener.onSoundSize((int) RecordUtils.getMaxDecibels(data));
                }
            }
        });
    }

    private void initMp3EncoderThread(int bufferSize) {
        try {
            mp3EncodeThread = new Mp3EncodeThread(resultFile, bufferSize);
            mp3EncodeThread.start();
        } catch (Exception e) {
            Logger.e(e, TAG, e.getMessage());
        }
    }

    private class AudioRecordThread extends Thread {
        private AudioRecord audioRecord;
        private int bufferSize;

        AudioRecordThread() {
            bufferSize = AudioRecord.getMinBufferSize(currentConfig.getSampleRate(),
                    currentConfig.getChannelConfig(), currentConfig.getEncodingConfig()) * RECORD_AUDIO_BUFFER_TIMES;
            Logger.d(TAG, "record buffer size = %s", bufferSize);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, currentConfig.getSampleRate(),
                    currentConfig.getChannelConfig(), currentConfig.getEncodingConfig(), bufferSize);
            if (currentConfig.getFormat() == RecordConfig.RecordFormat.MP3 && mp3EncodeThread == null) {
                initMp3EncoderThread(bufferSize);
            }
        }

        @Override
        public void run() {
            super.run();

            switch (currentConfig.getFormat()) {
                case MP3:
                    startMp3Recorder();
                    break;
                default:
                    startPcmRecorder();
                    break;
            }
        }

        private void startPcmRecorder() {
            state = RecordState.RECORDING;
            notifyState();
            Logger.d(TAG, "开始录制 Pcm");
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(tmpFile);
                audioRecord.startRecording();
                byte[] byteBuffer = new byte[bufferSize];

                while (state == RecordState.RECORDING) {
                    int end = audioRecord.read(byteBuffer, 0, byteBuffer.length);
                    notifyData(byteBuffer);
                    fos.write(byteBuffer, 0, end);
                    fos.flush();
                }
                audioRecord.stop();
                files.add(tmpFile);
                if (state == RecordState.STOP) {
                    makeFile();
                } else {
                    Logger.i(TAG, "暂停！");
                }
            } catch (Exception e) {
                Logger.e(e, TAG, e.getMessage());
                notifyError("录音失败");
            } finally {
                try {
                    if (fos != null) {
                        fos.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (state != RecordState.PAUSE) {
                state = RecordState.IDLE;
                notifyState();
                Logger.d(TAG, "录音结束");
            }
        }

        private void startMp3Recorder() {
            state = RecordState.RECORDING;
            notifyState();

            try {
                audioRecord.startRecording();
                short[] byteBuffer = new short[bufferSize];

                while (state == RecordState.RECORDING) {
                    int end = audioRecord.read(byteBuffer, 0, byteBuffer.length);
                    if (mp3EncodeThread != null) {
                        mp3EncodeThread.addChangeBuffer(new Mp3EncodeThread.ChangeBuffer(byteBuffer, end));
                    }
                    notifyData(ByteUtils.toBytes(byteBuffer));
                }
                audioRecord.stop();
            } catch (Exception e) {
                Logger.e(e, TAG, e.getMessage());
                notifyError("录音失败");
            }
            if (state != RecordState.PAUSE) {
                state = RecordState.IDLE;
                notifyState();
                if (mp3EncodeThread != null) {
                    mp3EncodeThread.stopSafe(new Mp3EncodeThread.EncordFinishListener() {
                        @Override
                        public void onFinish() {
                            notifyFinish();
                        }
                    });
                } else {
                    notifyFinish();
                }
            } else {
                Logger.d(TAG, "暂停");
            }
        }
    }

    private void makeFile() {
        switch (currentConfig.getFormat()) {
            case MP3:
                //nothing
                break;
            case WAV:
                mergePcmFile();
                makeWav();
                break;
            case PCM:
                mergePcmFile();
                break;
            default:
                break;
        }
        Logger.i(TAG, "录音完成！ path: %s ； 大小：%s", resultFile.getAbsoluteFile(), resultFile.length());
    }

    /**
     * 添加Wav头文件
     */
    private void makeWav() {
        if (!FileUtils.isFile(resultFile) || resultFile.length() == 0) {
            return;
        }
        byte[] header = WavUtils.generateWavFileHeader((int) resultFile.length(), currentConfig.getSampleRate(), currentConfig.getChannelCount(), currentConfig.getEncoding());
        WavUtils.writeHeader(resultFile, header);
    }

    /**
     * 合并文件
     */
    private void mergePcmFile() {
        boolean mergeSuccess = mergePcmFiles(resultFile, files);
        if (!mergeSuccess) {
            notifyError("合并失败");
        }
    }

    /**
     * 合并Pcm文件
     *
     * @param recordFile 输出文件
     * @param files      多个文件源
     * @return 是否成功
     */
    private boolean mergePcmFiles(File recordFile, List<File> files) {
        if (recordFile == null || files == null || files.size() <= 0) {
            return false;
        }

        FileOutputStream fos = null;
        BufferedOutputStream outputStream = null;
        byte[] buffer = new byte[1024];
        try {
            fos = new FileOutputStream(recordFile);
            outputStream = new BufferedOutputStream(fos);

            for (int i = 0; i < files.size(); i++) {
                BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(files.get(i)));
                int readCount;
                while ((readCount = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, readCount);
                }
                inputStream.close();
            }
        } catch (Exception e) {
            Logger.e(e, TAG, e.getMessage());
            return false;
        } finally {
            try {
                if (fos != null) {
                    fos.close();
                }
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        for (int i = 0; i < files.size(); i++) {
            files.get(i).delete();
        }
        files.clear();
        return true;
    }

    /**
     * 根据当前的时间生成相应的文件名
     * 实例 record_20160101_13_15_12
     */
    private String getTempFilePath() {
        String fileDir = String.format(Locale.getDefault(), "%s/Record/", Environment.getExternalStorageDirectory().getAbsolutePath());
        if (!FileUtils.createOrExistsDir(fileDir)) {
            Logger.e(TAG, "文件夹创建失败：%s", fileDir);
        }
        String fileName = String.format(Locale.getDefault(), "record_tmp_%s", FileUtils.getNowString(new SimpleDateFormat("yyyyMMdd_HH_mm_ss", Locale.SIMPLIFIED_CHINESE)));
        return String.format(Locale.getDefault(), "%s%s.pcm", fileDir, fileName);
    }

    /**
     * 表示当前状态
     */
    public enum RecordState {
        /**
         * 空闲状态
         */
        IDLE,
        /**
         * 录音中
         */
        RECORDING,
        /**
         * 暂停中
         */
        PAUSE,
        /**
         * 正在停止
         */
        STOP,
        /**
         * 录音流程结束（转换结束）
         */
        FINISH
    }

}
