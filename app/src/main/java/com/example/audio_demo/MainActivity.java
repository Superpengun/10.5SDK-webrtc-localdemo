package com.example.audio_demo;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ToggleButton;
import com.sinovoice.sdk.android.AudioPlayer;
import com.sinovoice.sdk.android.AudioRecorder;
import com.sinovoice.sdk.android.HciAudioManager;
import com.sinovoice.sdk.android.IAudioPlayerHandler;
import com.sinovoice.sdk.android.IAudioRecorderHandler;
import com.sinovoice.sdk.audio.HciAudioBuffer;
import com.sinovoice.sdk.audio.HciAudioError;
import com.sinovoice.sdk.audio.HciAudioMetrics;
import com.sinovoice.sdk.audio.HciAudioSink;
import com.sinovoice.sdk.audio.HciAudioSource;
import com.sinovoice.sdk.audio.IAudioCB;
import java.nio.ByteBuffer;

public class MainActivity extends Activity implements IAudioRecorderHandler, IAudioPlayerHandler {
  // 日志窗体最大记录的行数，避免溢出问题
  private static final int MAX_LOG_LINES = 5 * 1024;
  private static final int DELAY = 2000; // 延时，单位: ms

  private HciAudioManager am;
  private AudioRecorder audioRecorder;
  private AudioPlayer audioPlayer;
  private HciAudioBuffer audioBuffer;
  static private final int recorderOptions = AudioRecorder.ENABLE_AEC | AudioRecorder.ENABLE_NS;
  protected boolean recording = false, playing = false;
  private Thread uiThread;
  private TextView tv_logview;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    uiThread = Thread.currentThread();

    am = HciAudioManager
             .builder(this)
             // 音频设备采样率
             .setSampleRate(16000)
             // 是否使用硬件回声消除，硬件回声消除效果不好的话，
             // 设置为 false 将使用 WebRtc 回声消除算法进行代替。
             .setUseHardwareAcousticEchoCanceler(false)
             // 是否使用硬件降噪，硬件降噪效果不好的话，
             // 设置为 false 将使用 WebRtc 降噪算法进行代替。
             .setUseHardwareNoiseSuppressor(false)
             .create();
    audioRecorder = new AudioRecorder(am, "pcm_s16le_16k", DELAY + 200);
    audioPlayer = new AudioPlayer(am, "pcm_s16le_16k", 1000);

    tv_logview = (TextView) findViewById(R.id.tv_logview);

