/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.basicmediadecoder;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.DataSource;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;

/**
 * This activity uses a {@link android.view.TextureView} to render the frames of
 * a video decoded using {@link android.media.MediaCodec} API.
 */
public class MainActivity extends Activity {

	private static final String TAG = "MainActivity";

	private TextureView mPlaybackView;
	private TextView mAttribView = null;
	private Handler mUIHandler = new Handler(getBackgroundLooper());
	private Runnable mVideoEventRunnable = new Runnable() {
		@Override
		public void run() {
			runDecoder();
		}
	};

	private static Looper getBackgroundLooper() {
		HandlerThread handleThread = new HandlerThread(TAG);
		handleThread.start();
		return handleThread.getLooper();
	}

	private boolean mIsRunning;

	/**
	 * Called when the activity is first created.
	 */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.sample_main);
		mPlaybackView = (TextureView) findViewById(R.id.PlaybackView);
		mAttribView = (TextView) findViewById(R.id.AttribView);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.action_menu, menu);
		return true;
	}

	@Override
	protected void onPause() {
		super.onPause();
		mIsRunning = false;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.menu_play) {
			Log.v(TAG, "mVideoEventRunnable go");
			mAttribView.setVisibility(View.VISIBLE);
			mUIHandler.postDelayed(mVideoEventRunnable, 10);
			item.setEnabled(false);
		}
		return true;
	}

	private static class TsDataSource implements DataSource {
		private RandomAccessFile mFile;

		public TsDataSource(String path) {
			mFile = null;
			try {
				mFile = new RandomAccessFile(path, "r");
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}
		}
		
		@Override
		public int readAt(long offset, byte[] buffer, int size) {
			if (mFile != null) {
				try {
					mFile.seek( offset );
					return mFile.read( buffer );
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			return 0;
		}
		
		@Override
		public long getSize() {
			return -1;
		}

		@Override
		public void close() throws IOException {
		}
	}

	// TODO:
	// video & audio controller
	private static final String kMediaPath = Environment
			.getExternalStorageDirectory().getPath() + "/demo.ts";
	private static boolean kUseStreamingDataSource = false;
	private static boolean kUsePipeDecoding = false;
	private static boolean kForceShow = false;
	private static long kVideoDelayMin = -10000l;
	private static long kVideoDelayMax = 30000l;
	private static long kAudioDelayMin = -10000l;
	private static long kAudioDelayMax = 30000l;
	private TsDataSource mVideoDataSource;
	private TsDataSource mAudioDataSource;
	private MediaExtractor extractorVideo;
	private MediaExtractor extractorAudio;
	private MediaCodec decoderVideo;
	private MediaCodec decoderAudio;
	private Surface surface;
	private AudioTrack audioTracker;
	private int sampleRate;
	private int channelCount;
	private int videoTrackerIdx = -1;
	private int audioTrackerIdx = -1;

	private ByteBuffer[] videoInputBuffers;
	private ByteBuffer[] videoOutputBuffers;
	private ByteBuffer[] audioInputBuffers;
	private ByteBuffer[] audioOutputBuffers;

	private boolean isEOS = false;
	private long startedUs = 0;
	private long audioTimestampUs;
	private long videoTimestampUs;

	private Queue<Integer> vecVideoInIndex = new LinkedList<Integer>();
	private Queue<Integer> vecVideoOutIndex = new LinkedList<Integer>();
	private Queue<BufferInfo> vecVideoInfo = new LinkedList<BufferInfo>();

	private Queue<Integer> vecAudioInIndex = new LinkedList<Integer>();
	private Queue<Integer> vecAudioOutIndex = new LinkedList<Integer>();
	private Queue<BufferInfo> vecAudioInfo = new LinkedList<BufferInfo>();

	private int videoDequeuCounter = 0;
	private int audioDequeuCounter = 0;

	private boolean initAudioDecoder() {
		Log.d(TAG, "initAudioDecoder");
		extractorAudio = new MediaExtractor();
		if (kUseStreamingDataSource) {
			mAudioDataSource = new TsDataSource(kMediaPath);
			if ( !setDataSource(extractorAudio, mAudioDataSource) ) {
				Log.e( TAG, "setDataSource failed" );
				return false;
			}
		} else {
			try {
				extractorAudio.setDataSource(kMediaPath);
			} catch (IOException e1) {
				e1.printStackTrace();
				return false;
			}
		}

		int channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
		for (int i = 0; i < extractorAudio.getTrackCount(); i++) {
			MediaFormat format = extractorAudio.getTrackFormat(i);
			String mime = format.getString(MediaFormat.KEY_MIME);
			extractorAudio.unselectTrack(i);
			if (mime.startsWith("audio/")) {
				decoderAudio = MediaCodec.createDecoderByType(mime);
				decoderAudio.configure(format, null, null, 0);
				audioTrackerIdx = i;
				extractorAudio.selectTrack(i);

				// TODO:
				// init audio
				submittedBytes = 0;
				temporaryBufferSize = 0;
				lastRawPlaybackHeadPosition = 0;
				rawPlaybackHeadWrapCount = 0;
				sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
				channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
				frameSize = 2 * channelCount;
				switch (channelCount) {
				case 1:
					channelConfig = AudioFormat.CHANNEL_OUT_MONO;
					break;
				case 2:
					channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
					break;
				case 6:
					channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
					break;
				}
				bufferSize = AudioTrack.getMinBufferSize(sampleRate,
						channelConfig, AudioFormat.ENCODING_PCM_16BIT);
				Log.v(TAG, "sampleRate: " + sampleRate);
				Log.v(TAG, "bufferSize: " + bufferSize);
				break;
			}
		}
		audioTracker = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
				channelConfig, AudioFormat.ENCODING_PCM_16BIT, bufferSize,
				AudioTrack.MODE_STREAM);
		audioTracker.play();

		Log.v(TAG, "proc: decoder start");
		decoderAudio.start();
		audioInputBuffers = decoderAudio.getInputBuffers();
		audioOutputBuffers = decoderAudio.getOutputBuffers();
		return true;
	}

	private void releaseAudioDecoder() {
		Log.d(TAG, "releaseAudioDecoder");
		decoderAudio.stop();
		decoderAudio.release();
		audioTracker.stop();
		audioTracker.release();
		extractorAudio.release();
	}

	private void doAudioSomeWork(long timeUs) {
		while (!vecAudioOutIndex.isEmpty()) {
			int audioOutIdx = vecAudioOutIndex.peek();
			BufferInfo info = vecAudioInfo.peek();
			if (kForceShow) {
				audioTimestampUs = info.presentationTimeUs;
				writeAudioTrack(audioTracker, audioOutputBuffers[audioOutIdx],
						info.offset, info.size);
				decoderAudio.releaseOutputBuffer(audioOutIdx, false);
			} else {
				long delayUs = (timeUs - info.presentationTimeUs);
				Log.v(TAG, "releaseOutputBuffer audio ready: " + delayUs);
				if (delayUs > kAudioDelayMin) {
					if (delayUs > kAudioDelayMax) {
						Log.v(TAG, "drop audio");
						audioOutIdx = vecAudioOutIndex.poll();
						info = vecAudioInfo.poll();
						decoderAudio.releaseOutputBuffer(audioOutIdx, false);
					} else {
						Log.v(TAG, "play audio: " + info.presentationTimeUs);
						audioTimestampUs = info.presentationTimeUs;
						if (writeAudioTrack(audioTracker,
								audioOutputBuffers[audioOutIdx], info.offset,
								info.size)) {
							audioOutIdx = vecAudioOutIndex.poll();
							info = vecAudioInfo.poll();
							decoderAudio
									.releaseOutputBuffer(audioOutIdx, false);
						}
					}
				} else {
					Log.v(TAG, "too early audio");
					break;
				}
			}
		}
	}

	private void doAudioSomeWork() {
		audioDequeuCounter = 0;
		do {
			int audioInIdx = decoderAudio.dequeueInputBuffer(0);
			if (audioInIdx < 0) {
				break;
			}
			vecAudioInIndex.offer(audioInIdx);
			++audioDequeuCounter;
		} while (true);

		Log.v(TAG, "dequeue input: " + audioDequeuCounter);
		Log.v(TAG, "vecAudioInIndex size: " + vecAudioInIndex.size());

		audioDequeuCounter = 0;
		do {
			BufferInfo info = new BufferInfo();
			int audioOutIdx = decoderAudio.dequeueOutputBuffer(info, 0);
			if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
				Log.e(TAG, "BUFFER_FLAG_END_OF_STREAM");
			}
			if (audioOutIdx < 0) {
				if (MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED == audioOutIdx) {
					audioOutputBuffers = decoderAudio.getOutputBuffers();
					vecAudioOutIndex.clear();
					vecAudioInfo.clear();
				} else if (MediaCodec.INFO_OUTPUT_FORMAT_CHANGED == audioOutIdx) {
					Log.e(TAG, "INFO_OUTPUT_FORMAT_CHANGED");
				}
				break;
			}
			++audioDequeuCounter;
			vecAudioOutIndex.offer(audioOutIdx);
			vecAudioInfo.offer(info);
		} while (true);

		Log.v(TAG, "dequeue output: " + audioDequeuCounter);
		Log.v(TAG, "vecAudioOutIndex size: " + vecAudioOutIndex.size());

		do {
			int trackIdx = extractorAudio.getSampleTrackIndex();
			Log.v(TAG, "audio getSampleTrackIndex: " + trackIdx);
			if (trackIdx < 0) {
				isEOS = true;
				break;
			}
			long presentationTimeUs = extractorAudio.getSampleTime();
			if (audioTrackerIdx == trackIdx) {
				if (vecAudioInIndex.isEmpty()) {
					break;
				}
				int audioInIdx = vecAudioInIndex.poll();
				Log.v(TAG, "readSampleData audio ++");
				int size = extractorAudio.readSampleData(
						audioInputBuffers[audioInIdx], 0);
				Log.v(TAG, "readSampleData audio --");
				extractorAudio.advance();
				decoderAudio.queueInputBuffer(audioInIdx, 0, size,
						presentationTimeUs, 0);
			}
		} while (true);
	}

	private boolean initVideoDecoder() {
		Log.d(TAG, "initVideoDecoder");
		extractorVideo = new MediaExtractor();

		if (kUseStreamingDataSource) {
			mVideoDataSource = new TsDataSource(kMediaPath);
			if ( !setDataSource(extractorVideo, mVideoDataSource) ) {
				Log.e( TAG, "setDataSource failed" );
				return false;
			}
		} else {
			try {
				extractorVideo.setDataSource(kMediaPath);
			} catch (IOException e1) {
				e1.printStackTrace();
				return false;
			}
		}

		surface = new Surface(mPlaybackView.getSurfaceTexture());
		for (int i = 0; i < extractorVideo.getTrackCount(); i++) {
			MediaFormat format = extractorVideo.getTrackFormat(i);
			String mime = format.getString(MediaFormat.KEY_MIME);
			extractorVideo.unselectTrack(i);
			if (mime.startsWith("video/")) {
				decoderVideo = MediaCodec.createDecoderByType(mime);
				decoderVideo.configure(format, surface, null, 0);
				videoTrackerIdx = i;
				extractorVideo.selectTrack(i);
				break;
			}
		}

		Log.v(TAG, "proc: decoder start");
		decoderVideo.start();
		videoInputBuffers = decoderVideo.getInputBuffers();
		videoOutputBuffers = decoderVideo.getOutputBuffers();
		return true;
	}

	private void releaseVideoDecoder() {
		Log.d(TAG, "releaseVideoDecoder");
		decoderVideo.stop();
		decoderVideo.release();
		extractorVideo.release();
	}

	private void doVideoSomeWork() {
		videoDequeuCounter = 0;
		do {
			int videoInIdx = decoderVideo.dequeueInputBuffer(0);
			if (videoInIdx < 0) {
				break;
			}
			vecVideoInIndex.offer(videoInIdx);
			++videoDequeuCounter;
		} while (true);

		Log.v(TAG, "dequeue input: " + videoDequeuCounter);
		Log.v(TAG, "vecVideoInIndex size: " + vecVideoInIndex.size());
		videoDequeuCounter = 0;

		do {
			BufferInfo info = new BufferInfo();
			int videoOutIdx = decoderVideo.dequeueOutputBuffer(info, 0);
			if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
				Log.e(TAG, "BUFFER_FLAG_END_OF_STREAM");
			}
			if (videoOutIdx < 0) {
				if (MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED == videoOutIdx) {
					videoOutputBuffers = decoderVideo.getOutputBuffers();
					vecVideoOutIndex.clear();
					vecVideoInfo.clear();
				} else if (MediaCodec.INFO_OUTPUT_FORMAT_CHANGED == videoOutIdx) {
					Log.e(TAG, "INFO_OUTPUT_FORMAT_CHANGED");
				}
				break;
			}
			++videoDequeuCounter;
			vecVideoOutIndex.offer(videoOutIdx);
			vecVideoInfo.offer(info);
		} while (true);

		Log.v(TAG, "dequeue output: " + videoDequeuCounter);
		Log.v(TAG, "vecVideoOutIndex size: " + vecVideoOutIndex.size());

		do {
			int trackIdx = extractorVideo.getSampleTrackIndex();
			Log.v(TAG, "video getSampleTrackIndex: " + trackIdx);
			if (trackIdx < 0) {
				isEOS = true;
				break;
			}
			long presentationTimeUs = extractorVideo.getSampleTime();
			if (videoTrackerIdx == trackIdx) {
				if (vecVideoInIndex.isEmpty()) {
					break;
				}
				int videoInIdx = vecVideoInIndex.poll();
				Log.v(TAG, "readSampleData video ++");
				int size = extractorVideo.readSampleData(
						videoInputBuffers[videoInIdx], 0);
				Log.v(TAG, "readSampleData video --");
				extractorVideo.advance();
				decoderVideo.queueInputBuffer(videoInIdx, 0, size,
						presentationTimeUs, 0);
			}
		} while (true);
	}

	private void doVideoSomeWork(long timeUs) {
		while (!vecVideoOutIndex.isEmpty()) {
			int videoOutIdx = vecVideoOutIndex.peek();
			BufferInfo info = vecVideoInfo.peek();
			if (kForceShow) {
				videoTimestampUs = info.presentationTimeUs;
				decoderVideo.releaseOutputBuffer(videoOutIdx, true);
			} else {
				long delayUs = (timeUs - info.presentationTimeUs);
				Log.v(TAG, "releaseOutputBuffer video ready: " + delayUs);
				if (delayUs > kVideoDelayMin) {
					videoOutIdx = vecVideoOutIndex.poll();
					info = vecVideoInfo.poll();
					if (delayUs > kVideoDelayMax) {
						Log.v(TAG, "drop video");
						decoderVideo.releaseOutputBuffer(videoOutIdx, false);
					} else {
						Log.v(TAG, "play video: " + info.presentationTimeUs);
						videoTimestampUs = info.presentationTimeUs;
						decoderVideo.releaseOutputBuffer(videoOutIdx, true);
					}
				} else {
					Log.v(TAG, "too early video");
					break;
				}
			}
		}
	}

	private void doRender() {
		Log.d(TAG, "doRender");
		long nowUs = System.nanoTime() / 1000;
		if (0 == startedUs) {
			startedUs = nowUs + 1000000l;
		}
		long currentTimestampUs = nowUs - startedUs;
		Log.v(TAG, "play both ===========> " + (nowUs - startedUs));
		doAudioSomeWork(currentTimestampUs);
		doVideoSomeWork(currentTimestampUs);
	}

	private static Looper getBackgroundLooper(String name) {
		HandlerThread thread = new HandlerThread(name);
		thread.start();
		return thread.getLooper();
	}

	private class AudioHandler extends Handler {
		public AudioHandler() {
			super(getBackgroundLooper("audio"));
		}

		@Override
		public void handleMessage(Message msg) {
			doAudioSomeWork();
			try {
				mBarrier.await();
			} catch (InterruptedException e) {
				e.printStackTrace();
			} catch (BrokenBarrierException e) {
				e.printStackTrace();
			}
		}
	};

	private class VideoHandler extends Handler {
		public VideoHandler() {
			super(getBackgroundLooper("video"));
		}

		@Override
		public void handleMessage(Message msg) {
			doVideoSomeWork();
			try {
				mBarrier.await();
			} catch (InterruptedException e) {
				e.printStackTrace();
			} catch (BrokenBarrierException e) {
				e.printStackTrace();
			}
		}
	};

	private CyclicBarrier mBarrier = new CyclicBarrier(2, new Runnable() {
		@Override
		public void run() {
			doRender();
		}
	});
	private AudioHandler mAudioHandler = new AudioHandler();
	private VideoHandler mVideoHandler = new VideoHandler();

	// TODO:
	// TODO:
	// TODO:
	// important
	// main function
	public void runDecoder() {
		Log.v(TAG, "runDecoder");		
		if ( !initAudioDecoder() || !initVideoDecoder() ) {
			Log.e( TAG, "init decoder failed" );
			return ;
		}

		mIsRunning = true;
		isEOS = false;

		while (mIsRunning) {
			if (!isEOS) {
				if ( kUsePipeDecoding ) {
					mAudioHandler.sendEmptyMessage(0);
					mVideoHandler.sendEmptyMessage(0);
				}
				else {
					doAudioSomeWork();
					doVideoSomeWork();
					doRender();
				}
			}
		}

		releaseAudioDecoder();
		releaseVideoDecoder();
	}

	private long submittedBytes;
	private int temporaryBufferSize;
	private long lastRawPlaybackHeadPosition;
	private long rawPlaybackHeadWrapCount;
	private long frameSize;
	private int bufferSize;
	private long getPlaybackHeadPosition(AudioTrack audioTracker) {
		long rawPlaybackHeadPosition = 0xFFFFFFFFL & audioTracker
				.getPlaybackHeadPosition();
		if (lastRawPlaybackHeadPosition > rawPlaybackHeadPosition) {
			// The value must have wrapped around.
			rawPlaybackHeadWrapCount++;
		}
		lastRawPlaybackHeadPosition = rawPlaybackHeadPosition;
		return rawPlaybackHeadPosition + (rawPlaybackHeadWrapCount << 32);
	}

	private byte[] temporaryBuffer = null;
	private int temporaryBufferOffset = 0;

	private boolean writeAudioTrack(AudioTrack audioTracker, ByteBuffer buf,
			int offset, int size) {

		if (temporaryBufferSize == 0) {
			// Copy {@code buffer} into {@code temporaryBuffer}.
			// TODO: Bypass this copy step on versions of Android where
			// [redacted] is implemented.
			if (temporaryBuffer == null || temporaryBuffer.length < size) {
				temporaryBuffer = new byte[size];
			}
			buf.position(offset);
			buf.get(temporaryBuffer, 0, size);
			temporaryBufferOffset = 0;
			temporaryBufferSize = size;
		}

		int bytesPending = (int) (submittedBytes - getPlaybackHeadPosition(audioTracker)
				* frameSize);
		Log.v(TAG, "bytesPending: " + bytesPending);
		int bytesToWrite = bufferSize - bytesPending;
		Log.v(TAG, "bytesToWrite: " + bytesToWrite);

		if (bytesToWrite > 0) {
			bytesToWrite = Math.min(temporaryBufferSize, bytesToWrite);
			audioTracker.write(temporaryBuffer, temporaryBufferOffset,
					bytesToWrite);
			temporaryBufferOffset += bytesToWrite;
			temporaryBufferSize -= bytesToWrite;
			submittedBytes += bytesToWrite;
			Log.v(TAG, "temporaryBufferSize: " + temporaryBufferSize);
			if (0 == temporaryBufferSize) {
				return true;
			}
		}
		return false;
	}

	private static final long MICROS_PER_SECOND = 1000000l;

	private long framesToDurationUs(long frameCount) {
		return (frameCount * MICROS_PER_SECOND) / sampleRate;
	}

	private long durationUsToFrames(long durationUs) {
		return (durationUs * sampleRate) / MICROS_PER_SECOND;
	}

	// TODO:
	// private method
	private boolean setDataSource(Object mediaExtractor, DataSource dataSourceInstantance) {
		final String kDataSourceClass = "android.media.DataSource";
		final String kMediaExtractorClass = "android.media.MediaExtractor";
		final String kSetDataSourceMethod = "setDataSource";

		Class clsDataSource = null;
		try {
			clsDataSource = Class.forName(kDataSourceClass);
		} catch (ClassNotFoundException e) {
			Log.e(TAG, "ClassNotFoundException: " + kDataSourceClass);
			return false;
		}

		Class clsMediaExtractor = null;
		try {
			clsMediaExtractor = Class.forName(kMediaExtractorClass);
		} catch (ClassNotFoundException e) {
			Log.e(TAG, "ClassNotFoundException: " + kMediaExtractorClass);
			return false;
		}
		Method method = null;
		try {
			method = clsMediaExtractor.getMethod(kSetDataSourceMethod,
					new Class[] { clsDataSource });
		} catch (NoSuchMethodException e) {
			Log.e(TAG, "NoSuchMethodException: " + kSetDataSourceMethod);
			return false;
		}
		try {
			method.invoke(mediaExtractor, dataSourceInstantance);
		} catch (IllegalAccessException e) {
			Log.e(TAG, "invoke IllegalAccessException: " + kSetDataSourceMethod);
			return false;
		} catch (IllegalArgumentException e) {
			Log.e(TAG, "invoke IllegalArgumentException: "
					+ kSetDataSourceMethod);
			return false;
		} catch (InvocationTargetException e) {
			Log.e(TAG, "invoke InvocationTargetException: "
					+ kSetDataSourceMethod);
			return false;
		}
		return true;
	}
}