    // 录音按钮
    findViewById(R.id.btn_rec).setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View view) {
        if (!recording) {
          startRecord();
        } else {
          printLog("停止录音");
          audioRecorder.stop(false);
        }
      }
    });
    // 播放按钮
    findViewById(R.id.btn_play).setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View view) {
        if (!playing) {
          startPlay();
        } else {
          printLog("停止播放");
          audioPlayer.stop();
        }
      }
    });
    // 扬声器开关
    final AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    ((ToggleButton) findViewById(R.id.toggle_speaker))
        .setOnCheckedChangeListener(new OnCheckedChangeListener() {
          @Override
          public void onCheckedChanged(CompoundButton btn, boolean isChecked) {
            audioManager.setSpeakerphoneOn(isChecked);
          }
        });
    // 静音开关
    ((ToggleButton) findViewById(R.id.toggle_mute))
        .setOnCheckedChangeListener(new OnCheckedChangeListener() {
          @Override
          public void onCheckedChanged(CompoundButton btn, boolean isChecked) {
            am.setSpeakerMute(isChecked);
          }
        });
  }

  private void startRecord() {
    if (audioBuffer != null) {
      return;
    }
    printLog("开始录音");
    recording = audioRecorder.start(recorderOptions, MainActivity.this);
    if (!recording) {
      return;
    }
    setButtonText(R.id.btn_rec, "停止录音");
    HciAudioMetrics m = audioRecorder.audioSource().defaultMetrics().clone();
    m.setFrameTime(200); // 200ms 的 frame
    final int frameSize = m.frameSize();
    audioBuffer = new HciAudioBuffer(m, DELAY + 200);
    // 从录音机中读取数据写入 audioBuffer 中
    final HciAudioSource recorderSrc = audioRecorder.audioSource();
    final HciAudioSink playerSink = audioPlayer.audioSink();
    final HciAudioSource bufferSrc = audioBuffer;
    final HciAudioSink bufferSink = audioBuffer.audioSink();
    bufferSink.startWrite(m);
    bufferSrc.startRead(m);
    recorderSrc.startRead(m);
    final ByteBuffer audio = ByteBuffer.allocateDirect(m.getDataLength(DELAY));
    // 首次读取 DELAY 长度
    recorderSrc.asyncRead(audio, new IAudioCB() {
      @Override
      public void run(int retval) {
        if (retval < 0) {
          recorderSrc.endRead();
          bufferSink.endWrite(true);
          recorderSrc.endRead();
          return;
        }
        audio.flip();
        bufferSink.write(audio, false); // audioBuffer 有足够的缓冲写入
        audio.limit(frameSize).position(0);
        pump(bufferSrc, recorderSrc, bufferSink, playerSink, audio, frameSize);
      }
    });
  }

  private void startPlay() {
    printLog("开始播放");
    HciAudioSink playerSink = audioPlayer.audioSink();
    HciAudioMetrics m = playerSink.defaultMetrics().clone();
    m.setFrameTime(200);
    playerSink.startWrite(m); // 向 playerSink 中写入播放数据即可
    playing = audioPlayer.start(AudioManager.STREAM_MUSIC, this);
    if (!playing) {
      playerSink.endWrite(true);
      return;
    }
    setButtonText(R.id.btn_play, "停止播放");
  }

  private void pump(final HciAudioSource bufferSrc, final HciAudioSource recorderSrc,
      final HciAudioSink bufferSink, final HciAudioSink playerSink, final ByteBuffer audio,
      final int frameSize) {
    while (audioBuffer.bufferTimeLen() > DELAY - (playing ? 400 : 200)) {
      // 从 HciAudioBuffer 中读取 200ms 写入播放器中，如果未在播放丢弃数据
      bufferSrc.read(audio, false);
      audio.flip();
      if (playing) {
        playerSink.write(audio, false);
        audio.limit(frameSize).position(0);
      }
    }
    // 异步从录音机中读取 200ms
    recorderSrc.asyncRead(audio, new IAudioCB() {
      @Override
      public void run(int retval) {
        if (retval > 0) {
          if (retval < frameSize) { // 停止录音时可能有不完整的 frame，补零
            audio.put(new byte[frameSize - retval]);
          }
          audio.flip();
          // 将读到的录音数据写入 HciAudioBuffer 中并调用 pump 自身
          bufferSink.write(audio, false);
          audio.flip();
          pump(bufferSrc, recorderSrc, bufferSink, playerSink, audio, frameSize);
        } else {
          // 录音机停止后会读取失败，结束 HciAudioBuffer 的写入
          bufferSink.endWrite(retval != HciAudioError.END_NORMAL);
          recorderSrc.endRead();
          bufferSrc.endRead();
          audioBuffer.close();
          audioBuffer = null;
          printLog("");
        }
      }
    });
  }

  @Override
  public void onStart(AudioPlayer player) {
    printLog("播放器已启动");
  }

  @Override
  public void onStartFail(AudioPlayer player, String message) {
    printLog("播放器启动失败: " + message);
  }

  @Override
  public void onStop(AudioPlayer player) {
    printLog("播放器已停止");
    playing = false;
    setButtonText(R.id.btn_play, "开始播放");
  }

  @Override
  public void onAudio(AudioPlayer player, ByteBuffer audio, long timestamp) {}

  @Override
  public void onBufferEmpty(AudioPlayer player) {
    printLog("播放器缓冲区空");
  }

  @Override
  public void onError(AudioPlayer player, String message) {
    printLog("播放器发生错误: " + message);
    player.stop(); // 不调用 stop 仍会继续播放
  }

  @Override
  public void onSinkEnded(AudioPlayer player, boolean cancel) {
    player.stop();
  }

  @Override
  public void onStart(AudioRecorder recorder) {
    printLog("录音机已启动");
  }

  @Override
  public void onStartFail(AudioRecorder recorder, String message) {
    printLog("录音机启动失败: " + message);
  }

  @Override
  public void onStop(AudioRecorder recorder) {
    printLog("录音机已停止");
    recording = false;
    setButtonText(R.id.btn_rec, "开始录音");
  }

  @Override
  public void onAudio(AudioRecorder recorder, ByteBuffer arg1) {}

  @Override
  public void onBufferFull(AudioRecorder recorder) {
    printLog("录音机缓冲满");
  }

  @Override
  public void onError(AudioRecorder recorder, String message) {
    printLog("录音机发生错误: " + message);
    recorder.stop(true); // 不调用 stop 仍会继续录音
  }

  @Override
  public void onSourceEnded(AudioRecorder recorder) {
    recorder.stop(true);
  }

  private void setButtonText(final int id, final String text) {
    final Button btn = (Button) findViewById(id);
    btn.setText(text);
  }

  private void _printLog(String detail) {
    // 日志输出同时记录到日志文件中
    if (tv_logview == null) {
      return;
    }

    // 如日志行数大于上限，则清空日志内容
    if (tv_logview.getLineCount() > MAX_LOG_LINES) {
      tv_logview.setText("");
    }

    tv_logview.append(detail + "\n"); // 追加日志
    tv_logview.post(scrollLog); // 滚动到底部
  }

  private void printLog(final String detail) {
    if (uiThread == Thread.currentThread()) {
      _printLog(detail);
      return;
    }
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        _printLog(detail);
      }
    });
  }

  private final Runnable scrollLog = new Runnable() {
    @Override
    public void run() {
      ((ScrollView) tv_logview.getParent()).fullScroll(ScrollView.FOCUS_DOWN);
    }
  };
}
